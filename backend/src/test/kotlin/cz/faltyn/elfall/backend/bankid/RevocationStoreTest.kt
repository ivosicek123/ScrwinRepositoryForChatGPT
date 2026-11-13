package cz.faltyn.elfall.backend.bankid

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevocationStoreTest {
    private val dir = Files.createTempDirectory("ztrev").toFile()
    private val f = File(dir, "rev.log")
    private val key = OctetKeyPairGenerator(Curve.Ed25519).keyID("k").generate()
    private val base = "https://api.elfall.test"

    @Test fun survivesRestart() {
        val a = FileRevocationStore(f)
        a.add(RevocationKind.CREDENTIAL, "cred1", 1); a.add(RevocationKind.DEVICE, "jkt-1", 2)
        assertTrue(a.addJtiIfAbsent("j1", 3)); assertFalse(a.addJtiIfAbsent("j1", 3))
        val b = FileRevocationStore(f) // „restart“
        assertTrue(b.contains(RevocationKind.CREDENTIAL, "cred1")); assertTrue(b.contains(RevocationKind.DEVICE, "jkt-1"))
        assertFalse(b.addJtiIfAbsent("j1", 4), "replay po restartu musí selhat")
    }

    @Test fun zeroTrustRevocationPersistsAcrossInstances() {
        val dev = ECKeyGenerator(Curve.P_256).generate(); var t = 1_800_000_000L
        val z1 = ZeroTrust(ZtConfig(base, signingKey = key), now = { t }, store = FileRevocationStore(f))
        z1.revokeCredential("cred1"); z1.revokeDevice(ZeroTrust.thumbprint(dev))
        val z2 = ZeroTrust(ZtConfig(base, signingKey = key), now = { t }, store = FileRevocationStore(f))
        assertFailsWith<ZtDenied> { z2.issue("ivo", "cred1", dev.toPublicJWK()) }
        assertFailsWith<ZtDenied> { z2.issue("ivo", "cred2", dev.toPublicJWK()) }
    }

    @Test fun corruptLogFailsClosed() {
        f.writeText("v1\tCREDENTIAL\t1\tok\ngarbage\n")
        assertFailsWith<IllegalArgumentException> { FileRevocationStore(f) }
    }

    @Test fun rejectsInjectionIds() {
        val s = FileRevocationStore(f)
        assertFailsWith<IllegalArgumentException> { s.add(RevocationKind.CREDENTIAL, "a\nv1\tDEVICE\t1\tx", 1) }
        assertFalse(FileRevocationStore(f).contains(RevocationKind.DEVICE, "x"))
    }

    @Test fun purgeKeepsRevocationsAndCompactIsAtomic() {
        val s = FileRevocationStore(f)
        s.add(RevocationKind.CREDENTIAL, "c", 1); s.addJtiIfAbsent("old", 10); s.addJtiIfAbsent("new", 100)
        s.purgeJti(50); s.compact()
        val r = FileRevocationStore(f)
        assertTrue(r.contains(RevocationKind.CREDENTIAL, "c")); assertTrue(r.contains(RevocationKind.JTI, "new")); assertFalse(r.contains(RevocationKind.JTI, "old"))
        assertFalse(File(dir, "rev.log.tmp").exists())
    }

    @Test fun ztEnableDefaultOff() {
        val env = mapOf("ZT_PUBLIC_BASE_URL" to base, "ZT_SIGNING_JWK" to key.toJSONString(), "ZT_REVOCATION_FILE" to f.path)
        assertNull(ztFromEnv(env)); assertNull(ztFromEnv(env + ("ZT_ENABLE" to "false")))
        assertTrue(ztFromEnv(env + ("ZT_ENABLE" to "true")) != null)
        assertNull(ztFromEnv(mapOf("ZT_ENABLE" to "true", "ZT_PUBLIC_BASE_URL" to base)), "bez klíče fail-closed")
    }

    @Test fun metricsCountDenyReplayAndAuditChain() {
        val dev = ECKeyGenerator(Curve.P_256).generate(); val t = 1_800_000_000L
        val zt = ZeroTrust(ZtConfig(base, signingKey = key), now = { t })
        zt.metrics.denied("DPoP replay"); zt.metrics.denied("x\"}\n{evil")
        zt.issue("ivo", "c", dev.toPublicJWK())
        zt.audit.record("deny", "/a", "r", ts = t); zt.audit.record("deny", "/c", "r", ts = t); zt.audit.lines[0] = zt.audit.lines[0].replace("/a", "/b")
        assertFalse(zt.checkAuditChain())
        val m = zt.metrics.metricsText()
        assertTrue(m.contains("elfall_zt_tokens_issued_total 1")); assertTrue(m.contains("elfall_zt_audit_chain_failures_total 1"))
        assertTrue(m.contains("elfall_zt_deny_total{reason=\"DPoP replay\"} 1"))
        assertEquals(1, m.lines().count { it.startsWith("elfall_zt_deny_total{reason=\"x") && !it.contains("\n") })
        assertFalse(m.contains("{evil"))
    }
}
