package kinuvo.app.connection

import kinuvo.client.KinuvoClient
import kinuvo.protocol.Api
import kinuvo.protocol.approval.ApprovalDecision
import kinuvo.protocol.approval.InteractionAnswer
import kinuvo.protocol.approval.RespondParams
import kinuvo.protocol.push.PushKind
import kinuvo.protocol.push.PushMessage
import kinuvo.protocol.session.SendParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull

sealed interface NotificationAction {
    data class Reply(val text: String) : NotificationAction
    data object Allow : NotificationAction
    data object Deny : NotificationAction
}

/** Answers a pushed Notification in the background, on its own short connection, never the foreground Server's lifetime, selection or Device tools. */
class NotificationActions(private val servers: Servers, private val device: Device, private val dispatcher: CoroutineDispatcher = Dispatchers.Default) {
    fun failure(serverId: String?): String =
        "Couldn't reach ${serverId?.let(servers::find)?.label ?: "Server"}, open Kinuvo"

    suspend fun perform(push: PushMessage, action: NotificationAction): Boolean = withTimeoutOrNull(TIMEOUT) {
        try {
            val server = push.serverId?.let(servers::find) ?: return@withTimeoutOrNull false
            val sessionId = push.sessionId
            when (action) {
                is NotificationAction.Reply -> require(push.kind == PushKind.Reply && push.agentId != null && sessionId != null && action.text.isNotBlank())
                else -> require(push.kind == PushKind.Approval && push.approvalId != null && sessionId != null)
            }
            val endpoint = servers.endpoint(server)
            require(endpoint.token != null)
            val client = KinuvoClient(endpoint.url, device.name, token = endpoint.token, timeZone = device.timeZone, connection = endpoint.connection,
                dispatcher = dispatcher, registerDeviceTools = false)
            try {
                client.setActive(false)
                require(client.connect().capabilities.serverId == push.serverId)
                when (action) {
                    is NotificationAction.Reply -> {
                        client.call(Api.Send, SendParams(requireNotNull(push.agentId), sessionId, action.text))
                        true
                    }
                    else -> client.call(Api.Respond, RespondParams(requireNotNull(sessionId), requireNotNull(push.approvalId),
                        InteractionAnswer.Approval(if (action == NotificationAction.Allow) ApprovalDecision.AllowOnce else ApprovalDecision.Deny())))
                }
            } finally { client.close() }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    } ?: false

    private companion object {
        const val TIMEOUT = 20_000L
    }
}
