package cz.faltyn.elfall.shared

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class MempoolStats(val count: Long = 0, val vsize: Long = 0, @SerialName("total_fee") val totalFee: Long = 0)
@Serializable data class BlockSummary(val id: String, val height: Long, val timestamp: Long = 0, @SerialName("tx_count") val txCount: Long = 0, val size: Long = 0)
@Serializable data class BtcPrices(@SerialName("USD") val usd: Double? = null, @SerialName("EUR") val eur: Double? = null, @SerialName("CZK") val czk: Double? = null)

data class NetworkSnapshot(
    val network: BtcNetwork,
    val fees: FeeRecommendation,
    val mempool: MempoolStats,
    val tipHeight: Long,
    val recentBlocks: List<BlockSummary>,
    /** Mainnet market price (shown for both modes; testnet coins have no value). */
    val prices: BtcPrices?,
)

/**
 * Read-only mempool.space network info ("Síť / Network" screen). Display only — no signing.
 * Docs: https://mempool.space/docs/api/rest
 */
class NetworkInfoClient(private val http: HttpClient = EsploraClient.defaultHttpClient()) {
    suspend fun snapshot(network: BtcNetwork): NetworkSnapshot {
        val b = network.esploraUrl
        val fees: FeeRecommendation = http.get("$b/v1/fees/recommended").body()
        val mempool: MempoolStats = http.get("$b/mempool").body()
        val blocks: List<BlockSummary> = http.get("$b/v1/blocks").body()
        val tip = blocks.maxOfOrNull { it.height } ?: http.get("$b/blocks/tip/height").body<String>().trim().toLong()
        val prices = runCatching { http.get("${BtcNetwork.MAINNET.esploraUrl}/v1/prices").body<BtcPrices>() }.getOrNull()
        return NetworkSnapshot(network, fees, mempool, tip, blocks.take(6), prices)
    }
}
