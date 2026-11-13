# MiCA checklist for ELFALL / Eleanor (EU Regulation 2023/1114)

*Working checklist, not legal advice. Confirm with a Czech lawyer and ČNB before any mainnet launch.*

## 1. Scope – what ELFALL is
- [ ] **Eleanor = non-custodial wallet.** Recital 83: hardware/software providers of non-custodial wallets are out of
      MiCA scope, *as long as* ELFALL never holds or controls users' keys/assets. Keep: seed only on device, no server
      signing, no recovery service, no pooled addresses.
- [ ] **Card cash-out / swaps** must be performed by a **licensed CASP** (active: MoonPay, Coinbase), user contracts with them.
      ELFALL must not receive crypto or fiat on users' behalf → otherwise it becomes exchange/transfer/custody CASP
      (Art. 3(1)(16) services a, c, d, j).
- [ ] If ELFALL ever receives/transmits orders, gives crypto advice, or holds keys → **CASP authorisation (Art. 59–63)**
      from **ČNB**, incl. minimum capital (Art. 67: €50k/€125k/€150k by service class), governance, complaints handling,
      safeguarding, ICT/DORA.

## 2. FLC / yFLC classification
- [ ] Testnet only today → not offered to the public, no MiCA obligations yet. Keep "no value, testnet" labelling.
- [ ] **Danger: "1 FLC = 0.33 USD".** If presented as a *stable/target value* referencing USD, FLC can qualify as an
      **EMT** (refers to one official currency, Art. 3(1)(7)) – EMT issuance only by a **credit institution or e-money
      institution** + white paper (Art. 48–51). A basket/other reference → **ART** (Art. 3(1)(6)), authorisation under
      Title III (Art. 16+), own funds, reserve of assets, redemption rights. ⇒ Keep 0.33 USD strictly a *display
      constant*, never "peg/stable/redeemable", no reserve claims.
- [ ] If FLC is a plain "other crypto-asset" (no stabilisation, no reference) offered to the public in the EU or admitted
      to trading → **Title II crypto-asset white paper** (Art. 4–6), notified to ČNB (Art. 8), marketing communications
      rules (Art. 7), liability for the white paper (Art. 15), 14-day retail right of withdrawal (Art. 13 MiCA: 14 calendar days, free of charge, without giving reasons; does not apply if the crypto-asset was already admitted to trading before purchase).
- [x] **yFLC is FROZEN (zmrazeno)** by owner decision 2026-10-13: not issued, not distributed, not priced.
- [ ] yFLC ("yield") – any promised yield/interest: EMT/ART must not grant interest (Art. 40, 50); yield products may
      also be MiFID II financial instruments → out of MiCA, into securities law. Get legal opinion before mainnet.

## 2a. Scenario B findings (small FLC offer under Art. 4(2) exemption) — 2026-10-13
- [ ] **Art. 4(2) exemption** from the white-paper obligation applies only if the offer is: (a) to **fewer than 150
      natural or legal persons per member state** (acting for their own account), or (b) total consideration **below
      EUR 1,000,000 over 12 months**, or (c) addressed **solely to qualified investors**.
- [ ] The offeror must still be a **legal person** (Art. 4(1)(a)) – an individual cannot offer FLC to the public.
- [ ] **Art. 7 marketing rules** still apply: communications clearly identifiable, fair, clear, not misleading,
      consistent with any white paper; no peg/yield/guarantee claims.
- [ ] **Art. 4(4)**: the exemptions do **not** apply if the offeror communicates an intention to seek **admission to
      trading** (EU listing) – any listing plan removes the exemption and requires a full white paper.
- [ ] **Cap calculation:** EUR 1,000,000 × ECB rate 1.1188 USD/EUR (13 Oct 2026) = USD 1,118,800; at display price
      0.33 USD/FLC ⇒ **≈ 3.39M FLC** maximum per 12 months. **Recommended cap ~3.0M FLC** (buffer for FX moves).
- [ ] **Cost estimates (estimates only, not quotes):** exempt route (legal person, legal opinion, marketing review)
      **~70–200k CZK**; full Title II white paper route **~250–600k CZK**.
- [ ] **ESMA MiCA register status (checked 13 Oct 2026):**

| Provider | Entity | NCA | Authorised | ELFALL status |
|---|---|---|---|---|
| MoonPay | MoonPay Europe B.V. | AFM (NL) | 30/12/2024 | Active |
| Coinbase | Coinbase Luxembourg S.A. | CSSF (LU) | 20/06/2025 | Active |
| Ramp | Ramp Ireland | CBI (IE) | 02/12/2025 | Excluded (owner decision) |
| Banxa | EU Internet Ventures B.V. | AFM (NL) | 08/10/2025 | Excluded (owner decision) |
| Transak | – | – | not listed | Excluded |
| Mercuryo | – | – | not listed | Excluded |

## 3. AML / Travel Rule
- [ ] **Regulation (EU) 2023/1113 (TFR)** applies from 30 Dec 2024 to CASPs: originator/beneficiary info on every
      crypto transfer, no de-minimis threshold; self-hosted wallet transfers > €1,000 need ownership verification.
      ELFALL as non-custodial wallet is not obliged, but partner CASPs will request data → design UX for it.
- [ ] Czech AML Act **253/2008 Sb.** – CASPs are obliged persons (KYC, monitoring, reporting to FAÚ). Only licensed
      providers perform KYC; ELFALL collects none.
- [ ] Sanctions screening is done by partners; ELFALL blocks known sanctioned addresses client-side (optional).

## 4. DORA (Regulation (EU) 2022/2554)
- [ ] Applies from **17 Jan 2025** to CASPs authorised under MiCA (and issuers of ARTs): ICT risk framework, incident
      classification & reporting to ČNB, resilience testing, ICT third-party register (e.g. RunPod, Gemini, RPC providers).
- [ ] Not directly applicable to a non-custodial wallet developer, but adopt as best practice (`docs/security-weights.md`).

## 5. Czech specifics
- [ ] Competent authority: **Česká národní banka (ČNB)** – Act No. **31/2025 Sb.** (zákon o digitalizaci finančního trhu),
      effective 15 Feb 2025.
- [ ] Transitional (grandfathering) regime for entities with a Czech trade licence before 30 Dec 2024: had to apply by
      **31 Jul 2025**; **ended 1 Jul 2026**. Since then only CASPs authorised by ČNB or another EU NCA may provide services
      in CZ. ELFALL has no grandfathering → must partner with authorised CASPs or apply as new.
- [ ] EU-wide maximum transitional period ended **1 Jul 2026** (Art. 143(3)).

## 6. Key dates
| Date | Event |
|---|---|
| 30 Jun 2024 | Titles III & IV (ART/EMT) apply |
| 30 Dec 2024 | MiCA fully applicable (CASPs, white papers); TFR applies |
| 17 Jan 2025 | DORA applies |
| 15 Feb 2025 | Czech Act 31/2025 Sb. effective |
| 31 Jul 2025 | CZ deadline to apply for CASP to use transitional regime |
| 1 Jul 2026 | End of transitional period in CZ / EU max |

## 7. Other obligations to keep
- [ ] GDPR (data minimisation, DPA with providers, 72 h breach notification to ÚOOÚ).
- [ ] Marketing: no "investment", "guaranteed", "stable", "peg" claims for FLC.
- [ ] Market abuse rules (Title VI) if FLC is ever traded: insider info, no manipulation.

## Sources
- MiCA (EUR-Lex): https://eur-lex.europa.eu/eli/reg/2023/1114/oj/eng
- MiCA Art. 3 definitions (ESMA): https://www.esma.europa.eu/publications-and-data/interactive-single-rulebook/mica/article-3-definitions
- Recital 83 (non-custodial wallets): https://www.headlesslaw.com/mica/recitals/83
- TFR 2023/1113: https://eur-lex.europa.eu/eli/reg/2023/1113/oj/eng
- DORA 2022/2554: https://eur-lex.europa.eu/eli/reg/2022/2554/oj/eng
- ČNB – end of MiCA transitional period 1 Jul 2026: https://www.cnb.cz/cs/cnb-news/aktuality/Upozorneni-Ceske-narodni-banky-Konec-prechodneho-obdobi-podle-narizeni-MiCA-k1.7.2026
- ČNB – competence under MiCA: https://www.cnb.cz/cs/dohled-financni-trh/novinky/pusobnost-cnb_podle_narizeni_o_trzich_kryptoaktiv_mica/
- ČNB – crypto legislation: https://www.cnb.cz/cs/dohled-financni-trh/legislativni-zakladna/kryptoaktiva/pravni-predpisy/
- Act 31/2025 Sb.: https://www.dauc.cz/predpisy/6191/31-2025-sb
