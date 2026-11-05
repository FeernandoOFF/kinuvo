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
        content.userInfo = [:]
        content.categoryIdentifier = ""
        for (key, value) in ["serverId": push.serverId, "agentId": push.agentId, "sessionId": push.sessionId, "approvalId": push.approvalId] {
            if let value { content.userInfo[key] = value }
        }
        if push.serverId != nil {
            if push.kind == "Reply", push.agentId != nil, push.sessionId != nil { content.categoryIdentifier = "kinuvo.reply" }
            if push.kind == "Approval", push.approvalId != nil { content.categoryIdentifier = "kinuvo.approval" }
        }
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

/// What a pushed Notification decrypts to; `kinuvo.protocol.PushMessage`.
private struct PushMessage: Decodable {
    let title: String
    let body: String
    let agentId: String?
    let sessionId: String?
    let urgent: Bool?
    let serverId: String?
    let kind: String?
    let approvalId: String?
}
