package cz.faltyn.elfall.shared

import cz.faltyn.elfall.shared.offramp.FiatCurrency
import cz.faltyn.elfall.shared.offramp.MoonPaySellStub
import cz.faltyn.elfall.shared.offramp.OffRampConfig
import cz.faltyn.elfall.shared.offramp.OffRampQuoteRequest
import cz.faltyn.elfall.shared.offramp.OffRampValidation
import cz.faltyn.elfall.shared.offramp.CoinbaseOffRampStub
import cz.faltyn.elfall.shared.offramp.OffRampProviderId
import cz.faltyn.elfall.shared.offramp.defaultOffRampProviders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class OffRampTests {

    @Test fun onlyCzkEurUsdAllowed() {
        assertEquals(listOf(FiatCurrency.CZK, FiatCurrency.EUR, FiatCurrency.USD), FiatCurrency.supported)
        assertEquals(FiatCurrency.CZK, FiatCurrency.fromCode("czk"))
        assertEquals(FiatCurrency.EUR, FiatCurrency.fromCode("EUR"))
        assertEquals(FiatCurrency.USD, FiatCurrency.fromCode("usd"))
        assertNull(FiatCurrency.fromCode("GBP"))
        assertNull(FiatCurrency.fromCode("BTC"))
    }

    @Test fun quoteValidationRejectsOutOfRange() {
        val bad = OffRampQuoteRequest(amountCrypto = 0.0, currency = FiatCurrency.CZK, walletAddress = "tb1qtest")
        assertFailsWith<IllegalArgumentException> { OffRampValidation.validateRequest(bad) }
        val huge = bad.copy(amountCrypto = 100.0)
        assertFailsWith<IllegalArgumentException> { OffRampValidation.validateRequest(huge) }
    }

    @Test fun moonPayStubQuoteAndUrl() {
        runBlocking {
            val stub = MoonPaySellStub(OffRampConfig(btcCzk = 2_000_000.0, feeBps = 150))
            val req = OffRampQuoteRequest(0.01, "BTC", FiatCurrency.CZK, "tb1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu")
            val q = stub.quote(req)
            assertEquals(FiatCurrency.CZK, q.currency)
            assertEquals(2_000_000L, q.fiatGrossMinor)
            assertEquals(30_000L, q.feeMinor)
            assertEquals(1_970_000L, q.fiatNetMinor)
            val flow = stub.buildHostedFlowUrl(req, q)
            assertTrue(flow.url.startsWith("https://sell.moonpay.com?"))
            assertTrue(flow.url.contains("quoteCurrencyCode=czk"))
            assertTrue(flow.url.contains("apiKey=pk_test_REPLACE_ME"))
            assertTrue(!flow.url.contains("cardNumber"))
        }
    }

    @Test fun coinbaseStubUsesCardCashout() {
        runBlocking {
            val stub = CoinbaseOffRampStub()
            val req = OffRampQuoteRequest(0.05, "BTC", FiatCurrency.EUR, "tb1qdest")
            val q = stub.quote(req)
            assertEquals(FiatCurrency.EUR, q.currency)
            val flow = stub.buildHostedFlowUrl(req, q)
            assertTrue(flow.url.startsWith("https://pay.coinbase.com/"))
            assertTrue(flow.url.contains("fiatCurrency=EUR"))
            assertNotNull(OffRampValidation.formatFiat(q.fiatNetMinor, FiatCurrency.EUR))
        }
    }

    @Test fun onlyMoonPayAndCoinbaseActive() {
        assertEquals(listOf("MOONPAY", "COINBASE"), defaultOffRampProviders().map { it.id.name })
        assertEquals(listOf("ANYCOIN", "MOONPAY", "COINBASE"), OffRampProviderId.entries.map { it.name })
    }
}
