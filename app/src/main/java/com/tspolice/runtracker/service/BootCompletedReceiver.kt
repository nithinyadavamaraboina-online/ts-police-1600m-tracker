package com.tspolice.runtracker.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.tspolice.runtracker.data.RunRepository
import com.tspolice.runtracker.update.AppUpdater

/**
 * Automatically resumes All-Day Auto Walk/Jog/Run tracking and background OTA checks
 * after device reboot or in-app OTA APK upgrade when All-Day Auto Tracking is enabled.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        runCatching { AppUpdater(context).syncBackgroundUpdateSchedule() }

        val repo = RunRepository.getInstance(context)
        if (!repo.allDayTrackingEnabled) return

        val hasActivityRecognition = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val hasLocation =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        // On Android 14+ (API 34+), background BOOT_COMPLETED FGS requires FOREGROUND_SERVICE_TYPE_HEALTH + ACTIVITY_RECOGNITION
        val canStartFgsFromBoot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hasActivityRecognition
        } else {
            hasActivityRecognition || hasLocation
        }

        if (canStartFgsFromBoot) {
            runCatching {
                val svcIntent = Intent(context, RunTrackingService::class.java).apply {
                    putExtra(RunTrackingService.EXTRA_BOOT_AUTOSTART, true)
                }
                ContextCompat.startForegroundService(context, svcIntent)
            }
        }
    }
}
