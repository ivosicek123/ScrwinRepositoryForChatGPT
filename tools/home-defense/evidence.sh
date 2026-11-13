#!/usr/bin/env bash
# Evidence: verify | export [soubor.md] | add <modul> <závažnost> <text>
DIR="$(cd "$(dirname "$0")" && pwd)"; source "$DIR/lib/common.sh"
case "${1:-}" in
  verify) evidence_verify ;;
  add) evidence_log "$2" "$3" "$4" ;;
  export)
    out="${2:-$HD_DIR/hlaseni-$(date -u +%Y%m%dT%H%M%SZ).md}"
    evidence_verify > /tmp/hd-verify.$$ || true
    {
      echo "# Hlášení bezpečnostních zjištění (domácí síť / zařízení)"
      echo; echo "Vygenerováno (UTC): $(utc_now) · Zařízení: $(hostname)"
      echo; echo "Integrita záznamů: $(cat /tmp/hd-verify.$$)"
      echo "SHA-256 celého logu: $(sha256sum "$HD_LOG" | cut -d' ' -f1)"
      echo; echo "Určeno pro: Policie ČR (https://www.policie.cz/) / NÚKIB (https://www.nukib.gov.cz/) / CSIRT.CZ (https://csirt.cz/). Přiložte i originální soubor evidence.jsonl."
      echo; echo "| # | Čas (UTC) | Modul | Závažnost | Zjištění |"; echo "|---|---|---|---|---|"
      jq -r '"| \(.seq) | \(.ts_utc) | \(.module) | \(.severity) | \(.summary|gsub("\\|";"/")) |"' "$HD_LOG"
      echo; echo "Pozn.: Nástroj provádí pouze pasivní/inventární kontroly vlastní sítě a zařízení. Žádné dohledávání útočníka ani protiútok."
    } > "$out"; rm -f /tmp/hd-verify.$$; chmod 600 "$out"; echo "Hlášení: $out" ;;
  *) echo "Použití: $0 verify | export [soubor.md] | add <modul> <závažnost> <text>"; exit 2 ;;
esac
