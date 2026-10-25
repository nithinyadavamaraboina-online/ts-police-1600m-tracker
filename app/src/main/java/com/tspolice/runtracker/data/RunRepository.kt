package com.tspolice.runtracker.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.tspolice.runtracker.domain.GaitClassifier
import com.tspolice.runtracker.domain.LapSplit
import com.tspolice.runtracker.domain.MotionState
import com.tspolice.runtracker.domain.RunMode
import com.tspolice.runtracker.domain.RunSessionRecord
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
        private const val DB_VERSION = 1
        private const val TABLE_RUNS = "run_sessions"
        private const val PREFS_NAME = "ts_police_prefs"

        private const val KEY_TARGET_SEC = "target_time_sec"
        private const val KEY_RUN_SPEED_MPS = "run_threshold_mps"
        private const val KEY_VOICE_COACH = "voice_coach_enabled"
        private const val KEY_STEP_FALLBACK = "indoor_step_distance_fallback"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_RUNS (
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
        db.execSQL("CREATE INDEX idx_runs_date ON $TABLE_RUNS(date_iso, started_at_ms DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_RUNS")
        onCreate(db)
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

    var indoorStepFallbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_STEP_FALLBACK, true)
        set(value) = prefs.edit().putBoolean(KEY_STEP_FALLBACK, value).apply()

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
}
