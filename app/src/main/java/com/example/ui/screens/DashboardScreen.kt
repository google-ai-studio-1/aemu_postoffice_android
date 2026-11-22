package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.relay.SessionMode
import com.example.service.ServerRunStatus
import com.example.service.ServerRuntimeState
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.MonoMetricStyle
import com.example.ui.theme.StatusOnlineGreenDark
import com.example.ui.theme.StatusOnlineGreenLight
import java.util.Locale

@Composable
fun DashboardScreen(
    state: ServerRuntimeState,
    isIgnoringBatteryOptimizations: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onWakeLockToggle: (Boolean) -> Unit,
    onBatteryOptimizationToggle: (Boolean) -> Unit,
    onCopied: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val isDark = isSystemInDarkTheme()
    val onlineGreen = if (isDark) StatusOnlineGreenDark else StatusOnlineGreenLight

    val playerCount = state.adhocctlSnapshot.clients.size
    val gameCount = state.adhocctlSnapshot.games.size
    val groupCount = state.adhocctlSnapshot.games.values.sumOf { game ->
        game.groups.values.count { it.channel > 0 }
    }
    val pdpCount = state.relaySnapshot.sessions.count { it.mode == SessionMode.PDP }
    val ptpCount = state.relaySnapshot.sessions.size - pdpCount
    val pendingRelay = state.relaySnapshot.pendingCount

    val primaryIp = state.localAddresses.primaryIpv4
    val httpUrl = "http://$primaryIp:${state.activeConfig.httpStatusServerPort}/"

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                ServerStatusHeroCard(
                    state = state,
                    onlineColor = onlineGreen,
                    onStart = onStart,
                    onStop = onStop,
                    onRestart = onRestart
                )
            }

            if (state.status == ServerRunStatus.RUNNING && state.pendingRestartKeys.isNotEmpty()) {
                item {
                    RestartRequiredBanner(
                        keys = state.pendingRestartKeys,
                        onRestart = onRestart
                    )
                }
            }

            item {
                MetricsGrid(
                    players = playerCount,
                    games = gameCount,
                    groups = groupCount,
                    relayTotal = state.relaySnapshot.sessions.size,
                    pdpCount = pdpCount,
                    ptpCount = ptpCount,
                    pendingCount = pendingRelay
                )
            }

            item {
                AddressEndpointsCard(
                    primaryIp = primaryIp,
                    adhocctlPort = state.activeConfig.adhocctlPort,
                    relayPort = state.activeConfig.port,
                    httpUrl = httpUrl,
                    httpEnabled = state.activeConfig.enableAdhocctl,
                    extraIpv4 = state.localAddresses.ipv4Addresses.filter { it != primaryIp },
                    onCopyAddress = { label, value ->
                        clipboard.setText(AnnotatedString(value))
                        onCopied(label)
                    }
                )
            }

            item {
                WakeLockCard(
                    enabled = state.wakeLockEnabled,
                    onToggle = onWakeLockToggle
                )
            }

            item {
                BatteryOptimizationCard(
                    isIgnoring = isIgnoringBatteryOptimizations,
                    onToggle = onBatteryOptimizationToggle
                )
            }

            item {
                ActiveServicesCard(state = state, onlineColor = onlineGreen)
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerStatusHeroCard(
    state: ServerRuntimeState,
    onlineColor: Color,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit
) {
    val isRunning = state.status == ServerRunStatus.RUNNING
    val isStarting = state.status == ServerRunStatus.STARTING
    val isError = state.status == ServerRunStatus.ERROR

    val statusDotColor = when (state.status) {
        ServerRunStatus.RUNNING -> onlineColor
        ServerRunStatus.STARTING -> MaterialTheme.colorScheme.tertiary
        ServerRunStatus.ERROR -> MaterialTheme.colorScheme.error
        ServerRunStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val statusLabel = when (state.status) {
        ServerRunStatus.RUNNING -> stringResource(R.string.status_running)
        ServerRunStatus.STARTING -> stringResource(R.string.status_starting)
        ServerRunStatus.ERROR -> stringResource(R.string.status_error)
        ServerRunStatus.STOPPED -> stringResource(R.string.status_stopped)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("server_status_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.dp,
            color = if (isRunning) onlineColor.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(statusDotColor)
                        )
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = state.activeConfig.metadataName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = stringResource(R.string.label_uptime),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatUptime(state.uptimeSeconds),
                            style = MonoCodeStyle,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            if (isError && !state.errorMessage.isNullOrEmpty()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = state.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    )
                }
            }

            if (isRunning) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onStop,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("stop_server_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_power_stop),
                            contentDescription = stringResource(R.string.cd_power_toggle),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.action_stop_server))
                    }

                    OutlinedButton(
                        onClick = onRestart,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("restart_server_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(text = stringResource(R.string.action_restart_server))
                    }
                }
            } else {
                Button(
                    onClick = onStart,
                    enabled = !isStarting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("start_server_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_power_start),
                        contentDescription = stringResource(R.string.cd_power_toggle),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isStarting) {
                            stringResource(R.string.status_starting)
                        } else {
                            stringResource(R.string.action_start_server)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RestartRequiredBanner(
    keys: Set<String>,
    onRestart: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("restart_required_banner"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_warning_restart),
                    contentDescription = stringResource(R.string.cd_warning),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = stringResource(R.string.warning_restart_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = keys.joinToString(", "),
                style = MonoCodeStyle,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            OutlinedButton(
                onClick = onRestart,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("banner_restart_button"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(text = stringResource(R.string.action_restart_server))
            }
        }
    }
}

@Composable
private fun MetricsGrid(
    players: Int,
    games: Int,
    groups: Int,
    relayTotal: Int,
    pdpCount: Int,
    ptpCount: Int,
    pendingCount: Int
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricTile(
                title = stringResource(R.string.label_players_online),
                value = players.toString(),
                subtitle = "$games ${stringResource(R.string.label_active_games).lowercase()}",
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                title = stringResource(R.string.label_active_groups),
                value = groups.toString(),
                subtitle = "Active rooms",
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricTile(
                title = stringResource(R.string.label_relay_sessions),
                value = relayTotal.toString(),
                subtitle = "PDP $pdpCount | PTP $ptpCount",
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                title = stringResource(R.string.label_pending_sessions),
                value = pendingCount.toString(),
                subtitle = "Awaiting handshake",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MetricTile(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MonoMetricStyle,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AddressEndpointsCard(
    primaryIp: String,
    adhocctlPort: Int,
    relayPort: Int,
    httpUrl: String,
    httpEnabled: Boolean,
    extraIpv4: List<String>,
    onCopyAddress: (String, String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            EndpointBlock(
                iconRes = R.drawable.ic_wifi_lan,
                iconCd = stringResource(R.string.cd_lan_address),
                label = stringResource(R.string.label_local_address),
                value = primaryIp,
                caption = "Adhocctl :$adhocctlPort  |  Relay :$relayPort",
                copyTag = "copy_lan_ip_button",
                onCopy = { onCopyAddress("server IP", primaryIp) }
            )

            if (httpEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                EndpointBlock(
                    iconRes = R.drawable.ic_http_web,
                    iconCd = stringResource(R.string.cd_http_status),
                    label = stringResource(R.string.label_http_status),
                    value = httpUrl,
                    caption = "/data.json  |  /metadata.json",
                    copyTag = "copy_http_url_button",
                    onCopy = { onCopyAddress("HTTP status URL", httpUrl) }
                )
            }

            if (extraIpv4.isNotEmpty()) {
                Text(
                    text = "Other interfaces: ${extraIpv4.joinToString(", ")}",
                    style = MonoCodeStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EndpointBlock(
    iconRes: Int,
    iconCd: String,
    label: String,
    value: String,
    caption: String,
    copyTag: String,
    onCopy: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = iconCd,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(18.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onCopy,
                modifier = Modifier
                    .size(48.dp)
                    .testTag(copyTag)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_copy),
                    contentDescription = stringResource(R.string.cd_copy_address),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = value,
                    style = MonoCodeStyle.copy(fontSize = 14.sp, lineHeight = 20.sp),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WakeLockCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lock_wake),
                    contentDescription = stringResource(R.string.cd_wakelock),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(20.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.label_wakelock_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.label_wakelock_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag("wakelock_switch")
            )
        }
    }
}

@Composable
private fun BatteryOptimizationCard(
    isIgnoring: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isIgnoring) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.tertiaryContainer
                }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_battery_unrestricted),
                    contentDescription = stringResource(R.string.cd_battery_opt),
                    tint = if (isIgnoring) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    },
                    modifier = Modifier
                        .padding(10.dp)
                        .size(20.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.label_battery_opt_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (isIgnoring) {
                        stringResource(R.string.label_battery_opt_enabled)
                    } else {
                        stringResource(R.string.label_battery_opt_disabled)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = isIgnoring,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag("battery_optimization_switch")
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveServicesCard(
    state: ServerRuntimeState,
    onlineColor: Color
) {
    val cfg = state.activeConfig
    val running = state.status == ServerRunStatus.RUNNING

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.label_transports),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ServiceStatusChip(
                    iconRes = R.drawable.ic_tcp_transport,
                    iconCd = stringResource(R.string.cd_transport_tcp),
                    label = "Relay TCP :${cfg.port}",
                    active = running,
                    onlineColor = onlineColor
                )
                ServiceStatusChip(
                    iconRes = R.drawable.ic_enet_udp,
                    iconCd = stringResource(R.string.cd_transport_enet),
                    label = if (cfg.enableEnet) "Relay ENet :${cfg.port}" else "ENet Disabled",
                    active = running && cfg.enableEnet,
                    onlineColor = onlineColor
                )
                ServiceStatusChip(
                    iconRes = R.drawable.ic_gamepad_psp,
                    iconCd = stringResource(R.string.cd_game_room),
                    label = if (cfg.enableAdhocctl) "Adhocctl :${cfg.adhocctlPort}" else "Adhocctl Off",
                    active = running && cfg.enableAdhocctl,
                    onlineColor = onlineColor
                )
                ServiceStatusChip(
                    iconRes = R.drawable.ic_http_web,
                    iconCd = stringResource(R.string.cd_http_status),
                    label = if (cfg.enableAdhocctl) "HTTP :${cfg.httpStatusServerPort}" else "HTTP Off",
                    active = state.httpRunning,
                    onlineColor = onlineColor
                )
                ServiceStatusChip(
                    iconRes = R.drawable.ic_lock_wake,
                    iconCd = stringResource(R.string.cd_wakelock),
                    label = if (cfg.strictMode) "Strict Mode" else "Open Relay",
                    active = cfg.strictMode,
                    onlineColor = MaterialTheme.colorScheme.primary
                )
                ServiceStatusChip(
                    iconRes = R.drawable.ic_wifi_lan,
                    iconCd = stringResource(R.string.cd_lan_address),
                    label = if (cfg.adhocctlRelayOnly) "Relay Only" else "Direct IPv4",
                    active = cfg.adhocctlRelayOnly,
                    onlineColor = MaterialTheme.colorScheme.secondary
                )
            }
            Text(
                text = "Bind ${cfg.ipAddr}  |  Relay threads: ${cfg.numThreads}  |  Adhocctl threads: ${cfg.adhocctlNumThreads}",
                style = MonoCodeStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ServiceStatusChip(
    iconRes: Int,
    iconCd: String,
    label: String,
    active: Boolean,
    onlineColor: Color
) {
    val bg = if (active) {
        onlineColor.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (active) onlineColor else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bg,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = iconCd,
                tint = fg,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = fg
            )
        }
    }
}

private fun formatUptime(totalSeconds: Long): String {
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}
