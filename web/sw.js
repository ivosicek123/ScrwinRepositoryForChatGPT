// App-shell cache; API calls go network-first and are never cached with personal data beyond the browser.
const CACHE = 'eleanor-pwa-v5';
const SHELL = ['./', './index.html', './style.css', './app.js', './oidc-client.js', './passkey-client.js', './core-loader.js', './wasm/elfall_core.js', './wasm/elfall_core_bg.wasm', './vendor-qr.js', './manifest.webmanifest', './icon.svg'];
self.addEventListener('install', (e) => e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting())));
self.addEventListener('activate', (e) => e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim())));
self.addEventListener('fetch', (e) => {
  const u = new URL(e.request.url);
  if (u.origin !== location.origin) return; // public APIs: straight to network
  e.respondWith(caches.match(e.request).then((r) => r || fetch(e.request)));
});
