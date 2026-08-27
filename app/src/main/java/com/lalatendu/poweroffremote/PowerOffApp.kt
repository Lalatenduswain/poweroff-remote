package com.lalatendu.poweroffremote

import android.app.Application
import android.content.Context
import com.lalatendu.poweroffremote.data.crypto.CryptoManager
import com.lalatendu.poweroffremote.data.store.LogRepository
import com.lalatendu.poweroffremote.data.store.ServerRepository
import com.lalatendu.poweroffremote.data.store.SettingsRepository
import com.lalatendu.poweroffremote.domain.ActionRunner
import com.lalatendu.poweroffremote.domain.PowerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers

class AppContainer(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val servers = ServerRepository(context)
    val logs = LogRepository(context)
    val settings = SettingsRepository(context)
    val controller = PowerController(servers, logs)
    val runner = ActionRunner(controller, scope)

    /**
     * Wipes both vaults and throws away the Keystore key, so anything left on disk is
     * unrecoverable. A fresh key is minted the next time something is saved.
     */
    fun eraseEverything() {
        servers.eraseAll()
        logs.clear()
        CryptoManager.destroyKey()
    }

    fun shutdown() = scope.cancel()
}

class PowerOffApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as PowerOffApp).container
