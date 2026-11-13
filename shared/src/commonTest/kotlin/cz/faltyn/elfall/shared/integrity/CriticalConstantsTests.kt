package cz.faltyn.elfall.shared.integrity

import cz.faltyn.elfall.shared.assets.DevAssets
import cz.faltyn.elfall.shared.assets.FLC_REFERENCE_USD
import cz.faltyn.elfall.shared.offramp.CoinbaseOffRampStub
import cz.faltyn.elfall.shared.offramp.MoonPaySellStub
import cz.faltyn.elfall.shared.offramp.AnycoinProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import cz.faltyn.elfall.shared.offramp.FiatCurrency
import cz.faltyn.elfall.shared.offramp.OffRampQuoteRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CriticalConstantsTests {
    @Test fun assetCodesIssuerAndPriceArePinned() {
        val flc = DevAssets.catalogue.first { it.id == "FLC" }
        val yflc = DevAssets.catalogue.first { it.id == "yFLC" }
        assertEquals(CriticalConstants.FLC_ASSET_CODE, flc.symbol)
        assertEquals(CriticalConstants.YFLC_ASSET_CODE, yflc.symbol)
        assertEquals("GCUMZ6Z7GAYFL2MGSFRG7HXDPL4FRBPZYAYJGTWEEKDI3NJIFUMSRPKT", flc.issuerPublic)
        assertEquals(CriticalConstants.FLC_ISSUER_PUBLIC, flc.issuerPublic)
        assertEquals(CriticalConstants.FLC_ISSUER_PUBLIC, yflc.issuerPublic)
        assertEquals(0.33, FLC_REFERENCE_USD)
        assertEquals(CriticalConstants.FLC_REFERENCE_USD, flc.referenceUsd)
        assertTrue(yflc.frozen)
        assertEquals(56, flc.issuerPublic!!.length); assertTrue(flc.issuerPublic!!.startsWith("G"))
    }

    @Test fun authorAttributionPinned() {
        assertEquals("Ivo Faltýn — autor, vývojář a vlastník projektu ELFALL / Fallcoin / Eleanor", CriticalConstants.AUTHOR_ATTRIBUTION)
        assertEquals("ivosicek123", CriticalConstants.AUTHOR_GITHUB)
    }

    @Test fun offRampUrlsUsePinnedPrefixes() {
        val p = CriticalConstants.OFFRAMP_URL_PREFIXES
        assertEquals(3, p.size)
        assertTrue(p.all { it.startsWith("https://") })
        runBlocking {
            val req = OffRampQuoteRequest(0.01, "BTC", FiatCurrency.EUR, "tb1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu")
            listOf(MoonPaySellStub(), CoinbaseOffRampStub(), AnycoinProvider()).forEach { prov ->
                val url = prov.buildHostedFlowUrl(req, prov.quote(req)).url
                assertTrue(p.any { url.startsWith(it) }, "Neočekávaná URL poskytovatele: $url")
            }
        }
    }

    @Test fun tomlParserFindsIssuer() {
        val toml = """
            ACCOUNTS = ["GX"]
            [[CURRENCIES]]
            code = "XYZ"
            issuer = "GOTHER"
            [[CURRENCIES]]
            code = "FLC" # komentář
            issuer = "${CriticalConstants.FLC_ISSUER_PUBLIC}"
        """.trimIndent()
        assertEquals(listOf(CriticalConstants.FLC_ISSUER_PUBLIC), StellarTomlVerifier.currencyIssuers(toml, "FLC"))
    }

    private fun client(homeDomain: String, tomlIssuer: String) = HttpClient(MockEngine { req ->
        if (req.url.host.startsWith("horizon")) respond("""{"id":"x","home_domain":"$homeDomain"}""", HttpStatusCode.OK)
        else respond("[[CURRENCIES]]\ncode = \"FLC\"\nissuer = \"$tomlIssuer\"\n", HttpStatusCode.OK)
    })

    @Test fun runtimeCheckOk() = runTest {
        assertIs<IssuerCheck.Ok>(StellarTomlVerifier(client("elfall.pages.dev", CriticalConstants.FLC_ISSUER_PUBLIC)).verify())
    }

    @Test fun runtimeCheckDetectsSwappedIssuer() = runTest {
        assertIs<IssuerCheck.Mismatch>(StellarTomlVerifier(client("elfall.pages.dev", "GATTACKER")).verify())
    }

    @Test fun runtimeCheckDetectsSwappedDomain() = runTest {
        assertIs<IssuerCheck.Mismatch>(StellarTomlVerifier(client("evil.example", CriticalConstants.FLC_ISSUER_PUBLIC)).verify())
    }
}
