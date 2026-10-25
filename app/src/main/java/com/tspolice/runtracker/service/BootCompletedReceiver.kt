package com.tspolice.runtracker.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.tspolice.runtracker.data.RunRepository
import com.tspolice.runtracker.update.AppUpdater

/**
 * Automatically resumes All-Day Auto Walk/Jog/Run tracking and background OTA checks
 * after device reboot or in-app OTA APK upgrade.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        runCatching { AppUpdater(context).syncBackgroundUpdateSchedule() }

        val repo = RunRepository(context)
        if (!repo.allDayTrackingEnabled && !repo.stateChangeAudioEnabled) return

        val hasLocation =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasLocation) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, RunTrackingService::class.java))
            }
        }
    }
}
