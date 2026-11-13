package cz.faltyn.elfall.shared.identity

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/** Pomocné převody pro Swift (KotlinByteArray <-> Data) – implementace klíčů je ve Swiftu (Secure Enclave). */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object IosBridge {
    fun toNSData(b: ByteArray): NSData = if (b.isEmpty()) NSData() else b.usePinned { NSData.create(bytes = it.addressOf(0), length = b.size.toULong()) }
    fun toByteArray(d: NSData): ByteArray = ByteArray(d.length.toInt()).also { out ->
        if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), d.bytes, d.length) }
    }
    fun register(session: WebAuthSession, signer: HardwareKeySigner?) {
        PlatformIdentity.webAuthSession = session
        PlatformIdentity.keySigner = signer
    }
}
