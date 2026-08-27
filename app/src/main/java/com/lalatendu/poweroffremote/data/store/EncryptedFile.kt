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
            // A failure here means the Keystore key is gone (app data cleared, device restored to
            // new hardware, or the key was invalidated). The blob can never be recovered.
            Log.w(TAG, "Could not decrypt ${file.name}; discarding it", e)
            runCatching { file.delete() }
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
