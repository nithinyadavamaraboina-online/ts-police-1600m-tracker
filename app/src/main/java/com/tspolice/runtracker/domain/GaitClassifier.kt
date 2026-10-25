package com.tspolice.runtracker.domain

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
        const val DEFAULT_WALKING_SPEED_MPS = 0.50   // ~1.8 km/h
        const val DEFAULT_JOGGING_SPEED_MPS = 1.85   // ~6.66 km/h (9:00 /km)
        const val DEFAULT_RUNNING_SPEED_MPS = 3.05   // ~10.98 km/h (5:28 /km)

        const val UPGRADE_DEBOUNCE_MS = 1800L
        const val DOWNGRADE_DEBOUNCE_MS = 2800L
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
        val impliedStepSpeed = estimateSpeedFromStepsMps(cadenceSpm, dynamicAccelRms)

        if (hasValidGps) {
            val gps = gpsSpeedMps!!
            // Stationary / pocket-fidget rejection
            if (gps < DEFAULT_WALKING_SPEED_MPS && cadenceSpm < 42.0 && dynamicAccelRms < 1.35) {
                return MotionState.STANDING
            }
            if (gps < 0.35 && cadenceSpm < 55.0 && dynamicAccelRms < 2.2) {
                return MotionState.STANDING
            }

            // Fuse GPS ground speed with cadence-implied speed when steps are observed
            val fusedSpeed = if (cadenceSpm >= 55.0) {
                0.78 * gps + 0.22 * impliedStepSpeed
            } else {
                gps
            }

            return when {
                fusedSpeed >= targetSpeedThresholdMps -> MotionState.TARGET_PACE
                fusedSpeed >= runningSpeedThresholdMps ||
                    (gps >= 2.75 && cadenceSpm >= 166.0 && dynamicAccelRms >= 6.8) -> MotionState.RUNNING
                fusedSpeed >= DEFAULT_JOGGING_SPEED_MPS ||
                    (gps >= 1.55 && cadenceSpm >= 136.0 && dynamicAccelRms >= 3.8) -> MotionState.JOGGING
                else -> MotionState.WALKING
            }
        }

        // Indoor / pre-GPS-lock fallback: pure step cadence + 3-axis dynamic impulse RMS
        if (cadenceSpm < 36.0 && dynamicAccelRms < 1.20) {
            return MotionState.STANDING
        }
        return when {
            cadenceSpm >= 176.0 && dynamicAccelRms >= 8.2 -> MotionState.TARGET_PACE
            cadenceSpm >= 163.0 || (cadenceSpm >= 152.0 && dynamicAccelRms >= 6.6) -> MotionState.RUNNING
            cadenceSpm >= 134.0 || (cadenceSpm >= 122.0 && dynamicAccelRms >= 3.9) -> MotionState.JOGGING
            cadenceSpm >= 36.0 || dynamicAccelRms >= 1.45 -> MotionState.WALKING
            else -> MotionState.STANDING
        }
    }

    /**
     * Applies temporal hysteresis so momentary GPS noise neither pauses a run nor flickers badges.
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

        val requiredHoldMs = if (raw.ordinal > committedState.ordinal) {
            UPGRADE_DEBOUNCE_MS
        } else {
            DOWNGRADE_DEBOUNCE_MS
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

    // Low-pass gravity filter state when raw TYPE_ACCELEROMETER is used
    private var gravityX = 0.0
    private var gravityY = 0.0
    private var gravityZ = 9.81
    private var gravityInitialized = false

    private var prevDynamicMag = 0.0
    private var rising = false
    private var lastAccelStepMs = -10_000L
    private var lastHardwareStepMs: Long? = null

    var totalSteps: Int = 0
        private set

    fun reset() {
        stepTimestampsMs.clear()
        accelSamples.clear()
        gravityInitialized = false
        prevDynamicMag = 0.0
        rising = false
        lastAccelStepMs = -10_000L
        lastHardwareStepMs = null
        totalSteps = 0
    }

    fun onHardwareStepDetected(nowMs: Long, count: Int = 1) {
        lastHardwareStepMs = nowMs
        repeat(count.coerceIn(1, 6)) {
            stepTimestampsMs.addLast(nowMs)
            totalSteps++
        }
        pruneOld(nowMs)
    }

    /**
     * Feeds either linear acceleration (already gravity-free) or raw accelerometer (with gravity).
     * Returns true if the software fallback step detector registered a step peak.
     */
    fun onAccelerationSample(
        nowMs: Long,
        ax: Float,
        ay: Float,
        az: Float,
        includesGravity: Boolean
    ): Boolean {
        val dx: Double
        val dy: Double
        val dz: Double
        if (includesGravity) {
            if (!gravityInitialized) {
                gravityX = ax.toDouble()
                gravityY = ay.toDouble()
                gravityZ = az.toDouble()
                gravityInitialized = true
            } else {
                val alpha = 0.85
                gravityX = alpha * gravityX + (1.0 - alpha) * ax
                gravityY = alpha * gravityY + (1.0 - alpha) * ay
                gravityZ = alpha * gravityZ + (1.0 - alpha) * az
            }
            dx = ax - gravityX
            dy = ay - gravityY
            dz = az - gravityZ
        } else {
            dx = ax.toDouble()
            dy = ay.toDouble()
            dz = az.toDouble()
        }

        val mag = sqrt(dx * dx + dy * dy + dz * dz)
        accelSamples.addLast(nowMs to mag)
        pruneOld(nowMs)

        // Software step peak detector activates whenever hardware step sensor hasn't fired in last 2.5s
        var detectedStep = false
        val hwLast = lastHardwareStepMs
        val useSoftwareFallback = hwLast == null || (nowMs - hwLast) > 2500L
        if (useSoftwareFallback) {
            val minStepIntervalMs = 250L // max 240 steps/min
            val peakThreshold = 2.25     // m/s^2 dynamic impulse peak
            if (mag > prevDynamicMag) {
                rising = true
            } else if (rising && prevDynamicMag >= peakThreshold && (nowMs - lastAccelStepMs) >= minStepIntervalMs) {
                rising = false
                lastAccelStepMs = nowMs
                stepTimestampsMs.addLast(nowMs)
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
        val count = stepTimestampsMs.size
        if (count == 0) return 0.0
        val firstMs = stepTimestampsMs.first()
        val lastMs = stepTimestampsMs.last()
        val silenceMs = nowMs - lastMs
        if (silenceMs > 3500L) return 0.0

        if (count == 1) {
            return if (silenceMs < 1800L) 60.0 else 0.0
        }
        val stepSpanMs = (lastMs - firstMs).coerceAtLeast(300L)
        // Include trailing silence if user has slowed down since last detected footstrike
        val avgPeriodMs = stepSpanMs.toDouble() / (count - 1).toDouble()
        val effectiveSpanMs = if (silenceMs > avgPeriodMs * 1.35) {
            (nowMs - firstMs).toDouble()
        } else {
            stepSpanMs.toDouble()
        }
        val intervals = (count - 1).toDouble()
        return ((intervals / (effectiveSpanMs / 1000.0)) * 60.0).coerceIn(0.0, 235.0)
    }

    fun getDynamicAccelRms(nowMs: Long): Double {
        pruneOld(nowMs)
        val recent = accelSamples.filter { nowMs - it.first <= 2200L }
        if (recent.isEmpty()) return 0.0
        val meanSq = recent.sumOf { it.second * it.second } / recent.size
        return sqrt(meanSq)
    }

    private fun pruneOld(nowMs: Long) {
        while (stepTimestampsMs.isNotEmpty() && nowMs - stepTimestampsMs.first() > 8000L) {
            stepTimestampsMs.removeFirst()
        }
        while (accelSamples.isNotEmpty() && nowMs - accelSamples.first().first > 3000L) {
            accelSamples.removeFirst()
        }
    }
}
