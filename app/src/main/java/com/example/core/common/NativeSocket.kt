package com.example.core.common

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

enum class AddrFamily {
    IPV4,
    IPV6,
    UNKNOWN
}

data class AcceptedTcpSocket(
    val socket: NativeTcpSocket,
    val peerAddr: String,
    val peerPort: Int
)

object NativeSocketUtil {
    private val ipv4Regex = Regex("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$")

    fun getAddrFamily(ip: String): AddrFamily {
        val trimmed = ip.trim()
        if (trimmed.isEmpty()) return AddrFamily.UNKNOWN
        if (trimmed.contains(':')) {
            return try {
                val parsed = parseLiteralIp(trimmed)
                if (parsed != null) AddrFamily.IPV6 else AddrFamily.UNKNOWN
            } catch (_: Exception) {
                AddrFamily.UNKNOWN
            }
        }
        if (ipv4Regex.matches(trimmed)) {
            return AddrFamily.IPV4
        }
        return AddrFamily.UNKNOWN
    }

    fun parseLiteralIp(ip: String): InetAddress? {
        val trimmed = ip.trim()
        if (trimmed.isEmpty()) return null
        if (ipv4Regex.matches(trimmed)) {
            val parts = trimmed.split('.')
            val bytes = ByteArray(4) { i -> parts[i].toInt().toByte() }
            return InetAddress.getByAddress(bytes)
        }
        if (!trimmed.contains(':')) return null
        val lower = trimmed.lowercase()
        if (lower == "::" || lower == "::ffff:0.0.0.0") {
            return InetAddress.getByAddress(ByteArray(16))
        }
        if (lower.startsWith("::ffff:")) {
            val v4Part = lower.substring(7)
            if (ipv4Regex.matches(v4Part)) {
                val parts = v4Part.split('.')
                val bytes = ByteArray(16)
                bytes[10] = 0xFF.toByte()
                bytes[11] = 0xFF.toByte()
                for (i in 0 until 4) {
                    bytes[12 + i] = parts[i].toInt().toByte()
                }
                return InetAddress.getByAddress(bytes)
            }
        }
        val expanded = parseIpv6Bytes(lower) ?: return null
        return InetAddress.getByAddress(expanded)
    }

    private fun parseIpv6Bytes(addr: String): ByteArray? {
        var input = addr
        val zoneIdx = input.indexOf('%')
        if (zoneIdx >= 0) {
            input = input.substring(0, zoneIdx)
        }
        val doubleColon = input.indexOf("::")
        if (doubleColon >= 0 && input.indexOf("::", doubleColon + 2) >= 0) {
            return null
        }
        val words = IntArray(8)
        if (doubleColon >= 0) {
            val leftPart = input.substring(0, doubleColon)
            val rightPart = input.substring(doubleColon + 2)
            val leftTokens = if (leftPart.isEmpty()) emptyList() else leftPart.split(':')
            val rightTokens = parseTailTokens(rightPart) ?: return null
            if (leftTokens.size + rightTokens.size > 7) return null
            for (i in leftTokens.indices) {
                val v = leftTokens[i].toIntOrNull(16) ?: return null
                if (v !in 0..0xFFFF) return null
                words[i] = v
            }
            val rightStart = 8 - rightTokens.size
            for (i in rightTokens.indices) {
                words[rightStart + i] = rightTokens[i]
            }
        } else {
            val tokens = parseTailTokens(input) ?: return null
            if (tokens.size != 8) return null
            for (i in 0 until 8) {
                words[i] = tokens[i]
            }
        }
        val out = ByteArray(16)
        for (i in 0 until 8) {
            out[i * 2] = ((words[i] ushr 8) and 0xFF).toByte()
            out[i * 2 + 1] = (words[i] and 0xFF).toByte()
        }
        return out
    }

    private fun parseTailTokens(part: String): List<Int>? {
        if (part.isEmpty()) return emptyList()
        val raw = part.split(':')
        val result = ArrayList<Int>(raw.size + 1)
        for (idx in raw.indices) {
            val token = raw[idx]
            if (idx == raw.lastIndex && token.contains('.')) {
                if (!ipv4Regex.matches(token)) return null
                val p = token.split('.')
                result.add(((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF))
                result.add(((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF))
            } else {
                val v = token.toIntOrNull(16) ?: return null
                if (v !in 0..0xFFFF) return null
                result.add(v)
            }
        }
        return result
    }

    fun parseIpv4ToNetworkUint32(ip: String): Long {
        val normalized = if (ip.lowercase().startsWith("::ffff:")) ip.substring(7) else ip
        if (!ipv4Regex.matches(normalized)) {
            return 0xFFFFFFFFL
        }
        val parts = normalized.split('.')
        val b0 = parts[0].toLong() and 0xFFL
        val b1 = parts[1].toLong() and 0xFFL
        val b2 = parts[2].toLong() and 0xFFL
        val b3 = parts[3].toLong() and 0xFFL
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    fun normalizePeerIp(address: InetAddress?): String {
        if (address == null) return ""
        if (address is Inet4Address) {
            return address.hostAddress ?: ""
        }
        val bytes = address.address
        if (bytes.size == 16) {
            var isV4Mapped = true
            for (i in 0 until 10) {
                if (bytes[i].toInt() != 0) {
                    isV4Mapped = false
                    break
                }
            }
            if (isV4Mapped && (bytes[10].toInt() and 0xFF) == 0xFF && (bytes[11].toInt() and 0xFF) == 0xFF) {
                return "${bytes[12].toInt() and 0xFF}.${bytes[13].toInt() and 0xFF}.${bytes[14].toInt() and 0xFF}.${bytes[15].toInt() and 0xFF}"
            }
        }
        val host = address.hostAddress ?: ""
        val zoneIdx = host.indexOf('%')
        return if (zoneIdx >= 0) host.substring(0, zoneIdx) else host
    }

    fun resolveBindSocketAddress(ip: String, port: Int): InetSocketAddress? {
        val trimmed = ip.trim()
        val lower = trimmed.lowercase()
        if (lower == "::" || lower == "::ffff:0.0.0.0" || lower == "0.0.0.0") {
            return InetSocketAddress(port)
        }
        val addr = parseLiteralIp(trimmed) ?: return null
        return InetSocketAddress(addr, port)
    }
}

class NativeTcpSocket(val channel: SocketChannel) {
    @Volatile
    private var closed = false

    fun recv(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): Int {
        if (closed || !channel.isOpen) return -2
        return try {
            val bb = ByteBuffer.wrap(buf, offset, length)
            val read = channel.read(bb)
            when {
                read > 0 -> read
                read == 0 -> -1
                else -> 0
            }
        } catch (_: IOException) {
            -2
        }
    }

    fun send(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): Int {
        if (closed || !channel.isOpen) return -2
        return try {
            val bb = ByteBuffer.wrap(buf, offset, length)
            val written = channel.write(bb)
            if (written == 0) -1 else written
        } catch (_: IOException) {
            -2
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            channel.close()
        } catch (_: Exception) {
        }
    }
}

class NativeTcpListener private constructor(
    private val serverChannel: ServerSocketChannel
) {
    @Volatile
    private var closed = false

    fun accept(): AcceptedTcpSocket? {
        if (closed || !serverChannel.isOpen) {
            throw IOException("Listener socket closed")
        }
        val client = serverChannel.accept() ?: return null
        try {
            client.configureBlocking(false)
            try {
                client.setOption(StandardSocketOptions.TCP_NODELAY, true)
                client.setOption(StandardSocketOptions.SO_SNDBUF, 64 * 1024)
                client.setOption(StandardSocketOptions.SO_RCVBUF, 64 * 1024)
            } catch (_: Exception) {
                try {
                    client.socket().tcpNoDelay = true
                    client.socket().sendBufferSize = 64 * 1024
                    client.socket().receiveBufferSize = 64 * 1024
                } catch (_: Exception) {
                }
            }
            val remote = client.remoteAddress as? InetSocketAddress
            val ip = NativeSocketUtil.normalizePeerIp(remote?.address)
            val port = remote?.port ?: 0
            return AcceptedTcpSocket(NativeTcpSocket(client), ip, port)
        } catch (e: Exception) {
            try {
                client.close()
            } catch (_: Exception) {
            }
            return null
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            serverChannel.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        fun listen(ip: String, port: Int): NativeTcpListener? {
            val bindAddr = NativeSocketUtil.resolveBindSocketAddress(ip, port) ?: return null
            val channel = ServerSocketChannel.open()
            return try {
                try {
                    channel.setOption(StandardSocketOptions.SO_REUSEADDR, true)
                } catch (_: Exception) {
                    try {
                        channel.socket().reuseAddress = true
                    } catch (_: Exception) {
                    }
                }
                channel.bind(bindAddr, 1000)
                channel.configureBlocking(false)
                NativeTcpListener(channel)
            } catch (e: Exception) {
                ServerLogger.error(LogCategory.SYSTEM, "native_tcp_listen: bind/listen failed on $ip:$port (${e.message})")
                try {
                    channel.close()
                } catch (_: Exception) {
                }
                null
            }
        }
    }
}
