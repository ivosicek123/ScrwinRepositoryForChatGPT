#if RUST_CORE
import Foundation

/// Vault přes Rust jádro (core/, libsodium uvnitř) a UniFFI vazby (ElfallCore.xcframework).
/// Klíč zůstává v Keychainu (sdílený se SodiumVault), Rust ho neukládá.
final class RustCoreVault: LocalVault {
    static let shared = RustCoreVault()

    func seal(field: String, _ plaintext: String) -> String? {
        guard let k = SodiumVault.shared.dataKey() else { return nil }
        return try? vaultSeal(key: Data(k), field: field, plaintext: plaintext)
    }

    func open(field: String, _ sealed: String) -> String? {
        guard let k = SodiumVault.shared.dataKey() else { return nil }
        return try? vaultOpen(key: Data(k), field: field, sealed: sealed)
    }
}

/// Vstup podpisu (modul 1) – stejný formát jako Android/web, dokázaný ve SPARK.
enum SigningInput {
    static func frame(_ msg: Data) throws -> Data { try signingInput(msg: msg) }
}
#endif
