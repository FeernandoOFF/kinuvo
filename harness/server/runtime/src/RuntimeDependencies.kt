package kinuvo.harness.server

import kinuvo.harness.core.plugin.*
import kinuvo.harness.core.*
import kinuvo.harness.core.artifacts.Artifacts
import kinuvo.harness.core.knowledge.Knowledge
import kinuvo.harness.core.routines.Routines
import kinuvo.harness.core.skills.SkillRepository
import kinuvo.harness.core.store.*
import kinuvo.harness.core.tasks.Tasks
import kinuvo.harness.core.tools.Tool
import kinuvo.harness.core.tools.WebSearch
import kinuvo.harness.core.voice.VoiceProviders
import kinuvo.protocol.*
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    val tracing: AgentTracing? = null,
    var voice: VoiceProviders = VoiceProviders(),
    val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    val plugins get() = resources.plugins
    val mcp = plugins.firstNotNullOfOrNull { it.mcp } ?: McpManagement.Unavailable
    val execCheck = plugins.firstNotNullOfOrNull { it.execCheck }
    val usage: StateFlow<Usage?> = modelProviders.firstNotNullOfOrNull { it.usage } ?: MutableStateFlow(null)
}
