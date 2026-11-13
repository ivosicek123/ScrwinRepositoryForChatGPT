package cz.faltyn.elfall.shared

import cz.faltyn.elfall.shared.assets.DevAssets
import cz.faltyn.elfall.shared.assets.FLC_REFERENCE_USD
import cz.faltyn.elfall.shared.network.ElfallNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DevAssetsTests {
    @Test fun fiveChainsPlusFlc() {
        val ids = DevAssets.catalogue.map { it.id }
        assertTrue(ids.containsAll(listOf("tBTC", "XLM", "sepETH", "devSOL", "amoyMATIC", "FLC", "yFLC")))
        assertEquals(0.33, FLC_REFERENCE_USD)
    }

    @Test fun yflcIsFrozen() {
        val y = DevAssets.catalogue.first { it.id == "yFLC" }
        assertTrue(y.frozen)
        assertTrue(DevAssets.activeCatalogue.none { it.id == "yFLC" })
        assertTrue(!DevAssets.catalogue.first { it.id == "FLC" }.frozen)
    }

    @Test fun defaultNetworkIsTestnet() {
        assertEquals(ElfallNetwork.TESTNET, ElfallNetwork.DEFAULT)
        assertTrue(ElfallNetwork.TESTNET.isTestnet)
        assertTrue(!ElfallNetwork.MAINNET.isTestnet)
    }

    @Test fun stellarAssetsKeepHardcodedIssuerWhenNullPassed() {
        val hard = DevAssets.catalogue.first { it.id == "FLC" }.issuerPublic
        val flc = DevAssets.stellarTestnetAssets(null).filter { it.id == "FLC" || it.id == "yFLC" }
        assertEquals(2, flc.size)
        flc.forEach { assertEquals(hard, it.issuerPublic) }
        val override = DevAssets.stellarTestnetAssets("GOVERRIDE").first { it.id == "FLC" }
        assertEquals("GOVERRIDE", override.issuerPublic)
    }
}
