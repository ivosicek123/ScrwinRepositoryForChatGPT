import Foundation
import Security
import Sodium

/// At-rest šifrování citlivých lokálních dat (libsodium XChaCha20-Poly1305-IETF, AD = název pole).
/// Datový klíč (32 B) je v Keychainu jako kSecAttrAccessibleWhenUnlockedThisDeviceOnly (nezálohuje se, nepřenáší).
/// Společné rozhraní lokálního vaultu – SodiumVault (swift-sodium) i RustCoreVault mají formát `sv1:`.
protocol LocalVault {
    func seal(field: String, _ plaintext: String) -> String?
    func open(field: String, _ sealed: String) -> String?
}

/// Volba implementace: Rust jádro jen při kompilační podmínce RUST_CORE (výchozí vypnuto).
enum Vaults {
    static var local: LocalVault {
        #if RUST_CORE
        return RustCoreVault.shared
        #else
        return SodiumVault.shared
        #endif
    }
}

final class SodiumVault: LocalVault {
    static let shared = SodiumVault()
    private let sodium = Sodium()
    private let prefix = "sv1:"
    private let keyAccount = "cz.faltyn.elfall.vault.v1"

    /// Datový klíč sdílený oběma implementacemi (žádná migrace dat při přepnutí příznaku).
    func dataKey() -> Bytes? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: keyAccount, kSecReturnData as String: true]
        var item: CFTypeRef?
        if SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess, let d = item as? Data { return Bytes(d) }
        let key = sodium.aead.xchacha20poly1305ietf.key()
        let add: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: keyAccount,
                                  kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly, kSecValueData as String: Data(key)]
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess ? key : nil
    }

    func seal(field: String, _ plaintext: String) -> String? {
        guard let k = dataKey(),
              let r: Bytes = sodium.aead.xchacha20poly1305ietf.encrypt(message: Bytes(plaintext.utf8), secretKey: k, additionalData: Bytes("elfall/v1/\(field)".utf8))
        else { return nil }
        return prefix + Data(r).base64EncodedString()  // nonce || ciphertext+tag
    }

    /// nil = data změněna, podvržena nebo patří jinému poli.
    func open(field: String, _ sealed: String) -> String? {
        guard sealed.hasPrefix(prefix), let k = dataKey(), let raw = Data(base64Encoded: String(sealed.dropFirst(prefix.count))) else { return nil }
        guard let m = sodium.aead.xchacha20poly1305ietf.decrypt(nonceAndAuthenticatedCipherText: Bytes(raw), secretKey: k,
                                                                 additionalData: Bytes("elfall/v1/\(field)".utf8)) else { return nil }
        return String(bytes: m, encoding: .utf8)
    }
}

/// PIN/heslo: Argon2id (crypto_pwhash_str), OWASP: m = 19 MiB, t = 2, p = 1.
enum PinHasher {
    static let opsLimit = 2
    static let memLimit = 19 * 1024 * 1024
    static func hash(_ pin: String) -> String? { Sodium().pwHash.str(passwd: Bytes(pin.utf8), opsLimit: opsLimit, memLimit: memLimit) }
    static func verify(_ pin: String, _ stored: String) -> Bool { stored.hasPrefix("$argon2id$") && Sodium().pwHash.strVerify(hash: stored, passwd: Bytes(pin.utf8)) }
    static func needsRehash(_ stored: String) -> Bool { Sodium().pwHash.strNeedsRehash(hash: stored, opsLimit: opsLimit, memLimit: memLimit) }
}
