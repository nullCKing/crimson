package com.crimson.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.crimson.core.epg.XmltvTime
import com.crimson.core.model.Country
import com.crimson.core.model.FilterRules
import com.crimson.core.model.Market
import com.crimson.core.skip.ThemeSkipChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Everything the settings screen can change, and the small amount of state the player keeps. */
data class AppSettings(
    val rules: FilterRules = FilterRules.DEFAULT,
    /** Manual correction for providers that publish wrong XMLTV offsets, in hours. */
    val epgOffsetHours: Int = 0,
    /** `ts` or `m3u8`. Defaults to whatever the provider listed first at login. */
    val streamFormat: String = "ts",
    val lastEpgRefreshAt: Long = 0L,
    /** What the last guide import found, in one line, for the Settings screen. */
    val lastEpgSummary: String = "",
    /**
     * Whether the built-in public guide feeds are used on top of the provider's own data.
     *
     * On by default. A provider's XMLTV covers the channels it has ids for and no more, and the
     * viewer has no way of knowing which public feed carries the rest; making them go and find
     * out would be making them do research the app has already done. It is a setting at all only
     * so a metered connection can turn the downloads off.
     */
    val useExtraEpgSources: Boolean = true,
    /**
     * Which version of the title-matching rules the cached catalogue's keys were built with.
     *
     * The keys are computed once at import and stored, so improving the rules leaves a catalogue
     * whose keys mean something slightly different from what the curated lists now ask for. This
     * is how the app notices and rebuilds instead of quietly matching less than it should.
     */
    val catalogKeyVersion: Int = 0,
    val lastChannelStreamId: Long = 0L,
    val previousChannelStreamId: Long = 0L,
    val hasCompletedImport: Boolean = false,
    /** Which version of the IMDb title index the catalogue was last joined against. */
    val titleIndexVersion: Int = 0,
    /** Whether focusing a live channel plays it in the Live TV hero after a moment. */
    val livePreviews: Boolean = true,
    /** Captions on or off, remembered between titles the way a streaming service remembers it. */
    val captions: Boolean = false,
    val captionSize: CaptionSize = CaptionSize.MEDIUM,
    /** A dark box behind the text (the most legible) or just an outline around it. */
    val captionBackground: Boolean = true,
    /** `[door slams]` and speaker names, as closed captions have them; off for plain subtitles. */
    val captionSoundDescriptions: Boolean = true,
    /** Louder dialogue, quieter explosions; see DialogueLeveler. */
    val dialogueBoost: Boolean = false,
    /** How bright full-screen video is drawn, in percent; the menus are never dimmed. */
    val videoBrightness: Int = 100,
    /** English audio when a file has several tracks: the dub, for anime. */
    val preferEnglishAudio: Boolean = true,
    /** Theme-song skipping, per show, where the viewer has changed it; see [themeSkipFor]. */
    val themeSkip: Map<String, ThemeSkipChoice> = emptyMap(),
) {
    /** What to skip for a show: the viewer's choice, or the built-in one for the shows asked for. */
    fun themeSkipFor(show: String): ThemeSkipChoice = themeSkip[show] ?: ThemeSkipChoice.defaultFor(show)
}

enum class CaptionSize(val label: String, val scale: Float) {
    SMALL("Small", 0.8f), MEDIUM("Medium", 1f), LARGE("Large", 1.25f), EXTRA_LARGE("Extra large", 1.55f);

    fun next(): CaptionSize = entries[(ordinal + 1) % entries.size]
    fun previous(): CaptionSize = entries[(ordinal + entries.size - 1) % entries.size]
}

/**
 * Settings, in DataStore.
 *
 * The filter rules live here rather than in the database because they are the user's intent, not
 * the provider's data: wiping and re-importing the catalogue must not silently re-enable a country
 * the user turned off.
 */
class SettingsStore private constructor(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            rules = FilterRules(
                countries = prefs[KEY_COUNTRIES]
                    ?.mapNotNull { Country.fromCode(it) }
                    ?.toSet()
                    ?: FilterRules.DEFAULT.countries,
                markets = prefs[KEY_MARKETS]
                    ?.mapNotNull { name -> runCatching { Market.valueOf(name) }.getOrNull() }
                    ?.toSet()
                    ?: FilterRules.DEFAULT.markets,
                excludeKeywords = prefs[KEY_EXCLUSIONS]
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)
                    ?: emptyList(),
            ),
            epgOffsetHours = prefs[KEY_EPG_OFFSET] ?: 0,
            streamFormat = prefs[KEY_FORMAT] ?: "ts",
            lastEpgRefreshAt = prefs[KEY_EPG_REFRESHED] ?: 0L,
            lastEpgSummary = prefs[KEY_EPG_SUMMARY] ?: "",
            useExtraEpgSources = prefs[KEY_EXTRA_EPG] ?: true,
            catalogKeyVersion = prefs[KEY_CATALOG_VERSION] ?: 0,
            lastChannelStreamId = prefs[KEY_LAST_CHANNEL] ?: 0L,
            previousChannelStreamId = prefs[KEY_PREVIOUS_CHANNEL] ?: 0L,
            hasCompletedImport = prefs[KEY_IMPORTED] ?: false,
            titleIndexVersion = prefs[KEY_TITLE_INDEX] ?: 0,
            livePreviews = prefs[KEY_LIVE_PREVIEWS] ?: true,
            captions = prefs[KEY_CAPTIONS] ?: false,
            captionSize = prefs[KEY_CAPTION_SIZE]?.let { name -> CaptionSize.entries.firstOrNull { it.name == name } } ?: CaptionSize.MEDIUM,
            captionBackground = prefs[KEY_CAPTION_BACKGROUND] ?: true,
            captionSoundDescriptions = prefs[KEY_CAPTION_SDH] ?: true,
            dialogueBoost = prefs[KEY_DIALOGUE_BOOST] ?: false,
            videoBrightness = (prefs[KEY_VIDEO_BRIGHTNESS] ?: 100).coerceIn(MIN_BRIGHTNESS, 100),
            preferEnglishAudio = prefs[KEY_ENGLISH_AUDIO] ?: true,
            themeSkip = ThemeSkipChoice.decode(prefs[KEY_THEME_SKIP].orEmpty()),
        )
    }

    suspend fun setCountries(countries: Set<Country>) = dataStore.edit {
        it[KEY_COUNTRIES] = countries.map(Country::code).toSet()
    }

    suspend fun setMarkets(markets: Set<Market>) = dataStore.edit {
        it[KEY_MARKETS] = markets.map(Market::name).toSet()
    }

    suspend fun setExclusions(keywords: List<String>) = dataStore.edit {
        it[KEY_EXCLUSIONS] = keywords.joinToString(",")
    }

    suspend fun setEpgOffsetHours(hours: Int) = dataStore.edit {
        it[KEY_EPG_OFFSET] = hours.coerceIn(
            XmltvTime.MANUAL_OFFSET_HOURS.first,
            XmltvTime.MANUAL_OFFSET_HOURS.last,
        )
    }

    suspend fun setStreamFormat(format: String) = dataStore.edit {
        it[KEY_FORMAT] = if (format == "m3u8") "m3u8" else "ts"
    }

    suspend fun setEpgRefreshedAt(at: Long) = dataStore.edit { it[KEY_EPG_REFRESHED] = at }

    suspend fun setEpgSummary(summary: String) = dataStore.edit { it[KEY_EPG_SUMMARY] = summary }

    suspend fun setUseExtraEpgSources(use: Boolean) = dataStore.edit { it[KEY_EXTRA_EPG] = use }

    suspend fun setCatalogKeyVersion(version: Int) =
        dataStore.edit { it[KEY_CATALOG_VERSION] = version }

    /**
     * Records the channel being watched, keeping the one before it so Play/Pause can jump back.
     * Tuning to the channel already playing must not overwrite the previous one, or "last channel"
     * would become a no-op after any accidental re-tune.
     */
    suspend fun setCurrentChannel(streamId: Long) = dataStore.edit { prefs ->
        val current = prefs[KEY_LAST_CHANNEL] ?: 0L
        if (current != streamId) {
            if (current != 0L) prefs[KEY_PREVIOUS_CHANNEL] = current
            prefs[KEY_LAST_CHANNEL] = streamId
        }
    }

    suspend fun setImportCompleted(done: Boolean) = dataStore.edit { it[KEY_IMPORTED] = done }

    suspend fun setTitleIndexVersion(version: Int) = dataStore.edit { it[KEY_TITLE_INDEX] = version }

    suspend fun setLivePreviews(on: Boolean) = dataStore.edit { it[KEY_LIVE_PREVIEWS] = on }

    suspend fun setCaptions(on: Boolean) = dataStore.edit { it[KEY_CAPTIONS] = on }

    suspend fun setCaptionSize(size: CaptionSize) = dataStore.edit { it[KEY_CAPTION_SIZE] = size.name }

    suspend fun setCaptionBackground(on: Boolean) = dataStore.edit { it[KEY_CAPTION_BACKGROUND] = on }

    suspend fun setCaptionSoundDescriptions(on: Boolean) = dataStore.edit { it[KEY_CAPTION_SDH] = on }

    suspend fun setDialogueBoost(on: Boolean) = dataStore.edit { it[KEY_DIALOGUE_BOOST] = on }

    suspend fun setVideoBrightness(percent: Int) = dataStore.edit { it[KEY_VIDEO_BRIGHTNESS] = percent.coerceIn(MIN_BRIGHTNESS, 100) }

    suspend fun setPreferEnglishAudio(on: Boolean) = dataStore.edit { it[KEY_ENGLISH_AUDIO] = on }

    suspend fun setThemeSkip(show: String, choice: ThemeSkipChoice) = dataStore.edit { prefs ->
        val others = prefs[KEY_THEME_SKIP].orEmpty().filterNot { it.substringBefore('|') == show }
        prefs[KEY_THEME_SKIP] = others.toSet() + choice.encode(show)
    }

    suspend fun clear() = dataStore.edit { it.clear() }

    companion object {
        /** The dimmest video can go: dark enough for a dark room, never black. */
        const val MIN_BRIGHTNESS = 5

        /** The brightness steps Left and Right move through. */
        val BRIGHTNESS_STEPS = listOf(5, 10, 15, 20, 30, 40, 50, 60, 70, 80, 90, 100)

        private val stores = HashMap<String, SettingsStore>()

        /**
         * One store per profile, and only ever one instance per file: DataStore throws if two
         * instances in a process open the same file, so they are cached here for the process.
         */
        fun forProfile(context: Context, profileId: String): SettingsStore = synchronized(stores) {
            stores.getOrPut(profileId) {
                val app = context.applicationContext
                SettingsStore(
                    PreferenceDataStoreFactory.create(
                        produceFile = { app.preferencesDataStoreFile("crimson_$profileId") }
                    )
                )
            }
        }

        /** Deletes a profile's settings file. The cached instance, if any, is dropped first. */
        fun delete(context: Context, profileId: String) = synchronized(stores) {
            stores.remove(profileId)
            context.applicationContext.preferencesDataStoreFile("crimson_$profileId").delete()
        }

        private val KEY_COUNTRIES = stringSetPreferencesKey("filter_countries")
        private val KEY_MARKETS = stringSetPreferencesKey("filter_markets")
        private val KEY_EXCLUSIONS = stringPreferencesKey("filter_exclusions")
        private val KEY_EPG_OFFSET = intPreferencesKey("epg_offset_hours")
        private val KEY_FORMAT = stringPreferencesKey("stream_format")
        private val KEY_EPG_REFRESHED = longPreferencesKey("epg_refreshed_at")
        private val KEY_EPG_SUMMARY = stringPreferencesKey("epg_last_summary")
        private val KEY_EXTRA_EPG = booleanPreferencesKey("epg_extra_sources")
        private val KEY_CATALOG_VERSION = intPreferencesKey("catalog_key_version")
        private val KEY_LAST_CHANNEL = longPreferencesKey("last_channel")
        private val KEY_PREVIOUS_CHANNEL = longPreferencesKey("previous_channel")
        private val KEY_IMPORTED = booleanPreferencesKey("import_completed")
        private val KEY_TITLE_INDEX = intPreferencesKey("title_index_version")
        private val KEY_LIVE_PREVIEWS = booleanPreferencesKey("live_previews")
        private val KEY_CAPTIONS = booleanPreferencesKey("captions")
        private val KEY_CAPTION_SIZE = stringPreferencesKey("caption_size")
        private val KEY_CAPTION_BACKGROUND = booleanPreferencesKey("caption_background")
        private val KEY_CAPTION_SDH = booleanPreferencesKey("caption_sound_descriptions")
        private val KEY_DIALOGUE_BOOST = booleanPreferencesKey("dialogue_boost")
        private val KEY_VIDEO_BRIGHTNESS = intPreferencesKey("video_brightness")
        private val KEY_ENGLISH_AUDIO = booleanPreferencesKey("prefer_english_audio")
        private val KEY_THEME_SKIP = stringSetPreferencesKey("theme_skip")
    }
}
