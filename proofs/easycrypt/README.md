# Formální důkaz SodiumVault (EasyCrypt)

Soubor `SodiumVault.ec` obsahuje model a strojově ověřený důkaz v nástroji [EasyCrypt](https://github.com/EasyCrypt/easycrypt) pro šifrování citlivých lokálních dat peněženky (`androidApp/.../SodiumVault.kt`, `iosApp/iosApp/SodiumVault.swift`).

## Co je dokázáno
Konstrukce: `seal(pole, m) = AEAD.enc(k, náhodný nonce, ad("elfall/v1/" + pole), m)`, `unseal(pole, c) = AEAD.dec(k, ad(pole), c)`.

Hra **VAULT**: útočník smí libovolně volat `seal` a `unseal`; vyhraje, pokud `unseal` přijme dvojici (pole, šifrový text), kterou `seal` pro **totéž pole** nevytvořil. To zahrnuje:
- **vazbu na pole** – šifrový text pole A předložený jako pole B (např. podvržení výplatní adresy do pole Stellar účtu),
- **odhalení manipulace** – jakkoli změněný šifrový text,
- zcela nový padělek.

Věta `vault_int_eq`: pro každého útočníka A platí
`Pr[VAULT(A) vyhraje] = Pr[INT_CTXT(B(A)) vyhraje]`,
kde `B(A)` je explicitní redukce na hru **INT-CTXT** (integrita šifrových textů) podkladového AEAD. Tedy výhoda útočníka proti trezoru je **přesně** jeho výhoda proti integritě XChaCha20-Poly1305.

## Předpoklady (nejsou dokázány zde)
1. **XChaCha20-Poly1305 je INT-CTXT bezpečné** – standardní výsledek (Poly1305 jednorázová MAC + XChaCha20), v modelu je AEAD abstraktní.
2. **`ad_of` je injektivní** – axiom; platí pro pevný prefix `elfall/v1/` + název pole (UTF-8).
3. **Klíč je náhodný a neunikne** – Keystore/Keychain je modelováno ideálně.
4. Nonce se losuje z rozdělení `dnonce`; pro integritu není potřeba jeho jedinečnost (pro důvěrnost ano – 192bit náhodný nonce XChaCha činí kolize zanedbatelné).

## Co dokázáno NENÍ
- **Důvěrnost** (IND-CPA/IND-CCA) – není předmětem tohoto důkazu.
- Správnost konkrétní implementace v Kotlinu/Swiftu a v libsodium (shoda kódu s modelem se zajišťuje review a testy `SodiumVaultTest`).
- Postranní kanály, chyby paměti, kompromitace zařízení (root/jailbreak), útoky na Keystore/Keychain, malware zobrazující falešnou adresu v UI.
- **Rollback** (vrácení starší platné hodnoty stejného pole) – model jej za útok nepovažuje, protože starší hodnota byla skutečně zapečetěna; ochrana by vyžadovala čítač verzí.

## Ověření
- Lokálně: `opam pin add easycrypt git+https://github.com/EasyCrypt/easycrypt.git#<commit>; opam install easycrypt alt-ergo; easycrypt why3config; easycrypt compile proofs/easycrypt/SodiumVault.ec`.
- CI: `.github/workflows/proofs.yml` při každé změně `proofs/` nebo SodiumVault (EasyCrypt pinovaný na commit).

## Zdroje
- EasyCrypt: https://github.com/EasyCrypt/easycrypt , https://easycrypt.gitlab.io/easycrypt-web/
- Bellare, Namprempre – Authenticated Encryption (INT-CTXT): https://eprint.iacr.org/2000/025
- Rogaway – Authenticated-Encryption with Associated-Data: https://web.cs.ucdavis.edu/~rogaway/papers/ad.pdf
- RFC 8439 (ChaCha20-Poly1305): https://www.rfc-editor.org/rfc/rfc8439 ; XChaCha20 draft: https://datatracker.ietf.org/doc/html/draft-irtf-cfrg-xchacha
- libsodium AEAD: https://doc.libsodium.org/secret-key_cryptography/aead/chacha20-poly1305/xchacha20-poly1305_construction
