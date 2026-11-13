# Idris2 – invarianty tokenů FLC / yFLC

`FLC.idr` je **model** pravidel tokenů v Idris2 (`%default total`, takže každá věta je
skutečný důkaz, ne jen typově správná funkce). CI job `idris2` spouští `idris2 --check FLC.idr`.

## Co je dokázáno (v modelu)
1. **Pevná maximální nabídka** – `capInvariant`: žádná operace (`Mint`, `Burn`, `Transfer`) strop
   `cap` nezmění, tedy ani nezvýší; `traceCap`: totéž po libovolné posloupnosti operací.
2. **Nabídka nikdy nepřekročí strop** – `supplyBounded`, `traceBounded`. `Mint` lze vůbec
   sestavit jen s důkazem `supply + n <= cap`.
3. **Zmrazený yFLC nelze převést** – `yflcFrozen : Transferable YFLC -> Void`; `Transfer`
   vyžaduje důkaz `Transferable`, který pro yFLC neexistuje.

## Co dokázáno NENÍ
- Že skutečná aktiva na Stellaru tato pravidla dodržují. Na Stellaru to vynucují nastavení účtu
  vydavatele (zamčený issuer – master weight 0 po emisi, `AUTH_REQUIRED`/`AUTH_REVOCABLE`,
  odebraná trustline autorizace pro yFLC) – to je nutné ověřit on-chain, ne tímto modelem.
- Cílová cena FLC 0,33 USD, MiCA, off-ramp – mimo model.
- Správnost aplikačního kódu peněženky.

## Lokální ověření
```
idris2 --check proofs/idris/FLC.idr
```
