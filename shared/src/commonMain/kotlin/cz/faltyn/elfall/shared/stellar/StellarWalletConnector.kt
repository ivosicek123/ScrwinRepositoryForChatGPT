package cz.faltyn.elfall.shared.stellar

/**
 * Non-custodial Stellar signing connectors.
 *
 * - LOBSTR: WalletConnect 2.0 + mobile app; SEP-7 deep links when the OS routes web+stellar.
 *   Docs: https://lobstr.freshdesk.com/support/solutions/articles/151000001589
 *         https://github.com/Lobstrco/lobstr-browser-extension (@lobstrco/signer-extension-api for web)
 * - Freighter: browser `@stellar/freighter-api`; mobile via WalletConnect v2.
 *   Docs: https://docs.freighter.app/
 *
 * ELFALL Android path today: build SEP-7 URIs and hand them to the OS / wallet.
 * Full WalletConnect session + Freighter JS bridge belong in a web companion or future module
 * (Freighter extension API is JavaScript-only).
 */
enum class StellarWalletId(val displayName: String) {
    LOBSTR("LOBSTR"),
    FREIGHTER("Freighter"),
}

data class StellarConnectIntent(
    val wallet: StellarWalletId,
    /** Primary URI to open (SEP-7 or https fallback / docs). */
    val uri: String,
    val androidPackage: String? = null,
    val notes: String = "",
)

interface StellarWalletConnector {
    val id: StellarWalletId
    fun payIntent(request: Sep7.PayRequest): StellarConnectIntent
    fun signTxIntent(request: Sep7.TxRequest): StellarConnectIntent
}

class LobstrConnector : StellarWalletConnector {
    override val id = StellarWalletId.LOBSTR

    override fun payIntent(request: Sep7.PayRequest) = StellarConnectIntent(
        wallet = id,
        uri = Sep7.payUri(request),
        androidPackage = "com.lobstr.client",
        notes = "Opens SEP-7 pay URI; LOBSTR signs in-app (WalletConnect also supported for dApps).",
    )

    override fun signTxIntent(request: Sep7.TxRequest) = StellarConnectIntent(
        wallet = id,
        uri = Sep7.txUri(request),
        androidPackage = "com.lobstr.client",
        notes = "Opens SEP-7 tx URI for LOBSTR to review and sign. Keys never leave LOBSTR.",
    )
}

class FreighterConnector : StellarWalletConnector {
    override val id = StellarWalletId.FREIGHTER

    override fun payIntent(request: Sep7.PayRequest) = StellarConnectIntent(
        wallet = id,
        uri = Sep7.payUri(request),
        androidPackage = null, // Freighter mobile uses WalletConnect; no stable package assumed
        notes = "SEP-7 URI for wallets that handle web+stellar. On desktop web use @stellar/freighter-api; " +
            "on mobile prefer WalletConnect v2 (stellar_signXDR). See https://docs.freighter.app/",
    )

    override fun signTxIntent(request: Sep7.TxRequest) = StellarConnectIntent(
        wallet = id,
        uri = Sep7.txUri(request),
        notes = "SEP-7 tx URI. Freighter Mobile signing methods: stellar_signXDR / stellar_signAndSubmitXDR.",
    )
}

fun defaultStellarConnectors(): List<StellarWalletConnector> = listOf(LobstrConnector(), FreighterConnector())
