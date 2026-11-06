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
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
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
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tspolice.runtracker.data.RunRepository
import com.tspolice.runtracker.domain.DailyActivitySummary
import com.tspolice.runtracker.domain.GaitClassifier
import com.tspolice.runtracker.domain.HourlySensorBucket
import com.tspolice.runtracker.domain.IntervalPhase
import com.tspolice.runtracker.domain.IntervalWorkoutState
import com.tspolice.runtracker.domain.LapSplit
import com.tspolice.runtracker.domain.LiveTelemetry
import com.tspolice.runtracker.domain.MotionSignalProcessor
import com.tspolice.runtracker.domain.MotionState
import com.tspolice.runtracker.domain.MotionStateAudioCue
import com.tspolice.runtracker.domain.PocketAudioMode
import com.tspolice.runtracker.domain.PtTrialPhase
import com.tspolice.runtracker.domain.RunMode
import com.tspolice.runtracker.domain.RunSessionRecord
import com.tspolice.runtracker.domain.SamsungDailySnapshot
import com.tspolice.runtracker.domain.SensorTransitionEvent
import com.tspolice.runtracker.domain.TsPoliceBenchmark
import com.tspolice.runtracker.health.SamsungHealthSyncManager
import com.tspolice.runtracker.telemetry.TuningTelemetryManager
import com.tspolice.runtracker.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Pocket-safe foreground tracking & sensor-fusion service:
 *  - Fuses GPS ground speed + hardware Step Detector + dt-normalized 3-axis Accelerometer RMS
 *  - Smart 24/7 Battery Duty-Cycling: puts GPS & WakeLock to sleep when stationary indoors, waking instantly on steps
 *  - True TSLPRB Official Unpaused Race Clock during 1.6 km PT Trials (standing counts as penalty, never cheats clock!)
 *  - Guided 6x400m Track Interval Coach with automatic 90s recovery countdowns
 *  - Indian English (en-IN) TTS voice cues + 1,400m Final-200m Kick callout
 */
class RunTrackingService : Service(), SensorEventListener, LocationListener {

    companion object {
        private const val CHANNEL_ID = "ts_police_run_tracking"
        private const val NOTIFICATION_ID = 1600
        private const val ACTIVE_TICK_INTERVAL_MS = 200L
        private const val STANDBY_TICK_INTERVAL_MS = 1200L
        private const val STANDBY_IDLE_THRESHOLD_MS = 45_000L
        private const val GPS_FRESHNESS_MS = 5500L
        private const val MAX_ACCEPTABLE_GPS_ACCURACY_M = 26f
        private const val MAX_PT_TRIAL_GPS_ACCURACY_M = 24f
        private const val MAX_PLAUSIBLE_VEHICLE_SPEED_MPS = 55.0 // 198 km/h — keeps real bike/vehicle GPS speeds visible
        private const val STANDBY_SENSOR_BATCH_LATENCY_US = 3_000_000
        private const val WORKOUT_CKPT_PREFS = "ts_police_active_workout_ckpt"
        const val ACTION_MUTE_POCKET_AUDIO = "com.tspolice.runtracker.ACTION_MUTE_POCKET_AUDIO"
        const val EXTRA_BOOT_AUTOSTART = "com.tspolice.runtracker.EXTRA_BOOT_AUTOSTART"
    }

    inner class LocalBinder : Binder() {
        fun getService(): RunTrackingService = this@RunTrackingService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(LiveTelemetry) -> Unit>()

    private lateinit var repository: RunRepository
    private lateinit var tuningTelemetryManager: TuningTelemetryManager
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var serviceAlive = false
    private var remotePullListenerThread: Thread? = null
    private var lastAutoTelemetryCheckElapsedMs: Long = 0L

    private val signalProcessor = MotionSignalProcessor()
    private val gaitClassifier = GaitClassifier()

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var toneGenerator: ToneGenerator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingStateSpeechRunnable: Runnable? = null

    private var accelRegistered = false
    private var accelLowPowerMode = false
    private var stepDetectorRegistered = false
    private var stepCounterRegistered = false
    private var sigMotionArmed = false
    private var locationRegistered = false
    private var hasHardwareStepDetector = false
    private var lastStepCounterHwValue: Double? = null
    private var stepsAtLastStepCounter: Int = 0
    private var uiForegroundVisible = false
    private var isInForeground = false
    private var startedFromBootBackground = false
    private var lastForegroundServiceType = 0
    private var lastNotifiedSignature = ""

    // Smart 24/7 Battery Duty-Cycling state
    private var isLowPowerStandby = false
    private var lastMovementDetectedElapsedMs = 0L
    private var continuousFootMovementMs = 0L

    // 1.6 km PT Trial state & state-transition audio tracking
    private var ptTrialPhase: PtTrialPhase = PtTrialPhase.IDLE
    private var isRecording = false
    private var isAutoStartArmed = false
    private var ptJogRunConfirmMs = 0L
    private var ptLaunchBufferSec = 0.0
    private var ptLaunchBufferMeters = 0.0
    private var trialStartElapsedMs: Long = 0L
    private var trialInitialCreditSec: Double = 0.0
    private var lastTrialStepDtSec: Double = 0.2
    private var sessionTrackScale: Double = 1.0
    private var lastWorkoutCheckpointElapsedMs: Long = 0L
    private var announcedFinal200mKick = false
    private var lastAnnouncedState: MotionState = MotionState.STANDING
    private var lastMilestoneSpeechMs: Long = -10_000L
    private var lastFinishSpeechMs: Long = -10_000L
    private var lastStoppedPenaltySpeechElapsedMs: Long = -60_000L
    private var runMode: RunMode = RunMode.TIMED_1600M
    private var sessionStartedWallMs: Long = 0L
    private var lastTickElapsedMs: Long = 0L

    // Active 1.6 km PT Trial counters (activeDurationSec = continuous TSLPRB official race clock!)
    private var distanceMeters = 0.0
    private var activeDurationSec = 0.0
    private var pausedDurationSec = 0.0 // Stopped/standing penalty seconds during trial
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

    // Guided 400m Speed-Endurance Interval Coach state
    private var intervalPhase: IntervalPhase = IntervalPhase.IDLE
    private var intervalCurrentRep = 1
    private var intervalTotalReps = 6
    private var intervalRepDistM = 0.0
    private var intervalRepSec = 0.0
    private var intervalRestRemainingSec = 0.0
    private var intervalRestDurationSec = 90.0
    private var intervalTargetRepSec = 104.0
    private var intervalAnnounced10sReady = false
    private var intervalStartedWallMs = 0L
    private var intervalMaxSpeedMps = 0.0
    private var intervalCadenceSum = 0.0
    private var intervalCadenceCount = 0
    private val intervalCompletedSplitsSec = mutableListOf<Double>()

    // Always-On 24/7 All-Day Auto Activity counters (persisted per YYYY-MM-DD in SQLite)
    private var dailyDateIso: String = ""
    private var dailyWalkSec = 0.0
    private var dailyJogSec = 0.0
    private var dailyRunSec = 0.0
    private var dailyWalkMeters = 0.0
    private var dailyJogMeters = 0.0
    private var dailyRunMeters = 0.0
    private var dailyTotalSteps = 0
    private var dailyMaxSpeedMps = 0.0
    private var dailyMaxCadenceSpm = 0.0
    private var dailyDirty = false
    private var lastDailyPersistElapsedMs = 0L
    private var lastSignalStepsProcessed = 0

    // Intraday Hourly Sensor Fine-Tuning Bucket (persisted alongside daily summary)
    private var currentHourlyBucket: HourlySensorBucket? = null
    private var hourlyDirty = false
    private var lastHwStepsProcessed = 0
    private var lastSwStepsProcessed = 0

    // 12-second Pre-Vehicle Checkpoint Ring Buffer (erases initial bike rollout before GPS hits vehicle threshold)
    private data class PreVehicleCheckpoint(
        val elapsedMs: Long,
        val dateIso: String,
        val walkSec: Double,
        val jogSec: Double,
        val runSec: Double,
        val walkMeters: Double,
        val jogMeters: Double,
        val runMeters: Double,
        val totalSteps: Int,
        val maxSpeedMps: Double,
        val maxCadenceSpm: Double,
        val hourlyBucket: HourlySensorBucket?
    )
    private val preVehicleCheckpoints = ArrayDeque<PreVehicleCheckpoint>()
    private var lastCheckpointSampleMs = 0L
    private var wasInVehicleLatchLastTick = false
    private var wasInVehicleOrCooldownLastTick = false

    // Automatic Open Jog/Run bout detector (logs >= 600m casual jog/runs into 7:15 Progress & ETA automatically!)
    private var openBoutStartedWallMs = 0L
    private var openBoutStartedElapsedMs = 0L
    private var openBoutJogRunMeters = 0.0
    private var openBoutActiveSec = 0.0
    private var openBoutPausedSec = 0.0
    private var openBoutRunSec = 0.0
    private var openBoutJogSec = 0.0
    private var openBoutWalkSec = 0.0
    private var openBoutMaxSpeed = 0.0
    private var openBoutCadenceSum = 0.0
    private var openBoutCadenceCount = 0
    private var openBoutFirst1600Sec: Double? = null
    private var openBoutIdleMs = 0L

    // GPS tracking & 400m curve compensation state
    private var lastValidLocation: Location? = null
    private var lastValidLocationElapsedMs: Long = 0L
    private var lastValidBearingDeg: Float? = null
    private var lastLocationElapsedMs: Long = 0L
    private var lastPureGpsFixElapsedMs: Long = -100_000L
    private var lastGpsDistanceContributionMs: Long = -100_000L
    private var fallbackMetersCreditedSinceLastGps: Double = 0.0
    private var smoothedGpsSpeedMps: Double = 0.0
    private var latestGpsAccuracyM: Float? = null
    private var lastNotificationUpdateMs: Long = 0L

    private val sigMotionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            sigMotionArmed = false
            wakeFromLowPowerStandby()
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            onTick()
            val delay = if (isLowPowerStandby) STANDBY_TICK_INTERVAL_MS else ACTIVE_TICK_INTERVAL_MS
            mainHandler.postDelayed(this, delay)
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceAlive = true
        repository = RunRepository.getInstance(applicationContext)
        tuningTelemetryManager = TuningTelemetryManager(
            context = applicationContext,
            repository = repository,
            healthSyncManager = SamsungHealthSyncManager(applicationContext, repository)
        )
        signalProcessor.verifiedHardwarePedometerActive = repository.hasVerifiedHardwarePedometer
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        lastMovementDetectedElapsedMs = SystemClock.elapsedRealtime()
        syncThresholdsFromPrefs()
        ensureDailyLoadedForToday()
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TSPoliceTracker::PocketGaitWakeLock"
        )?.apply {
            setReferenceCounted(false)
        }

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttrs)
            .setWillPauseWhenDucked(false)
            .build()
        toneGenerator = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 95) }.getOrNull()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val indianLocale = Locale("en", "IN")
                val avail = tts?.isLanguageAvailable(indianLocale) ?: TextToSpeech.LANG_NOT_SUPPORTED
                tts?.language = if (avail >= TextToSpeech.LANG_AVAILABLE) indianLocale else Locale.US
                tts?.setAudioAttributes(audioAttrs)
                tts?.setSpeechRate(1.05f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = abandonAudioFocusQuietly()
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = abandonAudioFocusQuietly()
                    override fun onError(utteranceId: String?, errorCode: Int) = abandonAudioFocusQuietly()
                })
                ttsReady = true
            }
        }

        syncRemoteDiagnosticsSetting()
        mainHandler.postDelayed({
            if (serviceAlive && repository.remoteDiagnosticsOptIn) {
                triggerBackgroundAutoTelemetryIfDue(reason = "service_startup", checkPendingCommands = true)
            }
        }, 5_000L)
    }

    override fun onBind(intent: Intent?): IBinder {
        startedFromBootBackground = false
        startPassiveTelemetry()
        syncPocketForegroundAndWakeLock()
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_MUTE_POCKET_AUDIO) {
            repository.pocketAudioMode = PocketAudioMode.MUTED
            lastNotifiedSignature = ""
            syncPocketForegroundAndWakeLock()
            broadcastTelemetry()
            return START_STICKY
        }
        if (intent?.getBooleanExtra(EXTRA_BOOT_AUTOSTART, false) == true) {
            startedFromBootBackground = true
        }
        if (intent == null) {
            // OS restarted service via START_STICKY after memory reclaim — recover in-progress workout if any
            restoreActiveWorkoutCheckpointIfNeeded()
        }
        startPassiveTelemetry()
        syncPocketForegroundAndWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        serviceAlive = false
        remotePullListenerThread?.interrupt()
        remotePullListenerThread = null
        if (isHighPrecisionSessionActive()) {
            persistActiveWorkoutCheckpoint()
        }
        flushOpenBoutIfQualified()
        persistDailySummaryNow(async = false)
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        unregisterHardwareStreams()
        releaseWakeLockQuietly()
        abandonAudioFocusQuietly()
        runCatching { toneGenerator?.release() }
        toneGenerator = null
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    fun setUiForegroundVisible(visible: Boolean) {
        uiForegroundVisible = visible
        if (visible) {
            startedFromBootBackground = false
            wakeFromLowPowerStandby()
        }
        syncPocketForegroundAndWakeLock()
    }

    private fun isHighPrecisionSessionActive(): Boolean =
        ptTrialPhase != PtTrialPhase.IDLE || intervalPhase != IntervalPhase.IDLE

    /**
     * Keeps foreground service notification alive when tracking is enabled, while intelligently releasing
     * CPU WakeLock & GPS during stationary Low-Power Standby so 24/7 mode barely touches battery!
     */
    fun syncPocketForegroundAndWakeLock() {
        val keepForeground =
            repository.allDayTrackingEnabled || isHighPrecisionSessionActive() || repository.stateChangeAudioEnabled
        if (keepForeground) {
            promoteToForeground()
        } else {
            demoteFromForeground()
        }

        val needCpuWakeLock = isHighPrecisionSessionActive() ||
            (!isLowPowerStandby && (keepForeground || repository.pocketAudioMode == PocketAudioMode.ALL_STATES))
        if (needCpuWakeLock) {
            acquireWakeLockQuietly()
        } else {
            releaseWakeLockQuietly()
        }
    }

    private fun enterLowPowerStandby() {
        if (isLowPowerStandby || isHighPrecisionSessionActive() || uiForegroundVisible ||
            gaitClassifier.isInOrCoolingDownFromVehicle()
        ) return
        isLowPowerStandby = true
        pauseLocationUpdatesOnly()
        remotePullListenerThread?.interrupt()
        remotePullListenerThread = null
        registerAccelerometerWithRate(lowPower = true)
        armSignificantMotionWakeSensor()
        syncPocketForegroundAndWakeLock()
    }

    private fun wakeFromLowPowerStandby() {
        lastMovementDetectedElapsedMs = SystemClock.elapsedRealtime()
        if (!isLowPowerStandby) return
        isLowPowerStandby = false
        registerAccelerometerWithRate(lowPower = false)
        if (isHighPrecisionSessionActive() || uiForegroundVisible) {
            registerLocationIfPermitted()
        }
        syncRemoteDiagnosticsSetting()
        syncPocketForegroundAndWakeLock()
        mainHandler.removeCallbacks(tickRunnable)
        lastTickElapsedMs = SystemClock.elapsedRealtime()
        mainHandler.post(tickRunnable)
    }

    private fun persistActiveWorkoutCheckpoint() {
        val ckpt = getSharedPreferences(WORKOUT_CKPT_PREFS, Context.MODE_PRIVATE)
        if (ptTrialPhase != PtTrialPhase.RUNNING_TRIAL && intervalPhase == IntervalPhase.IDLE) {
            ckpt.edit().clear().apply()
            return
        }
        ckpt.edit()
            .putLong("savedAtWallMs", System.currentTimeMillis())
            .putString("ptTrialPhase", ptTrialPhase.name)
            .putLong("sessionStartedWallMs", sessionStartedWallMs)
            .putFloat("distanceMeters", distanceMeters.toFloat())
            .putFloat("activeDurationSec", activeDurationSec.toFloat())
            .putFloat("pausedDurationSec", pausedDurationSec.toFloat())
            .putFloat("runningSec", runningSec.toFloat())
            .putFloat("joggingSec", joggingSec.toFloat())
            .putFloat("walkingSec", walkingSec.toFloat())
            .putFloat("runningMeters", runningMeters.toFloat())
            .putFloat("joggingMeters", joggingMeters.toFloat())
            .putFloat("walkingMeters", walkingMeters.toFloat())
            .putFloat("maxSpeedMps", maxSpeedMps.toFloat())
            .putFloat("cadenceSamplesSum", cadenceSamplesSum.toFloat())
            .putInt("cadenceSamplesCount", cadenceSamplesCount)
            .putFloat("nextLapBoundaryM", nextLapBoundaryM.toFloat())
            .putFloat("prevLapCumulativeSec", prevLapCumulativeSec.toFloat())
            .putFloat("sessionTrackScale", sessionTrackScale.toFloat())
            .putBoolean("announcedFinal200mKick", announcedFinal200mKick)
            .putString("splitsJson", RunRepository.encodeSplitsJson(lapSplits))
            .putString("intervalPhase", intervalPhase.name)
            .putInt("intervalCurrentRep", intervalCurrentRep)
            .putInt("intervalTotalReps", intervalTotalReps)
            .putFloat("intervalRepDistM", intervalRepDistM.toFloat())
            .putFloat("intervalRepSec", intervalRepSec.toFloat())
            .putFloat("intervalRestRemainingSec", intervalRestRemainingSec.toFloat())
            .putFloat("intervalRestDurationSec", intervalRestDurationSec.toFloat())
            .putFloat("intervalTargetRepSec", intervalTargetRepSec.toFloat())
            .putLong("intervalStartedWallMs", intervalStartedWallMs)
            .putFloat("intervalMaxSpeedMps", intervalMaxSpeedMps.toFloat())
            .putFloat("intervalCadenceSum", intervalCadenceSum.toFloat())
            .putInt("intervalCadenceCount", intervalCadenceCount)
            .putString("intervalSplitsCsv", intervalCompletedSplitsSec.joinToString(","))
            .apply()
    }

    private fun clearActiveWorkoutCheckpoint() {
        getSharedPreferences(WORKOUT_CKPT_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun restoreActiveWorkoutCheckpointIfNeeded() {
        if (isHighPrecisionSessionActive()) return
        val ckpt = getSharedPreferences(WORKOUT_CKPT_PREFS, Context.MODE_PRIVATE)
        val savedAt = ckpt.getLong("savedAtWallMs", 0L)
        if (savedAt <= 0L) return
        val ageMs = System.currentTimeMillis() - savedAt
        if (ageMs !in 0L..(2L * 60L * 60L * 1000L)) {
            clearActiveWorkoutCheckpoint()
            return
        }
        val gapSec = ageMs / 1000.0
        val savedPtPhase = runCatching {
            PtTrialPhase.valueOf(ckpt.getString("ptTrialPhase", PtTrialPhase.IDLE.name) ?: PtTrialPhase.IDLE.name)
        }.getOrDefault(PtTrialPhase.IDLE)
        val savedIntPhase = runCatching {
            IntervalPhase.valueOf(ckpt.getString("intervalPhase", IntervalPhase.IDLE.name) ?: IntervalPhase.IDLE.name)
        }.getOrDefault(IntervalPhase.IDLE)

        if (savedPtPhase == PtTrialPhase.RUNNING_TRIAL) {
            runMode = RunMode.TIMED_1600M
            ptTrialPhase = PtTrialPhase.RUNNING_TRIAL
            isRecording = true
            isAutoStartArmed = false
            startedFromBootBackground = false
            sessionStartedWallMs = ckpt.getLong("sessionStartedWallMs", System.currentTimeMillis())
            distanceMeters = ckpt.getFloat("distanceMeters", 0f).toDouble()
            val savedActive = ckpt.getFloat("activeDurationSec", 0f).toDouble() + gapSec
            activeDurationSec = savedActive
            trialStartElapsedMs = SystemClock.elapsedRealtime()
            trialInitialCreditSec = savedActive
            pausedDurationSec = ckpt.getFloat("pausedDurationSec", 0f).toDouble()
            runningSec = ckpt.getFloat("runningSec", 0f).toDouble()
            joggingSec = ckpt.getFloat("joggingSec", 0f).toDouble()
            walkingSec = ckpt.getFloat("walkingSec", 0f).toDouble()
            runningMeters = ckpt.getFloat("runningMeters", 0f).toDouble()
            joggingMeters = ckpt.getFloat("joggingMeters", 0f).toDouble()
            walkingMeters = ckpt.getFloat("walkingMeters", 0f).toDouble()
            maxSpeedMps = ckpt.getFloat("maxSpeedMps", 0f).toDouble()
            cadenceSamplesSum = ckpt.getFloat("cadenceSamplesSum", 0f).toDouble()
            cadenceSamplesCount = ckpt.getInt("cadenceSamplesCount", 0)
            nextLapBoundaryM = ckpt.getFloat("nextLapBoundaryM", TsPoliceBenchmark.LAP_DISTANCE_M.toFloat()).toDouble()
            prevLapCumulativeSec = ckpt.getFloat("prevLapCumulativeSec", 0f).toDouble()
            sessionTrackScale = ckpt.getFloat("sessionTrackScale", 1.0f).toDouble().coerceIn(0.92, 1.08)
            announcedFinal200mKick = ckpt.getBoolean("announcedFinal200mKick", false)
            lapSplits.clear()
            lapSplits.addAll(RunRepository.decodeSplitsJson(ckpt.getString("splitsJson", "").orEmpty()))
            registerLocationIfPermitted()
        } else if (savedIntPhase == IntervalPhase.RUNNING_REP || savedIntPhase == IntervalPhase.REST_COUNTDOWN) {
            startedFromBootBackground = false
            intervalPhase = savedIntPhase
            intervalCurrentRep = ckpt.getInt("intervalCurrentRep", 1)
            intervalTotalReps = ckpt.getInt("intervalTotalReps", 6)
            intervalRepDistM = ckpt.getFloat("intervalRepDistM", 0f).toDouble()
            intervalRepSec = ckpt.getFloat("intervalRepSec", 0f).toDouble() +
                if (savedIntPhase == IntervalPhase.RUNNING_REP) gapSec else 0.0
            intervalRestDurationSec = ckpt.getFloat("intervalRestDurationSec", 90f).toDouble()
            intervalRestRemainingSec = if (savedIntPhase == IntervalPhase.REST_COUNTDOWN) {
                (ckpt.getFloat("intervalRestRemainingSec", 0f).toDouble() - gapSec).coerceAtLeast(1.0)
            } else 0.0
            intervalTargetRepSec = ckpt.getFloat("intervalTargetRepSec", 104f).toDouble()
            intervalStartedWallMs = ckpt.getLong("intervalStartedWallMs", System.currentTimeMillis())
            intervalMaxSpeedMps = ckpt.getFloat("intervalMaxSpeedMps", 0f).toDouble()
            intervalCadenceSum = ckpt.getFloat("intervalCadenceSum", 0f).toDouble()
            intervalCadenceCount = ckpt.getInt("intervalCadenceCount", 0)
            intervalCompletedSplitsSec.clear()
            ckpt.getString("intervalSplitsCsv", "").orEmpty()
                .split(",")
                .mapNotNull { it.trim().toDoubleOrNull() }
                .forEach { intervalCompletedSplitsSec.add(it) }
            registerLocationIfPermitted()
        }
    }

    private fun armSignificantMotionWakeSensor() {
        if (sigMotionArmed) return
        val sigSensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        sigMotionArmed = runCatching {
            sensorManager.requestTriggerSensor(sigMotionListener, sigSensor)
        }.getOrDefault(false)
    }

    private fun acquireWakeLockQuietly() {
        runCatching {
            val wl = wakeLock ?: return
            if (!wl.isHeld) {
                wl.acquire(4L * 60L * 60L * 1000L)
            }
        }
    }

    private fun releaseWakeLockQuietly() {
        runCatching {
            val wl = wakeLock ?: return
            if (wl.isHeld) {
                wl.release()
            }
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
        gaitClassifier.strideScaleFactor = repository.effectiveStrideScale()
        intervalTargetRepSec = (TsPoliceBenchmark.targetLap400mSec(repository.targetTimeSec) * 0.96).coerceIn(75.0, 145.0)
    }

    fun refreshPermissionsAndSensors() {
        registerMotionSensors()
        if (!isLowPowerStandby && (isHighPrecisionSessionActive() || uiForegroundVisible)) {
            registerLocationIfPermitted()
        }
        syncPocketForegroundAndWakeLock()
    }

    private fun ensureDailyLoadedForToday() {
        val today = LocalDate.now().toString()
        if (dailyDateIso == today) return
        if (dailyDateIso.isNotBlank() && dailyDirty) {
            // Defer flushing an active open bout if the runner is actively jogging/running across midnight!
            val activelyRunningAtMidnight = openBoutStartedWallMs > 0L &&
                (gaitClassifier.currentState().isJogOrRun() || openBoutIdleMs < 15_000L)
            if (!activelyRunningAtMidnight) {
                flushOpenBoutIfQualified()
            }
            persistDailySummaryNow(async = true)
        }
        val stored = repository.getDailyActivity(today)
        dailyDateIso = today
        dailyWalkSec = stored.walkingSec
        dailyJogSec = stored.joggingSec
        dailyRunSec = stored.runningSec
        dailyWalkMeters = stored.walkingMeters
        dailyJogMeters = stored.joggingMeters
        dailyRunMeters = stored.runningMeters
        dailyTotalSteps = stored.totalSteps
        dailyMaxSpeedMps = stored.maxSpeedMps
        dailyMaxCadenceSpm = stored.maxCadenceSpm
        dailyDirty = false
        lastSignalStepsProcessed = signalProcessor.totalSteps
        lastHwStepsProcessed = signalProcessor.hardwareStepsCount
        lastSwStepsProcessed = signalProcessor.fallbackSoftwareStepsCount
        ensureHourlyBucketLoaded()
    }

    private fun ensureHourlyBucketLoaded(): HourlySensorBucket {
        val nowDt = LocalDateTime.now()
        val dIso = nowDt.toLocalDate().toString()
        val hr = nowDt.hour
        val bKey = String.format(Locale.US, "%sT%02d", dIso, hr)
        val existing = currentHourlyBucket
        if (existing != null && existing.bucketKey == bKey) {
            return existing
        }
        if (existing != null && hourlyDirty) {
            val snapToSave = existing
            hourlyDirty = false
            if (serviceAlive) {
                serviceScope.launch { runCatching { repository.upsertHourlySensorBucket(snapToSave) } }
            } else {
                runCatching { repository.upsertHourlySensorBucket(snapToSave) }
            }
        }
        val loaded = runCatching { repository.getHourlySensorBucket(bKey, dIso, hr) }
            .getOrElse { HourlySensorBucket(bucketKey = bKey, dateIso = dIso, hourOfDay = hr) }
        currentHourlyBucket = loaded
        return loaded
    }

    fun flushTuningBuffersNow() {
        persistDailySummaryNow(async = false)
        currentHourlyBucket?.let { b ->
            runCatching { repository.upsertHourlySensorBucket(b) }
            hourlyDirty = false
        }
    }

    private fun currentDailySnapshot(): DailyActivitySummary = DailyActivitySummary(
        dateIso = dailyDateIso.ifBlank { LocalDate.now().toString() },
        walkingSec = dailyWalkSec,
        joggingSec = dailyJogSec,
        runningSec = dailyRunSec,
        walkingMeters = dailyWalkMeters,
        joggingMeters = dailyJogMeters,
        runningMeters = dailyRunMeters,
        totalSteps = dailyTotalSteps,
        maxSpeedMps = dailyMaxSpeedMps,
        maxCadenceSpm = dailyMaxCadenceSpm,
        isDemo = false
    )

    private fun persistDailySummaryNow(async: Boolean = true) {
        if (dailyDateIso.isBlank()) return
        val dailySnap = currentDailySnapshot()
        val bucketSnap = currentHourlyBucket?.takeIf { hourlyDirty }
        dailyDirty = false
        hourlyDirty = false
        lastDailyPersistElapsedMs = SystemClock.elapsedRealtime()
        if (async && serviceAlive) {
            serviceScope.launch {
                runCatching {
                    repository.upsertDailyActivity(dailySnap)
                    if (bucketSnap != null) {
                        repository.upsertHourlySensorBucket(bucketSnap)
                    }
                }
            }
        } else {
            runCatching {
                repository.upsertDailyActivity(dailySnap)
                if (bucketSnap != null) {
                    repository.upsertHourlySensorBucket(bucketSnap)
                }
            }
        }
    }

    fun resetTodayDailySummary() {
        dailyDateIso = LocalDate.now().toString()
        dailyWalkSec = 0.0
        dailyJogSec = 0.0
        dailyRunSec = 0.0
        dailyWalkMeters = 0.0
        dailyJogMeters = 0.0
        dailyRunMeters = 0.0
        dailyTotalSteps = 0
        dailyMaxSpeedMps = 0.0
        dailyMaxCadenceSpm = 0.0
        preVehicleCheckpoints.clear()
        resetOpenBout()
        persistDailySummaryNow()
        broadcastTelemetry()
    }

    fun alignTodayWithSamsungHealth(samsung: SamsungDailySnapshot) {
        ensureDailyLoadedForToday()
        if (samsung.dateIso.isNotBlank() && samsung.dateIso != dailyDateIso) return
        persistDailySummaryNow(async = false)

        val aligned = repository.alignDailyActivityWithSamsung(dailyDateIso, samsung)
        dailyWalkSec = aligned.walkingSec
        dailyJogSec = aligned.joggingSec
        dailyRunSec = aligned.runningSec
        dailyWalkMeters = aligned.walkingMeters
        dailyJogMeters = aligned.joggingMeters
        dailyRunMeters = aligned.runningMeters
        dailyTotalSteps = aligned.totalSteps
        dailyMaxSpeedMps = aligned.maxSpeedMps
        dailyMaxCadenceSpm = aligned.maxCadenceSpm
        preVehicleCheckpoints.clear()
        resetOpenBout()
        dailyDirty = false
        lastDailyPersistElapsedMs = SystemClock.elapsedRealtime()
        broadcastTelemetry()
    }

    // =========================================================================
    // 1.6 KM PT TRIAL CONTROLS (UNPAUSED TSLPRB RACE CLOCK + MANUAL LAP SNAP)
    // =========================================================================
    fun armPtTrial() {
        if (ptTrialPhase != PtTrialPhase.IDLE) return
        if (intervalPhase != IntervalPhase.IDLE) cancelIntervalWorkout(silent = true)
        startedFromBootBackground = false
        gaitClassifier.clearVehicleLatchForManualWorkout()
        signalProcessor.suppressStepsForVehicle = false
        signalProcessor.manualWorkoutActive = true
        wasInVehicleLatchLastTick = false
        lastStoppedPenaltySpeechElapsedMs = -60_000L
        wakeFromLowPowerStandby()
        registerLocationIfPermitted()
        flushOpenBoutIfQualified()
        resetOpenBout()
        syncThresholdsFromPrefs()
        refreshPermissionsAndSensors()

        runMode = RunMode.TIMED_1600M
        ptTrialPhase = PtTrialPhase.ARMED_WAITING_FOR_JOG_RUN
        isAutoStartArmed = true
        isRecording = false
        ptJogRunConfirmMs = 0L
        ptLaunchBufferSec = 0.0
        ptLaunchBufferMeters = 0.0
        sessionTrackScale = 1.0
        fallbackMetersCreditedSinceLastGps = 0.0
        announcedFinal200mKick = false
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

        syncPocketForegroundAndWakeLock()
        vibratePulse(140L)
        val targetFmt = spokenTime(repository.targetTimeSec)
        speakCoach("1.6 kilometer P T trial armed. Target $targetFmt. Clock starts when you jog or run.")
        broadcastTelemetry()
    }

    private fun beginArmedPtTrialNow(initialState: MotionState) {
        runMode = RunMode.TIMED_1600M
        ptTrialPhase = PtTrialPhase.RUNNING_TRIAL
        isAutoStartArmed = false
        isRecording = true
        startedFromBootBackground = false
        signalProcessor.manualWorkoutActive = true
        lastStoppedPenaltySpeechElapsedMs = -60_000L
        val creditSec = ptLaunchBufferSec.coerceIn(0.0, 2.6)
        val creditMeters = ptLaunchBufferMeters.coerceIn(0.0, 14.0)
        val nowElapsed = SystemClock.elapsedRealtime()
        trialStartElapsedMs = nowElapsed
        trialInitialCreditSec = creditSec
        lastTrialStepDtSec = 0.2
        sessionTrackScale = 1.0
        sessionStartedWallMs = System.currentTimeMillis() - (creditSec * 1000.0).toLong()
        lastCompletedSessionId = null
        announcedFinal200mKick = false

        ptJogRunConfirmMs = 0L
        ptLaunchBufferSec = 0.0
        ptLaunchBufferMeters = 0.0

        activeDurationSec = creditSec
        pausedDurationSec = 0.0
        if (initialState == MotionState.JOGGING) {
            joggingSec = creditSec
            runningSec = 0.0
        } else {
            runningSec = creditSec
            joggingSec = 0.0
        }
        walkingSec = 0.0
        distanceMeters = 0.0
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

        if (creditMeters > 0.0) {
            addPtTrialDistanceDelta(creditMeters, initialState, allowDuringWarmup = true)
        }
        persistActiveWorkoutCheckpoint()

        syncPocketForegroundAndWakeLock()
        playStateTone(MotionState.TARGET_PACE)
        vibratePulse(200L)
        speakCoach("Go! Official 1.6 kilometer clock running.")
        broadcastTelemetry()
    }

    /**
     * Bidirectional (±30m) manual 400m chalk-line lap snap for curved grounds where phone GPS drifts slightly.
     *  - Under-measured (`370m..399.5m` at chalk line): snaps to `nextLapBoundaryM` without inflating `runningMeters`
     *  - Over-measured (`400.5m..430m` right after auto-split): pulls `distanceMeters` back to the chalk boundary
     *    and updates the just-recorded split timestamp, nudging `sessionTrackScale` within `0.92..1.08`.
     */
    fun snapNext400mLap(): Boolean {
        if (ptTrialPhase != PtTrialPhase.RUNNING_TRIAL) return false
        val remainingToNextLap = nextLapBoundaryM - distanceMeters
        if (remainingToNextLap in 0.5..30.0) {
            val observedM = distanceMeters.coerceAtLeast(1.0)
            sessionTrackScale = (sessionTrackScale * (nextLapBoundaryM / observedM)).coerceIn(0.92, 1.08)
            addPtTrialDistanceDelta(
                deltaM = remainingToNextLap,
                state = gaitClassifier.currentState().let { if (it.countsAsActiveMovement) it else MotionState.RUNNING },
                allowDuringWarmup = true,
                isManualSnap = true
            )
            persistActiveWorkoutCheckpoint()
            broadcastTelemetry()
            return true
        }
        val prevBoundaryM = nextLapBoundaryM - TsPoliceBenchmark.LAP_DISTANCE_M
        val overshootPastPrevM = distanceMeters - prevBoundaryM
        if (prevBoundaryM >= TsPoliceBenchmark.LAP_DISTANCE_M &&
            overshootPastPrevM in 0.5..30.0 &&
            lapSplits.isNotEmpty()
        ) {
            val observedM = distanceMeters.coerceAtLeast(1.0)
            sessionTrackScale = (sessionTrackScale * (prevBoundaryM / observedM)).coerceIn(0.92, 1.08)
            distanceMeters = prevBoundaryM
            val lastIdx = lapSplits.lastIndex
            val prevCum = if (lastIdx > 0) lapSplits[lastIdx - 1].cumulativeSec else 0.0
            val updatedDur = (activeDurationSec - prevCum).coerceAtLeast(0.1)
            lapSplits[lastIdx] = lapSplits[lastIdx].copy(
                lapDurationSec = updatedDur,
                cumulativeSec = activeDurationSec
            )
            prevLapCumulativeSec = activeDurationSec
            persistActiveWorkoutCheckpoint()
            broadcastTelemetry()
            return true
        }
        return false
    }

    fun finishWorkout(forceSave: Boolean = true): Long? {
        if (ptTrialPhase != PtTrialPhase.RUNNING_TRIAL && !isRecording) return null
        ptTrialPhase = PtTrialPhase.IDLE
        isRecording = false
        isAutoStartArmed = false
        clearActiveWorkoutCheckpoint()
        signalProcessor.manualWorkoutActive = isHighPrecisionSessionActive()
        syncPocketForegroundAndWakeLock()

        var savedId: Long? = null
        if (forceSave && (distanceMeters >= 50.0 || activeDurationSec >= 15.0)) {
            val avgCadence = if (cadenceSamplesCount > 0) {
                cadenceSamplesSum / cadenceSamplesCount
            } else 0.0
            val equiv1600 = TsPoliceBenchmark.computeEquiv1600mSec(
                distanceMeters = max(distanceMeters, 1.0),
                activeDurationSec = max(activeDurationSec, 1.0),
                first1600mSplitSec = first1600mSplitSec
            )
            val stopPenaltyNote = if (pausedDurationSec >= 3.0) {
                " • ${pausedDurationSec.toInt()}s stopped"
            } else ""
            val startedDateIso = if (sessionStartedWallMs > 0L) {
                Instant.ofEpochMilli(sessionStartedWallMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()
            } else {
                LocalDate.now().toString()
            }
            val record = RunSessionRecord(
                startedAtEpochMs = sessionStartedWallMs,
                dateIso = startedDateIso,
                mode = RunMode.TIMED_1600M,
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
                notes = if (distanceMeters >= 1595.0) {
                    "Official 1.6 km PT Trial$stopPenaltyNote"
                } else {
                    "1.6 km PT Trial (${distanceMeters.toInt()}m partial$stopPenaltyNote)"
                },
                isDemo = false
            )
            savedId = repository.insertSession(record)
            lastCompletedSessionId = savedId
            vibratePulse(320L)
        }
        persistDailySummaryNow(async = true)
        if (savedId != null) {
            triggerBackgroundAutoTelemetryIfDue(reason = "pt_trial_finished", force = true)
        }
        broadcastTelemetry()
        return savedId
    }

    fun cancelPtTrial() {
        val wasRunningOrArmed = ptTrialPhase != PtTrialPhase.IDLE || isRecording || isAutoStartArmed
        ptTrialPhase = PtTrialPhase.IDLE
        isRecording = false
        isAutoStartArmed = false
        clearActiveWorkoutCheckpoint()
        signalProcessor.manualWorkoutActive = isHighPrecisionSessionActive()
        ptJogRunConfirmMs = 0L
        ptLaunchBufferSec = 0.0
        ptLaunchBufferMeters = 0.0
        sessionTrackScale = 1.0
        announcedFinal200mKick = false
        distanceMeters = 0.0
        activeDurationSec = 0.0
        pausedDurationSec = 0.0
        lapSplits.clear()
        syncPocketForegroundAndWakeLock()
        if (wasRunningOrArmed) {
            speakCoach("1.6 kilometer trial cancelled.")
        }
        broadcastTelemetry()
    }

    fun discardWorkout() = cancelPtTrial()

    // =========================================================================
    // GUIDED 400M SPEED-ENDURANCE INTERVAL COACH (6 x 400m / 4 x 400m)
    // =========================================================================
    fun startIntervalWorkout(
        totalReps: Int = 6,
        restSeconds: Double = 90.0,
        targetRepSec: Double? = null
    ) {
        if (ptTrialPhase != PtTrialPhase.IDLE) cancelPtTrial()
        startedFromBootBackground = false
        gaitClassifier.clearVehicleLatchForManualWorkout()
        signalProcessor.suppressStepsForVehicle = false
        signalProcessor.manualWorkoutActive = true
        wasInVehicleLatchLastTick = false
        lastStoppedPenaltySpeechElapsedMs = -60_000L
        wakeFromLowPowerStandby()
        registerLocationIfPermitted()
        flushOpenBoutIfQualified()
        resetOpenBout()
        syncThresholdsFromPrefs()
        if (targetRepSec != null && targetRepSec > 35.0) {
            intervalTargetRepSec = targetRepSec.coerceIn(70.0, 180.0)
        }

        intervalTotalReps = totalReps.coerceIn(2, 12)
        intervalRestDurationSec = restSeconds.coerceIn(30.0, 240.0)
        intervalCurrentRep = 1
        intervalRepDistM = 0.0
        intervalRepSec = 0.0
        intervalRestRemainingSec = 0.0
        intervalAnnounced10sReady = false
        intervalStartedWallMs = System.currentTimeMillis()
        intervalMaxSpeedMps = 0.0
        intervalCadenceSum = 0.0
        intervalCadenceCount = 0
        intervalCompletedSplitsSec.clear()
        intervalPhase = IntervalPhase.RUNNING_REP
        persistActiveWorkoutCheckpoint()

        syncPocketForegroundAndWakeLock()
        vibratePulse(200L)
        speakCoach("Starting $intervalTotalReps by 400 meter track intervals. Target ${spokenTime(intervalTargetRepSec)} per rep. Rep 1, Go!")
        broadcastTelemetry()
    }

    fun skipIntervalRestNow() {
        if (intervalPhase != IntervalPhase.REST_COUNTDOWN) return
        intervalCurrentRep++
        intervalRepDistM = 0.0
        intervalRepSec = 0.0
        intervalRestRemainingSec = 0.0
        intervalAnnounced10sReady = false
        intervalPhase = IntervalPhase.RUNNING_REP
        persistActiveWorkoutCheckpoint()
        vibratePulse(180L)
        speakCoach("Rep $intervalCurrentRep of $intervalTotalReps, Go!")
        broadcastTelemetry()
    }

    fun cancelIntervalWorkout(silent: Boolean = false) {
        val wasActive = intervalPhase != IntervalPhase.IDLE
        intervalPhase = IntervalPhase.IDLE
        intervalRepDistM = 0.0
        intervalRepSec = 0.0
        intervalRestRemainingSec = 0.0
        clearActiveWorkoutCheckpoint()
        syncPocketForegroundAndWakeLock()
        if (wasActive && !silent) {
            speakCoach("Interval workout stopped.")
        }
        broadcastTelemetry()
    }

    private fun addIntervalDistanceDelta(deltaM: Double, state: MotionState) {
        if (deltaM <= 0.0 || intervalPhase != IntervalPhase.RUNNING_REP || state == MotionState.VEHICLE) return
        intervalRepDistM += deltaM
        if (intervalRepDistM >= 400.0) {
            val finishedSplit = intervalRepSec.coerceAtLeast(35.0)
            intervalCompletedSplitsSec.add(finishedSplit)
            vibratePulse(260L)
            if (intervalCurrentRep >= intervalTotalReps) {
                completeIntervalSession()
            } else {
                intervalPhase = IntervalPhase.REST_COUNTDOWN
                intervalRestRemainingSec = intervalRestDurationSec
                intervalAnnounced10sReady = false
                persistActiveWorkoutCheckpoint()
                speakCoach(
                    "Rep $intervalCurrentRep done in ${spokenTime(finishedSplit)}. Walk and recover for ${intervalRestDurationSec.toInt()} seconds."
                )
            }
        }
    }

    private fun completeIntervalSession() {
        intervalPhase = IntervalPhase.IDLE
        clearActiveWorkoutCheckpoint()
        val repsDone = intervalCompletedSplitsSec.size
        if (repsDone > 0) {
            val totalDistM = repsDone * 400.0
            val totalActiveSec = intervalCompletedSplitsSec.sum()
            val avgRepSec = totalActiveSec / repsDone
            val projected1600 = avgRepSec * 4.0 * 1.03 // slight aerobic fatigue factor from 400m reps to continuous 1600m
            val avgCad = if (intervalCadenceCount > 0) intervalCadenceSum / intervalCadenceCount else 172.0
            val splits = intervalCompletedSplitsSec.mapIndexed { idx, dur ->
                LapSplit(
                    lapNumber = idx + 1,
                    distanceMeters = (idx + 1) * 400.0,
                    lapDurationSec = dur,
                    cumulativeSec = intervalCompletedSplitsSec.take(idx + 1).sum(),
                    dominantState = MotionState.RUNNING
                )
            }
            val startedDateIso = if (intervalStartedWallMs > 0L) {
                Instant.ofEpochMilli(intervalStartedWallMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()
            } else {
                LocalDate.now().toString()
            }
            val savedId = repository.insertSession(
                RunSessionRecord(
                    startedAtEpochMs = intervalStartedWallMs,
                    dateIso = startedDateIso,
                    mode = RunMode.INTERVAL_400M,
                    distanceMeters = totalDistM,
                    activeDurationSec = totalActiveSec,
                    pausedDurationSec = (repsDone - 1).coerceAtLeast(0) * intervalRestDurationSec,
                    first1600mSplitSec = null,
                    equiv1600mSec = projected1600,
                    avgCadenceSpm = avgCad,
                    maxSpeedMps = intervalMaxSpeedMps,
                    runningSec = totalActiveSec,
                    joggingSec = 0.0,
                    walkingSec = 0.0,
                    lapSplits = splits,
                    notes = "${repsDone}x400m Intervals (Avg ${TsPoliceBenchmark.formatDuration(avgRepSec)}/rep)",
                    isDemo = false
                )
            )
            lastCompletedSessionId = savedId
            speakCoach(
                "Interval workout complete! $repsDone reps finished, average ${spokenTime(avgRepSec)} per 400 meters."
            )
        }
        syncPocketForegroundAndWakeLock()
        persistDailySummaryNow(async = true)
        if (repsDone > 0) {
            triggerBackgroundAutoTelemetryIfDue(reason = "intervals_finished", force = true)
        }
        broadcastTelemetry()
    }

    // =========================================================================
    // HARDWARE SENSORS & SMART GPS DUTY-CYCLING
    // =========================================================================
    private fun startPassiveTelemetry() {
        refreshPermissionsAndSensors()
        mainHandler.removeCallbacks(tickRunnable)
        lastTickElapsedMs = SystemClock.elapsedRealtime()
        mainHandler.post(tickRunnable)
    }

    private fun registerMotionSensors() {
        val canReadSteps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
        } else true

        if (canReadSteps && !stepDetectorRegistered) {
            val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR, true)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            if (stepDetector != null) {
                stepDetectorRegistered = sensorManager.registerListener(
                    this,
                    stepDetector,
                    SensorManager.SENSOR_DELAY_FASTEST,
                    0
                )
                hasHardwareStepDetector = stepDetectorRegistered
                signalProcessor.hardwareStepSensorAvailable = hasHardwareStepDetector
            }
        }

        if (canReadSteps && !stepCounterRegistered) {
            val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER, true)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (stepCounter != null) {
                lastStepCounterHwValue = null
                stepCounterRegistered = sensorManager.registerListener(
                    this,
                    stepCounter,
                    SensorManager.SENSOR_DELAY_NORMAL,
                    0
                )
            }
        }

        if (!accelRegistered) {
            registerAccelerometerWithRate(lowPower = isLowPowerStandby)
        }
    }

    private fun registerAccelerometerWithRate(lowPower: Boolean) {
        val rawAccel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        if (accelRegistered && accelLowPowerMode == lowPower) return
        if (accelRegistered) {
            runCatching { sensorManager.unregisterListener(this, rawAccel) }
            accelRegistered = false
        }
        val delay = if (lowPower) SensorManager.SENSOR_DELAY_NORMAL else SensorManager.SENSOR_DELAY_GAME
        val maxBatchUs = if (lowPower) STANDBY_SENSOR_BATCH_LATENCY_US else 0
        accelRegistered = sensorManager.registerListener(this, rawAccel, delay, maxBatchUs)
        accelLowPowerMode = lowPower
    }

    private fun registerLocationIfPermitted() {
        if (locationRegistered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            startedFromBootBackground && !uiForegroundVisible && !isHighPrecisionSessionActive()
        ) {
            // Avoid background location access while running as a post-boot HEALTH-only standby service
            return
        }
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
            locationRegistered = true
            syncPocketForegroundAndWakeLock()
        }
    }

    private fun pauseLocationUpdatesOnly() {
        if (!locationRegistered) return
        runCatching { locationManager.removeUpdates(this) }
        locationRegistered = false
        lastValidLocation = null
        lastValidBearingDeg = null
        fallbackMetersCreditedSinceLastGps = 0.0
    }

    private fun unregisterHardwareStreams() {
        runCatching { sensorManager.unregisterListener(this) }
        if (sigMotionArmed) {
            sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)?.let {
                runCatching { sensorManager.cancelTriggerSensor(sigMotionListener, it) }
            }
            sigMotionArmed = false
        }
        pauseLocationUpdatesOnly()
        accelRegistered = false
        stepDetectorRegistered = false
        stepCounterRegistered = false
        lastStepCounterHwValue = null
    }

    private fun resolveSensorTimestampMs(event: SensorEvent, fallbackNowMs: Long): Long {
        val eventMs = event.timestamp / 1_000_000L
        return if (eventMs > 0L && abs(fallbackNowMs - eventMs) <= 20_000L) {
            eventMs.coerceAtMost(fallbackNowMs)
        } else {
            fallbackNowMs
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val nowMs = SystemClock.elapsedRealtime()
        val sensorMs = resolveSensorTimestampMs(event, nowMs)
        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                val steps = event.values.firstOrNull()?.toInt()?.coerceAtLeast(1) ?: 1
                if (!repository.hasVerifiedHardwarePedometer) {
                    repository.hasVerifiedHardwarePedometer = true
                }
                signalProcessor.onHardwareStepDetected(sensorMs, steps)
                wakeFromLowPowerStandby()
            }
            Sensor.TYPE_STEP_COUNTER -> {
                val cumulative = event.values.firstOrNull()?.toDouble() ?: return
                if (gaitClassifier.isInOrCoolingDownFromVehicle() || signalProcessor.suppressStepsForVehicle) {
                    // Discard batched scooter/vehicle vibration steps and re-anchor hardware counter
                    lastStepCounterHwValue = cumulative
                    stepsAtLastStepCounter = signalProcessor.totalSteps
                    return
                }
                val prevHw = lastStepCounterHwValue
                if (prevHw != null && cumulative > prevHw) {
                    val hwDelta = (cumulative - prevHw).toInt()
                    if (hwDelta > 0 && !repository.hasVerifiedHardwarePedometer) {
                        repository.hasVerifiedHardwarePedometer = true
                    }
                    val alreadyCounted = (signalProcessor.totalSteps - stepsAtLastStepCounter).coerceAtLeast(0)
                    signalProcessor.reconcileStepCounterDelta(sensorMs, hwDelta, alreadyCounted)
                    if (hwDelta > 0) wakeFromLowPowerStandby()
                }
                lastStepCounterHwValue = cumulative
                stepsAtLastStepCounter = signalProcessor.totalSteps
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val step = signalProcessor.onAccelerationSample(
                    nowMs = sensorMs,
                    ax = event.values[0],
                    ay = event.values[1],
                    az = event.values[2],
                    includesGravity = false
                )
                if (step) wakeFromLowPowerStandby()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val step = signalProcessor.onAccelerationSample(
                    nowMs = sensorMs,
                    ax = event.values[0],
                    ay = event.values[1],
                    az = event.values[2],
                    includesGravity = true
                )
                if (step || (isLowPowerStandby && signalProcessor.getDynamicAccelRms(sensorMs) >= 0.75)) {
                    wakeFromLowPowerStandby()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        val nowElapsed = SystemClock.elapsedRealtime()
        // Single-provider arbitration: when outdoor GPS_PROVIDER is fresh (<= 3.5s), ignore FUSED_PROVIDER
        // so two slightly offset streams never interleave zig-zag chords or artificial bearing swings!
        if (location.provider == LocationManager.GPS_PROVIDER) {
            lastPureGpsFixElapsedMs = nowElapsed
        } else if ((nowElapsed - lastPureGpsFixElapsedMs) <= 3_500L) {
            return
        }

        latestGpsAccuracyM = if (location.hasAccuracy()) location.accuracy else null
        val maxAllowedAcc = if (isHighPrecisionSessionActive()) {
            MAX_PT_TRIAL_GPS_ACCURACY_M
        } else {
            MAX_ACCEPTABLE_GPS_ACCURACY_M
        }
        if (location.hasAccuracy() && location.accuracy > maxAllowedAcc) {
            return
        }

        if (location.hasAccuracy()) {
            val hb = ensureHourlyBucketLoaded()
            currentHourlyBucket = hb.copy(
                gpsFixCount = hb.gpsFixCount + 1,
                gpsAccSumMeters = hb.gpsAccSumMeters + location.accuracy.toDouble()
            )
            hourlyDirty = true
        }

        val prev = lastValidLocation
        var observedSpeed = if (location.hasSpeed()) location.speed.toDouble() else -1.0

        if (prev != null) {
            val dtSec = ((location.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9)
                .takeIf { it > 0.05 } ?: ((nowElapsed - lastValidLocationElapsedMs) / 1000.0)
            val chordMeters = prev.distanceTo(location).toDouble()

            if (dtSec > 0.1) {
                val derivedSpeed = chordMeters / dtSec
                if (derivedSpeed > MAX_PLAUSIBLE_VEHICLE_SPEED_MPS) return
                if (observedSpeed < 0.0) {
                    observedSpeed = derivedSpeed
                }
            }

            lastLocationElapsedMs = nowElapsed

            val minMoveFloor = max(1.2, (location.accuracy * 0.20).toDouble())
            if (chordMeters >= minMoveFloor) {
                val curState = gaitClassifier.currentState()
                val candState = gaitClassifier.candidateState()
                val inVeh = gaitClassifier.isInOrCoolingDownFromVehicle()
                val activeCadence = signalProcessor.getCadenceSpm(nowElapsed) >= GaitClassifier.MIN_WALKING_CADENCE_SPM
                val onFoot = !inVeh && curState != MotionState.VEHICLE && candState != MotionState.VEHICLE &&
                    (curState.countsAsActiveMovement || activeCadence)

                // 400m oval track curve arc-to-chord compensation (strictly on crisp fixes <= 12m accuracy)
                val segBearing = prev.bearingTo(location)
                val prevBearing = lastValidBearingDeg
                val crispAccuracy = !location.hasAccuracy() || location.accuracy <= 12f
                val curveAdjustedMeters = if (onFoot && crispAccuracy && prevBearing != null && chordMeters in 2.5..18.0 &&
                    (curState.isJogOrRun() || candState.isJogOrRun())
                ) {
                    var turnDeg = abs(segBearing - prevBearing)
                    if (turnDeg > 180f) turnDeg = 360f - turnDeg
                    if (turnDeg in 12f..50f) {
                        val halfRad = Math.toRadians((turnDeg / 2f).toDouble())
                        val arcFactor = (halfRad / sin(halfRad)).coerceIn(1.0, 1.042)
                        chordMeters * arcFactor
                    } else {
                        chordMeters
                    }
                } else {
                    chordMeters
                }
                lastValidBearingDeg = segBearing

                if (onFoot) {
                    lastGpsDistanceContributionMs = nowElapsed
                    // Subtract any step-fallback meters already credited during a >4.0s GPS dropout window
                    val dedupedMeters = (curveAdjustedMeters - fallbackMetersCreditedSinceLastGps).coerceAtLeast(0.0)
                    fallbackMetersCreditedSinceLastGps = (fallbackMetersCreditedSinceLastGps - curveAdjustedMeters).coerceAtLeast(0.0)

                    if (dedupedMeters > 0.0) {
                        if (repository.allDayTrackingEnabled) {
                            val effectiveState = if (curState.countsAsActiveMovement) curState else {
                                if (candState.countsAsActiveMovement) candState else MotionState.WALKING
                            }
                            addAllDayDistanceDelta(dedupedMeters, effectiveState)
                            val hb = ensureHourlyBucketLoaded()
                            currentHourlyBucket = hb.copy(
                                gpsMeters = hb.gpsMeters + dedupedMeters,
                                walkMeters = hb.walkMeters + if (effectiveState.isJogOrRun()) 0.0 else dedupedMeters,
                                jogRunMeters = hb.jogRunMeters + if (effectiveState.isJogOrRun()) dedupedMeters else 0.0
                            )
                            hourlyDirty = true
                        }
                        when (ptTrialPhase) {
                            PtTrialPhase.RUNNING_TRIAL -> {
                                addPtTrialDistanceDelta(
                                    dedupedMeters * sessionTrackScale,
                                    curState,
                                    allowDuringWarmup = activeCadence
                                )
                            }
                            PtTrialPhase.ARMED_WAITING_FOR_JOG_RUN -> {
                                if (curState.isJogOrRun() || candState.isJogOrRun()) {
                                    ptLaunchBufferMeters = (ptLaunchBufferMeters + dedupedMeters).coerceAtMost(14.0)
                                }
                            }
                            PtTrialPhase.IDLE -> Unit
                        }
                        if (intervalPhase == IntervalPhase.RUNNING_REP) {
                            addIntervalDistanceDelta(dedupedMeters, curState)
                        }
                    }
                }
                lastValidLocation = location
                lastValidLocationElapsedMs = nowElapsed
            }
        } else {
            lastValidLocation = location
            lastValidLocationElapsedMs = nowElapsed
            lastLocationElapsedMs = nowElapsed
            fallbackMetersCreditedSinceLastGps = 0.0
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
        val dtMs = (nowMs - lastTickElapsedMs).coerceIn(0L, 2500L)
        val dtSec = dtMs / 1000.0
        lastTickElapsedMs = nowMs

        ensureDailyLoadedForToday()

        val gpsFresh = locationRegistered && lastLocationElapsedMs > 0L && (nowMs - lastLocationElapsedMs) <= GPS_FRESHNESS_MS
        if (!gpsFresh) {
            smoothedGpsSpeedMps *= 0.88
            if (smoothedGpsSpeedMps < 0.15) smoothedGpsSpeedMps = 0.0
        }

        val isManualWorkout = isHighPrecisionSessionActive()
        signalProcessor.manualWorkoutActive = isManualWorkout

        val cadenceSpm = signalProcessor.getCadenceSpm(nowMs)
        val accelRms = signalProcessor.getDynamicAccelRms(nowMs)
        val hfVibrationRms = signalProcessor.getHighFreqVibrationRms(nowMs)
        val minRawNorm = signalProcessor.getMinRawNorm(nowMs)
        val peakToTroughNorm = signalProcessor.getPeakToTroughNorm(nowMs)
        val p20RawNorm = signalProcessor.getP20RawNorm(nowMs)
        val gpsReliableForGait = gpsFresh &&
            (latestGpsAccuracyM == null || latestGpsAccuracyM!! <= 22f || smoothedGpsSpeedMps >= 0.70)
        val gpsForClassifier = if (gpsReliableForGait) smoothedGpsSpeedMps else null

        val wasInVehicleLatchBefore = wasInVehicleLatchLastTick
        val state = gaitClassifier.update(
            nowMs = nowMs,
            gpsSpeedMps = gpsForClassifier,
            cadenceSpm = cadenceSpm,
            dynamicAccelRms = accelRms,
            minRawNorm = minRawNorm,
            peakToTroughNorm = peakToTroughNorm,
            p20RawNorm = p20RawNorm,
            hfVibrationRms = hfVibrationRms,
            isManualWorkoutActive = isManualWorkout
        )
        val candState = gaitClassifier.candidateState()
        val inVehicleOrCooldown = gaitClassifier.isInOrCoolingDownFromVehicle()
        if (wasInVehicleOrCooldownLastTick && !inVehicleOrCooldown) {
            // Re-anchor TYPE_STEP_COUNTER on dismount so batched riding vibration steps are never injected!
            lastStepCounterHwValue = null
            stepsAtLastStepCounter = signalProcessor.totalSteps
        }
        wasInVehicleOrCooldownLastTick = inVehicleOrCooldown
        signalProcessor.suppressStepsForVehicle = inVehicleOrCooldown
        wasInVehicleLatchLastTick = gaitClassifier.isVehicleLatched()

        // 12-second Pre-Vehicle Rollback: if the rider just mounted a bike and accelerated into VEHICLE,
        // roll back the few seconds of initial rollout before GPS crossed vehicle threshold so ZERO fake
        // steps, distance, walk/jog/run seconds, or peak speed/cadence spikes pollute the day!
        if (!wasInVehicleLatchBefore && gaitClassifier.isVehicleLatched() &&
            ptTrialPhase == PtTrialPhase.IDLE && intervalPhase == IntervalPhase.IDLE
        ) {
            flushOpenBoutIfQualified()
            resetOpenBout()
            val rollbackSnap = preVehicleCheckpoints.firstOrNull {
                it.dateIso == dailyDateIso && (nowMs - it.elapsedMs) in 3_500L..14_500L
            } ?: preVehicleCheckpoints.lastOrNull { it.dateIso == dailyDateIso }
            if (rollbackSnap != null) {
                dailyWalkSec = rollbackSnap.walkSec
                dailyJogSec = rollbackSnap.jogSec
                dailyRunSec = rollbackSnap.runSec
                dailyWalkMeters = rollbackSnap.walkMeters
                dailyJogMeters = rollbackSnap.jogMeters
                dailyRunMeters = rollbackSnap.runMeters
                dailyTotalSteps = rollbackSnap.totalSteps
                dailyMaxSpeedMps = rollbackSnap.maxSpeedMps
                dailyMaxCadenceSpm = rollbackSnap.maxCadenceSpm
                rollbackSnap.hourlyBucket?.let { prevHb ->
                    val curHb = currentHourlyBucket
                    if (curHb != null && curHb.bucketKey == prevHb.bucketKey) {
                        currentHourlyBucket = prevHb.copy(vehicleSec = curHb.vehicleSec)
                        hourlyDirty = true
                    }
                }
                dailyDirty = true
            }
            preVehicleCheckpoints.clear()
        }

        // Smart GPS & Battery Duty-Cycling:
        //  - CRITICAL: NEVER shut off GPS while riding a bike/vehicle or waiting for dismount verification!
        //  - Spin up GPS quickly (5s walk or active acceleration) so bike departures lock GPS immediately.
        //  - Drop into Low-Power Standby (0% GPS drain, no WakeLock) after 45s stationary indoors!
        if (inVehicleOrCooldown) {
            lastMovementDetectedElapsedMs = nowMs
            continuousFootMovementMs = 0L
            if (isLowPowerStandby) {
                wakeFromLowPowerStandby()
            }
            registerLocationIfPermitted()
        } else if (state.countsAsActiveMovement || candState.countsAsActiveMovement || cadenceSpm >= 50.0 || accelRms >= 2.1) {
            lastMovementDetectedElapsedMs = nowMs
            continuousFootMovementMs = (continuousFootMovementMs + dtMs).coerceAtMost(60_000L)
            if (isHighPrecisionSessionActive() || state.isJogOrRun() || candState.isJogOrRun() ||
                continuousFootMovementMs >= 5_000L || accelRms >= 2.2
            ) {
                registerLocationIfPermitted()
            }
        } else {
            continuousFootMovementMs = 0L
            if (!isHighPrecisionSessionActive() && !uiForegroundVisible) {
                val idleMs = nowMs - lastMovementDetectedElapsedMs
                if (idleMs >= 20_000L && locationRegistered) {
                    pauseLocationUpdatesOnly()
                }
                if (idleMs >= STANDBY_IDLE_THRESHOLD_MS) {
                    enterLowPowerStandby()
                }
            }
        }

        syncPocketForegroundAndWakeLock()

        if (state != lastAnnouncedState) {
            val previousState = lastAnnouncedState
            lastAnnouncedState = state
            val transitionEvent = SensorTransitionEvent(
                epochMs = System.currentTimeMillis(),
                fromState = previousState.name,
                toState = state.name,
                gpsSpeedMps = gpsForClassifier,
                gpsAccMeters = latestGpsAccuracyM?.toDouble(),
                cadenceSpm = cadenceSpm,
                accelRms = accelRms,
                minRawNorm = minRawNorm,
                peakToTroughNorm = peakToTroughNorm,
                p20RawNorm = p20RawNorm,
                isLowPower = isLowPowerStandby,
                ptPhase = ptTrialPhase.name
            )
            if (serviceAlive) {
                serviceScope.launch { runCatching { repository.recordSensorTransition(transitionEvent) } }
            }
            if (dailyDirty || hourlyDirty) persistDailySummaryNow(async = true)
            onMotionStateChanged(previousState, state, nowMs)
        }

        val gpsActivelyMeasuringDistance =
            locationRegistered && lastGpsDistanceContributionMs > 0L && (nowMs - lastGpsDistanceContributionMs) <= 4000L
        val stepSpeedMps = gaitClassifier.estimateSpeedFromStepsMps(cadenceSpm, accelRms)
        val effectiveSpeedMps = when {
            !state.countsAsActiveMovement || inVehicleOrCooldown -> 0.0
            gpsActivelyMeasuringDistance && smoothedGpsSpeedMps > 0.55 -> smoothedGpsSpeedMps
            gpsFresh && smoothedGpsSpeedMps > stepSpeedMps -> smoothedGpsSpeedMps
            else -> stepSpeedMps
        }

        val currentSteps = signalProcessor.totalSteps
        val newSteps = (currentSteps - lastSignalStepsProcessed).coerceAtLeast(0)
        lastSignalStepsProcessed = currentSteps
        val curHwSteps = signalProcessor.hardwareStepsCount
        val curSwSteps = signalProcessor.fallbackSoftwareStepsCount
        val deltaHwSteps = (curHwSteps - lastHwStepsProcessed).coerceAtLeast(0)
        val deltaSwSteps = (curSwSteps - lastSwStepsProcessed).coerceAtLeast(0)
        lastHwStepsProcessed = curHwSteps
        lastSwStepsProcessed = curSwSteps

        val onFootNow = !inVehicleOrCooldown && state != MotionState.VEHICLE && candState != MotionState.VEHICLE &&
            (state.countsAsActiveMovement || cadenceSpm >= GaitClassifier.MIN_WALKING_CADENCE_SPM)
        val stepFallbackDeltaM = if (!gpsActivelyMeasuringDistance && repository.indoorStepFallbackEnabled && newSteps > 0 && onFootNow) {
            val dm = newSteps * gaitClassifier.estimateStrideLengthMeters(cadenceSpm.coerceAtLeast(85.0), accelRms)
            if (locationRegistered && lastValidLocation != null) {
                fallbackMetersCreditedSinceLastGps = (fallbackMetersCreditedSinceLastGps + dm).coerceAtMost(120.0)
            }
            dm
        } else {
            0.0
        }

        // Update Intraday Hourly Fine-Tuning Bucket
        run {
            val hb = ensureHourlyBucketLoaded()
            val countStepNow = newSteps > 0 && !inVehicleOrCooldown && state != MotionState.VEHICLE && candState != MotionState.VEHICLE
            val effStateForDist = if (state.countsAsActiveMovement) state else {
                if (candState.countsAsActiveMovement) candState else MotionState.WALKING
            }
            currentHourlyBucket = hb.copy(
                hwSteps = hb.hwSteps + if (countStepNow) deltaHwSteps else 0,
                fallbackSteps = hb.fallbackSteps + if (countStepNow) deltaSwSteps else 0,
                walkSec = hb.walkSec + if (state == MotionState.WALKING) dtSec else 0.0,
                jogSec = hb.jogSec + if (state == MotionState.JOGGING) dtSec else 0.0,
                runSec = hb.runSec + if (state == MotionState.RUNNING || state == MotionState.TARGET_PACE) dtSec else 0.0,
                vehicleSec = hb.vehicleSec + if (state == MotionState.VEHICLE) dtSec else 0.0,
                lowPowerSec = hb.lowPowerSec + if (isLowPowerStandby) dtSec else 0.0,
                walkMeters = hb.walkMeters + if (stepFallbackDeltaM > 0.0 && !effStateForDist.isJogOrRun()) stepFallbackDeltaM else 0.0,
                jogRunMeters = hb.jogRunMeters + if (stepFallbackDeltaM > 0.0 && effStateForDist.isJogOrRun()) stepFallbackDeltaM else 0.0,
                strideMeters = hb.strideMeters + stepFallbackDeltaM,
                maxSpeedMps = max(hb.maxSpeedMps, effectiveSpeedMps),
                maxCadenceSpm = if (!inVehicleOrCooldown && state.countsAsActiveMovement) {
                    val cappedCad = if (state.isJogOrRun() || effectiveSpeedMps >= 2.15) cadenceSpm else cadenceSpm.coerceAtMost(165.0)
                    max(hb.maxCadenceSpm, cappedCad)
                } else hb.maxCadenceSpm,
                maxAccelRms = max(hb.maxAccelRms, accelRms)
            )
            if (countStepNow || state != MotionState.STANDING || stepFallbackDeltaM > 0.0) {
                hourlyDirty = true
            }
        }

        // =========================================================================
        // 1. ALWAYS-ON 24/7 ALL-DAY OPEN AUTO TRACKING (Auto-Pauses When Standing)
        // =========================================================================
        if (repository.allDayTrackingEnabled) {
            if (newSteps > 0 && !inVehicleOrCooldown && state != MotionState.VEHICLE && candState != MotionState.VEHICLE) {
                dailyTotalSteps += newSteps
                dailyDirty = true
            }
            if (stepFallbackDeltaM > 0.0) {
                val effectiveState = if (state.countsAsActiveMovement) state else {
                    if (candState.countsAsActiveMovement) candState else MotionState.WALKING
                }
                addAllDayDistanceDelta(stepFallbackDeltaM, effectiveState)
            }
            if (state.countsAsActiveMovement && !inVehicleOrCooldown) {
                when (state) {
                    MotionState.WALKING -> dailyWalkSec += dtSec
                    MotionState.JOGGING -> dailyJogSec += dtSec
                    MotionState.RUNNING, MotionState.TARGET_PACE -> dailyRunSec += dtSec
                    MotionState.STANDING, MotionState.VEHICLE -> Unit
                }
                if (effectiveSpeedMps in 0.55..GaitClassifier.MAX_HUMAN_RUNNING_SPEED_MPS &&
                    effectiveSpeedMps > dailyMaxSpeedMps
                ) {
                    dailyMaxSpeedMps = effectiveSpeedMps
                }
                if (cadenceSpm in 68.0..210.0) {
                    val cappedDailyCad = if (state.isJogOrRun() || effectiveSpeedMps >= 2.15) {
                        cadenceSpm
                    } else {
                        cadenceSpm.coerceAtMost(165.0)
                    }
                    if (cappedDailyCad > dailyMaxCadenceSpm) {
                        dailyMaxCadenceSpm = cappedDailyCad
                    }
                }
                dailyDirty = true
            }

            // Sample pre-vehicle checkpoint every 2s while cleanly on foot / standing
            if (!inVehicleOrCooldown && state != MotionState.VEHICLE && candState != MotionState.VEHICLE &&
                (nowMs - lastCheckpointSampleMs) >= 2_000L
            ) {
                lastCheckpointSampleMs = nowMs
                preVehicleCheckpoints.addLast(
                    PreVehicleCheckpoint(
                        elapsedMs = nowMs,
                        dateIso = dailyDateIso,
                        walkSec = dailyWalkSec,
                        jogSec = dailyJogSec,
                        runSec = dailyRunSec,
                        walkMeters = dailyWalkMeters,
                        jogMeters = dailyJogMeters,
                        runMeters = dailyRunMeters,
                        totalSteps = dailyTotalSteps,
                        maxSpeedMps = dailyMaxSpeedMps,
                        maxCadenceSpm = dailyMaxCadenceSpm,
                        hourlyBucket = currentHourlyBucket
                    )
                )
                while (preVehicleCheckpoints.size > 10) {
                    preVehicleCheckpoints.removeFirst()
                }
            }

            if (ptTrialPhase == PtTrialPhase.IDLE && intervalPhase == IntervalPhase.IDLE) {
                updateAutomaticOpenRunBout(state, dtMs, dtSec, effectiveSpeedMps, cadenceSpm)
            }

            if ((dailyDirty || hourlyDirty) && (nowMs - lastDailyPersistElapsedMs) >= 5000L) {
                persistDailySummaryNow(async = true)
            }
        }

        // =========================================================================
        // 2. 1.6 KM PT TRIAL STATE MACHINE (CONTINUOUS MONOTONIC TSLPRB RFID CLOCK!)
        // =========================================================================
        when (ptTrialPhase) {
            PtTrialPhase.ARMED_WAITING_FOR_JOG_RUN -> {
                if (stepFallbackDeltaM > 0.0 && (state.isJogOrRun() || candState.isJogOrRun())) {
                    ptLaunchBufferMeters = (ptLaunchBufferMeters + stepFallbackDeltaM).coerceAtMost(14.0)
                }
                if (state.isJogOrRun()) {
                    ptLaunchBufferSec = (ptLaunchBufferSec + dtSec).coerceAtMost(2.6)
                    ptJogRunConfirmMs += dtMs
                    if (ptJogRunConfirmMs >= 400L) {
                        beginArmedPtTrialNow(state)
                        return
                    }
                } else if (candState.isJogOrRun()) {
                    ptLaunchBufferSec = (ptLaunchBufferSec + dtSec).coerceAtMost(2.6)
                    ptJogRunConfirmMs = 0L
                } else {
                    ptJogRunConfirmMs = 0L
                    ptLaunchBufferSec = 0.0
                    ptLaunchBufferMeters = 0.0
                }
            }
            PtTrialPhase.RUNNING_TRIAL -> {
                // Official TSLPRB rule: once you cross the start line, the race clock NEVER pauses!
                // Anchor directly to SystemClock.elapsedRealtime() so even >2.5s main-looper stalls never lose race time.
                val prevActiveSec = activeDurationSec
                val monotonicSec = if (trialStartElapsedMs > 0L) {
                    ((nowMs - trialStartElapsedMs).coerceAtLeast(0L) / 1000.0) + trialInitialCreditSec
                } else {
                    prevActiveSec + dtSec
                }
                activeDurationSec = max(prevActiveSec, monotonicSec)
                val trialStepDtSec = (activeDurationSec - prevActiveSec).coerceAtLeast(0.0)
                lastTrialStepDtSec = trialStepDtSec

                if (!state.countsAsActiveMovement) {
                    pausedDurationSec += trialStepDtSec // Tracks stopped/standing penalty seconds diagnostically
                } else {
                    if (effectiveSpeedMps > maxSpeedMps) maxSpeedMps = effectiveSpeedMps
                    if (cadenceSpm > 30.0) {
                        cadenceSamplesSum += cadenceSpm
                        cadenceSamplesCount++
                    }
                    when (state) {
                        MotionState.RUNNING, MotionState.TARGET_PACE -> runningSec += trialStepDtSec
                        MotionState.JOGGING -> joggingSec += trialStepDtSec
                        MotionState.WALKING -> walkingSec += trialStepDtSec
                        MotionState.STANDING, MotionState.VEHICLE -> Unit
                    }
                }
                if (stepFallbackDeltaM > 0.0) {
                    addPtTrialDistanceDelta(stepFallbackDeltaM * sessionTrackScale, state, allowDuringWarmup = true)
                }
            }
            PtTrialPhase.IDLE -> Unit
        }

        // =========================================================================
        // 3. GUIDED 400M INTERVAL COACH STATE MACHINE
        // =========================================================================
        when (intervalPhase) {
            IntervalPhase.RUNNING_REP -> {
                intervalRepSec += dtSec
                if (effectiveSpeedMps > intervalMaxSpeedMps) intervalMaxSpeedMps = effectiveSpeedMps
                if (cadenceSpm > 120.0) {
                    intervalCadenceSum += cadenceSpm
                    intervalCadenceCount++
                }
                if (stepFallbackDeltaM > 0.0) {
                    addIntervalDistanceDelta(stepFallbackDeltaM, state)
                }
            }
            IntervalPhase.REST_COUNTDOWN -> {
                intervalRestRemainingSec = (intervalRestRemainingSec - dtSec).coerceAtLeast(0.0)
                if (!intervalAnnounced10sReady && intervalRestRemainingSec in 0.1..10.5) {
                    intervalAnnounced10sReady = true
                    vibratePulse(120L)
                    speakCoach("10 seconds. Step to the line for rep ${intervalCurrentRep + 1}!")
                }
                if (intervalRestRemainingSec <= 0.0) {
                    skipIntervalRestNow()
                }
            }
            IntervalPhase.IDLE, IntervalPhase.COMPLETED -> Unit
        }

        if (isHighPrecisionSessionActive() && (nowMs - lastWorkoutCheckpointElapsedMs) >= 5_000L) {
            lastWorkoutCheckpointElapsedMs = nowMs
            persistActiveWorkoutCheckpoint()
        }

        if (isInForeground && nowMs - lastNotificationUpdateMs >= 1000L) {
            lastNotificationUpdateMs = nowMs
            updateForegroundNotificationIfChanged(state, effectiveSpeedMps)
        }

        if (repository.remoteDiagnosticsOptIn && nowMs - lastAutoTelemetryCheckElapsedMs >= 15L * 60_000L) {
            lastAutoTelemetryCheckElapsedMs = nowMs
            triggerBackgroundAutoTelemetryIfDue(reason = "periodic_background", checkPendingCommands = true)
        }

        val liveStrideM = gaitClassifier.computeLiveStrideLengthMeters(
            gpsSpeedMps = gpsForClassifier,
            cadenceSpm = cadenceSpm,
            dynamicAccelRms = accelRms,
            motionState = state
        )

        broadcastTelemetry(effectiveSpeedMps, gpsFresh, cadenceSpm, accelRms, liveStrideM)
    }

    private fun addAllDayDistanceDelta(deltaM: Double, state: MotionState) {
        if (deltaM <= 0.0 || state == MotionState.VEHICLE) return
        when (state) {
            MotionState.RUNNING, MotionState.TARGET_PACE -> dailyRunMeters += deltaM
            MotionState.JOGGING -> dailyJogMeters += deltaM
            MotionState.WALKING, MotionState.STANDING -> dailyWalkMeters += deltaM
            MotionState.VEHICLE -> Unit
        }
        if (ptTrialPhase == PtTrialPhase.IDLE && intervalPhase == IntervalPhase.IDLE && state.isJogOrRun()) {
            val prevBoutM = openBoutJogRunMeters
            openBoutJogRunMeters += deltaM
            if (prevBoutM < 1600.0 && openBoutJogRunMeters >= 1600.0 && openBoutFirst1600Sec == null) {
                // Include any mid-bout walking/standing breaks (<75s) so broken runs never masquerade as nonstop 1.6K trials!
                val totalBoutElapsedSec = if (openBoutStartedElapsedMs > 0L) {
                    ((SystemClock.elapsedRealtime() - openBoutStartedElapsedMs).coerceAtLeast(0L) / 1000.0)
                        .coerceAtLeast(openBoutActiveSec + openBoutPausedSec + openBoutWalkSec)
                } else {
                    openBoutActiveSec + openBoutPausedSec + openBoutWalkSec
                }
                openBoutFirst1600Sec = totalBoutElapsedSec
            }
        }
        dailyDirty = true
    }

    private fun updateAutomaticOpenRunBout(
        state: MotionState,
        dtMs: Long,
        dtSec: Double,
        effectiveSpeedMps: Double,
        cadenceSpm: Double
    ) {
        if (state.isJogOrRun()) {
            if (openBoutStartedWallMs == 0L) {
                openBoutStartedWallMs = System.currentTimeMillis()
                openBoutStartedElapsedMs = SystemClock.elapsedRealtime()
            }
            openBoutIdleMs = 0L
            openBoutActiveSec += dtSec
            if (state == MotionState.JOGGING) openBoutJogSec += dtSec else openBoutRunSec += dtSec
            if (effectiveSpeedMps > openBoutMaxSpeed) openBoutMaxSpeed = effectiveSpeedMps
            if (cadenceSpm > 60.0) {
                openBoutCadenceSum += cadenceSpm
                openBoutCadenceCount++
            }
        } else if (openBoutStartedWallMs > 0L) {
            openBoutIdleMs += dtMs
            if (state == MotionState.WALKING) {
                openBoutWalkSec += dtSec
            } else {
                openBoutPausedSec += dtSec
            }
            if (openBoutIdleMs >= 75_000L) {
                flushOpenBoutIfQualified()
                resetOpenBout()
            }
        }
    }

    private fun flushOpenBoutIfQualified() {
        if (openBoutStartedWallMs > 0L && openBoutJogRunMeters >= 300.0 && openBoutActiveSec >= 60.0) {
            val avgCad = if (openBoutCadenceCount > 0) openBoutCadenceSum / openBoutCadenceCount else 142.0
            val avgSpeedMps = openBoutJogRunMeters / openBoutActiveSec
            val isGenuineJogOrRunBout = openBoutRunSec >= 5.0 || avgSpeedMps >= 1.85 || avgCad >= 124.0
            if (isGenuineJogOrRunBout) {
                val totalElapsedBoutSec = openBoutActiveSec + openBoutPausedSec + openBoutWalkSec
                val effectiveFirst1600 = openBoutFirst1600Sec?.takeIf {
                    openBoutPausedSec < 15.0 && openBoutWalkSec < 20.0
                }
                val equiv = TsPoliceBenchmark.computeEquiv1600mSec(
                    distanceMeters = openBoutJogRunMeters,
                    activeDurationSec = max(openBoutActiveSec, totalElapsedBoutSec * 0.85),
                    first1600mSplitSec = effectiveFirst1600
                )
                val labelKind = if (openBoutRunSec >= openBoutActiveSec * 0.25 || avgSpeedMps >= 2.60) "Run" else "Jog"
                val boutDateIso = Instant.ofEpochMilli(openBoutStartedWallMs)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .toString()
                val savedId = repository.insertSession(
                    RunSessionRecord(
                        startedAtEpochMs = openBoutStartedWallMs,
                        dateIso = boutDateIso,
                        mode = RunMode.OPEN_PRACTICE,
                        distanceMeters = openBoutJogRunMeters,
                        activeDurationSec = openBoutActiveSec,
                        pausedDurationSec = openBoutPausedSec,
                        first1600mSplitSec = openBoutFirst1600Sec,
                        equiv1600mSec = equiv,
                        avgCadenceSpm = avgCad,
                        maxSpeedMps = openBoutMaxSpeed,
                        runningSec = openBoutRunSec,
                        joggingSec = openBoutJogSec,
                        walkingSec = openBoutWalkSec,
                        lapSplits = emptyList(),
                        notes = "All-Day Auto-Detected $labelKind (${openBoutJogRunMeters.toInt()}m)",
                        isDemo = false
                    )
                )
                lastCompletedSessionId = savedId
                persistDailySummaryNow(async = true)
                triggerBackgroundAutoTelemetryIfDue(reason = "open_run_finished", force = true)
            }
        }
        resetOpenBout()
    }

    fun syncRemoteDiagnosticsSetting() {
        if (!repository.remoteDiagnosticsOptIn || isLowPowerStandby || !serviceAlive) {
            remotePullListenerThread?.interrupt()
            remotePullListenerThread = null
        } else {
            startRemotePullCommandListener()
        }
    }

    private fun startRemotePullCommandListener() {
        if (!repository.remoteDiagnosticsOptIn || isLowPowerStandby || !serviceAlive) return
        if (remotePullListenerThread?.isAlive == true) return
        remotePullListenerThread = Thread({
            tuningTelemetryManager.listenForRemotePullCommandsBlocking(
                shouldContinue = { serviceAlive && repository.remoteDiagnosticsOptIn && !isLowPowerStandby },
                onPullCommand = { reqId ->
                    mainHandler.post {
                        if (!serviceAlive || !repository.remoteDiagnosticsOptIn) return@post
                        persistDailySummaryNow(async = true)
                        val liveSnap = currentDailySnapshot()
                        serviceScope.launch {
                            runCatching {
                                tuningTelemetryManager.pullBuildAndUploadTuningBundle(
                                    liveTodayOverride = liveSnap,
                                    triggerReason = "remote_pull_live",
                                    requestId = reqId,
                                    allowCloudUpload = true
                                )
                            }
                        }
                    }
                }
            )
        }, "TSPolice-RemotePullListener").apply {
            isDaemon = true
            start()
        }
    }

    private fun triggerBackgroundAutoTelemetryIfDue(
        reason: String,
        force: Boolean = false,
        checkPendingCommands: Boolean = false
    ) {
        if (!::tuningTelemetryManager.isInitialized || !repository.remoteDiagnosticsOptIn) return
        persistDailySummaryNow(async = true)
        val liveSnap = currentDailySnapshot()
        serviceScope.launch {
            runCatching {
                val handledCmd = if (checkPendingCommands) {
                    tuningTelemetryManager.checkAndHandlePendingPullCommands { liveSnap }
                } else {
                    false
                }
                if (!handledCmd) {
                    tuningTelemetryManager.maybeAutoSyncIfDue(
                        liveTodayOverride = liveSnap,
                        reason = reason,
                        force = force
                    )
                }
            }
        }
    }

    private fun resetOpenBout() {
        openBoutStartedWallMs = 0L
        openBoutStartedElapsedMs = 0L
        openBoutJogRunMeters = 0.0
        openBoutActiveSec = 0.0
        openBoutPausedSec = 0.0
        openBoutRunSec = 0.0
        openBoutJogSec = 0.0
        openBoutWalkSec = 0.0
        openBoutMaxSpeed = 0.0
        openBoutCadenceSum = 0.0
        openBoutCadenceCount = 0
        openBoutFirst1600Sec = null
        openBoutIdleMs = 0L
    }

    private fun addPtTrialDistanceDelta(
        deltaM: Double,
        state: MotionState,
        allowDuringWarmup: Boolean = false,
        isManualSnap: Boolean = false
    ) {
        if (deltaM <= 0.0 || ptTrialPhase != PtTrialPhase.RUNNING_TRIAL) return
        if (state == MotionState.VEHICLE) return
        if (!state.countsAsActiveMovement && !allowDuringWarmup) return

        val prevDist = distanceMeters
        distanceMeters += deltaM
        if (!isManualSnap) {
            when (state) {
                MotionState.RUNNING, MotionState.TARGET_PACE -> runningMeters += deltaM
                MotionState.JOGGING -> joggingMeters += deltaM
                MotionState.WALKING, MotionState.STANDING -> walkingMeters += deltaM
                MotionState.VEHICLE -> Unit
            }
        }

        val tickStartSec = (activeDurationSec - lastTrialStepDtSec).coerceAtLeast(prevLapCumulativeSec)
        var exactFinishSec: Double? = null

        // Record 400m splits with linear boundary time interpolation
        while (distanceMeters >= nextLapBoundaryM) {
            val lapNum = lapSplits.size + 1
            val boundarySec = if (isManualSnap || deltaM <= 0.05) {
                activeDurationSec
            } else {
                val frac = ((nextLapBoundaryM - prevDist) / deltaM).coerceIn(0.0, 1.0)
                (tickStartSec + (activeDurationSec - tickStartSec) * frac)
                    .coerceIn(prevLapCumulativeSec + 0.1, activeDurationSec)
            }
            val lapDur = (boundarySec - prevLapCumulativeSec).coerceAtLeast(0.1)
            prevLapCumulativeSec = boundarySec
            lapSplits.add(
                LapSplit(
                    lapNumber = lapNum,
                    distanceMeters = nextLapBoundaryM,
                    lapDurationSec = lapDur,
                    cumulativeSec = boundarySec,
                    dominantState = state
                )
            )
            if (nextLapBoundaryM == 1600.0 && first1600mSplitSec == null) {
                first1600mSplitSec = boundarySec
                exactFinishSec = boundarySec
            }
            onLapCrossed(lapNum, nextLapBoundaryM, lapDur)
            nextLapBoundaryM += TsPoliceBenchmark.LAP_DISTANCE_M
            persistActiveWorkoutCheckpoint()
        }

        // Final-200m (1,400m) Kick Callout on the hardest stretch of the 1.6 km trial!
        if (!announcedFinal200mKick && distanceMeters >= 1400.0 && distanceMeters < 1585.0) {
            announcedFinal200mKick = true
            vibratePulse(240L)
            val secRemaining = (repository.targetTimeSec - activeDurationSec).toInt()
            val kickMsg = if (secRemaining > 0) {
                "Final 200 meters! $secRemaining seconds left for target. Sprint home!"
            } else {
                "Final 200 meters! Kick hard to the finish line!"
            }
            speakCoach(kickMsg)
        }

        // Auto-complete 1.6 km TS Police PT Trial at 1,600m!
        if (distanceMeters >= TsPoliceBenchmark.TARGET_DISTANCE_M) {
            distanceMeters = TsPoliceBenchmark.TARGET_DISTANCE_M
            if (exactFinishSec != null) {
                activeDurationSec = exactFinishSec
            }
            val finalSec = activeDurationSec
            val targetSec = repository.targetTimeSec
            val merit = TsPoliceBenchmark.evaluateMerit1600m(finalSec, targetSec)
            finishWorkout(forceSave = true)
            val diff = finalSec - targetSec
            val verdict = if (diff <= 0.0) {
                "Qualified by ${(-diff).toInt()} seconds! ${merit.points} merit points!"
            } else {
                val secsOver = TsPoliceBenchmark.secondsAboveCutoff(finalSec, targetSec)
                "$secsOver seconds above target."
            }
            lastFinishSpeechMs = SystemClock.elapsedRealtime()
            speakCoach(
                "1.6 kilometer P T trial complete! Official time ${spokenTime(finalSec)}. $verdict"
            )
        }
    }

    private fun onLapCrossed(lapNum: Int, boundaryM: Double, lapDurSec: Double) {
        vibratePulse(180L)
        if (runMode == RunMode.TIMED_1600M && boundaryM >= 1600.0) return
        val delta = TsPoliceBenchmark.splitDeltaSec(boundaryM, activeDurationSec, repository.targetTimeSec)
        val deltaSpeech = if (delta != null) {
            if (delta <= 0.0) "Ahead by ${(-delta).toInt()} seconds."
            else "Behind by ${delta.toInt()} seconds."
        } else ""
        speakCoach(
            "Lap $lapNum, ${boundaryM.toInt()} meters. Split ${spokenTime(lapDurSec)}. $deltaSpeech"
        )
    }

    private fun onMotionStateChanged(previousState: MotionState, newState: MotionState, nowMs: Long) {
        val mode = repository.pocketAudioMode
        if (!mode.shouldAnnounceTransition(previousState, newState, ptTrialPhase, openBoutStartedWallMs > 0L)) {
            return
        }
        if (!isRecording && (nowMs - lastFinishSpeechMs) in 0L..6000L) return

        // Never spam "Stopped!" in the first 5s of launch or repeatedly within 25s
        if (ptTrialPhase == PtTrialPhase.RUNNING_TRIAL && newState == MotionState.STANDING) {
            if (activeDurationSec < 5.0 || (nowMs - lastStoppedPenaltySpeechElapsedMs) in 0L..25_000L) {
                return
            }
            lastStoppedPenaltySpeechElapsedMs = nowMs
        }

        requestAudioFocusQuietly()
        playStateTone(newState)
        vibratePulse(if (newState == MotionState.RUNNING || newState == MotionState.TARGET_PACE) 110L else 65L)

        val speech = if (ptTrialPhase == PtTrialPhase.RUNNING_TRIAL && newState == MotionState.STANDING) {
            "Stopped! Official race clock still running!"
        } else {
            MotionStateAudioCue.buildTransitionSpeech(
                previousState = previousState,
                newState = newState,
                isRecording = isRecording,
                targetTimeSec = repository.targetTimeSec,
                clockContinuesOnStop = (ptTrialPhase == PtTrialPhase.RUNNING_TRIAL)
            )
        }
        speakStateTransition(speech, nowMs)
    }

    fun previewStateChangeAudio(sampleState: MotionState = MotionState.RUNNING) {
        syncPocketForegroundAndWakeLock()
        requestAudioFocusQuietly()
        playStateTone(sampleState)
        val mode = repository.pocketAudioMode
        val speech = when (mode) {
            PocketAudioMode.SMART_TRACK -> {
                val cue = MotionStateAudioCue.buildTransitionSpeech(
                    previousState = MotionState.JOGGING,
                    newState = sampleState,
                    isRecording = true,
                    targetTimeSec = repository.targetTimeSec
                )
                "Smart track audio active. $cue"
            }
            PocketAudioMode.ALL_STATES -> "All states pocket test audio active."
            PocketAudioMode.MUTED -> "Pocket audio muted."
        }
        speakStateTransition(speech, SystemClock.elapsedRealtime())
    }

    private fun ttsMediaParams(): Bundle = Bundle().apply {
        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
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
            tts?.speak(speech, queueMode, ttsMediaParams(), "ts_state_${SystemClock.elapsedRealtime()}")
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
            MotionState.VEHICLE -> ToneGenerator.TONE_PROP_NACK to 180
            MotionState.STANDING -> ToneGenerator.TONE_PROP_NACK to 160
        }
        runCatching {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 95)
            }
            toneGenerator?.startTone(toneType, durationMs)
        }
    }

    private fun speakCoach(message: String) {
        if (!repository.voiceCoachEnabled || !ttsReady) return
        pendingStateSpeechRunnable?.let { mainHandler.removeCallbacks(it) }
        lastMilestoneSpeechMs = SystemClock.elapsedRealtime()
        requestAudioFocusQuietly()
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, ttsMediaParams(), "ts_coach_$lastMilestoneSpeechMs")
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

    private fun computeDesiredForegroundType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val hasLocationPerm =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasActivityPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var fgType = 0
            if (hasActivityPerm) {
                fgType = fgType or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            }
            val allowInteractiveTypes = !startedFromBootBackground || uiForegroundVisible || isHighPrecisionSessionActive()
            if (hasLocationPerm && allowInteractiveTypes && locationRegistered) {
                fgType = fgType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            if (allowInteractiveTypes && (uiForegroundVisible || isHighPrecisionSessionActive())) {
                fgType = fgType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
            return fgType
        }

        return if (hasLocationPerm) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
    }

    private fun promoteToForeground() {
        val desiredType = computeDesiredForegroundType()
        if (isInForeground && desiredType == lastForegroundServiceType) {
            return
        }
        val (title, subtitle, showMute) = computeForegroundNotificationCopy(gaitClassifier.currentState(), 0.0)
        val notification = buildForegroundNotificationFromCopy(title, subtitle, showMute)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && desiredType == 0) {
            isInForeground = false
            lastForegroundServiceType = 0
            stopSelf()
            return
        }

        val promoted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, desiredType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        }.getOrElse {
            // Fallback on Android 14+: retry with pure HEALTH FGS type ifACTIVITY_RECOGNITION is held
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                (desiredType and ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH) != 0 &&
                desiredType != ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            ) {
                runCatching {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
                    lastForegroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                    isInForeground = true
                    lastNotifiedSignature = "$title|$subtitle|$showMute"
                    return
                }
            }
            false
        }

        if (promoted) {
            isInForeground = true
            lastForegroundServiceType = desiredType
            lastNotifiedSignature = "$title|$subtitle|$showMute"
        } else {
            // Never swallow startForeground failure without stopSelf() — prevents ForegroundServiceDidNotStartInTimeException
            isInForeground = false
            lastForegroundServiceType = 0
            stopSelf()
        }
    }

    private fun demoteFromForeground() {
        if (!isInForeground) return
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        isInForeground = false
        lastForegroundServiceType = 0
        lastNotifiedSignature = ""
    }

    private fun computeForegroundNotificationCopy(
        state: MotionState,
        speedMps: Double
    ): Triple<String, String, Boolean> {
        val daily = currentDailySnapshot()
        val title = when {
            ptTrialPhase == PtTrialPhase.RUNNING_TRIAL ->
                "1.6K PT Trial: ${distanceMeters.toInt()}m / 1600m • ${TsPoliceBenchmark.formatDuration(activeDurationSec)}"
            ptTrialPhase == PtTrialPhase.ARMED_WAITING_FOR_JOG_RUN ->
                "1.6K PT Trial READY • Start Jogging/Running!"
            intervalPhase == IntervalPhase.RUNNING_REP ->
                "400m Intervals: Rep $intervalCurrentRep/$intervalTotalReps • ${intervalRepDistM.toInt()}m"
            intervalPhase == IntervalPhase.REST_COUNTDOWN ->
                "400m Recovery: ${intervalRestRemainingSec.toInt()}s left before Rep ${intervalCurrentRep + 1}"
            else ->
                "TS 1.6K • Run ${TsPoliceBenchmark.formatDuration(daily.jogRunCombinedSec)} • Walk ${TsPoliceBenchmark.formatDuration(daily.walkingSec)}"
        }
        val subtitle = when {
            ptTrialPhase == PtTrialPhase.RUNNING_TRIAL ->
                "Pace ${TsPoliceBenchmark.formatPacePerKm(speedMps)} • Official Unpaused Clock • ${state.label}"
            ptTrialPhase == PtTrialPhase.ARMED_WAITING_FOR_JOG_RUN ->
                "Walking ignored • Stopwatch launches automatically on Jog/Run"
            intervalPhase != IntervalPhase.IDLE ->
                "Pace ${TsPoliceBenchmark.formatPacePerKm(speedMps)} • Target ${TsPoliceBenchmark.formatDuration(intervalTargetRepSec)}/400m"
            isLowPowerStandby ->
                "${String.format(Locale.US, "%.2f km", daily.totalDistanceMeters / 1000.0)} • ${daily.totalSteps} steps • Battery Saver Standby"
            else ->
                "${String.format(Locale.US, "%.2f km", daily.totalDistanceMeters / 1000.0)} • ${daily.totalSteps} steps • ${state.label}"
        }
        val showMute = !isRecording && repository.pocketAudioMode != PocketAudioMode.MUTED
        return Triple(title, subtitle, showMute)
    }

    private fun updateForegroundNotificationIfChanged(state: MotionState, speedMps: Double) {
        val (title, subtitle, showMute) = computeForegroundNotificationCopy(state, speedMps)
        val sig = "$title|$subtitle|$showMute"
        if (sig == lastNotifiedSignature) return
        lastNotifiedSignature = sig
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildForegroundNotificationFromCopy(title, subtitle, showMute))
    }

    private fun buildForegroundNotificationFromCopy(
        title: String,
        subtitle: String,
        showMute: Boolean
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)

        if (showMute) {
            val muteIntent = Intent(this, RunTrackingService::class.java).apply {
                action = ACTION_MUTE_POCKET_AUDIO
            }
            val mutePending = PendingIntent.getService(
                this,
                1601,
                muteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_lock_silent_mode,
                "Mute Audio",
                mutePending
            )
        }

        return builder.build()
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
        accelRms: Double = 0.0,
        liveStrideLengthMeters: Double = 0.0
    ): LiveTelemetry {
        val daily = currentDailySnapshot()
        return LiveTelemetry(
            isRecording = ptTrialPhase == PtTrialPhase.RUNNING_TRIAL || intervalPhase != IntervalPhase.IDLE,
            isAutoStartArmed = ptTrialPhase == PtTrialPhase.ARMED_WAITING_FOR_JOG_RUN,
            ptTrialPhase = ptTrialPhase,
            mode = runMode,
            motionState = gaitClassifier.currentState(),
            distanceMeters = distanceMeters,
            activeDurationSec = activeDurationSec,
            pausedDurationSec = pausedDurationSec,
            currentSpeedMps = effectiveSpeedMps,
            cadenceSpm = cadenceSpm,
            liveStrideLengthMeters = liveStrideLengthMeters,
            dynamicAccelRms = accelRms,
            totalSteps = daily.totalSteps,
            gpsAccuracyMeters = latestGpsAccuracyM,
            isGpsLocked = gpsFresh,
            hasHardwareStepSensor = signalProcessor.isReceivingHardwareSteps(SystemClock.elapsedRealtime()),
            runningSec = runningSec,
            joggingSec = joggingSec,
            walkingSec = walkingSec,
            runningMeters = runningMeters,
            joggingMeters = joggingMeters,
            walkingMeters = walkingMeters,
            first1600mSplitSec = first1600mSplitSec,
            lapSplits = lapSplits.toList(),
            dailySummary = daily,
            justFinishedSessionId = lastCompletedSessionId,
            isLowPowerStandby = isLowPowerStandby,
            intervalState = IntervalWorkoutState(
                phase = intervalPhase,
                currentRep = intervalCurrentRep,
                totalReps = intervalTotalReps,
                repDistanceMeters = intervalRepDistM,
                repDurationSec = intervalRepSec,
                restRemainingSec = intervalRestRemainingSec,
                targetRepSec = intervalTargetRepSec,
                restDurationSec = intervalRestDurationSec,
                completedRepSplitsSec = intervalCompletedSplitsSec.toList()
            )
        )
    }

    private fun broadcastTelemetry(
        effectiveSpeedMps: Double = 0.0,
        gpsFresh: Boolean = false,
        cadenceSpm: Double = 0.0,
        accelRms: Double = 0.0,
        liveStrideLengthMeters: Double = 0.0
    ) {
        val snap = snapshotTelemetry(effectiveSpeedMps, gpsFresh, cadenceSpm, accelRms, liveStrideLengthMeters)
        for (l in listeners) {
            l(snap)
        }
    }
}
