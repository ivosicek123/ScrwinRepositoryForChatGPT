// Eleanor PWA — read-only companion. No keys, no signing, no seed in the browser.
// Defense-only design: nothing here accesses third-party systems beyond public read APIs.
'use strict';
const API = { testnet: 'https://mempool.space/testnet/api', mainnet: 'https://mempool.space/api' };
const EXPLORER = { testnet: 'https://mempool.space/testnet', mainnet: 'https://mempool.space' };
let infoNet = 'testnet';
const $ = (id) => document.getElementById(id);
const store = { get: (k) => localStorage.getItem('eleanor.' + k), set: (k, v) => localStorage.setItem('eleanor.' + k, v) };

// --- Navigation ---
function go(view) {
  document.querySelectorAll('.view').forEach((v) => v.classList.toggle('active', v.id === view));
  if (view === 'network') loadNetwork();
  if (view === 'history') loadHistory();
  if (view === 'home') loadBalance();
  scrollTo(0, 0);
}
document.querySelectorAll('[data-go]').forEach((b) => b.addEventListener('click', () => go(b.dataset.go)));

// --- Address helpers: logika je v Rust/Wasm (core/src/validate.rs, web/wasm/), JS jen deleguje ---
const core = () => globalThis.ElfallCore;
function parsePayment(raw) {
  const c = core();
  if (!c) return { address: String(raw).trim(), amount: null };
  const p = c.parsePayment(raw);
  const r = { address: p.address, amount: p.amountSats == null ? null : c.formatSats(p.amountSats) };
  if (p.free) p.free();
  return r;
}
// fail-closed: bez Wasm modulu žádnou adresu neoznačíme za platnou
function checkAddress(a) { const c = core(); return c ? (c.checkAddress(a) ?? null) : 'Ověřovací modul (Wasm) se načítá…'; }
addEventListener('elfall-core-ready', () => { if ($('dest') && $('dest').value) updateTransfer(); });
const fmt = (sats) => (core() && Number.isSafeInteger(sats) && sats >= 0 ? core().formatSats(sats) : (sats / 1e8).toFixed(8));
async function j(url) { const r = await fetch(url); if (!r.ok) throw new Error(r.status); return r.json(); }

// --- Receive ---
function renderQr(addr) {
  const qr = qrcode(0, 'M'); qr.addData('bitcoin:' + addr); qr.make();
  $('qr').innerHTML = qr.createSvgTag(6, 0); $('qr').style.display = 'block';
}
$('saveWatch').onclick = () => {
  const a = parsePayment($('watchAddr').value).address, e = checkAddress(a);
  $('watchErr').textContent = e || '';
  if (e) return;
  store.set('watch', a); renderQr(a); loadBalance();
};

// --- Balance / history ---
async function loadBalance() {
  const a = store.get('watch'); if (!a) return;
  $('watchShort').textContent = a.slice(0, 10) + '…' + a.slice(-6);
  try {
    const d = await j(`${API.testnet}/address/${a}`);
    const s = d.chain_stats, m = d.mempool_stats;
    $('balance').textContent = fmt(s.funded_txo_sum - s.spent_txo_sum + m.funded_txo_sum - m.spent_txo_sum) + ' tBTC';
  } catch { $('balance').textContent = 'Nedostupné'; }
}
async function loadHistory() {
  const a = store.get('watch'), el = $('histList');
  if (!a) { el.innerHTML = '<div class="empty">📭<br>Nejdřív nastavte adresu v sekci Přijmout.</div>'; return; }
  el.innerHTML = '<div class="empty">Načítám…</div>';
  try {
    const [txs, tip] = await Promise.all([j(`${API.testnet}/address/${a}/txs`), fetch(`${API.testnet}/blocks/tip/height`).then((r) => r.text())]);
    if (!txs.length) { el.innerHTML = '<div class="empty">📭<br>Zatím žádné transakce.</div>'; return; }
    el.innerHTML = txs.map((t) => {
      const inn = t.vout.filter((o) => o.scriptpubkey_address === a).reduce((x, o) => x + o.value, 0);
      const out = t.vin.filter((i) => i.prevout?.scriptpubkey_address === a).reduce((x, i) => x + i.prevout.value, 0);
      const net = inn - out, conf = t.status.confirmed ? (+tip - t.status.block_height + 1) : 0;
      const date = t.status.block_time ? new Date(t.status.block_time * 1000).toLocaleString('cs-CZ') : 'v mempoolu';
      return `<a class="row" href="${EXPLORER.testnet}/tx/${t.txid}" target="_blank" rel="noopener"><span class="${net >= 0 ? 'ok' : 'err'}">${net >= 0 ? '+' : ''}${fmt(net)}</span><span class="small muted">${conf ? conf + ' potvrz.' : 'nepotvrzeno'} · ${date}</span></a>`;
    }).join('');
  } catch { el.innerHTML = '<div class="empty">Historie je teď nedostupná.</div>'; }
}

// --- Transfer helper (no signing) ---
let fees = null;
async function updateTransfer() {
  const { address } = parsePayment($('dest').value), e = checkAddress(address);
  $('destMsg').textContent = $('dest').value ? (e || '✅ Adresa vypadá platně (testnet)') : '';
  $('destMsg').className = 'small ' + (e ? 'err' : 'ok');
  try { fees = fees || await j(`${API.testnet}/v1/fees/recommended`); } catch { return; }
  const vb = +$('vb').value || 141;
  $('feeEst').innerHTML = [['Rychle', fees.fastestFee], ['~30 min', fees.halfHourFee], ['~1 h', fees.hourFee]]
    .map(([n, r]) => `<div class="row"><span>${n} (${r} sat/vB)</span><b>${r * vb} sats</b></div>`).join('');
}
$('dest').oninput = updateTransfer; $('vb').oninput = updateTransfer;

// --- Network (read-only) ---
document.querySelectorAll('[data-net]').forEach((b) => b.onclick = () => {
  infoNet = b.dataset.net;
  document.querySelectorAll('[data-net]').forEach((x) => x.classList.toggle('on', x === b));
  loadNetwork();
});
async function loadNetwork() {
  const el = $('netInfo'), b = API[infoNet];
  el.innerHTML = '<div class="empty">Načítám…</div>';
  try {
    const [f, mp, blocks] = await Promise.all([j(`${b}/v1/fees/recommended`), j(`${b}/mempool`), j(`${b}/v1/blocks`)]);
    let price = ''; try { const p = await j(`${API.mainnet}/v1/prices`); price = `<div class="row"><span>Cena BTC (mainnet)</span><b>${p.USD.toLocaleString('cs-CZ')} USD · ${p.EUR.toLocaleString('cs-CZ')} EUR</b></div>`; } catch {}
    el.innerHTML = `<div class="card">
      <div class="row"><span>Poplatky (sat/vB)</span><b>${f.fastestFee} · ${f.halfHourFee} · ${f.hourFee}</b></div>
      <div class="row"><span>Mempool</span><b>${mp.count.toLocaleString('cs-CZ')} tx · ${(mp.vsize / 1e6).toFixed(1)} MvB</b></div>
      <div class="row"><span>Výška bloku</span><b>${blocks[0].height}</b></div>${price}</div>
      <h3>Poslední bloky</h3>${blocks.slice(0, 6).map((x) => `<div class="row"><span>#${x.height}</span><span class="small muted">${x.tx_count} tx · ${new Date(x.timestamp * 1000).toLocaleTimeString('cs-CZ')}</span></div>`).join('')}`;
  } catch { el.innerHTML = '<div class="empty">Síťová data nejsou dostupná.</div>'; }
}

// --- Helper glossary (Gemini planned; see assistant/) ---
const GLOSSARY = {
  'poplatek': 'Poplatek (sat/vB) platíte těžařům za zařazení transakce do bloku. Vyšší = rychlejší.',
  'mempool': 'Mempool je čekárna nepotvrzených transakcí.',
  'seed': '12 slov = záloha peněženky. Nikdy je nikomu nesdělujte, ani asistentovi.',
  'testnet': 'Testovací síť – mince nemají hodnotu. Eleanor podepisuje jen na testnetu.',
  'potvrzení': 'Počet bloků nad transakcí. 1+ = zapsáno, 6 = velmi bezpečné.',
  'bip21': 'Formát bitcoin:adresa?amount=… pro QR kódy.',
};
$('q').oninput = () => {
  const q = $('q').value.toLowerCase().trim();
  if (/\b(heslo|seed|privátní|private|klíč)\b/.test(q) && /(pošli|zadej|sdílej|send|share)/.test(q)) {
    $('answers').innerHTML = '<div class="card err">⚠️ Seed ani klíče nikdy nikam nezadávejte. Tohle je typický podvod.</div>'; return;
  }
  const hits = Object.entries(GLOSSARY).filter(([k, v]) => q && (k.includes(q) || v.toLowerCase().includes(q)));
  $('answers').innerHTML = hits.map(([k, v]) => `<div class="card"><b>${k}</b><br>${v}</div>`).join('') || (q ? '<div class="empty">Nenalezeno.</div>' : '');
};

$('clear').onclick = () => { localStorage.clear(); location.reload(); };

// --- Init ---
const saved = store.get('watch');
if (saved) { $('watchAddr').value = saved; renderQr(saved); }
loadBalance();
if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js');

// --- Bank iD (sandbox) – feature flag, výchozí VYPNUTO ---
const BANKID_ENABLED = false;
const BANKID_BACKEND = ''; // URL backend/bankid služby (jen přes HTTPS) – doplní se při nasazení, nikdy tajemství
const BANKID_ISSUER = 'https://oidc.sandbox.bankid.cz/'; // sandbox
(function bankId() {
  const card = document.getElementById('bankid-card');
  if (!card || !BANKID_ENABLED || !BANKID_BACKEND || !window.ElfallOidc) return;
  const O = window.ElfallOidc;
  const backend = O.assertSafeBackend(BANKID_BACKEND);
  card.hidden = false;
  // Návrat z Bank iD (redirect_uri = tato stránka): ověřit state a předat code backendu.
  if (new URLSearchParams(location.search).has('state')) {
    (async () => {
      try {
        const cb = O.parseCallback(sessionStorage, location.href);
        history.replaceState(null, '', location.pathname); // odstranit code z URL/historie
        const r = await fetch(backend + '/auth/callback', {method: 'POST', credentials: 'omit', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(cb)});
        if (!r.ok) throw new Error(r.status);
        const res = await r.json();
        alert(res.level === 'bank_aml_verified' ? 'Identita ověřena přes Bank iD' : 'Ověření dokončeno');
      } catch (e) { history.replaceState(null, '', location.pathname); alert('Ověření přes Bank iD se nezdařilo'); }
    })();
  }
  document.getElementById('bankid-verify').addEventListener('click', async () => {
    try {
      const r = await fetch(backend + '/auth/start', {method: 'POST', credentials: 'omit', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({purpose: 'kyc'})});
      if (!r.ok) throw new Error(r.status);
      const j = await r.json();
      O.remember(sessionStorage, j.state);
      location.assign(O.assertAuthorizeUrl(j.authorize_url, BANKID_ISSUER, j.state));
    } catch (e) { alert('Ověření přes Bank iD se nezdařilo'); }
  });
})();

// --- Bezpečnostní kontrola (jen lokální stav, žádná data neopouštějí zařízení) ---
(function securityChecklist() {
  const boxes = document.querySelectorAll('[data-sec]');
  if (!boxes.length) return;
  const KEY = 'elfall.securityChecklist';
  let state = {};
  try { state = JSON.parse(localStorage.getItem(KEY) || '{}') || {}; } catch (e) { state = {}; }
  const score = () => {
    const done = [...boxes].filter((b) => b.checked).length;
    document.getElementById('sec-score').textContent = `Splněno ${done} z ${boxes.length}.`;
  };
  boxes.forEach((b) => {
    b.checked = state[b.dataset.sec] === true;
    b.addEventListener('change', () => { state[b.dataset.sec] = b.checked; localStorage.setItem(KEY, JSON.stringify(state)); score(); });
  });
  score();
})();
