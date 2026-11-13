package cz.faltyn.elfall.shared

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import kotlin.random.Random

/**
 * Single-account BIP84 (native SegWit) wallet derived from a BIP39 mnemonic.
 * Keys live only in memory while unlocked; nothing is exported in plaintext.
 */
class WalletCore private constructor(private val seed: ByteArray, val network: BtcNetwork) {

    private val account = DeterministicWallet.generate(seed)
        .derivePrivateKey(KeyPath("m/84'/${network.coinType}'/0'"))

    private fun receiveKey(index: Long = 0): DeterministicWallet.ExtendedPrivateKey =
        account.derivePrivateKey(listOf(0L, index))

    internal fun receivePrivateKey(index: Long = 0): PrivateKey = receiveKey(index).privateKey
    fun receivePublicKey(index: Long = 0): PublicKey = receiveKey(index).publicKey

    /** First receive address (m/84'/c'/0'/0/0). */
    fun receiveAddress(index: Long = 0): String =
        Bitcoin.computeBIP84Address(receivePublicKey(index), network.chainHash)

    /** First [count] receive addresses (BIP84 external chain), for gap-limit scanning and "new address". */
    fun receiveAddresses(count: Int, start: Long = 0): List<String> = (start until start + count).map { receiveAddress(it) }

    /** Watch-only account xpub (safe to share, e.g. with the read-only elfall-mcp server). */
    fun accountXpub(): String = account.extendedPublicKey.encode(
        if (network == BtcNetwork.MAINNET) DeterministicWallet.zpub else DeterministicWallet.vpub,
    )

    companion object {
        fun newMnemonic(random: Random = secureRandom()): List<String> =
            MnemonicCode.toMnemonics(random.nextBytes(16)) // 12 words

        /** Swift-friendly: 12-word mnemonic from the platform CSPRNG. */
        fun generateMnemonic(): List<String> = newMnemonic()

        fun fromMnemonic(words: List<String>, network: BtcNetwork, passphrase: String = ""): WalletCore {
            MnemonicCode.validate(words)
            return WalletCore(MnemonicCode.toSeed(words, passphrase), network)
        }
    }
}

/** Platform CSPRNG (SecureRandom on Android, SecRandomCopyBytes on iOS). */
expect fun secureRandom(): Random
