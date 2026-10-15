package kinuvo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.composeunstyled.TooltipHost
import kinuvo.app.agent.OnboardingViewModel
import kinuvo.app.board.BoardDestination
import kinuvo.app.connection.*
import kinuvo.app.knowledge.KnowledgeViewModel
import kinuvo.app.native.DestinationLifetime
import kinuvo.app.native.StateObservation
import kinuvo.app.navigation.Route
import kinuvo.app.navigation.SettingsSection
import kinuvo.app.session.SessionViewModel
import kinuvo.ui.session.ChatScreen
import kinuvo.ui.navigation.rememberShellChrome
import kinuvo.ui.connection.ConnectScreen
import kinuvo.ui.agent.OnboardingScreen
import kinuvo.ui.design.HarnessTheme
import kinuvo.ui.design.LocalPopovers
import kinuvo.ui.design.Popovers
import kinuvo.ui.knowledge.KnowledgeScreen
import org.koin.mp.KoinPlatform
import platform.UIKit.UIViewController

/** The native shell and selected Compose hosts share this one app-scoped connection owner. */
class NativeApp {
    private val lifetime = DestinationLifetime()
    private val koin get() = KoinPlatform.getKoin()
    val viewModel: AppViewModel

    init {
        if (KoinPlatform.getKoinOrNull() == null) startKinuvo()
        viewModel = lifetime.own(koin.get<AppViewModel>())
    }

    val state get() = viewModel.state.value
    val serverLifetime get() = viewModel.serverLifetime
    fun observe(onState: (AppState) -> Unit): StateObservation = lifetime.observe(viewModel.state, onState)
    fun observeLinks(onLink: (Route.Chat?) -> Unit): StateObservation = lifetime.observe(pendingLink, onLink)
    fun consumeLink() { pendingLink.value = null }
    fun board(): BoardDestination = BoardDestination(koin.get<ConnectedServer>().board)
    fun close() = lifetime.close()

    fun fullAppController(route: Route?): UIViewController = ComposeUIViewController { KinuvoApp(viewModel, route, manageActivity = false) }

    /** Only setup/pairing is hosted here. The connected hierarchy belongs to SwiftUI. */
    fun connectionHost(): NativeScreenHost = NativeScreenHost { life ->
        val state by viewModel.state.collectAsState()
        if (state.onboarding) {
            val onboarding = remember { life.own(koin.get<OnboardingViewModel>()) }
            OnboardingScreen(viewModel, onboarding)
        } else ConnectScreen(
            error = (state.connection as? Connection.Unpaired)?.error,
            connecting = (state.connection as? Connection.Connecting)?.let { it.server ?: Server.Embedded },
            onConnect = viewModel::pair,
            servers = state.servers,
            onSelectServer = viewModel::use,
            initialUrl = state.pendingPair?.server ?: (state.connection as? Connection.Unpaired)?.server?.url ?: koin.get<Device>().serverUrl,
            pending = state.pendingPair,
            initialName = (state.connection as? Connection.Unpaired)?.server?.name.orEmpty(),
            onRenameServer = viewModel::rename,
            onForgetServer = { viewModel.forget(it) },
            onCancel = state.returnTo?.let { { viewModel.cancelAddServer() } },
        )
    }

    fun sessionHost(agentId: String?, sessionId: String?, onOpen: (String?, String?) -> Unit, onResolved: (Route.Chat) -> Unit,
                    onSettings: (Route.Settings) -> Unit, onNavigate: (Route) -> Unit, onMenu: () -> Unit): NativeScreenHost = NativeScreenHost { life ->
        val client = requireNotNull((viewModel.state.value.connection as? Connection.Ready)?.client)
        val session = remember {
            val route = agentId?.let(viewModel::lastView)?.takeIf { it.sessionId == sessionId } ?: Route.Chat(agentId, sessionId)
            life.own(SessionViewModel(client, koin.get(), route))
        }
        val state by session.state.collectAsState()
        LaunchedEffect(state.route) { onResolved(state.route) }
        ChatScreen(viewModel, session, false, rememberShellChrome(), onOpen = { onOpen(it.agentId, it.sessionId) },
            onSettings = { onSettings(Route.Settings(it)) }, onNavigate = onNavigate, onToggleShell = onMenu)
    }

    /** One selected screen. Callbacks hand every navigation decision back to the native destination. */
    fun knowledgeHost(note: String?, onOpen: (String?) -> Unit, onSettings: (Route.Settings) -> Unit): NativeScreenHost = NativeScreenHost { life ->
        val knowledge = remember { life.own(KnowledgeViewModel(koin.get(), Route.Knowledge(note))) }
        KnowledgeScreen(viewModel, knowledge, onOpen = { onOpen(it.note) }, onBack = null, onSettings = { viewModel.state.value.agents.firstOrNull()?.let { onSettings(Route.Settings(it.id, SettingsSection.Knowledge)) } })
    }
}

class NativeScreenHost internal constructor(content: @Composable (DestinationLifetime) -> Unit) : ViewModelStoreOwner {
    private val lifetime = DestinationLifetime()
    override val viewModelStore get() = lifetime.store
    val controller = ComposeUIViewController {
        val popovers = remember { Popovers() }
        CompositionLocalProvider(LocalViewModelStoreOwner provides this, LocalPopovers provides popovers) {
            HarnessTheme { TooltipHost(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) { content(lifetime); popovers.Host() }
            } }
        }
    }
    fun close() = lifetime.close()
}
