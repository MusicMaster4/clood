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
import kotlin.math.hypot
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
    private lateinit var rootFrame: FrameLayout
    private lateinit var endPill: LinearLayout
    private lateinit var endCallOverlay: EndCallRadialOverlay

    private var endPointerId = MotionEvent.INVALID_POINTER_ID
    private var endDownRawX = 0f
    private var endDownRawY = 0f
    private var endLastRawX = 0f
    private var endLastRawY = 0f
    private var endHoldOpened = false
    private var endGestureCancelled = false

    private var pulseAnimator: ObjectAnimator? = null
    private var lastPhase: CallPhase? = null

    private val handler = Handler(Looper.getMainLooper())
    private val openEndHoldMenuRunnable = Runnable {
        if (endGestureCancelled || endPointerId == MotionEvent.INVALID_POINTER_ID) return@Runnable
        if (CloodSessionBus.activeCall?.phase != CallPhase.ACTIVE) return@Runnable
        endHoldOpened = true
        endPill.parent?.requestDisallowInterceptTouchEvent(true)
        endPill.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val pillLoc = IntArray(2)
        val frameLoc = IntArray(2)
        endPill.getLocationOnScreen(pillLoc)
        rootFrame.getLocationOnScreen(frameLoc)
        val anchorX = pillLoc[0] + endPill.width * endPill.scaleX * 0.5f - frameLoc[0]
        val anchorY = pillLoc[1] + endPill.height * endPill.scaleY * 0.5f - frameLoc[1]
        endCallOverlay.open(
            anchorX = anchorX,
            anchorY = anchorY,
            fingerX = endLastRawX - frameLoc[0],
            fingerY = endLastRawY - frameLoc[1],
        )
    }
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
        if (CloodSessionBus.activeCall?.phase == CallPhase.RINGING) {
            startDotPulse()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            CloodConnectionService.dismissIncomingNotification(this)
        }
    }

    override fun onPause() {
        isForeground = false
        resetEndHoldGesture(immediate = true)
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
    }

    private fun renderCall(call: ActiveCallUi?) {
        if (call == null) {
            resetEndHoldGesture(immediate = true)
            finish()
            return
        }
        if (call.phase != CallPhase.ACTIVE) {
            resetEndHoldGesture(immediate = true)
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

                statusPill.visibility = View.VISIBLE
                statusBadgeText.text = getString(R.string.call_incoming_badge)
                setStatusDotColor(Color.parseColor("#34D399"))
                startDotPulse()
                timerText.visibility = View.GONE
                routeChip.visibility = if (stream == RingtoneStream.ALARM) View.VISIBLE else View.GONE
                if (stream == RingtoneStream.ALARM) {
                    routeChip.text = getString(R.string.call_alarm_badge)
                }

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

                statusPill.visibility = View.GONE
                stopDotPulse()

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
                routeChip.visibility = View.GONE

                updateTimer()
                updateControlStates(call)
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
        speakerBtn.setImageResource(R.drawable.ic_speaker)
        speakerBtn.background = circleControlBg(if (speakerActive) activeBg else idleBg, if (speakerActive) null else idleStroke)
        speakerBtn.setColorFilter(if (speakerActive) darkIcon else lightIcon)
        speakerLabel.text = getString(R.string.call_speaker)
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
    }

    private fun buildUi(): View {
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#09090B"))
        }
        rootFrame = frame

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
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
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
        orbStage.addView(orbView)

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

        // Active Call Controls: Floating Minimalist Buttons + Wide Crimson End Pill
        activePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(32) }
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

        val equalColParams = {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        controlsRow.addView(muteCol, equalColParams())
        controlsRow.addView(speakerCol, equalColParams())
        controlsRow.addView(btCol, equalColParams())

        endPill = LinearLayout(this).apply {
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
            attachEndPillHoldGesture(this)
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

        val endHoldHint = TextView(this).apply {
            text = getString(R.string.call_end_hold_hint)
            textSize = 10.5f
            setTextColor(Color.parseColor("#52525B"))
            letterSpacing = 0.03f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }

        activePanel.addView(controlsRow)
        activePanel.addView(endPill)
        activePanel.addView(endHoldHint)

        root.addView(statusPill)
        root.addView(callerText)
        root.addView(metaRow)
        root.addView(orbStage)
        root.addView(ringingContainer)
        root.addView(activePanel)

        frame.addView(root)

        endCallOverlay = EndCallRadialOverlay(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        frame.addView(endCallOverlay)
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
            setPadding(dp(18), dp(18), dp(18), dp(18))
            layoutParams = LinearLayout.LayoutParams(dp(62), dp(62))
            setOnClickListener { onClick(it) }
            attachPressScale(this)
        }
        val txt = TextView(this).apply {
            text = label
            textSize = 11.5f
            setTextColor(Color.parseColor("#8A8A96"))
            letterSpacing = 0.02f
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, dp(9), 0, 0)
        }
        col.addView(btn)
        col.addView(txt)
        return Triple(col, btn, txt)
    }

    private fun attachEndPillHoldGesture(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    endPointerId = event.getPointerId(0)
                    endDownRawX = event.rawX
                    endDownRawY = event.rawY
                    endLastRawX = event.rawX
                    endLastRawY = event.rawY
                    endHoldOpened = false
                    endGestureCancelled = false
                    v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90L).start()
                    handler.removeCallbacks(openEndHoldMenuRunnable)
                    handler.postDelayed(openEndHoldMenuRunnable, END_HOLD_DELAY_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val idx = event.findPointerIndex(endPointerId)
                    if (idx < 0) return@setOnTouchListener true
                    val rawX = if (idx == 0) event.rawX else event.getX(idx) + (event.rawX - event.x)
                    val rawY = if (idx == 0) event.rawY else event.getY(idx) + (event.rawY - event.y)
                    endLastRawX = rawX
                    endLastRawY = rawY

                    if (endHoldOpened) {
                        val frameLoc = IntArray(2)
                        rootFrame.getLocationOnScreen(frameLoc)
                        endCallOverlay.updateFinger(rawX - frameLoc[0], rawY - frameLoc[1])
                    } else if (!endGestureCancelled) {
                        if (hypot(rawX - endDownRawX, rawY - endDownRawY) > dp(24)) {
                            endGestureCancelled = true
                            handler.removeCallbacks(openEndHoldMenuRunnable)
                            v.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(openEndHoldMenuRunnable)
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
                    endPointerId = MotionEvent.INVALID_POINTER_ID

                    if (endHoldOpened) {
                        endHoldOpened = false
                        when (endCallOverlay.finishAndGetSelection()) {
                            EndCallOption.REDIAL_10M -> {
                                v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                CloodConnectionService.start(
                                    this@CallActivity,
                                    CloodConnectionService.ACTION_HANGUP_REDIAL,
                                )
                                finish()
                            }
                            EndCallOption.CANCEL -> {
                                v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                CloodConnectionService.start(
                                    this@CallActivity,
                                    CloodConnectionService.ACTION_HANGUP_CANCEL,
                                )
                                finish()
                            }
                            EndCallOption.NONE -> Unit
                        }
                    } else if (!endGestureCancelled) {
                        v.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    resetEndHoldGesture(immediate = false)
                    true
                }
                else -> false
            }
        }
    }

    private fun resetEndHoldGesture(immediate: Boolean) {
        handler.removeCallbacks(openEndHoldMenuRunnable)
        endPointerId = MotionEvent.INVALID_POINTER_ID
        endHoldOpened = false
        endGestureCancelled = false
        if (::endPill.isInitialized) {
            endPill.parent?.requestDisallowInterceptTouchEvent(false)
            endPill.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
        }
        if (::endCallOverlay.isInitialized) {
            if (immediate) endCallOverlay.dismissImmediate() else endCallOverlay.dismiss()
        }
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
        private const val END_HOLD_DELAY_MS = 320L

        @Volatile
        var isForeground: Boolean = false
    }
}
