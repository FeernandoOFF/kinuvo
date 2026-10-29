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

class Method<P, R>(val name: String, val params: KSerializer<P>, val result: KSerializer<R>)

class EventType<T>(val name: String, val payload: KSerializer<T>)

@Serializable
data class AgentInfo(val id: String, val name: String, val exec: String, val avatar: String? = null, val executionKind: ExecutionKind = ExecutionKind.Native, val connectionId: String? = null, val host: String? = null, val readiness: ExecutionReadiness = ExecutionReadiness(true, true))

object ProtocolVersion {
    const val Current = 3
    const val Mismatch = "protocol_mismatch: Update the Server and Device to matching versions"
    fun requireCompatible(version: Int) { require(version == Current) { Mismatch } }
}

/** Exactly one of [token] (a paired device) or [pairingCode] (first connection) is set. [timeZone] is the Device's IANA zone. */
@Serializable
data class ConnectParams(val device: String, val token: String? = null, val pairingCode: String? = null, val timeZone: String? = null, val deviceTools: Set<DeviceToolKind> = emptySet(), @kotlinx.serialization.Required val protocolVersion: Int = ProtocolVersion.Current)

@Serializable
data class ConnectResult(val token: String, val serverName: String, val usage: Usage? = null, val capabilities: ServerCapabilities, val management: Boolean, @kotlinx.serialization.Required val protocolVersion: Int = ProtocolVersion.Current)

@Serializable
data class AgentRef(val agentId: String)

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
data class SendParams(val agentId: String, val sessionId: String? = null, val text: String, val attachments: List<String> = emptyList(), val voice: Boolean = false)

/** [activation] is set when the text was a `/skill` command; [message] is what was sent to the model. */
@Serializable
data class SendResult(val sessionId: String, val runId: String, val message: String, val activation: SessionEntry.Activation? = null)

/** [active] is false while the app is in the background, so the Server notifies instead of counting it as watching. */
@Serializable
data class PresenceParams(val active: Boolean)

/** Replaces the Session's [SessionSettings]; they apply from its next run. */
@Serializable
data class ConfigureSessionParams(val agentId: String, val sessionId: String, val settings: SessionSettings)

/** Renames the Session (blank: back to its first message) or marks it a favorite; null fields stay as they are. */
@Serializable
data class UpdateSessionParams(val agentId: String, val sessionId: String, val title: String? = null, val favorite: Boolean? = null)

@Serializable
data class StopParams(val sessionId: String)

@Serializable
data class ApprovalParams(val approvalId: String, val decision: ApprovalDecision)

/** [before] is the last Session ID of the previous page. Lists the user's Sessions, or with [routineId] that Routine's. */
@Serializable
data class SessionsParams(val agentId: String, val before: String? = null, val limit: Int = 30, val routineId: String? = null)

@Serializable
data class SessionParams(val agentId: String, val sessionId: String)

/**
 * Subscribes to a Session: the server sends, as [Api.Update]s, the entries from [after] on and then the live state,
 * followed by live updates until [Api.Unsubscribe]. With no [after], or more than [limit] entries behind, it sends
 * [SessionChange.Reset] and only the last [limit] entries; older ones load with [Api.History].
 */
@Serializable
data class CatchUpParams(val agentId: String, val sessionId: String, val after: Int? = null, val limit: Int = 100)

@Serializable
data class UnsubscribeParams(val sessionId: String)

/** Up to [limit] entries before seq [before]. */
@Serializable
data class HistoryParams(val agentId: String, val sessionId: String, val before: Int, val limit: Int = 100)

/** One change to a Session, pushed in order to the clients subscribed to it. */
@Serializable
data class SessionUpdate(val agentId: String, val sessionId: String, val change: SessionChange)

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
    val ScreenOpen = Method("screens.open", kinuvo.protocol.ScreenOpen.serializer(), ScreenInfo.serializer())
    val Screens = Method("screens.list", BrowserOwner.serializer().nullable, ListSerializer(ScreenInfo.serializer()))
    val ScreenWatch = Method("screens.watch", kinuvo.protocol.ScreenWatch.serializer(), ScreenSubscription.serializer())
    val ScreenUnwatch = Method("screens.unwatch", String.serializer(), Unit.serializer())
    val ScreenAck = Method("screens.ack", ScreenFrameAck.serializer(), Unit.serializer())
    val ScreenControl = Method("screens.control", ScreenControlRequest.serializer(), kinuvo.protocol.ScreenControl.serializer())
    val ScreenInput = Method("screens.input", ScreenInputRequest.serializer(), ScreenInputResult.serializer())
    val ScreenCommand = Method("screens.command", kinuvo.protocol.ScreenCommand.serializer(), Unit.serializer())
    val ScreensChanged = EventType("screens.changed", BrowserOwner.serializer().nullable)
    val ScreenFrame = EventType("screens.frame", kinuvo.protocol.ScreenFrame.serializer())
    val ScreenStatus = EventType("screens.status", ScreenStreamStatus.serializer())

    val BrowserTabs = Method("browser.tabs", BrowserOwner.serializer(), ListSerializer(BrowserTab.serializer()))
    val BrowserCommand = Method("browser.command", kinuvo.protocol.BrowserCommand.serializer(), BrowserFrame.serializer().nullable)
    val BrowserChanged = EventType("browser.changed", BrowserOwner.serializer())
    val Capabilities = Method("session.capabilities", CapabilitiesParams.serializer(), SessionCapabilities.serializer())
    val CapabilitiesChanged = EventType("capabilities.changed", Unit.serializer())
    val Models = Method("models.list", ModelsParams.serializer(), ModelsCatalog.serializer())
    val Providers = Method("providers.list", Unit.serializer(), ListSerializer(ProviderInfo.serializer()))
    /** Starts signing in; the Server finishes it in the background and sends [ProvidersChanged]. */
    val LoginProvider = Method("providers.login", ProviderRef.serializer(), DeviceCode.serializer())
    val SetProviderApiKey = Method("providers.apiKey", SetProviderApiKeyParams.serializer(), Unit.serializer())
    val LogoutProvider = Method("providers.logout", ProviderRef.serializer(), Unit.serializer())
    val UploadAttachment = Method("attachments.upload", UploadAttachmentParams.serializer(), kinuvo.protocol.Attachment.serializer())
    val Attachment = Method("attachments.get", AttachmentRef.serializer(), AttachmentData.serializer())
    val DeviceTools = Method("device.tools", DeviceToolsParams.serializer(), Unit.serializer())
    val DeviceRead = Method("device.read", DeviceToolRequest.serializer(), DeviceToolResult.serializer())
    /** Sent by the Server to a Device that lets Agents control it; answered in the background too. */
    val ComputerAct = Method("computer.act", ComputerRequest.serializer(), ComputerResult.serializer())
    val Computers = Method("computers.list", Unit.serializer(), ListSerializer(ComputerInfo.serializer()))
    /** Starts or stops Live view frames of a Computer for this client. */
    val WatchComputer = Method("computer.watch", WatchComputerParams.serializer(), Unit.serializer())
    /** The user's own tap, typing or key on a Computer; it starts a Takeover. */
    val ComputerInput = Method("computer.input", ComputerInputParams.serializer(), ComputerFrame.serializer())
    val Takeover = Method("computer.takeover", TakeoverParams.serializer(), ComputerFrame.serializer())
    val Connect = Method("connect", ConnectParams.serializer(), ConnectResult.serializer())
    val RotateToken = Method("device.rotateToken", Unit.serializer(), String.serializer())
    /** Opens pairing for another Device; requires management access. */
    val Pairing = Method("pairing.code", PairingParams.serializer(), PairingCode.serializer())
    val PairedDevices = Method("devices.list", Unit.serializer(), ListSerializer(PairedDeviceInfo.serializer()))
    /** Unpairs a Device, closing its connections; a Device may revoke itself. */
    val RevokeDevice = Method("devices.revoke", DeviceRef.serializer(), Unit.serializer())
    val Agents = Method("agents.list", Unit.serializer(), ListSerializer(AgentInfo.serializer()))
    val Agent = Method("agent.get", AgentRef.serializer(), AgentDetails.serializer())
    val CreateAgent = Method("agent.create", CreateAgentParams.serializer(), AgentInfo.serializer())
    val UpdateAgent = Method("agent.update", UpdateAgentParams.serializer(), AgentDetails.serializer())
    val TestExec = Method("exec.test", TestExecParams.serializer(), ExecTestResult.serializer())
    /** Trust and Deny rules every Agent follows; a Server Deny rule wins over an Agent's Trust rule. */
    val ServerRules = Method("rules.server", Unit.serializer(), TrustRules.serializer())
    val SaveServerRules = Method("rules.saveServer", TrustRules.serializer(), TrustRules.serializer())
    val DeleteAgent = Method("agent.delete", AgentRef.serializer(), Unit.serializer())
    val Sessions = Method("sessions.list", SessionsParams.serializer(), SessionPage.serializer())
    /** Every Skill the Agent can see, including shadowed ones (not [SkillInfo.active]). */
    val Skills = Method("skills.list", AgentRef.serializer(), ListSerializer(SkillInfo.serializer()))
    val Skill = Method("skill.get", SkillParams.serializer(), SkillDetails.serializer())
    val SaveSkill = Method("skill.save", SaveSkillParams.serializer(), SkillInfo.serializer())
    val DeleteSkill = Method("skill.delete", SkillParams.serializer(), Unit.serializer())
    /** The Agent's MCP servers and the shared ones. */
    val McpServers = Method("mcp.list", AgentRef.serializer(), ListSerializer(McpServerInfo.serializer()))
    val SaveMcp = Method("mcp.save", SaveMcpParams.serializer(), McpServerInfo.serializer())
    val RemoveMcp = Method("mcp.remove", McpParams.serializer(), Unit.serializer())
    val TestMcp = Method("mcp.test", McpParams.serializer(), McpTestResult.serializer())
    /** Starts an OAuth sign-in to an HTTP MCP server. */
    val LoginMcp = Method("mcp.login", McpLoginParams.serializer(), McpLogin.serializer())
    val CompleteMcpLogin = Method("mcp.loginComplete", McpLoginCompleteParams.serializer(), McpServerInfo.serializer())
    val LogoutMcp = Method("mcp.logout", McpParams.serializer(), Unit.serializer())
    val CatchUp = Method("session.catchUp", CatchUpParams.serializer(), Unit.serializer())
    val Unsubscribe = Method("session.unsubscribe", UnsubscribeParams.serializer(), Unit.serializer())
    val History = Method("session.history", HistoryParams.serializer(), ListSerializer(SessionChange.Entry.serializer()))
    val Session = Method("session.get", SessionParams.serializer(), SessionInfo.serializer())
    val CreateSession = Method("session.create", AgentRef.serializer(), SessionInfo.serializer())
    val ConfigureSession = Method("session.configure", ConfigureSessionParams.serializer(), SessionInfo.serializer())
    val UpdateSession = Method("session.update", UpdateSessionParams.serializer(), SessionInfo.serializer())
    val Send = Method("chat.send", SendParams.serializer(), SendResult.serializer())
    val Stop = Method("chat.stop", StopParams.serializer(), Unit.serializer())
    val ReviewReflection = Method("reflection.review", ReviewReflectionParams.serializer(), Unit.serializer())
    val Approve = Method("approval.respond", ApprovalParams.serializer(), Boolean.serializer())
    val Answer = Method("questions.respond", QuestionsParams.serializer(), Boolean.serializer())
    /** Every Artifact, newest first. */
    val Artifacts = Method("artifacts.list", Unit.serializer(), ListSerializer(ArtifactInfo.serializer()))
    val Artifact = Method("artifact.get", ArtifactRef.serializer(), ArtifactData.serializer())
    val DeleteArtifact = Method("artifact.delete", ArtifactRef.serializer(), Unit.serializer())
    val Routines = Method("routines.list", AgentRef.serializer(), ListSerializer(Routine.serializer()))
    val CreateRoutine = Method("routine.create", CreateRoutineParams.serializer(), Routine.serializer())
    val UpdateRoutine = Method("routine.update", UpdateRoutineParams.serializer(), Routine.serializer())
    val RunRoutine = Method("routine.run", RoutineParams.serializer(), Unit.serializer())
    val RemoveRoutine = Method("routine.remove", RoutineParams.serializer(), Unit.serializer())
    val KnowledgeSearch = Method("knowledge.search", KnowledgeSearchParams.serializer(), ListSerializer(NoteSummary.serializer()))
    /** A note by ref or title. */
    val KnowledgeNote = Method("knowledge.get", NoteRef.serializer(), Note.serializer())
    /** The latest changes Agents made, newest first. */
    val KnowledgeChanges = Method("knowledge.changes", KnowledgeChangesParams.serializer(), ListSerializer(KnowledgeChange.serializer()))
    val UndoKnowledgeChange = Method("knowledge.undo", KnowledgeChangeRef.serializer(), KnowledgeChange.serializer())
    val TaskLists = Method("tasks.lists", Unit.serializer(), ListSerializer(TaskListInfo.serializer()))
    val Tasks = Method("tasks.list", TasksParams.serializer(), ListSerializer(TaskInfo.serializer()))
    val CreateTask = Method("task.create", CreateTaskParams.serializer(), TaskInfo.serializer())
    val UpdateTask = Method("task.update", UpdateTaskParams.serializer(), TaskInfo.serializer())
    val TasksSettings = Method("tasks.settings", Unit.serializer(), TasksSetup.serializer())
    val SaveTasksSettings = Method("tasks.saveSettings", kinuvo.protocol.TasksSettings.serializer(), TasksSetup.serializer())
    /** Every list a task provider has, whether the Board shows it or not. */
    val ProviderLists = Method("tasks.providerLists", TaskProviderRef.serializer(), ListSerializer(TaskListInfo.serializer()))
    val AuthorizeTasks = Method("tasks.authorize", TaskProviderRef.serializer(), ListSerializer(TaskListInfo.serializer()))
    val KnowledgeSettings = Method("knowledge.settings", Unit.serializer(), KnowledgeSetup.serializer())
    val SaveKnowledgeSettings = Method("knowledge.saveSettings", kinuvo.protocol.KnowledgeSettings.serializer(), KnowledgeSetup.serializer())
    /** Reads the schema with unsaved settings, so a broken setup shows in Settings rather than to Agents. */
    val TestKnowledge = Method("knowledge.test", kinuvo.protocol.KnowledgeSettings.serializer(), KnowledgeTestResult.serializer())
    /** The Server's password managers for Secret references. */
    val SecretProviders = Method("secrets.status", Unit.serializer(), ListSerializer(SecretProviderStatus.serializer()))
    val ConnectSecrets = Method("secrets.connect", ConnectSecretsParams.serializer(), SecretProviderStatus.serializer())
    val DisconnectSecrets = Method("secrets.disconnect", SecretProviderRef.serializer(), SecretProviderStatus.serializer())
    /** Vault, item or field names for picking a reference; never values. */
    val BrowseSecrets = Method("secrets.browse", BrowseSecretsParams.serializer(), ListSerializer(SecretEntry.serializer()))
    /** Forgets cached secret values, so the next use reads them again. */
    val RefreshSecrets = Method("secrets.refresh", Unit.serializer(), Unit.serializer())
    val Presence = Method("client.presence", PresenceParams.serializer(), Unit.serializer())
    val Service = Method("service.settings", ServiceRef.serializer(), ServiceSetup.serializer())
    val SaveService = Method("service.save", SaveServiceParams.serializer(), ServiceSetup.serializer())
    val TestService = Method("service.test", TestServiceParams.serializer(), ServiceTestResult.serializer())

    val ProbeHarnessConnection = Method("execution.connection.check", HarnessConnection.serializer(), ExecutionProbe.serializer())
    val ExecutionCatalogChanged = EventType("execution.catalog.changed", kinuvo.protocol.ExecutionCatalog.serializer())
    val ResumeExecution = Method("execution.resume", ExecutionRef.serializer(), kinuvo.protocol.ExecutionSnapshot.serializer())
    val CloseExecution = Method("execution.close", ExecutionRef.serializer(), Unit.serializer())
    val ExecutionSnapshot = Method("execution.snapshot", ExecutionRef.serializer(), kinuvo.protocol.ExecutionSnapshot.serializer())
    val ExecutionAnswer = Method("execution.answer", kinuvo.protocol.ExecutionAnswer.serializer(), Boolean.serializer())
    val ExecutionChanged = EventType("execution.changed", kinuvo.protocol.ExecutionSnapshot.serializer())
    val SaveRemoteLink = Method("execution.remote.save", kinuvo.protocol.SaveRemoteLink.serializer(), Unit.serializer())
    val GrantExecution = Method("execution.exposure.grant", ExposureGrant.serializer(), String.serializer())
    val ExecutionGrants = Method("execution.exposure.list", Unit.serializer(), ListSerializer(ExposureGrant.serializer()))
    val RevokeExecution = Method("execution.exposure.revoke", ExecutionRef.serializer(), Unit.serializer())
    val RemoteExecution = Method("execution.remote.call", RemoteExecutionCall.serializer(), RemoteExecutionResult.serializer())
    val DiscoverRemote = Method("execution.remote.discover", ExecutionRef.serializer(), RemoteExecutionResult.serializer())
    val ExecutionCatalog = Method("execution.catalog", Unit.serializer(), kinuvo.protocol.ExecutionCatalog.serializer())
    val SaveHarnessConnection = Method("execution.connection.save", HarnessConnection.serializer(), HarnessConnectionStatus.serializer())
    val DeleteHarnessConnection = Method("execution.connection.delete", ExecutionRef.serializer(), Unit.serializer())
    val RegisterExternalAgent = Method("execution.agent.save", AgentRegistration.serializer(), AgentRegistration.serializer())
    val UnregisterExternalAgent = Method("execution.agent.delete", ExecutionRef.serializer(), Unit.serializer())

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
