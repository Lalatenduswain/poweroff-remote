package com.lalatendu.poweroffremote.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lalatendu.poweroffremote.data.model.AuthMethod
import com.lalatendu.poweroffremote.data.model.Server
import com.lalatendu.poweroffremote.data.model.WakeMethod
import com.lalatendu.poweroffremote.data.store.AppSettings
import com.lalatendu.poweroffremote.domain.ActionResult
import com.lalatendu.poweroffremote.domain.ServerStatus
import com.lalatendu.poweroffremote.ui.components.ConfirmDialog
import com.lalatendu.poweroffremote.ui.components.ConsoleOutput
import com.lalatendu.poweroffremote.ui.components.LabelledRow
import com.lalatendu.poweroffremote.ui.components.SectionCard
import com.lalatendu.poweroffremote.ui.components.StatusDot
import com.lalatendu.poweroffremote.ui.components.statusLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDetailScreen(
    server: Server,
    gatewayName: String?,
    status: ServerStatus,
    busy: Boolean,
    lastResult: ActionResult?,
    settings: AppSettings,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPowerOff: () -> Unit,
    onReboot: () -> Unit,
    onWake: () -> Unit,
    onRefresh: () -> Unit,
    onTest: () -> Unit,
    onRunCommand: (String) -> Unit,
    onTrustNewHostKey: () -> Unit,
) {
    var confirming by remember { mutableStateOf<PendingAction?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("") }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(server.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit")
                    }
                    IconButton(onClick = { deleting = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(16.dp, 8.dp, 16.dp, 32.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = "Status") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(status = status, busy = busy)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = statusLabel(status, busy),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRefresh, enabled = !busy) { Text("Refresh") }
                }
                LabelledRow("Address", server.displayTarget)
                LabelledRow(
                    "Authentication",
                    if (server.authMethod == AuthMethod.PRIVATE_KEY) "Private key" else "Password",
                )
                LabelledRow(
                    "Privilege",
                    if (server.effectiveUseSudo) {
                        if (server.sudoPassword.isNotEmpty()) "sudo (password stored)" else "sudo -n"
                    } else "runs as ${server.username}",
                )
                if (server.macAddress.isNotBlank()) LabelledRow("MAC", server.macAddress)
                LabelledRow(
                    "Wake",
                    when (server.wakeMethod) {
                        WakeMethod.BROADCAST -> "Magic packet from this phone"
                        WakeMethod.GATEWAY -> "Relayed via ${gatewayName ?: "a missing server"}"
                        WakeMethod.NONE -> "Not configured"
                    },
                )
            }

            SectionCard(title = "Power") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { confirming = PendingAction.PowerOff },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Icon(Icons.Filled.PowerSettingsNew, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Power off")
                    }
                    OutlinedButton(
                        onClick = { confirming = PendingAction.Reboot },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.RestartAlt, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Reboot")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onWake,
                        enabled = !busy && server.wakeMethod != WakeMethod.NONE,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.WbSunny, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Wake")
                    }
                    OutlinedButton(
                        onClick = onTest,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Test SSH")
                    }
                }
            }

            SectionCard(title = "Run a command") {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Shell command") },
                    placeholder = { Text("uptime") },
                    singleLine = false,
                    maxLines = 3,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Button(
                    onClick = { onRunCommand(command) },
                    enabled = !busy && command.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Run")
                }
                Text(
                    text = "Runs as ${server.username}. sudo is only applied to the power commands.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (lastResult != null) {
                SectionCard(title = "Last result") {
                    Text(
                        text = lastResult.summary,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (lastResult.success) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                    if (lastResult.hostKeyChanged) {
                        Text(
                            text = "The host key changed. If you rebuilt or reinstalled this " +
                                "machine that is expected — otherwise stop and investigate.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onTrustNewHostKey) { Text("Forget the pinned key") }
                    }
                    ConsoleOutput(lastResult.detail)
                }
            }

            SectionCard(title = "Host key") {
                Text(
                    text = server.hostKeyFingerprint.ifBlank { "Not pinned yet — trusted on first connect" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                if (server.hostKeyFingerprint.isNotBlank()) {
                    TextButton(onClick = onTrustNewHostKey) { Text("Forget this key") }
                }
            }

            if (server.notes.isNotBlank()) {
                SectionCard(title = "Notes") { Text(server.notes) }
            }
        }
    }

    confirming?.let { action ->
        val isPowerOff = action == PendingAction.PowerOff
        val skipConfirm = !settings.confirmPowerOff
        if (skipConfirm) {
            confirming = null
            if (isPowerOff) onPowerOff() else onReboot()
        } else {
            ConfirmDialog(
                title = if (isPowerOff) "Power off ${server.name}?" else "Reboot ${server.name}?",
                body = "${server.displayTarget} will run " +
                    "`${if (isPowerOff) server.shutdownCommand else server.rebootCommand}`.",
                confirmLabel = if (isPowerOff) "Power off" else "Reboot",
                requiredPhrase = if (settings.typeNameToConfirm && isPowerOff) server.name else null,
                onConfirm = {
                    confirming = null
                    if (isPowerOff) onPowerOff() else onReboot()
                },
                onDismiss = { confirming = null },
            )
        }
    }

    if (deleting) {
        ConfirmDialog(
            title = "Delete ${server.name}?",
            body = "The stored credentials for this server are erased from the device.",
            confirmLabel = "Delete",
            onConfirm = {
                deleting = false
                onDelete()
            },
            onDismiss = { deleting = false },
        )
    }
}

private enum class PendingAction { PowerOff, Reboot }
