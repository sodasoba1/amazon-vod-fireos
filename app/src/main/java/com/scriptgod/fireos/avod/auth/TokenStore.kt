package com.scriptgod.fireos.avod.auth

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.scriptgod.fireos.avod.model.TokenData
import java.io.File

/** Stores Amazon tokens exclusively in this app's private internal storage. */
class TokenStore(private val context: Context) {

    companion object {
        private const val TAG = "TokenStore"
        private const val TOKEN_FILENAME = ".device-token"
        private val gson = Gson()

        /** Finds a non-empty token file only inside this app's private storage. */
        fun findTokenFile(context: Context): File? {
            val internal = File(context.filesDir, TOKEN_FILENAME)
            return internal.takeIf { it.isFile && it.length() > 0 }
        }
    }

    private val tokenFile: File get() = File(context.filesDir, TOKEN_FILENAME)

    /** Returns true if a non-empty token with valid access and refresh tokens exists. */
    fun isValid(): Boolean {
        val file = findTokenFile(context) ?: return false
        return try {
            val data = gson.fromJson(file.readText(), TokenData::class.java)
            data.accessToken.isNotEmpty() && data.refreshToken.isNotEmpty()
        } catch (_: Exception) { false }
    }

    /** Persists tokens only in Android's app-private internal files directory. */
    fun save(tokenData: TokenData) {
        tokenFile.writeText(gson.toJson(tokenData))
        Log.w(TAG, "Token saved in app-private storage")
    }
}
