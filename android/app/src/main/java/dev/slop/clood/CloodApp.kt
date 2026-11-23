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

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            "Clood Connection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }

        val callChannel = NotificationChannel(
            CHANNEL_INCOMING_CALL,
            "Incoming Clood Calls",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
            // Sound and vibration are controlled directly by CallAudioEngine using the system ringtone.
            setSound(null, null)
            enableVibration(false)
        }

        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(callChannel)
    }

    companion object {
        const val CHANNEL_SERVICE = "clood_service"
        const val CHANNEL_INCOMING_CALL = "clood_incoming_call"
    }
}
