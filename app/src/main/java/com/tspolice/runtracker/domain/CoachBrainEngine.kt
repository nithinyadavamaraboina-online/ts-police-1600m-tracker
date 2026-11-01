package com.tspolice.runtracker.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class LegFeelCheckIn(val chipLabel: String, val fullLabel: String) {
    AUTO("Auto", "Auto-Detect from Runs & Steps"),
    FRESH("Fresh", "Legs Feel Fresh & Ready"),
    TIRED("Tired", "Legs Feel Heavy / Tired"),
    SORE_SHINS("Sore Shins", "Shin / Calf / Ankle Pain")
}

enum class ExamTimeBatch(val shortLabel: String) {
    MORNING_GROUND("Morning Ground (6–9 AM)"),
    EVENING_OR_UNSURE("Evening / Not Sure")
}

enum class TimeOfDaySlot(
    val title: String,
    val clockRange: String,
    val sortOrder: Int
) {
    EARLY_MORNING("Early Morning", "5:00 – 7:59 AM", 0),
    MORNING("Morning", "8:00 – 11:29 AM", 1),
    AFTERNOON("Afternoon", "11:30 AM – 4:29 PM", 2),
    FIVE_PM("5:00 PM Slot", "4:30 – 5:44 PM", 3),
    SIX_PM("6:00 PM Slot", "5:45 – 6:59 PM", 4),
    LATE_EVENING("7:00+ PM Slot", "7:00 – 10:30 PM", 5);

    companion object {
        fun fromMinuteOfDay(minuteOfDay: Int): TimeOfDaySlot = when (minuteOfDay) {
            in 300..479 -> EARLY_MORNING
            in 480..689 -> MORNING
            in 690..989 -> AFTERNOON
            in 990..1064 -> FIVE_PM
            in 1065..1139 -> SIX_PM
            else -> LATE_EVENING
        }
    }
}

data class SlotPerformanceStat(
    val slot: TimeOfDaySlot,
    val runCount: Int,
    val trial1600Count: Int,
    val bestEquiv1600mSec: Double?,
    val avgEquiv1600mSec: Double?,
    val variationSec: Double?,
    val avgStopSec: Double,
    val avgPreRunSteps: Int?,
    val consistencyScore: Int,
    val isBestSlot: Boolean,
    val badgeLabel: String,
    val plainSummary: String
)

enum class DailySessionScheduleMode(val shortBadge: String) {
    TODAY_RUN_COMPLETED("Today's Run Done ✓"),
    ONE_EVENING_SESSION("1 Evening Run Only"),
    ONE_MORNING_EXAM_REHEARSAL("1 Morning Run (Exam Practice)"),
    LIGHT_MORNING_PLUS_EVENING("Optional 10m AM + Evening Run"),
    ZERO_RUNS_RECOVERY("0 Runs Today (Leg Repair)")
}

enum class CoachWorkoutKind {
    TODAY_DONE_RECOVER_TONIGHT,
    REST_AND_REPAIR,
    EASY_TALK_PACE_JOG,
    SPEED_400M_REPS,
    NONSTOP_1600M_TEST,
    SHARPENER_TAPER,
    EXAM_DAY_RACE
}

data class CoachBrainReport(
    val confidenceLabel: String,
    val dataPointsSummary: String,
    val workoutKind: CoachWorkoutKind,
    val effortBadgePlain: String,
    val accentKind: PushAccentKind,
    val simpleHeadline: String,
    val plainWhyExplanation: String,
    val whatToDo: List<String>,
    val whatNotToDo: List<String>,
    val fuelAndHydrationTip: String,
    val scheduleMode: DailySessionScheduleMode,
    val scheduleHeadline: String,
    val scheduleExplanation: String,
    val bestTimeSlot: TimeOfDaySlot?,
    val bestTimeWindowLabel: String,
    val timingVariationSummary: String,
    val slotStats: List<SlotPerformanceStat>,
    val personalInsights: List<String>,
    val recommendedIntervalLapSec: Double,
    val recommendedIntervalReps: Int,
    val recommendedAction: RecommendedWorkoutAction,
    val recommendedActionLabel: String
)

/**
 * Multi-stream Expert Running Coach Brain for the TS Police 1.6 km Physical Efficiency Test.
 *
 * Analyzes:
 *  1. Exact clock start times of every run (Early Morning vs 5:00 PM vs 6:00 PM vs 7:00+ PM)
 *  2. Pace variation, stop penalties, and 4x400m split pacing curves (fast-start blowup vs steady finish)
 *  3. Biomechanics: step cadence (steps/min), height/stride scale, and speed ceiling
 *  4. 14-day intraday hourly movement (wake/sleep rhythm + pre-run daytime walking fatigue)
 *  5. 7-day & 14-day training load + 1-tap runner leg check-in (Auto / Fresh / Tired / Sore Shins)
 *  6. Personalized fuel math (calories, carbs, protein, low pre-run fat, water) from body weight
 *
 * Speaks strictly in plain, everyday words ("What To Do" and "What NOT To Do") with zero sports jargon.
 */
object CoachBrainEngine {

    private data class EnrichedRun(
        val record: RunSessionRecord,
        val localHour: Int,
        val localMinute: Int,
        val slot: TimeOfDaySlot,
        val clockLabel: String,
        val preRunSteps: Int?,
        val dayTotalSteps: Int?
    )

    fun analyze(
        sessions: List<RunSessionRecord>,
        dailyMap: Map<String, DailyActivitySummary> = emptyMap(),
        samsungMap: Map<String, SamsungDailySnapshot> = emptyMap(),
        hourlyBuckets: List<HourlySensorBucket> = emptyList(),
        targetTimeSec: Double = TsPoliceBenchmark.DEFAULT_TARGET_TIME_SEC,
        deadlineIso: String = "2026-11-30",
        todayIso: String = LocalDate.now().toString(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        runnerHeightCm: Int = 174,
        runnerWeightKg: Int = 68,
        legFeel: LegFeelCheckIn = LegFeelCheckIn.AUTO,
        expectedExamBatch: ExamTimeBatch = ExamTimeBatch.MORNING_GROUND
    ): CoachBrainReport {
        val todayDate = runCatching { LocalDate.parse(todayIso) }.getOrElse { LocalDate.now(zoneId) }
        val deadlineDate = runCatching { LocalDate.parse(deadlineIso) }.getOrElse { LocalDate.of(2026, 11, 30) }
        val daysRemaining = (deadlineDate.toEpochDay() - todayDate.toEpochDay()).toInt().coerceAtLeast(0)
        val weightKg = runnerWeightKg.coerceIn(42, 125)
        val targetFmt = TsPoliceBenchmark.formatDuration(targetTimeSec)
        val targetLapSec = TsPoliceBenchmark.targetLap400mSec(targetTimeSec)
        val targetLapFmt = TsPoliceBenchmark.formatDuration(targetLapSec)

        // Group hourly buckets by date for wake-hour & pre-run step fatigue analysis
        val hourlyByDate = hourlyBuckets.groupBy { it.dateIso }

        fun effectiveDaySteps(dateIso: String): Int? {
            val trk = dailyMap[dateIso]?.totalSteps ?: 0
            val sam = samsungMap[dateIso]?.steps ?: 0
            val best = max(trk, sam)
            return if (best > 0) best else null
        }

        // Enrich all meaningful runs (>= 400m) with local clock time, slot, and pre-run step load
        val validRuns = sessions
            .filter { it.distanceMeters >= 400.0 && it.equiv1600mSec in 180.0..2400.0 }
            .sortedBy { it.startedAtEpochMs }

        val enrichedRuns = validRuns.map { r ->
            val zdt = Instant.ofEpochMilli(r.startedAtEpochMs).atZone(zoneId)
            val hr = zdt.hour
            val min = zdt.minute
            val minOfDay = hr * 60 + min
            val slot = TimeOfDaySlot.fromMinuteOfDay(minOfDay)
            val dayBuckets = hourlyByDate[r.dateIso].orEmpty()
            val preStepsFromBuckets = dayBuckets
                .filter { it.hourOfDay < hr }
                .sumOf { it.totalSteps }
            val daySteps = effectiveDaySteps(r.dateIso)
            val estimatedRunSteps = ((r.activeDurationSec / 60.0) * r.avgCadenceSpm.coerceIn(120.0, 185.0)).roundToInt()
            val preSteps = when {
                preStepsFromBuckets >= 250 -> preStepsFromBuckets
                daySteps != null && hr >= 16 -> (daySteps - estimatedRunSteps).coerceAtLeast(0)
                else -> null
            }
            EnrichedRun(
                record = r,
                localHour = hr,
                localMinute = min,
                slot = slot,
                clockLabel = format12HourClock(hr, min),
                preRunSteps = preSteps,
                dayTotalSteps = daySteps
            )
        }

        // Wake-up rhythm from hourly buckets (median first hour >= 60 steps between 4 AM and 11 AM)
        val wakeHours = hourlyByDate.values.mapNotNull { dayList ->
            dayList.filter { it.hourOfDay in 4..11 && (it.totalSteps >= 60 || it.walkMeters >= 40.0) }
                .minByOrNull { it.hourOfDay }
                ?.hourOfDay
        }
        val typicalWakeHour = if (wakeHours.isNotEmpty()) {
            wakeHours.sorted()[wakeHours.size / 2]
        } else null

        // 1. TIME-OF-DAY PERFORMANCE & VARIATION ENGINE
        val timingResult = analyzeTimeOfDayWindows(enrichedRuns, typicalWakeHour, expectedExamBatch)
        val bestSlot = timingResult.bestSlot
        val bestWindowText = when (bestSlot) {
            TimeOfDaySlot.SIX_PM -> "6:00 – 6:40 PM (Your Fastest & Steadiest Slot)"
            TimeOfDaySlot.FIVE_PM -> "5:00 – 5:40 PM (Your Fastest & Steadiest Slot)"
            TimeOfDaySlot.EARLY_MORNING -> "6:00 – 7:30 AM (Your Best Morning Slot)"
            TimeOfDaySlot.MORNING -> "8:00 – 9:30 AM (Your Best Slot)"
            TimeOfDaySlot.LATE_EVENING -> "7:00 – 7:45 PM (Try 6:00 PM for Fresher Legs)"
            TimeOfDaySlot.AFTERNOON -> "5:30 – 6:30 PM Recommended (Avoid Afternoon Heat)"
            null -> "5:30 – 6:30 PM (Peak Body Warmth & Lung Capacity)"
        }

        // 2. CAPABILITY, SPLITS & BIOMECHANICS ENGINE
        val trials1600 = validRuns.filter {
            it.mode == RunMode.TIMED_1600M || it.mode == RunMode.MANUAL_LOG || abs(it.distanceMeters - 1600.0) <= 80.0
        }
        val bestTrial = trials1600.minByOrNull { it.equiv1600mSec }
        val latestTrial = trials1600.maxByOrNull { it.startedAtEpochMs }
        val officialPbSec = bestTrial?.equiv1600mSec
        val movingPbSec = validRuns.minOfOrNull { r ->
            val pureMoveSec = (r.activeDurationSec - r.pausedDurationSec).coerceAtLeast(r.activeDurationSec * 0.55)
            TsPoliceBenchmark.computeEquiv1600mSec(r.distanceMeters, pureMoveSec, null)
        }
        val capabilitySec = listOfNotNull(officialPbSec, movingPbSec).minOrNull()
        val qualified = officialPbSec != null && officialPbSec <= targetTimeSec
        val hasNonstop1600 = trials1600.any { it.pausedDurationSec < 25.0 && it.walkingSec < 30.0 }

        val curLapSec = (capabilitySec ?: 600.0) / 4.0
        val recommendedIntervalLapSec = when {
            curLapSec <= targetLapSec + 4.0 -> (targetLapSec - 2.0).coerceAtLeast(92.0)
            else -> (targetLapSec + (curLapSec - targetLapSec) * 0.35).coerceIn(targetLapSec, 134.0)
        }
        val recommendedIntervalReps = when {
            validRuns.size <= 4 -> 5
            curLapSec > targetLapSec + 18.0 -> 5
            else -> 6
        }

        // Easy talk-pace target based on current capability (~28% slower per km than 1.6K race pace, floored at 6:05/km)
        val raceSecPerKm = (capabilitySec ?: 540.0) / 1.6
        val easyPaceLowSecKm = max(raceSecPerKm * 1.16, 365.0)
        val easyPaceHighSecKm = max(easyPaceLowSecKm + 30.0, 405.0)
        val easyPaceRangeStr = "${formatMinSecPlain(easyPaceLowSecKm)}–${formatMinSecPlain(easyPaceHighSecKm)} /km"

        // 3. WORKLOAD, CONSECUTIVE DAYS & RECOVERY DETECTION
        val todaySummary = dailyMap[todayIso] ?: DailyActivitySummary(dateIso = todayIso)
        val yesterdayIso = todayDate.minusDays(1).toString()
        val twoDaysAgoIso = todayDate.minusDays(2).toString()

        val todayRuns = validRuns.filter { it.dateIso == todayIso }
        val yesterdayRuns = validRuns.filter { it.dateIso == yesterdayIso }
        val twoDaysAgoRuns = validRuns.filter { it.dateIso == twoDaysAgoIso }

        val runsLast14d = validRuns.count {
            val d = runCatching { LocalDate.parse(it.dateIso) }.getOrNull()
            d != null && !d.isAfter(todayDate) && d.isAfter(todayDate.minusDays(14))
        }
        val earlyMorningRuns14d = enrichedRuns.count {
            val d = runCatching { LocalDate.parse(it.record.dateIso) }.getOrNull()
            d != null && !d.isAfter(todayDate) && d.isAfter(todayDate.minusDays(14)) &&
                it.slot == TimeOfDaySlot.EARLY_MORNING
        }

        // Count consecutive run days immediately preceding today
        var consecutiveDaysBeforeToday = 0
        for (back in 1..6) {
            val iso = todayDate.minusDays(back.toLong()).toString()
            val hadRun = validRuns.any { it.dateIso == iso } ||
                ((dailyMap[iso]?.jogRunCombinedMeters ?: 0.0) >= 900.0)
            if (hadRun) consecutiveDaysBeforeToday++ else break
        }

        fun isHardRun(r: RunSessionRecord): Boolean =
            r.mode == RunMode.TIMED_1600M ||
                r.mode == RunMode.INTERVAL_400M ||
                r.distanceMeters >= 1400.0 ||
                r.runningSec >= 120.0

        val todayQualityDone = todayRuns.any { it.distanceMeters >= 600.0 } ||
            todaySummary.jogRunCombinedMeters >= 1100.0
        val yesterdayWasHard = yesterdayRuns.any { isHardRun(it) } ||
            ((dailyMap[yesterdayIso]?.runningSec ?: 0.0) >= 150.0)
        val twoHardInLast3Days = (yesterdayRuns.count { isHardRun(it) } + twoDaysAgoRuns.count { isHardRun(it) }) >= 2

        val todayStepsNow = max(todaySummary.totalSteps, samsungMap[todayIso]?.steps ?: 0)
        val highFootFatigueToday = todayStepsNow >= 8500 && !todayQualityDone

        // Effective leg state combining manual 1-tap check-in + automatic sensor fatigue
        val needsFullRest = legFeel == LegFeelCheckIn.SORE_SHINS || consecutiveDaysBeforeToday >= 4
        val needsEasyDay = legFeel == LegFeelCheckIn.TIRED ||
            yesterdayWasHard ||
            twoHardInLast3Days ||
            consecutiveDaysBeforeToday >= 3 ||
            highFootFatigueToday

        // 4. DECIDE TODAY'S WORKOUT KIND (Plain, Smart Periodization)
        val workoutKind = when {
            legFeel == LegFeelCheckIn.SORE_SHINS -> CoachWorkoutKind.REST_AND_REPAIR
            daysRemaining == 0 && !todayQualityDone -> CoachWorkoutKind.EXAM_DAY_RACE
            todayQualityDone -> CoachWorkoutKind.TODAY_DONE_RECOVER_TONIGHT
            daysRemaining in 1..2 -> CoachWorkoutKind.SHARPENER_TAPER
            needsFullRest -> CoachWorkoutKind.REST_AND_REPAIR
            needsEasyDay -> CoachWorkoutKind.EASY_TALK_PACE_JOG
            !hasNonstop1600 -> CoachWorkoutKind.NONSTOP_1600M_TEST
            else -> {
                // Alternate Fast 400m Speed Laps (2x/wk) with Nonstop 1.6 km Time Check (1x every ~6 days)
                val daysSinceLast1600Trial = latestTrial?.let {
                    val ld = runCatching { LocalDate.parse(it.dateIso) }.getOrNull()
                    if (ld != null) (todayDate.toEpochDay() - ld.toEpochDay()).toInt() else 99
                } ?: 99
                if (daysSinceLast1600Trial >= 6 && daysRemaining >= 5) {
                    CoachWorkoutKind.NONSTOP_1600M_TEST
                } else {
                    CoachWorkoutKind.SPEED_400M_REPS
                }
            }
        }

        // 5. DECIDE 1 SESSION (EVENING) VS MORNING + EVENING (2 SESSIONS)
        val shouldRehearseMorningToday = !todayQualityDone &&
            expectedExamBatch == ExamTimeBatch.MORNING_GROUND &&
            daysRemaining in 4..24 &&
            earlyMorningRuns14d == 0 &&
            workoutKind in setOf(CoachWorkoutKind.EASY_TALK_PACE_JOG, CoachWorkoutKind.NONSTOP_1600M_TEST) &&
            todayDate.dayOfWeek.value == 7 // Sunday morning rehearsal

        val canUnlockOptionalLightDouble = !todayQualityDone &&
            runsLast14d >= 8 &&
            legFeel == LegFeelCheckIn.FRESH &&
            !yesterdayWasHard &&
            todayStepsNow < 5500 &&
            daysRemaining in 7..21 &&
            workoutKind == CoachWorkoutKind.SPEED_400M_REPS

        val scheduleMode = when {
            workoutKind == CoachWorkoutKind.TODAY_DONE_RECOVER_TONIGHT -> DailySessionScheduleMode.TODAY_RUN_COMPLETED
            workoutKind == CoachWorkoutKind.REST_AND_REPAIR -> DailySessionScheduleMode.ZERO_RUNS_RECOVERY
            shouldRehearseMorningToday -> DailySessionScheduleMode.ONE_MORNING_EXAM_REHEARSAL
            canUnlockOptionalLightDouble -> DailySessionScheduleMode.LIGHT_MORNING_PLUS_EVENING
            else -> DailySessionScheduleMode.ONE_EVENING_SESSION
        }

        val (scheduleHeadline, scheduleExplanation) = buildScheduleAdvice(
            mode = scheduleMode,
            runsLast14d = runsLast14d,
            bestSlotText = bestWindowText,
            expectedExamBatch = expectedExamBatch,
            earlyMorningRuns14d = earlyMorningRuns14d,
            daysRemaining = daysRemaining,
            typicalWakeHour = typicalWakeHour
        )

        // 6. PERSONALIZED BIOMECHANICS, SPLIT PACING & STEP-FATIGUE INSIGHTS
        val personalInsights = buildPersonalInsights(
            enrichedRuns = enrichedRuns,
            latestTrial = latestTrial,
            bestTrial = bestTrial,
            targetTimeSec = targetTimeSec,
            typicalWakeHour = typicalWakeHour,
            runnerHeightCm = runnerHeightCm
        )

        // 7. PERSONALIZED NUTRITION, CARBS, PROTEIN & HYDRATION TIP
        val fuelTip = buildFuelAndRecoveryTip(weightKg = weightKg, workoutKind = workoutKind)

        // 8. BUILD ZERO-JARGON "WHAT TO DO" & "WHAT NOT TO DO"
        val totalDaysTracked = (dailyMap.keys + samsungMap.keys + validRuns.map { it.dateIso }).distinct().size
        val totalSplitsCount = validRuns.sumOf { it.lapSplits.size }
        val confidenceLabel = when {
            validRuns.size >= 8 -> "High Precision (${validRuns.size} Runs)"
            validRuns.size >= 3 -> "Active Learning (${validRuns.size} Runs + ${max(totalDaysTracked, 1)}d Activity)"
            validRuns.isNotEmpty() -> "Calibrating (${validRuns.size} Run${if (validRuns.size == 1) "" else "s"} Logged)"
            else -> "Ready for Baseline Run"
        }
        val dataPointsSummary =
            "Brain analyzed ${validRuns.size} run${if (validRuns.size == 1) "" else "s"}, " +
                "$totalSplitsCount track lap splits, ${max(totalDaysTracked, 1)} days of step load & ${hourlyBuckets.size} hourly sensor buckets."

        val repLapFmt = TsPoliceBenchmark.formatDuration(recommendedIntervalLapSec)
        val bestRunToday = todayRuns.minByOrNull { it.equiv1600mSec }

        return when (workoutKind) {
            CoachWorkoutKind.TODAY_DONE_RECOVER_TONIGHT -> {
                val todayTimeStr = bestRunToday?.let { TsPoliceBenchmark.formatDuration(it.equiv1600mSec) }
                val ranHardToday = todayRuns.any { isHardRun(it) }
                val headline = if (todayTimeStr != null && abs(bestRunToday.distanceMeters - 1600.0) <= 80.0) {
                    "Today's $todayTimeStr Run Is Done! Rest Tonight → Slow Jog Tomorrow"
                } else {
                    "Today's Run Is Done! Recover Tonight"
                }
                val why = if (ranHardToday) {
                    "You worked your legs hard today${if (todayTimeStr != null) " ($todayTimeStr)" else ""}. " +
                        "Muscles don't get faster during the run — they get faster tonight while repairing! " +
                        "Tomorrow must be a slow, relaxed jog so your shins stay pain-free."
                } else {
                    "You completed your movement session today. Rest your legs tonight so you have fresh bounce for tomorrow evening's speed session."
                }
                val tomorrowPreview = if (ranHardToday) {
                    "Tomorrow evening (${bestSlotClockShort(bestSlot)}, 1 session only): Jog slowly for 15–20 mins (~2 km at $easyPaceRangeStr) — slow enough that you can talk in full sentences without gasping."
                } else {
                    "Tomorrow evening (${bestSlotClockShort(bestSlot)}, 1 session only): You'll be fresh for $recommendedIntervalReps × 400m fast laps (~$repLapFmt per lap)."
                }
                CoachBrainReport(
                    confidenceLabel = confidenceLabel,
                    dataPointsSummary = dataPointsSummary,
                    workoutKind = workoutKind,
                    effortBadgePlain = "DONE TODAY ✓",
                    accentKind = PushAccentKind.LIME_GO,
                    simpleHeadline = headline,
                    plainWhyExplanation = why,
                    whatToDo = listOf(
                        "Tonight: Drink 2–3 glasses of water and gently stretch your calves & shins for 5 minutes before sleep.",
                        tomorrowPreview,
                        "Sleep 7.5–8 hours tonight — growth hormone repairs calf micro-tears during deep sleep."
                    ),
                    whatNotToDo = listOf(
                        "Do NOT go for a second run tonight, and do NOT do both morning + evening runs tomorrow.",
                        if (ranHardToday) {
                            "Do NOT sprint or try to beat ${todayTimeStr ?: "your PB"} tomorrow — sprinting two days in a row causes shin splints and slows you down."
                        } else {
                            "Do NOT tire your legs with long late-night walking before tomorrow's fast session."
                        },
                        "Do NOT skip dinner protein tonight — hungry muscles recover half as fast."
                    ),
                    fuelAndHydrationTip = fuelTip,
                    scheduleMode = scheduleMode,
                    scheduleHeadline = scheduleHeadline,
                    scheduleExplanation = scheduleExplanation,
                    bestTimeSlot = bestSlot,
                    bestTimeWindowLabel = bestWindowText,
                    timingVariationSummary = timingResult.variationSummary,
                    slotStats = timingResult.orderedStats,
                    personalInsights = personalInsights,
                    recommendedIntervalLapSec = recommendedIntervalLapSec,
                    recommendedIntervalReps = recommendedIntervalReps,
                    recommendedAction = RecommendedWorkoutAction.DONE_FOR_TODAY,
                    recommendedActionLabel = "View Progress & Timing Stats  →"
                )
            }

            CoachWorkoutKind.REST_AND_REPAIR -> {
                val shinPain = legFeel == LegFeelCheckIn.SORE_SHINS
                CoachBrainReport(
                    confidenceLabel = confidenceLabel,
                    dataPointsSummary = dataPointsSummary,
                    workoutKind = workoutKind,
                    effortBadgePlain = "REST & HEAL",
                    accentKind = PushAccentKind.PURPLE_RECOVERY,
                    simpleHeadline = if (shinPain) {
                        "Shin & Calf Protection Day — Zero Running Today"
                    } else {
                        "Full Leg Repair Day — Zero Running Today"
                    },
                    plainWhyExplanation = if (shinPain) {
                        "You flagged shin/calf soreness. Running through shin pain turns a 2-day soreness into a 2-week injury right before your $deadlineIso exam!"
                    } else {
                        "You've stacked $consecutiveDaysBeforeToday active days in a row. Taking 1 complete rest day lets your tendons and calves rebuild stronger."
                    },
                    whatToDo = listOf(
                        if (shinPain) {
                            "Ice the front of your shins for 10 minutes twice today and do slow ankle circles while sitting."
                        } else {
                            "Give your legs a full break from running today — normal easy walking (< 5,000 steps) and 5 mins of calf stretching only."
                        },
                        "Drink 2.5–3 liters of water across the day so your muscles stay loose.",
                        "Go to sleep 30 minutes earlier tonight so your legs feel bouncy tomorrow evening."
                    ),
                    whatNotToDo = listOf(
                        "Do NOT run, sprint, or jump today — neither in the morning nor in the evening.",
                        "Do NOT test your shins by hopping on hard concrete or asphalt.",
                        "Do NOT cut calories drastically today — keep protein high so tissue heals quickly."
                    ),
                    fuelAndHydrationTip = fuelTip,
                    scheduleMode = scheduleMode,
                    scheduleHeadline = scheduleHeadline,
                    scheduleExplanation = scheduleExplanation,
                    bestTimeSlot = bestSlot,
                    bestTimeWindowLabel = bestWindowText,
                    timingVariationSummary = timingResult.variationSummary,
                    slotStats = timingResult.orderedStats,
                    personalInsights = personalInsights,
                    recommendedIntervalLapSec = recommendedIntervalLapSec,
                    recommendedIntervalReps = recommendedIntervalReps,
                    recommendedAction = RecommendedWorkoutAction.DONE_FOR_TODAY,
                    recommendedActionLabel = "View Timing & Recovery Stats  →"
                )
            }

            CoachWorkoutKind.EASY_TALK_PACE_JOG -> {
                val whyReason = when {
                    legFeel == LegFeelCheckIn.TIRED ->
                        "You marked your legs as tired today. A slow, gentle jog loosens stiff muscles without draining energy."
                    yesterdayWasHard ->
                        "Yesterday was a hard speed/trial effort. Running hard two days in a row causes shin pain — today's slow jog flushes soreness so you can run fast tomorrow!"
                    highFootFatigueToday ->
                        "You already walked $todayStepsNow steps today, so your calves are pre-tired. Keep this evening's run slow and comfortable."
                    else ->
                        "Easy endurance day: building steady heart & lung stamina makes Laps 3 and 4 of your 1.6 km test feel effortless."
                }
                val timeHint = if (scheduleMode == DailySessionScheduleMode.ONE_MORNING_EXAM_REHEARSAL) {
                    "6:30–7:30 AM (1 morning run only, rest in evening)"
                } else {
                    "${bestSlotClockShort(bestSlot)} (1 evening session only)"
                }
                CoachBrainReport(
                    confidenceLabel = confidenceLabel,
                    dataPointsSummary = dataPointsSummary,
                    workoutKind = workoutKind,
                    effortBadgePlain = "EASY SLOW JOG",
                    accentKind = PushAccentKind.PURPLE_RECOVERY,
                    simpleHeadline = "Slow, Relaxed 18–20 Min Jog (Talk-Test Pace)",
                    plainWhyExplanation = whyReason,
                    whatToDo = listOf(
                        "Go at $timeHint: Jog slowly nonstop for 18–20 minutes (~2.2 to 2.5 km around $easyPaceRangeStr).",
                        "Use the simple Talk Test: Breathe smoothly and keep your speed slow enough that you can speak a full sentence aloud without gasping.",
                        "Finish with 5 minutes of slow walking + calf & shin stretches."
                    ),
                    whatNotToDo = listOf(
                        "Do NOT sprint, race the stopwatch, or try a fast 1.6 km time test today.",
                        "Do NOT run twice today (skip morning + evening doubles so your shins stay fresh).",
                        "Do NOT stop completely if you feel winded — just shorten your steps into a tiny slow shuffle and keep moving."
                    ),
                    fuelAndHydrationTip = fuelTip,
                    scheduleMode = scheduleMode,
                    scheduleHeadline = scheduleHeadline,
                    scheduleExplanation = scheduleExplanation,
                    bestTimeSlot = bestSlot,
                    bestTimeWindowLabel = bestWindowText,
                    timingVariationSummary = timingResult.variationSummary,
                    slotStats = timingResult.orderedStats,
                    personalInsights = personalInsights,
                    recommendedIntervalLapSec = recommendedIntervalLapSec,
                    recommendedIntervalReps = recommendedIntervalReps,
                    recommendedAction = RecommendedWorkoutAction.EASY_AEROBIC_JOG,
                    recommendedActionLabel = "Start Easy 20-Min Jog (Today Tab Tracks Auto)"
                )
            }

            CoachWorkoutKind.SPEED_400M_REPS -> {
                val why = if (qualified) {
                    "You already beat $targetFmt! Crisp 400m speed laps keep your legs sharp without wearing you out."
                } else {
                    "Your legs are fresh after recovery! Running $recommendedIntervalReps separate 400m laps at ~$repLapFmt trains your legs to spin faster (168+ steps/min) toward your $targetFmt ($targetLapFmt/lap) goal without burning out."
                }
                CoachBrainReport(
                    confidenceLabel = confidenceLabel,
                    dataPointsSummary = dataPointsSummary,
                    workoutKind = workoutKind,
                    effortBadgePlain = "FAST 400M LAPS",
                    accentKind = PushAccentKind.ORANGE_HARD,
                    simpleHeadline = "Run $recommendedIntervalReps × 400m Fast Laps (~$repLapFmt per Lap)",
                    plainWhyExplanation = why,
                    whatToDo = listOf(
                        "Warm up first (${bestSlotClockShort(bestSlot)}): 5 mins brisk walk + light jog + leg swings before sprinting.",
                        "Open '400m Coach' and run $recommendedIntervalReps laps of 400m aiming for ~$repLapFmt each, walking slowly for 90 seconds between laps.",
                        "Take quick, light steps (~168–172 steps/min) and pump your elbows backward — don't reach forward with heavy stomps."
                    ),
                    whatNotToDo = listOf(
                        "Do NOT go all-out 100% sprint on Rep 1 — run Rep 1 controlled at ~$repLapFmt so Rep $recommendedIntervalReps is just as fast!",
                        "Do NOT sit down on the ground during the 90-second rest — keep walking slowly so your calves don't cramp.",
                        "Do NOT walk more than ~5,500 steps during the afternoon before this evening speed session."
                    ),
                    fuelAndHydrationTip = fuelTip,
                    scheduleMode = scheduleMode,
                    scheduleHeadline = scheduleHeadline,
                    scheduleExplanation = scheduleExplanation,
                    bestTimeSlot = bestSlot,
                    bestTimeWindowLabel = bestWindowText,
                    timingVariationSummary = timingResult.variationSummary,
                    slotStats = timingResult.orderedStats,
                    personalInsights = personalInsights,
                    recommendedIntervalLapSec = recommendedIntervalLapSec,
                    recommendedIntervalReps = recommendedIntervalReps,
                    recommendedAction = RecommendedWorkoutAction.START_400M_INTERVALS,
                    recommendedActionLabel = "Open $recommendedIntervalReps × 400m Speed Coach (~$repLapFmt)  →"
                )
            }

            CoachWorkoutKind.NONSTOP_1600M_TEST -> {
                val goalThisRunSec = if (capabilitySec != null) {
                    max(targetTimeSec, capabilitySec - 15.0)
                } else {
                    max(targetTimeSec, 540.0)
                }
                val lapAimFmt = TsPoliceBenchmark.formatDuration(goalThisRunSec / 4.0)
                val finishAimFmt = TsPoliceBenchmark.formatDuration(goalThisRunSec)
                val slotHint = if (scheduleMode == DailySessionScheduleMode.ONE_MORNING_EXAM_REHEARSAL) {
                    "6:30–7:30 AM (Morning Exam Dress Rehearsal — 1 session today)"
                } else {
                    "${bestSlotClockShort(bestSlot)} (1 evening session today)"
                }
                CoachBrainReport(
                    confidenceLabel = confidenceLabel,
                    dataPointsSummary = dataPointsSummary,
                    workoutKind = workoutKind,
                    effortBadgePlain = "1.6 KM TIME TEST",
                    accentKind = PushAccentKind.ORANGE_HARD,
                    simpleHeadline = "Run 1 Full Nonstop 1.6 km Time Check (Aim ~$finishAimFmt)",
                    plainWhyExplanation = if (!hasNonstop1600) {
                        "Your top priority right now is finishing all 4 laps (1,600m) without stopping to walk — eliminating stop breaks drops minutes immediately!"
                    } else {
                        "Your legs are rested and it's time to measure your new 1.6 km speed! Aim for steady ~$lapAimFmt laps with zero stops."
                    },
                    whatToDo = listOf(
                        "Run at $slotHint: Warm up 5 minutes (light jog + ankle bounces) so Lap 1 doesn't shock your lungs.",
                        "Hold steady ~$lapAimFmt on Laps 1, 2, and 3, then empty the tank and sprint the final 200 meters!",
                        "Keep your arms relaxed and step quickly when Lap 3 feels tough."
                    ),
                    whatNotToDo = listOf(
                        "Do NOT sprint the first 200 meters like a 100m race — starting too fast floods your legs with burn by 800m!",
                        "Do NOT stop or stand on the track (the official TSLPRB RFID clock never pauses).",
                        "Do NOT eat a heavy meal within 2.5 hours before this test (only 1 banana + water 60–90 mins prior)."
                    ),
                    fuelAndHydrationTip = fuelTip,
                    scheduleMode = scheduleMode,
                    scheduleHeadline = scheduleHeadline,
                    scheduleExplanation = scheduleExplanation,
                    bestTimeSlot = bestSlot,
                    bestTimeWindowLabel = bestWindowText,
                    timingVariationSummary = timingResult.variationSummary,
                    slotStats = timingResult.orderedStats,
                    personalInsights = personalInsights,
                    recommendedIntervalLapSec = recommendedIntervalLapSec,
                    recommendedIntervalReps = recommendedIntervalReps,
                    recommendedAction = RecommendedWorkoutAction.ARM_1600M_TRIAL,
                    recommendedActionLabel = "Arm 1.6 km PT Trial  →"
                )
            }

            CoachWorkoutKind.SHARPENER_TAPER -> {
                CoachBrainReport(
                    confidenceLabel = confidenceLabel,
                    dataPointsSummary = dataPointsSummary,
                    workoutKind = workoutKind,
                    effortBadgePlain = "FRESHEN LEGS",
                    accentKind = PushAccentKind.PURPLE_RECOVERY,
                    simpleHeadline = "Exam Taper ($daysRemaining Day${if (daysRemaining == 1) "" else "s"} Left) — Keep Legs Springy!",
                    plainWhyExplanation = "With only $daysRemaining day${if (daysRemaining == 1) "" else "s"} until $deadlineIso, hard workouts cannot add new fitness — they only tire your legs! Today is all about storing 100% energy.",
                    whatToDo = listOf(
                        "Do only 1 short 8-minute slow jog + 2 × 80m smooth fast strides to keep your legs awake.",
                        "Stay off your feet as much as possible (< 4,000 steps all day) and sleep 8+ hours tonight.",
                        "Sip water steadily all day and eat familiar, clean high-carb home meals (rice, roti, banana, curd)."
                    ),
                    whatNotToDo = listOf(
                        "Do NOT test a full 1.6 km run or hard 400m intervals today — save 100% of that fire for Exam Day!",
                        "Do NOT try spicy street food, heavy fried fats, or brand-new shoes right before your test.",
                        "Do NOT do heavy leg gym squats or long calf stretches that leave muscles slack."
                    ),
                    fuelAndHydrationTip = fuelTip,
                    scheduleMode = scheduleMode,
                    scheduleHeadline = scheduleHeadline,
                    scheduleExplanation = scheduleExplanation,
                    bestTimeSlot = bestSlot,
                    bestTimeWindowLabel = bestWindowText,
                    timingVariationSummary = timingResult.variationSummary,
                    slotStats = timingResult.orderedStats,
                    personalInsights = personalInsights,
                    recommendedIntervalLapSec = recommendedIntervalLapSec,
                    recommendedIntervalReps = recommendedIntervalReps,
                    recommendedAction = RecommendedWorkoutAction.EASY_AEROBIC_JOG,
                    recommendedActionLabel = "Keep Legs Fresh Today"
                )
            }

            CoachWorkoutKind.EXAM_DAY_RACE -> {
                CoachBrainReport(
                    confidenceLabel = confidenceLabel,
                    dataPointsSummary = dataPointsSummary,
                    workoutKind = workoutKind,
                    effortBadgePlain = "EXAM DAY • GO!",
                    accentKind = PushAccentKind.LIME_GO,
                    simpleHeadline = "Exam Day Game Plan: Beat $targetFmt ($targetLapFmt per Lap)!",
                    plainWhyExplanation = "All your training is banked. Run smart on Lap 1, hold quick steps on Laps 2–3, and give everything you have in the last 250 meters!",
                    whatToDo = listOf(
                        "2 hours before reporting: Eat 1–2 bananas or white bread + honey/jam + 300 ml water (fast energy, zero stomach heaviness).",
                        "12 mins before start line: 4-min light jog + ankle bounces + 2 short 50m pickups so your lungs are open from meter 1.",
                        "Race Plan: Lap 1 controlled at $targetLapFmt → Laps 2 & 3 lock quick 170+ steps/min → Final 250m ALL-OUT SPRINT!"
                    ),
                    whatNotToDo = listOf(
                        "Do NOT get pulled into a crazy 1:30 Lap 1 sprint by nervous runners at the gun — pass them on Lap 3!",
                        "Do NOT drink gulps of water right in the final 20 minutes before the whistle (just wet your mouth).",
                        "Do NOT look back on Lap 4 — lock your eyes on the finish line and drive your arms!"
                    ),
                    fuelAndHydrationTip = fuelTip,
                    scheduleMode = DailySessionScheduleMode.ONE_MORNING_EXAM_REHEARSAL,
                    scheduleHeadline = "Exam Day — 100% Focus on Your Official 1.6 km Run",
                    scheduleExplanation = "Warm up lightly 12 minutes before your batch starts so your heart rate and muscles are ready immediately.",
                    bestTimeSlot = bestSlot,
                    bestTimeWindowLabel = bestWindowText,
                    timingVariationSummary = timingResult.variationSummary,
                    slotStats = timingResult.orderedStats,
                    personalInsights = personalInsights,
                    recommendedIntervalLapSec = targetLapSec,
                    recommendedIntervalReps = 4,
                    recommendedAction = RecommendedWorkoutAction.ARM_1600M_TRIAL,
                    recommendedActionLabel = "Launch Official 1.6 km Trial  →"
                )
            }
        }
    }

    private data class TimeWindowEngineResult(
        val bestSlot: TimeOfDaySlot?,
        val orderedStats: List<SlotPerformanceStat>,
        val variationSummary: String
    )

    private fun analyzeTimeOfDayWindows(
        enrichedRuns: List<EnrichedRun>,
        typicalWakeHour: Int?,
        expectedExamBatch: ExamTimeBatch
    ): TimeWindowEngineResult {
        val bySlot = enrichedRuns.groupBy { it.slot }

        // Compute raw performance score per populated slot (lower equiv1600mSec + lower stopSec + more 1.6K trials = better)
        data class RawSlotCalc(
            val slot: TimeOfDaySlot,
            val runs: List<EnrichedRun>,
            val trialCount: Int,
            val bestSec: Double,
            val avgSec: Double,
            val variationSec: Double?,
            val avgStopSec: Double,
            val avgPreSteps: Int?,
            val consistencyScore: Int,
            val rankMetricSec: Double
        )

        val populatedCalcs = bySlot.map { (slot, list) ->
            // Prefer exact 1600m trials when computing pace representative of 1.6K performance, falling back to all runs in slot
            val trials = list.filter {
                it.record.mode == RunMode.TIMED_1600M ||
                    it.record.mode == RunMode.MANUAL_LOG ||
                    abs(it.record.distanceMeters - 1600.0) <= 80.0
            }
            val paceSource = trials.ifEmpty { list }
            val secs = paceSource.map { it.record.equiv1600mSec }
            val bestSec = secs.minOrNull() ?: 0.0
            val avgSec = secs.average()
            val runSpread = if (secs.size >= 2) (secs.maxOrNull()!! - secs.minOrNull()!!) else null
            // If only 1 trial in slot, check internal 400m lap-to-lap variation (excluding Lap 1 warmup delta)
            val singleTrialLapSpread = if (runSpread == null) {
                val splitTrial = trials.firstOrNull { it.record.lapSplits.size >= 3 }
                splitTrial?.record?.lapSplits?.map { it.lapDurationSec }?.let { laps ->
                    (laps.maxOrNull()!! - laps.minOrNull()!!)
                }
            } else null
            val variation = runSpread ?: singleTrialLapSpread

            val avgStop = list.map { it.record.pausedDurationSec }.average()
            val preStepsList = list.mapNotNull { it.preRunSteps }
            val avgPreSteps = if (preStepsList.isNotEmpty()) preStepsList.average().roundToInt() else null

            val stopPenaltyPts = (avgStop / 5.0).roundToInt().coerceAtMost(45)
            val spreadPenaltyPts = ((variation ?: 12.0) / 3.0).roundToInt().coerceAtMost(35)
            val bonusPts = min(list.size * 4, 12)
            val consistency = (92 - stopPenaltyPts - spreadPenaltyPts + bonusPts).coerceIn(18, 99)

            // Rank metric: lower is better (best + avg blend + stop penalty)
            val rankMetric = bestSec * 0.55 + avgSec * 0.45 + avgStop * 0.6

            RawSlotCalc(
                slot = slot,
                runs = list,
                trialCount = trials.size,
                bestSec = bestSec,
                avgSec = avgSec,
                variationSec = variation,
                avgStopSec = avgStop,
                avgPreSteps = avgPreSteps,
                consistencyScore = consistency,
                rankMetricSec = rankMetric
            )
        }

        val winnerSlot = populatedCalcs.minByOrNull { it.rankMetricSec }?.slot
        val calcMap = populatedCalcs.associateBy { it.slot }

        // Always include the 4 core comparison slots the runner cares about (Early Morning, 5 PM, 6 PM, 7+ PM)
        // plus Morning / Afternoon if they ever ran in those windows!
        val slotsToShow = buildList {
            add(TimeOfDaySlot.EARLY_MORNING)
            if (calcMap.containsKey(TimeOfDaySlot.MORNING)) add(TimeOfDaySlot.MORNING)
            if (calcMap.containsKey(TimeOfDaySlot.AFTERNOON)) add(TimeOfDaySlot.AFTERNOON)
            add(TimeOfDaySlot.FIVE_PM)
            add(TimeOfDaySlot.SIX_PM)
            add(TimeOfDaySlot.LATE_EVENING)
        }

        val bestCalc = winnerSlot?.let { calcMap[it] }

        val slotStats = slotsToShow.map { slot ->
            val c = calcMap[slot]
            if (c == null) {
                val hint = when (slot) {
                    TimeOfDaySlot.EARLY_MORNING -> {
                        val wakeNote = typicalWakeHour?.let { " (you currently wake ~${formatHourOnly(it)})" } ?: ""
                        if (expectedExamBatch == ExamTimeBatch.MORNING_GROUND) {
                            "0 runs yet$wakeNote — test 1 morning run on a weekend so Morning PET ground batches feel natural."
                        } else {
                            "0 runs yet$wakeNote — optional morning slot."
                        }
                    }
                    TimeOfDaySlot.FIVE_PM ->
                        "0 runs at 5:00 PM yet — try 1 evening run around 5:00–5:30 PM this week so the Brain can compare 5 PM vs 6 PM!"
                    TimeOfDaySlot.SIX_PM ->
                        "0 runs at 6:00 PM yet — body warmth peaks 5:30–6:30 PM, great slot to test!"
                    TimeOfDaySlot.LATE_EVENING ->
                        "0 runs after 7:00 PM — earlier evening (5:30–6:30 PM) usually avoids all-day leg fatigue."
                    else -> "No runs recorded in this window yet."
                }
                SlotPerformanceStat(
                    slot = slot,
                    runCount = 0,
                    trial1600Count = 0,
                    bestEquiv1600mSec = null,
                    avgEquiv1600mSec = null,
                    variationSec = null,
                    avgStopSec = 0.0,
                    avgPreRunSteps = null,
                    consistencyScore = 0,
                    isBestSlot = false,
                    badgeLabel = "UNTESTED",
                    plainSummary = hint
                )
            } else {
                val isBest = slot == winnerSlot
                val bestFmt = TsPoliceBenchmark.formatDuration(c.bestSec)
                val avgFmt = TsPoliceBenchmark.formatDuration(c.avgSec)
                val diffVsWinnerSec = if (!isBest && bestCalc != null) {
                    (c.bestSec - bestCalc.bestSec).roundToInt()
                } else 0

                val badge = when {
                    isBest -> "★ BEST SLOT"
                    diffVsWinnerSec >= 60 || c.avgStopSec >= 45.0 -> "SLOWER (+${TsPoliceBenchmark.formatDuration(diffVsWinnerSec.toDouble())})"
                    diffVsWinnerSec > 0 -> "+${diffVsWinnerSec}s VS BEST"
                    else -> "STEADY"
                }

                val clockSamples = c.runs.takeLast(2).joinToString(", ") { it.clockLabel }
                val stopNote = if (c.avgStopSec >= 20.0) {
                    " • ${TsPoliceBenchmark.formatDuration(c.avgStopSec)} stopped"
                } else {
                    " • Nonstop"
                }
                val varNote = if (c.runs.size >= 2 && c.trialCount >= 2 && c.variationSec != null) {
                    " (±${(c.variationSec / 2.0).roundToInt()}s variation)"
                } else if (c.trialCount == 1 && c.variationSec != null) {
                    " (${c.variationSec.roundToInt()}s lap spread)"
                } else ""
                val stepNote = c.avgPreSteps?.let {
                    if (it >= 7000) " after ~$it steps" else ""
                } ?: ""

                val summary = "${c.runs.size} run${if (c.runs.size == 1) "" else "s"} ($clockSamples) • " +
                    (if (c.trialCount >= 2) "Best $bestFmt / Avg $avgFmt" else "Best $bestFmt") +
                    "$varNote$stopNote$stepNote."

                SlotPerformanceStat(
                    slot = slot,
                    runCount = c.runs.size,
                    trial1600Count = c.trialCount,
                    bestEquiv1600mSec = c.bestSec,
                    avgEquiv1600mSec = c.avgSec,
                    variationSec = c.variationSec,
                    avgStopSec = c.avgStopSec,
                    avgPreRunSteps = c.avgPreSteps,
                    consistencyScore = c.consistencyScore,
                    isBestSlot = isBest,
                    badgeLabel = badge,
                    plainSummary = summary
                )
            }
        }

        val ranked = populatedCalcs.sortedBy { it.rankMetricSec }
        val variationSummary = when {
            ranked.isEmpty() -> {
                "No clock-timed runs yet. Start around 5:30–6:30 PM when body warmth peaks, and the Brain will compare your pace across Early Morning, 5:00 PM, and 6:00 PM!"
            }
            ranked.size == 1 -> {
                val only = ranked.first()
                val bestFmt = TsPoliceBenchmark.formatDuration(only.bestSec)
                val otherEvening = if (only.slot == TimeOfDaySlot.SIX_PM) "5:00 PM" else "6:00 PM"
                "All ${only.runs.size} run${if (only.runs.size == 1) "" else "s"} so far are in the ${only.slot.title} (best $bestFmt). Try 1 run around $otherEvening this week so the Brain can see which hour gives you steadier, faster splits!"
            }
            else -> {
                val top = ranked.first()
                val second = ranked[1]
                val slowest = ranked.last()
                val topBest = TsPoliceBenchmark.formatDuration(top.bestSec)
                val slowBest = TsPoliceBenchmark.formatDuration(slowest.bestSec)
                val gapSlow = (slowest.bestSec - top.bestSec).roundToInt()
                val fiveVsSixNote = if (calcMap.containsKey(TimeOfDaySlot.FIVE_PM) && calcMap.containsKey(TimeOfDaySlot.SIX_PM)) {
                    val c5 = calcMap.getValue(TimeOfDaySlot.FIVE_PM)
                    val c6 = calcMap.getValue(TimeOfDaySlot.SIX_PM)
                    val d = (c5.avgSec - c6.avgSec).roundToInt()
                    when {
                        abs(d) <= 4 -> " Both 5:00 PM and 6:00 PM perform nearly identically (${abs(d)}s apart)."
                        d > 0 -> " 6:00 PM beats 5:00 PM by ~${d}s on average."
                        else -> " 5:00 PM beats 6:00 PM by ~${abs(d)}s on average."
                    }
                } else if (!calcMap.containsKey(TimeOfDaySlot.FIVE_PM) && calcMap.containsKey(TimeOfDaySlot.SIX_PM)) {
                    " (5:00 PM not tested yet — try 1 run at 5:00–5:30 PM to compare with 6:00 PM!)"
                } else ""

                if (gapSlow > 0) {
                    "You run fastest & most consistently in the ${top.slot.title} (best $topBest, ${top.consistencyScore}% consistency) vs $slowBest in the ${slowest.slot.title} (+${TsPoliceBenchmark.formatDuration(gapSlow.toDouble())} slower).$fiveVsSixNote"
                } else {
                    "Your ${top.slot.title} and ${second.slot.title} are neck-and-neck around $topBest.$fiveVsSixNote"
                }
            }
        }

        return TimeWindowEngineResult(
            bestSlot = winnerSlot,
            orderedStats = slotStats,
            variationSummary = variationSummary
        )
    }

    private fun buildScheduleAdvice(
        mode: DailySessionScheduleMode,
        runsLast14d: Int,
        bestSlotText: String,
        expectedExamBatch: ExamTimeBatch,
        earlyMorningRuns14d: Int,
        daysRemaining: Int,
        typicalWakeHour: Int?
    ): Pair<String, String> {
        val morningExamNote = if (expectedExamBatch == ExamTimeBatch.MORNING_GROUND && earlyMorningRuns14d == 0 && daysRemaining > 3) {
            val wakeStr = typicalWakeHour?.let { " (you currently wake around ${formatHourOnly(it)})" } ?: ""
            " Once a week on the weekend, move your 1 run to 6:30–7:30 AM$wakeStr (and skip evening that day) so Morning Police PET ground batches feel easy!"
        } else ""

        return when (mode) {
            DailySessionScheduleMode.TODAY_RUN_COMPLETED ->
                "1 Session Today Is Complete — Rest Tonight (Skip Doubles)" to
                    "Your workout for today is already done! Do NOT add a second run tonight or tomorrow morning — 1 focused evening session ($bestSlotText) gives maximum speed gain while keeping shins injury-free.$morningExamNote"

            DailySessionScheduleMode.ONE_EVENING_SESSION ->
                "1 Evening Session Is Enough — Skip Morning + Evening Doubles" to
                    "With $runsLast14d run${if (runsLast14d == 1) "" else "s"} in the last 14 days, 1 quality evening session around $bestSlotText is 100% enough. Running hard twice a day (morning + evening) triples shin-splint risk and leaves your legs flat. Optional morning habit: 5 mins ankle mobility & calf raises ONLY — zero morning running!$morningExamNote"

            DailySessionScheduleMode.ONE_MORNING_EXAM_REHEARSAL ->
                "1 Morning Session Today (6:30–7:30 AM) — Skip Evening Run" to
                    "Still ONLY 1 run today — just do it in the morning (6:30–7:30 AM) and rest in the evening! TSLPRB PET ground tests usually happen in the early morning, so practicing 1 morning run per week teaches your lungs and legs to fire early."

            DailySessionScheduleMode.LIGHT_MORNING_PLUS_EVENING ->
                "Main Session in Evening + Optional 10-Min Slow Morning Shakeout" to
                    "Your 2-week base is strong and legs are fresh: your real speed workout stays in the evening ($bestSlotText). Morning is strictly optional — at most a super-slow 10-min wake-up jog (never two fast runs in one day!)."

            DailySessionScheduleMode.ZERO_RUNS_RECOVERY ->
                "0 Runs Today — Neither Morning Nor Evening" to
                    "Skip both morning and evening runs today so micro-stress in your shins, calves, and ankles heals completely before tomorrow's session."
        }
    }

    private fun buildPersonalInsights(
        enrichedRuns: List<EnrichedRun>,
        latestTrial: RunSessionRecord?,
        bestTrial: RunSessionRecord?,
        targetTimeSec: Double,
        typicalWakeHour: Int?,
        runnerHeightCm: Int
    ): List<String> {
        val insights = mutableListOf<String>()

        // 1. Time-of-day comparison insight (5 PM vs 6 PM vs Late Evening vs Morning)
        if (enrichedRuns.isNotEmpty()) {
            val bySlot = enrichedRuns.groupBy { it.slot }
            val slotBests = bySlot.mapValues { (_, list) ->
                val trials = list.filter {
                    it.record.mode == RunMode.TIMED_1600M ||
                        it.record.mode == RunMode.MANUAL_LOG ||
                        abs(it.record.distanceMeters - 1600.0) <= 80.0
                }
                (trials.ifEmpty { list }).minBy { it.record.equiv1600mSec }
            }
            val fastestEntry = slotBests.minByOrNull { it.value.record.equiv1600mSec }
            val slowestEntry = slotBests.maxByOrNull { it.value.record.equiv1600mSec }

            if (fastestEntry != null && slowestEntry != null && fastestEntry.key != slowestEntry.key) {
                val diff = (slowestEntry.value.record.equiv1600mSec - fastestEntry.value.record.equiv1600mSec).roundToInt()
                val fastFmt = TsPoliceBenchmark.formatDuration(fastestEntry.value.record.equiv1600mSec)
                val slowFmt = TsPoliceBenchmark.formatDuration(slowestEntry.value.record.equiv1600mSec)
                insights.add(
                    "Clock-Time Gap Found: You ran $fastFmt in the ${fastestEntry.key.title} (${fastestEntry.value.clockLabel}) vs $slowFmt in the ${slowestEntry.key.title} (${slowestEntry.value.clockLabel}) — a ${TsPoliceBenchmark.formatDuration(diff.toDouble())} difference! Stick to ${fastestEntry.key.clockRange} for speed runs."
                )
            } else if (fastestEntry != null) {
                val fastFmt = TsPoliceBenchmark.formatDuration(fastestEntry.value.record.equiv1600mSec)
                insights.add(
                    "Current Best Clock Window: ${fastestEntry.key.title} (${fastestEntry.value.clockLabel}, $fastFmt). Try 1 run at 5:00–5:30 PM this week so the Brain can compare 5 PM vs 6 PM directly!"
                )
            }
        }

        // 2. 4x400m Split Pacing Curve Diagnosis
        val splitRun = latestTrial?.takeIf { it.lapSplits.size >= 3 }
            ?: bestTrial?.takeIf { it.lapSplits.size >= 3 }
        if (splitRun != null) {
            val laps = splitRun.lapSplits.take(4)
            val l1 = laps.first().lapDurationSec
            val lLast = laps.last().lapDurationSec
            val midAvg = if (laps.size >= 3) laps.drop(1).map { it.lapDurationSec }.average() else lLast
            val lapListStr = laps.joinToString(" → ") { TsPoliceBenchmark.formatDuration(it.lapDurationSec) }
            when {
                l1 >= midAvg + 8.0 && abs(laps.last().lapDurationSec - laps[1].lapDurationSec) <= 8.0 -> {
                    insights.add(
                        "Smart Pacing Discipline ($lapListStr): You started Lap 1 controlled and sped up smoothly with zero late crash! Next trial, trim just 8–10s off Lap 1 after a good 5-min warm-up."
                    )
                }
                lLast - l1 >= 18.0 -> {
                    insights.add(
                        "Lap Burnout Detected ($lapListStr): Lap 4 was ${(lLast - l1).roundToInt()}s slower than Lap 1! Hold back slightly on the first 300m so your legs don't lock up after 800m."
                    )
                }
                else -> {
                    insights.add(
                        "Steady Lap Rhythm ($lapListStr): Even split control! Shaving ~${((splitRun.equiv1600mSec - targetTimeSec).coerceAtLeast(4.0) / 4.0).roundToInt()}s per 400m lap reaches ${TsPoliceBenchmark.formatDuration(targetTimeSec)}."
                    )
                }
            }
        }

        // 3. Step Rate (Cadence) Biomechanics Unlock
        val refRun = bestTrial ?: enrichedRuns.lastOrNull()?.record
        if (refRun != null && refRun.avgCadenceSpm > 100.0) {
            val cad = refRun.avgCadenceSpm.roundToInt()
            if (cad < 164) {
                insights.add(
                    "Biggest Speed Unlock — Quicker Steps ($cad → 168 steps/min): At ${runnerHeightCm}cm height, taking quicker, lighter steps (~168–172 steps/min) instead of heavy long bounds shaves 25–35s off 1.6 km with less shin impact!"
                )
            } else {
                insights.add(
                    "Strong Step Turnover ($cad steps/min): Your foot speed is already near Police PET race rhythm (168+ steps/min) — pair it with strong backward elbow drive on Laps 3–4."
                )
            }
        }

        // 4. Daytime Walking Step Fatigue Correlation
        val runsWithSteps = enrichedRuns.filter { (it.dayTotalSteps ?: 0) > 0 }
        if (runsWithSteps.size >= 2) {
            val freshest = runsWithSteps.minByOrNull { it.record.equiv1600mSec }
            val highStepRuns = runsWithSteps.filter { (it.dayTotalSteps ?: 0) >= 8000 && it != freshest }
            if (freshest != null && highStepRuns.isNotEmpty() && (freshest.dayTotalSteps ?: 99999) <= 6500) {
                val highMax = highStepRuns.maxOf { it.dayTotalSteps ?: 0 }
                insights.add(
                    "Daytime Walking Fatigue Rule: You ran your best (${TsPoliceBenchmark.formatDuration(freshest.record.equiv1600mSec)}) on a ~${freshest.dayTotalSteps}-step day vs slower runs on ${highMax}-step days. Keep daytime walking under ~5,500 steps before speed sessions!"
                )
            }
        }

        // 5. Wake-Up Rhythm Insight
        if (typicalWakeHour != null && typicalWakeHour >= 7) {
            insights.add(
                "Body Clock Rhythm: Sensor data shows you naturally start moving around ${formatHourOnly(typicalWakeHour)}. That's why evening (5:45–6:45 PM) gives your highest power right now — save early-morning runs for 1×/week exam practice."
            )
        }

        if (insights.isEmpty()) {
            insights.add(
                "Log your next 1.6 km run or 400m session and the Brain will automatically diagnose your lap splits, step rate, pre-run leg fatigue, and fastest clock hour!"
            )
        }
        return insights
    }

    private fun buildFuelAndRecoveryTip(weightKg: Int, workoutKind: CoachWorkoutKind): String {
        val dailyProteinG = (weightKg * 1.6).roundToInt()
        val postRunCarbsG = (weightKg * 1.15).roundToInt()
        val postRunProtG = (weightKg * 0.45).roundToInt().coerceIn(25, 42)
        val waterLiters = String.format("%.1fL", (weightKg * 0.042).coerceIn(2.4, 4.0))

        return when (workoutKind) {
            CoachWorkoutKind.TODAY_DONE_RECOVER_TONIGHT -> {
                val dinnerKcal = ((postRunCarbsG * 4) + (postRunProtG * 4) + 160).coerceIn(550, 850)
                "Dinner & Recovery (${weightKg}kg): Aim for ~${postRunCarbsG}g carbs + ~${postRunProtG}g protein (~$dinnerKcal kcal: rice/roti + eggs/chicken/dal/paneer, low fried fat) + $waterLiters daily water."
            }
            CoachWorkoutKind.SPEED_400M_REPS, CoachWorkoutKind.NONSTOP_1600M_TEST, CoachWorkoutKind.EXAM_DAY_RACE -> {
                "Pre-Run Fuel (60–90m before): ~28–35g fast carbs (1 banana or 2 dates + water, <3g fat so stomach stays light). Daily target: ~${dailyProteinG}g protein + $waterLiters water."
            }
            CoachWorkoutKind.EASY_TALK_PACE_JOG -> {
                val dayKcal = (weightKg * 32).coerceIn(1850, 2850)
                "Simple Fuel (${weightKg}kg): ~${dayKcal} kcal balanced day, ~${dailyProteinG}g protein, moderate carbs, avoid oily/high-fat snacks 2h before your jog + $waterLiters water."
            }
            CoachWorkoutKind.REST_AND_REPAIR, CoachWorkoutKind.SHARPENER_TAPER -> {
                val restKcal = (weightKg * 29).coerceIn(1750, 2600)
                "Repair Nutrition (${weightKg}kg): ~${restKcal} kcal clean home meals, keep protein steady (~${dailyProteinG}g) for tendon/shin repair + $waterLiters water."
            }
        }
    }

    private fun bestSlotClockShort(bestSlot: TimeOfDaySlot?): String = when (bestSlot) {
        TimeOfDaySlot.FIVE_PM -> "5:00–5:40 PM"
        TimeOfDaySlot.SIX_PM -> "5:45–6:35 PM"
        TimeOfDaySlot.EARLY_MORNING -> "6:15–7:15 AM"
        TimeOfDaySlot.MORNING -> "8:00–9:00 AM"
        else -> "5:30–6:30 PM"
    }

    private fun format12HourClock(hour24: Int, minute: Int): String {
        val amPm = if (hour24 < 12) "AM" else "PM"
        val h12 = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        return String.format("%d:%02d %s", h12, minute, amPm)
    }

    private fun formatHourOnly(hour24: Int): String {
        val amPm = if (hour24 < 12) "AM" else "PM"
        val h12 = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        return "$h12:00 $amPm"
    }

    private fun formatMinSecPlain(sec: Double): String {
        val r = sec.roundToInt().coerceAtLeast(0)
        return String.format("%d:%02d", r / 60, r % 60)
    }
}
