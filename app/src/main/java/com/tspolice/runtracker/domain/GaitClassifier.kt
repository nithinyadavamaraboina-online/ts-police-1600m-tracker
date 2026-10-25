package com.tspolice.runtracker.domain

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Multi-sensor gait classifier that fuses:
 *  1. High-accuracy GPS ground speed (m/s)
 *  2. Real-time step cadence (steps/min from TYPE_STEP_DETECTOR or accelerometer peak fallback)
 *  3. 3-axis dynamic acceleration RMS (m/s^2 vertical/body flight impulse)
 *
 * Automatically distinguishes STANDING (Auto-Pause), WALKING, JOGGING, RUNNING, and TARGET_PACE
 * without requiring manual sport selection.
 */
class GaitClassifier(
    var runningSpeedThresholdMps: Double = DEFAULT_RUNNING_SPEED_MPS,
    var targetSpeedThresholdMps: Double = TsPoliceBenchmark.targetSpeedMps()
) {
    companion object {
        const val DEFAULT_WALKING_SPEED_MPS = 0.75   // ~2.7 km/h real walking speed
        const val DEFAULT_JOGGING_SPEED_MPS = 1.85   // ~6.66 km/h (9:00 /km)
        const val DEFAULT_RUNNING_SPEED_MPS = 3.05   // ~10.98 km/h (5:28 /km)
        const val MIN_WALKING_CADENCE_SPM = 68.0     // rejects random hand movement / chair shifting

        const val STANDING_TO_WALK_DEBOUNCE_MS = 2200L
        const val UPGRADE_DEBOUNCE_MS = 1400L
        const val DOWNGRADE_DEBOUNCE_MS = 2000L
    }

    private var committedState: MotionState = MotionState.STANDING
    private var candidateState: MotionState = MotionState.STANDING
    private var candidateSinceMs: Long = 0L

    fun currentState(): MotionState = committedState

    fun reset() {
        committedState = MotionState.STANDING
        candidateState = MotionState.STANDING
        candidateSinceMs = 0L
    }

    /**
     * Stateless instant classification from physical metrics before temporal debounce.
     */
    fun classifyInstant(
        gpsSpeedMps: Double?,
        cadenceSpm: Double,
        dynamicAccelRms: Double
    ): MotionState {
        val hasValidGps = gpsSpeedMps != null && gpsSpeedMps.isFinite() && gpsSpeedMps >= 0.0
        val hasRhythmicSteps = cadenceSpm >= MIN_WALKING_CADENCE_SPM
        val impliedStepSpeed = estimateSpeedFromStepsMps(cadenceSpm, dynamicAccelRms)

        if (hasValidGps) {
            val gps = gpsSpeedMps!!
            // Sitting / hand-use / indoor GPS multipath rejection:
            // Without rhythmic footsteps (>= 68 spm), ignore indoor GPS wander (< 2.2 m/s) and phone handling!
            if (!hasRhythmicSteps && (gps < 2.20 || dynamicAccelRms < 2.20)) {
                return MotionState.STANDING
            }
            if (gps < DEFAULT_WALKING_SPEED_MPS && cadenceSpm < 74.0 && dynamicAccelRms < 0.85) {
                return MotionState.STANDING
            }

            // Fuse GPS ground speed with cadence-implied speed; never let a stationary indoor/lagging GPS
            // fix (0.0 m/s) crush unmistakable active footstrikes in a pocket.
            val fusedSpeed = when {
                hasRhythmicSteps && gps < 0.85 && dynamicAccelRms >= 0.85 ->
                    maxOf(0.78 * gps + 0.22 * impliedStepSpeed, 0.94 * impliedStepSpeed)
                hasRhythmicSteps && impliedStepSpeed > gps * 1.45 && dynamicAccelRms >= 3.5 ->
                    maxOf(0.78 * gps + 0.22 * impliedStepSpeed, 0.88 * impliedStepSpeed)
                hasRhythmicSteps ->
                    0.78 * gps + 0.22 * impliedStepSpeed
                else -> gps
            }

            return when {
                fusedSpeed >= targetSpeedThresholdMps ||
                    (cadenceSpm >= 176.0 && dynamicAccelRms >= 7.8 && gps >= 3.35) -> MotionState.TARGET_PACE
                fusedSpeed >= runningSpeedThresholdMps ||
                    (cadenceSpm >= 164.0 && dynamicAccelRms >= 6.0) -> MotionState.RUNNING
                fusedSpeed >= DEFAULT_JOGGING_SPEED_MPS ||
                    (cadenceSpm >= 134.0 && dynamicAccelRms >= 3.4) -> MotionState.JOGGING
                else -> MotionState.WALKING
            }
        }

        // Indoor / pre-GPS-lock path: require genuine rhythmic step cadence (>= 68 spm) + footstrike impact
        // so holding, tilting, or moving the phone while sitting never triggers WALKING.
        if (!hasRhythmicSteps || dynamicAccelRms < 0.75) {
            return MotionState.STANDING
        }
        return when {
            cadenceSpm >= 176.0 && dynamicAccelRms >= 7.8 -> MotionState.TARGET_PACE
            cadenceSpm >= 163.0 || (cadenceSpm >= 152.0 && dynamicAccelRms >= 6.0) -> MotionState.RUNNING
            cadenceSpm >= 134.0 || (cadenceSpm >= 124.0 && dynamicAccelRms >= 3.4) -> MotionState.JOGGING
            else -> MotionState.WALKING
        }
    }

    /**
     * Applies temporal hysteresis so momentary hand movement or GPS noise neither triggers false walking
     * nor prematurely pauses an active run.
     */
    fun update(
        nowMs: Long,
        gpsSpeedMps: Double?,
        cadenceSpm: Double,
        dynamicAccelRms: Double
    ): MotionState {
        val raw = classifyInstant(gpsSpeedMps, cadenceSpm, dynamicAccelRms)
        if (raw == committedState) {
            candidateState = raw
            candidateSinceMs = nowMs
            return committedState
        }

        if (raw != candidateState) {
            candidateState = raw
            candidateSinceMs = nowMs
            return committedState
        }

        val requiredHoldMs = when {
            committedState == MotionState.STANDING && raw == MotionState.WALKING -> STANDING_TO_WALK_DEBOUNCE_MS
            raw.ordinal > committedState.ordinal -> UPGRADE_DEBOUNCE_MS
            else -> DOWNGRADE_DEBOUNCE_MS
        }

        if (nowMs - candidateSinceMs >= requiredHoldMs) {
            committedState = raw
        }
        return committedState
    }

    /**
     * Biomechanical stride-length curve (meters/step) calibrated across walking, jogging, and 1600m running.
     */
    fun estimateStrideLengthMeters(cadenceSpm: Double, dynamicAccelRms: Double): Double {
        if (cadenceSpm <= 25.0) return 0.0
        val baseStride = when {
            cadenceSpm < 100.0 -> 0.68 + ((cadenceSpm - 60.0).coerceAtLeast(0.0) * 0.003)
            cadenceSpm < 135.0 -> 0.80 + ((cadenceSpm - 100.0) * 0.0045)
            cadenceSpm < 163.0 -> 0.96 + ((cadenceSpm - 135.0) * 0.0080)
            cadenceSpm < 185.0 -> 1.18 + ((cadenceSpm - 163.0) * 0.0095)
            else -> 1.39
        }
        val impulseBoost = when {
            dynamicAccelRms >= 8.5 -> 1.05
            dynamicAccelRms >= 6.0 -> 1.02
            else -> 1.00
        }
        return (baseStride * impulseBoost).coerceIn(0.55, 1.52)
    }

    fun estimateSpeedFromStepsMps(cadenceSpm: Double, dynamicAccelRms: Double): Double {
        if (cadenceSpm <= 25.0) return 0.0
        val strideMeters = estimateStrideLengthMeters(cadenceSpm, dynamicAccelRms)
        return (cadenceSpm / 60.0) * strideMeters
    }
}

/**
 * Sliding-window step cadence tracker + 3-axis accelerometer step peak fallback
 * for devices without a dedicated hardware TYPE_STEP_DETECTOR sensor.
 */
class MotionSignalProcessor {
    private val stepTimestampsMs = ArrayDeque<Long>()
    private val accelSamples = ArrayDeque<Pair<Long, Double>>()

    /**
     * When true, Android's dedicated hardware TYPE_STEP_DETECTOR sensor is active, so software
     * accelerometer peak step synthesis is disabled to prevent hand/phone gestures while sitting
     * from registering as fake steps.
     */
    var hardwareStepSensorAvailable: Boolean = false

    // Orientation-invariant scalar gravity norm (immune to tilting/rotating the phone in hand!)
    private var gravityNorm = 9.81
    private var gravityInitialized = false

    private var prevDynamicMag = 0.0
    private var rising = false
    private var lastAccelStepMs = -10_000L

    var totalSteps: Int = 0
        private set

    fun reset() {
        stepTimestampsMs.clear()
        accelSamples.clear()
        gravityNorm = 9.81
        gravityInitialized = false
        prevDynamicMag = 0.0
        rising = false
        lastAccelStepMs = -10_000L
        totalSteps = 0
    }

    fun onHardwareStepDetected(nowMs: Long, count: Int = 1) {
        totalSteps += count.coerceIn(1, 6)
        recordStepTimestamp(nowMs)
        pruneOld(nowMs)
    }

    private fun recordStepTimestamp(nowMs: Long) {
        val prev = stepTimestampsMs.lastOrNull()
        if (prev != null) {
            val gap = nowMs - prev
            if (gap < 220L) return // ignore duplicate burst event on same footstrike
            if (gap > 1500L) {
                // Gap > 1.5s (< 40 spm) breaks any continuous stride — start fresh burst from step 1
                stepTimestampsMs.clear()
            }
        }
        stepTimestampsMs.addLast(nowMs)
    }

    /**
     * Feeds either linear acceleration (already gravity-free) or raw accelerometer (with gravity).
     * Uses orientation-invariant scalar magnitude | ||a|| - g | when gravity is included so tilting,
     * rotating, or scrolling on the phone while sitting produces ~0 dynamic impulse.
     */
    fun onAccelerationSample(
        nowMs: Long,
        ax: Float,
        ay: Float,
        az: Float,
        includesGravity: Boolean
    ): Boolean {
        val rawNorm = sqrt(
            ax.toDouble() * ax.toDouble() +
                ay.toDouble() * ay.toDouble() +
                az.toDouble() * az.toDouble()
        )
        val mag = if (includesGravity) {
            if (!gravityInitialized) {
                gravityNorm = rawNorm
                gravityInitialized = true
            } else {
                gravityNorm = 0.92 * gravityNorm + 0.08 * rawNorm
            }
            abs(rawNorm - gravityNorm)
        } else {
            rawNorm
        }

        accelSamples.addLast(nowMs to mag)
        pruneOld(nowMs)

        // Software step peak detector ONLY runs on devices that lack a hardware TYPE_STEP_DETECTOR sensor
        var detectedStep = false
        if (!hardwareStepSensorAvailable) {
            val minStepIntervalMs = 280L // max ~214 steps/min
            val peakThreshold = 2.45     // m/s^2 vertical impact peak (ignores hand gestures while sitting)
            if (mag > prevDynamicMag) {
                rising = true
            } else if (rising && prevDynamicMag >= peakThreshold && (nowMs - lastAccelStepMs) >= minStepIntervalMs) {
                rising = false
                lastAccelStepMs = nowMs
                recordStepTimestamp(nowMs)
                totalSteps++
                detectedStep = true
            } else if (mag < prevDynamicMag) {
                rising = false
            }
        }
        prevDynamicMag = mag
        return detectedStep
    }

    fun getCadenceSpm(nowMs: Long): Double {
        pruneOld(nowMs)
        val totalCount = stepTimestampsMs.size
        // Require at least 4 consecutive unbroken footsteps before reporting any walking/running cadence!
        if (totalCount < 4) return 0.0

        val lastMs = stepTimestampsMs.last()
        val silenceMs = nowMs - lastMs
        val recentRms = getDynamicAccelRms(nowMs)
        if (silenceMs > 1900L || (silenceMs > 1400L && recentRms < 0.75)) {
            return 0.0
        }

        // Use most recent 6 footstrikes so Walk <-> Jog <-> Run cadence transitions react in ~1.5s
        val startIdx = (totalCount - 6).coerceAtLeast(0)
        val count = totalCount - startIdx
        val intervalsMs = DoubleArray(count - 1)
        var sumMs = 0.0
        for (i in 0 until count - 1) {
            val dt = (stepTimestampsMs[startIdx + i + 1] - stepTimestampsMs[startIdx + i]).toDouble()
            intervalsMs[i] = dt
            sumMs += dt
        }
        val meanPeriodMs = (sumMs / intervalsMs.size).coerceAtLeast(240.0)

        // Reject irregular / non-rhythmic phone shaking or hand taps (real human gait has low period variance)
        var varianceSum = 0.0
        for (dt in intervalsMs) {
            val diff = dt - meanPeriodMs
            varianceSum += diff * diff
        }
        val stdDevMs = sqrt(varianceSum / intervalsMs.size)
        val coeffOfVariation = stdDevMs / meanPeriodMs
        if (coeffOfVariation > 0.42) {
            return 0.0
        }

        val firstMs = stepTimestampsMs[startIdx]
        val stepSpanMs = (lastMs - firstMs).coerceAtLeast(300L)
        val effectiveSpanMs = if (silenceMs > meanPeriodMs * 1.35) {
            (nowMs - firstMs).toDouble()
        } else {
            stepSpanMs.toDouble()
        }
        return (((count - 1).toDouble() / (effectiveSpanMs / 1000.0)) * 60.0).coerceIn(0.0, 235.0)
    }

    fun getDynamicAccelRms(nowMs: Long): Double {
        pruneOld(nowMs)
        val recent = accelSamples.filter { nowMs - it.first <= 1500L }
        if (recent.isEmpty()) return 0.0
        val meanSq = recent.sumOf { it.second * it.second } / recent.size
        return sqrt(meanSq)
    }

    private fun pruneOld(nowMs: Long) {
        while (stepTimestampsMs.isNotEmpty() && nowMs - stepTimestampsMs.first() > 5000L) {
            stepTimestampsMs.removeFirst()
        }
        while (accelSamples.isNotEmpty() && nowMs - accelSamples.first().first > 2500L) {
            accelSamples.removeFirst()
        }
    }
}
