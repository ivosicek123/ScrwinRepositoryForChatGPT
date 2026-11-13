// Passkeys (WebAuthn) – klient. Klíč nikdy neopouští autentizátor; klient jen převádí JSON
// z backendu (/passkey/*) na volby navigator.credentials a ověřuje politiku (UV required,
// jen ES256/EdDSA, rpId = host stránky). Ověření podpisu dělá výhradně backend (webauthn4j).
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api; else root.ElfallPasskey = api;
})(typeof self !== 'undefined' ? self : this, function () {
  const ALLOWED_ALGS = [-7, -8]; // ES256, EdDSA
  const B64U = /^[A-Za-z0-9_-]+$/;

  function toBuf(s) {
    if (typeof s !== 'string' || !B64U.test(s)) throw new Error('neplatné base64url');
    const b = atob(s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - s.length % 4) % 4));
    const u = new Uint8Array(b.length); for (let i = 0; i < b.length; i++) u[i] = b.charCodeAt(i);
    return u.buffer;
  }
  function fromBuf(buf) {
    const u = new Uint8Array(buf); let s = '';
    for (let i = 0; i < u.length; i++) s += String.fromCharCode(u[i]);
    return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }
  function checkRp(rpId, host) {
    const c = (typeof globalThis !== 'undefined') && globalThis.ElfallCore; // Rust/Wasm, pokud je načten
    const ok = c ? c.rpIdMatches(String(rpId), String(host)) : (host === rpId || host.endsWith('.' + rpId));
    if (!ok) throw new Error('rpId neodpovídá originu');
  }
  function checkChallenge(c) { if (toBuf(c).byteLength < 16) throw new Error('slabá challenge'); }

  function creationOptions(o, host) {
    checkRp(o.rp.id, host); checkChallenge(o.challenge);
    if (!o.authenticatorSelection || o.authenticatorSelection.userVerification !== 'required') throw new Error('vyžadováno ověření uživatele');
    const params = (o.pubKeyCredParams || []).filter(p => p.type === 'public-key' && ALLOWED_ALGS.includes(p.alg));
    if (!params.length || params.length !== o.pubKeyCredParams.length) throw new Error('nepovolený algoritmus');
    return {
      challenge: toBuf(o.challenge), rp: { id: o.rp.id, name: o.rp.name },
      user: { id: toBuf(o.user.id), name: o.user.name, displayName: o.user.displayName },
      pubKeyCredParams: params, timeout: o.timeout, attestation: 'none',
      authenticatorSelection: { residentKey: 'required', userVerification: 'required' },
      excludeCredentials: (o.excludeCredentials || []).map(c => ({ type: 'public-key', id: toBuf(c.id) }))
    };
  }
  function requestOptions(o, host) {
    checkRp(o.rpId, host); checkChallenge(o.challenge);
    if (o.userVerification !== 'required') throw new Error('vyžadováno ověření uživatele');
    return { challenge: toBuf(o.challenge), rpId: o.rpId, timeout: o.timeout, userVerification: 'required',
      allowCredentials: (o.allowCredentials || []).map(c => ({ type: 'public-key', id: toBuf(c.id) })) };
  }
  function registrationBody(challenge, cred) {
    return { challenge, attestationObject: fromBuf(cred.response.attestationObject), clientDataJSON: fromBuf(cred.response.clientDataJSON) };
  }
  function loginBody(challenge, cred) {
    const r = cred.response;
    return { challenge, credentialId: fromBuf(cred.rawId), authenticatorData: fromBuf(r.authenticatorData),
      clientDataJSON: fromBuf(r.clientDataJSON), signature: fromBuf(r.signature), userHandle: r.userHandle ? fromBuf(r.userHandle) : null };
  }
  async function post(fetchFn, base, path, body) {
    const res = await fetchFn(base + path, { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    if (!res.ok) throw new Error('passkey: backend ' + res.status);
    return res.json();
  }
  async function register(env, base, user) {
    const o = await post(env.fetch, base, '/passkey/register/options', { user });
    const cred = await env.credentials.create({ publicKey: creationOptions(o, env.host) });
    return post(env.fetch, base, '/passkey/register/verify', registrationBody(o.challenge, cred));
  }
  async function login(env, base, user) {
    const o = await post(env.fetch, base, '/passkey/login/options', user ? { user } : {});
    const cred = await env.credentials.get({ publicKey: requestOptions(o, env.host) });
    return post(env.fetch, base, '/passkey/login/verify', loginBody(o.challenge, cred));
  }
  return { toBuf, fromBuf, creationOptions, requestOptions, registrationBody, loginBody, register, login, ALLOWED_ALGS };
});
