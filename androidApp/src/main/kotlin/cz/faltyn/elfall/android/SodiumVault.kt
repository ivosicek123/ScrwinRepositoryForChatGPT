package cz.faltyn.elfall.android

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.AEAD
import com.goterl.lazysodium.interfaces.PwHash
import java.util.Base64

/**
 * Šifrování citlivých lokálních dat (at-rest) přes libsodium: XChaCha20-Poly1305-IETF (AEAD, 192bit nonce).
 * Associated data = název pole → záznam nelze přesunout do jiného pole (např. prohodit výplatní adresu).
 * Datový klíč (32 B) je náhodný a uložený jen zabalený klíčem z Android Keystore (viz [KeyWrapper]).
 */
interface KeyWrapper {
    fun wrap(plain: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

class SodiumVault(private val ls: LazySodium, private val dataKey: ByteArray) : LocalVault {
    init { require(dataKey.size == AEAD.XCHACHA20POLY1305_IETF_KEYBYTES) { "klíč musí mít 32 B" } }

    override fun seal(field: String, plaintext: String): String {
        val nonce = ls.randomBytesBuf(AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES)
        val m = plaintext.encodeToByteArray(); val ad = ad(field)
        val c = ByteArray(m.size + AEAD.XCHACHA20POLY1305_IETF_ABYTES)
        check(ls.cryptoAeadXChaCha20Poly1305IetfEncrypt(c, null, m, m.size.toLong(), ad, ad.size.toLong(), null, nonce, dataKey)) { "šifrování selhalo" }
        m.fill(0)
        return PREFIX + B64E.encodeToString(nonce + c)
    }

    /** Vrací null, pokud data byla změněna/podvržena nebo patří jinému poli. */
    override fun open(field: String, sealed: String): String? {
        if (!sealed.startsWith(PREFIX)) return null
        val raw = runCatching { B64D.decode(sealed.removePrefix(PREFIX)) }.getOrNull() ?: return null
        val n = AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES
        if (raw.size < n + AEAD.XCHACHA20POLY1305_IETF_ABYTES) return null
        val nonce = raw.copyOfRange(0, n); val c = raw.copyOfRange(n, raw.size); val ad = ad(field)
        val m = ByteArray(c.size - AEAD.XCHACHA20POLY1305_IETF_ABYTES)
        val ok = ls.cryptoAeadXChaCha20Poly1305IetfDecrypt(m, null, null, c, c.size.toLong(), ad, ad.size.toLong(), nonce, dataKey)
        return if (ok) m.decodeToString().also { m.fill(0) } else null
    }

    private fun ad(field: String) = "elfall/v1/$field".encodeToByteArray()

    companion object {
        const val PREFIX = "sv1:"
        private val B64E = Base64.getEncoder(); private val B64D = Base64.getDecoder()
        fun newDataKey(ls: LazySodium): ByteArray = ls.randomBytesBuf(AEAD.XCHACHA20POLY1305_IETF_KEYBYTES)
    }
}

/**
 * Hashování PINu/hesla: Argon2id (libsodium crypto_pwhash_str), parametry dle OWASP Password Storage Cheat Sheet:
 * m = 19 MiB, t = 2, p = 1. Výstup je samopopisný řetězec $argon2id$v=19$m=19456,t=2,p=1$… (sůl uvnitř).
 */
class PinHasher(private val ls: LazySodium, private val opsLimit: Long = OPS, private val memLimit: Long = MEM) {
    fun hash(pin: CharArray): String {
        val p = String(pin).encodeToByteArray()
        try {
            val out = ByteArray(PwHash.STR_BYTES)
            check((ls as PwHash.Native).cryptoPwHashStr(out, p, p.size, opsLimit, com.sun.jna.NativeLong(memLimit))) { "Argon2id selhal" }
            return out.decodeToString().trimEnd('\u0000')
        } finally { p.fill(0) }
    }

    fun verify(pin: CharArray, stored: String): Boolean {
        if (!stored.startsWith("\$argon2id\$")) return false
        val p = String(pin).encodeToByteArray()
        try {
            val s = (stored + "\u0000").encodeToByteArray()
            return (ls as PwHash.Native).cryptoPwHashStrVerify(s, p, p.size)
        } finally { p.fill(0) }
    }

    // lazysodium vrací true = „volání úspěšné“ (rc == 0) = rehash NENÍ potřeba, proto negace.
    fun needsRehash(stored: String): Boolean = !
        (ls as PwHash.Native).cryptoPwHashStrNeedsRehash((stored + "\u0000").encodeToByteArray(), opsLimit, com.sun.jna.NativeLong(memLimit))

    companion object { const val OPS = 2L; const val MEM = 19L * 1024 * 1024 }
}
