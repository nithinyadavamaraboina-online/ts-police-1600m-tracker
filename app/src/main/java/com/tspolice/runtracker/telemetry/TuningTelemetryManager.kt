package com.tspolice.runtracker.telemetry

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import com.tspolice.runtracker.data.RunRepository
import com.tspolice.runtracker.domain.DailyActivitySummary
import com.tspolice.runtracker.domain.HourlySensorBucket
import com.tspolice.runtracker.domain.SamsungDailySnapshot
import com.tspolice.runtracker.domain.SamsungHealthComparator
import com.tspolice.runtracker.domain.SamsungHourlyBucket
import com.tspolice.runtracker.domain.TsPoliceBenchmark
import com.tspolice.runtracker.health.DeepSamsungTuningDataset
import com.tspolice.runtracker.health.SamsungHealthSyncManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

data class TuningPullAndUploadResult(
    val uploadedToCloud: Boolean,
    val syncCode: String,
    val downloadUrl: String,
    val localFilePath: String,
    val headline: String,
    val diagnosticsSummaryText: String,
    val rawJson: String,
    val deepSamsung: DeepSamsungTuningDataset
)

/**
 * Collects a complete, anonymous multi-day sensor + Samsung Health fine-tuning telemetry dataset
 * (7-day daily totals, 48-hour Yesterday+Today intraday hourly buckets, hardware sensor FIFO specs,
 * and last 300 raw DSP gait transitions) and uploads it in 1 tap so we can pull & fine-tune the app.
 */
class TuningTelemetryManager(
    private val context: Context,
    private val repository: RunRepository,
    private val healthSyncManager: SamsungHealthSyncManager
) {
    companion object {
        const val CLOUD_TELEMETRY_CHANNEL = "tspolice-tune-9f4c2e7b18a6d305e419"
        private const val UPLOAD_ENDPOINT = "https://ntfy.sh/$CLOUD_TELEMETRY_CHANNEL"
        const val LOCAL_EXPORT_FILENAME = "ts_police_tuning_latest.json"
    }

    suspend fun pullBuildAndUploadTuningBundle(
        liveTodayOverride: DailyActivitySummary? = null
    ): TuningPullAndUploadResult {
        val deepSamsung = healthSyncManager.pullFineTuningDataset()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)
        val todayIso = today.toString()
        val yesterdayIso = yesterday.toString()

        val todayTracker = liveTodayOverride?.takeIf { it.dateIso == todayIso }
            ?: repository.getDailyActivity(todayIso)
        val yesterdayTracker = repository.getDailyActivity(yesterdayIso)
        val todaySamsung = deepSamsung.syncOutcome.snapshot ?: repository.getCachedSamsungSnapshot(todayIso)
        val yesterdaySamsung = deepSamsung.syncOutcome.yesterdaySnapshot ?: repository.getCachedSamsungSnapshot(yesterdayIso)

        val hourlyTrackerMap = repository.getHourlySensorBucketsForDates(listOf(yesterdayIso, todayIso))
        val recentTransitions = repository.getRecentSensorTransitions(300)
        val allCachedSamsung = repository.getAllCachedSamsungSnapshots()

        val pkgInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val appVersionName = pkgInfo?.versionName ?: "unknown"
        val appVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo?.longVersionCode ?: 0L
        } else {
            @Suppress("DEPRECATION")
            pkgInfo?.versionCode?.toLong() ?: 0L
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryUnrestricted = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false

        // Compute actionable auto-tuning insights from the 48-hour dataset
        val diagInsights = buildAutoTuningInsights(
            yesterdayTracker = yesterdayTracker,
            yesterdaySamsung = yesterdaySamsung,
            todayTracker = todayTracker,
            todaySamsung = todaySamsung,
            hourlyTracker = hourlyTrackerMap,
            hourlySamsung = deepSamsung.intradayHourlyBuckets,
            batteryUnrestricted = batteryUnrestricted,
            strideScale = repository.effectiveStrideScale()
        )

        val root = JSONObject()
        root.put("schemaVersion", 2)
        root.put("generatedAtIso", Instant.now().toString())
        root.put("localTimezone", zone.id)
        root.put("todayIso", todayIso)
        root.put("yesterdayIso", yesterdayIso)

        // 1. Device, OS, App & Hardware Sensor Profile
        root.put("deviceProfile", buildDeviceAndSensorProfileJson(appVersionName, appVersionCode, batteryUnrestricted, deepSamsung))

        // 2. Runner & Classifier Configuration
        root.put(
            "classifierConfig",
            JSONObject().apply {
                put("targetTimeSec", repository.targetTimeSec)
                put("runningThresholdMps", repository.runningThresholdMps)
                put("runnerHeightCm", repository.runnerHeightCm)
                put("rawSamsungStrideScale", repository.strideCalibrationScale)
                put("hasSamsungStrideCalibration", repository.hasSamsungStrideCalibration)
                put("effectiveStrideScale", repository.effectiveStrideScale())
                put("pocketAudioMode", repository.pocketAudioMode.name)
                put("allDayTrackingEnabled", repository.allDayTrackingEnabled)
                put("indoorStepFallbackEnabled", repository.indoorStepFallbackEnabled)
                put("autoCalibrateFromSamsung", repository.autoCalibrateFromSamsung)
                put("autoImportSamsungRuns", repository.autoImportSamsungRuns)
            }
        )

        // 3. Auto-Tuning Diagnostic Summary
        root.put("autoTuningDiagnostics", diagInsights.first)

        // 4. Yesterday + Today + 2-Day Combined Summary
        val twoDayObj = JSONObject().apply {
            put("yesterday", encodeDayPairJson(yesterdayIso, yesterdayTracker, yesterdaySamsung))
            put("today", encodeDayPairJson(todayIso, todayTracker, todaySamsung))
            val combTrk = SamsungHealthComparator.combineDailySummaries(yesterdayTracker, todayTracker)
            val combSam = SamsungHealthComparator.combineSamsungSnapshots(yesterdaySamsung, todaySamsung)
            put("twoDayCombined", encodeDayPairJson("$yesterdayIso..$todayIso", combTrk, combSam))
        }
        root.put("twoDayComparison", twoDayObj)

        // 5. 48-Hour Intraday Hourly Comparison (Yesterday 00..23 + Today 00..23)
        val intradayArr = JSONArray()
        for (dIso in listOf(yesterdayIso, todayIso)) {
            for (hr in 0..23) {
                val bKey = String.format("%sT%02d", dIso, hr)
                val trkH = hourlyTrackerMap[bKey]
                val samH = deepSamsung.intradayHourlyBuckets[bKey]
                if (trkH != null || (samH != null && (samH.steps > 0 || samH.distanceMeters > 1.0))) {
                    intradayArr.put(encodeHourlyComparisonRow(bKey, dIso, hr, trkH, samH))
                }
            }
        }
        root.put("intradayHourlyComparison", intradayArr)

        // 6. 7-Day Daily Totals (Tracker vs Samsung Health)
        val sevenDayArr = JSONArray()
        for (daysAgo in 6 downTo 0) {
            val dIso = today.minusDays(daysAgo.toLong()).toString()
            val dTrk = if (dIso == todayIso) todayTracker else repository.getDailyActivity(dIso)
            val dSam = deepSamsung.sevenDaySnapshots[dIso] ?: allCachedSamsung[dIso]
            sevenDayArr.put(encodeDayPairJson(dIso, dTrk, dSam))
        }
        root.put("sevenDayComparison", sevenDayArr)

        // 7. Recent Real Run Sessions + 400m Splits
        val runsArr = JSONArray()
        for (s in repository.getAllSessions().filter { !it.isDemo }.take(30)) {
            val splitsArr = JSONArray()
            for (sp in s.lapSplits) {
                splitsArr.put(
                    JSONObject().apply {
                        put("lap", sp.lapNumber)
                        put("distM", sp.distanceMeters)
                        put("lapSec", sp.lapDurationSec)
                        put("cumSec", sp.cumulativeSec)
                        put("state", sp.dominantState.name)
                    }
                )
            }
            runsArr.put(
                JSONObject().apply {
                    put("dateIso", s.dateIso)
                    put("startedAtEpochMs", s.startedAtEpochMs)
                    put("mode", s.mode.name)
                    put("distanceMeters", s.distanceMeters)
                    put("activeDurationSec", s.activeDurationSec)
                    put("pausedDurationSec", s.pausedDurationSec)
                    if (s.first1600mSplitSec != null) put("first1600mSplitSec", s.first1600mSplitSec)
                    put("equiv1600mSec", s.equiv1600mSec)
                    put("avgCadenceSpm", s.avgCadenceSpm)
                    put("maxSpeedMps", s.maxSpeedMps)
                    put("runningSec", s.runningSec)
                    put("joggingSec", s.joggingSec)
                    put("walkingSec", s.walkingSec)
                    put("notes", s.notes)
                    put("lapSplits", splitsArr)
                }
            )
        }
        root.put("recentRunSessions", runsArr)

        // 8. Last 300 Raw DSP Gait State Transitions
        val transArr = JSONArray()
        for (ev in recentTransitions) {
            transArr.put(
                JSONObject().apply {
                    put("epochMs", ev.epochMs)
                    put("localTime", Instant.ofEpochMilli(ev.epochMs).atZone(zone).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    put("from", ev.fromState)
                    put("to", ev.toState)
                    if (ev.gpsSpeedMps != null) put("gpsSpeedMps", round3(ev.gpsSpeedMps))
                    if (ev.gpsAccMeters != null) put("gpsAccM", round2(ev.gpsAccMeters))
                    put("cadenceSpm", round2(ev.cadenceSpm))
                    put("accelRms", round3(ev.accelRms))
                    if (ev.minRawNorm != null) put("minRawNorm", round3(ev.minRawNorm))
                    if (ev.peakToTroughNorm != null) put("peakToTrough", round3(ev.peakToTroughNorm))
                    put("lowPower", ev.isLowPower)
                    put("ptPhase", ev.ptPhase)
                }
            )
        }
        root.put("recentStateTransitions", transArr)

        val jsonText = root.toString(2)

        // Save local file copy for instant ADB pull fallback
        val outDir = context.getExternalFilesDir(null) ?: context.filesDir
        val outFile = File(outDir, LOCAL_EXPORT_FILENAME)
        runCatching { outFile.writeText(jsonText, Charsets.UTF_8) }

        // Upload to cloud fine-tuning channel
        val uploadPair = uploadBundleToCloud(jsonText, todayIso)
        val nowClock = Instant.now().atZone(zone).format(DateTimeFormatter.ofPattern("dd MMM HH:mm"))

        return if (uploadPair != null) {
            val (code, url) = uploadPair
            val summaryLine = "Synced $nowClock  •  Code: $code"
            repository.lastTuningSyncSummary = summaryLine
            TuningPullAndUploadResult(
                uploadedToCloud = true,
                syncCode = code,
                downloadUrl = url,
                localFilePath = outFile.absolutePath,
                headline = "Tuning Data Uploaded! (Code: $code)",
                diagnosticsSummaryText = diagInsights.second,
                rawJson = jsonText,
                deepSamsung = deepSamsung
            )
        } else {
            val summaryLine = "Saved locally $nowClock (Offline — tap Share JSON)"
            repository.lastTuningSyncSummary = summaryLine
            TuningPullAndUploadResult(
                uploadedToCloud = false,
                syncCode = "LOCAL",
                downloadUrl = "",
                localFilePath = outFile.absolutePath,
                headline = "Tuning Bundle Ready (Saved Locally)",
                diagnosticsSummaryText = diagInsights.second,
                rawJson = jsonText,
                deepSamsung = deepSamsung
            )
        }
    }

    private fun uploadBundleToCloud(jsonPayload: String, dateIso: String): Pair<String, String>? {
        return runCatching {
            val bytes = jsonPayload.toByteArray(Charsets.UTF_8)
            val conn = (URL(UPLOAD_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                connectTimeout = 12_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Filename", "ts-police-tuning-$dateIso.json")
                setRequestProperty("Title", "TS Police 1.6K Fine-Tuning Snapshot ($dateIso)")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setFixedLengthStreamingMode(bytes.size)
            }
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return null
            }
            val respBody = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            val obj = JSONObject(respBody)
            val id = obj.optString("id", "")
            val attUrl = obj.optJSONObject("attachment")?.optString("url", "")
                ?: if (id.isNotBlank()) "https://ntfy.sh/file/$id.json" else ""
            if (id.isBlank()) null else (id to attUrl)
        }.getOrNull()
    }

    private fun buildDeviceAndSensorProfileJson(
        appVersionName: String,
        appVersionCode: Long,
        batteryUnrestricted: Boolean,
        deepSamsung: DeepSamsungTuningDataset
    ): JSONObject {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        fun sensorJson(type: Int): JSONObject? {
            val s = sm?.getDefaultSensor(type) ?: return null
            return JSONObject().apply {
                put("name", s.name)
                put("vendor", s.vendor)
                put("fifoMaxEventCount", s.fifoMaxEventCount)
                put("fifoReservedEventCount", s.fifoReservedEventCount)
                put("minDelayUs", s.minDelay)
                put("maxDelayUs", s.maxDelay)
                put("isWakeUpSensor", s.isWakeUpSensor)
                put("powerMa", s.power.toDouble())
            }
        }
        return JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("brand", Build.BRAND)
            put("model", Build.MODEL)
            put("androidRelease", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("appVersionName", appVersionName)
            put("appVersionCode", appVersionCode)
            put("batteryUnrestricted", batteryUnrestricted)
            put("samsungHealthInstalled", healthSyncManager.isSamsungHealthInstalled())
            put("healthSyncStatus", deepSamsung.syncOutcome.status.name)
            put("grantedHealthPermissions", JSONArray(deepSamsung.grantedPermissions))
            put("samsung48hRawStepsRecords", deepSamsung.rawStepsRecordCount48h)
            put("samsung48hRawDistanceRecords", deepSamsung.rawDistanceRecordCount48h)
            put("samsung48hRawExerciseRecords", deepSamsung.rawExerciseRecordCount48h)
            put("samsungAvgStepBucketMinutes", round2(deepSamsung.avgStepBucketMinutes))
            sensorJson(Sensor.TYPE_STEP_DETECTOR)?.let { put("stepDetectorSensor", it) }
            sensorJson(Sensor.TYPE_STEP_COUNTER)?.let { put("stepCounterSensor", it) }
            sensorJson(Sensor.TYPE_ACCELEROMETER)?.let { put("accelerometerSensor", it) }
            sensorJson(Sensor.TYPE_LINEAR_ACCELERATION)?.let { put("linearAccelSensor", it) }
            sensorJson(Sensor.TYPE_SIGNIFICANT_MOTION)?.let { put("significantMotionSensor", it) }
        }
    }

    private fun buildAutoTuningInsights(
        yesterdayTracker: DailyActivitySummary,
        yesterdaySamsung: SamsungDailySnapshot?,
        todayTracker: DailyActivitySummary,
        todaySamsung: SamsungDailySnapshot?,
        hourlyTracker: Map<String, HourlySensorBucket>,
        hourlySamsung: Map<String, SamsungHourlyBucket>,
        batteryUnrestricted: Boolean,
        strideScale: Double
    ): Pair<JSONObject, String> {
        val combTrk = SamsungHealthComparator.combineDailySummaries(yesterdayTracker, todayTracker)
        val combSam = SamsungHealthComparator.combineSamsungSnapshots(yesterdaySamsung, todaySamsung)

        val totalHwSteps = hourlyTracker.values.sumOf { it.hwSteps }
        val totalSwSteps = hourlyTracker.values.sumOf { it.fallbackSteps }
        val totalGpsM = hourlyTracker.values.sumOf { it.gpsMeters }
        val totalStrideM = hourlyTracker.values.sumOf { it.strideMeters }
        val totalVehicleSec = hourlyTracker.values.sumOf { it.vehicleSec }

        // Separate pure walk hours vs jog/run hours from Samsung Health to estimate Walk vs Run stride separately!
        var samPureWalkSteps = 0
        var samPureWalkDistM = 0.0
        var samRunHourSteps = 0
        var samRunHourDistM = 0.0
        for (sh in hourlySamsung.values) {
            if (sh.steps >= 80 && sh.distanceMeters >= 45.0) {
                if (sh.jogRunSec >= 60.0) {
                    samRunHourSteps += sh.steps
                    samRunHourDistM += sh.distanceMeters
                } else {
                    samPureWalkSteps += sh.steps
                    samPureWalkDistM += sh.distanceMeters
                }
            }
        }
        val observedWalkStrideM = if (samPureWalkSteps >= 150) samPureWalkDistM / samPureWalkSteps else null
        val observedRunStrideM = if (samRunHourSteps >= 150) samRunHourDistM / samRunHourSteps else null

        val recommendations = mutableListOf<String>()
        if (!batteryUnrestricted) {
            recommendations.add("Battery Optimization is RESTRICTED — enable Unrestricted Battery so OneUI never pauses pocket step detection.")
        }
        if (combSam == null || (combSam.steps == 0 && combSam.distanceMeters < 5.0)) {
            recommendations.add("Samsung Health returned 0 steps via Health Connect — open Samsung Health once to flush pedometer records.")
        } else {
            val stepDiff = combTrk.totalSteps - combSam.steps
            val denom = maxOf(combSam.steps, combTrk.totalSteps, 1)
            val matchPct = 100.0 * (1.0 - abs(stepDiff).toDouble() / denom)
            if (matchPct >= 93.0) {
                recommendations.add(String.format("2-Day Step Accuracy is strong (%.1f%% match: %,d vs %,d steps).", matchPct, combTrk.totalSteps, combSam.steps))
            } else if (stepDiff < -300) {
                recommendations.add("Samsung has ${-stepDiff} more steps over 2 days (likely Watch / pre-app walking or background sleep) — use Align or check Unrestricted Battery.")
            } else if (stepDiff > 300) {
                recommendations.add("Tracker counted +$stepDiff extra steps over 2 days — software peak threshold can be tightened slightly from this log.")
            }
        }
        if (observedWalkStrideM != null) {
            recommendations.add(String.format("Observed Samsung walk stride: %.2f m/step (current scale %.2fx).", observedWalkStrideM, strideScale))
        }
        if (observedRunStrideM != null) {
            recommendations.add(String.format("Observed Samsung run-hour stride: %.2f m/step.", observedRunStrideM))
        }
        if (totalVehicleSec >= 15.0) {
            recommendations.add("Bike/Vehicle filter cleanly rejected ${TsPoliceBenchmark.formatDuration(totalVehicleSec)} of non-foot travel.")
        }

        val obj = JSONObject().apply {
            put("twoDayTrackerSteps", combTrk.totalSteps)
            put("twoDaySamsungSteps", combSam?.steps ?: 0)
            put("twoDayTrackerDistanceM", round2(combTrk.totalDistanceMeters))
            put("twoDaySamsungDistanceM", round2(combSam?.distanceMeters ?: 0.0))
            put("hourlyLoggedHwSteps", totalHwSteps)
            put("hourlyLoggedFallbackSteps", totalSwSteps)
            put("hourlyLoggedGpsMeters", round2(totalGpsM))
            put("hourlyLoggedStrideMeters", round2(totalStrideM))
            put("hourlyVehicleIgnoredSec", round2(totalVehicleSec))
            if (observedWalkStrideM != null) put("observedSamsungWalkStrideM", round3(observedWalkStrideM))
            if (observedRunStrideM != null) put("observedSamsungRunHourStrideM", round3(observedRunStrideM))
            put("recommendations", JSONArray(recommendations))
        }

        val humanSummary = buildString {
            appendLine("• 2-Day Steps: Tracker ${combTrk.totalSteps} vs Samsung ${combSam?.steps ?: 0}")
            appendLine(
                String.format(
                    "• 2-Day Distance: Tracker %.2f km vs Samsung %.2f km",
                    combTrk.totalDistanceMeters / 1000.0,
                    (combSam?.distanceMeters ?: 0.0) / 1000.0
                )
            )
            appendLine("• Pedometer Split (logged hrs): HW $totalHwSteps / Accel-Fallback $totalSwSteps")
            for (r in recommendations) {
                appendLine("• $r")
            }
        }.trimEnd()

        return obj to humanSummary
    }

    private fun encodeDayPairJson(
        labelIso: String,
        trk: DailyActivitySummary,
        sam: SamsungDailySnapshot?
    ): JSONObject {
        val rep = sam?.let { SamsungHealthComparator.compare(trk, it, repository.effectiveStrideScale(), labelIso) }
        return JSONObject().apply {
            put("dateIso", labelIso)
            put(
                "tracker",
                JSONObject().apply {
                    put("steps", trk.totalSteps)
                    put("distanceMeters", round2(trk.totalDistanceMeters))
                    put("walkingMeters", round2(trk.walkingMeters))
                    put("joggingMeters", round2(trk.joggingMeters))
                    put("runningMeters", round2(trk.runningMeters))
                    put("walkingSec", round2(trk.walkingSec))
                    put("joggingSec", round2(trk.joggingSec))
                    put("runningSec", round2(trk.runningSec))
                    put("jogRunCombinedSec", round2(trk.jogRunCombinedSec))
                    put("maxSpeedMps", round3(trk.maxSpeedMps))
                    put("maxCadenceSpm", round2(trk.maxCadenceSpm))
                }
            )
            if (sam != null) {
                put(
                    "samsung",
                    JSONObject().apply {
                        put("sourceLabel", sam.sourceLabel)
                        put("steps", sam.steps)
                        put("distanceMeters", round2(sam.distanceMeters))
                        put("walkingSec", round2(sam.walkingSec))
                        put("jogRunSec", round2(sam.jogRunSec))
                        put("maxSpeedMps", round3(sam.maxSpeedMps))
                        put("maxCadenceSpm", round2(sam.maxCadenceSpm))
                        put("workoutCount", sam.workouts.size)
                    }
                )
            }
            if (rep != null) {
                put(
                    "comparison",
                    JSONObject().apply {
                        put("stepsDelta", rep.stepsDelta)
                        rep.stepsMatchPercent?.let { put("stepsMatchPercent", round2(it)) }
                        put("distanceDeltaMeters", round2(rep.distanceDeltaMeters))
                        rep.distanceMatchPercent?.let { put("distanceMatchPercent", round2(it)) }
                        rep.overallMatchPercent?.let { put("overallMatchPercent", round2(it)) }
                        rep.trackerStrideM?.let { put("trackerStrideM", round3(it)) }
                        rep.samsungStrideM?.let { put("samsungStrideM", round3(it)) }
                        put("recommendedStrideScale", round3(rep.recommendedStrideScale))
                    }
                )
            }
        }
    }

    private fun encodeHourlyComparisonRow(
        bucketKey: String,
        dateIso: String,
        hour: Int,
        trk: HourlySensorBucket?,
        sam: SamsungHourlyBucket?
    ): JSONObject {
        return JSONObject().apply {
            put("bucketKey", bucketKey)
            put("dateIso", dateIso)
            put("hour", hour)
            if (trk != null) {
                put(
                    "tracker",
                    JSONObject().apply {
                        put("totalSteps", trk.totalSteps)
                        put("hwSteps", trk.hwSteps)
                        put("fallbackSteps", trk.fallbackSteps)
                        put("distanceMeters", round2(trk.totalDistanceMeters))
                        put("gpsMeters", round2(trk.gpsMeters))
                        put("strideMeters", round2(trk.strideMeters))
                        put("walkSec", round2(trk.walkSec))
                        put("jogSec", round2(trk.jogSec))
                        put("runSec", round2(trk.runSec))
                        put("vehicleSec", round2(trk.vehicleSec))
                        put("lowPowerSec", round2(trk.lowPowerSec))
                        put("maxSpeedMps", round3(trk.maxSpeedMps))
                        put("maxCadenceSpm", round2(trk.maxCadenceSpm))
                        put("maxAccelRms", round3(trk.maxAccelRms))
                        trk.avgGpsAccuracyM?.let { put("avgGpsAccM", round2(it)) }
                    }
                )
            }
            if (sam != null) {
                put(
                    "samsung",
                    JSONObject().apply {
                        put("steps", sam.steps)
                        put("distanceMeters", round2(sam.distanceMeters))
                        put("walkSec", round2(sam.walkSec))
                        put("jogRunSec", round2(sam.jogRunSec))
                        put("maxSpeedMps", round3(sam.maxSpeedMps))
                        put("maxCadenceSpm", round2(sam.maxCadenceSpm))
                    }
                )
            }
            if (trk != null && sam != null) {
                put("stepsDelta", trk.totalSteps - sam.steps)
                put("distanceDeltaM", (trk.totalDistanceMeters - sam.distanceMeters).roundToInt())
            }
        }
    }

    private fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0
    private fun round3(v: Double): Double = kotlin.math.round(v * 1000.0) / 1000.0
}
