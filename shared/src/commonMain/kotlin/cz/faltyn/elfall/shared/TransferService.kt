package cz.faltyn.elfall.shared

/**
 * **Transfer** (převod): send BTC to another crypto address on the Bitcoin network
 * (and later cross-network hops via Swap). This is NOT a cash-out / withdrawal.
 *
 * Cash-out ("Výběr") = card only, CZK/EUR/USD → see offramp.OffRampProvider.
 *
 * Implementation delegates to the existing P2WPKH builder in [WithdrawService]
 * (kept as the low-level engine; UI must call it "Transfer").
 */
typealias TransferFeePreview = FeePreview

class TransferService(wallet: WalletCore, esplora: EsploraClient) {
    private val engine = WithdrawService(wallet, esplora)

    suspend fun preview(destination: String, amountSats: Long, feeRateSatPerVb: Long? = null): TransferFeePreview =
        engine.preview(destination, amountSats, feeRateSatPerVb)

    suspend fun send(preview: TransferFeePreview): String = engine.send(preview)
}
