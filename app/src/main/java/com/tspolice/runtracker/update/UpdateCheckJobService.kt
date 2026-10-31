package com.tspolice.runtracker.update

import android.app.job.JobParameters
import android.app.job.JobService
import com.tspolice.runtracker.data.RunRepository
import com.tspolice.runtracker.health.SamsungHealthSyncManager
import com.tspolice.runtracker.telemetry.TuningTelemetryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Periodic Android JobScheduler service (persisted across device reboots) that checks
 * GitHub Releases in the background even when the app is closed, pre-downloads and
 * verifies the SHA-256 checksum of any new APK, posts a 1-tap install notification,
 * and keeps hands-free cloud telemetry + remote pull requests alive.
 */
class UpdateCheckJobService : JobService() {

    private var jobScope: CoroutineScope? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        val appCtx = applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        jobScope = scope

        scope.launch {
            runCatching {
                val repo = RunRepository(appCtx)
                val telemetry = TuningTelemetryManager(
                    context = appCtx,
                    repository = repo,
                    healthSyncManager = SamsungHealthSyncManager(appCtx, repo)
                )
                val handledPull = telemetry.checkAndHandlePendingPullCommands { null }
                if (!handledPull) {
                    telemetry.maybeAutoSyncIfDue(reason = "job_scheduler_background")
                }
            }
            val updater = AppUpdater(appCtx)
            if (!updater.autoCheckOnLaunch) {
                jobFinished(params, false)
            } else {
                updater.runBackgroundCheckAndPreDownload {
                    jobFinished(params, false)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        jobScope?.cancel()
        jobScope = null
        // Return true so JobScheduler reschedules if connectivity dropped mid-download
        return true
    }
}
