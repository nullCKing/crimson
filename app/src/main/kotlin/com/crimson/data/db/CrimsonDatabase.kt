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
    ],
    version = 5,
    exportSchema = true,
)
abstract class CrimsonDatabase : RoomDatabase() {

    abstract fun channelDao(): ChannelDao
    abstract fun programDao(): ProgramDao
    abstract fun categoryDao(): CategoryDao
    abstract fun channelNumberDao(): ChannelNumberDao
    abstract fun favoritesDao(): FavoritesDao
    abstract fun libraryDao(): LibraryDao

    companion object {
        private const val NAME = "crimson.db"

        @Volatile
        private var instance: CrimsonDatabase? = null

        fun get(context: Context): CrimsonDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): CrimsonDatabase =
            Room.databaseBuilder(context, CrimsonDatabase::class.java, NAME)
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
