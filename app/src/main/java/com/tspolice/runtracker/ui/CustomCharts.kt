package com.tspolice.runtracker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import com.tspolice.runtracker.domain.DailyProgressPoint
import com.tspolice.runtracker.domain.ForecastStatus
import com.tspolice.runtracker.domain.MotionState
import com.tspolice.runtracker.domain.TargetForecast
import com.tspolice.runtracker.domain.TsPoliceBenchmark
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Circular 270-degree tactical gauge displaying:
 *  - 0..1600m progress ring with 400m/800m/1200m/1600m lap ticks
 *  - Auto-detected gait badge (STANDING / WALKING / JOGGING / RUNNING / TS POLICE PACE)
 *  - Tabular active timer + distance
 *  - Live +/- seconds ahead/behind the 7:15 TS Police cutoff
 */
class PaceArcGaugeView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFF1E293B.toInt()
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFF10B981.toInt()
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        color = 0xFF94A3B8.toInt()
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = sp(11.5f)
        color = 0xFFF8FAFC.toInt()
    }
    private val timerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = sp(40f)
        color = 0xFFF8FAFC.toInt()
    }
    private val distancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = sp(16f)
        color = 0xFFCBD5E1.toInt()
    }
    private val deltaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = sp(13f)
    }

    private val arcRect = RectF()
    private val pillRect = RectF()

    private var distanceM: Double = 0.0
    private var activeSec: Double = 0.0
    private var targetTimeSec: Double = TsPoliceBenchmark.DEFAULT_TARGET_TIME_SEC
    private var state: MotionState = MotionState.STANDING
    private var isRecording: Boolean = false
    private var isArmed: Boolean = false

    fun updateState(
        distanceMeters: Double,
        activeDurationSec: Double,
        targetSec: Double,
        motionState: MotionState,
        recording: Boolean,
        armed: Boolean
    ) {
        distanceM = distanceMeters
        activeSec = activeDurationSec
        targetTimeSec = targetSec
        state = motionState
        isRecording = recording
        isArmed = armed
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val desiredH = dp(252f).toInt()
        setMeasuredDimension(w, resolveSize(desiredH, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height * 0.54f
        val strokeW = dp(15f)
        trackPaint.strokeWidth = strokeW
        progressPaint.strokeWidth = strokeW

        val radius = min(width * 0.39f, height * 0.43f)
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Background 270 deg track
        canvas.drawArc(arcRect, 135f, 270f, false, trackPaint)

        // Progress arc (0..1600m; wraps cleanly on Open Practice runs >1600m)
        val ratio = ((distanceM / TsPoliceBenchmark.TARGET_DISTANCE_M).toFloat()).coerceIn(0f, 1f)
        progressPaint.color = state.colorArgb
        if (ratio > 0.002f) {
            canvas.drawArc(arcRect, 135f, 270f * ratio, false, progressPaint)
        }

        // Draw 400m, 800m, 1200m, 1600m lap markers along arc
        for (lap in 1..4) {
            val deg = 135.0 + 270.0 * (lap / 4.0)
            val rad = Math.toRadians(deg)
            val rInner = radius - strokeW * 0.68f
            val rOuter = radius + strokeW * 0.68f
            val x1 = cx + (rInner * cos(rad)).toFloat()
            val y1 = cy + (rInner * sin(rad)).toFloat()
            val x2 = cx + (rOuter * cos(rad)).toFloat()
            val y2 = cy + (rOuter * sin(rad)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }

        // State pill above timer
        val badgeStr = when {
            isArmed && !isRecording -> "ARMED • START JOG/RUN TO BEGIN"
            else -> state.badgeText
        }
        val textW = badgeTextPaint.measureText(badgeStr)
        val pillW = textW + dp(22f)
        val pillH = dp(24f)
        val pillTop = cy - dp(56f)
        pillRect.set(cx - pillW / 2f, pillTop, cx + pillW / 2f, pillTop + pillH)
        badgeBgPaint.color = (state.colorArgb and 0x00FFFFFF) or 0x33000000
        canvas.drawRoundRect(pillRect, dp(12f), dp(12f), badgeBgPaint)
        badgeTextPaint.color = if (state == MotionState.STANDING) 0xFFCBD5E1.toInt() else state.colorArgb
        canvas.drawText(badgeStr, cx, pillTop + dp(16.5f), badgeTextPaint)

        // Main timer
        val timeText = TsPoliceBenchmark.formatDuration(activeSec, includeTenths = true)
        canvas.drawText(timeText, cx, cy + dp(6f), timerPaint)

        // Distance subtitle
        val distText = if (distanceM < 1600.0) {
            "${distanceM.toInt()} m / 1,600 m"
        } else {
            String.format("%.2f km completed", distanceM / 1000.0)
        }
        canvas.drawText(distText, cx, cy + dp(32f), distancePaint)

        // Split delta vs 7:15 target
        val delta = TsPoliceBenchmark.splitDeltaSec(distanceM, activeSec, targetTimeSec)
        val targetLabel = TsPoliceBenchmark.formatDuration(targetTimeSec)
        val bottomText: String
        if (delta != null && (isRecording || activeSec > 0.0)) {
            if (delta <= 0.0) {
                deltaPaint.color = 0xFF10B981.toInt()
                bottomText = "${TsPoliceBenchmark.formatSignedDelta(delta)} AHEAD OF $targetLabel"
            } else {
                deltaPaint.color = 0xFFF59E0B.toInt()
                bottomText = "${TsPoliceBenchmark.formatSignedDelta(delta)} BEHIND $targetLabel"
            }
        } else {
            deltaPaint.color = 0xFF94A3B8.toInt()
            bottomText = "Goal: 1.60 km under $targetLabel (1:48.8 / 400m)"
        }
        canvas.drawText(bottomText, cx, cy + dp(62f), deltaPaint)
    }
}

/**
 * Proportional horizontal stacked bar showing Running vs Jogging vs Walking vs Paused share.
 */
class GaitBreakdownBarView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var runSec = 0.0
    private var jogSec = 0.0
    private var walkSec = 0.0
    private var pauseSec = 0.0

    fun setDurations(running: Double, jogging: Double, walking: Double, paused: Double) {
        runSec = running
        jogSec = jogging
        walkSec = walking
        pauseSec = paused
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, resolveSize(dp(14f).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val total = runSec + jogSec + walkSec + pauseSec
        val h = height.toFloat()
        val w = width.toFloat()
        if (total <= 0.1) {
            paint.color = 0xFF1E293B.toInt()
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
            return
        }

        val segments = listOf(
            runSec to 0xFF10B981.toInt(),
            jogSec to 0xFFF59E0B.toInt(),
            walkSec to 0xFF38BDF8.toInt(),
            pauseSec to 0xFF475569.toInt()
        ).filter { it.first > 0.01 }

        var x = 0f
        for ((dur, col) in segments) {
            val segW = ((dur / total) * w).toFloat()
            paint.color = col
            rect.set(x, 0f, (x + segW).coerceAtMost(w), h)
            canvas.drawRoundRect(rect, dp(4f), dp(4f), paint)
            x += segW
        }
    }
}

/**
 * Daily 1.6 km progression chart + forward regression projection line to the 7:15 target line.
 */
class ProgressTrendChartView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0xFF1E293B.toInt()
    }
    private val targetZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x1810B981
    }
    private val targetLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0xFF10B981.toInt()
        pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(5f)), 0f)
    }
    private val trendLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFF38BDF8.toInt()
    }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val projectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        color = 0xFFF59E0B.toInt()
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(5f)), 0f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10.5f)
        color = 0xFF94A3B8.toInt()
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(13f)
        color = 0xFF94A3B8.toInt()
    }

    private var forecast: TargetForecast? = null

    fun setForecast(data: TargetForecast) {
        forecast = data
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, resolveSize(dp(220f).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val f = forecast
        val pts: List<DailyProgressPoint> = f?.dailyPoints ?: emptyList()
        if (f == null || pts.isEmpty()) {
            canvas.drawText(
                "No 1.6 km runs yet — tap 'Load Sample Data' or record Day 1!",
                width / 2f,
                height / 2f,
                emptyPaint
            )
            return
        }

        val leftPad = dp(46f)
        val rightPad = dp(18f)
        val topPad = dp(18f)
        val bottomPad = dp(28f)
        val plotW = (width - leftPad - rightPad).coerceAtLeast(10f)
        val plotH = (height - topPad - bottomPad).coerceAtLeast(10f)

        val targetSec = f.targetTimeSec
        val minObserved = min(targetSec - 15.0, pts.minOf { it.bestEquiv1600mSec } - 12.0)
        val maxObserved = max(targetSec + 35.0, pts.maxOf { it.bestEquiv1600mSec } + 14.0)
        val secSpan = (maxObserved - minObserved).coerceAtLeast(30.0)

        // Y coordinate: slower times at top, faster (qualifying) times near bottom
        fun yForSec(sec: Double): Float {
            val norm = ((maxObserved - sec) / secSpan).toFloat().coerceIn(0f, 1f)
            return topPad + norm * plotH
        }

        val firstDay = pts.first().epochDay
        val lastDataDay = pts.last().epochDay
        val projDays = (f.estimatedDaysToTarget ?: 0).coerceAtMost(45)
        val maxEpochDay = if (f.status == ForecastStatus.ON_TRACK && projDays > 0) {
            lastDataDay + projDays
        } else {
            max(firstDay + 1L, lastDataDay)
        }
        val daySpan = max(1L, maxEpochDay - firstDay).toDouble()

        fun xForDay(epochDay: Long): Float {
            if (pts.size == 1 && projDays == 0) return leftPad + plotW * 0.5f
            val norm = ((epochDay - firstDay) / daySpan).toFloat().coerceIn(0f, 1f)
            return leftPad + norm * plotW
        }

        // Horizontal grid lines (4 levels)
        labelPaint.textAlign = Paint.Align.RIGHT
        for (i in 0..3) {
            val secVal = maxObserved - (secSpan * i / 3.0)
            val y = yForSec(secVal)
            canvas.drawLine(leftPad, y, leftPad + plotW, y, gridPaint)
            labelPaint.color = 0xFF64748B.toInt()
            canvas.drawText(TsPoliceBenchmark.formatDuration(secVal), leftPad - dp(6f), y + dp(3.5f), labelPaint)
        }

        // Shaded qualifying zone & 7:15 target line
        val targetY = yForSec(targetSec)
        canvas.drawRect(leftPad, targetY, leftPad + plotW, topPad + plotH, targetZonePaint)
        canvas.drawLine(leftPad, targetY, leftPad + plotW, targetY, targetLinePaint)
        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.color = 0xFF10B981.toInt()
        canvas.drawText(
            "Target ${TsPoliceBenchmark.formatDuration(targetSec)}",
            leftPad + dp(6f),
            (targetY - dp(5f)).coerceAtLeast(topPad + dp(10f)),
            labelPaint
        )

        // Observed progression path + subtle gradient fill
        if (pts.size >= 2) {
            val linePath = Path()
            val fillPath = Path()
            pts.forEachIndexed { idx, p ->
                val px = xForDay(p.epochDay)
                val py = yForSec(p.bestEquiv1600mSec)
                if (idx == 0) {
                    linePath.moveTo(px, py)
                    fillPath.moveTo(px, topPad + plotH)
                    fillPath.lineTo(px, py)
                } else {
                    linePath.lineTo(px, py)
                    fillPath.lineTo(px, py)
                }
            }
            val lastX = xForDay(pts.last().epochDay)
            fillPath.lineTo(lastX, topPad + plotH)
            fillPath.close()

            areaPaint.shader = LinearGradient(
                0f, topPad, 0f, topPad + plotH,
                0x4438BDF8, 0x0038BDF8, Shader.TileMode.CLAMP
            )
            canvas.drawPath(fillPath, areaPaint)
            canvas.drawPath(linePath, trendLinePaint)
        }

        // Forward projection line to target crossing
        if (f.status == ForecastStatus.ON_TRACK && projDays > 0 && f.currentCapabilitySec != null) {
            val startX = xForDay(lastDataDay)
            val startY = yForSec(f.currentCapabilitySec)
            val endX = xForDay(lastDataDay + projDays)
            val projPath = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, targetY)
            }
            canvas.drawPath(projPath, projectionPaint)
            dotPaint.color = 0xFF10B981.toInt()
            canvas.drawCircle(endX, targetY, dp(5f), dotPaint)
        }

        // Individual daily data points
        for (p in pts) {
            val px = xForDay(p.epochDay)
            val py = yForSec(p.bestEquiv1600mSec)
            dotPaint.color = if (p.bestEquiv1600mSec <= targetSec) {
                0xFF10B981.toInt()
            } else if (p.isExact1600mTrial) {
                0xFF38BDF8.toInt()
            } else {
                0xFFF59E0B.toInt()
            }
            canvas.drawCircle(px, py, dp(4.5f), dotPaint)
        }

        // X-axis date labels (start, latest, projected)
        labelPaint.color = 0xFF94A3B8.toInt()
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(pts.first().dateIso.takeLast(5), leftPad, height - dp(7f), labelPaint)

        if (maxEpochDay > firstDay) {
            labelPaint.textAlign = Paint.Align.RIGHT
            val rightLabel = if (f.status == ForecastStatus.ON_TRACK && f.projectedTargetDateIso != null) {
                "Est ${f.projectedTargetDateIso.takeLast(5)}"
            } else {
                pts.last().dateIso.takeLast(5)
            }
            canvas.drawText(rightLabel, leftPad + plotW, height - dp(7f), labelPaint)
        }
    }
}
