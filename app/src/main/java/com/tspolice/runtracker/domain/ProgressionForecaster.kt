package com.tspolice.runtracker.domain

import java.time.LocalDate
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

enum class ForecastStatus {
    NO_DATA,
    SINGLE_DAY_BASELINE,
    QUALIFIED,
    ON_TRACK,
    PLATEAU_OR_SLOWER
}

data class DailyProgressPoint(
    val dateIso: String,
    val epochDay: Long,
    val bestEquiv1600mSec: Double,
    val bestDistanceMeters: Double,
    val isExact1600mTrial: Boolean,
    val totalDistanceMeters: Double,
    val sessionCount: Int,
    val runningSharePct: Int
)

data class TargetForecast(
    val status: ForecastStatus,
    val targetTimeSec: Double,
    val dailyPoints: List<DailyProgressPoint>,
    val personalBestSec: Double?,
    val initialBaselineSec: Double?,
    val currentCapabilitySec: Double?,
    val gapToTargetSec: Double?,
    val totalImprovementSec: Double?,
    val improvementSecPerDay: Double?,
    val improvementSecPerWeek: Double?,
    val improvementSecPerSession: Double?,
    val estimatedDaysToTarget: Int?,
    val optimisticDaysToTarget: Int?,
    val conservativeDaysToTarget: Int?,
    val estimatedSessionsToTarget: Int?,
    val projectedTargetDateIso: String?,
    val practiceDaysPerWeek: Double,
    val qualifiedRunCount: Int,
    val coachSummary: String,
    val workoutPrescription: String
)

/**
 * Analyzes daily 1.6km practice logs and predicts how many days & sessions it will take
 * at the runner's observed rate of improvement to reach the TS Police 7:15 (435s) cutoff.
 */
object ProgressionForecaster {

    const val MIN_VALID_DISTANCE_M = TsPoliceBenchmark.MIN_FORECAST_DISTANCE_M
    const val MIN_VALID_DURATION_SEC = 90.0

    private fun continuousEquivSecForForecast(r: RunSessionRecord): Double =
        if (r.mode == RunMode.INTERVAL_400M) {
            // 400m interval reps include 90s recovery breaks; apply 1.07x endurance translation
            r.equiv1600mSec * 1.07
        } else {
            r.equiv1600mSec
        }

    fun analyze(
        sessions: List<RunSessionRecord>,
        targetTimeSec: Double = TsPoliceBenchmark.DEFAULT_TARGET_TIME_SEC,
        todayIso: String = LocalDate.now().toString()
    ): TargetForecast {
        val todayEpoch = runCatching { LocalDate.parse(todayIso).toEpochDay() }
            .getOrElse { LocalDate.now().toEpochDay() }
        val targetFmt = TsPoliceBenchmark.formatDuration(targetTimeSec)
        val targetLap = TsPoliceBenchmark.targetLap400mSec(targetTimeSec)
        val targetLapFmt = TsPoliceBenchmark.formatDuration(targetLap)
        val openerLapFmt = TsPoliceBenchmark.formatDuration(targetLap + 1.0)
        val closerLapFmt = TsPoliceBenchmark.formatDuration((targetLap - 3.0).coerceAtLeast(65.0))
        val intervalLapFmt = TsPoliceBenchmark.formatDuration((targetLap - 2.0).coerceAtLeast(65.0))

        val valid = sessions.filter {
            it.distanceMeters >= MIN_VALID_DISTANCE_M &&
                it.activeDurationSec >= MIN_VALID_DURATION_SEC &&
                it.equiv1600mSec in 180.0..1800.0
        }

        if (valid.isEmpty()) {
            return TargetForecast(
                status = ForecastStatus.NO_DATA,
                targetTimeSec = targetTimeSec,
                dailyPoints = emptyList(),
                personalBestSec = null,
                initialBaselineSec = null,
                currentCapabilitySec = null,
                gapToTargetSec = null,
                totalImprovementSec = null,
                improvementSecPerDay = null,
                improvementSecPerWeek = null,
                improvementSecPerSession = null,
                estimatedDaysToTarget = null,
                optimisticDaysToTarget = null,
                conservativeDaysToTarget = null,
                estimatedSessionsToTarget = null,
                projectedTargetDateIso = null,
                practiceDaysPerWeek = 0.0,
                qualifiedRunCount = 0,
                coachSummary = "Complete your first 1.6 km run (or load demo data) to establish your baseline and unlock your $targetFmt target-date forecast.",
                workoutPrescription = "Day 1 Goal: Run 1.6 km at a steady sustainable effort to record your true baseline split."
            )
        }

        val dailyPoints = valid
            .groupBy { it.dateIso }
            .mapNotNull { (dateIso, dayRuns) ->
                val epochDay = runCatching { LocalDate.parse(dateIso).toEpochDay() }.getOrNull()
                    ?: return@mapNotNull null
                val bestRun = dayRuns.minByOrNull { continuousEquivSecForForecast(it) } ?: return@mapNotNull null
                val totalActive = dayRuns.sumOf { it.runningSec + it.joggingSec + it.walkingSec }.coerceAtLeast(1.0)
                val runPct = ((dayRuns.sumOf { it.runningSec } / totalActive) * 100.0).roundToInt().coerceIn(0, 100)
                val isExactTrial = bestRun.isOfficial1600mTrial()
                DailyProgressPoint(
                    dateIso = dateIso,
                    epochDay = epochDay,
                    bestEquiv1600mSec = continuousEquivSecForForecast(bestRun),
                    bestDistanceMeters = bestRun.distanceMeters,
                    isExact1600mTrial = isExactTrial,
                    totalDistanceMeters = dayRuns.sumOf { it.distanceMeters },
                    sessionCount = dayRuns.size,
                    runningSharePct = runPct
                )
            }
            .sortedBy { it.epochDay }

        val continuousPbSec = valid
            .filter { it.mode != RunMode.INTERVAL_400M }
            .minOfOrNull { it.equiv1600mSec }
        val pbSec = continuousPbSec ?: dailyPoints.minOf { it.bestEquiv1600mSec }
        val initialSec = dailyPoints.first().bestEquiv1600mSec
        // Current capability uses the best of the most recent 3 practice days so an easy recovery jog day doesn't penalize ETA
        val recentWindow = dailyPoints.takeLast(3)
        val currentCapabilitySec = recentWindow.minOf { it.bestEquiv1600mSec }
        val totalImproved = initialSec - pbSec
        val qualifiedRuns = valid.count { it.mode != RunMode.INTERVAL_400M && it.equiv1600mSec <= targetTimeSec }

        val spanDays = max(1L, dailyPoints.last().epochDay - dailyPoints.first().epochDay + 1L)
        val observedFreqPerWeek = if (spanDays >= 5L) {
            ((dailyPoints.size.toDouble() / spanDays.toDouble()) * 7.0).coerceIn(1.5, 7.0)
        } else {
            5.0 // sensible training assumption during week 1
        }

        // Already achieved target on a continuous run! (Fragmented 400m interval reps never auto-qualify.)
        if (qualifiedRuns > 0 && continuousPbSec != null && continuousPbSec <= targetTimeSec) {
            val margin = (targetTimeSec - continuousPbSec).coerceAtLeast(0.0)
            val slope = if (dailyPoints.size >= 2) computeWeightedImprovementRatePerDay(dailyPoints, continuousPbSec) else null
            val qualDateIso = valid
                .filter { it.mode != RunMode.INTERVAL_400M && it.equiv1600mSec <= targetTimeSec }
                .minByOrNull { it.startedAtEpochMs }
                ?.dateIso
                ?: dailyPoints.first().dateIso
            return TargetForecast(
                status = ForecastStatus.QUALIFIED,
                targetTimeSec = targetTimeSec,
                dailyPoints = dailyPoints,
                personalBestSec = continuousPbSec,
                initialBaselineSec = initialSec,
                currentCapabilitySec = minOf(currentCapabilitySec, continuousPbSec),
                gapToTargetSec = 0.0,
                totalImprovementSec = totalImproved,
                improvementSecPerDay = slope,
                improvementSecPerWeek = slope?.let { it * 7.0 },
                improvementSecPerSession = slope?.let { (it * 7.0) / observedFreqPerWeek },
                estimatedDaysToTarget = 0,
                optimisticDaysToTarget = 0,
                conservativeDaysToTarget = 0,
                estimatedSessionsToTarget = 0,
                projectedTargetDateIso = qualDateIso,
                practiceDaysPerWeek = observedFreqPerWeek,
                qualifiedRunCount = qualifiedRuns,
                coachSummary = "QUALIFIED! Personal best ${TsPoliceBenchmark.formatDuration(continuousPbSec)} beats the $targetFmt cutoff by ${String.format(Locale.US, "%.0fs", margin)} ($qualifiedRuns qualifying session${if (qualifiedRuns == 1) "" else "s"}).",
                workoutPrescription = "Lock in consistency: practice negative splits (Lap 1-2 at $openerLapFmt, Lap 3-4 at $closerLapFmt) so race-day heat or wind never threatens your $targetFmt margin."
            )
        }

        val gapSec = (currentCapabilitySec - targetTimeSec).coerceAtLeast(0.1)
        val gapDisplaySec = TsPoliceBenchmark.secondsAboveCutoff(currentCapabilitySec, targetTimeSec)
        val pbFmt = TsPoliceBenchmark.formatDurationAgainstCutoff(pbSec, targetTimeSec)
        val capFmt = TsPoliceBenchmark.formatDurationAgainstCutoff(currentCapabilitySec, targetTimeSec)

        // Single practice day logged so far
        if (dailyPoints.size == 1) {
            val neededPerWeek4w = ceil(gapSec / 4.0).toInt().coerceAtLeast(1)
            return TargetForecast(
                status = ForecastStatus.SINGLE_DAY_BASELINE,
                targetTimeSec = targetTimeSec,
                dailyPoints = dailyPoints,
                personalBestSec = pbSec,
                initialBaselineSec = initialSec,
                currentCapabilitySec = currentCapabilitySec,
                gapToTargetSec = gapSec,
                totalImprovementSec = 0.0,
                improvementSecPerDay = null,
                improvementSecPerWeek = null,
                improvementSecPerSession = null,
                estimatedDaysToTarget = null,
                optimisticDaysToTarget = null,
                conservativeDaysToTarget = null,
                estimatedSessionsToTarget = null,
                projectedTargetDateIso = null,
                practiceDaysPerWeek = observedFreqPerWeek,
                qualifiedRunCount = 0,
                coachSummary = "Baseline recorded: $pbFmt (${gapDisplaySec}s above $targetFmt). Log 1 more practice day to calculate your personal improvement speed & target date!",
                workoutPrescription = "Shaving ${neededPerWeek4w}s/week hits $targetFmt in 4 weeks. Next workout: 5 × 400m intervals at $targetLapFmt with 90s walk recovery."
            )
        }

        val rateSecPerDay = computeWeightedImprovementRatePerDay(dailyPoints, pbSec)
        val rateSecPerWeek = rateSecPerDay * 7.0
        val rateSecPerSession = rateSecPerWeek / observedFreqPerWeek

        if (rateSecPerDay <= 0.08) {
            val trendWkRounded = (-rateSecPerWeek).roundToInt()
            val trendWkStr = if (trendWkRounded == 0) "0s/wk" else String.format(Locale.US, "%+ds/wk", trendWkRounded)
            return TargetForecast(
                status = ForecastStatus.PLATEAU_OR_SLOWER,
                targetTimeSec = targetTimeSec,
                dailyPoints = dailyPoints,
                personalBestSec = pbSec,
                initialBaselineSec = initialSec,
                currentCapabilitySec = currentCapabilitySec,
                gapToTargetSec = gapSec,
                totalImprovementSec = totalImproved,
                improvementSecPerDay = rateSecPerDay,
                improvementSecPerWeek = rateSecPerWeek,
                improvementSecPerSession = rateSecPerSession,
                estimatedDaysToTarget = null,
                optimisticDaysToTarget = null,
                conservativeDaysToTarget = null,
                estimatedSessionsToTarget = null,
                projectedTargetDateIso = null,
                practiceDaysPerWeek = observedFreqPerWeek,
                qualifiedRunCount = 0,
                coachSummary = "Recent pace trend is flat ($trendWkStr); ${gapDisplaySec}s left to reach $targetFmt from your $capFmt capability.",
                workoutPrescription = "Plateau breaker: alternate 1 interval day (6 × 400m @ $intervalLapFmt) with 1 easy 2.4 km talk-pace jog (keep Running share >75% on trial days)."
            )
        }

        // Cap extreme 2-day improvement spikes to physiological upper bound (~6.0 sec/day sustained)
        val effectiveRatePerDay = rateSecPerDay.coerceAtMost(6.0)
        val effectiveRatePerWeek = effectiveRatePerDay * 7.0
        val effectiveRatePerSession = effectiveRatePerWeek / observedFreqPerWeek
        val linearDays = gapSec / effectiveRatePerDay
        // Physiological taper multiplier: shaving the final seconds near target takes ~18% more time than linear extrapolation
        val taperMultiplier = if (currentCapabilitySec < targetTimeSec + 35.0) 1.22 else 1.15
        val realisticDays = ceil(linearDays * taperMultiplier).toInt().coerceIn(1, 730)
        val optimisticDays = ceil(linearDays).toInt().coerceIn(1, realisticDays)
        val conservativeDays = ceil(linearDays * 1.32).toInt().coerceIn(realisticDays, 900)
        val estSessions = ceil((realisticDays / 7.0) * observedFreqPerWeek).toInt().coerceAtLeast(1)

        val anchorDay = max(todayEpoch, dailyPoints.last().epochDay)
        val targetDateIso = LocalDate.ofEpochDay(anchorDay + realisticDays).toString()

        val currentLap = currentCapabilitySec / 4.0

        return TargetForecast(
            status = ForecastStatus.ON_TRACK,
            targetTimeSec = targetTimeSec,
            dailyPoints = dailyPoints,
            personalBestSec = pbSec,
            initialBaselineSec = initialSec,
            currentCapabilitySec = currentCapabilitySec,
            gapToTargetSec = gapSec,
            totalImprovementSec = totalImproved,
            improvementSecPerDay = effectiveRatePerDay,
            improvementSecPerWeek = effectiveRatePerWeek,
            improvementSecPerSession = effectiveRatePerSession,
            estimatedDaysToTarget = realisticDays,
            optimisticDaysToTarget = optimisticDays,
            conservativeDaysToTarget = conservativeDays,
            estimatedSessionsToTarget = estSessions,
            projectedTargetDateIso = targetDateIso,
            practiceDaysPerWeek = observedFreqPerWeek,
            qualifiedRunCount = 0,
            coachSummary = "Improving by ${String.format(Locale.US, "%.0fs/week", effectiveRatePerWeek)} (${gapDisplaySec}s left). At this practice pace, you'll break $targetFmt in ~$realisticDays days ($optimisticDays–$conservativeDays days, ~$estSessions sessions) around $targetDateIso.",
            workoutPrescription = "Current 400m lap avg is ${TsPoliceBenchmark.formatDuration(currentLap)} vs $targetLapFmt target. Focus next track session on holding $targetLapFmt/lap through 1,200m."
        )
    }

    /**
     * Recency-weighted least-squares slope over daily bests, down-weighting easy recovery jog days
     * (>12% slower than PB) so easy mileage days don't distort capability trend.
     * Returns positive seconds shaved per calendar day.
     */
    private fun computeWeightedImprovementRatePerDay(
        points: List<DailyProgressPoint>,
        pbSec: Double
    ): Double {
        val lastDay = points.last().epochDay.toDouble()
        val firstDay = points.first().epochDay.toDouble()
        if (lastDay <= firstDay) return 0.0

        var sumW = 0.0
        var sumWX = 0.0
        var sumWY = 0.0
        for (pt in points) {
            val x = pt.epochDay - firstDay
            val ageDays = lastDay - pt.epochDay
            val recencyWeight = exp(-ageDays / 21.0) // 3-week half-decay favors recent fitness
            val effortWeight = when {
                pt.isExact1600mTrial && pt.bestEquiv1600mSec <= pbSec * 1.12 -> 1.0
                pt.bestEquiv1600mSec <= pbSec * 1.10 -> 0.9
                else -> 0.22 // easy recovery jog day
            }
            val w = recencyWeight * effortWeight
            sumW += w
            sumWX += w * x
            sumWY += w * pt.bestEquiv1600mSec
        }
        if (sumW <= 1e-6) return 0.0
        val meanX = sumWX / sumW
        val meanY = sumWY / sumW

        var num = 0.0
        var den = 0.0
        for (pt in points) {
            val x = pt.epochDay - firstDay
            val ageDays = lastDay - pt.epochDay
            val effortWeight = when {
                pt.isExact1600mTrial && pt.bestEquiv1600mSec <= pbSec * 1.12 -> 1.0
                pt.bestEquiv1600mSec <= pbSec * 1.10 -> 0.9
                else -> 0.22
            }
            val w = exp(-ageDays / 21.0) * effortWeight
            val dx = x - meanX
            num += w * dx * (pt.bestEquiv1600mSec - meanY)
            den += w * dx * dx
        }
        if (den <= 1e-6) return 0.0
        val slopeSecPerDay = num / den
        // Negative slope in duration means runner is getting faster -> positive improvement rate!
        return -slopeSecPerDay
    }
}

enum class PushAccentKind {
    LIME_GO,
    ORANGE_HARD,
    PURPLE_RECOVERY
}

enum class RecommendedWorkoutAction {
    ARM_1600M_TRIAL,
    START_400M_INTERVALS,
    EASY_AEROBIC_JOG,
    DONE_FOR_TODAY
}

data class WeeklyGoalCheckpoint(
    val label: String,
    val dateLabel: String,
    val targetTimeSec: Double,
    val targetLap400mSec: Double
)

data class DeadlineGoalPlan(
    val deadlineIso: String,
    val deadlineLabel: String,
    val daysRemaining: Int,
    val targetTimeSec: Double,
    val officialBestSec: Double?,
    val movingCapabilitySec: Double?,
    val stoppedPenaltyInTrialSec: Double,
    val hasCleanNonstop1600m: Boolean,
    val thisWeekTargetSec: Double,
    val requiredCutPerWeekSec: Double?,
    val recommendedIntervalLapSec: Double,
    val readinessPercent: Int,
    val pushBadgeLabel: String,
    val pushRpeScore: Double,
    val pushAccentKind: PushAccentKind,
    val headlineTitle: String,
    val statusSummary: String,
    val todayPushDirective: String,
    val weeklyStructureHint: String,
    val checkpoints: List<WeeklyGoalCheckpoint>,
    val recommendedAction: RecommendedWorkoutAction,
    val recommendedActionLabel: String
)

/**
 * Periodized deadline coach for achieving the TSLPRB 1,600m target (7:15 / 435s) by a fixed exam date
 * (default Nov 30). Balances hard VO2max/race-pace sessions with shin-safe aerobic recovery days.
 */
object DeadlineGoalCoach {

    private fun formatShortDate(date: LocalDate): String {
        val m = date.month.name.take(3).lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
        return "$m ${date.dayOfMonth}"
    }

    fun buildPlan(
        sessions: List<RunSessionRecord>,
        todaySummary: DailyActivitySummary = DailyActivitySummary(),
        yesterdaySummary: DailyActivitySummary? = null,
        targetTimeSec: Double = TsPoliceBenchmark.DEFAULT_TARGET_TIME_SEC,
        deadlineIso: String = "2026-11-30",
        todayIso: String = LocalDate.now().toString()
    ): DeadlineGoalPlan {
        val todayDate = runCatching { LocalDate.parse(todayIso) }.getOrElse { LocalDate.now() }
        val deadlineDate = runCatching { LocalDate.parse(deadlineIso) }.getOrElse { LocalDate.of(2026, 11, 30) }
        val rawDaysToDeadline = (deadlineDate.toEpochDay() - todayDate.toEpochDay()).toInt()
        val isPastDeadline = rawDaysToDeadline < 0
        val daysRemaining = rawDaysToDeadline.coerceAtLeast(0)
        val rawDeadlineLabel = formatShortDate(deadlineDate)
        val deadlineLabel = if (isPastDeadline) "$rawDeadlineLabel Passed • Tap ✎" else rawDeadlineLabel
        val targetFmt = TsPoliceBenchmark.formatDuration(targetTimeSec)
        val targetLapSec = TsPoliceBenchmark.targetLap400mSec(targetTimeSec)

        val validRuns = sessions.filter {
            it.distanceMeters >= TsPoliceBenchmark.MIN_SESSION_DISTANCE_M &&
                it.activeDurationSec >= 60.0 &&
                it.equiv1600mSec in 180.0..1800.0
        }

        val continuousRuns = validRuns.filter { it.mode != RunMode.INTERVAL_400M }
        val officialBestSec = continuousRuns.minOfOrNull { it.equiv1600mSec }
        val trials1600 = validRuns.filter { it.isOfficial1600mTrial() }
        val bestTrial = trials1600.minByOrNull { it.equiv1600mSec }
        val stoppedPenaltySec = bestTrial?.pausedDurationSec ?: 0.0
        val hasCleanNonstop1600m = trials1600.any { it.pausedDurationSec < 15.0 && it.walkingSec < 30.0 }

        val continuousMovingEquivSec = continuousRuns.mapNotNull { s ->
            val movSec = s.netMovingDurationSec()
            val eq = TsPoliceBenchmark.computeEquiv1600mSec(s.distanceMeters, movSec, null)
            eq.takeIf { it in 180.0..1800.0 }
        }.minOrNull()

        val intervalEstEquivSec = validRuns
            .filter { it.mode == RunMode.INTERVAL_400M }
            .mapNotNull { s -> (s.equiv1600mSec * 1.07).takeIf { it in 180.0..1800.0 } }
            .minOrNull()
            ?.let { intEst ->
                if (continuousMovingEquivSec != null) max(intEst, continuousMovingEquivSec - 18.0) else intEst
            }

        val bestMovingEquivSec = listOfNotNull(continuousMovingEquivSec, intervalEstEquivSec).minOrNull()

        val movingCapSec = when {
            officialBestSec != null && bestMovingEquivSec != null -> minOf(officialBestSec, bestMovingEquivSec)
            else -> officialBestSec ?: bestMovingEquivSec
        }

        val anchorStartSec = (movingCapSec ?: 720.0).coerceIn(targetTimeSec, 840.0)
        val weeksLeft = (daysRemaining / 7.0).coerceAtLeast(0.5)
        val effectiveGapSec = ((movingCapSec ?: anchorStartSec) - targetTimeSec).coerceAtLeast(0.0)
        val requiredCutPerWeek = if (movingCapSec != null && effectiveGapSec > 0.0 && rawDaysToDeadline > 0) {
            effectiveGapSec / weeksLeft
        } else {
            null
        }

        // Convex milestone ladder: early nonstop + cadence gains shave bigger chunks in Weeks 1-2
        val checkpoints = buildCheckpoints(todayDate, deadlineDate, anchorStartSec, targetTimeSec)
        val thisWeekTargetSec = checkpoints.firstOrNull()?.targetTimeSec ?: targetTimeSec

        val curLapEst = (movingCapSec ?: 660.0) / 4.0
        val recommendedIntervalLapSec = when {
            curLapEst <= targetLapSec + 4.0 -> (targetLapSec - 2.5).coerceAtLeast(92.0)
            else -> (targetLapSec + (curLapEst - targetLapSec) * 0.32).coerceIn(targetLapSec, 138.0)
        }

        val readinessPct = when {
            officialBestSec != null && officialBestSec <= targetTimeSec -> 100
            movingCapSec == null -> 18
            else -> {
                val raw = ((1080.0 - movingCapSec) / (1080.0 - targetTimeSec) * 86.0 + 14.0).roundToInt()
                val stopPenaltyPts = if (!hasCleanNonstop1600m) 8 else 0
                (raw - stopPenaltyPts).coerceIn(15, 97)
            }
        }

        fun isHardEffort(r: RunSessionRecord): Boolean {
            if (r.mode == RunMode.TIMED_1600M || r.mode == RunMode.INTERVAL_400M) return true
            val movSec = r.netMovingDurationSec()
            if (r.runningSec >= 120.0 && (movSec <= 0.0 || r.runningSec >= movSec * 0.25)) return true
            if (r.distanceMeters >= 4500.0) return true
            if (movingCapSec != null && movSec > 0.0) {
                val runSpeed = r.distanceMeters / movSec
                val capSpeed = TsPoliceBenchmark.TARGET_DISTANCE_M / movingCapSec
                if (runSpeed >= maxOf(capSpeed * 0.90, 3.05)) return true
            } else if (r.avgCadenceSpm >= 160.0 && r.mode != RunMode.MANUAL_LOG) {
                return true
            }
            return false
        }

        // Inspect today's & yesterday's workload for smart Hard/Easy push modulation
        val yesterdayIso = todayDate.minusDays(1).toString()
        val todayRuns = sessions.filter { it.dateIso == todayIso && it.distanceMeters >= TsPoliceBenchmark.MIN_SESSION_DISTANCE_M }
        val yesterdayRuns = sessions.filter { it.dateIso == yesterdayIso && it.distanceMeters >= TsPoliceBenchmark.MIN_SESSION_DISTANCE_M }

        val todayQualityDone = todayRuns.any {
            it.mode == RunMode.TIMED_1600M || it.mode == RunMode.INTERVAL_400M || it.distanceMeters >= TsPoliceBenchmark.MIN_FORECAST_DISTANCE_M
        } || todaySummary.jogRunCombinedMeters >= 1100.0

        val yesterdayWasHard = yesterdayRuns.any { isHardEffort(it) } ||
            (yesterdaySummary?.runningSec ?: 0.0) >= 150.0

        val qualified = officialBestSec != null && officialBestSec <= targetTimeSec
        val headline = when {
            isPastDeadline -> "Goal Date ($rawDeadlineLabel) Passed  •  Tap ✎ to Set Next PET Date"
            rawDaysToDeadline == 0 -> "Goal Day ($rawDeadlineLabel)  •  Sub-$targetFmt Test!"
            else -> "$daysRemaining Days to $rawDeadlineLabel  •  $targetFmt Goal"
        }

        val statusSummary = when {
            qualified -> {
                "QUALIFIED! Personal best ${TsPoliceBenchmark.formatDuration(officialBestSec!!)} is already faster than $targetFmt."
            }
            bestTrial != null && stoppedPenaltySec >= 30.0 && !hasCleanNonstop1600m -> {
                "PB ${TsPoliceBenchmark.formatDurationAgainstCutoff(officialBestSec ?: bestTrial.equiv1600mSec, targetTimeSec)} " +
                    "(your 1.6K trial had ${TsPoliceBenchmark.formatDuration(stoppedPenaltySec)} stopped — " +
                    "moving pace was ${TsPoliceBenchmark.formatDurationAgainstCutoff(movingCapSec ?: bestTrial.equiv1600mSec, targetTimeSec)}). " +
                    "Week 1 target: ${TsPoliceBenchmark.formatDuration(thisWeekTargetSec)} nonstop."
            }
            movingCapSec != null -> {
                "Current capability ${TsPoliceBenchmark.formatDurationAgainstCutoff(movingCapSec, targetTimeSec)}  •  " +
                    "This week's target: ${TsPoliceBenchmark.formatDuration(thisWeekTargetSec)}  •  " +
                    "$deadlineLabel goal: $targetFmt (${TsPoliceBenchmark.formatDuration(targetLapSec)}/lap)."
            }
            isPastDeadline -> {
                "No 1.6 km baseline yet  •  Tap ✎ to pick your upcoming PET batch date for $targetFmt (${TsPoliceBenchmark.formatDuration(targetLapSec)}/400m lap)."
            }
            else -> {
                "No 1.6 km baseline yet  •  $daysRemaining days left to build to $targetFmt (${TsPoliceBenchmark.formatDuration(targetLapSec)}/400m lap)."
            }
        }

        val weeklyStructureHint =
            "Smart Weekly Plan (1 evening run/day • 2 rest days): 2× Fast 400m Laps  •  1× Nonstop 1.6 km Time Check  •  2× Slow Talk-Pace Jogs."

        // Decide TODAY'S exact Push & Workout Action
        return when {
            qualified -> DeadlineGoalPlan(
                deadlineIso = deadlineIso,
                deadlineLabel = deadlineLabel,
                daysRemaining = daysRemaining,
                targetTimeSec = targetTimeSec,
                officialBestSec = officialBestSec,
                movingCapabilitySec = movingCapSec,
                stoppedPenaltyInTrialSec = stoppedPenaltySec,
                hasCleanNonstop1600m = hasCleanNonstop1600m,
                thisWeekTargetSec = targetTimeSec,
                requiredCutPerWeekSec = 0.0,
                recommendedIntervalLapSec = targetLapSec - 2.0,
                readinessPercent = 100,
                pushBadgeLabel = "QUALIFIED • STAY SHARP",
                pushRpeScore = 7.0,
                pushAccentKind = PushAccentKind.LIME_GO,
                headlineTitle = headline,
                statusSummary = statusSummary,
                todayPushDirective = "You already beat $targetFmt! Keep your legs sharp without injury: 1 fast 400m lap session + 1 controlled 1.6 km run per week, keeping other jogs slow and relaxed.",
                weeklyStructureHint = weeklyStructureHint,
                checkpoints = checkpoints,
                recommendedAction = RecommendedWorkoutAction.START_400M_INTERVALS,
                recommendedActionLabel = "Run Sharpener 4 × 400m Laps  →"
            )

            rawDaysToDeadline == 0 -> DeadlineGoalPlan(
                deadlineIso = deadlineIso,
                deadlineLabel = deadlineLabel,
                daysRemaining = 0,
                targetTimeSec = targetTimeSec,
                officialBestSec = officialBestSec,
                movingCapabilitySec = movingCapSec,
                stoppedPenaltyInTrialSec = stoppedPenaltySec,
                hasCleanNonstop1600m = hasCleanNonstop1600m,
                thisWeekTargetSec = targetTimeSec,
                requiredCutPerWeekSec = requiredCutPerWeek,
                recommendedIntervalLapSec = targetLapSec,
                readinessPercent = readinessPct,
                pushBadgeLabel = "EXAM DAY • GO!",
                pushRpeScore = 10.0,
                pushAccentKind = PushAccentKind.LIME_GO,
                headlineTitle = headline,
                statusSummary = statusSummary,
                todayPushDirective = "Exam Day Game Plan: Start Lap 1 controlled at ${TsPoliceBenchmark.formatDuration(targetLapSec)} (do NOT sprint the first 200m!), keep quick 170+ steps/min on Laps 2–3, and sprint all-out in the final 250m!",
                weeklyStructureHint = weeklyStructureHint,
                checkpoints = checkpoints,
                recommendedAction = RecommendedWorkoutAction.ARM_1600M_TRIAL,
                recommendedActionLabel = "Launch Official 1.6 km Trial  →"
            )

            rawDaysToDeadline in 1..2 -> DeadlineGoalPlan(
                deadlineIso = deadlineIso,
                deadlineLabel = deadlineLabel,
                daysRemaining = daysRemaining,
                targetTimeSec = targetTimeSec,
                officialBestSec = officialBestSec,
                movingCapabilitySec = movingCapSec,
                stoppedPenaltyInTrialSec = stoppedPenaltySec,
                hasCleanNonstop1600m = hasCleanNonstop1600m,
                thisWeekTargetSec = targetTimeSec,
                requiredCutPerWeekSec = requiredCutPerWeek,
                recommendedIntervalLapSec = targetLapSec,
                readinessPercent = readinessPct,
                pushBadgeLabel = "FRESHEN LEGS",
                pushRpeScore = 3.0,
                pushAccentKind = PushAccentKind.PURPLE_RECOVERY,
                headlineTitle = headline,
                statusSummary = statusSummary,
                todayPushDirective = "Only $daysRemaining day${if (daysRemaining == 1) "" else "s"} to $deadlineLabel! Do NOT tire your legs with a hard test today — just an 8-min slow jog + 2 short smooth strides so your legs feel 100% bouncy on exam day.",
                weeklyStructureHint = weeklyStructureHint,
                checkpoints = checkpoints,
                recommendedAction = RecommendedWorkoutAction.EASY_AEROBIC_JOG,
                recommendedActionLabel = "Keep Legs Fresh Today"
            )

            todayQualityDone -> DeadlineGoalPlan(
                deadlineIso = deadlineIso,
                deadlineLabel = deadlineLabel,
                daysRemaining = daysRemaining,
                targetTimeSec = targetTimeSec,
                officialBestSec = officialBestSec,
                movingCapabilitySec = movingCapSec,
                stoppedPenaltyInTrialSec = stoppedPenaltySec,
                hasCleanNonstop1600m = hasCleanNonstop1600m,
                thisWeekTargetSec = thisWeekTargetSec,
                requiredCutPerWeekSec = requiredCutPerWeek,
                recommendedIntervalLapSec = recommendedIntervalLapSec,
                readinessPercent = readinessPct,
                pushBadgeLabel = "DONE TODAY ✓",
                pushRpeScore = 4.0,
                pushAccentKind = PushAccentKind.LIME_GO,
                headlineTitle = headline,
                statusSummary = statusSummary,
                todayPushDirective = "Great work — today's run is done (${formatShortDist(todaySummary.jogRunCombinedMeters)})! Drink water, stretch calves & shins tonight, and tomorrow do ONLY a slow, comfortable 18-min jog (talk-test pace — NO sprinting!).",
                weeklyStructureHint = weeklyStructureHint,
                checkpoints = checkpoints,
                recommendedAction = RecommendedWorkoutAction.DONE_FOR_TODAY,
                recommendedActionLabel = "View Progress & Splits  →"
            )

            yesterdayWasHard -> DeadlineGoalPlan(
                deadlineIso = deadlineIso,
                deadlineLabel = deadlineLabel,
                daysRemaining = daysRemaining,
                targetTimeSec = targetTimeSec,
                officialBestSec = officialBestSec,
                movingCapabilitySec = movingCapSec,
                stoppedPenaltyInTrialSec = stoppedPenaltySec,
                hasCleanNonstop1600m = hasCleanNonstop1600m,
                thisWeekTargetSec = thisWeekTargetSec,
                requiredCutPerWeekSec = requiredCutPerWeek,
                recommendedIntervalLapSec = recommendedIntervalLapSec,
                readinessPercent = readinessPct,
                pushBadgeLabel = "EASY SLOW JOG",
                pushRpeScore = 4.5,
                pushAccentKind = PushAccentKind.PURPLE_RECOVERY,
                headlineTitle = headline,
                statusSummary = statusSummary,
                todayPushDirective = "Yesterday was a hard run — sprinting two days in a row causes shin pain! Today do 1 slow 18–20 min jog where you can talk in full sentences without gasping, so your legs are fresh for tomorrow's speed session.",
                weeklyStructureHint = weeklyStructureHint,
                checkpoints = checkpoints,
                recommendedAction = RecommendedWorkoutAction.EASY_AEROBIC_JOG,
                recommendedActionLabel = "Easy 20-Min Slow Jog Today"
            )

            !hasCleanNonstop1600m -> DeadlineGoalPlan(
                deadlineIso = deadlineIso,
                deadlineLabel = deadlineLabel,
                daysRemaining = daysRemaining,
                targetTimeSec = targetTimeSec,
                officialBestSec = officialBestSec,
                movingCapabilitySec = movingCapSec,
                stoppedPenaltyInTrialSec = stoppedPenaltySec,
                hasCleanNonstop1600m = false,
                thisWeekTargetSec = thisWeekTargetSec,
                requiredCutPerWeekSec = requiredCutPerWeek,
                recommendedIntervalLapSec = recommendedIntervalLapSec,
                readinessPercent = readinessPct,
                pushBadgeLabel = "NONSTOP 1.6K TEST",
                pushRpeScore = 8.0,
                pushAccentKind = PushAccentKind.ORANGE_HARD,
                headlineTitle = headline,
                statusSummary = statusSummary,
                todayPushDirective = "Today's goal: finish a full 1.6 km PT Trial with ZERO walking or standing stops! Start steady on Laps 1–3 so you don't run out of breath, then push Lap 4 hard (< ${TsPoliceBenchmark.formatDuration(thisWeekTargetSec)}).",
                weeklyStructureHint = weeklyStructureHint,
                checkpoints = checkpoints,
                recommendedAction = RecommendedWorkoutAction.ARM_1600M_TRIAL,
                recommendedActionLabel = "Arm Zero-Stop 1.6 km Trial  →"
            )

            else -> {
                val lapFmt = TsPoliceBenchmark.formatDuration(recommendedIntervalLapSec, includeTenths = false)
                DeadlineGoalPlan(
                    deadlineIso = deadlineIso,
                    deadlineLabel = deadlineLabel,
                    daysRemaining = daysRemaining,
                    targetTimeSec = targetTimeSec,
                    officialBestSec = officialBestSec,
                    movingCapabilitySec = movingCapSec,
                    stoppedPenaltyInTrialSec = stoppedPenaltySec,
                    hasCleanNonstop1600m = true,
                    thisWeekTargetSec = thisWeekTargetSec,
                    requiredCutPerWeekSec = requiredCutPerWeek,
                    recommendedIntervalLapSec = recommendedIntervalLapSec,
                    readinessPercent = readinessPct,
                    pushBadgeLabel = "FAST 400M LAPS",
                    pushRpeScore = 8.5,
                    pushAccentKind = PushAccentKind.ORANGE_HARD,
                    headlineTitle = headline,
                    statusSummary = statusSummary,
                    todayPushDirective = "Fresh legs today — build speed! Run 6 × 400m fast laps aiming for ~$lapFmt per lap (quick, light 168+ steps/min) with 90s slow walk rest between laps.",
                    weeklyStructureHint = weeklyStructureHint,
                    checkpoints = checkpoints,
                    recommendedAction = RecommendedWorkoutAction.START_400M_INTERVALS,
                    recommendedActionLabel = "Launch 6 × 400m Speed Laps (~$lapFmt)  →"
                )
            }
        }
    }

    private fun formatShortDist(m: Double): String =
        if (m >= 1000.0) String.format(Locale.US, "%.2f km", m / 1000.0) else "${m.toInt()} m"

    private fun buildCheckpoints(
        todayDate: LocalDate,
        deadlineDate: LocalDate,
        startSec: Double,
        targetSec: Double
    ): List<WeeklyGoalCheckpoint> {
        val totalDays = (deadlineDate.toEpochDay() - todayDate.toEpochDay()).toInt()
        if (totalDays <= 0) {
            return listOf(
                WeeklyGoalCheckpoint(
                    label = "Exam Goal",
                    dateLabel = formatShortDate(deadlineDate),
                    targetTimeSec = targetSec,
                    targetLap400mSec = targetSec / 4.0
                )
            )
        }
        val gap = (startSec - targetSec).coerceAtLeast(0.0)
        // Build up to 4 milestone stages (Week 1, Week 2, Week 3, Exam Goal)
        val stageCount = when {
            totalDays >= 24 -> 4
            totalDays >= 15 -> 3
            totalDays >= 8 -> 2
            else -> 1
        }
        val fractionsRemaining = when (stageCount) {
            4 -> doubleArrayOf(0.62, 0.34, 0.14, 0.0)
            3 -> doubleArrayOf(0.55, 0.22, 0.0)
            2 -> doubleArrayOf(0.45, 0.0)
            else -> doubleArrayOf(0.0)
        }
        return (0 until stageCount).map { idx ->
            val isFinal = idx == stageCount - 1
            val dayOffset = if (isFinal) totalDays else (((idx + 1).toDouble() / stageCount) * totalDays).roundToInt()
            val cpDate = todayDate.plusDays(dayOffset.toLong())
            val goalSec = targetSec + gap * fractionsRemaining[idx]
            WeeklyGoalCheckpoint(
                label = if (isFinal) "Goal Day" else "Week ${idx + 1}",
                dateLabel = formatShortDate(cpDate),
                targetTimeSec = goalSec,
                targetLap400mSec = goalSec / 4.0
            )
        }
    }
}
