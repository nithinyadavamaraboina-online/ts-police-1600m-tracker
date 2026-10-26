package com.tspolice.runtracker.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

object AthleticColors {
    const val BG_CANVAS = 0xFF060A12.toInt()
    const val BG_HEADER = 0xFF080E1A.toInt()
    const val BG_NAV = 0xFF090F1D.toInt()

    const val SURFACE_TOP = 0xFF10192B.toInt()
    const val SURFACE_BOTTOM = 0xFF0B111E.toInt()
    const val SURFACE_RECESSED = 0xFF070C15.toInt()
    const val BORDER_SUBTLE = 0xFF1C2A44.toInt()
    const val BORDER_STRONG = 0xFF293B5C.toInt()
    const val DIVIDER = 0xFF162136.toInt()

    const val HERO_EMERALD_TOP = 0xFF0D2924.toInt()
    const val HERO_EMERALD_BOTTOM = 0xFF0A1322.toInt()
    const val HERO_EMERALD_BORDER = 0xFF1D6B53.toInt()

    const val HERO_CYAN_TOP = 0xFF0D233A.toInt()
    const val HERO_CYAN_BOTTOM = 0xFF0A1322.toInt()
    const val HERO_CYAN_BORDER = 0xFF1E5680.toInt()

    const val HERO_AMBER_TOP = 0xFF2B1E0D.toInt()
    const val HERO_AMBER_BOTTOM = 0xFF0B1220.toInt()
    const val HERO_AMBER_BORDER = 0xFF855516.toInt()

    const val EMERALD = 0xFF10B981.toInt()
    const val EMERALD_BRIGHT = 0xFF34D399.toInt()
    const val EMERALD_DARK = 0xFF059669.toInt()

    const val CYAN = 0xFF38BDF8.toInt()
    const val CYAN_BRIGHT = 0xFF7DD3FC.toInt()
    const val CYAN_DARK = 0xFF0284C7.toInt()

    const val AMBER = 0xFFF59E0B.toInt()
    const val AMBER_BRIGHT = 0xFFFBBF24.toInt()
    const val CRIMSON = 0xFFF43F5E.toInt()
    const val CRIMSON_DARK = 0xFFE11D48.toInt()
    const val VIOLET = 0xFFA78BFA.toInt()

    const val TEXT_PRIMARY = 0xFFF8FAFC.toInt()
    const val TEXT_SECONDARY = 0xFF94A3B8.toInt()
    const val TEXT_MUTED = 0xFF64748B.toInt()
    const val TEXT_ON_DARK_ACCENT = 0xFF03140E.toInt()
}

enum class IconKind {
    TRACK_EMBLEM,
    NAV_TRACKER,
    NAV_PROGRESS,
    NAV_SETTINGS,
    AUDIO_SMART,
    AUDIO_ALL,
    AUDIO_MUTED,
    TRASH
}

object AthleticTheme {
    val FONT_SANS_MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val FONT_SANS_BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val FONT_CONDENSED_BOLD: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val FONT_TABULAR_BOLD: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    fun gradientCard(
        ctx: Context,
        topColor: Int = AthleticColors.SURFACE_TOP,
        bottomColor: Int = AthleticColors.SURFACE_BOTTOM,
        borderColor: Int = AthleticColors.BORDER_SUBTLE,
        radiusDp: Float = 18f,
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
            isClickable = true
            isFocusable = true
            minHeight = dp(ctx, heightDp)
            setPadding(dp(ctx, 16f), dp(ctx, 13f), dp(ctx, 16f), dp(ctx, 13f))
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

    fun applyButtonStyle(ctx: Context, view: TextView, style: ButtonStyle, radiusDp: Float = 14f) {
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
                view.setTextColor(AthleticColors.TEXT_ON_DARK_ACCENT)
                view.background = rippleSurface(
                    ctx,
                    AthleticColors.CYAN_BRIGHT,
                    AthleticColors.CYAN_DARK,
                    AthleticColors.CYAN_BRIGHT,
                    radiusDp,
                    0x33000000
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
                    0xFF162238.toInt(),
                    0xFF10192B.toInt(),
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

        strokePaint.color = tintColor
        strokePaint.strokeWidth = dp(1.9f)
        fillPaint.color = tintColor

        when (kind) {
            IconKind.TRACK_EMBLEM -> {
                // Glowing emerald stadium track emblem with speed bolt
                fillPaint.color = 0x2410B981
                canvas.drawCircle(cx, cy, s * 0.48f, fillPaint)
                strokePaint.color = AthleticColors.EMERALD_BRIGHT
                strokePaint.strokeWidth = dp(1.8f)
                rect.set(cx - s * 0.36f, cy - s * 0.22f, cx + s * 0.36f, cy + s * 0.22f)
                canvas.drawRoundRect(rect, s * 0.22f, s * 0.22f, strokePaint)

                path.reset()
                path.moveTo(cx + s * 0.04f, cy - s * 0.16f)
                path.lineTo(cx - s * 0.09f, cy + s * 0.02f)
                path.lineTo(cx + s * 0.03f, cy + s * 0.02f)
                path.lineTo(cx - s * 0.04f, cy + s * 0.16f)
                strokePaint.color = AthleticColors.CYAN_BRIGHT
                canvas.drawPath(path, strokePaint)
            }

            IconKind.NAV_TRACKER -> {
                // Concentric athletic ring + pulse dot
                canvas.drawCircle(cx, cy, s * 0.38f, strokePaint)
                rect.set(cx - s * 0.21f, cy - s * 0.21f, cx + s * 0.21f, cy + s * 0.21f)
                canvas.drawArc(rect, -90f, 250f, false, strokePaint)
                canvas.drawCircle(cx, cy, s * 0.07f, fillPaint)
            }

            IconKind.NAV_PROGRESS -> {
                // Rising performance trend curve + arrow peak
                path.reset()
                path.moveTo(w * 0.16f, h * 0.76f)
                path.lineTo(w * 0.40f, h * 0.52f)
                path.lineTo(w * 0.58f, h * 0.62f)
                path.lineTo(w * 0.84f, h * 0.26f)
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(w * 0.66f, h * 0.26f)
                path.lineTo(w * 0.84f, h * 0.26f)
                path.lineTo(w * 0.84f, h * 0.44f)
                canvas.drawPath(path, strokePaint)
            }

            IconKind.NAV_SETTINGS -> {
                // Clean 2-slider tuning icon
                canvas.drawLine(w * 0.18f, h * 0.34f, w * 0.82f, h * 0.34f, strokePaint)
                canvas.drawLine(w * 0.18f, h * 0.66f, w * 0.82f, h * 0.66f, strokePaint)
                fillPaint.color = AthleticColors.BG_NAV
                canvas.drawCircle(w * 0.62f, h * 0.34f, s * 0.12f, fillPaint)
                canvas.drawCircle(w * 0.62f, h * 0.34f, s * 0.12f, strokePaint)
                canvas.drawCircle(w * 0.38f, h * 0.66f, s * 0.12f, fillPaint)
                canvas.drawCircle(w * 0.38f, h * 0.66f, s * 0.12f, strokePaint)
            }

            IconKind.AUDIO_SMART, IconKind.AUDIO_ALL, IconKind.AUDIO_MUTED -> {
                // Speaker cone
                path.reset()
                path.moveTo(w * 0.18f, h * 0.40f)
                path.lineTo(w * 0.34f, h * 0.40f)
                path.lineTo(w * 0.54f, h * 0.22f)
                path.lineTo(w * 0.54f, h * 0.78f)
                path.lineTo(w * 0.34f, h * 0.60f)
                path.lineTo(w * 0.18f, h * 0.60f)
                path.close()
                canvas.drawPath(path, strokePaint)

                if (kind == IconKind.AUDIO_MUTED) {
                    canvas.drawLine(w * 0.66f, h * 0.36f, w * 0.86f, h * 0.64f, strokePaint)
                    canvas.drawLine(w * 0.86f, h * 0.36f, w * 0.66f, h * 0.64f, strokePaint)
                } else {
                    rect.set(w * 0.48f, h * 0.32f, w * 0.74f, h * 0.68f)
                    canvas.drawArc(rect, -45f, 90f, false, strokePaint)
                    if (kind == IconKind.AUDIO_ALL) {
                        rect.set(w * 0.54f, h * 0.20f, w * 0.90f, h * 0.80f)
                        canvas.drawArc(rect, -48f, 96f, false, strokePaint)
                    }
                }
            }

            IconKind.TRASH -> {
                canvas.drawLine(w * 0.22f, h * 0.28f, w * 0.78f, h * 0.28f, strokePaint)
                rect.set(w * 0.30f, h * 0.28f, w * 0.70f, h * 0.82f)
                canvas.drawRoundRect(rect, dp(2f), dp(2f), strokePaint)
                canvas.drawLine(w * 0.40f, h * 0.18f, w * 0.60f, h * 0.18f, strokePaint)
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

        trackPaint.color = if (isChecked) AthleticColors.EMERALD else 0xFF182235.toInt()
        canvas.drawRoundRect(rect, r, r, trackPaint)

        if (!isChecked) {
            borderPaint.color = AthleticColors.BORDER_STRONG
            canvas.drawRoundRect(rect, r, r, borderPaint)
        }

        val thumbRadius = (h - dp(6f)) / 2f
        val cx = if (isChecked) w - dp(3f) - thumbRadius else dp(3f) + thumbRadius
        val cy = h / 2f
        thumbPaint.color = if (isChecked) 0xFF03140E.toInt() else 0xFFCBD5E1.toInt()
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
 * Sleek bottom navigation bar item with glowing active top indicator pill + custom vector icon + label.
 */
class BottomNavTabView(
    context: Context,
    iconKind: IconKind,
    label: String
) : LinearLayout(context) {
    private val indicator = View(context)
    private val iconView = VectorIconView(context, iconKind, AthleticColors.TEXT_MUTED, 21f)
    private val labelView = TextView(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isClickable = true
        isFocusable = true
        setPadding(
            AthleticTheme.dp(context, 6f),
            AthleticTheme.dp(context, 6f),
            AthleticTheme.dp(context, 6f),
            AthleticTheme.dp(context, 8f)
        )

        indicator.layoutParams = LayoutParams(
            AthleticTheme.dp(context, 28f),
            AthleticTheme.dp(context, 3f)
        ).apply {
            bottomMargin = AthleticTheme.dp(context, 5f)
        }
        indicator.background = AthleticTheme.solidPill(context, AthleticColors.EMERALD, AthleticColors.EMERALD, 3f, 0f)

        labelView.apply {
            text = label
            textSize = 11f
            typeface = AthleticTheme.FONT_SANS_BOLD
            setPadding(0, AthleticTheme.dp(context, 3f), 0, 0)
            setTextColor(AthleticColors.TEXT_MUTED)
        }

        addView(indicator)
        addView(iconView)
        addView(labelView)
        setActive(false)
    }

    fun setActive(active: Boolean) {
        indicator.visibility = if (active) View.VISIBLE else View.INVISIBLE
        val color = if (active) AthleticColors.EMERALD_BRIGHT else AthleticColors.TEXT_MUTED
        iconView.update(newTint = color)
        labelView.setTextColor(if (active) AthleticColors.TEXT_PRIMARY else AthleticColors.TEXT_MUTED)
    }

    fun setBadgeLabel(newLabel: String) {
        labelView.text = newLabel
    }
}
