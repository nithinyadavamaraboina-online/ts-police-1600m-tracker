package com.tspolice.runtracker.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.tspolice.runtracker.data.RunRepository
import com.tspolice.runtracker.domain.CoachBrainReport
import com.tspolice.runtracker.domain.DailyActivitySummary
import com.tspolice.runtracker.domain.ExamTimeBatch
import com.tspolice.runtracker.domain.LegFeelCheckIn
import com.tspolice.runtracker.domain.RunMode
import com.tspolice.runtracker.domain.RunSessionRecord
import com.tspolice.runtracker.domain.SamsungDailySnapshot
import com.tspolice.runtracker.domain.SamsungHealthComparator
import com.tspolice.runtracker.domain.TsPoliceBenchmark
import com.tspolice.runtracker.telemetry.TuningPullAndUploadResult
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

object SessionDialogs {

    private fun dp(ctx: Context, v: Float): Int = AthleticTheme.dp(ctx, v)

    /**
     * Rich dark-card session inspection modal showing TSLPRB Merit Points, stopped-time penalty,
     * pace/cadence stats, and full 400m lap splits, plus confirmation-protected Delete.
     */
    fun showSessionDetailDialog(
        ctx: Context,
        session: RunSessionRecord,
        targetSec: Double,
        deadlineIso: String = "2026-11-30",
        isPersonalBest: Boolean = false,
        onDeleteConfirmed: () -> Unit
    ) {
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AthleticColors.SURFACE_BOTTOM)
            setPadding(dp(ctx, 20f), dp(ctx, 18f), dp(ctx, 20f), dp(ctx, 14f))
        }

        val merit = TsPoliceBenchmark.evaluateMerit1600m(session.equiv1600mSec, targetSec)
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(ctx).apply {
            text = "${session.mode.title.uppercase()}  •  ${session.dateIso}"
            textSize = 11f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.CYAN_BRIGHT)
            letterSpacing = 0.06f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(headerRow)

        // Big 1.6K Time + Merit Pill
        root.addView(TextView(ctx).apply {
            val isExact = abs(session.distanceMeters - 1600.0) <= 50.0
            val suffix = if (isExact) "Official 1.6K" else "1.6K Equiv"
            text = "${TsPoliceBenchmark.formatDuration(session.equiv1600mSec)}  ($suffix)"
            textSize = 26f
            typeface = AthleticTheme.FONT_TABULAR_BOLD
            setTextColor(if (merit.qualified) AthleticColors.EMERALD_BRIGHT else AthleticColors.TEXT_PRIMARY)
            setPadding(0, dp(ctx, 6f), 0, dp(ctx, 4f))
        })

        root.addView(TextView(ctx).apply {
            text = "TSLPRB Score: ${merit.tierLabel}" + (merit.nextTierTimeSec?.let {
                "  •  Next tier (${merit.nextTierPoints} pts) at ${TsPoliceBenchmark.formatDuration(it)}"
            } ?: "")
            textSize = 12f
            typeface = AthleticTheme.FONT_SANS_MEDIUM
            setTextColor(if (merit.qualified) AthleticColors.EMERALD_BRIGHT else AthleticColors.AMBER_BRIGHT)
            setPadding(dp(ctx, 10f), dp(ctx, 6f), dp(ctx, 10f), dp(ctx, 6f))
            background = AthleticTheme.solidPill(
                ctx,
                if (merit.qualified) 0x22C6F432 else 0x22FF9F1C,
                if (merit.qualified) AthleticColors.HERO_EMERALD_BORDER else AthleticColors.HERO_AMBER_BORDER,
                10f
            )
        })

        // Stats box
        val statsBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12f), dp(ctx, 10f), dp(ctx, 12f), dp(ctx, 10f))
            background = AthleticTheme.solidPill(ctx, AthleticColors.SURFACE_RECESSED, AthleticColors.BORDER_SUBTLE, 12f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 12f); bottomMargin = dp(ctx, 12f) }
        }
        val avgSpd = if (session.activeDurationSec > 1.0) session.distanceMeters / session.activeDurationSec else 0.0
        val statLines = buildList {
            add("Distance" to String.format("%.2f km (%d m)", session.distanceMeters / 1000.0, session.distanceMeters.toInt()))
            add("Elapsed Clock" to TsPoliceBenchmark.formatDuration(session.activeDurationSec))
            add("Average Pace" to TsPoliceBenchmark.formatPacePerKm(avgSpd))
            add("Peak Speed" to String.format("%.1f km/h (%s)", session.maxSpeedMps * 3.6, TsPoliceBenchmark.formatPacePerKm(session.maxSpeedMps)))
            add("Avg Cadence" to "${session.avgCadenceSpm.toInt()} spm")
            if (session.pausedDurationSec >= 1.0) {
                val label = if (session.mode == RunMode.INTERVAL_400M) "Total Recovery Rest" else "Stopped / Standing Time"
                add(label to TsPoliceBenchmark.formatDuration(session.pausedDurationSec))
            }
        }
        for ((k, v) in statLines) {
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(ctx, 3f), 0, dp(ctx, 3f))
            }
            r.addView(TextView(ctx).apply {
                text = k
                textSize = 12f
                setTextColor(AthleticColors.TEXT_SECONDARY)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            r.addView(TextView(ctx).apply {
                text = v
                textSize = 12.5f
                typeface = AthleticTheme.FONT_TABULAR_BOLD
                setTextColor(AthleticColors.TEXT_PRIMARY)
            })
            statsBox.addView(r)
        }
        root.addView(statsBox)

        // 400m Lap Splits Breakdown
        root.addView(AthleticTheme.sectionLabel(ctx, "400M LAP SPLITS BREAKDOWN", AthleticColors.TEXT_SECONDARY))
        if (session.lapSplits.isEmpty()) {
            root.addView(TextView(ctx).apply {
                text = "No individual 400m splits recorded for this session (${session.notes})."
                textSize = 12f
                setTextColor(AthleticColors.TEXT_MUTED)
                setPadding(0, dp(ctx, 6f), 0, dp(ctx, 8f))
            })
        } else {
            val targetLap = TsPoliceBenchmark.targetLap400mSec(targetSec)
            val splitsWrap = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(ctx, 6f), 0, dp(ctx, 8f))
            }
            for (sp in session.lapSplits) {
                val fast = sp.lapDurationSec <= targetLap
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(ctx, 10f), dp(ctx, 7f), dp(ctx, 10f), dp(ctx, 7f))
                    background = AthleticTheme.solidPill(ctx, AthleticColors.SURFACE_RECESSED, AthleticColors.BORDER_SUBTLE, 9f)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(ctx, 5f) }
                }
                row.addView(TextView(ctx).apply {
                    text = "Lap ${sp.lapNumber} (${sp.distanceMeters.toInt()}m)"
                    textSize = 12.5f
                    typeface = AthleticTheme.FONT_SANS_MEDIUM
                    setTextColor(AthleticColors.TEXT_PRIMARY)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f)
                })
                row.addView(TextView(ctx).apply {
                    text = TsPoliceBenchmark.formatDuration(sp.lapDurationSec)
                    textSize = 13f
                    typeface = AthleticTheme.FONT_TABULAR_BOLD
                    setTextColor(if (fast) AthleticColors.EMERALD_BRIGHT else AthleticColors.AMBER_BRIGHT)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f)
                })
                row.addView(TextView(ctx).apply {
                    text = "Cum ${TsPoliceBenchmark.formatDuration(sp.cumulativeSec)}"
                    textSize = 11.5f
                    gravity = Gravity.END
                    setTextColor(AthleticColors.TEXT_SECONDARY)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f)
                })
                splitsWrap.addView(row)
            }
            root.addView(splitsWrap)
        }

        scroll.addView(root)
        val dlg = AlertDialog.Builder(ctx)
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNegativeButton("Share Run ↗") { _, _ ->
                RunShareHelper.showShareRunDialog(
                    ctx = ctx,
                    session = session,
                    targetSec = targetSec,
                    deadlineIso = deadlineIso,
                    isPersonalBest = isPersonalBest
                )
            }
            .setNeutralButton("Delete Run") { _, _ ->
                confirmDeleteSession(ctx, session, onDeleteConfirmed)
            }
            .create()
        dlg.show()
    }

    fun confirmDeleteSession(
        ctx: Context,
        session: RunSessionRecord,
        onDeleteConfirmed: () -> Unit
    ) {
        AlertDialog.Builder(ctx)
            .setTitle("Delete Run Session?")
            .setMessage(
                "Remove ${session.dateIso} (${String.format("%.2f km", session.distanceMeters / 1000.0)} in ${TsPoliceBenchmark.formatDuration(session.activeDurationSec)}) from your training log?"
            )
            .setPositiveButton("Delete") { _, _ -> onDeleteConfirmed() }
            .setNegativeButton("Keep Run", null)
            .show()
    }

    fun showManualRunDialog(
        ctx: Context,
        repository: RunRepository,
        onSaved: () -> Unit
    ) {
        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20f), dp(ctx, 14f), dp(ctx, 20f), dp(ctx, 6f))
        }
        val dateInput = EditText(ctx).apply {
            hint = "YYYY-MM-DD"
            setText(LocalDate.now().toString())
        }
        val distInput = EditText(ctx).apply {
            hint = "Meters (e.g. 1600)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("1600")
        }
        val minInput = EditText(ctx).apply {
            hint = "Minutes (e.g. 7)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("7")
        }
        val secInput = EditText(ctx).apply {
            hint = "Seconds (e.g. 12.5)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("35")
        }

        fun lbl(t: String) = TextView(ctx).apply {
            text = t
            textSize = 12f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setPadding(0, dp(ctx, 6f), 0, 0)
        }
        form.addView(lbl("Run Date (YYYY-MM-DD)"))
        form.addView(dateInput)
        form.addView(lbl("Distance (meters)"))
        form.addView(distInput)
        form.addView(lbl("Minutes"))
        form.addView(minInput)
        form.addView(lbl("Seconds"))
        form.addView(secInput)

        AlertDialog.Builder(ctx)
            .setTitle("Log Track Stopwatch Run")
            .setView(form)
            .setPositiveButton("Save Run") { _, _ ->
                val dateIso = runCatching { LocalDate.parse(dateInput.text.toString().trim()).toString() }
                    .getOrDefault(LocalDate.now().toString())
                val distM = distInput.text.toString().toDoubleOrNull() ?: 1600.0
                val mins = minInput.text.toString().toIntOrNull() ?: 0
                val secs = secInput.text.toString().toDoubleOrNull() ?: 0.0
                val totalSec = mins * 60.0 + secs
                if (distM >= 200.0 && totalSec >= 30.0) {
                    val epochMs = LocalDate.parse(dateIso)
                        .atTime(6, 30)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                    val is1600 = abs(distM - 1600.0) <= 25.0
                    repository.insertSession(
                        RunSessionRecord(
                            startedAtEpochMs = epochMs,
                            dateIso = dateIso,
                            mode = RunMode.MANUAL_LOG,
                            distanceMeters = distM,
                            activeDurationSec = totalSec,
                            pausedDurationSec = 0.0,
                            first1600mSplitSec = if (is1600) totalSec else null,
                            equiv1600mSec = TsPoliceBenchmark.computeEquiv1600mSec(
                                distM,
                                totalSec,
                                if (is1600) totalSec else null
                            ),
                            avgCadenceSpm = 168.0,
                            maxSpeedMps = distM / totalSec,
                            runningSec = totalSec,
                            joggingSec = 0.0,
                            walkingSec = 0.0,
                            notes = "Manual track stopwatch",
                            isDemo = false
                        )
                    )
                    onSaved()
                } else {
                    Toast.makeText(ctx, "Enter valid distance (>=200m) and duration.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showBackupAndRestoreDialog(
        ctx: Context,
        repository: RunRepository,
        onRestored: () -> Unit
    ) {
        AlertDialog.Builder(ctx)
            .setTitle("Backup & Restore Training Log")
            .setMessage("Export your 1.6 km runs & daily stats to WhatsApp/Drive/Clipboard, or paste a backup JSON to restore on a new phone.")
            .setPositiveButton("Export / Share Backup") { _, _ ->
                val json = repository.exportBackupJson()
                runCatching {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("TS_Police_1600m_Backup", json))
                }
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "TS Police 1.6km Training Backup")
                    putExtra(Intent.EXTRA_TEXT, json)
                }
                ctx.startActivity(Intent.createChooser(share, "Save or Share Training Backup"))
            }
            .setNeutralButton("Restore from JSON") { _, _ ->
                val input = EditText(ctx).apply {
                    hint = "Paste exported backup JSON here..."
                    minLines = 5
                }
                val wrap = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(ctx, 18f), dp(ctx, 12f), dp(ctx, 18f), dp(ctx, 4f))
                    addView(input)
                }
                AlertDialog.Builder(ctx)
                    .setTitle("Restore Runs from Backup JSON")
                    .setView(wrap)
                    .setPositiveButton("Restore") { _, _ ->
                        val raw = input.text.toString().trim()
                        val count = runCatching { repository.importBackupJson(raw) }.getOrDefault(-1)
                        if (count >= 0) {
                            Toast.makeText(ctx, "Restored $count run session(s)!", Toast.LENGTH_LONG).show()
                            onRestored()
                        } else {
                            Toast.makeText(ctx, "Invalid backup JSON format.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Rich dark modal displaying Yesterday, Today, and 2-Day Combined side-by-side comparison
     * against Samsung Health, with 1-tap Copy & Share Report.
     */
    fun showTwoDayComparisonDialog(
        ctx: Context,
        yesterdayTracker: DailyActivitySummary,
        yesterdaySamsung: SamsungDailySnapshot?,
        todayTracker: DailyActivitySummary,
        todaySamsung: SamsungDailySnapshot?,
        strideScale: Double
    ) {
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AthleticColors.SURFACE_BOTTOM)
            setPadding(dp(ctx, 18f), dp(ctx, 18f), dp(ctx, 18f), dp(ctx, 12f))
        }

        root.addView(AthleticTheme.sectionLabel(ctx, "SAMSUNG HEALTH 2-DAY ACCURACY AUDIT", AthleticColors.CYAN_BRIGHT))
        root.addView(TextView(ctx).apply {
            text = "Yesterday (${yesterdayTracker.dateIso})  +  Today (${todayTracker.dateIso})"
            textSize = 15f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.TEXT_PRIMARY)
            setPadding(0, dp(ctx, 5f), 0, dp(ctx, 2f))
        })
        root.addView(TextView(ctx).apply {
            text = String.format("Calibrated Personal Stride Scale: %.2fx", strideScale)
            textSize = 12f
            setTextColor(AthleticColors.EMERALD_BRIGHT)
            setPadding(0, 0, 0, dp(ctx, 10f))
        })

        fun addDaySection(title: String, trk: DailyActivitySummary, sam: SamsungDailySnapshot?) {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, 12f), dp(ctx, 10f), dp(ctx, 12f), dp(ctx, 10f))
                background = AthleticTheme.solidPill(ctx, AthleticColors.SURFACE_RECESSED, AthleticColors.BORDER_SUBTLE, 12f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 10f) }
            }

            val topRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(ctx, 6f))
            }
            topRow.addView(TextView(ctx).apply {
                text = title
                textSize = 12f
                typeface = AthleticTheme.FONT_SANS_BOLD
                setTextColor(AthleticColors.CYAN_BRIGHT)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            if (sam == null) {
                box.addView(topRow)
                box.addView(TextView(ctx).apply {
                    text = "Tracker: ${trk.totalSteps} steps • ${String.format("%.2f km", trk.totalDistanceMeters / 1000.0)}\nSamsung Health: Not synced yet for this date."
                    textSize = 12f
                    setTextColor(AthleticColors.TEXT_SECONDARY)
                })
                root.addView(box)
                return
            }

            val rep = SamsungHealthComparator.compare(trk, sam, strideScale, title.lowercase())
            val pctText = rep.overallMatchPercent?.let { String.format("%.1f%% MATCH", it) } ?: "SYNCED"
            val high = (rep.overallMatchPercent ?: 100.0) >= 88.0
            topRow.addView(TextView(ctx).apply {
                text = pctText
                textSize = 10.5f
                typeface = AthleticTheme.FONT_SANS_BOLD
                setTextColor(if (high) AthleticColors.EMERALD_BRIGHT else AthleticColors.AMBER_BRIGHT)
            })
            box.addView(topRow)

            fun addLine(metric: String, tVal: String, sVal: String, dVal: String) {
                val r = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(ctx, 2.5f), 0, dp(ctx, 2.5f))
                }
                r.addView(TextView(ctx).apply {
                    text = metric
                    textSize = 11.5f
                    setTextColor(AthleticColors.TEXT_SECONDARY)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
                })
                r.addView(TextView(ctx).apply {
                    text = tVal
                    textSize = 11.5f
                    gravity = Gravity.END
                    typeface = AthleticTheme.FONT_TABULAR_BOLD
                    setTextColor(AthleticColors.TEXT_PRIMARY)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f)
                })
                r.addView(TextView(ctx).apply {
                    text = sVal
                    textSize = 11.5f
                    gravity = Gravity.END
                    typeface = AthleticTheme.FONT_TABULAR_BOLD
                    setTextColor(AthleticColors.CYAN_BRIGHT)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f)
                })
                r.addView(TextView(ctx).apply {
                    text = dVal
                    textSize = 11f
                    gravity = Gravity.END
                    typeface = AthleticTheme.FONT_SANS_BOLD
                    setTextColor(AthleticColors.EMERALD_BRIGHT)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.05f)
                })
                box.addView(r)
            }

            fun fmtDist(m: Double) = if (m >= 1000.0) String.format("%.2f km", m / 1000.0) else "${m.toInt()} m"
            val stSign = if (rep.stepsDelta >= 0) "+${rep.stepsDelta}" else "${rep.stepsDelta}"
            val dm = rep.distanceDeltaMeters.roundToInt()
            val dmSign = if (dm >= 0) "+${dm}m" else "${dm}m"

            addLine("Steps", "${trk.totalSteps}", "${sam.steps}", stSign)
            addLine("Distance", fmtDist(trk.totalDistanceMeters), fmtDist(sam.distanceMeters), dmSign)
            addLine("Walk", TsPoliceBenchmark.formatDuration(trk.walkingSec), TsPoliceBenchmark.formatDuration(sam.walkingSec), "${rep.walkSecDelta.roundToInt()}s")
            addLine("Jog+Run", TsPoliceBenchmark.formatDuration(trk.jogRunCombinedSec), TsPoliceBenchmark.formatDuration(sam.jogRunSec), "${rep.jogRunSecDelta.roundToInt()}s")
            addLine("Max Pace", TsPoliceBenchmark.formatPacePerKm(trk.maxSpeedMps), TsPoliceBenchmark.formatPacePerKm(sam.maxSpeedMps), "")
            addLine("Max Cad.", "${trk.maxCadenceSpm.roundToInt()} spm", "${sam.maxCadenceSpm.roundToInt()} spm", "")
            addLine(
                "Stride",
                rep.trackerStrideM?.let { String.format("%.2fm", it) } ?: "--",
                rep.samsungStrideM?.let { String.format("%.2fm", it) } ?: "--",
                String.format("%.2fx", strideScale)
            )
            root.addView(box)
        }

        addDaySection("YESTERDAY (${yesterdayTracker.dateIso})", yesterdayTracker, yesterdaySamsung)
        addDaySection("TODAY (${todayTracker.dateIso})", todayTracker, todaySamsung)

        val combSam = SamsungHealthComparator.combineSamsungSnapshots(yesterdaySamsung, todaySamsung)
        val combTrk = SamsungHealthComparator.combineDailySummaries(yesterdayTracker, todayTracker)
        addDaySection("2-DAY COMBINED TOTAL", combTrk, combSam)

        val reportText = SamsungHealthComparator.buildTwoDayReportText(
            yesterdayTracker = yesterdayTracker,
            yesterdaySamsung = yesterdaySamsung,
            todayTracker = todayTracker,
            todaySamsung = todaySamsung,
            effectiveStrideScale = strideScale
        )

        scroll.addView(root)
        AlertDialog.Builder(ctx)
            .setView(scroll)
            .setPositiveButton("Copy Report") { _, _ ->
                runCatching {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("Samsung_2Day_Comparison", reportText))
                }
                Toast.makeText(ctx, "Copied Yesterday + Today comparison report to clipboard!", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Share") { _, _ ->
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "TS Police 1.6K vs Samsung Health (Yesterday & Today)")
                    putExtra(Intent.EXTRA_TEXT, reportText)
                }
                ctx.startActivity(Intent.createChooser(share, "Share 2-Day Accuracy Report"))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Displays the result of pulling & uploading the 7-day + 48h intraday fine-tuning telemetry bundle,
     * with 1-tap Sync Code copy and raw JSON share fallback.
     */
    fun showTuningUploadResultDialog(
        ctx: Context,
        result: TuningPullAndUploadResult
    ) {
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AthleticColors.SURFACE_BOTTOM)
            setPadding(dp(ctx, 20f), dp(ctx, 18f), dp(ctx, 20f), dp(ctx, 12f))
        }

        val accentCol = if (result.uploadedToCloud) AthleticColors.EMERALD_BRIGHT else AthleticColors.AMBER_BRIGHT
        root.addView(
            AthleticTheme.sectionLabel(
                ctx,
                if (result.uploadedToCloud) "FINE-TUNING BUNDLE UPLOADED ✓" else "FINE-TUNING BUNDLE SAVED LOCALLY",
                accentCol
            )
        )

        root.addView(TextView(ctx).apply {
            text = result.headline
            textSize = 18f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.TEXT_PRIMARY)
            setPadding(0, dp(ctx, 6f), 0, dp(ctx, 6f))
        })

        root.addView(TextView(ctx).apply {
            text = if (result.uploadedToCloud) {
                "Sync Code: ${result.syncCode}\nReply 'uploaded' (or paste this code) so we can pull your 7-day Samsung + 48h hourly sensor logs and fine-tune thresholds."
            } else {
                "No internet connection reached cloud relay — saved locally to:\n${result.localFilePath}\nTap 'Share JSON' below or retry on Wi-Fi/Mobile Data."
            }
            textSize = 12f
            typeface = AthleticTheme.FONT_SANS_MEDIUM
            setTextColor(accentCol)
            setPadding(dp(ctx, 11f), dp(ctx, 8f), dp(ctx, 11f), dp(ctx, 8f))
            background = AthleticTheme.solidPill(
                ctx,
                if (result.uploadedToCloud) 0x22C6F432 else 0x22FF9F1C,
                if (result.uploadedToCloud) AthleticColors.HERO_EMERALD_BORDER else AthleticColors.HERO_AMBER_BORDER,
                10f
            )
        })

        val diagBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12f), dp(ctx, 10f), dp(ctx, 12f), dp(ctx, 10f))
            background = AthleticTheme.solidPill(ctx, AthleticColors.SURFACE_RECESSED, AthleticColors.BORDER_SUBTLE, 12f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 12f) }
        }
        diagBox.addView(AthleticTheme.sectionLabel(ctx, "AUTO-COMPUTED 48H TUNING DIAGNOSTICS", AthleticColors.CYAN_BRIGHT))
        diagBox.addView(TextView(ctx).apply {
            text = result.diagnosticsSummaryText
            textSize = 12f
            setTextColor(AthleticColors.TEXT_PRIMARY)
            setPadding(0, dp(ctx, 6f), 0, 0)
        })
        root.addView(diagBox)

        scroll.addView(root)
        val copyLabel = if (result.uploadedToCloud) "Copy Sync Code" else "Copy JSON"
        AlertDialog.Builder(ctx)
            .setView(scroll)
            .setPositiveButton(copyLabel) { _, _ ->
                val clipPayload = if (result.uploadedToCloud) result.syncCode else result.rawJson
                runCatching {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("TS_Police_Tuning_Sync", clipPayload))
                }
                val toastMsg = if (result.uploadedToCloud) {
                    "Copied Sync Code: ${result.syncCode}"
                } else {
                    "Copied Fine-Tuning JSON to clipboard"
                }
                Toast.makeText(ctx, toastMsg, Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Share JSON") { _, _ ->
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "TS Police 1.6K Fine-Tuning Telemetry (${result.syncCode})")
                    putExtra(Intent.EXTRA_TEXT, result.rawJson)
                }
                ctx.startActivity(Intent.createChooser(share, "Share Fine-Tuning JSON"))
            }
            .setNegativeButton("Done", null)
            .show()
    }

    /**
     * Renders the Coach Brain's 1-vs-2 Sessions Verdict, Time-of-Day Pace & Consistency Table
     * (Early Morning vs 5:00 PM vs 6:00 PM vs 7:00+ PM), Deep Sensor Insights, and Weight/Batch controls
     * into any LinearLayout container (used both inline on Progress tab and in the full modal sheet).
     */
    fun populateCoachBrainIntelligenceSection(
        ctx: Context,
        host: LinearLayout,
        report: CoachBrainReport,
        repository: RunRepository,
        onSettingsChanged: () -> Unit
    ) {
        host.removeAllViews()

        // 1. 1 Session (Evening) vs Morning + Evening Verdict Box
        val schedBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 13f), dp(ctx, 11f), dp(ctx, 13f), dp(ctx, 11f))
            background = AthleticTheme.solidPill(
                ctx,
                0x28000000,
                AthleticColors.HERO_EMERALD_BORDER,
                13f
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 12f) }
        }
        val schedTop = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        schedTop.addView(TextView(ctx).apply {
            text = "1 SESSION VS MORNING + EVENING"
            textSize = 10f
            typeface = AthleticTheme.FONT_SANS_BOLD
            letterSpacing = 0.06f
            setTextColor(AthleticColors.EMERALD_BRIGHT)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        schedTop.addView(TextView(ctx).apply {
            text = report.scheduleMode.shortBadge
            textSize = 10f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.TEXT_ON_DARK_ACCENT)
            setPadding(dp(ctx, 8f), dp(ctx, 3f), dp(ctx, 8f), dp(ctx, 3f))
            background = AthleticTheme.solidPill(ctx, AthleticColors.EMERALD_BRIGHT, AthleticColors.EMERALD_BRIGHT, 9f, 0f)
        })
        schedBox.addView(schedTop)

        schedBox.addView(TextView(ctx).apply {
            text = report.scheduleHeadline
            textSize = 14f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.TEXT_PRIMARY)
            setPadding(0, dp(ctx, 6f), 0, dp(ctx, 4f))
        })
        schedBox.addView(TextView(ctx).apply {
            text = report.scheduleExplanation
            textSize = 12f
            setTextColor(AthleticColors.TEXT_SECONDARY)
            setLineSpacing(0f, 1.18f)
        })
        host.addView(schedBox)

        // 2. Time-of-Day Performance & Consistency Breakdown (Early Morning | 5 PM | 6 PM | 7+ PM)
        host.addView(
            AthleticTheme.sectionLabel(
                ctx,
                "YOUR BEST RUNNING CLOCK TIME (5 PM VS 6 PM VS MORNING)",
                AthleticColors.CYAN_BRIGHT
            )
        )
        host.addView(TextView(ctx).apply {
            text = report.timingVariationSummary
            textSize = 12f
            setTextColor(AthleticColors.TEXT_SECONDARY)
            setLineSpacing(0f, 1.16f)
            setPadding(0, dp(ctx, 5f), 0, dp(ctx, 9f))
        })

        for (st in report.slotStats) {
            val hasData = st.runCount > 0
            val borderCol = when {
                st.isBestSlot -> AthleticColors.EMERALD_BRIGHT
                hasData -> AthleticColors.HERO_AMBER_BORDER
                else -> AthleticColors.BORDER_SUBTLE
            }
            val bgCol = when {
                st.isBestSlot -> 0x1FC6F432
                hasData -> AthleticColors.SURFACE_RECESSED
                else -> 0x16000000
            }
            val slotCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, 12f), dp(ctx, 9f), dp(ctx, 12f), dp(ctx, 9f))
                background = AthleticTheme.solidPill(ctx, bgCol, borderCol, 11f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 6f) }
            }

            val topLine = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topLine.addView(TextView(ctx).apply {
                text = "${st.slot.title}  (${st.slot.clockRange})"
                textSize = 12.5f
                typeface = AthleticTheme.FONT_SANS_BOLD
                setTextColor(if (st.isBestSlot) AthleticColors.EMERALD_BRIGHT else AthleticColors.TEXT_PRIMARY)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(ctx, 6f)
                }
            })

            val pillCol = when {
                st.isBestSlot -> AthleticColors.EMERALD_BRIGHT
                hasData -> AthleticColors.AMBER_BRIGHT
                else -> AthleticColors.TEXT_MUTED
            }
            topLine.addView(TextView(ctx).apply {
                text = st.badgeLabel
                textSize = 9.5f
                typeface = AthleticTheme.FONT_SANS_BOLD
                setTextColor(pillCol)
                setPadding(dp(ctx, 7f), dp(ctx, 2.5f), dp(ctx, 7f), dp(ctx, 2.5f))
                background = AthleticTheme.solidPill(
                    ctx,
                    (pillCol and 0x00FFFFFF) or 0x22000000,
                    pillCol,
                    8f
                )
            })
            slotCard.addView(topLine)

            slotCard.addView(TextView(ctx).apply {
                text = st.plainSummary
                textSize = 11.5f
                setTextColor(if (hasData) AthleticColors.TEXT_PRIMARY else AthleticColors.TEXT_MUTED)
                setLineSpacing(0f, 1.14f)
                setPadding(0, dp(ctx, 4f), 0, 0)
            })
            host.addView(slotCard)
        }

        // 3. Deep Telemetry & Biomechanics Insights
        val insightsBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12f), dp(ctx, 10f), dp(ctx, 12f), dp(ctx, 10f))
            background = AthleticTheme.solidPill(ctx, AthleticColors.SURFACE_RECESSED, AthleticColors.BORDER_SUBTLE, 12f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(ctx, 8f)
                bottomMargin = dp(ctx, 10f)
            }
        }
        insightsBox.addView(
            AthleticTheme.sectionLabel(ctx, "WHAT YOUR COACH BRAIN FOUND IN YOUR DATA", AthleticColors.AMBER_BRIGHT)
        )
        for (ins in report.personalInsights) {
            insightsBox.addView(TextView(ctx).apply {
                text = "•  $ins"
                textSize = 12f
                setTextColor(AthleticColors.TEXT_PRIMARY)
                setLineSpacing(0f, 1.17f)
                setPadding(0, dp(ctx, 6f), 0, dp(ctx, 2f))
            })
        }
        host.addView(insightsBox)

        // 4. Quick Brain Calibration: Body Weight (for Carbs/Protein/Calories) + Expected Exam Batch
        val tuneBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12f), dp(ctx, 10f), dp(ctx, 12f), dp(ctx, 10f))
            background = AthleticTheme.solidPill(ctx, 0x22000000, AthleticColors.BORDER_SUBTLE, 12f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        tuneBox.addView(
            AthleticTheme.sectionLabel(ctx, "BRAIN PROFILE (WEIGHT FOR FUEL & EXAM BATCH)", AthleticColors.TEXT_SECONDARY)
        )

        val weightRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 8f), 0, dp(ctx, 8f))
        }
        weightRow.addView(TextView(ctx).apply {
            text = "Body Weight (Carbs & Protein Math)"
            textSize = 12f
            setTextColor(AthleticColors.TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val weightValTv = TextView(ctx).apply {
            text = "${repository.runnerWeightKg} kg"
            textSize = 13f
            typeface = AthleticTheme.FONT_TABULAR_BOLD
            setTextColor(AthleticColors.EMERALD_BRIGHT)
            setPadding(dp(ctx, 10f), 0, dp(ctx, 10f), 0)
        }
        fun mkWeightBtn(sym: String, delta: Int) = AthleticTheme.createButton(
            ctx,
            sym,
            AthleticTheme.ButtonStyle.SECONDARY_SLATE,
            32f
        ).apply {
            minWidth = dp(ctx, 34f)
            setPadding(dp(ctx, 10f), dp(ctx, 3f), dp(ctx, 10f), dp(ctx, 3f))
            setOnClickListener {
                repository.runnerWeightKg = (repository.runnerWeightKg + delta).coerceIn(42, 125)
                weightValTv.text = "${repository.runnerWeightKg} kg"
                onSettingsChanged()
            }
        }
        weightRow.addView(mkWeightBtn("−", -2))
        weightRow.addView(weightValTv)
        weightRow.addView(mkWeightBtn("+", +2))
        tuneBox.addView(weightRow)

        val batchRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        batchRow.addView(TextView(ctx).apply {
            text = "Expected PET Ground Slot:"
            textSize = 12f
            setTextColor(AthleticColors.TEXT_SECONDARY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val curBatch = repository.expectedExamBatch
        val batchBtn = TextView(ctx).apply {
            text = "${curBatch.shortLabel}  ↻"
            textSize = 11.5f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.CYAN_BRIGHT)
            setPadding(dp(ctx, 10f), dp(ctx, 5f), dp(ctx, 10f), dp(ctx, 5f))
            background = AthleticTheme.solidPill(ctx, 0x229D6BFF, AthleticColors.HERO_CYAN_BORDER, 9f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                repository.expectedExamBatch = if (repository.expectedExamBatch == ExamTimeBatch.MORNING_GROUND) {
                    ExamTimeBatch.EVENING_OR_UNSURE
                } else {
                    ExamTimeBatch.MORNING_GROUND
                }
                onSettingsChanged()
            }
        }
        batchRow.addView(batchBtn)
        tuneBox.addView(batchRow)

        tuneBox.addView(TextView(ctx).apply {
            text = report.dataPointsSummary
            textSize = 10.5f
            setTextColor(AthleticColors.TEXT_MUTED)
            setPadding(0, dp(ctx, 8f), 0, 0)
        })
        host.addView(tuneBox)
    }

    fun showCoachBrainDialog(
        ctx: Context,
        repository: RunRepository,
        reportProvider: () -> CoachBrainReport,
        onSettingsChanged: () -> Unit
    ) {
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 18f), dp(ctx, 16f), dp(ctx, 18f), dp(ctx, 10f))
            setBackgroundColor(AthleticColors.SURFACE_TOP)
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(ctx, 10f))
        }
        header.addView(TextView(ctx).apply {
            text = "EXPERT RUNNING COACH BRAIN"
            textSize = 12f
            typeface = AthleticTheme.FONT_SANS_BOLD
            letterSpacing = 0.06f
            setTextColor(AthleticColors.EMERALD_BRIGHT)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val confBadge = TextView(ctx).apply {
            textSize = 10f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.CYAN_BRIGHT)
            setPadding(dp(ctx, 8f), dp(ctx, 3f), dp(ctx, 8f), dp(ctx, 3f))
            background = AthleticTheme.solidPill(ctx, 0x229D6BFF, AthleticColors.HERO_CYAN_BORDER, 9f)
        }
        header.addView(confBadge)
        root.addView(header)

        val dynamicBody = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(dynamicBody)

        fun rebuild() {
            val latestReport = reportProvider()
            confBadge.text = latestReport.confidenceLabel
            populateCoachBrainIntelligenceSection(
                ctx = ctx,
                host = dynamicBody,
                report = latestReport,
                repository = repository
            ) {
                onSettingsChanged()
                rebuild()
            }
        }
        rebuild()

        scroll.addView(root)
        AlertDialog.Builder(ctx)
            .setView(scroll)
            .setPositiveButton("Got It", null)
            .show()
    }
}
