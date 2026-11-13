package cz.faltyn.elfall.shared.offramp

/**
 * Card / fiat cash-out ("Výběr na kartu" / Withdraw to card).
 *
 * Product rule: withdrawals go ONLY via licensed providers for CZK, EUR or USD.
 * Card PAN/CVV and KYC never touch ELFALL — hosted widgets / partner web flows only.
 *
 * Active providers (owner decision 2026-10-13): MoonPay Sell and Coinbase Offramp only.
 * Ramp, Banxa, Transak and Mercuryo were evaluated and excluded (see docs/offramp-providers.md).
 * AnycoinProvider is kept as code but is NOT in the active list.
 */
enum class FiatCurrency(val code: String, val symbol: String, val decimals: Int) {
    CZK("CZK", "Kč", 2),
    EUR("EUR", "€", 2),
    USD("USD", "$", 2);

    companion object {
        fun fromCode(code: String): FiatCurrency? =
            entries.firstOrNull { it.code.equals(code.trim(), ignoreCase = true) }

        val supported: List<FiatCurrency> = entries.toList()
    }
}

enum class OffRampProviderId(val displayName: String) {
    ANYCOIN("Anycoin M.P."),
    MOONPAY("MoonPay Sell"),
    COINBASE("Coinbase Offramp"),
}

data class OffRampQuoteRequest(
    val amountCrypto: Double,
    val cryptoAsset: String = "BTC",
    val currency: FiatCurrency,
    val walletAddress: String,
)

data class OffRampQuote(
    val provider: OffRampProviderId,
    val currency: FiatCurrency,
    val amountCrypto: Double,
    val cryptoAsset: String,
    val fiatGrossMinor: Long,
    val feeMinor: Long,
    val fiatNetMinor: Long,
    val rate: Double,
    val expiresAtEpochMs: Long,
    val notes: String = "",
) {
    init {
        require(currency in FiatCurrency.supported) { "Unsupported cash-out currency: ${currency.code}" }
        require(amountCrypto > 0) { "Amount must be positive" }
        require(fiatNetMinor >= 0) { "Net fiat cannot be negative" }
    }
}

data class HostedFlow(
    val provider: OffRampProviderId,
    val url: String,
    /** Optional Android package to prefer (e.g. cz.anycoin.mobile). */
    val androidPackage: String? = null,
)

interface OffRampProvider {
    val id: OffRampProviderId
    suspend fun quote(request: OffRampQuoteRequest): OffRampQuote
    fun buildHostedFlowUrl(request: OffRampQuoteRequest, quote: OffRampQuote): HostedFlow
}

object OffRampValidation {
    const val MIN_BTC = 0.0001
    const val MAX_BTC = 10.0

    fun validateRequest(request: OffRampQuoteRequest) {
        require(request.currency in FiatCurrency.supported) {
            "Cash-out currency must be CZK, EUR or USD (got ${request.currency.code})"
        }
        require(request.amountCrypto in MIN_BTC..MAX_BTC) {
            "Amount must be between $MIN_BTC and $MAX_BTC ${request.cryptoAsset}"
        }
        require(request.walletAddress.isNotBlank()) { "Wallet address required" }
        require(request.cryptoAsset.uppercase() == "BTC") {
            "Only BTC cash-out is wired in this stub (got ${request.cryptoAsset})"
        }
    }

    fun formatFiat(minor: Long, currency: FiatCurrency): String {
        val sign = if (minor < 0) "-" else ""
        val abs = kotlin.math.abs(minor)
        return "${currency.code} $sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }
}
