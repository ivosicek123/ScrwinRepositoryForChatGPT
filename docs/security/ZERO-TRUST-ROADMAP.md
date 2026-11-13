# Zero Trust – hodnocení plánu a roadmapa (ELFALL)

Stav k 14. 10. 2026, větev `security-review`. Odhady jsou pro 1 vývojáře; náklady bez mezd.
Princip: nic nevěřit implicitně (síť, zařízení, klient) – každá operace ověřena identitou,
kryptograficky a s minimem oprávnění.

## Přehled

| # | Položka | Co už existuje | Co se změní | Pracnost | Náklady | Priorita |
|---|---------|----------------|-------------|----------|---------|----------|
| 1 | WebAuthn passkeys | Bank iD OIDC (PKCE, PS512/PS256/ES256); **nově** backend `Passkeys.kt` (webauthn4j, ES256/EdDSA, UV required, attestation none) + `web/passkey-client.js`, testy | Trvalé úložiště credentialů (DB místo paměti), vazba passkey ↔ Bank iD identita, sign-counter politika, UI, Android Credential Manager / iOS `ASAuthorization` | 2–3 týdny | 0 Kč | **1** |
| 2 | Rust krypto jádro (UniFFI → Android/iOS, Wasm → web), po fázích | libsodium (lazysodium / swift-sodium), SodiumVault, Argon2id, Ed25519 manifest; formální modely (EasyCrypt, SPARK, Idris2) | Fáze A: Rust crate `elfall-core` (rámování podpisu, vault formát, `zeroize`), testovací vektory shodné s Kotlin/Swift; Fáze B: UniFFI pro Android+iOS, nahrazení duplicitního kódu; Fáze C: Wasm pro web (bez přístupu ke klíčům v JS heapu jen částečně možné) | A 2–3 t., B 3–4 t., C 2–3 t. | 0 Kč (cargo-vet/cargo-deny v CI) | **2** |
| 3 | ZTNA (přístup k backendu/adminu) | Backend váže na 127.0.0.1, k8s NetworkPolicy default-deny, distroless | Admin a staging jen přes identitně řízený přístup (Cloudflare Access / Tailscale / Pomerium), mTLS mezi službami, krátkodobé certifikáty, žádné veřejné SSH | 1 týden | 0–200 Kč/měs. (free tiery) | **3** |
| 4 | Suricata / Zeek | `tools/home-defense` (inventura, BLE, důkazní log) | IDS na hraně produkce (Suricata ET Open pravidla) + Zeek logy do SIEM; doma volitelně na Raspberry Pi za routerem | 1–2 týdny + provoz | HW 2–4 tis. Kč, provoz čas | 4 |
| 5 | eBPF/XDP firewall | k8s NetworkPolicy, seccomp, read-only rootfs | Cilium (eBPF CNI) s L7 politikami a Tetragon runtime detekcí; XDP drop pro DDoS jen pokud vlastní hosting – u managed k8s/CDN zbytečné | 1–2 týdny | 0 Kč (OSS) | 5 |
| 6 | F* / Rocq (Coq) | EasyCrypt (SodiumVault INT-CTXT redukce), SPARK (AoRTE rámování), Idris2 (FLC invarianty) – vše v CI | Ověřené primitivy přes **HACL\*/libcrux** (F*, již existují) místo vlastních důkazů; Rocq jen pro protokolové modely, pokud vznikne vlastní protokol | průběžně | 0 Kč | 6 |

## Doporučené pořadí
1. **Passkeys dokončit** (největší snížení rizika phishingu a převzetí účtu; základ hotov).
2. **ZTNA pro admin/staging** – levné a rychlé, zavře nejčastější vektor (vystavené služby).
3. **Rust jádro fáze A+B** – sjednotí kryptografii Android/iOS, odstraní duplicitu, umožní
   napojit formální modely na skutečný kód (vazba SPARK/EasyCrypt specifikace ↔ testovací vektory).
4. Suricata/Zeek + Cilium/Tetragon až s produkčním provozem (bez provozu nemají co detekovat).
5. Wasm (fáze C) a F*/HACL\* jako náhrada primitiv, až bude jádro stabilní.

## Rizika a poznámky
- Formální důkazy jsou **modely**; skutečnou jistotu přináší až sdílené jádro + testovací vektory.
- Wasm v prohlížeči nechrání klíče před XSS – nutná přísná CSP (již nasazena) a passkeys.
- Každá nová závislost: pin + `verification-metadata` / `cargo vet`, záznam do `SECURITY-LOG.md`.
- Nic z výše uvedeného nezahrnuje aktivní „hack-back“ – pouze obrana (POLICY.md, pravidlo 1).
