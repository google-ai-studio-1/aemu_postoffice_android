package com.example.core.adhocctl

import com.example.core.common.writeLeInt32

object AdhocctlPackets {
    const val OPCODE_PING = 0
    const val OPCODE_LOGIN = 1
    const val OPCODE_CONNECT = 2
    const val OPCODE_DISCONNECT = 3
    const val OPCODE_SCAN = 4
    const val OPCODE_SCAN_COMPLETE = 5
    const val OPCODE_CONNECT_BSSID = 6
    const val OPCODE_CHAT = 7

    const val ETHER_ADDR_LEN = 6
    const val GROUPNAME_LEN = 8
    const val NICKNAME_LEN = 128
    const val PRODUCT_CODE_LEN = 9
    const val CHAT_MESSAGE_LEN = 64

    const val SIZE_BASE = 1
    const val SIZE_LOGIN_C2S = 1 + ETHER_ADDR_LEN + NICKNAME_LEN + PRODUCT_CODE_LEN
    const val SIZE_CONNECT_C2S = 1 + GROUPNAME_LEN
    const val SIZE_CHAT_C2S = 1 + CHAT_MESSAGE_LEN

    const val SIZE_CONNECT_S2C = 1 + NICKNAME_LEN + ETHER_ADDR_LEN + 4
    const val SIZE_DISCONNECT_S2C = 1 + 4
    const val SIZE_SCAN_S2C = 1 + GROUPNAME_LEN + ETHER_ADDR_LEN
    const val SIZE_CONNECT_BSSID_S2C = 1 + ETHER_ADDR_LEN
    const val SIZE_CHAT_S2C = SIZE_CHAT_C2S + NICKNAME_LEN

    const val V1_PROTOCOL_DEFAULT_CHANNEL = 11

    fun buildConnectBssidS2C(groupMac: ByteArray): ByteArray {
        val out = ByteArray(SIZE_CONNECT_BSSID_S2C)
        out[0] = OPCODE_CONNECT_BSSID.toByte()
        System.arraycopy(groupMac, 0, out, 1, minOf(ETHER_ADDR_LEN, groupMac.size))
        return out
    }

    fun buildDisconnectS2C(ipId: Int): ByteArray {
        val out = ByteArray(SIZE_DISCONNECT_S2C)
        out[0] = OPCODE_DISCONNECT.toByte()
        writeLeInt32(out, 1, ipId)
        return out
    }

    fun buildChatS2C(nickname: String, message: String): ByteArray {
        val out = ByteArray(SIZE_CHAT_S2C)
        out[0] = OPCODE_CHAT.toByte()
        val msgBytes = message.toByteArray(Charsets.UTF_8)
        val msgCopyLen = minOf(msgBytes.size, CHAT_MESSAGE_LEN - 1)
        System.arraycopy(msgBytes, 0, out, 1, msgCopyLen)
        val nickBytes = nickname.toByteArray(Charsets.UTF_8)
        val nickCopyLen = minOf(nickBytes.size, NICKNAME_LEN - 1)
        System.arraycopy(nickBytes, 0, out, SIZE_CHAT_C2S, nickCopyLen)
        return out
    }

    fun buildScanS2C(groupName: String, leaderMac: ByteArray): ByteArray {
        val out = ByteArray(SIZE_SCAN_S2C)
        out[0] = OPCODE_SCAN.toByte()
        val grpBytes = groupName.toByteArray(Charsets.UTF_8)
        val grpLen = minOf(grpBytes.size, GROUPNAME_LEN)
        System.arraycopy(grpBytes, 0, out, 1, grpLen)
        System.arraycopy(leaderMac, 0, out, 1 + GROUPNAME_LEN, minOf(ETHER_ADDR_LEN, leaderMac.size))
        return out
    }

    fun buildScanCompleteS2C(): ByteArray {
        return byteArrayOf(OPCODE_SCAN_COMPLETE.toByte())
    }

    fun buildConnectNotifyS2C(nickname: String, mac: ByteArray, ipId: Int): ByteArray {
        val out = ByteArray(SIZE_CONNECT_S2C)
        out[0] = OPCODE_CONNECT.toByte()
        val nickBytes = nickname.toByteArray(Charsets.UTF_8)
        val nickCopyLen = minOf(nickBytes.size, NICKNAME_LEN - 1)
        System.arraycopy(nickBytes, 0, out, 1, nickCopyLen)
        System.arraycopy(mac, 0, out, 1 + NICKNAME_LEN, minOf(ETHER_ADDR_LEN, mac.size))
        writeLeInt32(out, 1 + NICKNAME_LEN + ETHER_ADDR_LEN, ipId)
        return out
    }
}
