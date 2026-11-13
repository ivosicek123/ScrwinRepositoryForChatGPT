package cz.faltyn.elfall.shared.identity

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Most k nativnímu prohlížeči pro OIDC (iOS: ASWebAuthenticationSession, Android: Custom Tab).
 * Callback API, aby šlo implementovat přímo ve Swiftu. completion(callbackUrl, error).
 */
interface WebAuthSession {
    fun authenticate(authorizeUrl: String, callbackScheme: String, completion: (String?, String?) -> Unit)
}

/** Hardwarový klíč zařízení (iOS: Secure Enclave P-256, Android: StrongBox/TEE). Podpis ES256 = raw R||S (64 B). */
interface HardwareKeySigner {
    val algorithm: String get() = "ES256"
    val isHardwareBacked: Boolean
    /** Veřejný klíč v X9.63 (0x04||X||Y, 65 B). */
    fun publicKeyX963(): ByteArray
    fun signEs256(data: ByteArray): ByteArray
}

/** Registr platformních implementací (nastaví je nativní aplikace při startu). */
expect object PlatformIdentity {
    var webAuthSession: WebAuthSession?
    var keySigner: HardwareKeySigner?
    val platform: String
}

suspend fun WebAuthSession.authenticate(authorizeUrl: String, callbackScheme: String): String =
    suspendCancellableCoroutine { cont ->
        authenticate(authorizeUrl, callbackScheme) { url, err ->
            if (url != null) cont.resume(url) else cont.resumeWithException(IdentityVerificationException(err ?: "Přihlášení zrušeno"))
        }
    }

/**
 * Celý klientský průběh Bank iD: backend /auth/start → kontrola authorize URL (issuer, code, PKCE S256, nonce, state)
 * → nativní prohlížeč → kontrola callbacku (jednorázový state) → backend /auth/callback (ověří id_token přes JWKS).
 */
class BankIdLoginFlow(
    private val verifier: IdentityVerifier,
    private val session: WebAuthSession,
    private val issuer: String = "https://oidc.sandbox.bankid.cz/",
    private val callbackScheme: String = "elfall",
) {
    suspend fun run(purpose: VerificationPurpose, txHash: String? = null, payoutIban: String? = null, payoutHolder: String? = null): VerificationResult {
        val start = verifier.start(purpose, txHash, payoutIban, payoutHolder)
        if (!OidcCallback.isValidState(start.state)) throw IdentityVerificationException("Neplatný state")
        OidcCallback.checkAuthorizeUrl(start.authorizeUrl, issuer, start.state)
        val cbUrl = session.authenticate(start.authorizeUrl, callbackScheme)
        if (!cbUrl.startsWith("$callbackScheme:")) throw IdentityVerificationException("Neočekávaná návratová URL")
        val cb = OidcCallback.parse(cbUrl, start.state)
        return verifier.complete(cb.code, cb.state)
    }
}
