package com.tspolice.runtracker.update

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * Periodic Android JobScheduler service (persisted across device reboots) that checks
 * GitHub Releases in the background even when the app is closed, pre-downloads and
 * verifies the SHA-256 checksum of any new APK, and posts a 1-tap install notification.
 */
class UpdateCheckJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val updater = AppUpdater(applicationContext)
        if (!updater.autoCheckOnLaunch) {
            jobFinished(params, false)
            return false
        }
        updater.runBackgroundCheckAndPreDownload {
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        // Return true so JobScheduler reschedules if connectivity dropped mid-download
        return true
    }
}
