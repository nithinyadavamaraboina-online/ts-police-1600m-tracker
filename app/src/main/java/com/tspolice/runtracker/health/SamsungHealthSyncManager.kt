package com.tspolice.runtracker.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.tspolice.runtracker.data.RunRepository
import com.tspolice.runtracker.domain.ExternalRunWorkout
import com.tspolice.runtracker.domain.GaitClassifier
import com.tspolice.runtracker.domain.HealthSyncStatus
import com.tspolice.runtracker.domain.RunMode
import com.tspolice.runtracker.domain.RunSessionRecord
import com.tspolice.runtracker.domain.SamsungDailySnapshot
import com.tspolice.runtracker.domain.SamsungHealthComparator
import com.tspolice.runtracker.domain.SamsungHourlyBucket
import com.tspolice.runtracker.domain.TsPoliceBenchmark
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

data class HealthSyncOutcome(
    val status: HealthSyncStatus,
    val snapshot: SamsungDailySnapshot? = null,
    val yesterdaySnapshot: SamsungDailySnapshot? = null,
    val importedRunCount: Int = 0,
    val updatedStrideScale: Double = 1.0,
    val statusMessage: String = ""
)

data class DeepSamsungTuningDataset(
    val syncOutcome: HealthSyncOutcome,
    val sevenDaySnapshots: Map<String, SamsungDailySnapshot> = emptyMap(),
    val intradayHourlyBuckets: Map<String, SamsungHourlyBucket> = emptyMap(),
    val rawStepsRecordCount48h: Int = 0,
    val rawDistanceRecordCount48h: Int = 0,
    val rawExerciseRecordCount48h: Int = 0,
    val avgStepBucketMinutes: Double = 0.0,
    val grantedPermissions: List<String> = emptyList()
)

/**
 * Syncs Yesterday + Today whole-day Steps, Distance, Walk/Run Exercise Sessions, Speed, and Cadence
 * from Samsung Health via Android Health Connect, compares against TS Police Tracker sensors,
 * calibrates personal stride length across the 2-day window, and backfills 14-day run history.
 */
class SamsungHealthSyncManager(
    private val context: Context,
    private val repository: RunRepository
) {
    companion object {
        val ALL_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(SpeedRecord::class),
            HealthPermission.getReadPermission(StepsCadenceRecord::class)
        )

        private val MINIMUM_REQUIRED_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class)
        )

        fun createPermissionRequestContract() =
            PermissionController.createRequestPermissionResultContract()
    }

    fun isSamsungHealthInstalled(): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(SamsungHealthComparator.SAMSUNG_HEALTH_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    fun getSdkStatus(): Int {
        return runCatching { HealthConnectClient.getSdkStatus(context) }
            .getOrDefault(HealthConnectClient.SDK_UNAVAILABLE)
    }

    fun openSamsungHealthOrHealthConnectSettings() {
        val hcIntent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(hcIntent) }.isSuccess) {
            return
        }
        val launchSamsung = context.packageManager.getLaunchIntentForPackage(
            SamsungHealthComparator.SAMSUNG_HEALTH_PACKAGE
        )?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        if (launchSamsung != null && runCatching { context.startActivity(launchSamsung) }.isSuccess) {
            return
        }
        val playIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=${SamsungHealthComparator.HEALTH_CONNECT_PACKAGE}")
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { context.startActivity(playIntent) }
    }

    fun openSamsungHealthAppDirectly(): Boolean {
        val launchSamsung = context.packageManager.getLaunchIntentForPackage(
            SamsungHealthComparator.SAMSUNG_HEALTH_PACKAGE
        )?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) } ?: return false
        return runCatching {
            context.startActivity(launchSamsung)
            true
        }.getOrDefault(false)
    }

    /**
     * Pulls both Yesterday (`today - 1`) and Today (`today`) from Samsung Health / Health Connect,
     * caches both daily snapshots, calibrates personal stride from the 2-day ground truth, and
     * backfills 14 days of non-overlapping running workouts.
     */
    suspend fun syncTodayAndCalibrate(): HealthSyncOutcome {
        val sdkStatus = getSdkStatus()
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            val msg = if (sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
                "Android Health Connect needs an update from Play Store to sync Samsung Health."
            } else {
                "Android Health Connect is not installed on this device."
            }
            return HealthSyncOutcome(
                status = HealthSyncStatus.NOT_INSTALLED,
                updatedStrideScale = repository.strideCalibrationScale,
                statusMessage = msg
            )
        }

        val client = runCatching { HealthConnectClient.getOrCreate(context) }.getOrElse { err ->
            return HealthSyncOutcome(
                status = HealthSyncStatus.ERROR,
                updatedStrideScale = repository.strideCalibrationScale,
                statusMessage = "Could not connect to Health Connect: ${err.localizedMessage ?: "Unknown error"}"
            )
        }

        val granted = runCatching { client.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())
        if (!granted.containsAll(MINIMUM_REQUIRED_PERMISSIONS)) {
            return HealthSyncOutcome(
                status = HealthSyncStatus.PERMISSION_REQUIRED,
                updatedStrideScale = repository.strideCalibrationScale,
                statusMessage = "Tap 'Connect Samsung Health' to grant Steps, Distance & Run read access."
            )
        }

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)
        val todayIso = today.toString()
        val yesterdayIso = yesterday.toString()

        return try {
            val twoDayStart = yesterday.atStartOfDay(zone).toInstant()
            val nowInstant = Instant.now().let { if (it.isAfter(twoDayStart)) it else twoDayStart.plusSeconds(1) }
            val twoDayFilter = TimeRangeFilter.between(twoDayStart, nowInstant)
            val selfPkg = context.packageName
            val canReadExercise = HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted

            val twoDaySteps = client.readRecords(
                ReadRecordsRequest(StepsRecord::class, timeRangeFilter = twoDayFilter)
            ).records.filter { it.metadata.dataOrigin.packageName != selfPkg }

            val twoDayDistances = client.readRecords(
                ReadRecordsRequest(DistanceRecord::class, timeRangeFilter = twoDayFilter)
            ).records.filter { it.metadata.dataOrigin.packageName != selfPkg }

            val twoDayExercises = if (canReadExercise) {
                runCatching {
                    client.readRecords(
                        ReadRecordsRequest(ExerciseSessionRecord::class, timeRangeFilter = twoDayFilter)
                    ).records.filter { it.metadata.dataOrigin.packageName != selfPkg }
                }.getOrDefault(emptyList())
            } else emptyList()

            var hasSamsungData =
                twoDaySteps.any { it.metadata.dataOrigin.packageName == SamsungHealthComparator.SAMSUNG_HEALTH_PACKAGE } ||
                twoDayDistances.any { it.metadata.dataOrigin.packageName == SamsungHealthComparator.SAMSUNG_HEALTH_PACKAGE } ||
                twoDayExercises.any { it.metadata.dataOrigin.packageName == SamsungHealthComparator.SAMSUNG_HEALTH_PACKAGE }

            val hasTwoDayExternal = twoDaySteps.isNotEmpty() || twoDayDistances.isNotEmpty() || twoDayExercises.isNotEmpty()

            if (!hasTwoDayExternal) {
                val weekFilter = TimeRangeFilter.between(today.minusDays(7).atStartOfDay(zone).toInstant(), nowInstant)
                val recentSamsungSteps = runCatching {
                    client.readRecords(
                        ReadRecordsRequest(
                            StepsRecord::class,
                            timeRangeFilter = weekFilter,
                            dataOriginFilter = setOf(DataOrigin(SamsungHealthComparator.SAMSUNG_HEALTH_PACKAGE))
                        )
                    ).records
                }.getOrDefault(emptyList())

                if (recentSamsungSteps.isEmpty()) {
                    return HealthSyncOutcome(
                        status = HealthSyncStatus.WAITING_FOR_SAMSUNG_SYNC,
                        updatedStrideScale = repository.strideCalibrationScale,
                        statusMessage = "Permissions granted! Open Samsung Health → ⋮ → Settings → Health Connect and turn ON sync."
                    )
                } else {
                    hasSamsungData = true
                }
            }

            val targetOrigins: Set<DataOrigin> = if (hasSamsungData) {
                setOf(DataOrigin(SamsungHealthComparator.SAMSUNG_HEALTH_PACKAGE))
            } else {
                val pkgs = buildSet {
                    twoDaySteps.forEach { add(DataOrigin(it.metadata.dataOrigin.packageName)) }
                    twoDayDistances.forEach { add(DataOrigin(it.metadata.dataOrigin.packageName)) }
                    twoDayExercises.forEach { add(DataOrigin(it.metadata.dataOrigin.packageName)) }
                }
                pkgs.ifEmpty { setOf(DataOrigin(SamsungHealthComparator.SAMSUNG_HEALTH_PACKAGE)) }
            }

            val sourcePkg = targetOrigins.first().packageName
            val sourceLabel = if (sourcePkg == SamsungHealthComparator.SAMSUNG_HEALTH_PACKAGE) {
                "Samsung Health"
            } else {
                "Health Connect"
            }

            // Pull Yesterday [00:00..24:00) AND Today [00:00..now) cleanly
            val yesterdayStart = yesterday.atStartOfDay(zone).toInstant()
            val todayStart = today.atStartOfDay(zone).toInstant()
            val safeNow = if (nowInstant.isAfter(todayStart)) nowInstant else todayStart.plusSeconds(1)

            val yesterdaySnapshot = pullDaySnapshot(
                client = client,
                granted = granted,
                dateIso = yesterdayIso,
                windowStart = yesterdayStart,
                windowEnd = todayStart,
                zone = zone,
                targetOrigins = targetOrigins,
                sourcePkg = sourcePkg,
                sourceLabel = sourceLabel
            )

            val todaySnapshot = pullDaySnapshot(
                client = client,
                granted = granted,
                dateIso = todayIso,
                windowStart = todayStart,
                windowEnd = safeNow,
                zone = zone,
                targetOrigins = targetOrigins,
                sourcePkg = sourcePkg,
                sourceLabel = sourceLabel
            )

            repository.saveSamsungSnapshot(yesterdaySnapshot)
            repository.saveSamsungSnapshot(todaySnapshot)

            // Auto-calibrate personal stride scale from Yesterday + Today combined ground truth
            val localYesterday = repository.getDailyActivity(yesterdayIso)
            val localToday = repository.getDailyActivity(todayIso)
            var currentScale = repository.strideCalibrationScale
            if (repository.autoCalibrateFromSamsung) {
                currentScale = SamsungHealthComparator.computeMultiDayCalibratedStrideScale(
                    yesterdayTracker = localYesterday,
                    yesterdaySamsung = yesterdaySnapshot,
                    todayTracker = localToday,
                    todaySamsung = todaySnapshot,
                    currentStrideScale = currentScale
                )
                repository.strideCalibrationScale = currentScale
                val combinedSteps = yesterdaySnapshot.steps + todaySnapshot.steps
                val combinedDist = yesterdaySnapshot.distanceMeters + todaySnapshot.distanceMeters
                if (combinedSteps >= 200 && combinedDist >= 110.0) {
                    repository.hasSamsungStrideCalibration = true
                }
            }

            // Backfill non-overlapping Samsung Health / Galaxy Watch runs (>= 600m) from the last 14 days
            var importedCount = 0
            if (repository.autoImportSamsungRuns && canReadExercise) {
                val twoWeekStart = today.minusDays(14).atStartOfDay(zone).toInstant()
                val historyExercises = runCatching {
                    client.readRecords(
                        ReadRecordsRequest(
                            ExerciseSessionRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(twoWeekStart, safeNow),
                            dataOriginFilter = targetOrigins
                        )
                    ).records
                }.getOrDefault(emptyList())

                val candidateWorkouts = mutableListOf<ExternalRunWorkout>()
                candidateWorkouts.addAll(yesterdaySnapshot.workouts)
                candidateWorkouts.addAll(todaySnapshot.workouts)

                for (ex in historyExercises) {
                    if (ex.exerciseType != ExerciseSessionRecord.EXERCISE_TYPE_RUNNING &&
                        ex.exerciseType != ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
                    ) continue
                    val extId = ex.metadata.id.ifBlank { "${sourcePkg}_${ex.startTime.toEpochMilli()}" }
                    if (candidateWorkouts.any { it.externalId == extId } || repository.isExternalWorkoutImported(extId)) continue
                    val durSec = (ex.endTime.toEpochMilli() - ex.startTime.toEpochMilli()) / 1000.0
                    if (durSec < 60.0) continue
                    val sessionAgg = runCatching {
                        client.aggregate(
                            AggregateRequest(
                                metrics = setOf(DistanceRecord.DISTANCE_TOTAL, StepsRecord.COUNT_TOTAL),
                                timeRangeFilter = TimeRangeFilter.between(ex.startTime, ex.endTime),
                                dataOriginFilter = targetOrigins
                            )
                        )
                    }.getOrNull()
                    val exDistM = sessionAgg?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters ?: 0.0
                    if (exDistM >= 600.0) {
                        val exSteps = sessionAgg?.get(StepsRecord.COUNT_TOTAL)?.toDouble() ?: 0.0
                        val avgCad = if (exSteps > 0.0) (exSteps / durSec) * 60.0 else 164.0
                        val avgSpd = exDistM / durSec
                        candidateWorkouts.add(
                            ExternalRunWorkout(
                                externalId = extId,
                                sourcePackage = sourcePkg,
                                startedAtEpochMs = ex.startTime.toEpochMilli(),
                                dateIso = ex.startTime.atZone(zone).toLocalDate().toString(),
                                durationSec = durSec,
                                distanceMeters = exDistM,
                                maxSpeedMps = (avgSpd * 1.08).coerceAtMost(GaitClassifier.MAX_HUMAN_RUNNING_SPEED_MPS),
                                avgCadenceSpm = avgCad.coerceIn(130.0, 210.0),
                                title = ex.title?.takeIf { it.isNotBlank() } ?: "$sourceLabel Run"
                            )
                        )
                    }
                }

                for (w in candidateWorkouts) {
                    if (w.distanceMeters >= 600.0 &&
                        !repository.isExternalWorkoutImported(w.externalId) &&
                        !repository.hasSessionNear(w.startedAtEpochMs)
                    ) {
                        val is1600 = kotlin.math.abs(w.distanceMeters - 1600.0) <= 45.0
                        repository.insertSession(
                            RunSessionRecord(
                                startedAtEpochMs = w.startedAtEpochMs,
                                dateIso = w.dateIso,
                                mode = if (is1600) RunMode.TIMED_1600M else RunMode.OPEN_PRACTICE,
                                distanceMeters = w.distanceMeters,
                                activeDurationSec = w.durationSec,
                                pausedDurationSec = 0.0,
                                first1600mSplitSec = if (is1600) w.durationSec else null,
                                equiv1600mSec = TsPoliceBenchmark.computeEquiv1600mSec(
                                    w.distanceMeters,
                                    w.durationSec,
                                    if (is1600) w.durationSec else null
                                ),
                                avgCadenceSpm = w.avgCadenceSpm,
                                maxSpeedMps = w.maxSpeedMps,
                                runningSec = w.durationSec * 0.75,
                                joggingSec = w.durationSec * 0.25,
                                walkingSec = 0.0,
                                notes = "Synced from ${w.title}",
                                isDemo = false
                            )
                        )
                        repository.markExternalWorkoutImported(w.externalId)
                        importedCount++
                    }
                }
            }

            HealthSyncOutcome(
                status = HealthSyncStatus.SYNCED,
                snapshot = todaySnapshot,
                yesterdaySnapshot = yesterdaySnapshot,
                importedRunCount = importedCount,
                updatedStrideScale = currentScale,
                statusMessage = "Pulled Yesterday & Today from $sourceLabel"
            )
        } catch (e: Exception) {
            HealthSyncOutcome(
                status = HealthSyncStatus.ERROR,
                snapshot = repository.getCachedSamsungSnapshot(todayIso),
                yesterdaySnapshot = repository.getCachedSamsungSnapshot(yesterdayIso),
                updatedStrideScale = repository.strideCalibrationScale,
                statusMessage = "Sync error: ${e.localizedMessage ?: "Failed reading Health Connect"}"
            )
        }
    }

    private suspend fun pullDaySnapshot(
        client: HealthConnectClient,
        granted: Set<String>,
        dateIso: String,
        windowStart: Instant,
        windowEnd: Instant,
        zone: ZoneId,
        targetOrigins: Set<DataOrigin>,
        sourcePkg: String,
        sourceLabel: String
    ): SamsungDailySnapshot {
        val dayFilter = TimeRangeFilter.between(windowStart, windowEnd)
        val canReadExercise = HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted

        val agg = runCatching {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL, DistanceRecord.DISTANCE_TOTAL),
                    timeRangeFilter = dayFilter,
                    dataOriginFilter = targetOrigins
                )
            )
        }.getOrNull()

        val scopedSteps = runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    StepsRecord::class,
                    timeRangeFilter = dayFilter,
                    dataOriginFilter = targetOrigins
                )
            ).records
        }.getOrDefault(emptyList())

        val scopedDistances = runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    DistanceRecord::class,
                    timeRangeFilter = dayFilter,
                    dataOriginFilter = targetOrigins
                )
            ).records
        }.getOrDefault(emptyList())

        val scopedExercises = if (canReadExercise) {
            runCatching {
                client.readRecords(
                    ReadRecordsRequest(
                        ExerciseSessionRecord::class,
                        timeRangeFilter = dayFilter,
                        dataOriginFilter = targetOrigins
                    )
                ).records
            }.getOrDefault(emptyList())
        } else emptyList()

        val totalSteps = (agg?.get(StepsRecord.COUNT_TOTAL)?.toInt())
            ?: scopedSteps.sumOf { it.count }.toInt()
        val rawDistMeters = agg?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters
            ?: scopedDistances.sumOf { it.distance.inMeters }
        val totalDistanceM = if (rawDistMeters > 1.0) rawDistMeters else (totalSteps * 0.76)

        val walkIntervals = mutableListOf<Pair<Long, Long>>()
        val runIntervals = mutableListOf<Pair<Long, Long>>()
        val runWorkouts = mutableListOf<ExternalRunWorkout>()

        for (ex in scopedExercises) {
            val sMs = max(ex.startTime.toEpochMilli(), windowStart.toEpochMilli())
            val eMs = min(ex.endTime.toEpochMilli(), windowEnd.toEpochMilli())
            if (eMs <= sMs) continue

            when (ex.exerciseType) {
                ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
                ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> {
                    runIntervals.add(sMs to eMs)
                    val durSec = (eMs - sMs) / 1000.0
                    val sessionAgg = runCatching {
                        client.aggregate(
                            AggregateRequest(
                                metrics = setOf(DistanceRecord.DISTANCE_TOTAL, StepsRecord.COUNT_TOTAL),
                                timeRangeFilter = TimeRangeFilter.between(ex.startTime, ex.endTime),
                                dataOriginFilter = targetOrigins
                            )
                        )
                    }.getOrNull()
                    val exDistM = sessionAgg?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters ?: 0.0
                    val exSteps = sessionAgg?.get(StepsRecord.COUNT_TOTAL)?.toDouble() ?: 0.0
                    val avgCad = if (durSec > 10.0 && exSteps > 0.0) (exSteps / durSec) * 60.0 else 164.0
                    val avgSpd = if (durSec > 5.0 && exDistM > 0.0) exDistM / durSec else 0.0
                    if (exDistM >= 400.0 && durSec >= 60.0) {
                        runWorkouts.add(
                            ExternalRunWorkout(
                                externalId = ex.metadata.id.ifBlank { "${sourcePkg}_${ex.startTime.toEpochMilli()}" },
                                sourcePackage = sourcePkg,
                                startedAtEpochMs = ex.startTime.toEpochMilli(),
                                dateIso = ex.startTime.atZone(zone).toLocalDate().toString(),
                                durationSec = durSec,
                                distanceMeters = exDistM,
                                maxSpeedMps = (avgSpd * 1.08).coerceAtMost(GaitClassifier.MAX_HUMAN_RUNNING_SPEED_MPS),
                                avgCadenceSpm = avgCad.coerceIn(130.0, 210.0),
                                title = ex.title?.takeIf { it.isNotBlank() } ?: "$sourceLabel Run"
                            )
                        )
                    }
                }

                ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
                ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> {
                    walkIntervals.add(sMs to eMs)
                }
            }
        }

        val mergedRunMs = mergeIntervals(runIntervals)
        val mergedWalkMs = mergeIntervals(walkIntervals)
        val allSessionIntervals = mergeIntervals(mergedRunMs + mergedWalkMs)

        var jogRunSec = mergedRunMs.sumOf { (s, e) -> (e - s) / 1000.0 }
        var walkSec = mergedWalkMs.sumOf { (s, e) -> (e - s) / 1000.0 }

        for (stepRec in scopedSteps) {
            val sMs = max(stepRec.startTime.toEpochMilli(), windowStart.toEpochMilli())
            val eMs = min(stepRec.endTime.toEpochMilli(), windowEnd.toEpochMilli())
            if (eMs <= sMs || stepRec.count < 6L) continue
            if (overlapsAny(sMs, eMs, allSessionIntervals)) continue

            val bucketDurSec = Duration.between(
                Instant.ofEpochMilli(sMs),
                Instant.ofEpochMilli(eMs)
            ).toMillis() / 1000.0
            if (bucketDurSec <= 0.5) continue

            val bucketCadenceSpm = (stepRec.count.toDouble() / bucketDurSec) * 60.0
            if (bucketDurSec <= 900.0 && bucketCadenceSpm >= 132.0) {
                jogRunSec += min(bucketDurSec, stepRec.count / 2.50)
            } else {
                walkSec += min(bucketDurSec, stepRec.count / 1.70)
            }
        }

        var maxSpeedMps = runWorkouts.maxOfOrNull { it.maxSpeedMps } ?: 0.0
        if (HealthPermission.getReadPermission(SpeedRecord::class) in granted) {
            runCatching {
                val speedRecords = client.readRecords(
                    ReadRecordsRequest(
                        SpeedRecord::class,
                        timeRangeFilter = dayFilter,
                        dataOriginFilter = targetOrigins
                    )
                ).records
                for (sr in speedRecords) {
                    for (sample in sr.samples) {
                        val mps = sample.speed.inMetersPerSecond
                        if (mps in 0.55..GaitClassifier.MAX_HUMAN_RUNNING_SPEED_MPS && mps > maxSpeedMps) {
                            maxSpeedMps = mps
                        }
                    }
                }
            }
        }

        var maxCadenceSpm = runWorkouts.maxOfOrNull { it.avgCadenceSpm } ?: 0.0
        if (HealthPermission.getReadPermission(StepsCadenceRecord::class) in granted) {
            runCatching {
                val cadRecords = client.readRecords(
                    ReadRecordsRequest(
                        StepsCadenceRecord::class,
                        timeRangeFilter = dayFilter,
                        dataOriginFilter = targetOrigins
                    )
                ).records
                for (cr in cadRecords) {
                    for (sample in cr.samples) {
                        if (sample.rate in 68.0..235.0 && sample.rate > maxCadenceSpm) {
                            maxCadenceSpm = sample.rate
                        }
                    }
                }
            }
        }

        return SamsungDailySnapshot(
            dateIso = dateIso,
            steps = totalSteps,
            distanceMeters = totalDistanceM,
            walkingSec = walkSec,
            jogRunSec = jogRunSec,
            maxSpeedMps = maxSpeedMps,
            maxCadenceSpm = maxCadenceSpm,
            sourcePackage = sourcePkg,
            sourceLabel = sourceLabel,
            syncedAtEpochMs = System.currentTimeMillis(),
            workouts = runWorkouts
        )
    }

    private fun mergeIntervals(raw: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        if (raw.isEmpty()) return emptyList()
        val sorted = raw.sortedBy { it.first }
        val merged = mutableListOf<Pair<Long, Long>>()
        var curStart = sorted[0].first
        var curEnd = sorted[0].second
        for (i in 1 until sorted.size) {
            val (nextStart, nextEnd) = sorted[i]
            if (nextStart <= curEnd) {
                curEnd = max(curEnd, nextEnd)
            } else {
                merged.add(curStart to curEnd)
                curStart = nextStart
                curEnd = nextEnd
            }
        }
        merged.add(curStart to curEnd)
        return merged
    }

    private fun overlapsAny(startMs: Long, endMs: Long, intervals: List<Pair<Long, Long>>): Boolean {
        for ((s, e) in intervals) {
            if (startMs < e && endMs > s) return true
        }
        return false
    }

    /**
     * Deep 7-day + 48-hour intraday Samsung Health pull used specifically for algorithm fine-tuning.
     * Pulls 7 days of daily snapshots plus hour-by-hour (00..23) Steps, Distance, Walk/Run durations,
     * Peak Speed, and Peak Cadence across Yesterday & Today!
     */
    suspend fun pullFineTuningDataset(): DeepSamsungTuningDataset {
        val baseOutcome = syncTodayAndCalibrate()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)

        if (getSdkStatus() != HealthConnectClient.SDK_AVAILABLE) {
            return DeepSamsungTuningDataset(
                syncOutcome = baseOutcome,
                sevenDaySnapshots = repository.getAllCachedSamsungSnapshots()
            )
        }
        val client = runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
            ?: return DeepSamsungTuningDataset(
                syncOutcome = baseOutcome,
                sevenDaySnapshots = repository.getAllCachedSamsungSnapshots()
            )
        val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
        if (!granted.containsAll(MINIMUM_REQUIRED_PERMISSIONS)) {
            return DeepSamsungTuningDataset(
                syncOutcome = baseOutcome,
                sevenDaySnapshots = repository.getAllCachedSamsungSnapshots(),
                grantedPermissions = granted.sorted()
            )
        }

        val sourcePkg = baseOutcome.snapshot?.sourcePackage
            ?: baseOutcome.yesterdaySnapshot?.sourcePackage
            ?: SamsungHealthComparator.SAMSUNG_HEALTH_PACKAGE
        val sourceLabel = baseOutcome.snapshot?.sourceLabel
            ?: baseOutcome.yesterdaySnapshot?.sourceLabel
            ?: "Samsung Health"
        val targetOrigins = setOf(DataOrigin(sourcePkg))

        // 1. Pull full 7-day daily snapshots (Days -6..-2; Yesterday & Today already pulled in baseOutcome)
        val sevenDays = LinkedHashMap<String, SamsungDailySnapshot>()
        val nowInstant = Instant.now()
        for (daysAgo in 6 downTo 2) {
            val d = today.minusDays(daysAgo.toLong())
            val dIso = d.toString()
            val wStart = d.atStartOfDay(zone).toInstant()
            val wEnd = d.plusDays(1).atStartOfDay(zone).toInstant()
            runCatching {
                val snap = pullDaySnapshot(
                    client = client,
                    granted = granted,
                    dateIso = dIso,
                    windowStart = wStart,
                    windowEnd = wEnd,
                    zone = zone,
                    targetOrigins = targetOrigins,
                    sourcePkg = sourcePkg,
                    sourceLabel = sourceLabel
                )
                repository.saveSamsungSnapshot(snap)
                sevenDays[dIso] = snap
            }
        }
        baseOutcome.yesterdaySnapshot?.let { sevenDays[yesterday.toString()] = it }
        baseOutcome.snapshot?.let { sevenDays[today.toString()] = it }

        // 2. Pull 48-hour raw records for Yesterday + Today and build exact hourly buckets (00..23)
        val yestStart = yesterday.atStartOfDay(zone).toInstant()
        val safeNow = if (nowInstant.isAfter(yestStart)) nowInstant else yestStart.plusSeconds(1)
        val filter48h = TimeRangeFilter.between(yestStart, safeNow)

        val steps48h = runCatching {
            client.readRecords(ReadRecordsRequest(StepsRecord::class, timeRangeFilter = filter48h, dataOriginFilter = targetOrigins)).records
        }.getOrDefault(emptyList())

        val dist48h = runCatching {
            client.readRecords(ReadRecordsRequest(DistanceRecord::class, timeRangeFilter = filter48h, dataOriginFilter = targetOrigins)).records
        }.getOrDefault(emptyList())

        val canReadEx = HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted
        val ex48h = if (canReadEx) {
            runCatching {
                client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, timeRangeFilter = filter48h, dataOriginFilter = targetOrigins)).records
            }.getOrDefault(emptyList())
        } else emptyList()

        val spd48h = if (HealthPermission.getReadPermission(SpeedRecord::class) in granted) {
            runCatching {
                client.readRecords(ReadRecordsRequest(SpeedRecord::class, timeRangeFilter = filter48h, dataOriginFilter = targetOrigins)).records
            }.getOrDefault(emptyList())
        } else emptyList()

        val cad48h = if (HealthPermission.getReadPermission(StepsCadenceRecord::class) in granted) {
            runCatching {
                client.readRecords(ReadRecordsRequest(StepsCadenceRecord::class, timeRangeFilter = filter48h, dataOriginFilter = targetOrigins)).records
            }.getOrDefault(emptyList())
        } else emptyList()

        val avgBucketMins = if (steps48h.isNotEmpty()) {
            steps48h.map { max(1L, (it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 1000L) / 60.0 }.average()
        } else 0.0

        val hourlyMap = LinkedHashMap<String, SamsungHourlyBucket>()
        for (date in listOf(yesterday, today)) {
            val dIso = date.toString()
            for (hr in 0..23) {
                val hStartMs = date.atTime(hr, 0).atZone(zone).toInstant().toEpochMilli()
                val hEndMs = date.atTime(hr, 0).plusHours(1).atZone(zone).toInstant().toEpochMilli()
                if (hStartMs >= safeNow.toEpochMilli()) {
                    hourlyMap[String.format("%sT%02d", dIso, hr)] = SamsungHourlyBucket(dateIso = dIso, hourOfDay = hr)
                    continue
                }

                var hSteps = 0.0
                var hWalkSec = 0.0
                var hJogRunSec = 0.0
                for (sr in steps48h) {
                    val rStart = sr.startTime.toEpochMilli()
                    val rEnd = sr.endTime.toEpochMilli()
                    val ovStart = max(rStart, hStartMs)
                    val ovEnd = min(rEnd, hEndMs)
                    if (ovEnd > ovStart) {
                        val totalDur = max(1L, rEnd - rStart).toDouble()
                        val frac = (ovEnd - ovStart) / totalDur
                        val partSteps = sr.count * frac
                        hSteps += partSteps
                        val durSec = (ovEnd - ovStart) / 1000.0
                        val cad = (sr.count.toDouble() / (totalDur / 1000.0)) * 60.0
                        if (totalDur <= 900_000.0 && cad >= 132.0) {
                            hJogRunSec += min(durSec, partSteps / 2.50)
                        } else if (sr.count >= 6L) {
                            hWalkSec += min(durSec, partSteps / 1.70)
                        }
                    }
                }

                var hDist = 0.0
                for (dr in dist48h) {
                    val rStart = dr.startTime.toEpochMilli()
                    val rEnd = dr.endTime.toEpochMilli()
                    val ovStart = max(rStart, hStartMs)
                    val ovEnd = min(rEnd, hEndMs)
                    if (ovEnd > ovStart) {
                        val frac = (ovEnd - ovStart) / max(1L, rEnd - rStart).toDouble()
                        hDist += dr.distance.inMeters * frac
                    }
                }
                if (hDist < 1.0 && hSteps >= 5.0) {
                    hDist = hSteps * 0.76
                }

                // Override/augment active seconds from explicit running/walking ExerciseSessionRecords in this hour
                var exRunSec = 0.0
                var exWalkSec = 0.0
                for (ex in ex48h) {
                    val ovStart = max(ex.startTime.toEpochMilli(), hStartMs)
                    val ovEnd = min(ex.endTime.toEpochMilli(), hEndMs)
                    if (ovEnd > ovStart) {
                        val sec = (ovEnd - ovStart) / 1000.0
                        when (ex.exerciseType) {
                            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
                            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> exRunSec += sec
                            ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
                            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> exWalkSec += sec
                        }
                    }
                }
                if (exRunSec > hJogRunSec) hJogRunSec = exRunSec
                if (exWalkSec > hWalkSec) hWalkSec = exWalkSec

                var hMaxSpeed = 0.0
                for (sr in spd48h) {
                    for (s in sr.samples) {
                        val t = s.time.toEpochMilli()
                        val v = s.speed.inMetersPerSecond
                        if (t in hStartMs until hEndMs && v in 0.5..GaitClassifier.MAX_HUMAN_RUNNING_SPEED_MPS && v > hMaxSpeed) {
                            hMaxSpeed = v
                        }
                    }
                }

                var hMaxCad = 0.0
                for (cr in cad48h) {
                    for (s in cr.samples) {
                        val t = s.time.toEpochMilli()
                        if (t in hStartMs until hEndMs && s.rate in 68.0..235.0 && s.rate > hMaxCad) {
                            hMaxCad = s.rate
                        }
                    }
                }

                hourlyMap[String.format("%sT%02d", dIso, hr)] = SamsungHourlyBucket(
                    dateIso = dIso,
                    hourOfDay = hr,
                    steps = kotlin.math.round(hSteps).toInt(),
                    distanceMeters = hDist,
                    walkSec = hWalkSec,
                    jogRunSec = hJogRunSec,
                    maxSpeedMps = hMaxSpeed,
                    maxCadenceSpm = hMaxCad
                )
            }
        }

        return DeepSamsungTuningDataset(
            syncOutcome = baseOutcome,
            sevenDaySnapshots = sevenDays,
            intradayHourlyBuckets = hourlyMap,
            rawStepsRecordCount48h = steps48h.size,
            rawDistanceRecordCount48h = dist48h.size,
            rawExerciseRecordCount48h = ex48h.size,
            avgStepBucketMinutes = avgBucketMins,
            grantedPermissions = granted.sorted()
        )
    }
}
