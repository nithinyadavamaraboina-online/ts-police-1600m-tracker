package com.tspolice.runtracker.domain

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Multi-sensor biomechanical gait & vehicle classifier that fuses:
 *  1. High-accuracy GPS ground speed (m/s)
 *  2. Hardware step cadence (TYPE_STEP_DETECTOR / TYPE_STEP_COUNTER) with 1-to-2 missed-step healing
 *     plus gait-band accelerometer cadence continuity when holding the phone to view the screen
 *  3. 3-axis gait-band dynamic acceleration RMS (0.5–4.8 Hz) vs high-frequency engine vibration RMS (>6 Hz)
 *  4. Spring-mass bounce detection so Slow-Jogging at Walking Speed (or Spot-Jogging) is recognized as JOGGING
 *  5. Workout-Mode Vehicle Immunity: explicit 1.6 km PT Trials & 400m Intervals NEVER false-trigger Bike/Vehicle
 */
class GaitClassifier(
    var runningSpeedThresholdMps: Double = DEFAULT_RUNNING_SPEED_MPS,
    var targetSpeedThresholdMps: Double = TsPoliceBenchmark.targetSpeedMps(),
    var strideScaleFactor: Double = 1.0
) {
    companion object {
        // Biomechanical Speed Thresholds (m/s)
        const val DEFAULT_WALKING_SPEED_MPS = 0.72       // ~2.6 km/h minimum purposeful walk speed
        const val DEFAULT_JOGGING_SPEED_MPS = 1.90       // ~6.84 km/h (8:46 /km) standard jog transition speed
        const val JOG_RETENTION_SPEED_MPS = 1.65         // ~5.94 km/h Schmitt-trigger hold so Jog never flickers to Walk
        const val DEFAULT_RUNNING_SPEED_MPS = 3.05       // ~10.98 km/h (5:28 /km) running speed
        const val SPRINT_VERIFICATION_SPEED_MPS = 5.30   // ~19.1 km/h — above this in open mode requires sprint impact
        const val MAX_HUMAN_RUNNING_SPEED_MPS = 7.20     // ~25.9 km/h — open-mode bike/vehicle ceiling
        const val MANUAL_WORKOUT_VEHICLE_MPS = 9.50      // ~34.2 km/h — manual 1.6K trials never trigger bike at human speeds

        // Biomechanical Cadence (steps/min) & Bandpass Bounce RMS (m/s^2) Thresholds
        // Note: 2-pole gait bandpass (`gaitNorm2 - baselineNorm`) has 0.69x gain and RMS adds 0.707x (= 0.49x of peak):
        //   - Normal/brisk walk (+/-1.4..2.4 m/s^2 peak) -> bandpass RMS ~0.68..1.18 m/s^2 at 68..126 spm
        //   - Slow-jog / smooth jog (+/-3.8..5.0 m/s^2 peak) -> bandpass RMS ~1.86..2.45 m/s^2 at 133..160 spm
        //   - Running / 7:15 pace (+/-6.2..9.0 m/s^2 peak) -> bandpass RMS ~3.04..4.40 m/s^2 at 162..190+ spm
        const val MIN_WALKING_CADENCE_SPM = 68.0
        const val SLOW_JOG_CADENCE_SPM = 133.0
        const val SLOW_JOG_MIN_BOUNCE_RMS = 1.85
        const val BOUNCY_SLOW_JOG_CADENCE_SPM = 125.0
        const val BOUNCY_SLOW_JOG_RMS = 2.45
        const val JOG_RETENTION_CADENCE_SPM = 122.0
        const val JOG_RETENTION_BOUNCE_RMS = 1.60

        // Temporal Hysteresis Debounce Windows (ms)
        const val STANDING_TO_WALK_DEBOUNCE_MS = 1700L
        const val VEHICLE_DEBOUNCE_MS = 1100L
        const val UPGRADE_DEBOUNCE_MS = 1200L
        const val DOWNGRADE_DEBOUNCE_MS = 2000L          // Resists brief turns/glances; manual workouts extend stand hold to 3.5s

        // Open-Mode Vehicle Latch & Dismount Verification
        const val PARKED_STOP_REQUIRED_MS = 4500L        // 4.5s parked (<0.85 m/s) before on-foot dismount unlocks
        const val DISMOUNT_FOOT_CONFIRM_MS = 3500L       // 3.5s (~6 real steps) of verified walk/jog unlocks foot mode
        const val VEHICLE_COOLDOWN_EXPIRY_MS = 150_000L  // 2.5-min traffic-light memory in passive 24/7 mode
    }

    private var committedState: MotionState = MotionState.STANDING
    private var candidateState: MotionState = MotionState.STANDING
    private var candidateSinceMs: Long = 0L
    private var lastUpdateMs: Long = -1L
    private var noStepFootBridgeMs: Long = 0L

    // Stateful Bike / Vehicle Latch (used ONLY in passive 24/7 mode, NEVER in manual 1.6K / Interval workouts!)
    private var vehicleLatchActive: Boolean = false
    private var vehicleCooldownActive: Boolean = false
    private var lastVehicleMotionMs: Long = -1_000_000L
    private var continuousStoppedMs: Long = 0L
    private var parkedStopCompleted: Boolean = false
    private var dismountFootConfirmMs: Long = 0L

    fun currentState(): MotionState = committedState

    fun candidateState(): MotionState = candidateState

    fun candidateIsJogOrRun(): Boolean =
        !isInOrCoolingDownFromVehicle() && candidateState.isJogOrRun()

    fun isInOrCoolingDownFromVehicle(): Boolean =
        vehicleLatchActive || vehicleCooldownActive ||
            committedState == MotionState.VEHICLE || candidateState == MotionState.VEHICLE

    fun isVehicleLatched(): Boolean = vehicleLatchActive

    /**
     * Immediately clears any passive-mode vehicle latch when the runner arms/starts a 1.6K Trial or 400m Intervals.
     */
    fun clearVehicleLatchForManualWorkout() {
        vehicleLatchActive = false
        vehicleCooldownActive = false
        parkedStopCompleted = false
        continuousStoppedMs = 0L
        dismountFootConfirmMs = 0L
        if (committedState == MotionState.VEHICLE || candidateState == MotionState.VEHICLE) {
            committedState = MotionState.STANDING
            candidateState = MotionState.STANDING
        }
    }

    fun reset() {
        committedState = MotionState.STANDING
        candidateState = MotionState.STANDING
        candidateSinceMs = 0L
        lastUpdateMs = -1L
        noStepFootBridgeMs = 0L
        vehicleLatchActive = false
        vehicleCooldownActive = false
        lastVehicleMotionMs = -1_000_000L
        continuousStoppedMs = 0L
        parkedStopCompleted = false
        dismountFootConfirmMs = 0L
    }

    /**
     * Instant biomechanical classification before temporal hysteresis:
     *  - Explicit manual workouts (`isManualWorkoutActive = true`) NEVER false-trigger `VEHICLE` at human speeds.
     *  - Detects Slow-Jogging at Walking Speed (`0.0–1.85 m/s`) via spring-mass bounce (`>=133 spm & RMS >=3.0`
     *    or `>=125 spm & RMS >=3.75`) so jogging slowly or spot-jogging is classified as `JOGGING`, not `WALKING`.
     *  - Uses Schmitt-trigger retention when `alreadyJogOrRun` is true so `JOGGING` never flickers down to `WALKING`.
     *  - Separates motorcycles/scooters in passive mode via step absence, impossible >2.10m stride, seated low-bounce
     *    cruising, and high-frequency (>6 Hz) engine vibration dominance — NEVER overriding real running footsteps!
     */
    @Suppress("UNUSED_PARAMETER")
    fun classifyInstant(
        gpsSpeedMps: Double?,
        cadenceSpm: Double,
        dynamicAccelRms: Double,
        minRawNorm: Double? = null,
        peakToTroughNorm: Double? = null,
        p20RawNorm: Double? = null,
        hfVibrationRms: Double? = null,
        isManualWorkoutActive: Boolean = false,
        alreadyJogOrRun: Boolean = committedState.isJogOrRun(),
        allowOpenFootGpsBridge: Boolean = false
    ): MotionState {
        val hasValidGps = gpsSpeedMps != null && gpsSpeedMps.isFinite() && gpsSpeedMps >= 0.0
        val hasRhythmicSteps = cadenceSpm >= MIN_WALKING_CADENCE_SPM
        val impliedStepSpeed = estimateSpeedFromStepsMps(cadenceSpm, dynamicAccelRms)

        // True aerial flight unloading + >2g footstrike impact (impossible during flat walking: minNorm ~5.7..7.5, p2t ~7..13).
        // Protects Slow-Jogging from JOGGING -> WALKING ping-pong even if pocket step_detector half-counts single-leg strides (~95..118 spm)!
        val hasAerialFlightSignature =
            dynamicAccelRms >= 2.15 &&
                minRawNorm != null && minRawNorm <= 4.65 &&
                peakToTroughNorm != null && peakToTroughNorm >= 20.0

        // Spring-mass aerial bounce signature (distinguishes Slow-Jogging at walking speed from brisk flat Walking!)
        val hasSpringMassJogBounce =
            (cadenceSpm >= SLOW_JOG_CADENCE_SPM && dynamicAccelRms >= SLOW_JOG_MIN_BOUNCE_RMS) ||
                (cadenceSpm >= BOUNCY_SLOW_JOG_CADENCE_SPM && dynamicAccelRms >= BOUNCY_SLOW_JOG_RMS) ||
                (alreadyJogOrRun && cadenceSpm >= JOG_RETENTION_CADENCE_SPM && dynamicAccelRms >= JOG_RETENTION_BOUNCE_RMS) ||
                (hasRhythmicSteps && hasAerialFlightSignature)

        if (hasValidGps) {
            val gps = gpsSpeedMps!!

            // -----------------------------------------------------------------
            // A. VEHICLE / BIKE REJECTION
            //    In explicit 1.6K PT Trial / 400m Interval mode, nobody turns on
            //    a track test to ride a bike — disable vehicle triggers for all
            //    human running & sprinting speeds (up to 9.5 m/s = 34.2 km/h)!
            // -----------------------------------------------------------------
            if (isManualWorkoutActive) {
                if (gps > MANUAL_WORKOUT_VEHICLE_MPS && dynamicAccelRms < 2.5 && !hasRhythmicSteps) {
                    return MotionState.VEHICLE
                }
            } else {
                // Passive 24/7 All-Day Mode vehicle checks:
                if (gps > MAX_HUMAN_RUNNING_SPEED_MPS) {
                    return MotionState.VEHICLE
                }
                if (gps > SPRINT_VERIFICATION_SPEED_MPS && (cadenceSpm < 164.0 || dynamicAccelRms < 6.0)) {
                    return MotionState.VEHICLE
                }
                // Moving >= 2.10 m/s (7.6 km/h+) with zero rhythmic footsteps = Bike / Scooter / Car
                if (!hasRhythmicSteps && gps >= 2.10) {
                    return MotionState.VEHICLE
                }
                if (hasRhythmicSteps) {
                    val impliedGpsStrideM = gps / (cadenceSpm / 60.0)
                    // Impossible human step length (> 2.10m) or fast cruise with slow bump cadence
                    if (gps >= 2.75 && (impliedGpsStrideM > 2.10 || (gps >= 3.55 && cadenceSpm < 118.0))) {
                        return MotionState.VEHICLE
                    }
                    // Seated smooth scooter/motorcycle saddle: moving >= 3.0 m/s (10.8+ km/h) with tiny <2.25 m/s^2
                    // gait bounce and seated ~1g gravity (impossible during 11+ km/h human running!)
                    if (gps >= 3.00 && dynamicAccelRms < 2.25 &&
                        (minRawNorm == null || minRawNorm > 7.20) &&
                        (peakToTroughNorm == null || peakToTroughNorm < 5.8)
                    ) {
                        return MotionState.VEHICLE
                    }
                    // High-frequency (>6 Hz) engine/chassis vibration dominating low-frequency human gait bounce
                    // (Guard `minRawNorm > 5.80` so sharp pavement heel-strike shockwaves during aerial running never trigger Vehicle!)
                    if (gps >= 2.40 && hfVibrationRms != null &&
                        (minRawNorm == null || minRawNorm > 5.80) &&
                        hfVibrationRms > 2.40 && hfVibrationRms > dynamicAccelRms * 1.30
                    ) {
                        return MotionState.VEHICLE
                    }
                }
            }

            // -----------------------------------------------------------------
            // B. STANDING / STOPPED CHECK
            // -----------------------------------------------------------------
            if (!hasRhythmicSteps) {
                // 1) During an active manual workout, if GPS shows real forward motion (>= 0.85 m/s) AND body is moving
                //    (e.g. holding phone steady to check split time!), keep gait alive from GPS speed instead of shouting "Stopped!"
                if (isManualWorkoutActive && gps >= 0.85 && dynamicAccelRms >= 0.90) {
                    return when {
                        gps >= targetSpeedThresholdMps -> MotionState.TARGET_PACE
                        gps >= runningSpeedThresholdMps -> MotionState.RUNNING
                        gps >= DEFAULT_JOGGING_SPEED_MPS || (alreadyJogOrRun && gps >= JOG_RETENTION_SPEED_MPS) || hasAerialFlightSignature ->
                            MotionState.JOGGING
                        else -> MotionState.WALKING
                    }
                }
                // 2) In normal Open Mode, if already walking/jogging on foot and Samsung's non-wakeup step_detector
                //    briefly pauses batching (<= 4.5s) while GPS + vertical body oscillation prove continuous foot motion,
                //    bridge the gap cleanly instead of falsely dropping to STANDING ("Stopped")!
                if (allowOpenFootGpsBridge &&
                    gps in 0.80..2.08 &&
                    dynamicAccelRms >= 0.95 &&
                    minRawNorm != null && minRawNorm <= 7.75 &&
                    peakToTroughNorm != null && peakToTroughNorm >= 6.0
                ) {
                    return if (hasAerialFlightSignature || (alreadyJogOrRun && dynamicAccelRms >= 1.75)) {
                        MotionState.JOGGING
                    } else {
                        MotionState.WALKING
                    }
                }
                return MotionState.STANDING
            }

            if (gps < DEFAULT_WALKING_SPEED_MPS && cadenceSpm < 74.0 && dynamicAccelRms < 0.80) {
                return MotionState.STANDING
            }

            // -----------------------------------------------------------------
            // C. ON-FOOT GAIT CLASSIFICATION (WALK vs SLOW-JOG vs JOG vs RUN)
            // -----------------------------------------------------------------
            // Case 1: Stationary indoor Wi-Fi location fix (gps < 0.45 m/s) or treadmill / spot-jogging in place!
            if (gps < 0.45) {
                return when {
                    cadenceSpm >= 176.0 && dynamicAccelRms >= 3.85 -> MotionState.TARGET_PACE
                    cadenceSpm >= 162.0 && dynamicAccelRms >= 2.95 -> MotionState.RUNNING
                    hasSpringMassJogBounce -> MotionState.JOGGING
                    else -> MotionState.WALKING
                }
            }

            // Case 2: Outdoor forward movement at Walking ground speed (0.45 .. 2.15 m/s = 1.6 .. 7.7 km/h):
            //  - If the runner is bouncing with jogging cadence (`hasSpringMassJogBounce`), it IS Slow-Jogging (`JOGGING`)
            //    even at 1.1 m/s walking speed! (And capped at JOGGING so 162 spm slow-jogging doesn't fake 11 km/h RUNNING.)
            if (gps < 2.15) {
                return if (gps >= DEFAULT_JOGGING_SPEED_MPS ||
                    (alreadyJogOrRun && gps >= JOG_RETENTION_SPEED_MPS) ||
                    hasSpringMassJogBounce
                ) {
                    MotionState.JOGGING
                } else {
                    MotionState.WALKING
                }
            }

            // Case 3: Outdoor Jogging / Running / 7:15 Target Pace (gps >= 2.15 m/s):
            // Weight real GPS ground speed at 86% so stride-scale variations never block entering RUNNING / TARGET_PACE!
            val fusedSpeed = if (impliedStepSpeed > gps * 1.35 && dynamicAccelRms >= 4.0) {
                maxOf(0.86 * gps + 0.14 * impliedStepSpeed, 0.90 * impliedStepSpeed)
            } else {
                0.86 * gps + 0.14 * impliedStepSpeed
            }

            return when {
                gps >= targetSpeedThresholdMps || fusedSpeed >= targetSpeedThresholdMps ||
                    (cadenceSpm >= 175.0 && dynamicAccelRms >= 7.6 && gps >= 3.25) -> MotionState.TARGET_PACE
                gps >= runningSpeedThresholdMps || fusedSpeed >= runningSpeedThresholdMps ||
                    (cadenceSpm >= 162.0 && dynamicAccelRms >= 5.6 && gps >= 2.45) -> MotionState.RUNNING
                else -> MotionState.JOGGING
            }
        }

        // ---------------------------------------------------------------------
        // D. INDOOR / PRE-GPS-LOCK PATH (gpsSpeedMps == null)
        // ---------------------------------------------------------------------
        if (!hasRhythmicSteps || dynamicAccelRms < 0.65) {
            return MotionState.STANDING
        }
        // Reject pure high-frequency engine/table buzz that lacks < 5 Hz vertical body oscillation
        if (!isManualWorkoutActive) {
            if (peakToTroughNorm != null && peakToTroughNorm < 1.65) {
                return MotionState.STANDING
            }
            if (hfVibrationRms != null && hfVibrationRms > 2.20 && hfVibrationRms > dynamicAccelRms * 1.25) {
                return MotionState.STANDING
            }
        }

        return when {
            cadenceSpm >= 176.0 && dynamicAccelRms >= 3.85 -> MotionState.TARGET_PACE
            cadenceSpm >= 162.0 && dynamicAccelRms >= 2.95 -> MotionState.RUNNING
            hasSpringMassJogBounce -> MotionState.JOGGING
            else -> MotionState.WALKING
        }
    }

    /**
     * Stateful update with workout-aware hysteresis & passive-mode vehicle latch:
     *  - When `isManualWorkoutActive == true` (1.6K PT Trial / 400m Intervals), vehicle latch is completely bypassed
     *    and `STANDING` ("Stopped!") requires 3.6s of genuine zero-motion stillness.
     *  - When `isManualWorkoutActive == false` (24/7 Open Mode), bike slowdowns stay `VEHICLE` until a 4.5s parked
     *    stop followed by 3.5s of verified human footsteps (walking OR jogging!).
     */
    fun update(
        nowMs: Long,
        gpsSpeedMps: Double?,
        cadenceSpm: Double,
        dynamicAccelRms: Double,
        minRawNorm: Double? = null,
        peakToTroughNorm: Double? = null,
        p20RawNorm: Double? = null,
        hfVibrationRms: Double? = null,
        isManualWorkoutActive: Boolean = false
    ): MotionState {
        val dtMs = if (lastUpdateMs >= 0L && nowMs > lastUpdateMs) {
            (nowMs - lastUpdateMs).coerceIn(1L, 2500L)
        } else {
            250L
        }
        lastUpdateMs = nowMs

        if (isManualWorkoutActive && (vehicleLatchActive || vehicleCooldownActive)) {
            clearVehicleLatchForManualWorkout()
        }

        if (vehicleCooldownActive && !vehicleLatchActive && (nowMs - lastVehicleMotionMs) > VEHICLE_COOLDOWN_EXPIRY_MS) {
            vehicleCooldownActive = false
            parkedStopCompleted = false
            dismountFootConfirmMs = 0L
        }

        if (cadenceSpm >= MIN_WALKING_CADENCE_SPM || !committedState.countsAsActiveMovement) {
            noStepFootBridgeMs = 0L
        } else {
            noStepFootBridgeMs = (noStepFootBridgeMs + dtMs).coerceAtMost(60_000L)
        }
        val canBridgeOpenFoot = committedState.countsAsActiveMovement && noStepFootBridgeMs <= 4500L

        val raw = classifyInstant(
            gpsSpeedMps = gpsSpeedMps,
            cadenceSpm = cadenceSpm,
            dynamicAccelRms = dynamicAccelRms,
            minRawNorm = minRawNorm,
            peakToTroughNorm = peakToTroughNorm,
            p20RawNorm = p20RawNorm,
            hfVibrationRms = hfVibrationRms,
            isManualWorkoutActive = isManualWorkoutActive,
            alreadyJogOrRun = committedState.isJogOrRun(),
            allowOpenFootGpsBridge = canBridgeOpenFoot
        )

        // 1. Entry into VEHICLE (Passive 24/7 mode only, or >34 km/h car in manual mode)
        if (raw == MotionState.VEHICLE) {
            val fastVehicle = !isManualWorkoutActive && (
                vehicleLatchActive || vehicleCooldownActive ||
                    (gpsSpeedMps != null && gpsSpeedMps >= 5.50 && cadenceSpm < 68.0)
                )
            if (fastVehicle) {
                enterOrRefreshVehicleLatch(nowMs)
                return MotionState.VEHICLE
            }
            if (candidateState != MotionState.VEHICLE) {
                candidateState = MotionState.VEHICLE
                candidateSinceMs = nowMs
                return committedState
            }
            val holdNeeded = if (isManualWorkoutActive) 5000L else VEHICLE_DEBOUNCE_MS
            if (nowMs - candidateSinceMs >= holdNeeded) {
                if (!isManualWorkoutActive) {
                    enterOrRefreshVehicleLatch(nowMs)
                } else {
                    committedState = MotionState.VEHICLE
                }
                return MotionState.VEHICLE
            }
            return committedState
        }

        // 2. Enforce Passive-Mode Sticky Vehicle Latch & Traffic-Light Cooldown
        if (!isManualWorkoutActive && (vehicleLatchActive || vehicleCooldownActive)) {
            val hasGps = gpsSpeedMps != null && gpsSpeedMps.isFinite() && gpsSpeedMps >= 0.0
            val gps = gpsSpeedMps ?: -1.0
            val isStoppedSpeed = (hasGps && gps < 0.85) ||
                (!hasGps && dynamicAccelRms < 0.75 && cadenceSpm < 50.0)

            if (isStoppedSpeed) {
                continuousStoppedMs = (continuousStoppedMs + dtMs).coerceAtMost(180_000L)
                if (continuousStoppedMs >= PARKED_STOP_REQUIRED_MS) {
                    parkedStopCompleted = true
                }
                dismountFootConfirmMs = 0L

                if (parkedStopCompleted && continuousStoppedMs >= 10_000L && cadenceSpm < 45.0) {
                    vehicleLatchActive = false
                    committedState = MotionState.STANDING
                    candidateState = MotionState.STANDING
                    candidateSinceMs = nowMs
                }
                return committedState
            }

            // Moving without having parked for >= 4.5s first -> still slowing down / crawling on the bike!
            if (!parkedStopCompleted) {
                enterOrRefreshVehicleLatch(nowMs)
                return MotionState.VEHICLE
            }

            // Bike DID park >= 4.5s: check if rider genuinely stepped off and started walking or jogging on foot!
            val noEngineDominance = hfVibrationRms == null || hfVibrationRms <= dynamicAccelRms * 1.10
            val plausibleDismountWalk = raw == MotionState.WALKING &&
                cadenceSpm in 70.0..138.0 &&
                dynamicAccelRms in 0.70..5.5 &&
                (!hasGps || gps in 0.45..1.95) &&
                noEngineDominance
            val plausibleDismountJog = raw.isJogOrRun() &&
                cadenceSpm in 130.0..195.0 &&
                dynamicAccelRms >= 3.2 &&
                (!hasGps || gps in 0.50..4.50) &&
                (minRawNorm != null && minRawNorm <= 6.8) &&
                noEngineDominance

            if (plausibleDismountWalk || plausibleDismountJog) {
                dismountFootConfirmMs += dtMs
                if (dismountFootConfirmMs >= DISMOUNT_FOOT_CONFIRM_MS) {
                    val unlockedState = if (plausibleDismountJog) MotionState.JOGGING else MotionState.WALKING
                    vehicleLatchActive = false
                    vehicleCooldownActive = false
                    parkedStopCompleted = false
                    continuousStoppedMs = 0L
                    dismountFootConfirmMs = 0L
                    committedState = unlockedState
                    candidateState = unlockedState
                    candidateSinceMs = nowMs
                    return unlockedState
                }
                return committedState
            } else {
                dismountFootConfirmMs = 0L
                if (hasGps && gps >= 1.90) {
                    enterOrRefreshVehicleLatch(nowMs)
                    return MotionState.VEHICLE
                }
                return committedState
            }
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
            committedState == MotionState.STANDING && raw.isJogOrRun() ->
                if (isManualWorkoutActive) 900L else STANDING_TO_WALK_DEBOUNCE_MS
            raw == MotionState.STANDING && isManualWorkoutActive -> 3500L // never shout "Stopped!" on a brief 1-2s stumble/turn!
            committedState.isJogOrRun() && raw == MotionState.WALKING -> 3500L // smooth anti-flicker hold for Jogging -> Walking
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
        dismountFootConfirmMs = 0L
    }

    /**
     * Impulse-aware biomechanical step ("stride") length curve (meters/step):
     *  - Walking (68–128 spm): ~0.66–0.85 m/step
     *  - Slow-Jogging at walking speed (128–162 spm, light bounce RMS ~3.0–3.6): ~0.58–0.76 m/step
     *  - Normal Jogging (132–162 spm, moderate bounce RMS ~4.2–5.5): ~0.86–1.06 m/step
     *  - Running / 7:15 Target Pace (160–195 spm, strong drive RMS >=6.0): ~1.14–1.46 m/step
     */
    fun estimateStrideLengthMeters(cadenceSpm: Double, dynamicAccelRms: Double): Double {
        if (cadenceSpm <= 25.0) return 0.0
        val baseStride = when {
            cadenceSpm < 100.0 -> 0.68 + ((cadenceSpm - 60.0).coerceAtLeast(0.0) * 0.003)
            cadenceSpm < 126.0 -> 0.80 + ((cadenceSpm - 100.0) * 0.0035)
            cadenceSpm < 163.0 -> {
                // Separate short-step Slow-Jogging from full-stride Jogging using vertical/forward impulse!
                val fullJogStride = 0.90 + ((cadenceSpm - 126.0) * 0.0072)
                val slowJogFactor = (0.65 + ((dynamicAccelRms - 1.85).coerceAtLeast(0.0) / 0.85) * 0.35).coerceIn(0.65, 1.04)
                fullJogStride * slowJogFactor
            }
            cadenceSpm < 185.0 -> {
                val fullRunStride = 1.17 + ((cadenceSpm - 163.0) * 0.0095)
                val driveFactor = (0.76 + ((dynamicAccelRms - 2.60).coerceAtLeast(0.0) / 1.40) * 0.28).coerceIn(0.76, 1.04)
                fullRunStride * driveFactor
            }
            else -> 1.39
        }
        val impulseBoost = when {
            dynamicAccelRms >= 8.5 -> 1.04
            dynamicAccelRms >= 6.5 -> 1.02
            else -> 1.00
        }
        val personalScale = strideScaleFactor.coerceIn(
            SamsungHealthComparator.MIN_STRIDE_SCALE,
            SamsungHealthComparator.MAX_STRIDE_SCALE
        )
        return (baseStride * impulseBoost * personalScale).coerceIn(0.45, 1.72)
    }

    /**
     * Computes live step ("stride") length in meters: uses direct GPS speed / step-rate when GPS is locked
     * outdoors (capturing exact 0.52m slow-jog steps vs 1.30m 7:15 running strides), falling back to the
     * impulse-aware biomechanical curve indoors.
     */
    fun computeLiveStrideLengthMeters(
        gpsSpeedMps: Double?,
        cadenceSpm: Double,
        dynamicAccelRms: Double,
        motionState: MotionState
    ): Double {
        if (!motionState.countsAsActiveMovement || cadenceSpm < 55.0) return 0.0
        val stepsPerSec = cadenceSpm / 60.0
        if (gpsSpeedMps != null && gpsSpeedMps.isFinite() && gpsSpeedMps >= 0.55) {
            return (gpsSpeedMps / stepsPerSec).coerceIn(0.38, 1.88)
        }
        return estimateStrideLengthMeters(cadenceSpm, dynamicAccelRms)
    }

    fun estimateSpeedFromStepsMps(cadenceSpm: Double, dynamicAccelRms: Double): Double {
        if (cadenceSpm <= 25.0) return 0.0
        val strideMeters = estimateStrideLengthMeters(cadenceSpm, dynamicAccelRms)
        return (cadenceSpm / 60.0) * strideMeters
    }
}

/**
 * Sliding-window step cadence tracker + hybrid pedometer:
 *  - Counts passive all-day steps (`totalSteps`) strictly from hardware `TYPE_STEP_DETECTOR` / `TYPE_STEP_COUNTER`
 *    once verified on the device (keeping 1:1 parity with Samsung Health and 0 bike overcounting).
 *  - Heals 1–2 dropped hardware step events in `getCadenceSpm` (common when glancing at the phone screen in hand!)
 *    AND maintains gait-band accelerometer cadence continuity (`accelStepTimestampsMs`) so holding the phone while
 *    jogging/running NEVER drops cadence to 0 ("Bike detected!" / "Stopped!") or halves cadence to "Walking!".
 */
class MotionSignalProcessor {
    private data class AccelFrame(
        val timestampMs: Long,
        val dynamicMag: Double,
        val rawGravityNorm: Double?,
        val hfResidualMag: Double = 0.0
    )

    private val stepTimestampsMs = ArrayDeque<Long>()
    private val accelStepTimestampsMs = ArrayDeque<Long>()
    private val accelSamples = ArrayDeque<AccelFrame>()
    private val pendingSoftwareStepMs = ArrayDeque<Long>()

    var hardwareStepSensorAvailable: Boolean = false
    var verifiedHardwarePedometerActive: Boolean = false
    var suppressStepsForVehicle: Boolean = false
    var manualWorkoutActive: Boolean = false

    private var lastActualHardwareStepMs: Long = -100_000L

    // Orientation-invariant scalar baseline + dt-normalized 2-pole ~4.8 Hz gait-band filter
    private var baselineNorm = 9.81
    private var gaitNorm1 = 9.81
    private var gaitNorm2 = 9.81
    private var filterInitialized = false
    private var lastAccelSampleMs = -1L

    // Bipolar peak-and-trough cycle state for gait-band cadence & fallback pedometer
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
        accelStepTimestampsMs.clear()
        accelSamples.clear()
        pendingSoftwareStepMs.clear()
        lastActualHardwareStepMs = -100_000L
        suppressStepsForVehicle = false
        manualWorkoutActive = false
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
        recordTimestampInQueue(stepTimestampsMs, nowMs)
        if (!suppressStepsForVehicle) {
            val c = count.coerceIn(1, 12)
            totalSteps += c
            hardwareStepsCount += c
        }
        pruneOld(nowMs)
    }

    fun reconcileStepCounterDelta(nowMs: Long, hwDeltaSteps: Int, stepsCountedSinceLastCounter: Int) {
        if (hwDeltaSteps <= 0) return
        verifiedHardwarePedometerActive = true
        lastActualHardwareStepMs = nowMs
        if (suppressStepsForVehicle) {
            pruneOld(nowMs)
            return
        }
        val missing = (hwDeltaSteps - stepsCountedSinceLastCounter).coerceIn(0, 300)
        if (missing > 0) {
            totalSteps += missing
            hardwareStepsCount += missing
            recordTimestampInQueue(stepTimestampsMs, nowMs)
        }
        pruneOld(nowMs)
    }

    private fun recordTimestampInQueue(queue: ArrayDeque<Long>, nowMs: Long) {
        val prev = queue.lastOrNull()
        if (prev != null) {
            val gap = nowMs - prev
            if (gap < 285L) return
            if (gap > 2200L) {
                queue.clear()
            }
        }
        queue.addLast(nowMs)
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
        val hfResidual = if (includesGravity) abs(rawNorm - gaitNorm2) else 0.0

        accelSamples.addLast(
            AccelFrame(
                timestampMs = nowMs,
                dynamicMag = mag,
                rawGravityNorm = if (includesGravity) rawNorm else null,
                hfResidualMag = hfResidual
            )
        )
        pruneOld(nowMs)

        if (suppressStepsForVehicle) {
            pendingSoftwareStepMs.clear()
            accelStepTimestampsMs.clear()
            awaitingTrough = false
            return false
        }

        val hardwareActivelyFiring = (nowMs - lastActualHardwareStepMs) in 0L..2800L
        // Count fallback steps into `totalSteps` ONLY on devices without a verified HW pedometer,
        // OR during an explicit manual 1.6K/Interval workout if HW step_detector went silent >3.2s while holding phone!
        val countIntoStepTotals = (!verifiedHardwarePedometerActive && !hardwareActivelyFiring) ||
            (manualWorkoutActive && (nowMs - lastActualHardwareStepMs) > 3200L)

        var detectedStep = false
        val minStepIntervalMs = 290L       // caps cadence at <= 207 spm (rejects engine harmonics & pocket double-slaps)
        val peakArmThreshold = 0.78        // m/s^2 upward footstrike compression above baseline
        val troughConfirmThreshold = -0.36 // m/s^2 unloading dip below baseline
        val minPeakToTroughSwing = 1.30    // m/s^2 total bipolar step swing

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
                recordGaitCadenceCycle(nowMs)
                if (countIntoStepTotals) {
                    detectedStep = registerSoftwareStepCandidate(nowMs)
                }
            }
        }
        return detectedStep
    }

    private fun isSeatedEngineBuzzDominant(nowMs: Long, recentRms: Double, recentHf: Double): Boolean {
        val minNorm = getMinRawNorm(nowMs)
        // Human aerial flight unloading (< 5.8 m/s^2) produces sharp asphalt heel-strike transients in `hfResidual`
        // (p2t ~30..52 m/s^2) that must NEVER be mistaken for seated scooter/motorcycle engine vibration!
        val hasAerialUnloading = minNorm != null && minNorm <= 5.80
        return !hasAerialUnloading && recentHf > 2.40 && recentHf > recentRms * 1.30
    }

    private fun recordGaitCadenceCycle(nowMs: Long) {
        val recentRms = getDynamicAccelRms(nowMs)
        val recentHf = getHighFreqVibrationRms(nowMs)
        if (isSeatedEngineBuzzDominant(nowMs, recentRms, recentHf)) return
        recordTimestampInQueue(accelStepTimestampsMs, nowMs)
    }

    private fun registerSoftwareStepCandidate(nowMs: Long): Boolean {
        val prevPending = pendingSoftwareStepMs.lastOrNull()
        if (prevPending != null && (nowMs - prevPending) > 1550L) {
            pendingSoftwareStepMs.clear()
        }

        val lastConfirmed = stepTimestampsMs.lastOrNull()
        if (lastConfirmed != null && (nowMs - lastConfirmed) <= 1550L && stepTimestampsMs.size >= 4) {
            pendingSoftwareStepMs.clear()
            recordTimestampInQueue(stepTimestampsMs, nowMs)
            totalSteps++
            fallbackSoftwareStepsCount++
            return true
        }

        pendingSoftwareStepMs.addLast(nowMs)
        if (pendingSoftwareStepMs.size >= 4) {
            while (pendingSoftwareStepMs.isNotEmpty()) {
                val ts = pendingSoftwareStepMs.removeFirst()
                recordTimestampInQueue(stepTimestampsMs, ts)
                totalSteps++
                fallbackSoftwareStepsCount++
            }
            return true
        }
        return false
    }

    /**
     * Computes live step cadence (spm) with:
     *  1. Automatic 1-to-2 missed-step interval healing (so if Samsung's step_detector drops a step while you
     *     look at your phone screen, 150 spm stays 150 spm instead of dropping to 0 or 120 spm!).
     *  2. Seamless gait-band accelerometer continuity during active workouts or brief hand-hold stabilization.
     */
    fun getCadenceSpm(nowMs: Long): Double {
        pruneOld(nowMs)
        val recentRms = getDynamicAccelRms(nowMs)
        val recentHf = getHighFreqVibrationRms(nowMs)
        val hwCad = computeCadenceFromQueue(stepTimestampsMs, nowMs, recentRms)

        val allowAccelContinuity = !suppressStepsForVehicle &&
            !isSeatedEngineBuzzDominant(nowMs, recentRms, recentHf) &&
            (
                !verifiedHardwarePedometerActive ||
                    manualWorkoutActive ||
                    ((nowMs - lastActualHardwareStepMs) in 0L..8_500L && recentRms >= 0.85)
                )

        if (!allowAccelContinuity) {
            return hwCad
        }

        val accelCad = if (accelStepTimestampsMs.size >= 4) {
            computeCadenceFromQueue(accelStepTimestampsMs, nowMs, recentRms)
        } else 0.0

        return when {
            // Heal pocket single-leg step_detector half-counting (~95..118 spm) when gait-band accel sees true ~140..165 spm!
            hwCad >= 65.0 && accelCad > hwCad * 1.12 && recentRms >= 1.55 -> accelCad
            hwCad >= 65.0 -> hwCad
            else -> accelCad
        }
    }

    private fun computeCadenceFromQueue(
        queue: ArrayDeque<Long>,
        nowMs: Long,
        recentRms: Double
    ): Double {
        val totalCount = queue.size
        if (totalCount < 3) return 0.0

        val lastMs = queue.last()
        val silenceMs = nowMs - lastMs
        val maxSilenceMs = if (recentRms >= 1.00) 3400L else 2200L
        if (silenceMs > maxSilenceMs || (silenceMs > 1600L && recentRms < 0.55)) {
            return 0.0
        }

        val startIdx = (totalCount - 7).coerceAtLeast(0)
        val count = totalCount - startIdx
        val rawIntervals = DoubleArray(count - 1)
        for (i in 0 until count - 1) {
            rawIntervals[i] = (queue[startIdx + i + 1] - queue[startIdx + i]).toDouble()
        }

        // Heal isolated 1-step or 2-step hardware sensor drops using lower-median reference period
        // (prevents 1-2 short pocket-bounce intervals from halving normal 550ms walking intervals into 216 spm!)
        val sortedRaw = rawIntervals.sortedArray()
        val refPeriodMs = sortedRaw[(sortedRaw.size - 1) / 2].coerceAtLeast(285.0)
        val healedIntervals = ArrayList<Double>(rawIntervals.size + 4)
        for (dt in rawIntervals) {
            val ratio = dt / refPeriodMs
            when {
                ratio in 1.66..2.38 && dt / 2.0 >= 320.0 -> {
                    healedIntervals.add(dt / 2.0)
                    healedIntervals.add(dt / 2.0)
                }
                ratio in 2.66..3.38 && dt / 3.0 >= 320.0 -> {
                    healedIntervals.add(dt / 3.0)
                    healedIntervals.add(dt / 3.0)
                    healedIntervals.add(dt / 3.0)
                }
                else -> healedIntervals.add(dt)
            }
        }

        val meanPeriodMs = (healedIntervals.sum() / healedIntervals.size).coerceAtLeast(285.0)
        var varianceSum = 0.0
        for (dt in healedIntervals) {
            val diff = dt - meanPeriodMs
            varianceSum += diff * diff
        }
        val stdDevMs = sqrt(varianceSum / healedIntervals.size)
        val coeffOfVariation = stdDevMs / meanPeriodMs
        if (coeffOfVariation > 0.48) {
            return 0.0
        }

        val decayThresholdRatio = if (recentRms >= 1.25) 2.65 else 1.45
        val totalSpanMs = if (silenceMs > meanPeriodMs * decayThresholdRatio) {
            healedIntervals.sum() + (silenceMs - meanPeriodMs * (decayThresholdRatio - 1.0))
        } else {
            healedIntervals.sum()
        }.coerceAtLeast(300.0)

        return ((healedIntervals.size.toDouble() / (totalSpanMs / 1000.0)) * 60.0).coerceIn(0.0, 210.0)
    }

    fun getDynamicAccelRms(nowMs: Long): Double {
        pruneOld(nowMs)
        val recent = accelSamples.filter { nowMs - it.timestampMs <= 1500L }
        if (recent.isEmpty()) return 0.0
        val meanSq = recent.sumOf { it.dynamicMag * it.dynamicMag } / recent.size
        return sqrt(meanSq)
    }

    fun getHighFreqVibrationRms(nowMs: Long): Double {
        pruneOld(nowMs)
        val recent = accelSamples.filter { nowMs - it.timestampMs <= 1500L }
        if (recent.isEmpty()) return 0.0
        val meanSq = recent.sumOf { it.hfResidualMag * it.hfResidualMag } / recent.size
        return sqrt(meanSq)
    }

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
        while (stepTimestampsMs.isNotEmpty() && nowMs - stepTimestampsMs.first() > 5500L) {
            stepTimestampsMs.removeFirst()
        }
        while (accelStepTimestampsMs.isNotEmpty() && nowMs - accelStepTimestampsMs.first() > 5500L) {
            accelStepTimestampsMs.removeFirst()
        }
        while (accelSamples.isNotEmpty() && nowMs - accelSamples.first().timestampMs > 2500L) {
            accelSamples.removeFirst()
        }
    }
}
