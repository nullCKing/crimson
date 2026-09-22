package com.crimson.data.profile

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.crimson.data.xtream.XtreamAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A profile: one Xtream login, with a name and an avatar, the way a streaming service has one
 * profile per person in the house.
 *
 * Each profile is a separate account as far as the data goes — its own database of channels,
 * guide and catalogue, its own settings, its own list and viewing history — because two logins
 * are usually two different providers, and nothing one imported means anything to the other.
 */
data class Profile(
    val id: String,
    val name: String,
    /** Index into the avatar palette; see `ui/components/Avatar.kt`. */
    val avatar: Int,
    val serverUrl: String,
    val username: String,
    val password: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val account: XtreamAccount get() = XtreamAccount(serverUrl, username, password)

    companion object {
        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
    }
}

/**
 * The profiles, encrypted at rest.
 *
 * They hold passwords, so they get the same treatment RetroGuide gave its single credential: a
 * key in the platform keystore, and a plain-preferences fallback only on the few Fire OS builds
 * whose keystore is broken, which the profile editor then says out loud.
 *
 * The list is small — a household's worth — so it is stored as one JSON document and held in
 * memory as a [StateFlow] the profile picker can watch.
 */
class ProfileStore(private val context: Context) {

    private var encrypted = true

    private val prefs: SharedPreferences = try {
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

    val isEncrypted: Boolean get() = encrypted

    private val _profiles = MutableStateFlow(read())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    fun byId(id: String?): Profile? = _profiles.value.firstOrNull { it.id == id }

    /** The profile that was used last, for the background guide refresh. */
    var lastProfileId: String?
        get() = runCatching { prefs.getString(KEY_LAST, null) }.getOrNull()
        set(value) {
            runCatching { prefs.edit().putString(KEY_LAST, value).apply() }
        }

    fun save(profile: Profile) {
        val list = _profiles.value.toMutableList()
        val index = list.indexOfFirst { it.id == profile.id }
        if (index >= 0) list[index] = profile else list.add(profile)
        write(list)
    }

    fun delete(id: String) {
        write(_profiles.value.filterNot { it.id == id })
        if (lastProfileId == id) lastProfileId = null
    }

    private fun read(): List<Profile> = try {
        val text = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            Profile(
                id = o.optString("id").ifEmpty { return@mapNotNull null },
                name = o.optString("name"),
                avatar = o.optInt("avatar"),
                serverUrl = o.optString("server"),
                username = o.optString("user"),
                password = o.optString("pass"),
                createdAt = o.optLong("created"),
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "profiles unreadable", e)
        emptyList()
    }

    private fun write(list: List<Profile>) {
        val array = JSONArray()
        list.forEach { p ->
            array.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("avatar", p.avatar)
                    .put("server", p.serverUrl)
                    .put("user", p.username)
                    .put("pass", p.password)
                    .put("created", p.createdAt)
            )
        }
        runCatching { prefs.edit().putString(KEY_PROFILES, array.toString()).commit() }
            .onFailure { Log.e(TAG, "profiles could not be saved", it) }
        _profiles.value = list
    }

    private companion object {
        const val TAG = "ProfileStore"
        const val FILE_NAME = "crimson_profiles"
        const val FALLBACK_FILE_NAME = "crimson_profiles_plain"
        const val KEY_PROFILES = "profiles"
        const val KEY_LAST = "last_profile"
    }
}
