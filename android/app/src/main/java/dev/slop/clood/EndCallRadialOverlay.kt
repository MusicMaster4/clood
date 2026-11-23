package dev.slop.clood

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class EndCallOption {
    NONE,
    REDIAL_10M,
    CANCEL,
}

/**
 * Full-screen floating radial bubble overlay triggered by pressing and holding the End Call pill.
 * Dragging onto either floating glass bubble selects an invalidation action:
 *  - Left (amber): discard transcript and ask the agent to call back in 10 minutes
 *  - Right (crimson): discard transcript and cancel/pause the agent's work
 * Releasing anywhere else dismisses cleanly without interrupting the active call.
 */
class EndCallRadialOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tetherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tetherPath = Path()

    private val anchorFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val anchorStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.25f)
    }

    private val bubbleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
    }
    private val bubbleHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val shortLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(12.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.02f
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(19f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = -0.01f
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(12.5f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        letterSpacing = 0.01f
    }

    private val redialIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_call_redial)?.mutate()
    private val cancelIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_call_cancel)?.mutate()
    private val closeIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_call_cancel)?.mutate()

    private val promptDefault = context.getString(R.string.call_hold_prompt)
    private val promptRelease = context.getString(R.string.call_hold_release_cancel)
    private val redialShort = context.getString(R.string.call_opt_redial_short)
    private val redialTitle = context.getString(R.string.call_opt_redial_title)
    private val redialDesc = context.getString(R.string.call_opt_redial_desc)
    private val cancelShort = context.getString(R.string.call_opt_cancel_short)
    private val cancelTitle = context.getString(R.string.call_opt_cancel_title)
    private val cancelDesc = context.getString(R.string.call_opt_cancel_desc)

    private var anchorX = 0f
    private var anchorY = 0f
    private var fingerX = 0f
    private var fingerY = 0f

    private var redialX = 0f
    private var redialY = 0f
    private var cancelX = 0f
    private var cancelY = 0f

    private var openProgress = 0f
    private var redialFocus = 0f
    private var cancelFocus = 0f
    private var isOpen = false
    private var selected: EndCallOption = EndCallOption.NONE

    private var openAnimator: ValueAnimator? = null
    private var focusAnimator: ValueAnimator? = null

    init {
        visibility = GONE
        isClickable = false
        isFocusable = false
    }

    fun open(anchorX: Float, anchorY: Float, fingerX: Float, fingerY: Float) {
        openAnimator?.cancel()
        focusAnimator?.cancel()

        this.anchorX = anchorX
        this.anchorY = anchorY
        this.fingerX = fingerX
        this.fingerY = fingerY
        this.selected = EndCallOption.NONE
        this.redialFocus = 0f
        this.cancelFocus = 0f
        this.isOpen = true

        recomputeBubblePositions()
        visibility = VISIBLE

        openAnimator = ValueAnimator.ofFloat(openProgress, 1f).apply {
            duration = 240L
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener {
                openProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        invalidate()
    }

    fun updateFinger(x: Float, y: Float) {
        if (!isOpen) return
        fingerX = x
        fingerY = y

        val next = resolveTarget(
            fingerX = x,
            fingerY = y,
            redialX = redialX,
            redialY = redialY,
            cancelX = cancelX,
            cancelY = cancelY,
            hitRadius = dp(BUBBLE_RADIUS_DP + HIT_SLOP_DP),
        )
        if (next != selected) {
            selected = next
            val haptic = if (next == EndCallOption.NONE) {
                HapticFeedbackConstants.CLOCK_TICK
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
            performHapticFeedback(haptic)
            animateFocus(next)
        }
        invalidate()
    }

    fun finishAndGetSelection(): EndCallOption {
        val result = if (isOpen) selected else EndCallOption.NONE
        dismiss()
        return result
    }

    fun dismiss() {
        if (!isOpen && visibility == GONE) return
        isOpen = false
        selected = EndCallOption.NONE
        openAnimator?.cancel()
        focusAnimator?.cancel()

        val start = openProgress
        openAnimator = ValueAnimator.ofFloat(start, 0f).apply {
            duration = 160L
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                openProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isOpen) {
                        visibility = GONE
                        redialFocus = 0f
                        cancelFocus = 0f
                    }
                }
            })
            start()
        }
    }

    fun dismissImmediate() {
        openAnimator?.cancel()
        focusAnimator?.cancel()
        isOpen = false
        selected = EndCallOption.NONE
        openProgress = 0f
        redialFocus = 0f
        cancelFocus = 0f
        visibility = GONE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isOpen) recomputeBubblePositions()
    }

    override fun onDetachedFromWindow() {
        openAnimator?.cancel()
        focusAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun recomputeBubblePositions() {
        val w = if (width > 0) width.toFloat() else resources.displayMetrics.widthPixels.toFloat()
        val (redial, cancel) = computeBubbleCenters(anchorX, anchorY, w, density)
        redialX = redial.first
        redialY = redial.second
        cancelX = cancel.first
        cancelY = cancel.second
    }

    private fun animateFocus(target: EndCallOption) {
        focusAnimator?.cancel()
        val startRedial = redialFocus
        val startCancel = cancelFocus
        val endRedial = if (target == EndCallOption.REDIAL_10M) 1f else 0f
        val endCancel = if (target == EndCallOption.CANCEL) 1f else 0f

        focusAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 155L
            interpolator = DecelerateInterpolator(2.1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                redialFocus = startRedial + (endRedial - startRedial) * t
                cancelFocus = startCancel + (endCancel - startCancel) * t
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = openProgress
        val clampedP = p.coerceIn(0f, 1f)
        if (clampedP <= 0.005f) return

        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Atmospheric obsidian scrim
        scrimPaint.color = ColorUtils.setAlphaComponent(COLOR_SCRIM, (220 * clampedP).toInt())
        canvas.drawRect(0f, 0f, w, h, scrimPaint)

        // 2. Ambient radial glow tinted by active bubble focus
        if (redialFocus > 0.01f) {
            drawFocusGlow(canvas, redialX, redialY, COLOR_REDIAL, redialFocus * clampedP)
        }
        if (cancelFocus > 0.01f) {
            drawFocusGlow(canvas, cancelX, cancelY, COLOR_CANCEL, cancelFocus * clampedP)
        }

        val curRedialX = anchorX + (redialX - anchorX) * p
        val curRedialY = anchorY + (redialY - anchorY) * p
        val curCancelX = anchorX + (cancelX - anchorX) * p
        val curCancelY = anchorY + (cancelY - anchorY) * p

        // 3. Subtle luminous tether from anchor toward active target / finger
        drawTether(canvas, curRedialX, curRedialY, curCancelX, curCancelY, clampedP)

        // 4. Neutral cancel-gesture anchor ring over End pill
        drawAnchorRing(canvas, clampedP)

        // 5. Left bubble: Redial in 10 min
        drawBubble(
            canvas = canvas,
            cx = curRedialX,
            cy = curRedialY,
            accentColor = COLOR_REDIAL,
            focusedIconColor = COLOR_DARK_ICON,
            icon = redialIcon,
            shortLabel = redialShort,
            focus = redialFocus,
            otherFocus = cancelFocus,
            openP = clampedP,
        )

        // 6. Right bubble: Cancel call & pause work
        drawBubble(
            canvas = canvas,
            cx = curCancelX,
            cy = curCancelY,
            accentColor = COLOR_CANCEL,
            focusedIconColor = Color.WHITE,
            icon = cancelIcon,
            shortLabel = cancelShort,
            focus = cancelFocus,
            otherFocus = redialFocus,
            openP = clampedP,
        )

        // 7. Dynamic centered title & subtitle above the bubbles
        drawHeaderCallout(canvas, clampedP)
    }

    private fun drawFocusGlow(canvas: Canvas, cx: Float, cy: Float, color: Int, strength: Float) {
        val radius = dp(170f)
        auraPaint.shader = RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(
                ColorUtils.setAlphaComponent(color, (66 * strength).toInt()),
                ColorUtils.setAlphaComponent(color, (18 * strength).toInt()),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, auraPaint)
        auraPaint.shader = null
    }

    private fun drawTether(
        canvas: Canvas,
        curRedialX: Float,
        curRedialY: Float,
        curCancelX: Float,
        curCancelY: Float,
        openP: Float,
    ) {
        val anyFocus = max(redialFocus, cancelFocus)
        val targetX = when {
            redialFocus >= cancelFocus && redialFocus > 0.05f -> lerp(fingerX, curRedialX, redialFocus)
            cancelFocus > 0.05f -> lerp(fingerX, curCancelX, cancelFocus)
            else -> fingerX
        }
        val targetY = when {
            redialFocus >= cancelFocus && redialFocus > 0.05f -> lerp(fingerY, curRedialY, redialFocus)
            cancelFocus > 0.05f -> lerp(fingerY, curCancelY, cancelFocus)
            else -> fingerY
        }

        val dist = hypot(targetX - anchorX, targetY - anchorY)
        if (dist < dp(18f)) return

        val accent = when {
            redialFocus > cancelFocus && redialFocus > 0.05f ->
                ColorUtils.blendARGB(Color.WHITE, COLOR_REDIAL, redialFocus)
            cancelFocus > 0.05f ->
                ColorUtils.blendARGB(Color.WHITE, COLOR_CANCEL, cancelFocus)
            else -> Color.WHITE
        }

        val startAlpha = (24 * openP).toInt()
        val endAlpha = ((72 + 125 * anyFocus) * openP).toInt().coerceIn(0, 220)
        tetherPaint.strokeWidth = dp(2f + anyFocus * 1.4f)
        tetherPaint.shader = LinearGradient(
            anchorX,
            anchorY,
            targetX,
            targetY,
            ColorUtils.setAlphaComponent(accent, startAlpha),
            ColorUtils.setAlphaComponent(accent, endAlpha),
            Shader.TileMode.CLAMP,
        )

        val ctrlX = (anchorX + targetX) * 0.5f
        val ctrlY = min(anchorY, targetY) + dp(18f)
        tetherPath.reset()
        tetherPath.moveTo(anchorX, anchorY)
        tetherPath.quadTo(ctrlX, ctrlY, targetX, targetY)
        canvas.drawPath(tetherPath, tetherPaint)
        tetherPaint.shader = null
    }

    private fun drawAnchorRing(canvas: Canvas, openP: Float) {
        val anyFocus = max(redialFocus, cancelFocus)
        val r = dp(25f) * (0.85f + 0.15f * openP) * (1f - anyFocus * 0.14f)
        val alphaFactor = openP * (1f - anyFocus * 0.52f)

        anchorFillPaint.color = ColorUtils.setAlphaComponent(COLOR_SURFACE, (235 * alphaFactor).toInt())
        canvas.drawCircle(anchorX, anchorY, r, anchorFillPaint)

        anchorStrokePaint.color = ColorUtils.setAlphaComponent(Color.WHITE, (54 * alphaFactor).toInt())
        canvas.drawCircle(anchorX, anchorY, r, anchorStrokePaint)

        closeIcon?.let { icon ->
            icon.setTint(ColorUtils.setAlphaComponent(Color.WHITE, (170 * alphaFactor).toInt()))
            val half = (dp(16f) * 0.5f).toInt()
            icon.setBounds(
                (anchorX - half).toInt(),
                (anchorY - half).toInt(),
                (anchorX + half).toInt(),
                (anchorY + half).toInt(),
            )
            icon.draw(canvas)
        }
    }

    private fun drawBubble(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        accentColor: Int,
        focusedIconColor: Int,
        icon: Drawable?,
        shortLabel: String,
        focus: Float,
        otherFocus: Float,
        openP: Float,
    ) {
        val scale = (0.72f + 0.28f * openP) * (1f + focus * 0.18f - otherFocus * 0.10f)
        val radius = dp(BUBBLE_RADIUS_DP) * scale
        val alphaScale = openP * (1f - otherFocus * 0.45f)

        // Outer luminous halo when hovered
        if (focus > 0.02f) {
            bubbleHaloPaint.color = ColorUtils.setAlphaComponent(accentColor, (72 * focus * openP).toInt())
            canvas.drawCircle(cx, cy, radius + dp(11f * focus), bubbleHaloPaint)
        }

        // Glass body morphing into vivid accent fill on focus
        val baseFill = ColorUtils.blendARGB(COLOR_SURFACE, accentColor, focus)
        bubbleFillPaint.color = ColorUtils.setAlphaComponent(baseFill, (246 * alphaScale).toInt().coerceIn(0, 255))
        canvas.drawCircle(cx, cy, radius, bubbleFillPaint)

        // Crisp hairline rim
        val rimColor = ColorUtils.blendARGB(
            ColorUtils.setAlphaComponent(accentColor, 145),
            ColorUtils.setAlphaComponent(Color.WHITE, 185),
            focus,
        )
        bubbleStrokePaint.color = ColorUtils.setAlphaComponent(
            rimColor,
            (Color.alpha(rimColor) * alphaScale).toInt().coerceIn(0, 255),
        )
        canvas.drawCircle(cx, cy, radius - dp(0.7f), bubbleStrokePaint)

        // Centered vector glyph
        icon?.let { drawable ->
            val iconColor = ColorUtils.blendARGB(accentColor, focusedIconColor, focus)
            drawable.setTint(
                ColorUtils.setAlphaComponent(iconColor, (255 * alphaScale).toInt().coerceIn(0, 255)),
            )
            val iconHalf = (dp(26f) * (1f + focus * 0.12f) * 0.5f).toInt()
            drawable.setBounds(
                (cx - iconHalf).toInt(),
                (cy - iconHalf).toInt(),
                (cx + iconHalf).toInt(),
                (cy + iconHalf).toInt(),
            )
            drawable.draw(canvas)
        }

        // Short caption tucked neatly beneath bubble
        val labelColor = ColorUtils.blendARGB(COLOR_LABEL_IDLE, Color.WHITE, focus)
        shortLabelPaint.color = ColorUtils.setAlphaComponent(
            labelColor,
            (255 * alphaScale).toInt().coerceIn(0, 255),
        )
        canvas.drawText(shortLabel, cx, cy + radius + dp(20f), shortLabelPaint)
    }

    private fun drawHeaderCallout(canvas: Canvas, openP: Float) {
        val centerX = width * 0.5f
        val baseY = min(redialY, cancelY) - dp(92f) + dp(10f) * (1f - openP)

        val (title, subtitle, subtitleColor) = when (selected) {
            EndCallOption.REDIAL_10M -> Triple(redialTitle, redialDesc, COLOR_REDIAL_SOFT)
            EndCallOption.CANCEL -> Triple(cancelTitle, cancelDesc, COLOR_CANCEL_SOFT)
            EndCallOption.NONE -> Triple(promptDefault, promptRelease, COLOR_MUTED)
        }

        val titleColor = if (selected == EndCallOption.NONE) COLOR_TITLE_IDLE else Color.WHITE
        titlePaint.color = ColorUtils.setAlphaComponent(titleColor, (255 * openP).toInt())
        canvas.drawText(title, centerX, baseY, titlePaint)

        subtitlePaint.color = ColorUtils.setAlphaComponent(subtitleColor, (230 * openP).toInt())
        canvas.drawText(subtitle, centerX, baseY + dp(22f), subtitlePaint)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    companion object {
        private const val BUBBLE_RADIUS_DP = 35f
        private const val HIT_SLOP_DP = 32f

        private val COLOR_SCRIM = Color.parseColor("#09090B")
        private val COLOR_SURFACE = Color.parseColor("#181822")
        private val COLOR_REDIAL = Color.parseColor("#F59E0B")
        private val COLOR_REDIAL_SOFT = Color.parseColor("#FCD34D")
        private val COLOR_CANCEL = Color.parseColor("#EF4444")
        private val COLOR_CANCEL_SOFT = Color.parseColor("#FCA5A5")
        private val COLOR_DARK_ICON = Color.parseColor("#09090B")
        private val COLOR_LABEL_IDLE = Color.parseColor("#D4D4D8")
        private val COLOR_TITLE_IDLE = Color.parseColor("#E4E4E7")
        private val COLOR_MUTED = Color.parseColor("#8A8A96")

        internal fun computeBubbleCenters(
            anchorX: Float,
            anchorY: Float,
            viewWidth: Float,
            density: Float,
        ): Pair<Pair<Float, Float>, Pair<Float, Float>> {
            val spreadX = min(viewWidth * 0.28f, 108f * density)
            val liftY = 128f * density
            val margin = 56f * density
            val maxEdge = max(margin, viewWidth - margin)
            val leftX = (anchorX - spreadX).coerceIn(margin, maxEdge)
            val rightX = (anchorX + spreadX).coerceIn(margin, maxEdge)
            val bubbleY = max(144f * density, anchorY - liftY)
            return Pair(leftX to bubbleY, rightX to bubbleY)
        }

        internal fun resolveTarget(
            fingerX: Float,
            fingerY: Float,
            redialX: Float,
            redialY: Float,
            cancelX: Float,
            cancelY: Float,
            hitRadius: Float,
        ): EndCallOption {
            val dRedial = hypot(fingerX - redialX, fingerY - redialY)
            val dCancel = hypot(fingerX - cancelX, fingerY - cancelY)
            val inRedial = dRedial <= hitRadius
            val inCancel = dCancel <= hitRadius
            return when {
                inRedial && inCancel -> if (dRedial <= dCancel) EndCallOption.REDIAL_10M else EndCallOption.CANCEL
                inRedial -> EndCallOption.REDIAL_10M
                inCancel -> EndCallOption.CANCEL
                else -> EndCallOption.NONE
            }
        }
    }
}
