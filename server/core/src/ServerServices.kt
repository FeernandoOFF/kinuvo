package kinuvo.server

import arrow.core.raise.Raise
import kinuvo.harness.Agent
import kinuvo.protocol.mcp.McpAppRequest
import kinuvo.protocol.mcp.McpAppToolRequest
import kinuvo.protocol.session.Attachment
import kinuvo.protocol.session.AttachmentRef
import kinuvo.protocol.session.UploadAttachmentParams
import kinuvo.protocol.surface.SurfaceImage
import kinuvo.server.artifacts.Artifacts
import kinuvo.server.devices.PeerRegistry
import kinuvo.server.execution.AgentExecution
import kinuvo.server.execution.ExecutionService
import kinuvo.server.knowledge.Knowledge
import kinuvo.server.plugin.McpManagement
import kinuvo.server.reflection.ReflectionReview
import kinuvo.server.screens.Computers
import kinuvo.server.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a route calls on: the services behind every request, shared by all connections. */
class ServerServices internal constructor(
    internal val harness: HarnessRuntime,
    val auth: PeerRegistry,
    val admin: Admin,
    val execution: ExecutionService,
    val dispatch: AgentExecution,
    val sessions: SessionService,
    val teams: Teams,
    val routines: RoutineService,
    val workflows: Workflows,
    val browser: BrowserAccess,
    val voice: VoiceService,
    val reflection: ReflectionReview,
    val logins: ProviderLogins,
    val notifications: Notifications,
    val log: SessionLog,
    val knowledge: Knowledge,
    val tasks: Tasks,
    val artifacts: Artifacts,
    val mcp: McpManagement,
    val secrets: Secrets,
    val computers: Computers,
    val settings: ServerSettings,
    private val prepareAttachment: (UploadAttachmentParams) -> UploadAttachmentParams,
    private val onCapabilitiesChanged: () -> Unit,
) {
    fun capabilitiesChanged() = onCapabilitiesChanged()

    suspend fun uploadAttachment(p: UploadAttachmentParams): Attachment = withContext(Dispatchers.Default) { harness.attachments.upload(prepareAttachment(p)) }

    suspend fun downloadAttachment(ref: AttachmentRef) = withContext(Dispatchers.Default) { harness.attachments.download(ref.id) }

    suspend fun modelCatalog(refresh: Boolean) = harness.modelCatalog(refresh)

    context(_: Raise<ProviderError>)
    fun customEndpoints() = harness.customEndpoints()

    suspend fun appResource(request: McpAppRequest) = harness.appResource(request)

    suspend fun appCallTool(agent: Agent, request: McpAppToolRequest) = harness.appCallTool(agent, request)

    suspend fun surfaceImage(url: String): SurfaceImage = fetchSurfaceImage(url)
}
