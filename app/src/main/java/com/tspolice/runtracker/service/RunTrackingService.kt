package com.tspolice.runtracker.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tspolice.runtracker.data.RunRepository
import com.tspolice.runtracker.domain.GaitClassifier
import com.tspolice.runtracker.domain.LapSplit
import com.tspolice.runtracker.domain.LiveTelemetry
import com.tspolice.runtracker.domain.MotionSignalProcessor
import com.tspolice.runtracker.domain.MotionState
import com.tspolice.runtracker.domain.MotionStateAudioCue
import com.tspolice.runtracker.domain.RunMode
import com.tspolice.runtracker.domain.RunSessionRecord
import com.tspolice.runtracker.domain.TsPoliceBenchmark
import com.tspolice.runtracker.ui.MainActivity
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max

/**
 * Pocket-safe foreground tracking & sensor-fusion service:
 *  - Fuses GPS ground speed + hardware Step Detector + 3-axis Accelerometer RMS
 *  - Auto-classifies STANDING (Auto-Pause), WALKING, JOGGING, RUNNING, and TS POLICE TARGET PACE
 *  - Supports Hands-Free Auto-Start when sustained jogging/running is detected
 *  - Announces 400m splits & 1.6km finish delta via Android TextToSpeech
 */
class RunTrackingService : Service(), SensorEventListener, LocationListener {

    companion object {
        private const val CHANNEL_ID = "ts_police_run_tracking"
        private const val NOTIFICATION_ID = 1600
        private const val TICK_INTERVAL_MS = 200L
        private const val GPS_FRESHNESS_MS = 5500L
        private const val MAX_ACCEPTABLE_GPS_ACCURACY_M = 28f
    }

    inner class LocalBinder : Binder() {
        fun getService(): RunTrackingService = this@RunTrackingService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(LiveTelemetry) -> Unit>()

    private lateinit var repository: RunRepository
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager

    private val signalProcessor = MotionSignalProcessor()
    private val gaitClassifier = GaitClassifier()

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var toneGenerator: ToneGenerator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingStateSpeechRunnable: Runnable? = null

    private var sensorsRegistered = false
    private var locationRegistered = false
    private var hasHardwareStepDetector = false
    private var uiForegroundVisible = false

    // Session & state-transition audio tracking
    private var isRecording = false
    private var isAutoStartArmed = false
    private var autoStartMotionStreakMs = 0L
    private var lastAnnouncedState: MotionState = MotionState.STANDING
    private var lastMilestoneSpeechMs: Long = -10_000L
    private var lastFinishSpeechMs: Long = -10_000L
    private var runMode: RunMode = RunMode.TIMED_1600M
    private var sessionStartedWallMs: Long = 0L
    private var lastTickElapsedMs: Long = 0L

    private var distanceMeters = 0.0
    private var activeDurationSec = 0.0
    private var pausedDurationSec = 0.0
    private var runningSec = 0.0
    private var joggingSec = 0.0
    private var walkingSec = 0.0
    private var runningMeters = 0.0
    private var joggingMeters = 0.0
    private var walkingMeters = 0.0
    private var maxSpeedMps = 0.0
    private var cadenceSamplesSum = 0.0
    private var cadenceSamplesCount = 0
    private var first1600mSplitSec: Double? = null
    private var lastCompletedSessionId: Long? = null

    private val lapSplits = mutableListOf<LapSplit>()
    private var nextLapBoundaryM = TsPoliceBenchmark.LAP_DISTANCE_M
    private var prevLapCumulativeSec = 0.0

    // GPS tracking state
    private var lastValidLocation: Location? = null
    private var lastLocationElapsedMs: Long = 0L
    private var smoothedGpsSpeedMps: Double = 0.0
    private var latestGpsAccuracyM: Float? = null
    private var lastStepCountForFallback: Int = 0
    private var lastNotificationUpdateMs: Long = 0L

    private val tickRunnable = object : Runnable {
        override fun run() {
            onTick()
            mainHandler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = RunRepository(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        syncThresholdsFromPrefs()
        createNotificationChannel()

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttrs)
            .setWillPauseWhenDucked(false)
            .build()
        toneGenerator = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 85) }.getOrNull()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setAudioAttributes(audioAttrs)
                tts?.setSpeechRate(1.06f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        abandonAudioFocusQuietly()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        abandonAudioFocusQuietly()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        abandonAudioFocusQuietly()
                    }
                })
                ttsReady = true
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        startPassiveTelemetry()
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startPassiveTelemetry()
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        unregisterHardwareStreams()
        abandonAudioFocusQuietly()
        runCatching { toneGenerator?.release() }
        toneGenerator = null
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    fun setUiForegroundVisible(visible: Boolean) {
        val becameVisible = visible && !uiForegroundVisible
        uiForegroundVisible = visible
        if (becameVisible && !isRecording && !isAutoStartArmed) {
            lastAnnouncedState = gaitClassifier.currentState()
        }
    }

    fun addTelemetryListener(listener: (LiveTelemetry) -> Unit) {
        listeners.add(listener)
        listener(snapshotTelemetry())
    }

    fun removeTelemetryListener(listener: (LiveTelemetry) -> Unit) {
        listeners.remove(listener)
    }

    fun syncThresholdsFromPrefs() {
        gaitClassifier.runningSpeedThresholdMps = repository.runningThresholdMps
        gaitClassifier.targetSpeedThresholdMps = TsPoliceBenchmark.targetSpeedMps(repository.targetTimeSec)
    }

    fun refreshPermissionsAndSensors() {
        registerLocationIfPermitted()
        registerMotionSensors()
    }

    fun setRunMode(mode: RunMode) {
        if (!isRecording) {
            runMode = mode
            broadcastTelemetry()
        }
    }

    fun setAutoStartArmed(armed: Boolean) {
        if (isRecording) return
        isAutoStartArmed = armed
        autoStartMotionStreakMs = 0L
        if (armed) {
            promoteToForeground()
            speakCoach("Auto start armed. Begin jogging or running whenever ready.")
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        broadcastTelemetry()
    }

    fun startWorkout(mode: RunMode = runMode, triggeredAutomatically: Boolean = false) {
        if (isRecording) return
        syncThresholdsFromPrefs()
        refreshPermissionsAndSensors()

        runMode = mode
        isRecording = true
        isAutoStartArmed = false
        autoStartMotionStreakMs = 0L
        sessionStartedWallMs = System.currentTimeMillis()
        lastTickElapsedMs = SystemClock.elapsedRealtime()
        lastCompletedSessionId = null

        distanceMeters = 0.0
        activeDurationSec = 0.0
        pausedDurationSec = 0.0
        runningSec = 0.0
        joggingSec = 0.0
        walkingSec = 0.0
        runningMeters = 0.0
        joggingMeters = 0.0
        walkingMeters = 0.0
        maxSpeedMps = 0.0
        cadenceSamplesSum = 0.0
        cadenceSamplesCount = 0
        first1600mSplitSec = null
        lapSplits.clear()
        nextLapBoundaryM = TsPoliceBenchmark.LAP_DISTANCE_M
        prevLapCumulativeSec = 0.0
        lastStepCountForFallback = signalProcessor.totalSteps
        lastAnnouncedState = gaitClassifier.currentState()

        promoteToForeground()
        vibratePulse(140L)
        if (triggeredAutomatically) {
            speakCoach("Movement detected. Timer started.")
        } else if (mode == RunMode.TIMED_1600M) {
            val targetFmt = spokenTime(repository.targetTimeSec)
            speakCoach("Starting 1.6 kilometer trial. Target $targetFmt.")
        } else {
            speakCoach("Practice run started.")
        }
        broadcastTelemetry()
    }

    /**
     * Stops recording. Saves to database if distance >= 50m (or forceSave = true).
     * Returns saved row ID or null if discarded.
     */
    fun finishWorkout(forceSave: Boolean = true): Long? {
        if (!isRecording) return null
        isRecording = false
        stopForeground(STOP_FOREGROUND_REMOVE)

        var savedId: Long? = null
        if (forceSave && (distanceMeters >= 30.0 || activeDurationSec >= 10.0)) {
            val avgCadence = if (cadenceSamplesCount > 0) {
                cadenceSamplesSum / cadenceSamplesCount
            } else 0.0
            val equiv1600 = TsPoliceBenchmark.computeEquiv1600mSec(
                distanceMeters = max(distanceMeters, 1.0),
                activeDurationSec = max(activeDurationSec, 1.0),
                first1600mSplitSec = first1600mSplitSec
            )
            val record = RunSessionRecord(
                startedAtEpochMs = sessionStartedWallMs,
                dateIso = LocalDate.now().toString(),
                mode = runMode,
                distanceMeters = distanceMeters,
                activeDurationSec = activeDurationSec,
                pausedDurationSec = pausedDurationSec,
                first1600mSplitSec = first1600mSplitSec,
                equiv1600mSec = equiv1600,
                avgCadenceSpm = avgCadence,
                maxSpeedMps = maxSpeedMps,
                runningSec = runningSec,
                joggingSec = joggingSec,
                walkingSec = walkingSec,
                lapSplits = lapSplits.toList(),
                notes = if (runMode == RunMode.TIMED_1600M && distanceMeters >= 1595.0) {
                    "Official 1.6 km Timed Trial"
                } else {
                    "Auto-tracked ${runMode.title}"
                },
                isDemo = false
            )
            savedId = repository.insertSession(record)
            lastCompletedSessionId = savedId
            vibratePulse(320L)
        }
        broadcastTelemetry()
        return savedId
    }

    fun discardWorkout() {
        isRecording = false
        isAutoStartArmed = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        distanceMeters = 0.0
        activeDurationSec = 0.0
        pausedDurationSec = 0.0
        lapSplits.clear()
        broadcastTelemetry()
    }

    private fun startPassiveTelemetry() {
        refreshPermissionsAndSensors()
        mainHandler.removeCallbacks(tickRunnable)
        lastTickElapsedMs = SystemClock.elapsedRealtime()
        mainHandler.post(tickRunnable)
    }

    private fun registerMotionSensors() {
        if (sensorsRegistered) return
        val canReadSteps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
        } else true

        if (canReadSteps) {
            val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            if (stepDetector != null) {
                hasHardwareStepDetector = sensorManager.registerListener(
                    this,
                    stepDetector,
                    SensorManager.SENSOR_DELAY_FASTEST
                )
            }
        }

        val linearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (linearAccel != null) {
            sensorManager.registerListener(this, linearAccel, SensorManager.SENSOR_DELAY_GAME)
        } else {
            val rawAccel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (rawAccel != null) {
                sensorManager.registerListener(this, rawAccel, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        sensorsRegistered = true
    }

    private fun registerLocationIfPermitted() {
        if (locationRegistered) return
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return

        runCatching {
            if (hasFine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500L,
                    0f,
                    this,
                    Looper.getMainLooper()
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                locationManager.allProviders.contains(LocationManager.FUSED_PROVIDER)
            ) {
                locationManager.requestLocationUpdates(
                    LocationManager.FUSED_PROVIDER,
                    500L,
                    0f,
                    this,
                    Looper.getMainLooper()
                )
            }
        }
        locationRegistered = true
    }

    private fun unregisterHardwareStreams() {
        runCatching { sensorManager.unregisterListener(this) }
        runCatching { locationManager.removeUpdates(this) }
        sensorsRegistered = false
        locationRegistered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val nowMs = SystemClock.elapsedRealtime()
        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                val steps = event.values.firstOrNull()?.toInt()?.coerceAtLeast(1) ?: 1
                signalProcessor.onHardwareStepDetected(nowMs, steps)
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                signalProcessor.onAccelerationSample(
                    nowMs = nowMs,
                    ax = event.values[0],
                    ay = event.values[1],
                    az = event.values[2],
                    includesGravity = false
                )
            }
            Sensor.TYPE_ACCELEROMETER -> {
                signalProcessor.onAccelerationSample(
                    nowMs = nowMs,
                    ax = event.values[0],
                    ay = event.values[1],
                    az = event.values[2],
                    includesGravity = true
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        latestGpsAccuracyM = if (location.hasAccuracy()) location.accuracy else null
        if (location.hasAccuracy() && location.accuracy > MAX_ACCEPTABLE_GPS_ACCURACY_M) {
            return
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        val prev = lastValidLocation
        var observedSpeed = if (location.hasSpeed()) location.speed.toDouble() else -1.0

        if (prev != null) {
            val dtSec = ((location.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9)
                .takeIf { it > 0.05 } ?: ((nowElapsed - lastLocationElapsedMs) / 1000.0)
            val deltaMeters = prev.distanceTo(location).toDouble()

            if (dtSec > 0.1) {
                val derivedSpeed = deltaMeters / dtSec
                // Reject impossible GPS teleport spikes (> 11.5 m/s = 41.4 km/h)
                if (derivedSpeed > 11.5) return
                if (observedSpeed < 0.0) {
                    observedSpeed = derivedSpeed
                }
            }

            // Accumulate GPS distance when recording and not below noise floor
            val minMoveFloor = max(1.2, (location.accuracy * 0.22).toDouble())
            if (isRecording && deltaMeters >= minMoveFloor) {
                addDistanceDelta(deltaMeters)
                lastValidLocation = location
                lastLocationElapsedMs = nowElapsed
            } else if (!isRecording) {
                lastValidLocation = location
                lastLocationElapsedMs = nowElapsed
            }
        } else {
            lastValidLocation = location
            lastLocationElapsedMs = nowElapsed
        }

        if (observedSpeed >= 0.0) {
            smoothedGpsSpeedMps = if (smoothedGpsSpeedMps <= 0.05) {
                observedSpeed
            } else {
                0.62 * observedSpeed + 0.38 * smoothedGpsSpeedMps
            }
        }
    }

    @Deprecated("Deprecated in Android API 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    private fun onTick() {
        val nowMs = SystemClock.elapsedRealtime()
        val dtMs = (nowMs - lastTickElapsedMs).coerceIn(0L, 2000L)
        val dtSec = dtMs / 1000.0
        lastTickElapsedMs = nowMs

        val gpsFresh = lastLocationElapsedMs > 0L && (nowMs - lastLocationElapsedMs) <= GPS_FRESHNESS_MS
        if (!gpsFresh) {
            smoothedGpsSpeedMps *= 0.88 // gentle decay if GPS signal drops indoors
            if (smoothedGpsSpeedMps < 0.15) smoothedGpsSpeedMps = 0.0
        }

        val cadenceSpm = signalProcessor.getCadenceSpm(nowMs)
        val accelRms = signalProcessor.getDynamicAccelRms(nowMs)
        val gpsForClassifier = if (gpsFresh) smoothedGpsSpeedMps else null

        val state = gaitClassifier.update(
            nowMs = nowMs,
            gpsSpeedMps = gpsForClassifier,
            cadenceSpm = cadenceSpm,
            dynamicAccelRms = accelRms
        )

        if (state != lastAnnouncedState) {
            val previousState = lastAnnouncedState
            lastAnnouncedState = state
            onMotionStateChanged(previousState, state, nowMs)
        }

        val stepSpeedMps = gaitClassifier.estimateSpeedFromStepsMps(cadenceSpm, accelRms)
        val effectiveSpeedMps = when {
            !state.countsAsActiveMovement -> 0.0
            gpsFresh && smoothedGpsSpeedMps > 0.25 -> smoothedGpsSpeedMps
            else -> stepSpeedMps
        }

        // Hands-Free Auto-Start detection (3.0 seconds of sustained Jogging/Running)
        if (!isRecording && isAutoStartArmed) {
            if (state == MotionState.JOGGING || state == MotionState.RUNNING || state == MotionState.TARGET_PACE) {
                autoStartMotionStreakMs += dtMs
                if (autoStartMotionStreakMs >= 3000L) {
                    startWorkout(mode = runMode, triggeredAutomatically = true)
                    return
                }
            } else {
                autoStartMotionStreakMs = 0L
            }
        }

        if (isRecording) {
            // Indoor / pre-GPS-lock step-stride distance fallback
            val currentSteps = signalProcessor.totalSteps
            val newSteps = (currentSteps - lastStepCountForFallback).coerceAtLeast(0)
            lastStepCountForFallback = currentSteps

            if (!gpsFresh && repository.indoorStepFallbackEnabled && newSteps > 0 && state.countsAsActiveMovement) {
                val strideM = gaitClassifier.estimateStrideLengthMeters(cadenceSpm, accelRms)
                addDistanceDelta(newSteps * strideM)
            }

            if (state.countsAsActiveMovement) {
                activeDurationSec += dtSec
                if (effectiveSpeedMps > maxSpeedMps) maxSpeedMps = effectiveSpeedMps
                if (cadenceSpm > 30.0) {
                    cadenceSamplesSum += cadenceSpm
                    cadenceSamplesCount++
                }
                when (state) {
                    MotionState.RUNNING, MotionState.TARGET_PACE -> runningSec += dtSec
                    MotionState.JOGGING -> joggingSec += dtSec
                    MotionState.WALKING -> walkingSec += dtSec
                    MotionState.STANDING -> Unit
                }
            } else {
                pausedDurationSec += dtSec
            }

            if (nowMs - lastNotificationUpdateMs >= 1000L) {
                lastNotificationUpdateMs = nowMs
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildForegroundNotification(state, effectiveSpeedMps))
            }
        }

        broadcastTelemetry(effectiveSpeedMps, gpsFresh, cadenceSpm, accelRms)
    }

    private fun addDistanceDelta(deltaM: Double) {
        if (deltaM <= 0.0 || !isRecording) return
        val state = gaitClassifier.currentState()
        distanceMeters += deltaM
        when (state) {
            MotionState.RUNNING, MotionState.TARGET_PACE -> runningMeters += deltaM
            MotionState.JOGGING -> joggingMeters += deltaM
            MotionState.WALKING -> walkingMeters += deltaM
            MotionState.STANDING -> runningMeters += deltaM
        }

        // Record 400m splits
        while (distanceMeters >= nextLapBoundaryM) {
            val lapNum = lapSplits.size + 1
            val lapDur = (activeDurationSec - prevLapCumulativeSec).coerceAtLeast(0.1)
            prevLapCumulativeSec = activeDurationSec
            lapSplits.add(
                LapSplit(
                    lapNumber = lapNum,
                    distanceMeters = nextLapBoundaryM,
                    lapDurationSec = lapDur,
                    cumulativeSec = activeDurationSec,
                    dominantState = state
                )
            )
            if (nextLapBoundaryM == 1600.0 && first1600mSplitSec == null) {
                first1600mSplitSec = activeDurationSec
            }
            onLapCrossed(lapNum, nextLapBoundaryM, lapDur)
            nextLapBoundaryM += TsPoliceBenchmark.LAP_DISTANCE_M
        }

        // Auto-complete 1.6 km TS Police Timed Trial at 1,600m!
        if (runMode == RunMode.TIMED_1600M && distanceMeters >= TsPoliceBenchmark.TARGET_DISTANCE_M) {
            distanceMeters = TsPoliceBenchmark.TARGET_DISTANCE_M
            val finalSec = activeDurationSec
            val targetSec = repository.targetTimeSec
            finishWorkout(forceSave = true)
            val diff = finalSec - targetSec
            val verdict = if (diff <= 0.0) {
                "Target achieved by ${ (-diff).toInt() } seconds!"
            } else {
                "${ diff.toInt() } seconds above target."
            }
            lastFinishSpeechMs = SystemClock.elapsedRealtime()
            speakCoach(
                "1.6 kilometer trial complete! Time ${spokenTime(finalSec)}. $verdict"
            )
        }
    }

    private fun onLapCrossed(lapNum: Int, boundaryM: Double, lapDurSec: Double) {
        vibratePulse(180L)
        if (runMode == RunMode.TIMED_1600M && boundaryM >= 1600.0) return // handled by finish announcement
        val delta = TsPoliceBenchmark.splitDeltaSec(boundaryM, activeDurationSec, repository.targetTimeSec)
        val deltaSpeech = if (delta != null) {
            if (delta <= 0.0) "Ahead by ${(-delta).toInt()} seconds."
            else "Behind by ${delta.toInt()} seconds."
        } else ""
        speakCoach(
            "Lap $lapNum, ${boundaryM.toInt()} meters. Split ${spokenTime(lapDurSec)}. $deltaSpeech"
        )
    }

    /**
     * Plays an instant earcon beep + clear TTS voice cue whenever the auto-detected MotionState changes.
     * Active during recorded workouts, Auto-Start armed mode, and whenever the app UI is visible on screen.
     */
    private fun onMotionStateChanged(previousState: MotionState, newState: MotionState, nowMs: Long) {
        if (!repository.stateChangeAudioEnabled) return
        if (!isRecording && !isAutoStartArmed && !uiForegroundVisible) return
        if (!isRecording && (nowMs - lastFinishSpeechMs) in 0L..6000L) return

        requestAudioFocusQuietly()
        playStateTone(newState)
        if (isRecording) {
            vibratePulse(70L)
        }

        val speech = MotionStateAudioCue.buildTransitionSpeech(
            previousState = previousState,
            newState = newState,
            isRecording = isRecording,
            targetTimeSec = repository.targetTimeSec
        )
        speakStateTransition(speech, nowMs)
    }

    /**
     * Manual test/preview cue triggered from the UI audio toggle or settings screen.
     */
    fun previewStateChangeAudio(sampleState: MotionState = MotionState.RUNNING) {
        requestAudioFocusQuietly()
        playStateTone(sampleState)
        val speech = if (repository.stateChangeAudioEnabled) {
            val cue = MotionStateAudioCue.buildTransitionSpeech(
                previousState = MotionState.JOGGING,
                newState = sampleState,
                isRecording = true,
                targetTimeSec = repository.targetTimeSec
            )
            "State audio active. $cue"
        } else {
            "State audio muted."
        }
        speakStateTransition(speech, SystemClock.elapsedRealtime())
    }

    private fun speakStateTransition(speech: String, nowMs: Long) {
        pendingStateSpeechRunnable?.let { mainHandler.removeCallbacks(it) }
        if (!ttsReady) {
            mainHandler.postDelayed({ abandonAudioFocusQuietly() }, 500L)
            return
        }
        val queueMode = if ((nowMs - lastMilestoneSpeechMs) in 0L..4500L) {
            TextToSpeech.QUEUE_ADD
        } else {
            TextToSpeech.QUEUE_FLUSH
        }
        val r = Runnable {
            tts?.speak(speech, queueMode, null, "ts_state_${SystemClock.elapsedRealtime()}")
        }
        pendingStateSpeechRunnable = r
        mainHandler.postDelayed(r, 130L)
    }

    private fun playStateTone(state: MotionState) {
        val (toneType, durationMs) = when (state) {
            MotionState.TARGET_PACE -> ToneGenerator.TONE_PROP_ACK to 160
            MotionState.RUNNING -> ToneGenerator.TONE_PROP_BEEP to 110
            MotionState.JOGGING -> ToneGenerator.TONE_PROP_BEEP2 to 120
            MotionState.WALKING -> ToneGenerator.TONE_SUP_PIP to 90
            MotionState.STANDING -> ToneGenerator.TONE_PROP_NACK to 160
        }
        runCatching {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
            }
            toneGenerator?.startTone(toneType, durationMs)
        }
    }

    private fun speakCoach(message: String) {
        if (!repository.voiceCoachEnabled || !ttsReady) return
        pendingStateSpeechRunnable?.let { mainHandler.removeCallbacks(it) }
        lastMilestoneSpeechMs = SystemClock.elapsedRealtime()
        requestAudioFocusQuietly()
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "ts_coach_$lastMilestoneSpeechMs")
    }

    private fun requestAudioFocusQuietly() {
        val req = audioFocusRequest ?: return
        runCatching { audioManager?.requestAudioFocus(req) }
    }

    private fun abandonAudioFocusQuietly() {
        val req = audioFocusRequest ?: return
        runCatching { audioManager?.abandonAudioFocusRequest(req) }
    }

    private fun spokenTime(sec: Double): String {
        val total = sec.toInt()
        val m = total / 60
        val s = total % 60
        return if (m > 0) "$m minutes $s seconds" else "$s seconds"
    }

    private fun vibratePulse(durationMs: Long) {
        runCatching {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun promoteToForeground() {
        val notification = buildForegroundNotification(gaitClassifier.currentState(), 0.0)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildForegroundNotification(state: MotionState, speedMps: Double): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (isAutoStartArmed && !isRecording) {
            "Auto-Start Armed • Waiting for Jog/Run"
        } else {
            "${String.format("%.2f km", distanceMeters / 1000.0)} • ${TsPoliceBenchmark.formatDuration(activeDurationSec)} • ${state.label}"
        }
        val subtitle = "Pace ${TsPoliceBenchmark.formatPacePerKm(speedMps)} • Target 1.6km < 7:15"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .build()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "TS Police 1.6km Live Run Tracking",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows live 1.6km distance, split pace, and auto-detected Running/Jogging gait"
        }
        nm.createNotificationChannel(channel)
    }

    private fun snapshotTelemetry(
        effectiveSpeedMps: Double = 0.0,
        gpsFresh: Boolean = false,
        cadenceSpm: Double = 0.0,
        accelRms: Double = 0.0
    ): LiveTelemetry = LiveTelemetry(
        isRecording = isRecording,
        isAutoStartArmed = isAutoStartArmed,
        mode = runMode,
        motionState = gaitClassifier.currentState(),
        distanceMeters = distanceMeters,
        activeDurationSec = activeDurationSec,
        pausedDurationSec = pausedDurationSec,
        currentSpeedMps = effectiveSpeedMps,
        cadenceSpm = cadenceSpm,
        dynamicAccelRms = accelRms,
        totalSteps = signalProcessor.totalSteps,
        gpsAccuracyMeters = latestGpsAccuracyM,
        isGpsLocked = gpsFresh,
        hasHardwareStepSensor = hasHardwareStepDetector,
        runningSec = runningSec,
        joggingSec = joggingSec,
        walkingSec = walkingSec,
        runningMeters = runningMeters,
        joggingMeters = joggingMeters,
        walkingMeters = walkingMeters,
        first1600mSplitSec = first1600mSplitSec,
        lapSplits = lapSplits.toList(),
        justFinishedSessionId = lastCompletedSessionId
    )

    private fun broadcastTelemetry(
        effectiveSpeedMps: Double = 0.0,
        gpsFresh: Boolean = false,
        cadenceSpm: Double = 0.0,
        accelRms: Double = 0.0
    ) {
        val snap = snapshotTelemetry(effectiveSpeedMps, gpsFresh, cadenceSpm, accelRms)
        for (l in listeners) {
            l(snap)
        }
    }
}
