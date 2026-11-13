# SPARK/Ada – vstup pro podpis Ed25519 a nulování klíčů

`src/elfall_signing.ad[sb]` je **model** části podepisovací cesty peněženky ve SPARK.
CI (`.github/workflows/proofs.yml`, job `spark`) spouští `gnatprove --level=2 --checks-as-errors=on`
přes Alire na každou změnu v `proofs/**`.

## Co je dokázáno
- **Absence běhových chyb** ve všech podprogramech: žádné přetečení celých čísel, žádný index
  mimo rozsah, žádné čtení neinicializovaných dat (flow analýza + důkaz).
- `Frame`: výstup má přesně délku `18 + délka zprávy`, začíná doménovým prefixem
  `"ELFALL-SIG-v1\0"` a zpráva leží beze změny hned za 4bajtovou délkou (big-endian).
  Zpráva je omezena na 65 536 B (podmínka `Pre`), takže délku nelze přetéct.
- `Unframe`: přijme jen vstup se správným prefixem a s deklarovanou délkou přesně rovnou zbytku
  (žádné „připojení“ dat za podepsanou zprávu, žádné zkrácení).
- `Zeroize`: po návratu jsou všechny bajty klíče nulové.
- `Is_Signature_Length` / `Is_Key_Length`: kontrola 64 B podpisu a 32 B klíče.

## Co dokázáno NENÍ
- Samotný Ed25519 (podpis/ověření) – ten dělá libsodium / platforma; zde jen formát vstupu.
- Že Kotlin/Swift kód skutečně volá tento formát – model je specifikace, ne sdílená knihovna
  (cesta k tomu: Rust/SPARK jádro přes FFI, viz `docs/security/ZERO-TRUST-ROADMAP.md`).
- Že překladač nevypustí nulování (dead-store elimination) – v aplikaci se používá
  `sodium_memzero`; důkaz pokrývá jen logickou hodnotu.
- Postranní kanály, časování, fyzické útoky.

## Lokální ověření
```
alr toolchain --select gnat_native gprbuild
cd proofs/spark && alr update && alr exec -- gnatprove -P elfall_signing.gpr -j0
```
