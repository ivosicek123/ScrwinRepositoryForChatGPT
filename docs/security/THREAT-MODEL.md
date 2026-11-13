# Model hrozeb (STRIDE, zkráceně)

## Aktiva
Seed peněženky (BTC BIP84, Stellar), výplatní adresa/účet, kritické konstanty (issuer FLC, URL off-rampu, cena 0,33 USD), identita uživatele z Bank iD, podpisový klíč vlastníka, CI/CD a závislosti, autorství.

## Hranice důvěry
Zařízení uživatele (Android/iOS/PWA) ↔ Bank iD backend (Kotlin/Ktor) ↔ Bank iD OP; aplikace ↔ veřejná API (mempool.space, Blockstream, Horizon); GitHub (repo, Actions) ↔ Maven/PyPI.

| Hrozba | Kategorie | Opatření | Zbytkové riziko |
|---|---|---|---|
| Podvržení issueru / URL off-rampu („swap“) | Tampering | podepsaný manifest SHA-512 + Ed25519, CODEOWNERS, runtime kontrola stellar.toml | do vložení klíče vlastníka nepodepsáno |
| Záměna výplatní adresy na zařízení | Tampering | (doporučeno) AEAD/HMAC výplatní adresy, potvrzení v UI, Bank iD vazba tx_hash | střední – viz SR-04 |
| Krádež seedu | Information disclosure | Keystore/Secure Enclave, AES-GCM, biometrie, no-backup | fallback bez zámku obrazovky – SR-03 |
| Login CSRF / injection kódu | Spoofing | state (jednorázový, TTL), PKCE S256, nonce, ověření callbacku | nízké |
| Podvržený id_token / alg confusion | Spoofing | JWKS, allowlist PS512/PS256/ES256, iss/aud/azp/exp/iat | nízké |
| Zahlcení /auth/start | DoS | strop PendingStore (10 000), TTL; (doporučeno) rate-limit na proxy | nízké |
| Kompromitace závislosti / akce | Supply chain | SHA piny, dependency verification, Dependabot cooldown, osv-scanner | build-time CVE v AGP – SR-09 |
| Neautorizovaný push / přepis historie | Elevation | jediný kolaborátor, CODEOWNERS, (doporučeno) ruleset bez force-push, podepsané commity | sdílený gh token na boxu |
| Phishing SEP-7 odkazem | Spoofing | (doporučeno) podpis SEP-7 URI | SR-05 |
| Únik osobních údajů | Information disclosure | minimalizace (jen výsledky ověření), žádná PII v repu | nízké |
