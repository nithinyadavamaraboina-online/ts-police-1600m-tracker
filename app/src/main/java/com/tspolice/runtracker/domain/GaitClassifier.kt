package com.tspolice.runtracker.domain

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Multi-sensor kinematic gait & vehicle classifier that fuses:
 *  1. High-accuracy GPS ground speed (m/s)
 *  2. Hardware step cadence (steps/min from TYPE_STEP_DETECTOR / TYPE_STEP_COUNTER, with fallback only when no working HW sensor exists)
 *  3. 3-axis dynamic acceleration RMS + 20th-percentile gravity unloading norm (airborne flight vs seated saddle)
 *  4. Stateful Vehicle Latch & Dismount Physics (impossible to jump off a moving bike into Running/Jogging/Walking!)
 */
class GaitClassifier(
    var runningSpeedThresholdMps: Double = DEFAULT_RUNNING_SPEED_MPS,
    var targetSpeedThresholdMps: Double = TsPoliceBenchmark.targetSpeedMps(),
    var strideScaleFactor: Double = 1.0
) {
    companion object {
        const val DEFAULT_WALKING_SPEED_MPS = 0.75      // ~2.7 km/h real walking speed
        const val DEFAULT_JOGGING_SPEED_MPS = 1.85      // ~6.66 km/h (9:00 /km)
        const val DEFAULT_RUNNING_SPEED_MPS = 3.05      // ~10.98 km/h (5:28 /km)
        const val SPRINT_VERIFICATION_SPEED_MPS = 5.00  // ~18.0 km/h — above this requires verified airborne sprint physics
        const val MAX_HUMAN_RUNNING_SPEED_MPS = 7.00    // ~25.2 km/h — unconditionally a bike/vehicle
        const val MIN_WALKING_CADENCE_SPM = 68.0        // rejects random hand movement / chair shifting

        const val STANDING_TO_WALK_DEBOUNCE_MS = 1800L
        const val VEHICLE_FAST_DEBOUNCE_MS = 0L         // instant commit above 5.2 m/s (18.7 km/h)
        const val VEHICLE_DEBOUNCE_MS = 900L            // sub-second lock-in on slow bike / scooter
        const val UPGRADE_DEBOUNCE_MS = 1400L
        const val DOWNGRADE_DEBOUNCE_MS = 2000L

        const val PARKED_STOP_REQUIRED_MS = 6000L       // must stay < 0.85 m/s for 6s before dismount can start
        const val DISMOUNT_WALK_CONFIRM_MS = 5500L      // 5.5s (~9-10 real walking steps at <= 1.9 m/s) unlocks foot mode
        const val VEHICLE_COOLDOWN_EXPIRY_MS = 180_000L // 3-min red-light memory so green-light acceleration stays VEHICLE
    }

    private var committedState: MotionState = MotionState.STANDING
    private var candidateState: MotionState = MotionState.STANDING
    private var candidateSinceMs: Long = 0L
    private var lastUpdateMs: Long = -1L

    // Stateful Bike / Vehicle Latch & Dismount Verification
    private var vehicleLatchActive: Boolean = false
    private var vehicleCooldownActive: Boolean = false
    private var lastVehicleMotionMs: Long = -1_000_000L
    private var continuousStoppedMs: Long = 0L
    private var parkedStopCompleted: Boolean = false
    private var dismountWalkConfirmMs: Long = 0L

    fun currentState(): MotionState = committedState

    fun candidateState(): MotionState = candidateState

    fun candidateIsJogOrRun(): Boolean =
        !isInOrCoolingDownFromVehicle() && candidateState.isJogOrRun()

    /**
     * True whenever the user is riding a bike/vehicle or stopped at a traffic light awaiting
     * a verified on-foot dismount walk. Used by RunTrackingService to keep GPS awake and block
     * road-bump phantom steps/distance.
     */
    fun isInOrCoolingDownFromVehicle(): Boolean =
        vehicleLatchActive || vehicleCooldownActive ||
            committedState == MotionState.VEHICLE || candidateState == MotionState.VEHICLE

    fun isVehicleLatched(): Boolean = vehicleLatchActive

    /**
     * Called when the user explicitly starts a manual track session (Arm 1.6K Trial / Start 400m Intervals).
     */
    fun clearVehicleLatchForManualWorkout() {
        vehicleLatchActive = false
        vehicleCooldownActive = false
        parkedStopCompleted = false
        continuousStoppedMs = 0L
        dismountWalkConfirmMs = 0L
        if (committedState == MotionState.VEHICLE) {
            committedState = MotionState.STANDING
            candidateState = MotionState.STANDING
        }
    }

    fun reset() {
        committedState = MotionState.STANDING
        candidateState = MotionState.STANDING
        candidateSinceMs = 0L
        lastUpdateMs = -1L
        vehicleLatchActive = false
        vehicleCooldownActive = false
        lastVehicleMotionMs = -1_000_000L
        continuousStoppedMs = 0L
        parkedStopCompleted = false
        dismountWalkConfirmMs = 0L
    }

    /**
     * Instant biomechanical classification before stateful vehicle-latch & temporal hysteresis.
     *  - Uses `p20RawNorm` (20th-percentile gravity norm over 1.5s) when available so a single pothole
     *    dipping `minRawNorm` on an Indian road can NEVER fake repeated human running flight phases!
     *  - Requires BOTH cadence AND vertical impact (`&&`, never `||`) when GPS is unavailable so engine
     *    or suspension buzz without GPS can never classify as Jogging or Running.
     */
    fun classifyInstant(
        gpsSpeedMps: Double?,
        cadenceSpm: Double,
        dynamicAccelRms: Double,
        minRawNorm: Double? = null,
        peakToTroughNorm: Double? = null,
        p20RawNorm: Double? = null
    ): MotionState {
        val hasValidGps = gpsSpeedMps != null && gpsSpeedMps.isFinite() && gpsSpeedMps >= 0.0
        val hasRhythmicSteps = cadenceSpm >= MIN_WALKING_CADENCE_SPM
        val impliedStepSpeed = estimateSpeedFromStepsMps(cadenceSpm, dynamicAccelRms)
        val flightNorm = p20RawNorm ?: minRawNorm

        if (hasValidGps) {
            val gps = gpsSpeedMps!!

            // Layer 1: Above human distance-running speed ceiling -> Bike / Vehicle
            if (gps > MAX_HUMAN_RUNNING_SPEED_MPS) {
                return MotionState.VEHICLE
            }
            if (gps > SPRINT_VERIFICATION_SPEED_MPS &&
                (cadenceSpm < 172.0 || dynamicAccelRms < 7.2 || (flightNorm != null && flightNorm > 6.3))
            ) {
                return MotionState.VEHICLE
            }

            // Layer 2: Moving >= 1.95 m/s (7.0 km/h+) without rhythmic footsteps = Bike / Scooter / Car
            if (!hasRhythmicSteps) {
                return if (gps >= 1.95) MotionState.VEHICLE else MotionState.STANDING
            }

            // Layer 3: Biomechanical stride-length & cadence invariants
            val impliedGpsStrideM = gps / (cadenceSpm / 60.0)
            if (gps >= 2.35 && (impliedGpsStrideM > 1.85 || cadenceSpm < 124.0)) {
                return MotionState.VEHICLE
            }
            if (gps >= 1.95 && impliedGpsStrideM > 1.58 && flightNorm != null && flightNorm > 7.0) {
                return MotionState.VEHICLE
            }

            // Layer 4: Seated saddle vs airborne human jog/run check:
            //  - Using p20RawNorm (20th percentile), human jogging/running spends ~25-35% of every stride in
            //    aerial unloading (p20 <= 6.95 m/s^2), whereas a seated bike rider stays near ~9.8 m/s^2 even
            //    if an isolated pothole momentarily spikes Peak-to-Trough!
            if (gps >= 2.25 && p20RawNorm != null && p20RawNorm > 7.30) {
                return MotionState.VEHICLE
            }
            if (gps >= 2.05 && flightNorm != null && peakToTroughNorm != null) {
                if (flightNorm > 6.80 && peakToTroughNorm < 9.2) {
                    return MotionState.VEHICLE
                }
            }

            if (gps < DEFAULT_WALKING_SPEED_MPS && cadenceSpm < 74.0 && dynamicAccelRms < 0.85) {
                return MotionState.STANDING
            }

            // Fuse GPS ground speed with cadence-implied speed
            val fusedSpeed = when {
                gps < 0.85 && dynamicAccelRms >= 0.65 ->
                    maxOf(0.78 * gps + 0.22 * impliedStepSpeed, 0.94 * impliedStepSpeed)
                impliedStepSpeed > gps * 1.45 && dynamicAccelRms >= 3.4 ->
                    maxOf(0.78 * gps + 0.22 * impliedStepSpeed, 0.88 * impliedStepSpeed)
                else ->
                    0.78 * gps + 0.22 * impliedStepSpeed
            }

            return when {
                fusedSpeed >= targetSpeedThresholdMps ||
                    (cadenceSpm >= 176.0 && dynamicAccelRms >= 7.8 && gps >= 3.35) -> MotionState.TARGET_PACE
                fusedSpeed >= runningSpeedThresholdMps ||
                    (cadenceSpm >= 164.0 && dynamicAccelRms >= 6.0 && gps >= 2.45) -> MotionState.RUNNING
                fusedSpeed >= DEFAULT_JOGGING_SPEED_MPS ||
                    (cadenceSpm >= 136.0 && dynamicAccelRms >= 3.5 && gps >= 1.55) -> MotionState.JOGGING
                else -> MotionState.WALKING
            }
        }

        // Indoor / pre-GPS-lock path: require genuine rhythmic step cadence (>= 68 spm) + footstrike impact
        if (!hasRhythmicSteps || dynamicAccelRms < 0.65) {
            return MotionState.STANDING
        }
        // Reject pure high-frequency engine/table buzz that lacks < 5 Hz vertical body oscillation
        if (peakToTroughNorm != null && peakToTroughNorm < 1.70) {
            return MotionState.STANDING
        }
        // Without GPS, prevent seated vibration (no aerial flight unloading) from EVER faking Jogging/Running
        val seatedVibrationWithoutFlight =
            (p20RawNorm != null && p20RawNorm > 7.20) ||
                (flightNorm != null && peakToTroughNorm != null && flightNorm > 6.5 && peakToTroughNorm < 9.0)
        if (seatedVibrationWithoutFlight) {
            return if (cadenceSpm >= 130.0) {
                MotionState.STANDING
            } else {
                MotionState.WALKING
            }
        }
        // Require BOTH cadence AND real footstrike RMS impact (never cadence alone!)
        return when {
            cadenceSpm >= 176.0 && dynamicAccelRms >= 7.8 -> MotionState.TARGET_PACE
            cadenceSpm >= 162.0 && dynamicAccelRms >= 5.6 -> MotionState.RUNNING
            cadenceSpm >= 134.0 && dynamicAccelRms >= 3.4 -> MotionState.JOGGING
            else -> MotionState.WALKING
        }
    }

    /**
     * Stateful update enforcing real-world dismount kinematics:
     *  - You CANNOT jump from a moving bike (`VEHICLE`) directly into `RUNNING`, `JOGGING`, or `WALKING`.
     *  - Slowing a bike down in traffic (`35 -> 15 -> 8 -> 4 km/h`) stays `VEHICLE` until the bike comes
     *    to a complete parked stop (`< 0.85 m/s` for >= 6.0s) followed by >= 5.5s of verified walking steps.
     *  - Stopping at a red traffic light and accelerating again snaps back to `VEHICLE` in 0ms!
     */
    fun update(
        nowMs: Long,
        gpsSpeedMps: Double?,
        cadenceSpm: Double,
        dynamicAccelRms: Double,
        minRawNorm: Double? = null,
        peakToTroughNorm: Double? = null,
        p20RawNorm: Double? = null
    ): MotionState {
        val dtMs = if (lastUpdateMs >= 0L && nowMs > lastUpdateMs) {
            (nowMs - lastUpdateMs).coerceIn(1L, 2500L)
        } else {
            250L
        }
        lastUpdateMs = nowMs

        if (vehicleCooldownActive && !vehicleLatchActive && (nowMs - lastVehicleMotionMs) > VEHICLE_COOLDOWN_EXPIRY_MS) {
            vehicleCooldownActive = false
            parkedStopCompleted = false
            dismountWalkConfirmMs = 0L
        }

        val raw = classifyInstant(gpsSpeedMps, cadenceSpm, dynamicAccelRms, minRawNorm, peakToTroughNorm, p20RawNorm)

        // 1. Immediate or fast entry into VEHICLE
        if (raw == MotionState.VEHICLE) {
            val fastVehicle = vehicleLatchActive || vehicleCooldownActive ||
                (gpsSpeedMps != null && gpsSpeedMps >= 5.20)
            if (fastVehicle) {
                enterOrRefreshVehicleLatch(nowMs)
                return MotionState.VEHICLE
            }
            if (candidateState != MotionState.VEHICLE) {
                candidateState = MotionState.VEHICLE
                candidateSinceMs = nowMs
                return committedState
            }
            if (nowMs - candidateSinceMs >= VEHICLE_DEBOUNCE_MS) {
                enterOrRefreshVehicleLatch(nowMs)
                return MotionState.VEHICLE
            }
            return committedState
        }

        // 2. Enforce Sticky Vehicle Latch & Red-Light Cooldown Physics
        if (vehicleLatchActive || vehicleCooldownActive) {
            val hasGps = gpsSpeedMps != null && gpsSpeedMps.isFinite() && gpsSpeedMps >= 0.0
            val gps = gpsSpeedMps ?: -1.0
            val isStoppedSpeed = (hasGps && gps < 0.85) ||
                (!hasGps && dynamicAccelRms < 0.75 && cadenceSpm < 50.0)

            if (isStoppedSpeed) {
                continuousStoppedMs = (continuousStoppedMs + dtMs).coerceAtMost(180_000L)
                if (continuousStoppedMs >= PARKED_STOP_REQUIRED_MS) {
                    parkedStopCompleted = true
                }
                dismountWalkConfirmMs = 0L

                // After 12s completely motionless at a stop, let UI rest at STANDING while keeping
                // vehicleCooldownActive armed so a green-light pull-away snaps straight back to VEHICLE!
                if (parkedStopCompleted && continuousStoppedMs >= 12_000L && cadenceSpm < 40.0) {
                    vehicleLatchActive = false
                    committedState = MotionState.STANDING
                    candidateState = MotionState.STANDING
                    candidateSinceMs = nowMs
                }
                return committedState
            }

            // Bike is moving (>= 0.85 m/s or active vibration):
            // Case A: Moving at >= 1.90 m/s (6.8 km/h+) without having dismounted -> STILL ON THE BIKE!
            if (hasGps && gps >= 1.90) {
                val flightNorm = p20RawNorm ?: minRawNorm
                val rareParkedToTrackRun = parkedStopCompleted &&
                    continuousStoppedMs >= 30_000L &&
                    raw.isJogOrRun() &&
                    cadenceSpm >= 145.0 &&
                    dynamicAccelRms >= 4.5 &&
                    (flightNorm != null && flightNorm <= 6.4)
                if (!rareParkedToTrackRun) {
                    enterOrRefreshVehicleLatch(nowMs)
                    return MotionState.VEHICLE
                }
            }

            // Case B: Slow crawl (0.85..1.90 m/s or temporary tunnel GPS loss) WITHOUT having parked for 6s first!
            if (!parkedStopCompleted) {
                enterOrRefreshVehicleLatch(nowMs)
                return MotionState.VEHICLE
            }

            // Case C: Bike DID stop >= 6s (`parkedStopCompleted == true`). Require 5.5s of genuine human walking
            // (72..135 spm at <= 1.90 m/s) to confirm the rider actually stepped off and walked away!
            val plausibleHumanWalk = raw == MotionState.WALKING &&
                cadenceSpm in 72.0..135.0 &&
                dynamicAccelRms in 0.70..5.5 &&
                (!hasGps || gps in 0.50..1.90)

            if (plausibleHumanWalk) {
                dismountWalkConfirmMs += dtMs
                if (dismountWalkConfirmMs >= DISMOUNT_WALK_CONFIRM_MS) {
                    // Verified on-foot dismount complete!
                    vehicleLatchActive = false
                    vehicleCooldownActive = false
                    parkedStopCompleted = false
                    continuousStoppedMs = 0L
                    dismountWalkConfirmMs = 0L
                    committedState = MotionState.WALKING
                    candidateState = MotionState.WALKING
                    candidateSinceMs = nowMs
                    return MotionState.WALKING
                }
            } else {
                dismountWalkConfirmMs = 0L
            }
            return committedState
        }

        // 3. Normal On-Foot Temporal Hysteresis
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
            raw.intensityRank > committedState.intensityRank -> UPGRADE_DEBOUNCE_MS
            else -> DOWNGRADE_DEBOUNCE_MS
        }

        if (nowMs - candidateSinceMs >= requiredHoldMs) {
            committedState = raw
        }
        return committedState
    }

    private fun enterOrRefreshVehicleLatch(nowMs: Long) {
        committedState = MotionState.VEHICLE
        candidateState = MotionState.VEHICLE
        candidateSinceMs = nowMs
        vehicleLatchActive = true
        vehicleCooldownActive = true
        lastVehicleMotionMs = nowMs
        continuousStoppedMs = 0L
        parkedStopCompleted = false
        dismountWalkConfirmMs = 0L
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
 *  - Uses hardware TYPE_STEP_DETECTOR / TYPE_STEP_COUNTER exclusively once the device's hardware pedometer
 *    has proven it emits real step events (`verifiedHardwarePedometerActive = true`, e.g. Samsung S23 LSM6DSO),
 *    so quiet periods while sitting or riding a bike NEVER trigger software vibration overcounting!
 *  - Falls back to orientation-invariant bipolar gait-band accelerometer pedometer ONLY on devices that lack
 *    a step sensor or whose OEM step sensor has emitted 0 events ever (`verifiedHardwarePedometerActive = false`).
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

    var hardwareStepSensorAvailable: Boolean = false

    /**
     * Becomes true as soon as TYPE_STEP_DETECTOR or TYPE_STEP_COUNTER delivers >= 1 real step on this phone
     * (and can be restored from SharedPreferences on startup). Prevents software accelerometer fallback from
     * fabricating steps during bike rides or hand movement when hardware rightly stays silent!
     */
    var verifiedHardwarePedometerActive: Boolean = false

    /**
     * Set to true by RunTrackingService while GaitClassifier's Vehicle Latch / Cooldown is active so
     * road bumps do not inflate totalSteps or trigger software step fallback.
     */
    var suppressStepsForVehicle: Boolean = false

    private var lastActualHardwareStepMs: Long = -100_000L

    // Orientation-invariant scalar baseline + dt-normalized 2-pole ~4.8 Hz gait-band filter
    private var baselineNorm = 9.81
    private var gaitNorm1 = 9.81
    private var gaitNorm2 = 9.81
    private var filterInitialized = false
    private var lastAccelSampleMs = -1L

    // Bipolar peak-and-trough cycle state for fallback software pedometer
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
        verifiedHardwarePedometerActive || ((nowMs - lastActualHardwareStepMs) in 0L..5000L)

    fun reset() {
        stepTimestampsMs.clear()
        accelSamples.clear()
        pendingSoftwareStepMs.clear()
        lastActualHardwareStepMs = -100_000L
        suppressStepsForVehicle = false
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
        verifiedHardwarePedometerActive = true
        lastActualHardwareStepMs = nowMs
        pendingSoftwareStepMs.clear()
        // Always record step timestamp so dismount-walk cadence detection works right after parking,
        // but do NOT inflate step totals while riding a bike!
        recordStepTimestamp(nowMs)
        if (!suppressStepsForVehicle) {
            val c = count.coerceIn(1, 12)
            totalSteps += c
            hardwareStepsCount += c
        }
        pruneOld(nowMs)
    }

    /**
     * Reconciles cumulative Sensor.TYPE_STEP_COUNTER deltas (up to 300 pocket-batched steps)
     * without double-counting steps already captured by TYPE_STEP_DETECTOR, and ignores deltas
     * accumulated while riding a vehicle.
     */
    fun reconcileStepCounterDelta(nowMs: Long, hwDeltaSteps: Int, stepsCountedSinceLastCounter: Int) {
        if (hwDeltaSteps <= 0) return
        verifiedHardwarePedometerActive = true
        if (suppressStepsForVehicle) {
            pruneOld(nowMs)
            return
        }
        val missing = (hwDeltaSteps - stepsCountedSinceLastCounter).coerceIn(0, 300)
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
            if (gap < 235L) return // ignore duplicate burst event on same footstrike
            if (gap > 1650L) {
                stepTimestampsMs.clear()
            }
        }
        stepTimestampsMs.addLast(nowMs)
    }

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
            val sampleRatio = dtSec / 0.02
            val alphaBase = if (abs(sampleRatio - 1.0) < 0.02) 0.06 else (1.0 - Math.pow(0.94, sampleRatio))
            val alphaGait = if (abs(sampleRatio - 1.0) < 0.02) 0.50 else (1.0 - Math.pow(0.50, sampleRatio))
            baselineNorm = (1.0 - alphaBase) * baselineNorm + alphaBase * rawNorm
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

        // Software pedometer ONLY runs when this phone does NOT have a verified working hardware pedometer
        // AND is not currently in Vehicle Latch/Cooldown!
        var detectedStep = false
        val hardwareActivelyFiring = (nowMs - lastActualHardwareStepMs) in 0L..2800L
        val allowSoftwareFallback = !verifiedHardwarePedometerActive && !hardwareActivelyFiring && !suppressStepsForVehicle
        if (allowSoftwareFallback) {
            val minStepIntervalMs = 285L       // caps software cadence at <= 210 spm (rejects engine harmonics)
            val peakArmThreshold = 0.85        // m/s^2 upward footstrike compression above baseline
            val troughConfirmThreshold = -0.40 // m/s^2 unloading dip below baseline
            val minPeakToTroughSwing = 1.45    // m/s^2 total bipolar step swing

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
        } else if (suppressStepsForVehicle) {
            pendingSoftwareStepMs.clear()
            awaitingTrough = false
        }
        return detectedStep
    }

    private fun registerSoftwareStepCandidate(nowMs: Long): Boolean {
        val prevPending = pendingSoftwareStepMs.lastOrNull()
        if (prevPending != null && (nowMs - prevPending) > 1550L) {
            pendingSoftwareStepMs.clear()
        }

        val lastConfirmed = stepTimestampsMs.lastOrNull()
        if (lastConfirmed != null && (nowMs - lastConfirmed) <= 1550L && stepTimestampsMs.size >= 4) {
            pendingSoftwareStepMs.clear()
            recordStepTimestamp(nowMs)
            totalSteps++
            fallbackSoftwareStepsCount++
            return true
        }

        pendingSoftwareStepMs.addLast(nowMs)
        if (pendingSoftwareStepMs.size >= 4) {
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
        if (totalCount < 3) return 0.0

        val lastMs = stepTimestampsMs.last()
        val silenceMs = nowMs - lastMs
        val recentRms = getDynamicAccelRms(nowMs)
        if (silenceMs > 2400L || (silenceMs > 1600L && recentRms < 0.55)) {
            return 0.0
        }

        val startIdx = (totalCount - 6).coerceAtLeast(0)
        val count = totalCount - startIdx
        val intervalsMs = DoubleArray(count - 1)
        var sumMs = 0.0
        for (i in 0 until count - 1) {
            val dt = (stepTimestampsMs[startIdx + i + 1] - stepTimestampsMs[startIdx + i]).toDouble()
            intervalsMs[i] = dt
            sumMs += dt
        }
        val meanPeriodMs = (sumMs / intervalsMs.size).coerceAtLeast(255.0)

        var varianceSum = 0.0
        for (dt in intervalsMs) {
            val diff = dt - meanPeriodMs
            varianceSum += diff * diff
        }
        val stdDevMs = sqrt(varianceSum / intervalsMs.size)
        val coeffOfVariation = stdDevMs / meanPeriodMs
        if (coeffOfVariation > 0.34) {
            return 0.0
        }

        val firstMs = stepTimestampsMs[startIdx]
        val stepSpanMs = (lastMs - firstMs).coerceAtLeast(300L)
        val effectiveSpanMs = if (silenceMs > meanPeriodMs * 1.35) {
            (nowMs - firstMs).toDouble()
        } else {
            stepSpanMs.toDouble()
        }
        return (((count - 1).toDouble() / (effectiveSpanMs / 1000.0)) * 60.0).coerceIn(0.0, 220.0)
    }

    fun getDynamicAccelRms(nowMs: Long): Double {
        pruneOld(nowMs)
        val recent = accelSamples.filter { nowMs - it.timestampMs <= 1500L }
        if (recent.isEmpty()) return 0.0
        val meanSq = recent.sumOf { it.dynamicMag * dynamicWeight(it.dynamicMag) } / recent.size
        return sqrt(meanSq)
    }

    private fun dynamicWeight(mag: Double): Double = mag

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

    /**
     * 20th-percentile gravity-included 3D norm over the last 1.5s.
     * Human jogging/running spends ~25-35% of every stride airborne (< 6.8 m/s^2), whereas a seated
     * motorcycle rider hitting an isolated pothole still has >80% of samples near ~9.8 m/s^2 (> 7.4 m/s^2)!
     */
    fun getP20RawNorm(nowMs: Long): Double? {
        pruneOld(nowMs)
        val vals = ArrayList<Double>(80)
        for (frame in accelSamples) {
            if (nowMs - frame.timestampMs <= 1500L && frame.rawGravityNorm != null) {
                vals.add(frame.rawGravityNorm)
            }
        }
        if (vals.size < 8) return getMinRawNorm(nowMs)
        vals.sort()
        val idx = ((vals.size - 1) * 0.20).toInt().coerceIn(0, vals.size - 1)
        return vals[idx]
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
