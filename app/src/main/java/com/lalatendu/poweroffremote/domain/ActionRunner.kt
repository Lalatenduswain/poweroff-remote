package com.lalatendu.poweroffremote.domain

import com.lalatendu.poweroffremote.data.model.Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ServerStatus { UNKNOWN, CHECKING, UP, DOWN }

/**
 * Application-scoped runner for remote actions. Keeping the in-flight state here rather than in a
 * ViewModel means a power-off keeps running (and still reports) across rotation or a trip to the
 * recents screen.
 */
class ActionRunner(
    private val controller: PowerController,
    private val scope: CoroutineScope,
) {

    private val wakeWatchers = mutableMapOf<String, Job>()

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    private val _statuses = MutableStateFlow<Map<String, ServerStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, ServerStatus>> = _statuses.asStateFlow()

    private val _lastResults = MutableStateFlow<Map<String, ActionResult>>(emptyMap())
    val lastResults: StateFlow<Map<String, ActionResult>> = _lastResults.asStateFlow()

    private val _messages = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun powerOff(server: Server) = run(server) { controller.powerOff(it).also { markDownSoon(server.id) } }

    fun reboot(server: Server) = run(server) { controller.reboot(it).also { markDownSoon(server.id) } }

    fun wake(server: Server) = run(server) { sent ->
        controller.wake(sent).also { if (it.success) watchForWake(sent) }
    }

    fun test(server: Server) = run(server) { controller.test(it) }

    fun runCommand(server: Server, command: String) = run(server) { controller.runCommand(it, command) }

    fun refreshStatus(server: Server) {
        if (server.id in _busy.value) return
        scope.launch {
            setStatus(server.id, ServerStatus.CHECKING)
            val result = controller.status(server)
            setStatus(server.id, if (result.success) ServerStatus.UP else ServerStatus.DOWN)
        }
    }

    fun refreshAll(servers: List<Server>) = servers.forEach { refreshStatus(it) }

    /**
     * Runs after a magic packet goes out. Deliberately outside [run] so the server is not held
     * "busy" for a minute and a half — the status dot carries the progress instead.
     */
    private fun watchForWake(server: Server) {
        wakeWatchers.remove(server.id)?.cancel()
        setStatus(server.id, ServerStatus.CHECKING)
        wakeWatchers[server.id] = scope.launch {
            val result = controller.awaitWake(server)
            setStatus(server.id, if (result.success) ServerStatus.UP else ServerStatus.DOWN)
            _lastResults.value = _lastResults.value + (server.id to result)
            _messages.tryEmit(result.summary)
        }
    }

    fun clearResult(serverId: String) {
        _lastResults.value = _lastResults.value - serverId
    }

    private fun run(server: Server, block: suspend (Server) -> ActionResult) {
        if (server.id in _busy.value) return
        scope.launch {
            _busy.value = _busy.value + server.id
            val result = try {
                block(server)
            } catch (e: Exception) {
                ActionResult(false, "Unexpected failure", e.message ?: e.javaClass.simpleName)
            } finally {
                _busy.value = _busy.value - server.id
            }
            _lastResults.value = _lastResults.value + (server.id to result)
            _messages.tryEmit(result.summary)
        }
    }

    /** A machine that was just told to power down is not "up" any more, whatever the last probe said. */
    private fun markDownSoon(serverId: String) {
        wakeWatchers.remove(serverId)?.cancel()
        setStatus(serverId, ServerStatus.UNKNOWN)
    }

    private fun setStatus(serverId: String, status: ServerStatus) {
        _statuses.value = _statuses.value + (serverId to status)
    }
}
