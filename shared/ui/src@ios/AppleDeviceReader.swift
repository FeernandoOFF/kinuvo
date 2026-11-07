import Contacts
import KotlinModules

final class AppleDeviceReader: NSObject, DeviceReader {
    let kinds = ["Contacts"]
    private let contacts = CNContactStore()

    func authorize(kind: String, completion: @escaping (String?) -> Void) {
        guard kind == "Contacts" else { completion("Unsupported Device tool"); return }
        contacts.requestAccess(for: .contacts) { granted, _ in
            DispatchQueue.main.async { completion(granted ? nil : "Contacts access was denied. Enable it in iOS Settings.") }
        }
    }

    func read(requestJson: String, completion: @escaping (String) -> Void) {
        guard let bytes = requestJson.data(using: .utf8),
              let request = (try? JSONSerialization.jsonObject(with: bytes)) as? [String: Any],
              let kind = request["kind"] as? String else {
            finish("Unavailable", message: "Invalid Device request", completion: completion); return
        }
        if kind == "Contacts" {
            DispatchQueue.global(qos: .userInitiated).async { self.readContacts(request, completion: completion) }
        } else { finish("Unsupported", completion: completion) }
    }

    private func readContacts(_ request: [String: Any], completion: @escaping (String) -> Void) {
        let status = CNContactStore.authorizationStatus(for: .contacts)
        guard status == .authorized || status == .limited else {
            finish("Denied", message: "Contacts access is denied. Enable it in iOS Settings.", completion: completion); return
        }
        guard let query = request["query"] as? String, !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, query.count <= 200 else {
            finish("Unavailable", message: "Supply a Contacts query", completion: completion); return
        }
        let limit = min(20, max(1, request["limit"] as? Int ?? 20))
        let keys = [CNContactGivenNameKey, CNContactFamilyNameKey, CNContactOrganizationNameKey, CNContactPhoneNumbersKey, CNContactEmailAddressesKey] as [CNKeyDescriptor]
        let fetch = CNContactFetchRequest(keysToFetch: keys)
        fetch.sortOrder = .userDefault
        let needle = query.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
        let digits = query.filter(\.isNumber)
        let phoneQuery = !digits.isEmpty && query.allSatisfy { $0.isNumber || "+()- .".contains($0) }
        var matches: [[String: Any]] = []
        do {
            try contacts.enumerateContacts(with: fetch) { contact, stop in
                let name = [contact.givenName, contact.familyName].filter { !$0.isEmpty }.joined(separator: " ")
                let displayName = name.isEmpty ? contact.organizationName : name
                let phones = contact.phoneNumbers.map { $0.value.stringValue }
                let emails = contact.emailAddresses.map { String($0.value) }
                let matched = displayName.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current).contains(needle)
                    || emails.contains { $0.lowercased().contains(query.lowercased()) }
                    || (phoneQuery && phones.contains { $0.filter(\.isNumber).contains(digits) })
                if matched {
                    matches.append(["name": String(displayName.prefix(500)), "phones": Array(phones.prefix(20)).map { String($0.prefix(100)) }, "emails": Array(emails.prefix(20)).map { String($0.prefix(500)) }])
                    if matches.count > limit { stop.pointee = true }
                }
            }
            finish("Ready", data: ["source": "Apple Contacts", "access": status == .limited ? "selected contacts" : "all contacts", "contacts": Array(matches.prefix(limit)), "truncated": matches.count > limit], completion: completion)
        } catch { finish("Unavailable", message: "Contacts could not be read", completion: completion) }
    }

    private func finish(_ status: String, data: [String: Any] = [:], message: String? = nil, completion: @escaping (String) -> Void) {
        var result: [String: Any] = ["status": status, "data": data]
        if let message { result["message"] = message }
        let bytes = try? JSONSerialization.data(withJSONObject: result, options: [.sortedKeys])
        let json = bytes.flatMap { String(data: $0, encoding: .utf8) } ?? "{\"status\":\"Unavailable\",\"message\":\"Device result could not be encoded\"}"
        DispatchQueue.main.async { completion(json) }
    }
}
