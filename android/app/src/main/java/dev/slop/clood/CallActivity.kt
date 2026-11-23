package dev.slop.clood

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.min

/**
 * Full-screen minimalist dark call UI with seamless lock-screen answering (never forces unlock),
 * bespoke swipe-to-answer/decline slider, ambient orb glow, and floating glass in-call dock.
 */
class CallActivity : AppCompatActivity(), CloodSessionBus.Listener {
    private lateinit var store: ConfigStore
    private lateinit var ambientGlowView: AmbientGlowView
    private lateinit var statusDot: View
    private lateinit var statusBadgeText: TextView
    private lateinit var statusPill: LinearLayout
    private lateinit var callerText: TextView
    private lateinit var timerText: TextView
    private lateinit var routeChip: TextView
    private lateinit var orbView: OrbGLView
    private lateinit var liveCueText: TextView

    private lateinit var ringingContainer: LinearLayout
    private lateinit var callSlider: CallSliderView
    private lateinit var slideHintText: TextView

    private lateinit var activePanel: LinearLayout
    private lateinit var muteBtn: ImageButton
    private lateinit var muteLabel: TextView
    private lateinit var speakerBtn: ImageButton
    private lateinit var speakerLabel: TextView
    private lateinit var bluetoothBtn: ImageButton
    private lateinit var bluetoothLabel: TextView

    private var pulseAnimator: ObjectAnimator? = null
    private var lastPhase: CallPhase? = null

    private val handler = Handler(Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            updateTimer()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ConfigStore(this)
        configureLockScreenWindow()
        configureEdgeToEdge()

        setContentView(buildUi())
        renderCall(CloodSessionBus.activeCall)
    }

    private fun configureLockScreenWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun configureEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderCall(CloodSessionBus.activeCall)
    }

    override fun onStart() {
        super.onStart()
        CloodSessionBus.register(this)
        renderCall(CloodSessionBus.activeCall)
        handler.post(timerTick)
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        // Immediately dismiss any incoming heads-up notification so only this full-screen UI is shown.
        CloodConnectionService.dismissIncomingNotification(this)
        orbView.onResume()
        startDotPulse()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            CloodConnectionService.dismissIncomingNotification(this)
        }
    }

    override fun onPause() {
        isForeground = false
        stopDotPulse()
        orbView.onPause()
        super.onPause()
    }

    override fun onStop() {
        handler.removeCallbacks(timerTick)
        CloodSessionBus.unregister(this)
        val current = CloodSessionBus.activeCall
        val pm = getSystemService(PowerManager::class.java)
        if (!isFinishing && current?.phase == CallPhase.RINGING && pm?.isInteractive == true) {
            CloodConnectionService.start(this, CloodConnectionService.ACTION_REPOST_RINGING_NOTIF, current.callId)
        }
        super.onStop()
    }

    override fun onCallChanged(call: ActiveCallUi?) {
        renderCall(call)
    }

    override fun onAgentAudioLevel(speaking: Boolean, level: Float) {
        val call = CloodSessionBus.activeCall ?: return
        if (call.phase != CallPhase.ACTIVE) return
        orbView.setOrbState(
            mode = if (speaking) OrbGLView.OrbMode.SPEAKING else OrbGLView.OrbMode.IDLE,
            colorHex = call.orbColor,
            audioLevel = level,
            previewTint = false,
        )
        updateLiveCue(call, speaking)
    }

    private fun renderCall(call: ActiveCallUi?) {
        if (call == null) {
            finish()
            return
        }
        callerText.text = call.callerName
        ambientGlowView.setTintHex(call.orbColor)

        when (call.phase) {
            CallPhase.RINGING -> {
                val stream = store.loadRingtoneStream()
                volumeControlStream = if (stream == RingtoneStream.ALARM) {
                    AudioManager.STREAM_ALARM
                } else {
                    AudioManager.STREAM_RING
                }

                statusBadgeText.text = getString(R.string.call_incoming_badge)
                setStatusDotColor(Color.parseColor("#34D399"))
                timerText.visibility = View.GONE
                routeChip.visibility = if (stream == RingtoneStream.ALARM) View.VISIBLE else View.GONE
                if (stream == RingtoneStream.ALARM) {
                    routeChip.text = getString(R.string.call_alarm_badge)
                }
                liveCueText.visibility = View.INVISIBLE

                if (lastPhase != CallPhase.RINGING) {
                    callSlider.reset()
                }
                ringingContainer.visibility = View.VISIBLE
                activePanel.visibility = View.GONE
                orbView.setOrbState(OrbGLView.OrbMode.RINGING, call.orbColor, 0.35f, previewTint = true)
            }
            CallPhase.ACTIVE -> {
                volumeControlStream = AudioManager.STREAM_VOICE_CALL
                CloodConnectionService.dismissIncomingNotification(this)

                if (lastPhase == CallPhase.RINGING) {
                    activePanel.alpha = 0f
                    activePanel.translationY = dp(18).toFloat()
                    activePanel.visibility = View.VISIBLE
                    activePanel.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(260L)
                        .start()
                } else {
                    activePanel.visibility = View.VISIBLE
                }
                ringingContainer.visibility = View.GONE
                timerText.visibility = View.VISIBLE
                routeChip.visibility = View.VISIBLE
                liveCueText.visibility = View.VISIBLE

                updateTimer()
                updateControlStates(call)
                updateLiveCue(call, CloodSessionBus.agentSpeaking)
                orbView.setOrbState(
                    mode = if (CloodSessionBus.agentSpeaking) OrbGLView.OrbMode.SPEAKING else OrbGLView.OrbMode.IDLE,
                    colorHex = call.orbColor,
                    audioLevel = CloodSessionBus.audioLevel,
                    previewTint = false,
                )
            }
            CallPhase.IDLE -> finish()
        }
        lastPhase = call.phase
    }

    private fun updateTimer() {
        val call = CloodSessionBus.activeCall ?: return
        if (call.phase != CallPhase.ACTIVE) return
        val started = call.answeredAtMs ?: call.startedAtMs
        val sec = ((System.currentTimeMillis() - started) / 1000L).coerceAtLeast(0L)
        timerText.text = "%02d:%02d".format(sec / 60, sec % 60)
    }

    private fun updateLiveCue(call: ActiveCallUi, speaking: Boolean) {
        when {
            call.muted -> {
                statusBadgeText.text = getString(R.string.call_muted_badge)
                setStatusDotColor(Color.parseColor("#F59E0B"))
                liveCueText.text = getString(R.string.call_status_muted)
                liveCueText.setTextColor(Color.parseColor("#F59E0B"))
            }
            speaking -> {
                statusBadgeText.text = getString(R.string.call_live_badge)
                setStatusDotColor(Color.parseColor("#34D399"))
                liveCueText.text = getString(R.string.call_status_speaking)
                liveCueText.setTextColor(Color.parseColor("#E4E4E7"))
            }
            else -> {
                statusBadgeText.text = getString(R.string.call_live_badge)
                setStatusDotColor(Color.parseColor("#34D399"))
                liveCueText.text = getString(R.string.call_status_listening)
                liveCueText.setTextColor(Color.parseColor("#71717A"))
            }
        }
    }

    private fun setStatusDotColor(color: Int) {
        (statusDot.background as? GradientDrawable)?.setColor(color)
    }

    private fun startDotPulse() {
        stopDotPulse()
        pulseAnimator = ObjectAnimator.ofFloat(statusDot, View.ALPHA, 0.35f, 1f).apply {
            duration = 950L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopDotPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    private fun updateControlStates(call: ActiveCallUi) {
        val activeBg = Color.parseColor("#F4F4F5")
        val idleBg = Color.parseColor("#1A1A22")
        val idleStroke = Color.parseColor("#2A2A36")
        val btActiveBg = Color.parseColor("#7C8CFF")
        val darkIcon = Color.parseColor("#09090B")
        val lightIcon = Color.parseColor("#F4F4F5")

        muteBtn.setImageResource(if (call.muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        muteBtn.background = circleControlBg(if (call.muted) activeBg else idleBg, if (call.muted) null else idleStroke)
        muteBtn.setColorFilter(if (call.muted) darkIcon else lightIcon)
        muteLabel.text = getString(if (call.muted) R.string.call_muted else R.string.call_mute)
        muteLabel.setTextColor(if (call.muted) Color.parseColor("#F4F4F5") else Color.parseColor("#8A8A96"))

        val speakerActive = call.audioRoute == AudioRoute.SPEAKER
        speakerBtn.setImageResource(if (speakerActive) R.drawable.ic_speaker else R.drawable.ic_earpiece)
        speakerBtn.background = circleControlBg(if (speakerActive) activeBg else idleBg, if (speakerActive) null else idleStroke)
        speakerBtn.setColorFilter(if (speakerActive) darkIcon else lightIcon)
        speakerLabel.text = getString(if (speakerActive) R.string.call_speaker else R.string.call_earpiece)
        speakerLabel.setTextColor(if (speakerActive) Color.parseColor("#F4F4F5") else Color.parseColor("#8A8A96"))

        val btActive = call.audioRoute == AudioRoute.BLUETOOTH
        val btEnabled = call.bluetoothAvailable || btActive
        bluetoothBtn.isEnabled = btEnabled
        bluetoothBtn.alpha = if (btEnabled) 1f else 0.32f
        bluetoothBtn.background = circleControlBg(if (btActive) btActiveBg else idleBg, if (btActive) null else idleStroke)
        bluetoothBtn.setColorFilter(lightIcon)
        bluetoothLabel.alpha = if (btEnabled) 1f else 0.32f
        bluetoothLabel.text = getString(if (btActive) R.string.call_bluetooth_on else R.string.call_bluetooth)
        bluetoothLabel.setTextColor(if (btActive) Color.parseColor("#C7D2FE") else Color.parseColor("#8A8A96"))

        routeChip.text = when (call.audioRoute) {
            AudioRoute.BLUETOOTH -> getString(R.string.call_route_bluetooth)
            AudioRoute.SPEAKER -> getString(R.string.call_route_speaker)
            AudioRoute.EARPIECE -> getString(R.string.call_route_earpiece)
        }
    }

    private fun buildUi(): View {
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#09090B"))
        }

        ambientGlowView = AmbientGlowView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        frame.addView(ambientGlowView)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        // Respect system status bar and gesture/button navigation bar insets seamlessly.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                dp(24),
                bars.top + dp(26),
                dp(24),
                bars.bottom + dp(28),
            )
            insets
        }

        // Top live / incoming badge pill
        statusPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#13131A"))
                setStroke(dp(1), Color.parseColor("#242430"))
            }
        }
        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#34D399"))
            }
        }
        statusBadgeText = TextView(this).apply {
            textSize = 10.5f
            setTextColor(Color.parseColor("#D4D4D8"))
            letterSpacing = 0.16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        statusPill.addView(statusDot)
        statusPill.addView(statusBadgeText)

        callerText = TextView(this).apply {
            textSize = 31f
            setTextColor(Color.parseColor("#FAFAFA"))
            letterSpacing = -0.01f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) }
        }

        val metaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }

        timerText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#A1A1AA"))
            letterSpacing = 0.08f
            fontFeatureSettings = "tnum"
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(10) }
        }

        routeChip = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#A1A1AA"))
            letterSpacing = 0.14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            visibility = View.GONE
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#14141C"))
                setStroke(dp(1), Color.parseColor("#262634"))
            }
        }
        metaRow.addView(timerText)
        metaRow.addView(routeChip)

        // Centerpiece Orb Stage
        val orbSizePx = min((resources.displayMetrics.widthPixels * 0.84f).toInt(), dp(330))
        val orbStage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        orbView = OrbGLView(this).apply {
            layoutParams = LinearLayout.LayoutParams(orbSizePx, orbSizePx)
        }
        liveCueText = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.parseColor("#71717A"))
            letterSpacing = 0.18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            visibility = View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        orbStage.addView(orbView)
        orbStage.addView(liveCueText)

        // Incoming Call Controls: Bi-directional Swipe Slider
        ringingContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        callSlider = CallSliderView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(78),
            )
            onAnswer = {
                val id = CloodSessionBus.activeCall?.callId
                CloodConnectionService.start(this@CallActivity, CloodConnectionService.ACTION_ANSWER, id)
            }
            onDecline = {
                val id = CloodSessionBus.activeCall?.callId
                CloodConnectionService.start(this@CallActivity, CloodConnectionService.ACTION_DECLINE, id)
                finish()
            }
        }

        val sliderLabelsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
            setPadding(dp(18), 0, dp(18), 0)
        }
        val declineHint = TextView(this).apply {
            text = getString(R.string.call_slide_decline)
            textSize = 11.5f
            setTextColor(Color.parseColor("#71717A"))
            letterSpacing = 0.06f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.START
        }
        slideHintText = TextView(this).apply {
            text = getString(R.string.call_slide_hint)
            textSize = 11f
            setTextColor(Color.parseColor("#52525B"))
            letterSpacing = 0.03f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(14) }
        }
        val answerHint = TextView(this).apply {
            text = getString(R.string.call_slide_answer)
            textSize = 11.5f
            setTextColor(Color.parseColor("#71717A"))
            letterSpacing = 0.06f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.END
        }
        sliderLabelsRow.addView(declineHint)
        sliderLabelsRow.addView(answerHint)

        ringingContainer.addView(slideHintText)
        ringingContainer.addView(callSlider)
        ringingContainer.addView(sliderLabelsRow)

        // Active Call Controls: Frosted Glass Console + Wide Crimson End Pill
        activePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val glassDock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(16), dp(22), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(32).toFloat()
                setColor(Color.parseColor("#121218"))
                setStroke(dp(1), Color.parseColor("#252532"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(20) }
        }

        val (muteCol, mBtn, mLbl) = dockControl(R.drawable.ic_mic, getString(R.string.call_mute)) {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            CloodConnectionService.start(this, CloodConnectionService.ACTION_TOGGLE_MUTE)
        }
        muteBtn = mBtn
        muteLabel = mLbl

        val (speakerCol, sBtn, sLbl) = dockControl(R.drawable.ic_speaker, getString(R.string.call_speaker)) {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            CloodConnectionService.start(this, CloodConnectionService.ACTION_TOGGLE_SPEAKER)
        }
        speakerBtn = sBtn
        speakerLabel = sLbl

        val (btCol, bBtn, bLbl) = dockControl(R.drawable.ic_bluetooth, getString(R.string.call_bluetooth)) {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            CloodConnectionService.start(this, CloodConnectionService.ACTION_TOGGLE_BLUETOOTH)
        }
        bluetoothBtn = bBtn
        bluetoothLabel = bLbl

        glassDock.addView(muteCol, marginEnd(dp(26)))
        glassDock.addView(speakerCol, marginEnd(dp(26)))
        glassDock.addView(btCol)

        val endPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.call_end)
            setPadding(dp(28), dp(16), dp(28), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#DC2626"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                dp(184),
                dp(58),
            )
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                CloodConnectionService.start(this@CallActivity, CloodConnectionService.ACTION_HANGUP)
                finish()
            }
            attachPressScale(this)
        }
        val endIcon = ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@CallActivity, R.drawable.ic_call_end))
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) }
        }
        val endText = TextView(this).apply {
            text = getString(R.string.call_end)
            textSize = 14.5f
            setTextColor(Color.WHITE)
            letterSpacing = 0.04f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        endPill.addView(endIcon)
        endPill.addView(endText)

        activePanel.addView(glassDock)
        activePanel.addView(endPill)

        root.addView(statusPill)
        root.addView(callerText)
        root.addView(metaRow)
        root.addView(orbStage)
        root.addView(ringingContainer)
        root.addView(activePanel)

        frame.addView(root)
        return frame
    }

    private fun dockControl(
        iconRes: Int,
        label: String,
        onClick: (View) -> Unit,
    ): Triple<LinearLayout, ImageButton, TextView> {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val btn = ImageButton(this).apply {
            contentDescription = label
            setImageDrawable(ContextCompat.getDrawable(this@CallActivity, iconRes))
            background = circleControlBg(Color.parseColor("#1A1A22"), Color.parseColor("#2A2A36"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58))
            setOnClickListener { onClick(it) }
            attachPressScale(this)
        }
        val txt = TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.parseColor("#8A8A96"))
            letterSpacing = 0.03f
            setPadding(0, dp(8), 0, 0)
        }
        col.addView(btn)
        col.addView(txt)
        return Triple(col, btn, txt)
    }

    private fun attachPressScale(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(90L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
            }
            false
        }
    }

    private fun circleControlBg(fillColor: Int, strokeColor: Int?) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fillColor)
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    private fun marginEnd(px: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { marginEnd = px }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Subtle atmospheric radial glow behind the orb tinted by the agent's orb color. */
    private class AmbientGlowView(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var tintColor = Color.parseColor("#7C8CFF")
        private var lastHex: String? = null

        fun setTintHex(hex: String) {
            if (hex == lastHex) return
            lastHex = hex
            tintColor = runCatching { Color.parseColor(hex) }.getOrDefault(Color.parseColor("#7C8CFF"))
            rebuildShader()
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            rebuildShader()
        }

        private fun rebuildShader() {
            if (width <= 0 || height <= 0) return
            val cx = width * 0.5f
            val cy = height * 0.44f
            val r = maxOf(width, height) * 0.52f
            paint.shader = RadialGradient(
                cx,
                cy,
                r,
                intArrayOf(
                    ColorUtils.setAlphaComponent(tintColor, 34),
                    ColorUtils.setAlphaComponent(tintColor, 12),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP,
            )
        }

        override fun onDraw(canvas: Canvas) {
            if (paint.shader != null) {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
        }
    }

    companion object {
        @Volatile
        var isForeground: Boolean = false
    }
}
