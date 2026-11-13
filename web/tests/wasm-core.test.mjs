import test from 'node:test';
import assert from 'node:assert';
import { readFileSync } from 'node:fs';
import { createHash, generateKeyPairSync, sign } from 'node:crypto';
import init, * as core from '../wasm/elfall_core.js';

const bytes = readFileSync(new URL('../wasm/elfall_core_bg.wasm', import.meta.url));
await init({ module_or_path: bytes });

test('loader pin matches binary', () => {
  const pin = readFileSync(new URL('../core-loader.js', import.meta.url), 'utf8').match(/WASM_SHA384 = '([^']+)'/)[1];
  assert.strictEqual(createHash('sha384').update(bytes).digest('base64'), pin);
});
test('address validation (checksum)', () => {
  assert.strictEqual(core.checkAddress('tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7'), undefined);
  assert.match(core.checkAddress('tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k8'), /Neplatný/);
  assert.match(core.checkAddress('bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4'), /mainnet/);
  assert.strictEqual(core.checkAddress(''), 'Zadejte adresu');
});
test('payment URI + amounts', () => {
  const p = core.parsePayment('bitcoin:tb1qx?amount=0.5');
  assert.strictEqual(p.address, 'tb1qx'); assert.strictEqual(p.amountSats, 50_000_000);
  assert.strictEqual(core.formatSats(1), '0.00000001');
  assert.throws(() => core.parseBtcAmount('1.000000001'));
  assert.throws(() => core.formatSats(-1));
});
test('framing + Ed25519 verify against Node crypto', () => {
  const msg = new TextEncoder().encode('pay 1 FLC');
  const framed = core.signingInput(msg);
  assert.deepStrictEqual(Buffer.from(framed.slice(0, 14)).toString('latin1'), 'ELFALL-SIG-v1\0');
  assert.deepStrictEqual([...core.parseSigningInput(framed)], [...msg]);
  const { publicKey, privateKey } = generateKeyPairSync('ed25519');
  const pk = publicKey.export({ format: 'der', type: 'spki' }).subarray(-32);
  const sig = sign(null, Buffer.from(framed), privateKey);
  assert.strictEqual(core.verifyFramed(pk, msg, sig), true);
  assert.strictEqual(core.verifyFramed(pk, new TextEncoder().encode('pay 2 FLC'), sig), false);
  assert.strictEqual(core.verifyFramed(pk, msg, sign(null, Buffer.from(msg), privateKey)), false); // bez rámce
});
test('rpId', () => { assert.ok(core.rpIdMatches('elfall.test', 'a.elfall.test')); assert.ok(!core.rpIdMatches('elfall.test', 'xelfall.test')); });
