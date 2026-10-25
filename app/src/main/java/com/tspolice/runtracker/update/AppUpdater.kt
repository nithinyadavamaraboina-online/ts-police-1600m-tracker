package com.tspolice.runtracker.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.tspolice.runtracker.ui.MainActivity
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

data class UpdateReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String?,
    val changelog: String,
    val publishedAt: String? = null
)

sealed class UpdateCheckResult {
    data class UpdateAvailable(
        val installedVersionName: String,
        val installedVersionCode: Int,
        val release: UpdateReleaseInfo
    ) : UpdateCheckResult()

    data class UpToDate(
        val installedVersionName: String,
        val installedVersionCode: Int
    ) : UpdateCheckResult()

    data class Error(val message: String) : UpdateCheckResult()
}

/**
 * Pure version comparison logic (unit-tested in JUnit without Android runtime stubs).
 */
object VersionComparator {
    fun isRemoteNewer(
        installedVersionCode: Int,
        installedVersionName: String,
        remoteVersionCode: Int,
        remoteVersionName: String
    ): Boolean {
        if (remoteVersionCode > 0 && installedVersionCode > 0 && remoteVersionCode != installedVersionCode) {
            return remoteVersionCode > installedVersionCode
        }
        return compareSemVer(remoteVersionName, installedVersionName) > 0
    }

    fun compareSemVer(a: String, b: String): Int {
        val partsA = parseSemVerParts(a)
        val partsB = parseSemVerParts(b)
        val maxLen = maxOf(partsA.size, partsB.size)
        for (i in 0 until maxLen) {
            val va = partsA.getOrElse(i) { 0 }
            val vb = partsB.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    fun parseSemVerParts(raw: String): List<Int> {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V").substringBefore("-")
        if (cleaned.isEmpty()) return listOf(0)
        return cleaned.split(".").map { it.toIntOrNull() ?: 0 }
    }
}

/**
 * Over-The-Air (OTA) GitHub Releases auto-updater:
 *  - Checks latest release manifest (zero API rate-limit asset + GitHub API fallback)
 *  - Streams APK with live progress & verifies SHA-256 integrity
 *  - Handles Android 8+ "Install Unknown Apps" permission flow and launches FileProvider installer
 */
class AppUpdater(private val context: Context) {

    companion object {
        const val GITHUB_OWNER = "nithinyadavamaraboina-online"
        const val GITHUB_REPO = "ts-police-1600m-tracker"

        const val LATEST_APK_DOWNLOAD_URL =
            "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest/download/ts-police-1600m-tracker.apk"

        private const val RELEASE_MANIFEST_URL =
            "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest/download/version.json"

        private const val GITHUB_LATEST_API_URL =
            "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

        private const val RAW_MAIN_MANIFEST_URL =
            "https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/main/version.json"

        private const val PREFS_NAME = "ts_police_updater_prefs"
        private const val KEY_AUTO_CHECK = "auto_check_updates"
        private const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version_tag"

        private const val BACKGROUND_JOB_ID = 1602
        private const val UPDATE_NOTIFICATION_ID = 1602
        private const val UPDATE_CHANNEL_ID = "ts_police_ota_updates"
        const val EXTRA_INSTALL_UPDATE_ON_OPEN = "extra_install_update_on_open"
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var pendingInstallApk: File? = null

    var autoCheckOnLaunch: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_CHECK, value).apply()
            syncBackgroundUpdateSchedule()
        }

    /**
     * Schedules or cancels periodic background GitHub Release checks via Android JobScheduler
     * (runs every 15 minutes whenever network is available, persisted across device reboots).
     */
    fun syncBackgroundUpdateSchedule() {
        val scheduler = appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        if (!autoCheckOnLaunch) {
            scheduler.cancel(BACKGROUND_JOB_ID)
            return
        }
        // Keep existing periodic schedule intact if already registered so reopening the app doesn't reset the timer
        if (scheduler.getPendingJob(BACKGROUND_JOB_ID) != null) {
            return
        }
        val canPersist = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECEIVE_BOOT_COMPLETED
        ) == PackageManager.PERMISSION_GRANTED

        val component = ComponentName(appContext, UpdateCheckJobService::class.java)
        val periodMs = maxOf(JobInfo.getMinPeriodMillis(), 15L * 60L * 1000L)
        val jobInfo = JobInfo.Builder(BACKGROUND_JOB_ID, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(periodMs)
            .setPersisted(canPersist)
            .build()
        runCatching { scheduler.schedule(jobInfo) }
    }

    /**
     * Invoked periodically in the background by [UpdateCheckJobService] even when the app is closed:
     * checks GitHub Releases, pre-downloads & SHA-256 verifies the APK into cache, and posts a
     * 1-tap system notification ready to install.
     */
    fun runBackgroundCheckAndPreDownload(onComplete: () -> Unit) {
        executor.execute {
            try {
                val (installedCode, installedName) = getInstalledVersion()
                val remote = fetchRemoteReleaseInfo()
                if (VersionComparator.isRemoteNewer(
                        installedVersionCode = installedCode,
                        installedVersionName = installedName,
                        remoteVersionCode = remote.versionCode,
                        remoteVersionName = remote.versionName
                    )
                ) {
                    // Pre-download and verify SHA-256 silently so tapping the notification installs immediately
                    runCatching { downloadAndVerifyApkFile(remote, onProgress = null) }
                    postBackgroundUpdateNotification(remote)
                }
            } catch (_: Exception) {
                // Silent retry on next JobScheduler window
            } finally {
                mainHandler.post { onComplete() }
            }
        }
    }

    fun getInstalledVersion(): Pair<Int, String> {
        return try {
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getPackageInfo(
                    appContext.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            }
            val code = pi.longVersionCode.toInt()
            val name = pi.versionName ?: "1.0.0"
            code to name
        } catch (_: Exception) {
            1 to "1.0.0"
        }
    }

    fun checkForUpdates(callback: (UpdateCheckResult) -> Unit) {
        executor.execute {
            val (installedCode, installedName) = getInstalledVersion()
            val result = try {
                val remote = fetchRemoteReleaseInfo()
                if (VersionComparator.isRemoteNewer(
                        installedVersionCode = installedCode,
                        installedVersionName = installedName,
                        remoteVersionCode = remote.versionCode,
                        remoteVersionName = remote.versionName
                    )
                ) {
                    UpdateCheckResult.UpdateAvailable(
                        installedVersionName = installedName,
                        installedVersionCode = installedCode,
                        release = remote
                    )
                } else {
                    UpdateCheckResult.UpToDate(
                        installedVersionName = installedName,
                        installedVersionCode = installedCode
                    )
                }
            } catch (e: Exception) {
                UpdateCheckResult.Error(e.message ?: "Unable to reach GitHub update server")
            }
            mainHandler.post { callback(result) }
        }
    }

    fun downloadAndInstall(
        release: UpdateReleaseInfo,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit,
        onNeedsInstallPermission: () -> Unit,
        onError: (String) -> Unit,
        onInstallerLaunched: () -> Unit
    ) {
        executor.execute {
            try {
                val finalFile = downloadAndVerifyApkFile(release, onProgress)
                mainHandler.post {
                    triggerPackageInstall(
                        apkFile = finalFile,
                        onNeedsInstallPermission = onNeedsInstallPermission,
                        onInstallerLaunched = onInstallerLaunched,
                        onError = onError
                    )
                }
            } catch (e: Exception) {
                mainHandler.post { onError(e.message ?: "Download failed") }
            }
        }
    }

    private fun downloadAndVerifyApkFile(
        release: UpdateReleaseInfo,
        onProgress: ((percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit)?
    ): File {
        val updatesDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
        val tagId = if (release.versionCode > 0) release.versionCode.toString() else release.versionName
        val partFile = File(updatesDir, "ts-police-update.apk.part")
        val finalFile = File(updatesDir, "ts-police-update-$tagId.apk")
        val expectedSha = release.sha256?.trim()?.lowercase()

        // Fast-path: reuse pre-downloaded APK from background job if already SHA-256 verified
        if (finalFile.exists() && finalFile.length() > 100_000L) {
            if (expectedSha.isNullOrEmpty() || expectedSha.length != 64 || sha256OfFile(finalFile) == expectedSha) {
                val len = finalFile.length()
                if (onProgress != null) {
                    mainHandler.post { onProgress(100, len, len) }
                }
                return finalFile
            }
            finalFile.delete()
        }

        // Clean older cached APKs
        updatesDir.listFiles()?.forEach { if (it != finalFile) it.delete() }

        val conn = openFollowingRedirects(release.apkUrl, acceptHeader = "application/octet-stream")
        val totalBytes = conn.contentLengthLong
        val digest = MessageDigest.getInstance("SHA-256")

        conn.inputStream.use { input ->
            FileOutputStream(partFile).use { output ->
                val buf = ByteArray(16 * 1024)
                var downloaded = 0L
                var lastReportedPct = -1
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    output.write(buf, 0, read)
                    digest.update(buf, 0, read)
                    downloaded += read
                    if (onProgress != null) {
                        val pct = if (totalBytes > 0L) {
                            ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                        } else -1
                        if (pct != lastReportedPct) {
                            lastReportedPct = pct
                            val snapBytes = downloaded
                            mainHandler.post { onProgress(pct, snapBytes, totalBytes) }
                        }
                    }
                }
            }
        }
        conn.disconnect()

        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        if (!expectedSha.isNullOrEmpty() && expectedSha.length == 64 && actualSha256 != expectedSha) {
            partFile.delete()
            throw IllegalStateException(
                "APK SHA-256 mismatch (expected ${expectedSha.take(10)}..., got ${actualSha256.take(10)}...)"
            )
        }

        if (finalFile.exists()) finalFile.delete()
        if (!partFile.renameTo(finalFile)) {
            partFile.copyTo(finalFile, overwrite = true)
            partFile.delete()
        }
        return finalFile
    }

    private fun sha256OfFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun postBackgroundUpdateNotification(release: UpdateReleaseInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val versionKey = "${release.versionCode}:${release.versionName}"
        if (prefs.getString(KEY_LAST_NOTIFIED_VERSION, null) == versionKey) {
            return
        }

        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            UPDATE_CHANNEL_ID,
            "TS Police App Updates",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts you when a new TS Police 1.6km Tracker OTA update is downloaded and ready to install"
        }
        nm.createNotificationChannel(channel)

        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_INSTALL_UPDATE_ON_OPEN, true)
        }
        val pending = PendingIntent.getActivity(
            appContext,
            UPDATE_NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, UPDATE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Update v${release.versionName} Ready to Install")
            .setContentText(release.changelog)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${release.changelog}\n\nTap to install now (your run history is preserved)."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        nm.notify(UPDATE_NOTIFICATION_ID, notification)
        prefs.edit().putString(KEY_LAST_NOTIFIED_VERSION, versionKey).apply()
    }

    /**
     * Resumes pending APK installation automatically once the user grants "Allow from this source".
     */
    fun resumePendingInstallIfPermitted(): Boolean {
        val apk = pendingInstallApk ?: return false
        if (!apk.exists()) {
            pendingInstallApk = null
            return false
        }
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            return false
        }
        pendingInstallApk = null
        launchPackageInstallerIntent(apk)
        return true
    }

    fun openUnknownSourcesSettings(activityContext: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activityContext.startActivity(intent)
    }

    private fun triggerPackageInstall(
        apkFile: File,
        onNeedsInstallPermission: () -> Unit,
        onInstallerLaunched: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            if (!appContext.packageManager.canRequestPackageInstalls()) {
                pendingInstallApk = apkFile
                onNeedsInstallPermission()
                return
            }
            pendingInstallApk = null
            launchPackageInstallerIntent(apkFile)
            onInstallerLaunched()
        } catch (e: Exception) {
            onError(e.message ?: "Could not launch Android Package Installer")
        }
    }

    private fun launchPackageInstallerIntent(apkFile: File) {
        val contentUri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(installIntent)
    }

    private fun fetchRemoteReleaseInfo(): UpdateReleaseInfo {
        // 1. Primary: Release asset version.json (no API rate limit, instant updates on new release)
        runCatching {
            val text = downloadText("$RELEASE_MANIFEST_URL?t=${System.currentTimeMillis() / 60000}")
            return parseVersionManifest(text)
        }

        // 2. Fallback: GitHub Releases latest REST API
        runCatching {
            val text = downloadText(GITHUB_LATEST_API_URL, acceptHeader = "application/vnd.github+json")
            return parseGithubApiRelease(text)
        }

        // 3. Fallback: version.json on main branch
        val fallbackText = downloadText("$RAW_MAIN_MANIFEST_URL?t=${System.currentTimeMillis() / 60000}")
        return parseVersionManifest(fallbackText)
    }

    private fun parseVersionManifest(jsonStr: String): UpdateReleaseInfo {
        val obj = JSONObject(jsonStr)
        val code = obj.optInt("versionCode", 0)
        val name = obj.optString("versionName", "").removePrefix("v")
        val url = obj.optString("apkUrl", LATEST_APK_DOWNLOAD_URL).ifBlank { LATEST_APK_DOWNLOAD_URL }
        val sha = obj.optString("sha256", "").ifBlank { null }
        val notes = obj.optString("changelog", "Performance improvements and bug fixes.")
        val pub = obj.optString("publishedAt", "").ifBlank { null }
        if (name.isBlank() && code <= 0) {
            throw IllegalArgumentException("Invalid version.json manifest")
        }
        return UpdateReleaseInfo(
            versionCode = code,
            versionName = name.ifBlank { "0.0.$code" },
            apkUrl = url,
            sha256 = sha,
            changelog = notes,
            publishedAt = pub
        )
    }

    private fun parseGithubApiRelease(jsonStr: String): UpdateReleaseInfo {
        val obj = JSONObject(jsonStr)
        val tag = obj.optString("tag_name", "").removePrefix("v").removePrefix("V")
        val body = obj.optString("body", "New release available.")
        // Optional embedded [versionCode:N] marker in release notes
        val codeMatch = Regex("""\[versionCode:(\d+)]""").find(body)
        val code = codeMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        var apkAssetUrl = LATEST_APK_DOWNLOAD_URL
        val assets = obj.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val aname = asset.optString("name", "")
                if (aname.endsWith(".apk", ignoreCase = true)) {
                    apkAssetUrl = asset.optString("browser_download_url", LATEST_APK_DOWNLOAD_URL)
                    break
                }
            }
        }
        return UpdateReleaseInfo(
            versionCode = code,
            versionName = tag,
            apkUrl = apkAssetUrl,
            sha256 = null,
            changelog = body.replace(Regex("""\[versionCode:\d+]"""), "").trim(),
            publishedAt = obj.optString("published_at", "").ifBlank { null }
        )
    }

    private fun downloadText(urlStr: String, acceptHeader: String = "application/json"): String {
        val conn = openFollowingRedirects(urlStr, acceptHeader = acceptHeader)
        return try {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun openFollowingRedirects(
        initialUrl: String,
        acceptHeader: String,
        maxRedirects: Int = 6
    ): HttpURLConnection {
        var currentUrl = initialUrl
        repeat(maxRedirects) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 12_000
                readTimeout = 25_000
                setRequestProperty("Accept", acceptHeader)
                setRequestProperty("User-Agent", "TSPolice1600mTracker-Android-OTA")
                setRequestProperty("Cache-Control", "no-cache")
            }
            val status = conn.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IllegalStateException("Redirect missing Location header ($status)")
                }
                currentUrl = URL(URL(currentUrl), location).toString()
            } else if (status in 200..299) {
                return conn
            } else {
                conn.disconnect()
                throw IllegalStateException("HTTP $status while fetching update")
            }
        }
        throw IllegalStateException("Too many redirects while fetching update")
    }
}
