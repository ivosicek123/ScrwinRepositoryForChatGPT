package cz.faltyn.elfall.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class WalletTests {
    // BIP84 test vector (https://github.com/bitcoin/bips/blob/master/bip-0084.mediawiki)
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")

    @Test fun bip84MainnetVector() {
        val w = WalletCore.fromMnemonic(words, BtcNetwork.MAINNET)
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", w.receiveAddress())
    }

    @Test fun testnetIsDefault() = assertEquals(BtcNetwork.TESTNET, BtcNetwork.DEFAULT)

    @Test fun testnetAddressIsTb() = assertTrue(WalletCore.fromMnemonic(words, BtcNetwork.TESTNET).receiveAddress().startsWith("tb1q"))

    @Test fun addressValidation() {
        assertTrue(AddressValidator.isValid("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", BtcNetwork.MAINNET))
        assertFalse(AddressValidator.isValid("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", BtcNetwork.TESTNET))
        assertFalse(AddressValidator.isValid("not-an-address", BtcNetwork.TESTNET))
        assertFalse(AddressValidator.isValid("1234567890", BtcNetwork.MAINNET))
    }

    @Test fun parseAmounts() {
        assertEquals(150_000L, parseBtcToSats("0.0015"))
        assertEquals(100_000_000L, parseBtcToSats("1"))
        assertEquals(null, parseBtcToSats("0.000000001"))
        assertEquals("0.00150000", 150_000L.satsToBtcString())
    }

    @Test fun coinSelectionAndFee() {
        val utxos = listOf(Utxo("a".repeat(64), 0, 50_000), Utxo("b".repeat(64), 1, 30_000))
        val p = WithdrawService.select("tb1qdest", 40_000, 2, utxos)
        assertEquals(1, p.inputs.size)
        assertEquals(WithdrawService.estimateVbytes(1, 2) * 2, p.feeSats)
        assertEquals(50_000 - 40_000 - p.feeSats, p.changeSats)
        assertFailsWith<IllegalArgumentException> { WithdrawService.select("tb1qdest", 90_000, 2, utxos) }
    }

    @Test fun signsTransaction() {
        val w = WalletCore.fromMnemonic(words, BtcNetwork.TESTNET)
        val svc = WithdrawService(w, EsploraClient(BtcNetwork.TESTNET.esploraUrl))
        val p = WithdrawService.select(w.receiveAddress(), 10_000, 1, listOf(Utxo("c".repeat(64), 0, 20_000)))
        val hex = svc.buildSignedTx(p)
        assertTrue(hex.startsWith("020000000001")) // v2 segwit tx
    }
}
