#!/usr/bin/env bash
# Inventura VLASTNÍ domácí sítě. Pouze RFC1918 a pouze podsíť, ve které je tento počítač.
# Použití: net-inventory.sh --i-own-this-network [--cidr 192.168.1.0/24] [--ports] [--allowlist soubor]
DIR="$(cd "$(dirname "$0")" && pwd)"; source "$DIR/lib/common.sh"
OWN=0; CIDR=""; PORTS=0; ALLOW="${HD_DIR}/allowlist.txt"; OUI="${HD_OUI:-$DIR/oui-min.txt}"
while [ $# -gt 0 ]; do case "$1" in
  --i-own-this-network) OWN=1 ;; --cidr) CIDR="$2"; shift ;; --ports) PORTS=1 ;; --allowlist) ALLOW="$2"; shift ;;
  *) echo "Neznámý parametr $1"; exit 2 ;; esac; shift; done
[ "$OWN" = 1 ] || { echo "Potvrďte, že síť je vaše: --i-own-this-network (skenování cizích sítí je zakázáno, § 230 TZ)."; exit 2; }

local_cidrs() { ip -o -4 addr show scope global | awk '{print $4}'; }
if [ -z "$CIDR" ]; then CIDR="$(local_cidrs | head -n1)"; fi
is_private_cidr "$CIDR" || { echo "Odmítnuto: $CIDR není privátní rozsah (RFC 1918)."; exit 3; }
in_local=0; for c in $(local_cidrs); do [ "${c%.*}" = "${CIDR%.*}" ] && in_local=1; done
[ "$in_local" = 1 ] || [ "${HD_SKIP_LOCAL_CHECK:-0}" = 1 ] || { echo "Odmítnuto: $CIDR není podsíť tohoto počítače."; exit 3; }

# Seznam IP MAC (nmap -sn, jinak ARP tabulka)
discover() {
  if [ -n "${HD_NEIGH_FILE:-}" ]; then cat "$HD_NEIGH_FILE"; return; fi
  if command -v nmap >/dev/null; then
    nmap -sn -n "$CIDR" -oG - 2>/dev/null >/dev/null || true
  fi
  ip neigh show | awk '$5 ~ /:/ {print $1, tolower($5)}'
}
vendor() { local p; p="$(echo "${1:0:8}" | tr 'a-f' 'A-F' | tr -d ':')"
  if [ $(( 0x${1:1:1} & 2 )) -ne 0 ]; then echo "náhodná (soukromá) MAC"; return; fi
  grep -i "^$p" "$OUI" 2>/dev/null | head -n1 | cut -f2- -d' ' || true; }

touch "$ALLOW" 2>/dev/null || true
unknown=0; total=0
printf '%-16s %-18s %-28s %s\n' IP MAC VÝROBCE STAV
while read -r ip mac; do
  [ -n "$mac" ] || continue; total=$((total + 1))
  v="$(vendor "$mac")"; name="$(grep -i "^$mac" "$ALLOW" 2>/dev/null | cut -f2- -d' ' || true)"
  if [ -n "$name" ]; then st="známé: $name"; else st="NEZNÁMÉ"; unknown=$((unknown + 1))
    evidence_log net-inventory stredni "Neznámé zařízení $ip $mac (${v:-výrobce neznámý})" \
      "$(jq -cn --arg ip "$ip" --arg mac "$mac" --arg v "$v" --arg cidr "$CIDR" '{ip:$ip,mac:$mac,vendor:$v,cidr:$cidr}')"; fi
  printf '%-16s %-18s %-28s %s\n' "$ip" "$mac" "${v:-?}" "$st"
  if [ "$PORTS" = 1 ] && command -v nmap >/dev/null && [ -z "${HD_NEIGH_FILE:-}" ]; then
    open="$(nmap -n -Pn --top-ports 100 --open "$ip" -oG - 2>/dev/null | grep -o 'Ports: .*' || true)"
    [ -n "$open" ] && { echo "   otevřené porty: $open"; evidence_log net-inventory info "Otevřené porty $ip: $open"; }
  fi
done < <(discover | sort -u)
evidence_log net-inventory info "Inventura $CIDR: $total zařízení, neznámých $unknown"
echo "Celkem $total, neznámých $unknown. Známá zařízení zapište do $ALLOW (formát: 'aa:bb:cc:dd:ee:ff Název')."
