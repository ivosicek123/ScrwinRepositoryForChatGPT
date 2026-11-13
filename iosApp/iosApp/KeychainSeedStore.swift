import Foundation
import LocalAuthentication
import Security

/// Stores the mnemonic in the Keychain behind Face ID / Touch ID (biometryCurrentSet, this device only).
/// Reading the item triggers the system biometric prompt. Nothing is synced to iCloud.
enum KeychainSeedStore {
    private static let service = "cz.faltyn.eleanor.seed"
    private static let account = "mnemonic"

    static func hasSeed() -> Bool {
        let ctx = LAContext(); ctx.interactionNotAllowed = true
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecAttrAccount as String: account, kSecUseAuthenticationContext as String: ctx]
        let st = SecItemCopyMatching(q as CFDictionary, nil)
        return st == errSecSuccess || st == errSecInteractionNotAllowed
    }

    static func save(_ words: [String]) throws {
        var err: Unmanaged<CFError>?
        guard let ac = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
                                                       .biometryCurrentSet, &err) else { throw err!.takeRetainedValue() as Error }
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                   kSecAttrAccount as String: account]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = words.joined(separator: " ").data(using: .utf8)!
        add[kSecAttrAccessControl as String] = ac
        let st = SecItemAdd(add as CFDictionary, nil)
        guard st == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(st)) }
    }

    /// Prompts Face ID. Returns nil if cancelled.
    static func load(reason: String) throws -> [String]? {
        let ctx = LAContext(); ctx.localizedReason = reason
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecAttrAccount as String: account, kSecReturnData as String: true,
                                kSecUseAuthenticationContext as String: ctx]
        var out: CFTypeRef?
        let st = SecItemCopyMatching(q as CFDictionary, &out)
        if st == errSecUserCanceled || st == errSecAuthFailed { return nil }
        guard st == errSecSuccess, let d = out as? Data, let s = String(data: d, encoding: .utf8) else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(st))
        }
        return s.split(separator: " ").map(String.init)
    }

    static func wipe() {
        SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service] as CFDictionary)
    }
}
