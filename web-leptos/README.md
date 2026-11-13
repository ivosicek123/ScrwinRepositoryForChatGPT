# Eleanor PWA – Leptos (CSR) + Trunk

Port obrazovek `web/` (Domů, Přijmout + QR, Převést, Historie, Síť, Pomocník, Nastavení, Bezpečnostní kontrola)
do Rustu/Leptos 0.8, **jen klientské vykreslování (CSR), žádný SSR server**. Validace adres, BIP21, částek a
`rpId` přímo z `elfall-core` (tentýž kód jako Android/iOS). Nová komponenta **SecureLogin** (passkeys).

## Sestavení a testy
```
scripts/build-web-leptos.sh   # cargo test → trunk build --release --locked → node --test tests/dist.test.mjs
```
Výstup `web-leptos/dist/`: prázdný `index.html` (Trunk), `elfall-web_bg.wasm`, `elfall-web.js` (jediný JS most =
glue od wasm-bindgen + jeden inline `init()` od Trunku), `style.css`, `sw.js`, `csp.txt`, `_headers`, `nginx-csp.conf`.
Nástroje: Rust 1.90.0, Trunk 0.21.14, wasm-bindgen 0.2.129 (připnuto v Cargo.toml). `wasm-opt` vypnut
(`data-wasm-opt="0"`) – Trunkem stažený binaryen neuměl bulk-memory; velikost řeší `[profile.release]`.

## Bezpečnost – co platí a co ne
- **Ochranu před XSS zajišťuje CSP a escapování, ne WebAssembly.** Wasm kód má přes wasm-bindgen stejný přístup
  k DOM jako JS; pokud by aplikace vložila útočníkův text jako HTML, Wasm ji neochrání. Proto: Leptos vkládá text
  jako textové uzly (escapuje), nepoužíváme `inner_html`, odkazy z dat (txid) se skládají jen po validaci (64 hex).
- **CSP:** `default-src 'none'; script-src 'self' 'wasm-unsafe-eval' 'sha384-<hash inline zavaděče>'`,
  `connect-src 'self' https://mempool.space` (self = backend `/passkey/*`), `frame-ancestors 'none'`, bez
  `unsafe-inline` / `unsafe-eval`. Hash počítá Trunk hook `scripts/web-leptos-csp.sh` po každém buildu a zapíše
  ho do `<meta>` (bez frame-ancestors – meta ho neumí), `_headers` a `nginx-csp.conf` (`deploy/web-leptos/`).
- **Vlastní pin wasm:** Trunk hook nahradí inline init zavaděčem, který wasm stáhne, ověří SHA-384 proti pinu z buildu a předá `init()` až ověřené bajty (fail-closed). Je to navíc k SRI.
- **SRI:** Trunk přidá `integrity="sha384-…"` na modulepreload glue JS, preload wasm a CSS (testováno).
- **`strip = true` / `opt-level = "z"` nejsou bezpečnostní opatření** – jen zmenšují binárku. Wasm je veřejný
  a lze ho dekompilovat; nesmí obsahovat žádné tajemství ani „skrytou“ logiku.
- **Žádné tajné klíče v prohlížeči:** wasm neexportuje podpis/šifrování (test), localStorage jen adresa
  k sledování a stav checklistu. Passkey: privátní klíč zůstává v autentizátoru; challenge vydává a podpis
  ověřuje backend (webauthn4j); klient vynucuje UV, ES256/EdDSA, rpId = host, challenge ≥ 16 B.
- Service worker cachuje jen shell vlastního originu; veřejné API a `/passkey/*` nikdy.

## Neportováno
Bank iD (OIDC) – ve `web/` vypnuto příznakem; zůstává v JS verzi. Původní `web/` zůstává beze změny.
