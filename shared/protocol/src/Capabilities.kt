package kinuvo.protocol

import kotlinx.serialization.Serializable

@Serializable enum class Support { Supported, Unsupported, Unknown }
@Serializable enum class Availability { Ready, Unavailable, Loading }
@Serializable enum class Authorization { Allowed, NeedsAccess, Denied }
@Serializable enum class ExecutionLocation { ServerHost, Device, External }
@Serializable enum class ExecutionLifetime { Foreground, BackgroundBudget, Continuous }
@Serializable enum class WakeSupport { None, BestEffort, Scheduled }
@Serializable enum class ServerFeature { Attachments, Skills, Mcp, Knowledge, Tasks, Artifacts, Routines, DeviceTools, Shell, Pairing, Computer }

@Serializable
data class CapabilityState(
    val support: Support = Support.Supported,
    val availability: Availability = Availability.Ready,
    val authorization: Authorization = Authorization.Allowed,
    val reason: String? = null,
    val reasonCode: String? = null,
) {
    val actionable get() = support != Support.Unsupported && availability == Availability.Ready && authorization == Authorization.Allowed
    fun requireReady() { check(actionable) { reason ?: "This operation is unavailable" } }
    companion object {
        fun unsupported(reason: String) = CapabilityState(Support.Unsupported, reason = reason, reasonCode = "unsupported")
        fun unknown(reason: String = "Checking support") = CapabilityState(Support.Unknown, reason = reason, reasonCode = "unknown")
        fun unavailable(reason: String, code: String = "unavailable") = CapabilityState(availability = Availability.Unavailable, reason = reason, reasonCode = code)
    }
}

@Serializable
data class ServerCapabilities(
    val serverId: String,
    val features: Set<ServerFeature> = emptySet(),
    val lifetime: ExecutionLifetime = ExecutionLifetime.Foreground,
    val wake: WakeSupport = WakeSupport.None,
    val maxAttachments: Int = MAX_ATTACHMENTS,
    val maxAttachmentBytes: Int = MAX_ATTACHMENT_BYTES,
)

/** Whether the connected Server offers [feature]. */
fun ServerCapabilities?.supports(feature: ServerFeature) = this != null && feature in features

@Serializable
data class ResolvedTool(
    /** The model-facing alias; [identity] owns routing and policy. */
    val id: String,
    val name: String = id,
    val location: ExecutionLocation = ExecutionLocation.ServerHost,
    val target: String? = null,
    val state: CapabilityState = CapabilityState(),
    val identity: ToolIdentity? = null,
)

@Serializable
data class SessionCapabilities(
    val revision: Long = 0,
    val server: ServerCapabilities,
    val chat: CapabilityState = CapabilityState(),
    val inputs: Map<String, CapabilityState> = emptyMap(),
    val tools: List<ResolvedTool> = emptyList(),
) {
    fun input(kind: String) = inputs[kind] ?: CapabilityState.unsupported("This model does not accept $kind input")
}

@Serializable
data class CapabilitiesParams(val agentId: String, val sessionId: String? = null, val settings: SessionSettings? = null)
