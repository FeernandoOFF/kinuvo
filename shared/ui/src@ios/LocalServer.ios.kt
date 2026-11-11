package kinuvo.ui

import kinuvo.app.connection.*
import kinuvo.app.agent.*

import kinuvo.harness.apple.AppleLanguageModel
import kinuvo.harness.apple.AppleProvider
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.knowledgeConfig
import kinuvo.harness.core.store.saveKnowledgeConfig
import kinuvo.harness.server.EmbeddedServerHost
import kinuvo.harness.server.embeddedPlugins
import kinuvo.harness.core.plugin.ModelProviders
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

internal val iosOnDeviceSupport = OnDeviceSupport { provider -> provider != "apple" || appleModel?.unavailableCode("system") == null && appleModel != null }

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
                val embedded = embeddedPlugins(home, ModelProviders.of(AppleProvider(model)))
                EmbeddedServerHost.create(home, plugins = embedded.plugins, modelSources = embedded.modelSources).also { host ->
                    server = host
                    localHost = host
                    MainScope().launch { host.runtime.runActivity.collect { backgroundRuns?.update(it.runs, it.steps) } }
                }
            }
        }
        return Endpoint("local://kinuvo", local.token, local::connect)
    }

}
