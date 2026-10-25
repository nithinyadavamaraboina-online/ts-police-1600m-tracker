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
    val importedRunCount: Int = 0,
    val updatedStrideScale: Double = 1.0,
    val statusMessage: String = ""
)

/**
 * Syncs whole-day Steps, Distance, Walk/Run Exercise Sessions, Speed, and Cadence from Samsung Health
 * via Android Health Connect, compares against TS Police Tracker sensors, calibrates personal stride
 * length, and optionally imports missed Samsung Health / Galaxy Watch runs (>= 600m).
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
        // Prefer Health Connect settings screen so the user can see/toggle Samsung Health + TS Police 1.6K directly
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

        return try {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val todayIso = today.toString()
            val startOfDay = today.atStartOfDay(zone).toInstant()
            val nowInstant = Instant.now().let { if (it.isAfter(startOfDay)) it else startOfDay.plusSeconds(1) }
            val dayFilter = TimeRangeFilter.between(startOfDay, nowInstant)
            val selfPkg = context.packageName

            val allSteps = client.readRecords(
                ReadRecordsRequest(StepsRecord::class, timeRangeFilter = dayFilter)
            ).records.filter { it.metadata.dataOrigin.packageName != selfPkg }

            val allDistances = client.readRecords(
                ReadRecordsRequest(DistanceRecord::class, timeRangeFilter = dayFilter)
            ).records.filter { it.metadata.dataOrigin.packageName != selfPkg }

            val canReadExercise = HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted
            val allExercises = if (canReadExercise) {
                runCatching {
                    client.readRecords(
                        ReadRecordsRequest(ExerciseSessionRecord::class, timeRangeFilter = dayFilter)
                    ).records.filter { it.metadata.dataOrigin.packageName != selfPkg }
                }.getOrDefault(emptyList())
            } else emptyList()

            val hasSamsungData = allSteps.any { it.metadata.dataOrigin.packageName == SamsungHealthComparator.SAMSUNG_HEALTH_PACKAGE } ||
                allDistances.any { it.metadata.dataOrigin.packageName == SamsungHealthComparator.SAMSUNG_HEALTH_PACKAGE } ||
                allExercises.any { it.metadata.dataOrigin.packageName == SamsungHealthComparator.SAMSUNG_HEALTH_PACKAGE }

            val hasAnyExternalData = allSteps.isNotEmpty() || allDistances.isNotEmpty() || allExercises.isNotEmpty()

            if (!hasAnyExternalData) {
                // Check past 7 days to distinguish "0 steps so far at midnight" vs "Samsung Health sync toggle is OFF"
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
                        statusMessage = "Permissions granted! Now open Samsung Health → ⋮ → Settings → Health Connect and turn ON sync."
                    )
                }
            }

            val targetOrigins: Set<DataOrigin> = if (hasSamsungData) {
                setOf(DataOrigin(SamsungHealthComparator.SAMSUNG_HEALTH_PACKAGE))
            } else {
                val pkgs = buildSet {
                    allSteps.forEach { add(DataOrigin(it.metadata.dataOrigin.packageName)) }
                    allDistances.forEach { add(DataOrigin(it.metadata.dataOrigin.packageName)) }
                    allExercises.forEach { add(DataOrigin(it.metadata.dataOrigin.packageName)) }
                }
                pkgs.ifEmpty { setOf(DataOrigin(SamsungHealthComparator.SAMSUNG_HEALTH_PACKAGE)) }
            }

            val sourcePkg = targetOrigins.first().packageName
            val sourceLabel = if (sourcePkg == SamsungHealthComparator.SAMSUNG_HEALTH_PACKAGE) {
                "Samsung Health"
            } else {
                "Health Connect"
            }

            // Use Health Connect OS-level interval deduplication for total Steps & Distance
            val agg = runCatching {
                client.aggregate(
                    AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL, DistanceRecord.DISTANCE_TOTAL),
                        timeRangeFilter = dayFilter,
                        dataOriginFilter = targetOrigins
                    )
                )
            }.getOrNull()

            val scopedSteps = allSteps.filter { DataOrigin(it.metadata.dataOrigin.packageName) in targetOrigins }
            val scopedDistances = allDistances.filter { DataOrigin(it.metadata.dataOrigin.packageName) in targetOrigins }
            val scopedExercises = allExercises.filter { DataOrigin(it.metadata.dataOrigin.packageName) in targetOrigins }

            val totalSteps = (agg?.get(StepsRecord.COUNT_TOTAL)?.toInt())
                ?: scopedSteps.sumOf { it.count }.toInt()
            val rawDistMeters = agg?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters
                ?: scopedDistances.sumOf { it.distance.inMeters }
            // Fallback stride estimate only if Samsung wrote steps without DistanceRecord yet
            val totalDistanceM = if (rawDistMeters > 1.0) rawDistMeters else (totalSteps * 0.76)

            // Partition ExerciseSessionRecords into Walking vs Jogging/Running & merge overlapping intervals
            val walkIntervals = mutableListOf<Pair<Long, Long>>()
            val runIntervals = mutableListOf<Pair<Long, Long>>()
            val runWorkouts = mutableListOf<ExternalRunWorkout>()

            for (ex in scopedExercises) {
                val sMs = max(ex.startTime.toEpochMilli(), startOfDay.toEpochMilli())
                val eMs = min(ex.endTime.toEpochMilli(), nowInstant.toEpochMilli())
                if (eMs <= sMs) continue

                when (ex.exerciseType) {
                    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
                    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> {
                        runIntervals.add(sMs to eMs)
                        val durSec = (eMs - sMs) / 1000.0
                        val sessionFilter = TimeRangeFilter.between(ex.startTime, ex.endTime)
                        val sessionAgg = runCatching {
                            client.aggregate(
                                AggregateRequest(
                                    metrics = setOf(DistanceRecord.DISTANCE_TOTAL, StepsRecord.COUNT_TOTAL),
                                    timeRangeFilter = sessionFilter,
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

            // Add active step durations from passive pedometer buckets outside explicit workout sessions
            for (stepRec in scopedSteps) {
                val sMs = max(stepRec.startTime.toEpochMilli(), startOfDay.toEpochMilli())
                val eMs = min(stepRec.endTime.toEpochMilli(), nowInstant.toEpochMilli())
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

            // Read optional peak Speed & peak Cadence samples if permitted
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

            val snapshot = SamsungDailySnapshot(
                dateIso = todayIso,
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
            repository.saveSamsungSnapshot(snapshot)

            // Auto-calibrate personal stride scale from Samsung Health ground truth
            val localToday = repository.getDailyActivity(todayIso)
            var currentScale = repository.strideCalibrationScale
            if (repository.autoCalibrateFromSamsung) {
                currentScale = SamsungHealthComparator.computeCalibratedStrideScale(
                    tracker = localToday,
                    samsung = snapshot,
                    currentStrideScale = currentScale
                )
                repository.strideCalibrationScale = currentScale
            }

            // Optionally import non-overlapping Samsung Health / Galaxy Watch runs (>= 600m) into Progress & 7:15 ETA
            var importedCount = 0
            if (repository.autoImportSamsungRuns) {
                for (w in runWorkouts) {
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
                snapshot = snapshot,
                importedRunCount = importedCount,
                updatedStrideScale = currentScale,
                statusMessage = "Synced with $sourceLabel"
            )
        } catch (e: Exception) {
            HealthSyncOutcome(
                status = HealthSyncStatus.ERROR,
                snapshot = repository.getCachedSamsungSnapshot(LocalDate.now().toString()),
                updatedStrideScale = repository.strideCalibrationScale,
                statusMessage = "Sync error: ${e.localizedMessage ?: "Failed reading Health Connect"}"
            )
        }
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
}
