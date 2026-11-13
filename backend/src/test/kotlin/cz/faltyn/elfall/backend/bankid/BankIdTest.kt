package cz.faltyn.elfall.backend.bankid

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Port 21 Python testů (backend/bankid/tests) + navíc RS256/ES256. Mockovaný OIDC provider, fiktivní data. */
class BankIdTest {
    companion object {
        const val ISS = "https://oidc.sandbox.bankid.cz/"
        val CFG = BankIdConfig("test-client", "test-secret", "https://app.test/cb")
        val KEY: RSAKey = RSAKeyGenerator(2048).keyID("k1").keyUse(KeyUse.SIGNATURE).generate()
        val OTHER: RSAKey = RSAKeyGenerator(2048).keyID("k1").generate()
        val EC: ECKey = ECKeyGenerator(Curve.P_256).keyID("e1").keyUse(KeyUse.SIGNATURE).generate()
        const val TEST_IBAN = "CZ00 0000 0000 0000 0000 0000"
        val PROFILE = """{"sub":"user-1","given_name":"Jan","family_name":"Novák","majority":true,
            "paymentAccounts":["CZ0000000000000000000000"],
            "verified_claims":{"verification":{"trust_framework":"cz_aml","verification_process":"x"}}}"""
        val DISCO = """{"issuer":"$ISS","authorization_endpoint":"${ISS}auth","token_endpoint":"${ISS}token",
            "jwks_uri":"${ISS}.well-known/jwks","profile_endpoint":"${ISS}profile"}"""
        fun now() = System.currentTimeMillis() / 1000
    }

    fun idToken(nonce: String, key: RSAKey = KEY, aud: List<String> = listOf("test-client"), iss: String = ISS, expIn: Long = 300,
                authTime: Long? = null, acr: String = "loa3", sub: String = "user-1", iat: Long = now(), azp: String? = null,
                alg: JWSAlgorithm = JWSAlgorithm.PS512): String {
        val c = JWTClaimsSet.Builder().issuer(iss).audience(aud).subject(sub).issueTime(Date(iat * 1000))
            .expirationTime(Date((now() + expIn) * 1000)).claim("nonce", nonce).claim("acr", acr).claim("auth_time", authTime ?: now())
        azp?.let { c.claim("azp", it) }
        return SignedJWT(JWSHeader.Builder(alg).keyID("k1").type(JOSEObjectType.JWT).build(), c.build()).apply { sign(RSASSASigner(key)) }.serialize()
    }

    /** Mock OP: tokenFn dostane formulář a vrátí id_token. */
    inner class Op(var tokenFn: (Map<String, List<String>>) -> String = { "" }, val keys: JWKSet = JWKSet(KEY.toPublicJWK())) {
        var form: Map<String, List<String>> = emptyMap()
        val engine = MockEngine { req ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when (req.url.toString()) {
                ISS + ".well-known/openid-configuration" -> respond(DISCO, HttpStatusCode.OK, json)
                ISS + ".well-known/jwks" -> respond(keys.toString(true), HttpStatusCode.OK, json)
                ISS + "profile" -> respond(PROFILE, HttpStatusCode.OK, json)
                ISS + "token" -> {
                    val body = String((req.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes())
                    form = parseQueryString(body).entries().associate { it.key to it.value }
                    respond("""{"access_token":"at","token_type":"Bearer","id_token":"${tokenFn(form)}"}""", HttpStatusCode.OK, json)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = BankIdClient(CFG, HttpClient(engine))
    }

    private fun api(op: Op, block: suspend Api.() -> Unit) = testApplication {
        application { bankIdModule(CFG, op.client) }
        Api(client).block()
    }

    class Api(val c: HttpClient) {
        suspend fun post(path: String, body: String) = c.post(path) { contentType(ContentType.Application.Json); setBody(body) }
        suspend fun start(body: String): Pair<String, Map<String, String>> {
            val r = post("/auth/start", body); assertEquals(200, r.status.value, r.bodyAsText())
            val j = Json.parseToJsonElement(r.bodyAsText()).jsonObject
            val q = Url(j["authorize_url"]!!.jsonPrimitive.content).parameters
            return j["state"]!!.jsonPrimitive.content to q.names().associateWith { q[it]!! }
        }
        suspend fun cb(state: String) = post("/auth/callback", """{"code":"c","state":"$state"}""")
    }
    private suspend fun io.ktor.client.statement.HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    // ---- port test_bankid.py ----
    @Test fun authorizeUrlParams() = api(Op()) {
        val (_, q) = start("""{"purpose":"kyc"}""")
        assertEquals("openid profile.name profile.birthdate profile.verification", q["scope"])
        assertEquals("S256", q["code_challenge_method"]); assertEquals(43, q["code_challenge"]!!.length)
        assertEquals("loa3", q["acr_values"]); assertEquals("code", q["response_type"])
        assertTrue("nonce" in q && "state" in q)
    }

    @Test fun kycFlow() { val op = Op(); api(op) {
        val (state, q) = start("""{"purpose":"kyc"}"""); op.tokenFn = { idToken(q["nonce"]!!) }
        val r = cb(state); assertEquals(200, r.status.value, r.bodyAsText())
        val j = r.obj(); assertEquals("bank_aml_verified", j["level"]!!.jsonPrimitive.content)
        assertTrue("code_verifier" in op.form); assertEquals(listOf("test-secret"), op.form["client_secret"])
        assertFalse("birthdate" in j)
    } }

    @Test fun stateSingleUse() { val op = Op(); api(op) {
        val (state, q) = start("""{"purpose":"kyc"}"""); op.tokenFn = { idToken(q["nonce"]!!) }
        assertEquals(200, cb(state).status.value); assertEquals(400, cb(state).status.value)
    } }

    private fun rejected(nonceOk: Boolean = true, tok: (String) -> String) { val op = Op(); api(op) {
        val (state, q) = start("""{"purpose":"kyc"}"""); op.tokenFn = { tok(if (nonceOk) q["nonce"]!! else "wrong") }
        assertEquals(401, cb(state).status.value)
    } }
    @Test fun rejectOtherKey() = rejected { idToken(it, key = OTHER) }
    @Test fun rejectAud() = rejected { idToken(it, aud = listOf("evil")) }
    @Test fun rejectIss() = rejected { idToken(it, iss = "https://evil/") }
    @Test fun rejectExpired() = rejected { idToken(it, expIn = -120) }
    @Test fun rejectNonce() = rejected(nonceOk = false) { idToken(it) }

    @Test fun accountOwnership() { val op = Op(); api(op) {
        val (state, q) = start("""{"purpose":"account","payout_iban":"$TEST_IBAN","payout_holder":"JAN NOVAK"}""")
        assertTrue("profile.paymentAccounts" in q["scope"]!!); op.tokenFn = { idToken(q["nonce"]!!) }
        val j = cb(state).obj()
        assertTrue(j["confirmed"]!!.jsonPrimitive.boolean && j["iban_match"]!!.jsonPrimitive.boolean && j["name_match"]!!.jsonPrimitive.boolean)
        assertFalse("paymentAccounts" in j)
    } }

    @Test fun accountMismatch() { val op = Op(); api(op) {
        val (state, q) = start("""{"purpose":"account","payout_iban":"CZ9900000000000000000001","payout_holder":"Petr X"}""")
        op.tokenFn = { idToken(q["nonce"]!!) }
        val j = cb(state).obj()
        assertFalse(j["confirmed"]!!.jsonPrimitive.boolean); assertFalse(j["name_match"]!!.jsonPrimitive.boolean)
    } }

    @Test fun txnStepUp() { val op = Op(); api(op) {
        val h = "ab".repeat(32)
        val (state, q) = start("""{"purpose":"txn","tx_hash":"$h"}""")
        assertEquals("login", q["prompt"]); assertEquals("0", q["max_age"]); assertEquals("openid", q["scope"])
        op.tokenFn = { idToken(q["nonce"]!!) }
        val j = cb(state).obj(); assertTrue(j["confirmed"]!!.jsonPrimitive.boolean); assertEquals(h, j["tx_hash"]!!.jsonPrimitive.content)
    } }

    @Test fun txnStaleAuthRejected() { val op = Op(); api(op) {
        val (state, q) = start("""{"purpose":"txn","tx_hash":"${"cd".repeat(32)}"}""")
        op.tokenFn = { idToken(q["nonce"]!!, authTime = now() - 3600) }
        assertEquals(401, cb(state).status.value)
    } }

    @Test fun txnRequiresHash() = api(Op()) { assertEquals(400, post("/auth/start", """{"purpose":"txn","tx_hash":"nothex"}""").status.value) }

    @Test fun configEnv() {
        val base = mapOf("BANKID_CLIENT_ID" to "x", "BANKID_CLIENT_SECRET" to "x", "BANKID_REDIRECT_URI" to "https://app.test/cb")
        assertEquals(ISS, BankIdConfig.fromEnv(base + ("BANKID_ENV" to "sandbox")).issuer)
        assertFailsWith<IllegalStateException> { BankIdConfig.fromEnv(base + ("BANKID_ENV" to "production")) }
    }

    // ---- port test_oidc_hardening.py ----
    private fun verify(tok: String, nonce: String = "n") = runBlocking { Op().client.verifyIdToken(tok, nonce) }

    @Test fun validTokenPasses() = assertEquals("user-1", verify(idToken("n"))["sub"])
    @Test fun algNoneRejected() {
        val c = JWTClaimsSet.Builder().issuer(ISS).audience("test-client").subject("u").issueTime(Date()).expirationTime(Date(System.currentTimeMillis() + 60000)).claim("nonce", "n").build()
        assertFailsWith<OidcError> { verify(PlainJWT(c).serialize()) }
    }
    @Test fun hs256ConfusionRejected() {
        val c = JWTClaimsSet.Builder().issuer(ISS).audience("test-client").subject("u").issueTime(Date()).expirationTime(Date(System.currentTimeMillis() + 60000)).claim("nonce", "n").build()
        val t = SignedJWT(JWSHeader.Builder(JWSAlgorithm.HS256).keyID("k1").build(), c).apply { sign(MACSigner(ByteArray(32) { 'x'.code.toByte() })) }.serialize()
        assertTrue(assertFailsWith<OidcError> { verify(t) }.message!!.contains("algoritmus"))
    }
    @Test fun azpMismatch() { assertTrue(assertFailsWith<OidcError> { verify(idToken("n", aud = listOf("test-client", "other"), azp = "other")) }.message!!.contains("azp")) }
    @Test fun iatFuture() { assertFailsWith<OidcError> { verify(idToken("n", iat = now() + 3600)) } }
    @Test fun emptyNonceRejected() { assertTrue(assertFailsWith<OidcError> { verify(idToken(""), "") }.message!!.contains("nonce")) }
    @Test fun redirectMustBeHttps() {
        val e = assertFailsWith<IllegalStateException> { BankIdConfig.fromEnv(mapOf("BANKID_CLIENT_ID" to "x", "BANKID_CLIENT_SECRET" to "x", "BANKID_REDIRECT_URI" to "http://evil.example/cb")) }
        assertTrue(e.message!!.contains("https"))
    }

    // ---- nové: silnější algoritmy ----
    @Test fun rs256Rejected() { assertTrue(assertFailsWith<OidcError> { verify(idToken("n", alg = JWSAlgorithm.RS256)) }.message!!.contains("algoritmus")) }
    @Test fun ps256Accepted() = assertEquals("user-1", verify(idToken("n", alg = JWSAlgorithm.PS256))["sub"])
    @Test fun es256Accepted() {
        val c = JWTClaimsSet.Builder().issuer(ISS).audience("test-client").subject("u").issueTime(Date()).expirationTime(Date(System.currentTimeMillis() + 60000)).claim("nonce", "n").build()
        val t = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID("e1").build(), c).apply { sign(ECDSASigner(EC)) }.serialize()
        val op = Op(keys = JWKSet(listOf(KEY.toPublicJWK(), EC.toPublicJWK())))
        assertEquals("u", runBlocking { op.client.verifyIdToken(t, "n") }["sub"])
    }

    // ---- security-review ----
    @Test fun pendingStoreIsBounded() {
        val st = PendingStore(maxItems = 2)
        st.put("a", Pending("kyc", "v", "n", now())); st.put("b", Pending("kyc", "v", "n", now()))
        assertFailsWith<OidcError> { st.put("c", Pending("kyc", "v", "n", now())) }
        st.pop("a"); st.put("c", Pending("kyc", "v", "n", now()))
    }
    @Test fun expiredStateRejected() {
        var t = 1000L
        val st = PendingStore(ttlS = 10, now = { t })
        st.put("a", Pending("kyc", "v", "n", t)); t += 11
        assertEquals(null, st.pop("a"))
    }
}
