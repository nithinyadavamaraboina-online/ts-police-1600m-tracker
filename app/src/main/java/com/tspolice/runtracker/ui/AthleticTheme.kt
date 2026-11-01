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
    TRASH
}

object AthleticTheme {
    val FONT_SANS_MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val FONT_SANS_BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val FONT_CONDENSED_BOLD: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    // Roboto Black: heavy athletic numerals (Roboto digits are tabular by default, so live stats never jitter)
    val FONT_TABULAR_BOLD: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

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
                // Glowing neon-lime stadium track emblem with electric-purple speed bolt
                fillPaint.color = 0x24C6F432
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
 * Sleek stadium-pill bottom navigation tab item with strictly centered square icon + centered label.
 */
class BottomNavTabView(
    context: Context,
    iconKind: IconKind,
    label: String
) : LinearLayout(context) {
    private val iconView = VectorIconView(context, iconKind, AthleticColors.TEXT_SECONDARY, 20f)
    private val labelView = TextView(context)

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
            setTextColor(AthleticColors.TEXT_SECONDARY)
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
        setActive(false)
    }

    fun setActive(active: Boolean) {
        if (active) {
            background = AthleticTheme.rippleSurface(
                context,
                startColor = 0xFF233008.toInt(),
                endColor = 0xFF131A05.toInt(),
                borderColor = 0xFF7CA81B.toInt(),
                radiusDp = 26f,
                rippleColor = 0x33DAFF5E
            )
            iconView.update(newTint = AthleticColors.EMERALD_BRIGHT)
            labelView.typeface = AthleticTheme.FONT_SANS_BOLD
            labelView.setTextColor(AthleticColors.EMERALD_BRIGHT)
        } else {
            val mask = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = AthleticTheme.dp(context, 26f).toFloat()
                setColor(0xFFFFFFFF.toInt())
            }
            background = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, mask)
            iconView.update(newTint = AthleticColors.TEXT_SECONDARY)
            labelView.typeface = AthleticTheme.FONT_SANS_MEDIUM
            labelView.setTextColor(0xFF94949E.toInt())
        }
    }

    fun setBadgeLabel(newLabel: String) {
        labelView.text = newLabel
    }
}
