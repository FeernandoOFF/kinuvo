package kinuvo.server

import arrow.core.getOrElse
import arrow.core.raise.Raise
import arrow.core.raise.context.either
import kinuvo.protocol.Frame
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.ProtocolVersion
import kinuvo.protocol.WireError
import kinuvo.protocol.WireErrorCode
import kinuvo.protocol.WireErrorReason
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.connection.ProtocolRange
import kinuvo.protocol.session.SessionId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The shown text of a refusal that needs management access. */
const val MANAGEMENT_REQUIRED = "This action requires management access"

/** An expected failure of a request. Services raise one per feature; the route registry sends it as a [WireError] with its [code]. */
interface ServerError {
    val code: String
    val message: String
    val data: JsonElement? get() = null
}

fun ServerError.toWireError() = WireError(code, message, data)

/** Carries a [ServerError] through a contract that can only throw, such as the harness SPIs and plugin callbacks. */
class ServerException(val error: ServerError) : IllegalStateException(error.message)

/** Runs [block] for a contract that can only throw: a raised error becomes a [ServerException]. */
inline fun <E : ServerError, A> throwing(block: context(Raise<E>) () -> A): A = either(block).getOrElse { throw ServerException(it) }

/** Bad params (including ones that fail to decode) are `invalid_params`; anything unexpected is `internal`. */
fun Throwable.toWireError(): WireError = when (this) {
    is ServerException -> error.toWireError()
    is IllegalArgumentException -> WireError(WireErrorCode.InvalidParams, message ?: toString())
    else -> WireError(WireErrorCode.Internal, message ?: toString())
}

fun Frame.Request.failure(error: ServerError) = Frame.Response(id, ok = false, error = error.toWireError())

sealed class AgentError(override val code: String, override val message: String) : ServerError {
    data class NotFound(val id: AgentId) : AgentError(WireErrorCode.NotFound, "No agent '$id'")
    data object ShellInUse : AgentError(WireErrorCode.Conflict, "Stop the agent's runs before changing where its shell runs")
    data object OnlyAgent : AgentError(WireErrorCode.Conflict, "Can't delete the only Agent")
    data class Running(val id: AgentId) : AgentError(WireErrorCode.Conflict, "Agent '$id' has an active run; stop it first")
}

sealed class SkillError(override val code: String, override val message: String) : ServerError {
    data class NotFound(val name: String) : SkillError(WireErrorCode.NotFound, "No skill '$name'")
    data class NotInScope(val name: String, val scope: String) : SkillError(WireErrorCode.NotFound, "No skill '$name' in $scope skills")
}

sealed class SessionError(override val code: String, override val message: String) : ServerError {
    data class NotFound(val id: SessionId) : SessionError(WireErrorCode.NotFound, "No session '$id'")
    data class NotOfAgent(val id: SessionId, val agentId: AgentId) : SessionError(WireErrorCode.NotFound, "No session '$id' for agent '$agentId'")
    data object Unknown : SessionError(WireErrorCode.NotFound, "Unknown Session")
    data object CannotResume : SessionError(WireErrorCode.Conflict, "This Session cannot resume")
}

sealed class RoutineError(override val code: String, override val message: String) : ServerError {
    data class NotFound(val id: String) : RoutineError(WireErrorCode.NotFound, "No routine '$id'")
    data object NoScheduler : RoutineError(WireErrorCode.Unavailable, "No server is running to run it; start one with `kinuvo serve` or the desktop app")
}

sealed class BrowserError(override val code: String, override val message: String) : ServerError {
    data object Unavailable : BrowserError(WireErrorCode.Unavailable, "Browser is unavailable on this Server")
    data object Managed : BrowserError(WireErrorCode.Unavailable, "Browser is managed by this Agent's harness and isn't available in Kinuvo")
    data object Disabled : BrowserError(WireErrorCode.Unavailable, "Browser is disabled for this Agent")
    data object UseScreens : BrowserError(WireErrorCode.Unavailable, "Use screens.control and frame-bound screens.input/command")
}

sealed class ComputerError(override val code: String, override val message: String) : ServerError {
    data object UseWatch : ComputerError(WireErrorCode.Unavailable, "Use bounded screens.watch")
    data object UseInput : ComputerError(WireErrorCode.Unavailable, "Use frame-bound screens.input")
    data object UseControl : ComputerError(WireErrorCode.Unavailable, "Use generation-bound screens.control")
}

sealed class DeviceError(override val code: String, override val message: String) : ServerError {
    data class NotFound(val id: String) : DeviceError(WireErrorCode.NotFound, "No paired Device '$id'")
    data object BrowserCredentials : DeviceError(WireErrorCode.Unavailable, "Rotate browser credentials over HTTPS")
    data object CannotPair : DeviceError(WireErrorCode.Unavailable, "This Server can't pair Devices; pair with one started by `kinuvo serve` or the desktop app")
}

sealed class VoiceError(override val code: String, override val message: String) : ServerError {
    data object NoDownloads : VoiceError(WireErrorCode.Unavailable, "Downloaded voice models are unavailable on this Server")
    data object NoTranscription : VoiceError(WireErrorCode.Unavailable, "Server transcription is not configured")
    data object NoSynthesis : VoiceError(WireErrorCode.Unavailable, "Server synthesis is not configured")
    data object NoLiveVoice : VoiceError(WireErrorCode.Unavailable, "Live voice is unavailable or disabled on this Server")
    data object RequestActive : VoiceError(WireErrorCode.Conflict, "Voice request is already active")
    data object CallActive : VoiceError(WireErrorCode.Conflict, "A live voice call is already active on this connection")
}

sealed class ProviderError(override val code: String, override val message: String) : ServerError {
    data object NoCustomEndpoints : ProviderError(WireErrorCode.Unavailable, "Custom providers are unavailable on this Server")
}

sealed class KnowledgeError(override val code: String, override val message: String) : ServerError {
    data class NoteNotFound(val ref: String) : KnowledgeError(WireErrorCode.NotFound, "No note '$ref'")
}

sealed class ExecutionError(override val code: String, override val message: String) : ServerError {
    data object NoRemoteServers : ExecutionError(WireErrorCode.Unavailable, "Remote Servers unavailable")
    data object ConnectionInUse : ExecutionError(WireErrorCode.Conflict, "Connection has live Sessions")
    data object AgentInUse : ExecutionError(WireErrorCode.Conflict, "Agent has live Sessions")
    data object NativeAgentId : ExecutionError(WireErrorCode.Conflict, "Agent ID belongs to a native Agent")
    data object ReservedAgentId : ExecutionError(WireErrorCode.Conflict, "Agent ID is reserved by an external registration")
    data object AccessRevoked : ExecutionError(WireErrorCode.Forbidden, "Remote access revoked")
    data object NotExposed : ExecutionError(WireErrorCode.Forbidden, "Agent is not exposed")
    data object NotGranted : ExecutionError(WireErrorCode.Forbidden, "Session does not belong to this execution grant")
}

/** Who may make a request; a refusal that needs management access says so in its data. */
sealed class AccessError(override val code: String, override val message: String, override val data: JsonElement? = null) : ServerError {
    data object ManagementRequired : AccessError(WireErrorCode.Forbidden, MANAGEMENT_REQUIRED, buildJsonObject { put("reason", WireErrorReason.Management) })
    data object LinkRestricted : AccessError(WireErrorCode.Forbidden, "Execution link does not allow this operation")
    data class UnknownMethod(val method: String) : AccessError(WireErrorCode.NotFound, "Unknown method $method")
}

sealed class ConnectError(override val code: String, override val message: String, override val data: JsonElement? = null) : ServerError {
    data object AlreadyConnected : ConnectError(WireErrorCode.Conflict, "Already connected")
    /** Says which versions this Server speaks, so a Device can tell which side to update. */
    data object ProtocolMismatch : ConnectError(WireErrorCode.ProtocolMismatch, ProtocolVersion.Mismatch,
        ProtocolJson.encodeToJsonElement(ProtocolRange.serializer(), ProtocolRange(ProtocolVersion.Minimum, ProtocolVersion.Current)))
    data object Unpaired : ConnectError(WireErrorCode.Forbidden, "This device was unpaired")
    data object InvalidPairing : ConnectError(WireErrorCode.Forbidden, "Invalid or expired pairing code")
}
