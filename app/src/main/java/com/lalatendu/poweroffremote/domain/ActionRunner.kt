package com.lalatendu.poweroffremote.domain

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.lalatendu.poweroffremote.data.model.ActionType
import com.lalatendu.poweroffremote.data.model.Server
import com.lalatendu.poweroffremote.domain.PowerActionWorker.Companion.actionResultFrom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ServerStatus { UNKNOWN, CHECKING, UP, DOWN }

/**
 * The single dispatch point for remote actions.
 *
 * Power actions go through WorkManager so they outlive the UI — a shutdown started just before the
 * app is swiped away still completes and still lands in the audit log. Interactive actions (a
 * connection test, an ad-hoc command, a reachability probe) stay as plain coroutines: they only
 * mean anything while someone is looking at the result.
 */
class ActionRunner(
    context: Context,
    private val controller: PowerController,
    private val scope: CoroutineScope,
) {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    private val wakeWatchers = mutableMapOf<String, Job>()

    /** Servers busy with a foreground action the user is watching. */
    private val _interactiveBusy = MutableStateFlow<Set<String>>(emptySet())

    /** Servers busy with a WorkManager power action, rebuilt from WorkManager on every launch. */
    private val _workBusy = MutableStateFlow<Set<String>>(emptySet())

    val busy: StateFlow<Set<String>> =
        combine(_interactiveBusy, _workBusy) { interactive, work -> interactive + work }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

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

    init {
        // Work that outlived the last process shows up here, so the UI reflects it after a restart
        // instead of looking idle while a shutdown is still in flight.
        scope.launch {
            workManager.getWorkInfosByTagFlow(PowerActionWorker.TAG_POWER_ACTION).collect { infos ->
                _workBusy.value = infos
                    .filterNot { it.state.isFinished }
                    .mapNotNull { PowerActionWorker.serverIdFromTags(it.tags) }
                    .toSet()
            }
        }
    }

    fun powerOff(server: Server) = enqueue(server, ActionType.POWER_OFF)

    fun reboot(server: Server) = enqueue(server, ActionType.REBOOT)

    fun wake(server: Server) = enqueue(server, ActionType.WAKE)

    fun test(server: Server) = runInteractive(server) { controller.test(it) }

    fun runCommand(server: Server, command: String) =
        runInteractive(server) { controller.runCommand(it, command) }

    fun refreshStatus(server: Server) {
        if (server.id in busy.value) return
        scope.launch {
            setStatus(server.id, ServerStatus.CHECKING)
            val result = controller.status(server)
            setStatus(server.id, if (result.success) ServerStatus.UP else ServerStatus.DOWN)
        }
    }

    fun refreshAll(servers: List<Server>) = servers.forEach { refreshStatus(it) }

    fun clearResult(serverId: String) {
        _lastResults.value = _lastResults.value - serverId
    }

    private fun enqueue(server: Server, action: ActionType) {
        if (server.id in busy.value) return

        if (action != ActionType.WAKE) markDownSoon(server.id)

        val request = OneTimeWorkRequestBuilder<PowerActionWorker>()
            .setInputData(
                workDataOf(
                    PowerActionWorker.KEY_SERVER_ID to server.id,
                    PowerActionWorker.KEY_SERVER_NAME to server.name,
                    PowerActionWorker.KEY_ACTION to action.name,
                )
            )
            // Power actions are user-initiated and finish in seconds, which is exactly what
            // expedited work is for. Falling back to ordinary work is fine if the quota is spent.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(PowerActionWorker.TAG_POWER_ACTION)
            .addTag(PowerActionWorker.SERVER_TAG_PREFIX + server.id)
            .build()

        // KEEP, not REPLACE: a second tap must not cancel a shutdown that is already going out.
        workManager.enqueueUniqueWork(
            PowerActionWorker.uniqueWorkName(server.id),
            ExistingWorkPolicy.KEEP,
            request,
        )

        scope.launch {
            val finished: WorkInfo = workManager.getWorkInfoByIdFlow(request.id)
                .filterNotNull()
                .first { it.state.isFinished }

            val result = when (finished.state) {
                WorkInfo.State.CANCELLED -> ActionResult(false, "${server.name}: action cancelled")
                else -> actionResultFrom(finished.outputData)
            }
            _lastResults.value = _lastResults.value + (server.id to result)
            _messages.tryEmit(result.summary)

            if (action == ActionType.WAKE && result.success) watchForWake(server)
        }
    }

    private fun runInteractive(server: Server, block: suspend (Server) -> ActionResult) {
        if (server.id in busy.value) return
        scope.launch {
            _interactiveBusy.value = _interactiveBusy.value + server.id
            val result = try {
                block(server)
            } catch (e: Exception) {
                ActionResult(false, "Unexpected failure", e.message ?: e.javaClass.simpleName)
            } finally {
                _interactiveBusy.value = _interactiveBusy.value - server.id
            }
            _lastResults.value = _lastResults.value + (server.id to result)
            _messages.tryEmit(result.summary)
        }
    }

    /**
     * Runs after a magic packet goes out. Deliberately outside the busy state so the server is not
     * held for a minute and a half — the status dot carries the progress instead.
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

    /** A machine that was just told to power down is not "up" any more, whatever the last probe said. */
    private fun markDownSoon(serverId: String) {
        wakeWatchers.remove(serverId)?.cancel()
        setStatus(serverId, ServerStatus.UNKNOWN)
    }

    private fun setStatus(serverId: String, status: ServerStatus) {
        _statuses.value = _statuses.value + (serverId to status)
    }
}
