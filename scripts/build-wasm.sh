#!/usr/bin/env bash
# Reprodukovatelné sestavení web/wasm/ z core/ (Rust 1.90.0, wasm-bindgen-cli 0.2.129).
set -euo pipefail
cd "$(dirname "$0")/.."
(cd core && cargo build --locked --release --target wasm32-unknown-unknown --features wasm)
wasm-bindgen --target web --no-typescript --out-dir web/wasm core/target/wasm32-unknown-unknown/release/elfall_core.wasm
H=$(openssl dgst -sha384 -binary web/wasm/elfall_core_bg.wasm | base64 -w0)
sed -i "s|^const WASM_SHA384 = '.*';|const WASM_SHA384 = '$H';|" web/core-loader.js
echo "wasm SHA-384: $H"
