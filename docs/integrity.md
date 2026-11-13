# Integrita kritických hodnot (ochrana proti „swap“ útoku)

Cíl: nikdo (útočník, kompromitovaný účet, škodlivý PR) nesmí **tiše** změnit vydavatele FLC, kódy aktiv, ceny,
URL poskytovatelů off-rampu, atribuci autora ani výplatní údaje.

## Vrstvy
1. **`integrity/critical.json`** – kanonické hodnoty (issuer `GCUMZ6Z7…RPKT`, `FLC`/`yFLC`, `home_domain`, 0,33 USD, URL prefixy, autor).
2. **`integrity/MANIFEST.sha512`** – SHA-512 souborů z `integrity/protected-files.txt`.
3. **`integrity/MANIFEST.sha512.sig`** – SSH podpis vlastníka (`ssh-keygen -Y sign`, namespace `elfall-integrity`),
   ověřovaný proti `integrity/allowed_signers` (veřejný klíč vlastníka, nejlépe hardwarový FIDO2 `sk-ssh-ed25519`).
4. **CI `.github/workflows/integrity.yml`** – `scripts/integrity_manifest.py verify` selže, pokud: hash ≠ manifest,
   konstanta ve zdroji ≠ critical.json, chybí/neplatí podpis, nebo se v chráněném souboru objeví vzor čísla účtu.
   **Dokud vlastník nevloží klíč a nepodepíše manifest, CI záměrně selhává.**
5. **CODEOWNERS** – změny `integrity/`, `assets/`, `offramp/`, `.well-known/`, AUTHORS/NOTICE/LICENSE vyžadují review @ivosicek123
   (aktivovat v rulesetu „Require review from Code Owners“).
6. **Testy** `CriticalConstantsTests.kt` (Kotlin) a `scripts/tests/test_integrity_manifest.py` (Python).
7. **Runtime** `StellarTomlVerifier` – aplikace ověří `home_domain` účtu vydavatele na Horizonu a `[[CURRENCIES]]` v
   `https://elfall.pages.dev/.well-known/stellar.toml` (SEP-1). Nesoulad ⇒ operace s FLC se zablokují (Android: Nastavení → „Ověřit vydavatele FLC“).

## První nastavení (vlastník)
```bash
ssh-keygen -t ed25519-sk -C "ivo@elfall-integrity"            # hardwarový klíč (YubiKey apod.)
echo "ivosicek123 namespaces=\"elfall-integrity\" $(cat ~/.ssh/id_ed25519_sk.pub)" >> integrity/allowed_signers
scripts/sign_manifest.sh ~/.ssh/id_ed25519_sk
git add integrity && git commit -S -m "integrity: owner-signed manifest"
```
Na Stellar testnetu nastavte u účtu vydavatele `home_domain = elfall.pages.dev` (operace *Set Options*, podepisuje issuer –
mimo repo) a nasaďte `web/` na Cloudflare Pages, aby `/.well-known/stellar.toml` byl dostupný s `Access-Control-Allow-Origin: *`.

## Výplatní účet – jen commitment, nikdy číslo
Číslo účtu **není a nesmí být** v repozitáři. Prostý SHA-256 čísla účtu je prolomitelný hrubou silou (~10¹⁰ možností),
proto se ukládá jen HMAC s tajnou solí uloženou offline (např. v trezoru/password manageru):
```bash
SALT=$(openssl rand -hex 32)   # uložit OFFLINE, nikdy do gitu
printf '%s' "<cislo_uctu>/<kod_banky>" | openssl dgst -sha256 -hmac "$SALT" | awk '{print $2}'
```
Výsledek vložte do `critical.json → payout_account_commitment.value` a manifest znovu podepište. Při sporu prokážete shodu
předložením účtu + soli (např. notáři), aniž by byl účet kdy veřejný.

## Ukotvení release v čase – OpenTimestamps
```bash
pipx install opentimestamps-client
scripts/timestamp_release.sh androidApp/build/outputs/apk integrity/MANIFEST.sha512 elfall-sbom.spdx.json
ots upgrade release-hashes-*.ots     # po několika hodinách (potvrzení v Bitcoin bloku)
ots verify  release-hashes-*.ots
```
Zdroje: https://opentimestamps.org, https://man.openbsd.org/ssh-keygen#Y, https://developers.stellar.org/docs/tokens/publishing-asset-info (SEP-1).
