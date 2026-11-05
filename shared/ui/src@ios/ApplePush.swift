import UIKit
import UserNotifications
import KotlinModules

/// APNs through the Push relay. Registers when Kotlin starts it, asks for Notifications when Kotlin requests it (after
/// onboarding or on opening a chat), reports this install's subscription while they're allowed, and opens a tapped
/// Notification's Session.
final class ApplePush: NSObject, NativePush, UNUserNotificationCenterDelegate {
    static let shared = ApplePush()
    private var relay = ""
    private var update: ((PushSubscription?) -> Void)?
    private var token: String?

    func start(relay: String, update: @escaping (PushSubscription?) -> Void) {
        DispatchQueue.main.async {
            self.relay = relay
            self.update = update
            NotificationCenter.default.addObserver(self, selector: #selector(self.refresh), name: UIApplication.didBecomeActiveNotification, object: nil)
            UIApplication.shared.registerForRemoteNotifications()
        }
    }

    func requestPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in
            DispatchQueue.main.async { self.refresh() }
        }
    }

    func registered(_ deviceToken: Data) {
        token = deviceToken.map { String(format: "%02x", $0) }.joined()
        refresh()
    }

    @objc private func refresh() {
        guard let update, let token else { return }
        #if DEBUG
        let endpoint = "\(relay)/apns/sandbox/\(token)"
        #else
        let endpoint = "\(relay)/apns/production/\(token)"
        #endif
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            let allowed = [.authorized, .provisional, .ephemeral].contains(settings.authorizationStatus)
            let subscription = allowed ? PushKeys.saved(create: true).map {
                PushSubscription(endpoint: endpoint, p256dh: $0.p256dh.base64URL, auth: $0.auth.base64URL)
            } : nil
            DispatchQueue.main.async { update(subscription) }
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        if let link = response.notification.request.content.userInfo["link"] as? String { DeepLinksKt.openLink(url: link) }
        completionHandler()
    }
}

/// Hands APNs device tokens to [ApplePush].
final class PushAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = ApplePush.shared
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        ApplePush.shared.registered(deviceToken)
    }
}
