package kinuvo.server

import kinuvo.harness.AgentDocs
import kinuvo.server.plugin.McpManagement
import kinuvo.server.plugin.*
import kinuvo.server.artifacts.Artifacts
import kinuvo.server.knowledge.Knowledge
import kinuvo.server.routines.Routines
import kinuvo.server.skills.SkillRepository
import kinuvo.server.store.*
import kinuvo.server.tasks.Tasks
import kinuvo.server.plugin.Tool
import kinuvo.server.plugin.WebSearch
import kinuvo.server.plugin.VoiceProviders
import kinuvo.protocol.*
import kotlin.time.Clock
import kotlinx.io.files.Path
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kinuvo.server.providers.ModelProvider

interface ReflectionProgress {
    fun get(): Map<String, Int>
    fun save(progress: Map<String, Int>)
}

interface Attachments {
    fun upload(params: UploadAttachmentParams): Attachment
    fun get(id: String): Attachment
    fun download(id: String): AttachmentData
    fun bytes(attachment: Attachment): ByteArray
}

/** Execution accepts domain adapters, with no installation paths or concrete file construction. */
class RuntimeDependencies(
    val store: Store,
    val docs: AgentDocs,
    val skills: SkillRepository,
    val trustRules: TrustRulesRepository,
    val routines: Routines,
    val artifacts: Artifacts,
    val knowledge: Knowledge,
    val tasks: Tasks,
    val attachments: Attachments,
    val settings: ServerSettings,
    val secrets: Secrets,
    val reflectionProgress: ReflectionProgress,
    val serverId: String,
    val resources: ServerResources,
    val modelProviders: List<ModelProvider>,
    val modelCatalog: suspend (Boolean) -> ModelsCatalog,
    val webFetch: (WebSearch?) -> Tool,
    /** Where the built-in harness records traces; null when tracing is off. */
    val traces: Path? = null,
    var voice: VoiceProviders = VoiceProviders(),
    val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    val plugins get() = resources.plugins
    val mcp = plugins.firstNotNullOfOrNull { it.mcp } ?: McpManagement.Unavailable
    val execCheck = plugins.firstNotNullOfOrNull { it.execCheck }
    val usage: StateFlow<Usage?> = modelProviders.firstNotNullOfOrNull { it.usage } ?: MutableStateFlow(null)
}
