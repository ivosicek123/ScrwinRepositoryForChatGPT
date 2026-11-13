# Security re-review (2026-10-13)

## Applied in this PR
- CI: all actions pinned to commit SHAs; workflow + per-job `permissions: contents: read`; `persist-credentials: false`;
  job timeouts; concurrency cancel; Gradle wrapper validation; pytest pinned.
- Added `.github/dependabot.yml` (actions, gradle, pip) and `.github/workflows/codeql.yml` (manual/weekly – repo is private,
  code-scanning upload needs GitHub Advanced Security; enable GHAS or make public, then add push/PR triggers).
- Android: `network_security_config` (no cleartext, system CAs only), `usesCleartextTraffic=false`, `allowBackup=false` +
  `fullBackupContent=false` + `dataExtractionRules` (no cloud backup/device transfer), zxing `CaptureActivity` `exported=false`,
  R8 `isMinifyEnabled`/`isShrinkResources` for **release** + `proguard-rules.pro`.
- iOS: explicit ATS (`NSAllowsArbitraryLoads=false`).
- Web: CSP meta (self + mempool.space/blockstream.info only, `object-src 'none'`, `base-uri 'none'`), `no-referrer`;
  `web/_headers` with CSP, HSTS, nosniff, `X-Frame-Options: DENY`, Permissions-Policy, COOP.
- RunPod Dockerfile runs as non-root (uid 10001). `.gitignore` extended (`*.pem`, `*.p12`, `*.key`, `.env.*`).
- Bitcoin data failover (mempool.space → Blockstream) – availability.

## Not applied – recommended next
1. `runpod/requirements.txt` uses `>=` ranges → pin exact versions with hashes (`pip-compile --generate-hashes`).
2. Gradle: enable dependency verification (`gradle/verification-metadata.xml`) + lockfiles.
3. Dockerfile: pin base image by digest (`python:3.12-slim@sha256:…`).
4. R8 release build not exercised in CI (only debug) → add `assembleEleanorBitcoinTestnetRelease` (unsigned) job and verify keep rules.
5. Certificate pinning for mempool/blockstream – trade-off (rotation risk); consider only for own backend.
6. Web CSP `style-src 'unsafe-inline'` needed by bundled qrcode-generator; remove once QR uses SVG DOM only.
7. `web/app.js` uses `innerHTML` (12×) – audit that only escaped/static data is inserted, or switch to `textContent`.
8. Upgrade actions to latest majors (checkout v7, setup-java v6, setup-python v7, upload-artifact v7) via Dependabot PRs.
9. Branch protection on `FaltynIvo`: required CI, no force-push, signed commits.
10. `scripts/issue_flc_testnet.py` reads secrets from `scripts/.env` – keep testnet-only; never reuse for mainnet; use HSM/KMS later.
11. Secret scanning + push protection (GitHub setting).
