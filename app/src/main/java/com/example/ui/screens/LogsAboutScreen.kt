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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.common.LogCategory
import com.example.core.common.LogEntry
import com.example.core.common.LogLevel
import com.example.ui.LogFilterUiState
import com.example.ui.theme.MonoCodeStyle

@Composable
fun LogsAboutScreen(
    logs: List<LogEntry>,
    filter: LogFilterUiState,
    gplLicenseText: String,
    onSelectCategory: (LogCategory?) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleAutoScroll: () -> Unit,
    onClearLogs: () -> Unit,
    onExportLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }

    val filteredLogs = remember(logs, filter.selectedCategory, filter.searchQuery) {
        val query = filter.searchQuery.trim().lowercase()
        logs.filter { entry ->
            val catMatch = filter.selectedCategory == null || entry.category == filter.selectedCategory
            val queryMatch = query.isEmpty() ||
                entry.message.lowercase().contains(query) ||
                entry.category.name.lowercase().contains(query)
            catMatch && queryMatch
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(filteredLogs.size, filter.autoScroll) {
        if (filter.autoScroll && filteredLogs.isNotEmpty()) {
            listState.scrollToItem(filteredLogs.lastIndex)
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = filter.searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text(stringResource(R.string.search_logs_hint)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = stringResource(R.string.cd_search),
                        modifier = Modifier.size(18.dp)
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("logs_search_input")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${filteredLogs.size} / ${logs.size} entries",
                    style = MonoCodeStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(
                        onClick = onToggleAutoScroll,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("logs_autoscroll_button")
                    ) {
                        Icon(
                            painter = painterResource(
                                if (filter.autoScroll) R.drawable.ic_pause_scroll else R.drawable.ic_play_scroll
                            ),
                            contentDescription = stringResource(R.string.cd_toggle_scroll),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = onExportLogs,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("logs_export_button")
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_export_file),
                            contentDescription = stringResource(R.string.cd_export_json),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = onClearLogs,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("logs_clear_button")
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_clear_logs),
                            contentDescription = stringResource(R.string.cd_clear_logs),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = { showAboutDialog = true },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("about_dialog_button")
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_info_license),
                            contentDescription = stringResource(R.string.cd_about_info),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = filter.selectedCategory == null,
                        onClick = { onSelectCategory(null) },
                        label = { Text("All (${logs.size})") },
                        modifier = Modifier.testTag("log_filter_all")
                    )
                }
                items(LogCategory.entries) { category ->
                    FilterChip(
                        selected = filter.selectedCategory == category,
                        onClick = { onSelectCategory(category) },
                        label = { Text(category.name) },
                        modifier = Modifier.testTag("log_filter_${category.name.lowercase()}")
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                if (filteredLogs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.empty_logs_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.empty_logs_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredLogs, key = { it.id }) { entry ->
                            LogEntryRow(entry = entry)
                        }
                    }
                }
            }
        }
    }

    if (showAboutDialog) {
        AboutGplDialog(
            gplText = gplLicenseText,
            onDismiss = { showAboutDialog = false }
        )
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    val accent = when (entry.level) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        LogLevel.INFO -> MaterialTheme.colorScheme.primary
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = entry.timestamp,
                style = MonoCodeStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "[${entry.category.name}]",
                style = MonoCodeStyle,
                color = accent
            )
            if (entry.level != LogLevel.INFO) {
                Text(
                    text = entry.level.name,
                    style = MonoCodeStyle,
                    color = accent
                )
            }
        }
        Text(
            text = entry.message,
            style = MonoCodeStyle,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun AboutGplDialog(
    gplText: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.about_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.about_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.about_upstream_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.about_upstream_url),
                        style = MonoCodeStyle,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.about_license_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.about_license_name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                ) {
                    Text(
                        text = gplText,
                        style = MonoCodeStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("about_dialog_close_button")
            ) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}
