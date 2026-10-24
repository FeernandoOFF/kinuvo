import UIKit
import BackgroundTasks
import KotlinModules

/// Keeps the iPhone Server's runs going after the app leaves the screen. A run the user starts also starts a
/// continued-processing task, shown as a Live Activity once the app is in the background; if iOS refuses one,
/// the app asks for the usual short grace period instead. When iOS ends either, the runs stop and save.
final class AppleBackgroundRuns: NSObject, BackgroundRuns {
    private let identifier = Bundle.main.bundleIdentifier! + ".run"
    private var runs: Int32 = 0
    private var steps: Int64 = 0
    private var submitted = false
    private var task: BGContinuedProcessingTask?
    private var taskStart: Int64 = 0
    private var grace = UIBackgroundTaskIdentifier.invalid

    override init() {
        super.init()
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: .main) { [weak self] task in
            guard let self, let task = task as? BGContinuedProcessingTask else { task.setTaskCompleted(success: false); return }
            self.started(task)
        }
        NotificationCenter.default.addObserver(self, selector: #selector(backgrounded), name: UIApplication.didEnterBackgroundNotification, object: nil)
    }

    func update(runs: Int32, steps: Int64) {
        self.runs = runs
        self.steps = steps
        guard runs > 0 else { finish(success: true); return }
        submit()
        if let task {
            task.progress.totalUnitCount = steps - taskStart + 1
            task.progress.completedUnitCount = steps - taskStart
        }
    }

    /// Continued processing has to start in the foreground, from something the user did: here, sending a message.
    private func submit() {
        guard !submitted, task == nil, UIApplication.shared.applicationState == .active else { return }
        let request = BGContinuedProcessingTaskRequest(identifier: identifier, title: "Kinuvo is working", subtitle: "Your Agent is finishing its reply")
        request.strategy = .fail
        submitted = (try? BGTaskScheduler.shared.submit(request)) != nil
    }

    private func started(_ task: BGContinuedProcessingTask) {
        submitted = false
        guard runs > 0 else { task.setTaskCompleted(success: true); return }
        self.task = task
        taskStart = steps
        task.progress.totalUnitCount = 1
        // Stopping the runs reports none left; the task is completed here, once, as failed.
        task.expirationHandler = { [weak self] in
            Task { @MainActor in
                guard let self else { task.setTaskCompleted(success: false); return }
                self.task = nil
                self.stopRuns { task.setTaskCompleted(success: false) }
            }
        }
        endGrace()
    }

    @objc private func backgrounded() {
        guard runs > 0, task == nil, grace == .invalid else { return }
        grace = UIApplication.shared.beginBackgroundTask(withName: "Kinuvo run") { [weak self] in
            self?.stopRuns { self?.endGrace() }
        }
    }

    private func finish(success: Bool) {
        task?.setTaskCompleted(success: success)
        task = nil
        endGrace()
    }

    private func endGrace() {
        guard grace != .invalid else { return }
        UIApplication.shared.endBackgroundTask(grace)
        grace = .invalid
    }

    private func stopRuns(then done: @escaping () -> Void) {
        LocalServer_iosKt.stopLocalRuns { DispatchQueue.main.async(execute: done) }
    }
}
