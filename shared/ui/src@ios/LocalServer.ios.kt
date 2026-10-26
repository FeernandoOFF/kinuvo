package kinuvo.ui

import kinuvo.app.connection.*
import kinuvo.app.agent.*

import kinuvo.harness.apple.AppleLanguageModel
import kinuvo.harness.apple.AppleProvider
import kinuvo.harness.codex.CodexProvider
import kinuvo.harness.codex.OpenAIProvider
import kinuvo.harness.koog.compatibleProviders
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.knowledgeConfig
import kinuvo.harness.core.store.saveKnowledgeConfig
import kinuvo.harness.firecrawl.FirecrawlPlugin
import kinuvo.harness.ntfy.NtfyPlugin
import kinuvo.harness.executor.ExecutorPlugin
import kinuvo.harness.anytype.AnytypePlugin
import kinuvo.harness.mcp.McpPlugin
import kinuvo.harness.server.EmbeddedServerHost
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

private var appleModel: AppleLanguageModel? = null
private var backgroundRuns: BackgroundRuns? = null
private var localHost: EmbeddedServerHost? = null

/** Keeps the iPhone Server's runs going after the app leaves the screen, for as long as iOS allows. */
interface BackgroundRuns {
    /** [runs] going and the [steps] they've taken so far; called on the main thread. */
    fun update(runs: Int, steps: Long)
}

fun installBackgroundRuns(keeper: BackgroundRuns) { backgroundRuns = keeper }

/** Stops every run on the iPhone Server, e.g. when iOS ends the app's background time; [completion] runs once they've saved. */
fun stopLocalRuns(completion: () -> Unit) {
    MainScope().launch { try { localHost?.runtime?.stopRuns() } finally { completion() } }
}

fun installAppleLanguageModel(model: AppleLanguageModel) { appleModel = model }

internal class IosEmbeddedServer : EmbeddedServer {
    private val lock = Mutex()
    private var server: EmbeddedServerHost? = null

    override suspend fun endpoint(): Endpoint {
        val local = lock.withLock {
            server ?: run {
                val root = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
                val model = checkNotNull(appleModel) { "Apple's on-device model bridge is unavailable" }
                val home = Path(root, "Kinuvo")
                val moved = kinuvo.ui.connection.folderAccess?.restore().orEmpty()
                AgentFiles(home).let { files ->
                    val knowledge = files.knowledgeConfig()
                    knowledge.path?.let(moved::get)?.let { files.saveKnowledgeConfig(knowledge.copy(path = it)) }
                }
                EmbeddedServerHost.create(home, plugins = listOf(AppleProvider(model), CodexProvider(), OpenAIProvider(AgentFiles(home)), McpPlugin(AgentFiles(home)), FirecrawlPlugin(), NtfyPlugin(),
                    ExecutorPlugin(), AnytypePlugin(AgentFiles(home))) + compatibleProviders(AgentFiles(home), attachments = kinuvo.harness.core.store.FileAttachments(Path(home, "attachments"))::bytes)).also { host ->
                    server = host
                    localHost = host
                    MainScope().launch { host.runtime.runActivity.collect { backgroundRuns?.update(it.runs, it.steps) } }
                }
            }
        }
        return Endpoint("local://kinuvo", local.token, local::connect)
    }

}
