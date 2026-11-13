# ELFALL developer testnet (design)

Status: **config + stubs only, not deployed** (RunPod balance $0). Source of truth: `runpod/devnet/devnet.json`;
the app-side catalogue mirrors it in `shared/.../assets/DevAssets.kt`.

## Coins (≥ 5 chains) + FLC/yFLC
| Id | Chain | Data source (primary → fallback) | Faucet |
|---|---|---|---|
| tBTC | Bitcoin testnet3 | mempool.space → Blockstream Esplora | coinfaucet.eu |
| sBTC | Bitcoin signet | mempool.space → Blockstream Esplora | signetfaucet.com |
| sepETH | Ethereum Sepolia (chainId 11155111) | publicnode → rpc.sepolia.org | Google Cloud Web3 faucet |
| tLTC | Litecoin testnet | litecoinspace.org (Esplora-compatible) | litecointf.salmen.website |
| XLM | Stellar testnet | Horizon testnet | Friendbot |
| devSOL | Solana devnet | api.devnet.solana.com | faucet.solana.com |
| (amoyMATIC) | Polygon Amoy | already in app catalogue, optional | – |
| **FLC / yFLC** | Stellar testnet `credit_alphanum4`, issuer `GCUMZ6…RPKT` | Horizon | FLC issued by `scripts/issue_flc_testnet.py`; **yFLC frozen (zmrazeno) – not issued** |

**Display price: 1 FLC = 0.33 USD** — a testnet display constant (`FLC_REFERENCE_USD`), **not a peg**, no redemption
claim, no reserve. Any "target price" language on mainnet would trigger MiCA ART/EMT analysis (see `docs/mica-checklist.md`).

## Architecture
```
app (Eleanor/ELFALL) ──► public testnet APIs (read + broadcast, failover)
          └──► (optional) RunPod worker "elfall-devnet" = read-only proxy/cache, reads devnet.json
```
- Worker is stateless, holds **no keys**; signing happens only on device (Keystore/Keychain).
- Rate limit 60 req/min/IP, deny-by-default routing (only endpoints listed in `devnet.json`).
- `mainnet_signing: false` is enforced in config tests (`runpod/tests/test_devnet_config.py`).

## Deploy later (when funded)
1. Build `runpod/Dockerfile`, push image, create serverless endpoint with `NETWORK=testnet`, mount `devnet.json`.
2. Secrets (if any, e.g. a paid RPC key) only as RunPod endpoint env vars — never in repo.
3. Point the `elfall` flavor at the endpoint; `eleanor` stays standalone on public APIs.
