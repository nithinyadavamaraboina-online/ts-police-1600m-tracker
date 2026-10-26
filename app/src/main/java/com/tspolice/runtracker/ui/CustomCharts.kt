package com.tspolice.runtracker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import com.tspolice.runtracker.domain.DailyProgressPoint
import com.tspolice.runtracker.domain.ForecastStatus
import com.tspolice.runtracker.domain.IntervalPhase
import com.tspolice.runtracker.domain.IntervalWorkoutState
import com.tspolice.runtracker.domain.MotionState
import com.tspolice.runtracker.domain.TargetForecast
import com.tspolice.runtracker.domain.TsPoliceBenchmark
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Dual concentric athletic rings (Jog+Run conditioning outer ring + Daily Steps inner ring)
 * for the All-Day Open Mode hero card.
 */
class DailyActivityRingsView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val centerValPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = AthleticTheme.FONT_CONDENSED_BOLD
        textSize = sp(19f)
        color = AthleticColors.TEXT_PRIMARY
    }
    private val centerSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = AthleticTheme.FONT_SANS_BOLD
        textSize = sp(9.5f)
        color = AthleticColors.TEXT_SECONDARY
        letterSpacing = 0.06f
    }

    private val outerRect = RectF()
    private val innerRect = RectF()

    private var jogRunSec = 0.0
    private var totalSteps = 0

    fun updateMetrics(jogRunCombinedSec: Double, steps: Int) {
        if (jogRunSec == jogRunCombinedSec && totalSteps == steps) return
        jogRunSec = jogRunCombinedSec
        totalSteps = steps
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(126f).roundToInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val stroke = dp(10.5f)
        val gap = dp(4.5f)

        val rOuter = min(cx, cy) - stroke * 0.65f
        val rInner = rOuter - stroke - gap

        outerRect.set(cx - rOuter, cy - rOuter, cx + rOuter, cy + rOuter)
        innerRect.set(cx - rInner, cy - rInner, cx + rInner, cy + rInner)

        trackPaint.strokeWidth = stroke
        outerPaint.strokeWidth = stroke
        innerPaint.strokeWidth = stroke

        // Background tracks
        trackPaint.color = 0x2410B981
        canvas.drawOval(outerRect, trackPaint)
        trackPaint.color = 0x2438BDF8
        canvas.drawOval(innerRect, trackPaint)

        // Outer ring: Jog+Run goal = 900s (15 mins daily conditioning)
        val runRatio = (jogRunSec / 900.0).toFloat().coerceIn(0.02f, 1f)
        outerPaint.color = AthleticColors.EMERALD_BRIGHT
        canvas.drawArc(outerRect, -90f, 360f * runRatio, false, outerPaint)

        // Glowing tip dot on outer ring
        val outerEndRad = Math.toRadians((-90.0 + 360.0 * runRatio))
        tipPaint.color = 0xFF6EE7B7.toInt()
        canvas.drawCircle(
            cx + (rOuter * cos(outerEndRad)).toFloat(),
            cy + (rOuter * sin(outerEndRad)).toFloat(),
            stroke * 0.34f,
            tipPaint
        )

        // Inner ring: Daily Steps goal = 6,000 steps
        val stepRatio = (totalSteps / 6000.0).toFloat().coerceIn(0.02f, 1f)
        innerPaint.color = AthleticColors.CYAN
        canvas.drawArc(innerRect, -90f, 360f * stepRatio, false, innerPaint)

        // Center steps readout
        val stepsLabel = if (totalSteps >= 1000) {
            String.format("%.1fk", totalSteps / 1000.0)
        } else {
            "$totalSteps"
        }
        canvas.drawText(stepsLabel, cx, cy + dp(4f), centerValPaint)
        canvas.drawText("STEPS", cx, cy + dp(17f), centerSubPaint)
    }
}

/**
 * Circular 270-degree Race Cockpit Gauge for the 1.6 km TSLPRB PT Trial:
 *  - Glowing progress arc + leading orb
 *  - Labeled 400m / 800m / 1.2K / 1.6K lap markers
 *  - True continuous official stopwatch + Stopped-time penalty diagnostic
 */
class PaceArcGaugeView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFF172236.toInt()
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val orbGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val orbCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0xFF475569.toInt()
    }
    private val tickLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = AthleticTheme.FONT_SANS_BOLD
        textSize = sp(9.5f)
        color = 0xFF64748B.toInt()
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = AthleticTheme.FONT_SANS_BOLD
        textSize = sp(10.5f)
        letterSpacing = 0.05f
    }
    private val timerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = AthleticTheme.FONT_TABULAR_BOLD
        textSize = sp(44f)
        color = AthleticColors.TEXT_PRIMARY
    }
    private val distancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = AthleticTheme.FONT_SANS_BOLD
        textSize = sp(15.5f)
        color = 0xFFCBD5E1.toInt()
    }
    private val deltaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = AthleticTheme.FONT_SANS_BOLD
        textSize = sp(12.5f)
    }
    private val penaltyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = AthleticTheme.FONT_SANS_MEDIUM
        textSize = sp(11f)
        color = 0xFFFCA5A5.toInt()
    }

    private val arcRect = RectF()
    private val pillRect = RectF()

    private var distanceM: Double = 0.0
    private var activeSec: Double = 0.0
    private var stoppedPenaltySec: Double = 0.0
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
        armed: Boolean,
        pausedPenaltySec: Double = 0.0
    ) {
        distanceM = distanceMeters
        activeSec = activeDurationSec
        targetTimeSec = targetSec
        state = motionState
        isRecording = recording
        isArmed = armed
        stoppedPenaltySec = pausedPenaltySec
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val desiredH = dp(274f).toInt()
        setMeasuredDimension(w, resolveSize(desiredH, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height * 0.54f
        val strokeW = dp(14f)
        trackPaint.strokeWidth = strokeW
        progressPaint.strokeWidth = strokeW
        haloPaint.strokeWidth = strokeW + dp(10f)

        val radius = min(width * 0.37f, height * 0.41f)
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Track background
        canvas.drawArc(arcRect, 135f, 270f, false, trackPaint)

        // Active progress arc + subtle halo + glowing leading orb
        val ratio = ((distanceM / TsPoliceBenchmark.TARGET_DISTANCE_M).toFloat()).coerceIn(0f, 1f)
        val activeColor = when {
            isArmed && !isRecording -> AthleticColors.AMBER
            isRecording && !state.countsAsActiveMovement -> AthleticColors.CRIMSON
            else -> state.colorArgb
        }
        if (ratio > 0.003f) {
            val sweep = 270f * ratio
            haloPaint.color = (activeColor and 0x00FFFFFF) or 0x26000000
            progressPaint.color = activeColor
            canvas.drawArc(arcRect, 135f, sweep, false, haloPaint)
            canvas.drawArc(arcRect, 135f, sweep, false, progressPaint)

            val tipRad = Math.toRadians((135.0 + sweep))
            val tipX = cx + (radius * cos(tipRad)).toFloat()
            val tipY = cy + (radius * sin(tipRad)).toFloat()
            orbGlowPaint.color = (activeColor and 0x00FFFFFF) or 0x66000000
            canvas.drawCircle(tipX, tipY, dp(10f), orbGlowPaint)
            canvas.drawCircle(tipX, tipY, dp(4.5f), orbCorePaint)
        }

        // Labeled 400m, 800m, 1.2K, 1.6K markers
        val lapLabels = arrayOf("400m", "800m", "1.2K", "1.6K")
        for (lap in 1..4) {
            val deg = 135.0 + 270.0 * (lap / 4.0)
            val rad = Math.toRadians(deg)
            val rInner = radius - strokeW * 0.58f
            val rOuter = radius + strokeW * 0.58f
            canvas.drawLine(
                cx + (rInner * cos(rad)).toFloat(),
                cy + (rInner * sin(rad)).toFloat(),
                cx + (rOuter * cos(rad)).toFloat(),
                cy + (rOuter * sin(rad)).toFloat(),
                tickPaint
            )
            val rLbl = radius + strokeW * 1.48f
            val lx = cx + (rLbl * cos(rad)).toFloat()
            val ly = cy + (rLbl * sin(rad)).toFloat() + dp(3f)
            val passed = distanceM >= lap * 400.0
            tickLabelPaint.color = if (passed) AthleticColors.EMERALD_BRIGHT else 0xFF64748B.toInt()
            canvas.drawText(lapLabels[lap - 1], lx, ly, tickLabelPaint)
        }

        // Top state badge
        val badgeStr = when {
            isArmed && !isRecording -> "ARMED • LAUNCHES ON JOG / RUN"
            isRecording && state == MotionState.STANDING -> "STOPPED • OFFICIAL CLOCK RUNNING!"
            else -> state.badgeText
        }
        val pillColor = when {
            isArmed && !isRecording -> AthleticColors.AMBER_BRIGHT
            isRecording && state == MotionState.STANDING -> AthleticColors.CRIMSON
            !isRecording && state == MotionState.STANDING -> 0xFF94A3B8.toInt()
            else -> state.colorArgb
        }
        val textW = badgeTextPaint.measureText(badgeStr)
        val pillW = textW + dp(20f)
        val pillH = dp(23f)
        val pillTop = cy - dp(58f)
        pillRect.set(cx - pillW / 2f, pillTop, cx + pillW / 2f, pillTop + pillH)
        badgeBgPaint.color = (pillColor and 0x00FFFFFF) or 0x29000000
        canvas.drawRoundRect(pillRect, dp(12f), dp(12f), badgeBgPaint)
        badgeTextPaint.color = pillColor
        canvas.drawText(badgeStr, cx, pillTop + dp(15.5f), badgeTextPaint)

        // Official continuous stopwatch
        val timeText = TsPoliceBenchmark.formatDuration(activeSec, includeTenths = true)
        canvas.drawText(timeText, cx, cy + dp(6f), timerPaint)

        // Distance subtitle
        val distText = "${distanceM.toInt()} m  /  1,600 m"
        canvas.drawText(distText, cx, cy + dp(31f), distancePaint)

        // Split delta vs target cutoff
        val delta = TsPoliceBenchmark.splitDeltaSec(distanceM, activeSec, targetTimeSec)
        val targetLabel = TsPoliceBenchmark.formatDuration(targetTimeSec)
        val bottomText: String
        if (isArmed && !isRecording) {
            deltaPaint.color = AthleticColors.AMBER_BRIGHT
            bottomText = "Walk to start line • Clock starts at 0:00 on first jog stride"
        } else if (delta != null && (isRecording || activeSec > 0.0)) {
            if (delta <= 0.0) {
                deltaPaint.color = AthleticColors.EMERALD_BRIGHT
                bottomText = "${TsPoliceBenchmark.formatSignedDelta(delta)} AHEAD OF $targetLabel CUTOFF"
            } else {
                deltaPaint.color = AthleticColors.AMBER_BRIGHT
                bottomText = "${TsPoliceBenchmark.formatSignedDelta(delta)} BEHIND $targetLabel CUTOFF"
            }
        } else {
            val lapTarget = TsPoliceBenchmark.formatDuration(TsPoliceBenchmark.targetLap400mSec(targetTimeSec), true)
            deltaPaint.color = AthleticColors.TEXT_SECONDARY
            bottomText = "Target < $targetLabel ($lapTarget / 400m • Unpaused RFID Rules)"
        }
        canvas.drawText(bottomText, cx, cy + dp(60f), deltaPaint)

        if (isRecording && stoppedPenaltySec >= 2.0) {
            canvas.drawText(
                "Stopped ${stoppedPenaltySec.toInt()}s during trial (official clock never pauses)",
                cx,
                cy + dp(80f),
                penaltyPaint
            )
        }
    }
}

/**
 * 360-degree Guided 400m Speed-Endurance Interval Dial (Rep 0..400m or Recovery Rest Countdown).
 */
class IntervalDialView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFF172236.toInt()
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = AthleticTheme.FONT_SANS_BOLD
        textSize = sp(11f)
        letterSpacing = 0.06f
    }
    private val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = AthleticTheme.FONT_TABULAR_BOLD
        textSize = sp(38f)
        color = AthleticColors.TEXT_PRIMARY
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = AthleticTheme.FONT_SANS_MEDIUM
        textSize = sp(13.5f)
        color = AthleticColors.TEXT_SECONDARY
    }

    private val rect = RectF()
    private var state = IntervalWorkoutState()

    fun update(newState: IntervalWorkoutState) {
        state = newState
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, resolveSize(dp(226f).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val stroke = dp(13f)
        val r = min(cx, cy) - stroke * 1.15f
        rect.set(cx - r, cy - r, cx + r, cy + r)

        trackPaint.strokeWidth = stroke
        arcPaint.strokeWidth = stroke
        canvas.drawOval(rect, trackPaint)

        when (state.phase) {
            IntervalPhase.IDLE, IntervalPhase.COMPLETED -> {
                arcPaint.color = AthleticColors.AMBER
                canvas.drawArc(rect, -90f, 360f * 0.02f, false, arcPaint)
                badgePaint.color = AthleticColors.AMBER_BRIGHT
                canvas.drawText("SPEED ENDURANCE COACH", cx, cy - dp(38f), badgePaint)
                canvas.drawText("6 × 400m", cx, cy + dp(6f), mainPaint)
                canvas.drawText(
                    "Target ${TsPoliceBenchmark.formatDuration(state.targetRepSec, true)} / 400m  •  90s Rest",
                    cx,
                    cy + dp(32f),
                    subPaint
                )
            }
            IntervalPhase.RUNNING_REP -> {
                val ratio = (state.repDistanceMeters / 400.0).toFloat().coerceIn(0.01f, 1f)
                arcPaint.color = AthleticColors.EMERALD_BRIGHT
                canvas.drawArc(rect, -90f, 360f * ratio, false, arcPaint)
                badgePaint.color = AthleticColors.EMERALD_BRIGHT
                canvas.drawText("REP ${state.currentRep} OF ${state.totalReps} • SPRINT", cx, cy - dp(38f), badgePaint)
                canvas.drawText(TsPoliceBenchmark.formatDuration(state.repDurationSec, true), cx, cy + dp(6f), mainPaint)
                canvas.drawText("${state.repDistanceMeters.toInt()} m / 400 m", cx, cy + dp(32f), subPaint)
            }
            IntervalPhase.REST_COUNTDOWN -> {
                val ratio = (state.restRemainingSec / state.restDurationSec.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
                arcPaint.color = AthleticColors.CYAN
                canvas.drawArc(rect, -90f, 360f * ratio, false, arcPaint)
                badgePaint.color = AthleticColors.CYAN_BRIGHT
                canvas.drawText("ACTIVE WALK RECOVERY", cx, cy - dp(38f), badgePaint)
                canvas.drawText("${state.restRemainingSec.roundToInt()}s", cx, cy + dp(6f), mainPaint)
                canvas.drawText("Next: Rep ${state.currentRep + 1} of ${state.totalReps}", cx, cy + dp(32f), subPaint)
            }
        }
    }
}

/**
 * Clean gapped multi-segment horizontal bar showing Running / Jogging / Walking / Stopped share.
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
        setMeasuredDimension(w, resolveSize(dp(12f).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val total = runSec + jogSec + walkSec + pauseSec
        val h = height.toFloat()
        val w = width.toFloat()
        if (total <= 0.1) {
            paint.color = 0xFF172236.toInt()
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
            return
        }

        val segments = listOf(
            runSec to AthleticColors.EMERALD_BRIGHT,
            jogSec to AthleticColors.AMBER_BRIGHT,
            walkSec to AthleticColors.CYAN,
            pauseSec to AthleticColors.CRIMSON
        ).filter { it.first > 0.01 }

        val gap = if (segments.size > 1) dp(3f) else 0f
        val usableW = (w - gap * (segments.size - 1)).coerceAtLeast(10f)

        var x = 0f
        segments.forEachIndexed { idx, (dur, col) ->
            val segW = ((dur / total) * usableW).toFloat().coerceAtLeast(dp(4f))
            paint.color = col
            val right = if (idx == segments.lastIndex) w else (x + segW).coerceAtMost(w)
            rect.set(x, 0f, right, h)
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
            x = right + gap
        }
    }
}

/**
 * Smooth Cubic-Bezier Daily 1.6 km Progression Chart + Forward Target Projection.
 */
class ProgressTrendChartView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0xFF182438.toInt()
    }
    private val targetZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val targetLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        color = AthleticColors.EMERALD_BRIGHT
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
    }
    private val trendLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = AthleticColors.CYAN_BRIGHT
    }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val projectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        color = AthleticColors.AMBER_BRIGHT
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(5f)), 0f)
    }
    private val dotHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = AthleticTheme.FONT_SANS_MEDIUM
        textSize = sp(10.5f)
        color = AthleticColors.TEXT_SECONDARY
    }
    private val emptyTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = AthleticTheme.FONT_SANS_BOLD
        textSize = sp(14f)
        color = AthleticColors.TEXT_PRIMARY
    }
    private val emptySubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(12f)
        color = AthleticColors.TEXT_SECONDARY
    }

    private var forecast: TargetForecast? = null

    fun setForecast(data: TargetForecast) {
        forecast = data
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, resolveSize(dp(228f).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val f = forecast
        val pts: List<DailyProgressPoint> = f?.dailyPoints ?: emptyList()
        if (f == null || pts.isEmpty()) {
            canvas.drawText("No 1.6 km Baseline Logged Yet", width / 2f, height * 0.45f, emptyTitlePaint)
            canvas.drawText(
                "Run a PT Trial, sync Samsung Health, or preview Sample Data below",
                width / 2f,
                height * 0.58f,
                emptySubPaint
            )
            return
        }

        val leftPad = dp(46f)
        val rightPad = dp(16f)
        val topPad = dp(18f)
        val bottomPad = dp(28f)
        val plotW = (width - leftPad - rightPad).coerceAtLeast(10f)
        val plotH = (height - topPad - bottomPad).coerceAtLeast(10f)

        val targetSec = f.targetTimeSec
        val minObserved = min(targetSec - 15.0, pts.minOf { it.bestEquiv1600mSec } - 12.0)
        val maxObserved = max(targetSec + 35.0, pts.maxOf { it.bestEquiv1600mSec } + 14.0)
        val secSpan = (maxObserved - minObserved).coerceAtLeast(30.0)

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

        // Grid lines
        labelPaint.textAlign = Paint.Align.RIGHT
        for (i in 0..3) {
            val secVal = maxObserved - (secSpan * i / 3.0)
            val y = yForSec(secVal)
            canvas.drawLine(leftPad, y, leftPad + plotW, y, gridPaint)
            labelPaint.color = AthleticColors.TEXT_MUTED
            canvas.drawText(TsPoliceBenchmark.formatDuration(secVal), leftPad - dp(7f), y + dp(3.5f), labelPaint)
        }

        // Target qualifying zone
        val targetY = yForSec(targetSec)
        targetZonePaint.shader = LinearGradient(
            0f, targetY, 0f, topPad + plotH,
            0x2810B981, 0x0510B981, Shader.TileMode.CLAMP
        )
        canvas.drawRect(leftPad, targetY, leftPad + plotW, topPad + plotH, targetZonePaint)
        canvas.drawLine(leftPad, targetY, leftPad + plotW, targetY, targetLinePaint)
        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.color = AthleticColors.EMERALD_BRIGHT
        canvas.drawText(
            "QUALIFYING ${TsPoliceBenchmark.formatDuration(targetSec)}",
            leftPad + dp(6f),
            (targetY - dp(5f)).coerceAtLeast(topPad + dp(10f)),
            labelPaint
        )

        // Smooth cubic Bezier path through daily points
        if (pts.size >= 2) {
            val coords = pts.map { xForDay(it.epochDay) to yForSec(it.bestEquiv1600mSec) }
            val linePath = Path()
            val fillPath = Path()

            linePath.moveTo(coords[0].first, coords[0].second)
            fillPath.moveTo(coords[0].first, topPad + plotH)
            fillPath.lineTo(coords[0].first, coords[0].second)

            for (i in 0 until coords.size - 1) {
                val (x0, y0) = coords[i]
                val (x1, y1) = coords[i + 1]
                val cx1 = x0 + (x1 - x0) * 0.45f
                val cx2 = x0 + (x1 - x0) * 0.55f
                linePath.cubicTo(cx1, y0, cx2, y1, x1, y1)
                fillPath.cubicTo(cx1, y0, cx2, y1, x1, y1)
            }
            fillPath.lineTo(coords.last().first, topPad + plotH)
            fillPath.close()

            areaPaint.shader = LinearGradient(
                0f, topPad, 0f, topPad + plotH,
                0x4838BDF8, 0x0038BDF8, Shader.TileMode.CLAMP
            )
            canvas.drawPath(fillPath, areaPaint)
            canvas.drawPath(linePath, trendLinePaint)
        }

        // Forward projection line
        if (f.status == ForecastStatus.ON_TRACK && projDays > 0 && f.currentCapabilitySec != null) {
            val startX = xForDay(lastDataDay)
            val startY = yForSec(f.currentCapabilitySec)
            val endX = xForDay(lastDataDay + projDays)
            val projPath = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, targetY)
            }
            canvas.drawPath(projPath, projectionPaint)
            dotHaloPaint.color = 0x4410B981
            dotPaint.color = AthleticColors.EMERALD_BRIGHT
            canvas.drawCircle(endX, targetY, dp(8f), dotHaloPaint)
            canvas.drawCircle(endX, targetY, dp(4.5f), dotPaint)
        }

        // Data points with soft halo
        for (p in pts) {
            val px = xForDay(p.epochDay)
            val py = yForSec(p.bestEquiv1600mSec)
            val col = when {
                p.bestEquiv1600mSec <= targetSec -> AthleticColors.EMERALD_BRIGHT
                p.isExact1600mTrial -> AthleticColors.CYAN_BRIGHT
                else -> AthleticColors.AMBER_BRIGHT
            }
            dotHaloPaint.color = (col and 0x00FFFFFF) or 0x36000000
            dotPaint.color = col
            canvas.drawCircle(px, py, dp(7.5f), dotHaloPaint)
            canvas.drawCircle(px, py, dp(4f), dotPaint)
        }

        // X-axis date labels
        labelPaint.color = AthleticColors.TEXT_SECONDARY
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(pts.first().dateIso.takeLast(5), leftPad, height - dp(6f), labelPaint)

        if (maxEpochDay > firstDay) {
            labelPaint.textAlign = Paint.Align.RIGHT
            val rightLabel = if (f.status == ForecastStatus.ON_TRACK && f.projectedTargetDateIso != null) {
                "Goal ${f.projectedTargetDateIso.takeLast(5)}"
            } else {
                pts.last().dateIso.takeLast(5)
            }
            canvas.drawText(rightLabel, leftPad + plotW, height - dp(6f), labelPaint)
        }
    }
}
