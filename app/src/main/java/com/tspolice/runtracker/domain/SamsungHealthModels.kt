package com.tspolice.runtracker.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class HealthSyncStatus {
    NOT_INSTALLED,
    PERMISSION_REQUIRED,
    WAITING_FOR_SAMSUNG_SYNC,
    SYNCED,
    ERROR
}

enum class ComparisonDayScope(val tabLabel: String, val titlePrefix: String) {
    TODAY("Today", "Today"),
    YESTERDAY("Yesterday", "Yesterday"),
    TWO_DAY_COMBINED("2-Day Total", "Yesterday + Today")
}

data class ExternalRunWorkout(
    val externalId: String,
    val sourcePackage: String,
    val startedAtEpochMs: Long,
    val dateIso: String,
    val durationSec: Double,
    val distanceMeters: Double,
    val maxSpeedMps: Double,
    val avgCadenceSpm: Double,
    val title: String
)

/**
 * Whole-day ground-truth snapshot synced from Samsung Health (via Android Health Connect).
 */
data class SamsungDailySnapshot(
    val dateIso: String = "",
    val steps: Int = 0,
    val distanceMeters: Double = 0.0,
    val walkingSec: Double = 0.0,
    val jogRunSec: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val maxCadenceSpm: Double = 0.0,
    val sourcePackage: String = SamsungHealthComparator.SAMSUNG_HEALTH_PACKAGE,
    val sourceLabel: String = "Samsung Health",
    val syncedAtEpochMs: Long = 0L,
    val workouts: List<ExternalRunWorkout> = emptyList(),
    val rawHealthConnectDistanceMeters: Double = distanceMeters,
    val distanceIncludedPassiveEstimate: Boolean = false
)

data class AccuracyComparisonReport(
    val stepsDelta: Int,
    val stepsMatchPercent: Double?,
    val distanceDeltaMeters: Double,
    val distanceMatchPercent: Double?,
    val walkSecDelta: Double,
    val jogRunSecDelta: Double,
    val maxSpeedDeltaMps: Double?,
    val maxCadenceDeltaSpm: Double?,
    val trackerStrideM: Double?,
    val samsungStrideM: Double?,
    val overallMatchPercent: Double?,
    val recommendedStrideScale: Double,
    val headline: String,
    val detailAdvice: String
)

/**
 * Compares TS Police Tracker sensor metrics against Samsung Health data (for Today, Yesterday,
 * or 2-Day Combined) and computes a personalized stride-length calibration multiplier.
 */
object SamsungHealthComparator {
    const val SAMSUNG_HEALTH_PACKAGE = "com.sec.android.app.shealth"
    const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"

    const val MIN_STRIDE_SCALE = 0.88
    const val MAX_STRIDE_SCALE = 1.25
    private const val DEFAULT_UNSCALED_WALK_STRIDE_M = 0.77
    private const val DEFAULT_UNSCALED_RUN_STRIDE_M = 1.14

    /**
     * Samsung Health on Android 15/16 writes Today's ongoing daily `StepsRecord` / `DistanceRecord`
     * with a 24-hour span `[00:00 .. 23:59:59.999]` whose `endTime` is in the future (midnight tonight).
     * Clamping future `recordEndMs` to `nowEpochMs` prevents time-slicing logic from discarding Today's
     * already-completed steps into future hours!
     */
    fun effectiveRecordEndEpochMs(
        recordStartMs: Long,
        recordEndMs: Long,
        nowEpochMs: Long
    ): Long {
        if (recordEndMs <= recordStartMs) return recordStartMs + 1L
        return if (recordStartMs <= nowEpochMs && recordEndMs > nowEpochMs + 60_000L) {
            max(recordStartMs + 60_000L, nowEpochMs)
        } else {
            recordEndMs
        }
    }

    fun combineDailySummaries(
        yesterday: DailyActivitySummary,
        today: DailyActivitySummary
    ): DailyActivitySummary {
        return DailyActivitySummary(
            dateIso = "${yesterday.dateIso}..${today.dateIso}",
            walkingSec = yesterday.walkingSec + today.walkingSec,
            joggingSec = yesterday.joggingSec + today.joggingSec,
            runningSec = yesterday.runningSec + today.runningSec,
            walkingMeters = yesterday.walkingMeters + today.walkingMeters,
            joggingMeters = yesterday.joggingMeters + today.joggingMeters,
            runningMeters = yesterday.runningMeters + today.runningMeters,
            totalSteps = yesterday.totalSteps + today.totalSteps,
            maxSpeedMps = max(yesterday.maxSpeedMps, today.maxSpeedMps),
            maxCadenceSpm = max(yesterday.maxCadenceSpm, today.maxCadenceSpm),
            isDemo = yesterday.isDemo && today.isDemo
        )
    }

    fun combineSamsungSnapshots(
        yesterday: SamsungDailySnapshot?,
        today: SamsungDailySnapshot?
    ): SamsungDailySnapshot? {
        if (yesterday == null && today == null) return null
        if (yesterday == null) return today
        if (today == null) return yesterday
        return SamsungDailySnapshot(
            dateIso = "${yesterday.dateIso}..${today.dateIso}",
            steps = yesterday.steps + today.steps,
            distanceMeters = yesterday.distanceMeters + today.distanceMeters,
            walkingSec = yesterday.walkingSec + today.walkingSec,
            jogRunSec = yesterday.jogRunSec + today.jogRunSec,
            maxSpeedMps = max(yesterday.maxSpeedMps, today.maxSpeedMps),
            maxCadenceSpm = max(yesterday.maxCadenceSpm, today.maxCadenceSpm),
            sourcePackage = today.sourcePackage.ifBlank { yesterday.sourcePackage },
            sourceLabel = today.sourceLabel.ifBlank { yesterday.sourceLabel },
            syncedAtEpochMs = max(yesterday.syncedAtEpochMs, today.syncedAtEpochMs),
            workouts = (yesterday.workouts + today.workouts).distinctBy { it.externalId },
            rawHealthConnectDistanceMeters = yesterday.rawHealthConnectDistanceMeters + today.rawHealthConnectDistanceMeters,
            distanceIncludedPassiveEstimate = yesterday.distanceIncludedPassiveEstimate || today.distanceIncludedPassiveEstimate
        )
    }

    fun hasCompleteRawDistanceForCalibration(snap: SamsungDailySnapshot?): Boolean {
        if (snap == null || snap.steps < 250 || snap.rawHealthConnectDistanceMeters < 150.0) return false
        if (snap.distanceIncludedPassiveEstimate) return false
        val rawStride = snap.rawHealthConnectDistanceMeters / snap.steps
        return rawStride in 0.62..1.52
    }

    /**
     * Uses only days whose raw Health Connect DistanceRecord covers the whole day's steps (`0.62..1.52 m/step`),
     * preventing Samsung Health's intra-day workout-only DistanceRecords (e.g. 2.1km walk out of 6,000 steps)
     * from dragging down personal stride calibration! If neither day has complete raw distance and a legacy
     * pre-fix scale was stuck near the old 0.80x floor, heals cleanly back to [fallbackHeightScale].
     */
    fun computeMultiDayCalibratedStrideScale(
        yesterdayTracker: DailyActivitySummary,
        yesterdaySamsung: SamsungDailySnapshot?,
        todayTracker: DailyActivitySummary,
        todaySamsung: SamsungDailySnapshot?,
        currentStrideScale: Double = 1.0,
        fallbackHeightScale: Double = 1.0
    ): Double {
        val yestValid = hasCompleteRawDistanceForCalibration(yesterdaySamsung)
        val todayValid = hasCompleteRawDistanceForCalibration(todaySamsung)

        return when {
            yestValid && todayValid -> {
                val combSam = combineSamsungSnapshots(yesterdaySamsung, todaySamsung)!!
                val combTrk = combineDailySummaries(yesterdayTracker, todayTracker)
                computeCalibratedStrideScale(combTrk, combSam, currentStrideScale)
            }
            yestValid -> computeCalibratedStrideScale(yesterdayTracker, yesterdaySamsung!!, currentStrideScale)
            todayValid -> computeCalibratedStrideScale(todayTracker, todaySamsung!!, currentStrideScale)
            currentStrideScale < 0.89 -> fallbackHeightScale.coerceIn(MIN_STRIDE_SCALE, MAX_STRIDE_SCALE)
            else -> currentStrideScale.coerceIn(MIN_STRIDE_SCALE, MAX_STRIDE_SCALE)
        }
    }

    fun computeCalibratedStrideScale(
        tracker: DailyActivitySummary,
        samsung: SamsungDailySnapshot,
        currentStrideScale: Double = 1.0
    ): Double {
        val clampedCurrent = currentStrideScale.coerceIn(MIN_STRIDE_SCALE, MAX_STRIDE_SCALE)
        val distForCal = if (samsung.distanceIncludedPassiveEstimate) {
            samsung.rawHealthConnectDistanceMeters
        } else {
            samsung.distanceMeters
        }
        if (samsung.steps < 200 || distForCal < 120.0) {
            return clampedCurrent
        }
        val samsungStrideM = distForCal / samsung.steps
        // Reject partial Health Connect distance syncs (< 0.62 m/step is physically impossible for adult gait)
        if (samsungStrideM !in 0.62..1.52) {
            return clampedCurrent
        }

        val samActiveSec = samsung.walkingSec + samsung.jogRunSec
        val samRunShare = if (samActiveSec >= 30.0) {
            (samsung.jogRunSec / samActiveSec).coerceIn(0.0, 1.0)
        } else {
            val trkActive = tracker.walkingSec + tracker.jogRunCombinedSec
            if (trkActive >= 60.0 && samsung.jogRunSec > 0.0) {
                (tracker.jogRunCombinedSec / trkActive).coerceIn(0.0, 1.0)
            } else 0.0
        }

        val unscaledReferenceStrideM =
            (1.0 - samRunShare) * DEFAULT_UNSCALED_WALK_STRIDE_M +
                samRunShare * DEFAULT_UNSCALED_RUN_STRIDE_M

        val targetScale = (samsungStrideM / unscaledReferenceStrideM)
            .coerceIn(MIN_STRIDE_SCALE, MAX_STRIDE_SCALE)

        // Heal previously under-calibrated scales (< 0.85x) immediately rather than dragging them forward
        if (clampedCurrent < 0.85 && targetScale >= 0.90) {
            return targetScale
        }
        return (0.75 * targetScale + 0.25 * clampedCurrent)
            .coerceIn(MIN_STRIDE_SCALE, MAX_STRIDE_SCALE)
    }

    fun compare(
        tracker: DailyActivitySummary,
        samsung: SamsungDailySnapshot,
        currentStrideScale: Double = 1.0,
        dayLabel: String = "today"
    ): AccuracyComparisonReport {
        val stepsDelta = tracker.totalSteps - samsung.steps
        val distDeltaM = tracker.totalDistanceMeters - samsung.distanceMeters
        val walkDeltaSec = tracker.walkingSec - samsung.walkingSec
        val jogRunDeltaSec = tracker.jogRunCombinedSec - samsung.jogRunSec

        val maxSpeedDeltaMps = if (tracker.maxSpeedMps >= 0.4 && samsung.maxSpeedMps >= 0.4) {
            tracker.maxSpeedMps - samsung.maxSpeedMps
        } else null

        val maxCadenceDeltaSpm = if (tracker.maxCadenceSpm >= 60.0 && samsung.maxCadenceSpm >= 60.0) {
            tracker.maxCadenceSpm - samsung.maxCadenceSpm
        } else null

        val stepsMatchPct = if (samsung.steps >= 30 || tracker.totalSteps >= 30) {
            val denom = max(max(samsung.steps, tracker.totalSteps), 1).toDouble()
            (100.0 * (1.0 - abs(stepsDelta) / denom)).coerceIn(0.0, 100.0)
        } else null

        val distMatchPct = if (samsung.distanceMeters >= 25.0 || tracker.totalDistanceMeters >= 25.0) {
            val denom = max(max(samsung.distanceMeters, tracker.totalDistanceMeters), 1.0)
            (100.0 * (1.0 - abs(distDeltaM) / denom)).coerceIn(0.0, 100.0)
        } else null

        val overallPct = when {
            stepsMatchPct != null && distMatchPct != null -> 0.55 * stepsMatchPct + 0.45 * distMatchPct
            stepsMatchPct != null -> stepsMatchPct
            distMatchPct != null -> distMatchPct
            else -> null
        }

        val trackerStrideM = if (tracker.totalSteps >= 80 && tracker.totalDistanceMeters >= 40.0) {
            tracker.totalDistanceMeters / tracker.totalSteps
        } else null

        val samsungStrideM = if (samsung.steps >= 80 && samsung.distanceMeters >= 40.0) {
            samsung.distanceMeters / samsung.steps
        } else null

        val recScale = computeCalibratedStrideScale(tracker, samsung, currentStrideScale)
        val scalePct = ((recScale - 1.0) * 100.0).roundToInt()
        val scaleSign = if (scalePct >= 0) "+$scalePct%" else "$scalePct%"

        val headline = when {
            samsung.steps == 0 && samsung.distanceMeters < 5.0 ->
                "No ${samsung.sourceLabel} steps recorded for $dayLabel"
            overallPct != null && overallPct >= 92.0 ->
                String.format("%.1f%% Accuracy Match vs %s (%s)", overallPct, samsung.sourceLabel, dayLabel)
            samsung.steps - tracker.totalSteps >= 350 ->
                "${samsung.sourceLabel} has +${samsung.steps - tracker.totalSteps} extra steps ($dayLabel)"
            overallPct != null ->
                String.format("%.1f%% Match vs %s (%s)", overallPct, samsung.sourceLabel, dayLabel)
            else ->
                "Synced with ${samsung.sourceLabel} ($dayLabel)"
        }

        val detailAdvice = when {
            samsung.steps == 0 && samsung.distanceMeters < 5.0 ->
                "Open Samsung Health once so it flushes $dayLabel's pedometer & workout records to Health Connect."
            tracker.totalSteps - samsung.steps >= 300 || (tracker.jogRunCombinedSec - samsung.jogRunSec) >= 30.0 ->
                "Pre-update bike/vibration drift detected on $dayLabel — tap 'Align' below to clean $dayLabel to ${samsung.sourceLabel} ground truth."
            samsungStrideM != null ->
                String.format(
                    "%s stride %.2f m/step → Personal stride scale %.2fx (%s). Use 'Align' if you wore a Watch or walked without the app.",
                    samsung.sourceLabel,
                    samsungStrideM,
                    recScale,
                    scaleSign
                )
            else ->
                "Walk 200+ steps with Samsung Health so automatic personal stride calibration locks in."
        }

        return AccuracyComparisonReport(
            stepsDelta = stepsDelta,
            stepsMatchPercent = stepsMatchPct,
            distanceDeltaMeters = distDeltaM,
            distanceMatchPercent = distMatchPct,
            walkSecDelta = walkDeltaSec,
            jogRunSecDelta = jogRunDeltaSec,
            maxSpeedDeltaMps = maxSpeedDeltaMps,
            maxCadenceDeltaSpm = maxCadenceDeltaSpm,
            trackerStrideM = trackerStrideM,
            samsungStrideM = samsungStrideM,
            overallMatchPercent = overallPct,
            recommendedStrideScale = recScale,
            headline = headline,
            detailAdvice = detailAdvice
        )
    }

    /**
     * Generates a clean, structured Yesterday + Today comparison report for viewing or copying.
     */
    fun buildTwoDayReportText(
        yesterdayTracker: DailyActivitySummary,
        yesterdaySamsung: SamsungDailySnapshot?,
        todayTracker: DailyActivitySummary,
        todaySamsung: SamsungDailySnapshot?,
        effectiveStrideScale: Double
    ): String {
        val sb = StringBuilder()
        sb.appendLine("TS POLICE 1.6K vs SAMSUNG HEALTH — YESTERDAY & TODAY REPORT")
        sb.appendLine("Personal Stride Calibration: ${String.format("%.2fx", effectiveStrideScale)}")
        sb.appendLine("===========================================================")

        fun appendDayBlock(title: String, trk: DailyActivitySummary, sam: SamsungDailySnapshot?) {
            sb.appendLine()
            sb.appendLine("[$title • ${trk.dateIso}]")
            if (sam == null) {
                sb.appendLine("  Tracker : ${trk.totalSteps} steps | ${String.format("%.2f km", trk.totalDistanceMeters / 1000.0)} | Walk ${TsPoliceBenchmark.formatDuration(trk.walkingSec)} | Jog+Run ${TsPoliceBenchmark.formatDuration(trk.jogRunCombinedSec)}")
                sb.appendLine("  Samsung : Not synced yet for ${trk.dateIso}")
                return
            }
            val rep = compare(trk, sam, effectiveStrideScale, title.lowercase())
            val matchStr = rep.overallMatchPercent?.let { String.format("%.1f%% Overall Match", it) } ?: "Synced"
            sb.appendLine("  Verdict : $matchStr")
            sb.appendLine(
                String.format(
                    "  Steps   : Tracker %,d  vs  Samsung %,d  (%+d%s)",
                    trk.totalSteps,
                    sam.steps,
                    rep.stepsDelta,
                    rep.stepsMatchPercent?.let { String.format(", %.0f%%", it) } ?: ""
                )
            )
            sb.appendLine(
                String.format(
                    "  Distance: Tracker %.2f km  vs  Samsung %.2f km  (%+d m%s)",
                    trk.totalDistanceMeters / 1000.0,
                    sam.distanceMeters / 1000.0,
                    rep.distanceDeltaMeters.roundToInt(),
                    rep.distanceMatchPercent?.let { String.format(", %.0f%%", it) } ?: ""
                )
            )
            sb.appendLine(
                "  Walk    : Tracker ${TsPoliceBenchmark.formatDuration(trk.walkingSec)}  vs  Samsung ${TsPoliceBenchmark.formatDuration(sam.walkingSec)}"
            )
            sb.appendLine(
                "  Jog+Run : Tracker ${TsPoliceBenchmark.formatDuration(trk.jogRunCombinedSec)}  vs  Samsung ${TsPoliceBenchmark.formatDuration(sam.jogRunSec)}"
            )
            sb.appendLine(
                "  MaxPace : Tracker ${TsPoliceBenchmark.formatPacePerKm(trk.maxSpeedMps)}  vs  Samsung ${TsPoliceBenchmark.formatPacePerKm(sam.maxSpeedMps)}"
            )
            sb.appendLine(
                "  MaxCad  : Tracker ${trk.maxCadenceSpm.roundToInt()} spm  vs  Samsung ${sam.maxCadenceSpm.roundToInt()} spm"
            )
            val trkSt = rep.trackerStrideM?.let { String.format("%.2f m", it) } ?: "--"
            val samSt = rep.samsungStrideM?.let { String.format("%.2f m", it) } ?: "--"
            sb.appendLine("  Stride  : Tracker $trkSt  vs  Samsung $samSt")
            if (sam.workouts.isNotEmpty()) {
                sb.appendLine("  Samsung Runs (${sam.workouts.size}):")
                for (w in sam.workouts) {
                    sb.appendLine("    • ${w.title}: ${String.format("%.2f km", w.distanceMeters / 1000.0)} in ${TsPoliceBenchmark.formatDuration(w.durationSec)}")
                }
            }
        }

        appendDayBlock("YESTERDAY", yesterdayTracker, yesterdaySamsung)
        appendDayBlock("TODAY", todayTracker, todaySamsung)

        val combinedSam = combineSamsungSnapshots(yesterdaySamsung, todaySamsung)
        if (combinedSam != null) {
            val combinedTrk = combineDailySummaries(yesterdayTracker, todayTracker)
            appendDayBlock("2-DAY COMBINED", combinedTrk, combinedSam)
        }
        return sb.toString().trimEnd()
    }
}

/**
 * Per-hour Tracker sensor telemetry bucket (`YYYY-MM-DDTHH`) for deep algorithm fine-tuning.
 */
data class HourlySensorBucket(
    val bucketKey: String,
    val dateIso: String,
    val hourOfDay: Int,
    val hwSteps: Int = 0,
    val fallbackSteps: Int = 0,
    val walkSec: Double = 0.0,
    val jogSec: Double = 0.0,
    val runSec: Double = 0.0,
    val vehicleSec: Double = 0.0,
    val lowPowerSec: Double = 0.0,
    val walkMeters: Double = 0.0,
    val jogRunMeters: Double = 0.0,
    val gpsMeters: Double = 0.0,
    val strideMeters: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val maxCadenceSpm: Double = 0.0,
    val maxAccelRms: Double = 0.0,
    val gpsFixCount: Int = 0,
    val gpsAccSumMeters: Double = 0.0
) {
    val totalSteps: Int get() = hwSteps + fallbackSteps
    val totalDistanceMeters: Double get() = max(walkMeters + jogRunMeters, gpsMeters + strideMeters)
    val avgGpsAccuracyM: Double?
        get() = if (gpsFixCount > 0) gpsAccSumMeters / gpsFixCount else null
}

/**
 * Per-hour Samsung Health ground-truth bucket (`00`..`23`) pulled from Health Connect.
 */
data class SamsungHourlyBucket(
    val dateIso: String,
    val hourOfDay: Int,
    val steps: Int = 0,
    val distanceMeters: Double = 0.0,
    val walkSec: Double = 0.0,
    val jogRunSec: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val maxCadenceSpm: Double = 0.0
)

/**
 * Compact raw DSP feature snapshot recorded on each [MotionState] transition (last 300 kept).
 */
data class SensorTransitionEvent(
    val epochMs: Long,
    val fromState: String,
    val toState: String,
    val gpsSpeedMps: Double?,
    val gpsAccMeters: Double?,
    val cadenceSpm: Double,
    val accelRms: Double,
    val minRawNorm: Double?,
    val peakToTroughNorm: Double?,
    val isLowPower: Boolean,
    val ptPhase: String,
    val p20RawNorm: Double? = null
)
