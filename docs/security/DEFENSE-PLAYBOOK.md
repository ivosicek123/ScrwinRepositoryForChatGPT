# Obrana infrastruktury, dat a zařízení – příručka (pouze obrana)

Bez osobních údajů. Platí pro vlastníka, přispěvatele i uživatele aplikace.

## 1. Infrastruktura a ochrana proti úniku dat
- **Účty:** GitHub, Apple ID, Google, e-mail, operátor, banka, Bank iD – unikátní hesla ze správce hesel, 2FA přednostně **hardwarovým klíčem FIDO2/passkey**; SMS 2FA jen jako poslední možnost (SIM swap).
- **GitHub:** jediný kolaborátor, ruleset (zákaz force-push a mazání větví, povinné review CODEOWNERS, podepsané commity), tokeny s minimálními scopes a expirací, pravidelná kontrola *Settings → Sessions / SSH keys / Applications*, žádné veřejné forky.
- **Tajemství:** jen v proměnných prostředí / správci tajemství; gitleaks v CI; při podezření na únik **nejdřív rotovat**, pak vyšetřovat.
- **Zálohy:** šifrované, offline kopie (3-2-1), test obnovy.
- **Logy a monitoring:** GitHub audit/events, Actions běhy, upozornění na nové přihlášení u všech účtů.

## 2. Třídy útoků a obrana
| Útok | Jak vypadá | Obrana |
|---|---|---|
| Phishing / falešná podpora | e-mail/SMS/chat s odkazem, „ověřte účet“, falešný Bank iD | kontrola domény, passkeys/FIDO2 (odolné proti phishingu), nikdy nesdílet seed ani kódy |
| Malware / RAT / nástroje vzdáleného přístupu | AnyDesk/TeamViewer „na pomoc“, neznámé APK, makra | instalace jen z obchodů, Play Protect, žádný sideloading, odinstalovat neznámé nástroje |
| Supply chain | kompromitovaná knihovna/akce | SHA piny, dependency verification, Dependabot s cooldownem, SBOM, osv-scanner, Scorecard (SLSA) |
| Credential stuffing | přihlášení uniklými hesly | unikátní hesla, kontrola Have I Been Pwned, 2FA, rate-limit na backendu |
| SIM swap / port-out / SS7 | převzetí čísla, odchyt SMS | **PIN/heslo u operátora**, zákaz výměny SIM bez osobní návštěvy, nepoužívat SMS jako 2FA pro důležité účty |
| Wi-Fi evil twin / deauth | falešná síť se stejným jménem | HTTPS/HSTS (aplikace má ATS/NSC), nepřipojovat se automaticky k otevřeným sítím, WPA3, VPN od důvěryhodného poskytovatele |
| Bluetooth | párování s neznámým zařízením, sledovací tagy | vypnout, když se nepoužívá; mazat neznámá spárovaná zařízení; upozornění na neznámé AirTagy (iOS/Android) |
| USB („juice jacking“), podvržená zařízení | veřejné nabíječky, neznámé flash disky | vlastní nabíječka/datový blokátor, neznámá USB nepřipojovat; iOS „Příslušenství USB“ vypnuto při zamčení |
| Stalkerware / MDM profily (iPhone) | konfigurační profil, VPN, správa zařízení | Nastavení → Obecné → *VPN a správa zařízení*; smazat neznámé profily; Bezpečnostní kontrola |
| Podvodné platební odkazy (SEP-7) | změněná adresa příjemce | kontrola adresy, podepsané SEP-7 URI (viz SR-05) |

## 3. Detekce a reakce (playbooky)
**A. Podezření na kompromitaci iPhonu**
1. Aktualizovat iOS. 2. *Bezpečnostní kontrola* (Nastavení → Soukromí a zabezpečení): zastavit sdílení, zkontrolovat zařízení přihlášená k Apple ID. 3. *VPN a správa zařízení*: odstranit neznámé profily. 4. *Najít → Lidé*: komu sdílím polohu. 5. Zapnout **Režim izolace (Lockdown Mode)** při cíleném ohrožení. 6. Změnit heslo Apple ID z jiného důvěryhodného zařízení, přidat bezpečnostní klíče. 7. Forenzní kontrola **MVT (Mobile Verification Toolkit, Amnesty International)** ze zálohy iTunes/Finder; případně odborník (NÚKIB/CSIRT.CZ).
**B. Android:** aktualizace, Play Protect, kontrola *Správci zařízení* a *Usnadnění přístupu* (časté u stalkerware), odinstalovat neznámé aplikace, MVT `mvt-android`.
**C. Router:** změnit admin heslo, aktualizovat firmware, vypnout WPS/UPnP/vzdálenou správu, zkontrolovat DNS servery a připojená zařízení, WPA3/WPA2-AES.
**D. Operátor:** nastavit zákaznický PIN, zakázat port-out/výměnu SIM bez ověření, při ztrátě signálu okamžitě volat operátora.
**E. Hardwarové klíče:** 2 klíče FIDO2 (hlavní + záložní) pro GitHub, Apple ID, Google, e-mail; podepisovací klíč `sk-ssh-ed25519` pro integrity manifest.
**F. Seed fráze:** generovat jen na zařízení offline; zapsat na papír/kov, uložit na 2 fyzicky oddělených místech; nikdy fotografovat, psát do cloudu, e-mailu, chatu ani AI asistenta; nikdo z projektu o seed nežádá.
**G. Únik tajemství z repa:** okamžitě zneplatnit/rotovat, zapsat do SECURITY-LOG, historii nepřepisovat bez souhlasu vlastníka (únik již je veřejný pro ty, kdo repo mají).

## 4. EEG a BCI zařízení – fakta
- Spotřebitelské EEG čelenky (např. Muse, Emotiv) a lékařské EEG **vyžadují fyzický kontakt elektrod s pokožkou hlavy** (suché nebo gelové elektrody); měří slabé elektrické potenciály v mikrovoltech, které na dálku zachytit nelze.
- Data přenášejí přes **Bluetooth Low Energy s krátkým dosahem** (typicky jednotky až desítky metrů) do spárované aplikace; bez nasazeného zařízení nic neměří.
- Implantabilní BCI (např. klinické studie Neuralink, Synchron, BrainGate) vyžadují **chirurgický zákrok** a jsou dostupné jen v regulovaných klinických studiích.
- **Žádná známá technologie neumí na dálku číst ani ovládat myšlenky.** EEG zachycuje hrubé vzorce aktivity (např. pozornost, spánek), nikoli obsah myšlenek, a nevysílá nic do mozku.
- **Kontrola spárovaných/okolních BLE zařízení:** iPhone *Nastavení → Bluetooth* (Moje zařízení – odebrat neznámá); Android *Nastavení → Připojená zařízení*; seznam vysílajících BLE zařízení v okolí ukáže aplikace typu nRF Connect (Nordic Semiconductor). Neznámé sledovací tagy: iOS upozorní automaticky, Android *Bezpečnost a nouzové situace → Upozornění na neznámé sledovací zařízení*.
- Pokud jsou obavy ze sledování provázeny stresem nebo nespavostí, je vhodné obrátit se i na praktického lékaře – je to běžná a rozumná součást péče o sebe.

## Zdroje
- Apple – Příručka osobní bezpečnosti / Bezpečnostní kontrola: https://support.apple.com/guide/personal-safety/welcome/web
- Apple – Režim izolace (Lockdown Mode): https://support.apple.com/105120
- Apple – Neznámé AirTagy: https://support.apple.com/119874
- Google – Upozornění na neznámé sledovače: https://support.google.com/android/answer/13658562
- MVT (Amnesty International): https://docs.mvt.re/
- CISA – Phishing-resistant MFA: https://www.cisa.gov/sites/default/files/publications/fact-sheet-implementing-phishing-resistant-mfa-508c.pdf
- FIDO Alliance – Passkeys: https://fidoalliance.org/passkeys/
- NIST SP 800-63B-4: https://pages.nist.gov/800-63-4/sp800-63b.html
- FBI/FCC – SIM swapping: https://www.fcc.gov/consumers/guides/cell-phone-fraud ; https://www.ic3.gov/PSA/2022/PSA220208
- FCC – Juice jacking: https://www.fcc.gov/juice-jacking-tips-avoid-it
- NÚKIB: https://www.nukib.gov.cz/ ; CSIRT.CZ: https://csirt.cz/
- OWASP MASVS: https://mas.owasp.org/MASVS/ ; SLSA: https://slsa.dev/ ; OpenSSF Scorecard: https://scorecard.dev/
- Coalition Against Stalkerware: https://stopstalkerware.org/
- Wi-Fi Alliance WPA3: https://www.wi-fi.org/discover-wi-fi/security
- EEG princip (NIH/NCBI StatPearls „EEG“): https://www.ncbi.nlm.nih.gov/books/NBK563295/
- Nordic nRF Connect: https://www.nordicsemi.com/Products/Development-tools/nRF-Connect-for-mobile
