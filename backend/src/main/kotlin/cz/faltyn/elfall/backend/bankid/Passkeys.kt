package cz.faltyn.elfall.backend.bankid

import com.webauthn4j.WebAuthnManager
import com.webauthn4j.credential.CredentialRecord
import com.webauthn4j.credential.CredentialRecordImpl
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticationRequest
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.RegistrationRequest
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * WebAuthn / passkeys (webauthn4j). Phishing-odolné přihlášení (NIST SP 800-63B-4 AAL2/AAL3).
 * Politika: jen ES256 a EdDSA, user verification POVINNÉ, challenge jednorázová s TTL, kontrola origin + rpId,
 * kontrola čítače podpisů (klonovaný autentikátor). Úložiště je in-memory (produkce: DB).
 */
data class PasskeyConfig(val rpId: String, val rpName: String, val origin: String, val challengeTtlS: Long = 300) {
    init {
        require(origin.startsWith("https://") || origin.startsWith("http://localhost")) { "WEBAUTHN_ORIGIN musí být https" }
        require(java.net.URI(origin).host == rpId || java.net.URI(origin).host.endsWith(".$rpId")) { "rpId musí odpovídat originu" }
    }
    companion object {
        fun fromEnv(e: Map<String, String> = System.getenv()): PasskeyConfig? {
            val rp = e["WEBAUTHN_RP_ID"] ?: return null
            return PasskeyConfig(rp, e["WEBAUTHN_RP_NAME"] ?: "ELFALL", e["WEBAUTHN_ORIGIN"] ?: "https://$rp")
        }
    }
}

val PASSKEY_ALGS = listOf(COSEAlgorithmIdentifier.ES256, COSEAlgorithmIdentifier.EdDSA)

class PasskeyService(private val cfg: PasskeyConfig, private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private val manager = WebAuthnManager.createNonStrictWebAuthnManager() // attestation "none" (soukromí)
    private val rnd = SecureRandom()
    private data class Pending(val kind: String, val user: String?, val userHandle: ByteArray?, val created: Long)
    private val challenges = ConcurrentHashMap<String, Pending>()
    private data class Stored(val user: String, val userHandle: ByteArray, val record: CredentialRecord)
    private val credentials = ConcurrentHashMap<String, Stored>()
    private val userHandles = ConcurrentHashMap<String, ByteArray>()
    private val params = PASSKEY_ALGS.map { PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, it) }

    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun unb64(s: String) = Base64.getUrlDecoder().decode(s)
    private fun newChallenge(p: Pending): String {
        gc(); require(challenges.size < 10_000) { "příliš mnoho rozpracovaných požadavků" }
        val c = b64(ByteArray(32).also(rnd::nextBytes)); challenges[c] = p; return c
    }
    private fun take(c: String, kind: String): Pending? { gc(); return challenges.remove(c)?.takeIf { it.kind == kind } }
    private fun gc() { val t = now(); challenges.entries.removeIf { t - it.value.created > cfg.challengeTtlS } }
    private fun server(c: String) = ServerProperty(Origin(cfg.origin), cfg.rpId, DefaultChallenge(unb64(c)))

    fun registrationOptions(user: String): JsonObject {
        require(USER.matches(user)) { "neplatné uživatelské jméno" }
        val uh = userHandles.getOrPut(user) { ByteArray(32).also(rnd::nextBytes) } // náhodný, bez osobních údajů
        val ch = newChallenge(Pending("reg", user, uh, now()))
        return buildJsonObject {
            put("challenge", ch)
            put("rp", buildJsonObject { put("id", cfg.rpId); put("name", cfg.rpName) })
            put("user", buildJsonObject { put("id", b64(uh)); put("name", user); put("displayName", user) })
            put("pubKeyCredParams", buildJsonArray { PASSKEY_ALGS.forEach { a -> add(buildJsonObject { put("type", "public-key"); put("alg", a.value) }) } })
            put("timeout", cfg.challengeTtlS * 1000)
            put("attestation", "none")
            put("authenticatorSelection", buildJsonObject { put("residentKey", "required"); put("userVerification", "required") })
            put("excludeCredentials", JsonArray(credentials.filterValues { it.user == user }.keys.map { buildJsonObject { put("type", "public-key"); put("id", it) } }))
        }
    }

    fun register(challenge: String, attestationObject: String, clientDataJSON: String): String {
        val p = take(challenge, "reg") ?: throw PasskeyError("neznámá nebo expirovaná challenge")
        val data = try {
            manager.verify(RegistrationRequest(unb64(attestationObject), unb64(clientDataJSON)), RegistrationParameters(server(challenge), params, true, true))
        } catch (e: Exception) { throw PasskeyError("registrace neověřena: ${e.javaClass.simpleName}") }
        val ao = data.attestationObject ?: throw PasskeyError("chybí attestationObject")
        val cd = ao.authenticatorData.attestedCredentialData ?: throw PasskeyError("chybí credential data")
        val id = b64(cd.credentialId)
        if (credentials.containsKey(id)) throw PasskeyError("credential již existuje")
        credentials[id] = Stored(p.user!!, p.userHandle!!, CredentialRecordImpl(ao, data.collectedClientData, data.clientExtensions, data.transports))
        return id
    }

    fun authenticationOptions(user: String?): JsonObject {
        val ch = newChallenge(Pending("auth", user, null, now()))
        val allow = credentials.filterValues { user != null && it.user == user }.keys
        return buildJsonObject {
            put("challenge", ch); put("rpId", cfg.rpId); put("timeout", cfg.challengeTtlS * 1000); put("userVerification", "required")
            put("allowCredentials", JsonArray(allow.map { buildJsonObject { put("type", "public-key"); put("id", it) } }))
        }
    }

    fun authenticate(challenge: String, credentialId: String, authenticatorData: String, clientDataJSON: String, signature: String, userHandle: String?): String {
        val p = take(challenge, "auth") ?: throw PasskeyError("neznámá nebo expirovaná challenge")
        val st = credentials[credentialId] ?: throw PasskeyError("neznámý credential")
        if (p.user != null && p.user != st.user) throw PasskeyError("credential nepatří uživateli")
        if (userHandle != null && !java.security.MessageDigest.isEqual(unb64(userHandle), st.userHandle)) throw PasskeyError("userHandle nesouhlasí")
        val data = try {
            manager.verify(
                AuthenticationRequest(unb64(credentialId), userHandle?.let(::unb64), unb64(authenticatorData), unb64(clientDataJSON), unb64(signature)),
                AuthenticationParameters(server(challenge), st.record, listOf(unb64(credentialId)), true, true),
            )
        } catch (e: Exception) { throw PasskeyError("přihlášení neověřeno: ${e.javaClass.simpleName}") }
        st.record.counter = data.authenticatorData!!.signCount
        return st.user
    }

    companion object { private val USER = Regex("^[A-Za-z0-9._-]{3,64}$") }
}

class PasskeyError(msg: String) : Exception(msg)

@Serializable data class PkUser(val user: String? = null)
@Serializable data class PkRegister(val challenge: String, val attestationObject: String, val clientDataJSON: String)
@Serializable data class PkLogin(val challenge: String, val credentialId: String, val authenticatorData: String, val clientDataJSON: String, val signature: String, val userHandle: String? = null)

private val PJ = Json { ignoreUnknownKeys = true }
private suspend fun ApplicationCall.pj(status: HttpStatusCode, o: JsonObject) = respondText(o.toString(), io.ktor.http.ContentType.Application.Json, status)
private fun err(m: String?) = buildJsonObject { put("detail", m ?: "chyba") }

fun Application.passkeyModule(svc: PasskeyService) {
    routing {
        post("/passkey/register/options") {
            val r = runCatching { PJ.decodeFromString(PkUser.serializer(), call.receiveText()) }.getOrNull()
            try { call.pj(HttpStatusCode.OK, svc.registrationOptions(r?.user ?: "")) } catch (e: IllegalArgumentException) { call.pj(HttpStatusCode.BadRequest, err(e.message)) }
        }
        post("/passkey/register/verify") {
            val r = runCatching { PJ.decodeFromString(PkRegister.serializer(), call.receiveText()) }.getOrNull()
                ?: return@post call.pj(HttpStatusCode.UnprocessableEntity, err("neplatný požadavek"))
            try { call.pj(HttpStatusCode.OK, buildJsonObject { put("credentialId", svc.register(r.challenge, r.attestationObject, r.clientDataJSON)) }) }
            catch (e: PasskeyError) { call.pj(HttpStatusCode.BadRequest, err(e.message)) }
        }
        post("/passkey/login/options") {
            val r = runCatching { PJ.decodeFromString(PkUser.serializer(), call.receiveText()) }.getOrNull()
            try { call.pj(HttpStatusCode.OK, svc.authenticationOptions(r?.user)) } catch (e: IllegalArgumentException) { call.pj(HttpStatusCode.TooManyRequests, err(e.message)) }
        }
        post("/passkey/login/verify") {
            val r = runCatching { PJ.decodeFromString(PkLogin.serializer(), call.receiveText()) }.getOrNull()
                ?: return@post call.pj(HttpStatusCode.UnprocessableEntity, err("neplatný požadavek"))
            try {
                val u = svc.authenticate(r.challenge, r.credentialId, r.authenticatorData, r.clientDataJSON, r.signature, r.userHandle)
                call.pj(HttpStatusCode.OK, buildJsonObject { put("user", u); put("verified", JsonPrimitive(true)) })
            } catch (e: PasskeyError) { call.pj(HttpStatusCode.Unauthorized, err(e.message)) }
        }
    }
}
