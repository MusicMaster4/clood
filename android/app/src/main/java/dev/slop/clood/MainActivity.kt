package dev.slop.clood

import android.Manifest
import android.app.Dialog
import android.app.DownloadManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/**
 * Minimalist dark home screen centered on the Clood orb, QR pairing,
 * audio/notification settings, and channel-isolated updates.
 */
class MainActivity : AppCompatActivity(), CloodSessionBus.Listener {
    private lateinit var store: ConfigStore
    private lateinit var updater: AppUpdater
    private lateinit var previewAudio: CallAudioEngine
    private lateinit var statusDot: View
    private lateinit var nameText: TextView
    private lateinit var versionText: TextView
    private lateinit var orbView: OrbGLView
    private lateinit var cueText: TextView
    private lateinit var permissionChip: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var stopPreviewRunnable: Runnable? = null
    private var settingsDialog: Dialog? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshPermissionsChip() }

    private val qrLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refreshUi() }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id < 0 || !updater.isPendingDownload(id)) return
            if (!updater.canInstallPackages()) {
                cueText.text = "Allow install from unknown apps"
                startActivity(updater.requestInstallPermission())
                return
            }
            runCatching { updater.verifyAndInstall(id) }
                .onFailure { cueText.text = it.message ?: "Update failed" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ConfigStore(this)
        updater = AppUpdater(this)
        previewAudio = CallAudioEngine(this)
        setContentView(buildUi())

        requestRuntimePermissionsIfNeeded()
        if (store.load() != null) {
            CloodConnectionService.start(this)
        }
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onStart() {
        super.onStart()
        CloodSessionBus.register(this)
        refreshUi()
    }

    override fun onStop() {
        stopRingtonePreview()
        CloodSessionBus.unregister(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        orbView.onResume()
        refreshPermissionsChip()
        if (CloodSessionBus.activeCall != null) {
            startActivity(Intent(this, CallActivity::class.java))
        }
    }

    override fun onPause() {
        orbView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        stopRingtonePreview()
        settingsDialog?.dismiss()
        settingsDialog = null
        runCatching { unregisterReceiver(downloadReceiver) }
        super.onDestroy()
    }

    override fun onConnectionChanged(state: ConnectionState) {
        updateStatusDot(state)
    }

    override fun onAppearanceChanged(agentName: String, orbColor: String) {
        nameText.text = agentName
        orbView.setOrbState(OrbGLView.OrbMode.IDLE, orbColor, 0f, previewTint = true)
    }

    private fun refreshUi() {
        val cfg = store.load()
        nameText.text = cfg?.agentName ?: "Clood"
        val channelLabel = if (BuildConfig.UPDATE_CHANNEL == "testing") "beta" else "stable"
        versionText.text = "v${BuildConfig.VERSION_NAME} · $channelLabel"
        orbView.setOrbState(
            OrbGLView.OrbMode.IDLE,
            cfg?.orbColor ?: "#7c8cff",
            0f,
            previewTint = true,
        )
        updateStatusDot(if (cfg == null) ConnectionState.UNPAIRED else CloodSessionBus.connectionState)
        refreshPermissionsChip()
    }

    private fun updateStatusDot(state: ConnectionState) {
        val color = when (state) {
            ConnectionState.CONNECTED -> Color.parseColor("#34D399")
            ConnectionState.CONNECTING -> Color.parseColor("#F59E0B")
            ConnectionState.OFFLINE -> Color.parseColor("#EF4444")
            ConnectionState.UNPAIRED -> Color.parseColor("#3F3F46")
        }
        (statusDot.background as? GradientDrawable)?.setColor(color)
        cueText.text = when (state) {
            ConnectionState.CONNECTED -> ""
            ConnectionState.CONNECTING -> "CONNECTING"
            ConnectionState.OFFLINE -> "OFFLINE"
            ConnectionState.UNPAIRED -> "SCAN QR"
        }
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (needed.isNotEmpty()) permLauncher.launch(needed.toTypedArray())
    }

    private fun refreshPermissionsChip() {
        val nm = getSystemService(NotificationManager::class.java)
        val missingFullScreen = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            nm != null && !nm.canUseFullScreenIntent()
        val pm = getSystemService(PowerManager::class.java)
        val batteryOptimized = pm != null && !pm.isIgnoringBatteryOptimizations(packageName)

        when {
            missingFullScreen -> {
                permissionChip.visibility = View.VISIBLE
                permissionChip.text = "Enable lock-screen calls"
                permissionChip.setOnClickListener {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    }
                }
            }
            batteryOptimized -> {
                permissionChip.visibility = View.VISIBLE
                permissionChip.text = "Allow background calls"
                permissionChip.setOnClickListener {
                    runCatching {
                        startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    }
                }
            }
            else -> {
                permissionChip.visibility = View.GONE
            }
        }
    }

    private fun checkForUpdates() {
        cueText.text = "CHECKING UPDATES…"
        thread {
            val result = runCatching { UpdateClient.fetch(BuildConfig.UPDATE_CHANNEL) }
            runOnUiThread {
                result.onSuccess { manifest ->
                    if (!manifest.isNewerThan(BuildConfig.VERSION_CODE)) {
                        cueText.text = "UP TO DATE"
                    } else {
                        cueText.text = "DOWNLOADING v${manifest.versionName}…"
                        runCatching { updater.enqueue(manifest) }
                            .onFailure { cueText.text = it.message ?: "Update failed" }
                    }
                }.onFailure {
                    cueText.text = it.message ?: "Update check failed"
                }
            }
        }
    }

    private fun stopRingtonePreview() {
        stopPreviewRunnable?.let { handler.removeCallbacks(it) }
        stopPreviewRunnable = null
        previewAudio.stopRingtone()
    }

    private fun showSettingsDialog() {
        settingsDialog?.dismiss()
        stopRingtonePreview()

        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        settingsDialog = dialog

        var selectedStream = store.loadRingtoneStream()
        var previewPlaying = false

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(Color.parseColor("#111116"))
                setStroke(dp(1), Color.parseColor("#262632"))
            }
        }

        // Header
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(20) }
        }
        val titleTxt = TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 18f
            setTextColor(Color.parseColor("#FAFAFA"))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val doneBtn = TextView(this).apply {
            text = getString(R.string.settings_done)
            textSize = 13f
            setTextColor(Color.parseColor("#A1A1AA"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#1A1A22"))
            }
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(titleTxt)
        headerRow.addView(doneBtn)
        card.addView(headerRow)

        // Section 1: Ringtone Volume Channel (Ringtone vs Alarm)
        val ringSectionTitle = TextView(this).apply {
            text = getString(R.string.settings_ringtone_title)
            textSize = 13.5f
            setTextColor(Color.parseColor("#F4F4F5"))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val ringSectionDesc = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#8A8A96"))
            setPadding(0, dp(4), 0, dp(12))
        }

        val segmentTrack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#181820"))
                setStroke(dp(1), Color.parseColor("#252532"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val optRing = TextView(this).apply {
            text = getString(R.string.settings_ringtone_opt_ring)
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val optAlarm = TextView(this).apply {
            text = getString(R.string.settings_ringtone_opt_alarm)
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        segmentTrack.addView(optRing)
        segmentTrack.addView(optAlarm)

        val testBtn = TextView(this).apply {
            text = getString(R.string.settings_ringtone_test)
            textSize = 12f
            setTextColor(Color.parseColor("#D4D4D8"))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#1A1A22"))
                setStroke(dp(1), Color.parseColor("#2A2A36"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }

        fun renderSegmentState() {
            val isAlarm = selectedStream == RingtoneStream.ALARM
            optRing.background = if (!isAlarm) pillSelectedBg() else null
            optRing.setTextColor(if (!isAlarm) Color.parseColor("#09090B") else Color.parseColor("#A1A1AA"))
            optAlarm.background = if (isAlarm) pillSelectedBg() else null
            optAlarm.setTextColor(if (isAlarm) Color.parseColor("#09090B") else Color.parseColor("#A1A1AA"))
            ringSectionDesc.text = getString(
                if (isAlarm) R.string.settings_ringtone_desc_alarm else R.string.settings_ringtone_desc_ring,
            )
            testBtn.text = getString(
                if (previewPlaying) R.string.settings_ringtone_stop else R.string.settings_ringtone_test,
            )
        }

        optRing.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            selectedStream = RingtoneStream.RINGTONE
            store.saveRingtoneStream(selectedStream)
            if (previewPlaying) {
                stopRingtonePreview()
                previewPlaying = false
            }
            renderSegmentState()
        }
        optAlarm.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            selectedStream = RingtoneStream.ALARM
            store.saveRingtoneStream(selectedStream)
            if (previewPlaying) {
                stopRingtonePreview()
                previewPlaying = false
            }
            renderSegmentState()
        }
        testBtn.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (previewPlaying) {
                stopRingtonePreview()
                previewPlaying = false
                renderSegmentState()
            } else {
                previewPlaying = true
                renderSegmentState()
                previewAudio.startRingtone(selectedStream)
                val autoStop = Runnable {
                    stopRingtonePreview()
                    previewPlaying = false
                    renderSegmentState()
                }
                stopPreviewRunnable = autoStop
                handler.postDelayed(autoStop, 2200L)
            }
        }
        renderSegmentState()

        card.addView(ringSectionTitle)
        card.addView(ringSectionDesc)
        card.addView(segmentTrack)
        card.addView(testBtn)
        card.addView(settingsDivider())

        // Section 2: Display over other apps (Full-screen call takeover even while unlocked in other apps)
        val canOverlay = Settings.canDrawOverlays(this)
        card.addView(
            settingsActionRow(
                title = getString(R.string.settings_overlay_title),
                desc = getString(R.string.settings_overlay_desc),
                badgeText = getString(if (canOverlay) R.string.settings_state_on else R.string.settings_state_off),
                badgePositive = canOverlay,
            ) {
                dialog.dismiss()
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                }
            },
        )
        card.addView(settingsDivider())

        // Section 3: Hide persistent background notification channel
        val nm = getSystemService(NotificationManager::class.java)
        val serviceChannel = nm?.getNotificationChannel(CloodApp.CHANNEL_SERVICE)
        val notifHidden = serviceChannel?.importance == NotificationManager.IMPORTANCE_NONE
        card.addView(
            settingsActionRow(
                title = getString(R.string.settings_notif_title),
                desc = getString(R.string.settings_notif_desc),
                badgeText = getString(if (notifHidden) R.string.settings_state_hidden else R.string.settings_state_visible),
                badgePositive = notifHidden,
            ) {
                dialog.dismiss()
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            putExtra(Settings.EXTRA_CHANNEL_ID, CloodApp.CHANNEL_SERVICE)
                        },
                    )
                }
            },
        )

        dialog.setOnDismissListener {
            stopRingtonePreview()
            if (settingsDialog === dialog) settingsDialog = null
        }

        val container = FrameLayout(this).apply {
            setPadding(dp(18), dp(18), dp(18), dp(18))
            addView(
                card,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        dialog.setContentView(container)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.68f)
        }
        dialog.show()
    }

    private fun pillSelectedBg() = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(Color.parseColor("#F4F4F5"))
    }

    private fun settingsDivider() = View(this).apply {
        setBackgroundColor(Color.parseColor("#1E1E28"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1),
        ).apply {
            topMargin = dp(18)
            bottomMargin = dp(18)
        }
    }

    private fun settingsActionRow(
        title: String,
        desc: String,
        badgeText: String,
        badgePositive: Boolean,
        onClick: () -> Unit,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(12) }
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 13.5f
            setTextColor(Color.parseColor("#F4F4F5"))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val descView = TextView(this).apply {
            text = desc
            textSize = 11.5f
            setTextColor(Color.parseColor("#8A8A96"))
            setPadding(0, dp(3), 0, 0)
        }
        textCol.addView(titleView)
        textCol.addView(descView)

        val badge = TextView(this).apply {
            text = badgeText
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (badgePositive) Color.parseColor("#34D399") else Color.parseColor("#E4E4E7"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(if (badgePositive) Color.parseColor("#142822") else Color.parseColor("#1C1C26"))
                setStroke(dp(1), if (badgePositive) Color.parseColor("#1E4D3E") else Color.parseColor("#2B2B38"))
            }
        }
        row.addView(textCol)
        row.addView(badge)
        return row
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#09090B"))
            setPadding(dp(20), dp(44), dp(20), dp(36))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#3F3F46"))
            }
        }

        nameText = TextView(this).apply {
            text = "Clood"
            textSize = 15f
            setTextColor(Color.parseColor("#D4D4D8"))
            letterSpacing = 0.04f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        versionText = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#52525B"))
        }

        topBar.addView(statusDot)
        topBar.addView(nameText)
        topBar.addView(versionText)

        val stage = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        orbView = OrbGLView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(270), dp(270), Gravity.CENTER)
        }
        stage.addView(orbView)

        cueText = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#71717A"))
            letterSpacing = 0.14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        }

        permissionChip = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#E4E4E7"))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#18181B"))
                setStroke(dp(1), Color.parseColor("#27272A"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(18) }
        }

        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#141418"))
                setStroke(dp(1), Color.parseColor("#222228"))
            }
        }

        val qrBtn = dockButton(R.drawable.ic_qr, getString(R.string.main_cd_qr)) {
            qrLauncher.launch(Intent(this, QrScannerActivity::class.java))
        }
        val settingsBtn = dockButton(R.drawable.ic_settings, getString(R.string.main_cd_settings)) {
            showSettingsDialog()
        }
        val updateBtn = dockButton(R.drawable.ic_update, getString(R.string.main_cd_update)) {
            checkForUpdates()
        }

        dock.addView(qrBtn, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(10) })
        dock.addView(settingsBtn, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(10) })
        dock.addView(updateBtn, LinearLayout.LayoutParams(dp(48), dp(48)))

        root.addView(topBar)
        root.addView(stage)
        root.addView(cueText)
        root.addView(permissionChip)
        root.addView(dock)
        return root
    }

    private fun dockButton(iconRes: Int, desc: String, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            contentDescription = desc
            setImageDrawable(ContextCompat.getDrawable(this@MainActivity, iconRes))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
