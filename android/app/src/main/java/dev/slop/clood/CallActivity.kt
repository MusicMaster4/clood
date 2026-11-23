package dev.slop.clood

import android.app.KeyguardManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Full-screen minimalist lock-screen call UI: glowing Clood orb + native call actions.
 */
class CallActivity : AppCompatActivity(), CloodSessionBus.Listener {
    private lateinit var orbView: OrbGLView
    private lateinit var callerText: TextView
    private lateinit var cueText: TextView
    private lateinit var ringingRow: LinearLayout
    private lateinit var activeRow: LinearLayout
    private lateinit var muteBtn: ImageButton
    private lateinit var speakerBtn: ImageButton

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
                cueText.text = "INCOMING"
                ringingRow.visibility = View.VISIBLE
                activeRow.visibility = View.GONE
                orbView.setOrbState(OrbGLView.OrbMode.RINGING, call.orbColor, 0.35f, previewTint = true)
            }
            CallPhase.ACTIVE -> {
                ringingRow.visibility = View.GONE
                activeRow.visibility = View.VISIBLE
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
        muteBtn.setImageResource(if (call.muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        muteBtn.background = circleBg(if (call.muted) 0x33FFFFFF else 0x1AFFFFFF)
        speakerBtn.background = circleBg(if (call.speakerOn) 0x33FFFFFF else 0x1AFFFFFF)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#09090B"))
            setPadding(dp(24), dp(64), dp(24), dp(52))
        }

        callerText = TextView(this).apply {
            textSize = 24f
            setTextColor(Color.parseColor("#F4F4F5"))
            letterSpacing = 0.03f
            gravity = Gravity.CENTER
        }

        cueText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#71717A"))
            letterSpacing = 0.14f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }

        val orbContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        orbView = OrbGLView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(300), dp(300), Gravity.CENTER)
        }
        orbContainer.addView(orbView)

        ringingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val declineBtn = roundActionButton(R.drawable.ic_call_end, Color.parseColor("#EF4444"), 72) {
            val id = CloodSessionBus.activeCall?.callId
            CloodConnectionService.start(this, CloodConnectionService.ACTION_DECLINE, id)
            finish()
        }
        val answerBtn = roundActionButton(R.drawable.ic_call_accept, Color.parseColor("#22C55E"), 72) {
            val km = getSystemService(KeyguardManager::class.java)
            if (km?.isKeyguardLocked == true) {
                runCatching { km.requestDismissKeyguard(this, null) }
            }
            val id = CloodSessionBus.activeCall?.callId
            CloodConnectionService.start(this, CloodConnectionService.ACTION_ANSWER, id)
        }
        ringingRow.addView(declineBtn, marginEnd(dp(64)))
        ringingRow.addView(answerBtn)

        activeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        muteBtn = roundActionButton(R.drawable.ic_mic, 0x1AFFFFFF, 60) {
            CloodConnectionService.start(this, CloodConnectionService.ACTION_TOGGLE_MUTE)
        }
        val hangupBtn = roundActionButton(R.drawable.ic_call_end, Color.parseColor("#EF4444"), 70) {
            CloodConnectionService.start(this, CloodConnectionService.ACTION_HANGUP)
            finish()
        }
        speakerBtn = roundActionButton(R.drawable.ic_speaker, 0x33FFFFFF, 60) {
            CloodConnectionService.start(this, CloodConnectionService.ACTION_TOGGLE_SPEAKER)
        }
        activeRow.addView(muteBtn, marginEnd(dp(28)))
        activeRow.addView(hangupBtn, marginEnd(dp(28)))
        activeRow.addView(speakerBtn)

        root.addView(callerText)
        root.addView(cueText)
        root.addView(orbContainer)
        root.addView(ringingRow)
        root.addView(activeRow)
        return root
    }

    private fun roundActionButton(iconRes: Int, bgColor: Int, sizeDp: Int, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@CallActivity, iconRes))
            background = circleBg(bgColor)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            setOnClickListener { onClick() }
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
