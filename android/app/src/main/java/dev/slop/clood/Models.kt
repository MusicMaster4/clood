package dev.slop.clood

import java.net.URI
import java.net.URLDecoder

enum class AudioCodecType(val wireId: Byte, val wireName: String) {
    ADPCM_24K(1, "adpcm_24k"),
    ULAW_24K(2, "ulaw_24k");

    companion object {
        fun fromName(name: String?): AudioCodecType =
            if (name.equals("ulaw_24k", ignoreCase = true)) ULAW_24K else ADPCM_24K
    }
}

enum class ConnectionState {
    UNPAIRED,
    CONNECTING,
    CONNECTED,
    OFFLINE,
}

enum class CallPhase {
    IDLE,
    RINGING,
    ACTIVE,
}

enum class AudioRoute {
    EARPIECE,
    SPEAKER,
    BLUETOOTH,
}

data class PairingConfig(
    val host: String,
    val port: Int,
    val token: String,
    val agentName: String = "Clood",
    val orbColor: String = "#7c8cff",
    val codec: AudioCodecType = AudioCodecType.ADPCM_24K,
)

data class ActiveCallUi(
    val callId: String,
    val callerName: String,
    val orbColor: String,
    val phase: CallPhase,
    val startedAtMs: Long,
    val answeredAtMs: Long? = null,
    val muted: Boolean = false,
    val audioRoute: AudioRoute = AudioRoute.SPEAKER,
    val bluetoothAvailable: Boolean = false,
)

object TailscaleNet {
    /** Strictly validates Tailscale CGNAT IPv4 range 100.64.0.0/10. */
    fun isTailscaleIpv4(host: String): Boolean {
        val parts = host.trim().split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 100 && (octets[1] and 0xC0) == 64
    }

    /** Parses `clood://pair?host=...&port=...&token=...&name=...&color=...&codec=...`. */
    fun parsePairingUri(raw: String): PairingConfig? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "clood" || uri.host != "pair") return null
        val query = uri.rawQuery ?: return null
        val params = mutableMapOf<String, String>()
        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
            val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            params[k] = v
        }
        val host = params["host"]?.trim() ?: return null
        val port = params["port"]?.toIntOrNull()?.takeIf { it in 1024..65535 } ?: return null
        val token = params["token"]?.trim()?.takeIf { it.length >= 8 } ?: return null
        if (!isTailscaleIpv4(host)) return null
        return PairingConfig(
            host = host,
            port = port,
            token = token,
            agentName = params["name"]?.takeIf { it.isNotBlank() } ?: "Clood",
            orbColor = params["color"]?.takeIf { it.startsWith("#") } ?: "#7c8cff",
            codec = AudioCodecType.fromName(params["codec"]),
        )
    }
}
