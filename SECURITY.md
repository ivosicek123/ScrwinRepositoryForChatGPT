# Bezpečnostní politika / Security Policy

## Podporované verze
| Větev | Stav |
|---|---|
| `eleanor-rebuild` (testnet) | podporováno – bezpečnostní opravy |
| ostatní | nepodporováno |

Aplikace ELFALL / Eleanor běží **pouze na testnetu** (Bitcoin testnet/signet, Stellar testnet). Neukládejte do ní skutečné prostředky.

## Hlášení zranitelnosti
- **Nehlaste zranitelnosti veřejným issue.**
- Použijte GitHub **Private vulnerability reporting**: záložka *Security → Report a vulnerability*
  (https://github.com/ivosicek123/bitcoin-wallet-android/security/advisories/new).
- Uveďte: popis, dotčené soubory/verzi (commit), kroky k reprodukci, dopad, případně PoC.
- Nikdy neposílejte seed fráze, privátní klíče ani osobní údaje.

## Reakční doby (cíl)
- potvrzení přijetí: do 3 pracovních dnů,
- první posouzení (CVSS): do 10 pracovních dnů,
- oprava kritické chyby: do 30 dnů; koordinované zveřejnění po opravě (max. 90 dní).

## Rozsah
V rozsahu: `shared/`, `androidApp/`, `iosApp/`, `web/`, `runpod/`, `assistant/`, `scripts/`, CI workflow.
Mimo rozsah: služby třetích stran (MoonPay, Coinbase, Anycoin, mempool.space, Blockstream, Stellar Horizon), sociální inženýrství, DoS.

## Bezpečnostní opatření v repozitáři
Připnuté SHA GitHub Actions, least-privilege `permissions`, CodeQL, Dependabot, OpenSSF Scorecard, SBOM (SPDX + CycloneDX),
Gradle dependency verification (`gradle/verification-metadata.xml`), pip `--require-hashes` (`runpod/requirements.lock.txt`),
striktní CSP (`web/_headers`), CODEOWNERS. Kontrolní seznam: OWASP MASVS (https://mas.owasp.org/MASVS/).
