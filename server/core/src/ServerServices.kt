package kinuvo.server

import kinuvo.protocol.session.Attachment
import kinuvo.protocol.session.AttachmentRef
import kinuvo.protocol.session.UploadAttachmentParams
import kinuvo.protocol.surface.SurfaceImage
import kinuvo.server.devices.PeerRegistry
import kinuvo.server.execution.AgentExecution
import kinuvo.server.execution.ExecutionService
import kinuvo.server.reflection.ReflectionReview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a route calls on: the services behind every request, shared by all connections. */
class ServerServices internal constructor(
    val harness: HarnessRuntime,
    val auth: PeerRegistry,
    val admin: Admin,
    val execution: ExecutionService,
    val dispatch: AgentExecution,
    val sessions: SessionService,
    val routines: RoutineService,
    val browser: BrowserAccess,
    val voice: VoiceService,
    val reflection: ReflectionReview,
    val logins: ProviderLogins,
    val notifications: Notifications,
    private val prepareAttachment: (UploadAttachmentParams) -> UploadAttachmentParams,
    private val onCapabilitiesChanged: () -> Unit,
) {
    fun capabilitiesChanged() = onCapabilitiesChanged()

    suspend fun uploadAttachment(p: UploadAttachmentParams): Attachment = withContext(Dispatchers.Default) { harness.attachments.upload(prepareAttachment(p)) }

    suspend fun downloadAttachment(ref: AttachmentRef) = withContext(Dispatchers.Default) { harness.attachments.download(ref.id) }

    suspend fun surfaceImage(url: String): SurfaceImage = fetchSurfaceImage(url)
}
