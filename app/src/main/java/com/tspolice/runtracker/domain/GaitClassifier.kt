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
    var targetSpeedThresholdMps: Double = TsPoliceBenchmark.targetSpeedMps(),
    var strideScaleFactor: Double = 1.0
) {
    companion object {
        const val DEFAULT_WALKING_SPEED_MPS = 0.75   // ~2.7 km/h real walking speed
        const val DEFAULT_JOGGING_SPEED_MPS = 1.85   // ~6.66 km/h (9:00 /km)
        const val DEFAULT_RUNNING_SPEED_MPS = 3.05   // ~10.98 km/h (5:28 /km)
        const val SPRINT_VERIFICATION_SPEED_MPS = 5.60 // ~20.2 km/h — requires true sprint cadence & impact
        const val MAX_HUMAN_RUNNING_SPEED_MPS = 7.50 // ~27.0 km/h — faster than this is unconditionally a bike/vehicle!
        const val MIN_WALKING_CADENCE_SPM = 68.0     // rejects random hand movement / chair shifting

        const val STANDING_TO_WALK_DEBOUNCE_MS = 1800L
        const val VEHICLE_DEBOUNCE_MS = 3000L
        const val UPGRADE_DEBOUNCE_MS = 1400L
        const val DOWNGRADE_DEBOUNCE_MS = 2000L
    }

    private var committedState: MotionState = MotionState.STANDING
    private var candidateState: MotionState = MotionState.STANDING
    private var candidateSinceMs: Long = 0L

    fun currentState(): MotionState = committedState

    fun candidateState(): MotionState = candidateState

    fun candidateIsJogOrRun(): Boolean = candidateState.isJogOrRun()

    fun reset() {
        committedState = MotionState.STANDING
        candidateState = MotionState.STANDING
        candidateSinceMs = 0L
    }

    /**
     * Stateless instant classification from physical metrics before temporal debounce.
     * Distinguishes human gait (Standing / Walking / Jogging / Running / Target Pace) from
     * riding a Bike / Scooter / Vehicle using 4 biomechanical invariants:
     *  1. Speed ceiling (> 6.6 m/s = 23.8 km/h -> VEHICLE)
     *  2. Moving GPS speed (>= 2.1 m/s) with zero rhythmic footsteps -> VEHICLE
     *  3. Implied GPS stride length > 1.95 m/step (or >= 2.6 m/s with cadence < 122 spm) -> VEHICLE
     *  4. Seated saddle gravity profile (no < 6.4 m/s^2 aerial unloading trough while moving >= 2.6 m/s) -> VEHICLE
     */
    fun classifyInstant(
        gpsSpeedMps: Double?,
        cadenceSpm: Double,
        dynamicAccelRms: Double,
        minRawNorm: Double? = null,
        peakToTroughNorm: Double? = null
    ): MotionState {
        val hasValidGps = gpsSpeedMps != null && gpsSpeedMps.isFinite() && gpsSpeedMps >= 0.0
        val hasRhythmicSteps = cadenceSpm >= MIN_WALKING_CADENCE_SPM
        val impliedStepSpeed = estimateSpeedFromStepsMps(cadenceSpm, dynamicAccelRms)

        if (hasValidGps) {
            val gps = gpsSpeedMps!!

            // Layer 1: Above human distance-running speed ceiling -> Bike / Vehicle
            if (gps > MAX_HUMAN_RUNNING_SPEED_MPS) {
                return MotionState.VEHICLE
            }
            if (gps > SPRINT_VERIFICATION_SPEED_MPS && (cadenceSpm < 170.0 || dynamicAccelRms < 7.0)) {
                return MotionState.VEHICLE
            }

            // Layer 2: Moving without rhythmic footsteps:
            //  - >= 2.10 m/s (7.6 km/h+) without footsteps = Bike / Scooter / Car
            //  - < 2.10 m/s without footsteps = Sitting / Standing / Indoor GPS wander
            if (!hasRhythmicSteps) {
                return if (gps >= 2.10) MotionState.VEHICLE else MotionState.STANDING
            }

            // Layer 3: Biomechanical stride-length check (catches bumpy roads faking occasional steps on a bike)
            val impliedGpsStrideM = gps / (cadenceSpm / 60.0)
            if (gps >= 2.50 && (impliedGpsStrideM > 1.95 || cadenceSpm < 122.0)) {
                return MotionState.VEHICLE
            }

            // Layer 4: Seated saddle vs airborne human running check when gait-band 1g gravity norm is available:
            // Human jogging/running (>= 2.3 m/s) unloads the body into aerial flight (||a|| dips <= 6.4 m/s^2
            // with peak-to-trough swing >= 9.2 m/s^2), whereas riding a bike stays seated around ~9.8 m/s^2.
            if (gps >= 2.30 && minRawNorm != null && peakToTroughNorm != null) {
                if (minRawNorm > 6.4 && peakToTroughNorm < 9.2) {
                    return MotionState.VEHICLE
                }
            }

            if (gps < DEFAULT_WALKING_SPEED_MPS && cadenceSpm < 74.0 && dynamicAccelRms < 0.85) {
                return MotionState.STANDING
            }

            // Fuse GPS ground speed with cadence-implied speed; never let a stationary indoor/lagging GPS
            // fix (0.0 m/s) crush unmistakable active footstrikes in a pocket.
            val fusedSpeed = when {
                gps < 0.85 && dynamicAccelRms >= 0.55 ->
                    maxOf(0.78 * gps + 0.22 * impliedStepSpeed, 0.94 * impliedStepSpeed)
                impliedStepSpeed > gps * 1.45 && dynamicAccelRms >= 3.2 ->
                    maxOf(0.78 * gps + 0.22 * impliedStepSpeed, 0.88 * impliedStepSpeed)
                else ->
                    0.78 * gps + 0.22 * impliedStepSpeed
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
        if (!hasRhythmicSteps || dynamicAccelRms < 0.55) {
            return MotionState.STANDING
        }
        // Reject pure high-frequency engine/table buzz that lacks < 5 Hz vertical body oscillation
        if (peakToTroughNorm != null && peakToTroughNorm < 1.55) {
            return MotionState.STANDING
        }
        // Without GPS, prevent seated bike vibration (no <= 6.4 m/s^2 flight unloading) from faking Jogging/Running
        // while preserving normal indoor walking (which naturally stays > 6.4 m/s^2 at 68..127 spm).
        val seatedVibrationWithoutFlight = minRawNorm != null && peakToTroughNorm != null &&
            minRawNorm > 6.4 && peakToTroughNorm < 9.0
        if (seatedVibrationWithoutFlight && cadenceSpm >= 128.0) {
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
        dynamicAccelRms: Double,
        minRawNorm: Double? = null,
        peakToTroughNorm: Double? = null
    ): MotionState {
        val raw = classifyInstant(gpsSpeedMps, cadenceSpm, dynamicAccelRms, minRawNorm, peakToTroughNorm)
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
            raw == MotionState.VEHICLE -> VEHICLE_DEBOUNCE_MS
            committedState == MotionState.STANDING && raw == MotionState.WALKING -> STANDING_TO_WALK_DEBOUNCE_MS
            raw.intensityRank > committedState.intensityRank -> UPGRADE_DEBOUNCE_MS
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
        val personalScale = strideScaleFactor.coerceIn(
            SamsungHealthComparator.MIN_STRIDE_SCALE,
            SamsungHealthComparator.MAX_STRIDE_SCALE
        )
        return (baseStride * impulseBoost * personalScale).coerceIn(0.50, 1.72)
    }

    fun estimateSpeedFromStepsMps(cadenceSpm: Double, dynamicAccelRms: Double): Double {
        if (cadenceSpm <= 25.0) return 0.0
        val strideMeters = estimateStrideLengthMeters(cadenceSpm, dynamicAccelRms)
        return (cadenceSpm / 60.0) * strideMeters
    }
}

/**
 * Sliding-window step cadence tracker + hybrid hardware/software pedometer:
 *  - Uses hardware TYPE_STEP_DETECTOR / TYPE_STEP_COUNTER whenever real hardware step events arrive.
 *  - Automatically falls back within 2.8s to an orientation-invariant bipolar gait-band accelerometer
 *    pedometer whenever a device lacks TYPE_STEP_DETECTOR or its OEM sensor hub emits 0 events!
 *  - Uses a 3-step rhythmic confirmation buffer on software steps so sitting/tilting/bumping the phone
 *    produces 0 false steps, while real walking/jogging/running counts every step cleanly.
 */
class MotionSignalProcessor {
    private data class AccelFrame(
        val timestampMs: Long,
        val dynamicMag: Double,
        val rawGravityNorm: Double?
    )

    private val stepTimestampsMs = ArrayDeque<Long>()
    private val accelSamples = ArrayDeque<AccelFrame>()
    private val pendingSoftwareStepMs = ArrayDeque<Long>()

    /**
     * Kept for diagnostic compatibility; software step detection is NEVER permanently disabled by registration
     * alone — it only yields while real hardware step events were actually received in the last 2.8 seconds!
     */
    var hardwareStepSensorAvailable: Boolean = false

    private var lastActualHardwareStepMs: Long = -100_000L

    // Orientation-invariant scalar baseline + dt-normalized 2-pole ~4.8 Hz gait-band filter
    private var baselineNorm = 9.81
    private var gaitNorm1 = 9.81
    private var gaitNorm2 = 9.81
    private var filterInitialized = false
    private var lastAccelSampleMs = -1L

    // Bipolar peak-and-trough cycle state for accurate software pedometer
    private var awaitingTrough = false
    private var cyclePeakDelta = 0.0
    private var cyclePeakTimeMs = -10_000L
    private var lastConfirmedSwStepMs = -10_000L

    var totalSteps: Int = 0
        private set

    var hardwareStepsCount: Int = 0
        private set

    var fallbackSoftwareStepsCount: Int = 0
        private set

    fun isReceivingHardwareSteps(nowMs: Long): Boolean =
        (nowMs - lastActualHardwareStepMs) in 0L..5000L

    fun reset() {
        stepTimestampsMs.clear()
        accelSamples.clear()
        pendingSoftwareStepMs.clear()
        lastActualHardwareStepMs = -100_000L
        baselineNorm = 9.81
        gaitNorm1 = 9.81
        gaitNorm2 = 9.81
        filterInitialized = false
        lastAccelSampleMs = -1L
        awaitingTrough = false
        cyclePeakDelta = 0.0
        cyclePeakTimeMs = -10_000L
        lastConfirmedSwStepMs = -10_000L
        totalSteps = 0
        hardwareStepsCount = 0
        fallbackSoftwareStepsCount = 0
    }

    fun onHardwareStepDetected(nowMs: Long, count: Int = 1) {
        lastActualHardwareStepMs = nowMs
        pendingSoftwareStepMs.clear()
        val c = count.coerceIn(1, 12)
        totalSteps += c
        hardwareStepsCount += c
        recordStepTimestamp(nowMs)
        pruneOld(nowMs)
    }

    /**
     * Reconciles cumulative Sensor.TYPE_STEP_COUNTER deltas without double-counting steps already
     * captured by TYPE_STEP_DETECTOR or the accelerometer pedometer.
     */
    fun reconcileStepCounterDelta(nowMs: Long, hwDeltaSteps: Int, stepsCountedSinceLastCounter: Int) {
        if (hwDeltaSteps <= 0) return
        val missing = (hwDeltaSteps - stepsCountedSinceLastCounter).coerceIn(0, 12)
        if (missing > 0) {
            totalSteps += missing
            hardwareStepsCount += missing
            recordStepTimestamp(nowMs)
        }
        pruneOld(nowMs)
    }

    private fun recordStepTimestamp(nowMs: Long) {
        val prev = stepTimestampsMs.lastOrNull()
        if (prev != null) {
            val gap = nowMs - prev
            if (gap < 220L) return // ignore duplicate burst event on same footstrike
            if (gap > 1650L) {
                // Gap > 1.65s (< 36 spm) breaks any continuous stride — start fresh burst from step 1
                stepTimestampsMs.clear()
            }
        }
        stepTimestampsMs.addLast(nowMs)
    }

    /**
     * Feeds either linear acceleration (already gravity-free) or raw accelerometer (with gravity).
     * Uses orientation-invariant scalar norm filtered through a 2-pole ~4.8 Hz gait filter so:
     *  - tilting/rotating/scrolling the phone while sitting produces ~0 dynamic impulse (0 steps),
     *  - 20-40 Hz motorcycle engine & asphalt buzz cancels out BEFORE rectification, and
     *  - walking (even holding the phone in hand), jogging, and running detect every bipolar step!
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

        if (!filterInitialized) {
            baselineNorm = rawNorm
            gaitNorm1 = rawNorm
            gaitNorm2 = rawNorm
            filterInitialized = true
        } else {
            val dtSec = if (lastAccelSampleMs >= 0L && nowMs > lastAccelSampleMs) {
                ((nowMs - lastAccelSampleMs) / 1000.0).coerceIn(0.004, 0.12)
            } else {
                0.02
            }
            val sampleRatio = dtSec / 0.02 // 1.0 at 50 Hz (20ms); normalizes 20 Hz screen-off & 100 Hz sensors
            val alphaBase = if (abs(sampleRatio - 1.0) < 0.02) 0.06 else (1.0 - Math.pow(0.94, sampleRatio))
            val alphaGait = if (abs(sampleRatio - 1.0) < 0.02) 0.50 else (1.0 - Math.pow(0.50, sampleRatio))
            baselineNorm = (1.0 - alphaBase) * baselineNorm + alphaBase * rawNorm
            // 2-pole ~4.8 Hz gait filter: passes >90% of walking/running steps while rejecting ~89% of 25 Hz bike buzz
            gaitNorm1 = (1.0 - alphaGait) * gaitNorm1 + alphaGait * rawNorm
            gaitNorm2 = (1.0 - alphaGait) * gaitNorm2 + alphaGait * gaitNorm1
        }
        lastAccelSampleMs = nowMs

        val gaitDelta = gaitNorm2 - baselineNorm
        val mag = if (includesGravity) abs(gaitDelta) else gaitNorm2.coerceAtLeast(0.0)

        accelSamples.addLast(
            AccelFrame(
                timestampMs = nowMs,
                dynamicMag = mag,
                rawGravityNorm = if (includesGravity) gaitNorm2 else null
            )
        )
        pruneOld(nowMs)

        // Software pedometer runs automatically whenever hardware TYPE_STEP_DETECTOR hasn't delivered
        // a real step in the last 2.8 seconds (fixes OEM phones where TYPE_STEP_DETECTOR is silent!).
        var detectedStep = false
        val hardwareActivelyFiring = (nowMs - lastActualHardwareStepMs) in 0L..2800L
        if (!hardwareActivelyFiring) {
            val minStepIntervalMs = 250L // up to 240 spm sprint
            val peakArmThreshold = 0.92  // m/s^2 upward footstrike compression above baseline
            val troughConfirmThreshold = -0.42 // m/s^2 unloading dip below baseline
            val minPeakToTroughSwing = 1.60    // m/s^2 total bipolar step swing

            if (!awaitingTrough) {
                if (gaitDelta >= peakArmThreshold) {
                    awaitingTrough = true
                    cyclePeakDelta = gaitDelta
                    cyclePeakTimeMs = nowMs
                }
            } else {
                if (gaitDelta > cyclePeakDelta) {
                    cyclePeakDelta = gaitDelta
                    cyclePeakTimeMs = nowMs
                } else if (nowMs - cyclePeakTimeMs > 900L) {
                    // Timed out without an unloading trough (e.g., slow one-way hand lift) -> disarm
                    awaitingTrough = false
                    cyclePeakDelta = 0.0
                } else if (
                    gaitDelta <= troughConfirmThreshold &&
                    (cyclePeakDelta - gaitDelta) >= minPeakToTroughSwing &&
                    (nowMs - lastConfirmedSwStepMs) >= minStepIntervalMs
                ) {
                    awaitingTrough = false
                    cyclePeakDelta = 0.0
                    lastConfirmedSwStepMs = nowMs
                    detectedStep = registerSoftwareStepCandidate(nowMs)
                }
            }
        }
        return detectedStep
    }

    private fun registerSoftwareStepCandidate(nowMs: Long): Boolean {
        val prevPending = pendingSoftwareStepMs.lastOrNull()
        if (prevPending != null && (nowMs - prevPending) > 1550L) {
            // Isolated bump > 1.55s ago was not part of a walk -> discard it!
            pendingSoftwareStepMs.clear()
        }

        // If already in an active confirmed stride (last confirmed step <= 1550ms ago and >= 3 steps in window),
        // count every subsequent step immediately with zero lag!
        val lastConfirmed = stepTimestampsMs.lastOrNull()
        if (lastConfirmed != null && (nowMs - lastConfirmed) <= 1550L && stepTimestampsMs.size >= 3) {
            pendingSoftwareStepMs.clear()
            recordStepTimestamp(nowMs)
            totalSteps++
            fallbackSoftwareStepsCount++
            return true
        }

        pendingSoftwareStepMs.addLast(nowMs)
        if (pendingSoftwareStepMs.size >= 3) {
            // 3 consecutive rhythmic footsteps confirmed -> flush all 3 steps into totalSteps!
            while (pendingSoftwareStepMs.isNotEmpty()) {
                val ts = pendingSoftwareStepMs.removeFirst()
                recordStepTimestamp(ts)
                totalSteps++
                fallbackSoftwareStepsCount++
            }
            return true
        }
        return false
    }

    fun getCadenceSpm(nowMs: Long): Double {
        pruneOld(nowMs)
        val totalCount = stepTimestampsMs.size
        // Require at least 3 consecutive rhythmic footsteps before reporting cadence
        if (totalCount < 3) return 0.0

        val lastMs = stepTimestampsMs.last()
        val silenceMs = nowMs - lastMs
        val recentRms = getDynamicAccelRms(nowMs)
        if (silenceMs > 2400L || (silenceMs > 1600L && recentRms < 0.55)) {
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

        // Reject irregular / non-rhythmic road bumps or hand taps (human gait has tight stride regularity)
        var varianceSum = 0.0
        for (dt in intervalsMs) {
            val diff = dt - meanPeriodMs
            varianceSum += diff * diff
        }
        val stdDevMs = sqrt(varianceSum / intervalsMs.size)
        val coeffOfVariation = stdDevMs / meanPeriodMs
        if (coeffOfVariation > 0.38) {
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
        val recent = accelSamples.filter { nowMs - it.timestampMs <= 1500L }
        if (recent.isEmpty()) return 0.0
        val meanSq = recent.sumOf { it.dynamicMag * it.dynamicMag } / recent.size
        return sqrt(meanSq)
    }

    /**
     * Minimum total 3D acceleration norm (m/s^2) in the last 1.5s when gravity is included.
     * Airborne running dips < 5.5 m/s^2 during flight phase; seated bike riding stays near ~7-10 m/s^2.
     */
    fun getMinRawNorm(nowMs: Long): Double? {
        pruneOld(nowMs)
        var minVal: Double? = null
        for (frame in accelSamples) {
            if (nowMs - frame.timestampMs <= 1500L && frame.rawGravityNorm != null) {
                val v = frame.rawGravityNorm
                if (minVal == null || v < minVal) minVal = v
            }
        }
        return minVal
    }

    fun getPeakToTroughNorm(nowMs: Long): Double? {
        pruneOld(nowMs)
        var minVal: Double? = null
        var maxVal: Double? = null
        for (frame in accelSamples) {
            if (nowMs - frame.timestampMs <= 1500L && frame.rawGravityNorm != null) {
                val v = frame.rawGravityNorm
                if (minVal == null || v < minVal) minVal = v
                if (maxVal == null || v > maxVal) maxVal = v
            }
        }
        return if (minVal != null && maxVal != null) (maxVal - minVal) else null
    }

    private fun pruneOld(nowMs: Long) {
        while (stepTimestampsMs.isNotEmpty() && nowMs - stepTimestampsMs.first() > 5000L) {
            stepTimestampsMs.removeFirst()
        }
        while (accelSamples.isNotEmpty() && nowMs - accelSamples.first().timestampMs > 2500L) {
            accelSamples.removeFirst()
        }
    }
}
