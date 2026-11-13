package cz.faltyn.elfall.android

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assume.assumeTrue
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import uniffi.elfall_core.CoreException

/** Rust jádro (UniFFI) proti hostitelské knihovně; kompatibilita s lazysodium oběma směry. */
class RustCoreTest {
    private val ls = LazySodiumJava(SodiumJava())
    private val key = ByteArray(32) { it.toByte() }

    @Before fun lib() = assumeTrue("libelfall_core není sestavena (cargo build --release --features ffi)", RustCore.available)

    @Test fun flagDefaultOff() { assertFalse(BuildConfig.RUST_CORE); assertFalse(RustCore.enabled(flag = false)) }
    @Test fun framingKotlinEqualsRust() {
        for (n in listOf(0, 1, 255, 256, 65_535, 65_536)) {
            val m = ByteArray(n) { (it * 7).toByte() }
            assertContentEquals(SigningInput.frameKotlin(m), SigningInput.frame(m, useRust = true))
        }
        assertFailsWith<CoreException> { SigningInput.frame(ByteArray(65_537), useRust = true) }
        assertFailsWith<IllegalArgumentException> { SigningInput.frameKotlin(ByteArray(65_537)) }
    }
    @Test fun rustSealsLazysodiumOpens() {
        val s = RustCoreVault(key).seal("payout_address", "GABC")
        assertEquals("GABC", SodiumVault(ls, key).open("payout_address", s))
        assertNull(SodiumVault(ls, key).open("stellar_account", s))
    }
    @Test fun lazysodiumSealsRustOpens() {
        val s = SodiumVault(ls, key).seal("stellar_account", "GXYZ")
        assertEquals("GXYZ", RustCoreVault(key).open("stellar_account", s))
        assertNull(RustCoreVault(key).open("payout_address", s))
        assertNull(RustCoreVault(key).open("stellar_account", s.dropLast(2) + "AA"))
    }
    @Test fun ed25519LibsodiumInterop() {
        val seed = ByteArray(32) { 9 }
        val pk = uniffi.elfall_core.ed25519PublicKey(seed)
        val sig = uniffi.elfall_core.ed25519SignFramed(seed, "tx".encodeToByteArray())
        // lazysodium ověří podpis Rustu nad orámovaným vstupem
        val framed = SigningInput.frameKotlin("tx".encodeToByteArray())
        assertTrue(ls.cryptoSignVerifyDetached(sig, framed, framed.size, pk))
        assertTrue(uniffi.elfall_core.ed25519VerifyFramed(pk, "tx".encodeToByteArray(), sig))
        assertFalse(uniffi.elfall_core.ed25519VerifyFramed(pk, "tX".encodeToByteArray(), sig))
    }
    @Test fun argon2idInterop() {
        val h = uniffi.elfall_core.pinHash("2468")
        assertTrue(h.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"))
        assertTrue(PinHasher(ls).verify("2468".toCharArray(), h))
        assertTrue(uniffi.elfall_core.pinVerify("2468", PinHasher(ls).hash("2468".toCharArray())))
        assertFalse(uniffi.elfall_core.pinVerify("2469", h))
    }
}
