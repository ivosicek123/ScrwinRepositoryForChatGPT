package cz.faltyn.elfall.android

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Biometric / device-credential confirmation before signing a withdrawal. */
object BiometricGate {
    private const val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    fun isAvailable(activity: FragmentActivity) =
        BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun confirm(activity: FragmentActivity, title: String, subtitle: String, onResult: (Boolean, String?) -> Unit) {
        if (!isAvailable(activity)) { onResult(false, "Set up a screen lock or biometrics to send funds"); return }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true, null)
                override fun onAuthenticationError(code: Int, msg: CharSequence) = onResult(false, msg.toString())
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title).setSubtitle(subtitle)
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()
        )
    }
}
