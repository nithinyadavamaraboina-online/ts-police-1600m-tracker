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
import android.net.ConnectivityManager
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
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

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
        private const val BACKGROUND_CHECK_PERIOD_MS = 6L * 60L * 60L * 1000L

        private val SHA256_HEX_REGEX = Regex("^[0-9a-f]{64}$")
        private val BODY_SHA256_REGEX = Regex("""SHA-256[:\s`]*([0-9a-fA-F]{64})""", RegexOption.IGNORE_CASE)

        private val SHARED_EXECUTOR = Executors.newSingleThreadExecutor(
            ThreadFactory { r ->
                Thread(r, "AppUpdater-IO").apply { isDaemon = true }
            }
        )
        private val downloadLock = Any()

        fun validateUpdateUrl(urlStr: String) {
            val parsed = runCatching { URL(urlStr) }.getOrElse {
                throw SecurityException("Invalid update URL: $urlStr")
            }
            if (parsed.protocol.lowercase(Locale.US) != "https") {
                throw SecurityException("Refusing non-HTTPS update URL: ${parsed.protocol}")
            }
            val host = parsed.host?.lowercase(Locale.US).orEmpty()
            val isAllowedHost =
                host == "github.com" ||
                    host == "api.github.com" ||
                    host == "raw.githubusercontent.com" ||
                    host == "objects.githubusercontent.com" ||
                    host == "release-assets.githubusercontent.com" ||
                    host.endsWith(".githubusercontent.com") ||
                    host.endsWith(".github.com")
            if (!isAllowedHost) {
                throw SecurityException("Refusing untrusted update host: $host")
            }
        }

        fun parseVersionManifestJson(jsonStr: String): UpdateReleaseInfo {
            val obj = JSONObject(jsonStr)
            val code = obj.optInt("versionCode", 0)
            val name = obj.optString("versionName", "").removePrefix("v").removePrefix("V")
            val url = obj.optString("apkUrl", LATEST_APK_DOWNLOAD_URL).ifBlank { LATEST_APK_DOWNLOAD_URL }
            validateUpdateUrl(url)
            val sha = obj.optString("sha256", "").trim().lowercase(Locale.US).takeIf { SHA256_HEX_REGEX.matches(it) }
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

        fun parseGithubApiReleaseJson(jsonStr: String): UpdateReleaseInfo {
            val obj = JSONObject(jsonStr)
            val tag = obj.optString("tag_name", "").removePrefix("v").removePrefix("V")
            val body = obj.optString("body", "New release available.")
            val codeMatch = Regex("""\[versionCode:(\d+)]""").find(body)
            val code = codeMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            var apkAssetUrl = LATEST_APK_DOWNLOAD_URL
            var assetSha256: String? = null
            val assets = obj.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val aname = asset.optString("name", "")
                    if (aname.endsWith(".apk", ignoreCase = true)) {
                        apkAssetUrl = asset.optString("browser_download_url", LATEST_APK_DOWNLOAD_URL)
                            .ifBlank { LATEST_APK_DOWNLOAD_URL }
                        val digestStr = asset.optString("digest", "").trim()
                            .removePrefix("sha256:")
                            .lowercase(Locale.US)
                        if (SHA256_HEX_REGEX.matches(digestStr)) {
                            assetSha256 = digestStr
                        }
                        break
                    }
                }
            }
            validateUpdateUrl(apkAssetUrl)
            if (assetSha256 == null) {
                val bodySha = BODY_SHA256_REGEX.find(body)?.groupValues?.getOrNull(1)?.lowercase(Locale.US)
                if (bodySha != null && SHA256_HEX_REGEX.matches(bodySha)) {
                    assetSha256 = bodySha
                }
            }
            return UpdateReleaseInfo(
                versionCode = code,
                versionName = tag,
                apkUrl = apkAssetUrl,
                sha256 = assetSha256,
                changelog = body.replace(Regex("""\[versionCode:\d+]"""), "").trim(),
                publishedAt = obj.optString("published_at", "").ifBlank { null }
            )
        }
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = SHARED_EXECUTOR
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
     * (runs every 6 hours whenever network is available, persisted across device reboots).
     */
    fun syncBackgroundUpdateSchedule() {
        val scheduler = appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        if (!autoCheckOnLaunch) {
            scheduler.cancel(BACKGROUND_JOB_ID)
            return
        }
        val periodMs = maxOf(JobInfo.getMinPeriodMillis(), BACKGROUND_CHECK_PERIOD_MS)
        val pendingJob = scheduler.getPendingJob(BACKGROUND_JOB_ID)
        if (pendingJob != null && pendingJob.intervalMillis >= periodMs) {
            return
        }
        val canPersist = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECEIVE_BOOT_COMPLETED
        ) == PackageManager.PERMISSION_GRANTED

        val component = ComponentName(appContext, UpdateCheckJobService::class.java)
        val jobInfo = JobInfo.Builder(BACKGROUND_JOB_ID, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(periodMs)
            .setPersisted(canPersist)
            .build()
        runCatching { scheduler.schedule(jobInfo) }
    }

    /**
     * Invoked periodically in the background by [UpdateCheckJobService]:
     *  - On unmetered Wi-Fi: pre-downloads & SHA-256 verifies the APK into cache, then posts
     *    a "Ready to Install" notification strictly when verification succeeds.
     *  - On metered cellular: avoids burning mobile data on full APK downloads and posts a
     *    lightweight "Update Available" notification so the user can download on tap.
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
                    val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    val isMetered = cm?.isActiveNetworkMetered ?: true
                    if (!isMetered && !remote.sha256.isNullOrBlank()) {
                        runCatching { downloadAndVerifyApkFile(remote, onProgress = null) }
                            .onSuccess { postBackgroundUpdateNotification(remote, preDownloaded = true) }
                            .onFailure { postBackgroundUpdateNotification(remote, preDownloaded = false) }
                    } else {
                        postBackgroundUpdateNotification(remote, preDownloaded = false)
                    }
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
    ): File = synchronized(downloadLock) {
        val expectedSha = release.sha256?.trim()?.lowercase(Locale.US)
        if (expectedSha == null || !SHA256_HEX_REGEX.matches(expectedSha)) {
            throw SecurityException("Release v${release.versionName} is missing a valid SHA-256 checksum; refusing unverified APK install.")
        }
        validateUpdateUrl(release.apkUrl)

        val updatesDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
        val rawTag = if (release.versionCode > 0) release.versionCode.toString() else release.versionName
        val tagId = rawTag.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val partFile = File(updatesDir, "ts-police-update-$tagId.apk.part")
        val finalFile = File(updatesDir, "ts-police-update-$tagId.apk")

        // Fast-path: reuse pre-downloaded APK from background job ONLY if SHA-256 matches
        if (finalFile.exists() && finalFile.length() > 100_000L) {
            if (sha256OfFile(finalFile) == expectedSha) {
                val len = finalFile.length()
                if (onProgress != null) {
                    mainHandler.post { onProgress(100, len, len) }
                }
                return finalFile
            }
            finalFile.delete()
        }

        // Clean older cached APKs
        updatesDir.listFiles()?.forEach { if (it != finalFile && it != partFile) it.delete() }

        val conn = openFollowingRedirects(release.apkUrl, acceptHeader = "application/octet-stream")
        val totalBytes = conn.contentLengthLong
        val digest = MessageDigest.getInstance("SHA-256")

        try {
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
        } finally {
            conn.disconnect()
        }

        val actualSha256 = digest.digest().joinToString("") { "%02x".format(Locale.US, it) }
        if (actualSha256 != expectedSha) {
            partFile.delete()
            throw SecurityException(
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
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it) }
    }

    private fun postBackgroundUpdateNotification(release: UpdateReleaseInfo, preDownloaded: Boolean) {
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
            description = "Alerts you when a new TS Police 1.6km Tracker OTA update is available"
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

        val title = if (preDownloaded) {
            "Update v${release.versionName} Ready to Install"
        } else {
            "Update v${release.versionName} Available"
        }
        val footer = if (preDownloaded) {
            "SHA-256 verified on Wi-Fi. Tap to install now (your run history is preserved)."
        } else {
            "Tap to download & verify v${release.versionName} (your run history is preserved)."
        }

        val notification = NotificationCompat.Builder(appContext, UPDATE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(release.changelog)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${release.changelog}\n\n$footer"))
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

    private fun parseVersionManifest(jsonStr: String): UpdateReleaseInfo = parseVersionManifestJson(jsonStr)

    private fun parseGithubApiRelease(jsonStr: String): UpdateReleaseInfo = parseGithubApiReleaseJson(jsonStr)

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
            validateUpdateUrl(currentUrl)
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
