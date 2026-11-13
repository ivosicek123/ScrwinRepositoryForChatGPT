package cz.faltyn.elfall.shared.identity

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BankIdLoginFlowTests {
    private val s = "s".repeat(43)
    private val good = "https://oidc.sandbox.bankid.cz/auth?response_type=code&code_challenge=x&code_challenge_method=S256&nonce=n&state=$s"

    private class FakeVerifier(val url: String, val state: String) : IdentityVerifier {
        override val enabled = true
        var completed: Pair<String, String>? = null
        override suspend fun start(purpose: VerificationPurpose, txHash: String?, payoutIban: String?, payoutHolder: String?) = AuthStart(url, state)
        override suspend fun complete(code: String, state: String): VerificationResult { completed = code to state; return VerificationResult("kyc", level = "bank_aml_verified") }
    }
    private class FakeSession(val reply: String?) : WebAuthSession {
        var opened: String? = null
        override fun authenticate(authorizeUrl: String, callbackScheme: String, completion: (String?, String?) -> Unit) { opened = authorizeUrl; completion(reply, "cancel") }
    }

    @Test fun happyPath() = runTest {
        val v = FakeVerifier(good, s); val ses = FakeSession("elfall://cb?code=abc&state=$s")
        val r = BankIdLoginFlow(v, ses).run(VerificationPurpose.KYC)
        assertTrue(r.isKycVerified); assertEquals("abc" to s, v.completed); assertEquals(good, ses.opened)
    }
    @Test fun maliciousAuthorizeUrlNeverOpened() = runTest {
        val ses = FakeSession("elfall://cb?code=abc&state=$s")
        assertFailsWith<IdentityVerificationException> { BankIdLoginFlow(FakeVerifier(good.replace("oidc.sandbox.bankid.cz", "evil.example"), s), ses).run(VerificationPurpose.KYC) }
        assertEquals(null, ses.opened)
    }
    @Test fun stateMismatch() = runTest {
        assertFailsWith<IdentityVerificationException> { BankIdLoginFlow(FakeVerifier(good, s), FakeSession("elfall://cb?code=abc&state=${"t".repeat(43)}")).run(VerificationPurpose.KYC) }
    }
    @Test fun foreignScheme() = runTest {
        assertFailsWith<IdentityVerificationException> { BankIdLoginFlow(FakeVerifier(good, s), FakeSession("https://evil/cb?code=abc&state=$s")).run(VerificationPurpose.KYC) }
    }
    @Test fun cancelled() = runTest {
        assertFailsWith<IdentityVerificationException> { BankIdLoginFlow(FakeVerifier(good, s), FakeSession(null)).run(VerificationPurpose.KYC) }
    }
    @Test fun registry() { PlatformIdentity.webAuthSession = null; assertEquals(null, PlatformIdentity.webAuthSession); assertTrue(PlatformIdentity.platform.isNotEmpty()) }
}
