package com.crimson.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.crimson.AppContainer
import com.crimson.core.epg.EpgSources
import com.crimson.data.epg.EpgProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import java.util.concurrent.TimeUnit

/**
 * Refreshes guide data in the background, at most every twelve hours.
 *
 * The guide stays usable throughout: the import writes into the same tables the guide reads, but
 * the database runs in write-ahead logging mode, so readers are never blocked by the writer.
 * Programmes are inserted with `IGNORE` on conflict and old ones pruned at the end, so a refresh
 * that fails halfway leaves the guide with older data rather than with a hole in it.
 */
class EpgRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.get(applicationContext)

        // The profile on screen, or the one used last when the app is not running. With no
        // profile at all there is nothing to refresh.
        val session = container.session
            ?: container.profiles.byId(container.profiles.lastProfileId)?.let(container::activate)
            ?: return Result.success()

        val importer = session.xmltvImporter()
        val settings = session.settings

        return try {
            val current = settings.settings.first()
            val extras =
                if (current.useExtraEpgSources)
                    EpgSources.forCountries(current.rules.countries.map { it.code }.toSet())
                else emptyList()
            val done = importer.import(
                manualOffsetHours = current.epgOffsetHours,
                extraSources = extras,
            ).last()
            if (done is EpgProgress.Done) {
                Log.i(TAG, "guide refreshed: kept ${done.kept} of ${done.seen} programmes")
                settings.setEpgSummary(done.report.summary())
            }
            settings.setEpgRefreshedAt(System.currentTimeMillis())
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "guide refresh failed", e)
            // Retried with WorkManager's own backoff; the guide keeps the data it already has.
            Result.retry()
        }
    }

    companion object {
        const val TAG = "epg-refresh"
        private const val WORK_NAME = "epg-refresh-periodic"

        /** Twelve hours, as the spec asks. */
        const val INTERVAL_HOURS = 12L

        fun schedule(context: Context, policy: ExistingPeriodicWorkPolicy) {
            val request = PeriodicWorkRequestBuilder<EpgRefreshWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS,
            )
                .addTag(TAG)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, policy, request)
        }
    }
}
