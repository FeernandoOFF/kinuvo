package kinuvo.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class ExecutionKind { Native, Acp, Remote }

@Serializable
sealed interface ExecutionConnection {
    @Serializable
    data class Acp(val command: List<String>, val environment: Map<String, String> = emptyMap()) : ExecutionConnection
    /** Credentials stay in the Server's paired connection machinery. */
    @Serializable
    data class Remote(val serverId: String) : ExecutionConnection
}

@Serializable
data class HarnessConnection(val id: String, val name: String, val connection: ExecutionConnection, val host: String? = null)

@Serializable
data class AgentRegistration(val id: String, val name: String, val connectionId: String, val avatar: String? = null, val cwd: String? = null, val remoteAgentId: String? = null, val archived: Boolean = false)

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

@Serializable
enum class ExecutionRequestKind { Approval, Question }

@Serializable
data class ExecutionRequest(val id: String, val kind: ExecutionRequestKind, val payload: JsonElement, val options: List<ExternalApprovalOption> = emptyList(), val prompt: ExecutionPrompt? = null)

@Serializable
data class ExecutionAnswer(val sessionId: String, val requestId: String, val answer: JsonElement)

/** Complete keyed messages, rather than append-only deltas, make replay idempotent. */
@Serializable
data class ExecutionMessage(val id: String, val role: String, val text: String, val payload: JsonElement? = null)

@Serializable
data class ExecutionSnapshot(
    val sessionId: String,
    val phase: ExecutionPhase = ExecutionPhase.Idle,
    val messages: List<ExecutionMessage> = emptyList(),
    val pending: List<ExecutionRequest> = emptyList(),
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
data class ExposureGrant(val id: String, val agentIds: Set<String>)
@Serializable
data class RemoteExecutionCall(val agentId: String? = null, val sessionId: String? = null, val action: String = "list", val text: String? = null, val requestId: String? = null, val answer: JsonElement? = null)
@Serializable
data class RemoteExecutionResult(val agents: List<AgentInfo> = emptyList(), val snapshot: ExecutionSnapshot? = null, val accepted: Boolean = true)
