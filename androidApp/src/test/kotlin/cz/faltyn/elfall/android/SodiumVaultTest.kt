package cz.faltyn.elfall.android

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SodiumVaultTest {
    private val ls = LazySodiumJava(SodiumJava())
    private val vault = SodiumVault(ls, SodiumVault.newDataKey(ls))
    private val addr = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"

    @Test fun roundTrip() = assertEquals(addr, vault.open("payout_address", vault.seal("payout_address", addr)))
    @Test fun randomNonce() = assertNotEquals(vault.seal("payout_address", addr), vault.seal("payout_address", addr))
    @Test fun noPlaintextInCiphertext() = assertFalse(vault.seal("payout_address", addr).contains("tb1q"))
    @Test fun tamperDetected() {
        val s = vault.seal("payout_address", addr)
        val raw = java.util.Base64.getDecoder().decode(s.removePrefix(SodiumVault.PREFIX)); raw[raw.size - 1] = (raw.last() + 1).toByte()
        assertNull(vault.open("payout_address", SodiumVault.PREFIX + java.util.Base64.getEncoder().encodeToString(raw)))
    }
    @Test fun fieldSwapDetected() = assertNull(vault.open("stellar_account", vault.seal("payout_address", addr)))
    @Test fun wrongKeyRejected() = assertNull(SodiumVault(ls, SodiumVault.newDataKey(ls)).open("payout_address", vault.seal("payout_address", addr)))
    @Test fun plaintextLegacyRejected() = assertNull(vault.open("payout_address", addr))

    private val hasher = PinHasher(ls)
    @Test fun argon2idOwaspParams() {
        val h = hasher.hash("123456".toCharArray())
        assertTrue(h.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"), h)
        assertTrue(hasher.verify("123456".toCharArray(), h))
        assertFalse(hasher.verify("123457".toCharArray(), h))
        assertFalse(hasher.needsRehash(h))
    }
    @Test fun saltedHashesDiffer() = assertNotEquals(hasher.hash("1111".toCharArray()), hasher.hash("1111".toCharArray()))
    @Test fun weakParamsNeedRehash() = assertTrue(hasher.needsRehash(PinHasher(ls, 1, 8L * 1024 * 1024).hash("1".toCharArray())))

    // Kompatibilita s Rust jádrem (core/, RustCrypto): vektor vytvořený v Rustu musí otevřít libsodium.
    @Test fun rustCoreVaultVector() = assertEquals("GABCDEF",
        SodiumVault(ls, ByteArray(32) { 0x42 }).open("payout", "sv1:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXy73GpIPkgWFy7/n/ePZi7kBBt5WPl9s="))
    @Test fun rustCoreArgon2idVector() = kotlin.test.assertTrue(PinHasher(ls).verify("1234".toCharArray(), "\$argon2id\$v=19\$m=1024,t=1,p=1\$CjaNrp63TTTVFnOC636bJA\$E9qhk2znI0VxIFdt7zJpPkS1xG+vmK2fNp9nFnD/zlo"))
}
