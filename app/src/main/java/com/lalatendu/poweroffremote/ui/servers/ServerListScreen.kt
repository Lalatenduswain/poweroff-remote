package com.lalatendu.poweroffremote.ui.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lalatendu.poweroffremote.data.model.Server
import com.lalatendu.poweroffremote.data.model.WakeMethod
import com.lalatendu.poweroffremote.data.store.AppSettings
import com.lalatendu.poweroffremote.domain.ServerStatus
import com.lalatendu.poweroffremote.ui.components.ConfirmDialog
import com.lalatendu.poweroffremote.ui.components.StatusDot
import com.lalatendu.poweroffremote.ui.components.statusLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    servers: List<Server>,
    statuses: Map<String, ServerStatus>,
    busy: Set<String>,
    settings: AppSettings,
    snackbarHostState: SnackbarHostState,
    onOpen: (Server) -> Unit,
    onAdd: () -> Unit,
    onPowerOff: (Server) -> Unit,
    onWake: (Server) -> Unit,
    onRefreshAll: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var pendingPowerOff by remember { mutableStateOf<Server?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Servers") },
                actions = {
                    IconButton(onClick = onRefreshAll, enabled = servers.isNotEmpty()) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh all")
                    }
                    IconButton(onClick = onOpenLogs) {
                        Icon(Icons.Filled.History, contentDescription = "Activity log")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "Add server")
            }
        },
    ) { padding ->
        if (servers.isEmpty()) {
            EmptyState(modifier = Modifier.padding(padding), onAdd = onAdd)
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(servers, key = { it.id }) { server ->
                ServerCard(
                    server = server,
                    status = statuses[server.id] ?: ServerStatus.UNKNOWN,
                    busy = server.id in busy,
                    onOpen = { onOpen(server) },
                    onPowerOff = {
                        if (settings.confirmPowerOff) pendingPowerOff = server else onPowerOff(server)
                    },
                    onWake = { onWake(server) },
                )
            }
        }
    }

    pendingPowerOff?.let { server ->
        ConfirmDialog(
            title = "Power off ${server.name}?",
            body = "${server.displayTarget} will run `${server.shutdownCommand}`. " +
                "You will need Wake-on-LAN or physical access to bring it back.",
            confirmLabel = "Power off",
            requiredPhrase = if (settings.typeNameToConfirm) server.name else null,
            onConfirm = {
                pendingPowerOff = null
                onPowerOff(server)
            },
            onDismiss = { pendingPowerOff = null },
        )
    }
}

@Composable
private fun ServerCard(
    server: Server,
    status: ServerStatus,
    busy: Boolean,
    onOpen: () -> Unit,
    onPowerOff: () -> Unit,
    onWake: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status = status, busy = busy)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = server.displayTarget,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = statusLabel(status, busy),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (server.wakeMethod != WakeMethod.NONE) {
                    OutlinedButton(
                        onClick = onWake,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.WbSunny, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Wake")
                    }
                }
                OutlinedButton(
                    onClick = onPowerOff,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Filled.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Power off", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier, onAdd: () -> Unit) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Filled.PowerSettingsNew,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "No servers yet",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = "Add a machine with its SSH details and MAC address, " +
                    "then power it off or wake it from here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(onClick = onAdd, modifier = Modifier.padding(top = 12.dp)) {
                Text("Add a server")
            }
        }
    }
}
