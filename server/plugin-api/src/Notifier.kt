package kinuvo.server.plugin

import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId

/** A way to reach the user when no client is watching, e.g. ntfy now, APNs later; set up in `config.yaml`. */
interface NotificationProvider : ConfigurableProvider {
    /** The provider with [options], whose values may hold `${…}` references; fails when a required one is missing. */
    fun configured(options: Map<String, String>, host: PluginHost): Notifier
}

fun interface Notifier {
    suspend fun send(notification: Notification)
}

/** [agentId] and [sessionId] say what it's about, so a client can open it. */
data class Notification(
    val title: String,
    val body: String,
    val agentId: AgentId? = null,
    val sessionId: SessionId? = null,
    val urgent: Boolean = false,
)
