package cz.faltyn.elfall.backend.bankid

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Povolené algoritmy podpisu id_tokenu. Bank iD (sandbox i produkce) dle discovery podporuje POUZE PS512,
 * proto je PS512 nutné ponechat. PS256 a ES256 jsou povoleny pro případ rotace. RS256 (PKCS#1 v1.5),
 * HS*, `none` jsou vždy odmítnuty.
 */
val ALLOWED_ALGS = setOf(JWSAlgorithm.PS512, JWSAlgorithm.PS256, JWSAlgorithm.ES256)
private const val LEEWAY_S = 30L

class OidcError(msg: String) : Exception(msg)

private val rnd = SecureRandom()
fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
fun randomToken(n: Int = 32) = b64url(ByteArray(n).also { rnd.nextBytes(it) })
fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())

fun pkcePair(): Pair<String, String> { val v = randomToken(48); return v to b64url(sha256(v)) }
/** nonce vázající transakci: SHA256(salt|tx_hash). */
fun txnBinding(txHash: String, salt: String) = b64url(sha256("$salt|$txHash"))

data class Pending(
    val purpose: String, val verifier: String, val nonce: String, val created: Long,
    val txHash: String? = null, val payoutIban: String? = null, val payoutHolder: String? = null,
)

/** In-memory úložiště state -> Pending s TTL; pop je atomické a jednorázové (ochrana proti replay). */
class PendingStore(
    private val ttlS: Long = 600,
    private val maxItems: Int = 10_000, // CWE-770: strop proti zahlcení paměti přes /auth/start
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val items = ConcurrentHashMap<String, Pending>()
    fun put(state: String, p: Pending) {
        gc()
        if (items.size >= maxItems) throw OidcError("příliš mnoho rozpracovaných přihlášení")
        items[state] = p
    }
    fun pop(state: String): Pending? { gc(); return items.remove(state) }
    private fun gc() { val t = now(); items.entries.removeIf { t - it.value.created > ttlS } }
}

class BankIdClient(private val cfg: BankIdConfig, private val http: HttpClient, private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var disco: JsonObject? = null
    @Volatile private var jwks: JWKSet? = null

    suspend fun discovery(): JsonObject = disco ?: run {
        val r = http.get(cfg.discoveryUrl)
        if (!r.status.isSuccess()) throw OidcError("discovery ${r.status.value}")
        val d = json.parseToJsonElement(r.bodyAsText()).jsonObject
        if (d.str("issuer") != cfg.issuer) throw OidcError("issuer v discovery nesouhlasí")
        d.also { disco = it }
    }

    private suspend fun endpoint(name: String): String = discovery().str(name) ?: throw OidcError("chybí $name")

    suspend fun jwks(refresh: Boolean = false): JWKSet {
        if (!refresh) jwks?.let { return it }
        val r = http.get(endpoint("jwks_uri"))
        if (!r.status.isSuccess()) throw OidcError("jwks ${r.status.value}")
        return JWKSet.parse(r.bodyAsText()).also { jwks = it }
    }

    suspend fun authorizeUrl(scopes: List<String>, state: String, nonce: String, challenge: String, stepUp: Boolean = false): String {
        val u = URLBuilder(endpoint("authorization_endpoint"))
        u.parameters.apply {
            append("client_id", cfg.clientId); append("redirect_uri", cfg.redirectUri)
            append("response_type", "code"); append("scope", scopes.joinToString(" "))
            append("state", state); append("nonce", nonce)
            append("code_challenge", challenge); append("code_challenge_method", "S256")
            append("acr_values", "loa3"); append("ui_locales", "cs"); append("display", "page")
            if (stepUp) { append("prompt", "login"); append("max_age", "0") } else append("prompt", "consent")
        }
        return u.buildString()
    }

    suspend fun exchangeCode(code: String, verifier: String): JsonObject {
        val r = http.submitForm(endpoint("token_endpoint"), Parameters.build {
            append("grant_type", "authorization_code"); append("code", code)
            append("redirect_uri", cfg.redirectUri); append("client_id", cfg.clientId)
            append("client_secret", cfg.clientSecret); append("code_verifier", verifier)
        })
        if (r.status.value != 200) throw OidcError("token endpoint ${r.status.value}")
        return json.parseToJsonElement(r.bodyAsText()).jsonObject
    }

    suspend fun verifyIdToken(idToken: String, nonce: String, maxAuthAgeS: Long? = null): Map<String, Any?> {
        val jwt = try { SignedJWT.parse(idToken) } catch (e: Exception) { throw OidcError("neplatná hlavička id_tokenu") }
        val alg = jwt.header.algorithm
        if (alg !in ALLOWED_ALGS) throw OidcError("nepovolený algoritmus id_tokenu")
        val key = keyFor(jwt.header.keyID, alg)
        val verifier = when (key) {
            is RSAKey -> RSASSAVerifier(key)
            is ECKey -> ECDSAVerifier(key)
            else -> throw OidcError("nepodporovaný typ klíče")
        }
        if (!jwt.verify(verifier) || jwt.state != JWSObject.State.VERIFIED) throw OidcError("id_token neověřen: podpis")
        val c = jwt.jwtClaimsSet
        val t = now()
        for (req in listOf("exp", "iat", "iss", "aud", "sub")) if (c.getClaim(req) == null) throw OidcError("id_token neověřen: chybí $req")
        if (c.issuer != cfg.issuer) throw OidcError("id_token neověřen: iss")
        if (cfg.clientId !in c.audience) throw OidcError("id_token neověřen: aud")
        if (c.expirationTime.time / 1000 + LEEWAY_S < t) throw OidcError("id_token neověřen: exp")
        if (c.issueTime.time / 1000 > t + LEEWAY_S) throw OidcError("iat je v budoucnosti")
        val azp = c.getStringClaim("azp")
        if ((c.audience.size > 1 || azp != null) && azp != cfg.clientId) throw OidcError("azp nesouhlasí")
        val tokNonce = c.getStringClaim("nonce") ?: ""
        if (nonce.isEmpty() || !MessageDigest.isEqual(tokNonce.toByteArray(), nonce.toByteArray())) throw OidcError("nonce nesouhlasí")
        if (maxAuthAgeS != null) {
            val at = (c.getClaim("auth_time") as? Number)?.toLong() ?: c.getDateClaim("auth_time")?.time?.div(1000)
            if (at == null || t - at > maxAuthAgeS) throw OidcError("autentizace není čerstvá (step-up)")
        }
        return c.claims.mapValues { (k, v) -> if (k in setOf("exp", "iat", "nbf", "auth_time") && v is java.util.Date) v.time / 1000 else v }
    }

    private suspend fun keyFor(kid: String?, alg: JWSAlgorithm): JWK {
        val wantRsa = alg != JWSAlgorithm.ES256
        for (refresh in listOf(false, true)) { // rotace klíčů
            val keys = jwks(refresh).keys.filter {
                (it.keyUse == null || it.keyUse == KeyUse.SIGNATURE) && (if (wantRsa) it is RSAKey else it is ECKey) &&
                    (it.algorithm == null || it.algorithm == alg)
            }
            if (kid == null) { if (keys.size == 1) return keys[0] else continue }
            keys.firstOrNull { it.keyID == kid }?.let { return it }
        }
        throw OidcError("klíč z JWKS nenalezen")
    }

    suspend fun profile(accessToken: String): JsonObject {
        val r = http.get(endpoint("profile_endpoint")) { header("Authorization", "Bearer $accessToken") }
        if (r.status.value != 200) throw OidcError("profile endpoint ${r.status.value}")
        return json.parseToJsonElement(r.bodyAsText()).jsonObject
    }
}

internal fun JsonObject.str(k: String): String? = this[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
