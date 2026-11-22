package com.example.core.relay

import com.example.core.common.ByteKey

data class RelaySnapshotClient(
    val mac: ByteKey,
    val ip: String,
    val port: Int,
    val pdpPorts: List<Int>,
    val ptpListenPorts: List<Int>,
    val ptpConnectPorts: List<Int>,
    val ptpAcceptPorts: List<Int>
)

data class RelaySessionInfo(
    val identifier: String,
    val mode: SessionMode,
    val phase: SessionPhase,
    val clientAddr: String,
    val clientPort: Int,
    val fromMac: String,
    val fromPort: Int,
    val toMac: String,
    val toPort: Int,
    val transport: String,
    val queuedSendBytes: Int
)

data class RelaySnapshot(
    val clients: Map<ByteKey, RelaySnapshotClient> = emptyMap(),
    val sessions: List<RelaySessionInfo> = emptyList(),
    val pendingCount: Int = 0
)
