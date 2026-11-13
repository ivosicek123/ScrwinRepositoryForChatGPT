package cz.faltyn.elfall.shared

/**
 * Platform secure storage for the BIP39 mnemonic and user preferences.
 * Android: AES-GCM key in Android Keystore. iOS: Keychain (TODO in iosMain).
 * The mnemonic is never exported in plaintext by the app (no "show private key" API).
 */
interface SeedStore {
    fun hasSeed(): Boolean
    fun saveMnemonic(words: List<String>)
    fun loadMnemonic(): List<String>?
    fun wipe()

    fun savePayoutAddress(address: String?)
    fun loadPayoutAddress(): String?
}
