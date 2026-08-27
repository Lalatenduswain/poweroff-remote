package com.lalatendu.poweroffremote.data.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM using a key that is generated inside the Android Keystore and never leaves it
 * (StrongBox / TEE backed where the device supports it).
 *
 * The key is deliberately NOT bound to user authentication: power actions have to work from a
 * widget or a background retry. The app-level biometric lock guards the UI instead.
 */
object CryptoManager {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "poweroff_remote_vault_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    private val lock = Any()

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun secretKey(): SecretKey = synchronized(lock) {
        val ks = keyStore()
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generateKey()
    }

    private fun generateKey(): SecretKey {
        fun build(strongBox: Boolean): SecretKey {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
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
     * Drops the master key. Every stored blob becomes permanently unreadable, so this is only
     * called from the "erase everything" path in Settings.
     */
    fun destroyKey() = synchronized(lock) {
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
        Unit
    }
}
