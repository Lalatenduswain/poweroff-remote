package com.lalatendu.poweroffremote.widget

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.fragment.app.FragmentActivity
import androidx.glance.action.ActionParameters
import com.lalatendu.poweroffremote.appContainer
import com.lalatendu.poweroffremote.data.model.Server
import com.lalatendu.poweroffremote.ui.components.ConfirmDialog
import com.lalatendu.poweroffremote.ui.lock.AppLock
import com.lalatendu.poweroffremote.ui.theme.PowerOffRemoteTheme

/**
 * The only route from the widget to a power action.
 *
 * A widget button must not be a way around the app lock or the confirmation step, so both are
 * applied again here before anything is enqueued. The activity is transparent, so from the user's
 * point of view it is just a dialog over the home screen.
 */
class WidgetConfirmActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = appContainer
        val serverId = intent?.getStringExtra(SERVER_ID_KEY.name)
        val action = intent?.getStringExtra(ACTION_KEY.name) ?: ACTION_POWER_OFF
        val server = container.servers.get(serverId)

        if (server == null) {
            finish()
            return
        }

        val settings = container.settings.settings.value
        val lockNeeded = settings.requireUnlock && AppLock.isAvailable(this)

        setContent {
            PowerOffRemoteTheme(dynamicColor = settings.dynamicColor) {
                var unlocked by remember { mutableStateOf(!lockNeeded) }

                LaunchedEffect(Unit) {
                    if (!unlocked) {
                        AppLock.prompt(
                            activity = this@WidgetConfirmActivity,
                            title = confirmTitle(action, server),
                            subtitle = "Unlock to continue",
                            onSuccess = { unlocked = true },
                            onFailure = { finish() },
                        )
                    }
                }

                if (unlocked) {
                    val isWake = action == ACTION_WAKE
                    // Waking a machine is not destructive, so it does not need the typed-name
                    // safeguard that a shutdown does.
                    ConfirmDialog(
                        title = confirmTitle(action, server),
                        body = if (isWake) {
                            "A Wake-on-LAN magic packet will be sent to ${server.name}."
                        } else {
                            "${server.displayTarget} will run `${server.shutdownCommand}`."
                        },
                        confirmLabel = if (isWake) "Wake" else "Power off",
                        destructive = !isWake,
                        requiredPhrase = if (!isWake && settings.typeNameToConfirm) server.name else null,
                        onConfirm = {
                            if (isWake) container.runner.wake(server)
                            else container.runner.powerOff(server)
                            finish()
                        },
                        onDismiss = { finish() },
                    )
                }
            }
        }
    }

    private fun confirmTitle(action: String, server: Server): String =
        if (action == ACTION_WAKE) "Wake ${server.name}?" else "Power off ${server.name}?"

    companion object {
        const val ACTION_POWER_OFF = "power_off"
        const val ACTION_WAKE = "wake"

        val SERVER_ID_KEY = ActionParameters.Key<String>("server_id")
        val ACTION_KEY = ActionParameters.Key<String>("action")
    }
}
