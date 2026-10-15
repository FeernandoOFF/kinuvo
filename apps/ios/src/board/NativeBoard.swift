import SwiftUI
import KotlinModules

private final class BoardModel: ObservableObject {
    let app: NativeApp
    @Published var state: BoardState?
    private var destination: BoardDestination?
    private var observation: StateObservation?

    init(app: NativeApp) { self.app = app }
    var viewModel: BoardViewModel? { destination?.viewModel }
    func start() {
        guard destination == nil else { return }
        let destination = app.board()
        self.destination = destination
        state = destination.state
        observation = destination.observe { [weak self] state in
            precondition(Thread.isMainThread)
            self?.state = state
        }
    }
    func stop() {
        observation?.cancel()
        observation = nil
        destination?.close()
        destination = nil
    }
    deinit { stop() }
}

struct NativeBoard: View {
    @StateObject private var model: BoardModel
    let agents: [AgentInfo]
    let openSession: (String?, String?) -> Void
    @State private var title = ""
    @State private var assignee = ""

    init(app: NativeApp, agents: [AgentInfo], openSession: @escaping (String?, String?) -> Void) {
        _model = StateObject(wrappedValue: BoardModel(app: app))
        self.agents = agents
        self.openSession = openSession
    }

    var body: some View {
        List {
            if let state = model.state {
                board(state)
            } else { ProgressView() }
        }
        .navigationTitle("Board · SwiftUI")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.start() }
        .onDisappear { model.stop() }
        .refreshable { _ = model.viewModel?.refresh() }
    }

    @ViewBuilder private func board(_ state: BoardState) -> some View {
        if let error = state.board.error ?? state.errors.last { Text(error).foregroundStyle(.red) }
        Toggle("Show done", isOn: Binding(get: { state.showDone }, set: { model.viewModel?.showDone(show: $0) }))
        newTask
        if let tasks = state.board.tasks {
            ForEach(TaskStatus.entries, id: \.name) { status in
                if status != .done || state.showDone {
                    Section(statusTitle(status)) {
                        ForEach(tasks.filter { $0.status == status }, id: \.id) { task in
                            taskRow(task)
                        }
                    }
                }
            }
            if tasks.isEmpty { Text("No tasks yet.").foregroundStyle(.secondary) }
        } else { ProgressView("Loading Board…") }
    }

    private var newTask: some View {
        Section("New task") {
            TextField("Task title", text: $title)
            Picker("Agent", selection: $assignee) {
                Text("Unassigned").tag("")
                ForEach(agents, id: \.id) { Text($0.name).tag($0.id) }
            }
            Button("Add task") {
                model.viewModel?.add(params: CreateTaskParams(title: title, list: nil, notes: "", due: nil, assignee: assignee.isEmpty ? nil : assignee))
            }.disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
    }

    private func taskRow(_ task: TaskInfo) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(task.title)
            if !task.notes.isEmpty { Text(task.notes).font(.caption).foregroundStyle(.secondary) }
            Picker("Status", selection: Binding(get: { task.status }, set: {
                model.viewModel?.update(params: UpdateTaskParams(taskId: task.id, title: nil, status: $0, assignee: nil, due: nil))
            })) {
                ForEach(TaskStatus.entries, id: \.name) { Text(statusTitle($0)).tag($0) }
            }
            if let session = task.sessionIds.last {
                Button("Open session") { openSession(task.assignee, session) }
            }
        }
    }
}

private func statusTitle(_ status: TaskStatus) -> String {
    switch status {
    case .todo: return "To do"
    case .inprogress: return "In progress"
    case .waiting: return "Waiting"
    default: return "Done"
    }
}
