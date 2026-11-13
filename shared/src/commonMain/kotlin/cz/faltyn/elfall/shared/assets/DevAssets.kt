package cz.faltyn.elfall.shared.assets

/**
 * Developer testnet asset catalogue (5 chains + FLC/yFLC on Stellar testnet).
 * FLC reference 0.33 USD is display-only — NOT a peg.
 */
const val FLC_REFERENCE_USD = 0.33
const val FLC_REFERENCE_DISCLAIMER_CS =
    "1,00 FLC = 0,33 USD je jen zobrazovací konstanta na TESTNETU. Není to skutečný peg ani nárok na USD."
const val FLC_REFERENCE_DISCLAIMER_EN =
    "1.00 FLC = 0.33 USD is a TESTNET display constant only. NOT a real peg or claim on USD."

data class DevAsset(
    val id: String,
    val symbol: String,
    val networkId: String,
    val kind: String,
    /** Stellar issuer public key once issued; empty until scripts/issue_flc_testnet.py runs. */
    val issuerPublic: String? = null,
    val referenceUsd: Double? = null,
    /** Frozen assets are shown only with the 'zmrazeno' label and are never issued/distributed. */
    val frozen: Boolean = false,
)

/** Czech UI label for frozen assets. */
const val FROZEN_LABEL_CS = "zmrazeno"

/** yFLC is frozen (owner decision 2026-10-13): no issuance, no distribution. */
const val YFLC_FROZEN = true

object DevAssets {
    val catalogue: List<DevAsset> = listOf(
        DevAsset("tBTC", "tBTC", "bitcoin-testnet", "native"),
        DevAsset("XLM", "XLM", "stellar-testnet", "native"),
        DevAsset("sepETH", "ETH", "ethereum-sepolia", "native"),
        DevAsset("devSOL", "SOL", "solana-devnet", "native"),
        DevAsset("amoyMATIC", "MATIC", "polygon-amoy", "native"),
        DevAsset("sBTC", "BTC", "bitcoin-signet", "native"),
        DevAsset("tLTC", "LTC", "litecoin-testnet", "native"),
        DevAsset("FLC", "FLC", "stellar-testnet", "credit_alphanum4", issuerPublic = "GCUMZ6Z7GAYFL2MGSFRG7HXDPL4FRBPZYAYJGTWEEKDI3NJIFUMSRPKT", referenceUsd = FLC_REFERENCE_USD),
        DevAsset("yFLC", "yFLC", "stellar-testnet", "credit_alphanum4", issuerPublic = "GCUMZ6Z7GAYFL2MGSFRG7HXDPL4FRBPZYAYJGTWEEKDI3NJIFUMSRPKT", referenceUsd = FLC_REFERENCE_USD, frozen = true),
    )

    val activeCatalogue: List<DevAsset> get() = catalogue.filter { !it.frozen }

    fun stellarTestnetAssets(issuerPublic: String? = null): List<DevAsset> =
        catalogue.filter { it.networkId == "stellar-testnet" }.map {
            if (it.id == "FLC" || it.id == "yFLC") it.copy(issuerPublic = issuerPublic ?: it.issuerPublic) else it
        }
}
