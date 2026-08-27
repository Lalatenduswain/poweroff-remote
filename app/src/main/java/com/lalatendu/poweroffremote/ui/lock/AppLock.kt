package com.lalatendu.poweroffremote.ui.lock

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Fingerprint / face / PIN gate in front of the credential vault. */
object AppLock {

    /** 0 when the device has nothing enrolled that we can use. */
    fun allowedAuthenticators(context: Context): Int {
        val manager = BiometricManager.from(context)
        fun usable(mask: Int) = manager.canAuthenticate(mask) == BiometricManager.BIOMETRIC_SUCCESS

        // Combining a biometric with the device PIN is not supported on API 28-29.
        val combined = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        if (Build.VERSION.SDK_INT !in 28..29 && usable(combined)) return combined
        if (usable(BIOMETRIC_WEAK)) return BIOMETRIC_WEAK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && usable(DEVICE_CREDENTIAL)) {
            return DEVICE_CREDENTIAL
        }
        return 0
    }

    fun isAvailable(context: Context): Boolean = allowedAuthenticators(context) != 0

    fun prompt(
        activity: FragmentActivity,
        title: String = "Unlock PowerOff Remote",
        subtitle: String = "Your server credentials are locked",
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val authenticators = allowedAuthenticators(activity)
        if (authenticators == 0) {
            onFailure("No screen lock is set up on this device")
            return
        }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                    onSuccess()

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) =
                    onFailure(errString.toString())
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)
            .apply {
                if (authenticators and DEVICE_CREDENTIAL == 0) setNegativeButtonText("Cancel")
            }
            .build()

        prompt.authenticate(info)
    }
}
