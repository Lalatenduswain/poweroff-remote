package com.lalatendu.poweroffremote.data.store

import android.content.Context
import com.lalatendu.poweroffremote.data.crypto.CryptoManager
import com.lalatendu.poweroffremote.data.model.ActionType
import com.lalatendu.poweroffremote.data.model.LogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Audit trail of every action the app performed. Encrypted, capped, newest first. */
class LogRepository(
    context: Context,
    fileName: String = "activity.vault",
    crypto: CryptoManager = CryptoManager.default,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val store = EncryptedFile(File(context.filesDir, fileName), crypto)

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private fun load(): List<LogEntry> {
        val raw = store.read() ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(LogEntry.serializer()), raw) }
            .getOrDefault(emptyList())
    }

    fun record(
        serverId: String,
        serverName: String,
        action: ActionType,
        success: Boolean,
        message: String,
        detail: String = "",
    ) {
        val entry = LogEntry(
            serverId = serverId,
            serverName = serverName,
            action = action,
            success = success,
            message = message,
            detail = detail.take(MAX_DETAIL),
        )
        val updated = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        _entries.value = updated
        store.write(json.encodeToString(ListSerializer(LogEntry.serializer()), updated))
    }

    fun clear() {
        _entries.value = emptyList()
        store.delete()
    }

    fun forServer(id: String): List<LogEntry> = _entries.value.filter { it.serverId == id }

    private companion object {
        const val MAX_ENTRIES = 400
        const val MAX_DETAIL = 4000
    }
}
