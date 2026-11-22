package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.adhocctl.GameDb
import com.example.ui.theme.MonoCodeStyle

@Composable
fun GameDbVisualEditor(
    gameDb: GameDb,
    onAddName: (String, String) -> Unit,
    onRemoveName: (String) -> Unit,
    onAddCrosslink: (String, String) -> Unit,
    onRemoveCrosslink: (String) -> Unit,
    onAddCodQuirk: (String) -> Unit,
    onRemoveCodQuirk: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    val cleanQuery = query.trim().lowercase()

    val filteredNames = remember(gameDb.names, cleanQuery) {
        gameDb.names.entries.filter { (id, name) ->
            cleanQuery.isEmpty() ||
                id.lowercase().contains(cleanQuery) ||
                name.lowercase().contains(cleanQuery)
        }
    }

    val filteredCrosslinks = remember(gameDb.crosslinks, cleanQuery) {
        gameDb.crosslinks.entries.filter { (from, to) ->
            cleanQuery.isEmpty() ||
                from.lowercase().contains(cleanQuery) ||
                to.lowercase().contains(cleanQuery)
        }
    }

    val filteredQuirks = remember(gameDb.codQuirkGames, cleanQuery) {
        gameDb.codQuirkGames.filter { id ->
            cleanQuery.isEmpty() || id.lowercase().contains(cleanQuery)
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.search_games_hint)) },
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
                    .weight(1f)
                    .testTag("gamedb_search_input")
            )
            OutlinedButton(
                onClick = { showAddDialog = true },
                modifier = Modifier
                    .height(52.dp)
                    .testTag("gamedb_add_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add_entry),
                    contentDescription = stringResource(R.string.cd_add_entry),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.action_add))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = section == 0,
                onClick = { section = 0 },
                label = { Text("Titles (${gameDb.names.size})") },
                modifier = Modifier.testTag("gamedb_section_names")
            )
            FilterChip(
                selected = section == 1,
                onClick = { section = 1 },
                label = { Text("Crosslinks (${gameDb.crosslinks.size})") },
                modifier = Modifier.testTag("gamedb_section_crosslinks")
            )
            FilterChip(
                selected = section == 2,
                onClick = { section = 2 },
                label = { Text("CoD (${gameDb.codQuirkGames.size})") },
                modifier = Modifier.testTag("gamedb_section_cod")
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (section) {
                0 -> {
                    items(filteredNames, key = { it.key }) { (id, title) ->
                        GameDbRowCard(
                            primary = id,
                            secondary = title,
                            onDelete = { onRemoveName(id) }
                        )
                    }
                }
                1 -> {
                    items(filteredCrosslinks, key = { it.key }) { (fromId, toId) ->
                        GameDbRowCard(
                            primary = "$fromId -> $toId",
                            secondary = gameDb.names[toId] ?: "Target game ID",
                            onDelete = { onRemoveCrosslink(fromId) }
                        )
                    }
                }
                else -> {
                    items(filteredQuirks, key = { it }) { id ->
                        GameDbRowCard(
                            primary = id,
                            secondary = gameDb.names[id] ?: "Call of Duty quirk enabled",
                            onDelete = { onRemoveCodQuirk(id) }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        GameDbAddEntryDialog(
            section = section,
            onDismiss = { showAddDialog = false },
            onConfirmName = { id, title ->
                onAddName(id, title)
                showAddDialog = false
            },
            onConfirmCrosslink = { from, to ->
                onAddCrosslink(from, to)
                showAddDialog = false
            },
            onConfirmCod = { id ->
                onAddCodQuirk(id)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun GameDbRowCard(
    primary: String,
    secondary: String,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = primary,
                    style = MonoCodeStyle,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete_entry),
                    contentDescription = stringResource(R.string.cd_delete_entry),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun GameDbAddEntryDialog(
    section: Int,
    onDismiss: () -> Unit,
    onConfirmName: (String, String) -> Unit,
    onConfirmCrosslink: (String, String) -> Unit,
    onConfirmCod: (String) -> Unit
) {
    var fieldOne by rememberSaveable { mutableStateOf("") }
    var fieldTwo by rememberSaveable { mutableStateOf("") }

    val title = when (section) {
        0 -> "Add Game Title"
        1 -> "Add Crosslink"
        else -> "Add CoD Quirk Game"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = fieldOne,
                    onValueChange = { fieldOne = it },
                    label = { Text(if (section == 1) "Source Game ID (e.g. ULJM05800)" else "Game ID (e.g. ULUS10566)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (section == 0 || section == 1) {
                    OutlinedTextField(
                        value = fieldTwo,
                        onValueChange = { fieldTwo = it },
                        label = { Text(if (section == 0) "Game Title" else "Target Game ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (section) {
                        0 -> onConfirmName(fieldOne, fieldTwo)
                        1 -> onConfirmCrosslink(fieldOne, fieldTwo)
                        else -> onConfirmCod(fieldOne)
                    }
                }
            ) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}
