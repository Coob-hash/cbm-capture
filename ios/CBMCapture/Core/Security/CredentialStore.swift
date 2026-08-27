import Foundation
import Security

/// Keychain storage for the bearer token.
///
/// `UserDefaults` would be the easy place to put this and the wrong one: it is readable from a
/// file-system backup and survives in plaintext. `kSecAttrAccessibleAfterFirstUnlock` is the
/// right accessibility class here because the background upload task needs the token while the
/// device is locked but after the worker has unlocked it at least once since boot.
struct CredentialStore: Sendable {

    private let service: String
    private let account = "cbm.capture.bearer-token"

    init(service: String = Bundle.main.bundleIdentifier ?? "ai.cbm.capture") {
        self.service = service
    }

    func save(token: String) throws {
        let data = Data(token.utf8)
        var query = baseQuery()
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock

        let status = SecItemAdd(query as CFDictionary, nil)
        switch status {
        case errSecSuccess:
            return
        case errSecDuplicateItem:
            let attributes: [String: Any] = [kSecValueData as String: data]
            let updateStatus = SecItemUpdate(baseQuery() as CFDictionary, attributes as CFDictionary)
            guard updateStatus == errSecSuccess else { throw KeychainError(status: updateStatus) }
        default:
            throw KeychainError(status: status)
        }
    }

    func token() -> String? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data,
              let token = String(data: data, encoding: .utf8),
              !token.isEmpty else {
            return nil
        }
        return token
    }

    func deleteToken() {
        SecItemDelete(baseQuery() as CFDictionary)
    }

    private func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
    }

    struct KeychainError: Error, LocalizedError {
        let status: OSStatus
        var errorDescription: String? {
            "Could not store the access token securely (Keychain error \(status))."
        }
    }
}
