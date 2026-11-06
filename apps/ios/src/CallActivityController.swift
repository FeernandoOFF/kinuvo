import ActivityKit
import Foundation
import KotlinModules

@MainActor
final class CallActivityController {
    static let shared = CallActivityController()
    private var bridge: NativeLiveCalls?
    private var observation: StateObservation?
    private var activity: Activity<CallAttributes>?
    private var pending: Task<Void, Never>?
    private var muted = false
    private var latest: ActiveCall?

    func start() {
        guard bridge == nil else { return }
        let bridge = NativeLiveCalls()
        self.bridge = bridge
        observation = bridge.observe { [weak self] call in
            self?.changed(call)
        }
    }

    func toggleMute() { bridge?.mute(muted: !muted) }
    func end() { bridge?.end() }

    private func changed(_ call: ActiveCall?) {
        latest = call
        guard let call, call.voice.phase != VoicePhase.off else {
            pending?.cancel()
            pending = nil
            let old = activity
            activity = nil
            Task { await old?.end(nil, dismissalPolicy: .immediate) }
            return
        }
        muted = call.voice.paused
        guard pending == nil else { return }
        pending = Task {
            try? await Task.sleep(for: .milliseconds(500))
            guard !Task.isCancelled, let call = latest else { return }
            defer { pending = nil }
            let attributes = CallAttributes(agentName: call.agentName, agentId: call.agentId, sessionId: call.sessionId, ink: bridge?.ink(agentId: call.agentId) ?? 0xFFA98BF0)
            let content = ActivityContent(state: CallAttributes.ContentState(
                phase: call.voice.phase.name, muted: call.voice.paused,
                caption: String((call.voice.caption.isEmpty ? call.voice.heard : call.voice.caption).prefix(160)),
                startedAt: Date(timeIntervalSince1970: Double(call.startedAt) / 1000)), staleDate: nil)
            if let old = activity, old.attributes.sessionId != attributes.sessionId {
                await old.end(nil, dismissalPolicy: .immediate)
                activity = nil
            }
            if let activity { await activity.update(content) }
            else if ActivityAuthorizationInfo().areActivitiesEnabled {
                activity = try? Activity.request(attributes: attributes, content: content)
            }
        }
    }
}
