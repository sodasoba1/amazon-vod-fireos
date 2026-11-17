package com.scriptgod.fireos.avod.auth

import android.content.Context
import android.os.Build
import android.util.Log
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Resolves Amazon's Android TV DTID from the physical Android build.
 * The ACM request is unauthenticated; the returned DTID is cached for seven days.
 * A failed/unknown mapping deliberately falls back to the existing Android profile.
 */
class AmazonDtidResolver(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("amazon_device_identity", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    fun resolve(deviceId: String): String {
        val cached = preferences.getString(KEY_DTID, null)?.takeIf { it.isNotBlank() }
        val cachedAt = preferences.getLong(KEY_TIMESTAMP, 0L)
        val isFresh = cached != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS
        if (isFresh) return cached!!

        val mapped = runCatching { requestMapping(deviceId) }.onFailure {
            Log.w(TAG, "Amazon DTID mapping unavailable (${it.javaClass.simpleName}); retaining existing Android profile")
        }.getOrNull()

        if (!mapped.isNullOrBlank()) {
            preferences.edit()
                .putString(KEY_DTID, mapped)
                .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                .apply()
            Log.i(TAG, "Amazon DTID mapping resolved and cached")
            return mapped
        }

        if (cached != null) {
            Log.w(TAG, "Amazon DTID refresh failed; using previously cached mapping")
            return cached
        }
        Log.w(TAG, "No Amazon DTID mapping available; using Android profile fallback")
        return AmazonAuthService.DEVICE_TYPE_ID
    }

    private fun requestMapping(deviceId: String): String? {
        val terminator = when {
            Build.VERSION.SDK_INT < 29 -> "abzzpdkgwfbs"
            Build.VERSION.SDK_INT == 29 -> "abbfmcmp8tye"
            else -> "ab8mt4dd97et"
        }
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("$terminator.api.amazonvideo.com")
            .addPathSegments("acm/GetConfiguration/androidtv-primevideo-dtid-config")
            .addQueryParameter("deviceId", deviceId)
            .addQueryParameter("deviceTypeId", "")
            .addQueryParameter("manufacturer", Build.MANUFACTURER.lowercase(Locale.ROOT))
            .addQueryParameter("chipset_id", Build.HARDWARE.lowercase(Locale.ROOT))
            .addQueryParameter("model_name", Build.MODEL.lowercase(Locale.ROOT))
            .addQueryParameter("brand", Build.BRAND.lowercase(Locale.ROOT))
            .addQueryParameter("product", Build.PRODUCT.lowercase(Locale.ROOT))
            .addQueryParameter("device", Build.DEVICE.lowercase(Locale.ROOT))
            .addQueryParameter("firmware", Build.DISPLAY)
            .addQueryParameter("format", "json")
            .build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Amazon DTID mapping returned HTTP ${response.code}")
                return null
            }
            val body = response.body?.string().orEmpty()
            return runCatching {
                JSONObject(body).optString("generatedDTID").takeIf { it.isNotBlank() }
            }.getOrNull()
        }
    }

    companion object {
        private const val TAG = "AmazonDtidResolver"
        private const val KEY_DTID = "amazon_device_type_id"
        private const val KEY_TIMESTAMP = "amazon_device_type_id_timestamp"
        private const val CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
