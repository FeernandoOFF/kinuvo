package kinuvo.protocol

import kotlinx.serialization.Serializable

@Serializable enum class Support { Supported, Unsupported, Unknown }
@Serializable enum class Availability { Ready, Unavailable, Loading }
@Serializable enum class Authorization { Allowed, NeedsAccess, Denied }
@Serializable enum class ExecutionLocation { ServerHost, Device, External }
@Serializable enum class ExecutionLifetime { Foreground, BackgroundBudget, Continuous }
@Serializable enum class WakeSupport { None, BestEffort, Scheduled }
@Serializable enum class ServerFeature { Attachments, Skills, Mcp, Knowledge, Tasks, Artifacts, Routines, DeviceTools, Shell, Pairing, Computer, Browser, Screens }

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
    /**
     * The options this Server can run, by [Integration] name, so a client skips ones it doesn't know; null from a Server
     * that doesn't say.
     */
    val integrations: Map<String, Set<String>>? = null,
    val platform: Platform = Platform.Unknown,
    val voice: VoiceSetup = VoiceSetup(),
)

/**
 * Something Settings sets up, offered as options the Server can run: exec backends (host, container, ssh), MCP
 * transports (stdio, http), password manager schemes, provider IDs and built-in tool groups. A Server without an option can't set it up.
 */
@Serializable enum class Integration { Harness, Exec, Mcp, Secrets, Knowledge, Tasks, Search, Notifications, Tools }

object IntegrationOptions {
    const val EXEC_HOST = "host"
    const val EXEC_CONTAINER = "container"
    const val EXEC_SSH = "ssh"
    const val MCP_STDIO = "stdio"
    const val MCP_HTTP = "http"
}

/** Whether the connected Server offers [feature]. */
fun ServerCapabilities?.supports(feature: ServerFeature) = this != null && feature in features

/** Whether the connected Server can set up [integration], or its [option]; assumed when it doesn't say. */
fun ServerCapabilities?.offers(integration: Integration, option: String? = null): Boolean {
    val options = this?.integrations?.let { it[integration.name].orEmpty() } ?: return true
    return if (option == null) options.isNotEmpty() else option in options
}

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
    /** Accepted attachment MIME types, resolved from the model and provider's file handling. Null from older Servers. */
    val attachments: Map<String, CapabilityState>? = null,
) {
    fun input(kind: String) = inputs[kind] ?: CapabilityState.unsupported("This model does not accept $kind input")
    fun attachment(mimeType: String): CapabilityState = when {
        ServerFeature.Attachments !in server.features -> CapabilityState.unsupported("Attachments are not available on this Server")
        attachments != null -> attachments[mimeType] ?: CapabilityState.unsupported("This file type isn't supported by this provider and model")
        else -> input(if (mimeType.startsWith("image/")) "image" else "file")
    }
}

@Serializable
data class CapabilitiesParams(val agentId: String, val sessionId: String? = null, val settings: SessionSettings? = null)
