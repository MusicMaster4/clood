package dev.slop.clood

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val callId = intent.getStringExtra(CloodConnectionService.EXTRA_CALL_ID)
        CloodConnectionService.start(context, action, callId)
    }
}
