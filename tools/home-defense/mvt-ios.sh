#!/usr/bin/env bash
# Kontrola iPhonu nástrojem MVT (Amnesty International) z ŠIFROVANÉ zálohy Finder/iTunes.
# Použití: mvt-ios.sh <složka_zálohy> [výstup]
# Instalace MVT: pipx install mvt   (https://docs.mvt.re/)
DIR="$(cd "$(dirname "$0")" && pwd)"; source "$DIR/lib/common.sh"
BK="${1:?cesta ke složce zálohy iPhonu}"; OUT="${2:-$HD_DIR/mvt-$(date -u +%Y%m%dT%H%M%SZ)}"
need mvt-ios || { echo "Nainstalujte: pipx install mvt"; exit 1; }
mkdir -p "$OUT"; chmod 700 "$OUT"
DEC="$OUT/decrypted"
echo "1) Dešifrování zálohy (heslo zálohy zadáte interaktivně, neukládá se):"
mvt-ios decrypt-backup -d "$DEC" "$BK"
echo "2) Stažení aktuálních indikátorů kompromitace (IOC):"
mvt-ios download-iocs || true
echo "3) Kontrola zálohy:"
mvt-ios check-backup --output "$OUT/results" "$DEC" | tee "$OUT/mvt.log"
det="$(grep -ci 'detected' "$OUT/mvt.log" || true)"
if [ "${det:-0}" -gt 0 ]; then
  evidence_log mvt-ios vysoka "MVT: $det řádků s detekcí – předejte výsledky odborníkovi (Amnesty Security Lab / NÚKIB)" "$(jq -cn --arg o "$OUT" '{output:$o}')"
else
  evidence_log mvt-ios info "MVT: bez známých indikátorů (to nevylučuje neznámé hrozby)" "$(jq -cn --arg o "$OUT" '{output:$o}')"
fi
echo "Hotovo: $OUT. Dešifrovanou zálohu po kontrole bezpečně smažte: rm -rf \"$DEC\""
