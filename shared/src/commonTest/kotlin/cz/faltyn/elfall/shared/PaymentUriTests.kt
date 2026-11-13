package cz.faltyn.elfall.shared

import kotlin.test.*

class PaymentUriTests {
    private val tb = "tb1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"

    @Test fun bareAddress() = assertEquals(ParsedPayment.Bitcoin(tb), Bip21.parse(tb))

    @Test fun bip21Full() {
        val p = Bip21.parse("bitcoin:$tb?amount=0.0015&label=Ivo%20F&message=dik")!!
        assertEquals(tb, p.address); assertEquals(150_000L, p.amountSats)
        assertEquals("Ivo F", p.label); assertEquals("dik", p.message)
    }

    @Test fun bip21UppercaseScheme() = assertEquals(tb, Bip21.parse("BITCOIN:$tb")!!.address)

    @Test fun bip21RejectsUnknownRequired() = assertNull(Bip21.parse("bitcoin:$tb?req-somethingnew=1"))

    @Test fun bip21RejectsBadAmount() = assertNull(Bip21.parse("bitcoin:$tb?amount=abc"))

    @Test fun bip21Build() {
        assertEquals("bitcoin:$tb?amount=0.0015", Bip21.build(tb, 150_000))
        assertEquals("bitcoin:$tb", Bip21.build(tb))
    }

    @Test fun sep7AndStellarAccount() {
        val g = "GCALNQQBXAPZ2WIRSDDBMSTAKCUH5SG6U76YBFLQLIXJTF7FE5AX7AOO"
        val p = PaymentUriParser.parse("web+stellar:pay?destination=$g&amount=12.5&asset_code=FLC") as ParsedPayment.Stellar
        assertEquals(g, p.destination); assertEquals("12.5", p.amount); assertEquals("FLC", p.assetCode)
        assertEquals(ParsedPayment.Stellar(g), PaymentUriParser.parse(g))
    }

    @Test fun multipleReceiveAddresses() {
        val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
        val w = WalletCore.fromMnemonic(words, BtcNetwork.MAINNET)
        val a = w.receiveAddresses(3)
        assertEquals(3, a.toSet().size)
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", a[0])
        assertEquals("bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g", a[1]) // BIP84 vector m/84'/0'/0'/0/1
    }

    @Test fun historyMapping() {
        val own = setOf(tb)
        val txs = listOf(
            EsploraTx("aa", EsploraTxStatus(true, 100, 1_700_000_000), vout = listOf(EsploraVout(tb, 5000))),
            EsploraTx("bb", EsploraTxStatus(false), vin = listOf(EsploraVin(EsploraVout(tb, 5000))), vout = listOf(EsploraVout("other", 4000))),
        )
        val h = HistoryMapper.fromEsplora(txs, own, tipHeight = 105, explorerBase = "https://mempool.space/testnet")
        assertEquals(5000L, h[0].netSats); assertEquals(6L, h[0].confirmations)
        assertEquals(-5000L, h[1].netSats); assertEquals(0L, h[1].confirmations)
        assertEquals("https://mempool.space/testnet/tx/aa", h[0].explorerUrl)
    }
}

class NetworkInfoTests {
    @Test fun parsesMempoolJson() {
        val j = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val m = j.decodeFromString<MempoolStats>("""{"count":1234,"vsize":567890,"total_fee":1000,"fee_histogram":[]}""")
        assertEquals(1234L, m.count)
        val b = j.decodeFromString<List<BlockSummary>>("""[{"id":"00ab","height":4200000,"timestamp":1,"tx_count":12,"size":999,"extras":{}}]""")
        assertEquals(4200000L, b[0].height)
        val p = j.decodeFromString<BtcPrices>("""{"time":1,"USD":85000,"EUR":80000,"GBP":1}""")
        assertEquals(85000.0, p.usd)
    }
}
