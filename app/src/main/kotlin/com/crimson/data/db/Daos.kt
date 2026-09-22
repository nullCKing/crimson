package com.crimson.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(channels: List<ChannelEntity>)

    /**
     * Blocking counterpart, called from inside the streaming JSON parse.
     *
     * The parser hands over one channel at a time through a plain (non-suspending) callback, so
     * the only way to flush a full batch *during* a category — rather than after it — is a
     * blocking write. That matters for providers that put fifty thousand channels in a single
     * category: without it, peak memory would be a function of the largest category rather than
     * of the batch size. The caller is already on Dispatchers.IO.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAllBlocking(channels: List<ChannelEntity>)

    /**
     * The guide's channel list, already filtered by the current rules.
     *
     * This is the "instant filter change" path in the spec: removing a country or a market is a
     * different set of bind arguments against rows that are already on the device. There is no
     * network call and nothing is re-parsed, so the only cost is an indexed scan of a few hundred
     * rows — comfortably inside the 100 ms budget.
     *
     * A channel with no market is national and is kept whenever its country is allowed. A channel
     * with a market is a local and additionally needs that market to be allowed.
     */
    @Query(
        """
        SELECT * FROM channels
        WHERE country IN (:countries)
          AND (market IS NULL OR market IN (:markets))
        ORDER BY number ASC
        """
    )
    fun observeFiltered(countries: List<String>, markets: List<String>): Flow<List<ChannelEntity>>

    @Query(
        """
        SELECT * FROM channels
        WHERE country IN (:countries)
          AND (market IS NULL OR market IN (:markets))
        ORDER BY number ASC
        """
    )
    suspend fun getFiltered(countries: List<String>, markets: List<String>): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE streamId = :streamId")
    suspend fun byStreamId(streamId: Long): ChannelEntity?

    @Query("SELECT * FROM channels ORDER BY number ASC")
    suspend fun all(): List<ChannelEntity>

    /** Title search over the channel list, for the Browse screen's search box. */
    @Query(
        """
        SELECT * FROM channels
        WHERE displayName LIKE '%' || :query || '%' OR originalName LIKE '%' || :query || '%'
        ORDER BY LENGTH(displayName) ASC, number ASC LIMIT :limit
        """
    )
    suspend fun search(query: String, limit: Int): List<ChannelEntity>

    @Query("SELECT COUNT(*) FROM channels")
    suspend fun count(): Int

    @Query("SELECT DISTINCT country FROM channels")
    suspend fun importedCountries(): List<String>

    /** Removes channels the provider no longer lists. Their numbers stay in `channel_numbers`. */
    @Query("DELETE FROM channels WHERE categoryId IN (:categoryIds) AND lastSeenAt < :before")
    suspend fun deleteStaleIn(categoryIds: List<String>, before: Long)

    @Query("DELETE FROM channels")
    suspend fun clear()
}

@Dao
interface ProgramDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(programs: List<ProgramEntity>)

    /**
     * Blocking counterpart, called from inside the XMLTV pull-parse.
     *
     * The parser walks the document in a plain loop and cannot suspend mid-element, so batches
     * are written blocking. The caller is already on Dispatchers.IO, and write-ahead logging
     * keeps the guide readable while this runs.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAllBlocking(programs: List<ProgramEntity>)

    /**
     * Everything on these channels that overlaps the window.
     *
     * `startMs < :to AND endMs > :from` is an overlap test, not a containment test: a three-hour
     * film that began before the window opened still has to be drawn, with a notch on its left
     * edge. Asking for `startMs BETWEEN` would silently lose it.
     */
    @Query(
        """
        SELECT * FROM programs
        WHERE channelKey IN (:channelKeys)
          AND startMs < :to AND endMs > :from
        ORDER BY channelKey ASC, startMs ASC
        """
    )
    suspend fun inWindow(channelKeys: List<String>, from: Long, to: Long): List<ProgramEntity>

    @Query(
        """
        SELECT * FROM programs
        WHERE channelKey = :channelKey AND endMs > :now
        ORDER BY startMs ASC LIMIT :limit
        """
    )
    suspend fun upcoming(channelKey: String, now: Long, limit: Int): List<ProgramEntity>

    @Query("SELECT * FROM programs WHERE channelKey = :channelKey AND startMs <= :now AND endMs > :now LIMIT 1")
    suspend fun nowPlaying(channelKey: String, now: Long): ProgramEntity?

    /** What is on now across many channels at once, for the live cards. */
    @Query("SELECT * FROM programs WHERE channelKey IN (:channelKeys) AND startMs <= :now AND endMs > :now")
    suspend fun nowPlayingFor(channelKeys: List<String>, now: Long): List<ProgramEntity>

    @Query("SELECT COUNT(*) FROM programs")
    suspend fun count(): Int

    /** Which of these channels already have guide data, so the short-EPG fallback can skip them. */
    @Query("SELECT DISTINCT channelKey FROM programs WHERE channelKey IN (:channelKeys)")
    suspend fun keysWithData(channelKeys: List<String>): List<String>

    /** How many channels have any guide data at all, for the Settings screen's summary. */
    @Query("SELECT COUNT(DISTINCT channelKey) FROM programs")
    suspend fun channelsWithData(): Int

    /** Rows from the public feeds, removed when the viewer turns them off. */
    @Query("DELETE FROM programs WHERE source NOT IN (:keep)")
    suspend fun deleteSourcesOtherThan(keep: List<String>)

    @Query("DELETE FROM programs WHERE endMs < :before")
    suspend fun prune(before: Long)

    @Query("DELETE FROM programs WHERE channelKey = :channelKey")
    suspend fun clearChannel(channelKey: String)

    @Query("DELETE FROM programs")
    suspend fun clear()
}

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(categories: List<CategoryEntity>)

    @Query("SELECT * FROM categories")
    suspend fun all(): List<CategoryEntity>

    @Query("SELECT * FROM categories ORDER BY name ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE imported = 0")
    suspend fun notYetImported(): List<CategoryEntity>

    @Query("UPDATE categories SET imported = 1, lastImportedAt = :at WHERE categoryId = :categoryId")
    suspend fun markImported(categoryId: String, at: Long)

    @Query("DELETE FROM categories")
    suspend fun clear()
}

@Dao
interface ChannelNumberDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(numbers: List<ChannelNumberEntity>)

    /** Blocking counterpart, for flushing a batch from inside the streaming parse. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAllBlocking(numbers: List<ChannelNumberEntity>)

    @Query("SELECT * FROM channel_numbers")
    suspend fun all(): List<ChannelNumberEntity>

    /** Blocking counterpart, for loading the ledger before a streaming import begins. */
    @Query("SELECT * FROM channel_numbers")
    fun allBlocking(): List<ChannelNumberEntity>

    @Query("SELECT MAX(number) FROM channel_numbers WHERE number BETWEEN :from AND :to")
    suspend fun highestIn(from: Int, to: Int): Int?

    @Query("DELETE FROM channel_numbers")
    suspend fun clear()
}

@Dao
interface FavoritesDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavoriteChannel(favorite: FavoriteChannelEntity)

    @Query("DELETE FROM favorite_channels WHERE streamId = :streamId")
    suspend fun removeFavoriteChannel(streamId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_channels WHERE streamId = :streamId)")
    suspend fun isFavoriteChannel(streamId: Long): Boolean

    @Query("SELECT streamId FROM favorite_channels")
    fun observeFavoriteChannelIds(): Flow<List<Long>>

    @Query("SELECT streamId FROM favorite_channels")
    suspend fun getFavoriteChannelIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavoriteCategory(favorite: FavoriteCategoryEntity)

    @Query("DELETE FROM favorite_categories WHERE categoryId = :categoryId")
    suspend fun removeFavoriteCategory(categoryId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_categories WHERE categoryId = :categoryId)")
    suspend fun isFavoriteCategory(categoryId: String): Boolean

    @Query("SELECT categoryId FROM favorite_categories")
    fun observeFavoriteCategoryIds(): Flow<List<String>>

    @Query("SELECT categoryId FROM favorite_categories")
    suspend fun getFavoriteCategoryIds(): List<String>
}

@Dao
interface LibraryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertVodBlocking(items: List<VodEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSeriesBlocking(items: List<SeriesEntity>)

    /**
     * The films whose title key is one of [keys].
     *
     * This is how a curated list is resolved: the list's titles become keys, and one query
     * returns only the handful of rows the viewer actually has, instead of the catalogue.
     */
    @Query("SELECT * FROM vod WHERE titleKey IN (:keys) OR titleKeyAlt IN (:keys)")
    suspend fun vodByTitleKeys(keys: List<String>): List<VodEntity>

    @Query("SELECT * FROM series WHERE titleKey IN (:keys) OR titleKeyAlt IN (:keys)")
    suspend fun seriesByTitleKeys(keys: List<String>): List<SeriesEntity>

    /** The categories the cached catalogue actually uses, without reading the catalogue. */
    @Query("SELECT DISTINCT categoryId, categoryName FROM vod WHERE categoryId IS NOT NULL ORDER BY categoryName ASC")
    suspend fun vodCategories(): List<CategoryRef>

    @Query("SELECT DISTINCT categoryId, categoryName FROM series WHERE categoryId IS NOT NULL ORDER BY categoryName ASC")
    suspend fun seriesCategories(): List<CategoryRef>

    @Query("SELECT * FROM vod WHERE categoryId = :categoryId ORDER BY name ASC")
    suspend fun vodInCategory(categoryId: String): List<VodEntity>

    @Query("SELECT * FROM series WHERE categoryId = :categoryId ORDER BY name ASC")
    suspend fun seriesInCategory(categoryId: String): List<SeriesEntity>

    /**
     * Title search.
     *
     * `LIKE '%x%'` cannot use the index and scans the table, which is the right trade here: the
     * catalogue is tens of thousands of rows, a scan of that is a few milliseconds, and the
     * alternative — an FTS table — doubles the storage and still cannot match inside a word,
     * which is exactly what someone typing "bat" for Batman needs.
     */
    @Query("SELECT * FROM vod WHERE name LIKE '%' || :query || '%' ORDER BY LENGTH(name) ASC LIMIT :limit")
    suspend fun searchVod(query: String, limit: Int): List<VodEntity>

    @Query("SELECT * FROM series WHERE name LIKE '%' || :query || '%' ORDER BY LENGTH(name) ASC LIMIT :limit")
    suspend fun searchSeries(query: String, limit: Int): List<SeriesEntity>

    @Query("SELECT COUNT(*) FROM vod")
    suspend fun vodCount(): Int

    /** A feed row: SQL built by [com.crimson.core.catalog.CatalogSql]. */
    @RawQuery(observedEntities = [VodEntity::class])
    suspend fun vodQuery(query: SupportSQLiteQuery): List<VodEntity>

    @RawQuery(observedEntities = [SeriesEntity::class])
    suspend fun seriesQuery(query: SupportSQLiteQuery): List<SeriesEntity>

    @Query("SELECT * FROM vod WHERE streamId = :id")
    suspend fun vod(id: Long): VodEntity?

    @Query("SELECT * FROM series WHERE seriesId = :id")
    suspend fun series(id: Long): SeriesEntity?

    @Query("SELECT * FROM vod WHERE streamId IN (:ids)")
    suspend fun vodByIds(ids: List<Long>): List<VodEntity>

    @Query("SELECT * FROM series WHERE seriesId IN (:ids)")
    suspend fun seriesByIds(ids: List<Long>): List<SeriesEntity>

    /**
     * Joins one IMDb title onto the catalogue.
     *
     * Two statements rather than one `OR`, so each can use its own index. The year window is
     * plus or minus one because a film released at the turn of a year is filed under either,
     * and a name with no year at all is matched on the name alone — the index is applied in
     * ascending order of popularity, so the most-rated title of that name is the last to write.
     */
    @Query(
        """
        UPDATE vod SET genres = :genres, imdbRating = :rating, imdbVotes = :votes,
            titleYear = COALESCE(titleYear, :year)
        WHERE titleKey = :key AND (titleYear IS NULL OR titleYear BETWEEN :year - 1 AND :year + 1)
        """
    )
    fun enrichVodBlocking(key: String, year: Int, genres: String, rating: Float, votes: Int): Int

    @Query(
        """
        UPDATE vod SET genres = :genres, imdbRating = :rating, imdbVotes = :votes,
            titleYear = COALESCE(titleYear, :year)
        WHERE titleKeyAlt = :key AND (titleYear IS NULL OR titleYear BETWEEN :year - 1 AND :year + 1)
        """
    )
    fun enrichVodAltBlocking(key: String, year: Int, genres: String, rating: Float, votes: Int): Int

    @Query(
        """
        UPDATE series SET genres = :genres, imdbRating = :rating, imdbVotes = :votes,
            titleYear = COALESCE(titleYear, :year)
        WHERE (titleKey = :key OR titleKeyAlt = :key)
          AND (titleYear IS NULL OR titleYear BETWEEN :year - 1 AND :year + 1)
        """
    )
    fun enrichSeriesBlocking(key: String, year: Int, genres: String, rating: Float, votes: Int): Int

    @Query("SELECT COUNT(*) FROM vod WHERE imdbVotes IS NOT NULL")
    suspend fun vodIndexedCount(): Int

    @Query("SELECT COUNT(*) FROM series WHERE imdbVotes IS NOT NULL")
    suspend fun seriesIndexedCount(): Int

    @Query("SELECT COUNT(*) FROM series")
    suspend fun seriesCount(): Int

    @Query("DELETE FROM vod")
    suspend fun clearVod()

    @Query("DELETE FROM series")
    suspend fun clearSeries()
}

/** A category as the cached catalogue knows it. */
data class CategoryRef(val categoryId: String, val categoryName: String?)

@Dao
interface UserDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addToList(item: MyListEntity)

    @Query("DELETE FROM my_list WHERE kind = :kind AND itemId = :itemId")
    suspend fun removeFromList(kind: String, itemId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM my_list WHERE kind = :kind AND itemId = :itemId)")
    suspend fun isInList(kind: String, itemId: Long): Boolean

    @Query("SELECT * FROM my_list ORDER BY addedAt DESC")
    fun observeList(): Flow<List<MyListEntity>>

    @Query("SELECT * FROM my_list ORDER BY addedAt DESC")
    suspend fun list(): List<MyListEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: WatchProgressEntity)

    @Query("SELECT * FROM watch_progress WHERE `key` = :key")
    suspend fun progress(key: String): WatchProgressEntity?

    @Query("SELECT * FROM watch_progress WHERE seriesId = :seriesId ORDER BY updatedAt DESC")
    suspend fun progressForSeries(seriesId: Long): List<WatchProgressEntity>

    @Query("SELECT * FROM watch_progress ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<WatchProgressEntity>

    @Query("SELECT * FROM watch_progress ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<WatchProgressEntity>>

    @Query("DELETE FROM watch_progress WHERE `key` = :key")
    suspend fun deleteProgress(key: String)
}
