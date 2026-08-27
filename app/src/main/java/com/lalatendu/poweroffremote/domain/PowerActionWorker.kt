package com.lalatendu.poweroffremote.domain

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lalatendu.poweroffremote.R
import com.lalatendu.poweroffremote.appContainer
import com.lalatendu.poweroffremote.data.model.ActionType

/**
 * Carries out a power action outside the UI's lifetime.
 *
 * The point is survival: a shutdown started just before the user swipes the app away, or before
 * Android reclaims the process, still completes and still lands in the audit log. Only the
 * server id travels in the input data — credentials stay in the vault and are read here by id.
 */
class PowerActionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer

        val serverId = inputData.getString(KEY_SERVER_ID)
            ?: return Result.failure(failureData("No server was supplied"))
        val action = inputData.getString(KEY_ACTION)
            ?.let { runCatching { ActionType.valueOf(it) }.getOrNull() }
            ?: return Result.failure(failureData("Unknown action"))
        val server = container.servers.get(serverId)
            ?: return Result.failure(failureData("That server no longer exists"))

        val result = when (action) {
            ActionType.POWER_OFF -> container.controller.powerOff(server)
            ActionType.REBOOT -> container.controller.reboot(server)
            ActionType.WAKE -> container.controller.wake(server)
            else -> return Result.failure(failureData("$action cannot be run in the background"))
        }

        // Deliberately no Result.retry(). Re-running a shutdown whose reply was merely lost is not
        // obviously harmless, and an authentication failure will not fix itself. A failed power
        // action should surface to the user, not quietly repeat.
        val data = result.toData()
        return if (result.success) Result.success(data) else Result.failure(data)
    }

    /**
     * Expedited work below API 31 runs as a foreground service, which needs a notification. From
     * API 31 the platform uses a job quota instead and this is never called.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL_ID) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Power actions",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Shown while a shutdown, reboot or wake is in flight." }
            )
        }

        val name = inputData.getString(KEY_SERVER_NAME).orEmpty()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(if (name.isBlank()) "Working" else "Working on $name")
            .setSmallIcon(R.drawable.ic_power_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun failureData(message: String): Data =
        workDataOf(KEY_SUCCESS to false, KEY_SUMMARY to message, KEY_DETAIL to "")

    companion object {
        const val KEY_SERVER_ID = "server_id"
        const val KEY_SERVER_NAME = "server_name"
        const val KEY_ACTION = "action"
        const val KEY_SUCCESS = "success"
        const val KEY_SUMMARY = "summary"
        const val KEY_DETAIL = "detail"
        const val KEY_HOST_KEY_CHANGED = "host_key_changed"
        const val KEY_FINGERPRINT = "fingerprint"

        const val TAG_POWER_ACTION = "power-action"
        const val SERVER_TAG_PREFIX = "server:"

        private const val CHANNEL_ID = "power-actions"
        private const val NOTIFICATION_ID = 4711

        /** Work Data is capped at 10KB, and command output can be long. */
        private const val MAX_DETAIL = 2000

        fun uniqueWorkName(serverId: String): String = "power-action-$serverId"

        fun serverIdFromTags(tags: Set<String>): String? =
            tags.firstOrNull { it.startsWith(SERVER_TAG_PREFIX) }?.removePrefix(SERVER_TAG_PREFIX)

        private fun ActionResult.toData(): Data = workDataOf(
            KEY_SUCCESS to success,
            KEY_SUMMARY to summary,
            KEY_DETAIL to detail.take(MAX_DETAIL),
            KEY_HOST_KEY_CHANGED to hostKeyChanged,
            KEY_FINGERPRINT to presentedFingerprint,
        )

        fun actionResultFrom(data: Data): ActionResult = ActionResult(
            success = data.getBoolean(KEY_SUCCESS, false),
            summary = data.getString(KEY_SUMMARY) ?: "The action ended without reporting anything",
            detail = data.getString(KEY_DETAIL).orEmpty(),
            hostKeyChanged = data.getBoolean(KEY_HOST_KEY_CHANGED, false),
            presentedFingerprint = data.getString(KEY_FINGERPRINT).orEmpty(),
        )
    }
}
