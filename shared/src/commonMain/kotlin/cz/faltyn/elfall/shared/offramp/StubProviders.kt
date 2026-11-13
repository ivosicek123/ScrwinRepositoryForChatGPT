package cz.faltyn.elfall.shared.offramp

/**
 * Provider catalogue. Active: MoonPay and Coinbase only (owner decision 2026-10-13).
 * Anycoin stays as code but is not active. Ramp/Banxa/Transak/Mercuryo are excluded.
 * No real API keys — placeholders in [OffRampConfig] only.
 *
 * Anycoin honesty note (research 2026-10-12):
 * - Public site (https://www.anycoin.cz) documents buy/sell for CZK/EUR, BankID KYC, bank-transfer
 *   fiat withdrawal, and Google Pay / Apple Pay for *purchases* — **no public partner/sell API**.
 * - Integration today = hosted web / app deep link. Partner API contact required for in-app quotes.
 * - Public docs emphasize fiat withdrawal to a **bank account**, not a card PAN. Card cash-out may
 *   still require the user to complete payout inside Anycoin after KYC.
 */
data class OffRampConfig(
    val anycoinAffiliateOrPartnerId: String = "REPLACE_ME",
    val moonPayPublishableKey: String = "pk_test_REPLACE_ME",
    val coinbaseAppId: String = "REPLACE_ME",
    val btcCzk: Double = 2_000_000.0,
    val btcEur: Double = 80_000.0,
    val btcUsd: Double = 85_000.0,
    val feeBps: Int = 150,
) {
    fun rate(currency: FiatCurrency): Double = when (currency) {
        FiatCurrency.CZK -> btcCzk
        FiatCurrency.EUR -> btcEur
        FiatCurrency.USD -> btcUsd
    }
}

abstract class HostedOffRampStub(
    override val id: OffRampProviderId,
    protected val config: OffRampConfig,
) : OffRampProvider {
    override suspend fun quote(request: OffRampQuoteRequest): OffRampQuote {
        OffRampValidation.validateRequest(request)
        val rate = config.rate(request.currency)
        val grossMinor = (request.amountCrypto * rate * 100).toLong()
        val feeMinor = grossMinor * config.feeBps / 10_000
        return OffRampQuote(
            provider = id,
            currency = request.currency,
            amountCrypto = request.amountCrypto,
            cryptoAsset = request.cryptoAsset.uppercase(),
            fiatGrossMinor = grossMinor,
            feeMinor = feeMinor,
            fiatNetMinor = grossMinor - feeMinor,
            rate = rate,
            expiresAtEpochMs = 0L,
        )
    }
}

/** Primary CZ provider — hosted flow only until a partner API exists. */
class AnycoinProvider(config: OffRampConfig = OffRampConfig()) : HostedOffRampStub(OffRampProviderId.ANYCOIN, config) {
    override suspend fun quote(request: OffRampQuoteRequest): OffRampQuote {
        val base = super.quote(request)
        val note = when (request.currency) {
            FiatCurrency.CZK, FiatCurrency.EUR ->
                "Hosted Anycoin web/app. Public docs: fiat out to bank account after KYC; no public sell API."
            FiatCurrency.USD ->
                "USD may be limited on Anycoin (CZ/EUR focus). Confirm in the Anycoin UI; fallbacks available."
        }
        return base.copy(notes = note)
    }

    override fun buildHostedFlowUrl(request: OffRampQuoteRequest, quote: OffRampQuote): HostedFlow {
        OffRampValidation.validateRequest(request)
        // No documented query API — land on sell/home with locale hints. Partner deeplink TBD.
        val url = buildString {
            append("https://www.anycoin.cz/?locale=")
            append(if (request.currency == FiatCurrency.CZK) "cs_CZ" else "en_US")
            append("&elfall_asset=BTC")
            append("&elfall_amount=${request.amountCrypto}")
            append("&elfall_fiat=${request.currency.code}")
            if (config.anycoinAffiliateOrPartnerId != "REPLACE_ME") {
                append("&ref=${config.anycoinAffiliateOrPartnerId}")
            }
        }
        return HostedFlow(id, url, androidPackage = "cz.anycoin.mobile")
    }
}

class MoonPaySellStub(config: OffRampConfig = OffRampConfig()) : HostedOffRampStub(OffRampProviderId.MOONPAY, config) {
    override fun buildHostedFlowUrl(request: OffRampQuoteRequest, quote: OffRampQuote): HostedFlow {
        OffRampValidation.validateRequest(request)
        val url = buildString {
            append("https://sell.moonpay.com?")
            append("apiKey=${config.moonPayPublishableKey}")
            append("&baseCurrencyCode=btc")
            append("&baseCurrencyAmount=${request.amountCrypto}")
            append("&quoteCurrencyCode=${request.currency.code.lowercase()}")
            append("&refundWalletAddress=${request.walletAddress}")
        }
        return HostedFlow(id, url)
    }
}

/** Coinbase Onramp/Offramp (CDP) one-click sell URL. Needs user Coinbase account + KYC. */
class CoinbaseOffRampStub(config: OffRampConfig = OffRampConfig()) : HostedOffRampStub(OffRampProviderId.COINBASE, config) {
    override fun buildHostedFlowUrl(request: OffRampQuoteRequest, quote: OffRampQuote): HostedFlow {
        OffRampValidation.validateRequest(request)
        val url = buildString {
            append("https://pay.coinbase.com/v3/sell/input?")
            append("appId=${config.coinbaseAppId}")
            append("&defaultAsset=BTC")
            append("&defaultNetwork=bitcoin")
            append("&presetCryptoAmount=${request.amountCrypto}")
            append("&fiatCurrency=${request.currency.code}")
            append("&defaultCashoutMethod=CARD")
        }
        return HostedFlow(id, url)
    }
}

/** Active off-ramp providers: MoonPay, Coinbase. */
fun defaultOffRampProviders(config: OffRampConfig = OffRampConfig()): List<OffRampProvider> = listOf(
    MoonPaySellStub(config),
    CoinbaseOffRampStub(config),
)
