package com.lalatendu.poweroffremote.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.lalatendu.poweroffremote.data.model.AuthMethod
import com.lalatendu.poweroffremote.data.model.Server
import com.lalatendu.poweroffremote.data.model.WakeMethod
import com.lalatendu.poweroffremote.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditorScreen(
    initial: Server,
    isNew: Boolean,
    otherServers: List<Server>,
    onBack: () -> Unit,
    onSave: (Server) -> Unit,
) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPassword by remember { mutableStateOf(false) }
    var showSudoPassword by remember { mutableStateOf(false) }
    var gatewayMenuOpen by remember { mutableStateOf(false) }

    val keyPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            error = "Could not read that file"
        } else {
            draft = draft.copy(privateKeyPem = text.trim(), authMethod = AuthMethod.PRIVATE_KEY)
            error = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "Add server" else "Edit server") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val problem = draft.validate()
                        if (problem != null) error = problem else onSave(draft.normalised())
                    }) { Text("Save") }
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
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            SectionCard(title = "Server") {
                Field("Name", draft.name, "Home lab", onChange = { draft = draft.copy(name = it) })
                Field(
                    "Host or IP", draft.host, "192.168.1.200",
                    onChange = { draft = draft.copy(host = it) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(
                        label = "SSH port",
                        value = draft.port,
                        modifier = Modifier.weight(1f),
                        onChange = { draft = draft.copy(port = it) },
                    )
                    NumberField(
                        label = "Timeout (s)",
                        value = draft.connectTimeoutSec,
                        modifier = Modifier.weight(1f),
                        onChange = { draft = draft.copy(connectTimeoutSec = it.coerceIn(3, 120)) },
                    )
                }
                Field(
                    "Username", draft.username, "root",
                    onChange = { draft = draft.copy(username = it) },
                )
            }

            SectionCard(title = "Authentication") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draft.authMethod == AuthMethod.PASSWORD,
                        onClick = { draft = draft.copy(authMethod = AuthMethod.PASSWORD) },
                        label = { Text("Password") },
                    )
                    FilterChip(
                        selected = draft.authMethod == AuthMethod.PRIVATE_KEY,
                        onClick = { draft = draft.copy(authMethod = AuthMethod.PRIVATE_KEY) },
                        label = { Text("Private key") },
                    )
                }

                if (draft.authMethod == AuthMethod.PASSWORD) {
                    SecretField(
                        label = "SSH password",
                        value = draft.password,
                        visible = showPassword,
                        onToggle = { showPassword = !showPassword },
                        onChange = { draft = draft.copy(password = it) },
                    )
                } else {
                    OutlinedTextField(
                        value = draft.privateKeyPem,
                        onValueChange = { draft = draft.copy(privateKeyPem = it) },
                        label = { Text("Private key (OpenSSH or PEM)") },
                        placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 220.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                    OutlinedButton(
                        onClick = { keyPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Import key from a file") }
                    SecretField(
                        label = "Key passphrase (optional)",
                        value = draft.privateKeyPassphrase,
                        visible = false,
                        onToggle = {},
                        showToggle = false,
                        onChange = { draft = draft.copy(privateKeyPassphrase = it) },
                    )
                }
            }

            SectionCard(title = "Privileges") {
                if (draft.username.trim() == "root") {
                    Text(
                        "Connecting as root — sudo is not used.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ToggleRow(
                        label = "Run power commands with sudo",
                        checked = draft.useSudo,
                        onChange = { draft = draft.copy(useSudo = it) },
                    )
                    if (draft.useSudo) {
                        SecretField(
                            label = "sudo password (blank = NOPASSWD)",
                            value = draft.sudoPassword,
                            visible = showSudoPassword,
                            onToggle = { showSudoPassword = !showSudoPassword },
                            onChange = { draft = draft.copy(sudoPassword = it) },
                        )
                        Text(
                            "Leave this blank if /etc/sudoers grants NOPASSWD for the shutdown " +
                                "command — that is the safer setup.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SectionCard(title = "Commands") {
                Field(
                    "Shutdown", draft.shutdownCommand, Server.DEFAULT_SHUTDOWN,
                    mono = true,
                    onChange = { draft = draft.copy(shutdownCommand = it) },
                )
                Field(
                    "Reboot", draft.rebootCommand, Server.DEFAULT_REBOOT,
                    mono = true,
                    onChange = { draft = draft.copy(rebootCommand = it) },
                )
                Text(
                    "Use `systemctl poweroff` on systemd hosts that restrict shutdown, " +
                        "or a full path such as /sbin/shutdown -h now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = "Wake-on-LAN") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draft.wakeMethod == WakeMethod.BROADCAST,
                        onClick = { draft = draft.copy(wakeMethod = WakeMethod.BROADCAST) },
                        label = { Text("From phone") },
                    )
                    FilterChip(
                        selected = draft.wakeMethod == WakeMethod.GATEWAY,
                        onClick = { draft = draft.copy(wakeMethod = WakeMethod.GATEWAY) },
                        label = { Text("Via gateway") },
                    )
                    FilterChip(
                        selected = draft.wakeMethod == WakeMethod.NONE,
                        onClick = { draft = draft.copy(wakeMethod = WakeMethod.NONE) },
                        label = { Text("Off") },
                    )
                }

                if (draft.wakeMethod != WakeMethod.NONE) {
                    Field(
                        "MAC address", draft.macAddress, "aa:bb:cc:dd:ee:ff",
                        mono = true,
                        onChange = { draft = draft.copy(macAddress = it) },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = draft.wolBroadcast,
                            onValueChange = { draft = draft.copy(wolBroadcast = it) },
                            label = { Text("Broadcast") },
                            placeholder = { Text("auto") },
                            singleLine = true,
                            modifier = Modifier.weight(2f),
                        )
                        NumberField(
                            label = "Port",
                            value = draft.wolPort,
                            modifier = Modifier.weight(1f),
                            onChange = { draft = draft.copy(wolPort = it) },
                        )
                    }
                    Field(
                        "SecureOn password (optional)", draft.wolSecureOn, "11-22-33-44-55-66",
                        mono = true,
                        onChange = { draft = draft.copy(wolSecureOn = it) },
                    )
                }

                when (draft.wakeMethod) {
                    WakeMethod.BROADCAST -> Text(
                        "The phone broadcasts the magic packet itself. It only works when the " +
                            "phone is on the same network as the server — not over mobile data or a VPN.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    WakeMethod.GATEWAY -> {
                        val gateway = otherServers.firstOrNull { it.id == draft.wakeGatewayId }
                        Box {
                            OutlinedButton(
                                onClick = { gatewayMenuOpen = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = otherServers.isNotEmpty(),
                            ) {
                                Text(
                                    text = gateway?.name
                                        ?: if (otherServers.isEmpty()) "Add another server first"
                                        else "Choose the relay server",
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = gatewayMenuOpen,
                                onDismissRequest = { gatewayMenuOpen = false },
                            ) {
                                otherServers.forEach { candidate ->
                                    DropdownMenuItem(
                                        text = { Text("${candidate.name} (${candidate.host})") },
                                        onClick = {
                                            draft = draft.copy(wakeGatewayId = candidate.id)
                                            gatewayMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                        Text(
                            "An always-on machine on the target's network is asked over SSH to " +
                                "emit the magic packet. This is what works from anywhere.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    WakeMethod.NONE -> Unit
                }
            }

            SectionCard(title = "Notes") {
                OutlinedTextField(
                    value = draft.notes,
                    onValueChange = { draft = draft.copy(notes = it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp),
                    placeholder = { Text("Anything you want to remember about this box") },
                )
            }

            Button(
                onClick = {
                    val problem = draft.validate()
                    if (problem != null) error = problem else onSave(draft.normalised())
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (isNew) "Add server" else "Save changes") }
        }
    }
}

private fun Server.normalised(): Server = copy(
    name = name.trim(),
    host = host.trim(),
    username = username.trim(),
    macAddress = macAddress.trim().lowercase(),
    wolBroadcast = wolBroadcast.trim(),
    wolSecureOn = wolSecureOn.trim(),
    shutdownCommand = shutdownCommand.trim().ifEmpty { Server.DEFAULT_SHUTDOWN },
    rebootCommand = rebootCommand.trim().ifEmpty { Server.DEFAULT_REBOOT },
    wakeGatewayId = if (wakeMethod == WakeMethod.GATEWAY) wakeGatewayId else "",
)

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    mono: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        textStyle = if (mono) {
            MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        } else MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun NumberField(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text ->
            val digits = text.filter { it.isDigit() }.take(5)
            onChange(digits.toIntOrNull() ?: 0)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun SecretField(
    label: String,
    value: String,
    visible: Boolean,
    onToggle: () -> Unit,
    showToggle: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            if (showToggle) {
                IconButton(onClick = onToggle) {
                    Icon(
                        imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (visible) "Hide" else "Show",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
