package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.adhocctl.AdhocctlSnapshotGame
import com.example.core.relay.RelaySessionInfo
import com.example.core.relay.SessionMode
import com.example.service.ServerRuntimeState
import com.example.ui.theme.MonoCodeStyle

@Composable
fun RoomsSessionsScreen(
    state: ServerRuntimeState,
    modifier: Modifier = Modifier
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                SegmentedButton(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    modifier = Modifier.testTag("rooms_tab_lobby")
                ) {
                    Text(
                        text = "${stringResource(R.string.tab_adhocctl_lobby)} (${state.adhocctlSnapshot.clients.size})",
                        textAlign = TextAlign.Center
                    )
                }
                SegmentedButton(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    modifier = Modifier.testTag("rooms_tab_sessions")
                ) {
                    Text(
                        text = "${stringResource(R.string.tab_relay_sessions)} (${state.relaySnapshot.sessions.size})",
                        textAlign = TextAlign.Center
                    )
                }
            }

            if (selectedTab == 0) {
                AdhocctlLobbyList(state = state)
            } else {
                RelaySessionsList(state = state)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdhocctlLobbyList(state: ServerRuntimeState) {
    val gamesList = state.adhocctlSnapshot.games.values.toList()
    if (gamesList.isEmpty()) {
        EmptyStatePanel(
            iconRes = R.drawable.ic_gamepad_psp,
            iconCd = stringResource(R.string.cd_game_room),
            title = stringResource(R.string.empty_lobby_title),
            description = stringResource(R.string.empty_lobby_desc)
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(gamesList, key = { it.gameCode }) { game ->
            GameLobbyCard(game = game, state = state)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameLobbyCard(
    game: AdhocctlSnapshotGame,
    state: ServerRuntimeState
) {
    val gameName = state.activeGameDb.names[game.gameCode] ?: game.gameCode
    val crosslinkedFrom = state.activeGameDb.crosslinks.entries
        .filter { it.value == game.gameCode }
        .map { it.key }
    val totalPlayers = game.groups.values.sumOf { it.members.size }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("game_lobby_card_${game.gameCode}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = gameName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (crosslinkedFrom.isNotEmpty()) {
                            "${game.gameCode}  |  Crosslinked: ${crosslinkedFrom.joinToString(", ")}"
                        } else {
                            game.gameCode
                        },
                        style = MonoCodeStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "$totalPlayers online",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            for ((groupKey, group) in game.groups) {
                val roomLabel = if (group.channel < 0) {
                    "Idle Lobby (ch ${group.channel})"
                } else {
                    "Room ${group.name.ifEmpty { "(unnamed)" }}  |  Ch ${group.channel}"
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = roomLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${group.members.size} player(s)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (mac in group.members) {
                                val client = state.adhocctlSnapshot.clients[mac]
                                val relayClient = state.relaySnapshot.clients[mac]
                                val pdpPorts = relayClient?.pdpPorts?.distinct()?.sorted().orEmpty()
                                val ptpPorts = relayClient?.let {
                                    (it.ptpListenPorts + it.ptpConnectPorts + it.ptpAcceptPorts).distinct().sorted()
                                }.orEmpty()

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = client?.nickname?.ifEmpty { mac.toMacString() } ?: mac.toMacString(),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = mac.toMacString(),
                                            style = MonoCodeStyle,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (pdpPorts.isNotEmpty() || ptpPorts.isNotEmpty()) {
                                            Text(
                                                text = "PDP:${pdpPorts.joinToString(",")} PTP:${ptpPorts.joinToString(",")}",
                                                style = MonoCodeStyle,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RelaySessionsList(state: ServerRuntimeState) {
    val sessions = state.relaySnapshot.sessions
    if (sessions.isEmpty() && state.relaySnapshot.pendingCount == 0) {
        EmptyStatePanel(
            iconRes = R.drawable.ic_tcp_transport,
            iconCd = stringResource(R.string.cd_transport_tcp),
            title = stringResource(R.string.empty_sessions_title),
            description = stringResource(R.string.empty_sessions_desc)
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state.relaySnapshot.pendingCount > 0) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Text(
                        text = "${state.relaySnapshot.pendingCount} pending relay connection(s) awaiting init packet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    )
                }
            }
        }

        items(sessions, key = { it.identifier }) { info ->
            RelaySessionCard(info = info)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RelaySessionCard(info: RelaySessionInfo) {
    val modeColor = when (info.mode) {
        SessionMode.PDP -> MaterialTheme.colorScheme.primary
        SessionMode.PTP_LISTEN -> MaterialTheme.colorScheme.secondary
        SessionMode.PTP_CONNECT -> MaterialTheme.colorScheme.tertiary
        SessionMode.PTP_ACCEPT -> MaterialTheme.colorScheme.secondary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = modeColor.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = info.mode.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = modeColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = info.transport,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = info.phase.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = "From ${info.fromMac}:${info.fromPort}" +
                    if (info.mode != SessionMode.PDP && info.mode != SessionMode.PTP_LISTEN) {
                        "  ->  ${info.toMac}:${info.toPort}"
                    } else {
                        ""
                    },
                style = MonoCodeStyle,
                color = MaterialTheme.colorScheme.onSurface
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "${info.clientAddr}:${info.clientPort}",
                    style = MonoCodeStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Queue: ${info.queuedSendBytes} B",
                    style = MonoCodeStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyStatePanel(
    iconRes: Int,
    iconCd: String,
    title: String,
    description: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = iconCd,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(16.dp)
                        .size(32.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 360.dp)
            )
        }
    }
}
