# iPhone – bezpečnostní kontrola (krok za krokem)

1. **Aktualizace:** Nastavení → Obecné → Aktualizace softwaru (zapnout automatické).
2. **Bezpečnostní kontrola (Safety Check):** Nastavení → Soukromí a zabezpečení → Bezpečnostní kontrola → *Spravovat sdílení a přístup*: zkontrolujte lidi, aplikace, zařízení přihlášená k Apple ID.
3. **Profily a správa zařízení:** Nastavení → Obecné → VPN a správa zařízení – nemáte-li firemní telefon, nemá tu být nic. Neznámé profily odstraňte.
4. **VPN:** Nastavení → VPN – jen ty, které jste sami nainstalovali.
5. **Najít (Find My):** aplikace Najít → Lidé – komu sdílíte polohu.
6. **Apple ID:** Nastavení → [jméno] → Přihlášení a zabezpečení: dvoufaktorové ověření, **bezpečnostní klíče** (FIDO2), zkontrolovat důvěryhodná telefonní čísla; Zařízení – odebrat neznámá.
7. **Kód zámku:** min. 6 číslic nebo alfanumerický; *Ochrana ukradeného zařízení* zapnout.
8. **Aplikace:** odinstalovat neznámé; Nastavení → Soukromí → Poloha, Mikrofon, Kamera, Bluetooth, Místní síť – zkontrolovat oprávnění.
9. **Bluetooth:** Nastavení → Bluetooth → odebrat neznámá zařízení. iOS sám upozorní na neznámý AirTag, který se s vámi pohybuje.
10. **Režim izolace (Lockdown Mode):** při podezření na cílený útok – Nastavení → Soukromí a zabezpečení → Režim izolace.
11. **MVT:** vytvořte **šifrovanou** zálohu ve Finderu/iTunes a spusťte `./mvt-ios.sh <složka_zálohy>`.
12. **Operátor:** nastavte zákaznický PIN a zákaz výměny SIM / přenosu čísla bez osobního ověření.

Zdroje: https://support.apple.com/guide/personal-safety/welcome/web · https://support.apple.com/105120 · https://docs.mvt.re/en/latest/ios/backup/check/
