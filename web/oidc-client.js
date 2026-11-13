// Bank iD – klientská část (prohlížeč). Klient NIKDY nedrží client_secret, PKCE verifier ani nonce;
// ty zůstávají na backendu (backend/bankid). Klient jen váže `state` na svou záložku (sessionStorage)
// a po návratu z Bank iD ověří, že `state` sedí (ochrana proti CSRF / login injection).
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api; else root.ElfallOidc = api;
})(typeof self !== 'undefined' ? self : this, function () {
  const KEY = 'elfall.bankid.state';
  const STATE_RE = /^[A-Za-z0-9_-]{32,128}$/;

  function constantTimeEqual(a, b) {
    if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
    let d = 0;
    for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
    return d === 0;
  }

  /** Povolit jen https backend (výjimka localhost pro vývoj). */
  function assertSafeBackend(url) {
    const u = new URL(url);
    if (u.protocol !== 'https:' && !(u.protocol === 'http:' && u.hostname === 'localhost')) throw new Error('Backend musí být HTTPS');
    return u.origin;
  }

  /** Ověří authorize URL od backendu: musí mířit na očekávaného issuera a nést PKCE S256 + state + nonce. */
  function assertAuthorizeUrl(authorizeUrl, expectedIssuer, state) {
    const u = new URL(authorizeUrl);
    const iss = new URL(expectedIssuer);
    if (u.protocol !== 'https:' || u.origin !== iss.origin) throw new Error('Neočekávaný poskytovatel identity');
    const q = u.searchParams;
    if (q.get('response_type') !== 'code') throw new Error('Jen Authorization Code flow');
    if (q.get('code_challenge_method') !== 'S256' || !q.get('code_challenge')) throw new Error('Chybí PKCE S256');
    if (!q.get('nonce')) throw new Error('Chybí nonce');
    if (!constantTimeEqual(q.get('state') || '', state)) throw new Error('state nesouhlasí');
    return u.toString();
  }

  function remember(storage, state) {
    if (!STATE_RE.test(state)) throw new Error('Neplatný state');
    storage.setItem(KEY, state);
  }

  /** Zpracuje návratovou URL. Vrací {code, state}; state je jednorázový (smaže se). */
  function parseCallback(storage, callbackUrl) {
    const q = new URL(callbackUrl).searchParams;
    const expected = storage.getItem(KEY);
    storage.removeItem(KEY);
    if (q.get('error')) throw new Error('Bank iD: ' + q.get('error'));
    const code = q.get('code'), state = q.get('state');
    if (!code || !state) throw new Error('Chybí code/state');
    if (!expected || !constantTimeEqual(state, expected)) throw new Error('state nesouhlasí');
    return { code, state };
  }

  return { KEY, constantTimeEqual, assertSafeBackend, assertAuthorizeUrl, remember, parseCallback };
});
