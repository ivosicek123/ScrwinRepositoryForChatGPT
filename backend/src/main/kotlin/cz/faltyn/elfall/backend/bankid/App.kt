package cz.faltyn.elfall.backend.bankid

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable data class StartRequest(val purpose: String, val tx_hash: String? = null, val payout_iban: String? = null, val payout_holder: String? = null)
@Serializable data class CallbackRequest(val code: String, val state: String)

private val TX_HASH = Regex("^[0-9a-fA-F]{64}$")
private val PURPOSE = Regex("^(kyc|account|txn)$")
private val J = Json { ignoreUnknownKeys = true }

private fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull; is Boolean -> JsonPrimitive(v); is Number -> JsonPrimitive(v); is String -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to toJson(it.value) }); else -> JsonPrimitive(v.toString())
}

private suspend fun io.ktor.server.application.ApplicationCall.json(status: HttpStatusCode, m: Map<String, Any?>) =
    respondText(toJson(m).toString(), io.ktor.http.ContentType.Application.Json, status)

fun Application.bankIdModule(cfg: BankIdConfig, bank: BankIdClient, store: PendingStore = PendingStore(cfg.pendingTtlS)) {
    install(ContentNegotiation) { json() }
    routing {
        post("/auth/start") {
            val req = runCatching { J.decodeFromString(StartRequest.serializer(), call.receiveText()) }.getOrNull()
            if (req == null || !PURPOSE.matches(req.purpose)) return@post call.json(HttpStatusCode.UnprocessableEntity, mapOf("detail" to "neplatný požadavek"))
            if (req.purpose == "txn" && !(req.tx_hash != null && TX_HASH.matches(req.tx_hash)))
                return@post call.json(HttpStatusCode.BadRequest, mapOf("detail" to "tx_hash musí být SHA-256 hex"))
            if (req.purpose == "account" && req.payout_iban.isNullOrEmpty())
                return@post call.json(HttpStatusCode.BadRequest, mapOf("detail" to "chybí payout_iban"))
            val (verifier, challenge) = pkcePair()
            val state = randomToken()
            var nonce = randomToken()
            if (req.purpose == "txn") nonce = txnBinding(req.tx_hash!!, nonce)
            val scopes = when (req.purpose) { "kyc" -> cfg.kycScopes; "account" -> cfg.accountScopes; else -> cfg.txnScopes }
            try {
                store.put(state, Pending(req.purpose, verifier, nonce, System.currentTimeMillis() / 1000, req.tx_hash, req.payout_iban, req.payout_holder))
            } catch (e: OidcError) {
                return@post call.json(HttpStatusCode.TooManyRequests, mapOf("detail" to e.message))
            }
            val url = bank.authorizeUrl(scopes, state, nonce, challenge, stepUp = req.purpose == "txn")
            call.json(HttpStatusCode.OK, mapOf("authorize_url" to url, "state" to state))
        }
        post("/auth/callback") {
            val req = runCatching { J.decodeFromString(CallbackRequest.serializer(), call.receiveText()) }.getOrNull()
                ?: return@post call.json(HttpStatusCode.UnprocessableEntity, mapOf("detail" to "neplatný požadavek"))
            val p = store.pop(req.state) ?: return@post call.json(HttpStatusCode.BadRequest, mapOf("detail" to "neznámý nebo expirovaný state"))
            try {
                val tokens = bank.exchangeCode(req.code, p.verifier)
                val claims = bank.verifyIdToken(tokens.str("id_token") ?: throw OidcError("chybí id_token"), p.nonce,
                    if (p.purpose == "txn") cfg.stepUpMaxAgeS else null)
                val sub = claims["sub"] as String
                if (p.purpose == "txn") {
                    if (claims["acr"] != "loa3") throw OidcError("acr není loa3")
                    return@post call.json(HttpStatusCode.OK, mapOf("purpose" to "txn", "confirmed" to true, "tx_hash" to p.txHash, "sub" to sub))
                }
                val profile = bank.profile(tokens.str("access_token") ?: throw OidcError("chybí access_token"))
                profile.str("sub")?.let { if (it != sub) throw OidcError("sub v profilu nesouhlasí s id_tokenem") }
                val body = if (p.purpose == "kyc") mapOf("purpose" to "kyc", "sub" to sub) + Logic.kycLevel(profile, claims)
                else mapOf("purpose" to "account", "sub" to sub) + Logic.accountOwnership(profile, p.payoutIban, p.payoutHolder)
                call.json(HttpStatusCode.OK, body)
            } catch (e: OidcError) {
                call.json(HttpStatusCode.Unauthorized, mapOf("detail" to e.message))
            }
        }
        get("/healthz") { call.json(HttpStatusCode.OK, mapOf("ok" to true, "env" to cfg.env)) }
    }
}

fun main() {
    val cfg = BankIdConfig.fromEnv()
    val bank = BankIdClient(cfg, HttpClient(CIO) { expectSuccess = false; engine { requestTimeout = 10_000 } })
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val passkeys = PasskeyConfig.fromEnv()?.let { PasskeyService(it) }
    embeddedServer(ServerCIO, port = port, host = System.getenv("BIND") ?: "127.0.0.1") {
        bankIdModule(cfg, bank)
        passkeys?.let { passkeyModule(it) } // zapnuto jen při nastaveném WEBAUTHN_RP_ID
        // Zero Trust (deny-by-default + DPoP tokeny ≤ 5 min) – jen s passkeys a ZT_PUBLIC_BASE_URL
        // Postupné nasazení: ZT_ENABLE=true (výchozí vypnuto), viz deploy/rollout/README.md
        val zt = ztFromEnv(System.getenv())
        if (passkeys != null && zt != null) {
            zeroTrustModule(zt, passkeys)
            // metriky na odděleném interním listeneru (Prometheus), ne na veřejném portu
            val mPort = System.getenv("ZT_METRICS_PORT")?.toInt() ?: 9464
            embeddedServer(ServerCIO, port = mPort, host = System.getenv("ZT_METRICS_BIND") ?: "127.0.0.1") {
                routing { get("/metrics") { zt.checkAuditChain(); call.respondText(zt.metrics.metricsText(), io.ktor.http.ContentType.parse("text/plain; version=0.0.4")) } }
            }.start(wait = false)
        }
    }.start(wait = true)
}

/** Zero Trust jen při ZT_ENABLE=true + ZT_PUBLIC_BASE_URL + klíči ze secret store; revokace v ZT_REVOCATION_FILE. */
fun ztFromEnv(e: Map<String, String>): ZeroTrust? {
    if (e["ZT_ENABLE"]?.lowercase() != "true") return null
    val cfg = e["ZT_PUBLIC_BASE_URL"]?.let { ZtConfig.fromEnv(it, e) } ?: return null
    val store = FileRevocationStore(java.io.File(e["ZT_REVOCATION_FILE"] ?: "/var/lib/elfall/zt-revocations.log"))
    return ZeroTrust(cfg, store = store)
}
