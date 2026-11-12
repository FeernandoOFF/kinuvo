package kinuvo.protocol.push

import kotlinx.serialization.Serializable
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId

/** Where and how a Device receives Notifications: a Web Push [endpoint], its P-256 public key and auth secret (base64url). */
@Serializable
data class PushSubscription(val endpoint: String, val p256dh: String, val auth: String)

/** A null [subscription] stops pushes to this Device. */
@Serializable
data class PushParams(val subscription: PushSubscription? = null)

/** What a pushed Notification decrypts to on the Device; [serverId] is the Server that sent it. */
@Serializable
data class PushMessage(val title: String, val body: String, val agentId: AgentId? = null, val sessionId: SessionId? = null, val urgent: Boolean = false, val serverId: String? = null)
