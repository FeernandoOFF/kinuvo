package kinuvo.server

import arrow.core.raise.Raise
import kinuvo.protocol.device.DeviceToolKind
import kinuvo.protocol.device.PairedDeviceInfo
import kinuvo.protocol.execution.RemoteExecutionCall
import kinuvo.protocol.execution.RemoteExecutionResult
import kinuvo.protocol.push.PushSubscription
import kinuvo.protocol.surface.UiCatalog
import kinuvo.protocol.voice.LiveVoiceCall
import kinuvo.protocol.voice.LiveVoiceParams
import kinuvo.server.screens.Screens

/** The connection a request arrived on: its credential, subscriptions and per-connection state. */
interface Caller {
    val management: Boolean
    val linked: Boolean
    val browser: Boolean
    val deviceId: String?
    val subscriber: SessionLog.Subscriber
    val screens: Screens

    /** The Surface catalogs this connection renders, for runs it starts. */
    val surfaceCatalogs: List<UiCatalog>

    fun requireDevice(): String
    fun setSurfaceCatalogs(catalogs: List<UiCatalog>)
    suspend fun presence(active: Boolean)
    suspend fun setDeviceTools(tools: Set<DeviceToolKind>)
    fun setPush(subscription: PushSubscription?)
    fun pairedDevices(): List<PairedDeviceInfo>
    context(_: Raise<AccessError>, _: Raise<DeviceError>)
    fun revokeDevice(id: String, requestId: String)
    context(_: Raise<DeviceError>)
    fun rotateToken(): String
    fun unwatchScreen(id: String)
    context(_: Raise<VoiceError>, _: Raise<AgentError>)
    suspend fun startLiveVoice(params: LiveVoiceParams): LiveVoiceCall
    fun endLiveVoice(callId: String)
    context(_: Raise<ExecutionError>)
    suspend fun remoteExecution(call: RemoteExecutionCall): RemoteExecutionResult
}
