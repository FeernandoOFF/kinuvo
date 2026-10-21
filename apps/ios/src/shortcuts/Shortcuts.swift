import AppIntents
import KotlinModules

struct StartVoiceChat: AppIntent {
    static let title: LocalizedStringResource = "Start Voice Chat"
    static let description = IntentDescription("Opens a new chat with your Agent and starts talking.")
    static let supportedModes: IntentModes = .foreground(.immediate)
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

    @MainActor
    func perform() async throws -> some IntentResult {
        DeepLinksKt.openNewChat(voice: true, message: nil)
        return .result()
    }
}

struct NewChat: AppIntent {
    static let title: LocalizedStringResource = "New Chat"
    static let description = IntentDescription("Opens a new chat with your Agent, ready to type.")
    static let supportedModes: IntentModes = .foreground(.immediate)
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

    @MainActor
    func perform() async throws -> some IntentResult {
        DeepLinksKt.openNewChat(voice: false, message: nil)
        return .result()
    }
}

struct AskAgent: AppIntent {
    static let title: LocalizedStringResource = "Ask Kinuvo"
    static let description = IntentDescription("Sends a message to your Agent in a new chat.")
    static let supportedModes: IntentModes = .foreground(.immediate)
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

    @Parameter(title: "Message", requestValueDialog: "What do you want to ask?")
    var message: String

    @MainActor
    func perform() async throws -> some IntentResult {
        DeepLinksKt.openNewChat(voice: false, message: message)
        return .result()
    }
}

struct KinuvoShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: StartVoiceChat(), phrases: ["Talk to \(.applicationName)", "Start a voice chat in \(.applicationName)"],
                    shortTitle: "Voice Chat", systemImageName: "waveform")
        AppShortcut(intent: AskAgent(), phrases: ["Ask \(.applicationName)", "Send a message to \(.applicationName)"],
                    shortTitle: "Ask", systemImageName: "text.bubble")
        AppShortcut(intent: NewChat(), phrases: ["New chat in \(.applicationName)", "Start a new \(.applicationName) chat"],
                    shortTitle: "New Chat", systemImageName: "square.and.pencil")
    }
}
