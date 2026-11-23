package dev.slop.clood

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Avant-garde bi-directional glass pill slider for incoming calls:
 * drag right to answer, drag left to decline, with breathing halo,
 * directional chevron shimmer, smooth color morphing, and haptic threshold lock.
 */
class CallSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onAnswer: (() -> Unit)? = null
    var onDecline: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val trackRect = RectF()
    private val fillRect = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.25f)
    }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val chevronPath = Path()

    private val acceptIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_call_accept)?.mutate()
    private val declineIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_call_end)?.mutate()
    private val handleIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_call_accept)?.mutate()

    // Normalized offset in [-1f, 1f]: -1 = full left (Decline), 0 = center, +1 = full right (Answer)
    private var progress = 0f
    private var dragging = false
    private var committed = false
    private var crossedThreshold = false
    private var downX = 0f
    private var downY = 0f
    private var movedBeyondSlop = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    private var ambientPhase = 0f
    private var springAnimator: ValueAnimator? = null
    private val ambientAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            ambientPhase = it.animatedValue as Float
            if (!committed) invalidate()
        }
    }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.call_slide_hint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!ambientAnimator.isStarted) ambientAnimator.start()
    }

    override fun onDetachedFromWindow() {
        ambientAnimator.cancel()
        springAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    fun reset() {
        springAnimator?.cancel()
        committed = false
        dragging = false
        crossedThreshold = false
        progress = 0f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = dp(80f).toInt()
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = resolveSize(desiredH, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    private fun trackHeight(): Float = min(height.toFloat() - dp(4f), dp(74f))
    private fun handleRadius(): Float = (trackHeight() - dp(12f)) * 0.5f
    private fun maxTravel(): Float {
        val halfTrack = (width.toFloat() - dp(12f)) * 0.5f
        return max(1f, halfTrack - handleRadius() - dp(6f))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val th = trackHeight()
        val top = (h - th) * 0.5f
        val radius = th * 0.5f
        trackRect.set(dp(4f), top, w - dp(4f), top + th)

        val absP = abs(progress)
        val isRight = progress >= 0f
        val activeColor = if (isRight) COLOR_ANSWER else COLOR_DECLINE

        // Obsidian translucent track background
        trackPaint.color = COLOR_TRACK_BG
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

        // Directional glow fill that grows with drag progress
        if (absP > 0.02f) {
            val fillAlpha = (absP * 92f).toInt().coerceIn(0, 110)
            val edgeAlpha = (absP * 30f).toInt().coerceIn(0, 45)
            val cx = w * 0.5f
            if (isRight) {
                fillRect.set(cx - radius, top, trackRect.right, top + th)
                fillPaint.shader = LinearGradient(
                    cx - radius, 0f, trackRect.right, 0f,
                    ColorUtils.setAlphaComponent(COLOR_ANSWER, edgeAlpha),
                    ColorUtils.setAlphaComponent(COLOR_ANSWER, fillAlpha),
                    Shader.TileMode.CLAMP,
                )
            } else {
                fillRect.set(trackRect.left, top, cx + radius, top + th)
                fillPaint.shader = LinearGradient(
                    trackRect.left, 0f, cx + radius, 0f,
                    ColorUtils.setAlphaComponent(COLOR_DECLINE, fillAlpha),
                    ColorUtils.setAlphaComponent(COLOR_DECLINE, edgeAlpha),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRoundRect(trackRect, radius, radius, fillPaint)
            fillPaint.shader = null
        }

        // Hairline rim border morphing subtly toward active action color
        borderPaint.color = if (absP > 0.05f) {
            ColorUtils.blendARGB(COLOR_TRACK_BORDER, ColorUtils.setAlphaComponent(activeColor, 165), absP)
        } else {
            COLOR_TRACK_BORDER
        }
        canvas.drawRoundRect(trackRect, radius, radius, borderPaint)

        val cy = h * 0.5f
        val endInset = trackRect.left + radius

        // Left endpoint: Decline icon
        val leftFade = if (progress > 0f) (1f - progress * 1.4f).coerceIn(0.15f, 1f) else 1f
        val leftBoost = if (progress < 0f) absP else 0f
        drawEndGlyph(
            canvas = canvas,
            drawable = declineIcon,
            cx = endInset,
            cy = cy,
            size = dp(22f),
            tint = ColorUtils.blendARGB(
                ColorUtils.setAlphaComponent(COLOR_DECLINE, (190 * leftFade).toInt()),
                Color.WHITE,
                (leftBoost * 0.65f).coerceIn(0f, 1f),
            ),
            scale = 1f + leftBoost * 0.14f,
        )

        // Right endpoint: Answer icon
        val rightFade = if (progress < 0f) (1f - absP * 1.4f).coerceIn(0.15f, 1f) else 1f
        val rightBoost = if (progress > 0f) absP else 0f
        drawEndGlyph(
            canvas = canvas,
            drawable = acceptIcon,
            cx = trackRect.right - radius,
            cy = cy,
            size = dp(22f),
            tint = ColorUtils.blendARGB(
                ColorUtils.setAlphaComponent(COLOR_ANSWER, (195 * rightFade).toInt()),
                Color.WHITE,
                (rightBoost * 0.65f).coerceIn(0f, 1f),
            ),
            scale = 1f + rightBoost * 0.14f,
        )

        // Subtle animated chevrons on both sides when idle / lightly dragged
        val chevronFade = (1f - absP * 2.4f).coerceIn(0f, 1f)
        if (chevronFade > 0.02f) {
            val midLeft = (endInset + w * 0.5f) * 0.5f
            val midRight = ((trackRect.right - radius) + w * 0.5f) * 0.5f
            drawChevrons(canvas, midLeft, cy, dir = -1f, baseColor = COLOR_DECLINE, fade = chevronFade)
            drawChevrons(canvas, midRight, cy, dir = 1f, baseColor = COLOR_ANSWER, fade = chevronFade)
        }

        // Draggable center handle
        val handleX = w * 0.5f + progress * maxTravel()
        val hr = handleRadius()

        // Breathing soft outer aura around handle
        val pulse = 0.5f + 0.5f * sin(ambientPhase * 2.0 * PI).toFloat()
        val haloColor = if (absP > 0.06f) activeColor else Color.WHITE
        val haloAlpha = if (dragging) {
            (48 + absP * 48).toInt()
        } else {
            (18 + pulse * 26).toInt()
        }
        haloPaint.color = ColorUtils.setAlphaComponent(haloColor, haloAlpha)
        canvas.drawCircle(handleX, cy, hr + dp(4f + pulse * 3f), haloPaint)

        // Handle body morphing smoothly from pearl alabaster -> emerald / crimson
        val handleFill = if (absP > 0.04f) {
            ColorUtils.blendARGB(COLOR_HANDLE_IDLE, activeColor, min(1f, absP * 1.15f))
        } else {
            COLOR_HANDLE_IDLE
        }
        handlePaint.color = handleFill
        canvas.drawCircle(handleX, cy, hr, handlePaint)

        handleRimPaint.color = ColorUtils.setAlphaComponent(Color.WHITE, if (dragging) 95 else 45)
        canvas.drawCircle(handleX, cy, hr - dp(0.6f), handleRimPaint)

        // Handle icon rotates subtly toward hang-up orientation when dragged left
        handleIcon?.let { icon ->
            val iconColor = if (absP > 0.4f) {
                Color.WHITE
            } else {
                ColorUtils.blendARGB(COLOR_HANDLE_ICON_IDLE, Color.WHITE, absP / 0.4f)
            }
            icon.setTint(iconColor)
            val iconHalf = (dp(24f) * 0.5f).toInt()
            canvas.save()
            canvas.translate(handleX, cy)
            val rotDeg = if (progress < 0f) {
                absP * 135f
            } else {
                -progress * 10f
            }
            canvas.rotate(rotDeg)
            icon.setBounds(-iconHalf, -iconHalf, iconHalf, iconHalf)
            icon.draw(canvas)
            canvas.restore()
        }
    }

    private fun drawEndGlyph(
        canvas: Canvas,
        drawable: Drawable?,
        cx: Float,
        cy: Float,
        size: Float,
        tint: Int,
        scale: Float,
    ) {
        drawable ?: return
        drawable.setTint(tint)
        val half = (size * 0.5f).toInt()
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)
        drawable.setBounds(-half, -half, half, half)
        drawable.draw(canvas)
        canvas.restore()
    }

    private fun drawChevrons(
        canvas: Canvas,
        centerX: Float,
        cy: Float,
        dir: Float,
        baseColor: Int,
        fade: Float,
    ) {
        val spacing = dp(8f)
        val arm = dp(4.5f)
        for (i in 0..1) {
            val offset = (i - 0.5f) * spacing * dir
            val wave = 0.5f + 0.5f * sin((ambientPhase * 2.0 * PI) - i * 1.1).toFloat()
            val alpha = ((42 + wave * 95) * fade).toInt().coerceIn(0, 180)
            chevronPaint.color = ColorUtils.setAlphaComponent(baseColor, alpha)
            val x = centerX + offset
            chevronPath.reset()
            chevronPath.moveTo(x - arm * 0.5f * dir, cy - arm)
            chevronPath.lineTo(x + arm * 0.5f * dir, cy)
            chevronPath.lineTo(x - arm * 0.5f * dir, cy + arm)
            canvas.drawPath(chevronPath, chevronPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (committed) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val handleX = width * 0.5f + progress * maxTravel()
                val touchRadius = handleRadius() + dp(28f)
                if (abs(event.x - handleX) > touchRadius) {
                    return false
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                springAnimator?.cancel()
                activePointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                movedBeyondSlop = false
                dragging = true
                crossedThreshold = false
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(activePointerId)
                if (idx < 0 || !dragging) return false
                val dx = event.getX(idx) - downX
                if (abs(dx) > dp(6f)) movedBeyondSlop = true
                progress = (dx / maxTravel()).coerceIn(-1f, 1f)
                val nowCrossed = abs(progress) >= COMMIT_THRESHOLD
                if (nowCrossed && !crossedThreshold) {
                    crossedThreshold = true
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                } else if (!nowCrossed && crossedThreshold) {
                    crossedThreshold = false
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)

                if (event.actionMasked == MotionEvent.ACTION_UP && abs(progress) >= COMMIT_THRESHOLD) {
                    committed = true
                    val target = if (progress > 0f) 1f else -1f
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    animateTo(target, 140L) {
                        if (target > 0f) onAnswer?.invoke() else onDecline?.invoke()
                    }
                } else if (event.actionMasked == MotionEvent.ACTION_UP && !movedBeyondSlop) {
                    // Subtle horizontal nudge hint if user merely taps the handle
                    performClick()
                    playNudgeHint()
                } else {
                    animateTo(0f, 240L)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun animateTo(target: Float, durationMs: Long, onEnd: (() -> Unit)? = null) {
        springAnimator?.cancel()
        val start = progress
        springAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(2.2f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            if (onEnd != null) {
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        onEnd()
                    }
                })
            }
            start()
        }
    }

    private fun playNudgeHint() {
        springAnimator?.cancel()
        springAnimator = ValueAnimator.ofFloat(0f, 0.22f, -0.16f, 0f).apply {
            duration = 460L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    companion object {
        private const val COMMIT_THRESHOLD = 0.72f
        private val COLOR_TRACK_BG = Color.parseColor("#13131A")
        private val COLOR_TRACK_BORDER = Color.parseColor("#272734")
        private val COLOR_HANDLE_IDLE = Color.parseColor("#F4F4F5")
        private val COLOR_HANDLE_ICON_IDLE = Color.parseColor("#09090B")
        private val COLOR_ANSWER = Color.parseColor("#22C55E")
        private val COLOR_DECLINE = Color.parseColor("#EF4444")
    }
}
