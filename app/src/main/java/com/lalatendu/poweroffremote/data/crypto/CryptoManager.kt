package com.lalatendu.poweroffremote.data.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM using a key that is generated inside the Android Keystore and never leaves it
 * (StrongBox / TEE backed where the device supports it).
 *
 * The key is deliberately NOT bound to user authentication: power actions have to work from a
 * widget or a background retry. The app-level biometric lock guards the UI instead.
 *
 * [keyAlias] is a constructor parameter so tests can exercise the real Keystore against a
 * throwaway alias instead of the alias holding the user's live vault.
 */
class CryptoManager(private val keyAlias: String = DEFAULT_ALIAS) {

    private val lock = Any()

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun secretKey(): SecretKey = synchronized(lock) {
        val ks = keyStore()
        (ks.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generateKey()
    }

    private fun generateKey(): SecretKey {
        fun build(strongBox: Boolean): SecretKey {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .apply {
                    if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        setIsStrongBoxBacked(true)
                    }
                }
                .build()
            generator.init(spec)
            return generator.generateKey()
        }
        return runCatching { build(strongBox = true) }.getOrElse { build(strongBox = false) }
    }

    /** Returns iv || ciphertext || tag. */
    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        require(iv.size == IV_SIZE) { "Unexpected GCM IV size: ${iv.size}" }
        return iv + cipher.doFinal(plain)
    }

    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_SIZE) { "Ciphertext is truncated" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, blob, 0, IV_SIZE)
        )
        return cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE)
    }

    fun encryptToString(plain: String): String =
        Base64.encodeToString(encrypt(plain.toByteArray()), Base64.NO_WRAP)

    fun decryptFromString(encoded: String): String =
        String(decrypt(Base64.decode(encoded, Base64.NO_WRAP)))

    /**
     * Where the platform says this key actually lives. Best effort: older devices only report a
     * boolean, and a few report nothing useful at all.
     */
    fun securityLevel(): String = try {
        val key = secretKey()
        val info = SecretKeyFactory.getInstance(key.algorithm, KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
                else -> "unknown"
            }

            @Suppress("DEPRECATION")
            info.isInsideSecureHardware -> "secure hardware"

            else -> "software"
        }
    } catch (e: Exception) {
        "unknown"
    }

    /**
     * Drops the master key. Every stored blob becomes permanently unreadable, so this is only
     * called from the "erase everything" path in Settings.
     */
    fun destroyKey() = synchronized(lock) {
        runCatching { keyStore().deleteEntry(keyAlias) }
        Unit
    }

    companion object {
        const val DEFAULT_ALIAS = "poweroff_remote_vault_key_v1"

        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128

        /** The instance guarding the user's live vault. */
        val default: CryptoManager by lazy { CryptoManager() }
    }
}
