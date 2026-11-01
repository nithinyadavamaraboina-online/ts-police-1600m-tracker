package com.tspolice.runtracker.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure-black AMOLED palette with neon lime (primary / "go") and electric purple (secondary) accents.
 * Legacy token names (EMERALD / CYAN / AMBER) are kept so every screen re-skins from this one place:
 *  - EMERALD_* = neon lime   (walking-to-target success, primary actions, active nav)
 *  - CYAN_*    = electric purple (jogging, secondary actions, info)
 *  - AMBER_*   = hot orange  (warnings, running effort)
 */
object AthleticColors {
    const val BG_CANVAS = 0xFF000000.toInt()
    const val BG_HEADER = 0xFF000000.toInt()
    const val BG_NAV = 0xFF0A0A0C.toInt()

    const val SURFACE_TOP = 0xFF141417.toInt()
    const val SURFACE_BOTTOM = 0xFF0C0C0E.toInt()
    const val SURFACE_RECESSED = 0xFF08080A.toInt()
    const val BORDER_SUBTLE = 0xFF222227.toInt()
    const val BORDER_STRONG = 0xFF34343C.toInt()
    const val DIVIDER = 0xFF1A1A1F.toInt()

    const val HERO_EMERALD_TOP = 0xFF1C2606.toInt()
    const val HERO_EMERALD_BOTTOM = 0xFF0A0B07.toInt()
    const val HERO_EMERALD_BORDER = 0xFF5A7A12.toInt()

    const val HERO_CYAN_TOP = 0xFF1E1236.toInt()
    const val HERO_CYAN_BOTTOM = 0xFF0A090E.toInt()
    const val HERO_CYAN_BORDER = 0xFF5B34B8.toInt()

    const val HERO_AMBER_TOP = 0xFF2A1606.toInt()
    const val HERO_AMBER_BOTTOM = 0xFF0B0907.toInt()
    const val HERO_AMBER_BORDER = 0xFF8F4C0E.toInt()

    // Neon lime
    const val EMERALD = 0xFFC6F432.toInt()
    const val EMERALD_BRIGHT = 0xFFDAFF5E.toInt()
    const val EMERALD_DARK = 0xFF9ACB10.toInt()

    // Electric purple
    const val CYAN = 0xFF9D6BFF.toInt()
    const val CYAN_BRIGHT = 0xFFBB97FF.toInt()
    const val CYAN_DARK = 0xFF7C3AED.toInt()

    // Hot orange / red / magenta
    const val AMBER = 0xFFFF9F1C.toInt()
    const val AMBER_BRIGHT = 0xFFFFB84D.toInt()
    const val CRIMSON = 0xFFFF3B5C.toInt()
    const val CRIMSON_DARK = 0xFFE11D48.toInt()
    const val VIOLET = 0xFFF472D0.toInt()

    const val TEXT_PRIMARY = 0xFFFFFFFF.toInt()
    const val TEXT_SECONDARY = 0xFFA1A1AA.toInt()
    const val TEXT_MUTED = 0xFF71717A.toInt()
    const val TEXT_ON_DARK_ACCENT = 0xFF0A0F00.toInt()
}

enum class IconKind {
    TRACK_EMBLEM,
    NAV_TRACKER,
    NAV_PROGRESS,
    NAV_SETTINGS,
    AUDIO_SMART,
    AUDIO_ALL,
    AUDIO_MUTED,
    SHARE,
    TRASH
}

object AthleticTheme {
    val FONT_SANS_MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val FONT_SANS_BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val FONT_CONDENSED_BOLD: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    // Roboto Black: heavy athletic numerals (Roboto digits are tabular by default, so live stats never jitter)
    val FONT_TABULAR_BOLD: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    fun lerpColor(startColor: Int, endColor: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val sA = (startColor ushr 24) and 0xFF
        val sR = (startColor ushr 16) and 0xFF
        val sG = (startColor ushr 8) and 0xFF
        val sB = startColor and 0xFF

        val eA = (endColor ushr 24) and 0xFF
        val eR = (endColor ushr 16) and 0xFF
        val eG = (endColor ushr 8) and 0xFF
        val eB = endColor and 0xFF

        val a = (sA + (eA - sA) * f).roundToInt()
        val r = (sR + (eR - sR) * f).roundToInt()
        val g = (sG + (eG - sG) * f).roundToInt()
        val b = (sB + (eB - sB) * f).roundToInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun gradientCard(
        ctx: Context,
        topColor: Int = AthleticColors.SURFACE_TOP,
        bottomColor: Int = AthleticColors.SURFACE_BOTTOM,
        borderColor: Int = AthleticColors.BORDER_SUBTLE,
        radiusDp: Float = 22f,
        strokeDp: Float = 1f
    ): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(topColor, bottomColor)
    ).apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(ctx, radiusDp).toFloat()
        if (strokeDp > 0f) {
            setStroke(dp(ctx, strokeDp).coerceAtLeast(1), borderColor)
        }
    }

    fun solidPill(
        ctx: Context,
        fillColor: Int,
        borderColor: Int = fillColor,
        radiusDp: Float = 12f,
        strokeDp: Float = 1f
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(ctx, radiusDp).toFloat()
        setColor(fillColor)
        if (strokeDp > 0f) {
            setStroke(dp(ctx, strokeDp).coerceAtLeast(1), borderColor)
        }
    }

    fun rippleSurface(
        ctx: Context,
        startColor: Int,
        endColor: Int = startColor,
        borderColor: Int = startColor,
        radiusDp: Float = 14f,
        rippleColor: Int = 0x33FFFFFF
    ): Drawable {
        val content = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(startColor, endColor)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setStroke(dp(ctx, 1f).coerceAtLeast(1), borderColor)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setColor(0xFFFFFFFF.toInt())
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    fun createButton(
        ctx: Context,
        label: String,
        style: ButtonStyle = ButtonStyle.PRIMARY_EMERALD,
        heightDp: Float = 50f
    ): TextView {
        return TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = FONT_SANS_BOLD
            letterSpacing = 0.04f
            isClickable = true
            isFocusable = true
            minHeight = dp(ctx, heightDp)
            setPadding(dp(ctx, 18f), dp(ctx, 13f), dp(ctx, 18f), dp(ctx, 13f))
            applyButtonStyle(ctx, this, style)
        }
    }

    enum class ButtonStyle {
        PRIMARY_EMERALD,
        PRIMARY_CYAN,
        WARNING_AMBER,
        DANGER_CRIMSON,
        SECONDARY_SLATE,
        SUBTLE_OUTLINE
    }

    // Fully rounded pill buttons (Nike Run Club / Strava style)
    fun applyButtonStyle(ctx: Context, view: TextView, style: ButtonStyle, radiusDp: Float = 28f) {
        when (style) {
            ButtonStyle.PRIMARY_EMERALD -> {
                view.setTextColor(AthleticColors.TEXT_ON_DARK_ACCENT)
                view.background = rippleSurface(
                    ctx,
                    AthleticColors.EMERALD_BRIGHT,
                    AthleticColors.EMERALD_DARK,
                    AthleticColors.EMERALD_BRIGHT,
                    radiusDp,
                    0x33000000
                )
            }
            ButtonStyle.PRIMARY_CYAN -> {
                // White text on electric purple for legible contrast
                view.setTextColor(AthleticColors.TEXT_PRIMARY)
                view.background = rippleSurface(
                    ctx,
                    AthleticColors.CYAN,
                    AthleticColors.CYAN_DARK,
                    AthleticColors.CYAN_BRIGHT,
                    radiusDp,
                    0x33FFFFFF
                )
            }
            ButtonStyle.WARNING_AMBER -> {
                view.setTextColor(AthleticColors.TEXT_ON_DARK_ACCENT)
                view.background = rippleSurface(
                    ctx,
                    AthleticColors.AMBER_BRIGHT,
                    AthleticColors.AMBER,
                    AthleticColors.AMBER_BRIGHT,
                    radiusDp,
                    0x33000000
                )
            }
            ButtonStyle.DANGER_CRIMSON -> {
                view.setTextColor(AthleticColors.TEXT_PRIMARY)
                view.background = rippleSurface(
                    ctx,
                    AthleticColors.CRIMSON,
                    AthleticColors.CRIMSON_DARK,
                    AthleticColors.CRIMSON,
                    radiusDp
                )
            }
            ButtonStyle.SECONDARY_SLATE -> {
                view.setTextColor(AthleticColors.TEXT_PRIMARY)
                view.background = rippleSurface(
                    ctx,
                    0xFF1E1E23.toInt(),
                    0xFF141417.toInt(),
                    AthleticColors.BORDER_STRONG,
                    radiusDp
                )
            }
            ButtonStyle.SUBTLE_OUTLINE -> {
                view.setTextColor(AthleticColors.TEXT_SECONDARY)
                view.background = rippleSurface(
                    ctx,
                    AthleticColors.SURFACE_RECESSED,
                    AthleticColors.SURFACE_RECESSED,
                    AthleticColors.BORDER_SUBTLE,
                    radiusDp
                )
            }
        }
    }

    fun sectionLabel(ctx: Context, title: String, accentColor: Int = AthleticColors.TEXT_SECONDARY): TextView =
        TextView(ctx).apply {
            text = title
            textSize = 11f
            typeface = FONT_SANS_BOLD
            setTextColor(accentColor)
            letterSpacing = 0.08f
        }

    fun hairlineDivider(ctx: Context, verticalMarginDp: Float = 8f): View = View(ctx).apply {
        setBackgroundColor(AthleticColors.DIVIDER)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(ctx, 1f).coerceAtLeast(1)
        ).apply {
            topMargin = dp(ctx, verticalMarginDp)
            bottomMargin = dp(ctx, verticalMarginDp)
        }
    }
}

/**
 * Crisp Canvas-rendered vector icon view (zero external bitmap dependencies, sharp at any DPI).
 */
class VectorIconView(
    context: Context,
    var kind: IconKind,
    var tintColor: Int = AthleticColors.TEXT_PRIMARY,
    private val sizeDp: Float = 20f
) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val path = Path()
    private val rect = RectF()

    fun update(newKind: IconKind = kind, newTint: Int = tintColor) {
        kind = newKind
        tintColor = newTint
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val px = dp(sizeDp).roundToInt()
        setMeasuredDimension(resolveSize(px, widthMeasureSpec), resolveSize(px, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val s = min(w, h)
        val cx = w / 2f
        val cy = h / 2f
        val ox = cx - s / 2f
        val oy = cy - s / 2f
        fun x(f: Float): Float = ox + s * f
        fun y(f: Float): Float = oy + s * f

        strokePaint.color = tintColor
        strokePaint.strokeWidth = dp(1.9f)
        fillPaint.color = tintColor

        when (kind) {
            IconKind.TRACK_EMBLEM -> {
                // Compact Sprinting Runner Emblem (generous inner margin)
                fillPaint.color = 0x20C6F432
                canvas.drawCircle(cx, cy, s * 0.46f, fillPaint)

                // Electric-purple sweeping stadium track arc
                strokePaint.color = AthleticColors.CYAN
                strokePaint.strokeWidth = s * 0.048f
                rect.set(x(0.14f), y(0.14f), x(0.86f), y(0.86f))
                canvas.drawArc(rect, 140f, 245f, false, strokePaint)

                // Compact speed trails behind sprinter
                strokePaint.strokeWidth = s * 0.036f
                strokePaint.color = AthleticColors.CYAN_BRIGHT
                canvas.drawLine(x(0.24f), y(0.43f), x(0.35f), y(0.43f), strokePaint)
                strokePaint.color = AthleticColors.EMERALD
                canvas.drawLine(x(0.21f), y(0.50f), x(0.33f), y(0.50f), strokePaint)

                // Compact forward-leaning sprinter head
                fillPaint.color = AthleticColors.EMERALD_BRIGHT
                canvas.drawCircle(x(0.60f), y(0.32f), s * 0.052f, fillPaint)

                // Compact V-tapered forward-leaning torso
                path.reset()
                path.moveTo(x(0.50f), y(0.38f))
                path.lineTo(x(0.57f), y(0.405f))
                path.lineTo(x(0.48f), y(0.54f))
                path.lineTo(x(0.425f), y(0.515f))
                path.close()
                canvas.drawPath(path, fillPaint)

                // Rear counter-swing arm + front pumping lead arm
                strokePaint.color = AthleticColors.EMERALD
                strokePaint.strokeWidth = s * 0.048f
                path.reset()
                path.moveTo(x(0.51f), y(0.395f))
                path.lineTo(x(0.42f), y(0.40f))
                path.lineTo(x(0.38f), y(0.47f))
                canvas.drawPath(path, strokePaint)

                strokePaint.color = AthleticColors.EMERALD_BRIGHT
                path.reset()
                path.moveTo(x(0.55f), y(0.41f))
                path.lineTo(x(0.61f), y(0.47f))
                path.lineTo(x(0.69f), y(0.42f))
                canvas.drawPath(path, strokePaint)

                // Rear push-off leg + front high-knee sprint leg
                strokePaint.color = AthleticColors.EMERALD
                strokePaint.strokeWidth = s * 0.052f
                path.reset()
                path.moveTo(x(0.44f), y(0.525f))
                path.lineTo(x(0.36f), y(0.625f))
                path.lineTo(x(0.27f), y(0.665f))
                canvas.drawPath(path, strokePaint)

                strokePaint.color = AthleticColors.EMERALD_BRIGHT
                path.reset()
                path.moveTo(x(0.47f), y(0.53f))
                path.lineTo(x(0.61f), y(0.555f))
                path.lineTo(x(0.57f), y(0.70f))
                path.lineTo(x(0.61f), y(0.71f))
                canvas.drawPath(path, strokePaint)
            }

            IconKind.NAV_TRACKER -> {
                // Crisp athletic stopwatch (1:1 square-bounded)
                val bodyCy = y(0.55f)
                canvas.drawCircle(cx, bodyCy, s * 0.34f, strokePaint)
                canvas.drawLine(x(0.39f), y(0.11f), x(0.61f), y(0.11f), strokePaint)
                canvas.drawLine(cx, y(0.11f), cx, y(0.21f), strokePaint)
                canvas.drawLine(x(0.73f), y(0.27f), x(0.81f), y(0.19f), strokePaint)
                canvas.drawLine(cx, bodyCy, x(0.64f), y(0.41f), strokePaint)
                canvas.drawCircle(cx, bodyCy, s * 0.06f, fillPaint)
            }

            IconKind.NAV_PROGRESS -> {
                // 3 ascending rounded performance bars (1:1 square-bounded)
                strokePaint.strokeWidth = dp(2.6f)
                canvas.drawLine(x(0.23f), y(0.84f), x(0.23f), y(0.58f), strokePaint)
                canvas.drawLine(x(0.50f), y(0.84f), x(0.50f), y(0.38f), strokePaint)
                canvas.drawLine(x(0.77f), y(0.84f), x(0.77f), y(0.16f), strokePaint)
            }

            IconKind.NAV_SETTINGS -> {
                // Dual precision tuning sliders with open-knob tracks (1:1 square-bounded)
                val r = s * 0.115f
                val y1 = y(0.33f)
                val kx1 = x(0.63f)
                canvas.drawLine(x(0.15f), y1, kx1 - r, y1, strokePaint)
                canvas.drawLine(kx1 + r, y1, x(0.85f), y1, strokePaint)
                canvas.drawCircle(kx1, y1, r, strokePaint)

                val y2 = y(0.67f)
                val kx2 = x(0.37f)
                canvas.drawLine(x(0.15f), y2, kx2 - r, y2, strokePaint)
                canvas.drawLine(kx2 + r, y2, x(0.85f), y2, strokePaint)
                canvas.drawCircle(kx2, y2, r, strokePaint)
            }

            IconKind.AUDIO_SMART, IconKind.AUDIO_ALL, IconKind.AUDIO_MUTED -> {
                // Speaker cone (1:1 square-bounded)
                path.reset()
                path.moveTo(x(0.18f), y(0.40f))
                path.lineTo(x(0.34f), y(0.40f))
                path.lineTo(x(0.54f), y(0.22f))
                path.lineTo(x(0.54f), y(0.78f))
                path.lineTo(x(0.34f), y(0.60f))
                path.lineTo(x(0.18f), y(0.60f))
                path.close()
                canvas.drawPath(path, strokePaint)

                if (kind == IconKind.AUDIO_MUTED) {
                    canvas.drawLine(x(0.66f), y(0.36f), x(0.86f), y(0.64f), strokePaint)
                    canvas.drawLine(x(0.86f), y(0.36f), x(0.66f), y(0.64f), strokePaint)
                } else {
                    rect.set(x(0.48f), y(0.32f), x(0.74f), y(0.68f))
                    canvas.drawArc(rect, -45f, 90f, false, strokePaint)
                    if (kind == IconKind.AUDIO_ALL) {
                        rect.set(x(0.54f), y(0.20f), x(0.90f), y(0.80f))
                        canvas.drawArc(rect, -48f, 96f, false, strokePaint)
                    }
                }
            }

            IconKind.SHARE -> {
                // Upward share arrow + rounded bottom tray
                canvas.drawLine(x(0.50f), y(0.60f), x(0.50f), y(0.16f), strokePaint)
                path.reset()
                path.moveTo(x(0.33f), y(0.33f))
                path.lineTo(x(0.50f), y(0.16f))
                path.lineTo(x(0.67f), y(0.33f))
                canvas.drawPath(path, strokePaint)

                path.reset()
                path.moveTo(x(0.22f), y(0.54f))
                path.lineTo(x(0.22f), y(0.76f))
                path.quadTo(x(0.22f), y(0.84f), x(0.30f), y(0.84f))
                path.lineTo(x(0.70f), y(0.84f))
                path.quadTo(x(0.78f), y(0.84f), x(0.78f), y(0.76f))
                path.lineTo(x(0.78f), y(0.54f))
                canvas.drawPath(path, strokePaint)
            }

            IconKind.TRASH -> {
                canvas.drawLine(x(0.22f), y(0.28f), x(0.78f), y(0.28f), strokePaint)
                rect.set(x(0.30f), y(0.28f), x(0.70f), y(0.82f))
                canvas.drawRoundRect(rect, dp(2f), dp(2f), strokePaint)
                canvas.drawLine(x(0.40f), y(0.18f), x(0.60f), y(0.18f), strokePaint)
            }
        }
    }
}

/**
 * Modern iOS / Samsung OneUI style pill toggle switch (replaces ugly stock Android Switch).
 */
class ModernToggleSwitchView(context: Context, initialChecked: Boolean = false) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    var isChecked: Boolean = initialChecked
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var onCheckedChange: ((Boolean) -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
    }
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            isChecked = !isChecked
            onCheckedChange?.invoke(isChecked)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = dp(46f).roundToInt()
        val h = dp(26f).roundToInt()
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        rect.set(dp(1f), dp(1f), w - dp(1f), h - dp(1f))

        trackPaint.color = if (isChecked) AthleticColors.EMERALD else 0xFF1E1E23.toInt()
        canvas.drawRoundRect(rect, r, r, trackPaint)

        if (!isChecked) {
            borderPaint.color = AthleticColors.BORDER_STRONG
            canvas.drawRoundRect(rect, r, r, borderPaint)
        }

        val thumbRadius = (h - dp(6f)) / 2f
        val cx = if (isChecked) w - dp(3f) - thumbRadius else dp(3f) + thumbRadius
        val cy = h / 2f
        thumbPaint.color = if (isChecked) 0xFF0A0F00.toInt() else 0xFFD4D4D8.toInt()
        canvas.drawCircle(cx, cy, thumbRadius, thumbPaint)
    }
}

/**
 * Clean 2-line setting row paired with [ModernToggleSwitchView].
 */
class ModernSettingRow(
    context: Context,
    title: String,
    subtitle: String,
    initialChecked: Boolean,
    onToggle: (Boolean) -> Unit
) : LinearLayout(context) {

    val toggleView = ModernToggleSwitchView(context, initialChecked)

    var isChecked: Boolean
        get() = toggleView.isChecked
        set(value) {
            toggleView.isChecked = value
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val padV = AthleticTheme.dp(context, 10f)
        setPadding(0, padV, 0, padV)
        isClickable = true
        isFocusable = true

        val textCol = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = AthleticTheme.dp(context, 12f)
            }
        }
        textCol.addView(TextView(context).apply {
            text = title
            textSize = 13.5f
            typeface = AthleticTheme.FONT_SANS_MEDIUM
            setTextColor(AthleticColors.TEXT_PRIMARY)
        })
        textCol.addView(TextView(context).apply {
            text = subtitle
            textSize = 11.5f
            setTextColor(AthleticColors.TEXT_SECONDARY)
            setPadding(0, AthleticTheme.dp(context, 2f), 0, 0)
        })

        toggleView.onCheckedChange = onToggle
        setOnClickListener {
            toggleView.isChecked = !toggleView.isChecked
            onToggle(toggleView.isChecked)
        }

        addView(textCol)
        addView(toggleView)
    }
}

/**
 * Sleek stadium-pill bottom navigation tab item with strictly centered square icon + centered label
 * and continuous 120Hz selection interpolation driven by [SlidingBottomNavDock].
 */
class BottomNavTabView(
    context: Context,
    iconKind: IconKind,
    label: String
) : LinearLayout(context) {
    private val iconView = VectorIconView(context, iconKind, AthleticColors.TEXT_SECONDARY, 20f)
    private val labelView = TextView(context)
    private var lastFraction = -1f
    private var isBoldLabel = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setPadding(
            AthleticTheme.dp(context, 6f),
            AthleticTheme.dp(context, 7f),
            AthleticTheme.dp(context, 6f),
            AthleticTheme.dp(context, 7f)
        )

        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = AthleticTheme.dp(context, 26f).toFloat()
            setColor(0xFFFFFFFF.toInt())
        }
        background = RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), null, mask)

        val iconSizePx = AthleticTheme.dp(context, 20f)
        iconView.layoutParams = LayoutParams(iconSizePx, iconSizePx).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }

        labelView.apply {
            text = label
            textSize = 11.5f
            typeface = AthleticTheme.FONT_SANS_MEDIUM
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            maxLines = 1
            setTextColor(0xFF94949E.toInt())
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = AthleticTheme.dp(context, 3.5f)
            }
        }

        addView(iconView)
        addView(labelView)
        setSelectionFraction(0f)
    }

    fun setSelectionFraction(fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        if (abs(f - lastFraction) < 0.003f) return
        lastFraction = f

        val iconCol = AthleticTheme.lerpColor(
            AthleticColors.TEXT_SECONDARY,
            AthleticColors.EMERALD_BRIGHT,
            f
        )
        val textCol = AthleticTheme.lerpColor(
            0xFF94949E.toInt(),
            AthleticColors.EMERALD_BRIGHT,
            f
        )
        iconView.update(newTint = iconCol)
        val scale = 1f + 0.08f * f
        iconView.scaleX = scale
        iconView.scaleY = scale
        iconView.translationY = -context.resources.displayMetrics.density * 1.0f * f

        labelView.setTextColor(textCol)
        val wantBold = f >= 0.5f
        if (wantBold != isBoldLabel) {
            isBoldLabel = wantBold
            labelView.typeface = if (wantBold) AthleticTheme.FONT_SANS_BOLD else AthleticTheme.FONT_SANS_MEDIUM
        }
    }

    fun setActive(active: Boolean) {
        setSelectionFraction(if (active) 1f else 0f)
    }

    fun setBadgeLabel(newLabel: String) {
        labelView.text = newLabel
    }
}

/**
 * Contoured AMOLED stadium-capsule bottom navigation dock with a continuous hardware-accelerated
 * sliding neon-lime pill that tracks both horizontal page swipes AND direct thumb-scrubbing on the dock.
 */
class SlidingBottomNavDock(
    context: Context,
    specs: List<Pair<IconKind, String>>,
    private val onTabTapped: (Int) -> Unit
) : FrameLayout(context) {

    private val slidingPill = View(context)
    private val tabsRow = LinearLayout(context)
    val tabViews: List<BottomNavTabView>

    var onScrubStart: (() -> Unit)? = null
    var onScrubDelta: ((Float) -> Unit)? = null
    var onScrubEnd: ((Int) -> Unit)? = null

    var currentProgress: Float = 0f
        private set

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop * 0.75f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var startProgress = 0f
    private var isScrubbing = false

    init {
        val pad = AthleticTheme.dp(context, 5f)
        setPadding(pad, pad, pad, pad)
        clipToPadding = false
        background = AthleticTheme.gradientCard(
            context,
            topColor = 0xFF151519.toInt(),
            bottomColor = 0xFF0C0C10.toInt(),
            borderColor = AthleticColors.BORDER_STRONG,
            radiusDp = 32f,
            strokeDp = 1f
        )
        elevation = AthleticTheme.dp(context, 8f).toFloat()

        slidingPill.background = AthleticTheme.gradientCard(
            context,
            topColor = 0xFF253409.toInt(),
            bottomColor = 0xFF141C05.toInt(),
            borderColor = 0xFF84B61D.toInt(),
            radiusDp = 26f,
            strokeDp = 1.2f
        )
        addView(slidingPill, LayoutParams(0, 0))

        tabsRow.orientation = LinearLayout.HORIZONTAL
        tabsRow.gravity = Gravity.CENTER_VERTICAL
        val builtTabs = mutableListOf<BottomNavTabView>()
        specs.forEachIndexed { index, (icon, label) ->
            val tv = BottomNavTabView(context, icon, label).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { onTabTapped(index) }
            }
            builtTabs.add(tv)
            tabsRow.addView(tv)
        }
        tabViews = builtTabs
        addView(
            tabsRow,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        setScrollProgress(0f)
    }

    fun setScrollProgress(progress: Float) {
        val maxIdx = (tabViews.size - 1).coerceAtLeast(0).toFloat()
        currentProgress = progress.coerceIn(0f, maxIdx)
        applyProgressToViews()
    }

    private fun applyProgressToViews() {
        val innerW = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        if (innerW > 0 && tabViews.isNotEmpty()) {
            val tabW = innerW.toFloat() / tabViews.size
            slidingPill.translationX = currentProgress * tabW
        }
        tabViews.forEachIndexed { i, tv ->
            val frac = (1f - abs(currentProgress - i)).coerceIn(0f, 1f)
            tv.setSelectionFraction(frac)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val innerW = (right - left - paddingLeft - paddingRight).coerceAtLeast(0)
        val innerH = (bottom - top - paddingTop - paddingBottom).coerceAtLeast(0)
        if (tabViews.isNotEmpty() && innerW > 0 && innerH > 0) {
            val pillW = (innerW.toFloat() / tabViews.size).roundToInt()
            slidingPill.measure(
                MeasureSpec.makeMeasureSpec(pillW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(innerH, MeasureSpec.EXACTLY)
            )
            slidingPill.layout(paddingLeft, paddingTop, paddingLeft + pillW, paddingTop + innerH)
            applyProgressToViews()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                startProgress = currentProgress
                isScrubbing = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                if (dx > touchSlop && dx > dy) {
                    isScrubbing = true
                    lastX = ev.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onScrubStart?.invoke()
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                if (!isScrubbing) {
                    val dx = abs(ev.x - downX)
                    val dy = abs(ev.y - downY)
                    if (dx > touchSlop && dx > dy) {
                        isScrubbing = true
                        lastX = ev.x
                        parent?.requestDisallowInterceptTouchEvent(true)
                        onScrubStart?.invoke()
                    }
                }
                if (isScrubbing) {
                    val innerW = (width - paddingLeft - paddingRight).coerceAtLeast(1)
                    val tabW = innerW.toFloat() / tabViews.size.coerceAtLeast(1)
                    val deltaProgress = (ev.x - lastX) / tabW
                    lastX = ev.x
                    onScrubDelta?.invoke(deltaProgress)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isScrubbing) {
                    isScrubbing = false
                    val lastIdx = (tabViews.size - 1).coerceAtLeast(0)
                    val moved = currentProgress - startProgress
                    val target = when {
                        moved > 0.22f && moved < 0.5f -> (startProgress.roundToInt() + 1).coerceIn(0, lastIdx)
                        moved < -0.22f && moved > -0.5f -> (startProgress.roundToInt() - 1).coerceIn(0, lastIdx)
                        else -> currentProgress.roundToInt().coerceIn(0, lastIdx)
                    }
                    onScrubEnd?.invoke(target)
                }
            }
        }
        return true
    }
}

/**
 * Fluid 3-segment capsule switcher with an animated color-morphing sliding pill and thumb-scrubbing.
 */
class SlidingSegmentedBar(
    context: Context,
    labels: List<String>,
    private val accentColors: IntArray,
    private val onSegmentSelected: (Int) -> Unit
) : FrameLayout(context) {

    private val pillDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = AthleticTheme.dp(context, 12f).toFloat()
        setColor(accentColors.firstOrNull() ?: AthleticColors.EMERALD_BRIGHT)
    }
    private val slidingPill = View(context).apply { background = pillDrawable }
    private val labelsRow = LinearLayout(context)
    val segmentViews: List<TextView>

    private var currentProgress = 0f
    private var activeIndex = 0
    private var pillAnimator: ValueAnimator? = null
    private val smoothInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop * 0.75f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var startProgress = 0f
    private var isScrubbing = false

    init {
        val pad = AthleticTheme.dp(context, 4f)
        setPadding(pad, pad, pad, pad)
        clipToPadding = false
        background = AthleticTheme.solidPill(
            context,
            AthleticColors.SURFACE_RECESSED,
            AthleticColors.BORDER_SUBTLE,
            15f
        )
        addView(slidingPill, LayoutParams(0, 0))

        labelsRow.orientation = LinearLayout.HORIZONTAL
        labelsRow.gravity = Gravity.CENTER_VERTICAL
        val builtViews = mutableListOf<TextView>()
        labels.forEachIndexed { idx, text ->
            val tv = TextView(context).apply {
                this.text = text
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                textSize = 12.5f
                typeface = AthleticTheme.FONT_SANS_BOLD
                maxLines = 1
                setPadding(
                    AthleticTheme.dp(context, 6f),
                    AthleticTheme.dp(context, 10f),
                    AthleticTheme.dp(context, 6f),
                    AthleticTheme.dp(context, 10f)
                )
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    selectSegment(idx, animate = true)
                    onSegmentSelected(idx)
                }
            }
            builtViews.add(tv)
            labelsRow.addView(tv)
        }
        segmentViews = builtViews
        addView(
            labelsRow,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        setProgressInternal(0f)
    }

    fun selectSegment(index: Int, animate: Boolean = true) {
        val clamped = index.coerceIn(0, (segmentViews.size - 1).coerceAtLeast(0))
        activeIndex = clamped
        pillAnimator?.cancel()
        val target = clamped.toFloat()
        if (!animate || !isLaidOut || abs(currentProgress - target) < 0.005f) {
            setProgressInternal(target)
            return
        }
        val start = currentProgress
        pillAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = 250L
            interpolator = smoothInterpolator
            addUpdateListener { anim ->
                setProgressInternal(anim.animatedValue as Float)
            }
            start()
        }
    }

    private fun sampleColor(progress: Float): Int {
        if (accentColors.isEmpty()) return AthleticColors.EMERALD_BRIGHT
        val maxIdx = accentColors.lastIndex
        val clamped = progress.coerceIn(0f, maxIdx.toFloat())
        val low = clamped.toInt().coerceIn(0, maxIdx)
        val high = (low + 1).coerceIn(0, maxIdx)
        val frac = clamped - low
        return AthleticTheme.lerpColor(accentColors[low], accentColors[high], frac)
    }

    private fun setProgressInternal(progress: Float) {
        val maxIdx = (segmentViews.size - 1).coerceAtLeast(0).toFloat()
        currentProgress = progress.coerceIn(0f, maxIdx)
        pillDrawable.setColor(sampleColor(currentProgress))
        val innerW = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        if (innerW > 0 && segmentViews.isNotEmpty()) {
            val segW = innerW.toFloat() / segmentViews.size
            slidingPill.translationX = currentProgress * segW
        }
        segmentViews.forEachIndexed { idx, tv ->
            val frac = (1f - abs(currentProgress - idx)).coerceIn(0f, 1f)
            tv.setTextColor(
                AthleticTheme.lerpColor(
                    AthleticColors.TEXT_SECONDARY,
                    AthleticColors.TEXT_ON_DARK_ACCENT,
                    frac
                )
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val innerW = (right - left - paddingLeft - paddingRight).coerceAtLeast(0)
        val innerH = (bottom - top - paddingTop - paddingBottom).coerceAtLeast(0)
        if (segmentViews.isNotEmpty() && innerW > 0 && innerH > 0) {
            val pillW = (innerW.toFloat() / segmentViews.size).roundToInt()
            slidingPill.measure(
                MeasureSpec.makeMeasureSpec(pillW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(innerH, MeasureSpec.EXACTLY)
            )
            slidingPill.layout(paddingLeft, paddingTop, paddingLeft + pillW, paddingTop + innerH)
            setProgressInternal(currentProgress)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                startProgress = currentProgress
                isScrubbing = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                if (dx > touchSlop && dx > dy) {
                    isScrubbing = true
                    lastX = ev.x
                    pillAnimator?.cancel()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                if (!isScrubbing) {
                    val dx = abs(ev.x - downX)
                    val dy = abs(ev.y - downY)
                    if (dx > touchSlop && dx > dy) {
                        isScrubbing = true
                        lastX = ev.x
                        pillAnimator?.cancel()
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                if (isScrubbing) {
                    val innerW = (width - paddingLeft - paddingRight).coerceAtLeast(1)
                    val segW = innerW.toFloat() / segmentViews.size.coerceAtLeast(1)
                    val delta = (ev.x - lastX) / segW
                    lastX = ev.x
                    setProgressInternal(currentProgress + delta)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isScrubbing) {
                    isScrubbing = false
                    val lastIdx = (segmentViews.size - 1).coerceAtLeast(0)
                    val moved = currentProgress - startProgress
                    val target = when {
                        moved > 0.22f && moved < 0.5f -> (startProgress.roundToInt() + 1).coerceIn(0, lastIdx)
                        moved < -0.22f && moved > -0.5f -> (startProgress.roundToInt() - 1).coerceIn(0, lastIdx)
                        else -> currentProgress.roundToInt().coerceIn(0, lastIdx)
                    }
                    selectSegment(target, animate = true)
                    onSegmentSelected(target)
                }
            }
        }
        return true
    }
}

/**
 * Custom AMOLED stadium-pill progress bar for OTA updates (supports both animated indeterminate
 * comet sweep and smooth determinate Electric Purple -> Neon Lime gradient fill).
 */
class NeonUpdateProgressBarView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF17171E.toInt()
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = AthleticColors.BORDER_STRONG
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rect = RectF()
    private val fillRect = RectF()

    var isIndeterminateMode: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                syncIndeterminateAnim()
                invalidate()
            }
        }

    private var displayedFraction = 0f
    private var progressAnim: ValueAnimator? = null
    private var cometPhase = 0f
    private var cometAnim: ValueAnimator? = null

    fun setProgressPercent(percent: Int, animate: Boolean = true) {
        isIndeterminateMode = false
        val target = (percent.coerceIn(0, 100)) / 100f
        progressAnim?.cancel()
        if (!animate || !isLaidOut || abs(displayedFraction - target) < 0.005f) {
            displayedFraction = target
            invalidate()
            return
        }
        progressAnim = ValueAnimator.ofFloat(displayedFraction, target).apply {
            duration = 150L
            addUpdateListener { a ->
                displayedFraction = a.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun syncIndeterminateAnim() {
        if (isIndeterminateMode && isAttachedToWindow && visibility == VISIBLE) {
            if (cometAnim?.isRunning == true) return
            cometAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1150L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                addUpdateListener { a ->
                    cometPhase = a.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            cometAnim?.cancel()
            cometAnim = null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncIndeterminateAnim()
    }

    override fun onDetachedFromWindow() {
        cometAnim?.cancel()
        cometAnim = null
        progressAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncIndeterminateAnim()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = dp(12f).roundToInt()
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, resolveSize(desiredH, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) {
            fillPaint.shader = LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                intArrayOf(AthleticColors.CYAN, AthleticColors.EMERALD, AthleticColors.EMERALD_BRIGHT),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 2f || h <= 2f) return
        val r = h / 2f
        val inset = dp(0.6f)
        rect.set(inset, inset, w - inset, h - inset)
        canvas.drawRoundRect(rect, r, r, trackPaint)
        canvas.drawRoundRect(rect, r, r, borderPaint)

        if (isIndeterminateMode) {
            val cometW = (w * 0.36f).coerceAtLeast(h)
            val travel = w + cometW
            val left = (-cometW + cometPhase * travel).coerceIn(inset, w - inset)
            val right = (-cometW + cometPhase * travel + cometW).coerceIn(inset, w - inset)
            if (right - left > dp(2f)) {
                fillRect.set(left, inset, right, h - inset)
                canvas.drawRoundRect(fillRect, r, r, fillPaint)
            }
        } else if (displayedFraction > 0.002f) {
            val fillW = ((w - inset * 2f) * displayedFraction).coerceAtLeast(h - inset * 2f)
            fillRect.set(inset, inset, (inset + fillW).coerceAtMost(w - inset), h - inset)
            canvas.drawRoundRect(fillRect, r, r, fillPaint)
        }
    }
}
