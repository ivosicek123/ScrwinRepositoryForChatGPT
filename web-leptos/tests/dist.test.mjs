// node --test web-leptos/tests/dist.test.mjs (po trunk build --release)
import test from 'node:test';
import assert from 'node:assert';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
const d = (p) => new URL(`../dist/${p}`, import.meta.url);
const read = (p) => readFileSync(d(p), 'utf8');
const sha384 = (b) => createHash('sha384').update(b).digest('base64');
const html = read('index.html');
const wasm = readFileSync(d('elfall-web_bg.wasm'));
const csp = read('csp.txt').trim();

test('SRI: glue JS, wasm a CSS odpovídají souborům', () => {
  for (const f of ['elfall-web.js', 'elfall-web_bg.wasm', 'style.css']) {
    const m = html.match(new RegExp(`href="/${f.replace(/[.]/g, '\\.')}"[^>]*integrity="sha384-([^"]+)"`));
    assert.ok(m, f); assert.strictEqual(m[1], sha384(readFileSync(d(f))), f);
  }
});
test('CSP: jediný inline skript je povolen přesně svým SHA-384', () => {
  const inline = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
  assert.strictEqual(inline.length, 1);
  assert.ok(csp.includes(`'sha384-${sha384(inline[0])}'`));
  assert.match(csp, /^default-src 'none'; script-src 'self' 'wasm-unsafe-eval' 'sha384-[A-Za-z0-9+/=]+';/);
  assert.match(csp, /connect-src 'self' https:\/\/mempool\.space;/);
  assert.match(csp, /frame-ancestors 'none'/);
  assert.doesNotMatch(csp, /unsafe-inline|'unsafe-eval'|\*/);
  assert.ok(html.includes('http-equiv="Content-Security-Policy"'));
  assert.ok(read('nginx-csp.conf').includes(csp) && read('_headers').includes(csp));
});
test('žádné inline styly/handlery v index.html', () => {
  assert.doesNotMatch(html, /\sstyle=|\son[a-z]+=/i);
});
test('glue od wasm-bindgen bez eval/new Function', () => {
  assert.doesNotMatch(read('elfall-web.js'), /\beval\(|new Function\(/);
});
test('wasm: platný modul, žádné tajné API (sign/seal/vault)', async () => {
  const m = await WebAssembly.compile(wasm);
  const ex = WebAssembly.Module.exports(m).map((e) => e.name).join(' ');
  assert.doesNotMatch(ex, /sign|seal|vault|secret|pin_hash/i);
});
test('service worker: verze = hash wasm, necachuje cizí originy ani /passkey/', () => {
  const sw = read('sw.js');
  assert.ok(sw.includes(`eleanor-leptos-${createHash('sha256').update(wasm).digest('hex').slice(0, 12)}`));
  assert.match(sw, /u\.origin !== location\.origin/);
  assert.match(sw, /\/passkey\//);
  for (const f of ['./elfall-web.js', './elfall-web_bg.wasm', './style.css']) assert.ok(sw.includes(`'${f}'`), f);
});
test('vlastní pin: zavaděč ověřuje SHA-384 wasm a předává init() bajty, ne URL', () => {
  const inline = html.match(/<script type="module">([\s\S]*?)<\/script>/)[1];
  assert.strictEqual(inline.match(/WASM_SHA384 = '([^']+)'/)[1], sha384(wasm));
  assert.match(inline, /crypto\.subtle\.digest\('SHA-384', bytes\)/);
  assert.match(inline, /init\(\{ module_or_path: bytes \}\)/);
  assert.match(inline, /throw new Error\('wasm pin'\)/);
  assert.doesNotMatch(inline, /window\.wasmBindings/, 'exporty se nevystavují do window');
});
test('vlastní pin: změněný wasm zavaděč odmítne (simulace)', async () => {
  const inline = html.match(/<script type="module">([\s\S]*?)<\/script>/)[1];
  const pin = inline.match(/WASM_SHA384 = '([^']+)'/)[1];
  const tampered = Buffer.from(wasm); tampered[tampered.length - 1] ^= 1;
  const digest = (b) => createHash('sha384').update(b).digest('base64');
  assert.strictEqual(digest(wasm), pin);
  assert.notStrictEqual(digest(tampered), pin);
});
