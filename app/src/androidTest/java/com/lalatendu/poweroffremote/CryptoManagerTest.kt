package com.lalatendu.poweroffremote

import android.security.keystore.KeyInfo
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lalatendu.poweroffremote.data.crypto.CryptoManager
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKeyFactory

/**
 * Exercises the real Android Keystore on the device.
 *
 * Every test runs against a throwaway alias, never [CryptoManager.DEFAULT_ALIAS], so running the
 * suite can never destroy the vault of whoever owns the phone.
 */
@RunWith(AndroidJUnit4::class)
class CryptoManagerTest {

    private lateinit var alias: String
    private lateinit var crypto: CryptoManager

    @Before
    fun setUp() {
        alias = "poweroff_test_${System.nanoTime()}"
        crypto = CryptoManager(alias)
    }

    @After
    fun tearDown() {
        crypto.destroyKey()
        assertFalse("test alias leaked into the keystore", keyStore().containsAlias(alias))
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Test
    fun theTestNeverTouchesTheProductionAlias() {
        assertNotEquals(CryptoManager.DEFAULT_ALIAS, alias)
    }

    @Test
    fun roundTripsBytes() {
        val plain = "root:hunter2 ÿ binary 🔒".toByteArray()
        assertArrayEquals(plain, crypto.decrypt(crypto.encrypt(plain)))
    }

    @Test
    fun roundTripsStringsIncludingUnicode() {
        val secret = "{\"password\":\"p@ss ଓଡ଼ିଆ 🔒\"}"
        assertEquals(secret, crypto.decryptFromString(crypto.encryptToString(secret)))
    }

    @Test
    fun roundTripsAnEmptyPayload() {
        assertArrayEquals(ByteArray(0), crypto.decrypt(crypto.encrypt(ByteArray(0))))
    }

    @Test
    fun roundTripsAPayloadLargerThanOneBlock() {
        val big = ByteArray(200_000) { (it % 251).toByte() }
        assertArrayEquals(big, crypto.decrypt(crypto.encrypt(big)))
    }

    @Test
    fun theCiphertextDoesNotContainThePlaintext() {
        val secret = "correct-horse-battery-staple"
        val blob = crypto.encrypt(secret.toByteArray())
        assertFalse(String(blob, Charsets.ISO_8859_1).contains(secret))
    }

    /** setRandomizedEncryptionRequired means the same input must never produce the same output. */
    @Test
    fun encryptingTwiceProducesDifferentCiphertextAndDifferentIvs() {
        val plain = "same input".toByteArray()
        val first = crypto.encrypt(plain)
        val second = crypto.encrypt(plain)

        assertFalse(first.contentEquals(second))
        assertFalse(
            "the IV must not repeat",
            first.copyOfRange(0, IV_SIZE).contentEquals(second.copyOfRange(0, IV_SIZE)),
        )
        assertArrayEquals(plain, crypto.decrypt(first))
        assertArrayEquals(plain, crypto.decrypt(second))
    }

    @Test
    fun theBlobIsAnIvFollowedByCiphertextAndTag() {
        val plain = "twelve bytes".toByteArray()
        val blob = crypto.encrypt(plain)
        // 12 byte IV + ciphertext the same length as the plaintext + 16 byte GCM tag.
        assertEquals(IV_SIZE + plain.size + TAG_SIZE, blob.size)
    }

    @Test
    fun tamperingWithTheCiphertextIsRejected() {
        val blob = crypto.encrypt("shutdown -h now".toByteArray())
        blob[blob.size - TAG_SIZE - 1] = (blob[blob.size - TAG_SIZE - 1] + 1).toByte()
        try {
            crypto.decrypt(blob)
            fail("a modified ciphertext must not decrypt")
        } catch (expected: AEADBadTagException) {
            // GCM did its job.
        }
    }

    @Test
    fun tamperingWithTheTagIsRejected() {
        val blob = crypto.encrypt("shutdown -h now".toByteArray())
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte()
        try {
            crypto.decrypt(blob)
            fail("a modified tag must not decrypt")
        } catch (expected: AEADBadTagException) {
        }
    }

    @Test
    fun tamperingWithTheIvIsRejected() {
        val blob = crypto.encrypt("shutdown -h now".toByteArray())
        blob[0] = (blob[0] + 1).toByte()
        try {
            crypto.decrypt(blob)
            fail("a modified IV must not decrypt")
        } catch (expected: AEADBadTagException) {
        }
    }

    @Test
    fun aTruncatedBlobIsRejectedRatherThanCrashing() {
        try {
            crypto.decrypt(ByteArray(IV_SIZE))
            fail("a blob with no ciphertext must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun anotherAliasCannotReadOurCiphertext() {
        val blob = crypto.encrypt("only ours".toByteArray())
        val stranger = CryptoManager("poweroff_test_stranger_${System.nanoTime()}")
        try {
            stranger.decrypt(blob)
            fail("a different key must not decrypt our blob")
        } catch (expected: AEADBadTagException) {
        } finally {
            stranger.destroyKey()
        }
    }

    @Test
    fun theKeyMaterialIsNotExportable() {
        crypto.encrypt("force the key into existence".toByteArray())
        val entry = keyStore().getEntry(alias, null) as KeyStore.SecretKeyEntry
        assertNull("a Keystore key must never hand back its bytes", entry.secretKey.encoded)
    }

    @Test
    fun theKeyIsAes256ForGcmOnly() {
        crypto.encrypt("force the key into existence".toByteArray())
        val key = (keyStore().getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo

        assertEquals("AES", key.algorithm)
        assertEquals(256, info.keySize)
        assertTrue(info.blockModes.contains("GCM"))
        assertFalse("CBC must not be permitted", info.blockModes.contains("CBC"))
        assertTrue(info.encryptionPaddings.contains("NoPadding"))
    }

    /** Reported rather than asserted: not every device has a TEE, and none of them lie usefully. */
    @Test
    fun reportsWhereTheKeyLives() {
        crypto.encrypt("force the key into existence".toByteArray())
        val level = crypto.securityLevel()
        Log.i(TAG, "key security level on this device: $level")
        assertTrue(
            "unexpected security level: $level",
            level in setOf("StrongBox", "TEE", "secure hardware", "software", "unknown"),
        )
    }

    @Test
    fun destroyingTheKeyMakesOldCiphertextUnreadableAndMintsAFreshKey() {
        val blob = crypto.encrypt("gone after this".toByteArray())
        crypto.destroyKey()
        assertFalse(keyStore().containsAlias(alias))

        try {
            crypto.decrypt(blob)
            fail("ciphertext must not survive the key being destroyed")
        } catch (expected: AEADBadTagException) {
        }

        // The next write silently mints a new key, so the app keeps working after an erase.
        val fresh = crypto.encrypt("new era".toByteArray())
        assertEquals("new era", String(crypto.decrypt(fresh)))
        assertTrue(keyStore().containsAlias(alias))
    }

    private companion object {
        const val TAG = "CryptoManagerTest"
        const val IV_SIZE = 12
        const val TAG_SIZE = 16
    }
}
