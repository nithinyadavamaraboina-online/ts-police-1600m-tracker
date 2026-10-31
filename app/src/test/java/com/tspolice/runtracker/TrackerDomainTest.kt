package com.tspolice.runtracker

import com.tspolice.runtracker.domain.DailyActivitySummary
import com.tspolice.runtracker.domain.ForecastStatus
import com.tspolice.runtracker.domain.GaitClassifier
import com.tspolice.runtracker.domain.HourlySensorBucket
import com.tspolice.runtracker.domain.MotionSignalProcessor
import com.tspolice.runtracker.domain.MotionState
import com.tspolice.runtracker.domain.MotionStateAudioCue
import com.tspolice.runtracker.domain.PocketAudioMode
import com.tspolice.runtracker.domain.ProgressionForecaster
import com.tspolice.runtracker.domain.PtTrialPhase
import com.tspolice.runtracker.domain.RunMode
import com.tspolice.runtracker.domain.RunSessionRecord
import com.tspolice.runtracker.domain.SamsungDailySnapshot
import com.tspolice.runtracker.domain.SamsungHealthComparator
import com.tspolice.runtracker.domain.TsPoliceBenchmark
import com.tspolice.runtracker.telemetry.TuningTelemetryManager
import com.tspolice.runtracker.update.VersionComparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class TrackerDomainTest {

    @Test
    fun `ts police 1600m benchmark constants match 7m15s cutoff`() {
        assertEquals(435.0, TsPoliceBenchmark.DEFAULT_TARGET_TIME_SEC, 0.001)
        assertEquals(108.75, TsPoliceBenchmark.targetLap400mSec(), 0.001)
        assertEquals(3.678, TsPoliceBenchmark.targetSpeedMps(), 0.01)
        assertEquals("7:15", TsPoliceBenchmark.formatDuration(435.0))
        assertEquals("1:48.8", TsPoliceBenchmark.formatDuration(108.75, includeTenths = true))
    }

    @Test
    fun `gait classifier distinguishes standing walking jogging running and target pace`() {
        val classifier = GaitClassifier()

        assertEquals(
            MotionState.STANDING,
            classifier.classifyInstant(gpsSpeedMps = 0.05, cadenceSpm = 0.0, dynamicAccelRms = 0.15)
        )
        assertEquals(
            MotionState.WALKING,
            classifier.classifyInstant(gpsSpeedMps = 1.30, cadenceSpm = 104.0, dynamicAccelRms = 2.4)
        )
        assertEquals(
            MotionState.JOGGING,
            classifier.classifyInstant(gpsSpeedMps = 2.35, cadenceSpm = 148.0, dynamicAccelRms = 5.1)
        )
        assertEquals(
            MotionState.RUNNING,
            classifier.classifyInstant(gpsSpeedMps = 3.30, cadenceSpm = 168.0, dynamicAccelRms = 7.4)
        )
        assertEquals(
            MotionState.TARGET_PACE,
            classifier.classifyInstant(gpsSpeedMps = 3.82, cadenceSpm = 178.0, dynamicAccelRms = 8.9)
        )
    }

    @Test
    fun `gait classifier works indoors without gps lock and enforces hysteresis`() {
        val classifier = GaitClassifier()

        // Indoor cadence + acceleration only
        assertEquals(
            MotionState.JOGGING,
            classifier.classifyInstant(gpsSpeedMps = null, cadenceSpm = 146.0, dynamicAccelRms = 4.9)
        )
        assertEquals(
            MotionState.RUNNING,
            classifier.classifyInstant(gpsSpeedMps = null, cadenceSpm = 169.0, dynamicAccelRms = 7.6)
        )

        // Hysteresis upgrade requires >= 1800ms sustained
        assertEquals(MotionState.STANDING, classifier.update(1000L, 3.4, 170.0, 7.8))
        assertEquals(MotionState.STANDING, classifier.update(2000L, 3.4, 170.0, 7.8))
        assertEquals(MotionState.RUNNING, classifier.update(2900L, 3.4, 170.0, 7.8))

        // Brief 1-second stop does NOT prematurely auto-pause
        assertEquals(MotionState.RUNNING, classifier.update(3500L, 0.0, 0.0, 0.2))
        assertEquals(MotionState.RUNNING, classifier.update(5000L, 0.0, 0.0, 0.2))
        // Sustained 3-second stop auto-pauses cleanly
        assertEquals(MotionState.STANDING, classifier.update(6500L, 0.0, 0.0, 0.2))
    }

    @Test
    fun `accelerometer peak fallback detects running cadence without hardware step sensor`() {
        val dsp = MotionSignalProcessor()
        // Simulate 168 spm (2.8 Hz) dynamic running vertical impulse over 6 seconds at 50 Hz
        val freqHz = 2.8
        for (i in 0..300) {
            val tMs = i * 20L
            val tSec = tMs / 1000.0
            val wave = (4.5 * kotlin.math.max(0.0, sin(2.0 * Math.PI * freqHz * tSec))).toFloat()
            dsp.onAccelerationSample(nowMs = tMs, ax = 0f, ay = wave, az = 0f, includesGravity = false)
        }
        val cadence = dsp.getCadenceSpm(6000L)
        assertTrue("Expected cadence near 168 spm, got $cadence", cadence in 150.0..185.0)
        assertTrue(dsp.totalSteps >= 14)
    }

    @Test
    fun `progression forecaster estimates days to 7m15s and resists easy jog distortion`() {
        val runs = listOf(
            session("2026-10-12", 1600.0, 505.0, RunMode.TIMED_1600M),
            session("2026-10-15", 1600.0, 493.0, RunMode.TIMED_1600M),
            session("2026-10-17", 2800.0, 990.0, RunMode.OPEN_PRACTICE), // easy aerobic jog
            session("2026-10-19", 1600.0, 480.0, RunMode.TIMED_1600M),
            session("2026-10-23", 1600.0, 468.0, RunMode.TIMED_1600M)
        )

        val forecast = ProgressionForecaster.analyze(
            sessions = runs,
            targetTimeSec = 435.0,
            todayIso = "2026-10-25"
        )

        assertEquals(ForecastStatus.ON_TRACK, forecast.status)
        assertEquals(468.0, forecast.personalBestSec ?: 0.0, 0.1)
        assertEquals(33.0, forecast.gapToTargetSec ?: 0.0, 0.1)
        assertNotNull(forecast.estimatedDaysToTarget)
        assertTrue("Expected ~10..22 days remaining, got ${forecast.estimatedDaysToTarget}",
            forecast.estimatedDaysToTarget!! in 8..25)
        assertNotNull(forecast.projectedTargetDateIso)
    }

    @Test
    fun `progression forecaster marks sub 435s runner as qualified`() {
        val runs = listOf(
            session("2026-10-18", 1600.0, 452.0, RunMode.TIMED_1600M),
            session("2026-10-25", 1600.0, 431.4, RunMode.TIMED_1600M)
        )
        val forecast = ProgressionForecaster.analyze(runs, targetTimeSec = 435.0, todayIso = "2026-10-25")
        assertEquals(ForecastStatus.QUALIFIED, forecast.status)
        assertEquals(0, forecast.estimatedDaysToTarget)
        assertEquals(1, forecast.qualifiedRunCount)
    }

    @Test
    fun `ota version comparator detects newer versionCode and semver accurately`() {
        assertTrue(VersionComparator.isRemoteNewer(1, "1.0.0", 2, "1.1.0"))
        assertFalse(VersionComparator.isRemoteNewer(2, "1.1.0", 2, "1.1.0"))
        assertFalse(VersionComparator.isRemoteNewer(3, "1.2.0", 2, "1.1.0"))
        // Fallback when remote versionCode is 0 (pure GitHub tag comparison)
        assertTrue(VersionComparator.isRemoteNewer(1, "1.0.0", 0, "v1.0.1"))
        assertFalse(VersionComparator.isRemoteNewer(1, "1.2.0", 0, "v1.1.9"))
    }

    @Test
    fun `motion state audio cue builds clear spoken transitions across states`() {
        assertEquals(
            "Walking.",
            MotionStateAudioCue.buildTransitionSpeech(MotionState.STANDING, MotionState.WALKING, isRecording = true)
        )
        assertEquals(
            "Jogging.",
            MotionStateAudioCue.buildTransitionSpeech(MotionState.WALKING, MotionState.JOGGING, isRecording = true)
        )
        assertEquals(
            "Running.",
            MotionStateAudioCue.buildTransitionSpeech(MotionState.JOGGING, MotionState.RUNNING, isRecording = true)
        )
        assertEquals(
            "Target pace! On 7 15 speed.",
            MotionStateAudioCue.buildTransitionSpeech(MotionState.RUNNING, MotionState.TARGET_PACE, isRecording = true, targetTimeSec = 435.0)
        )
        assertEquals(
            "Running. Below target pace.",
            MotionStateAudioCue.buildTransitionSpeech(MotionState.TARGET_PACE, MotionState.RUNNING, isRecording = true)
        )
        assertEquals(
            "Jogging. Pace dropped.",
            MotionStateAudioCue.buildTransitionSpeech(MotionState.RUNNING, MotionState.JOGGING, isRecording = true)
        )
        assertEquals(
            "Standing. Auto paused.",
            MotionStateAudioCue.buildTransitionSpeech(MotionState.JOGGING, MotionState.STANDING, isRecording = true)
        )
        assertEquals(
            "Standing.",
            MotionStateAudioCue.buildTransitionSpeech(MotionState.WALKING, MotionState.STANDING, isRecording = false)
        )
        assertEquals(
            "Bike or vehicle. Ignored.",
            MotionStateAudioCue.buildTransitionSpeech(MotionState.STANDING, MotionState.VEHICLE, isRecording = false)
        )
        assertEquals(
            "Vehicle detected. Run paused.",
            MotionStateAudioCue.buildTransitionSpeech(MotionState.RUNNING, MotionState.VEHICLE, isRecording = true)
        )
    }

    @Test
    fun `pocket gait classifier detects walking jogging and running even when indoor gps speed is zero`() {
        val classifier = GaitClassifier()

        // Stationary indoor GPS (0.0 m/s) must NOT suppress real footstrikes in a pocket
        assertEquals(
            MotionState.WALKING,
            classifier.classifyInstant(gpsSpeedMps = 0.0, cadenceSpm = 102.0, dynamicAccelRms = 2.2)
        )
        assertEquals(
            MotionState.JOGGING,
            classifier.classifyInstant(gpsSpeedMps = 0.0, cadenceSpm = 148.0, dynamicAccelRms = 4.6)
        )
        assertEquals(
            MotionState.RUNNING,
            classifier.classifyInstant(gpsSpeedMps = 0.0, cadenceSpm = 170.0, dynamicAccelRms = 7.2)
        )
    }

    @Test
    fun `sitting and tilting or moving phone in hand never triggers false walking`() {
        val dsp = MotionSignalProcessor()
        val classifier = GaitClassifier()

        // 1. Simulate sitting in a chair and tilting/rotating the phone back and forth by 90 degrees
        //    plus light hand/scroll movement while indoor GPS drifts up to 1.2 m/s!
        for (i in 0..250) {
            val tMs = i * 20L
            val angle = sin(i * 0.08) * (Math.PI / 2.0) // full 90-degree wrist tilt!
            val handFidget = (0.35 * sin(i * 0.37)).toFloat()
            val ax = (9.81 * sin(angle)).toFloat() + handFidget
            val ay = (0.25 * kotlin.math.cos(i * 0.21)).toFloat()
            val az = (9.81 * kotlin.math.cos(angle)).toFloat()
            dsp.onAccelerationSample(nowMs = tMs, ax = ax, ay = ay, az = az, includesGravity = true)

            val state = classifier.update(
                nowMs = tMs,
                gpsSpeedMps = 1.20, // indoor GPS multipath wander while sitting!
                cadenceSpm = dsp.getCadenceSpm(tMs),
                dynamicAccelRms = dsp.getDynamicAccelRms(tMs)
            )
            assertEquals("Expected STANDING while sitting & tilting phone at ${tMs}ms", MotionState.STANDING, state)
        }

        // 2. Simulate 2 isolated chair-shift bumps — fewer than 4 rhythmic steps must stay STANDING
        dsp.onHardwareStepDetected(6000L, 1)
        dsp.onHardwareStepDetected(6800L, 1)
        assertEquals(0.0, dsp.getCadenceSpm(7000L), 0.001)
        assertEquals(
            MotionState.STANDING,
            classifier.update(7000L, gpsSpeedMps = 0.8, cadenceSpm = dsp.getCadenceSpm(7000L), dynamicAccelRms = 1.1)
        )
    }

    @Test
    fun `riding a bike or scooter never classifies as running even on bumpy roads`() {
        val classifier = GaitClassifier()

        // 1. Bike cruising at 18 km/h (5.0 m/s) with road vibration (3.8 m/s^2) and 0 footsteps -> VEHICLE (not RUNNING!)
        assertEquals(
            MotionState.VEHICLE,
            classifier.classifyInstant(gpsSpeedMps = 5.0, cadenceSpm = 0.0, dynamicAccelRms = 3.8)
        )
        assertFalse(MotionState.VEHICLE.countsAsActiveMovement)

        // 2. Slow scooter in traffic at 12.6 km/h (3.5 m/s — exact running speed) without footsteps -> VEHICLE
        assertEquals(
            MotionState.VEHICLE,
            classifier.classifyInstant(gpsSpeedMps = 3.5, cadenceSpm = 0.0, dynamicAccelRms = 3.1)
        )

        // 3. Bumpy road faking 95 spm step events while riding at 4.5 m/s (impossible 2.84m stride) -> VEHICLE
        assertEquals(
            MotionState.VEHICLE,
            classifier.classifyInstant(gpsSpeedMps = 4.5, cadenceSpm = 95.0, dynamicAccelRms = 4.2)
        )

        // 4. High-speed motorcycle cruise at 50.4 km/h (14.0 m/s) -> VEHICLE
        assertEquals(
            MotionState.VEHICLE,
            classifier.classifyInstant(gpsSpeedMps = 14.0, cadenceSpm = 165.0, dynamicAccelRms = 6.5)
        )

        // 5. Full end-to-end DSP simulation: motorcycle with heavy 25 Hz engine vibration (+/- 6 m/s^2)
        //    + 1.5 Hz road suspension bounce (+/- 2.0 m/s^2) + false hardware step triggers at 168 spm
        //    while moving right at TS Police 1600m target speed (3.70 m/s = 13.3 km/h)!
        val bikeDsp = MotionSignalProcessor().apply { hardwareStepSensorAvailable = true }
        var nextFakeStepMs = 300L
        for (i in 0..200) {
            val tMs = i * 20L
            val tSec = tMs / 1000.0
            val engineBuzz25Hz = 6.0 * sin(2.0 * Math.PI * 25.0 * tSec)
            val roadBounce1p5Hz = 2.0 * sin(2.0 * Math.PI * 1.5 * tSec)
            val ay = (9.81 + engineBuzz25Hz + roadBounce1p5Hz).toFloat()
            bikeDsp.onAccelerationSample(nowMs = tMs, ax = 0f, ay = ay, az = 0f, includesGravity = true)
            if (tMs >= nextFakeStepMs) {
                bikeDsp.onHardwareStepDetected(tMs, 1)
                nextFakeStepMs += 357L // 168 spm false pedometer vibration triggers!
            }
        }
        val stateOnBumpyBike = classifier.classifyInstant(
            gpsSpeedMps = 3.70,
            cadenceSpm = bikeDsp.getCadenceSpm(4000L),
            dynamicAccelRms = bikeDsp.getDynamicAccelRms(4000L),
            minRawNorm = bikeDsp.getMinRawNorm(4000L),
            peakToTroughNorm = bikeDsp.getPeakToTroughNorm(4000L)
        )
        assertEquals("Seated bumpy motorcycle at 3.7 m/s must be VEHICLE, never RUNNING", MotionState.VEHICLE, stateOnBumpyBike)

        // 6. Verify real human running with aerial flight unloading still hits TARGET_PACE / RUNNING,
        //    and indoor walking with gravity norm still hits WALKING!
        assertEquals(
            MotionState.TARGET_PACE,
            classifier.classifyInstant(
                gpsSpeedMps = 3.80,
                cadenceSpm = 176.0,
                dynamicAccelRms = 8.5,
                minRawNorm = 2.1,
                peakToTroughNorm = 17.5
            )
        )
        assertEquals(
            MotionState.WALKING,
            classifier.classifyInstant(
                gpsSpeedMps = null,
                cadenceSpm = 104.0,
                dynamicAccelRms = 2.1,
                minRawNorm = 7.2,
                peakToTroughNorm = 4.8
            )
        )
    }

    @Test
    fun `steps and stride distance count reliably even when oem hardware step detector emits zero events`() {
        // Reproduces OEM Android phones where TYPE_STEP_DETECTOR registers non-null (hardwareStepSensorAvailable = true)
        // yet never delivers sensor callbacks, while the user walks holding the phone in hand (+/- 1.35 m/s^2 at 105 spm)!
        val dsp = MotionSignalProcessor().apply { hardwareStepSensorAvailable = true }
        val classifier = GaitClassifier()
        var accumulatedDistanceM = 0.0
        var lastSteps = 0

        val stepFreqHz = 1.75 // 105 steps/min normal walk
        for (i in 0..350) {
            val tMs = i * 20L
            val tSec = tMs / 1000.0
            val ay = (9.81 + 1.35 * sin(2.0 * Math.PI * stepFreqHz * tSec)).toFloat()
            dsp.onAccelerationSample(nowMs = tMs, ax = 0f, ay = ay, az = 0f, includesGravity = true)

            val cadence = dsp.getCadenceSpm(tMs)
            val rms = dsp.getDynamicAccelRms(tMs)
            val state = classifier.update(
                nowMs = tMs,
                gpsSpeedMps = 0.0, // stationary indoor FUSED_PROVIDER Wi-Fi fix!
                cadenceSpm = cadence,
                dynamicAccelRms = rms,
                minRawNorm = dsp.getMinRawNorm(tMs),
                peakToTroughNorm = dsp.getPeakToTroughNorm(tMs)
            )
            val newSteps = dsp.totalSteps - lastSteps
            lastSteps = dsp.totalSteps
            if (newSteps > 0 && state != MotionState.VEHICLE && (state.countsAsActiveMovement || cadence >= GaitClassifier.MIN_WALKING_CADENCE_SPM)) {
                accumulatedDistanceM += newSteps * classifier.estimateStrideLengthMeters(cadence.coerceAtLeast(85.0), rms)
            }
        }

        assertTrue("Expected >= 10 steps counted on silent-HW-sensor phone, got ${dsp.totalSteps}", dsp.totalSteps >= 10)
        val endCadence = dsp.getCadenceSpm(7000L)
        assertTrue("Expected walking cadence ~105 spm, got $endCadence", endCadence in 92.0..118.0)
        assertEquals(MotionState.WALKING, classifier.currentState())
        assertTrue("Expected >= 7.0m indoor stride distance, got $accumulatedDistanceM", accumulatedDistanceM >= 7.0)
    }

    @Test
    fun `daily activity summary combines whole-day jogging and running while keeping walking separate`() {
        val summary = DailyActivitySummary(
            dateIso = "2026-10-25",
            walkingSec = 2400.0,   // 40m 00s walked across the day
            joggingSec = 420.0,    // 7m 00s jogged
            runningSec = 450.0,    // 7m 30s ran
            walkingMeters = 2950.0,
            joggingMeters = 1010.0,
            runningMeters = 1610.0,
            totalSteps = 6820,
            maxSpeedMps = 4.00,    // 4:10 /km peak pace
            maxCadenceSpm = 181.0
        )

        assertEquals(870.0, summary.jogRunCombinedSec, 0.001)      // 14:30 combined jog + run
        assertEquals("14:30", TsPoliceBenchmark.formatDuration(summary.jogRunCombinedSec))
        assertEquals("40:00", TsPoliceBenchmark.formatDuration(summary.walkingSec))
        assertEquals(2620.0, summary.jogRunCombinedMeters, 0.001)  // 2.62 km jog + run
        assertEquals(5570.0, summary.totalDistanceMeters, 0.001)   // 5.57 km whole-day total
        assertEquals("4:10 /km", TsPoliceBenchmark.formatPacePerKm(summary.maxSpeedMps))
    }

    @Test
    fun `armed 1600m pt trial ignores standing walking and bike and launches only on jogging or running`() {
        assertFalse(MotionState.STANDING.isJogOrRun())
        assertFalse(MotionState.WALKING.isJogOrRun())
        assertFalse(MotionState.VEHICLE.isJogOrRun())
        assertTrue(MotionState.JOGGING.isJogOrRun())
        assertTrue(MotionState.RUNNING.isJogOrRun())
        assertTrue(MotionState.TARGET_PACE.isJogOrRun())

        val classifier = GaitClassifier()
        var phase = PtTrialPhase.ARMED_WAITING_FOR_JOG_RUN
        var confirmMs = 0L
        var launchBufferSec = 0.0
        var activeTrialSec = 0.0

        // 1. User arms PT Trial, puts phone in pocket, and WALKS for 10 seconds to the 400m track start line:
        //    PT Trial MUST stay ARMED_WAITING_FOR_JOG_RUN at 0.0s!
        for (step in 1..40) {
            val tMs = step * 250L
            val state = classifier.update(tMs, gpsSpeedMps = 1.32, cadenceSpm = 106.0, dynamicAccelRms = 2.2)
            assertFalse("Walking must never flag candidateIsJogOrRun", classifier.candidateIsJogOrRun())
            assertFalse("Walking must never flag state.isJogOrRun", state.isJogOrRun())
            assertEquals(PtTrialPhase.ARMED_WAITING_FOR_JOG_RUN, phase)
        }
        assertEquals(MotionState.WALKING, classifier.currentState())
        assertEquals(0.0, activeTrialSec, 0.001)

        // 2. User crosses start line and breaks into RUNNING (3.65 m/s, 172 spm):
        //    Candidate buffers launch seconds immediately, then commits RUNNING_TRIAL and credits launch time!
        for (step in 41..52) {
            val tMs = step * 250L
            val dtSec = 0.25
            val state = classifier.update(tMs, gpsSpeedMps = 3.65, cadenceSpm = 172.0, dynamicAccelRms = 7.8)
            if (phase == PtTrialPhase.ARMED_WAITING_FOR_JOG_RUN) {
                if (state.isJogOrRun()) {
                    launchBufferSec = (launchBufferSec + dtSec).coerceAtMost(2.6)
                    confirmMs += 250L
                    if (confirmMs >= 400L) {
                        phase = PtTrialPhase.RUNNING_TRIAL
                        activeTrialSec = launchBufferSec
                    }
                } else if (classifier.candidateIsJogOrRun()) {
                    launchBufferSec = (launchBufferSec + dtSec).coerceAtMost(2.6)
                    confirmMs = 0L
                }
            } else {
                activeTrialSec += dtSec
            }
        }

        assertEquals(PtTrialPhase.RUNNING_TRIAL, phase)
        assertTrue("Expected launch stride time credited (~2.0..3.0s), got $activeTrialSec", activeTrialSec >= 1.75)
    }

    @Test
    fun `samsung health comparator computes accuracy match and calibrates personal stride safely`() {
        val trackerToday = DailyActivitySummary(
            dateIso = "2026-10-26",
            walkingSec = 1800.0,
            joggingSec = 300.0,
            runningSec = 300.0,
            walkingMeters = 1950.0,
            joggingMeters = 720.0,
            runningMeters = 980.0, // total 3650m across 4350 steps (~0.839 m/step)
            totalSteps = 4350
        )
        val samsungToday = SamsungDailySnapshot(
            dateIso = "2026-10-26",
            steps = 4480,
            distanceMeters = 4032.0, // 0.90 m/step (taller runner calibrated in Samsung Health / Watch!)
            walkingSec = 1860.0,
            jogRunSec = 615.0,
            maxSpeedMps = 3.78,
            maxCadenceSpm = 176.0
        )

        val report = SamsungHealthComparator.compare(trackerToday, samsungToday, currentStrideScale = 1.0)
        assertEquals(-130, report.stepsDelta)
        assertTrue((report.stepsMatchPercent ?: 0.0) > 96.0)
        assertTrue((report.distanceMatchPercent ?: 0.0) > 89.0)
        assertTrue("Expected stride scale > 1.03 for longer-stride runner, got ${report.recommendedStrideScale}",
            report.recommendedStrideScale in 1.03..1.15)

        // Verify GaitClassifier applies calibrated stride scale to live indoor/pocket step estimation
        val uncalibrated = GaitClassifier(strideScaleFactor = 1.0)
        val calibrated = GaitClassifier(strideScaleFactor = report.recommendedStrideScale)
        val baseStride = uncalibrated.estimateStrideLengthMeters(cadenceSpm = 170.0, dynamicAccelRms = 7.2)
        val tunedStride = calibrated.estimateStrideLengthMeters(cadenceSpm = 170.0, dynamicAccelRms = 7.2)
        assertEquals(baseStride * report.recommendedStrideScale, tunedStride, 0.005)

        // Even when user wore Galaxy Watch without phone (Samsung has 5x more steps!), per-step stride
        // normalization prevents stride scale from blowing up!
        val partialPhoneDay = DailyActivitySummary(dateIso = "2026-10-26", walkingSec = 400.0, walkingMeters = 620.0, totalSteps = 800)
        val fullWatchDay = SamsungDailySnapshot(dateIso = "2026-10-26", steps = 5000, distanceMeters = 4000.0, walkingSec = 2900.0)
        val watchScale = SamsungHealthComparator.computeCalibratedStrideScale(partialPhoneDay, fullWatchDay, currentStrideScale = 1.0)
        assertTrue("Watch-only extra steps must keep per-step stride scale near ~1.02x, got $watchScale",
            watchScale in 0.98..1.08)
    }

    @Test
    fun `smart pocket audio stays silent on casual indoor walking while speaking during runs and pt trials`() {
        // SMART_TRACK: silent on everyday STANDING <-> WALKING when idle
        assertFalse(
            PocketAudioMode.SMART_TRACK.shouldAnnounceTransition(
                previousState = MotionState.STANDING,
                newState = MotionState.WALKING,
                ptTrialPhase = PtTrialPhase.IDLE,
                inOpenRunBout = false
            )
        )
        assertFalse(
            PocketAudioMode.SMART_TRACK.shouldAnnounceTransition(
                previousState = MotionState.WALKING,
                newState = MotionState.STANDING,
                ptTrialPhase = PtTrialPhase.IDLE,
                inOpenRunBout = false
            )
        )

        // SMART_TRACK: speaks immediately when breaking into JOGGING/RUNNING or during an armed/running PT trial
        assertTrue(
            PocketAudioMode.SMART_TRACK.shouldAnnounceTransition(
                previousState = MotionState.WALKING,
                newState = MotionState.JOGGING,
                ptTrialPhase = PtTrialPhase.IDLE,
                inOpenRunBout = false
            )
        )
        assertTrue(
            PocketAudioMode.SMART_TRACK.shouldAnnounceTransition(
                previousState = MotionState.STANDING,
                newState = MotionState.WALKING,
                ptTrialPhase = PtTrialPhase.ARMED_WAITING_FOR_JOG_RUN,
                inOpenRunBout = false
            )
        )

        // ALL_STATES speaks every transition; MUTED speaks none
        assertTrue(
            PocketAudioMode.ALL_STATES.shouldAnnounceTransition(
                previousState = MotionState.STANDING,
                newState = MotionState.WALKING,
                ptTrialPhase = PtTrialPhase.IDLE,
                inOpenRunBout = false
            )
        )
        assertFalse(
            PocketAudioMode.MUTED.shouldAnnounceTransition(
                previousState = MotionState.JOGGING,
                newState = MotionState.TARGET_PACE,
                ptTrialPhase = PtTrialPhase.RUNNING_TRIAL,
                inOpenRunBout = true
            )
        )
    }

    @Test
    fun `dt normalized accelerometer filter preserves cadence and rms across 20hz screen off and 100hz rates`() {
        val freqHz = 2.8 // 168 spm running footstrikes

        fun runSimulation(stepMs: Long): Pair<Double, Double> {
            val dsp = MotionSignalProcessor()
            var tMs = 0L
            while (tMs <= 6000L) {
                val tSec = tMs / 1000.0
                val ay = (9.81 + 6.5 * sin(2.0 * Math.PI * freqHz * tSec)).toFloat()
                dsp.onAccelerationSample(nowMs = tMs, ax = 0f, ay = ay, az = 0f, includesGravity = true)
                tMs += stepMs
            }
            return dsp.getCadenceSpm(6000L) to dsp.getDynamicAccelRms(6000L)
        }

        val (cadence20Hz, rms20Hz) = runSimulation(50L)   // 20 Hz Samsung screen-off pocket throttling
        val (cadence50Hz, rms50Hz) = runSimulation(20L)   // 50 Hz standard rate
        val (cadence100Hz, rms100Hz) = runSimulation(10L) // 100 Hz fast OEM sensor rate

        assertTrue("20Hz screen-off cadence should be ~168 spm, got $cadence20Hz", cadence20Hz in 152.0..184.0)
        assertTrue("50Hz cadence should be ~168 spm, got $cadence50Hz", cadence50Hz in 152.0..184.0)
        assertTrue("100Hz cadence should be ~168 spm, got $cadence100Hz", cadence100Hz in 152.0..184.0)
        assertTrue("20Hz RMS ($rms20Hz), 50Hz RMS ($rms50Hz) and 100Hz RMS ($rms100Hz) should stay closely matched",
            kotlin.math.abs(rms20Hz - rms50Hz) < 0.85 && kotlin.math.abs(rms50Hz - rms100Hz) < 0.85 && rms20Hz > 3.0)
    }

    @Test
    fun `tslprb 1600m merit gradation points and runner height stride baseline compute accurately`() {
        val topMerit = TsPoliceBenchmark.evaluateMerit1600m(312.0) // 5:12
        assertTrue(topMerit.qualified)
        assertEquals(100, topMerit.points)

        val borderPass = TsPoliceBenchmark.evaluateMerit1600m(434.2) // 7:14.2
        assertTrue(borderPass.qualified)
        assertEquals(40, borderPass.points)
        assertEquals(420.0, borderPass.nextTierTimeSec ?: 0.0, 0.01)

        val missedCutoff = TsPoliceBenchmark.evaluateMerit1600m(452.0) // 7:32
        assertFalse(missedCutoff.qualified)
        assertEquals(0, missedCutoff.points)

        val exServicemenPass = TsPoliceBenchmark.evaluateMerit1600m(540.0, targetTimeSec = 570.0) // 9:00 <= 9:30
        assertTrue(exServicemenPass.qualified)
        assertEquals(40, exServicemenPass.points)

        assertEquals(1.0, TsPoliceBenchmark.heightStrideScale(172), 0.001)
        assertTrue(TsPoliceBenchmark.heightStrideScale(184) > 1.05)
        assertTrue(TsPoliceBenchmark.heightStrideScale(160) < 0.95)
    }

    @Test
    fun `yesterday and today samsung health comparison aggregates 2 day metrics and calibrates stride reliably`() {
        val yesterdayTracker = DailyActivitySummary(
            dateIso = "2026-10-26",
            walkingSec = 2400.0,
            joggingSec = 360.0,
            runningSec = 420.0,
            walkingMeters = 2850.0,
            joggingMeters = 860.0,
            runningMeters = 1520.0, // 5,230m across 6,100 steps
            totalSteps = 6100,
            maxSpeedMps = 3.85,
            maxCadenceSpm = 176.0
        )
        val yesterdaySamsung = SamsungDailySnapshot(
            dateIso = "2026-10-26",
            steps = 6220,
            distanceMeters = 5535.0, // ~0.89 m/step
            walkingSec = 2460.0,
            jogRunSec = 795.0,
            maxSpeedMps = 3.88,
            maxCadenceSpm = 178.0
        )
        // Early morning today: only 55 indoor steps so far!
        val todayTracker = DailyActivitySummary(
            dateIso = "2026-10-27",
            walkingSec = 40.0,
            walkingMeters = 42.0,
            totalSteps = 55,
            maxSpeedMps = 1.25,
            maxCadenceSpm = 102.0
        )
        val todaySamsung = SamsungDailySnapshot(
            dateIso = "2026-10-27",
            steps = 60,
            distanceMeters = 46.0,
            walkingSec = 44.0,
            maxSpeedMps = 1.28,
            maxCadenceSpm = 104.0
        )

        val combTrk = SamsungHealthComparator.combineDailySummaries(yesterdayTracker, todayTracker)
        val combSam = SamsungHealthComparator.combineSamsungSnapshots(yesterdaySamsung, todaySamsung)!!
        assertEquals(6155, combTrk.totalSteps)
        assertEquals(6280, combSam.steps)
        assertEquals(3.85, combTrk.maxSpeedMps, 0.001)
        assertEquals(178.0, combSam.maxCadenceSpm, 0.001)

        // Multi-day calibration anchors on Yesterday's full 6,220-step day instead of skipping due to Today's <200 steps!
        val twoDayScale = SamsungHealthComparator.computeMultiDayCalibratedStrideScale(
            yesterdayTracker = yesterdayTracker,
            yesterdaySamsung = yesterdaySamsung,
            todayTracker = todayTracker,
            todaySamsung = todaySamsung,
            currentStrideScale = 1.0
        )
        assertTrue("Expected 2-day calibrated stride scale > 1.02, got $twoDayScale", twoDayScale in 1.02..1.12)

        val reportTxt = SamsungHealthComparator.buildTwoDayReportText(
            yesterdayTracker = yesterdayTracker,
            yesterdaySamsung = yesterdaySamsung,
            todayTracker = todayTracker,
            todaySamsung = todaySamsung,
            effectiveStrideScale = twoDayScale
        )
        assertTrue(reportTxt.contains("[YESTERDAY • 2026-10-26]"))
        assertTrue(reportTxt.contains("[TODAY • 2026-10-27]"))
        assertTrue(reportTxt.contains("[2-DAY COMBINED"))
    }

    @Test
    fun `fine tuning telemetry separates hardware steps from software fallback steps and locks verified hardware pedometer`() {
        // 1. On devices with a verified working hardware pedometer (like Samsung S23 SM-S911B),
        //    once real hardware steps arrive, quiet bike/hand vibration MUST NOT trigger software fallback steps!
        val verifiedHwDsp = MotionSignalProcessor().apply { hardwareStepSensorAvailable = true }
        verifiedHwDsp.onHardwareStepDetected(1000L, 4)
        verifiedHwDsp.reconcileStepCounterDelta(2000L, hwDeltaSteps = 6, stepsCountedSinceLastCounter = 4)
        assertTrue(verifiedHwDsp.verifiedHardwarePedometerActive)
        assertEquals(6, verifiedHwDsp.hardwareStepsCount)
        assertEquals(0, verifiedHwDsp.fallbackSoftwareStepsCount)
        assertEquals(6, verifiedHwDsp.totalSteps)

        val freqHz = 2.0 // 120 spm vibration after >3.5s hardware step silence
        for (i in 0..250) {
            val tMs = 6000L + i * 20L
            val tSec = (i * 20L) / 1000.0
            val ay = (9.81 + 2.2 * sin(2.0 * Math.PI * freqHz * tSec)).toFloat()
            verifiedHwDsp.onAccelerationSample(nowMs = tMs, ax = 0f, ay = ay, az = 0f, includesGravity = true)
        }
        assertEquals("Verified hardware pedometer must never fabricate software vibration steps!", 0, verifiedHwDsp.fallbackSoftwareStepsCount)
        assertEquals(6, verifiedHwDsp.totalSteps)

        // 2. On devices whose OEM hardware step sensor emits 0 events (verifiedHardwarePedometerActive == false),
        //    software gait fallback attributes purely to fallbackSoftwareStepsCount
        val fallbackDsp = MotionSignalProcessor().apply { hardwareStepSensorAvailable = false }
        for (i in 0..250) {
            val tMs = i * 20L
            val tSec = tMs / 1000.0
            val ay = (9.81 + 2.2 * sin(2.0 * Math.PI * freqHz * tSec)).toFloat()
            fallbackDsp.onAccelerationSample(nowMs = tMs, ax = 0f, ay = ay, az = 0f, includesGravity = true)
        }
        assertEquals(0, fallbackDsp.hardwareStepsCount)
        assertTrue("Expected software fallback steps > 6, got ${fallbackDsp.fallbackSoftwareStepsCount}", fallbackDsp.fallbackSoftwareStepsCount > 6)
        assertEquals(fallbackDsp.fallbackSoftwareStepsCount, fallbackDsp.totalSteps)

        // 3. HourlySensorBucket aggregates steps, GPS vs stride distance, and mean GPS accuracy cleanly
        val bucket = HourlySensorBucket(
            bucketKey = "2026-10-27T07",
            dateIso = "2026-10-27",
            hourOfDay = 7,
            hwSteps = 1420,
            fallbackSteps = 80,
            walkSec = 420.0,
            jogSec = 180.0,
            runSec = 310.0,
            vehicleSec = 25.0,
            gpsMeters = 1520.0,
            strideMeters = 190.0,
            gpsFixCount = 40,
            gpsAccSumMeters = 260.0
        )
        assertEquals(1500, bucket.totalSteps)
        assertEquals(1710.0, bucket.totalDistanceMeters, 0.001)
        assertEquals(6.5, bucket.avgGpsAccuracyM ?: 0.0, 0.001)

        verifiedHwDsp.reset()
        assertEquals(0, verifiedHwDsp.hardwareStepsCount)
        assertEquals(0, verifiedHwDsp.fallbackSoftwareStepsCount)
        assertEquals(0, verifiedHwDsp.totalSteps)
    }

    @Test
    fun `bike latch prevents jogging walking or running when slowing down speeding up or stopping at traffic lights until verified dismount walk`() {
        val classifier = GaitClassifier()
        var nowMs = 1_000L

        // 1. Accelerate onto bike (22 km/h = 6.1 m/s) -> instant VEHICLE latch
        assertEquals(MotionState.VEHICLE, classifier.update(nowMs, gpsSpeedMps = 6.1, cadenceSpm = 0.0, dynamicAccelRms = 3.5))
        assertTrue(classifier.isInOrCoolingDownFromVehicle())

        // 2. Slow bike down to Running speed (3.5 m/s) even if bumpy road fakes 172 spm + pothole dip!
        for (step in 1..15) {
            nowMs += 400L
            val st = classifier.update(
                nowMs = nowMs,
                gpsSpeedMps = 3.5,
                cadenceSpm = 172.0,
                dynamicAccelRms = 6.8,
                minRawNorm = 4.2,       // single pothole dip!
                peakToTroughNorm = 11.5,
                p20RawNorm = 8.4        // seated on saddle 80% of window
            )
            assertEquals("Slowing bike to 3.5 m/s must stay VEHICLE, never RUNNING", MotionState.VEHICLE, st)
        }

        // 3. Slow bike down further to Jogging speed (2.2 m/s) with 145 spm vibration -> STILL VEHICLE!
        for (step in 1..15) {
            nowMs += 400L
            val st = classifier.update(
                nowMs = nowMs,
                gpsSpeedMps = 2.2,
                cadenceSpm = 145.0,
                dynamicAccelRms = 4.2,
                p20RawNorm = 8.6
            )
            assertEquals("Slowing bike to 2.2 m/s must stay VEHICLE, never JOGGING", MotionState.VEHICLE, st)
        }

        // 4. Slow bike down even more to Walking crawl in traffic (1.2 m/s) WITHOUT having parked -> STILL VEHICLE!
        for (step in 1..15) {
            nowMs += 400L
            val st = classifier.update(
                nowMs = nowMs,
                gpsSpeedMps = 1.2,
                cadenceSpm = 98.0,
                dynamicAccelRms = 2.3,
                p20RawNorm = 8.9
            )
            assertEquals("Crawling bike in traffic at 1.2 m/s without parking must stay VEHICLE, never WALKING", MotionState.VEHICLE, st)
        }

        // 5. Lose GPS inside an underpass for 8 seconds while vibrating at 165 spm -> STILL VEHICLE!
        for (step in 1..20) {
            nowMs += 400L
            val st = classifier.update(
                nowMs = nowMs,
                gpsSpeedMps = null,
                cadenceSpm = 165.0,
                dynamicAccelRms = 5.9,
                p20RawNorm = 8.5
            )
            assertEquals("Temporary GPS loss while latched on bike must stay VEHICLE", MotionState.VEHICLE, st)
        }

        // 6. Stop at a red traffic light for 20 seconds (< 0.85 m/s) -> settles to STANDING while keeping cooldown armed,
        //    then green light acceleration (1.3 -> 2.4 -> 4.2 m/s) snaps straight back to VEHICLE with ZERO walk/jog/run!
        for (step in 1..50) {
            nowMs += 400L
            val st = classifier.update(nowMs, gpsSpeedMps = 0.0, cadenceSpm = 0.0, dynamicAccelRms = 0.25)
            assertFalse("Stopped at red light must never count as movement", st.countsAsActiveMovement)
        }
        assertTrue("Red-light cooldown must remain armed", classifier.isInOrCoolingDownFromVehicle())

        // Green light pull-away through walking/jogging/running speeds:
        val pullAwaySpeeds = listOf(1.3, 1.8, 2.3, 3.1, 3.8, 5.4)
        for (spd in pullAwaySpeeds) {
            nowMs += 400L
            val st = classifier.update(nowMs, gpsSpeedMps = spd, cadenceSpm = 110.0, dynamicAccelRms = 3.2, p20RawNorm = 8.5)
            assertFalse("Accelerating from red light at $spd m/s must NEVER trigger Walk/Jog/Run, got $st", st.countsAsActiveMovement)
        }
        assertEquals(MotionState.VEHICLE, classifier.currentState())

        // 7. Genuine Park & Dismount: park bike (< 0.85 m/s) for 7 seconds, then walk away on foot (104 spm at 1.25 m/s) for 6s!
        for (step in 1..18) {
            nowMs += 400L
            classifier.update(nowMs, gpsSpeedMps = 0.1, cadenceSpm = 0.0, dynamicAccelRms = 0.2)
        }
        var unlockedWalk = false
        for (step in 1..16) {
            nowMs += 400L
            val st = classifier.update(nowMs, gpsSpeedMps = 1.25, cadenceSpm = 104.0, dynamicAccelRms = 2.1, p20RawNorm = 7.4)
            if (st == MotionState.WALKING) unlockedWalk = true
        }
        assertTrue("6s genuine walking after 7s parked stop must unlock WALKING", unlockedWalk)
        assertFalse("Vehicle latch and cooldown must clear after verified dismount walk", classifier.isInOrCoolingDownFromVehicle())
    }

    @Test
    fun `partial intraday samsung distance record does not corrupt multi day personal stride calibration`() {
        // Exact real-world numbers from Samsung Galaxy S23 (SM-S911B) telemetry:
        // Yesterday: 5,297 steps, 4,025.72m full-day Health Connect distance (0.760 m/step)
        // Today: 6,007 steps, raw Health Connect only flushed 2,107.70m from 7 PM walk session (0.351 m/step!)
        val yesterdayTrk = DailyActivitySummary(dateIso = "2026-10-26", totalSteps = 5297, walkingMeters = 4025.72, walkingSec = 3115.0)
        val yesterdaySam = SamsungDailySnapshot(
            dateIso = "2026-10-26",
            steps = 5297,
            distanceMeters = 4025.72,
            rawHealthConnectDistanceMeters = 4025.72,
            distanceIncludedPassiveEstimate = false,
            walkingSec = 3115.88
        )
        val todayTrk = DailyActivitySummary(dateIso = "2026-10-27", totalSteps = 6007, walkingMeters = 4565.0, walkingSec = 3366.0)
        val todaySam = SamsungDailySnapshot(
            dateIso = "2026-10-27",
            steps = 6007,
            distanceMeters = 4565.32, // reconstructed for UI comparison
            rawHealthConnectDistanceMeters = 2107.70, // partial workout-only Health Connect record!
            distanceIncludedPassiveEstimate = true,
            walkingSec = 3513.0
        )

        // Even starting from a previously corrupted 0.81x stride scale, multi-day calibration rejects Today's
        // partial raw distance and heals immediately to Yesterday's true ~0.987x (0.760 / 0.770)!
        val healedScale = SamsungHealthComparator.computeMultiDayCalibratedStrideScale(
            yesterdayTracker = yesterdayTrk,
            yesterdaySamsung = yesterdaySam,
            todayTracker = todayTrk,
            todaySamsung = todaySam,
            currentStrideScale = 0.8106
        )
        assertTrue("Expected healed stride scale ~0.987x (in 0.96..1.01), got $healedScale", healedScale in 0.96..1.01)
    }

    @Test
    fun `manual 1600m pt trial and open mode never false trigger bike when speeding up from jog to run with phone in hand`() {
        // 1. Normal 24/7 Open Mode: accelerating from Jog (2.1 m/s) -> Run (3.3 m/s) -> 7:15 Target (3.85 m/s)
        //    even when holding phone in hand where arm-swing centripetal force keeps p20RawNorm around 8.4 m/s^2!
        val openClassifier = GaitClassifier()
        var nowMs = 1_000L
        val openProgression = listOf(
            Triple(2.15, 146.0, 4.1),
            Triple(2.65, 156.0, 5.2),
            Triple(3.25, 166.0, 6.4),
            Triple(3.85, 174.0, 7.9)
        )
        var jogStarted = false
        for ((gps, cad, rms) in openProgression) {
            for (tick in 1..8) {
                nowMs += 250L
                val st = openClassifier.update(
                    nowMs = nowMs,
                    gpsSpeedMps = gps,
                    cadenceSpm = cad,
                    dynamicAccelRms = rms,
                    minRawNorm = 4.6,
                    peakToTroughNorm = 11.2,
                    p20RawNorm = 8.45,
                    hfVibrationRms = 1.1,
                    isManualWorkoutActive = false
                )
                if (st.isJogOrRun()) jogStarted = true
                assertNotEquals("Speeding up from Jog to Run in Open Mode must NEVER say Bike/Vehicle!", MotionState.VEHICLE, st)
                if (jogStarted) {
                    assertNotEquals("Once jogging/running, speeding up must NEVER drop to Standing/Stopped!", MotionState.STANDING, st)
                    assertNotEquals("Once jogging/running, speeding up must NEVER drop to Walking!", MotionState.WALKING, st)
                }
            }
        }
        assertTrue(jogStarted)
        assertEquals(MotionState.TARGET_PACE, openClassifier.currentState())

        // 2. Explicit 1.6K PT Trial Mode: even if runner sprints at 6.2 m/s OR holds phone rock-steady for 3 seconds
        //    to read split pace (temporarily dropping hardware step cadence to 0.0 spm), NEVER triggers Bike or Stopped!
        val ptClassifier = GaitClassifier()
        var ptNowMs = 10_000L
        for (tick in 1..6) {
            ptNowMs += 250L
            ptClassifier.update(
                nowMs = ptNowMs,
                gpsSpeedMps = 2.30,
                cadenceSpm = 148.0,
                dynamicAccelRms = 4.3,
                p20RawNorm = 8.6,
                isManualWorkoutActive = true
            )
        }
        assertEquals(MotionState.JOGGING, ptClassifier.currentState())

        // Speed up into full Run / Sprint + glance at screen (cadence = 0.0 for 2.5s while GPS = 3.55 m/s)
        for (tick in 1..10) {
            ptNowMs += 250L
            val st = ptClassifier.update(
                nowMs = ptNowMs,
                gpsSpeedMps = 3.55,
                cadenceSpm = 0.0,
                dynamicAccelRms = 1.9,
                p20RawNorm = 9.2,
                isManualWorkoutActive = true
            )
            assertNotEquals("1.6K Trial must NEVER trigger Bike when glancing at screen!", MotionState.VEHICLE, st)
            assertNotEquals("1.6K Trial must NEVER shout Stopped while moving at 3.55 m/s!", MotionState.STANDING, st)
        }
        assertEquals(MotionState.RUNNING, ptClassifier.currentState())
    }

    @Test
    fun `slow jogging at walking speed detects as jogging with short stride length while brisk flat walk stays walking`() {
        val classifier = GaitClassifier()

        // 1. Slow-Jogging at Walking Speed: 1.25 m/s (4.5 km/h = 13:20 /km), 142 spm, vertical bounce RMS = 3.45 m/s^2
        val slowJogState = classifier.classifyInstant(
            gpsSpeedMps = 1.25,
            cadenceSpm = 142.0,
            dynamicAccelRms = 3.45
        )
        assertEquals("Slow-jogging at 1.25 m/s walking speed must detect as JOGGING", MotionState.JOGGING, slowJogState)
        val slowJogStride = classifier.computeLiveStrideLengthMeters(1.25, 142.0, 3.45, slowJogState)
        assertTrue("Slow-jog live stride at 1.25 m/s & 142 spm should be ~0.53m, got $slowJogStride", slowJogStride in 0.48..0.58)

        // 2. Spot-Jogging in place / indoor warmup: 0.0 m/s Wi-Fi GPS, 140 spm, bounce RMS = 3.5 m/s^2
        assertEquals(
            MotionState.JOGGING,
            classifier.classifyInstant(gpsSpeedMps = 0.0, cadenceSpm = 140.0, dynamicAccelRms = 3.50)
        )

        // 3. Brisk Flat Walking at even faster ground speed: 1.40 m/s (5.04 km/h), 115 spm, flat walk RMS = 2.15 m/s^2
        val briskWalkState = classifier.classifyInstant(
            gpsSpeedMps = 1.40,
            cadenceSpm = 115.0,
            dynamicAccelRms = 2.15
        )
        assertEquals("Brisk flat walking at 115 spm without aerial bounce must stay WALKING", MotionState.WALKING, briskWalkState)
        val briskWalkStride = classifier.computeLiveStrideLengthMeters(1.40, 115.0, 2.15, briskWalkState)
        assertTrue("Brisk walk stride at 1.40 m/s & 115 spm should be ~0.73m, got $briskWalkStride", briskWalkStride in 0.68..0.78)
    }

    @Test
    fun `missed hardware step events while looking at screen heal cleanly without dropping jogging to walking or stopped`() {
        val dsp = MotionSignalProcessor().apply {
            hardwareStepSensorAvailable = true
            verifiedHardwarePedometerActive = true
        }
        val classifier = GaitClassifier()

        // Simulate 12 seconds of 150 spm jogging (400ms step period) where Samsung TYPE_STEP_DETECTOR
        // drops single steps (800ms gap) and a double step (1200ms gap) while runner checks the screen,
        // AND GPS temporarily drops to null (warming >24m accuracy on ground)!
        val stepFreqHz = 2.50 // 150 spm
        var nextHwStepMs = 400L
        var stepIdx = 0
        for (i in 0..600) {
            val tMs = i * 20L
            val tSec = tMs / 1000.0
            val ay = (9.81 + 4.8 * sin(2.0 * Math.PI * stepFreqHz * tSec)).toFloat()
            dsp.onAccelerationSample(nowMs = tMs, ax = 0f, ay = ay, az = 0f, includesGravity = true)

            if (tMs >= nextHwStepMs) {
                stepIdx++
                // Drop step #8 (creates 800ms gap) and steps #16-#17 (creates 1200ms gap)!
                val dropThisHwStep = (stepIdx == 8 || stepIdx == 16 || stepIdx == 17)
                if (!dropThisHwStep) {
                    dsp.onHardwareStepDetected(tMs, 1)
                }
                nextHwStepMs += 400L
            }

            if (tMs >= 1400L && i % 10 == 0) {
                val cad = dsp.getCadenceSpm(tMs)
                val rms = dsp.getDynamicAccelRms(tMs)
                val gpsSimulated = if (tMs in 5000L..8500L) null else 1.85 // test pre-GPS null AND slow 1.85 m/s jog!
                val state = classifier.update(
                    nowMs = tMs,
                    gpsSpeedMps = gpsSimulated,
                    cadenceSpm = cad,
                    dynamicAccelRms = rms,
                    minRawNorm = dsp.getMinRawNorm(tMs),
                    peakToTroughNorm = dsp.getPeakToTroughNorm(tMs),
                    p20RawNorm = dsp.getP20RawNorm(tMs),
                    hfVibrationRms = dsp.getHighFreqVibrationRms(tMs)
                )
                assertTrue("Expected healed jogging cadence ~140..160 spm at ${tMs}ms, got $cad", cad in 138.0..162.0)
                if (tMs >= 3400L) {
                    assertEquals("Jogging must NEVER drop to Walking or Stopped at ${tMs}ms!", MotionState.JOGGING, state)
                }
            }
        }
    }

    @Test
    fun `remote pull command parser extracts pull now request id and ignores keepalive frames`() {
        val keepaliveLine = """{"id":"k1","time":1793415000,"event":"keepalive","topic":"tspolice-cmd-9f4c2e7b18a6d305e419"}"""
        assertEquals(null, TuningTelemetryManager.parseRemotePullCommandLine(keepaliveLine))

        val plainPullLine = """{"id":"m99","time":1793415123,"event":"message","topic":"tspolice-cmd-9f4c2e7b18a6d305e419","message":"PULL_NOW:a1b2c3d4e5"}"""
        val parsed = TuningTelemetryManager.parseRemotePullCommandLine(plainPullLine)
        assertNotNull(parsed)
        assertEquals("a1b2c3d4e5", parsed!!.first)
        assertEquals(1793415123L, parsed.second)
    }

    @Test
    fun `android 16 full day future end time record clamping and legacy 080x stride scale healing work cleanly`() {
        // 1. Samsung Health Android 16 stamps Today's daily StepsRecord as [00:00 .. 23:59:59.999] (future endTime!).
        //    effectiveRecordEndEpochMs clamps future endTime to `nowMs` so intraday buckets never lose 63% of Today's steps!
        val dayStartMs = 1_793_385_000_000L // 00:00 local
        val midnightFutureMs = dayStartMs + 86_399_999L // 23:59:59.999 tonight
        val morningNowMs = dayStartMs + (8L * 3600_000L + 50L * 60_000L) // 08:50 AM
        assertEquals(
            morningNowMs,
            SamsungHealthComparator.effectiveRecordEndEpochMs(dayStartMs, midnightFutureMs, morningNowMs)
        )
        // Past completed day (Yesterday 00:00 .. 23:59:59.999 < morningNowMs) stays untouched
        val yestStartMs = dayStartMs - 86_400_000L
        val yestEndMs = dayStartMs - 1L
        assertEquals(
            yestEndMs,
            SamsungHealthComparator.effectiveRecordEndEpochMs(yestStartMs, yestEndMs, morningNowMs)
        )

        // 2. When Samsung Health has only passive-estimated distance (0 raw DistanceRecords) and a legacy
        //    pre-fix partial sync had pinned stride scale to 0.801x, multi-day calibration heals to runner height scale!
        val yestTrk = DailyActivitySummary(dateIso = "2026-10-30", walkingSec = 1990.0, walkingMeters = 2594.0, totalSteps = 5621)
        val todayTrk = DailyActivitySummary(dateIso = "2026-10-31", walkingSec = 2152.0, joggingSec = 286.0, walkingMeters = 2593.0, joggingMeters = 350.0, totalSteps = 4558)
        val yestSam = SamsungDailySnapshot(dateIso = "2026-10-30", steps = 5925, distanceMeters = 4555.0, rawHealthConnectDistanceMeters = 0.0, distanceIncludedPassiveEstimate = true)
        val todaySam = SamsungDailySnapshot(dateIso = "2026-10-31", steps = 4697, distanceMeters = 3611.0, rawHealthConnectDistanceMeters = 0.0, distanceIncludedPassiveEstimate = true)

        assertFalse(SamsungHealthComparator.hasCompleteRawDistanceForCalibration(yestSam))
        assertFalse(SamsungHealthComparator.hasCompleteRawDistanceForCalibration(todaySam))

        val height174Scale = TsPoliceBenchmark.heightStrideScale(174) // ~1.0116x
        val healedScale = SamsungHealthComparator.computeMultiDayCalibratedStrideScale(
            yesterdayTracker = yestTrk,
            yesterdaySamsung = yestSam,
            todayTracker = todayTrk,
            todaySamsung = todaySam,
            currentStrideScale = 0.8013,
            fallbackHeightScale = height174Scale
        )
        assertEquals(height174Scale, healedScale, 0.005)
    }

    @Test
    fun `pavement slow jogging with pocket single leg half counted steps and sharp heel shock stays jogging without ping pong`() {
        val dsp = MotionSignalProcessor().apply {
            hardwareStepSensorAvailable = true
            verifiedHardwarePedometerActive = true
        }
        val classifier = GaitClassifier()

        // Reproduce exact Oct 31 morning telemetry (07:28-08:03 on SM-S911B):
        //  - User slow-jogs at ~1.12 m/s (148 spm = 405ms true step period) on pavement
        //  - Sharp asphalt heel-strike spikes rawNorm up to ~38 m/s^2 while aerial flight unloads minRawNorm to ~2.8 m/s^2
        //  - Pocket step_detector only catches same-side leg (~74..102 spm) AND pauses batching for 2.5s mid-bout!
        val stepPeriodMs = 405L
        var nextTrueStepMs = 405L
        var stepNum = 0
        var jogCommitted = false

        for (i in 0..550) {
            val tMs = i * 20L
            val phaseInStep = (tMs % stepPeriodMs).toDouble() / stepPeriodMs
            // Realistic pavement slow-jog waveform: aerial unloading (~2.6 m/s^2) + sharp 36 m/s^2 impact spike!
            val ay = when {
                phaseInStep < 0.12 -> 36.5f // sharp heel/midfoot asphalt strike!
                phaseInStep < 0.45 -> 13.5f
                phaseInStep < 0.80 -> 2.65f // aerial flight unloading!
                else -> 8.5f
            }
            dsp.onAccelerationSample(nowMs = tMs, ax = 0f, ay = ay, az = 0f, includesGravity = true)

            if (tMs >= nextTrueStepMs) {
                stepNum++
                // Simulate pocket single-leg half-counting (drops every odd step after step 6)
                // PLUS a complete 2.4s hardware step batching pause at steps 18..23!
                val inBatchPause = stepNum in 18..23
                val halfCountDrop = stepNum > 6 && (stepNum % 2 == 1)
                if (!inBatchPause && !halfCountDrop) {
                    dsp.onHardwareStepDetected(tMs, 1)
                }
                nextTrueStepMs += stepPeriodMs
            }

            if (tMs >= 1600L && i % 10 == 0) {
                val cad = dsp.getCadenceSpm(tMs)
                val rms = dsp.getDynamicAccelRms(tMs)
                val state = classifier.update(
                    nowMs = tMs,
                    gpsSpeedMps = 1.12,
                    cadenceSpm = cad,
                    dynamicAccelRms = rms,
                    minRawNorm = dsp.getMinRawNorm(tMs),
                    peakToTroughNorm = dsp.getPeakToTroughNorm(tMs),
                    p20RawNorm = dsp.getP20RawNorm(tMs),
                    hfVibrationRms = dsp.getHighFreqVibrationRms(tMs),
                    isManualWorkoutActive = false
                )
                if (state == MotionState.JOGGING) jogCommitted = true
                if (jogCommitted) {
                    assertEquals(
                        "Pavement slow-jog must NEVER ping-pong to WALKING or STANDING at ${tMs}ms (cad=$cad, rms=$rms)",
                        MotionState.JOGGING,
                        state
                    )
                }
                assertTrue("Cadence must never spike to fake >205 spm, got $cad", cad <= 205.0)
            }
        }
        assertTrue("Slow-jogging should commit JOGGING cleanly", jogCommitted)
    }

    private fun session(date: String, distM: Double, sec: Double, mode: RunMode): RunSessionRecord {
        val is1600 = mode == RunMode.TIMED_1600M
        return RunSessionRecord(
            startedAtEpochMs = 1_700_000_000_000L,
            dateIso = date,
            mode = mode,
            distanceMeters = distM,
            activeDurationSec = sec,
            pausedDurationSec = 0.0,
            first1600mSplitSec = if (is1600) sec else null,
            equiv1600mSec = TsPoliceBenchmark.computeEquiv1600mSec(distM, sec, if (is1600) sec else null),
            avgCadenceSpm = 168.0,
            maxSpeedMps = distM / sec,
            runningSec = sec * 0.85,
            joggingSec = sec * 0.15,
            walkingSec = 0.0
        )
    }
}
