package cz.faltyn.elfall.shared.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OidcCallbackTests {
    private val iss = "https://oidc.sandbox.bankid.cz/"
    private val s = "a".repeat(43)
    private fun auth(extra: String = "response_type=code&code_challenge=x&code_challenge_method=S256&nonce=n&state=$s") = "${iss}auth?$extra"

    @Test fun validAuthorizeUrl() = OidcCallback.checkAuthorizeUrl(auth(), iss, s)
    @Test fun foreignIssuer() { assertFailsWith<IdentityVerificationException> { OidcCallback.checkAuthorizeUrl("https://evil.example/auth?response_type=code&code_challenge=x&code_challenge_method=S256&nonce=n&state=$s", iss, s) } }
    @Test fun plainPkce() { assertFailsWith<IdentityVerificationException> { OidcCallback.checkAuthorizeUrl(auth("response_type=code&code_challenge=x&code_challenge_method=plain&nonce=n&state=$s"), iss, s) } }
    @Test fun missingNonce() { assertFailsWith<IdentityVerificationException> { OidcCallback.checkAuthorizeUrl(auth("response_type=code&code_challenge=x&code_challenge_method=S256&state=$s"), iss, s) } }
    @Test fun callbackOk() = assertEquals(OidcCallback.Callback("c%", s), OidcCallback.parse("elfall://cb?code=c%25&state=$s", s))
    @Test fun callbackMismatch() { assertFailsWith<IdentityVerificationException> { OidcCallback.parse("elfall://cb?code=c&state=$s", "b".repeat(43)) } }
    @Test fun callbackNoExpected() { assertFailsWith<IdentityVerificationException> { OidcCallback.parse("elfall://cb?code=c&state=$s", null) } }
    @Test fun callbackError() { assertFailsWith<IdentityVerificationException> { OidcCallback.parse("elfall://cb?error=access_denied&state=$s", s) } }
    @Test fun stateFormat() { assertTrue(OidcCallback.isValidState(s)); assertTrue(!OidcCallback.isValidState("x")) }
}
