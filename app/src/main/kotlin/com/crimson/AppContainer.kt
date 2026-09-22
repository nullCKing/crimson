package com.crimson

import android.content.Context
import com.crimson.data.db.CrimsonDatabase
import com.crimson.data.epg.ShortEpgFetcher
import com.crimson.data.epg.XmltvImporter
import com.crimson.data.settings.CredentialStore
import com.crimson.data.settings.SettingsStore
import com.crimson.data.xtream.ChannelImporter
import com.crimson.data.xtream.LibraryImporter
import com.crimson.data.xtream.XtreamAccount
import com.crimson.data.xtream.XtreamClient
import com.crimson.domain.GuideRepository
import com.crimson.player.ExoPlayerController
import okhttp3.OkHttpClient

/**
 * Manual dependency wiring.
 *
 * A dependency-injection framework would earn its keep in a larger app; here it would be a build
 * plugin and a layer of generated code in exchange for constructing eight objects. The one thing
 * worth being careful about is the player: [player] is created once and shared, because a second
 * ExoPlayer would open a second stream and Xtream accounts are sold with a connection limit.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val httpClient: OkHttpClient = XtreamClient.defaultClient()

    val database: CrimsonDatabase = CrimsonDatabase.get(appContext)
    val settings = SettingsStore(appContext)
    val credentials = CredentialStore(appContext)
    val guideRepository = GuideRepository(database)

    val imageLoader: coil.ImageLoader by lazy {
        coil.ImageLoader.Builder(appContext)
            .okHttpClient(httpClient)
            .memoryCache {
                coil.memory.MemoryCache.Builder(appContext)
                    .maxSizePercent(0.10)
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(appContext.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .respectCacheHeaders(false)
            .build()
            .also { coil.Coil.setImageLoader(it) }
    }

    init {
        // Warm up ImageLoader
        imageLoader
    }

    /** The one and only player. */
    val player: ExoPlayerController by lazy { ExoPlayerController(appContext, httpClient) }

    /** Null until the user has signed in. */
    @Volatile
    var client: XtreamClient? = null
        private set

    fun connect(account: XtreamAccount): XtreamClient =
        XtreamClient(account, httpClient).also { client = it }

    fun disconnect() {
        client = null
    }

    fun channelImporter(): ChannelImporter? = client?.let { ChannelImporter(it, database) }

    fun xmltvImporter(): XmltvImporter? = client?.let { XmltvImporter(it, database) }

    fun shortEpgFetcher(): ShortEpgFetcher? = client?.let { ShortEpgFetcher(it, database) }

    fun libraryImporter(): LibraryImporter? = client?.let { LibraryImporter(it, database) }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context).also { instance = it }
            }
    }
}
