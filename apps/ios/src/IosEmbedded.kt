package dev.kinuvo.ios.embedded

import kinuvo.app.connection.EmbeddedServer
import kinuvo.protocol.capability.ServerFeature
import kinuvo.app.connection.Endpoint
import kinuvo.app.connection.OnDeviceSupport
import kinuvo.app.connection.ServerSharing
import kinuvo.app.connection.SharingOption
import kinuvo.app.connection.SharingState
import kinuvo.client.TailcatForwarder
import kinuvo.tailcat.Tailcat
import kinuvo.plugins.apple.AppleSystem
import kinuvo.plugins.apple.ApplePlugin
import kinuvo.providers.apple.AppleLanguageModel
import kinuvo.providers.apple.AppleProvider
import kinuvo.server.DeviceAuth
import kinuvo.server.NetworkListener
import kinuvo.server.NetworkSecurity
import kinuvo.server.devices.SharedPeers
import kinuvo.server.embedded.EmbeddedServerHost
import kinuvo.server.embedded.EmbeddedSharing
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
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDevice

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
        single<TailcatForwarder> { Tailcat }
        single<EmbeddedServer> { IosEmbeddedServer(model, folders, apple) }
    })
}

/**
 * The iPhone's own Server. With Any network on, other Devices reach it through Tailcat while the app is in the foreground;
 * iOS suspends the tunnel with the app.
 */
internal class IosEmbeddedServer(private val model: AppleLanguageModel, private val folders: FolderAccess, private val apple: AppleSystem) : EmbeddedServer {
    private val lock = Mutex()
    private var server: EmbeddedServerHost? = null
    private val home = Path(NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String, "Kinuvo")
    private val auth = DeviceAuth(Path(home, "server-devices.json"))
    private val defaults = NSUserDefaults.standardUserDefaults
    private val tailcat get() = Tailcat.takeIf { it.supported }
    private var tailcatListener: NetworkListener? = null
    init { auth.offlineTailcatRevoke = { phone -> tailcat?.revoke(Path(home, "tailcat"), phone) } }
    private val sharingHost = EmbeddedSharing(
        Path(home, "tailcat"), UIDevice.currentDevice.name, { tailcat },
        { tailcat -> tailcat && defaults.boolForKey(TAILCAT) },
        { tailcat, enabled -> if (tailcat) defaults.setBool(enabled, TAILCAT) },
        {},
        {
            val listener = NetworkListener(requireNotNull(server).runtime, auth, NetworkSecurity(trustedProxies = emptySet()), remoteOnly = true)
            tailcatListener = listener
            listener.start("127.0.0.1", emptyList())
        },
        { tailcatListener?.stop(); tailcatListener = null },
        { tunnel, pairable -> auth.tailcat(tunnel); auth.pairable = pairable },
    )
    private val tailcatSharing = object : ServerSharing {
        override val enabled get() = sharingHost.enabled
        override val state get() = when (sharingHost.state) {
            EmbeddedSharing.State.Unavailable -> SharingState.Unavailable
            EmbeddedSharing.State.Off -> SharingState.Off
            EmbeddedSharing.State.Starting -> SharingState.Starting
            EmbeddedSharing.State.On -> SharingState.On
            EmbeddedSharing.State.Error -> SharingState.Error("Could not start Tailcat; try again")
        }
        override suspend fun share(enabled: Boolean) { host(); sharingHost.share(enabled) }
        override suspend fun reset() { host(); sharingHost.reset() }
    }
    override val sharingOptions get() = mapOf(SharingOption.Lan to null, SharingOption.Tailcat to tailcatSharing)
    override val sharesInBackground = false
    // Any network belongs to this Server, so restore it at launch even when the app opens on a remote Server.
    init { if (tailcat != null && defaults.boolForKey(TAILCAT)) MainScope().launch { runCatching { host() } } }

    override suspend fun endpoint(): Endpoint {
        val local = host()
        return Endpoint("local://kinuvo", local.token, local::connect)
    }

    private suspend fun host(): EmbeddedServerHost = lock.withLock {
        server ?: run {
            val moved = folders.restore()
            AgentFiles(home).let { files ->
                val knowledge = files.knowledgeConfig()
                knowledge.path?.let(moved::get)?.let { files.saveKnowledgeConfig(knowledge.copy(path = it)) }
            }
            val embedded = embeddedPlugins(home, ModelProviders.of(AppleProvider(model)), listOf(ApplePlugin(apple)))
            EmbeddedServerHost.create(home, plugins = embedded.plugins, modelSources = embedded.modelSources,
                features = setOf(ServerFeature.Attachments, ServerFeature.DeviceTools, ServerFeature.Computer), peers = { SharedPeers(it, auth) }).also { host ->
                server = host
                localHost = host
                MainScope().launch { host.runtime.runActivity.collect { backgroundRuns?.update(it.runs, it.steps) } }
                sharingHost.restore()
            }
        }
    }

    private companion object {
        const val TAILCAT = "kinuvo-server.tailcat"
    }
}
