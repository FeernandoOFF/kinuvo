package kinuvo.ui

import kinuvo.app.connection.*
import kinuvo.app.device.*
import kinuvo.app.session.*
import kinuvo.app.agent.*
import kinuvo.app.board.*
import kinuvo.app.knowledge.*
import kinuvo.app.navigation.*
import kinuvo.app.artifacts.*

import kinuvo.client.KinuvoClient
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Storage and Device details for the current platform. */
internal expect val platformModule: Module

private val appModule = module {
    single { getOrNull<MobileTestMode>()?.servers ?: Servers(get(), getOrNull(), get()) }
    single { Notifications(getAll()) }
    single { DeviceAccess(get(), get(), getOrNull()) }
    single { ConnectedServer(get(), get()) }
    single { get<ConnectedServer>().sessions }
    single { get<ConnectedServer>().routines }
    single { get<ConnectedServer>().config }
    single { get<ConnectedServer>().board }
    single { get<ConnectedServer>().knowledge }
    single { get<ConnectedServer>().devices }
    viewModel { OnboardingViewModel(get()) }
    viewModel { AppViewModel(get(), get(), get(), get()) }
    viewModel { (client: KinuvoClient, route: Route.Chat) -> SessionViewModel(client, get(), route) }
    viewModel { (engine: SpeechEngine, session: SessionViewModel) -> VoiceViewModel(engine, session) }
    viewModel { (engine: SpeechEngine) -> DictationViewModel(engine) }
    viewModel { (route: Route.Settings) -> SettingsViewModel(get(), get(), get(), route) }
    viewModel { BoardViewModel(get()) }
    viewModel { (route: Route.Knowledge) -> KnowledgeViewModel(get(), route) }
    viewModel { (client: KinuvoClient, route: Route.Artifacts) -> ArtifactsViewModel(client, route, get()) }
    viewModel { (client: KinuvoClient, route: Route.Computers) -> kinuvo.app.computer.ComputersViewModel(client, route, get()) }
}

/** Starts DI once per process; [modules] add or override bindings, e.g. an [EmbeddedServer]. */
fun startKinuvo(vararg modules: Module, config: KoinApplication.() -> Unit = {}): KoinApplication = startKoin {
    config()
    modules(appModule, platformModule, *modules)
}
