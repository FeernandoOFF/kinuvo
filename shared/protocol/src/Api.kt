package kinuvo.protocol

import kinuvo.protocol.AgentEvent
import kinuvo.protocol.ApprovalDecision
import kinuvo.protocol.SessionEntry
import kinuvo.protocol.SessionInfo
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** WebSocket text frames: requests, responses and server-pushed events (OpenClaw-style). */
@Serializable
sealed interface Frame {
    @Serializable @SerialName("req")
    data class Request(val id: String, val method: String, val params: JsonElement = JsonNull) : Frame

    @Serializable @SerialName("res")
    data class Response(val id: String, val ok: Boolean, val payload: JsonElement = JsonNull, val error: String? = null) : Frame

    @Serializable @SerialName("event")
    data class Event(val event: String, val payload: JsonElement, val seq: Long) : Frame
}

val ProtocolJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "type"
}

enum class MethodDirection { ToServer, ToDevice }

class Method<P, R>(val name: String, val params: KSerializer<P>, val result: KSerializer<R>, val direction: MethodDirection = MethodDirection.ToServer) {
    fun requireDirection(expected: MethodDirection) {
        require(direction == expected) { "Wrong direction for $name: expected $expected" }
    }
}

class EventType<T>(val name: String, val payload: KSerializer<T>)

@Serializable
data class AgentInfo(val id: String, val name: String, val exec: String, val avatar: String? = null, val executionKind: ExecutionKind = ExecutionKind.Native, val connectionId: String? = null, val host: String? = null, val readiness: ExecutionReadiness = ExecutionReadiness(true, true))

object ProtocolVersion {
    const val Current = 5
    const val Mismatch = "protocol_mismatch: Update the Server and Device to matching versions"
    fun requireCompatible(version: Int) { require(version == Current) { Mismatch } }
}

/** Requests a Device may have unanswered on one connection; the Server drops a connection that sends more. */
const val MAX_CONCURRENT_REQUESTS = 8

/** Exactly one of [token] (a paired device) or [pairingCode] (first connection) is set. [timeZone] is the Device's IANA zone. */
@Serializable
data class ConnectParams(val device: String, val token: String? = null, val pairingCode: String? = null, val timeZone: String? = null, val deviceTools: Set<DeviceToolKind> = emptySet(), @kotlinx.serialization.Required val protocolVersion: Int = ProtocolVersion.Current)

@Serializable
data class ConnectResult(val token: String, val serverName: String, val usage: Usage? = null, val capabilities: ServerCapabilities, val management: Boolean, @kotlinx.serialization.Required val protocolVersion: Int = ProtocolVersion.Current, val deviceId: String? = null)

@Serializable
data class AgentRef(val agentId: AgentId)

/** A Skill an Agent can use; [userInvocable] ones can be activated with `/name`. [active] is false when a Skill of the same name shadows it. */
@Serializable
data class SkillInfo(
    val name: String,
    val description: String,
    val userInvocable: Boolean = true,
    val modelInvocable: Boolean = true,
    val scope: ConfigScope = ConfigScope.Agent,
    val active: Boolean = true,
)

/** [voice] marks [text] as transcribed from voice chat. */
@Serializable
data class SendParams(val agentId: AgentId, val sessionId: SessionId? = null, val text: String, val attachments: List<String> = emptyList(), val voice: Boolean = false, val submissionId: String? = null)

/** [activation] is set when the text was a `/skill` command; [message] is what was sent to the model. */
@Serializable
data class SendResult(val sessionId: SessionId, val runId: String, val message: String, val activation: SessionEntry.Activation? = null)

/** [active] is false while the app is in the background, so the Server notifies instead of counting it as watching. */
@Serializable
data class PresenceParams(val active: Boolean)

/** Replaces the Session's [SessionSettings]; they apply from its next run. */
@Serializable
data class ConfigureSessionParams(val agentId: AgentId, val sessionId: SessionId, val settings: SessionSettings)

/** Renames the Session (blank: back to its first message) or marks it a favorite; null fields stay as they are. */
@Serializable
data class UpdateSessionParams(val agentId: AgentId, val sessionId: SessionId, val title: String? = null, val favorite: Boolean? = null)

@Serializable
data class StopParams(val sessionId: SessionId)

@Serializable
data class ApprovalParams(val approvalId: String, val decision: ApprovalDecision)

/** [before] is the last Session ID of the previous page. Lists the user's Sessions, or with [routineId] that Routine's. */
@Serializable
data class SessionsParams(val agentId: AgentId, val before: SessionId? = null, val limit: Int = 30, val routineId: String? = null)

@Serializable
data class SessionParams(val agentId: AgentId, val sessionId: SessionId)

/**
 * Subscribes to a Session: the server sends, as [Api.Update]s, the entries from [after] on and then the live state,
 * followed by live updates until [Api.Unsubscribe]. With no [after], or more than [limit] entries behind, it sends
 * [SessionChange.Reset] and only the last [limit] entries; older ones load with [Api.History].
 */
@Serializable
data class CatchUpParams(val agentId: AgentId, val sessionId: SessionId, val after: Int? = null, val limit: Int = 100)

@Serializable
data class UnsubscribeParams(val sessionId: SessionId)

/** Up to [limit] entries before seq [before]. */
@Serializable
data class HistoryParams(val agentId: AgentId, val sessionId: SessionId, val before: Int, val limit: Int = 100)

/** One change to a Session, pushed in order to the clients subscribed to it. */
@Serializable
data class SessionUpdate(val agentId: AgentId, val sessionId: SessionId, val change: SessionChange)

@Serializable
sealed interface SessionChange {
    /** Atomically replaces a mutable transcript window and its live state. */
    @Serializable @SerialName("window")
    data class Window(val firstSeq: Int, val entries: List<SessionEntry>, val state: LiveState) : SessionChange

    /** A persisted entry; [seq] is its position in the Session. */
    @Serializable @SerialName("entry")
    data class Entry(val seq: Int, val entry: SessionEntry) : SessionChange

    /** A run event not covered by entries, e.g. a text delta. */
    @Serializable @SerialName("event")
    data class Event(val event: AgentEvent) : SessionChange

    /** The catch-up starts a fresh window at [firstSeq]; drop what was held before. */
    @Serializable @SerialName("reset")
    data class Reset(val firstSeq: Int) : SessionChange

    /** Replaces the client's live state; sent at the end of a catch-up. */
    @Serializable @SerialName("live")
    data class Live(val state: LiveState) : SessionChange
}

object Api {
    private val descriptors = mutableListOf<Method<*, *>>()
    val methods: List<Method<*, *>> get() = descriptors.toList()

    private fun <P, R> method(name: String, params: KSerializer<P>, result: KSerializer<R>, direction: MethodDirection = MethodDirection.ToServer) =
        Method(name, params, result, direction).also { descriptors.add(it) }

    val ScreenOpen = method("screens.open", kinuvo.protocol.ScreenOpen.serializer(), ScreenInfo.serializer())
    val Screens = method("screens.list", BrowserOwner.serializer().nullable, ListSerializer(ScreenInfo.serializer()))
    val ScreenWatch = method("screens.watch", kinuvo.protocol.ScreenWatch.serializer(), ScreenSubscription.serializer())
    val ScreenUnwatch = method("screens.unwatch", String.serializer(), Unit.serializer())
    val ScreenAck = method("screens.ack", ScreenFrameAck.serializer(), Unit.serializer())
    val ScreenControl = method("screens.control", ScreenControlRequest.serializer(), kinuvo.protocol.ScreenControl.serializer())
    val ScreenInput = method("screens.input", ScreenInputRequest.serializer(), ScreenInputResult.serializer())
    val ScreenCommand = method("screens.command", kinuvo.protocol.ScreenCommand.serializer(), Unit.serializer())
    val ScreensChanged = EventType("screens.changed", BrowserOwner.serializer().nullable)
    val ScreenFrame = EventType("screens.frame", kinuvo.protocol.ScreenFrame.serializer())
    val ScreenStatus = EventType("screens.status", ScreenStreamStatus.serializer())

    val BrowserPresentation = method("browser.presentation", BrowserOwner.serializer(), kinuvo.protocol.BrowserPresentation.serializer())
    val SaveBrowserPresentation = method("browser.savePresentation", BrowserPresentationRequest.serializer(), kinuvo.protocol.BrowserPresentation.serializer())
    val BrowserTabs = method("browser.tabs", BrowserOwner.serializer(), ListSerializer(BrowserTab.serializer()))
    val BrowserCommand = method("browser.command", kinuvo.protocol.BrowserCommand.serializer(), BrowserFrame.serializer().nullable)
    val BrowserChanged = EventType("browser.changed", BrowserOwner.serializer())

    val VoiceModels = method("voice.models", Unit.serializer(), VoiceModelsState.serializer())
    val VoiceDownload = method("voice.download", String.serializer(), Unit.serializer())
    val VoiceCancelDownload = method("voice.cancelDownload", Unit.serializer(), Unit.serializer())
    val VoiceRemove = method("voice.remove", String.serializer(), Unit.serializer())
    val VoiceSelect = method("voice.select", VoiceSelection.serializer(), Unit.serializer())
    val VoiceLanguage = method("voice.language", String.serializer(), Unit.serializer())
    val VoiceSetup = method("voice.setup", Unit.serializer(), kinuvo.protocol.VoiceSetup.serializer())
    val Transcribe = method("voice.transcribe", TranscribeParams.serializer(), String.serializer())
    val Synthesize = method("voice.synthesize", SynthesizeParams.serializer(), SpeechAudio.serializer())
    val CancelVoice = method("voice.cancel", CancelVoiceParams.serializer(), Unit.serializer())
    val Capabilities = method("session.capabilities", CapabilitiesParams.serializer(), SessionCapabilities.serializer())
    val CapabilitiesChanged = EventType("capabilities.changed", Unit.serializer())
    val Models = method("models.list", ModelsParams.serializer(), ModelsCatalog.serializer())
    val Providers = method("providers.list", Unit.serializer(), ListSerializer(ProviderInfo.serializer()))
    /** Starts signing in; the Server finishes it in the background and sends [ProvidersChanged]. */
    val LoginProvider = method("providers.login", ProviderRef.serializer(), DeviceCode.serializer())
    val SetProviderApiKey = method("providers.apiKey", SetProviderApiKeyParams.serializer(), Unit.serializer())
    val LogoutProvider = method("providers.logout", ProviderRef.serializer(), Unit.serializer())
    /** Starts downloading a model to the Server; progress and the result follow in [ProvidersChanged]. */
    val DownloadProviderModel = method("providers.download", ProviderModelRef.serializer(), Unit.serializer())
    val CancelProviderDownload = method("providers.cancelDownload", ProviderRef.serializer(), Unit.serializer())
    val RemoveProviderModel = method("providers.removeModel", ProviderModelRef.serializer(), Unit.serializer())
    val UploadAttachment = method("attachments.upload", UploadAttachmentParams.serializer(), kinuvo.protocol.Attachment.serializer())
    val Attachment = method("attachments.get", AttachmentRef.serializer(), AttachmentData.serializer())
    val DeviceTools = method("device.tools", DeviceToolsParams.serializer(), Unit.serializer())
    val DeviceRead = method("device.read", DeviceToolRequest.serializer(), DeviceToolResult.serializer(), MethodDirection.ToDevice)
    /** Sent by the Server to a Device that lets Agents control it; answered in the background too. */
    val ComputerAct = method("computer.act", ComputerRequest.serializer(), ComputerResult.serializer(), MethodDirection.ToDevice)
    val Computers = method("computers.list", Unit.serializer(), ListSerializer(ComputerInfo.serializer()))
    /** Starts or stops Live view frames of a Computer for this client. */
    val WatchComputer = method("computer.watch", WatchComputerParams.serializer(), Unit.serializer())
    /** The user's own tap, typing or key on a Computer; it starts a Takeover. */
    val ComputerInput = method("computer.input", ComputerInputParams.serializer(), ComputerFrame.serializer())
    val Takeover = method("computer.takeover", TakeoverParams.serializer(), ComputerFrame.serializer())
    val Connect = method("connect", ConnectParams.serializer(), ConnectResult.serializer())
    val RotateToken = method("device.rotateToken", Unit.serializer(), String.serializer())
    /** Opens pairing for another Device; requires management access. */
    val Pairing = method("pairing.code", PairingParams.serializer(), PairingCode.serializer())
    val PairedDevices = method("devices.list", Unit.serializer(), ListSerializer(PairedDeviceInfo.serializer()))
    /** Unpairs a Device, closing its connections; a Device may revoke itself. */
    val RevokeDevice = method("devices.revoke", DeviceRef.serializer(), Unit.serializer())
    val Agents = method("agents.list", Unit.serializer(), ListSerializer(AgentInfo.serializer()))
    val Agent = method("agent.get", AgentRef.serializer(), AgentDetails.serializer())
    val CreateAgent = method("agent.create", CreateAgentParams.serializer(), AgentInfo.serializer())
    val UpdateAgent = method("agent.update", UpdateAgentParams.serializer(), AgentDetails.serializer())
    val TestExec = method("exec.test", TestExecParams.serializer(), ExecTestResult.serializer())
    /** Trust and Deny rules every Agent follows; a Server Deny rule wins over an Agent's Trust rule. */
    val ServerRules = method("rules.server", Unit.serializer(), TrustRules.serializer())
    val SaveServerRules = method("rules.saveServer", TrustRules.serializer(), TrustRules.serializer())
    val DeleteAgent = method("agent.delete", AgentRef.serializer(), Unit.serializer())
    val Sessions = method("sessions.list", SessionsParams.serializer(), SessionPage.serializer())
    /** Every Skill the Agent can see, including shadowed ones (not [SkillInfo.active]). */
    val Skills = method("skills.list", AgentRef.serializer(), ListSerializer(SkillInfo.serializer()))
    val Skill = method("skill.get", SkillParams.serializer(), SkillDetails.serializer())
    val SaveSkill = method("skill.save", SaveSkillParams.serializer(), SkillInfo.serializer())
    val DeleteSkill = method("skill.delete", SkillParams.serializer(), Unit.serializer())
    /** The Agent's MCP servers and the shared ones. */
    val McpServers = method("mcp.list", AgentRef.serializer(), ListSerializer(McpServerInfo.serializer()))
    val SaveMcp = method("mcp.save", SaveMcpParams.serializer(), McpServerInfo.serializer())
    val RemoveMcp = method("mcp.remove", McpParams.serializer(), Unit.serializer())
    val TestMcp = method("mcp.test", McpParams.serializer(), McpTestResult.serializer())
    /** Starts an OAuth sign-in to an HTTP MCP server. */
    val LoginMcp = method("mcp.login", McpLoginParams.serializer(), McpLogin.serializer())
    val CompleteMcpLogin = method("mcp.loginComplete", McpLoginCompleteParams.serializer(), McpServerInfo.serializer())
    val LogoutMcp = method("mcp.logout", McpParams.serializer(), Unit.serializer())
    val CatchUp = method("session.catchUp", CatchUpParams.serializer(), Unit.serializer())
    val Unsubscribe = method("session.unsubscribe", UnsubscribeParams.serializer(), Unit.serializer())
    val History = method("session.history", HistoryParams.serializer(), ListSerializer(SessionChange.Entry.serializer()))
    val Session = method("session.get", SessionParams.serializer(), SessionInfo.serializer())
    val CreateSession = method("session.create", AgentRef.serializer(), SessionInfo.serializer())
    val ConfigureSession = method("session.configure", ConfigureSessionParams.serializer(), SessionInfo.serializer())
    val UpdateSession = method("session.update", UpdateSessionParams.serializer(), SessionInfo.serializer())
    val Send = method("chat.send", SendParams.serializer(), SendResult.serializer())
    val Stop = method("chat.stop", StopParams.serializer(), Unit.serializer())
    val ReviewReflection = method("reflection.review", ReviewReflectionParams.serializer(), Unit.serializer())
    val Approve = method("approval.respond", ApprovalParams.serializer(), Boolean.serializer())
    val Answer = method("questions.respond", QuestionsParams.serializer(), Boolean.serializer())
    /** Every Artifact, newest first. */
    val Artifacts = method("artifacts.list", Unit.serializer(), ListSerializer(ArtifactInfo.serializer()))
    val Artifact = method("artifact.get", ArtifactRef.serializer(), ArtifactData.serializer())
    val DeleteArtifact = method("artifact.delete", ArtifactRef.serializer(), Unit.serializer())
    val Routines = method("routines.list", AgentRef.serializer(), ListSerializer(Routine.serializer()))
    val CreateRoutine = method("routine.create", CreateRoutineParams.serializer(), Routine.serializer())
    val UpdateRoutine = method("routine.update", UpdateRoutineParams.serializer(), Routine.serializer())
    val RunRoutine = method("routine.run", RoutineParams.serializer(), Unit.serializer())
    val RemoveRoutine = method("routine.remove", RoutineParams.serializer(), Unit.serializer())
    val KnowledgeSearch = method("knowledge.search", KnowledgeSearchParams.serializer(), ListSerializer(NoteSummary.serializer()))
    /** A note by ref or title. */
    val KnowledgeNote = method("knowledge.get", NoteRef.serializer(), Note.serializer())
    /** The latest changes Agents made, newest first. */
    val KnowledgeChanges = method("knowledge.changes", KnowledgeChangesParams.serializer(), ListSerializer(KnowledgeChange.serializer()))
    val UndoKnowledgeChange = method("knowledge.undo", KnowledgeChangeRef.serializer(), KnowledgeChange.serializer())
    val TaskLists = method("tasks.lists", Unit.serializer(), ListSerializer(TaskListInfo.serializer()))
    val Tasks = method("tasks.list", TasksParams.serializer(), ListSerializer(TaskInfo.serializer()))
    val CreateTask = method("task.create", CreateTaskParams.serializer(), TaskInfo.serializer())
    val UpdateTask = method("task.update", UpdateTaskParams.serializer(), TaskInfo.serializer())
    val TasksSettings = method("tasks.settings", Unit.serializer(), TasksSetup.serializer())
    val SaveTasksSettings = method("tasks.saveSettings", kinuvo.protocol.TasksSettings.serializer(), TasksSetup.serializer())
    /** Every list a task provider has, whether the Board shows it or not. */
    val ProviderLists = method("tasks.providerLists", TaskProviderRef.serializer(), ListSerializer(TaskListInfo.serializer()))
    val AuthorizeTasks = method("tasks.authorize", TaskProviderRef.serializer(), ListSerializer(TaskListInfo.serializer()))
    val KnowledgeSettings = method("knowledge.settings", Unit.serializer(), KnowledgeSetup.serializer())
    val SaveKnowledgeSettings = method("knowledge.saveSettings", kinuvo.protocol.KnowledgeSettings.serializer(), KnowledgeSetup.serializer())
    /** Reads the schema with unsaved settings, so a broken setup shows in Settings rather than to Agents. */
    val TestKnowledge = method("knowledge.test", kinuvo.protocol.KnowledgeSettings.serializer(), KnowledgeTestResult.serializer())
    /** The Server's password managers for Secret references. */
    val SecretProviders = method("secrets.status", Unit.serializer(), ListSerializer(SecretProviderStatus.serializer()))
    val ConnectSecrets = method("secrets.connect", ConnectSecretsParams.serializer(), SecretProviderStatus.serializer())
    val DisconnectSecrets = method("secrets.disconnect", SecretProviderRef.serializer(), SecretProviderStatus.serializer())
    /** Vault, item or field names for picking a reference; never values. */
    val BrowseSecrets = method("secrets.browse", BrowseSecretsParams.serializer(), ListSerializer(SecretEntry.serializer()))
    /** Forgets cached secret values, so the next use reads them again. */
    val RefreshSecrets = method("secrets.refresh", Unit.serializer(), Unit.serializer())
    val Presence = method("client.presence", PresenceParams.serializer(), Unit.serializer())
    val Service = method("service.settings", ServiceRef.serializer(), ServiceSetup.serializer())
    val SaveService = method("service.save", SaveServiceParams.serializer(), ServiceSetup.serializer())
    val TestService = method("service.test", TestServiceParams.serializer(), ServiceTestResult.serializer())

    val ProbeHarnessConnection = method("execution.connection.check", HarnessConnection.serializer(), ExecutionProbe.serializer())
    val ExecutionCatalogChanged = EventType("execution.catalog.changed", kinuvo.protocol.ExecutionCatalog.serializer())
    val ResumeExecution = method("execution.resume", ExecutionRef.serializer(), kinuvo.protocol.ExecutionSnapshot.serializer())
    val CloseExecution = method("execution.close", ExecutionRef.serializer(), Unit.serializer())
    val ExecutionSnapshot = method("execution.snapshot", ExecutionRef.serializer(), kinuvo.protocol.ExecutionSnapshot.serializer())
    val ExecutionAnswer = method("execution.answer", kinuvo.protocol.ExecutionAnswer.serializer(), Boolean.serializer())
    val ExecutionChanged = EventType("execution.changed", kinuvo.protocol.ExecutionSnapshot.serializer())
    val SaveRemoteLink = method("execution.remote.save", kinuvo.protocol.SaveRemoteLink.serializer(), Unit.serializer())
    val GrantExecution = method("execution.exposure.grant", ExposureGrant.serializer(), String.serializer())
    val ExecutionGrants = method("execution.exposure.list", Unit.serializer(), ListSerializer(ExposureGrant.serializer()))
    val RevokeExecution = method("execution.exposure.revoke", ExecutionRef.serializer(), Unit.serializer())
    val RemoteExecution = method("execution.remote.call", RemoteExecutionCall.serializer(), RemoteExecutionResult.serializer())
    val DiscoverRemote = method("execution.remote.discover", ExecutionRef.serializer(), RemoteExecutionResult.serializer())
    val ExecutionCatalog = method("execution.catalog", Unit.serializer(), kinuvo.protocol.ExecutionCatalog.serializer())
    val SaveHarnessConnection = method("execution.connection.save", HarnessConnection.serializer(), HarnessConnectionStatus.serializer())
    val DeleteHarnessConnection = method("execution.connection.delete", ExecutionRef.serializer(), Unit.serializer())
    val RegisterExternalAgent = method("execution.agent.save", AgentRegistration.serializer(), AgentRegistration.serializer())
    val UnregisterExternalAgent = method("execution.agent.delete", ExecutionRef.serializer(), Unit.serializer())

    val Update = EventType("session.update", SessionUpdate.serializer())
    /** A Session was created, renamed or changed status; sent to every client. */
    val SessionChanged = EventType("session.changed", SessionInfo.serializer())
    val Usage = EventType("usage", kinuvo.protocol.Usage.serializer())
    val ProvidersChanged = EventType("providers.changed", ListSerializer(ProviderInfo.serializer()))
    /** Every Agent, after one was created, renamed or deleted. */
    val AgentsChanged = EventType("agents.changed", ListSerializer(AgentInfo.serializer()))
    val RoutinesChanged = EventType("routines.changed", kinuvo.protocol.RoutinesChanged.serializer())
    val RoutineRan = EventType("routine.ran", kinuvo.protocol.RoutineRan.serializer())
    /** An Agent changed Knowledge, or a change was undone. */
    val KnowledgeChanged = EventType("knowledge.changed", KnowledgeChange.serializer())
    /** An Artifact was saved or deleted. */
    val ArtifactChanged = EventType("artifact.changed", ArtifactChange.serializer())
    /** A Task changed on the Board, e.g. an Agent started or finished it. */
    val TaskChanged = EventType("task.changed", TaskInfo.serializer())
    /** A Device paired or was revoked. */
    val DevicesChanged = EventType("devices.changed", Unit.serializer())
    /** A Live view frame, or a change of who controls a Computer, sent to the clients watching it. */
    val ComputerFrameEvent = EventType("computer.frame", ComputerFrame.serializer())
    /** A Computer became available, changed control or an Agent asked for a Takeover; sent to every client. */
    val ComputersChanged = EventType("computers.changed", ListSerializer(ComputerInfo.serializer()))
}
