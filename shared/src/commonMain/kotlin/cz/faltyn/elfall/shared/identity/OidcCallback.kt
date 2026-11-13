package cz.faltyn.elfall.shared.identity

/**
 * Klientská (mobilní) část Bank iD OIDC: vazba `state` + ověření návratové URL a authorize URL.
 * PKCE verifier, nonce, výměna code a ověření podpisu id_tokenu (JWKS, iss/aud/exp/nonce) probíhají
 * výhradně na backendu (backend/bankid/oidc.py) – klient nikdy nedrží client_secret.
 */
object OidcCallback {
    private val STATE_RE = Regex("^[A-Za-z0-9_-]{32,128}$")

    data class Callback(val code: String, val state: String)

    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var d = 0
        for (i in a.indices) d = d or (a[i].code xor b[i].code)
        return d == 0
    }

    fun isValidState(state: String) = STATE_RE.matches(state)

    /** Ověří authorize URL vrácenou backendem: https, origin issuera, code flow, PKCE S256, nonce, state. */
    fun checkAuthorizeUrl(authorizeUrl: String, expectedIssuer: String, state: String) {
        val origin = origin(expectedIssuer)
        if (!authorizeUrl.startsWith("https://") || origin(authorizeUrl) != origin)
            throw IdentityVerificationException("Neočekávaný poskytovatel identity")
        val q = query(authorizeUrl)
        if (q["response_type"] != "code") throw IdentityVerificationException("Jen Authorization Code flow")
        if (q["code_challenge_method"] != "S256" || q["code_challenge"].isNullOrEmpty()) throw IdentityVerificationException("Chybí PKCE S256")
        if (q["nonce"].isNullOrEmpty()) throw IdentityVerificationException("Chybí nonce")
        if (!constantTimeEquals(q["state"] ?: "", state)) throw IdentityVerificationException("state nesouhlasí")
    }

    /** Zpracuje redirect (deep link) z Bank iD; `expectedState` je jednorázový – volající ho po volání zahodí. */
    fun parse(callbackUrl: String, expectedState: String?): Callback {
        val q = query(callbackUrl)
        q["error"]?.let { throw IdentityVerificationException("Bank iD: $it") }
        val code = q["code"]; val state = q["state"]
        if (code.isNullOrEmpty() || state.isNullOrEmpty()) throw IdentityVerificationException("Chybí code/state")
        if (expectedState == null || !constantTimeEquals(state, expectedState)) throw IdentityVerificationException("state nesouhlasí")
        return Callback(code, state)
    }

    private fun origin(url: String): String {
        val rest = url.substringAfter("://", "")
        return url.substringBefore("://").lowercase() + "://" + rest.substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
    }

    internal fun query(url: String): Map<String, String> =
        url.substringAfter('?', "").substringBefore('#').split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('='); val v = it.substringAfter('=', "")
            decode(k) to decode(v)
        }

    private fun decode(s: String): String {
        val out = StringBuilder(); var i = 0
        val bytes = ArrayList<Byte>()
        fun flush() { if (bytes.isNotEmpty()) { out.append(bytes.toByteArray().decodeToString()); bytes.clear() } }
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) { bytes.add(s.substring(i + 1, i + 3).toInt(16).toByte()); i += 3; continue }
            flush(); out.append(if (c == '+') ' ' else c); i++
        }
        flush(); return out.toString()
    }
}
