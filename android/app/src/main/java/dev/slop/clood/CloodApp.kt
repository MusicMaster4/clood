package dev.slop.clood

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager

class CloodApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        // Remove legacy IMPORTANCE_LOW channel so upgrading installs immediately use the silent MIN channel.
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_SERVICE) }

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            getString(R.string.notif_service_channel),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.notif_service_desc)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }

        val callChannel = NotificationChannel(
            CHANNEL_INCOMING_CALL,
            getString(R.string.notif_call_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
            // Sound and vibration are controlled directly by CallAudioEngine using the selected stream.
            setSound(null, null)
            enableVibration(false)
        }

        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(callChannel)
    }

    companion object {
        private const val LEGACY_CHANNEL_SERVICE = "clood_service"
        const val CHANNEL_SERVICE = "clood_service_silent"
        const val CHANNEL_INCOMING_CALL = "clood_incoming_call"
    }
}
