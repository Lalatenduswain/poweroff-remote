package com.lalatendu.poweroffremote.data.store

import android.util.Log
import com.lalatendu.poweroffremote.data.crypto.CryptoManager
import java.io.File

/** A single file on internal storage whose whole content is AES-GCM encrypted. */
class EncryptedFile(
    private val file: File,
    private val crypto: CryptoManager = CryptoManager.default,
) {

    fun read(): String? {
        if (!file.exists()) return null
        return try {
            crypto.decryptFromString(file.readText())
        } catch (e: Exception) {
            // Usually the Keystore key is gone (app data cleared, device restored onto new
            // hardware, key invalidated) and the blob really is unrecoverable. But it can also be
            // a bug on our side — Android 8.0 corrupts large Keystore operations, which is exactly
            // how this was found — so move the file aside rather than deleting it. The app starts
            // empty either way, and nothing is destroyed that a fix might have recovered.
            Log.w(TAG, "Could not decrypt ${file.name}; setting it aside", e)
            runCatching {
                val quarantine = File(file.parentFile, "${file.name}.unreadable")
                quarantine.delete()
                if (!file.renameTo(quarantine)) file.delete()
            }
            null
        }
    }

    fun write(content: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(crypto.encryptToString(content))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun delete() {
        runCatching { file.delete() }
    }

    private companion object {
        const val TAG = "EncryptedFile"
    }
}
