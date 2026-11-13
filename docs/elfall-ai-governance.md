# ELFALL / Eleanor – řízení AI a schvalovací hierarchie

> Stav: návrh + kódový stub (`assistant/`). Žádný reálný API klíč není v repozitáři.

## 1. Tři úrovně (implementace `assistant/hierarchy.py`)

| Úroveň | Kdo | Smí | Nesmí |
|---|---|---|---|
| **Tier 1 – Analytik („sprinter“)** | Gemini AI | Analyzovat, navrhnout **možnosti A–E** s důsledky a varováními; sám uzavřít jen **nízké informační** položky (úroveň 1, bez kategorie, bez akce) | Schválit cokoli nad úroveň 1; vidět seed/klíče/osobní údaje; provádět akce; obejít Fallcoin |
| **Tier 2 – Supervizor** | Fallcoin | Rozšířená expertní schválení **středních případů (2–3)**; **veto** návrhu Gemini; risk skóre 0–100, policy pravidla, rate limit, hash-řetězený audit log | Schválit úroveň ≥ 4, GDPR, bezpečnost, právo/nelegalitu, pohyb peněz, práci s klíči; povolit útočné akce |
| **Tier 3 – Watchdog / konečná instance** | Ivo Faltyn (člověk) | Konečné rozhodnutí ve všech kritických věcech (fronta `IvoWatchdog`, rozhoduje jen `decided_by="ivo"`) | Ani Ivo nemůže v systému povolit útočné akce – v kódu neexistují |

Tok: `událost → Gemini (A–E) → [úroveň 1 → Gemini] | Fallcoin review (risk + policy + rate limit + audit) → schváleno / veto / eskalace → Ivo`.

### Pravidla Fallcoin (policy, deny-by-default)
| Pravidlo | Váha rizika | Výsledek |
|---|---|---|
| `offensive_action` / neznámá akce | 100 | **DENIED** + notifikace Iva |
| `secret_material_in_context` (seed, mnemonic, klíč, heslo, PIN, OTP, CVV, karta, IBAN) | 60 | eskalace Ivovi; hodnoty se nikdy nelogují |
| `watchdog_category` (critical, gdpr, security_breach, illegal, legal_uncertain, funds_at_risk, money_movement, key_handling) | 50 | eskalace Ivovi |
| `money_movement` (v kontextu je částka) | 50 | eskalace Ivovi |
| `unknown_category` | 20 | eskalace (nejistota → výš) |
| rate limit (20 událostí / 60 s na zdroj) překročen | – | eskalace Ivovi |
| Gemini navrhl neexistující možnost | – | **VETO** |

Risk = `úroveň × 15 + Σ váh` (max 100); risk ≥ 70 nebo úroveň ≥ 4 → Ivo.
Audit log: append-only SHA-256 řetězec (`AuditLog.verify()` odhalí úpravu), ukládá jen klíče kontextu, nikdy hodnoty.

## 2. Úrovně závažnosti 1–5

| Úroveň | Název | Příklady | Schvaluje |
|---|---|---|---|
| 1 | Informační | dotaz na poplatek, vysvětlení pojmu | Gemini (Tier 1), Fallcoin namátkově kontroluje |
| 2 | Nízká | opakované neplatné adresy, chyba API | Fallcoin |
| 3 | Střední | podezřelé chování relace, neobvyklá částka na testnetu, chybná konfigurace | Fallcoin |
| 4 | Vysoká | pokus o převzetí účtu, únik tokenu, podezření na phishing cílený na uživatele | **Ivo** |
| 5 | Kritická | bezpečnostní průnik, únik osobních údajů (GDPR čl. 33 – hlášení ÚOOÚ do 72 h), nelegální požadavek, ohrožení prostředků | **Ivo** (okamžitá notifikace) |

**Pevná pravidla (nelze přebít konfigurací):**
- Kategorie `critical`, `gdpr`, `security_breach`, `illegal`, `legal_uncertain`, `funds_at_risk`, `money_movement`, `key_handling` → vždy **Ivo**, bez ohledu na úroveň.
- Deny-by-default: co není výslovně povoleno (obranná akce, známá kategorie), je zamítnuto nebo eskalováno.
- Každé rozhodnutí každé úrovně se zapisuje do audit logu; logy incidentů uchovávat max. 12 měsíců (bezpečnostní incidenty s důkazy po dobu řízení), pak smazat.
- Žádná role nikdy nesbírá ani nepožaduje seed, klíče, hesla, PIN ani údaje karet.
- Fallcoin **nikdy** automaticky neschvaluje úroveň ≥ 4 ani výše uvedené kategorie.
- Když si systém není jistý (chybí úroveň, Gemini nedostupné) → eskalace o úroveň výš.

## 3. Pouze obrana – právní hranice

**Povoleno (obranné akce):** detekovat, blokovat, logovat, zneplatnit relace/tokeny, zmrazit funkce aplikace,
upozornit Iva, zachovat důkazy (hashované logy, časová razítka) pro orgány činné v trestním řízení / NÚKIB / ÚOOÚ.

**ZAKÁZÁNO:** útočné protiútoky, „hacking back“, neoprávněný přístup k cizím systémům (§ 230 TZ), dekódování cizích
peněženek, malware, nelegální sledování, automatické „odvetné“ akce. Žádná z rolí – ani Ivo – nemůže tyto akce
v systému spustit; v kódu neexistují.

## 4. Volání Gemini (placeholder)

```python
from assistant import GeminiClient, ApprovalGate, Incident
client = GeminiClient(api_key=os.environ.get("GEMINI_API_KEY"))   # bez klíče = offline stub
options = client.propose_options(incident)        # 5 možností A–E s důsledky a varováními
decision = ApprovalGate().route(incident, options) # -> AUTO | FALLCOIN | IVO (zpětná kompatibilita)

from assistant import Hierarchy
review = Hierarchy().handle(incident, gemini_choice="B")  # -> Review(verdict, tier, level, risk, audit_hash)
```
Reálné napojení: Google Gemini API (`generativelanguage.googleapis.com`, model např. `gemini-2.x-flash`),
klíč pouze v prostředí (`GEMINI_API_KEY`), nikdy v repozitáři ani v aplikaci. Do promptu se **nikdy** neposílá seed,
klíče, plné adresy uživatelů spojené s identitou, ani jiné osobní údaje (minimalizace dle GDPR čl. 5).

## 5. Web ↔ aplikace
Gemini funguje jako asistent mezi PWA (`web/`) a mobilní aplikací: nápověda, fráze, odpovědi. Fallcoin je jeho
nadřízený a poradce – kontroluje návrhy a schvaluje střední případy. Ivo dohlíží na vše kritické.

## 6. Závazná omezení (platí pro všechny úrovně)
- **Žádný sběr tajemství** (seed, klíče, hesla, PIN, OTP, CVV, karty, IBAN) – ani v promptu, logu, ticketu.
- **Pouze obrana**, žádný hack-back; neznámá akce = zamítnuto (deny-by-default).
- **Povinná eskalace** Ivovi u kritických případů, GDPR, bezpečnosti, práva/nelegality, pohybu peněz a práce s klíči;
  žádné automatické schválení po vypršení času.
- **Audit log** každého rozhodnutí; **rate limity**; **GDPR minimalizace**; **retence** logů 30 dní / incidentů 12 měsíců.
- **KYC/AML** pouze licencovaní poskytovatelé (MiCA CASP); viz `docs/mica-checklist.md`, `docs/offramp-providers.md`.
