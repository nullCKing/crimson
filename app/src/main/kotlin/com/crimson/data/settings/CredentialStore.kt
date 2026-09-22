package com.crimson.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.crimson.data.xtream.XtreamAccount

/**
 * The provider's server, username and password, encrypted at rest.
 *
 * Credentials are entered on first launch and never compiled in: nothing in the repository, the
 * APK or `reports/discovery.md` contains them, and `secrets/` is gitignored.
 *
 * Encryption uses a key held in the platform keystore, so the stored file is unreadable to other
 * apps and to anyone pulling the data directory off a rooted device. It is not proof against an
 * attacker who already controls the device — the app has to be able to decrypt these to log in —
 * but it is the right bar for a credential the user did not choose to share.
 *
 * On a device whose keystore is broken (which happens on a few Fire OS builds after a factory
 * reset) the encrypted store cannot be opened at all. Rather than making the app unusable, it
 * falls back to ordinary preferences and the login screen says the credentials are stored
 * unencrypted, so the user can decide.
 */
class CredentialStore(private val context: Context) {

    private var encrypted = true
    private val fallbackPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(FALLBACK_FILE_NAME, Context.MODE_PRIVATE)
    }

    private var prefs: SharedPreferences = try {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.w(TAG, "encrypted storage unavailable, falling back to plain preferences", e)
        encrypted = false
        context.getSharedPreferences(FALLBACK_FILE_NAME, Context.MODE_PRIVATE)
    }

    /** False when the keystore was unavailable and credentials are stored in the clear. */
    val isEncrypted: Boolean get() = encrypted

    fun load(): XtreamAccount? {
        // Try encrypted / primary store first
        try {
            val server = prefs.getString(KEY_SERVER, null)
            val user = prefs.getString(KEY_USER, null)
            val pass = prefs.getString(KEY_PASS, null)
            if (!server.isNullOrBlank() && !user.isNullOrBlank() && pass != null) {
                return XtreamAccount(server, user, pass)
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to read encrypted credentials; checking fallback", e)
            encrypted = false
            prefs = fallbackPrefs
        }

        // Fallback store
        try {
            val server = fallbackPrefs.getString(KEY_SERVER, null) ?: return null
            val user = fallbackPrefs.getString(KEY_USER, null) ?: return null
            val pass = fallbackPrefs.getString(KEY_PASS, null) ?: return null
            if (server.isNotBlank() && user.isNotBlank()) {
                return XtreamAccount(server, user, pass)
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to read fallback preferences", e)
        }
        return null
    }

    fun save(account: XtreamAccount) {
        var savedInPrimary = false
        try {
            savedInPrimary = prefs.edit()
                .putString(KEY_SERVER, account.serverUrl)
                .putString(KEY_USER, account.username)
                .putString(KEY_PASS, account.password)
                .commit()
        } catch (e: Exception) {
            Log.w(TAG, "failed to save to encrypted preferences, writing to fallback", e)
            encrypted = false
            prefs = fallbackPrefs
        }

        if (!savedInPrimary) {
            try {
                fallbackPrefs.edit()
                    .putString(KEY_SERVER, account.serverUrl)
                    .putString(KEY_USER, account.username)
                    .putString(KEY_PASS, account.password)
                    .commit()
            } catch (e: Exception) {
                Log.e(TAG, "failed to save to fallback preferences", e)
            }
        }
    }

    fun clear() {
        try {
            prefs.edit().clear().commit()
        } catch (e: Exception) {
            Log.w(TAG, "failed to clear primary preferences", e)
        }
        try {
            fallbackPrefs.edit().clear().commit()
        } catch (e: Exception) {
            Log.w(TAG, "failed to clear fallback preferences", e)
        }
    }

    private companion object {
        const val TAG = "CredentialStore"
        const val FILE_NAME = "crimson_credentials"
        const val FALLBACK_FILE_NAME = "crimson_credentials_plain"
        const val KEY_SERVER = "server"
        const val KEY_USER = "username"
        const val KEY_PASS = "password"
    }
}
