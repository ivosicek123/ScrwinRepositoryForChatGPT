package cz.faltyn.elfall.shared.identity

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BankIdVerifierTests {
    private val hash = "ab".repeat(32)
    private val jsonHdr = headersOf(HttpHeaders.ContentType, "application/json")

    private fun verifier(enabled: Boolean = true, status: HttpStatusCode = HttpStatusCode.OK, seen: MutableList<String> = mutableListOf()) =
        BankIdVerifier(HttpClient(MockEngine { req ->
            seen += req.url.encodedPath + " " + (req.body as TextContent).text
            when (req.url.encodedPath) {
                "/auth/start" -> respond("""{"authorize_url":"https://oidc.sandbox.bankid.cz/auth?x=1","state":"s1"}""", status, jsonHdr)
                else -> respond("""{"purpose":"txn","sub":"u","confirmed":true,"tx_hash":"$hash"}""", status, jsonHdr)
            }
        }), "https://backend.test/", enabled)

    @Test fun flagOffByDefault() = assertFalse(IdentityFlags.bankIdEnabled)

    @Test fun disabledVerifierThrows() = runTest {
        assertFailsWith<IllegalStateException> { verifier(enabled = false).start(VerificationPurpose.KYC) }
    }

    @Test fun startSendsPurpose() = runTest {
        val seen = mutableListOf<String>()
        val s = verifier(seen = seen).start(VerificationPurpose.KYC)
        assertEquals("s1", s.state)
        assertTrue(seen.single().startsWith("/auth/start ") && seen.single().contains("\"purpose\":\"kyc\""))
    }

    @Test fun txnRequiresHash() = runTest {
        assertFailsWith<IllegalArgumentException> { verifier().start(VerificationPurpose.TRANSACTION, txHash = "zz") }
    }

    @Test fun completeAndGate() = runTest {
        val v = verifier()
        v.start(VerificationPurpose.TRANSACTION, txHash = hash)
        val r = v.complete("code", "s1")
        assertTrue(WithdrawalGate.isConfirmed(r, hash))
        assertFalse(WithdrawalGate.isConfirmed(r, "cd".repeat(32)))
    }

    @Test fun backendErrorMapped() = runTest {
        assertFailsWith<IdentityVerificationException> { verifier(status = HttpStatusCode.Unauthorized).complete("c", "s") }
    }
}
