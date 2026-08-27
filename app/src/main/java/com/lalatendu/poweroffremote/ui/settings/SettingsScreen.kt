package com.lalatendu.poweroffremote.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lalatendu.poweroffremote.BuildConfig
import com.lalatendu.poweroffremote.data.store.AppSettings
import com.lalatendu.poweroffremote.ui.components.ConfirmDialog
import com.lalatendu.poweroffremote.ui.components.SectionCard
import com.lalatendu.poweroffremote.ui.lock.AppLock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    serverCount: Int,
    logCount: Int,
    onBack: () -> Unit,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onClearLogs: () -> Unit,
    onEraseEverything: () -> Unit,
) {
    val context = LocalContext.current
    val lockAvailable = remember { AppLock.isAvailable(context) }
    var erasing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            SectionCard(title = "Security") {
                SettingRow(
                    label = "Lock the app",
                    description = if (lockAvailable) {
                        "Ask for fingerprint, face or the device PIN before showing anything."
                    } else {
                        "Unavailable — set a screen lock on this device first."
                    },
                    checked = settings.requireUnlock && lockAvailable,
                    enabled = lockAvailable,
                    onChange = { value -> onChange { it.copy(requireUnlock = value) } },
                )
                SettingRow(
                    label = "Block screenshots",
                    description = "Hides the app in the recents list and blocks screen capture.",
                    checked = settings.blockScreenshots,
                    onChange = { value -> onChange { it.copy(blockScreenshots = value) } },
                )
            }

            SectionCard(title = "Safety") {
                SettingRow(
                    label = "Confirm before power off",
                    description = "Always ask before shutting a machine down or rebooting it.",
                    checked = settings.confirmPowerOff,
                    onChange = { value -> onChange { it.copy(confirmPowerOff = value) } },
                )
                SettingRow(
                    label = "Type the name to confirm",
                    description = "For production boxes: the confirm button stays disabled until " +
                        "you type the server name.",
                    checked = settings.typeNameToConfirm,
                    enabled = settings.confirmPowerOff,
                    onChange = { value -> onChange { it.copy(typeNameToConfirm = value) } },
                )
            }

            SectionCard(title = "Appearance") {
                SettingRow(
                    label = "Use the system colour scheme",
                    description = "Follow the wallpaper colours on Android 12 and later.",
                    checked = settings.dynamicColor,
                    onChange = { value -> onChange { it.copy(dynamicColor = value) } },
                )
            }

            SectionCard(title = "Stored data") {
                Text(
                    text = "$serverCount server${if (serverCount == 1) "" else "s"} and " +
                        "$logCount log entr${if (logCount == 1) "y" else "ies"} on this device, " +
                        "encrypted with a key held in the Android Keystore.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onClearLogs,
                    enabled = logCount > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Clear the activity log") }
                OutlinedButton(
                    onClick = { erasing = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Erase everything", color = MaterialTheme.colorScheme.error)
                }
            }

            SectionCard(title = "About") {
                Text(
                    text = "PowerOff Remote ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Shutdown and reboot run over SSH. Power on uses a Wake-on-LAN magic " +
                        "packet, sent either from this phone or relayed by another server you " +
                        "have already saved. Credentials never leave the device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (erasing) {
        ConfirmDialog(
            title = "Erase everything?",
            body = "Every saved server, credential and log entry is deleted and the encryption " +
                "key is destroyed. This cannot be undone.",
            confirmLabel = "Erase",
            requiredPhrase = "erase",
            onConfirm = {
                erasing = false
                onEraseEverything()
            },
            onDismiss = { erasing = false },
        )
    }
}

@Composable
private fun SettingRow(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
