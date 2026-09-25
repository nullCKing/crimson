package com.crimson

import android.app.Application
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.crimson.work.EpgRefreshWorker

class CrimsonApp : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        container = AppContainer.get(this)
        EpgRefreshWorker.schedule(this, ExistingPeriodicWorkPolicy.KEEP)
    }

    /**
     * WorkManager is initialised here rather than by its default provider so it starts with the
     * app's own configuration. The manifest removes the default initialiser to match.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onLowMemory() {
        super.onLowMemory()
        // On a 1 GB stick this is a real event, not a formality.
        WorkManager.getInstance(this).cancelAllWorkByTag(EpgRefreshWorker.TAG)
    }
}
