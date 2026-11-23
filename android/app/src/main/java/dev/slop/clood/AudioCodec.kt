package dev.slop.clood

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Wire-compatible low-latency 24 kHz mono audio codecs matching `src-tauri/src/codec.rs`.
 * Frame layout: [codecId: u8, rmsLevel: u8, payload...]
 */
object AudioCodec {
    private val IMA_INDEX_TABLE = intArrayOf(
        -1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8,
    )

    private val IMA_STEP_TABLE = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66,
        73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449,
        494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272,
        2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493,
        10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
    )

    data class DecodedFrame(val samples: ShortArray, val level: Float)

    fun pcmRmsLevel(samples: ShortArray, count: Int = samples.size): Float {
        if (count <= 0) return 0f
        var sumSq = 0.0
        for (i in 0 until count) {
            val v = samples[i].toDouble() / 32768.0
            sumSq += v * v
        }
        val rms = sqrt(sumSq / count).toFloat()
        val scaled = ((rms - 0.008f) / 0.18f).coerceIn(0f, 1f)
        return scaled.toDouble().pow(0.65).toFloat()
    }

    fun encodeWireFrame(samples: ShortArray, count: Int, codec: AudioCodecType): ByteArray {
        val level = pcmRmsLevel(samples, count)
        val levelByte = (level * 255f).roundToInt().coerceIn(0, 255).toByte()
        return when (codec) {
            AudioCodecType.ADPCM_24K -> encodeAdpcmFrame(samples, count, levelByte)
            AudioCodecType.ULAW_24K -> {
                val out = ByteArray(2 + count)
                out[0] = codec.wireId
                out[1] = levelByte
                for (i in 0 until count) {
                    out[2 + i] = linearToUlaw(samples[i])
                }
                out
            }
        }
    }

    fun decodeWireFrame(frame: ByteArray): DecodedFrame? {
        if (frame.size < 2) return null
        val codecId = frame[0].toInt() and 0xFF
        val level = (frame[1].toInt() and 0xFF) / 255f
        val samples = when (codecId) {
            0 -> {
                val count = (frame.size - 2) / 2
                ShortArray(count) { i ->
                    val lo = frame[2 + i * 2].toInt() and 0xFF
                    val hi = frame[3 + i * 2].toInt()
                    ((hi shl 8) or lo).toShort()
                }
            }
            1 -> decodeAdpcmPayload(frame, 2) ?: return null
            2 -> ShortArray(frame.size - 2) { i -> ulawToLinear(frame[2 + i]) }
            else -> return null
        }
        return DecodedFrame(samples, level)
    }

    private fun encodeAdpcmFrame(samples: ShortArray, count: Int, levelByte: Byte): ByteArray {
        if (count <= 0) return byteArrayOf(AudioCodecType.ADPCM_24K.wireId, levelByte)
        val nibbleBytes = count / 2 // ceil((count - 1) / 2)
        val out = ByteArray(2 + 5 + nibbleBytes)
        out[0] = AudioCodecType.ADPCM_24K.wireId
        out[1] = levelByte

        var predictor = samples[0].toInt()
        var index = if (count > 1) {
            val diff = abs(samples[1].toInt() - predictor)
            val found = IMA_STEP_TABLE.indexOfFirst { it >= diff }
            if (found >= 0) found else 88
        } else {
            0
        }

        out[2] = (predictor and 0xFF).toByte()
        out[3] = ((predictor ushr 8) and 0xFF).toByte()
        out[4] = index.toByte()
        out[5] = (count and 0xFF).toByte()
        out[6] = ((count ushr 8) and 0xFF).toByte()

        var outPos = 7
        var hasLow = false
        var lowNibble = 0

        for (i in 1 until count) {
            val step = IMA_STEP_TABLE[index]
            var diff = samples[i].toInt() - predictor
            var nibble = 0
            if (diff < 0) {
                nibble = 8
                diff = -diff
            }
            var delta = step ushr 3
            if (diff >= step) {
                nibble = nibble or 4
                diff -= step
                delta += step
            }
            if (diff >= (step ushr 1)) {
                nibble = nibble or 2
                diff -= (step ushr 1)
                delta += (step ushr 1)
            }
            if (diff >= (step ushr 2)) {
                nibble = nibble or 1
                delta += (step ushr 2)
            }
            predictor = if ((nibble and 8) != 0) predictor - delta else predictor + delta
            predictor = predictor.coerceIn(-32768, 32767)
            index = (index + IMA_INDEX_TABLE[nibble]).coerceIn(0, 88)

            if (hasLow) {
                out[outPos++] = ((lowNibble and 0x0F) or ((nibble and 0x0F) shl 4)).toByte()
                hasLow = false
            } else {
                lowNibble = nibble and 0x0F
                hasLow = true
            }
        }
        if (hasLow && outPos < out.size) {
            out[outPos] = (lowNibble and 0x0F).toByte()
        }
        return out
    }

    private fun decodeAdpcmPayload(frame: ByteArray, offset: Int): ShortArray? {
        if (frame.size - offset < 5) return null
        val loP = frame[offset].toInt() and 0xFF
        val hiP = frame[offset + 1].toInt()
        var predictor = ((hiP shl 8) or loP).toShort().toInt()
        var index = (frame[offset + 2].toInt() and 0xFF).coerceIn(0, 88)
        val totalSamples = (frame[offset + 3].toInt() and 0xFF) or
            ((frame[offset + 4].toInt() and 0xFF) shl 8)
        if (totalSamples == 0) return ShortArray(0)

        val out = ShortArray(totalSamples)
        out[0] = predictor.toShort()
        var written = 1

        for (pos in (offset + 5) until frame.size) {
            val b = frame[pos].toInt() and 0xFF
            for (shift in intArrayOf(0, 4)) {
                if (written >= totalSamples) break
                val nibble = (b ushr shift) and 0x0F
                val step = IMA_STEP_TABLE[index]
                var delta = step ushr 3
                if ((nibble and 4) != 0) delta += step
                if ((nibble and 2) != 0) delta += step ushr 1
                if ((nibble and 1) != 0) delta += step ushr 2
                predictor = if ((nibble and 8) != 0) predictor - delta else predictor + delta
                predictor = predictor.coerceIn(-32768, 32767)
                index = (index + IMA_INDEX_TABLE[nibble]).coerceIn(0, 88)
                out[written++] = predictor.toShort()
            }
        }
        return out
    }

    private fun linearToUlaw(sample: Short): Byte {
        var pcm = sample.toInt()
        val sign = if (pcm < 0) {
            pcm = -pcm
            0x80
        } else {
            0
        }
        if (pcm > 32635) pcm = 32635
        pcm += 0x84
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && (pcm and mask) == 0) {
            exponent--
            mask = mask ushr 1
        }
        val mantissa = (pcm ushr (exponent + 3)) and 0x0F
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

    private fun ulawToLinear(ulawByte: Byte): Short {
        val u = ulawByte.toInt().inv() and 0xFF
        val sign = u and 0x80
        val exponent = (u ushr 4) and 0x07
        val mantissa = u and 0x0F
        var t = ((mantissa shl 3) + 0x84) shl exponent
        t -= 0x84
        return (if (sign != 0) -t else t).toShort()
    }
}
