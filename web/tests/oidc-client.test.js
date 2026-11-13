const test = require('node:test');
const assert = require('node:assert');
const O = require('../oidc-client.js');
const mem = () => { const m = new Map(); return { getItem: k => m.has(k) ? m.get(k) : null, setItem: (k, v) => m.set(k, v), removeItem: k => m.delete(k) }; };
const ISS = 'https://oidc.sandbox.bankid.cz/';
const S = 'a'.repeat(43);
const auth = (o = {}) => { const u = new URL(ISS + 'auth'); const p = Object.assign({ response_type: 'code', code_challenge: 'x', code_challenge_method: 'S256', nonce: 'n', state: S }, o); for (const k in p) if (p[k] !== null) u.searchParams.set(k, p[k]); return u.toString(); };

test('valid authorize url', () => assert.ok(O.assertAuthorizeUrl(auth(), ISS, S)));
test('foreign issuer rejected', () => assert.throws(() => O.assertAuthorizeUrl(auth().replace('oidc.sandbox.bankid.cz', 'evil.example'), ISS, S)));
test('missing PKCE rejected', () => assert.throws(() => O.assertAuthorizeUrl(auth({ code_challenge_method: 'plain' }), ISS, S)));
test('implicit flow rejected', () => assert.throws(() => O.assertAuthorizeUrl(auth({ response_type: 'token' }), ISS, S)));
test('missing nonce rejected', () => assert.throws(() => O.assertAuthorizeUrl(auth({ nonce: null }), ISS, S)));
test('callback ok + single use', () => {
  const s = mem(); O.remember(s, S);
  assert.deepStrictEqual(O.parseCallback(s, 'https://app/cb?code=c&state=' + S), { code: 'c', state: S });
  assert.throws(() => O.parseCallback(s, 'https://app/cb?code=c&state=' + S));
});
test('callback state mismatch', () => { const s = mem(); O.remember(s, S); assert.throws(() => O.parseCallback(s, 'https://app/cb?code=c&state=' + 'b'.repeat(43))); });
test('callback error param', () => { const s = mem(); O.remember(s, S); assert.throws(() => O.parseCallback(s, 'https://app/cb?error=access_denied&state=' + S)); });
test('http backend rejected', () => { assert.throws(() => O.assertSafeBackend('http://evil.example')); assert.ok(O.assertSafeBackend('http://localhost:8000')); });
test('bad state format rejected', () => assert.throws(() => O.remember(mem(), 'short')));
