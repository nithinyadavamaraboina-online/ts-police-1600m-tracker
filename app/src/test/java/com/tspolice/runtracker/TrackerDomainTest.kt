package com.tspolice.runtracker

import com.tspolice.runtracker.domain.DailyActivitySummary
import com.tspolice.runtracker.domain.ForecastStatus
import com.tspolice.runtracker.domain.GaitClassifier
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
import com.tspolice.runtracker.update.VersionComparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
