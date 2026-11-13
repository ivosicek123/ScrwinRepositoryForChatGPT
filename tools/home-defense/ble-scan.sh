#!/usr/bin/env bash
# Pasivní seznam Bluetooth/BLE zařízení v dosahu (jen čtení; nic se nepáruje ani neruší).
# Použití: ble-scan.sh [sekundy=20]
DIR="$(cd "$(dirname "$0")" && pwd)"; source "$DIR/lib/common.sh"
SECS="${1:-20}"
list() {
  if [ -n "${HD_BLE_FILE:-}" ]; then cat "$HD_BLE_FILE"; return; fi
  need bluetoothctl || { echo "Nainstalujte bluez (bluetoothctl)."; exit 1; }
  bluetoothctl --timeout "$SECS" scan on >/dev/null 2>&1 || true
  bluetoothctl devices | sed 's/^Device //'
}
classify() { local n; n="$(echo "$1" | tr 'A-Z' 'a-z')"
  case "$n" in
    *muse*|*emotiv*|*neurosity*|*crown*|*openbci*|*mendi*|*dreem*|*neurable*|*idun*|*naox*|*eeg*) echo "EEG/neuro-wearable (měří jen při nasazení na hlavu, krátký dosah)";;
    *airpods*|*buds*|*earbud*|*beats*|*jabra*|*wh-*|*wf-*|*headphone*|*sluch*) echo "sluchátka/hearables";;
    *airtag*|*tile*|*smarttag*|*tracker*|*chipolo*) echo "SLEDOVACÍ TAG – ověřte";;
    *watch*|*band*|*fitbit*|*garmin*|*mi\ smart*) echo "nositelná elektronika";;
    *tv*|*speaker*|*soundbar*|*jbl*) echo "domácí elektronika";;
    *) echo "neurčeno";; esac; }
printf '%-18s %-30s %s\n' MAC NÁZEV TYP
n=0
while read -r mac name; do
  [ -n "$mac" ] || continue; n=$((n + 1)); t="$(classify "$name")"
  printf '%-18s %-30s %s\n' "$mac" "${name:-?}" "$t"
  case "$t" in SLEDOVACÍ*|EEG*) evidence_log ble stredni "BLE zařízení v dosahu: $name ($mac) – $t" \
    "$(jq -cn --arg mac "$mac" --arg n "$name" --arg t "$t" '{mac:$mac,name:$n,type:$t}')";; esac
done < <(list)
evidence_log ble info "BLE sken: $n zařízení v dosahu"
cat <<'TXT'
Rady: neznámé zařízení ve vlastním seznamu odeberte (iPhone: Nastavení → Bluetooth → ⓘ → Zapomenout;
Linux: bluetoothctl remove <MAC>; blokace: bluetoothctl block <MAC>). Náhodné MAC adresy se mění – to je normální.
EEG/BCI zařízení měří jen s elektrodami na hlavě; na dálku nic nečtou ani neovládají.
TXT
