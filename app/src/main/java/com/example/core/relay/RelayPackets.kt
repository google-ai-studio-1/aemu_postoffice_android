package com.example.core.relay

import com.example.core.common.readLeInt32
import com.example.core.common.readLeUInt16
import com.example.core.common.readLeUInt32
import com.example.core.common.writeLeInt32
import com.example.core.common.writeLeUInt16

object RelayPackets {
    const val PDP_BLOCK_MAX = 10 * 1024
    const val PTP_BLOCK_MAX = 50 * 1024

    const val INIT_PDP = 0
    const val INIT_PTP_LISTEN = 1
    const val INIT_PTP_CONNECT = 2
    const val INIT_PTP_ACCEPT = 3
    const val INIT_PDP_UNRELIABLE = 4

    const val SIZE_INIT = 24
    const val SIZE_PDP = 14
    const val SIZE_PTP_CONNECT = 10
    const val SIZE_PTP_DATA = 4

    data class InitPacket(
        val initType: Int,
        val srcAddr: ByteArray,
        val sport: Int,
        val dstAddr: ByteArray,
        val dport: Int
    )

    data class PdpHeader(
        val addr: ByteArray,
        val port: Int,
        val size: Long
    )

    fun parseInit(buf: ByteArray, offset: Int): InitPacket {
        val initType = readLeInt32(buf, offset)
        val src = ByteArray(6)
        System.arraycopy(buf, offset + 4, src, 0, 6)
        val sport = readLeUInt16(buf, offset + 12)
        val dst = ByteArray(6)
        System.arraycopy(buf, offset + 14, dst, 0, 6)
        val dport = readLeUInt16(buf, offset + 22)
        return InitPacket(initType, src, sport, dst, dport)
    }

    fun parsePdpHeader(buf: ByteArray, offset: Int): PdpHeader {
        val addr = ByteArray(6)
        System.arraycopy(buf, offset, addr, 0, 6)
        val port = readLeUInt16(buf, offset + 8)
        val size = readLeUInt32(buf, offset + 10)
        return PdpHeader(addr, port, size)
    }

    fun parsePtpDataSize(buf: ByteArray, offset: Int): Long {
        return readLeUInt32(buf, offset)
    }

    fun buildPtpConnectPacket(mac: ByteArray, port: Int): ByteArray {
        val out = ByteArray(SIZE_PTP_CONNECT)
        System.arraycopy(mac, 0, out, 0, minOf(6, mac.size))
        writeLeUInt16(out, 8, port)
        return out
    }

    fun buildPdpPacket(fromMac: ByteArray, fromPort: Int, payload: ByteArray, payloadOffset: Int, payloadLen: Int): ByteArray {
        val out = ByteArray(SIZE_PDP + payloadLen)
        System.arraycopy(fromMac, 0, out, 0, minOf(6, fromMac.size))
        writeLeUInt16(out, 8, fromPort)
        writeLeInt32(out, 10, payloadLen)
        if (payloadLen > 0) {
            System.arraycopy(payload, payloadOffset, out, SIZE_PDP, payloadLen)
        }
        return out
    }

    fun buildPtpDataPacket(payload: ByteArray, payloadOffset: Int, payloadLen: Int): ByteArray {
        val out = ByteArray(SIZE_PTP_DATA + payloadLen)
        writeLeInt32(out, 0, payloadLen)
        if (payloadLen > 0) {
            System.arraycopy(payload, payloadOffset, out, SIZE_PTP_DATA, payloadLen)
        }
        return out
    }
}
