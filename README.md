# ELFALL — Bitcoin wallet + card cash-out (2-in-1)

**Verze:** 1.0.0-rc1 (release candidate, neveřejný; postup viz [docs/RELEASE-CHECKLIST.md](docs/RELEASE-CHECKLIST.md))

**CZ:** ELFALL (Eleanor + Fallcoin) je neúschovná Bitcoin peněženka pro Android a iOS s režimy Příjem, Převod, Směna a **Výběr na kartu** (jen CZK / EUR / USD přes licencovaného poskytovatele).

**EN:** ELFALL (Eleanor + Fallcoin) is a non-custodial Bitcoin wallet for Android & iOS with Receive, Transfer, Swap, and **Withdraw to card** (CZK / EUR / USD only via a licensed provider).

> ⚠️ Early development. Defaults to **testnet**. Do not use with real funds until a security review is done.

## Autor a vlastník / Author & owner

**Ivo Faltýn — autor, vývojář a vlastník projektu ELFALL / Fallcoin / Eleanor** (GitHub [@ivosicek123](https://github.com/ivosicek123)). Viz [AUTHORS](AUTHORS), [NOTICE](NOTICE), [LICENSE](LICENSE).

## Modes

| Mode | Description |
|---|---|
| **Receive / Příjem** | BIP84 address |
| **Transfer / Převod** | Send BTC to a crypto address (on-device sign + biometric confirm) |
| **Swap / Směna** | Crypto↔crypto via provider widget stub (non-custodial) |
| **Withdraw to card / Výběr na kartu** | Cash-out **only** to payment cards in CZK, EUR or USD (MoonPay / Coinbase hosted stubs). Card + KYC stay with the provider. |

## Stack

Kotlin Multiplatform (`shared/`), Jetpack Compose Android app, SwiftUI iOS skeleton, bitcoin-kmp, Ktor, Android Keystore. Package: `cz.faltyn.elfall`.

## Build

```bash
./gradlew :shared:testDebugUnitTest
./gradlew :androidApp:assembleBitcoinTestnetDebug
```

## Docs

- [Architecture (CZ+EN)](docs/elfall-architecture.md)
- [Attachment audit (redacted)](docs/attachment-audit.md)

## License

BSD 2-Clause.

## Network / Síť (RunPod)

See [docs/elfall-network.md](docs/elfall-network.md) (Czech-first). Local worker: `cd runpod && pip install -r requirements.txt && pytest && python local_runner.py health`.
**Do not deploy to RunPod while balance is $0.**
