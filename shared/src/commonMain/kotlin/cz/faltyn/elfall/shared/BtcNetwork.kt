package cz.faltyn.elfall.shared

import fr.acinq.bitcoin.Chain

/** Supported networks. TESTNET is the default everywhere; MAINNET is opt-in via the `mainnet` build flavor. */
enum class BtcNetwork(
    val chain: Chain,
    val esploraUrl: String,
    val coinType: Int,
    val explorerUrl: String,
    /** Blockstream Esplora fallback used by [EsploraClient] when mempool.space fails. */
    val fallbackEsploraUrls: List<String>,
) {
    TESTNET(Chain.Testnet3, "https://mempool.space/testnet/api", 1, "https://mempool.space/testnet", listOf("https://blockstream.info/testnet/api")),
    SIGNET(Chain.Signet, "https://mempool.space/signet/api", 1, "https://mempool.space/signet", listOf("https://blockstream.info/signet/api")),
    MAINNET(Chain.Mainnet, "https://mempool.space/api", 0, "https://mempool.space", listOf("https://blockstream.info/api"));

    /** Primary (mempool.space) first, then Blockstream fallbacks. */
    val esploraEndpoints: List<String> get() = listOf(esploraUrl) + fallbackEsploraUrls

    val chainHash get() = chain.chainHash

    companion object {
        val DEFAULT = TESTNET
        fun fromName(name: String?): BtcNetwork = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
