package com.lalatendu.poweroffremote.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.lalatendu.poweroffremote.R
import com.lalatendu.poweroffremote.appContainer
import com.lalatendu.poweroffremote.data.model.Server
import com.lalatendu.poweroffremote.data.model.WakeMethod
import com.lalatendu.poweroffremote.domain.ServerStatus

/**
 * Home-screen widget: the whole point of the app in one tap.
 *
 * It never performs a power action itself. Every button opens [WidgetConfirmActivity], which
 * re-applies the app lock and the confirmation step — a widget must not become a way around
 * either of them.
 *
 * Note this does put server names on the home screen, outside the app's FLAG_SECURE window. That
 * is inherent to having a widget at all; the credentials stay in the vault regardless.
 */
class PowerWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = context.appContainer
        // Snapshot outside provideContent: reading a StateFlow's value inside composition neither
        // recomposes nor is legal here. The widget refreshes through updateAll instead.
        val servers = container.servers.servers.value
        val statuses = container.runner.statuses.value
        val busy = container.runner.busy.value

        provideContent {
            GlanceTheme {
                WidgetBody(servers, statuses, busy)
            }
        }
    }

    @Composable
    private fun WidgetBody(
        servers: List<Server>,
        statuses: Map<String, ServerStatus>,
        busy: Set<String>,
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .padding(12.dp)
        ) {
            Text(
                text = "PowerOff Remote",
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = GlanceModifier.padding(bottom = 6.dp),
            )

            if (servers.isEmpty()) {
                Text(
                    text = "No servers yet. Open the app to add one.",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                )
                return@Column
            }

            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(servers, itemId = { it.id.hashCode().toLong() }) { server ->
                    ServerRow(
                        server = server,
                        status = statuses[server.id] ?: ServerStatus.UNKNOWN,
                        busy = server.id in busy,
                    )
                }
            }
        }
    }

    @Composable
    private fun ServerRow(server: Server, status: ServerStatus, busy: Boolean) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = GlanceModifier
                    .size(10.dp)
                    .cornerRadius(5.dp)
                    .background(statusColour(status, busy))
            ) {}

            Spacer(GlanceModifier.width(8.dp))

            Text(
                text = server.name,
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurface),
                modifier = GlanceModifier.defaultWeight(),
            )

            if (server.wakeMethod != WakeMethod.NONE) {
                ActionButton(
                    icon = R.drawable.ic_widget_wake,
                    description = "Wake ${server.name}",
                    server = server,
                    action = WidgetConfirmActivity.ACTION_WAKE,
                    enabled = !busy,
                )
                Spacer(GlanceModifier.width(4.dp))
            }
            ActionButton(
                icon = R.drawable.ic_widget_power,
                description = "Power off ${server.name}",
                server = server,
                action = WidgetConfirmActivity.ACTION_POWER_OFF,
                enabled = !busy,
            )
        }
    }

    @Composable
    private fun ActionButton(
        icon: Int,
        description: String,
        server: Server,
        action: String,
        enabled: Boolean,
    ) {
        val base = GlanceModifier
            .size(32.dp)
            .cornerRadius(16.dp)
            .background(GlanceTheme.colors.secondaryContainer)

        Box(
            modifier = if (enabled) {
                base.clickable(
                    actionStartActivity<WidgetConfirmActivity>(
                        actionParametersOf(
                            WidgetConfirmActivity.SERVER_ID_KEY to server.id,
                            WidgetConfirmActivity.ACTION_KEY to action,
                        )
                    )
                )
            } else base,
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = description,
                modifier = GlanceModifier.size(18.dp),
            )
        }
    }

    private fun statusColour(status: ServerStatus, busy: Boolean): androidx.glance.unit.ColorProvider =
        when {
            busy || status == ServerStatus.CHECKING -> androidx.glance.unit.ColorProvider(Color(0xFFFFB020))
            status == ServerStatus.UP -> androidx.glance.unit.ColorProvider(Color(0xFF22D3A5))
            status == ServerStatus.DOWN -> androidx.glance.unit.ColorProvider(Color(0xFFE5484D))
            else -> androidx.glance.unit.ColorProvider(Color(0xFF8A94A6))
        }

    companion object {
        /** Called whenever the vault or an action result changes, so the dots stay honest. */
        suspend fun refresh(context: Context) {
            runCatching { PowerWidget().updateAll(context.applicationContext) }
        }
    }
}

class PowerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PowerWidget()
}
