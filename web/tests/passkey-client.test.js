const test = require('node:test');
const assert = require('node:assert');
const P = require('../passkey-client.js');
const CH = P.fromBuf(new Uint8Array(32).fill(7).buffer);
const reg = (o = {}) => Object.assign({ challenge: CH, rp: { id: 'elfall.test', name: 'ELFALL' }, user: { id: P.fromBuf(new Uint8Array(32).buffer), name: 'ivo', displayName: 'ivo' },
  pubKeyCredParams: [{ type: 'public-key', alg: -7 }, { type: 'public-key', alg: -8 }], timeout: 300000, attestation: 'none',
  authenticatorSelection: { residentKey: 'required', userVerification: 'required' }, excludeCredentials: [] }, o);
const auth = (o = {}) => Object.assign({ challenge: CH, rpId: 'elfall.test', timeout: 300000, userVerification: 'required', allowCredentials: [] }, o);

test('base64url round trip', () => { const b = new Uint8Array([0, 255, 62, 63, 1]).buffer; assert.deepStrictEqual(new Uint8Array(P.toBuf(P.fromBuf(b))), new Uint8Array(b)); });
test('bad base64url rejected', () => assert.throws(() => P.toBuf('a+b/')));
test('creation options ok', () => { const o = P.creationOptions(reg(), 'elfall.test'); assert.strictEqual(o.challenge.byteLength, 32); assert.strictEqual(o.authenticatorSelection.userVerification, 'required'); });
test('subdomain origin ok', () => assert.ok(P.creationOptions(reg(), 'app.elfall.test')));
test('foreign rpId rejected', () => assert.throws(() => P.creationOptions(reg({ rp: { id: 'evil.example', name: 'x' } }), 'elfall.test')));
test('suffix trick rejected', () => assert.throws(() => P.creationOptions(reg(), 'notelfall.test')));
test('RS256 rejected', () => assert.throws(() => P.creationOptions(reg({ pubKeyCredParams: [{ type: 'public-key', alg: -257 }] }), 'elfall.test')));
test('UV not required rejected', () => assert.throws(() => P.creationOptions(reg({ authenticatorSelection: { userVerification: 'preferred' } }), 'elfall.test')));
test('short challenge rejected', () => assert.throws(() => P.requestOptions(auth({ challenge: P.fromBuf(new Uint8Array(8).buffer) }), 'elfall.test')));
test('request UV required', () => assert.throws(() => P.requestOptions(auth({ userVerification: 'discouraged' }), 'elfall.test')));
test('full login flow with stubs', async () => {
  const calls = [];
  const fetch = async (url, init) => { calls.push([url, JSON.parse(init.body)]); return { ok: true, json: async () => url.endsWith('/options') ? auth() : { user: 'ivo' } }; };
  const credentials = { get: async () => ({ rawId: new Uint8Array([1, 2]).buffer, response: { authenticatorData: new Uint8Array([3]).buffer, clientDataJSON: new Uint8Array([4]).buffer, signature: new Uint8Array([5]).buffer, userHandle: null } }) };
  const r = await P.login({ fetch, credentials, host: 'elfall.test' }, 'https://api.elfall.test', 'ivo');
  assert.deepStrictEqual(r, { user: 'ivo' });
  assert.strictEqual(calls[1][1].challenge, CH);
  assert.strictEqual(calls[1][1].credentialId, 'AQI');
});
test('backend error surfaces', async () => {
  const fetch = async () => ({ ok: false, status: 400 });
  await assert.rejects(P.register({ fetch, credentials: {}, host: 'elfall.test' }, 'https://api.elfall.test', 'ivo'));
});
