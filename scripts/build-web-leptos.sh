#!/usr/bin/env bash
# Reprodukovatelné sestavení web-leptos/dist: Trunk 0.21.14 + Rust 1.90.0 + wasm-bindgen-cli 0.2.129 (stažen Trunkem
# nebo z PATH). Výstup = statické soubory (index.html, .wasm, glue JS od wasm-bindgen, CSP soubory) – bez SSR serveru.
set -euo pipefail
cd "$(dirname "$0")/../web-leptos"
cargo test --locked                       # politika (logic/passkey) nativně
NO_COLOR=true trunk build --release --locked
node --test tests/dist.test.mjs           # SRI, CSP hash, glue bez eval, SW
