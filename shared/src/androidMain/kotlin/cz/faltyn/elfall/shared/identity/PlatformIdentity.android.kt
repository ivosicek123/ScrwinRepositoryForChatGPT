package cz.faltyn.elfall.shared.identity

actual object PlatformIdentity {
    actual var webAuthSession: WebAuthSession? = null
    actual var keySigner: HardwareKeySigner? = null
    actual val platform: String = "android"
}
