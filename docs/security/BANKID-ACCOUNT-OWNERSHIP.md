# Bank iD — ověření vlastnictví bankovního účtu

**Stav:** technická specifikace / sandbox only  
**Vlastník projektu:** Ivo Faltýn  
**Rozsah:** ELFALL / Identity Core; bez ukládání bankovních přihlašovacích údajů

## 1. Cíl a hranice

Ověřit, že konkrétní IBAN je uveden v claimu `paymentAccounts` vráceném Bank iD Profile API pro právě autentizovaného uživatele. Bank iD dokumentuje `profile.paymentAccounts` jako seznam platebních účtů, jejichž majitelem je fyzická osoba u daného IdP. Samotné ověření identity (KYC) není totéž co ověření vlastnictví účtu.

Tato kontrola nesmí sama o sobě autorizovat výběr, převod ani změnu payout účtu.

## 2. Požadovaný tok

1. Backend zahájí OIDC Authorization Code flow se scope `openid profile.name profile.paymentAccounts` a PKCE S256.
2. Vygeneruje kryptograficky náhodné `state` a `nonce`; pending transakce má krátkou TTL, je jednorázová a svázaná s konkrétním interním uživatelem, účelem a požadovaným IBAN.
3. Callback ověří jednorázový state, PKCE, podpis ID tokenu, issuer, audience/azp, exp, iat a nonce podle konfigurace Bank iD.
4. Backend načte Profile API pomocí access tokenu a vyžaduje shodu `profile.sub` s `sub` v ověřeném ID tokenu.
5. Backend kanonizuje IBAN (odstranění mezer, uppercase; navíc validace délky a checksumu IBAN).
6. Vlastnictví je potvrzeno pouze při přesné shodě kanonického požadovaného IBAN s jednou položkou v claimu `paymentAccounts`.
7. Chybějící nebo neposkytnutý claim znamená `inconclusive`, nikoli úspěch. Neshoda znamená `not_confirmed`.
8. Výsledek se sváže s interním identity subject a konkrétní payout destinací; změna IBAN vyžaduje nové ověření.

## 3. Pravidla vyhodnocení

- `confirmed = true` pouze při přesné shodě IBAN v `paymentAccounts`.
- Jméno je podpůrná kontrola konzistence, nikoli důkaz vlastnictví bankovního účtu.
- Normalizace jména nesmí přepsat ani obejít výsledek kontroly IBAN.
- Nepředpokládat, že každý uživatel Bank iD má platební účet nebo že každý IdP claim poskytne.
- Je-li tvar claimu jiný než očekávaný, bezpečně odmítnout vyhodnocení a zaznamenat technickou chybu bez uložení surového profilu.
- Účet u jiného IdP/banky nemusí být v seznamu dostupný; neúspěch shody tedy neprokazuje, že uživatel účet nevlastní.

## 4. Minimalizace dat a audit

- Nikdy neukládat přihlašovací údaje do internetového bankovnictví.
- Neukládat surový Profile payload, celý IBAN ani nepotřebné KYC údaje do aplikačních logů.
- Uložit pouze interní subject, identifikátor poskytovatele/issuer, čas a verzi ověřovacího toku, výsledek, důvod výsledku a bezpečný otisk payout IBAN (např. HMAC s klíčem v secret store).
- Přístup k výsledkům omezit na služby, které je potřebují; přístupy auditovat a definovat retenční dobu.
- Chybové odpovědi nesmí prozrazovat, zda jiný uživatel ověřil konkrétní IBAN.

## 5. Oddělení autentizace a finanční autorizace

Úspěšné ověření vlastnictví účtu není souhlas s konkrétní platbou ani autorizace výběru. Finanční operace vyžaduje samostatnou čerstvou autentizaci, kontrolu oprávnění, limitů, idempotency key, kontrolu rizika a auditní záznam. ELEANOR zůstává autoritou pro casino ledger a settlement; ELFALL pouze spravuje wallet/identity/security.

## 6. Sandbox a produkční brána

- Výchozí prostředí zůstává Bank iD Sandbox.
- Testy musí používat výhradně syntetické profily a fiktivní IBAN.
- Produkční přístup se nesmí aktivovat bez smlouvy a schválení Bank iD, registrace aplikace, správně nastavených redirect URI, produkčních tajemství v secret store, bezpečnostní revize a integračních testů s reálným IdP.
- Produkční konfigurace musí zůstat fail-closed; žádný fallback na ruční zadání jména nebo IBAN nesmí vytvořit stav `confirmed`.

## 7. Testovací matice

- Přesná shoda IBAN → `confirmed`.
- IBAN s mezerami / malými písmeny → po kanonizaci shoda.
- Jiný IBAN → `not_confirmed`.
- Chybějící `paymentAccounts` → `inconclusive`.
- Neplatný IBAN checksum → odmítnout před porovnáním.
- Shodné jméno, ale odlišný IBAN → nikdy nepotvrdit.
- Odlišné formátování jména, ale shodný IBAN → vlastnictví rozhoduje claim; případná name warning je samostatná.
- Nesoulad `sub` mezi ID tokenem a Profile API → odmítnout.
- Opakovaný/expirující state, chybný nonce, issuer, audience, podpis nebo PKCE → odmítnout.
- Opakované callbacky a souběžné požadavky → nejvýše jedno úspěšné spotřebování state.
- Surový IBAN ani token se neobjeví v logu.

## 8. Aktuální audit zdrojového kódu

Při čtení výchozí větve byly nalezeny `Logic.kt`, `App.kt`, `Config.kt` a `BankIdTest.kt`. Implementace již používá OIDC, PKCE, state/nonce, Profile API a porovnání `paymentAccounts`. Testy jsou však mockované a používají syntetická data; nedokládají živé spojení s Bank iD.

Před produkcí je nutné dále prověřit:
- trvalé a distribuované úložiště pending state (aktuální tok používá `PendingStore` předaný jako výchozí instance modulu);
- vazbu pending state na přihlášenou interní session a CSRF/origin ochranu;
- úplný tvar claimu `paymentAccounts` dle smluvní technické specifikace a všech podporovaných bank;
- chování při chybách Profile API, rate limits a opakování požadavků;
- validaci IBAN checksum a bezpečné logování;
- provozní TLS/mTLS, správu klíčů a rotaci tajemství.

**Tato specifikace sama o sobě neznamená, že Bank iD je aktivně připojeno nebo že proběhlo reálné ověření identity či vlastnictví účtu.**
