package cz.faltyn.elfall.shared

import cz.faltyn.elfall.shared.offramp.AnycoinProvider
import cz.faltyn.elfall.shared.offramp.FiatCurrency
import cz.faltyn.elfall.shared.offramp.OffRampQuoteRequest
import cz.faltyn.elfall.shared.offramp.defaultOffRampProviders
import cz.faltyn.elfall.shared.stellar.FreighterConnector
import cz.faltyn.elfall.shared.stellar.LobstrConnector
import cz.faltyn.elfall.shared.stellar.Sep7
import cz.faltyn.elfall.shared.google.GoogleWalletHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class Sep7Tests {
    private val dest = "GCALNQQBXAPZ2WIRSDDBMSTAKCUH5SG6U76YBFLQLIXJTF7FE5AX7AOO"

    @Test fun payUriMatchesSep7ExampleShape() {
        val uri = Sep7.payUri(
            Sep7.PayRequest(
                destination = dest,
                amount = "120.1234567",
                memo = "skdjfasf",
                memoType = "MEMO_TEXT",
                msg = "pay me with lumens",
            )
        )
        assertTrue(uri.startsWith("web+stellar:pay?"))
        assertTrue(uri.contains("destination=$dest"))
        assertTrue(uri.contains("amount=120.1234567"))
        assertTrue(uri.contains("memo=skdjfasf"))
        assertTrue(uri.contains("memo_type=MEMO_TEXT"))
        assertTrue(uri.contains("msg=pay%20me%20with%20lumens"))
    }

    @Test fun encodeSpacesAndReserved() {
        assertEquals("pay%20me", Sep7.encode("pay me"))
        assertEquals("a%26b%3Dc", Sep7.encode("a&b=c"))
    }

    @Test fun rejectBadDestination() {
        assertFailsWith<IllegalArgumentException> {
            Sep7.payUri(Sep7.PayRequest(destination = "not-an-account"))
        }
    }

    @Test fun txUriRequiresXdr() {
        val uri = Sep7.txUri(Sep7.TxRequest(xdrBase64 = "AAAAAgAAAAA="))
        assertTrue(uri.startsWith("web+stellar:tx?"))
        assertTrue(uri.contains("xdr=AAAAAgAAAAA%3D") || uri.contains("xdr=AAAAAgAAAAA="))
    }

    @Test fun lobstrAndFreighterWrapSep7() {
        val pay = Sep7.PayRequest(destination = dest, amount = "1")
        val lobstr = LobstrConnector().payIntent(pay)
        val freighter = FreighterConnector().payIntent(pay)
        assertEquals(lobstr.uri, freighter.uri)
        assertTrue(lobstr.uri.startsWith("web+stellar:pay?"))
        assertEquals("com.lobstr.client", lobstr.androidPackage)
    }

    @Test fun activeProvidersAreMoonPayAndCoinbase() {
        val ids = defaultOffRampProviders().map { it.id.name }
        assertEquals(listOf("MOONPAY", "COINBASE"), ids)
    }

    @Test fun anycoinHostedFlow() {
        runBlocking {
            val p = AnycoinProvider()
            val req = OffRampQuoteRequest(0.01, "BTC", FiatCurrency.CZK, "tb1qtest")
            val q = p.quote(req)
            val flow = p.buildHostedFlowUrl(req, q)
            assertTrue(flow.url.startsWith("https://www.anycoin.cz/"))
            assertEquals("cz.anycoin.mobile", flow.androidPackage)
            assertTrue(q.notes.isNotBlank())
        }
    }

    @Test fun googleWalletNotPayoutDestination() {
        assertEquals(
            GoogleWalletHelper.Role.NOT_A_PAYOUT_DESTINATION,
            GoogleWalletHelper.cashOutGuidance().role,
        )
    }
}
