package cz.faltyn.elfall.shared.identity

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Feature flagy ověření identity. Bank iD je ve výchozím stavu VYPNUTO (jen sandbox). */
object IdentityFlags {
    var bankIdEnabled: Boolean = false
}

object BankIdStrings {
    const val VERIFY_BUTTON = "Ověřit přes Bank iD"
    const val CONFIRM_WITHDRAWAL = "Potvrdit výběr přes Bank iD"
    const val VERIFIED = "Identita ověřena přes Bank iD"
    const val FAILED = "Ověření přes Bank iD se nezdařilo"
    const val SANDBOX_NOTE = "Testovací prostředí (sandbox) – nepoužívejte skutečné údaje."
}

enum class VerificationPurpose(val wire: String) { KYC("kyc"), ACCOUNT("account"), TRANSACTION("txn") }

@Serializable
data class StartRequest(
    val purpose: String,
    @SerialName("tx_hash") val txHash: String? = null,
    @SerialName("payout_iban") val payoutIban: String? = null,
    @SerialName("payout_holder") val payoutHolder: String? = null,
)

@Serializable
data class AuthStart(@SerialName("authorize_url") val authorizeUrl: String, val state: String)

@Serializable
data class CallbackRequest(val code: String, val state: String)

@Serializable
data class VerificationResult(
    val purpose: String,
    val sub: String? = null,
    val level: String? = null,
    val confirmed: Boolean? = null,
    @SerialName("iban_match") val ibanMatch: Boolean? = null,
    @SerialName("name_match") val nameMatch: Boolean? = null,
    @SerialName("tx_hash") val txHash: String? = null,
) {
    val isKycVerified get() = level == "bank_aml_verified"
}

class IdentityVerificationException(message: String) : Exception(message)

/** Obecné rozhraní poskytovatele ověření identity (Bank iD, v budoucnu případně jiné). */
interface IdentityVerifier {
    val enabled: Boolean
    /** Vrátí URL, kterou klient otevře v prohlížeči (Custom Tab / ASWebAuthenticationSession / okno). */
    suspend fun start(purpose: VerificationPurpose, txHash: String? = null, payoutIban: String? = null, payoutHolder: String? = null): AuthStart
    /** Dokončí přihlášení po návratu na redirect URI (code + state předá backendu). */
    suspend fun complete(code: String, state: String): VerificationResult
}

/**
 * Bank iD verifier – volá backend (backend/bankid). Klient nikdy nedrží client_secret
 * a nevyměňuje code sám; PKCE verifier i nonce zůstávají na serveru.
 */
class BankIdVerifier(
    private val http: HttpClient,
    private val backendBaseUrl: String,
    override val enabled: Boolean = IdentityFlags.bankIdEnabled,
) : IdentityVerifier {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun start(purpose: VerificationPurpose, txHash: String?, payoutIban: String?, payoutHolder: String?): AuthStart {
        check(enabled) { "Bank iD je vypnuto" }
        if (purpose == VerificationPurpose.TRANSACTION) {
            require(txHash != null && TX_HASH.matches(txHash)) { "txHash musí být SHA-256 hex" }
        }
        return post("/auth/start", json.encodeToString(StartRequest.serializer(), StartRequest(purpose.wire, txHash, payoutIban, payoutHolder)))
            .let { json.decodeFromString(AuthStart.serializer(), it) }
    }

    override suspend fun complete(code: String, state: String): VerificationResult {
        check(enabled) { "Bank iD je vypnuto" }
        return post("/auth/callback", json.encodeToString(CallbackRequest.serializer(), CallbackRequest(code, state)))
            .let { json.decodeFromString(VerificationResult.serializer(), it) }
    }

    private suspend fun post(path: String, body: String): String {
        val r = http.post(backendBaseUrl.trimEnd('/') + path) { contentType(ContentType.Application.Json); setBody(body) }
        if (!r.status.isSuccess()) throw IdentityVerificationException("${BankIdStrings.FAILED} (${r.status.value})")
        return r.bodyAsText()
    }

    companion object { private val TX_HASH = Regex("^[0-9a-fA-F]{64}$") }
}

/** Brána před výběrem: když je flag vypnutý, nic neblokuje; jinak vyžaduje potvrzení svázané s hashem. */
object WithdrawalGate {
    fun requiresBankId(verifier: IdentityVerifier) = verifier.enabled
    fun isConfirmed(result: VerificationResult?, expectedTxHash: String) =
        result != null && result.purpose == "txn" && result.confirmed == true && result.txHash.equals(expectedTxHash, ignoreCase = true)
}
