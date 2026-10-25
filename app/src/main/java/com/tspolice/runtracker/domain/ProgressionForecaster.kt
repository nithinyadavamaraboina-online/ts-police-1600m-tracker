package com.tspolice.runtracker.domain

import java.time.LocalDate
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

    const val MIN_VALID_DISTANCE_M = 600.0
    const val MIN_VALID_DURATION_SEC = 90.0

    fun analyze(
        sessions: List<RunSessionRecord>,
        targetTimeSec: Double = TsPoliceBenchmark.DEFAULT_TARGET_TIME_SEC,
        todayIso: String = LocalDate.now().toString()
    ): TargetForecast {
        val todayEpoch = runCatching { LocalDate.parse(todayIso).toEpochDay() }
            .getOrElse { LocalDate.now().toEpochDay() }

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
                coachSummary = "Complete your first 1.6 km run (or load demo data) to establish your baseline and unlock your target-date forecast.",
                workoutPrescription = "Day 1 Goal: Run 1.6 km at a steady sustainable effort to record your true baseline split."
            )
        }

        val dailyPoints = valid
            .groupBy { it.dateIso }
            .mapNotNull { (dateIso, dayRuns) ->
                val epochDay = runCatching { LocalDate.parse(dateIso).toEpochDay() }.getOrNull()
                    ?: return@mapNotNull null
                val bestRun = dayRuns.minByOrNull { it.equiv1600mSec } ?: return@mapNotNull null
                val totalActive = dayRuns.sumOf { it.runningSec + it.joggingSec + it.walkingSec }.coerceAtLeast(1.0)
                val runPct = ((dayRuns.sumOf { it.runningSec } / totalActive) * 100.0).roundToInt().coerceIn(0, 100)
                val isExactTrial = bestRun.mode == RunMode.TIMED_1600M ||
                    bestRun.mode == RunMode.MANUAL_LOG ||
                    bestRun.first1600mSplitSec != null ||
                    kotlin.math.abs(bestRun.distanceMeters - 1600.0) <= 50.0
                DailyProgressPoint(
                    dateIso = dateIso,
                    epochDay = epochDay,
                    bestEquiv1600mSec = bestRun.equiv1600mSec,
                    bestDistanceMeters = bestRun.distanceMeters,
                    isExact1600mTrial = isExactTrial,
                    totalDistanceMeters = dayRuns.sumOf { it.distanceMeters },
                    sessionCount = dayRuns.size,
                    runningSharePct = runPct
                )
            }
            .sortedBy { it.epochDay }

        val pbSec = dailyPoints.minOf { it.bestEquiv1600mSec }
        val initialSec = dailyPoints.first().bestEquiv1600mSec
        // Current capability uses the best of the most recent 3 practice days so an easy recovery jog day doesn't penalize ETA
        val recentWindow = dailyPoints.takeLast(3)
        val currentCapabilitySec = recentWindow.minOf { it.bestEquiv1600mSec }
        val totalImproved = initialSec - pbSec
        val qualifiedRuns = valid.count { it.equiv1600mSec <= targetTimeSec }

        val spanDays = max(1L, dailyPoints.last().epochDay - dailyPoints.first().epochDay + 1L)
        val observedFreqPerWeek = if (spanDays >= 5L) {
            ((dailyPoints.size.toDouble() / spanDays.toDouble()) * 7.0).coerceIn(1.5, 7.0)
        } else {
            5.0 // sensible training assumption during week 1
        }

        // Already achieved target!
        if (pbSec <= targetTimeSec) {
            val margin = targetTimeSec - pbSec
            val slope = if (dailyPoints.size >= 2) computeWeightedImprovementRatePerDay(dailyPoints, pbSec) else null
            return TargetForecast(
                status = ForecastStatus.QUALIFIED,
                targetTimeSec = targetTimeSec,
                dailyPoints = dailyPoints,
                personalBestSec = pbSec,
                initialBaselineSec = initialSec,
                currentCapabilitySec = currentCapabilitySec,
                gapToTargetSec = 0.0,
                totalImprovementSec = totalImproved,
                improvementSecPerDay = slope,
                improvementSecPerWeek = slope?.let { it * 7.0 },
                improvementSecPerSession = slope?.let { (it * 7.0) / observedFreqPerWeek },
                estimatedDaysToTarget = 0,
                optimisticDaysToTarget = 0,
                conservativeDaysToTarget = 0,
                estimatedSessionsToTarget = 0,
                projectedTargetDateIso = dailyPoints.first { it.bestEquiv1600mSec <= targetTimeSec }.dateIso,
                practiceDaysPerWeek = observedFreqPerWeek,
                qualifiedRunCount = qualifiedRuns,
                coachSummary = "QUALIFIED! Personal best ${TsPoliceBenchmark.formatDuration(pbSec, true)} beats the 7:15 cutoff by ${String.format("%.1fs", margin)} ($qualifiedRuns qualifying session${if (qualifiedRuns == 1) "" else "s"}).",
                workoutPrescription = "Lock in consistency: practice negative splits (Lap 1-2 at 1:49, Lap 3-4 at 1:45) so race-day heat or wind never threatens your 7:15 margin."
            )
        }

        val gapSec = (currentCapabilitySec - targetTimeSec).coerceAtLeast(0.1)

        // Single practice day logged so far
        if (dailyPoints.size == 1) {
            val neededPerWeek4w = gapSec / 4.0
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
                coachSummary = "Baseline recorded: ${TsPoliceBenchmark.formatDuration(pbSec)} (${String.format("%.0fs", gapSec)} above 7:15). Log 1 more practice day to calculate your personal improvement speed & target date!",
                workoutPrescription = "Shaving ${String.format("%.1fs", neededPerWeek4w)}/week hits 7:15 in 4 weeks. Next workout: 5 × 400m intervals at 1:48 with 90s walk recovery."
            )
        }

        val rateSecPerDay = computeWeightedImprovementRatePerDay(dailyPoints, pbSec)
        val rateSecPerWeek = rateSecPerDay * 7.0
        val rateSecPerSession = rateSecPerWeek / observedFreqPerWeek

        if (rateSecPerDay <= 0.08) {
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
                coachSummary = "Recent pace trend is flat (${String.format("%+.1fs/wk", -rateSecPerWeek)}); ${String.format("%.0fs", gapSec)} left to reach 7:15 from your ${TsPoliceBenchmark.formatDuration(currentCapabilitySec)} capability.",
                workoutPrescription = "Plateau breaker: alternate 1 interval day (6 × 400m @ 1:46) with 1 easy 3 km aerobic jog (keep Running share >75% on trial days)."
            )
        }

        // Cap extreme 2-day improvement spikes to physiological upper bound (~6.0 sec/day sustained)
        val effectiveRatePerDay = rateSecPerDay.coerceAtMost(6.0)
        val linearDays = gapSec / effectiveRatePerDay
        // Physiological taper multiplier: shaving the final seconds near 7:15 takes ~18% more time than linear extrapolation
        val taperMultiplier = if (currentCapabilitySec < 470.0) 1.22 else 1.15
        val realisticDays = ceil(linearDays * taperMultiplier).toInt().coerceIn(1, 730)
        val optimisticDays = ceil(linearDays).toInt().coerceIn(1, realisticDays)
        val conservativeDays = ceil(linearDays * 1.32).toInt().coerceIn(realisticDays, 900)
        val estSessions = ceil((realisticDays / 7.0) * observedFreqPerWeek).toInt().coerceAtLeast(1)

        val anchorDay = max(todayEpoch, dailyPoints.last().epochDay)
        val targetDateIso = LocalDate.ofEpochDay(anchorDay + realisticDays).toString()

        val targetLap = TsPoliceBenchmark.targetLap400mSec(targetTimeSec)
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
            improvementSecPerDay = rateSecPerDay,
            improvementSecPerWeek = rateSecPerWeek,
            improvementSecPerSession = rateSecPerSession,
            estimatedDaysToTarget = realisticDays,
            optimisticDaysToTarget = optimisticDays,
            conservativeDaysToTarget = conservativeDays,
            estimatedSessionsToTarget = estSessions,
            projectedTargetDateIso = targetDateIso,
            practiceDaysPerWeek = observedFreqPerWeek,
            qualifiedRunCount = 0,
            coachSummary = "Improving by ${String.format("%.1fs/week", rateSecPerWeek)} (${String.format("%.1fs", gapSec)} left). At this practice pace, you'll break 7:15 in ~$realisticDays days ($optimisticDays–$conservativeDays days, ~$estSessions sessions) around $targetDateIso.",
            workoutPrescription = "Current 400m lap avg is ${TsPoliceBenchmark.formatDuration(currentLap, true)} vs ${TsPoliceBenchmark.formatDuration(targetLap, true)} target. Focus next track session on holding 1:48/lap through 1,200m."
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
