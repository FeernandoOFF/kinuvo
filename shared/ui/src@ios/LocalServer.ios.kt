package kinuvo.ui

import kinuvo.app.connection.*
import kinuvo.app.agent.*

import kinuvo.harness.apple.AppleLanguageModel
import kinuvo.harness.apple.AppleProvider
import kinuvo.harness.codex.CodexProvider
import kinuvo.harness.codex.OpenAIProvider
import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.firecrawl.FirecrawlPlugin
import kinuvo.harness.mcp.McpPlugin
import kinuvo.harness.server.EmbeddedServerHost
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

private var appleModel: AppleLanguageModel? = null

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
                EmbeddedServerHost.create(home, plugins = listOf(AppleProvider(model), CodexProvider(), OpenAIProvider(AgentFiles(home)), McpPlugin(AgentFiles(home)), FirecrawlPlugin())).also { server = it }
            }
        }
        return Endpoint("local://kinuvo", local.token, local::connect)
    }

}
