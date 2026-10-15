import Foundation
import Security
import KotlinModules

final class AppleKeychain: NSObject, NativeKeychain {
    func read(service: String, account: String) -> String? {
        var query = item(service, account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func write(service: String, account: String, value: String) -> Bool {
        let attributes: [String: Any] = [kSecValueData as String: Data(value.utf8),
                                         kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemUpdate(item(service, account) as CFDictionary, attributes as CFDictionary)
        guard status == errSecItemNotFound else { return status == errSecSuccess }
        return SecItemAdd(item(service, account).merging(attributes) { $1 } as CFDictionary, nil) == errSecSuccess
    }

    func delete(service: String, account: String) {
        SecItemDelete(item(service, account) as CFDictionary)
    }

    private func item(_ service: String, _ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }
}
