package cz.faltyn.elfall.backend.bankid

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.PlainHeader
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.response.respondText
import io.ktor.server.testing.testApplication
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun edKey() = OctetKeyPairGenerator(Curve.Ed25519).keyID(UUID.randomUUID().toString()).generate()

class ZeroTrustTest {
    private var t = 1_800_000_000L
    private val base = "https://api.elfall.test"
    private val zt = ZeroTrust(ZtConfig(base, signingKey = edKey()), now = { t })
    private val dev: ECKey = ECKeyGenerator(Curve.P_256).generate()

    private fun proof(k: ECKey = dev, htm: String = "GET", path: String = "/wallet", token: String? = null, iat: Long = t, jti: String = UUID.randomUUID().toString()): String {
        val c = JWTClaimsSet.Builder().claim("htm", htm).claim("htu", base + path).issueTime(Date(iat * 1000)).jwtID(jti)
        token?.let { c.claim("ath", ZeroTrust.b64(ZeroTrust.sha256(it.toByteArray()))) }
        val j = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType("dpop+jwt")).jwk(k.toPublicJWK()).build(), c.build())
        j.sign(ECDSASigner(k)); return j.serialize()
    }
    private fun tok() = zt.issue("ivo", "cred1", dev.toPublicJWK())
    private fun deny(f: () -> Unit) = assertFailsWith<ZtDenied> { f() }

    @Test fun validRequest() { val tk = tok(); assertEquals("ivo", zt.verify("GET", "/wallet", "DPoP $tk", proof(token = tk)).user) }
    @Test fun ttlMax5Min() { assertFailsWith<IllegalArgumentException> { ZtConfig(base, tokenTtlS = 301, signingKey = edKey()) } }
    @Test fun expiredToken() { val tk = tok(); t += 300; deny { zt.verify("GET", "/wallet", "DPoP $tk", proof(token = tk)) } }
    @Test fun bearerSchemeRejected() { val tk = tok(); deny { zt.verify("GET", "/wallet", "Bearer $tk", proof(token = tk)) } }
    @Test fun missingProof() { val tk = tok(); deny { zt.verify("GET", "/wallet", "DPoP $tk", null) } }
    @Test fun otherDeviceKeyRejected() { val tk = tok(); deny { zt.verify("GET", "/wallet", "DPoP $tk", proof(k = ECKeyGenerator(Curve.P_256).generate(), token = tk)) } }
    @Test fun wrongMethodOrUrl() { val tk = tok(); deny { zt.verify("POST", "/wallet", "DPoP $tk", proof(token = tk)) }; deny { zt.verify("GET", "/admin", "DPoP $tk", proof(token = tk)) } }
    @Test fun proofReplay() { val tk = tok(); val p = proof(token = tk); zt.verify("GET", "/wallet", "DPoP $tk", p); deny { zt.verify("GET", "/wallet", "DPoP $tk", p) }; assertEquals(1L, zt.metrics.replay.get()) }
    @Test fun staleProof() { val tk = tok(); deny { zt.verify("GET", "/wallet", "DPoP $tk", proof(token = tk, iat = t - 120)) } }
    @Test fun athMismatch() { val tk = tok(); deny { zt.verify("GET", "/wallet", "DPoP $tk", proof(token = tk + "x")) } }
    @Test fun tamperedToken() { val tk = tok(); val bad = tk.dropLast(2) + if (tk.endsWith("AA")) "BB" else "AA"; deny { zt.verify("GET", "/wallet", "DPoP $bad", proof(token = bad)) } }
    @Test fun foreignServerKey() { val other = ZeroTrust(ZtConfig(base, signingKey = edKey()), now = { t }); val tk = other.issue("ivo", "cred1", dev.toPublicJWK()); deny { zt.verify("GET", "/wallet", "DPoP $tk", proof(token = tk)) } }
    @Test fun revokedCredentialAndDevice() {
        val tk = tok(); zt.revokeCredential("cred1"); deny { zt.verify("GET", "/wallet", "DPoP $tk", proof(token = tk)) }; deny { tok() }
        val z2 = ZeroTrust(ZtConfig(base, signingKey = edKey()), now = { t }); val tk2 = z2.issue("ivo", "c2", dev.toPublicJWK())
        z2.revokeDevice(ZeroTrust.thumbprint(dev)); assertFailsWith<ZtDenied> { z2.verify("GET", "/wallet", "DPoP $tk2", proof(token = tk2)) }
    }
    private fun claims() = JWTClaimsSet.Builder().subject("ivo").claim("cred", "cred1").claim("cnf", mapOf("jkt" to ZeroTrust.thumbprint(dev)))
        .issueTime(Date(t * 1000)).expirationTime(Date((t + 60) * 1000)).jwtID(UUID.randomUUID().toString()).build()
    @Test fun tokenIsEdDSA() { val h = SignedJWT.parse(tok()).header; assertEquals(JWSAlgorithm.EdDSA, h.algorithm); assertEquals("at+jwt", h.type.type) }
    @Test fun hs256TokenRejected() {
        val j = SignedJWT(JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType("at+jwt")).build(), claims()); j.sign(MACSigner(ByteArray(32)))
        val tk = j.serialize(); deny { zt.verify("GET", "/wallet", "DPoP $tk", proof(token = tk)) }
    }
    @Test fun algNoneRejected() { val tk = PlainJWT(PlainHeader.Builder().type(JOSEObjectType("at+jwt")).build(), claims()).serialize(); deny { zt.verify("GET", "/wallet", "DPoP $tk", proof(token = tk)) } }
    @Test fun es256TokenRejected() {
        val j = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType("at+jwt")).build(), claims()); j.sign(ECDSASigner(dev))
        val tk = j.serialize(); deny { zt.verify("GET", "/wallet", "DPoP $tk", proof(token = tk)) }
    }
    @Test fun ttl5MinBoundary() { val tk = tok(); t += 299; zt.verify("GET", "/wallet", "DPoP $tk", proof(token = tk)); t += 1; deny { zt.verify("GET", "/wallet", "DPoP $tk", proof(token = tk)) } }
    @Test fun keyFromEnvOnly() {
        assertEquals(null, ZtConfig.fromEnv(base, emptyMap()))
        val k = edKey(); val c = ZtConfig.fromEnv(base, mapOf("ZT_SIGNING_JWK" to k.toJSONString()))!!
        assertEquals(k.keyID, c.signingKey.keyID); assertTrue(!c.toString().contains(k.d.toString()))
        assertFailsWith<IllegalArgumentException> { ZtConfig(base, signingKey = k.toPublicJWK()) }
    }
    // nimbus odmítne privátní JWK v hlavičce už při sestavení; ZeroTrust navíc kontroluje isPrivate
    @Test fun privateKeyInProofRejected() { assertFailsWith<IllegalArgumentException> { JWSHeader.Builder(JWSAlgorithm.ES256).jwk(dev).build() } }

    @Test fun pluginDenyByDefaultAndAudit() = testApplication {
        val pk = PasskeyService(PasskeyConfig("elfall.test", "ELFALL", "https://elfall.test"))
        application {
            zeroTrustModule(zt, pk)
            routing { get("/wallet") { call.respondText("secret") }; get("/healthz") { call.respondText("ok") } }
        }
        assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
        val r = client.get("/wallet"); assertEquals(HttpStatusCode.Unauthorized, r.status); assertTrue(r.bodyAsText().contains("unauthorized"))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/unknown-route").status)
        val tk = tok()
        val ok = client.get("/wallet") { header("Authorization", "DPoP $tk"); header("DPoP", proof(token = tk)) }
        assertEquals("secret", ok.bodyAsText())
        val who = client.get("/zt/whoami") { header("Authorization", "DPoP $tk"); header("DPoP", proof(path = "/zt/whoami", token = tk)) }
        assertTrue(who.bodyAsText().contains("ivo"))
        assertTrue(zt.audit.lines.any { it.contains("\"deny\"") } && zt.audit.lines.any { it.contains("\"allow\"") })
        assertTrue(AuditLog.verifyChain(zt.audit.lines))
        assertTrue(!AuditLog.verifyChain(zt.audit.lines.drop(1)))
    }
}
