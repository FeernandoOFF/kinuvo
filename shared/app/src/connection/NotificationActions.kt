package kinuvo.app.connection

import kinuvo.client.KinuvoClient
import kinuvo.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull

sealed interface NotificationAction {
    data class Reply(val text: String) : NotificationAction
    data object Allow : NotificationAction
    data object Deny : NotificationAction
}

/** A background action has its own connection, never the foreground Server's lifetime or selection. */
class NotificationActions(private val servers: Servers, private val device: Device, private val dispatcher: CoroutineDispatcher = Dispatchers.Default) {
    fun failure(serverId: String?): String =
        "Couldn't reach ${serverId?.let(servers::find)?.label ?: "Server"}, open Kinuvo"

    suspend fun perform(push: PushMessage, action: NotificationAction): Boolean = withTimeoutOrNull(20_000) {
        try {
            val server = push.serverId?.let(servers::find) ?: return@withTimeoutOrNull false
            when (action) {
                is NotificationAction.Reply -> require(push.kind == PushKind.Reply && !push.agentId.isNullOrBlank() && !push.sessionId.isNullOrBlank() && action.text.isNotBlank())
                else -> require(push.kind == PushKind.Approval && !push.approvalId.isNullOrBlank())
            }
            val endpoint = servers.endpoint(server)
            require(endpoint.token != null)
            val client = KinuvoClient(endpoint.url, device.name, token = endpoint.token, timeZone = device.timeZone, connection = endpoint.connection, dispatcher = dispatcher, registerDeviceTools = false)
            try {
                client.setActive(false)
                val connected = client.connect()
                require(connected.capabilities.serverId == push.serverId)
                when (action) {
                    is NotificationAction.Reply -> {
                        client.call(Api.Send, SendParams(requireNotNull(push.agentId), requireNotNull(push.sessionId), action.text))
                        true
                    }
                    else -> client.call(Api.Approve, ApprovalParams(requireNotNull(push.approvalId),
                        if (action == NotificationAction.Allow) ApprovalDecision.AllowOnce else ApprovalDecision.Deny()))
                }
            } finally { client.close() }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    } ?: false
}
