// App-shell cache (verze = hash wasm, doplní scripts/web-leptos-csp.sh). Cizí originy a /passkey/* jdou vždy na síť.
const CACHE = 'eleanor-leptos-__WASM_TAG__';
const SHELL = ['./', './index.html', './style.css', './elfall-web.js', './elfall-web_bg.wasm', './manifest.webmanifest', './icon.svg'];
self.addEventListener('install', (e) => e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting())));
self.addEventListener('activate', (e) => e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim())));
self.addEventListener('fetch', (e) => {
  const u = new URL(e.request.url);
  if (u.origin !== location.origin || e.request.method !== 'GET' || u.pathname.startsWith('/passkey/')) return;
  e.respondWith(caches.match(e.request).then((r) => r || fetch(e.request)));
});
