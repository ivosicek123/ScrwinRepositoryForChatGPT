#!/usr/bin/env bash
# Ukotvení hashů release artefaktů v Bitcoinu přes OpenTimestamps (https://opentimestamps.org).
# Použití: scripts/timestamp_release.sh <soubor|adresář>...   (např. APK, IPA, SBOM, MANIFEST.sha512, podepsané PDF)
# Výstup: release-hashes-<datum>.sha256 + .ots (důkaz). Po ~1–6 h: `ots upgrade *.ots`, ověření: `ots verify *.ots`.
# Instalace klienta: pipx install opentimestamps-client
set -euo pipefail
[ $# -ge 1 ] || { echo "Použití: $0 <soubor|adresář>..." >&2; exit 2; }
OUT="release-hashes-$(date -u +%Y%m%dT%H%M%SZ).sha256"
find "$@" -type f ! -name '*.ots' -print0 | sort -z | xargs -0 sha256sum > "$OUT"
echo "Zapsáno $OUT ($(wc -l < "$OUT") souborů); git HEAD: $(git rev-parse HEAD 2>/dev/null || echo n/a)" >&2
if command -v ots >/dev/null 2>&1; then
  ots stamp "$OUT"
  echo "Vytvořeno $OUT.ots – uložte ho mimo repo (+ do releasu). Za několik hodin spusťte: ots upgrade $OUT.ots" >&2
else
  echo "VAROVÁNÍ: 'ots' není nainstalován (pipx install opentimestamps-client). Hash je připraven, razítko NEvzniklo." >&2
  exit 3
fi
