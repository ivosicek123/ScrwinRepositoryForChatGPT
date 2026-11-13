#!/usr/bin/env bash
# ElfallCore.xcframework z core/ (macOS + Xcode 15+, Rust 1.90.0 s cíli aarch64-apple-ios a aarch64-apple-ios-sim).
# Výstup: core/build/ElfallCore.xcframework + iosApp/RustCore/elfall_core.swift (obaleno #if RUST_CORE).
set -euo pipefail
cd "$(dirname "$0")/../core"
export IPHONEOS_DEPLOYMENT_TARGET=16.0
for t in aarch64-apple-ios aarch64-apple-ios-sim; do
  cargo build --locked --release --target "$t" --features ffi --lib
done
# vazby z hostitelské (debug) knihovny – release profil stripuje UniFFI metadata
cargo build --locked --features bindgen
rm -rf build/gen build/ElfallCore.xcframework && mkdir -p build/gen/headers
./target/debug/uniffi-bindgen generate --library target/debug/libelfall_core.dylib --language swift --no-format --out-dir build/gen
cp build/gen/elfall_coreFFI.h build/gen/headers/
cp include/elfall_gpu.h build/gen/headers/   # ruční C ABI zero-copy bufferů (gpu.rs)
sed 's/header "elfall_coreFFI.h"/header "elfall_coreFFI.h"\n    header "elfall_gpu.h"/' build/gen/elfall_coreFFI.modulemap > build/gen/headers/module.modulemap
xcodebuild -create-xcframework \
  -library target/aarch64-apple-ios/release/libelfall_core.a -headers build/gen/headers \
  -library target/aarch64-apple-ios-sim/release/libelfall_core.a -headers build/gen/headers \
  -output build/ElfallCore.xcframework
{ echo "#if RUST_CORE"; cat build/gen/elfall_core.swift; echo "#endif"; } > ../iosApp/RustCore/elfall_core.swift
echo "OK: core/build/ElfallCore.xcframework"
