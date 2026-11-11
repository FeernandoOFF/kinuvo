import UserNotifications

/// Decrypts a pushed Notification (the relay's `k` field) and shows its title and body. Anything it can't open keeps the relay's placeholder.
final class NotificationService: UNNotificationServiceExtension {
    override func didReceive(_ request: UNNotificationRequest, withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        contentHandler(open(request) ?? request.content)
    }

    private func open(_ request: UNNotificationRequest) -> UNNotificationContent? {
        guard let record = (request.content.userInfo["k"] as? String).flatMap({ Data(base64URL: $0) }),
              let plain = try? PushKeys.saved()?.open(record),
              let push = try? JSONDecoder().decode(PushMessage.self, from: plain),
              let content = request.content.mutableCopy() as? UNMutableNotificationContent else { return nil }
        content.title = push.title
        content.body = push.body
        if let session = push.sessionId { content.threadIdentifier = session }
        if let agent = push.agentId {
            content.userInfo["link"] = "kinuvo://chat/\(agent)" + (push.sessionId.map { "/\($0)" } ?? "") + (push.serverId.map { "?server=\($0)" } ?? "")
        }
        if push.urgent == true { content.interruptionLevel = .timeSensitive }
        return content
    }
}

/// What a pushed Notification decrypts to; `kinuvo.protocol.push.PushMessage`.
private struct PushMessage: Decodable {
    let title: String
    let body: String
    let agentId: String?
    let sessionId: String?
    let urgent: Bool?
    let serverId: String?
}
