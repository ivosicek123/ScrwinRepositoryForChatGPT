package cz.faltyn.elfall.shared

import kotlin.coroutines.cancellation.CancellationException

/**
 * Swift/iOS-friendly entry points: no default args, checked exceptions surfaced as Swift `throws`.
 * Android uses the underlying classes directly.
 */
class EleanorFacade(val network: BtcNetwork) {
    private val esplora = EsploraClient.forNetwork(network)
    private var wallet: WalletCore? = null

    fun generateMnemonic(): List<String> = WalletCore.newMnemonic()

    @Throws(IllegalArgumentException::class)
    fun open(words: List<String>) { wallet = WalletCore.fromMnemonic(words, network, "") }

    fun close() { wallet = null }
    fun isOpen() = wallet != null

    fun receiveAddress(index: Int): String = requireNotNull(wallet).receiveAddress(index.toLong())
    fun receiveAddresses(count: Int): List<String> = requireNotNull(wallet).receiveAddresses(count, 0)
    fun bip21(address: String): String = Bip21.build(address, null, null)

    fun validate(address: String): String? = (AddressValidator.check(address, network) as? AddressCheck.Invalid)?.reason

    /** Returns null for unknown; "btc:<addr>|<sats or ->" or "xlm:<G…>|<amount or ->". */
    fun parseScanned(raw: String): String? = when (val p = PaymentUriParser.parse(raw)) {
        is ParsedPayment.Bitcoin -> "btc:${p.address}|${p.amountSats ?: "-"}"
        is ParsedPayment.Stellar -> "xlm:${p.destination}|${p.amount ?: "-"}"
        is ParsedPayment.Unknown -> null
    }

    fun parseBtcAmount(input: String): Long = parseBtcToSats(input) ?: -1
    fun formatSats(sats: Long): String = sats.satsToBtcString()

    @Throws(Exception::class, CancellationException::class)
    suspend fun balanceSats(addressCount: Int): Long = receiveAddresses(addressCount).sumOf { esplora.balanceSats(it) }

    @Throws(Exception::class, CancellationException::class)
    suspend fun previewTransfer(destination: String, amountSats: Long): FeePreview =
        TransferService(requireNotNull(wallet), esplora).preview(destination, amountSats, null)

    @Throws(Exception::class, CancellationException::class)
    suspend fun send(preview: FeePreview): String = TransferService(requireNotNull(wallet), esplora).send(preview)

    @Throws(Exception::class, CancellationException::class)
    suspend fun history(addressCount: Int): List<HistoryItem> {
        val addrs = receiveAddresses(addressCount)
        val tip = esplora.tipHeight()
        val txs = addrs.flatMap { esplora.addressTxs(it) }.distinctBy { it.txid }
        return HistoryMapper.fromEsplora(txs, addrs.toSet(), tip, network.explorerUrl)
    }

    @Throws(Exception::class, CancellationException::class)
    suspend fun networkSnapshot(infoNetwork: BtcNetwork): NetworkSnapshot = NetworkInfoClient().snapshot(infoNetwork)
}
