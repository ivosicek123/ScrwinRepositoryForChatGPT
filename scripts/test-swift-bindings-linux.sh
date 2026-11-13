#!/usr/bin/env bash
# Ověření UniFFI Swift vazeb a zero-copy C ABI bez macOS: Swift 6 na Linuxu + hostitelská libelfall_core.so.
# Kompiluje i aplikační iosApp/iosApp/RustGpuHandoff.swift (s -D RUST_CORE), Metal se jen simuluje.
set -euo pipefail
R="$(cd "$(dirname "$0")/.." && pwd)"; T=$(mktemp -d)
(cd "$R/core" && cargo build --locked --release --features ffi --lib)
mkdir -p "$T/inc" && cp "$R/core/bindings/elfall_coreFFI.h" "$R/core/include/elfall_gpu.h" "$T/inc/"
sed -e 's/use "Darwin"//' -e 's/header "elfall_coreFFI.h"/header "elfall_coreFFI.h"\n    header "elfall_gpu.h"/' \
  "$R/core/bindings/elfall_coreFFI.modulemap" > "$T/inc/module.modulemap"
swiftc -O -D RUST_CORE -I "$T/inc" -L "$R/core/target/release" -lelfall_core \
  "$R/core/swift-harness/main.swift" "$R/core/swift-harness/channel_gpu.swift" \
  "$R/iosApp/iosApp/RustGpuHandoff.swift" "$R/core/bindings/elfall_core.swift" -o "$T/t"
LD_LIBRARY_PATH="$R/core/target/release" "$T/t"
