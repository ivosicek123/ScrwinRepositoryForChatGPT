package cz.faltyn.elfall.shared.swap

/**
 * Cross-crypto Swap (non-custodial). Card cash-out is NOT a swap — see offramp.OffRampProvider.
 * Stubs open a provider hosted widget or document a THORChain / Changelly flow; ELFALL never holds funds.
 */
enum class SwapProviderId(val displayName: String) {
    CHANGELLY("Changelly"),
    THORCHAIN("THORChain (docs)"),
}

data class SwapQuoteRequest(
    val fromAsset: String,
    val toAsset: String,
    val amount: Double,
    val refundAddress: String,
)

data class SwapQuote(
    val provider: SwapProviderId,
    val fromAsset: String,
    val toAsset: String,
    val amountIn: Double,
    val amountOut: Double,
    val rate: Double,
    val feeNote: String,
)

data class SwapHostedFlow(val provider: SwapProviderId, val url: String)

interface SwapProvider {
    val id: SwapProviderId
    suspend fun quote(request: SwapQuoteRequest): SwapQuote
    fun buildHostedFlowUrl(request: SwapQuoteRequest, quote: SwapQuote): SwapHostedFlow
}

class ChangellySwapStub : SwapProvider {
    override val id = SwapProviderId.CHANGELLY
    override suspend fun quote(request: SwapQuoteRequest): SwapQuote {
        require(request.amount > 0)
        require(request.fromAsset.isNotBlank() && request.toAsset.isNotBlank())
        // Placeholder 1:1-ish rate; widget is authoritative.
        val out = request.amount * 0.99
        return SwapQuote(id, request.fromAsset.uppercase(), request.toAsset.uppercase(), request.amount, out, 0.99, "~1% widget fee (estimate)")
    }
    override fun buildHostedFlowUrl(request: SwapQuoteRequest, quote: SwapQuote) = SwapHostedFlow(
        id,
        // Docs: https://changelly.com/ — API key goes in config later; no custody in ELFALL.
        "https://changelly.com/widget/v1?from=${request.fromAsset.lowercase()}&to=${request.toAsset.lowercase()}&amount=${request.amount}&address=${request.refundAddress}&apiKey=REPLACE_ME",
    )
}

/** THORChain is documented only for now (no native client wired). */
class ThorchainDocsStub : SwapProvider {
    override val id = SwapProviderId.THORCHAIN
    override suspend fun quote(request: SwapQuoteRequest): SwapQuote {
        require(request.amount > 0)
        return SwapQuote(id, request.fromAsset.uppercase(), request.toAsset.uppercase(), request.amount, 0.0, 0.0, "See https://docs.thorchain.org/ — not executed in-app yet")
    }
    override fun buildHostedFlowUrl(request: SwapQuoteRequest, quote: SwapQuote) = SwapHostedFlow(
        id,
        "https://docs.thorchain.org/",
    )
}

fun defaultSwapProviders(): List<SwapProvider> = listOf(ChangellySwapStub(), ThorchainDocsStub())
