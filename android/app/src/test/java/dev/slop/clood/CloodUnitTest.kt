package dev.slop.clood

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class CloodUnitTest {
    @Test
    fun tailscaleIpValidationRestrictsToCgnat() {
        assertTrue(TailscaleNet.isTailscaleIpv4("100.64.0.1"))
        assertTrue(TailscaleNet.isTailscaleIpv4("100.113.41.57"))
        assertTrue(TailscaleNet.isTailscaleIpv4("100.127.255.254"))
        assertFalse(TailscaleNet.isTailscaleIpv4("100.63.255.255"))
        assertFalse(TailscaleNet.isTailscaleIpv4("100.128.0.1"))
        assertFalse(TailscaleNet.isTailscaleIpv4("192.168.1.10"))
        assertFalse(TailscaleNet.isTailscaleIpv4("8.8.8.8"))
    }

    @Test
    fun pairingUriParsesValidTailscaleUriAndRejectsNonTailscale() {
        val ok = TailscaleNet.parsePairingUri(
            "clood://pair?host=100.113.41.57&port=19480&token=abcdef1234567890&name=Clood&color=%237c8cff&codec=adpcm_24k",
        )
        assertNotNull(ok)
        assertEquals("100.113.41.57", ok!!.host)
        assertEquals(19480, ok.port)
        assertEquals("#7c8cff", ok.orbColor)
        assertEquals(AudioCodecType.ADPCM_24K, ok.codec)

        val bad = TailscaleNet.parsePairingUri(
            "clood://pair?host=192.168.1.10&port=19480&token=abcdef1234567890",
        )
        assertNull(bad)
    }

    @Test
    fun audioCodecsRoundTripCleanly() {
        val sine = ShortArray(480) { i ->
            (sin(i * 440.0 * 2.0 * PI / 24000.0) * 14000.0).toInt().toShort()
        }

        val adpcmFrame = AudioCodec.encodeWireFrame(sine, sine.size, AudioCodecType.ADPCM_24K)
        val decodedAdpcm = AudioCodec.decodeWireFrame(adpcmFrame)!!
        assertEquals(sine.size, decodedAdpcm.samples.size)
        val adpcmErr = sine.indices.maxOf { abs(sine[it].toInt() - decodedAdpcm.samples[it].toInt()) }
        assertTrue("ADPCM error too high: $adpcmErr", adpcmErr < 1600)

        val ulawFrame = AudioCodec.encodeWireFrame(sine, sine.size, AudioCodecType.ULAW_24K)
        val decodedUlaw = AudioCodec.decodeWireFrame(ulawFrame)!!
        assertEquals(sine.size, decodedUlaw.samples.size)
        val ulawErr = sine.indices.maxOf { abs(sine[it].toInt() - decodedUlaw.samples[it].toInt()) }
        assertTrue("u-law error too high: $ulawErr", ulawErr < 800)
    }

    @Test
    fun updateManifestRejectsNonGithubAndMatchesChannels() {
        val valid = """
            {
              "schemaVersion": 1,
              "channel": "testing",
              "versionName": "1.0.1-testing.1",
              "versionCode": 42,
              "apkUrl": "https://github.com/MusicMaster4/clood/releases/download/v1.0.1-testing.1/clood-beta.apk",
              "sha256": "${"a".repeat(64)}"
            }
        """.trimIndent()
        val parsed = AndroidUpdateManifest.fromJson(valid)
        assertEquals("testing", parsed.channel)
        assertTrue(parsed.isNewerThan(41))
        assertFalse(parsed.isNewerThan(42))
        assertTrue(UpdateClient.manifestUrl("testing").contains("channel-testing"))
        assertTrue(UpdateClient.manifestUrl("stable").contains("/releases/latest/download/"))
    }
}
