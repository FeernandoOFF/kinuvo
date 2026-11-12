package kinuvo.protocol

import kinuvo.protocol.workflow.*
import kinuvo.protocol.workflow.Workflow as WorkflowValue
import kinuvo.protocol.team.TeamId
import kinuvo.protocol.team.TeamSnapshot
import kinuvo.protocol.team.TeamSpec
import kinuvo.protocol.team.UpdateTeamParams
import kinuvo.protocol.team.TeamSendParams
import kinuvo.protocol.team.TeamRespondParams
import kinuvo.protocol.team.TeamChanged as TeamChangedValue
import kinuvo.protocol.agent.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.artifact.*
import kinuvo.protocol.board.*
import kinuvo.protocol.browser.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.computer.*
import kinuvo.protocol.connection.*
import kinuvo.protocol.device.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.knowledge.*
import kinuvo.protocol.mcp.*
import kinuvo.protocol.model.*
import kinuvo.protocol.push.*
import kinuvo.protocol.reflection.*
import kinuvo.protocol.routine.*
import kinuvo.protocol.screen.*
import kinuvo.protocol.secret.*
import kinuvo.protocol.service.*
import kinuvo.protocol.session.*
import kinuvo.protocol.skill.*
import kinuvo.protocol.surface.*
import kinuvo.protocol.usage.*
import kinuvo.protocol.voice.*
import kinuvo.protocol.surface.SurfaceImage as SurfaceImageResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** WebSocket text frames: requests, responses and server-pushed events (OpenClaw-style). */
@Serializable
sealed interface Frame {
    @Serializable @SerialName("req")
    data class Request(val id: String, val method: String, val params: JsonElement = JsonNull) : Frame

    @Serializable @SerialName("res")
    data class Response(val id: String, val ok: Boolean, val payload: JsonElement = JsonNull, @Serializable(with = WireErrorSerializer::class) val error: WireError? = null) : Frame

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

object ProtocolVersion {
    const val Current = 8
    const val Mismatch = "protocol_mismatch: Update the Server and Device to matching versions"
    fun isCompatible(version: Int) = version == Current
    fun requireCompatible(version: Int) { require(isCompatible(version)) { Mismatch } }
}

/** Requests a Device may have unanswered on one connection; the Server drops a connection that sends more. */
const val MAX_CONCURRENT_REQUESTS = 8

object Api {
    val Workflows = Method("workflows.list", Unit.serializer(), ListSerializer(WorkflowValue.serializer()))
    val Workflow = Method("workflows.get", WorkflowId.serializer(), WorkflowValue.serializer())
    val CreateWorkflow = Method("workflows.create", WorkflowValue.serializer(), WorkflowValue.serializer())
    val UpdateWorkflow = Method("workflows.update", WorkflowValue.serializer(), WorkflowValue.serializer())
    val DeleteWorkflow = Method("workflows.delete", WorkflowId.serializer(), Unit.serializer())
    val RunWorkflow = Method("workflows.run", WorkflowId.serializer(), WorkflowRun.serializer())
    val CancelWorkflow = Method("workflows.cancel", WorkflowRunId.serializer(), WorkflowRun.serializer())
    val WorkflowRuns = Method("workflows.runs", WorkflowId.serializer(), ListSerializer(WorkflowRun.serializer()))
    val WorkflowsChanged = EventType("workflows.changed", ListSerializer(WorkflowValue.serializer()))
    val WorkflowRunChanged = EventType("workflows.runChanged", WorkflowRun.serializer())
    val Teams = Method("teams.list", Unit.serializer(), ListSerializer(TeamSnapshot.serializer()))
    val Team = Method("teams.get", TeamId.serializer(), TeamSnapshot.serializer())
    val CreateTeam = Method("teams.create", TeamSpec.serializer(), TeamSnapshot.serializer())
    val UpdateTeam = Method("teams.update", UpdateTeamParams.serializer(), TeamSnapshot.serializer())
    val DeleteTeam = Method("teams.delete", TeamId.serializer(), Unit.serializer())
    val TeamSend = Method("teams.send", TeamSendParams.serializer(), Unit.serializer())
    val TeamStop = Method("teams.stop", TeamId.serializer(), Unit.serializer())
    val TeamRespond = Method("teams.respond", TeamRespondParams.serializer(), Boolean.serializer())
    val TeamChanged = EventType("teams.changed", TeamChangedValue.serializer())

    val ScreenOpen = Method("screens.open", kinuvo.protocol.screen.ScreenOpen.serializer(), ScreenInfo.serializer())
    val Screens = Method("screens.list", BrowserOwner.serializer().nullable, ListSerializer(ScreenInfo.serializer()))
    val ScreenWatch = Method("screens.watch", kinuvo.protocol.screen.ScreenWatch.serializer(), ScreenSubscription.serializer())
    val ScreenUnwatch = Method("screens.unwatch", String.serializer(), Unit.serializer())
    val ScreenAck = Method("screens.ack", ScreenFrameAck.serializer(), Unit.serializer())
    val ScreenControl = Method("screens.control", ScreenControlRequest.serializer(), kinuvo.protocol.screen.ScreenControl.serializer())
    val ScreenInput = Method("screens.input", ScreenInputRequest.serializer(), ScreenInputResult.serializer())
    val ScreenCommand = Method("screens.command", kinuvo.protocol.screen.ScreenCommand.serializer(), Unit.serializer())
    val ScreensChanged = EventType("screens.changed", BrowserOwner.serializer().nullable)
    val ScreenFrame = EventType("screens.frame", kinuvo.protocol.screen.ScreenFrame.serializer())
    val ScreenStatus = EventType("screens.status", ScreenStreamStatus.serializer())

    val BrowserPresentation = Method("browser.presentation", BrowserOwner.serializer(), kinuvo.protocol.browser.BrowserPresentation.serializer())
    val SaveBrowserPresentation = Method("browser.savePresentation", BrowserPresentationRequest.serializer(), kinuvo.protocol.browser.BrowserPresentation.serializer())
    val BrowserTabs = Method("browser.tabs", BrowserOwner.serializer(), ListSerializer(BrowserTab.serializer()))
    val BrowserCommand = Method("browser.command", kinuvo.protocol.browser.BrowserCommand.serializer(), BrowserFrame.serializer().nullable)
    val BrowserChanged = EventType("browser.changed", BrowserOwner.serializer())

    val VoiceModels = Method("voice.models", Unit.serializer(), VoiceModelsState.serializer())
    val VoiceDownload = Method("voice.download", String.serializer(), Unit.serializer())
    val VoiceCancelDownload = Method("voice.cancelDownload", Unit.serializer(), Unit.serializer())
    val VoiceRemove = Method("voice.remove", String.serializer(), Unit.serializer())
    val VoiceSelect = Method("voice.select", VoiceSelection.serializer(), Unit.serializer())
    val VoiceLanguage = Method("voice.language", String.serializer(), Unit.serializer())
    val VoiceSetup = Method("voice.setup", Unit.serializer(), kinuvo.protocol.voice.VoiceSetup.serializer())
    val Transcribe = Method("voice.transcribe", TranscribeParams.serializer(), String.serializer())
    val Synthesize = Method("voice.synthesize", SynthesizeParams.serializer(), SpeechAudio.serializer())
    val CancelVoice = Method("voice.cancel", CancelVoiceParams.serializer(), Unit.serializer())
    val LiveVoiceStart = Method("voice.live.start", LiveVoiceParams.serializer(), LiveVoiceCall.serializer())
    val LiveVoiceSettings = Method("voice.live.settings", Unit.serializer(), kinuvo.protocol.voice.LiveVoiceSettings.serializer())
    val SaveLiveVoice = Method("voice.live.save", Boolean.serializer(), kinuvo.protocol.voice.LiveVoiceSettings.serializer())
    val LiveVoiceEnd = Method("voice.live.end", LiveVoiceRef.serializer(), Unit.serializer())
    /** Sent by the Device, unlike other events. */
    val LiveVoiceInput = EventType("voice.live.input", kinuvo.protocol.voice.LiveVoiceInput.serializer())
    val LiveVoiceOutput = EventType("voice.live.output", kinuvo.protocol.voice.LiveVoiceOutput.serializer())
    val Capabilities = Method("session.capabilities", CapabilitiesParams.serializer(), SessionCapabilities.serializer())
    val CapabilitiesChanged = EventType("capabilities.changed", Unit.serializer())
    val Models = Method("models.list", ModelsParams.serializer(), ModelsCatalog.serializer())
    val DiscoverEndpoint = Method("providers.discoverEndpoint", CustomEndpointParams.serializer(), ListSerializer(ModelInfo.serializer()))
    val SaveEndpoint = Method("providers.saveEndpoint", CustomEndpointParams.serializer(), ProviderRef.serializer())
    val Providers = Method("providers.list", Unit.serializer(), ListSerializer(ProviderInfo.serializer()))
    /** Starts signing in; the Server finishes it in the background and sends [ProvidersChanged]. */
    val LoginProvider = Method("providers.login", ProviderRef.serializer(), DeviceCode.serializer())
    val SetProviderApiKey = Method("providers.apiKey", SetProviderApiKeyParams.serializer(), Unit.serializer())
    val LogoutProvider = Method("providers.logout", ProviderRef.serializer(), Unit.serializer())
    /** Starts downloading a model to the Server; progress and the result follow in [ProvidersChanged]. */
    val DownloadProviderModel = Method("providers.download", ProviderModelRef.serializer(), Unit.serializer())
    val CancelProviderDownload = Method("providers.cancelDownload", ProviderRef.serializer(), Unit.serializer())
    val RemoveProviderModel = Method("providers.removeModel", ProviderModelRef.serializer(), Unit.serializer())
    val UploadAttachment = Method("attachments.upload", UploadAttachmentParams.serializer(), kinuvo.protocol.session.Attachment.serializer())
    val AppResource = Method("apps.resource", McpAppRequest.serializer(), McpAppResource.serializer())
    val AppCallTool = Method("apps.callTool", McpAppToolRequest.serializer(), JsonObject.serializer())
    val SurfaceImage = Method("surfaces.image", SurfaceImageParams.serializer(), SurfaceImageResult.serializer())
    val Attachment = Method("attachments.get", AttachmentRef.serializer(), AttachmentData.serializer())
    val DeviceTools = Method("device.tools", DeviceToolsParams.serializer(), Unit.serializer())
    /** Sets where the Server pushes this Device's Notifications. */
    val SubscribePush = Method("device.push", PushParams.serializer(), Unit.serializer())
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
    val SurfaceCatalogs = Method("surfaces.catalogs", SurfaceCatalogsParams.serializer(), Unit.serializer())
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
    val ImageModel = Method("models.image", Unit.serializer(), ProviderModelRef.serializer().nullable)
    val SaveImageModel = Method("models.saveImage", ProviderModelRef.serializer().nullable, ProviderModelRef.serializer().nullable)
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
    val Respond = Method("session.respond", RespondParams.serializer(), Boolean.serializer())
    /** Every Artifact, newest first. */
    val Artifacts = Method("artifacts.list", Unit.serializer(), ListSerializer(ArtifactInfo.serializer()))
    val Artifact = Method("artifact.get", ArtifactRef.serializer(), ArtifactData.serializer())
    val SaveArtifact = Method("artifact.save", ArtifactSave.serializer(), ArtifactInfo.serializer())
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
    val SaveTasksSettings = Method("tasks.saveSettings", SaveTasksParams.serializer(), TasksSetup.serializer())
    /** Every list a task provider has, whether the Board shows it or not. */
    val ProviderLists = Method("tasks.providerLists", TaskProviderRef.serializer(), ListSerializer(TaskListInfo.serializer()))
    val AuthorizeTasks = Method("tasks.authorize", TaskProviderRef.serializer(), ListSerializer(TaskListInfo.serializer()))
    val KnowledgeSettings = Method("knowledge.settings", Unit.serializer(), KnowledgeSetup.serializer())
    val SaveKnowledgeSettings = Method("knowledge.saveSettings", kinuvo.protocol.knowledge.KnowledgeSettings.serializer(), KnowledgeSetup.serializer())
    /** Reads the schema with unsaved settings, so a broken setup shows in Settings rather than to Agents. */
    val TestKnowledge = Method("knowledge.test", kinuvo.protocol.knowledge.KnowledgeSettings.serializer(), KnowledgeTestResult.serializer())
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
    val ExecutionCatalogChanged = EventType("execution.catalog.changed", kinuvo.protocol.execution.ExecutionCatalog.serializer())
    val ResumeExecution = Method("execution.resume", ExecutionRef.serializer(), kinuvo.protocol.execution.ExecutionSnapshot.serializer())
    val CloseExecution = Method("execution.close", ExecutionRef.serializer(), Unit.serializer())
    val ExecutionSnapshot = Method("execution.snapshot", ExecutionRef.serializer(), kinuvo.protocol.execution.ExecutionSnapshot.serializer())
    val ExecutionChanged = EventType("execution.changed", kinuvo.protocol.execution.ExecutionSnapshot.serializer())
    val SaveRemoteLink = Method("execution.remote.save", kinuvo.protocol.execution.SaveRemoteLink.serializer(), Unit.serializer())
    val GrantExecution = Method("execution.exposure.grant", ExposureGrant.serializer(), String.serializer())
    val ExecutionGrants = Method("execution.exposure.list", Unit.serializer(), ListSerializer(ExposureGrant.serializer()))
    val RevokeExecution = Method("execution.exposure.revoke", ExecutionRef.serializer(), Unit.serializer())
    val RemoteExecution = Method("execution.remote.call", RemoteExecutionCall.serializer(), RemoteExecutionResult.serializer())
    val DiscoverRemote = Method("execution.remote.discover", ExecutionRef.serializer(), RemoteExecutionResult.serializer())
    val ExecutionCatalog = Method("execution.catalog", Unit.serializer(), kinuvo.protocol.execution.ExecutionCatalog.serializer())
    val SaveHarnessConnection = Method("execution.connection.save", HarnessConnection.serializer(), HarnessConnectionStatus.serializer())
    val DeleteHarnessConnection = Method("execution.connection.delete", ExecutionRef.serializer(), Unit.serializer())
    val RegisterExternalAgent = Method("execution.agent.save", AgentRegistration.serializer(), AgentRegistration.serializer())
    val UnregisterExternalAgent = Method("execution.agent.delete", ExecutionRef.serializer(), Unit.serializer())

    val Update = EventType("session.update", SessionUpdate.serializer())
    /** A Session was created, renamed or changed status; sent to every client. */
    val SessionChanged = EventType("session.changed", SessionInfo.serializer())
    val Usage = EventType("usage", kinuvo.protocol.usage.Usage.serializer())
    val ProvidersChanged = EventType("providers.changed", ListSerializer(ProviderInfo.serializer()))
    /** Every Agent, after one was created, renamed or deleted. */
    val AgentsChanged = EventType("agents.changed", ListSerializer(AgentInfo.serializer()))
    val RoutinesChanged = EventType("routines.changed", kinuvo.protocol.routine.RoutinesChanged.serializer())
    val RoutineRan = EventType("routine.ran", kinuvo.protocol.routine.RoutineRan.serializer())
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
