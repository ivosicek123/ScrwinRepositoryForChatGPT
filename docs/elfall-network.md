# ELFALL síť / Network — RunPod jako první server

**CZ (hlavní):** RunPod je první síťový server ELFALL. Začínáme **výhradně na TESTNETU**. Mainnet až po testech, bezpečnostní kontrole a právní připravenosti (MiCA / ČNB).

**EN:** RunPod is ELFALL’s first network server. **TESTNET only** at start; mainnet is gated.

---

## 1. Architektura

```
ELFALL app (Android/iOS)
        │  HTTPS + bearer (později)
        ▼
RunPod Serverless worker (Python, scale-to-zero)
  routes: health, assets, btc/*, stellar/*, eth/block, sol/slot, polygon/block, flc/price
        │
        ├── mempool.space TESTNET Esplora (nebo vlastní bitcoind+esplora na CPU podu)
        ├── Horizon TESTNET (nebo stellar-quickstart na CPU podu)
        ├── Ethereum Sepolia RPC
        ├── Solana Devnet RPC
        └── Polygon Amoy RPC
```

| Vrstva | Účel |
|---|---|
| **Serverless worker** (`runpod/`) | Levné, scale-to-zero; proxy + metadata FLC/yFLC |
| **Volitelný CPU pod** | Vlastní `bitcoind` testnet + Esplora, `stellar-quickstart` — až bude kredit |

**Výchozí `NETWORK=testnet`.** Mainnet vyžaduje `ALLOW_MAINNET=true` + checklist níže.

Kód: `runpod/app/handler.py`, `Dockerfile`, `requirements.txt`, testy `runpod/tests/`.

---

## 2. Pět vývojářských testnet mincí + FLC / yFLC

| ID | Síť | Poznámka |
|---|---|---|
| tBTC | Bitcoin testnet3 | přes mempool.space/testnet |
| XLM | Stellar testnet | Horizon testnet |
| sepETH | Ethereum Sepolia | veřejný RPC |
| devSOL | Solana Devnet | veřejný RPC |
| amoyMATIC | Polygon Amoy | veřejný RPC |
| **FLC** | Stellar testnet asset | FALLCOIN — `scripts/issue_flc_testnet.py` |
| **yFLC** | Stellar testnet asset | **zmrazeno** – nevydává se ani nedistribuuje |

### Referenční cena (jen zobrazení)

**1.00 FLC = 0.33 USD** (yFLC je zmrazeno – bez ceny) je konstanta v konfiguraci (`FLC_REFERENCE_USD`).

> ⚠️ **Není to reálný peg.** Není to e-money, není to pohledávka na USD rezervy, není auditováno. Pouze pro UI na testnetu.

### yFLC (koncept)

Na testnetu je yFLC samostatné Stellar aktivum (`yFLC` od stejného issueru). „Yield-bearing wrapper“ = budoucí logika (rebase / claim) — **zmrazeno (rozhodnutí vlastníka 13. 10. 2026) – skript yFLC nevydává**, žádný hlavní síťový produkt.

---

## 3. Mainnet — fázovaný checklist (nezapínat předčasně)

1. Bezpečnostní review peněženky (keystore, biometric bind, audit závislostí).
2. Právo EU **MiCA**: token odkazující na USD / e-money token → autorizace CASP / ART/EMT, rezervy, whitepaper.
3. **ČNB** / passporting — konzultace před jakýmkoli veřejným FLC na mainnetu.
4. Rezervy + audit, pokud by kdy existoval USD-referenced token (dnes **neplánujeme** bez licence).
5. KYC/AML u výběru na kartu (Anycoin / jiný licencovaný partner).
6. Feature flag: `NETWORK=testnet` default; mainnet jen s `ALLOW_MAINNET=true`.
7. Postupné zapnutí sítí: nejdřív BTC+XLM testnet → pak Sepolia/Sol/Amoy → teprve pak mainnet po jedné síti.

---

## 4. Náklady RunPod (orientačně, zůstatek uživatele = **$0**)

| Režim | Odhad | Poznámka |
|---|---|---|
| **Serverless** (scale-to-zero) | ~$0.0001–0.0004 / s aktivního běhu GPU/CPU workeru; při nule requestů ≈ **$0** | Vhodné pro proxy API |
| **Malý CPU pod** (např. 2 vCPU / 4 GB) | řádově **$10–30 / měsíc** dle aktuálního ceníku | Pro vlastní bitcoind/Horizon |
| Deploy teď | **NE** — kredit $0; vyvíjet lokálně (`python local_runner.py`, Docker) |

Ceny ověřte na [runpod.io/pricing](https://www.runpod.io/pricing). **Nic nedeployujeme**, dokud nebude kredit.

---

## 5. Tajemství

- Issuer secret keys **nikdy** do gitu.
- Skript ukládá do `scripts/.env` a `scripts/flc_keys.json` (gitignore).
- Do výstupu / PR jdou jen **veřejné** klíče a odkazy stellar.expert (testnet).

## 6. Partner follow-up

Anycoin: domluvit partner API / deeplink (viz `docs/elfall-architecture.md`). RunPod: doplnit kredit až po lokálních testech.


---

## 7. Vydané TESTNET assety (veřejné klíče)

Vygenerováno `scripts/issue_flc_testnet.py` (2026-10-12). Tajné klíče **nejsou** v gitu.

| | |
|---|---|
| Issuer | `GCUMZ6Z7GAYFL2MGSFRG7HXDPL4FRBPZYAYJGTWEEKDI3NJIFUMSRPKT` |
| Distribution | `GCDRCM3ZSPYC2WPN5WLLDOTO7FSTOEGLC7IBOCWEWMO7BYUMO5V7IXF6` |
| FLC | [stellar.expert](https://stellar.expert/explorer/testnet/asset/FLC-GCUMZ6Z7GAYFL2MGSFRG7HXDPL4FRBPZYAYJGTWEEKDI3NJIFUMSRPKT) |
| yFLC | [stellar.expert](https://stellar.expert/explorer/testnet/asset/yFLC-GCUMZ6Z7GAYFL2MGSFRG7HXDPL4FRBPZYAYJGTWEEKDI3NJIFUMSRPKT) |
| Issuer účet | [explorer](https://stellar.expert/explorer/testnet/account/GCUMZ6Z7GAYFL2MGSFRG7HXDPL4FRBPZYAYJGTWEEKDI3NJIFUMSRPKT) |

Referenční cena zobrazení: **1 FLC = 0,33 USD** (není peg).
