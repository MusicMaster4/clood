package dev.slop.clood

import android.content.Context

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("clood_config", Context.MODE_PRIVATE)

    fun load(): PairingConfig? {
        val host = prefs.getString(KEY_HOST, null) ?: return null
        val port = prefs.getInt(KEY_PORT, 0)
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        if (port !in 1024..65535 || token.isBlank() || !TailscaleNet.isTailscaleIpv4(host)) return null
        return PairingConfig(
            host = host,
            port = port,
            token = token,
            agentName = prefs.getString(KEY_NAME, "Clood") ?: "Clood",
            orbColor = prefs.getString(KEY_COLOR, "#7c8cff") ?: "#7c8cff",
            codec = AudioCodecType.fromName(prefs.getString(KEY_CODEC, "adpcm_24k")),
        )
    }

    fun save(config: PairingConfig) {
        prefs.edit()
            .putString(KEY_HOST, config.host)
            .putInt(KEY_PORT, config.port)
            .putString(KEY_TOKEN, config.token)
            .putString(KEY_NAME, config.agentName)
            .putString(KEY_COLOR, config.orbColor)
            .putString(KEY_CODEC, config.codec.wireName)
            .apply()
    }

    fun updateSyncedAppearance(agentName: String?, orbColor: String?, codecName: String?) {
        val editor = prefs.edit()
        if (!agentName.isNullOrBlank()) editor.putString(KEY_NAME, agentName)
        if (!orbColor.isNullOrBlank()) editor.putString(KEY_COLOR, orbColor)
        if (!codecName.isNullOrBlank()) editor.putString(KEY_CODEC, AudioCodecType.fromName(codecName).wireName)
        editor.apply()
    }

    fun loadRingtoneStream(): RingtoneStream =
        RingtoneStream.fromPref(prefs.getString(KEY_RINGTONE_STREAM, RingtoneStream.RINGTONE.prefValue))

    fun saveRingtoneStream(stream: RingtoneStream) {
        prefs.edit().putString(KEY_RINGTONE_STREAM, stream.prefValue).apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_HOST)
            .remove(KEY_PORT)
            .remove(KEY_TOKEN)
            .remove(KEY_NAME)
            .remove(KEY_COLOR)
            .remove(KEY_CODEC)
            .apply()
    }

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_NAME = "agent_name"
        private const val KEY_COLOR = "orb_color"
        private const val KEY_CODEC = "audio_codec"
        private const val KEY_RINGTONE_STREAM = "ringtone_stream"
    }
}
