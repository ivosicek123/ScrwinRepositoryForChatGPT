#!/usr/bin/env bash
# Společné funkce: evidence log (JSONL + SHA-256 řetěz), kontrola vlastní sítě.
set -euo pipefail
HD_DIR="${HD_DIR:-$HOME/.home-defense}"
HD_LOG="${HD_LOG:-$HD_DIR/evidence.jsonl}"

need() { command -v "$1" >/dev/null 2>&1 || { echo "Chybí nástroj: $1" >&2; return 1; }; }
utc_now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# evidence_log <modul> <závažnost: info|nizka|stredni|vysoka> <shrnutí> [detail-json]
evidence_log() {
  need jq; need sha256sum
  local module="$1" sev="$2" summary="$3" detail="${4:-}"
  [ -n "$detail" ] || detail='{}'
  mkdir -p "$(dirname "$HD_LOG")"; chmod 700 "$(dirname "$HD_LOG")" 2>/dev/null || true
  local prev="0000000000000000000000000000000000000000000000000000000000000000" seq=0
  if [ -s "$HD_LOG" ]; then
    prev="$(tail -n1 "$HD_LOG" | jq -r .hash)"; seq="$(tail -n1 "$HD_LOG" | jq -r .seq)"; seq=$((seq + 1))
  fi
  local body
  body="$(jq -cn --argjson seq "$seq" --arg ts "$(utc_now)" --arg host "$(hostname)" --arg m "$module" \
    --arg s "$sev" --arg sum "$summary" --argjson d "$detail" --arg prev "$prev" \
    '{seq:$seq,ts_utc:$ts,host:$host,module:$m,severity:$s,summary:$sum,detail:$d,prev_hash:$prev}')"
  local h; h="$(printf '%s' "$body" | sha256sum | cut -d' ' -f1)"
  printf '%s\n' "$(jq -c --arg h "$h" '. + {hash:$h}' <<<"$body")" >> "$HD_LOG"
  chmod 600 "$HD_LOG" 2>/dev/null || true
}

# evidence_verify – ověří celý řetěz; vrací 0 = neporušeno
evidence_verify() {
  need jq; local prev="0000000000000000000000000000000000000000000000000000000000000000" n=0 line
  [ -s "$HD_LOG" ] || { echo "Log je prázdný."; return 0; }
  while IFS= read -r line; do
    local body h exp p
    body="$(jq -c 'del(.hash)' <<<"$line")"; h="$(jq -r .hash <<<"$line")"; p="$(jq -r .prev_hash <<<"$line")"
    exp="$(printf '%s' "$body" | sha256sum | cut -d' ' -f1)"
    if [ "$p" != "$prev" ] || [ "$h" != "$exp" ]; then echo "PORUŠENO na záznamu $n"; return 1; fi
    prev="$h"; n=$((n + 1))
  done < "$HD_LOG"
  echo "Řetěz neporušen: $n záznamů, poslední hash $prev"
}

# Jen privátní rozsahy (RFC 1918) – nástroj se nesmí použít na cizí sítě.
is_private_cidr() {
  local ip="${1%/*}"
  [[ "$ip" =~ ^10\. ]] || [[ "$ip" =~ ^192\.168\. ]] || [[ "$ip" =~ ^172\.(1[6-9]|2[0-9]|3[01])\. ]]
}
