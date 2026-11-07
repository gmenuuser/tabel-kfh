import Foundation
import Security

/// Where the device token is kept. The app uses the Keychain; the unit tests keep it in memory (the CI simulator
/// runs the app unsigned, without Keychain access).
protocol TokenStore {
    func save(_ token: String)
    func token() -> String?
    func delete()
}

/// The device token in the Keychain: this phone only, readable after the first unlock (the sync runs in the
/// background too).
enum Keychain {
    static let service = "ru.kfh.tabel.session"
    private static let account = "deviceToken"

    private static var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    static func save(_ token: String) {
        delete()
        var item = query
        item[kSecValueData as String] = Data(token.utf8)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(item as CFDictionary, nil)
    }

    static func token() -> String? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &result) == errSecSuccess, let data = result as? Data else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    static func delete() {
        SecItemDelete(query as CFDictionary)
    }
}

/// `TokenStore` over `Keychain`.
struct KeychainTokenStore: TokenStore {
    func save(_ token: String) { Keychain.save(token) }
    func token() -> String? { Keychain.token() }
    func delete() { Keychain.delete() }
}
