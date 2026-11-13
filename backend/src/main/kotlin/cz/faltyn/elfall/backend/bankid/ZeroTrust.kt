package cz.faltyn.elfall.backend.bankid

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.Ed25519Signer
import com.nimbusds.jose.crypto.Ed25519Verifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.OctetKeyPair
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Zero Trust: krátkodobé (≤ 5 min) sender-constrained přístupové tokeny ve stylu DPoP (RFC 9449).
 * Token je vázán na passkey credential (claim `cred`) a na klíč zařízení (claim `cnf.jkt` = RFC 7638
 * otisk P-256 klíče, typicky ze Secure Enclave / Android Keystore). Každý požadavek nese nový
 * DPoP důkaz podepsaný klíčem zařízení (htm, htu, iat, jti, ath). Vše mimo [PUBLIC_ROUTES] je
 * zamítnuto (deny-by-default). Model politiky: proofs/rocq/KeyPolicy.v.
 */
data class ZtConfig(
    val publicBaseUrl: String,
    val tokenTtlS: Long = 300,
    val proofMaxAgeS: Long = 60,
    /** Ed25519 podpisový klíč serveru (OKP JWK s `d`). Nikdy v repozitáři – jen z env/secret store, viz [fromEnv]. */
    val signingKey: OctetKeyPair,
) {
    init {
        require(signingKey.curve == Curve.Ed25519 && signingKey.isPrivate) { "signingKey musí být privátní Ed25519 OKP" }
        require(tokenTtlS in 1..300) { "TTL tokenu max 5 minut" }
        require(publicBaseUrl.startsWith("https://") || publicBaseUrl.startsWith("http://localhost")) { "publicBaseUrl musí být https" }
    }
    override fun toString() = "ZtConfig(publicBaseUrl=$publicBaseUrl, tokenTtlS=$tokenTtlS, kid=${signingKey.keyID})" // nikdy nevypisuje klíč

    companion object {
        /**
         * Klíč z prostředí: `ZT_SIGNING_JWK_FILE` (cesta k souboru ze secret store, preferováno) nebo `ZT_SIGNING_JWK`
         * (JSON OKP JWK). Bez klíče vrací null => Zero Trust se nespustí (fail-closed, žádný náhodný ani vestavěný klíč).
         */
        fun fromEnv(publicBaseUrl: String, e: Map<String, String> = System.getenv()): ZtConfig? {
            val json = e["ZT_SIGNING_JWK_FILE"]?.let { java.io.File(it).readText() } ?: e["ZT_SIGNING_JWK"] ?: return null
            return ZtConfig(publicBaseUrl, signingKey = OctetKeyPair.parse(json))
        }
    }
}

val PUBLIC_ROUTES = setOf(
    "/healthz", "/auth/start", "/auth/callback",
    "/passkey/register/options", "/passkey/register/verify", "/passkey/login/options", "/passkey/login/verify",
    "/zt/token",
)

class ZtDenied(val reason: String) : Exception(reason)
data class ZtPrincipal(val user: String, val credentialId: String, val deviceJkt: String)

/** Auditní log: JSONL, každý řádek nese SHA-256 předchozího (detekce úprav/mazání). */
class AuditLog(private val sink: (String) -> Unit = { System.err.println(it) }) {
    private var prev = "0".repeat(64)
    val lines = mutableListOf<String>()
    @Synchronized fun record(decision: String, path: String, reason: String, user: String? = null, ts: Long) {
        val line = buildJsonObject {
            put("ts", ts); put("decision", decision); put("path", path); put("reason", reason)
            user?.let { put("user", it) }; put("prev", prev)
        }.toString()
        prev = sha256Hex(line); lines += line; sink(line)
    }
    companion object {
        fun sha256Hex(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        fun verifyChain(lines: List<String>): Boolean {
            var prev = "0".repeat(64)
            for (l in lines) { if (!l.contains("\"prev\":\"$prev\"")) return false; prev = sha256Hex(l) }
            return true
        }
    }
}

/** Čítače pro Prometheus (exportuje [metricsText]); jen počty a důvody, nikdy tokeny/klíče/uživatelé. */
class ZtMetrics {
    val allow = AtomicLong(); val issued = AtomicLong(); val replay = AtomicLong(); val auditChainFailures = AtomicLong()
    val deny = ConcurrentHashMap<String, AtomicLong>()
    fun denied(reason: String) { deny.computeIfAbsent(reason.replace(Regex("[^\\p{L}\\p{N} /_.-]"), "_")) { AtomicLong() }.incrementAndGet() }
    fun metricsText(): String = buildString {
        append("# TYPE elfall_zt_allow_total counter\nelfall_zt_allow_total ${allow.get()}\n")
        append("# TYPE elfall_zt_tokens_issued_total counter\nelfall_zt_tokens_issued_total ${issued.get()}\n")
        append("# TYPE elfall_zt_dpop_replay_total counter\nelfall_zt_dpop_replay_total ${replay.get()}\n")
        append("# TYPE elfall_zt_audit_chain_failures_total counter\nelfall_zt_audit_chain_failures_total ${auditChainFailures.get()}\n")
        append("# TYPE elfall_zt_deny_total counter\n")
        deny.toSortedMap().forEach { (r, v) -> append("elfall_zt_deny_total{reason=\"${r.replace("\"", "")}\"} ${v.get()}\n") }
    }
}

class ZeroTrust(
    val cfg: ZtConfig,
    val audit: AuditLog = AuditLog(),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val store: RevocationStore = InMemoryRevocationStore(),
    val metrics: ZtMetrics = ZtMetrics(),
) {
    private val verifier = Ed25519Verifier(cfg.signingKey.toPublicJWK())

    fun revokeCredential(id: String) { store.add(RevocationKind.CREDENTIAL, id, now()); audit.record("revoke", "-", "credential", ts = now()) }
    fun revokeDevice(jkt: String) { store.add(RevocationKind.DEVICE, jkt, now()); audit.record("revoke", "-", "device", ts = now()) }

    /** Vydá token po úspěšném ověření passkey + důkazu držení klíče zařízení. */
    fun issue(user: String, credentialId: String, deviceKey: ECKey): String {
        val jkt = thumbprint(deviceKey)
        if (store.contains(RevocationKind.CREDENTIAL, credentialId) || store.contains(RevocationKind.DEVICE, jkt)) throw ZtDenied("odvolaný klíč")
        val t = now()
        val claims = JWTClaimsSet.Builder().subject(user).claim("cred", credentialId)
            .claim("cnf", mapOf("jkt" to jkt)).issueTime(Date(t * 1000)).expirationTime(Date((t + cfg.tokenTtlS) * 1000))
            .jwtID(UUID.randomUUID().toString()).build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.EdDSA).type(JOSEObjectType("at+jwt")).keyID(cfg.signingKey.keyID).build(), claims)
        jwt.sign(Ed25519Signer(cfg.signingKey)); metrics.issued.incrementAndGet(); return jwt.serialize()
    }

    /** Ověří DPoP důkaz (podpis klíčem zařízení, htm/htu/iat/jti[, ath]) a vrátí veřejný klíč. */
    fun verifyProof(proof: String?, method: String, path: String, accessToken: String?): ECKey {
        if (proof.isNullOrBlank()) throw ZtDenied("chybí DPoP")
        val p = runCatching { SignedJWT.parse(proof) }.getOrNull() ?: throw ZtDenied("neplatný DPoP")
        val h = p.header
        if (h.type?.type != "dpop+jwt" || h.algorithm != JWSAlgorithm.ES256) throw ZtDenied("DPoP typ/alg")
        val jwk = h.jwk as? ECKey ?: throw ZtDenied("DPoP bez EC klíče")
        if (jwk.isPrivate || jwk.curve != Curve.P_256) throw ZtDenied("DPoP klíč")
        if (!runCatching { p.verify(ECDSAVerifier(jwk)) }.getOrDefault(false)) throw ZtDenied("DPoP podpis")
        val c = p.jwtClaimsSet
        if (c.getStringClaim("htm") != method || c.getStringClaim("htu") != cfg.publicBaseUrl.trimEnd('/') + path) throw ZtDenied("DPoP htm/htu")
        val iat = c.issueTime?.time?.div(1000) ?: throw ZtDenied("DPoP iat")
        if (kotlin.math.abs(now() - iat) > cfg.proofMaxAgeS) throw ZtDenied("DPoP prošlý")
        if (accessToken != null && c.getStringClaim("ath") != b64(sha256(accessToken.toByteArray()))) throw ZtDenied("DPoP ath")
        val jti = c.jwtid ?: throw ZtDenied("DPoP jti")
        gc(); if (!store.addJtiIfAbsent(jti, iat)) { metrics.replay.incrementAndGet(); throw ZtDenied("DPoP replay") }
        return jwk
    }

    fun verify(method: String, path: String, authorization: String?, proof: String?): ZtPrincipal {
        val token = authorization?.takeIf { it.startsWith("DPoP ") }?.removePrefix("DPoP ")?.trim() ?: throw ZtDenied("chybí DPoP token")
        val at = runCatching { SignedJWT.parse(token) }.getOrNull() ?: throw ZtDenied("neplatný token")
        // jen EdDSA (Ed25519); HS*, none, RS*, ES* atd. zamítnuto ještě před ověřením podpisu
        if (at.header.algorithm != JWSAlgorithm.EdDSA || at.header.type?.type != "at+jwt") throw ZtDenied("alg/typ tokenu")
        if (!runCatching { at.verify(verifier) }.getOrDefault(false)) throw ZtDenied("podpis tokenu")
        val c = at.jwtClaimsSet
        val exp = c.expirationTime?.time?.div(1000) ?: throw ZtDenied("exp")
        val iat = c.issueTime?.time?.div(1000) ?: throw ZtDenied("iat")
        if (now() >= exp || exp - iat > cfg.tokenTtlS) throw ZtDenied("token vypršel")
        val cred = c.getStringClaim("cred") ?: throw ZtDenied("cred")
        @Suppress("UNCHECKED_CAST") val jkt = (c.getJSONObjectClaim("cnf")?.get("jkt") as? String) ?: throw ZtDenied("cnf")
        if (store.contains(RevocationKind.CREDENTIAL, cred)) throw ZtDenied("odvolaný passkey")
        if (store.contains(RevocationKind.DEVICE, jkt)) throw ZtDenied("odvolané zařízení")
        val key = verifyProof(proof, method, path, token)
        if (thumbprint(key) != jkt) throw ZtDenied("klíč zařízení nesouhlasí")
        return ZtPrincipal(c.subject, cred, jkt)
    }

    private fun gc() { val lim = now() - 2 * cfg.proofMaxAgeS; store.purgeJti(lim) }

    /** Ověří hash řetěz auditního logu; selhání započítá do metriky (alert ZtAuditChainBroken). */
    fun checkAuditChain(): Boolean = AuditLog.verifyChain(audit.lines).also { if (!it) metrics.auditChainFailures.incrementAndGet() }

    companion object {
        fun thumbprint(k: ECKey): String = k.toPublicJWK().computeThumbprint().toString()
        fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
        fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
}

val ZtPrincipalKey = AttributeKey<ZtPrincipal>("ztPrincipal")
class ZtPluginConfig { var zt: ZeroTrust? = null }

/** Deny-by-default: každý požadavek mimo PUBLIC_ROUTES musí projít [ZeroTrust.verify]. */
val ZeroTrustPlugin = createApplicationPlugin("ZeroTrust", ::ZtPluginConfig) {
    val zt = requireNotNull(pluginConfig.zt) { "ZeroTrust není nakonfigurován" }
    onCall { call ->
        val path = call.request.path()
        if (path in PUBLIC_ROUTES) return@onCall
        try {
            val p = zt.verify(call.request.httpMethod.value, path, call.request.headers["Authorization"], call.request.headers["DPoP"])
            call.attributes.put(ZtPrincipalKey, p)
            zt.metrics.allow.incrementAndGet()
            zt.audit.record("allow", path, "ok", p.user, ts = System.currentTimeMillis() / 1000)
        } catch (e: ZtDenied) {
            zt.metrics.denied(e.reason)
            zt.audit.record("deny", path, e.reason, ts = System.currentTimeMillis() / 1000)
            call.response.headers.append("WWW-Authenticate", "DPoP algs=\"ES256\"")
            call.respondText("{\"error\":\"unauthorized\"}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.Unauthorized)
        }
    }
}

@Serializable data class ZtTokenRequest(val challenge: String, val credentialId: String, val authenticatorData: String,
    val clientDataJSON: String, val signature: String, val userHandle: String? = null)

private val ZJ = Json { ignoreUnknownKeys = true }

fun Application.zeroTrustModule(zt: ZeroTrust, passkeys: PasskeyService) {
    install(ZeroTrustPlugin) { this.zt = zt }
    routing {
        // passkey aserce + DPoP důkaz (bez tokenu) => token vázaný na credential a klíč zařízení
        post("/zt/token") {
            val r = runCatching { ZJ.decodeFromString(ZtTokenRequest.serializer(), call.receiveText()) }.getOrNull()
            val res = runCatching {
                val r2 = r ?: throw ZtDenied("neplatný požadavek")
                val key = zt.verifyProof(call.request.headers["DPoP"], "POST", "/zt/token", null)
                val user = runCatching { passkeys.authenticate(r2.challenge, r2.credentialId, r2.authenticatorData, r2.clientDataJSON, r2.signature, r2.userHandle) }
                    .getOrElse { throw ZtDenied("passkey") }
                user to zt.issue(user, r2.credentialId, key)
            }
            res.fold({ (user, tok) ->
                zt.audit.record("issue", "/zt/token", "ok", user, ts = System.currentTimeMillis() / 1000)
                call.respondText(buildJsonObject { put("access_token", tok); put("token_type", "DPoP"); put("expires_in", zt.cfg.tokenTtlS) }.toString(), io.ktor.http.ContentType.Application.Json)
            }, { e ->
                zt.metrics.denied((e as? ZtDenied)?.reason ?: "chyba")
                zt.audit.record("deny", "/zt/token", (e as? ZtDenied)?.reason ?: "chyba", ts = System.currentTimeMillis() / 1000)
                call.respondText("{\"error\":\"unauthorized\"}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.Unauthorized)
            })
        }
        get("/zt/whoami") {
            val p = call.attributes[ZtPrincipalKey]
            call.respondText(buildJsonObject { put("user", p.user) }.toString(), io.ktor.http.ContentType.Application.Json)
        }
    }
}
