import KotlinModules

/** Reminders and Calendar for the on-device Server: EventKit through the Swift core shared with the Mac app. */
final class AppleEventKitSystem: NSObject, AppleSystem {
    private let eventKit = AppleEventKit()

    func call(method: String, params: String) -> String {
        eventKit.call(method: method, params: params)
    }
}
