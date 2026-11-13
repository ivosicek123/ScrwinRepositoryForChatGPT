// Tenký zavaděč Rust/Wasm jádra (core/, feature "wasm"). Veškerá logika validace, rámování
// a ověření podpisu je v Rustu; JS jen ověří integritu binárky, inicializuje ji a vystaví API.
// CSP: script-src 'self' 'wasm-unsafe-eval' (bez 'unsafe-eval').
import init, * as core from './wasm/elfall_core.js';

const WASM_SHA384 = 'NrA7Mo4k9ov+NohnZgeS6z8gpl7hrnHH6bL8sWvVDPRsSvD+kDFaCV6yv5qPQXPE'; // aktualizuje scripts/build-wasm.sh

async function load() {
  const bytes = await (await fetch(new URL('./wasm/elfall_core_bg.wasm', import.meta.url))).arrayBuffer();
  const digest = btoa(String.fromCharCode(...new Uint8Array(await crypto.subtle.digest('SHA-384', bytes))));
  if (digest !== WASM_SHA384) throw new Error('Wasm integrita nesouhlasí');
  await init({ module_or_path: bytes });
  globalThis.ElfallCore = Object.freeze({
    parsePayment: core.parsePayment, checkAddress: core.checkAddress, parseBtcAmount: core.parseBtcAmount,
    formatSats: core.formatSats, rpIdMatches: core.rpIdMatches,
    signingInput: core.signingInput, parseSigningInput: core.parseSigningInput, verifyFramed: core.verifyFramed,
  });
  dispatchEvent(new Event('elfall-core-ready'));
}
load().catch((e) => console.error('ElfallCore:', e.message)); // fail-closed: bez jádra adresy neprojdou
