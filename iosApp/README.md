# Eleanor iOS (standalone, testnet)

SwiftUI app using the Kotlin Multiplatform `Shared` framework (wallet core, BIP84, BIP21, fee preview, signing,
mempool.space history + network info). Screens: Příjem (QR), Převod (QR scan + Face ID confirm), Historie, Síť.

- Seed: Keychain item with `biometryCurrentSet` access control (Face ID / Touch ID), this device only.
- Network: **testnet only**, direct mempool.space (no RunPod / ELFALL network). Mainnet data on the Síť tab is display-only.

## Build (macOS)
```bash
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
brew install xcodegen && cd iosApp && xcodegen generate
xcodebuild -project Eleanor.xcodeproj -scheme Eleanor -sdk iphonesimulator -arch arm64 \
  -configuration Debug CODE_SIGNING_ALLOWED=NO build
```
CI (`.github/workflows/ci.yml`, job `ios-simulator`) does this and uploads `Eleanor-simulator.app.zip`.

## Real iPhone
Installing on a physical iPhone / TestFlight requires an **Apple Developer Program** membership (99 USD/yr),
signing certificates and a provisioning profile. Not set up — the CI artifact runs only in the iOS Simulator.

## UIKit + Metal + Rust jádro (větev ios-uikit-core)
- Shell je **UIKit** (`AppDelegate`/`SceneDelegate`, view controllery v `Controllers.swift`); SwiftUI odstraněno.
- **Metal přímo (bez MetalKit/SwiftUI)**: `MetalCanvasView` = UIView s `CAMetalLayer`, vlastní `MTLDevice`/fronta/render pipeline
  a smyčka `CADisplayLink` (běží jen během animace). Každá obrazovka má `MetalSceneView` – pozadí s gradientem
  a SDF kartami kreslené shaderem; UIKit prvky jsou nad ním jen pro text, vstup a přístupnost (VoiceOver).
- `Shaders.metal` (Metal Shading Language) – QR textura s nearest-neighbour vzorkováním, graf zůstatku (výplň s gradientem
  + čára, odkrývací animace). `ChartMath.swift` – simd lineární algebra (ortografická matice data→clip,
  interpolace matic, easing, kritická pružina), `Transitions.swift` – přechody přes simd 3×3 matice.
  Bez Metal zařízení fallback na `UIImageView`; respektuje „Omezit pohyb“.
- **Rust jádro**: `scripts/build-xcframework.sh` → `core/build/ElfallCore.xcframework`, vazby
  `RustCore/elfall_core.swift`. Výchozí `project.yml` je bez Rustu; `xcodegen --spec project-rustcore.yml`
  zapne `RUST_CORE` (`Vaults.local` → `RustCoreVault`, klíč v Keychainu sdílený → bez migrace).
- Secure Enclave podepisovač a `ASWebAuthenticationSession` (Bank iD) beze změny.
