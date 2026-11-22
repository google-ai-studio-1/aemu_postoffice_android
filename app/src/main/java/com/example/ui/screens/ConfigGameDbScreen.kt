package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.common.ServerConfig
import com.example.ui.ConfigEditorUiState
import com.example.ui.theme.MonoCodeStyle

@Composable
fun ConfigGameDbScreen(
    editorState: ConfigEditorUiState,
    onUpdateConfigVisual: (ServerConfig) -> Unit,
    onSaveConfig: () -> Unit,
    onResetConfig: () -> Unit,
    onImportConfig: () -> Unit,
    onExportConfig: () -> Unit,
    onAddGameName: (String, String) -> Unit,
    onRemoveGameName: (String) -> Unit,
    onAddCrosslink: (String, String) -> Unit,
    onRemoveCrosslink: (String) -> Unit,
    onAddCodQuirk: (String) -> Unit,
    onRemoveCodQuirk: (String) -> Unit,
    onSaveGameDb: () -> Unit,
    onResetGameDb: () -> Unit,
    onImportGameDb: () -> Unit,
    onExportGameDb: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activeFileTab by rememberSaveable { mutableIntStateOf(0) }

    val isConfigTab = activeFileTab == 0
    val validationMessage = if (isConfigTab) editorState.configValidationMessage else editorState.gameDbValidationMessage
    val validationIsError = if (isConfigTab) editorState.configValidationIsError else editorState.gameDbValidationIsError

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = activeFileTab == 0,
                        onClick = { activeFileTab = 0 },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        modifier = Modifier.testTag("config_tab_server")
                    ) {
                        Text(
                            if (editorState.configDirty) {
                                "${stringResource(R.string.tab_server_config)} *"
                            } else {
                                stringResource(R.string.tab_server_config)
                            }
                        )
                    }
                    SegmentedButton(
                        selected = activeFileTab == 1,
                        onClick = { activeFileTab = 1 },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        modifier = Modifier.testTag("config_tab_gamedb")
                    ) {
                        Text(
                            if (editorState.gameDbDirty) {
                                "${stringResource(R.string.tab_game_db)} *"
                            } else {
                                stringResource(R.string.tab_game_db)
                            }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = if (isConfigTab) onImportConfig else onImportGameDb,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("config_import_button")
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_import_file),
                                contentDescription = stringResource(R.string.cd_import_json),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = if (isConfigTab) onExportConfig else onExportGameDb,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("config_export_button")
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_export_file),
                                contentDescription = stringResource(R.string.cd_export_json),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = if (isConfigTab) onResetConfig else onResetGameDb,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("config_reset_button")
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_reset_defaults),
                                contentDescription = stringResource(R.string.cd_reset_defaults),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Button(
                        onClick = if (isConfigTab) onSaveConfig else onSaveGameDb,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("config_save_button"),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_save_check),
                            contentDescription = stringResource(R.string.cd_save_changes),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_save))
                    }
                }

                if (!validationMessage.isNullOrEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (validationIsError) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.tertiaryContainer
                        }
                    ) {
                        Text(
                            text = validationMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (validationIsError) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp)
                        )
                    }
                }
            }

            if (isConfigTab) {
                VisualServerConfigForm(
                    config = editorState.configDraft,
                    onUpdate = onUpdateConfigVisual,
                    modifier = Modifier.weight(1f)
                )
            } else {
                GameDbVisualEditor(
                    gameDb = editorState.gameDbDraft,
                    onAddName = onAddGameName,
                    onRemoveName = onRemoveGameName,
                    onAddCrosslink = onAddCrosslink,
                    onRemoveCrosslink = onRemoveCrosslink,
                    onAddCodQuirk = onAddCodQuirk,
                    onRemoveCodQuirk = onRemoveCodQuirk,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun VisualServerConfigForm(
    config: ServerConfig,
    onUpdate: (ServerConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ConfigSectionCard(title = stringResource(R.string.section_network)) {
                ConfigTextField(
                    key = "ip_addr",
                    label = "Bind IP Address",
                    value = config.ipAddr,
                    restartRequired = true,
                    onValueChange = { onUpdate(config.copy(ipAddr = it)) }
                )
                ConfigIntField(
                    key = "port",
                    label = "Relay Port",
                    value = config.port,
                    restartRequired = true,
                    onValueChange = { onUpdate(config.copy(port = it)) }
                )
                ConfigIntField(
                    key = "adhocctl_port",
                    label = "Adhocctl Port",
                    value = config.adhocctlPort,
                    restartRequired = true,
                    onValueChange = { onUpdate(config.copy(adhocctlPort = it)) }
                )
                ConfigIntField(
                    key = "http_status_server_port",
                    label = "HTTP Status Server Port",
                    value = config.httpStatusServerPort,
                    restartRequired = true,
                    onValueChange = { onUpdate(config.copy(httpStatusServerPort = it)) }
                )
                ConfigSwitchRow(
                    key = "enable_enet",
                    label = "Enable ENet UDP Transport",
                    checked = config.enableEnet,
                    restartRequired = true,
                    onCheckedChange = { onUpdate(config.copy(enableEnet = it)) }
                )
                ConfigSwitchRow(
                    key = "enable_adhocctl",
                    label = "Enable Integrated Adhocctl Server",
                    checked = config.enableAdhocctl,
                    restartRequired = true,
                    onCheckedChange = { onUpdate(config.copy(enableAdhocctl = it)) }
                )
                ConfigSwitchRow(
                    key = "strict_mode",
                    label = "Strict Mode (verify Adhocctl lobby membership)",
                    checked = config.strictMode,
                    restartRequired = true,
                    onCheckedChange = { onUpdate(config.copy(strictMode = it)) }
                )
                ConfigSwitchRow(
                    key = "adhocctl_relay_only",
                    label = "Adhocctl Relay-Only Mode",
                    checked = config.adhocctlRelayOnly,
                    restartRequired = true,
                    onCheckedChange = { onUpdate(config.copy(adhocctlRelayOnly = it)) }
                )
            }
        }

        item {
            ConfigSectionCard(title = stringResource(R.string.section_relay)) {
                ConfigIntField(
                    key = "num_threads",
                    label = "Relay Worker Threads",
                    value = config.numThreads,
                    restartRequired = true,
                    onValueChange = { onUpdate(config.copy(numThreads = it)) }
                )
                ConfigLongField(
                    key = "target_tick_interval_ms",
                    label = "Active Tick Interval (ms)",
                    value = config.targetTickIntervalMs,
                    restartRequired = false,
                    onValueChange = { onUpdate(config.copy(targetTickIntervalMs = it)) }
                )
                ConfigLongField(
                    key = "target_tick_interval_idle_ms",
                    label = "Idle Tick Interval (ms)",
                    value = config.targetTickIntervalIdleMs,
                    restartRequired = false,
                    onValueChange = { onUpdate(config.copy(targetTickIntervalIdleMs = it)) }
                )
                ConfigLongField(
                    key = "session_init_time_limit_ms",
                    label = "Session Init Timeout (ms)",
                    value = config.sessionInitTimeLimitMs,
                    restartRequired = false,
                    onValueChange = { onUpdate(config.copy(sessionInitTimeLimitMs = it)) }
                )
                ConfigLongField(
                    key = "connect_time_limit_ms",
                    label = "PTP Connect Timeout (ms)",
                    value = config.connectTimeLimitMs,
                    restartRequired = false,
                    onValueChange = { onUpdate(config.copy(connectTimeLimitMs = it)) }
                )
                ConfigLongField(
                    key = "data_queue_size_limit_byte",
                    label = "Send Queue Size Limit (bytes)",
                    value = config.dataQueueSizeLimitByte,
                    restartRequired = false,
                    onValueChange = { onUpdate(config.copy(dataQueueSizeLimitByte = it)) }
                )
                ConfigIntField(
                    key = "max_num_sessions",
                    label = "Max Total Sessions",
                    value = config.maxNumSessions,
                    restartRequired = false,
                    onValueChange = { onUpdate(config.copy(maxNumSessions = it)) }
                )
                ConfigIntField(
                    key = "max_num_sessions_per_mac",
                    label = "Max Sessions Per MAC",
                    value = config.maxNumSessionsPerMac,
                    restartRequired = false,
                    onValueChange = { onUpdate(config.copy(maxNumSessionsPerMac = it)) }
                )
            }
        }

        item {
            ConfigSectionCard(title = stringResource(R.string.section_adhocctl)) {
                ConfigIntField(
                    key = "adhocctl_num_threads",
                    label = "Adhocctl Worker Threads",
                    value = config.adhocctlNumThreads,
                    restartRequired = true,
                    onValueChange = { onUpdate(config.copy(adhocctlNumThreads = it)) }
                )
                ConfigLongField(
                    key = "adhocctl_target_tick_interval_ms",
                    label = "Adhocctl Active Tick Interval (ms)",
                    value = config.adhocctlTargetTickIntervalMs,
                    restartRequired = false,
                    onValueChange = { onUpdate(config.copy(adhocctlTargetTickIntervalMs = it)) }
                )
                ConfigLongField(
                    key = "adhocctl_target_tick_interval_idle_ms",
                    label = "Adhocctl Idle Tick Interval (ms)",
                    value = config.adhocctlTargetTickIntervalIdleMs,
                    restartRequired = false,
                    onValueChange = { onUpdate(config.copy(adhocctlTargetTickIntervalIdleMs = it)) }
                )
                ConfigIntField(
                    key = "adhocctl_data_queue_size_limit_byte",
                    label = "Adhocctl Queue Limit (bytes)",
                    value = config.adhocctlDataQueueSizeLimitByte,
                    restartRequired = false,
                    onValueChange = { onUpdate(config.copy(adhocctlDataQueueSizeLimitByte = it)) }
                )
                ConfigLongField(
                    key = "adhocctl_timeout_ms",
                    label = "Adhocctl Keepalive Timeout (ms)",
                    value = config.adhocctlTimeoutMs,
                    restartRequired = false,
                    onValueChange = { onUpdate(config.copy(adhocctlTimeoutMs = it)) }
                )
                ConfigIntField(
                    key = "adhocctl_max_num_sessions",
                    label = "Adhocctl Max Sessions",
                    value = config.adhocctlMaxNumSessions,
                    restartRequired = false,
                    onValueChange = { onUpdate(config.copy(adhocctlMaxNumSessions = it)) }
                )
                ConfigIntField(
                    key = "adhocctl_max_num_sessions_per_ip",
                    label = "Adhocctl Max Sessions Per IP",
                    value = config.adhocctlMaxNumSessionsPerIp,
                    restartRequired = false,
                    onValueChange = { onUpdate(config.copy(adhocctlMaxNumSessionsPerIp = it)) }
                )
            }
        }

        item {
            ConfigSectionCard(title = stringResource(R.string.section_http_metadata)) {
                ConfigTextField(
                    key = "metadata_name",
                    label = "Server Name",
                    value = config.metadataName,
                    restartRequired = false,
                    onValueChange = { onUpdate(config.copy(metadataName = it)) }
                )
                ConfigTextField(
                    key = "metadata_location",
                    label = "Location",
                    value = config.metadataLocation,
                    restartRequired = false,
                    onValueChange = { onUpdate(config.copy(metadataLocation = it)) }
                )
                ConfigTextField(
                    key = "metadata_website",
                    label = "Website URL",
                    value = config.metadataWebsite,
                    restartRequired = false,
                    onValueChange = { onUpdate(config.copy(metadataWebsite = it)) }
                )
                ConfigTextField(
                    key = "metadata_admin_email",
                    label = "Admin Email",
                    value = config.metadataAdminEmail,
                    restartRequired = false,
                    onValueChange = { onUpdate(config.copy(metadataAdminEmail = it)) }
                )
                ConfigTextField(
                    key = "metadata_owner_name",
                    label = "Owner Name",
                    value = config.metadataOwnerName,
                    restartRequired = false,
                    onValueChange = { onUpdate(config.copy(metadataOwnerName = it)) }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ConfigSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
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
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            content()
        }
    }
}

@Composable
private fun ReloadBadge(restartRequired: Boolean) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (restartRequired) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
    ) {
        Text(
            text = if (restartRequired) {
                stringResource(R.string.tag_restart_required)
            } else {
                stringResource(R.string.tag_hot_reload)
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (restartRequired) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun ConfigTextField(
    key: String,
    label: String,
    value: String,
    restartRequired: Boolean,
    onValueChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            ReloadBadge(restartRequired = restartRequired)
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MonoCodeStyle,
            supportingText = { Text(key, style = MonoCodeStyle) },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cfg_field_$key")
        )
    }
}

@Composable
private fun ConfigIntField(
    key: String,
    label: String,
    value: Int,
    restartRequired: Boolean,
    onValueChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            ReloadBadge(restartRequired = restartRequired)
        }
        OutlinedTextField(
            value = value.toString(),
            onValueChange = { txt ->
                val parsed = txt.trim().toIntOrNull()
                if (parsed != null) onValueChange(parsed)
            },
            singleLine = true,
            textStyle = MonoCodeStyle,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text(key, style = MonoCodeStyle) },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cfg_field_$key")
        )
    }
}

@Composable
private fun ConfigLongField(
    key: String,
    label: String,
    value: Long,
    restartRequired: Boolean,
    onValueChange: (Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            ReloadBadge(restartRequired = restartRequired)
        }
        OutlinedTextField(
            value = value.toString(),
            onValueChange = { txt ->
                val parsed = txt.trim().toLongOrNull()
                if (parsed != null) onValueChange(parsed)
            },
            singleLine = true,
            textStyle = MonoCodeStyle,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text(key, style = MonoCodeStyle) },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cfg_field_$key")
        )
    }
}

@Composable
private fun ConfigSwitchRow(
    key: String,
    label: String,
    checked: Boolean,
    restartRequired: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = key,
                    style = MonoCodeStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ReloadBadge(restartRequired = restartRequired)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag("cfg_switch_$key")
        )
    }
}
