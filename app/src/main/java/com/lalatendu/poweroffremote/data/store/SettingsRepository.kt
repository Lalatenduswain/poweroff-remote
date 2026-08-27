package com.lalatendu.poweroffremote.data.store

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val requireUnlock: Boolean = true,
    val blockScreenshots: Boolean = true,
    val confirmPowerOff: Boolean = true,
    val typeNameToConfirm: Boolean = false,
    val dynamicColor: Boolean = true,
    /** Seconds the app may spend in the background before it locks again. 0 = lock immediately. */
    val lockGraceSeconds: Int = 30,
) {
    companion object {
        val LOCK_GRACE_CHOICES = listOf(0, 30, 60, 300)

        fun graceLabel(seconds: Int): String = when (seconds) {
            0 -> "Immediately"
            in 1..59 -> "${seconds}s"
            else -> "${seconds / 60} min"
        }
    }
}

/** Non-secret preferences. Nothing here is worth encrypting. */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(
        AppSettings(
            requireUnlock = prefs.getBoolean(KEY_UNLOCK, true),
            blockScreenshots = prefs.getBoolean(KEY_SCREENSHOTS, true),
            confirmPowerOff = prefs.getBoolean(KEY_CONFIRM, true),
            typeNameToConfirm = prefs.getBoolean(KEY_TYPE_NAME, false),
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC, true),
            lockGraceSeconds = prefs.getInt(KEY_LOCK_GRACE, 30),
        )
    )
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit()
            .putBoolean(KEY_UNLOCK, next.requireUnlock)
            .putBoolean(KEY_SCREENSHOTS, next.blockScreenshots)
            .putBoolean(KEY_CONFIRM, next.confirmPowerOff)
            .putBoolean(KEY_TYPE_NAME, next.typeNameToConfirm)
            .putBoolean(KEY_DYNAMIC, next.dynamicColor)
            .putInt(KEY_LOCK_GRACE, next.lockGraceSeconds)
            .apply()
    }

    private companion object {
        const val KEY_UNLOCK = "require_unlock"
        const val KEY_SCREENSHOTS = "block_screenshots"
        const val KEY_CONFIRM = "confirm_power_off"
        const val KEY_TYPE_NAME = "type_name_to_confirm"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_LOCK_GRACE = "lock_grace_seconds"
    }
}
