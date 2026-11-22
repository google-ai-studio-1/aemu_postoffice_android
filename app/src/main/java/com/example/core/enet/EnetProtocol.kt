package com.example.core.enet

import com.example.core.common.readBeInt32
import com.example.core.common.readBeUInt16
import com.example.core.common.writeBeInt32
import com.example.core.common.writeBeUInt16

object EnetConstants {
    const val PROTOCOL_MINIMUM_MTU = 576
    const val PROTOCOL_MAXIMUM_MTU = 4096
    const val PROTOCOL_DEFAULT_MTU = 1392
    const val PROTOCOL_MAXIMUM_PACKET_COMMANDS = 32
    const val PROTOCOL_MINIMUM_WINDOW_SIZE = 4096
    const val PROTOCOL_MAXIMUM_WINDOW_SIZE = 65536
    const val PROTOCOL_MINIMUM_CHANNEL_COUNT = 1
    const val PROTOCOL_MAXIMUM_CHANNEL_COUNT = 255
    const val PROTOCOL_MAXIMUM_PEER_ID = 0x0FFF
    const val PROTOCOL_MAXIMUM_FRAGMENT_COUNT = 1024 * 1024

    const val HEADER_FLAG_COMPRESSED = 0x4000
    const val HEADER_FLAG_SENT_TIME = 0x8000
    const val HEADER_SESSION_MASK = 0x3000
    const val HEADER_SESSION_SHIFT = 12

    const val COMMAND_NONE = 0
    const val COMMAND_ACKNOWLEDGE = 1
    const val COMMAND_CONNECT = 2
    const val COMMAND_VERIFY_CONNECT = 3
    const val COMMAND_DISCONNECT = 4
    const val COMMAND_PING = 5
    const val COMMAND_SEND_RELIABLE = 6
    const val COMMAND_SEND_UNRELIABLE = 7
    const val COMMAND_SEND_FRAGMENT = 8
    const val COMMAND_SEND_UNSEQUENCED = 9
    const val COMMAND_BANDWIDTH_LIMIT = 10
    const val COMMAND_THROTTLE_CONFIGURE = 11
    const val COMMAND_SEND_UNRELIABLE_FRAGMENT = 12

    const val COMMAND_MASK = 0x0F
    const val COMMAND_FLAG_ACKNOWLEDGE = 0x80
    const val COMMAND_FLAG_UNSEQUENCED = 0x40

    const val PACKET_FLAG_RELIABLE = 1 shl 0
    const val PACKET_FLAG_UNSEQUENCED = 1 shl 1

    const val PEER_DEFAULT_ROUND_TRIP_TIME = 500
    const val PEER_PING_INTERVAL = 500L
    const val PEER_TIMEOUT_MINIMUM = 5000L
    const val PEER_TIMEOUT_MAXIMUM = 30000L
    const val PEER_TIMEOUT_LIMIT = 32

    const val HEADER_SIZE_WITH_TIME = 4
    const val HEADER_SIZE_NO_TIME = 2
    const val COMMAND_HEADER_SIZE = 4

    const val SIZE_ACKNOWLEDGE = 8
    const val SIZE_CONNECT = 48
    const val SIZE_VERIFY_CONNECT = 44
    const val SIZE_DISCONNECT = 8
    const val SIZE_PING = 4
    const val SIZE_SEND_RELIABLE = 6
    const val SIZE_SEND_UNRELIABLE = 8
    const val SIZE_SEND_FRAGMENT = 24
    const val SIZE_SEND_UNSEQUENCED = 8
    const val SIZE_BANDWIDTH_LIMIT = 12
    const val SIZE_THROTTLE_CONFIGURE = 16
    const val SIZE_SEND_UNRELIABLE_FRAGMENT = 24

    fun commandBaseSize(commandNumber: Int): Int {
        return when (commandNumber) {
            COMMAND_ACKNOWLEDGE -> SIZE_ACKNOWLEDGE
            COMMAND_CONNECT -> SIZE_CONNECT
            COMMAND_VERIFY_CONNECT -> SIZE_VERIFY_CONNECT
            COMMAND_DISCONNECT -> SIZE_DISCONNECT
            COMMAND_PING -> SIZE_PING
            COMMAND_SEND_RELIABLE -> SIZE_SEND_RELIABLE
            COMMAND_SEND_UNRELIABLE -> SIZE_SEND_UNRELIABLE
            COMMAND_SEND_FRAGMENT -> SIZE_SEND_FRAGMENT
            COMMAND_SEND_UNSEQUENCED -> SIZE_SEND_UNSEQUENCED
            COMMAND_BANDWIDTH_LIMIT -> SIZE_BANDWIDTH_LIMIT
            COMMAND_THROTTLE_CONFIGURE -> SIZE_THROTTLE_CONFIGURE
            COMMAND_SEND_UNRELIABLE_FRAGMENT -> SIZE_SEND_UNRELIABLE_FRAGMENT
            else -> -1
        }
    }

    fun seqDiff(a: Int, b: Int): Int {
        return ((a - b) shl 16) shr 16
    }

    fun encodePacketHeader(
        buf: ByteArray,
        peerId: Int,
        sessionId: Int,
        sentTime: Int?
    ): Int {
        var headerWord = (peerId and PROTOCOL_MAXIMUM_PEER_ID) or
            ((sessionId and 0x03) shl HEADER_SESSION_SHIFT)
        if (sentTime != null) {
            headerWord = headerWord or HEADER_FLAG_SENT_TIME
            writeBeUInt16(buf, 0, headerWord)
            writeBeUInt16(buf, 2, sentTime and 0xFFFF)
            return HEADER_SIZE_WITH_TIME
        }
        writeBeUInt16(buf, 0, headerWord)
        return HEADER_SIZE_NO_TIME
    }

    fun buildAcknowledge(
        channelId: Int,
        reliableSeq: Int,
        receivedSeq: Int,
        receivedSentTime: Int
    ): ByteArray {
        val out = ByteArray(SIZE_ACKNOWLEDGE)
        out[0] = COMMAND_ACKNOWLEDGE.toByte()
        out[1] = (channelId and 0xFF).toByte()
        writeBeUInt16(out, 2, reliableSeq and 0xFFFF)
        writeBeUInt16(out, 4, receivedSeq and 0xFFFF)
        writeBeUInt16(out, 6, receivedSentTime and 0xFFFF)
        return out
    }

    fun buildVerifyConnect(
        reliableSeq: Int,
        outgoingPeerId: Int,
        incomingSessionId: Int,
        outgoingSessionId: Int,
        mtu: Int,
        windowSize: Int,
        channelCount: Int,
        incomingBandwidth: Int,
        outgoingBandwidth: Int,
        throttleInterval: Int,
        throttleAccel: Int,
        throttleDecel: Int,
        connectId: Int
    ): ByteArray {
        val out = ByteArray(SIZE_VERIFY_CONNECT)
        out[0] = (COMMAND_VERIFY_CONNECT or COMMAND_FLAG_ACKNOWLEDGE).toByte()
        out[1] = 0xFF.toByte()
        writeBeUInt16(out, 2, reliableSeq and 0xFFFF)
        writeBeUInt16(out, 4, outgoingPeerId and 0xFFFF)
        out[6] = (incomingSessionId and 0xFF).toByte()
        out[7] = (outgoingSessionId and 0xFF).toByte()
        writeBeInt32(out, 8, mtu)
        writeBeInt32(out, 12, windowSize)
        writeBeInt32(out, 16, channelCount)
        writeBeInt32(out, 20, incomingBandwidth)
        writeBeInt32(out, 24, outgoingBandwidth)
        writeBeInt32(out, 28, throttleInterval)
        writeBeInt32(out, 32, throttleAccel)
        writeBeInt32(out, 36, throttleDecel)
        writeBeInt32(out, 40, connectId)
        return out
    }

    fun buildPing(reliableSeq: Int): ByteArray {
        val out = ByteArray(SIZE_PING)
        out[0] = (COMMAND_PING or COMMAND_FLAG_ACKNOWLEDGE).toByte()
        out[1] = 0xFF.toByte()
        writeBeUInt16(out, 2, reliableSeq and 0xFFFF)
        return out
    }

    fun buildDisconnect(reliableSeq: Int, data: Int, acknowledge: Boolean): ByteArray {
        val out = ByteArray(SIZE_DISCONNECT)
        val flags = if (acknowledge) COMMAND_FLAG_ACKNOWLEDGE else COMMAND_FLAG_UNSEQUENCED
        out[0] = (COMMAND_DISCONNECT or flags).toByte()
        out[1] = 0xFF.toByte()
        writeBeUInt16(out, 2, reliableSeq and 0xFFFF)
        writeBeInt32(out, 4, data)
        return out
    }

    fun buildSendReliable(channelId: Int, reliableSeq: Int, payload: ByteArray, offset: Int = 0, length: Int = payload.size - offset): ByteArray {
        val out = ByteArray(SIZE_SEND_RELIABLE + length)
        out[0] = (COMMAND_SEND_RELIABLE or COMMAND_FLAG_ACKNOWLEDGE).toByte()
        out[1] = (channelId and 0xFF).toByte()
        writeBeUInt16(out, 2, reliableSeq and 0xFFFF)
        writeBeUInt16(out, 4, length and 0xFFFF)
        if (length > 0) {
            System.arraycopy(payload, offset, out, 6, length)
        }
        return out
    }

    fun buildSendFragment(
        channelId: Int,
        reliableSeq: Int,
        startSeq: Int,
        fragmentCount: Int,
        fragmentNumber: Int,
        totalLength: Int,
        fragmentOffset: Int,
        payload: ByteArray,
        payloadOffset: Int,
        payloadLength: Int
    ): ByteArray {
        val out = ByteArray(SIZE_SEND_FRAGMENT + payloadLength)
        out[0] = (COMMAND_SEND_FRAGMENT or COMMAND_FLAG_ACKNOWLEDGE).toByte()
        out[1] = (channelId and 0xFF).toByte()
        writeBeUInt16(out, 2, reliableSeq and 0xFFFF)
        writeBeUInt16(out, 4, startSeq and 0xFFFF)
        writeBeUInt16(out, 6, payloadLength and 0xFFFF)
        writeBeInt32(out, 8, fragmentCount)
        writeBeInt32(out, 12, fragmentNumber)
        writeBeInt32(out, 16, totalLength)
        writeBeInt32(out, 20, fragmentOffset)
        if (payloadLength > 0) {
            System.arraycopy(payload, payloadOffset, out, 24, payloadLength)
        }
        return out
    }

    fun buildSendUnsequenced(
        channelId: Int,
        reliableSeq: Int,
        unsequencedGroup: Int,
        payload: ByteArray,
        offset: Int = 0,
        length: Int = payload.size - offset
    ): ByteArray {
        val out = ByteArray(SIZE_SEND_UNSEQUENCED + length)
        out[0] = (COMMAND_SEND_UNSEQUENCED or COMMAND_FLAG_UNSEQUENCED).toByte()
        out[1] = (channelId and 0xFF).toByte()
        writeBeUInt16(out, 2, reliableSeq and 0xFFFF)
        writeBeUInt16(out, 4, unsequencedGroup and 0xFFFF)
        writeBeUInt16(out, 6, length and 0xFFFF)
        if (length > 0) {
            System.arraycopy(payload, offset, out, 8, length)
        }
        return out
    }

    fun buildConnect(
        reliableSeq: Int,
        outgoingPeerId: Int,
        incomingSessionId: Int,
        outgoingSessionId: Int,
        mtu: Int,
        windowSize: Int,
        channelCount: Int,
        connectId: Int
    ): ByteArray {
        val out = ByteArray(SIZE_CONNECT)
        out[0] = (COMMAND_CONNECT or COMMAND_FLAG_ACKNOWLEDGE).toByte()
        out[1] = 0xFF.toByte()
        writeBeUInt16(out, 2, reliableSeq and 0xFFFF)
        writeBeUInt16(out, 4, outgoingPeerId and 0xFFFF)
        out[6] = (incomingSessionId and 0xFF).toByte()
        out[7] = (outgoingSessionId and 0xFF).toByte()
        writeBeInt32(out, 8, mtu)
        writeBeInt32(out, 12, windowSize)
        writeBeInt32(out, 16, channelCount)
        writeBeInt32(out, 20, 0)
        writeBeInt32(out, 24, 0)
        writeBeInt32(out, 28, 5000)
        writeBeInt32(out, 32, 2)
        writeBeInt32(out, 36, 2)
        writeBeInt32(out, 40, connectId)
        writeBeInt32(out, 44, 0)
        return out
    }

    fun parseConnectFields(buf: ByteArray, offset: Int): ParsedConnect {
        return ParsedConnect(
            outgoingPeerId = readBeUInt16(buf, offset + 4),
            incomingSessionId = buf[offset + 6].toInt() and 0xFF,
            outgoingSessionId = buf[offset + 7].toInt() and 0xFF,
            mtu = readBeInt32(buf, offset + 8),
            windowSize = readBeInt32(buf, offset + 12),
            channelCount = readBeInt32(buf, offset + 16),
            incomingBandwidth = readBeInt32(buf, offset + 20),
            outgoingBandwidth = readBeInt32(buf, offset + 24),
            throttleInterval = readBeInt32(buf, offset + 28),
            throttleAccel = readBeInt32(buf, offset + 32),
            throttleDecel = readBeInt32(buf, offset + 36),
            connectId = readBeInt32(buf, offset + 40)
        )
    }
}

data class ParsedConnect(
    val outgoingPeerId: Int,
    val incomingSessionId: Int,
    val outgoingSessionId: Int,
    val mtu: Int,
    val windowSize: Int,
    val channelCount: Int,
    val incomingBandwidth: Int,
    val outgoingBandwidth: Int,
    val throttleInterval: Int,
    val throttleAccel: Int,
    val throttleDecel: Int,
    val connectId: Int
)
