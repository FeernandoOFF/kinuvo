import ActivityKit
import AppIntents
import Foundation

struct CallAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var phase: String
        var muted: Bool
        var caption: String
        var startedAt: Date
    }
    var agentName: String
    var agentId: String
    var sessionId: String
    /// The Agent's colour as 0xAARRGGBB.
    var ink: Int64
}

struct MuteCallIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Mute or unmute"
    func perform() async throws -> some IntentResult {
#if !KINUVO_CALL_WIDGET
        await MainActor.run { CallActivityController.shared.toggleMute() }
#endif
        return .result()
    }
}

struct EndCallIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "End voice chat"
    func perform() async throws -> some IntentResult {
#if !KINUVO_CALL_WIDGET
        await MainActor.run { CallActivityController.shared.end() }
#endif
        return .result()
    }
}
