# Domácí bezpečnostní sada (pouze obrana)

Bash nástroje pro kontrolu **vlastní** domácí sítě a zařízení se zápisem zjištění do důkazního logu.

> **Zákazy:** žádné skenování cizích sítí, žádné dohledávání útočníka (traceback), žádný protiútok (hack-back), žádné rušení signálu. Neoprávněný přístup k cizímu systému je trestný (§ 230 trestního zákoníku). Při podezření na trestný čin kontaktujte Policii ČR (158) nebo NÚKIB/CSIRT.CZ.

## Požadavky
`bash`, `jq`, `iproute2` (`ip`), volitelně `nmap`, `bluez` (`bluetoothctl`), pro iPhone `mvt` (`pipx install mvt`).
Debian/Ubuntu: `sudo apt install jq nmap bluez`.

## Nástroje
| Skript | Co dělá |
|---|---|
| `net-inventory.sh --i-own-this-network [--cidr 192.168.1.0/24] [--ports]` | Seznam zařízení ve vlastní síti (nmap -sn / ARP), výrobce dle MAC, porovnání s allowlistem `~/.home-defense/allowlist.txt` (`aa:bb:cc:dd:ee:ff Název`). `--ports` = top 100 portů jen na nalezených zařízeních. Odmítne veřejné rozsahy a podsítě, ve kterých počítač není (kontrola /24). |
| `ble-scan.sh [sekundy]` | Pasivní seznam Bluetooth/BLE zařízení v dosahu, třídění (sluchátka, EEG/neuro-wearables, sledovací tagy…), rady k odebrání/blokaci. |
| `iphone-checklist.md`, `mvt-ios.sh <záloha>` | Ruční kontrola iPhonu + MVT analýza šifrované zálohy Finder/iTunes. |
| `router-audit.sh` | Interaktivní audit routeru (firmware, heslo, WPS, vzdálená správa, UPnP, šifrování, DNS, klienti, hostovská síť, přesměrování portů). |
| `evidence.sh verify \| export [soubor.md] \| add …` | Důkazní log `~/.home-defense/evidence.jsonl`: každý záznam s UTC časem, `prev_hash` a SHA-256 (řetěz – změna se odhalí). Export hlášení v Markdownu pro Policii ČR / NÚKIB. |

## Postup
1. `./net-inventory.sh --i-own-this-network` → doplňte známá zařízení do allowlistu, spusťte znovu.
2. `./ble-scan.sh 30`
3. Projděte `iphone-checklist.md`, případně `./mvt-ios.sh ~/Zálohy/iPhone`.
4. `./router-audit.sh`
5. `./evidence.sh verify && ./evidence.sh export` → hlášení + originální `evidence.jsonl` (zálohujte, nemažte).

## Fakta k EEG/BCI
EEG zařízení měří jen s elektrodami na hlavě a vysílají přes Bluetooth na krátkou vzdálenost; neumějí na dálku číst ani ovládat myšlenky. Viz `docs/security/DEFENSE-PLAYBOOK.md`.

## Testy
`tools/home-defense/tests/run.sh` (bez sítě a Bluetooth, s fixtures) – 15 testů.

## Zdroje
NÚKIB https://www.nukib.gov.cz/ · CSIRT.CZ https://csirt.cz/ · Policie ČR https://www.policie.cz/ · MVT https://docs.mvt.re/ · Nmap https://nmap.org/book/man-host-discovery.html · BlueZ https://www.bluez.org/ · Apple Personal Safety https://support.apple.com/guide/personal-safety/welcome/web
