import Foundation
import Security

/// Stores the Overdrive server bearer token in the macOS Keychain (ADR 0044).
/// The token is never persisted in `UserDefaults`, plists, ride records, or logs.
public final class KeychainStore {
    private let service: String
    private let account: String

    public init(service: String = "app.overdrive.ride", account: String = "sync-bearer-token") {
        self.service = service
        self.account = account
    }

    public func readToken() -> String {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data, let text = String(data: data, encoding: .utf8) else {
            return ""
        }
        return Self.sanitizeToken(text)
    }

    public func writeToken(_ raw: String) {
        let clean = Self.sanitizeToken(raw)
        if clean.isEmpty {
            deleteToken()
            return
        }
        let encoded = Data(clean.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let attributes: [String: Any] = [
            kSecValueData as String: encoded,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let updated = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if updated == errSecItemNotFound {
            var addQuery = query
            addQuery[kSecValueData as String] = encoded
            addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            _ = SecItemAdd(addQuery as CFDictionary, nil)
        }
    }

    public func deleteToken() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        _ = SecItemDelete(query as CFDictionary)
    }

    /// Strips control characters and surrounding spaces and limits length to 512 chars (spec §4.9).
    public static func sanitizeToken(_ raw: String) -> String {
        let cleaned = raw.filter { scalar in
            guard let ascii = scalar.asciiValue else { return true }
            return ascii >= 0x20 && ascii != 0x7F
        }.trimmingCharacters(in: .whitespacesAndNewlines)
        return String(cleaned.prefix(512))
    }
}
