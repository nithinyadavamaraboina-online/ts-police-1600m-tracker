package com.tspolice.runtracker.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.tspolice.runtracker.domain.DailyActivitySummary
import com.tspolice.runtracker.domain.GaitClassifier
import com.tspolice.runtracker.domain.LapSplit
import com.tspolice.runtracker.domain.MotionState
import com.tspolice.runtracker.domain.PocketAudioMode
import com.tspolice.runtracker.domain.RunMode
import com.tspolice.runtracker.domain.RunSessionRecord
import com.tspolice.runtracker.domain.SamsungDailySnapshot
import com.tspolice.runtracker.domain.SamsungHealthComparator
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
        private const val DB_VERSION = 2
        private const val TABLE_RUNS = "run_sessions"
        private const val TABLE_DAILY = "daily_activity"
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
        private const val KEY_IMPORTED_EXT_WORKOUTS = "imported_external_workout_ids"
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

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Non-destructive upgrade: preserve all existing user run_sessions!
        if (oldVersion < 2) {
            createDailyTableIfNeeded(db)
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

    fun saveSamsungSnapshot(snapshot: SamsungDailySnapshot) {
        val obj = JSONObject().apply {
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
        }
        prefs.edit().putString(KEY_CACHED_SAMSUNG_JSON, obj.toString()).apply()
    }

    fun getCachedSamsungSnapshot(dateIso: String): SamsungDailySnapshot? {
        val raw = prefs.getString(KEY_CACHED_SAMSUNG_JSON, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            if (o.optString("dateIso") != dateIso) return null
            SamsungDailySnapshot(
                dateIso = o.optString("dateIso"),
                steps = o.optInt("steps", 0),
                distanceMeters = o.optDouble("distanceMeters", 0.0),
                walkingSec = o.optDouble("walkingSec", 0.0),
                jogRunSec = o.optDouble("jogRunSec", 0.0),
                maxSpeedMps = o.optDouble("maxSpeedMps", 0.0),
                maxCadenceSpm = o.optDouble("maxCadenceSpm", 0.0),
                sourcePackage = o.optString("sourcePackage", SamsungHealthComparator.SAMSUNG_HEALTH_PACKAGE),
                sourceLabel = o.optString("sourceLabel", "Samsung Health"),
                syncedAtEpochMs = o.optLong("syncedAtEpochMs", 0L)
            )
        }.getOrNull()
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
}
