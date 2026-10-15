import SwiftUI
import KotlinModules

private enum NativeRoute: Hashable {
    case board
    case knowledge(String?)
    case session(String?, String?)
}

final class NativeAppModel: ObservableObject {
    let app = NativeApp()
    @Published var state: AppState
    @Published fileprivate var path: [NativeRoute] = []
    @Published var fullApp = false
    var fullRoute: (any Route)?
    private var observation: StateObservation?
    private var links: StateObservation?
    private var pendingChat: RouteChat?
    private var generation: Int64

    init() {
        state = app.state
        generation = app.serverLifetime
        observation = app.observe { [weak self] state in
            guard let self else { return }
            precondition(Thread.isMainThread)
            if self.generation != self.app.serverLifetime {
                self.generation = self.app.serverLifetime
                self.path = []
            }
            self.state = state
            self.openPendingChat()
        }
        links = app.observeLinks { [weak self] route in
            guard let self, let route, !self.fullApp else { return }
            self.pendingChat = route
            self.openPendingChat()
        }
    }

    private func openPendingChat() {
        guard state.connection is ConnectionReady, !state.onboarding, !fullApp, let route = pendingChat else { return }
        pendingChat = nil
        app.consumeLink()
        path.append(.session(route.agentId, route.sessionId))
    }

    func open(_ route: any Route) {
        if let chat = route as? RouteChat { path.append(.session(chat.agentId, chat.sessionId)) }
        else if let knowledge = route as? RouteKnowledge { path.append(.knowledge(knowledge.note)) }
        else { fullRoute = route; fullApp = true }
    }

    func showDestination() {
        guard !fullApp else { return }
        if case let .session(agent, session) = path.last {
            let remembered = agent.map { app.viewModel.lastView(agentId: $0) }
            let route = remembered?.sessionId == session ? remembered : nil
            app.viewModel.showView(route: route ?? RouteChat(agentId: agent, sessionId: session, slot: UUID().uuidString, compose: false))
        } else { app.viewModel.showView(route: RouteBoard.shared) }
    }

    func selectAgent(_ id: String) {
        app.viewModel.selectAgent(agentId: id,
            current: nil, compact: true) { self.open($0) }
    }

    func active(_ active: Bool) {
        (state.connection as? ConnectionReady)?.client.setActive(active: active)
    }

    deinit {
        observation?.cancel()
        links?.cancel()
        app.close()
    }
}

struct NativeShell: View {
    @StateObject private var model = NativeAppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack(path: $model.path) {
            Group {
                if model.state.onboarding || !(model.state.connection is ConnectionReady) {
                    SelectedComposeScreen { model.app.connectionHost() }
                } else {
                    List {
                        Section("Features") {
                            NavigationLink("Board", value: NativeRoute.board)
                                .disabled(model.state.capabilities?.features.contains(ServerFeature.tasks) == false)
                            NavigationLink("Knowledge", value: NativeRoute.knowledge(nil))
                                .disabled(model.state.capabilities?.features.contains(ServerFeature.knowledge) == false)
                            Button("Full app · all features") { model.fullRoute = nil; model.fullApp = true }
                        }
                        Section("Agents") {
                            ForEach(model.state.agents, id: \.id) { agent in
                                Button(agent.name) { model.selectAgent(agent.id) }
                            }
                        }
                        Section("Servers") {
                            ForEach(Array(model.state.servers.enumerated()), id: \.offset) { _, server in
                                Button(server is ServerEmbedded ? "This device" : ((server as? ServerRemote)?.name ?? (server as? ServerRemote)?.url ?? "Server")) {
                                    model.app.viewModel.use(server: server)
                                }
                            }
                            Button("Add server") { model.app.viewModel.addServer() }
                        }
                    }
                }
            }
            .navigationTitle("Kinuvo")
            .navigationDestination(for: NativeRoute.self) { route in
                switch route {
                case .board:
                    NativeBoard(app: model.app, agents: model.state.agents) { agent, session in
                        model.path.append(.session(agent, session))
                    }
                    .id(model.app.serverLifetime)
                case .knowledge(let note):
                    SelectedComposeScreen {
                        model.app.knowledgeHost(note: note, onOpen: { model.path.append(.knowledge($0)) }, onSettings: model.open)
                    }
                    .navigationTitle("Knowledge")
                    .navigationBarTitleDisplayMode(.inline)
                case .session(let agent, let session):
                    SelectedComposeScreen {
                        model.app.sessionHost(agentId: agent, sessionId: session,
                            onOpen: { model.path.append(.session($0, $1)) },
                            onResolved: { route in
                                guard !model.fullApp, model.path.last == .session(agent, session) else { return }
                                model.app.viewModel.showView(route: route)
                                if agent != route.agentId || session != route.sessionId {
                                    model.path[model.path.count - 1] = .session(route.agentId, route.sessionId)
                                }
                            },
                            onSettings: model.open, onNavigate: model.open,
                            onMenu: { model.fullRoute = nil; model.fullApp = true })
                    }
                    .navigationTitle("Session")
                    .navigationBarTitleDisplayMode(.inline)
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Full app") { model.fullRoute = nil; model.fullApp = true }
                }
            }
        }
        .sheet(isPresented: $model.fullApp) {
            NavigationStack {
                FullComposeApp(app: model.app, route: model.fullRoute).ignoresSafeArea()
                    .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { model.fullApp = false } } }
            }
        }
        .onChange(of: model.path) { _, _ in model.showDestination() }
        .onChange(of: model.fullApp) { _, _ in model.showDestination() }
        .onChange(of: scenePhase) { _, phase in model.active(phase == .active) }
        .onChange(of: model.state.connection is ConnectionReady) { _, ready in if ready { model.active(scenePhase == .active) } }
    }
}

struct SelectedComposeScreen: UIViewControllerRepresentable {
    let create: () -> NativeScreenHost

    class Coordinator {
        var host: NativeScreenHost?
    }
    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIViewController(context: Context) -> UIViewController {
        let host = create()
        context.coordinator.host = host
        return host.controller
    }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
    static func dismantleUIViewController(_ controller: UIViewController, coordinator: Coordinator) {
        coordinator.host?.close()
        coordinator.host = nil
    }
}

private struct FullComposeApp: UIViewControllerRepresentable {
    let app: NativeApp
    let route: (any Route)?
    func makeUIViewController(context: Context) -> UIViewController { app.fullAppController(route: route) }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
