#!/usr/bin/env bash
# Testy bez sítě a bez Bluetooth (fixtures). Spuštění: tools/home-defense/tests/run.sh
set -uo pipefail
T="$(cd "$(dirname "$0")" && pwd)"; R="$T/.."; F="$T/fixtures"
export HD_DIR="$(mktemp -d)"; export HD_LOG="$HD_DIR/evidence.jsonl"
pass=0; fail=0
ok() { if eval "$2"; then pass=$((pass+1)); echo "ok - $1"; else fail=$((fail+1)); echo "FAIL - $1"; fi; }

# síťová inventura
ok "bez potvrzení vlastnictví odmítne" '! "$R/net-inventory.sh" --cidr 192.168.1.0/24 >/dev/null 2>&1'
ok "veřejný rozsah odmítnut" '"$R/net-inventory.sh" --i-own-this-network --cidr 8.8.8.0/24 >/dev/null 2>&1; [ $? -eq 3 ]'
ok "cizí privátní podsíť odmítnuta" '"$R/net-inventory.sh" --i-own-this-network --cidr 10.250.250.0/24 >/dev/null 2>&1; [ $? -eq 3 ]'
echo "a4:c1:38:11:22:33 Router" > "$HD_DIR/allowlist.txt"
out="$(HD_SKIP_LOCAL_CHECK=1 HD_NEIGH_FILE="$F/neigh.txt" "$R/net-inventory.sh" --i-own-this-network --cidr 192.168.1.0/24)"
ok "inventura najde 2 neznámá" 'grep -q "neznámých 2" <<<"$out"'
ok "výrobce Raspberry Pi" 'grep -q "Raspberry Pi" <<<"$out"'
ok "náhodná MAC rozpoznána" 'grep -q "náhodná" <<<"$out"'
ok "známé zařízení" 'grep -q "známé: Router" <<<"$out"'

# BLE
out="$(HD_BLE_FILE="$F/ble.txt" "$R/ble-scan.sh")"
ok "BLE EEG wearable klasifikováno" 'grep -q "EEG/neuro" <<<"$out"'
ok "BLE AirTag označen" 'grep -q "SLEDOVACÍ TAG" <<<"$out"'
ok "BLE sluchátka" 'grep -q "sluchátka" <<<"$out"'

# router
out="$("$R/router-audit.sh" "$F/router-answers.txt")"
ok "router: 3 nevyhovující" 'grep -q "Nevyhovujících bodů: 3" <<<"$out"'

# evidence
ok "řetěz neporušen" '"$R/evidence.sh" verify | grep -q "neporušen"'
ok "JSONL validní a UTC" 'jq -e "select(.ts_utc|test(\"Z$\"))" "$HD_LOG" >/dev/null'
"$R/evidence.sh" export "$HD_DIR/r.md" >/dev/null
ok "export hlášení" 'grep -q "NÚKIB" "$HD_DIR/r.md" && grep -q "Řetěz neporušen" "$HD_DIR/r.md"'
sed -i '2s/Neznámé/Upraveno/' "$HD_LOG"
ok "manipulace odhalena" '! "$R/evidence.sh" verify >/dev/null'

rm -rf "$HD_DIR"; echo "pass=$pass fail=$fail"; [ "$fail" -eq 0 ]
