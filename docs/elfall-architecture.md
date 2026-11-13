# ELFALL architecture / Architektura ELFALL

**CZ:** ELFALL = Eleanor + Fallcoin. Jedno 2-v-1 APK: neúschovná Bitcoin peněženka + Stellar režim (podpis v LOBSTR/Freighter) + Převod / Směna + **Výběr na kartu** (MoonPay / Coinbase) jen pro CZK/EUR/USD.

**EN:** ELFALL = Eleanor + Fallcoin. One 2-in-1 APK: non-custodial Bitcoin wallet + Stellar mode (signing in LOBSTR/Freighter) + Transfer / Swap + **Withdraw to card** (MoonPay / Coinbase) for CZK/EUR/USD only.

---

## Integration research (honest, 2026-10-12)

| Service | Can do for ELFALL | Cannot / caveats | Docs |
|---|---|---|---|
| **LOBSTR** | WalletConnect 2.0 login + in-app signing; SEP-7 `web+stellar:` deep links; browser signer ext. (`@lobstrco/signer-extension-api`) | ELFALL must not hold Stellar keys; WC session wiring is still a stub beyond SEP-7 URI open | [WC login](https://lobstr.freshdesk.com/support/solutions/articles/151000001589), [signer ext](https://github.com/Lobstrco/lobstr-browser-extension) |
| **Freighter** | Browser: `@stellar/freighter-api`. Mobile: WalletConnect v2 (`stellar_signXDR`, …) | Extension API is **JS-only** — not callable from Kotlin Android directly; use SEP-7 / WC bridge | [docs.freighter.app](https://docs.freighter.app/) |
| **Anycoin M.P.** (MP Developers s.r.o., CZ CASP / MiCA) | Hosted web/app for buy/sell CZK/EUR; BankID KYC; app `cz.anycoin.mobile` | **No public partner/sell API found.** Public flow documents fiat out to a **bank account**, Google Pay for *purchases*. Partner contact needed for true in-app quotes / card payout confirmation | [anycoin.cz how-it-works](https://www.anycoin.cz/en/how-it-works) |
| **Google Wallet** | Passes API (tickets, loyalty). **Google Pay** = checkout to *pay* a merchant | **Not a crypto→card payout destination.** Do not list as cash-out target | [Wallet API](https://developers.google.com/wallet/reference/rest), [Google Pay](https://developers.google.com/pay/api/web/guides/tutorial) |
| **SEP-0007** | Standard `web+stellar:pay` / `web+stellar:tx` URIs for delegated signing | Wallet must be installed and register the scheme | [SEP-0007](https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0007.md) |

**Legal:** ELFALL is non-custodial. Licensed providers perform KYC/AML. No cracking vaults, no mining others' devices/sites, no decoding others' crypto products without permission.

---

## Product modes

| Mode (EN) | Mode (CZ) | Implementation |
|---|---|---|
| Receive | Příjem | BIP84 BTC address; Stellar receive via watch address + SEP-7 pay request to LOBSTR/Freighter |
| Transfer | Převod | On-chain BTC send (device sign). Stellar: SEP-7 / WC → LOBSTR or Freighter |
| Swap | Směna | Provider widget stubs (Changelly / THORChain docs) |
| Withdraw to card | Výběr na kartu | **MoonPay, Coinbase** (only active providers; Anycoin not active). Currencies: **CZK, EUR, USD only** |

---

## Code map

- `shared/.../offramp/` — `MoonPaySellStub` + `CoinbaseOffRampStub` active (`AnycoinProvider` kept, inactive)
- `shared/.../stellar/Sep7.kt` — SEP-7 URI builder + tests
- `shared/.../stellar/StellarWalletConnector.kt` — `LobstrConnector`, `FreighterConnector`
- `shared/.../google/GoogleWalletHelper.kt` — capability stub (not a payout rail)
- Track B (RunPod) — unchanged; $0 credit → local first

### Partner follow-up (Anycoin)

Contact Anycoin business / support for: (1) partner or affiliate deep-link parameters, (2) whether card payout (vs bank account) is available for CZK/EUR/USD, (3) any private API for quotes. Until then ELFALL opens the public site/app only.

## Šifrování lokálních dat peněženky (SodiumVault)
Výplatní adresa a Stellar účet se ukládají šifrovaně a autentizovaně (libsodium XChaCha20-Poly1305, název pole jako associated data; klíč chráněn Android Keystore / iOS Keychain). Formální model a strojově ověřený důkaz integrity a vazby na pole (redukce na AEAD INT-CTXT, EasyCrypt): [proofs/easycrypt/README.md](../proofs/easycrypt/README.md).

### Další formální modely
- `proofs/spark/` – SPARK/Ada: rámování vstupu podpisu Ed25519 a nulování klíčů (absence běhových chyb).
- `proofs/idris/` – Idris2: invarianty FLC (pevný strop nabídky, zmrazený yFLC).
- Plán dalšího postupu: `docs/security/ZERO-TRUST-ROADMAP.md`.
