package cz.faltyn.elfall.shared

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import kotlin.random.Random

@OptIn(ExperimentalForeignApi::class)
private object IosSecureRandom : Random() {
    override fun nextBits(bitCount: Int): Int {
        val bytes = ByteArray(4)
        bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, 4u, it.addressOf(0)) }
        val v = (bytes[0].toInt() and 0xff shl 24) or (bytes[1].toInt() and 0xff shl 16) or
            (bytes[2].toInt() and 0xff shl 8) or (bytes[3].toInt() and 0xff)
        return v ushr (32 - bitCount) and (-bitCount shr 31)
    }
}

actual fun secureRandom(): Random = IosSecureRandom

// TODO(iOS): implement SeedStore on top of Keychain (kSecClassGenericPassword, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
// and gate access with LocalAuthentication (Face ID / Touch ID).
