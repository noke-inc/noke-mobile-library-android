package com.noke.nokemobilelibrary.phonekey.internal

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File

/** Encrypted storage for the Phone Key gateway API key. */
internal object PhoneKeyCredentialStore {
    private const val TAG = "PhoneKeyCredentialStore"
    private const val FILE_NAME = "noke_phone_key_credentials"
    private const val MASTER_KEY_ALIAS = "noke_phone_key_credentials_master"
    private const val KEY_API_KEY = "api_key"

    private val lock = Any()
    @Volatile private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: synchronized(lock) {
            prefs ?: PhoneKeyManager.openEncryptedPrefs(context.applicationContext, FILE_NAME, MASTER_KEY_ALIAS)
                .also { prefs = it }
        }

    /**
     * Stores the API key encrypted; a null or blank value clears it.
     * @throws IllegalStateException if the key cannot be persisted
     */
    @JvmStatic
    fun setApiKey(context: Context, apiKey: String?) {
        try {
            val editor = prefs(context).edit()
            if (apiKey.isNullOrBlank()) editor.remove(KEY_API_KEY) else editor.putString(KEY_API_KEY, apiKey)
            check(editor.commit()) { "commit returned false" }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist Phone Key API key: ${e.message}", e)
            throw IllegalStateException("Failed to persist Phone Key API key", e)
        }
    }

    /** Returns the stored API key, or null if none is stored or storage is unreadable. */
    @JvmStatic
    fun getApiKey(context: Context): String? {
        // Don't create a keystore master key just to find out nothing was ever stored.
        if (prefs == null && !File(context.applicationInfo.dataDir, "shared_prefs/$FILE_NAME.xml").exists()) return null
        return try {
            prefs(context).getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read Phone Key API key: ${e.message}", e)
            null
        }
    }
}
