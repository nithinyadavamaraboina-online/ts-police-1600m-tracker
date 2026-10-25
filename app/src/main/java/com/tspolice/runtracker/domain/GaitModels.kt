package com.tspolice.runtracker.domain

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Automatically detected movement state from fused GPS speed, step cadence, and 3-axis acceleration.
 * Eliminates manual sport selection (Standing / Walking / Jogging / Running / Target Pace).
 */
enum class MotionState(
    val label: String,
    val badgeText: String,
    val colorArgb: Int,
    val countsAsActiveMovement: Boolean
) {
    STANDING(
        label = "Standing (Auto-Pause)",
        badgeText = "AUTO-PAUSED • STANDING",
        colorArgb = 0xFF64748B.toInt(),
        countsAsActiveMovement = false
    ),
    WALKING(
        label = "Walking",
        badgeText = "AUTO-DETECTED • WALKING",
        colorArgb = 0xFF38BDF8.toInt(),
        countsAsActiveMovement = true
    ),
    JOGGING(
        label = "Jogging",
        badgeText = "AUTO-DETECTED • JOGGING",
        colorArgb = 0xFFF59E0B.toInt(),
        countsAsActiveMovement = true
    ),
    RUNNING(
        label = "Running",
        badgeText = "AUTO-DETECTED • RUNNING",
        colorArgb = 0xFF22C55E.toInt(),
        countsAsActiveMovement = true
    ),
    TARGET_PACE(
        label = "TS Police Target Pace",
        badgeText = "TS POLICE 7:15 PACE • RUNNING",
        colorArgb = 0xFF10B981.toInt(),
        countsAsActiveMovement = true
    )
}

enum class RunMode(val title: String, val subtitle: String) {
    TIMED_1600M(
        title = "1.6 km TS Police Trial",
        subtitle = "Auto-finishes at 1,600m • 400m track splits vs 7:15"
    ),
    OPEN_PRACTICE(
        title = "Open Practice Run",
        subtitle = "Unlimited distance • Auto-detects Jog vs Run + 1.6km equiv"
    ),
    MANUAL_LOG(
        title = "Manual Stopwatch Entry",
        subtitle = "Logged from track stopwatch"
    )
}

data class LapSplit(
    val lapNumber: Int,
    val distanceMeters: Double,
    val lapDurationSec: Double,
    val cumulativeSec: Double,
    val dominantState: MotionState
)

data class LiveTelemetry(
    val isRecording: Boolean = false,
    val isAutoStartArmed: Boolean = false,
    val mode: RunMode = RunMode.TIMED_1600M,
    val motionState: MotionState = MotionState.STANDING,
    val distanceMeters: Double = 0.0,
    val activeDurationSec: Double = 0.0,
    val pausedDurationSec: Double = 0.0,
    val currentSpeedMps: Double = 0.0,
    val cadenceSpm: Double = 0.0,
    val dynamicAccelRms: Double = 0.0,
    val totalSteps: Int = 0,
    val gpsAccuracyMeters: Float? = null,
    val isGpsLocked: Boolean = false,
    val hasHardwareStepSensor: Boolean = false,
    val runningSec: Double = 0.0,
    val joggingSec: Double = 0.0,
    val walkingSec: Double = 0.0,
    val runningMeters: Double = 0.0,
    val joggingMeters: Double = 0.0,
    val walkingMeters: Double = 0.0,
    val first1600mSplitSec: Double? = null,
    val lapSplits: List<LapSplit> = emptyList(),
    val justFinishedSessionId: Long? = null
)

data class RunSessionRecord(
    val id: Long = 0L,
    val startedAtEpochMs: Long,
    val dateIso: String, // YYYY-MM-DD in local timezone
    val mode: RunMode,
    val distanceMeters: Double,
    val activeDurationSec: Double,
    val pausedDurationSec: Double,
    val first1600mSplitSec: Double?,
    val equiv1600mSec: Double,
    val avgCadenceSpm: Double,
    val maxSpeedMps: Double,
    val runningSec: Double,
    val joggingSec: Double,
    val walkingSec: Double,
    val lapSplits: List<LapSplit> = emptyList(),
    val notes: String = "",
    val isDemo: Boolean = false
)

/**
 * Benchmarks & pacing mathematics for Telangana State Police (TSLPRB) 1600m PET.
 * Qualifying target: 1,600 meters in 7 minutes 15 seconds (435.0 seconds).
 */
object TsPoliceBenchmark {
    const val TARGET_DISTANCE_M = 1600.0
    const val DEFAULT_TARGET_TIME_SEC = 435.0 // 7 min 15 sec
    const val LAP_DISTANCE_M = 400.0

    fun targetPaceSecPerKm(targetTimeSec: Double = DEFAULT_TARGET_TIME_SEC): Double =
        targetTimeSec / (TARGET_DISTANCE_M / 1000.0)

    fun targetSpeedMps(targetTimeSec: Double = DEFAULT_TARGET_TIME_SEC): Double =
        TARGET_DISTANCE_M / targetTimeSec

    fun targetLap400mSec(targetTimeSec: Double = DEFAULT_TARGET_TIME_SEC): Double =
        targetTimeSec / 4.0

    /**
     * Negative delta = ahead of 7:15 schedule; positive delta = behind 7:15 schedule.
     */
    fun splitDeltaSec(
        distanceMeters: Double,
        activeDurationSec: Double,
        targetTimeSec: Double = DEFAULT_TARGET_TIME_SEC
    ): Double? {
        if (distanceMeters < 20.0) return null
        val cappedDist = distanceMeters.coerceAtMost(TARGET_DISTANCE_M)
        val expectedSec = (cappedDist / TARGET_DISTANCE_M) * targetTimeSec
        return activeDurationSec - expectedSec
    }

    /**
     * Projects 1,600m finish time blending cumulative average speed and current smoothed speed.
     */
    fun projected1600mFinishSec(
        distanceMeters: Double,
        activeDurationSec: Double,
        currentSpeedMps: Double
    ): Double? {
        if (distanceMeters >= TARGET_DISTANCE_M && activeDurationSec > 0.0) {
            return activeDurationSec * (TARGET_DISTANCE_M / distanceMeters)
        }
        val avgSpeed = if (activeDurationSec > 3.0) distanceMeters / activeDurationSec else 0.0
        val effectiveRemainingSpeed = when {
            avgSpeed > 0.6 && currentSpeedMps > 0.6 -> 0.65 * avgSpeed + 0.35 * currentSpeedMps
            currentSpeedMps > 0.6 -> currentSpeedMps
            avgSpeed > 0.6 -> avgSpeed
            else -> return null
        }
        val remainingMeters = (TARGET_DISTANCE_M - distanceMeters).coerceAtLeast(0.0)
        return activeDurationSec + (remainingMeters / effectiveRemainingSpeed)
    }

    /**
     * Riegel physiological endurance scaling to convert runs >= 600m into a 1.6km equivalent time.
     * Exact 1,600m trials (or open runs with a recorded 1,600m split) use exact observed time.
     */
    fun computeEquiv1600mSec(
        distanceMeters: Double,
        activeDurationSec: Double,
        first1600mSplitSec: Double? = null
    ): Double {
        if (first1600mSplitSec != null && first1600mSplitSec > 0.0) {
            return first1600mSplitSec
        }
        if (distanceMeters <= 1.0 || activeDurationSec <= 1.0) return 0.0
        // Within +/- 40m of 1600m, use linear pace normalization
        if (abs(distanceMeters - TARGET_DISTANCE_M) <= 40.0) {
            return activeDurationSec * (TARGET_DISTANCE_M / distanceMeters)
        }
        return activeDurationSec * (TARGET_DISTANCE_M / distanceMeters).pow(1.06)
    }

    fun formatDuration(seconds: Double, includeTenths: Boolean = false): String {
        if (!seconds.isFinite() || seconds < 0.0) return "--:--"
        if (includeTenths) {
            val totalTenths = (seconds * 10.0).roundToInt()
            val mins = totalTenths / 600
            val secs = (totalTenths % 600) / 10
            val tenths = totalTenths % 10
            return String.format("%d:%02d.%d", mins, secs, tenths)
        }
        val rounded = seconds.roundToInt()
        val hours = rounded / 3600
        val mins = (rounded % 3600) / 60
        val secs = rounded % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, mins, secs)
        } else {
            String.format("%d:%02d", mins, secs)
        }
    }

    fun formatPacePerKm(speedMps: Double): String {
        if (!speedMps.isFinite() || speedMps < 0.35) return "--:-- /km"
        val secPerKm = (1000.0 / speedMps).roundToInt()
        val mins = secPerKm / 60
        val secs = secPerKm % 60
        return String.format("%d:%02d /km", mins, secs)
    }

    fun formatPaceFromSecPerKm(secPerKm: Double): String {
        if (!secPerKm.isFinite() || secPerKm <= 0.0) return "--:-- /km"
        val rounded = secPerKm.roundToInt()
        return String.format("%d:%02d /km", rounded / 60, rounded % 60)
    }

    fun formatSignedDelta(deltaSec: Double): String {
        val sign = if (deltaSec <= 0.0) "-" else "+"
        val absVal = abs(deltaSec)
        val mins = (absVal.toInt()) / 60
        val secs = (absVal.roundToInt()) % 60
        return if (mins > 0) {
            String.format("%s%d:%02d", sign, mins, secs)
        } else {
            String.format("%s%.1fs", sign, absVal)
        }
    }
}

/**
 * Builds concise spoken coaching cues whenever the auto-detected [MotionState] changes.
 */
object MotionStateAudioCue {
    fun buildTransitionSpeech(
        previousState: MotionState,
        newState: MotionState,
        isRecording: Boolean,
        targetTimeSec: Double = TsPoliceBenchmark.DEFAULT_TARGET_TIME_SEC
    ): String {
        return when (newState) {
            MotionState.TARGET_PACE -> {
                val mins = targetTimeSec.toInt() / 60
                val secs = targetTimeSec.toInt() % 60
                if (secs == 0) {
                    "Target pace! Under $mins minutes."
                } else {
                    "Target pace! On $mins $secs speed."
                }
            }
            MotionState.RUNNING -> {
                if (previousState == MotionState.TARGET_PACE && isRecording) {
                    "Running. Below target pace."
                } else {
                    "Running."
                }
            }
            MotionState.JOGGING -> {
                if ((previousState == MotionState.RUNNING || previousState == MotionState.TARGET_PACE) && isRecording) {
                    "Jogging. Pace dropped."
                } else {
                    "Jogging."
                }
            }
            MotionState.WALKING -> "Walking."
            MotionState.STANDING -> {
                if (isRecording) "Standing. Auto paused." else "Standing."
            }
        }
    }
}

