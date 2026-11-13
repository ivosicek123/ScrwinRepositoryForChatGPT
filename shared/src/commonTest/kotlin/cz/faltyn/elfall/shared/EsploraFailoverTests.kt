package cz.faltyn.elfall.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EsploraFailoverTests {
    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    private val calls = mutableListOf<String>()

    private fun client(handler: (String) -> Pair<HttpStatusCode, String>) = HttpClient(MockEngine { req ->
        val url = req.url.toString()
        calls += url
        val (st, body) = handler(url)
        respond(body, st, json)
    }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    @Test fun everyNetworkHasBlockstreamFallback() {
        BtcNetwork.entries.forEach {
            assertTrue(it.esploraEndpoints.first().startsWith("https://mempool.space"))
            assertTrue(it.fallbackEsploraUrls.any { u -> u.startsWith("https://blockstream.info") })
        }
    }

    @Test fun failsOverToBlockstreamOn5xx() = runTest {
        val c = EsploraClient.forNetwork(BtcNetwork.TESTNET, client { url ->
            if (url.contains("mempool.space")) HttpStatusCode.ServiceUnavailable to "down"
            else HttpStatusCode.OK to """[{"txid":"aa","vout":0,"value":1500}]"""
        })
        assertEquals(1500, c.balanceSats("tb1qxyz"))
        assertTrue(calls.last().startsWith("https://blockstream.info/testnet/api"))
    }

    @Test fun feesFallBackToFeeEstimates() = runTest {
        val c = EsploraClient.forNetwork(BtcNetwork.MAINNET, client { url ->
            when {
                url.contains("mempool.space") -> HttpStatusCode.BadGateway to "x"
                url.endsWith("/fee-estimates") -> HttpStatusCode.OK to """{"1":12.3,"3":8.0,"6":4.2,"144":1.1}"""
                else -> HttpStatusCode.NotFound to "nf"
            }
        })
        val f = c.recommendedFees()
        assertEquals(13, f.fastestFee); assertEquals(8, f.halfHourFee); assertEquals(5, f.hourFee); assertEquals(2, f.economyFee)
    }

    @Test fun rejectedBroadcastIsNotRetried() = runTest {
        val c = EsploraClient.forNetwork(BtcNetwork.SIGNET, client { HttpStatusCode.BadRequest to "bad-txns" })
        assertFailsWith<EsploraRejected> { c.broadcast("00") }
        assertEquals(1, calls.size)
    }
}
