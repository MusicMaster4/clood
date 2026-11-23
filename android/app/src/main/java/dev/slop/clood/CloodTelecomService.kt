package dev.slop.clood

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.OutcomeReceiver
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

class CloodTelecomService : ConnectionService() {
    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection = takePendingCall(request) ?: Connection.createCanceledConnection()

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ) {
        takePendingCall(request)?.onReject()
    }

    companion object {
        // Both services run on the main thread. Clood supports one incoming call at a time.
        private var pendingCall: CloodTelecomCall? = null

        internal fun reportIncomingCall(context: Context, call: CloodTelecomCall): Boolean {
            val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
            val handle = PhoneAccountHandle(ComponentName(context, CloodTelecomService::class.java), "clood")
            return runCatching {
                telecom.registerPhoneAccount(
                    PhoneAccount.builder(handle, context.getString(R.string.app_name))
                        .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                        .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_SIP))
                        .build(),
                )
                pendingCall = call
                telecom.addNewIncomingCall(handle, Bundle().apply {
                    putString(CloodConnectionService.EXTRA_CALL_ID, call.callId)
                    putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, call.address)
                })
                true
            }.getOrElse {
                clearPendingCall(call)
                Log.w("CloodTelecom", "Could not report the incoming call to Android Telecom", it)
                false
            }
        }

        internal fun clearPendingCall(call: CloodTelecomCall) {
            if (pendingCall === call) pendingCall = null
        }

        private fun takePendingCall(request: ConnectionRequest): CloodTelecomCall? {
            val call = pendingCall ?: return null
            if (request.extras?.getString(CloodConnectionService.EXTRA_CALL_ID) != call.callId) return null
            pendingCall = null
            return call
        }
    }
}

internal class CloodTelecomCall(
    call: ActiveCallUi,
    private val executor: Executor,
    private val showIncomingCall: () -> Unit,
    private val answer: () -> Unit,
    private val reject: () -> Unit,
    private val disconnect: () -> Unit,
    private val silence: () -> Unit,
    private val audioRouteChanged: (AudioRoute?, Boolean) -> Unit,
) : Connection() {
    val callId = call.callId
    private var endpoints = emptyList<CallEndpoint>()
    private var needsInitialRoute = true

    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        audioModeIsVoip = true
        setAddress(Uri.fromParts(PhoneAccount.SCHEME_SIP, "clood", null), TelecomManager.PRESENTATION_ALLOWED)
        setCallerDisplayName(call.callerName, TelecomManager.PRESENTATION_ALLOWED)
        setRinging()
    }

    override fun onShowIncomingCallUi() = showIncomingCall()
    override fun onAnswer() = answer()
    override fun onAnswer(videoState: Int) = answer()
    override fun onReject() = reject()
    override fun onReject(rejectReason: Int) = reject()
    override fun onReject(replyMessage: String?) = reject()
    override fun onAbort() = disconnect()
    override fun onDisconnect() = disconnect()
    override fun onSilence() = silence()

    fun activate() {
        setActive()
        applyInitialRoute()
    }

    fun finish(cause: Int) {
        CloodTelecomService.clearPendingCall(this)
        setDisconnected(DisconnectCause(cause))
        destroy()
    }

    @Suppress("DEPRECATION")
    fun selectRoute(route: AudioRoute) {
        needsInitialRoute = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val endpointType = when (route) {
                AudioRoute.EARPIECE -> CallEndpoint.TYPE_EARPIECE
                AudioRoute.SPEAKER -> CallEndpoint.TYPE_SPEAKER
                AudioRoute.BLUETOOTH -> CallEndpoint.TYPE_BLUETOOTH
            }
            val endpoint = endpoints.firstOrNull { it.endpointType == endpointType } ?: return
            requestCallEndpointChange(endpoint, executor, object : OutcomeReceiver<Void?, CallEndpointException> {
                override fun onResult(result: Void?) = Unit

                override fun onError(error: CallEndpointException) {
                    Log.w("CloodTelecom", "Could not switch call audio to ${route.name}", error)
                }
            })
        } else {
            setAudioRoute(when (route) {
                AudioRoute.EARPIECE -> CallAudioState.ROUTE_EARPIECE
                AudioRoute.SPEAKER -> CallAudioState.ROUTE_SPEAKER
                AudioRoute.BLUETOOTH -> CallAudioState.ROUTE_BLUETOOTH
            })
        }
    }

    private fun applyInitialRoute() {
        if (state == STATE_ACTIVE && needsInitialRoute &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || endpoints.isNotEmpty())
        ) {
            selectRoute(AudioRoute.EARPIECE)
        }
    }

    @Deprecated("Telecom reports call endpoints on Android 14 and newer")
    override fun onCallAudioStateChanged(state: CallAudioState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val route = when (state.route) {
            CallAudioState.ROUTE_EARPIECE -> AudioRoute.EARPIECE
            CallAudioState.ROUTE_SPEAKER -> AudioRoute.SPEAKER
            CallAudioState.ROUTE_BLUETOOTH -> AudioRoute.BLUETOOTH
            else -> null
        }
        audioRouteChanged(route, state.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH != 0)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) {
        endpoints = availableEndpoints
        applyInitialRoute()
        publishEndpoint(currentCallEndpoint)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        publishEndpoint(callEndpoint)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun publishEndpoint(endpoint: CallEndpoint?) {
        val route = when (endpoint?.endpointType) {
            CallEndpoint.TYPE_EARPIECE -> AudioRoute.EARPIECE
            CallEndpoint.TYPE_SPEAKER -> AudioRoute.SPEAKER
            CallEndpoint.TYPE_BLUETOOTH -> AudioRoute.BLUETOOTH
            else -> null
        }
        audioRouteChanged(route, endpoints.any { it.endpointType == CallEndpoint.TYPE_BLUETOOTH })
    }
}
