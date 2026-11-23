package dev.slop.clood

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

object CloodSessionBus {
    interface Listener {
        fun onConnectionChanged(state: ConnectionState) {}
        fun onAppearanceChanged(agentName: String, orbColor: String) {}
        fun onCallChanged(call: ActiveCallUi?) {}
        fun onAgentAudioLevel(speaking: Boolean, level: Float) {}
    }

    @Volatile var connectionState: ConnectionState = ConnectionState.UNPAIRED
    @Volatile var activeCall: ActiveCallUi? = null
    @Volatile var agentSpeaking: Boolean = false
    @Volatile var audioLevel: Float = 0f

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()

    fun register(listener: Listener) {
        listeners.add(listener)
    }

    fun unregister(listener: Listener) {
        listeners.remove(listener)
    }

    fun publishConnection(state: ConnectionState) {
        connectionState = state
        mainHandler.post { listeners.forEach { it.onConnectionChanged(state) } }
    }

    fun publishAppearance(agentName: String, orbColor: String) {
        mainHandler.post { listeners.forEach { it.onAppearanceChanged(agentName, orbColor) } }
    }

    fun publishCall(call: ActiveCallUi?) {
        activeCall = call
        mainHandler.post { listeners.forEach { it.onCallChanged(call) } }
    }

    fun publishAudioLevel(speaking: Boolean, level: Float) {
        agentSpeaking = speaking
        audioLevel = level
        mainHandler.post { listeners.forEach { it.onAgentAudioLevel(speaking, level) } }
    }
}

class CloodConnectionService : Service() {
    private lateinit var store: ConfigStore
    private lateinit var audioEngine: CallAudioEngine

    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var socket: WebSocket? = null
    private var reconnectDelayMs = 2000L
    private var destroyed = false
    private var ringWakeLock: PowerManager.WakeLock? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var activeCodec: AudioCodecType = AudioCodecType.ADPCM_24K
    private var micFgsActive = false
    private var pendingIncomingNotifRunnable: Runnable? = null

    private val silenceReset = Runnable {
        CloodSessionBus.publishAudioLevel(false, 0f)
    }

    private val reconnectRunnable = Runnable {
        if (!destroyed) connectNow()
    }

    override fun onCreate() {
        super.onCreate()
        store = ConfigStore(this)
        audioEngine = CallAudioEngine(this)
        startMinimalForeground(includeMic = false)
    }

    private fun startMinimalForeground(includeMic: Boolean) {
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (includeMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID_SERVICE,
                buildServiceNotification(),
                fgsType,
            )
            micFgsActive = includeMic
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RECONNECT -> {
                reconnectDelayMs = 2000L
                connectNow()
            }
            ACTION_DISCONNECT -> {
                teardownCall(notifyPc = true)
                socket?.close(1000, "User disconnected")
                socket = null
                CloodSessionBus.publishConnection(ConnectionState.UNPAIRED)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ANSWER -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: CloodSessionBus.activeCall?.callId
                if (callId != null) answerCall(callId)
            }
            ACTION_DECLINE -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: CloodSessionBus.activeCall?.callId
                if (callId != null) declineCall(callId)
            }
            ACTION_HANGUP -> {
                teardownCall(notifyPc = true)
            }
            ACTION_HANGUP_REDIAL -> {
                teardownCall(notifyPc = true, hangupReason = HANGUP_REASON_REDIAL_10M)
            }
            ACTION_HANGUP_CANCEL -> {
                teardownCall(notifyPc = true, hangupReason = HANGUP_REASON_CANCEL)
            }
            ACTION_REPOST_RINGING_NOTIF -> {
                val current = CloodSessionBus.activeCall
                if (current != null && current.phase == CallPhase.RINGING && !CallActivity.isForeground) {
                    postIncomingCallNotification(current)
                }
            }
            ACTION_TOGGLE_MUTE -> {
                val current = CloodSessionBus.activeCall ?: return START_STICKY
                val nextMuted = !current.muted
                audioEngine.setMuted(nextMuted)
                CloodSessionBus.publishCall(current.copy(muted = nextMuted))
            }
            ACTION_TOGGLE_SPEAKER -> {
                val current = CloodSessionBus.activeCall ?: return START_STICKY
                val desired = if (current.audioRoute == AudioRoute.SPEAKER) {
                    AudioRoute.EARPIECE
                } else {
                    AudioRoute.SPEAKER
                }
                val applied = audioEngine.setAudioRoute(desired)
                val updated = current.copy(
                    audioRoute = applied,
                    bluetoothAvailable = audioEngine.isBluetoothAvailable(),
                )
                CloodSessionBus.publishCall(updated)
                syncProximityWakeLock(updated)
            }
            ACTION_TOGGLE_BLUETOOTH -> {
                val current = CloodSessionBus.activeCall ?: return START_STICKY
                val btAvail = audioEngine.isBluetoothAvailable()
                val desired = if (current.audioRoute == AudioRoute.BLUETOOTH) {
                    AudioRoute.EARPIECE
                } else if (btAvail) {
                    AudioRoute.BLUETOOTH
                } else {
                    current.audioRoute
                }
                val applied = audioEngine.setAudioRoute(desired)
                val updated = current.copy(
                    audioRoute = applied,
                    bluetoothAvailable = btAvail,
                )
                CloodSessionBus.publishCall(updated)
                syncProximityWakeLock(updated)
            }
            else -> {
                if (socket == null) connectNow()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        clearPendingIncomingNotif()
        mainHandler.removeCallbacksAndMessages(null)
        teardownCall(notifyPc = false)
        socket?.close(1000, "Service stopped")
        socket = null
        releaseWakeLock()
        releaseProximityWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connectNow() {
        mainHandler.removeCallbacks(reconnectRunnable)
        socket?.cancel()
        socket = null

        val cfg = store.load()
        if (cfg == null) {
            CloodSessionBus.publishConnection(ConnectionState.UNPAIRED)
            stopSelf()
            return
        }

        activeCodec = cfg.codec
        CloodSessionBus.publishConnection(ConnectionState.CONNECTING)

        val device = URLEncoder.encode(Build.MODEL ?: "Android", "UTF-8")
        val token = URLEncoder.encode(cfg.token, "UTF-8")
        val url = "ws://${cfg.host}:${cfg.port}/ws/phone?token=$token&device=$device"

        val req = Request.Builder().url(url).build()
        socket = client.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    reconnectDelayMs = 2000L
                    CloodSessionBus.publishConnection(ConnectionState.CONNECTED)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleServerJson(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleServerAudio(bytes.toByteArray())
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (socket !== webSocket) return
                    socket = null
                    teardownCall(notifyPc = false)
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (socket !== webSocket) return
                    socket = null
                    teardownCall(notifyPc = false)
                    if (response?.code == 401 || response?.code == 403) {
                        CloodSessionBus.publishConnection(ConnectionState.OFFLINE)
                        return
                    }
                    scheduleReconnect()
                }
            },
        )
    }

    private fun scheduleReconnect() {
        if (destroyed) return
        CloodSessionBus.publishConnection(ConnectionState.OFFLINE)
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(20_000L)
        mainHandler.postDelayed(reconnectRunnable, delay)
    }

    private fun handleServerJson(raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (json.optString("type")) {
            "config_sync" -> {
                val name = json.optString("agent_name")
                val color = json.optString("orb_color")
                val codec = json.optString("audio_codec")
                store.updateSyncedAppearance(name, color, codec)
                if (codec.isNotBlank()) activeCodec = AudioCodecType.fromName(codec)
                val cfg = store.load()
                if (cfg != null) {
                    CloodSessionBus.publishAppearance(cfg.agentName, cfg.orbColor)
                }
            }
            "call_incoming" -> {
                val callId = json.optString("call_id").takeIf { it.isNotBlank() } ?: return
                val cfg = store.load()
                val callerName = json.optString("caller_name").ifBlank { cfg?.agentName ?: "Clood" }
                val orbColor = json.optString("orb_color").ifBlank { cfg?.orbColor ?: "#7c8cff" }
                val codecName = json.optString("audio_codec")
                if (codecName.isNotBlank()) activeCodec = AudioCodecType.fromName(codecName)

                val incoming = ActiveCallUi(
                    callId = callId,
                    callerName = callerName,
                    orbColor = orbColor,
                    phase = CallPhase.RINGING,
                    startedAtMs = System.currentTimeMillis(),
                )
                CloodSessionBus.publishCall(incoming)
                acquireWakeLock()
                audioEngine.startRingtone(store.loadRingtoneStream())
                presentIncomingCall(incoming)
            }
            "call_ended" -> {
                val callId = json.optString("call_id")
                val current = CloodSessionBus.activeCall
                if (current != null && (callId.isBlank() || current.callId == callId)) {
                    teardownCall(notifyPc = false)
                }
            }
            "unpaired" -> {
                store.clear()
                teardownCall(notifyPc = false)
                socket?.close(1000, "Unpaired")
                CloodSessionBus.publishConnection(ConnectionState.UNPAIRED)
                stopSelf()
            }
        }
    }

    /**
     * Prefer turning the screen straight into [CallActivity] without a duplicate heads-up banner:
     * - When screen is off/locked: post the full-screen-intent notification so Android wakes & opens
     *   [CallActivity]; as soon as [CallActivity.onResume] fires, it dismisses the heads-up notification.
     * - When screen is awake & unlocked: launch [CallActivity] directly first; only fall back to the
     *   notification if Android blocked background activity launch after a brief grace window.
     */
    private fun presentIncomingCall(incoming: ActiveCallUi) {
        clearPendingIncomingNotif()
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(KeyguardManager::class.java)
        val screenOffOrLocked = pm?.isInteractive != true || km?.isKeyguardLocked == true

        if (screenOffOrLocked) {
            postIncomingCallNotification(incoming)
            launchCallActivity(incoming)
        } else {
            launchCallActivity(incoming)
            val fallback = Runnable {
                val current = CloodSessionBus.activeCall
                if (current?.callId == incoming.callId &&
                    current.phase == CallPhase.RINGING &&
                    !CallActivity.isForeground
                ) {
                    postIncomingCallNotification(current)
                }
            }
            pendingIncomingNotifRunnable = fallback
            mainHandler.postDelayed(fallback, 220L)
        }
    }

    private fun clearPendingIncomingNotif() {
        pendingIncomingNotifRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingIncomingNotifRunnable = null
    }

    private fun handleServerAudio(bytes: ByteArray) {
        val current = CloodSessionBus.activeCall ?: return
        if (current.phase != CallPhase.ACTIVE) return
        val decoded = AudioCodec.decodeWireFrame(bytes) ?: return
        audioEngine.playSamples(decoded.samples)
        val speaking = decoded.level > 0.03f
        CloodSessionBus.publishAudioLevel(speaking, decoded.level)
        mainHandler.removeCallbacks(silenceReset)
        if (speaking) {
            mainHandler.postDelayed(silenceReset, 340L)
        }
    }

    private fun answerCall(callId: String) {
        val current = CloodSessionBus.activeCall ?: return
        if (current.callId != callId || current.phase != CallPhase.RINGING) return

        clearPendingIncomingNotif()
        cancelIncomingCallNotification()
        audioEngine.stopRingtone()
        releaseWakeLock()

        val btAvailable = audioEngine.isBluetoothAvailable()
        val active = current.copy(
            phase = CallPhase.ACTIVE,
            answeredAtMs = System.currentTimeMillis(),
            bluetoothAvailable = btAvailable,
        )
        CloodSessionBus.publishCall(active)
        syncProximityWakeLock(active)

        socket?.send(
            JSONObject()
                .put("type", "call_accept")
                .put("call_id", callId)
                .toString(),
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            startMinimalForeground(includeMic = true)
        }

        audioEngine.startCallAudio(activeCodec, active.audioRoute) { encodedFrame ->
            socket?.send(encodedFrame.toByteString(0, encodedFrame.size))
        }

        if (!CallActivity.isForeground) {
            launchCallActivity(active)
        }
    }

    private fun declineCall(callId: String) {
        socket?.send(
            JSONObject()
                .put("type", "call_decline")
                .put("call_id", callId)
                .toString(),
        )
        teardownCall(notifyPc = false)
    }

    private fun teardownCall(notifyPc: Boolean, hangupReason: String? = null) {
        val current = CloodSessionBus.activeCall
        if (notifyPc && current != null) {
            socket?.send(
                buildHangupPayload(
                    callId = current.callId,
                    isRinging = current.phase == CallPhase.RINGING,
                    reason = hangupReason,
                ),
            )
        }
        clearPendingIncomingNotif()
        mainHandler.removeCallbacks(silenceReset)
        audioEngine.stopRingtone()
        audioEngine.stopCallAudio()
        cancelIncomingCallNotification()
        releaseWakeLock()
        releaseProximityWakeLock()
        if (micFgsActive) {
            startMinimalForeground(includeMic = false)
        }
        CloodSessionBus.publishAudioLevel(false, 0f)
        CloodSessionBus.publishCall(null)
    }

    private fun postIncomingCallNotification(call: ActiveCallUi) {
        val fullScreenIntent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CALL_ID, call.callId)
        }
        val fullScreenPending = PendingIntent.getActivity(
            this,
            101,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val answerPending = PendingIntent.getBroadcast(
            this,
            102,
            Intent(this, CallActionReceiver::class.java).apply {
                action = ACTION_ANSWER
                putExtra(EXTRA_CALL_ID, call.callId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val declinePending = PendingIntent.getBroadcast(
            this,
            103,
            Intent(this, CallActionReceiver::class.java).apply {
                action = ACTION_DECLINE
                putExtra(EXTRA_CALL_ID, call.callId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val caller = Person.Builder()
            .setName(call.callerName)
            .setImportant(true)
            .build()

        val notif = NotificationCompat.Builder(this, CloodApp.CHANNEL_INCOMING_CALL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(call.callerName)
            .setContentText(getString(R.string.notif_call_text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreenPending)
            .setFullScreenIntent(fullScreenPending, true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declinePending, answerPending))
            .build()

        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID_INCOMING_CALL, notif)
    }

    private fun cancelIncomingCallNotification() {
        getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID_INCOMING_CALL)
    }

    private fun launchCallActivity(call: ActiveCallUi) {
        val intent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CALL_ID, call.callId)
        }
        runCatching { startActivity(intent) }
    }

    private fun buildServiceNotification(): Notification {
        val openPending = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CloodApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_service_text))
            .setContentIntent(openPending)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    private fun acquireWakeLock() {
        releaseWakeLock()
        val pm = getSystemService(PowerManager::class.java) ?: return
        @Suppress("DEPRECATION")
        ringWakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "clood:incoming_call",
        ).apply {
            acquire(60_000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (ringWakeLock?.isHeld == true) ringWakeLock?.release()
        }
        ringWakeLock = null
    }

    private fun syncProximityWakeLock(call: ActiveCallUi?) {
        val shouldHold = call != null &&
            call.phase == CallPhase.ACTIVE &&
            call.audioRoute == AudioRoute.EARPIECE
        if (shouldHold) {
            acquireProximityWakeLock()
        } else {
            releaseProximityWakeLock()
        }
    }

    private fun acquireProximityWakeLock() {
        if (proximityWakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        runCatching {
            val lock = proximityWakeLock ?: pm.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "clood:proximity_screen_off",
            ).also { proximityWakeLock = it }
            if (!lock.isHeld) {
                lock.acquire()
            }
        }
    }

    private fun releaseProximityWakeLock() {
        runCatching {
            proximityWakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
                }
            }
        }
        proximityWakeLock = null
    }

    companion object {
        const val ACTION_RECONNECT = "dev.slop.clood.RECONNECT"
        const val ACTION_DISCONNECT = "dev.slop.clood.DISCONNECT"
        const val ACTION_ANSWER = "dev.slop.clood.ANSWER"
        const val ACTION_DECLINE = "dev.slop.clood.DECLINE"
        const val ACTION_HANGUP = "dev.slop.clood.HANGUP"
        const val ACTION_HANGUP_REDIAL = "dev.slop.clood.HANGUP_REDIAL"
        const val ACTION_HANGUP_CANCEL = "dev.slop.clood.HANGUP_CANCEL"
        const val HANGUP_REASON_REDIAL_10M = "redial_10m"
        const val HANGUP_REASON_CANCEL = "cancel"
        const val ACTION_REPOST_RINGING_NOTIF = "dev.slop.clood.REPOST_RINGING_NOTIF"
        const val ACTION_TOGGLE_MUTE = "dev.slop.clood.TOGGLE_MUTE"
        const val ACTION_TOGGLE_SPEAKER = "dev.slop.clood.TOGGLE_SPEAKER"
        const val ACTION_TOGGLE_BLUETOOTH = "dev.slop.clood.TOGGLE_BLUETOOTH"
        const val EXTRA_CALL_ID = "call_id"

        private const val NOTIF_ID_SERVICE = 1001
        private const val NOTIF_ID_INCOMING_CALL = 1002

        fun dismissIncomingNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID_INCOMING_CALL)
        }

        internal fun buildHangupPayload(
            callId: String,
            isRinging: Boolean,
            reason: String? = null,
        ): String {
            val type = if (isRinging) "call_decline" else "hangup"
            val json = JSONObject()
                .put("type", type)
                .put("call_id", callId)
            if (!isRinging && !reason.isNullOrBlank()) {
                json.put("reason", reason)
            }
            return json.toString()
        }

        fun start(context: Context, action: String? = null, callId: String? = null) {
            val intent = Intent(context, CloodConnectionService::class.java).apply {
                if (action != null) this.action = action
                if (callId != null) putExtra(EXTRA_CALL_ID, callId)
            }
            context.startForegroundService(intent)
        }
    }
}
