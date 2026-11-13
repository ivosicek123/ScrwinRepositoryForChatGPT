import Foundation
import Security
import LocalAuthentication
import Shared

/// Klíč zařízení v Secure Enclave (P-256, ES256). Privátní klíč nikdy neopustí čip; použití vyžaduje biometrii.
/// Na simulátoru (bez Secure Enclave) se použije Keychain klíč chráněný kSecAttrAccessibleWhenUnlockedThisDeviceOnly.
final class SecureEnclaveSigner: NSObject, HardwareKeySigner {
    private let tag = "cz.faltyn.elfall.devicekey".data(using: .utf8)!
    private(set) var isHardwareBacked: Bool = false
    var algorithm: String { "ES256" }  // Kotlin default z rozhraní se do Swiftu nepřenáší

    private func loadOrCreate() throws -> SecKey {
        let q: [String: Any] = [kSecClass as String: kSecClassKey, kSecAttrApplicationTag as String: tag,
                                kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom, kSecReturnRef as String: true]
        var item: CFTypeRef?
        if SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess, let k = item {
            isHardwareBacked = SecureEnclaveSigner.enclaveAvailable
            return (k as! SecKey)
        }
        var err: Unmanaged<CFError>?
        let hw = SecureEnclaveSigner.enclaveAvailable
        let flags: SecAccessControlCreateFlags = hw ? [.privateKeyUsage, .biometryCurrentSet] : []
        guard let acl = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, flags, &err) else { throw err!.takeRetainedValue() }
        var attrs: [String: Any] = [kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom, kSecAttrKeySizeInBits as String: 256,
            kSecPrivateKeyAttrs as String: [kSecAttrIsPermanent as String: true, kSecAttrApplicationTag as String: tag, kSecAttrAccessControl as String: acl]]
        if hw { attrs[kSecAttrTokenID as String] = kSecAttrTokenIDSecureEnclave }
        guard let key = SecKeyCreateRandomKey(attrs as CFDictionary, &err) else { throw err!.takeRetainedValue() }
        isHardwareBacked = hw
        return key
    }

    static var enclaveAvailable: Bool {
        #if targetEnvironment(simulator)
        return false
        #else
        return true
        #endif
    }

    /// Podpis s předaným LAContextem (už ověřeným Face ID) – bez druhého dotazu na biometrii.
    func sign(_ msg: Data, context: LAContext) -> Data? {
        let q: [String: Any] = [kSecClass as String: kSecClassKey, kSecAttrApplicationTag as String: tag,
                                kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom, kSecReturnRef as String: true,
                                kSecUseAuthenticationContext as String: context]
        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess, let k = item else { _ = try? loadOrCreate(); return nil }
        var err: Unmanaged<CFError>?
        guard let der = SecKeyCreateSignature(k as! SecKey, .ecdsaSignatureMessageX962SHA256, msg as CFData, &err) as Data? else { return nil }
        return SecureEnclaveSigner.derToRaw(der)
    }

    func publicKeyX963() -> KotlinByteArray {
        guard let k = try? loadOrCreate(), let pub = SecKeyCopyPublicKey(k),
              let d = SecKeyCopyExternalRepresentation(pub, nil) as Data? else { return KotlinByteArray(size: 0) }
        return IosBridge.shared.toByteArray(d: d)
    }

    func signEs256(data: KotlinByteArray) -> KotlinByteArray {
        guard let k = try? loadOrCreate() else { return KotlinByteArray(size: 0) }
        var err: Unmanaged<CFError>?
        let msg = IosBridge.shared.toNSData(b: data) as Data
        guard let der = SecKeyCreateSignature(k, .ecdsaSignatureMessageX962SHA256, msg as CFData, &err) as Data? else { return KotlinByteArray(size: 0) }
        return IosBridge.shared.toByteArray(d: SecureEnclaveSigner.derToRaw(der))
    }

    /// ECDSA DER (SEQUENCE{INTEGER r, INTEGER s}) -> JOSE raw R||S (2×32 B).
    static func derToRaw(_ der: Data) -> Data {
        let b = [UInt8](der); var i = 2; if b[1] & 0x80 != 0 { i += Int(b[1] & 0x7f) }
        func int() -> [UInt8] { i += 1; let len = Int(b[i]); i += 1; var v = Array(b[i..<i+len]); i += len
            while v.count > 32 && v.first == 0 { v.removeFirst() }; return [UInt8](repeating: 0, count: 32 - v.count) + v }
        let r = int(); let s = int(); return Data(r + s)
    }
}
