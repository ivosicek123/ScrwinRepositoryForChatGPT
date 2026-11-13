#!/usr/bin/env bash
# Interaktivní audit domácího routeru. Odpovědi a/n; lze předat soubor s odpověďmi (testy): router-audit.sh odpovedi.txt
DIR="$(cd "$(dirname "$0")" && pwd)"; source "$DIR/lib/common.sh"
ANS="${1:-/dev/stdin}"
Q=( "Firmware routeru je aktuální (kontrolováno za poslední měsíc)?|vysoka|Aktualizujte firmware z webu výrobce."
    "Výchozí administrátorské heslo bylo změněno na silné unikátní?|vysoka|Změňte heslo správce (min. 16 znaků)."
    "WPS je vypnuté?|stredni|Vypněte WPS (PIN lze prolomit)."
    "Vzdálená správa z internetu (WAN admin) je vypnutá?|vysoka|Vypněte vzdálenou správu."
    "UPnP je vypnuté (nebo jen pokud ho nutně potřebujete)?|stredni|Vypněte UPnP."
    "Wi‑Fi používá WPA3 nebo WPA2-AES (ne WEP/TKIP)?|vysoka|Nastavte WPA3/WPA2-AES."
    "DNS servery v routeru jsou ty, které jste nastavili (žádné neznámé)?|vysoka|Neznámé DNS = možné převzetí routeru; obnovte tovární nastavení a změňte hesla."
    "Seznam připojených klientů odpovídá vašim zařízením?|stredni|Neznámé klienty zablokujte, změňte heslo Wi‑Fi."
    "Síť pro hosty/IoT je oddělená?|nizka|Zapněte hostovskou síť pro IoT a návštěvy."
    "Přesměrování portů obsahuje jen pravidla, která znáte?|stredni|Smažte neznámá přesměrování portů." )
fail=0
exec 3<"$ANS"
for q in "${Q[@]}"; do IFS='|' read -r text sev fix <<<"$q"
  printf '%s [a/n] ' "$text"; read -r a <&3 || a=""; [ -t 3 ] || echo "$a"
  if [ "${a,,}" != "a" ]; then fail=$((fail + 1)); echo "  → $fix"; evidence_log router "$sev" "Router: $text – NE. $fix"; fi
done
evidence_log router info "Audit routeru dokončen, nevyhovujících bodů: $fail"
echo "Nevyhovujících bodů: $fail"
