package com.example.core.adhocctl

import com.example.core.common.ByteKey

data class AdhocctlSnapshotClient(
    val mac: ByteKey,
    val ip: String,
    val port: Int,
    val nickname: String,
    val gameCode: String,
    val groupKey: String
)

data class AdhocctlSnapshotGroup(
    val channel: Int,
    val name: String,
    val members: List<ByteKey>
)

data class AdhocctlSnapshotGame(
    val gameCode: String,
    val groups: Map<String, AdhocctlSnapshotGroup>
)

data class AdhocctlSnapshot(
    val clients: Map<ByteKey, AdhocctlSnapshotClient> = emptyMap(),
    val games: Map<String, AdhocctlSnapshotGame> = emptyMap()
) {
    companion object {
        fun makeGroupKey(channel: Int, groupName: String): String {
            return "${channel}_$groupName"
        }
    }
}
