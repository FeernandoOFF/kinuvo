package dev.kinuvo.ios.embedded

import kinuvo.app.connection.EmbeddedServer
import kinuvo.app.connection.Endpoint
import kinuvo.app.connection.OnDeviceSupport
import kinuvo.plugins.apple.AppleSystem
import kinuvo.plugins.apple.ApplePlugin
import kinuvo.providers.apple.AppleLanguageModel
import kinuvo.providers.apple.AppleProvider
import kinuvo.server.embedded.EmbeddedServerHost
import kinuvo.server.embedded.embeddedPlugins
import kinuvo.server.iosKeychain
import kinuvo.server.plugin.Keychain
import kinuvo.server.providers.ModelProviders
import kinuvo.server.store.AgentFiles
import kinuvo.server.store.knowledgeConfig
import kinuvo.server.store.saveKnowledgeConfig
import kinuvo.ui.NativeKeychain
import kinuvo.ui.connection.FolderAccess
import kinuvo.ui.startKinuvo
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import org.koin.dsl.module
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

private var backgroundRuns: BackgroundRuns? = null
private var localHost: EmbeddedServerHost? = null

/** Keeps the iPhone Server's runs going after the app leaves the screen, for as long as iOS allows. */
interface BackgroundRuns {
    /** [runs] going and the [steps] they've taken so far; called on the main thread. */
    fun update(runs: Int, steps: Long)
}

/** Stops every run on the iPhone Server, e.g. when iOS ends the app's background time; [completion] runs once they've saved. */
fun stopLocalRuns(completion: () -> Unit) {
    MainScope().launch { try { localHost?.runtime?.stopRuns() } finally { completion() } }
}

/**
 * Starts the shared Koin graph with the on-device Server bound as the [EmbeddedServer].
 * [folders] reopens the folders the user picked outside the sandbox; their moved paths reach the Server when it starts.
 */
fun startKinuvoWithEmbeddedServer(model: AppleLanguageModel, keychain: NativeKeychain, folders: FolderAccess, runs: BackgroundRuns, apple: AppleSystem) {
    backgroundRuns = runs
    iosKeychain = { service, account ->
        object : Keychain {
            override val name = "iOS Keychain"
            override suspend fun read() = keychain.read(service, account)
            override suspend fun write(token: String) = check(keychain.write(service, account, token)) { "Couldn't save to the iOS Keychain" }
            override suspend fun delete() = keychain.delete(service, account)
        }
    }
    startKinuvo(module {
        single<OnDeviceSupport> { OnDeviceSupport { provider -> provider != "apple" || model.unavailableCode("system") == null } }
        single<EmbeddedServer> { IosEmbeddedServer(model, folders, apple) }
    })
}

internal class IosEmbeddedServer(private val model: AppleLanguageModel, private val folders: FolderAccess, private val apple: AppleSystem) : EmbeddedServer {
    private val lock = Mutex()
    private var server: EmbeddedServerHost? = null

    override suspend fun endpoint(): Endpoint {
        val local = lock.withLock {
            server ?: run {
                val root = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
                val home = Path(root, "Kinuvo")
                val moved = folders.restore()
                AgentFiles(home).let { files ->
                    val knowledge = files.knowledgeConfig()
                    knowledge.path?.let(moved::get)?.let { files.saveKnowledgeConfig(knowledge.copy(path = it)) }
                }
                val embedded = embeddedPlugins(home, ModelProviders.of(AppleProvider(model)), listOf(ApplePlugin(apple)))
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
