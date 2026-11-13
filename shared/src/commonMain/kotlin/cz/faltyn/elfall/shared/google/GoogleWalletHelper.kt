package cz.faltyn.elfall.shared.google

/**
 * Google Wallet / Google Pay — capability stub (research 2026-10-12).
 *
 * ## What Google Wallet API is
 * Passes API for loyalty cards, tickets, boarding passes, etc.
 * Docs: https://developers.google.com/wallet/reference/rest
 * **Cannot** receive crypto→card payouts. Not a bank/card cash-out destination.
 *
 * ## What Google Pay API is
 * Checkout API so users pay *merchants* with a card saved to Google.
 * Docs: https://developers.google.com/pay/api/web/guides/tutorial
 * Useful if ELFALL (or a licensed partner) **sells** crypto and collects fiat —
 * Google Pay is the payment method for the *buy* side, not a payout rail.
 *
 * ## Implication for ELFALL "Výběr na kartu"
 * Cash-out destinations remain licensed off-ramp providers (MoonPay / Coinbase).
 * Google Wallet/Pay may later help the user *select* a payment method for **buying** crypto,
 * or display an ELFALL loyalty/receipt pass — never as the crypto payout destination itself.
 *
 * Anycoin's public materials mention Google Pay for deposits/purchases, not for receiving sells.
 */
object GoogleWalletHelper {
    const val WALLET_PASSES_DOCS = "https://developers.google.com/wallet/reference/rest"
    const val GOOGLE_PAY_DOCS = "https://developers.google.com/pay/api/web/guides/tutorial"

    enum class Role {
        /** Not supported — do not offer as cash-out destination. */
        NOT_A_PAYOUT_DESTINATION,
        /** Future: Google Pay button on a buy-crypto flow via a licensed seller. */
        BUY_SIDE_PAYMENT_METHOD,
        /** Future: save an ELFALL receipt / loyalty pass into Google Wallet. */
        PASS_ISSUER,
    }

    data class Guidance(
        val role: Role,
        val summaryEn: String,
        val summaryCs: String,
        val docsUrl: String,
    )

    fun cashOutGuidance() = Guidance(
        role = Role.NOT_A_PAYOUT_DESTINATION,
        summaryEn = "Google Wallet does not receive card payouts from crypto sells. Use MoonPay/Coinbase.",
        summaryCs = "Google Wallet nepřijímá výplaty z prodeje krypta na kartu. Použijte MoonPay/Coinbase.",
        docsUrl = WALLET_PASSES_DOCS,
    )

    fun buySideGuidance() = Guidance(
        role = Role.BUY_SIDE_PAYMENT_METHOD,
        summaryEn = "Google Pay can be a checkout method when buying crypto through a licensed seller (not implemented).",
        summaryCs = "Google Pay může sloužit k platbě při nákupu krypta u licencovaného prodejce (zatím neimplementováno).",
        docsUrl = GOOGLE_PAY_DOCS,
    )
}
