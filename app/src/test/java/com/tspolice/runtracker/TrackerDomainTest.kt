package com.tspolice.runtracker

import com.tspolice.runtracker.domain.ForecastStatus
import com.tspolice.runtracker.domain.GaitClassifier
import com.tspolice.runtracker.domain.MotionSignalProcessor
import com.tspolice.runtracker.domain.MotionState
import com.tspolice.runtracker.domain.MotionStateAudioCue
import com.tspolice.runtracker.domain.ProgressionForecaster
import com.tspolice.runtracker.domain.RunMode
import com.tspolice.runtracker.domain.RunSessionRecord
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
