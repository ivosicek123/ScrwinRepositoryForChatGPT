package cz.faltyn.elfall.shared

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class EsploraTx(
    val txid: String,
    val status: EsploraTxStatus = EsploraTxStatus(),
    val vin: List<EsploraVin> = emptyList(),
    val vout: List<EsploraVout> = emptyList(),
    val fee: Long = 0,
)
@Serializable data class EsploraTxStatus(
    val confirmed: Boolean = false,
    @SerialName("block_height") val blockHeight: Long? = null,
    @SerialName("block_time") val blockTime: Long? = null,
)
@Serializable data class EsploraVin(val prevout: EsploraVout? = null)
@Serializable data class EsploraVout(
    @SerialName("scriptpubkey_address") val address: String? = null,
    val value: Long = 0,
)

/** One line in the history list (positive = received, negative = sent). */
data class HistoryItem(
    val id: String,
    val netSats: Long,
    val confirmations: Long,
    val timeEpochSec: Long?,
    val explorerUrl: String,
    val asset: String = "BTC",
    val amountText: String = netSats.satsToBtcString(),
)

object HistoryMapper {
    fun fromEsplora(txs: List<EsploraTx>, own: Set<String>, tipHeight: Long, explorerBase: String): List<HistoryItem> =
        txs.map { tx ->
            val received = tx.vout.filter { it.address in own }.sumOf { it.value }
            val spent = tx.vin.mapNotNull { it.prevout }.filter { it.address in own }.sumOf { it.value }
            val conf = tx.status.blockHeight?.let { tipHeight - it + 1 } ?: 0
            HistoryItem(tx.txid, received - spent, conf.coerceAtLeast(0), tx.status.blockTime, "$explorerBase/tx/${tx.txid}")
        }
}

suspend fun EsploraClient.addressTxs(address: String): List<EsploraTx> = getJson("/address/$address/txs")
suspend fun EsploraClient.tipHeight(): Long = getText("/blocks/tip/height").trim().toLong()

@Serializable data class HorizonPayments(@SerialName("_embedded") val embedded: HorizonEmbedded = HorizonEmbedded())
@Serializable data class HorizonEmbedded(val records: List<HorizonPayment> = emptyList())
@Serializable data class HorizonPayment(
    val id: String,
    val type: String = "",
    val from: String? = null,
    val to: String? = null,
    val amount: String? = null,
    @SerialName("asset_type") val assetType: String? = null,
    @SerialName("asset_code") val assetCode: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("transaction_hash") val txHash: String = "",
)

/** Read-only Stellar Horizon client (testnet by default). */
class HorizonClient(
    val baseUrl: String = "https://horizon-testnet.stellar.org",
    private val http: HttpClient = EsploraClient.defaultHttpClient(),
    val explorerBase: String = "https://stellar.expert/explorer/testnet",
) {
    suspend fun payments(account: String, limit: Int = 20): List<HorizonPayment> =
        http.get("$baseUrl/accounts/$account/payments?order=desc&limit=$limit").body<HorizonPayments>().embedded.records

    fun toHistory(account: String, p: List<HorizonPayment>): List<HistoryItem> = p.filter { it.amount != null }.map {
        val asset = if (it.assetType == "native") "XLM" else (it.assetCode ?: "?")
        val sign = if (it.to == account) "+" else "-"
        HistoryItem(it.id, 0, 1, null, "$explorerBase/tx/${it.txHash}", asset, "$sign${it.amount}")
    }
}
