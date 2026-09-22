package com.crimson.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChannelEntity::class,
        ProgramEntity::class,
        CategoryEntity::class,
        ChannelNumberEntity::class,
        FavoriteChannelEntity::class,
        FavoriteCategoryEntity::class,
        VodEntity::class,
        SeriesEntity::class,
        MyListEntity::class,
        WatchProgressEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class CrimsonDatabase : RoomDatabase() {

    abstract fun channelDao(): ChannelDao
    abstract fun programDao(): ProgramDao
    abstract fun categoryDao(): CategoryDao
    abstract fun channelNumberDao(): ChannelNumberDao
    abstract fun favoritesDao(): FavoritesDao
    abstract fun libraryDao(): LibraryDao
    abstract fun userDao(): UserDao

    companion object {
        private val open = HashMap<String, CrimsonDatabase>()

        private fun fileName(profileId: String) = "crimson_$profileId.db"

        /**
         * The database for one profile. Every profile has its own file, because each is a
         * different provider's channels and catalogue, and its own list and history.
         *
         * Instances stay open for the life of the process once opened, rather than being closed
         * on a profile switch: a query still in flight from the profile being left would throw on
         * a closed database, and a household has a handful of profiles at most.
         */
        fun open(context: Context, profileId: String): CrimsonDatabase = synchronized(open) {
            open.getOrPut(profileId) { build(context.applicationContext, fileName(profileId)) }
        }

        /** Removes a deleted profile's database. */
        fun delete(context: Context, profileId: String) = synchronized(open) {
            open.remove(profileId)?.close()
            context.applicationContext.deleteDatabase(fileName(profileId))
        }

        private fun build(context: Context, name: String): CrimsonDatabase =
            Room.databaseBuilder(context, CrimsonDatabase::class.java, name)
                // The database is a cache of the provider's data, rebuildable by re-importing.
                // On a schema change, throwing it away and re-importing is both simpler and
                // safer than migrating a table the user has no unique data in.
                .fallbackToDestructiveMigration()
                // Write-ahead logging keeps the guide readable while the EPG refresh is writing,
                // which is the spec's "must stay usable during a refresh". Set here rather than
                // with a PRAGMA in onOpen: Room's AUTOMATIC mode falls back to TRUNCATE on a
                // low-RAM device, which a 1 GB stick is, and `execSQL("PRAGMA journal_mode=WAL")`
                // throws on Android because that PRAGMA returns a row.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // A Fire Stick Lite has 1 GB of RAM for everything. The default 2 MB page
                        // cache is more than this workload needs; 1 MB keeps the footprint down
                        // without measurably hurting the windowed queries.
                        db.execSQL("PRAGMA cache_size=-1024")
                        db.execSQL("PRAGMA synchronous=NORMAL")
                    }
                })
                .build()
    }
}
