# Váhy bezpečnostních pravidel

Skóre incidentu = `max(základní úroveň, round(Σ váha × signál))`, omezeno na 1–5. Pevné kategorie (GDPR, průnik,
nelegalita, ohrožení prostředků) vždy → úroveň 5 / Ivo. Implementace: `assistant/approval.py`.

| Kontrola / signál | Váha | Obranná akce |
|---|---|---|
| Únik seedu / klíče (podezření) | 5.0 | zmrazit převody, notifikovat Iva, doporučit přesun prostředků |
| Únik osobních údajů (GDPR) | 5.0 | izolovat, zachovat důkazy, Ivo → hlášení ÚOOÚ do 72 h |
| Neautorizovaný přístup k backendu/repo | 4.5 | zneplatnit tokeny, rotovat klíče, logy |
| Únik API tokenu / feed URL | 4.0 | revokovat token, audit |
| Phishing / podvodná doména cílící uživatele | 3.5 | varování v aplikaci, report registrátorovi |
| Selhání biometrie opakovaně (≥ 5) | 2.5 | dočasný zámek, vyžádat PIN zařízení |
| Mainnet adresa v testnet režimu | 1.5 | blokovat, vysvětlit |
| Neplatná adresa / chybný vstup | 0.5 | validace, nápověda |
| Výpadek veřejného API | 0.5 | fallback, zobrazit stav |

## Kontroly v aplikaci (váha = priorita implementace)
| Kontrola | Váha | Stav |
|---|---|---|
| Seed jen v Keystore/Keychain, vázán na biometrii | 5 | ✅ |
| Podpis jen na testnetu (mainnet zamčen) | 5 | ✅ |
| Žádné klíče v PWA / u AI | 5 | ✅ |
| Žádné tajemství v repozitáři (.gitignore, kontrola) | 4 | ✅ |
| Validace adres + náhled poplatku před odesláním | 4 | ✅ |
| Audit log incidentů (hash řetězec) | 3 | stub |
| Rate-limit / zámek po selhání autentizace | 3 | systémový (BiometricPrompt) |

## Závazná pravidla a omezení (deny-by-default)
1. **Žádný sběr tajemství:** aplikace, PWA, AI, backend ani podpora nikdy nežádají, nepřijímají, neloggují ani neukládají
   seed, mnemonic, privátní klíč, xprv, heslo, PIN, OTP, CVV ani čísla karet. Kontext s takovými klíči je zahozen
   (`GeminiClient.sanitize`) a incident jde rovnou Ivovi (`secret_material_in_context`).
2. **Pouze obrana:** žádný hack-back, protiútok, skenování cizích systémů, DDoS, malware, dekódování cizích peněženek
   (§ 230–232 TZ). Neznámá akce = zamítnuto.
3. **Povinná eskalace:** úroveň ≥ 4, GDPR, bezpečnost, právo/nelegalita, pohyb peněz, práce s klíči → vždy Ivo.
   Nejistota = o úroveň výš. Žádný timeout nesmí vést k automatickému schválení.
4. **Logování:** každé rozhodnutí do hash-řetězeného audit logu (bez hodnot osobních údajů a tajemství).
5. **Rate limity:** asistent 20 rozhodnutí / 60 s na zdroj (pak eskalace); backend proxy 60 req/min/IP;
   biometrie dle systému + zámek po 5 neúspěších.
6. **Deny-by-default:** povolené jsou jen vyjmenované akce, endpointy (`runpod/devnet/devnet.json`) a domény (CSP).
7. **GDPR minimalizace:** žádné účty, žádná analytika třetích stran; do AI promptu jen anonymizovaný kontext.
8. **Retence:** provozní logy 30 dní, audit log incidentů 12 měsíců, důkazy k řízení po dobu řízení; pak smazat.
9. **KYC/AML** provádějí výhradně licencovaní poskytovatelé (MiCA CASP) v jejich rozhraní; ELFALL KYC data nepřijímá.
10. **Supply chain:** GitHub Actions připnuté na SHA, Dependabot, CodeQL, žádná tajemství v repozitáři.
