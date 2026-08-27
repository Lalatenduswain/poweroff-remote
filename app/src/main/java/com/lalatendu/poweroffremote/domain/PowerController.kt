package com.lalatendu.poweroffremote.domain

import com.lalatendu.poweroffremote.data.model.ActionType
import com.lalatendu.poweroffremote.data.model.Server
import com.lalatendu.poweroffremote.data.model.WakeMethod
import com.lalatendu.poweroffremote.data.store.LogRepository
import com.lalatendu.poweroffremote.data.store.ServerRepository
import com.lalatendu.poweroffremote.net.Reachability
import com.lalatendu.poweroffremote.net.SshClient
import com.lalatendu.poweroffremote.net.SshOutcome
import com.lalatendu.poweroffremote.net.WolSender

data class ActionResult(
    val success: Boolean,
    val summary: String,
    val detail: String = "",
    /** Set when the server presented a host key different from the pinned one. */
    val hostKeyChanged: Boolean = false,
    val presentedFingerprint: String = "",
)

/**
 * Every remote action the app can perform, plus the audit trail and host-key bookkeeping
 * that has to happen around it.
 */
class PowerController(
    private val servers: ServerRepository,
    private val logs: LogRepository,
) {

    suspend fun powerOff(server: Server): ActionResult =
        runPowerCommand(server, server.shutdownCommand, ActionType.POWER_OFF, "Power off")

    suspend fun reboot(server: Server): ActionResult =
        runPowerCommand(server, server.rebootCommand, ActionType.REBOOT, "Reboot")

    suspend fun test(server: Server): ActionResult {
        val outcome = SshClient.probe(server)
        rememberHostKey(server, outcome)
        val result = ActionResult(
            success = outcome.ok,
            summary = if (outcome.ok) "Connected to ${server.name}" else (outcome.error ?: "Connection failed"),
            detail = buildDetail(outcome),
            hostKeyChanged = outcome.hostKeyChanged,
            presentedFingerprint = outcome.hostKeyFingerprint.orEmpty(),
        )
        log(server, ActionType.TEST, result)
        return result
    }

    suspend fun status(server: Server): ActionResult {
        val reach = Reachability.check(server)
        val result = ActionResult(
            success = reach.up,
            summary = if (reach.up) "${server.name} is up" else "${server.name} is not responding",
            detail = reach.detail,
        )
        log(server, ActionType.STATUS, result)
        return result
    }

    suspend fun runCommand(server: Server, command: String): ActionResult {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return ActionResult(false, "Nothing to run")

        val outcome = SshClient.exec(server, trimmed)
        rememberHostKey(server, outcome)
        val result = ActionResult(
            success = outcome.ok,
            summary = if (outcome.ok) "Command finished (exit 0)"
            else (outcome.error ?: "Command failed"),
            detail = buildDetail(outcome),
            hostKeyChanged = outcome.hostKeyChanged,
            presentedFingerprint = outcome.hostKeyFingerprint.orEmpty(),
        )
        log(server, ActionType.COMMAND, result, extra = "$ $trimmed")
        return result
    }

    suspend fun wake(server: Server): ActionResult {
        val result = when (server.wakeMethod) {
            WakeMethod.NONE -> ActionResult(
                false,
                "Wake is turned off for this server",
                "Set a wake method in the server settings first.",
            )

            WakeMethod.BROADCAST -> {
                val outcome = WolSender.send(server)
                ActionResult(
                    success = outcome.ok,
                    summary = if (outcome.ok) "Magic packet sent to ${server.name}"
                    else (outcome.error ?: "Could not send the magic packet"),
                    detail = if (outcome.ok) {
                        "Sent to ${outcome.targets.joinToString(", ")}\n" +
                            "Give it 30-60 seconds, then refresh the status."
                    } else {
                        "A magic packet is not routable — the phone has to be on the same " +
                            "network as the server. Use a gateway relay when you are away."
                    },
                )
            }

            WakeMethod.GATEWAY -> wakeViaGateway(server)
        }
        log(server, ActionType.WAKE, result)
        return result
    }

    private suspend fun wakeViaGateway(server: Server): ActionResult {
        val gateway = servers.get(server.wakeGatewayId)
            ?: return ActionResult(
                false,
                "Gateway server is missing",
                "The server chosen to relay the magic packet no longer exists.",
            )

        val command = gatewayWakeCommand(
            mac = server.macAddress,
            broadcast = server.wolBroadcast.ifBlank { "255.255.255.255" },
            port = server.wolPort,
            secureOn = server.wolSecureOn,
        )
        val outcome = SshClient.exec(gateway, command)
        rememberHostKey(gateway, outcome)

        return ActionResult(
            success = outcome.ok,
            summary = if (outcome.ok) "${gateway.name} sent the magic packet"
            else "Relay through ${gateway.name} failed",
            detail = buildString {
                appendLine("Relayed via ${gateway.displayTarget}")
                val output = outcome.combinedOutput
                if (output.isNotEmpty()) appendLine(output)
                if (!outcome.ok) outcome.error?.let { appendLine(it) }
                if (outcome.ok) append("Give it 30-60 seconds, then refresh the status.")
            }.trim(),
            hostKeyChanged = outcome.hostKeyChanged,
            presentedFingerprint = outcome.hostKeyFingerprint.orEmpty(),
        )
    }

    private suspend fun runPowerCommand(
        server: Server,
        rawCommand: String,
        action: ActionType,
        label: String,
    ): ActionResult {
        val command = rawCommand.trim().ifEmpty {
            if (action == ActionType.REBOOT) Server.DEFAULT_REBOOT else Server.DEFAULT_SHUTDOWN
        }
        val needsSudoPassword = server.effectiveUseSudo && server.sudoPassword.isNotEmpty()

        val wrapped = when {
            !server.effectiveUseSudo -> command
            needsSudoPassword -> "sudo -S -p '' $command"
            else -> "sudo -n $command"
        }

        val outcome = SshClient.exec(
            server = server,
            command = wrapped,
            stdin = if (needsSudoPassword) server.sudoPassword + "\n" else null,
            expectDisconnect = true,
        )
        rememberHostKey(server, outcome)

        val result = ActionResult(
            success = outcome.ok,
            summary = if (outcome.ok) "$label sent to ${server.name}" else "$label failed",
            detail = buildString {
                appendLine("$ $wrapped")
                val output = outcome.combinedOutput
                if (output.isNotEmpty()) appendLine(output)
                if (!outcome.ok) {
                    outcome.error?.let { appendLine(it) }
                    appendLine(sudoHint(outcome, server))
                } else if (outcome.exitCode == -1) {
                    appendLine("The link dropped straight after the command — the machine is going down.")
                }
            }.trim(),
            hostKeyChanged = outcome.hostKeyChanged,
            presentedFingerprint = outcome.hostKeyFingerprint.orEmpty(),
        )
        log(server, action, result)
        return result
    }

    private fun sudoHint(outcome: SshOutcome, server: Server): String {
        val text = outcome.combinedOutput.lowercase()
        return when {
            text.contains("a password is required") || text.contains("sudo: a terminal is required") ->
                "sudo wants a password. Add the sudo password in the server settings, or allow " +
                    "NOPASSWD for the power command in /etc/sudoers."
            text.contains("incorrect password") || text.contains("sorry, try again") ->
                "The stored sudo password was rejected."
            text.contains("is not in the sudoers file") ->
                "${server.username} is not allowed to run sudo on this host."
            text.contains("command not found") ->
                "That command does not exist on the server. Try `systemctl poweroff` " +
                    "or the full path, e.g. /sbin/shutdown -h now."
            else -> ""
        }
    }

    /** Pins the key on first successful contact so a later swap is caught. */
    private fun rememberHostKey(server: Server, outcome: SshOutcome) {
        val fingerprint = outcome.hostKeyFingerprint ?: return
        if (outcome.hostKeyChanged) return
        if (server.hostKeyFingerprint.isBlank()) servers.rememberHostKey(server.id, fingerprint)
    }

    private fun buildDetail(outcome: SshOutcome): String = buildString {
        val output = outcome.combinedOutput
        if (output.isNotEmpty()) appendLine(output)
        if (!outcome.ok) outcome.error?.let { appendLine(it) }
    }.trim()

    private fun log(server: Server, action: ActionType, result: ActionResult, extra: String = "") {
        logs.record(
            serverId = server.id,
            serverName = server.name,
            action = action,
            success = result.success,
            message = result.summary,
            detail = listOf(extra, result.detail).filter { it.isNotBlank() }.joinToString("\n"),
        )
    }

    companion object {
        /**
         * A magic packet cannot be routed, so when the phone is off-network we ask an always-on
         * box on the target's LAN to emit it. Tries wakeonlan, then python3, then etherwake so it
         * works on a stock server without extra packages in most cases.
         */
        fun gatewayWakeCommand(
            mac: String,
            broadcast: String,
            port: Int,
            secureOn: String = "",
        ): String {
            val safeMac = sanitize(mac)
            val safeBroadcast = sanitize(broadcast)
            val safePort = port.coerceIn(1, 65535)
            val safeSecureOn = sanitize(secureOn)

            val python = listOf(
                "import socket,sys",
                "mac=sys.argv[1].replace(\":\",\"\").replace(\"-\",\"\")",
                "sec=sys.argv[4].replace(\":\",\"\").replace(\"-\",\"\")",
                "pkt=bytes.fromhex(\"ff\"*6+mac*16+sec)",
                "s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)",
                "s.setsockopt(socket.SOL_SOCKET,socket.SO_BROADCAST,1)",
                "s.sendto(pkt,(sys.argv[2],int(sys.argv[3])))",
                "print(\"magic packet sent to \"+sys.argv[2]+\":\"+sys.argv[3])",
            ).joinToString("\n")

            val pythonBranch =
                "python3 -c '$python' '$safeMac' '$safeBroadcast' '$safePort' '$safeSecureOn'"

            // wakeonlan has no portable SecureOn flag, so skip it when a password is set.
            val wakeonlanBranch =
                if (safeSecureOn.isEmpty()) {
                    "if command -v wakeonlan >/dev/null 2>&1; then " +
                        "wakeonlan -i '$safeBroadcast' -p '$safePort' '$safeMac'; " +
                        "elif command -v python3 >/dev/null 2>&1; then $pythonBranch; "
                } else {
                    "if command -v python3 >/dev/null 2>&1; then $pythonBranch; "
                }

            return wakeonlanBranch +
                "elif command -v etherwake >/dev/null 2>&1; then " +
                "sudo -n etherwake '$safeMac' 2>/dev/null || etherwake '$safeMac'; " +
                "else echo 'no wake tool on this host - install wakeonlan or python3' >&2; exit 1; fi"
        }

        /** These values reach a remote shell, so keep them to characters that cannot escape quoting. */
        private fun sanitize(value: String): String =
            value.trim().filter { it.isLetterOrDigit() || it == '.' || it == ':' || it == '-' }
    }
}
