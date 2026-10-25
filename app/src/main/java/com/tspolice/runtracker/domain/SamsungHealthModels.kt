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
    val trackerStrideM: Double?,
    val samsungStrideM: Double?,
    val overallMatchPercent: Double?,
    val recommendedStrideScale: Double,
    val headline: String,
    val detailAdvice: String
)

/**
 * Compares TS Police Tracker sensor metrics against Samsung Health data and computes a personalized
 * stride-length calibration multiplier so live indoor/pocket pace & distance match the runner's exact stride.
 */
object SamsungHealthComparator {
    const val SAMSUNG_HEALTH_PACKAGE = "com.sec.android.app.shealth"
    const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"

    const val MIN_STRIDE_SCALE = 0.80
    const val MAX_STRIDE_SCALE = 1.25
    private const val DEFAULT_UNSCALED_WALK_STRIDE_M = 0.77
    private const val DEFAULT_UNSCALED_RUN_STRIDE_M = 1.14

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
        currentStrideScale: Double = 1.0
    ): AccuracyComparisonReport {
        val stepsDelta = tracker.totalSteps - samsung.steps
        val distDeltaM = tracker.totalDistanceMeters - samsung.distanceMeters
        val walkDeltaSec = tracker.walkingSec - samsung.walkingSec
        val jogRunDeltaSec = tracker.jogRunCombinedSec - samsung.jogRunSec

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
                "Waiting for ${samsung.sourceLabel} steps today"
            overallPct != null && overallPct >= 92.0 ->
                String.format("%.1f%% Accuracy Match vs %s", overallPct, samsung.sourceLabel)
            samsung.steps - tracker.totalSteps >= 350 ->
                "${samsung.sourceLabel} has +${samsung.steps - tracker.totalSteps} extra pre-app / Watch steps"
            overallPct != null ->
                String.format("%.1f%% Match vs %s • Auto-Calibrating Stride", overallPct, samsung.sourceLabel)
            else ->
                "Synced with ${samsung.sourceLabel}"
        }

        val detailAdvice = when {
            samsung.steps == 0 && samsung.distanceMeters < 5.0 ->
                "Open Samsung Health once so it flushes today's latest pedometer batch to Health Connect."
            samsungStrideM != null ->
                String.format(
                    "%s stride %.2f m/step → Personal stride scale %.2fx (%s). Tap 'Align Today' if you wore a Watch or walked before installing.",
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
            trackerStrideM = trackerStrideM,
            samsungStrideM = samsungStrideM,
            overallMatchPercent = overallPct,
            recommendedStrideScale = recScale,
            headline = headline,
            detailAdvice = detailAdvice
        )
    }
}
