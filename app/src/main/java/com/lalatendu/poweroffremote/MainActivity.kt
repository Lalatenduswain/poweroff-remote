package com.lalatendu.poweroffremote

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lalatendu.poweroffremote.data.model.Server
import com.lalatendu.poweroffremote.ui.detail.ServerDetailScreen
import com.lalatendu.poweroffremote.ui.editor.ServerEditorScreen
import com.lalatendu.poweroffremote.ui.lock.AppLock
import com.lalatendu.poweroffremote.ui.lock.LockScreen
import com.lalatendu.poweroffremote.ui.logs.ActivityLogScreen
import com.lalatendu.poweroffremote.ui.servers.ServerListScreen
import com.lalatendu.poweroffremote.ui.settings.SettingsScreen
import com.lalatendu.poweroffremote.ui.theme.PowerOffRemoteTheme
import com.lalatendu.poweroffremote.domain.ServerStatus

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppRoot(this) }
    }
}

@Composable
private fun AppRoot(activity: FragmentActivity) {
    val container = LocalContext.current.appContainer
    val settings by container.settings.settings.collectAsStateWithLifecycle()

    // Keep the credential screens out of screenshots and the recents thumbnail.
    DisposableEffect(settings.blockScreenshots) {
        if (settings.blockScreenshots) {
            activity.window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { }
    }

    PowerOffRemoteTheme(dynamicColor = settings.dynamicColor) {
        val lockNeeded = settings.requireUnlock && AppLock.isAvailable(activity)
        var unlocked by remember { mutableStateOf(!lockNeeded) }
        var lockMessage by remember { mutableStateOf<String?>(null) }

        // Re-lock after the app has been away long enough. A short grace period means glancing at
        // another app to copy a MAC address does not cost a second fingerprint; it also absorbs
        // devices that report ON_STOP while the system biometric sheet is up.
        var leftForegroundAt by remember { mutableStateOf(0L) }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, lockNeeded, settings.lockGraceSeconds) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP ->
                        if (lockNeeded) leftForegroundAt = SystemClock.elapsedRealtime()

                    Lifecycle.Event.ON_START -> {
                        val since = leftForegroundAt
                        if (lockNeeded && since != 0L) {
                            val away = SystemClock.elapsedRealtime() - since
                            if (away >= settings.lockGraceSeconds * 1000L) unlocked = false
                            leftForegroundAt = 0L
                        }
                    }

                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        fun requestUnlock() = AppLock.prompt(
            activity = activity,
            onSuccess = { unlocked = true; lockMessage = null },
            onFailure = { lockMessage = it },
        )

        LaunchedEffect(lockNeeded, unlocked) {
            if (lockNeeded && !unlocked) requestUnlock()
        }

        if (lockNeeded && !unlocked) {
            LockScreen(message = lockMessage, onUnlock = { requestUnlock() })
        } else {
            AppNavigation()
        }
    }
}

@Composable
private fun AppNavigation() {
    val container = LocalContext.current.appContainer
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val servers by container.servers.servers.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val statuses by container.runner.statuses.collectAsStateWithLifecycle()
    val busy by container.runner.busy.collectAsStateWithLifecycle()
    val results by container.runner.lastResults.collectAsStateWithLifecycle()
    val logs by container.logs.entries.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        container.runner.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    NavHost(navController = navController, startDestination = Routes.SERVERS) {

        composable(Routes.SERVERS) {
            LaunchedEffect(servers.size) { container.runner.refreshAll(servers) }
            ServerListScreen(
                servers = servers,
                statuses = statuses,
                busy = busy,
                settings = settings,
                snackbarHostState = snackbarHostState,
                onOpen = { navController.navigate("${Routes.DETAIL}/${it.id}") },
                onAdd = { navController.navigate(Routes.editor(null)) },
                onPowerOff = { container.runner.powerOff(it) },
                onWake = { container.runner.wake(it) },
                onRefreshAll = { container.runner.refreshAll(servers) },
                onOpenLogs = { navController.navigate(Routes.LOGS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = "${Routes.DETAIL}/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val server = servers.firstOrNull { it.id == id }
            if (server == null) {
                LaunchedEffect(id) { navController.popBackStack() }
                return@composable
            }
            ServerDetailScreen(
                server = server,
                gatewayName = servers.firstOrNull { it.id == server.wakeGatewayId }?.name,
                status = statuses[server.id] ?: ServerStatus.UNKNOWN,
                busy = server.id in busy,
                lastResult = results[server.id],
                settings = settings,
                snackbarHostState = snackbarHostState,
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(Routes.editor(server.id)) },
                onDelete = {
                    container.servers.delete(server.id)
                    container.runner.clearResult(server.id)
                    navController.popBackStack()
                },
                onPowerOff = { container.runner.powerOff(server) },
                onReboot = { container.runner.reboot(server) },
                onWake = { container.runner.wake(server) },
                onRefresh = { container.runner.refreshStatus(server) },
                onTest = { container.runner.test(server) },
                onRunCommand = { container.runner.runCommand(server, it) },
                onTrustNewHostKey = { container.servers.forgetHostKey(server.id) },
            )
        }

        composable(
            route = "${Routes.EDITOR}?id={id}",
            arguments = listOf(
                navArgument("id") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            ),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val existing = servers.firstOrNull { it.id == id }
            ServerEditorScreen(
                initial = existing ?: Server(),
                isNew = existing == null,
                otherServers = servers.filter { it.id != id },
                onBack = { navController.popBackStack() },
                onSave = {
                    container.servers.upsert(it)
                    navController.popBackStack()
                },
            )
        }

        composable(Routes.LOGS) {
            ActivityLogScreen(
                entries = logs,
                onBack = { navController.popBackStack() },
                onClear = { container.logs.clear() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                settings = settings,
                serverCount = servers.size,
                logCount = logs.size,
                onBack = { navController.popBackStack() },
                onChange = { transform -> container.settings.update(transform) },
                onClearLogs = { container.logs.clear() },
                onEraseEverything = {
                    container.eraseEverything()
                    navController.popBackStack(Routes.SERVERS, inclusive = false)
                },
            )
        }
    }
}

private object Routes {
    const val SERVERS = "servers"
    const val DETAIL = "detail"
    const val EDITOR = "editor"
    const val LOGS = "logs"
    const val SETTINGS = "settings"

    fun editor(id: String?): String = "$EDITOR?id=${id.orEmpty()}"
}
