package com.crimson

import android.content.Context
import com.crimson.data.db.CrimsonDatabase
import com.crimson.data.epg.ShortEpgFetcher
import com.crimson.data.epg.XmltvImporter
import com.crimson.data.profile.Profile
import com.crimson.data.profile.ProfileStore
import com.crimson.data.settings.SettingsStore
import com.crimson.data.sports.SportsRepository
import com.crimson.data.xtream.ChannelImporter
import com.crimson.data.xtream.LibraryImporter
import com.crimson.data.xtream.XtreamClient
import com.crimson.domain.GuideRepository
import com.crimson.player.ExoPlayerController
import okhttp3.OkHttpClient

/**
 * Everything that belongs to one signed-in profile: its provider connection, its database and its
 * settings. Switching profile swaps the whole session; nothing in one leaks into another.
 */
class Session(context: Context, val profile: Profile, httpClient: OkHttpClient) {
    val client: XtreamClient = XtreamClient(profile.account, httpClient)
    val database: CrimsonDatabase = CrimsonDatabase.open(context, profile.id)
    val settings: SettingsStore = SettingsStore.forProfile(context, profile.id)
    val guideRepository = GuideRepository(database)

    private val appContext = context.applicationContext

    fun channelImporter() = ChannelImporter(client, database)
    fun xmltvImporter() = XmltvImporter(client, database)
    fun shortEpgFetcher() = ShortEpgFetcher(client, database)
    fun libraryImporter() = LibraryImporter(client, database) {
        appContext.assets.open(TITLE_INDEX_ASSET).reader()
    }

    companion object {
        const val TITLE_INDEX_ASSET = "title_index.tsv"
    }
}

/**
 * Manual dependency wiring.
 *
 * A dependency-injection framework would earn its keep in a larger app; here it would be a build
 * plugin and a layer of generated code in exchange for constructing a dozen objects. The one thing
 * worth being careful about is the player: [player] is created once and shared across every
 * profile and screen, because a second ExoPlayer would open a second stream and Xtream accounts
 * are sold with a connection limit.
 *
 * The per-profile objects live in [session]. The accessors below it read through to the current
 * session and throw without one — every screen that uses them is behind the profile picker.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val httpClient: OkHttpClient = XtreamClient.defaultClient()

    val profiles = ProfileStore(appContext)
    val sports = SportsRepository(httpClient)

    val imageLoader: coil.ImageLoader by lazy {
        coil.ImageLoader.Builder(appContext)
            .okHttpClient(httpClient)
            .memoryCache {
                coil.memory.MemoryCache.Builder(appContext)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(appContext.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(120L * 1024 * 1024)
                    .build()
            }
            // Posters fade in rather than pop: the one animation that makes a poster wall look
            // finished instead of loading. Cheap, because it is a single alpha on the image layer.
            .crossfade(220)
            .respectCacheHeaders(false)
            .allowRgb565(true)
            .build()
            .also { coil.Coil.setImageLoader(it) }
    }

    init {
        imageLoader
    }

    /** The one and only player. */
    val player: ExoPlayerController by lazy { ExoPlayerController(appContext, httpClient) }

    @Volatile
    var session: Session? = null
        private set

    /** Makes [profile] the signed-in one. Re-activating the current profile is a no-op. */
    fun activate(profile: Profile): Session {
        session?.let { if (it.profile == profile) return it }
        return Session(appContext, profile, httpClient).also {
            session = it
            profiles.lastProfileId = profile.id
        }
    }

    fun deactivate() {
        session = null
    }

    /** Removes a profile and everything stored for it. */
    fun deleteProfile(profile: Profile) {
        if (session?.profile?.id == profile.id) session = null
        profiles.delete(profile.id)
        CrimsonDatabase.delete(appContext, profile.id)
        SettingsStore.delete(appContext, profile.id)
    }

    private fun requireSession(): Session = session ?: error("no profile is signed in")

    val database: CrimsonDatabase get() = requireSession().database
    val settings: SettingsStore get() = requireSession().settings
    val guideRepository: GuideRepository get() = requireSession().guideRepository
    val client: XtreamClient? get() = session?.client

    fun channelImporter(): ChannelImporter? = session?.channelImporter()
    fun xmltvImporter(): XmltvImporter? = session?.xmltvImporter()
    fun shortEpgFetcher(): ShortEpgFetcher? = session?.shortEpgFetcher()
    fun libraryImporter(): LibraryImporter? = session?.libraryImporter()

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context).also { instance = it }
            }
    }
}
