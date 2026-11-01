package com.tspolice.runtracker.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.tspolice.runtracker.domain.RunMode
import com.tspolice.runtracker.domain.RunSessionRecord
import com.tspolice.runtracker.domain.TsPoliceBenchmark
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Generates a high-resolution (1080x1350) AMOLED athletic poster card for 1,600m runs
 * and launches Android's native Share Sheet with both the visual poster PNG and formatted stats.
 */
object RunShareHelper {

    fun buildShareCaption(
        session: RunSessionRecord,
        targetSec: Double = TsPoliceBenchmark.DEFAULT_TARGET_TIME_SEC,
        deadlineIso: String = "2026-11-30",
        isPersonalBest: Boolean = false,
        todayIso: String = LocalDate.now().toString()
    ): String {
        val isExact1600 = abs(session.distanceMeters - 1600.0) <= 60.0
        val timeFmt = TsPoliceBenchmark.formatDuration(session.equiv1600mSec)
        val targetFmt = TsPoliceBenchmark.formatDuration(targetSec)
        val merit = TsPoliceBenchmark.evaluateMerit1600m(session.equiv1600mSec, targetSec)
        val badge = when {
            merit.qualified -> "✓ QUALIFIED (< $targetFmt!)"
            isPersonalBest -> "★ NEW PB!"
            else -> sessionModeTitle(session)
        }
        val datePretty = formatPrettyDate(session.dateIso)
        val avgLapFmt = TsPoliceBenchmark.formatDuration(session.equiv1600mSec / 4.0)
        val avgSpd = if (session.activeDurationSec > 1.0) session.distanceMeters / session.activeDurationSec else 0.0
        val paceFmt = TsPoliceBenchmark.formatPacePerKm(avgSpd)

        val todayDate = runCatching { LocalDate.parse(todayIso) }.getOrElse { LocalDate.now() }
        val deadlineDate = runCatching { LocalDate.parse(deadlineIso) }.getOrElse { LocalDate.of(2026, 11, 30) }
        val daysLeft = (deadlineDate.toEpochDay() - todayDate.toEpochDay()).toInt().coerceAtLeast(0)
        val deadlinePretty = formatShortMonthDay(deadlineDate)
        val gapSec = session.equiv1600mSec - targetSec
        val goalLine = if (gapSec <= 0.0) {
            "Goal (< $targetFmt): Beat cutoff by ${String.format("%.0fs", abs(gapSec))}!"
        } else {
            "Goal (< $targetFmt by $deadlinePretty): ${String.format("%.0fs", gapSec)} to shave (${daysLeft}d left)"
        }

        return buildString {
            if (isExact1600) {
                appendLine("TS Police 1.6 km Run — $timeFmt  $badge")
            } else {
                appendLine(
                    "TS Police Run (${String.format("%.2f km", session.distanceMeters / 1000.0)}) — $timeFmt 1.6K pace  $badge"
                )
            }
            appendLine("$datePretty  •  Avg 400m: $avgLapFmt  •  Pace: $paceFmt")
            if (session.lapSplits.isNotEmpty()) {
                val splitsSummary = session.lapSplits.take(6).joinToString(" | ") { sp ->
                    "L${sp.lapNumber} ${TsPoliceBenchmark.formatDuration(sp.lapDurationSec)}"
                }
                appendLine("Splits: $splitsSummary")
            }
            append(goalLine)
        }
    }

    fun showShareRunDialog(
        ctx: Context,
        session: RunSessionRecord,
        targetSec: Double,
        deadlineIso: String,
        isPersonalBest: Boolean,
        readinessPercent: Int? = null
    ) {
        val posterBitmap = renderSharePosterBitmap(
            session = session,
            targetSec = targetSec,
            deadlineIso = deadlineIso,
            isPersonalBest = isPersonalBest,
            readinessPercentOverride = readinessPercent
        )
        val caption = buildShareCaption(
            session = session,
            targetSec = targetSec,
            deadlineIso = deadlineIso,
            isPersonalBest = isPersonalBest
        )

        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)

        val dp = { v: Float -> AthleticTheme.dp(ctx, v) }

        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
            background = AthleticTheme.gradientCard(
                ctx,
                AthleticColors.SURFACE_TOP,
                AthleticColors.BG_CANVAS,
                AthleticColors.BORDER_STRONG,
                22f
            )
        }

        val topHeader = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10f))
        }
        topHeader.addView(
            AthleticTheme.sectionLabel(ctx, "SHARE 1.6 KM RUN CARD", AthleticColors.EMERALD_BRIGHT).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        topHeader.addView(TextView(ctx).apply {
            text = "HD POSTER"
            textSize = 10f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setTextColor(AthleticColors.CYAN_BRIGHT)
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
            background = AthleticTheme.solidPill(ctx, 0x269D6BFF, AthleticColors.CYAN_BRIGHT, 9f)
        })
        sheet.addView(topHeader)

        val previewImage = ImageView(ctx).apply {
            setImageBitmap(posterBitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            val cornerPx = dp(16f).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerPx)
                }
            }
            clipToOutline = true
            val maxPreviewH = (ctx.resources.displayMetrics.heightPixels * 0.56f).roundToInt()
            maxHeight = maxPreviewH
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14f)
            }
        }
        sheet.addView(previewImage)

        val shareCardBtn = AthleticTheme.createButton(
            ctx,
            "Share Run Card & Splits  ↗",
            AthleticTheme.ButtonStyle.PRIMARY_EMERALD,
            48f
        ).apply {
            textSize = 13.5f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8f)
            }
            setOnClickListener {
                sharePosterAndText(ctx, posterBitmap, session, caption)
                dialog.dismiss()
            }
        }
        sheet.addView(shareCardBtn)

        val secondaryRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val copyTextBtn = AthleticTheme.createButton(
            ctx,
            "Copy Text",
            AthleticTheme.ButtonStyle.SECONDARY_SLATE,
            42f
        ).apply {
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(5f)
            }
            setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("TS Police 1.6K Run", caption))
                Toast.makeText(ctx, "Copied 1.6 km run summary!", Toast.LENGTH_SHORT).show()
            }
        }
        val closeBtn = AthleticTheme.createButton(
            ctx,
            "Close",
            AthleticTheme.ButtonStyle.SUBTLE_OUTLINE,
            42f
        ).apply {
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f).apply {
                marginStart = dp(5f)
            }
            setOnClickListener { dialog.dismiss() }
        }
        secondaryRow.addView(copyTextBtn)
        secondaryRow.addView(closeBtn)
        sheet.addView(secondaryRow)

        dialog.setContentView(sheet)
        dialog.window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.setDimAmount(0.82f)
            val widthPx = (ctx.resources.displayMetrics.widthPixels * 0.92f).roundToInt()
            win.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()

        sheet.alpha = 0f
        sheet.translationY = dp(22f).toFloat()
        sheet.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator(1.7f))
            .start()
    }

    private fun sharePosterAndText(
        ctx: Context,
        bitmap: Bitmap,
        session: RunSessionRecord,
        caption: String
    ) {
        runCatching {
            val shareDir = File(ctx.cacheDir, "shares").apply { mkdirs() }
            // Clean up older temporary share PNGs
            shareDir.listFiles()?.forEach { old ->
                if (System.currentTimeMillis() - old.lastModified() > 3_600_000L) {
                    old.delete()
                }
            }
            val outFile = File(shareDir, "ts_police_1600m_${session.dateIso}_${session.id}.png")
            FileOutputStream(outFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                fos.flush()
            }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", outFile)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, caption)
                clipData = ClipData.newRawUri("TS Police 1.6K Run", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(sendIntent, "Share 1.6 km Run"))
        }.onFailure {
            // Fallback to plain-text share if external chooser fails
            val textIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, caption)
            }
            ctx.startActivity(Intent.createChooser(textIntent, "Share 1.6 km Run"))
        }
    }

    private fun renderSharePosterBitmap(
        session: RunSessionRecord,
        targetSec: Double,
        deadlineIso: String,
        isPersonalBest: Boolean,
        readinessPercentOverride: Int?
    ): Bitmap {
        val w = 1080
        val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val boldSans = Typeface.create("sans-serif", Typeface.BOLD)
        val medSans = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val regSans = Typeface.create("sans-serif", Typeface.NORMAL)

        // 1. Deep Obsidian AMOLED Background + Ambient Stadium Glows
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(0xFF0C1206.toInt(), 0xFF09090D.toInt(), 0xFF120D1E.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        val topGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                260f, 340f, 520f,
                intArrayOf(0x2EC6F432, 0x0EC6F432, 0x00000000),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), topGlowPaint)

        val botGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                860f, 1120f, 480f,
                intArrayOf(0x289D6BFF, 0x0C9D6BFF, 0x00000000),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), botGlowPaint)

        // Subtle stadium track lane geometry in upper background
        val trackLanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0x10FFFFFF
        }
        c.drawRoundRect(RectF(36f, 36f, w - 36f, h - 36f), 56f, 56f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = 0xFF2B331E.toInt()
        })
        c.drawArc(RectF(620f, -180f, 1260f, 460f), 110f, 150f, false, trackLanePaint)
        c.drawArc(RectF(660f, -140f, 1220f, 420f), 110f, 150f, false, trackLanePaint)

        // 2. Top Brand Header + Runner Emblem + Date Pill
        drawRunnerBadge(c, cx = 114f, cy = 118f, radius = 42f)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = 34f
            color = Color.WHITE
            letterSpacing = 0.04f
        }
        c.drawText("TS POLICE 1.6K", 176f, 112f, titlePaint)

        val subHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = 19f
            color = AthleticColors.EMERALD_BRIGHT
            letterSpacing = 0.07f
        }
        c.drawText("1,600M PET TRACK COACH", 176f, 142f, subHeaderPaint)

        val dateStr = formatPrettyDate(session.dateIso).uppercase()
        val dateTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = 21f
            color = 0xFFD4D4DC.toInt()
            letterSpacing = 0.04f
        }
        val dateW = dateTextPaint.measureText(dateStr)
        val dateRect = RectF(w - 76f - dateW - 44f, 88f, w - 76f, 146f)
        c.drawRoundRect(dateRect, 29f, 29f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF17171F.toInt()
        })
        c.drawRoundRect(dateRect, 29f, 29f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0xFF2F2F3C.toInt()
        })
        c.drawText(dateStr, dateRect.left + 22f, 124f, dateTextPaint)

        // 3. Achievement Badge + Giant 186px Finish Time
        val merit = TsPoliceBenchmark.evaluateMerit1600m(session.equiv1600mSec, targetSec)
        val isExact1600 = abs(session.distanceMeters - 1600.0) <= 60.0
        val targetFmt = TsPoliceBenchmark.formatDuration(targetSec)
        val (statusText, statusCol, statusBg) = when {
            merit.qualified -> Triple(
                "★  QUALIFIED  •  SUB-$targetFmt ACHIEVED",
                AthleticColors.EMERALD_BRIGHT,
                0x28C6F432
            )
            isPersonalBest -> Triple(
                "★  NEW PERSONAL BEST  •  1,600M",
                AthleticColors.EMERALD_BRIGHT,
                0x28C6F432
            )
            else -> Triple(
                "${sessionModeTitle(session).uppercase()}  •  TRACK LOG",
                AthleticColors.CYAN_BRIGHT,
                0x289D6BFF
            )
        }
        val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = 22f
            color = statusCol
            letterSpacing = 0.07f
        }
        val badgeW = badgeTextPaint.measureText(statusText) + 52f
        val badgeRect = RectF(76f, 198f, 76f + badgeW, 256f)
        c.drawRoundRect(badgeRect, 29f, 29f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = statusBg })
        c.drawRoundRect(badgeRect, 29f, 29f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = statusCol
        })
        c.drawText(statusText, badgeRect.left + 26f, 235f, badgeTextPaint)

        val heroTimeStr = TsPoliceBenchmark.formatDuration(session.equiv1600mSec)
        val heroTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = 188f
            color = if (merit.qualified || isPersonalBest) AthleticColors.EMERALD_BRIGHT else Color.WHITE
            setShadowLayer(28f, 0f, 8f, if (merit.qualified || isPersonalBest) 0x40C6F432 else 0x339D6BFF)
        }
        c.drawText(heroTimeStr, 72f, 442f, heroTimePaint)

        val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = 23f
            color = 0xFFA5A5B2.toInt()
            letterSpacing = 0.08f
        }
        val subCaption = if (isExact1600) {
            "OFFICIAL 1.60 KM FINISH TIME"
        } else {
            "1.60 KM PACE EQUIVALENT  •  ${String.format("%.2f KM", session.distanceMeters / 1000.0)} LOGGED"
        }
        c.drawText(subCaption, 78f, 492f, captionPaint)

        // 4. 3-Column Bento Metrics Strip
        val bentoTop = 536f
        val bentoBot = 706f
        val gap = 20f
        val colW = (w - 152f - gap * 2f) / 3f

        val avgLapStr = TsPoliceBenchmark.formatDuration(session.equiv1600mSec / 4.0)
        val avgSpd = if (session.activeDurationSec > 1.0) session.distanceMeters / session.activeDurationSec else 0.0
        val avgPaceStr = TsPoliceBenchmark.formatPacePerKm(avgSpd)
        val diffSec = session.equiv1600mSec - targetSec
        val diffStr = if (diffSec <= 0.0) {
            "-${String.format("%.0fs", abs(diffSec))}"
        } else {
            "+${String.format("%.0fs", diffSec)}"
        }

        val tiles = listOf(
            Triple("AVG 400M LAP", avgLapStr, "Target ≤ ${TsPoliceBenchmark.formatDuration(targetSec / 4.0)}"),
            Triple("AVG PACE", avgPaceStr, String.format("%.1f km/h avg", avgSpd * 3.6)),
            Triple("VS $targetFmt GOAL", diffStr, if (diffSec <= 0.0) "Target Met ✓" else "Seconds to shave")
        )

        for ((i, t) in tiles.withIndex()) {
            val left = 76f + i * (colW + gap)
            val r = RectF(left, bentoTop, left + colW, bentoBot)
            c.drawRoundRect(r, 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF13131A.toInt() })
            c.drawRoundRect(r, 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = 0xFF262632.toInt()
            })

            c.drawText(t.first, left + 24f, bentoTop + 44f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = boldSans
                textSize = 18f
                color = if (i == 0) AthleticColors.EMERALD_BRIGHT else if (i == 1) AthleticColors.CYAN_BRIGHT else AthleticColors.AMBER_BRIGHT
                letterSpacing = 0.06f
            })
            c.drawText(t.second, left + 24f, bentoTop + 104f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = boldSans
                textSize = 42f
                color = Color.WHITE
            })
            c.drawText(t.third, left + 24f, bentoTop + 142f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = regSans
                textSize = 19f
                color = 0xFF8E8E9A.toInt()
            })
        }

        // 5. 4x400m Track Lap Splits Visual Section (or Run Telemetry Breakdown)
        val splitsCardRect = RectF(76f, 738f, w - 76f, 1138f)
        c.drawRoundRect(splitsCardRect, 32f, 32f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111118.toInt() })
        c.drawRoundRect(splitsCardRect, 32f, 32f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0xFF252532.toInt()
        })

        if (session.lapSplits.isNotEmpty()) {
            val targetLapSec = TsPoliceBenchmark.targetLap400mSec(targetSec)
            c.drawText("400M TRACK LAP SPLITS", 108f, 788f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = boldSans
                textSize = 20f
                color = AthleticColors.CYAN_BRIGHT
                letterSpacing = 0.07f
            })
            val rightHint = "TARGET ≤ ${TsPoliceBenchmark.formatDuration(targetLapSec)} /LAP"
            val rhPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = boldSans
                textSize = 18f
                color = 0xFF7E7E8C.toInt()
                letterSpacing = 0.05f
            }
            c.drawText(rightHint, w - 108f - rhPaint.measureText(rightHint), 788f, rhPaint)

            val shown = session.lapSplits.take(4)
            val fastest = shown.minOf { it.lapDurationSec }.coerceAtLeast(1.0)
            val slowest = shown.maxOf { it.lapDurationSec }.coerceAtLeast(fastest + 5.0)

            val rowStartTop = 816f
            val rowH = 62f
            val rowGap = 14f

            for ((idx, sp) in shown.withIndex()) {
                val top = rowStartTop + idx * (rowH + rowGap)
                val rowRect = RectF(108f, top, w - 108f, top + rowH)
                c.drawRoundRect(rowRect, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF191922.toInt() })

                val hitTarget = sp.lapDurationSec <= targetLapSec
                // Faster lap -> wider energy bar
                val norm = 1f - ((sp.lapDurationSec - fastest) / (slowest - fastest + 12.0)).toFloat().coerceIn(0f, 0.45f)
                val maxBarW = rowRect.width() * 0.56f
                val fillW = (maxBarW * norm).coerceAtLeast(140f)
                val barRect = RectF(rowRect.left, top, rowRect.left + fillW, top + rowH)
                c.drawRoundRect(barRect, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (hitTarget) 0x2EC6F432 else 0x269D6BFF
                })

                c.drawText("LAP ${sp.lapNumber}", rowRect.left + 22f, top + 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = boldSans
                    textSize = 23f
                    color = Color.WHITE
                })

                val cumStr = "Cum ${TsPoliceBenchmark.formatDuration(sp.cumulativeSec)}"
                c.drawText(cumStr, rowRect.left + 145f, top + 39f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = medSans
                    textSize = 20f
                    color = 0xFF9898A6.toInt()
                })

                val splitStr = TsPoliceBenchmark.formatDuration(sp.lapDurationSec)
                val spPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = boldSans
                    textSize = 29f
                    color = if (hitTarget) AthleticColors.EMERALD_BRIGHT else AthleticColors.AMBER_BRIGHT
                }
                c.drawText(splitStr, rowRect.right - 24f - spPaint.measureText(splitStr), top + 42f, spPaint)
            }
        } else {
            c.drawText("SESSION TELEMETRY", 108f, 792f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = boldSans
                textSize = 20f
                color = AthleticColors.CYAN_BRIGHT
                letterSpacing = 0.07f
            })
            val rows = listOf(
                "Total Distance" to String.format("%.2f km (%d m)", session.distanceMeters / 1000.0, session.distanceMeters.toInt()),
                "Active Duration" to TsPoliceBenchmark.formatDuration(session.activeDurationSec),
                "Average Cadence" to "${session.avgCadenceSpm.roundToInt()} spm",
                "Peak Speed" to String.format("%.1f km/h (%s)", session.maxSpeedMps * 3.6, TsPoliceBenchmark.formatPacePerKm(session.maxSpeedMps))
            )
            for ((idx, pair) in rows.withIndex()) {
                val y = 858f + idx * 70f
                c.drawText(pair.first, 108f, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = medSans
                    textSize = 24f
                    color = 0xFF9E9EAC.toInt()
                })
                val vp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = boldSans
                    textSize = 27f
                    color = Color.WHITE
                }
                c.drawText(pair.second, w - 108f - vp.measureText(pair.second), y, vp)
            }
        }

        // 6. Goal Countdown & Readiness Footer Strip
        val deadlineDate = runCatching { LocalDate.parse(deadlineIso) }.getOrElse { LocalDate.of(2026, 11, 30) }
        val daysLeft = (deadlineDate.toEpochDay() - LocalDate.now().toEpochDay()).toInt().coerceAtLeast(0)
        val deadlineLabel = formatShortMonthDay(deadlineDate).uppercase()
        val readiness = readinessPercentOverride ?: when {
            merit.qualified -> 100
            else -> ((1080.0 - session.equiv1600mSec) / (1080.0 - targetSec) * 86.0 + 14.0).roundToInt().coerceIn(15, 97)
        }

        val footerRect = RectF(76f, 1168f, w - 76f, 1284f)
        c.drawRoundRect(footerRect, 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF14180D.toInt() })
        c.drawRoundRect(footerRect, 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0xFF334418.toInt()
        })

        val footerTitle = if (daysLeft == 0) {
            "GOAL DAY ($deadlineLabel)  •  <$targetFmt TARGET"
        } else {
            "$deadlineLabel GOAL (<$targetFmt)  •  $daysLeft DAYS LEFT"
        }
        c.drawText(footerTitle, 108f, 1214f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = 20f
            color = AthleticColors.EMERALD_BRIGHT
            letterSpacing = 0.06f
        })

        val readyTxt = "$readiness% READY"
        val readyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = 21f
            color = Color.WHITE
        }
        c.drawText(readyTxt, w - 108f - readyPaint.measureText(readyTxt), 1214f, readyPaint)

        // Progress bar inside footer
        val barTrack = RectF(108f, 1236f, w - 108f, 1256f)
        c.drawRoundRect(barTrack, 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF22252A.toInt() })
        val fillFrac = (readiness / 100f).coerceIn(0.05f, 1f)
        val barFill = RectF(barTrack.left, barTrack.top, barTrack.left + barTrack.width() * fillFrac, barTrack.bottom)
        c.drawRoundRect(barFill, 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                barTrack.left, 0f, barTrack.right, 0f,
                intArrayOf(AthleticColors.CYAN, AthleticColors.EMERALD, AthleticColors.EMERALD_BRIGHT),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP
            )
        })

        return bmp
    }

    private fun drawRunnerBadge(c: Canvas, cx: Float, cy: Float, radius: Float) {
        c.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22C6F432 })
        c.drawArc(
            RectF(cx - radius * 0.82f, cy - radius * 0.82f, cx + radius * 0.82f, cy + radius * 0.82f),
            140f,
            245f,
            false,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = radius * 0.11f
                strokeCap = Paint.Cap.ROUND
                color = AthleticColors.CYAN
            }
        )
        val limeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = radius * 0.13f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = AthleticColors.EMERALD_BRIGHT
        }
        c.drawCircle(cx + radius * 0.24f, cy - radius * 0.34f, radius * 0.13f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AthleticColors.EMERALD_BRIGHT
        })
        val p = Path().apply {
            moveTo(cx + radius * 0.12f, cy - radius * 0.16f)
            lineTo(cx - radius * 0.08f, cy + radius * 0.12f)
            moveTo(cx - radius * 0.26f, cy - radius * 0.08f)
            lineTo(cx + radius * 0.08f, cy - radius * 0.16f)
            lineTo(cx + radius * 0.34f, cy - radius * 0.04f)
            moveTo(cx - radius * 0.08f, cy + radius * 0.12f)
            lineTo(cx + radius * 0.20f, cy + radius * 0.18f)
            lineTo(cx + radius * 0.14f, cy + radius * 0.44f)
            moveTo(cx - radius * 0.08f, cy + radius * 0.12f)
            lineTo(cx - radius * 0.36f, cy + radius * 0.36f)
        }
        c.drawPath(p, limeStroke)
    }

    private fun sessionModeTitle(s: RunSessionRecord): String = when (s.mode) {
        RunMode.TIMED_1600M -> "1.6K Trial"
        RunMode.INTERVAL_400M -> "400m Intervals"
        RunMode.MANUAL_LOG -> "1.6K Stopwatch"
        RunMode.OPEN_PRACTICE -> "Track Practice"
    }

    private fun formatPrettyDate(dateIso: String): String {
        val d = runCatching { LocalDate.parse(dateIso) }.getOrNull() ?: return dateIso
        val mon = d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        return "$mon ${d.dayOfMonth}, ${d.year}"
    }

    private fun formatShortMonthDay(d: LocalDate): String {
        val mon = d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        return "$mon ${d.dayOfMonth}"
    }
}
