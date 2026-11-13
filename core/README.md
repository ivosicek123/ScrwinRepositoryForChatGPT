# elfall-core (Rust) – fáze 2 ZERO-TRUST-ROADMAP

Jedna implementace kryptografie pro Android/iOS (UniFFI, feature `ffi`) a web (wasm-bindgen, feature `wasm`).
Kryptografie: **libsodium** (crate `libsodium-sys-stable`, staví ověřený zdrojový tarball libsodium):
`crypto_sign` (Ed25519), `crypto_aead_xchacha20poly1305_ietf`, `crypto_pwhash_str_alg` (Argon2id).
`unsafe` je povolen jen v `src/sodium.rs` (FFI s kontrolou délek). Toolchain: Rust 1.90.0.

**wasm32:** libsodium-sys nelze přeložit pro `wasm32-unknown-unknown`, proto web dostává jen
rámování + ověření podpisu v čistém Rustu (`ed25519-dalek` `verify_strict`, ~92 KB). Alternativa
do budoucna: libsodium.js (oficiální Emscripten build) pro případné další operace.

**Android:** UniFFI vazby v `androidApp/src/main/kotlin/uniffi/elfall_core/`, knihovny přes
`cargo ndk` (arm64-v8a, armeabi-v7a, x86_64). Příznak `-Pelfall.rustCore=true` → `BuildConfig.RUST_CORE`;
výchozí **vypnuto** (původní lazysodium cesta).

| Modul | Stav | Kompatibilita |
|---|---|---|
| `framing` – vstup podpisu `"ELFALL-SIG-v1\0" ‖ len_be32 ‖ msg` | **první migrovaný modul** | přesně specifikace dokázaná ve SPARK (`proofs/spark/`) |
| `sign` – Ed25519 nad orámovaným vstupem | hotovo | RFC 8032 vektor |
| `vault` – XChaCha20-Poly1305, `sv1:` formát | Android za příznakem | vektor ověřen libsodium (Android test `rustCoreVaultVector`) |
| `pin` – Argon2id m=19456,t=2,p=1 | hotovo, zatím nepoužito | PHC ověřen libsodium (`rustCoreArgon2idVector`) |

Web dostává jen veřejné operace (rámování, ověření podpisu) – tajné klíče v prohlížeči nedržíme.
Vygenerované vazby: `bindings/` (Kotlin `uniffi/elfall_core/`, Swift `elfall_core.swift`).
