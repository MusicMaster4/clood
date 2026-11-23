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
 * Full-duplex 24 kHz voice engine (with hardware echo cancellation) + native system ringtone.
 */
class CallAudioEngine(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private var ringtonePlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    private val running = AtomicBoolean(false)
    @Volatile private var muted = false
    @Volatile private var speakerOn = true

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

    @SuppressLint("MissingPermission")
    fun startCallAudio(codec: AudioCodecType, onEncodedMicFrame: (ByteArray) -> Unit) {
        stopRingtone()
        stopCallAudio()

        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        applySpeakerRouting(speakerOn)

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

    fun setSpeaker(enabled: Boolean) {
        speakerOn = enabled
        applySpeakerRouting(enabled)
    }

    private fun applySpeakerRouting(enabled: Boolean) {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val targetType = if (enabled) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            } else {
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
            val device = am.availableCommunicationDevices.firstOrNull { it.type == targetType }
            if (device != null) {
                am.setCommunicationDevice(device)
            }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = enabled
        }
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
        }
        runCatching { audioManager?.mode = AudioManager.MODE_NORMAL }
    }

    companion object {
        private const val SAMPLE_RATE = 24000
        private const val FRAME_SAMPLES = 480 // 20 ms
    }
}
