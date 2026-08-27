package com.lalatendu.poweroffremote.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

enum class ActionType(val label: String) {
    POWER_OFF("Power off"),
    REBOOT("Reboot"),
    WAKE("Wake"),
    STATUS("Status check"),
    TEST("Connection test"),
    COMMAND("Custom command")
}

@Serializable
data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val serverId: String = "",
    val serverName: String = "",
    val action: ActionType = ActionType.STATUS,
    val success: Boolean = false,
    val message: String = "",
    val detail: String = "",
)
