package kinuvo.app

import kinuvo.protocol.workflow.WorkflowId
import kinuvo.app.workflow.WorkflowViewModel
import kinuvo.app.agent.*
import kinuvo.app.team.TeamViewModel
import kinuvo.protocol.team.TeamId
import kinuvo.app.settings.*
import kinuvo.app.onboarding.*
import kinuvo.app.artifacts.ArtifactsViewModel
import kinuvo.app.board.BoardViewModel
import kinuvo.app.connection.*
import kinuvo.app.device.DeviceAccess
import kinuvo.app.home.HomeViewModel
import kinuvo.app.knowledge.KnowledgeViewModel
import kinuvo.app.navigation.Route
import kinuvo.app.screen.LiveViewModel
import kinuvo.app.session.*
import kinuvo.app.surface.UiCatalogs
import kinuvo.app.voice.ServerVoiceViewModel
import kinuvo.client.KinuvoClient
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Repositories and ViewModels shared by every client; hosts add their platform's bindings, e.g. [Preferences] and [Device]. */
val appModule = module {
    single { getOrNull<MobileTestMode>()?.servers ?: Servers(get(), getOrNull(), get()) }
    single { Notifications(getAll()) }
    single { LiveCalls() }
    single { AiConsent(get(), getOrNull<MobileTestMode>()?.let { it.override == null } == true) }
    single { DeviceAccess(get(), get(), getOrNull()) }
    single { ConnectedServer(get(), get(), getOrNull(), getOrNull() ?: OnDeviceSupport.All) }
    single { get<ConnectedServer>().surfaceImages }
    single { get<ConnectedServer>().sessions }
    single { get<ConnectedServer>().routines }
    single { get<ConnectedServer>().workflows }
    viewModel { (id: WorkflowId) -> WorkflowViewModel(get(), id) }
    single { get<ConnectedServer>().teams }
    viewModel { (id: TeamId) -> TeamViewModel(get(), id) }
    single { get<ConnectedServer>().config }
    single { get<ConnectedServer>().harnesses }
    single { get<ConnectedServer>().board }
    single { get<ConnectedServer>().knowledge }
    single { get<ConnectedServer>().devices }
    viewModel { EndpointViewModel(get<ConfigRepository>()) }
    viewModel { ServerVoiceViewModel(get()) }
    viewModel { OnboardingViewModel(get(), getOrNull() ?: NotificationPermission.None) }
    viewModel { AppViewModel(get(), get(), get(), get(), getOrNull() ?: ClientCache.None, getOrNull() ?: NotificationPermission.None, getOrNull<UiCatalogs>()?.all.orEmpty()) }
    viewModel { (client: KinuvoClient, route: Route.Chat) -> SessionViewModel(client, get(), route, get()) }
    viewModel { (engine: SpeechEngine, session: SessionViewModel) -> VoiceViewModel(engine, session, get(), get()) }
    viewModel { (transcriber: Transcriber) -> DictationViewModel(if (transcriber is SpeechEngine) ServerSpeech(transcriber, get()) else transcriber) }
    viewModel { (route: Route.Settings) -> SettingsViewModel(get(), get(), get(), route) }
    viewModel { BoardViewModel(get()) }
    viewModel { AgentInspectorViewModel(get()) }
    viewModel { (app: AppViewModel) -> HomeViewModel(app.state, get()) }
    viewModel { (route: Route.Knowledge) -> KnowledgeViewModel(get(), route) }
    viewModel { (client: KinuvoClient, route: Route.Artifacts) -> ArtifactsViewModel(client, route, get()) }
    viewModel { (route: Route) -> LiveViewModel(get<ConnectedServer>(), route) }
}
