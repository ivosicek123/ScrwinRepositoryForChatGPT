package cz.faltyn.elfall.shared

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Utxo(val txid: String, val vout: Long, val value: Long, val status: UtxoStatus = UtxoStatus())

@Serializable
data class UtxoStatus(val confirmed: Boolean = false, @SerialName("block_height") val blockHeight: Long? = null)

@Serializable
data class FeeRecommendation(
    val fastestFee: Long,
    val halfHourFee: Long,
    val hourFee: Long,
    val economyFee: Long = 1,
    val minimumFee: Long = 1,
)

/** Non-retryable failure (e.g. HTTP 4xx on broadcast = invalid tx): do not try other endpoints. */
class EsploraRejected(message: String) : Exception(message)

/**
 * Minimal, key-free failover client for public Esplora REST APIs.
 * Endpoints are tried in order (mempool.space first, then Blockstream Esplora). Network errors and
 * HTTP 5xx/429 fail over to the next endpoint; HTTP 4xx on broadcast is final (tx rejected).
 */
class EsploraClient(
    private val baseUrl: String,
    private val http: HttpClient = defaultHttpClient(),
    fallbackUrls: List<String> = emptyList(),
) {
    val endpoints: List<String> = (listOf(baseUrl) + fallbackUrls).map { it.trimEnd('/') }.distinct()
    internal val base get() = baseUrl
    internal val httpClient get() = http

    internal suspend fun <T> withFailover(block: suspend (String) -> T): T {
        var last: Throwable? = null
        for (b in endpoints) {
            try {
                return block(b)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EsploraRejected) {
                throw e
            } catch (e: Throwable) {
                last = e
            }
        }
        throw IllegalStateException("All Esplora endpoints failed: ${last?.message}", last)
    }

    private suspend fun ok(resp: HttpResponse): HttpResponse {
        if (!resp.status.isSuccess()) error("HTTP ${resp.status.value} from ${resp.call.request.url}")
        return resp
    }

    internal suspend inline fun <reified T> getJson(path: String): T =
        withFailover { b -> okPublic(httpClient.get("$b$path")).body<T>() }

    internal suspend fun getText(path: String): String = withFailover { b -> ok(http.get("$b$path")).bodyAsText() }

    @PublishedApi internal suspend fun okPublic(resp: HttpResponse): HttpResponse = ok(resp)

    suspend fun utxos(address: String): List<Utxo> = getJson("/address/$address/utxo")

    suspend fun balanceSats(address: String): Long = utxos(address).sumOf { it.value }

    /** mempool.space `/v1/fees/recommended`; on Blockstream falls back to `/fee-estimates` (target blocks -> sat/vB). */
    suspend fun recommendedFees(): FeeRecommendation = withFailover { b ->
        runCatching { ok(http.get("$b/v1/fees/recommended")).body<FeeRecommendation>() }.getOrElse { first ->
            if (first is CancellationException) throw first
            val est: Map<String, Double> = ok(http.get("$b/fee-estimates")).body()
            feesFromEstimates(est)
        }
    }

    /** Broadcasts a raw transaction hex; returns the txid. */
    suspend fun broadcast(txHex: String): String = withFailover { b ->
        val resp = http.post("$b/tx") { setBody(txHex) }
        val text = resp.bodyAsText()
        when {
            resp.status.isSuccess() -> text.trim()
            resp.status.value in 400..499 && resp.status.value != 429 -> throw EsploraRejected("Broadcast rejected: $text")
            else -> error("Broadcast failed (${resp.status.value}): $text")
        }
    }

    companion object {
        fun defaultHttpClient() = HttpClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        fun forNetwork(network: BtcNetwork, http: HttpClient = defaultHttpClient()) =
            EsploraClient(network.esploraUrl, http, network.fallbackEsploraUrls)

        internal fun feesFromEstimates(est: Map<String, Double>): FeeRecommendation {
            val byTarget = est.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.sortedBy { it.first }
            fun at(target: Int): Long {
                val v = byTarget.firstOrNull { it.first >= target }?.second ?: byTarget.lastOrNull()?.second ?: 1.0
                return kotlin.math.ceil(v).toLong().coerceAtLeast(1)
            }
            return FeeRecommendation(fastestFee = at(1), halfHourFee = at(3), hourFee = at(6), economyFee = at(144), minimumFee = 1)
        }
    }
}
