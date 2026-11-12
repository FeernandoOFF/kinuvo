package kinuvo.protocol.execution

import kinuvo.protocol.approval.InteractionId
import kinuvo.protocol.agent.AgentInfo
import kinuvo.protocol.approval.InteractionAnswer
import kinuvo.protocol.approval.PendingInteraction
import kinuvo.protocol.surface.SurfaceAction
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class ExecutionKind { Native, Acp, Remote }

@Serializable
sealed interface ExecutionConnection {
    /** The Server's own harness; it has nothing to configure, so it is never saved or sent. */
    @Serializable @SerialName("builtin")
    data object BuiltIn : ExecutionConnection

    @Serializable @SerialName("kinuvo.protocol.ExecutionConnection.Acp")
    data class Acp(val command: List<String>, val environment: Map<String, String> = emptyMap()) : ExecutionConnection
    /** Credentials stay in the Server's paired connection machinery. */
    @Serializable @SerialName("kinuvo.protocol.ExecutionConnection.Remote")
    data class Remote(val serverId: String) : ExecutionConnection
}

@Serializable
data class HarnessConnection(val id: String, val name: String, val connection: ExecutionConnection, val host: String? = null)

@Serializable
data class AgentRegistration(val id: AgentId, val name: String, val connectionId: String, val avatar: String? = null, val cwd: String? = null, val remoteAgentId: String? = null, val archived: Boolean = false)

@Serializable
data class ExecutionReadiness(val configured: Boolean, val online: Boolean, val reason: String? = null, val reasonCode: String? = null)

@Serializable
data class ExecutionCapabilities(val resume: Boolean = false, val cancel: Boolean = false, val questions: Boolean = false, val approvals: Boolean = false, val supportedMimeTypes: Set<String> = emptySet())

@Serializable
data class HarnessConnectionStatus(val config: HarnessConnection, val readiness: ExecutionReadiness, val capabilities: ExecutionCapabilities = ExecutionCapabilities())

@Serializable
data class ExecutionCatalog(val connections: List<HarnessConnectionStatus>, val agents: List<AgentRegistration>)

@Serializable
data class ExecutionRef(val id: String)

@Serializable
enum class ExecutionPhase { Idle, Running, RequiresAction, Lost }

/** Complete keyed messages, rather than append-only deltas, make replay idempotent. */
@Serializable
data class ExecutionMessage(val id: String, val role: String, val text: String, val payload: JsonElement? = null)

@Serializable
data class ExecutionSnapshot(
    val sessionId: SessionId,
    val phase: ExecutionPhase = ExecutionPhase.Idle,
    val messages: List<ExecutionMessage> = emptyList(),
    val pending: List<PendingInteraction> = emptyList(),
    val stopReason: String? = null,
    val externalSessionId: String? = null,
)

@Serializable
data class ExternalApprovalOption(val id: String, val label: String, val kind: String)

@Serializable
data class ExecutionProbe(val readiness: ExecutionReadiness, val title: String? = null, val version: String? = null, val authenticated: Boolean? = null, val authMethods: JsonElement? = null)

/** Input-only link credential; never part of ExecutionCatalog. */
@Serializable
data class SaveRemoteLink(val id: String, val url: String, val token: String)
@Serializable
data class ExposureGrant(val id: String, val agentIds: Set<AgentId>)
@Serializable
data class RemoteExecutionCall(val agentId: AgentId? = null, val sessionId: SessionId? = null, val action: String = "list", val text: String? = null, val requestId: InteractionId? = null, val answer: InteractionAnswer? = null, val surfaceAction: SurfaceAction? = null)
@Serializable
data class RemoteExecutionResult(val agents: List<AgentInfo> = emptyList(), val snapshot: ExecutionSnapshot? = null, val accepted: Boolean = true)
