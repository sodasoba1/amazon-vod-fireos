package com.avod.fireos.avod.auth

import android.util.Log
import com.avod.fireos.avod.model.TokenData
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * Code-Based Linking (CBL) login — Amazon's "go to amazon.com/code and enter this code" / QR flow.
 *
 * Avoids the username/password + email/approval MFA path entirely (and its throttling): the user
 * approves on their phone, and we poll for the result. On success we run the SAME device
 * registration (`/auth/register`) the password flow uses, so the resulting token is the same kind
 * that playback already accepts.
 *
 * EXPERIMENTAL — the exact codepair scope and whether the registered token is accepted by
 * GetPlaybackResources need a real-device test. All steps log verbosely (never token values) so the
 * flow can be tuned from logcat.
 */
class CblLoginFlow {

    companion object {
        private const val TAG = "CblLoginFlow"
        private const val CBL_DOMAIN = "amazon.com"
        private const val APP_KEY = "A1MPSLFC7L5AFK"   // same client key the password flow uses
    }

    data class CodePair(
        val userCode: String,
        val deviceCode: String,
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
    private var clientId: String = ""

    /** Step 1: ask Amazon for a code pair the user enters at amazon.com/code. */
    fun createCodePair(): CodePair {
        deviceId = UUID.randomUUID().toString().replace("-", "").lowercase()
        clientId = hexEncode("$deviceId#$APP_KEY")

        val form = FormBody.Builder()
            .add("response_type", "device_code")
            .add("client_id", clientId)
            .add("scope", "device_auth_access")
            .build()

        val resp = client.newCall(
            Request.Builder()
                .url("https://api.$CBL_DOMAIN/auth/O2/create/codepair")
                .post(form)
                .header("User-Agent", AmazonAuthService.USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept-Language", "en-US")
                .build()
        ).execute()
        val body = resp.body?.string() ?: throw RuntimeException("Empty codepair response")
        Log.w(TAG, "Codepair: HTTP ${resp.code}, body=${body.length}")
        if (!resp.isSuccessful) throw RuntimeException("Codepair failed: HTTP ${resp.code} — ${errorOf(body)}")

        val json = gson.fromJson(body, JsonObject::class.java)
        val userCode = json.get("user_code")?.asString ?: throw RuntimeException("No user_code")
        val deviceCode = json.get("device_code")?.asString ?: throw RuntimeException("No device_code")
        val uri = json.get("verification_uri")?.asString ?: "https://$CBL_DOMAIN/code"
        val interval = json.get("interval")?.asInt ?: 5
        val expiresIn = json.get("expires_in")?.asInt ?: 1800
        Log.w(TAG, "Codepair ok: userCode=$userCode uri=$uri interval=$interval expiresIn=$expiresIn")
        return CodePair(userCode, deviceCode, uri, interval, expiresIn)
    }

    /**
     * Step 2: poll once for approval. Returns [PollResult.Pending] while the user hasn't approved,
     * [PollResult.Success] with a registered token once they have, or [PollResult.Error] on a hard
     * failure (expired/denied).
     */
    fun pollOnce(deviceCode: String): PollResult {
        val form = FormBody.Builder()
            .add("grant_type", "device_code")
            .add("device_code", deviceCode)
            .build()
        val resp = client.newCall(
            Request.Builder()
                .url("https://api.$CBL_DOMAIN/auth/O2/token")
                .post(form)
                .header("User-Agent", AmazonAuthService.USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()
        ).execute()
        val body = resp.body?.string() ?: ""
        Log.w(TAG, "Poll: HTTP ${resp.code}, body=${body.length}")

        if (!resp.isSuccessful) {
            val err = errorOf(body)
            return when (err) {
                "authorization_pending", "slow_down" -> PollResult.Pending
                else -> PollResult.Error(err ?: "HTTP ${resp.code}")
            }
        }
        val json = gson.fromJson(body, JsonObject::class.java)
        val accessToken = json.get("access_token")?.asString
            ?: return PollResult.Error("No access_token in token response")
        // The device is now linked; register it the same way the password flow does so the token
        // carries the Prime Video device registration playback expects.
        return try {
            PollResult.Success(registerWithAccessToken(accessToken))
        } catch (e: Exception) {
            PollResult.Error("Registration after approval failed: ${e.message}")
        }
    }

    /** Step 3: exchange the CBL access token for a device-registered token via /auth/register. */
    private fun registerWithAccessToken(accessToken: String): TokenData {
        val regData = mapOf(
            "domain"        to "DeviceLegacy",
            "device_type"   to AmazonAuthService.DEVICE_TYPE_ID,
            "device_serial" to deviceId,
            "app_name"      to AmazonAuthService.APP_NAME,
            "app_version"   to AmazonAuthService.APP_VERSION,
            "device_model"  to AmazonAuthService.DEVICE_MODEL,
            "os_version"    to AmazonAuthService.OS_VERSION,
        )
        val authData = mapOf("access_token" to accessToken)
        val payload = mapOf(
            "auth_data"            to authData,
            "registration_data"    to regData,
            "requested_token_type" to listOf("bearer", "website_cookies"),
            "requested_extensions" to listOf("device_info", "customer_info"),
            "cookies"              to mapOf("domain" to ".$CBL_DOMAIN", "website_cookies" to emptyList<String>()),
        )
        val reqId = UUID.randomUUID().toString().replace("-", "")
        val resp = client.newCall(
            Request.Builder()
                .url("https://api.$CBL_DOMAIN/auth/register")
                .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
                .header("User-Agent", AmazonAuthService.USER_AGENT)
                .header("Accept-Charset", "utf-8")
                .header("X-Requested-With", AmazonAuthService.APP_NAME)
                .header("x-gasc-enabled", "true")
                .header("Accept-Language", "en-US")
                .header("Content-Type", "application/json")
                .header("x-amzn-identity-auth-domain", "api.$CBL_DOMAIN")
                .header("x-amzn-requestid", reqId)
                .build()
        ).execute()
        val body = resp.body?.string() ?: throw RuntimeException("Empty registration response")
        Log.w(TAG, "CBL registration: HTTP ${resp.code}, body=${body.length}")
        if (!resp.isSuccessful) throw RuntimeException(errorOf(body) ?: "HTTP ${resp.code}")

        val json = gson.fromJson(body, JsonObject::class.java)
        val respObj = json.getAsJsonObject("response") ?: throw RuntimeException("Missing 'response'")
        if (respObj.has("error"))
            throw RuntimeException(respObj.getAsJsonObject("error").get("message")?.asString ?: "Unknown error")
        val bearer = respObj.getAsJsonObject("success")
            ?.getAsJsonObject("tokens")?.getAsJsonObject("bearer")
            ?: throw RuntimeException("No bearer token in response")
        val expiresIn = bearer.get("expires_in")?.asLong ?: 3600L
        return TokenData(
            accessToken  = bearer.get("access_token")?.asString  ?: throw RuntimeException("No access_token"),
            refreshToken = bearer.get("refresh_token")?.asString ?: throw RuntimeException("No refresh_token"),
            deviceId     = deviceId,
            expiresIn    = expiresIn,
            expiresAt    = System.currentTimeMillis() / 1000 + expiresIn,
        )
    }

    private fun errorOf(body: String): String? = try {
        val json = gson.fromJson(body, JsonObject::class.java)
        json.get("error")?.asString
            ?: json.getAsJsonObject("response")?.getAsJsonObject("error")?.get("message")?.asString
    } catch (_: Exception) { null }

    private fun hexEncode(s: String): String = s.toByteArray().joinToString("") { "%02x".format(it) }
}
