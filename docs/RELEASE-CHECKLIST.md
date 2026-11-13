# Release checklist – 1.0.0-rc1

Stav k 2026-10-14. Tag `v1.0.0-rc1` existuje **jen lokálně** (nepushnutý); veřejný release se nevytváří (pravidlo soukromí).
Vydání až po splnění všech bodů a výslovném schválení Ivo Faltýna.

| # | Podmínka | Jak ověřit | Stav |
|---|---|---|---|
| 1 | CI zelené na `FaltynIvo` (CI, Integrity, rust-core vč. Miri, Proofs, SBOM, Scorecard) | GitHub → Actions; nejdřív vyřešit Billing & plans (joby se nyní nespouštějí) | ☐ |
| 2 | Podepsaný integrity manifest | Ed25519 klíč vlastníka v `integrity/allowed_signers`, `scripts/sign_manifest.sh`, `python3 scripts/integrity_manifest.py verify` (s podpisem) | ☐ |
| 3 | macOS / iOS build | jobs `ios-simulator`, `ios-shared-tests`, `ios-rustcore-macos` zelené; ruční test Face ID + Secure Enclave + Metal na zařízení | ☐ |
| 4 | Passkeys v prohlížečích | registrace + přihlášení: Safari (macOS/iOS), Chrome, Firefox, Edge; web-leptos SecureLogin i web/ | ☐ |
| 5 | Zero Trust | `zt-signing` secret vytvořen mimo repo; canary 1 % → 100 % dle `deploy/rollout/README.md`; alerty načtené | ☐ |
| 6 | Bezpečnostní log | `docs/security/SECURITY-LOG.md` doplněn o vydání | ☐ |
| 7 | Tajemství | žádné klíče/tokeny v repozitáři (secret scanning, `git log -p` sken) | ☐ |
| 8 | Kritické konstanty | `integrity/critical.json` beze změny (FLC 0.33 USD, testnet, issuer, off-ramp allowlist) | ☐ |
