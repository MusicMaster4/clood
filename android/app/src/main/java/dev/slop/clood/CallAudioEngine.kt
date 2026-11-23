package dev.slop.clood

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Full-duplex 24 kHz voice engine (with hardware echo cancellation),
 * 3-way audio routing (Bluetooth / Speaker / Earpiece), and native system ringtone.
 */
class CallAudioEngine(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private var ringtonePlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    private val running = AtomicBoolean(false)
    @Volatile private var muted = false
    @Volatile private var currentRoute: AudioRoute = AudioRoute.SPEAKER

    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var recordThread: Thread? = null

    fun startRingtone() {
        stopRingtone()
        val mode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            runCatching {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                if (uri != null) {
                    ringtonePlayer = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build(),
                        )
                        setDataSource(context, uri)
                        isLooping = true
                        prepare()
                        start()
                    }
                }
            }
        }

        if (mode != AudioManager.RINGER_MODE_SILENT) {
            runCatching {
                val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                vibrator = vib
                vib?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0L, 650L, 900L), 0),
                )
            }
        }
    }

    fun stopRingtone() {
        runCatching {
            ringtonePlayer?.stop()
            ringtonePlayer?.release()
        }
        ringtonePlayer = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    fun isBluetoothAvailable(): Boolean {
        val am = audioManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                am.availableCommunicationDevices.any { isBluetoothCommDevice(it) }
            }.getOrDefault(false)
        } else {
            runCatching {
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                }
            }.getOrDefault(false)
        }
    }

    private fun isBluetoothCommDevice(device: AudioDeviceInfo): Boolean {
        if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        ) {
            return true
        }
        return false
    }

    @SuppressLint("MissingPermission")
    fun startCallAudio(
        codec: AudioCodecType,
        initialRoute: AudioRoute,
        onEncodedMicFrame: (ByteArray) -> Unit,
    ) {
        stopRingtone()
        stopCallAudio()

        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        setAudioRoute(initialRoute)

        val minPlayBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minPlayBuf, FRAME_SAMPLES * 2 * 4))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track.play()
        audioTrack = track

        val minRecBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minRecBuf, FRAME_SAMPLES * 2 * 4),
        )

        val sessionId = record.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            aec = runCatching { AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = runCatching { NoiseSuppressor.create(sessionId)?.apply { enabled = true } }.getOrNull()
        }
        if (AutomaticGainControl.isAvailable()) {
            agc = runCatching { AutomaticGainControl.create(sessionId)?.apply { enabled = true } }.getOrNull()
        }

        audioRecord = record
        running.set(true)
        record.startRecording()

        recordThread = thread(name = "clood-mic", priority = Thread.MAX_PRIORITY) {
            val buf = ShortArray(FRAME_SAMPLES)
            while (running.get()) {
                val read = record.read(buf, 0, FRAME_SAMPLES)
                if (read > 0 && !muted) {
                    val encoded = AudioCodec.encodeWireFrame(buf, read, codec)
                    onEncodedMicFrame(encoded)
                }
            }
        }
    }

    fun playSamples(samples: ShortArray) {
        if (!running.get() || samples.isEmpty()) return
        runCatching {
            audioTrack?.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        }
    }

    fun setMuted(value: Boolean) {
        muted = value
    }

    fun setAudioRoute(route: AudioRoute): AudioRoute {
        val am = audioManager ?: return route
        val effectiveRoute = if (route == AudioRoute.BLUETOOTH && !isBluetoothAvailable()) {
            AudioRoute.EARPIECE
        } else {
            route
        }
        currentRoute = effectiveRoute

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val devices = am.availableCommunicationDevices
                val target = when (effectiveRoute) {
                    AudioRoute.BLUETOOTH -> devices.firstOrNull { isBluetoothCommDevice(it) }
                    AudioRoute.SPEAKER -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    AudioRoute.EARPIECE -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                }
                if (target != null) {
                    am.setCommunicationDevice(target)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                when (effectiveRoute) {
                    AudioRoute.BLUETOOTH -> {
                        am.isSpeakerphoneOn = false
                        am.startBluetoothSco()
                        am.isBluetoothScoOn = true
                    }
                    AudioRoute.SPEAKER -> {
                        am.stopBluetoothSco()
                        am.isBluetoothScoOn = false
                        am.isSpeakerphoneOn = true
                    }
                    AudioRoute.EARPIECE -> {
                        am.stopBluetoothSco()
                        am.isBluetoothScoOn = false
                        am.isSpeakerphoneOn = false
                    }
                }
            }
        }
        return effectiveRoute
    }

    fun stopCallAudio() {
        running.set(false)
        runCatching { recordThread?.join(250) }
        recordThread = null

        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null

        runCatching { aec?.release() }
        runCatching { ns?.release() }
        runCatching { agc?.release() }
        aec = null
        ns = null
        agc = null

        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
        runCatching { audioTrack?.release() }
        audioTrack = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager?.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager?.stopBluetoothSco()
                audioManager?.isBluetoothScoOn = false
                audioManager?.isSpeakerphoneOn = false
            }
        }
        runCatching { audioManager?.mode = AudioManager.MODE_NORMAL }
    }

    companion object {
        private const val SAMPLE_RATE = 24000
        private const val FRAME_SAMPLES = 480 // 20 ms
    }
}
