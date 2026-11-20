package com.avod.fireos.avod.auth

import android.util.Log
import com.avod.fireos.avod.model.TokenData
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * Code-Based Linking (CBL) login — Amazon's "go to amazon.<tld>/code and enter this code" flow.
 *
 * This is Amazon's MAP device-linking flow (NOT the Login-with-Amazon developer CBL at
 * /auth/O2/create/codepair, which needs a registered amzn1.application-oa2-client id):
 *
 *   1. POST /auth/create/codepair  -> public_code, private_code, expires_in,
 *                                     polling_interval_in_seconds
 *   2. User enters public_code at amazon.<tld>/code and signs in there (Amazon handles the
 *      password, 2SV, passkeys and CAPTCHA on its own pages).
 *   3. Poll POST /auth/register with auth_data = { code_pair, use_global_authentication } until
 *      Amazon links the device. HTTP 401 means "not confirmed yet".
 *
 * The same flow is used by mkb79/Audible (#906), audible-rs and aioamazondevices. Note that
 * code_data.domain must be "Device" — "DeviceLegacy" is rejected with HTTP 400 InvalidValue — so
 * registration_data.domain is also "Device" here rather than the password flow's "DeviceLegacy".
 *
 * UNVERIFIED FOR THIS DEVICE TYPE: the flow is confirmed working for Audible's device type, but
 * whether /auth/create/codepair accepts this device type has not been tested. If codepair returns 400, that is the first thing to suspect.
 */
class CblLoginFlow {

    companion object {
        private const val TAG = "CblLoginFlow"
        private const val DOMAIN = "amazon.com"

        /**
         * Device type used for the code pair and registration.
         *
         * AKPGW064GI9HE = Fire TV Stick 4K 1st Gen (AFTMM/mantis) — a real Fire TV hardware type.
         * Chosen because /auth/create/codepair is known to accept Fire and iPhone device types,
         * while arbitrary types may be rejected with HTTP 400.
         *
         * Fallback to try if codepair rejects this one:
         *   A43PXU4ZN2AL1 = "Prime Video for Android" (com.amazon.avod.thirdpartyclient) — the
         *   type the password flow registers as (AmazonAuthService.DEVICE_TYPE_ID).
         */
        val CBL_DEVICE_TYPE = AmazonAuthService.DEVICE_TYPE_ID
    }

    data class CodePair(
        val publicCode: String,
        val privateCode: String,
        val verificationUri: String,
        val intervalSeconds: Int,
        val expiresInSeconds: Int,
    )

    sealed class PollResult {
        data class Success(val tokenData: TokenData) : PollResult()
        object Pending : PollResult()
        data class Error(val message: String) : PollResult()
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .followRedirects(true).followSslRedirects(false)
        .build()

    private var deviceId: String = ""

    /** Step 1: ask Amazon for a code pair the user enters at amazon.<tld>/code. */
    fun createCodePair(): CodePair {
        deviceId = UUID.randomUUID().toString().replace("-", "").lowercase()

        val payload = mapOf(
            "code_data" to mapOf(
                "device_serial" to deviceId,
                "device_type"   to CBL_DEVICE_TYPE,
                "domain"        to "Device",          // "DeviceLegacy" is rejected here
            ),
            "code_properties" to mapOf(
                "code_duration" to "600",
                "code_length"   to "5",
            ),
        )

        val resp = client.newCall(
            Request.Builder()
                .url("https://api.$DOMAIN/auth/create/codepair")
                .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
                .header("User-Agent", AmazonAuthService.USER_AGENT)
                .header("Content-Type", "application/json")
                .header("Accept-Charset", "utf-8")
                .header("Accept-Language", "en-US")
                .header("x-amzn-identity-auth-domain", "api.$DOMAIN")
                .header("x-amzn-requestid", UUID.randomUUID().toString().replace("-", ""))
                .build()
        ).execute()
        val body = resp.body?.string() ?: throw RuntimeException("Empty codepair response")
        Log.w(TAG, "Codepair: HTTP ${resp.code}, body=${body.length}")
        if (!resp.isSuccessful) {
            throw RuntimeException(
                "Codepair failed: HTTP ${resp.code} — ${errorOf(body) ?: "unknown"}" +
                    if (resp.code == 400) " (device_type may not be accepted by this endpoint)" else ""
            )
        }

        val json = gson.fromJson(body, JsonObject::class.java)
        val publicCode = json.get("public_code")?.asString ?: throw RuntimeException("No public_code")
        val privateCode = json.get("private_code")?.asString ?: throw RuntimeException("No private_code")
        val expiresIn = json.get("expires_in")?.asString?.toIntOrNull()
            ?: json.get("expires_in")?.asInt ?: 600
        val interval = json.get("polling_interval_in_seconds")?.asString?.toIntOrNull()
            ?: json.get("polling_interval_in_seconds")?.asInt ?: 5
        Log.w(TAG, "Codepair ok: publicCode=$publicCode interval=$interval expiresIn=$expiresIn")
        return CodePair(
            publicCode = publicCode,
            privateCode = privateCode,
            verificationUri = "https://$DOMAIN/code",
            intervalSeconds = interval,
            expiresInSeconds = expiresIn,
        )
    }

    /**
     * Step 3: poll once. Amazon returns HTTP 401 until the user confirms the code at
     * amazon.<tld>/code; once confirmed the usual registration response comes back with tokens.
     */
    fun pollOnce(pair: CodePair): PollResult {
        val regData = mapOf(
            "domain"        to "Device",
            "device_type"   to CBL_DEVICE_TYPE,
            "device_serial" to deviceId,
            "app_name"      to AmazonAuthService.APP_NAME,
            "app_version"   to AmazonAuthService.APP_VERSION,
            "device_model"  to AmazonAuthService.DEVICE_MODEL,
            "os_version"    to AmazonAuthService.OS_VERSION,
        )
        val authData = mapOf(
            "code_pair" to mapOf(
                "public_code"  to pair.publicCode,
                "private_code" to pair.privateCode,
            ),
            "use_global_authentication" to "true",
        )
        val payload = mapOf(
            "auth_data"            to authData,
            "registration_data"    to regData,
            "requested_token_type" to listOf("bearer", "website_cookies"),
            "requested_extensions" to listOf("device_info", "customer_info"),
            "cookies"              to mapOf("domain" to ".$DOMAIN", "website_cookies" to emptyList<String>()),
        )

        val resp = client.newCall(
            Request.Builder()
                .url("https://api.$DOMAIN/auth/register")
                .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
                .header("User-Agent", AmazonAuthService.USER_AGENT)
                .header("Accept-Charset", "utf-8")
                .header("X-Requested-With", AmazonAuthService.APP_NAME)
                .header("Accept-Language", "en-US")
                .header("Content-Type", "application/json")
                .header("x-amzn-identity-auth-domain", "api.$DOMAIN")
                .header("x-amzn-requestid", UUID.randomUUID().toString().replace("-", ""))
                .build()
        ).execute()
        val body = resp.body?.string() ?: ""
        Log.w(TAG, "Poll /auth/register: HTTP ${resp.code}, body=${body.length}")

        // 401 = user hasn't confirmed the code yet.
        if (resp.code == 401) return PollResult.Pending
        if (!resp.isSuccessful) return PollResult.Error(errorOf(body) ?: "HTTP ${resp.code}")

        val json = gson.fromJson(body, JsonObject::class.java)
        val respObj = json.getAsJsonObject("response")
            ?: return PollResult.Error("Missing 'response' in registration")
        if (respObj.has("error")) {
            return PollResult.Error(
                respObj.getAsJsonObject("error").get("message")?.asString ?: "Registration error"
            )
        }
        // A 200 without a success block is a rejection, not a pending state.
        val bearer = respObj.getAsJsonObject("success")
            ?.getAsJsonObject("tokens")?.getAsJsonObject("bearer")
            ?: return PollResult.Error("Sign-in was not approved")

        val expiresIn = bearer.get("expires_in")?.asLong ?: 3600L
        return PollResult.Success(
            TokenData(
                accessToken  = bearer.get("access_token")?.asString
                    ?: return PollResult.Error("No access_token"),
                refreshToken = bearer.get("refresh_token")?.asString
                    ?: return PollResult.Error("No refresh_token"),
                deviceId     = deviceId,
                expiresIn    = expiresIn,
                expiresAt    = System.currentTimeMillis() / 1000 + expiresIn,
            )
        )
    }

    private fun errorOf(body: String): String? = try {
        val json = gson.fromJson(body, JsonObject::class.java)
        json.get("error")?.let { e ->
            if (e.isJsonObject) e.asJsonObject.get("message")?.asString
                ?: e.asJsonObject.get("code")?.asString
            else e.asString
        }
            ?: json.get("error_description")?.asString
            ?: json.getAsJsonObject("response")?.getAsJsonObject("error")?.get("message")?.asString
    } catch (_: Exception) { null }
}
