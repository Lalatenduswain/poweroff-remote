package com.lalatendu.poweroffremote.data.store

import android.content.Context
import com.lalatendu.poweroffremote.data.model.Server
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

class ServerRepository(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val store = EncryptedFile(File(context.filesDir, "servers.vault"))

    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<Server>> = _servers.asStateFlow()

    private fun load(): List<Server> {
        val raw = store.read() ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(Server.serializer()), raw) }
            .getOrDefault(emptyList())
    }

    private fun persist(list: List<Server>) {
        _servers.value = list
        store.write(json.encodeToString(ListSerializer(Server.serializer()), list))
    }

    fun get(id: String?): Server? = id?.let { key -> _servers.value.firstOrNull { it.id == key } }

    fun upsert(server: Server) {
        val current = _servers.value
        val index = current.indexOfFirst { it.id == server.id }
        persist(
            if (index >= 0) current.toMutableList().apply { this[index] = server }
            else current + server
        )
    }

    fun delete(id: String) = persist(_servers.value.filterNot { it.id == id })

    /** Records the host key we trusted on first connect, or a key the user re-approved. */
    fun rememberHostKey(id: String, fingerprint: String) {
        get(id)?.let { if (it.hostKeyFingerprint != fingerprint) upsert(it.copy(hostKeyFingerprint = fingerprint)) }
    }

    fun forgetHostKey(id: String) {
        get(id)?.let { upsert(it.copy(hostKeyFingerprint = "")) }
    }

    fun eraseAll() {
        store.delete()
        _servers.value = emptyList()
    }
}
