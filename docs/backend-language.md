# Backend language for RunPod workers & assistant – recommendation

Current: Python (`runpod/` FastAPI/httpx/stellar-sdk handler, `assistant/` pure Python). No rewrite yet.

| Option | + | − |
|---|---|---|
| **Python (status quo)** | RunPod SDK is Python-first; stellar-sdk mature; fastest iteration; tests exist | Duplicates Kotlin logic (fees, address validation, FLC constants); dynamic typing; bigger images (~150 MB slim) |
| **Kotlin/JVM + Ktor (reuse `shared` KMP)** | Same code as apps (BtcNetwork, EsploraClient failover, DevAssets, validation) → no drift; strong typing; one CI toolchain | JVM cold start (1–3 s) hurts serverless unless GraalVM native-image; RunPod has no Kotlin SDK (use HTTP worker / custom container); Stellar Java SDK OK |
| **Go** | Tiny static binaries, ~ms cold start, great HTTP, low memory | No code sharing; stellar Go SDK good, bitcoin libs (btcd) good; another language to maintain |
| **Rust** | Max safety/perf, small images, excellent crypto crates (bdk, secp256k1) | Slowest development; steep learning curve; overkill for a read-only proxy |
| **TypeScript/Bun** | Shares code with `web/` PWA; fast startup with Bun; huge ecosystem (stellar-sdk JS) | npm supply-chain risk; no sharing with Kotlin apps; Bun maturity |

## Recommendation
1. **Keep Python for `assistant/`** (LLM tooling, Gemini SDK, governance logic; not latency-critical).
2. **Move RunPod read/proxy workers to Kotlin + Ktor server, compiled with GraalVM native-image** (or Kotlin/Native),
   reusing `shared` (BtcNetwork endpoints, EsploraClient failover, DevAssets/devnet config). Biggest win = one source
   of truth for chain logic; native-image removes the JVM cold-start problem.
3. If native-image proves painful, **Go** is the pragmatic fallback for a stateless proxy (small, fast, simple).
4. Rust only if workers ever do heavy crypto (they shouldn't – signing stays on device).
Migration path: add a `server/` Gradle module (Ktor CIO) → parity tests against Python handler → switch RunPod image.
