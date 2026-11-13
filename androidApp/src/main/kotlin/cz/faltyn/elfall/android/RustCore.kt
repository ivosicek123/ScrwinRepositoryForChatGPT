package cz.faltyn.elfall.android

/**
 * Rust jádro (core/, libsodium uvnitř) přes UniFFI. Zapíná se jen buildem s
 * `-Pelfall.rustCore=true` (BuildConfig.RUST_CORE, výchozí false). Když knihovna chybí
 * nebo se nenačte, aplikace zůstává na původní cestě (lazysodium) – žádný pád.
 */
object RustCore {
    val available: Boolean by lazy {
        runCatching { uniffi.elfall_core.signingInput(ByteArray(0)); true }.getOrDefault(false)
    }
    fun enabled(flag: Boolean = BuildConfig.RUST_CORE): Boolean = flag && available
}

/** Společné rozhraní lokálního vaultu – obě implementace mají identický formát `sv1:`. */
interface LocalVault {
    fun seal(field: String, plaintext: String): String
    fun open(field: String, sealed: String): String?
}

class RustCoreVault(private val key: ByteArray) : LocalVault {
    init { require(key.size == 32) { "klíč musí mít 32 B" } }
    override fun seal(field: String, plaintext: String): String = uniffi.elfall_core.vaultSeal(key, field, plaintext)
    override fun open(field: String, sealed: String): String? =
        try { uniffi.elfall_core.vaultOpen(key, field, sealed) } catch (_: uniffi.elfall_core.CoreException) { null }
}

/**
 * Vstup pro podpis Ed25519 (modul 1 migrace): `"ELFALL-SIG-v1\0" ‖ len_be32 ‖ msg`, msg ≤ 65 536 B.
 * Specifikace dokázaná ve SPARK (proofs/spark/). Kotlin implementace je záložní cesta při vypnutém
 * příznaku; testy hlídají, že je bajtově shodná s Rustem.
 */
object SigningInput {
    val PREFIX = "ELFALL-SIG-v1\u0000".encodeToByteArray()
    const val MAX_MESSAGE = 65_536

    fun frame(msg: ByteArray, useRust: Boolean = RustCore.enabled()): ByteArray =
        if (useRust) uniffi.elfall_core.signingInput(msg) else frameKotlin(msg)

    internal fun frameKotlin(msg: ByteArray): ByteArray {
        require(msg.size <= MAX_MESSAGE) { "zpráva je příliš dlouhá" }
        val n = msg.size
        return PREFIX + byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + msg
    }
}
