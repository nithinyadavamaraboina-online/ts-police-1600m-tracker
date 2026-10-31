package com.tspolice.runtracker.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.tspolice.runtracker.domain.DailyActivitySummary
import com.tspolice.runtracker.domain.ExternalRunWorkout
import com.tspolice.runtracker.domain.GaitClassifier
import com.tspolice.runtracker.domain.HourlySensorBucket
import com.tspolice.runtracker.domain.LapSplit
import com.tspolice.runtracker.domain.MotionState
import com.tspolice.runtracker.domain.PocketAudioMode
import com.tspolice.runtracker.domain.RunMode
import com.tspolice.runtracker.domain.RunSessionRecord
import com.tspolice.runtracker.domain.SamsungDailySnapshot
import com.tspolice.runtracker.domain.SamsungHealthComparator
import com.tspolice.runtracker.domain.SensorTransitionEvent
import com.tspolice.runtracker.domain.TsPoliceBenchmark
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

class RunRepository(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {
    companion object {
        private const val DB_NAME = "ts_police_1600m.db"
        private const val DB_VERSION = 4
        private const val TABLE_RUNS = "run_sessions"
        private const val TABLE_DAILY = "daily_activity"
        private const val TABLE_HOURLY = "sensor_hourly_buckets"
        private const val TABLE_TRANSITIONS = "sensor_transition_log"
        private const val PREFS_NAME = "ts_police_prefs"

        private const val KEY_TARGET_SEC = "target_time_sec"
        private const val KEY_RUN_SPEED_MPS = "run_threshold_mps"
        private const val KEY_VOICE_COACH = "voice_coach_enabled"
        private const val KEY_STATE_CHANGE_AUDIO = "state_change_audio_enabled"
        private const val KEY_POCKET_AUDIO_MODE = "pocket_audio_mode_v2"
        private const val KEY_RUNNER_HEIGHT_CM = "runner_height_cm"
        private const val KEY_HAS_SAMSUNG_CAL = "has_samsung_stride_cal"
        private const val KEY_STEP_FALLBACK = "indoor_step_distance_fallback"
        private const val KEY_ALL_DAY_AUTO = "all_day_auto_tracking_enabled"
        private const val KEY_STRIDE_SCALE = "samsung_stride_scale_factor"
        private const val KEY_AUTO_CALIBRATE_SAMSUNG = "auto_calibrate_from_samsung"
        private const val KEY_AUTO_IMPORT_SAMSUNG_RUNS = "auto_import_samsung_runs"
        private const val KEY_CACHED_SAMSUNG_JSON = "cached_samsung_snapshot_json"
        private const val KEY_CACHED_SAMSUNG_MAP_JSON = "cached_samsung_snapshots_map_v2"
        private const val KEY_IMPORTED_EXT_WORKOUTS = "imported_external_workout_ids"
        private const val KEY_LAST_TUNING_SYNC_SUMMARY = "last_tuning_sync_summary"
        private const val KEY_LAST_TUNING_SYNC_EPOCH_MS = "last_tuning_sync_epoch_ms"
        private const val KEY_LAST_TUNING_SYNC_DATE_ISO = "last_tuning_sync_date_iso"
        private const val KEY_LAST_TUNING_SYNC_STEPS = "last_tuning_sync_steps"
        private const val KEY_LAST_HANDLED_PULL_EPOCH_SEC = "last_handled_pull_epoch_sec"
        private const val KEY_LAST_HANDLED_PULL_ID = "last_handled_pull_id"
        private const val KEY_CACHED_DEEP_SAMSUNG_48H_JSON = "cached_deep_samsung_48h_json"
        private const val KEY_VERIFIED_HW_PEDOMETER = "verified_hw_pedometer_active"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_RUNS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at_ms INTEGER NOT NULL,
                date_iso TEXT NOT NULL,
                mode TEXT NOT NULL,
                distance_m REAL NOT NULL,
                active_sec REAL NOT NULL,
                paused_sec REAL NOT NULL,
                first_1600_sec REAL,
                equiv_1600_sec REAL NOT NULL,
                avg_cadence_spm REAL NOT NULL,
                max_speed_mps REAL NOT NULL,
                running_sec REAL NOT NULL,
                jogging_sec REAL NOT NULL,
                walking_sec REAL NOT NULL,
                splits_json TEXT NOT NULL,
                notes TEXT NOT NULL,
                is_demo INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_runs_date ON $TABLE_RUNS(date_iso, started_at_ms DESC)")
        createDailyTableIfNeeded(db)
        createTuningTablesIfNeeded(db)
    }

    private fun createDailyTableIfNeeded(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_DAILY (
                date_iso TEXT PRIMARY KEY NOT NULL,
                walking_sec REAL NOT NULL DEFAULT 0,
                jogging_sec REAL NOT NULL DEFAULT 0,
                running_sec REAL NOT NULL DEFAULT 0,
                walking_m REAL NOT NULL DEFAULT 0,
                jogging_m REAL NOT NULL DEFAULT 0,
                running_m REAL NOT NULL DEFAULT 0,
                total_steps INTEGER NOT NULL DEFAULT 0,
                max_speed_mps REAL NOT NULL DEFAULT 0,
                max_cadence_spm REAL NOT NULL DEFAULT 0,
                is_demo INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    private fun createTuningTablesIfNeeded(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_HOURLY (
                bucket_key TEXT PRIMARY KEY NOT NULL,
                date_iso TEXT NOT NULL,
                hour_of_day INTEGER NOT NULL,
                hw_steps INTEGER NOT NULL DEFAULT 0,
                fallback_steps INTEGER NOT NULL DEFAULT 0,
                walk_sec REAL NOT NULL DEFAULT 0,
                jog_sec REAL NOT NULL DEFAULT 0,
                run_sec REAL NOT NULL DEFAULT 0,
                vehicle_sec REAL NOT NULL DEFAULT 0,
                low_power_sec REAL NOT NULL DEFAULT 0,
                walk_m REAL NOT NULL DEFAULT 0,
                jog_run_m REAL NOT NULL DEFAULT 0,
                gps_m REAL NOT NULL DEFAULT 0,
                stride_m REAL NOT NULL DEFAULT 0,
                max_speed_mps REAL NOT NULL DEFAULT 0,
                max_cadence_spm REAL NOT NULL DEFAULT 0,
                max_accel_rms REAL NOT NULL DEFAULT 0,
                gps_fix_count INTEGER NOT NULL DEFAULT 0,
                gps_acc_sum REAL NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_hourly_date ON $TABLE_HOURLY(date_iso, hour_of_day)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_TRANSITIONS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                epoch_ms INTEGER NOT NULL,
                from_state TEXT NOT NULL,
                to_state TEXT NOT NULL,
                gps_speed_mps REAL,
                gps_acc_m REAL,
                cadence_spm REAL NOT NULL,
                accel_rms REAL NOT NULL,
                min_raw_norm REAL,
                peak_to_trough REAL,
                p20_raw_norm REAL,
                is_low_power INTEGER NOT NULL DEFAULT 0,
                pt_phase TEXT NOT NULL
            )
            """.trimIndent()
        )
        ensureTransitionP20Column(db)
    }

    private fun ensureTransitionP20Column(db: SQLiteDatabase) {
        var hasP20 = false
        db.rawQuery("PRAGMA table_info($TABLE_TRANSITIONS)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (nameIdx >= 0 && c.getString(nameIdx) == "p20_raw_norm") {
                    hasP20 = true
                    break
                }
            }
        }
        if (!hasP20) {
            runCatching {
                db.execSQL("ALTER TABLE $TABLE_TRANSITIONS ADD COLUMN p20_raw_norm REAL")
            }
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Non-destructive upgrade: preserve all existing user run_sessions & daily_activity!
        if (oldVersion < 2) {
            createDailyTableIfNeeded(db)
        }
        if (oldVersion < 3) {
            createTuningTablesIfNeeded(db)
        }
        if (oldVersion < 4) {
            createTuningTablesIfNeeded(db)
            ensureTransitionP20Column(db)
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) {
            createTuningTablesIfNeeded(db)
        }
    }

    var targetTimeSec: Double
        get() = prefs.getFloat(KEY_TARGET_SEC, TsPoliceBenchmark.DEFAULT_TARGET_TIME_SEC.toFloat()).toDouble()
        set(value) = prefs.edit().putFloat(KEY_TARGET_SEC, value.toFloat()).apply()

    var runningThresholdMps: Double
        get() = prefs.getFloat(KEY_RUN_SPEED_MPS, GaitClassifier.DEFAULT_RUNNING_SPEED_MPS.toFloat()).toDouble()
        set(value) = prefs.edit().putFloat(KEY_RUN_SPEED_MPS, value.toFloat()).apply()

    var voiceCoachEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_COACH, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_COACH, value).apply()

    var pocketAudioMode: PocketAudioMode
        get() {
            val saved = prefs.getString(KEY_POCKET_AUDIO_MODE, null)
            if (saved != null) {
                return runCatching { PocketAudioMode.valueOf(saved) }.getOrDefault(PocketAudioMode.SMART_TRACK)
            }
            val legacyEnabled = prefs.getBoolean(KEY_STATE_CHANGE_AUDIO, true)
            return if (legacyEnabled) PocketAudioMode.SMART_TRACK else PocketAudioMode.MUTED
        }
        set(value) {
            prefs.edit()
                .putString(KEY_POCKET_AUDIO_MODE, value.name)
                .putBoolean(KEY_STATE_CHANGE_AUDIO, value != PocketAudioMode.MUTED)
                .apply()
        }

    var stateChangeAudioEnabled: Boolean
        get() = pocketAudioMode != PocketAudioMode.MUTED
        set(value) {
            pocketAudioMode = if (value) PocketAudioMode.SMART_TRACK else PocketAudioMode.MUTED
        }

    var runnerHeightCm: Int
        get() = prefs.getInt(KEY_RUNNER_HEIGHT_CM, 172).coerceIn(145, 205)
        set(value) = prefs.edit().putInt(KEY_RUNNER_HEIGHT_CM, value.coerceIn(145, 205)).apply()

    var hasSamsungStrideCalibration: Boolean
        get() = prefs.getBoolean(KEY_HAS_SAMSUNG_CAL, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_SAMSUNG_CAL, value).apply()

    fun effectiveStrideScale(): Double {
        return if (autoCalibrateFromSamsung && hasSamsungStrideCalibration) {
            strideCalibrationScale
        } else {
            TsPoliceBenchmark.heightStrideScale(runnerHeightCm)
        }
    }

    var indoorStepFallbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_STEP_FALLBACK, true)
        set(value) = prefs.edit().putBoolean(KEY_STEP_FALLBACK, value).apply()

    var hasVerifiedHardwarePedometer: Boolean
        get() = prefs.getBoolean(KEY_VERIFIED_HW_PEDOMETER, false)
        set(value) = prefs.edit().putBoolean(KEY_VERIFIED_HW_PEDOMETER, value).apply()

    var allDayTrackingEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALL_DAY_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_ALL_DAY_AUTO, value).apply()

    var strideCalibrationScale: Double
        get() = prefs.getFloat(KEY_STRIDE_SCALE, 1.0f).toDouble()
            .coerceIn(SamsungHealthComparator.MIN_STRIDE_SCALE, SamsungHealthComparator.MAX_STRIDE_SCALE)
        set(value) = prefs.edit().putFloat(
            KEY_STRIDE_SCALE,
            value.coerceIn(SamsungHealthComparator.MIN_STRIDE_SCALE, SamsungHealthComparator.MAX_STRIDE_SCALE).toFloat()
        ).apply()

    var autoCalibrateFromSamsung: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CALIBRATE_SAMSUNG, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CALIBRATE_SAMSUNG, value).apply()

    var autoImportSamsungRuns: Boolean
        get() = prefs.getBoolean(KEY_AUTO_IMPORT_SAMSUNG_RUNS, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_IMPORT_SAMSUNG_RUNS, value).apply()

    private fun encodeSamsungSnapshot(snapshot: SamsungDailySnapshot): JSONObject {
        val workoutsArr = JSONArray()
        for (w in snapshot.workouts) {
            workoutsArr.put(
                JSONObject().apply {
                    put("externalId", w.externalId)
                    put("sourcePackage", w.sourcePackage)
                    put("startedAtEpochMs", w.startedAtEpochMs)
                    put("dateIso", w.dateIso)
                    put("durationSec", w.durationSec)
                    put("distanceMeters", w.distanceMeters)
                    put("maxSpeedMps", w.maxSpeedMps)
                    put("avgCadenceSpm", w.avgCadenceSpm)
                    put("title", w.title)
                }
            )
        }
        return JSONObject().apply {
            put("dateIso", snapshot.dateIso)
            put("steps", snapshot.steps)
            put("distanceMeters", snapshot.distanceMeters)
            put("walkingSec", snapshot.walkingSec)
            put("jogRunSec", snapshot.jogRunSec)
            put("maxSpeedMps", snapshot.maxSpeedMps)
            put("maxCadenceSpm", snapshot.maxCadenceSpm)
            put("sourcePackage", snapshot.sourcePackage)
            put("sourceLabel", snapshot.sourceLabel)
            put("syncedAtEpochMs", snapshot.syncedAtEpochMs)
            put("workouts", workoutsArr)
            put("rawHealthConnectDistanceMeters", snapshot.rawHealthConnectDistanceMeters)
            put("distanceIncludedPassiveEstimate", snapshot.distanceIncludedPassiveEstimate)
        }
    }

    private fun decodeSamsungSnapshot(o: JSONObject): SamsungDailySnapshot {
        val workoutsList = mutableListOf<ExternalRunWorkout>()
        val wArr = o.optJSONArray("workouts")
        if (wArr != null) {
            for (i in 0 until wArr.length()) {
                val wo = wArr.optJSONObject(i) ?: continue
                workoutsList.add(
                    ExternalRunWorkout(
                        externalId = wo.optString("externalId"),
                        sourcePackage = wo.optString("sourcePackage", SamsungHealthComparator.SAMSUNG_HEALTH_PACKAGE),
                        startedAtEpochMs = wo.optLong("startedAtEpochMs", 0L),
                        dateIso = wo.optString("dateIso", o.optString("dateIso")),
                        durationSec = wo.optDouble("durationSec", 0.0),
                        distanceMeters = wo.optDouble("distanceMeters", 0.0),
                        maxSpeedMps = wo.optDouble("maxSpeedMps", 0.0),
                        avgCadenceSpm = wo.optDouble("avgCadenceSpm", 0.0),
                        title = wo.optString("title", "Samsung Health Run")
                    )
                )
            }
        }
        val distMeters = o.optDouble("distanceMeters", 0.0)
        return SamsungDailySnapshot(
            dateIso = o.optString("dateIso"),
            steps = o.optInt("steps", 0),
            distanceMeters = distMeters,
            walkingSec = o.optDouble("walkingSec", 0.0),
            jogRunSec = o.optDouble("jogRunSec", 0.0),
            maxSpeedMps = o.optDouble("maxSpeedMps", 0.0),
            maxCadenceSpm = o.optDouble("maxCadenceSpm", 0.0),
            sourcePackage = o.optString("sourcePackage", SamsungHealthComparator.SAMSUNG_HEALTH_PACKAGE),
            sourceLabel = o.optString("sourceLabel", "Samsung Health"),
            syncedAtEpochMs = o.optLong("syncedAtEpochMs", 0L),
            workouts = workoutsList,
            rawHealthConnectDistanceMeters = o.optDouble("rawHealthConnectDistanceMeters", distMeters),
            distanceIncludedPassiveEstimate = o.optBoolean("distanceIncludedPassiveEstimate", false)
        )
    }

    fun saveSamsungSnapshot(snapshot: SamsungDailySnapshot) {
        if (snapshot.dateIso.isBlank()) return
        val mapObj = runCatching {
            val existing = prefs.getString(KEY_CACHED_SAMSUNG_MAP_JSON, null)
            if (existing != null) JSONObject(existing) else JSONObject()
        }.getOrDefault(JSONObject())

        mapObj.put(snapshot.dateIso, encodeSamsungSnapshot(snapshot))

        // Prune entries older than 14 days so SharedPreferences stays tiny
        val keys = mapObj.keys().asSequence().toList().sortedDescending()
        val trimmed = JSONObject()
        for (k in keys.take(14)) {
            trimmed.put(k, mapObj.getJSONObject(k))
        }

        prefs.edit()
            .putString(KEY_CACHED_SAMSUNG_MAP_JSON, trimmed.toString())
            .putString(KEY_CACHED_SAMSUNG_JSON, encodeSamsungSnapshot(snapshot).toString())
            .apply()
    }

    fun getCachedSamsungSnapshot(dateIso: String): SamsungDailySnapshot? {
        if (dateIso.isBlank()) return null
        val mapRaw = prefs.getString(KEY_CACHED_SAMSUNG_MAP_JSON, null)
        if (mapRaw != null) {
            val fromMap = runCatching {
                val mapObj = JSONObject(mapRaw)
                val entry = mapObj.optJSONObject(dateIso)
                entry?.let { decodeSamsungSnapshot(it) }
            }.getOrNull()
            if (fromMap != null) return fromMap
        }

        val raw = prefs.getString(KEY_CACHED_SAMSUNG_JSON, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            if (o.optString("dateIso") != dateIso) return null
            decodeSamsungSnapshot(o)
        }.getOrNull()
    }

    fun getAllCachedSamsungSnapshots(): Map<String, SamsungDailySnapshot> {
        val out = LinkedHashMap<String, SamsungDailySnapshot>()
        val mapRaw = prefs.getString(KEY_CACHED_SAMSUNG_MAP_JSON, null)
        if (mapRaw != null) {
            runCatching {
                val mapObj = JSONObject(mapRaw)
                for (k in mapObj.keys().asSequence().toList().sortedDescending()) {
                    mapObj.optJSONObject(k)?.let { out[k] = decodeSamsungSnapshot(it) }
                }
            }
        }
        return out
    }

    /**
     * Bidirectionally aligns a day's SQLite [DailyActivitySummary] with Samsung Health ground truth:
     *  - Adds missed steps/distance if the user wore a Galaxy Watch or walked while the app was off.
     *  - Cleans overcounted pre-update bike/vibration steps, fake jog/run time, and vehicle speed spikes
     *    while preserving any real saved 1.6 km / Open Run sessions recorded in [TABLE_RUNS] for [dateIso].
     */
    fun alignDailyActivityWithSamsung(dateIso: String, samsung: SamsungDailySnapshot): DailyActivitySummary {
        val cur = getDailyActivity(dateIso)
        if (samsung.steps <= 0 && samsung.distanceMeters < 5.0) {
            return cur
        }

        val dayRuns = getAllSessions().filter { !it.isDemo && it.dateIso == dateIso }
        val savedRunSec = dayRuns.sumOf { it.runningSec }
        val savedJogSec = dayRuns.sumOf { it.joggingSec }
        val savedRunDistM = dayRuns.sumOf { it.distanceMeters }
        val savedMaxSpeed = dayRuns.maxOfOrNull { it.maxSpeedMps } ?: 0.0
        val savedMaxCad = dayRuns.maxOfOrNull { it.avgCadenceSpm } ?: 0.0

        val newSteps = if (samsung.steps > 0) {
            kotlin.math.max(samsung.steps, (savedRunDistM / 1.25).toInt())
        } else {
            cur.totalSteps
        }

        val targetJogRunSec = kotlin.math.max(samsung.jogRunSec, savedRunSec + savedJogSec)

        val newWalkSec: Double
        val newJogSec: Double
        val newRunSec: Double
        val newWalkM: Double
        val newJogM: Double
        val newRunM: Double
        val newMaxSpeed: Double
        val newMaxCad: Double

        if (targetJogRunSec < 5.0) {
            // Pure walking day (no real jog/run in Samsung Health or saved run_sessions) — erase bike pollution!
            newJogSec = 0.0
            newRunSec = 0.0
            newJogM = 0.0
            newRunM = 0.0
            newWalkSec = if (samsung.walkingSec > 0.0) samsung.walkingSec else (newSteps / 1.72)
            newWalkM = if (samsung.distanceMeters > 0.0) {
                samsung.distanceMeters
            } else {
                newSteps * 0.76 * effectiveStrideScale()
            }
            newMaxSpeed = samsung.maxSpeedMps.takeIf { it in 0.45..GaitClassifier.MAX_HUMAN_RUNNING_SPEED_MPS }
                ?: cur.maxSpeedMps.takeIf { it in 0.45..2.05 }
                ?: 1.45
            newMaxCad = samsung.maxCadenceSpm.takeIf { it in 65.0..145.0 }
                ?: cur.maxCadenceSpm.takeIf { it in 65.0..138.0 }
                ?: 110.0
        } else {
            // Day includes real running/jogging (from Samsung Health and/or saved run_sessions)
            val runShareOfJogRun = when {
                savedRunSec + savedJogSec >= targetJogRunSec * 0.80 && (savedRunSec + savedJogSec) > 0.0 ->
                    (savedRunSec / (savedRunSec + savedJogSec)).coerceIn(0.0, 1.0)
                cur.jogRunCombinedSec > 5.0 ->
                    (cur.runningSec / cur.jogRunCombinedSec).coerceIn(0.25, 0.85)
                else -> 0.55
            }
            newRunSec = targetJogRunSec * runShareOfJogRun
            newJogSec = targetJogRunSec * (1.0 - runShareOfJogRun)
            newWalkSec = if (samsung.walkingSec > 0.0) samsung.walkingSec else cur.walkingSec

            val targetTotalDist = kotlin.math.max(samsung.distanceMeters, savedRunDistM)
            val estJogRunDist = if (savedRunDistM > 0.0 && samsung.jogRunSec <= savedRunSec + savedJogSec + 30.0) {
                savedRunDistM.coerceAtMost(targetTotalDist)
            } else {
                (targetJogRunSec * 2.85).coerceAtMost(targetTotalDist * 0.92)
            }
            newWalkM = (targetTotalDist - estJogRunDist).coerceAtLeast(0.0)
            newRunM = estJogRunDist * runShareOfJogRun
            newJogM = estJogRunDist * (1.0 - runShareOfJogRun)

            val candidateSpeed = maxOf(
                savedMaxSpeed,
                samsung.maxSpeedMps.takeIf { it <= GaitClassifier.MAX_HUMAN_RUNNING_SPEED_MPS } ?: 0.0,
                cur.maxSpeedMps.takeIf { it <= 5.10 } ?: 0.0
            )
            newMaxSpeed = if (candidateSpeed >= 0.50) candidateSpeed else 3.20

            val candidateCad = maxOf(
                savedMaxCad,
                samsung.maxCadenceSpm.takeIf { it <= 220.0 } ?: 0.0,
                cur.maxCadenceSpm.takeIf { it <= 195.0 } ?: 0.0
            )
            newMaxCad = if (candidateCad >= 68.0) candidateCad else 162.0
        }

        val updated = cur.copy(
            dateIso = dateIso,
            walkingSec = newWalkSec,
            joggingSec = newJogSec,
            runningSec = newRunSec,
            walkingMeters = newWalkM,
            joggingMeters = newJogM,
            runningMeters = newRunM,
            totalSteps = newSteps,
            maxSpeedMps = newMaxSpeed,
            maxCadenceSpm = newMaxCad,
            isDemo = false
        )
        upsertDailyActivity(updated)
        return updated
    }

    fun hasSessionNear(startedAtMs: Long, windowMs: Long = 15L * 60_000L): Boolean {
        val low = (startedAtMs - windowMs).toString()
        val high = (startedAtMs + windowMs).toString()
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_RUNS WHERE started_at_ms BETWEEN ? AND ?",
            arrayOf(low, high)
        ).use { c ->
            return c.moveToFirst() && c.getInt(0) > 0
        }
    }

    fun isExternalWorkoutImported(externalId: String): Boolean {
        val current = prefs.getStringSet(KEY_IMPORTED_EXT_WORKOUTS, emptySet()) ?: emptySet()
        return externalId in current
    }

    fun markExternalWorkoutImported(externalId: String) {
        val updated = LinkedHashSet(prefs.getStringSet(KEY_IMPORTED_EXT_WORKOUTS, emptySet()) ?: emptySet())
        updated.add(externalId)
        prefs.edit().putStringSet(KEY_IMPORTED_EXT_WORKOUTS, updated).apply()
    }

    fun getDailyActivity(dateIso: String): DailyActivitySummary {
        readableDatabase.query(
            TABLE_DAILY,
            null,
            "date_iso = ?",
            arrayOf(dateIso),
            null,
            null,
            null
        ).use { c ->
            if (c.moveToFirst()) {
                return DailyActivitySummary(
                    dateIso = c.getString(c.getColumnIndexOrThrow("date_iso")),
                    walkingSec = c.getDouble(c.getColumnIndexOrThrow("walking_sec")),
                    joggingSec = c.getDouble(c.getColumnIndexOrThrow("jogging_sec")),
                    runningSec = c.getDouble(c.getColumnIndexOrThrow("running_sec")),
                    walkingMeters = c.getDouble(c.getColumnIndexOrThrow("walking_m")),
                    joggingMeters = c.getDouble(c.getColumnIndexOrThrow("jogging_m")),
                    runningMeters = c.getDouble(c.getColumnIndexOrThrow("running_m")),
                    totalSteps = c.getInt(c.getColumnIndexOrThrow("total_steps")),
                    maxSpeedMps = c.getDouble(c.getColumnIndexOrThrow("max_speed_mps")),
                    maxCadenceSpm = c.getDouble(c.getColumnIndexOrThrow("max_cadence_spm")),
                    isDemo = c.getInt(c.getColumnIndexOrThrow("is_demo")) == 1
                )
            }
        }
        return DailyActivitySummary(dateIso = dateIso)
    }

    fun upsertDailyActivity(summary: DailyActivitySummary) {
        if (summary.dateIso.isBlank()) return
        val values = ContentValues().apply {
            put("date_iso", summary.dateIso)
            put("walking_sec", summary.walkingSec)
            put("jogging_sec", summary.joggingSec)
            put("running_sec", summary.runningSec)
            put("walking_m", summary.walkingMeters)
            put("jogging_m", summary.joggingMeters)
            put("running_m", summary.runningMeters)
            put("total_steps", summary.totalSteps)
            put("max_speed_mps", summary.maxSpeedMps)
            put("max_cadence_spm", summary.maxCadenceSpm)
            put("is_demo", if (summary.isDemo) 1 else 0)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_DAILY,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getRecentDailyActivities(limit: Int = 14): List<DailyActivitySummary> {
        val out = mutableListOf<DailyActivitySummary>()
        readableDatabase.query(
            TABLE_DAILY,
            null,
            null,
            null,
            null,
            null,
            "date_iso DESC",
            limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    DailyActivitySummary(
                        dateIso = c.getString(c.getColumnIndexOrThrow("date_iso")),
                        walkingSec = c.getDouble(c.getColumnIndexOrThrow("walking_sec")),
                        joggingSec = c.getDouble(c.getColumnIndexOrThrow("jogging_sec")),
                        runningSec = c.getDouble(c.getColumnIndexOrThrow("running_sec")),
                        walkingMeters = c.getDouble(c.getColumnIndexOrThrow("walking_m")),
                        joggingMeters = c.getDouble(c.getColumnIndexOrThrow("jogging_m")),
                        runningMeters = c.getDouble(c.getColumnIndexOrThrow("running_m")),
                        totalSteps = c.getInt(c.getColumnIndexOrThrow("total_steps")),
                        maxSpeedMps = c.getDouble(c.getColumnIndexOrThrow("max_speed_mps")),
                        maxCadenceSpm = c.getDouble(c.getColumnIndexOrThrow("max_cadence_spm")),
                        isDemo = c.getInt(c.getColumnIndexOrThrow("is_demo")) == 1
                    )
                )
            }
        }
        return out
    }

    fun insertSession(record: RunSessionRecord): Long {
        val values = ContentValues().apply {
            put("started_at_ms", record.startedAtEpochMs)
            put("date_iso", record.dateIso)
            put("mode", record.mode.name)
            put("distance_m", record.distanceMeters)
            put("active_sec", record.activeDurationSec)
            put("paused_sec", record.pausedDurationSec)
            if (record.first1600mSplitSec != null) {
                put("first_1600_sec", record.first1600mSplitSec)
            } else {
                putNull("first_1600_sec")
            }
            put("equiv_1600_sec", record.equiv1600mSec)
            put("avg_cadence_spm", record.avgCadenceSpm)
            put("max_speed_mps", record.maxSpeedMps)
            put("running_sec", record.runningSec)
            put("jogging_sec", record.joggingSec)
            put("walking_sec", record.walkingSec)
            put("splits_json", encodeSplits(record.lapSplits))
            put("notes", record.notes)
            put("is_demo", if (record.isDemo) 1 else 0)
        }
        return writableDatabase.insert(TABLE_RUNS, null, values)
    }

    fun getAllSessions(): List<RunSessionRecord> {
        val out = mutableListOf<RunSessionRecord>()
        readableDatabase.query(
            TABLE_RUNS,
            null,
            null,
            null,
            null,
            null,
            "date_iso DESC, started_at_ms DESC"
        ).use { c ->
            while (c.moveToNext()) {
                val first1600Idx = c.getColumnIndexOrThrow("first_1600_sec")
                val first1600 = if (c.isNull(first1600Idx)) null else c.getDouble(first1600Idx)
                val modeStr = c.getString(c.getColumnIndexOrThrow("mode"))
                val mode = runCatching { RunMode.valueOf(modeStr) }.getOrDefault(RunMode.TIMED_1600M)
                out.add(
                    RunSessionRecord(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        startedAtEpochMs = c.getLong(c.getColumnIndexOrThrow("started_at_ms")),
                        dateIso = c.getString(c.getColumnIndexOrThrow("date_iso")),
                        mode = mode,
                        distanceMeters = c.getDouble(c.getColumnIndexOrThrow("distance_m")),
                        activeDurationSec = c.getDouble(c.getColumnIndexOrThrow("active_sec")),
                        pausedDurationSec = c.getDouble(c.getColumnIndexOrThrow("paused_sec")),
                        first1600mSplitSec = first1600,
                        equiv1600mSec = c.getDouble(c.getColumnIndexOrThrow("equiv_1600_sec")),
                        avgCadenceSpm = c.getDouble(c.getColumnIndexOrThrow("avg_cadence_spm")),
                        maxSpeedMps = c.getDouble(c.getColumnIndexOrThrow("max_speed_mps")),
                        runningSec = c.getDouble(c.getColumnIndexOrThrow("running_sec")),
                        joggingSec = c.getDouble(c.getColumnIndexOrThrow("jogging_sec")),
                        walkingSec = c.getDouble(c.getColumnIndexOrThrow("walking_sec")),
                        lapSplits = decodeSplits(c.getString(c.getColumnIndexOrThrow("splits_json"))),
                        notes = c.getString(c.getColumnIndexOrThrow("notes")),
                        isDemo = c.getInt(c.getColumnIndexOrThrow("is_demo")) == 1
                    )
                )
            }
        }
        return out
    }

    fun deleteSession(id: Long) {
        writableDatabase.delete(TABLE_RUNS, "id = ?", arrayOf(id.toString()))
    }

    fun hasDemoSessions(): Boolean {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_RUNS WHERE is_demo = 1", null).use { c ->
            return c.moveToFirst() && c.getInt(0) > 0
        }
    }

    fun clearDemoSessions() {
        writableDatabase.delete(TABLE_RUNS, "is_demo = 1", null)
        writableDatabase.delete(TABLE_DAILY, "is_demo = 1", null)
    }

    /**
     * Seeds realistic 12-day Telangana Police 1600m practice progression so the user can preview
     * the trend chart & Days-to-7:15 ETA engine immediately (removable in 1 tap).
     */
    fun seedSampleProgressionIfRequested() {
        clearDemoSessions()
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()

        // (daysAgo, distanceM, durationSec, runShare, mode, note)
        val samples = listOf(
            SampleDay(13, 1600.0, 514.0, 0.58, RunMode.TIMED_1600M, "Baseline 1.6km trial (8:34)"),
            SampleDay(11, 2400.0, 845.0, 0.42, RunMode.OPEN_PRACTICE, "Easy aerobic jog + strides"),
            SampleDay(9,  1600.0, 499.0, 0.67, RunMode.TIMED_1600M, "Track trial #2 (8:19)"),
            SampleDay(7,  1600.0, 488.0, 0.74, RunMode.TIMED_1600M, "Morning ground 4x400m continuous (8:08)"),
            SampleDay(5,  2800.0, 970.0, 0.45, RunMode.OPEN_PRACTICE, "Endurance jog session"),
            SampleDay(3,  1600.0, 476.0, 0.81, RunMode.TIMED_1600M, "Strong negative split trial (7:56)"),
            SampleDay(1,  1600.0, 467.0, 0.86, RunMode.TIMED_1600M, "Latest 1.6km test — 32s away from 7:15!")
        )

        for (s in samples) {
            val date = today.minusDays(s.daysAgo.toLong())
            val startMs = date.atTime(6, 15).atZone(zone).toInstant().toEpochMilli()
            val runSec = s.durationSec * s.runShare
            val jogSec = s.durationSec * (1.0 - s.runShare)
            val is1600 = s.mode == RunMode.TIMED_1600M
            val splits = if (is1600) {
                val lapAvg = s.durationSec / 4.0
                listOf(
                    LapSplit(1, 400.0, lapAvg - 2.0, lapAvg - 2.0, MotionState.RUNNING),
                    LapSplit(2, 800.0, lapAvg + 0.5, 2 * lapAvg - 1.5, MotionState.RUNNING),
                    LapSplit(3, 1200.0, lapAvg + 2.5, 3 * lapAvg + 1.0, MotionState.JOGGING),
                    LapSplit(4, 1600.0, lapAvg - 1.0, s.durationSec, MotionState.RUNNING)
                )
            } else emptyList()

            insertSession(
                RunSessionRecord(
                    startedAtEpochMs = startMs,
                    dateIso = date.toString(),
                    mode = s.mode,
                    distanceMeters = s.distanceM,
                    activeDurationSec = s.durationSec,
                    pausedDurationSec = 4.0,
                    first1600mSplitSec = if (is1600) s.durationSec else null,
                    equiv1600mSec = TsPoliceBenchmark.computeEquiv1600mSec(
                        s.distanceM,
                        s.durationSec,
                        if (is1600) s.durationSec else null
                    ),
                    avgCadenceSpm = if (s.runShare > 0.7) 169.0 else 154.0,
                    maxSpeedMps = (s.distanceM / s.durationSec) * 1.12,
                    runningSec = runSec,
                    joggingSec = jogSec,
                    walkingSec = 0.0,
                    lapSplits = splits,
                    notes = s.note,
                    isDemo = true
                )
            )

            // Also seed whole-day automatic walk + jog/run activity for those past days
            val walkSec = 1950.0 + (s.daysAgo * 85.0)
            val walkM = walkSec * 1.28
            upsertDailyActivity(
                DailyActivitySummary(
                    dateIso = date.toString(),
                    walkingSec = walkSec,
                    joggingSec = jogSec,
                    runningSec = runSec,
                    walkingMeters = walkM,
                    joggingMeters = s.distanceM * (1.0 - s.runShare),
                    runningMeters = s.distanceM * s.runShare,
                    totalSteps = ((walkM / 0.76) + (s.distanceM / 1.18)).toInt(),
                    maxSpeedMps = (s.distanceM / s.durationSec) * 1.14,
                    maxCadenceSpm = if (s.runShare > 0.7) 176.0 else 164.0,
                    isDemo = true
                )
            )
        }
    }

    private data class SampleDay(
        val daysAgo: Int,
        val distanceM: Double,
        val durationSec: Double,
        val runShare: Double,
        val mode: RunMode,
        val note: String
    )

    private fun encodeSplits(splits: List<LapSplit>): String {
        val arr = JSONArray()
        for (sp in splits) {
            arr.put(
                JSONObject().apply {
                    put("lap", sp.lapNumber)
                    put("dist", sp.distanceMeters)
                    put("dur", sp.lapDurationSec)
                    put("cum", sp.cumulativeSec)
                    put("state", sp.dominantState.name)
                }
            )
        }
        return arr.toString()
    }

    private fun decodeSplits(raw: String): List<LapSplit> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val list = ArrayList<LapSplit>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val st = runCatching { MotionState.valueOf(o.optString("state")) }
                    .getOrDefault(MotionState.RUNNING)
                list.add(
                    LapSplit(
                        lapNumber = o.optInt("lap", i + 1),
                        distanceMeters = o.optDouble("dist", (i + 1) * 400.0),
                        lapDurationSec = o.optDouble("dur", 0.0),
                        cumulativeSec = o.optDouble("cum", 0.0),
                        dominantState = st
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    /**
     * Exports all non-demo run sessions, daily activity history, and runner profile into portable JSON.
     */
    fun exportBackupJson(): String {
        val root = JSONObject()
        root.put("schemaVersion", 1)
        root.put("exportedAtIso", LocalDate.now().toString())
        root.put("targetTimeSec", targetTimeSec)
        root.put("runnerHeightCm", runnerHeightCm)
        root.put("strideCalibrationScale", strideCalibrationScale)

        val runsArr = JSONArray()
        for (s in getAllSessions().filter { !it.isDemo }) {
            runsArr.put(
                JSONObject().apply {
                    put("startedAtEpochMs", s.startedAtEpochMs)
                    put("dateIso", s.dateIso)
                    put("mode", s.mode.name)
                    put("distanceMeters", s.distanceMeters)
                    put("activeDurationSec", s.activeDurationSec)
                    put("pausedDurationSec", s.pausedDurationSec)
                    if (s.first1600mSplitSec != null) put("first1600mSplitSec", s.first1600mSplitSec)
                    put("equiv1600mSec", s.equiv1600mSec)
                    put("avgCadenceSpm", s.avgCadenceSpm)
                    put("maxSpeedMps", s.maxSpeedMps)
                    put("runningSec", s.runningSec)
                    put("joggingSec", s.joggingSec)
                    put("walkingSec", s.walkingSec)
                    put("splitsJson", encodeSplits(s.lapSplits))
                    put("notes", s.notes)
                }
            )
        }
        root.put("sessions", runsArr)

        val dailyArr = JSONArray()
        for (d in getRecentDailyActivities(60).filter { !it.isDemo }) {
            dailyArr.put(
                JSONObject().apply {
                    put("dateIso", d.dateIso)
                    put("walkingSec", d.walkingSec)
                    put("joggingSec", d.joggingSec)
                    put("runningSec", d.runningSec)
                    put("walkingMeters", d.walkingMeters)
                    put("joggingMeters", d.joggingMeters)
                    put("runningMeters", d.runningMeters)
                    put("totalSteps", d.totalSteps)
                    put("maxSpeedMps", d.maxSpeedMps)
                    put("maxCadenceSpm", d.maxCadenceSpm)
                }
            )
        }
        root.put("dailyActivity", dailyArr)
        val samArr = JSONArray()
        for ((_, snap) in getAllCachedSamsungSnapshots()) {
            samArr.put(encodeSamsungSnapshot(snap))
        }
        root.put("samsungSnapshots", samArr)
        return root.toString(2)
    }

    /**
     * Restores non-duplicate sessions from an exported JSON backup string. Returns count imported.
     */
    fun importBackupJson(rawJson: String): Int {
        val root = JSONObject(rawJson)
        val sessionsArr = root.optJSONArray("sessions") ?: JSONArray()
        var imported = 0
        for (i in 0 until sessionsArr.length()) {
            val o = sessionsArr.getJSONObject(i)
            val startMs = o.optLong("startedAtEpochMs", 0L)
            if (startMs <= 0L || hasSessionNear(startMs, windowMs = 60_000L)) continue
            val mode = runCatching { RunMode.valueOf(o.optString("mode")) }.getOrDefault(RunMode.TIMED_1600M)
            val f1600 = if (o.has("first1600mSplitSec")) o.optDouble("first1600mSplitSec") else null
            insertSession(
                RunSessionRecord(
                    startedAtEpochMs = startMs,
                    dateIso = o.optString("dateIso", LocalDate.now().toString()),
                    mode = mode,
                    distanceMeters = o.optDouble("distanceMeters", 1600.0),
                    activeDurationSec = o.optDouble("activeDurationSec", 480.0),
                    pausedDurationSec = o.optDouble("pausedDurationSec", 0.0),
                    first1600mSplitSec = f1600,
                    equiv1600mSec = o.optDouble("equiv1600mSec", 480.0),
                    avgCadenceSpm = o.optDouble("avgCadenceSpm", 165.0),
                    maxSpeedMps = o.optDouble("maxSpeedMps", 3.5),
                    runningSec = o.optDouble("runningSec", 0.0),
                    joggingSec = o.optDouble("joggingSec", 0.0),
                    walkingSec = o.optDouble("walkingSec", 0.0),
                    lapSplits = decodeSplits(o.optString("splitsJson", "")),
                    notes = o.optString("notes", "Restored backup"),
                    isDemo = false
                )
            )
            imported++
        }
        return imported
    }

    var lastTuningSyncSummary: String
        get() = prefs.getString(KEY_LAST_TUNING_SYNC_SUMMARY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_TUNING_SYNC_SUMMARY, value).apply()

    var lastTuningSyncEpochMs: Long
        get() = prefs.getLong(KEY_LAST_TUNING_SYNC_EPOCH_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_TUNING_SYNC_EPOCH_MS, value).apply()

    var lastTuningSyncDateIso: String
        get() = prefs.getString(KEY_LAST_TUNING_SYNC_DATE_ISO, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_TUNING_SYNC_DATE_ISO, value).apply()

    var lastTuningSyncSteps: Int
        get() = prefs.getInt(KEY_LAST_TUNING_SYNC_STEPS, -1)
        set(value) = prefs.edit().putInt(KEY_LAST_TUNING_SYNC_STEPS, value).apply()

    var lastHandledRemotePullEpochSec: Long
        get() = prefs.getLong(KEY_LAST_HANDLED_PULL_EPOCH_SEC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_HANDLED_PULL_EPOCH_SEC, value).apply()

    var lastHandledRemotePullId: String
        get() = prefs.getString(KEY_LAST_HANDLED_PULL_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_HANDLED_PULL_ID, value).apply()

    var cachedDeepSamsung48hJson: String
        get() = prefs.getString(KEY_CACHED_DEEP_SAMSUNG_48H_JSON, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CACHED_DEEP_SAMSUNG_48H_JSON, value).apply()

    fun getHourlySensorBucket(bucketKey: String, dateIso: String, hourOfDay: Int): HourlySensorBucket {
        readableDatabase.query(
            TABLE_HOURLY,
            null,
            "bucket_key = ?",
            arrayOf(bucketKey),
            null,
            null,
            null
        ).use { c ->
            if (c.moveToFirst()) {
                return readHourlyRow(c)
            }
        }
        return HourlySensorBucket(bucketKey = bucketKey, dateIso = dateIso, hourOfDay = hourOfDay)
    }

    fun upsertHourlySensorBucket(bucket: HourlySensorBucket) {
        if (bucket.bucketKey.isBlank()) return
        val values = ContentValues().apply {
            put("bucket_key", bucket.bucketKey)
            put("date_iso", bucket.dateIso)
            put("hour_of_day", bucket.hourOfDay)
            put("hw_steps", bucket.hwSteps)
            put("fallback_steps", bucket.fallbackSteps)
            put("walk_sec", bucket.walkSec)
            put("jog_sec", bucket.jogSec)
            put("run_sec", bucket.runSec)
            put("vehicle_sec", bucket.vehicleSec)
            put("low_power_sec", bucket.lowPowerSec)
            put("walk_m", bucket.walkMeters)
            put("jog_run_m", bucket.jogRunMeters)
            put("gps_m", bucket.gpsMeters)
            put("stride_m", bucket.strideMeters)
            put("max_speed_mps", bucket.maxSpeedMps)
            put("max_cadence_spm", bucket.maxCadenceSpm)
            put("max_accel_rms", bucket.maxAccelRms)
            put("gps_fix_count", bucket.gpsFixCount)
            put("gps_acc_sum", bucket.gpsAccSumMeters)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_HOURLY,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getHourlySensorBucketsForDates(dateIsos: List<String>): Map<String, HourlySensorBucket> {
        if (dateIsos.isEmpty()) return emptyMap()
        val placeholders = dateIsos.joinToString(",") { "?" }
        val out = LinkedHashMap<String, HourlySensorBucket>()
        readableDatabase.query(
            TABLE_HOURLY,
            null,
            "date_iso IN ($placeholders)",
            dateIsos.toTypedArray(),
            null,
            null,
            "date_iso ASC, hour_of_day ASC"
        ).use { c ->
            while (c.moveToNext()) {
                val b = readHourlyRow(c)
                out[b.bucketKey] = b
            }
        }
        return out
    }

    private fun readHourlyRow(c: android.database.Cursor): HourlySensorBucket {
        return HourlySensorBucket(
            bucketKey = c.getString(c.getColumnIndexOrThrow("bucket_key")),
            dateIso = c.getString(c.getColumnIndexOrThrow("date_iso")),
            hourOfDay = c.getInt(c.getColumnIndexOrThrow("hour_of_day")),
            hwSteps = c.getInt(c.getColumnIndexOrThrow("hw_steps")),
            fallbackSteps = c.getInt(c.getColumnIndexOrThrow("fallback_steps")),
            walkSec = c.getDouble(c.getColumnIndexOrThrow("walk_sec")),
            jogSec = c.getDouble(c.getColumnIndexOrThrow("jog_sec")),
            runSec = c.getDouble(c.getColumnIndexOrThrow("run_sec")),
            vehicleSec = c.getDouble(c.getColumnIndexOrThrow("vehicle_sec")),
            lowPowerSec = c.getDouble(c.getColumnIndexOrThrow("low_power_sec")),
            walkMeters = c.getDouble(c.getColumnIndexOrThrow("walk_m")),
            jogRunMeters = c.getDouble(c.getColumnIndexOrThrow("jog_run_m")),
            gpsMeters = c.getDouble(c.getColumnIndexOrThrow("gps_m")),
            strideMeters = c.getDouble(c.getColumnIndexOrThrow("stride_m")),
            maxSpeedMps = c.getDouble(c.getColumnIndexOrThrow("max_speed_mps")),
            maxCadenceSpm = c.getDouble(c.getColumnIndexOrThrow("max_cadence_spm")),
            maxAccelRms = c.getDouble(c.getColumnIndexOrThrow("max_accel_rms")),
            gpsFixCount = c.getInt(c.getColumnIndexOrThrow("gps_fix_count")),
            gpsAccSumMeters = c.getDouble(c.getColumnIndexOrThrow("gps_acc_sum"))
        )
    }

    fun recordSensorTransition(event: SensorTransitionEvent) {
        val values = ContentValues().apply {
            put("epoch_ms", event.epochMs)
            put("from_state", event.fromState)
            put("to_state", event.toState)
            if (event.gpsSpeedMps != null) put("gps_speed_mps", event.gpsSpeedMps) else putNull("gps_speed_mps")
            if (event.gpsAccMeters != null) put("gps_acc_m", event.gpsAccMeters) else putNull("gps_acc_m")
            put("cadence_spm", event.cadenceSpm)
            put("accel_rms", event.accelRms)
            if (event.minRawNorm != null) put("min_raw_norm", event.minRawNorm) else putNull("min_raw_norm")
            if (event.peakToTroughNorm != null) put("peak_to_trough", event.peakToTroughNorm) else putNull("peak_to_trough")
            if (event.p20RawNorm != null) put("p20_raw_norm", event.p20RawNorm) else putNull("p20_raw_norm")
            put("is_low_power", if (event.isLowPower) 1 else 0)
            put("pt_phase", event.ptPhase)
        }
        val db = writableDatabase
        val rowId = db.insert(TABLE_TRANSITIONS, null, values)
        if (rowId > 0L && rowId % 40L == 0L) {
            db.execSQL("DELETE FROM $TABLE_TRANSITIONS WHERE id <= (SELECT MAX(id) - 320 FROM $TABLE_TRANSITIONS)")
        }
    }

    fun getRecentSensorTransitions(limit: Int = 300): List<SensorTransitionEvent> {
        val out = mutableListOf<SensorTransitionEvent>()
        readableDatabase.query(
            TABLE_TRANSITIONS,
            null,
            null,
            null,
            null,
            null,
            "id DESC",
            limit.toString()
        ).use { c ->
            val gpsSpdIdx = c.getColumnIndexOrThrow("gps_speed_mps")
            val gpsAccIdx = c.getColumnIndexOrThrow("gps_acc_m")
            val minNormIdx = c.getColumnIndexOrThrow("min_raw_norm")
            val p2tIdx = c.getColumnIndexOrThrow("peak_to_trough")
            val p20Idx = c.getColumnIndex("p20_raw_norm")
            while (c.moveToNext()) {
                out.add(
                    SensorTransitionEvent(
                        epochMs = c.getLong(c.getColumnIndexOrThrow("epoch_ms")),
                        fromState = c.getString(c.getColumnIndexOrThrow("from_state")),
                        toState = c.getString(c.getColumnIndexOrThrow("to_state")),
                        gpsSpeedMps = if (c.isNull(gpsSpdIdx)) null else c.getDouble(gpsSpdIdx),
                        gpsAccMeters = if (c.isNull(gpsAccIdx)) null else c.getDouble(gpsAccIdx),
                        cadenceSpm = c.getDouble(c.getColumnIndexOrThrow("cadence_spm")),
                        accelRms = c.getDouble(c.getColumnIndexOrThrow("accel_rms")),
                        minRawNorm = if (c.isNull(minNormIdx)) null else c.getDouble(minNormIdx),
                        peakToTroughNorm = if (c.isNull(p2tIdx)) null else c.getDouble(p2tIdx),
                        p20RawNorm = if (p20Idx < 0 || c.isNull(p20Idx)) null else c.getDouble(p20Idx),
                        isLowPower = c.getInt(c.getColumnIndexOrThrow("is_low_power")) == 1,
                        ptPhase = c.getString(c.getColumnIndexOrThrow("pt_phase"))
                    )
                )
            }
        }
        return out
    }
}
