package cz.faltyn.elfall.android

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import cz.faltyn.elfall.shared.SeedStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Mnemonic encrypted with an AES-256-GCM Android Keystore key that is **bound to user authentication**
 * (biometric or device credential, valid [AUTH_VALIDITY_SECONDS] after a successful BiometricPrompt).
 * Encrypt/decrypt throws UserNotAuthenticatedException unless BiometricGate succeeded just before.
 * If the device has no secure lock screen, falls back to an unbound key and reports it via [isAuthBound].
 * No API exports the seed or private keys.
 */
class KeystoreSeedStore(context: Context) : SeedStore {
    private val prefs = context.getSharedPreferences("elfall_secure_v2", Context.MODE_PRIVATE)
    private val alias = "elfall_seed_key_v2"

    val isAuthBound: Boolean get() = prefs.getBoolean("auth_bound", false)

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        return try {
            generate(authBound = true).also { prefs.edit().putBoolean("auth_bound", true).apply() }
        } catch (e: Exception) {
            // e.g. no screen lock set → cannot create an auth-bound key
            generate(authBound = false).also { prefs.edit().putBoolean("auth_bound", false).apply() }
        }
    }

    private fun generate(authBound: Boolean): SecretKey {
        val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (authBound) {
            b.setUserAuthenticationRequired(true)
            b.setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                b.setUserAuthenticationParameters(
                    AUTH_VALIDITY_SECONDS,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
            } else {
                @Suppress("DEPRECATION")
                b.setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
            }
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(b.build())
        return gen.generateKey()
    }

    override fun hasSeed() = prefs.contains("seed")

    override fun saveMnemonic(words: List<String>) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = c.doFinal(words.joinToString(" ").encodeToByteArray())
        prefs.edit()
            .putString("seed", Base64.encodeToString(ct, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .apply()
    }

    override fun loadMnemonic(): List<String>? {
        val ct = prefs.getString("seed", null) ?: return null
        val iv = prefs.getString("iv", null) ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return c.doFinal(Base64.decode(ct, Base64.NO_WRAP)).decodeToString().split(" ")
    }

    override fun wipe() {
        prefs.edit().clear().apply()
        keyStore().deleteEntry(alias)
    }

    // SR-04: výplatní adresa a Stellar účet šifrované + autentizované (libsodium XChaCha20-Poly1305, AD = název pole).
    private val vault: LocalVault by lazy {
        val ls = com.goterl.lazysodium.LazySodiumAndroid(com.goterl.lazysodium.SodiumAndroid())
        val wrapper = KeystoreKeyWrapper("elfall_vault_wrap_v1")
        val stored = prefs.getString("vault_key", null)
        val key = stored?.let { wrapper.unwrap(Base64.decode(it, Base64.NO_WRAP)) } ?: SodiumVault.newDataKey(ls).also {
            prefs.edit().putString("vault_key", Base64.encodeToString(wrapper.wrap(it), Base64.NO_WRAP)).apply()
        }
        // Rust jádro jen při BuildConfig.RUST_CORE (výchozí vypnuto); formát sv1: je shodný → bez migrace
        if (RustCore.enabled()) RustCoreVault(key) else SodiumVault(ls, key)
    }

    private fun putSealed(field: String, value: String?) {
        if (value == null) prefs.edit().remove(field).apply()
        else prefs.edit().putString(field, vault.seal(field, value)).apply()
    }

    /** Načte a ověří; starý nešifrovaný záznam jednorázově převede. Podvržená data → null (adresu nutno zadat znovu). */
    private fun getSealed(field: String): String? {
        val v = prefs.getString(field, null) ?: return null
        if (!v.startsWith(SodiumVault.PREFIX)) { putSealed(field, v); return v }
        return vault.open(field, v)
    }

    override fun savePayoutAddress(address: String?) = putSealed("payout_address", address)
    override fun loadPayoutAddress(): String? = getSealed("payout_address")

    fun saveAddressIndex(i: Long) = prefs.edit().putLong("addr_index", i).apply()
    fun loadAddressIndex(): Long = prefs.getLong("addr_index", 0)
    fun saveStellarAccount(a: String?) = putSealed("stellar_account", a)
    fun loadStellarAccount(): String? = getSealed("stellar_account")

    companion object { const val AUTH_VALIDITY_SECONDS = 30 }
}
