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
    val workouts: List<ExternalRunWorkout> = emptyList()
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

    const val MIN_STRIDE_SCALE = 0.80
    const val MAX_STRIDE_SCALE = 1.25
    private const val DEFAULT_UNSCALED_WALK_STRIDE_M = 0.77
    private const val DEFAULT_UNSCALED_RUN_STRIDE_M = 1.14

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
            workouts = (yesterday.workouts + today.workouts).distinctBy { it.externalId }
        )
    }

    /**
     * Uses Yesterday + Today combined steps & distance when available so early-morning partial
     * steps never skew stride calibration, while full 24h yesterday ground truth anchors accuracy.
     */
    fun computeMultiDayCalibratedStrideScale(
        yesterdayTracker: DailyActivitySummary,
        yesterdaySamsung: SamsungDailySnapshot?,
        todayTracker: DailyActivitySummary,
        todaySamsung: SamsungDailySnapshot?,
        currentStrideScale: Double = 1.0
    ): Double {
        val combinedSam = combineSamsungSnapshots(yesterdaySamsung, todaySamsung)
            ?: return currentStrideScale.coerceIn(MIN_STRIDE_SCALE, MAX_STRIDE_SCALE)
        val combinedTrk = combineDailySummaries(yesterdayTracker, todayTracker)
        return computeCalibratedStrideScale(combinedTrk, combinedSam, currentStrideScale)
    }

    fun computeCalibratedStrideScale(
        tracker: DailyActivitySummary,
        samsung: SamsungDailySnapshot,
        currentStrideScale: Double = 1.0
    ): Double {
        val clampedCurrent = currentStrideScale.coerceIn(MIN_STRIDE_SCALE, MAX_STRIDE_SCALE)
        if (samsung.steps < 200 || samsung.distanceMeters < 110.0) {
            return clampedCurrent
        }
        val samsungStrideM = samsung.distanceMeters / samsung.steps
        if (samsungStrideM !in 0.52..1.60) {
            return clampedCurrent
        }

        val samActiveSec = samsung.walkingSec + samsung.jogRunSec
        val samRunShare = if (samActiveSec >= 30.0) {
            (samsung.jogRunSec / samActiveSec).coerceIn(0.0, 1.0)
        } else 0.0

        val trkActiveSec = tracker.walkingSec + tracker.jogRunCombinedSec
        val trkRunShare = if (trkActiveSec >= 30.0) {
            (tracker.jogRunCombinedSec / trkActiveSec).coerceIn(0.0, 1.0)
        } else 0.0

        val unscaledReferenceStrideM = if (
            tracker.totalSteps >= 200 &&
            tracker.totalDistanceMeters >= 100.0 &&
            abs(trkRunShare - samRunShare) <= 0.25
        ) {
            val currentTrackerStride = tracker.totalDistanceMeters / tracker.totalSteps
            (currentTrackerStride / clampedCurrent).coerceIn(0.55, 1.45)
        } else {
            (1.0 - samRunShare) * DEFAULT_UNSCALED_WALK_STRIDE_M +
                samRunShare * DEFAULT_UNSCALED_RUN_STRIDE_M
        }

        val targetScale = (samsungStrideM / unscaledReferenceStrideM)
            .coerceIn(MIN_STRIDE_SCALE, MAX_STRIDE_SCALE)
        return (0.65 * targetScale + 0.35 * clampedCurrent)
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
