# Eleanor PWA (`web/`)

Česká progresivní webová aplikace – **jen pro čtení**, testnet. Žádný seed, klíče ani podepisování v prohlížeči
(to dělá jen mobilní Eleanor s biometrií). Karty: Přijmout (QR), Převést (kontrola adresy + odhad poplatku),
Historie, Síť (poplatky, mempool, bloky, cena; mainnet jen zobrazení), Pomocník (slovníček; Gemini plánován), Nastavení.

## Spuštění
```bash
cd web && python3 -m http.server 8080   # http://localhost:8080
```
Statický hosting (GitHub Pages, Cloudflare Pages…) – service worker vyžaduje HTTPS.

## Instalace na iPhone
- **Safari**: Sdílet → „Přidat na plochu“.
- **Chrome (CriOS, iOS 16.4+)**: Sdílet → „Přidat na plochu“. Aplikace se otevře na celou obrazovku.

Knihovna QR: `vendor-qr.js` = qrcode-generator 1.4.4 (MIT), přibalena lokálně kvůli offline režimu.
