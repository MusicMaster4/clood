package dev.slop.clood

import android.app.KeyguardManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Full-screen minimalist dark call UI with lock-screen wake-up,
 * glowing Clood orb, and full audio controls (Mute, Speaker, Bluetooth, Answer, Decline, End).
 */
class CallActivity : AppCompatActivity(), CloodSessionBus.Listener {
    private lateinit var orbView: OrbGLView
    private lateinit var callerText: TextView
    private lateinit var cueText: TextView
    private lateinit var routePill: TextView
    private lateinit var ringingRow: LinearLayout
    private lateinit var activePanel: LinearLayout

    private lateinit var muteBtn: ImageButton
    private lateinit var muteLabel: TextView
    private lateinit var speakerBtn: ImageButton
    private lateinit var speakerLabel: TextView
    private lateinit var bluetoothBtn: ImageButton
    private lateinit var bluetoothLabel: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            updateTimer()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(buildUi())
        renderCall(CloodSessionBus.activeCall)
    }

    override fun onStart() {
        super.onStart()
        CloodSessionBus.register(this)
        renderCall(CloodSessionBus.activeCall)
        handler.post(timerTick)
    }

    override fun onStop() {
        handler.removeCallbacks(timerTick)
        CloodSessionBus.unregister(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        orbView.onResume()
    }

    override fun onPause() {
        orbView.onPause()
        super.onPause()
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
            finish()
            return
        }
        callerText.text = call.callerName
        when (call.phase) {
            CallPhase.RINGING -> {
                cueText.text = "INCOMING CALL"
                routePill.visibility = View.GONE
                ringingRow.visibility = View.VISIBLE
                activePanel.visibility = View.GONE
                orbView.setOrbState(OrbGLView.OrbMode.RINGING, call.orbColor, 0.35f, previewTint = true)
            }
            CallPhase.ACTIVE -> {
                ringingRow.visibility = View.GONE
                activePanel.visibility = View.VISIBLE
                routePill.visibility = View.VISIBLE
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
    }

    private fun updateTimer() {
        val call = CloodSessionBus.activeCall ?: return
        if (call.phase != CallPhase.ACTIVE) return
        val started = call.answeredAtMs ?: call.startedAtMs
        val sec = ((System.currentTimeMillis() - started) / 1000L).coerceAtLeast(0L)
        cueText.text = "%d:%02d".format(sec / 60, sec % 60)
    }

    private fun updateControlStates(call: ActiveCallUi) {
        val onBg = Color.parseColor("#F4F4F5")
        val offBg = Color.parseColor("#1D1D24")
        val btActiveBg = Color.parseColor("#6366F1")

        muteBtn.setImageResource(if (call.muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        muteBtn.background = circleBg(if (call.muted) onBg else offBg)
        muteBtn.setColorFilter(if (call.muted) Color.parseColor("#09090B") else Color.parseColor("#F4F4F5"))
        muteLabel.text = if (call.muted) "Muted" else "Mute"

        val speakerActive = call.audioRoute == AudioRoute.SPEAKER
        speakerBtn.background = circleBg(if (speakerActive) onBg else offBg)
        speakerBtn.setColorFilter(if (speakerActive) Color.parseColor("#09090B") else Color.parseColor("#F4F4F5"))
        speakerLabel.text = if (speakerActive) "Speaker" else "Phone"

        val btActive = call.audioRoute == AudioRoute.BLUETOOTH
        bluetoothBtn.isEnabled = call.bluetoothAvailable || btActive
        bluetoothBtn.alpha = if (call.bluetoothAvailable || btActive) 1f else 0.35f
        bluetoothBtn.background = circleBg(if (btActive) btActiveBg else offBg)
        bluetoothBtn.setColorFilter(Color.parseColor("#F4F4F5"))
        bluetoothLabel.alpha = if (call.bluetoothAvailable || btActive) 1f else 0.35f
        bluetoothLabel.text = if (btActive) "BT On" else "Bluetooth"

        routePill.text = when (call.audioRoute) {
            AudioRoute.BLUETOOTH -> "BLUETOOTH"
            AudioRoute.SPEAKER -> "SPEAKER"
            AudioRoute.EARPIECE -> "EARPIECE"
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#09090B"))
            setPadding(dp(24), dp(60), dp(24), dp(42))
        }

        callerText = TextView(this).apply {
            textSize = 28f
            setTextColor(Color.parseColor("#F4F4F5"))
            letterSpacing = 0.02f
            gravity = Gravity.CENTER
        }

        cueText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#8A8A96"))
            letterSpacing = 0.16f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }

        routePill = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#A1A1AA"))
            letterSpacing = 0.14f
            visibility = View.GONE
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#14141A"))
                setStroke(dp(1), Color.parseColor("#24242E"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }

        val orbContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        orbView = OrbGLView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(310), dp(310), Gravity.CENTER)
        }
        orbContainer.addView(orbView)

        // Incoming Call Controls (Decline / Answer)
        ringingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(12))
        }

        val declineCol = labeledRoundAction(
            iconRes = R.drawable.ic_call_end,
            bgColor = Color.parseColor("#EF4444"),
            ringColor = Color.parseColor("#33EF4444"),
            sizeDp = 74,
            label = "Decline",
        ) { view ->
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            val id = CloodSessionBus.activeCall?.callId
            CloodConnectionService.start(this, CloodConnectionService.ACTION_DECLINE, id)
            finish()
        }

        val answerCol = labeledRoundAction(
            iconRes = R.drawable.ic_call_accept,
            bgColor = Color.parseColor("#22C55E"),
            ringColor = Color.parseColor("#3322C55E"),
            sizeDp = 74,
            label = "Answer",
        ) { view ->
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            val km = getSystemService(KeyguardManager::class.java)
            if (km?.isKeyguardLocked == true) {
                runCatching { km.requestDismissKeyguard(this, null) }
            }
            val id = CloodSessionBus.activeCall?.callId
            CloodConnectionService.start(this, CloodConnectionService.ACTION_ANSWER, id)
        }

        ringingRow.addView(declineCol, marginEnd(dp(68)))
        ringingRow.addView(answerCol)

        // Active Call Controls (Frosted dock: Mute / Speaker / Bluetooth + End Call)
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
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor("#131318"))
                setStroke(dp(1), Color.parseColor("#24242E"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(22) }
        }

        val (muteCol, mBtn, mLbl) = dockControl(R.drawable.ic_mic, "Mute") {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            CloodConnectionService.start(this, CloodConnectionService.ACTION_TOGGLE_MUTE)
        }
        muteBtn = mBtn
        muteLabel = mLbl

        val (speakerCol, sBtn, sLbl) = dockControl(R.drawable.ic_speaker, "Speaker") {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            CloodConnectionService.start(this, CloodConnectionService.ACTION_TOGGLE_SPEAKER)
        }
        speakerBtn = sBtn
        speakerLabel = sLbl

        val (btCol, bBtn, bLbl) = dockControl(R.drawable.ic_bluetooth, "Bluetooth") {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            CloodConnectionService.start(this, CloodConnectionService.ACTION_TOGGLE_BLUETOOTH)
        }
        bluetoothBtn = bBtn
        bluetoothLabel = bLbl

        glassDock.addView(muteCol, marginEnd(dp(24)))
        glassDock.addView(speakerCol, marginEnd(dp(24)))
        glassDock.addView(btCol)

        val hangupBtn = ImageButton(this).apply {
            contentDescription = "End call"
            setImageDrawable(ContextCompat.getDrawable(this@CallActivity, R.drawable.ic_call_end))
            background = circleBg(Color.parseColor("#EF4444"))
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                CloodConnectionService.start(this@CallActivity, CloodConnectionService.ACTION_HANGUP)
                finish()
            }
        }

        activePanel.addView(glassDock)
        activePanel.addView(hangupBtn)

        root.addView(callerText)
        root.addView(cueText)
        root.addView(routePill)
        root.addView(orbContainer)
        root.addView(ringingRow)
        root.addView(activePanel)
        return root
    }

    private fun labeledRoundAction(
        iconRes: Int,
        bgColor: Int,
        ringColor: Int,
        sizeDp: Int,
        label: String,
        onClick: (View) -> Unit,
    ): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val ring = FrameLayout(this).apply {
            background = circleBg(ringColor)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val btn = ImageButton(this).apply {
            contentDescription = label
            setImageDrawable(ContextCompat.getDrawable(this@CallActivity, iconRes))
            background = circleBg(bgColor)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.CENTER)
            setOnClickListener { onClick(it) }
        }
        ring.addView(btn)
        val txt = TextView(this).apply {
            text = label
            textSize = 11.5f
            setTextColor(Color.parseColor("#A1A1AA"))
            setPadding(0, dp(10), 0, 0)
        }
        col.addView(ring)
        col.addView(txt)
        return col
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
            background = circleBg(Color.parseColor("#1D1D24"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58))
            setOnClickListener { onClick(it) }
        }
        val txt = TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.parseColor("#A1A1AA"))
            setPadding(0, dp(7), 0, 0)
        }
        col.addView(btn)
        col.addView(txt)
        return Triple(col, btn, txt)
    }

    private fun circleBg(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun marginEnd(px: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { marginEnd = px }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
