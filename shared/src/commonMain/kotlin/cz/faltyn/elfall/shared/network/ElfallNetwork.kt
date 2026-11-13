package cz.faltyn.elfall.shared.network

/**
 * ELFALL network mode. Default = TESTNET. Mainnet is a placeholder until the rollout checklist
 * in docs/elfall-network.md is complete.
 */
enum class ElfallNetwork(val id: String, val displayName: String, val isTestnet: Boolean) {
    TESTNET("testnet", "Testnet (vývoj)", true),
    MAINNET("mainnet", "Mainnet (zatím zamčeno)", false);

    companion object {
        val DEFAULT = TESTNET
        fun fromId(id: String?): ElfallNetwork =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DEFAULT
    }
}
