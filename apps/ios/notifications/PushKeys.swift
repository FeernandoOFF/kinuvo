import CryptoKit
import Foundation
import Security

/// This install's Web Push keys (RFC 8291). The app makes them; the Keychain group shares them with the Notification Service Extension.
struct PushKeys {
    let key: P256.KeyAgreement.PrivateKey
    let auth: Data

    var p256dh: Data { key.publicKey.x963Representation }

    /// The saved keys; made and saved first when [create] is set.
    static func saved(create: Bool = false) -> PushKeys? {
        var query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "dev.kinuvo.push", kSecAttrAccount as String: "keys"]
        if let group = Bundle.main.object(forInfoDictionaryKey: "KinuvoKeychainGroup") as? String { query[kSecAttrAccessGroup as String] = group }
        var result: CFTypeRef?
        var read = query
        read[kSecReturnData as String] = true
        if SecItemCopyMatching(read as CFDictionary, &result) == errSecSuccess, let data = result as? Data, data.count == 48,
           let key = try? P256.KeyAgreement.PrivateKey(rawRepresentation: data.prefix(32)) {
            return PushKeys(key: key, auth: data.suffix(16))
        }
        guard create else { return nil }
        let keys = PushKeys(key: P256.KeyAgreement.PrivateKey(), auth: Data(SymmetricKey(size: .bits128).withUnsafeBytes { Array($0) }))
        SecItemDelete(query as CFDictionary)
        query[kSecValueData as String] = keys.key.rawRepresentation + keys.auth
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(query as CFDictionary, nil) == errSecSuccess ? keys : nil
    }

    /// The plaintext of a single-record `aes128gcm` [record] encrypted to these keys.
    func open(_ record: Data) throws -> Data {
        let bytes = [UInt8](record)
        guard bytes.count > 21, bytes.count > 21 + Int(bytes[20]) + 16 else { throw PushError() }
        let salt = Data(bytes[0..<16])
        let end = 21 + Int(bytes[20])
        let sender = try P256.KeyAgreement.PublicKey(x963Representation: bytes[21..<end])
        let ikm = try key.sharedSecretFromKeyAgreement(with: sender).hkdfDerivedSymmetricKey(
            using: SHA256.self, salt: auth, sharedInfo: Data("WebPush: info\0".utf8) + p256dh + sender.x963Representation, outputByteCount: 32)
        let cek = HKDF<SHA256>.deriveKey(inputKeyMaterial: ikm, salt: salt, info: Data("Content-Encoding: aes128gcm\0".utf8), outputByteCount: 16)
        let nonce = HKDF<SHA256>.deriveKey(inputKeyMaterial: ikm, salt: salt, info: Data("Content-Encoding: nonce\0".utf8), outputByteCount: 12)
        let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce.withUnsafeBytes { Data($0) }), ciphertext: bytes[end..<(bytes.count - 16)], tag: bytes.suffix(16))
        var plain = [UInt8](try AES.GCM.open(box, using: cek))
        while plain.last == 0 { plain.removeLast() }
        guard plain.popLast() == 2 else { throw PushError() }
        return Data(plain)
    }
}

struct PushError: Error {}

extension Data {
    init?(base64URL: String) {
        var text = base64URL.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        text += String(repeating: "=", count: (4 - text.count % 4) % 4)
        self.init(base64Encoded: text)
    }

    var base64URL: String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
}
