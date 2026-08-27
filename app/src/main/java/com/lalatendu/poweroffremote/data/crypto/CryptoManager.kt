package com.lalatendu.poweroffremote.data.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

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

    /**
     * Envelope encryption: a fresh random AES key encrypts the payload in this process, and only
     * that 32-byte key is passed through the Keystore.
     *
     * Not premature caution — Android 8.0's keystore daemon corrupts a large single operation. A
     * 200KB round trip fails there with AEADBadTagException ("Signature/MAC verification failed"),
     * while API 29 and up handle it fine. The activity log alone can reach hundreds of kilobytes,
     * and a vault that will not decrypt is a vault that is gone, so the bulk data must not go
     * through the Keystore at all. The root key stays non-exportable and hardware-backed; only its
     * workload changes.
     *
     * Layout: [version][keystore iv][wrapped data key][data iv][ciphertext||tag]
     */
    fun encrypt(plain: ByteArray): ByteArray {
        val dataKey = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        try {
            val dataIv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
            val payloadCipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(dataKey, "AES"),
                    GCMParameterSpec(TAG_BITS, dataIv),
                )
            }
            val payload = payloadCipher.doFinal(plain)

            val wrapCipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, secretKey())
            }
            val wrapIv = wrapCipher.iv
            require(wrapIv.size == IV_SIZE) { "Unexpected GCM IV size: ${wrapIv.size}" }
            val wrappedKey = wrapCipher.doFinal(dataKey)

            return byteArrayOf(VERSION_ENVELOPE) + wrapIv + wrappedKey + dataIv + payload
        } finally {
            dataKey.fill(0)
        }
    }

    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_SIZE) { "Ciphertext is truncated" }

        // A legacy blob opens with a random IV byte, so it can look like a version marker one time
        // in 256. GCM authentication makes a misread fail cleanly rather than return junk, so
        // trying the envelope first and falling back is safe.
        if (blob[0] == VERSION_ENVELOPE && blob.size > ENVELOPE_PREFIX) {
            runCatching { decryptEnvelope(blob) }.getOrNull()?.let { return it }
        }
        return decryptLegacy(blob)
    }

    private fun decryptEnvelope(blob: ByteArray): ByteArray {
        var offset = 1
        val wrapIv = blob.copyOfRange(offset, offset + IV_SIZE); offset += IV_SIZE
        val wrappedKey = blob.copyOfRange(offset, offset + WRAPPED_KEY_BYTES); offset += WRAPPED_KEY_BYTES
        val dataIv = blob.copyOfRange(offset, offset + IV_SIZE); offset += IV_SIZE

        val unwrap = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, wrapIv))
        }
        val dataKey = unwrap.doFinal(wrappedKey)
        try {
            val payloadCipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(dataKey, "AES"),
                    GCMParameterSpec(TAG_BITS, dataIv),
                )
            }
            return payloadCipher.doFinal(blob, offset, blob.size - offset)
        } finally {
            dataKey.fill(0)
        }
    }

    /** Vaults written before envelope encryption: the Keystore key applied straight to the data. */
    private fun decryptLegacy(blob: ByteArray): ByteArray {
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
        private const val KEY_BYTES = 32
        private const val WRAPPED_KEY_BYTES = KEY_BYTES + 16
        private const val VERSION_ENVELOPE: Byte = 0x02

        /** version + keystore iv + wrapped key + data iv */
        private const val ENVELOPE_PREFIX = 1 + IV_SIZE + WRAPPED_KEY_BYTES + IV_SIZE

        /** The instance guarding the user's live vault. */
        val default: CryptoManager by lazy { CryptoManager() }
    }
}
