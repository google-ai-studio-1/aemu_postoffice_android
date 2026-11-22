package com.example.core.enet

import com.example.core.common.LogCategory
import com.example.core.common.NativeSocketUtil
import com.example.core.common.ServerLogger
import com.example.core.common.readBeInt32
import com.example.core.common.readBeUInt16
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.ArrayDeque

enum class EnetPeerState {
    DISCONNECTED,
    ACKNOWLEDGING_CONNECT,
    CONNECTED,
    DISCONNECTING,
    ACKNOWLEDGING_DISCONNECT,
    ZOMBIE
}

enum class EnetEventType {
    NONE,
    CONNECT,
    DISCONNECT,
    DISCONNECT_TIMEOUT,
    RECEIVE
}

data class EnetEvent(
    val type: EnetEventType,
    val peer: EnetPeer? = null,
    val channelId: Int = 0,
    val data: ByteArray? = null
)

class EnetPeer(
    val incomingPeerId: Int
) {
    var state: EnetPeerState = EnetPeerState.DISCONNECTED
    var remoteAddress: InetSocketAddress? = null
    var remoteIp: String = ""
    var remotePort: Int = 0
    var outgoingPeerId: Int = EnetConstants.PROTOCOL_MAXIMUM_PEER_ID
    var incomingSessionId: Int = 0xFF
    var outgoingSessionId: Int = 0xFF
    var connectId: Int = 0
    var mtu: Int = EnetConstants.PROTOCOL_DEFAULT_MTU
    var windowSize: Int = EnetConstants.PROTOCOL_MAXIMUM_WINDOW_SIZE
    var channelCount: Int = 1

    var outgoingReliableSeqPeer: Int = 0
    var outgoingUnsequencedGroup: Int = 0
    var rtt: Int = EnetConstants.PEER_DEFAULT_ROUND_TRIP_TIME
    var lastReceiveTime: Long = 0L
    var lastSendTime: Long = 0L
    var nextTimeout: Long = 0L
    var earliestTimeout: Long = 0L

    var channels: Array<EnetChannel> = emptyArray()
    val pendingAcks = ArrayDeque<PendingAck>()
    val outgoingReliable = ArrayDeque<OutgoingReliableCommand>()
    val sentReliable = ArrayList<OutgoingReliableCommand>()
    val outgoingUnreliable = ArrayDeque<ByteArray>()

    fun reset() {
        state = EnetPeerState.DISCONNECTED
        remoteAddress = null
        remoteIp = ""
        remotePort = 0
        outgoingPeerId = EnetConstants.PROTOCOL_MAXIMUM_PEER_ID
        incomingSessionId = 0xFF
        outgoingSessionId = 0xFF
        connectId = 0
        mtu = EnetConstants.PROTOCOL_DEFAULT_MTU
        windowSize = EnetConstants.PROTOCOL_MAXIMUM_WINDOW_SIZE
        channelCount = 1
        outgoingReliableSeqPeer = 0
        outgoingUnsequencedGroup = 0
        rtt = EnetConstants.PEER_DEFAULT_ROUND_TRIP_TIME
        lastReceiveTime = 0L
        lastSendTime = 0L
        nextTimeout = 0L
        earliestTimeout = 0L
        channels = emptyArray()
        pendingAcks.clear()
        outgoingReliable.clear()
        sentReliable.clear()
        outgoingUnreliable.clear()
    }
}

class EnetChannel {
    var outgoingReliableSeq: Int = 0
    var outgoingUnreliableSeq: Int = 0
    var incomingReliableSeq: Int = 0
    val pendingReliable = HashMap<Int, ByteArray>()
    val fragmentAssemblies = HashMap<Int, FragmentAssembly>()
}

class FragmentAssembly(
    val startSeq: Int,
    val fragmentCount: Int,
    val totalLength: Int
) {
    val buffer = ByteArray(totalLength)
    val received = BooleanArray(fragmentCount)
    var remaining: Int = fragmentCount
}

data class PendingAck(
    val channelId: Int,
    val receivedReliableSeq: Int,
    val receivedSentTime: Int
)

class OutgoingReliableCommand(
    val channelId: Int,
    val reliableSeq: Int,
    val commandNumber: Int,
    val bytes: ByteArray
) {
    var sentTime: Long = 0L
    var roundTripTimeout: Long = EnetConstants.PEER_DEFAULT_ROUND_TRIP_TIME.toLong()
    var sendAttempts: Int = 0
}

class EnetHost private constructor(
    private val channel: DatagramChannel,
    maxPeers: Int,
    private val channelLimit: Int
) {
    private val peerCount = maxPeers.coerceIn(1, EnetConstants.PROTOCOL_MAXIMUM_PEER_ID)
    private val peers = Array(peerCount) { idx -> EnetPeer(idx) }
    private val events = ArrayDeque<EnetEvent>()
    private val recvBuffer = ByteBuffer.allocate(EnetConstants.PROTOCOL_MAXIMUM_MTU + 256)
    private val sendPacketBuf = ByteArray(EnetConstants.PROTOCOL_MAXIMUM_MTU + 64)
    private var closed = false
    private val startNanos = System.nanoTime()

    private fun nowMs(): Long = (System.nanoTime() - startNanos) / 1_000_000L

    fun service(): EnetEvent {
        if (closed) {
            return EnetEvent(EnetEventType.NONE)
        }
        if (events.isNotEmpty()) {
            return events.removeFirst()
        }
        val now = nowMs()
        receiveIncoming(now)
        processTimeoutsAndPings(now)
        flushOutgoing(now)
        return if (events.isNotEmpty()) events.removeFirst() else EnetEvent(EnetEventType.NONE)
    }

    fun sendPacket(peer: EnetPeer, channelId: Int, data: ByteArray, reliable: Boolean): Boolean {
        if (closed || peer.state != EnetPeerState.CONNECTED) {
            return false
        }
        if (channelId < 0 || channelId >= peer.channels.size) {
            return false
        }
        val ch = peer.channels[channelId]
        val maxFragmentPayload = (peer.mtu - EnetConstants.HEADER_SIZE_WITH_TIME - EnetConstants.SIZE_SEND_FRAGMENT).coerceAtLeast(256)

        if (!reliable && data.size <= peer.mtu - EnetConstants.HEADER_SIZE_WITH_TIME - EnetConstants.SIZE_SEND_UNSEQUENCED) {
            peer.outgoingUnsequencedGroup = (peer.outgoingUnsequencedGroup + 1) and 0xFFFF
            val cmd = EnetConstants.buildSendUnsequenced(
                channelId = channelId,
                reliableSeq = ch.outgoingReliableSeq,
                unsequencedGroup = peer.outgoingUnsequencedGroup,
                payload = data
            )
            peer.outgoingUnreliable.addLast(cmd)
            return true
        }

        if (data.size <= peer.mtu - EnetConstants.HEADER_SIZE_WITH_TIME - EnetConstants.SIZE_SEND_RELIABLE) {
            ch.outgoingReliableSeq = (ch.outgoingReliableSeq + 1) and 0xFFFF
            val cmd = EnetConstants.buildSendReliable(channelId, ch.outgoingReliableSeq, data)
            peer.outgoingReliable.addLast(
                OutgoingReliableCommand(channelId, ch.outgoingReliableSeq, EnetConstants.COMMAND_SEND_RELIABLE, cmd)
            )
            return true
        }

        val fragmentCount = (data.size + maxFragmentPayload - 1) / maxFragmentPayload
        if (fragmentCount > EnetConstants.PROTOCOL_MAXIMUM_FRAGMENT_COUNT) {
            return false
        }
        val startSeq = (ch.outgoingReliableSeq + 1) and 0xFFFF
        var offset = 0
        for (fragIdx in 0 until fragmentCount) {
            val len = minOf(maxFragmentPayload, data.size - offset)
            ch.outgoingReliableSeq = (ch.outgoingReliableSeq + 1) and 0xFFFF
            val cmd = EnetConstants.buildSendFragment(
                channelId = channelId,
                reliableSeq = ch.outgoingReliableSeq,
                startSeq = startSeq,
                fragmentCount = fragmentCount,
                fragmentNumber = fragIdx,
                totalLength = data.size,
                fragmentOffset = offset,
                payload = data,
                payloadOffset = offset,
                payloadLength = len
            )
            peer.outgoingReliable.addLast(
                OutgoingReliableCommand(channelId, ch.outgoingReliableSeq, EnetConstants.COMMAND_SEND_FRAGMENT, cmd)
            )
            offset += len
        }
        return true
    }

    fun disconnectPeer(peer: EnetPeer, data: Int = 0) {
        if (peer.state == EnetPeerState.DISCONNECTED || peer.state == EnetPeerState.ZOMBIE) {
            return
        }
        if (peer.state == EnetPeerState.ACKNOWLEDGING_CONNECT) {
            peer.reset()
            return
        }
        peer.outgoingReliableSeqPeer = (peer.outgoingReliableSeqPeer + 1) and 0xFFFF
        val cmd = EnetConstants.buildDisconnect(peer.outgoingReliableSeqPeer, data, acknowledge = true)
        peer.outgoingReliable.addLast(
            OutgoingReliableCommand(0xFF, peer.outgoingReliableSeqPeer, EnetConstants.COMMAND_DISCONNECT, cmd)
        )
        peer.state = EnetPeerState.DISCONNECTING
    }

    fun destroy() {
        closed = true
        for (peer in peers) {
            peer.reset()
        }
        events.clear()
        try {
            channel.close()
        } catch (_: Exception) {
        }
    }

    private fun receiveIncoming(now: Long) {
        var reads = 0
        while (reads < 256) {
            recvBuffer.clear()
            val sender = try {
                channel.receive(recvBuffer) as? InetSocketAddress
            } catch (_: Exception) {
                return
            } ?: break
            reads++
            val length = recvBuffer.position()
            if (length < EnetConstants.HEADER_SIZE_NO_TIME) continue
            val data = recvBuffer.array()
            handleIncomingPacket(sender, data, length, now)
        }
    }

    private fun handleIncomingPacket(sender: InetSocketAddress, data: ByteArray, length: Int, now: Long) {
        val headerWord = readBeUInt16(data, 0)
        val hasSentTime = (headerWord and EnetConstants.HEADER_FLAG_SENT_TIME) != 0
        val compressed = (headerWord and EnetConstants.HEADER_FLAG_COMPRESSED) != 0
        if (compressed) return
        val peerId = headerWord and EnetConstants.PROTOCOL_MAXIMUM_PEER_ID
        val sessionId = (headerWord and EnetConstants.HEADER_SESSION_MASK) ushr EnetConstants.HEADER_SESSION_SHIFT

        val headerSize = if (hasSentTime) EnetConstants.HEADER_SIZE_WITH_TIME else EnetConstants.HEADER_SIZE_NO_TIME
        if (length < headerSize) return
        val sentTime = if (hasSentTime) readBeUInt16(data, 2) else 0

        var peer: EnetPeer? = null
        if (peerId != EnetConstants.PROTOCOL_MAXIMUM_PEER_ID) {
            if (peerId >= peers.size) return
            val candidate = peers[peerId]
            if (candidate.state == EnetPeerState.DISCONNECTED || candidate.state == EnetPeerState.ZOMBIE) return
            if (candidate.remoteAddress != sender) return
            if (candidate.incomingSessionId != sessionId) return
            peer = candidate
            peer.lastReceiveTime = now
        }

        var pos = headerSize
        while (pos + EnetConstants.COMMAND_HEADER_SIZE <= length) {
            val cmdByte = data[pos].toInt() and 0xFF
            val cmdNumber = cmdByte and EnetConstants.COMMAND_MASK
            val needsAck = (cmdByte and EnetConstants.COMMAND_FLAG_ACKNOWLEDGE) != 0
            val channelId = data[pos + 1].toInt() and 0xFF
            val reliableSeq = readBeUInt16(data, pos + 2)
            val baseSize = EnetConstants.commandBaseSize(cmdNumber)
            if (baseSize < 0 || pos + baseSize > length) break

            var cmdTotalSize = baseSize
            when (cmdNumber) {
                EnetConstants.COMMAND_CONNECT -> {
                    if (peer == null) {
                        peer = handleConnect(sender, data, pos, reliableSeq, sentTime, now)
                    }
                }
                EnetConstants.COMMAND_ACKNOWLEDGE -> {
                    if (peer != null) {
                        val recvSeq = readBeUInt16(data, pos + 4)
                        val recvSentTime = readBeUInt16(data, pos + 6)
                        handleAcknowledge(peer, channelId, recvSeq, recvSentTime, now)
                    }
                }
                EnetConstants.COMMAND_DISCONNECT -> {
                    if (peer != null) {
                        if (needsAck) {
                            peer.pendingAcks.addLast(PendingAck(channelId, reliableSeq, sentTime))
                            flushPeerNow(peer, now)
                        }
                        if (peer.state == EnetPeerState.CONNECTED) {
                            events.addLast(EnetEvent(EnetEventType.DISCONNECT, peer))
                        }
                        peer.reset()
                        return
                    }
                }
                EnetConstants.COMMAND_PING -> {
                    if (peer != null && needsAck) {
                        peer.pendingAcks.addLast(PendingAck(channelId, reliableSeq, sentTime))
                    }
                }
                EnetConstants.COMMAND_SEND_RELIABLE -> {
                    val dataLen = readBeUInt16(data, pos + 4)
                    cmdTotalSize = baseSize + dataLen
                    if (pos + cmdTotalSize > length) break
                    if (peer != null) {
                        if (needsAck) {
                            peer.pendingAcks.addLast(PendingAck(channelId, reliableSeq, sentTime))
                        }
                        if (peer.state == EnetPeerState.CONNECTED && channelId < peer.channels.size) {
                            val payload = data.copyOfRange(pos + baseSize, pos + cmdTotalSize)
                            enqueueReliablePayload(peer, channelId, reliableSeq, payload)
                        }
                    }
                }
                EnetConstants.COMMAND_SEND_UNRELIABLE -> {
                    val dataLen = readBeUInt16(data, pos + 6)
                    cmdTotalSize = baseSize + dataLen
                    if (pos + cmdTotalSize > length) break
                    if (peer != null && peer.state == EnetPeerState.CONNECTED && channelId < peer.channels.size) {
                        val payload = data.copyOfRange(pos + baseSize, pos + cmdTotalSize)
                        events.addLast(EnetEvent(EnetEventType.RECEIVE, peer, channelId, payload))
                    }
                }
                EnetConstants.COMMAND_SEND_UNSEQUENCED -> {
                    val dataLen = readBeUInt16(data, pos + 6)
                    cmdTotalSize = baseSize + dataLen
                    if (pos + cmdTotalSize > length) break
                    if (peer != null && peer.state == EnetPeerState.CONNECTED && channelId < peer.channels.size) {
                        val payload = data.copyOfRange(pos + baseSize, pos + cmdTotalSize)
                        events.addLast(EnetEvent(EnetEventType.RECEIVE, peer, channelId, payload))
                    }
                }
                EnetConstants.COMMAND_SEND_FRAGMENT,
                EnetConstants.COMMAND_SEND_UNRELIABLE_FRAGMENT -> {
                    val startSeq = readBeUInt16(data, pos + 4)
                    val dataLen = readBeUInt16(data, pos + 6)
                    val fragmentCount = readBeInt32(data, pos + 8)
                    val fragmentNumber = readBeInt32(data, pos + 12)
                    val totalLength = readBeInt32(data, pos + 16)
                    val fragmentOffset = readBeInt32(data, pos + 20)
                    cmdTotalSize = baseSize + dataLen
                    if (pos + cmdTotalSize > length) break
                    if (peer != null) {
                        if (needsAck) {
                            peer.pendingAcks.addLast(PendingAck(channelId, reliableSeq, sentTime))
                        }
                        if (peer.state == EnetPeerState.CONNECTED && channelId < peer.channels.size) {
                            handleFragment(
                                peer = peer,
                                channelId = channelId,
                                startSeq = startSeq,
                                fragmentCount = fragmentCount,
                                fragmentNumber = fragmentNumber,
                                totalLength = totalLength,
                                fragmentOffset = fragmentOffset,
                                data = data,
                                payloadStart = pos + baseSize,
                                payloadLen = dataLen,
                                reliable = cmdNumber == EnetConstants.COMMAND_SEND_FRAGMENT
                            )
                        }
                    }
                }
                EnetConstants.COMMAND_BANDWIDTH_LIMIT,
                EnetConstants.COMMAND_THROTTLE_CONFIGURE -> {
                    if (peer != null && needsAck) {
                        peer.pendingAcks.addLast(PendingAck(channelId, reliableSeq, sentTime))
                    }
                }
                else -> break
            }
            pos += cmdTotalSize
        }
    }

    private fun handleConnect(
        sender: InetSocketAddress,
        data: ByteArray,
        pos: Int,
        reliableSeq: Int,
        sentTime: Int,
        now: Long
    ): EnetPeer? {
        val parsed = EnetConstants.parseConnectFields(data, pos)
        if (parsed.channelCount < EnetConstants.PROTOCOL_MINIMUM_CHANNEL_COUNT ||
            parsed.channelCount > EnetConstants.PROTOCOL_MAXIMUM_CHANNEL_COUNT
        ) {
            return null
        }

        for (existing in peers) {
            if (existing.state != EnetPeerState.DISCONNECTED &&
                existing.remoteAddress == sender &&
                existing.connectId == parsed.connectId
            ) {
                return existing
            }
        }

        val slot = peers.firstOrNull { it.state == EnetPeerState.DISCONNECTED } ?: return null
        slot.reset()

        val negotiatedChannels = minOf(parsed.channelCount, channelLimit).coerceAtLeast(1)
        val negotiatedMtu = parsed.mtu.coerceIn(EnetConstants.PROTOCOL_MINIMUM_MTU, EnetConstants.PROTOCOL_MAXIMUM_MTU)
        val negotiatedWindow = parsed.windowSize.coerceIn(
            EnetConstants.PROTOCOL_MINIMUM_WINDOW_SIZE,
            EnetConstants.PROTOCOL_MAXIMUM_WINDOW_SIZE
        )

        var incomingSession = (parsed.incomingSessionId + 1) and 0x03
        if (incomingSession == parsed.outgoingSessionId) {
            incomingSession = (incomingSession + 1) and 0x03
        }
        var outgoingSession = (parsed.outgoingSessionId + 1) and 0x03
        if (outgoingSession == parsed.incomingSessionId) {
            outgoingSession = (outgoingSession + 1) and 0x03
        }

        slot.state = EnetPeerState.ACKNOWLEDGING_CONNECT
        slot.remoteAddress = sender
        slot.remoteIp = NativeSocketUtil.normalizePeerIp(sender.address)
        slot.remotePort = sender.port
        slot.outgoingPeerId = parsed.outgoingPeerId
        slot.incomingSessionId = incomingSession
        slot.outgoingSessionId = outgoingSession
        slot.connectId = parsed.connectId
        slot.mtu = negotiatedMtu
        slot.windowSize = negotiatedWindow
        slot.channelCount = negotiatedChannels
        slot.channels = Array(negotiatedChannels) { EnetChannel() }
        slot.lastReceiveTime = now
        slot.lastSendTime = now
        slot.outgoingReliableSeqPeer = 1

        slot.pendingAcks.addLast(PendingAck(0xFF, reliableSeq, sentTime))

        val verifyCmd = EnetConstants.buildVerifyConnect(
            reliableSeq = slot.outgoingReliableSeqPeer,
            outgoingPeerId = slot.incomingPeerId,
            incomingSessionId = slot.incomingSessionId,
            outgoingSessionId = slot.outgoingSessionId,
            mtu = slot.mtu,
            windowSize = slot.windowSize,
            channelCount = slot.channelCount,
            incomingBandwidth = 0,
            outgoingBandwidth = 0,
            throttleInterval = parsed.throttleInterval,
            throttleAccel = parsed.throttleAccel,
            throttleDecel = parsed.throttleDecel,
            connectId = slot.connectId
        )
        slot.outgoingReliable.addLast(
            OutgoingReliableCommand(
                channelId = 0xFF,
                reliableSeq = slot.outgoingReliableSeqPeer,
                commandNumber = EnetConstants.COMMAND_VERIFY_CONNECT,
                bytes = verifyCmd
            )
        )
        return slot
    }

    private fun handleAcknowledge(
        peer: EnetPeer,
        channelId: Int,
        receivedSeq: Int,
        receivedSentTime: Int,
        now: Long
    ) {
        val currentLow = (now and 0xFFFFL).toInt()
        val rttSample = ((currentLow - receivedSentTime) and 0xFFFF)
        if (rttSample in 1..10000) {
            peer.rtt = ((peer.rtt * 7) + rttSample) / 8
            if (peer.rtt < 10) peer.rtt = 10
        }

        val iterator = peer.sentReliable.iterator()
        while (iterator.hasNext()) {
            val cmd = iterator.next()
            if (cmd.channelId == channelId && cmd.reliableSeq == receivedSeq) {
                iterator.remove()
                when (cmd.commandNumber) {
                    EnetConstants.COMMAND_VERIFY_CONNECT -> {
                        if (peer.state == EnetPeerState.ACKNOWLEDGING_CONNECT) {
                            peer.state = EnetPeerState.CONNECTED
                            events.addLast(EnetEvent(EnetEventType.CONNECT, peer))
                        }
                    }
                    EnetConstants.COMMAND_DISCONNECT -> {
                        if (peer.state == EnetPeerState.DISCONNECTING) {
                            events.addLast(EnetEvent(EnetEventType.DISCONNECT, peer))
                            peer.reset()
                            return
                        }
                    }
                }
                break
            }
        }
        if (peer.sentReliable.isEmpty()) {
            peer.earliestTimeout = 0L
        }
    }

    private fun enqueueReliablePayload(peer: EnetPeer, channelId: Int, reliableSeq: Int, payload: ByteArray) {
        val ch = peer.channels[channelId]
        val expected = (ch.incomingReliableSeq + 1) and 0xFFFF
        val diff = EnetConstants.seqDiff(reliableSeq, expected)
        if (diff < 0) return
        if (diff == 0) {
            ch.incomingReliableSeq = reliableSeq
            events.addLast(EnetEvent(EnetEventType.RECEIVE, peer, channelId, payload))
            drainOrderedReliable(peer, channelId, ch)
        } else {
            ch.pendingReliable[reliableSeq] = payload
        }
    }

    private fun drainOrderedReliable(peer: EnetPeer, channelId: Int, ch: EnetChannel) {
        while (true) {
            val nextSeq = (ch.incomingReliableSeq + 1) and 0xFFFF
            val nextPayload = ch.pendingReliable.remove(nextSeq) ?: break
            ch.incomingReliableSeq = nextSeq
            events.addLast(EnetEvent(EnetEventType.RECEIVE, peer, channelId, nextPayload))
        }
    }

    private fun handleFragment(
        peer: EnetPeer,
        channelId: Int,
        startSeq: Int,
        fragmentCount: Int,
        fragmentNumber: Int,
        totalLength: Int,
        fragmentOffset: Int,
        data: ByteArray,
        payloadStart: Int,
        payloadLen: Int,
        reliable: Boolean
    ) {
        if (fragmentCount <= 0 || fragmentCount > EnetConstants.PROTOCOL_MAXIMUM_FRAGMENT_COUNT) return
        if (fragmentNumber < 0 || fragmentNumber >= fragmentCount) return
        if (totalLength <= 0 || totalLength > 1024 * 1024) return
        if (fragmentOffset < 0 || fragmentOffset + payloadLen > totalLength) return

        val ch = peer.channels[channelId]
        val assembly = ch.fragmentAssemblies.getOrPut(startSeq) {
            FragmentAssembly(startSeq, fragmentCount, totalLength)
        }
        if (assembly.fragmentCount != fragmentCount || assembly.totalLength != totalLength) return
        if (!assembly.received[fragmentNumber]) {
            System.arraycopy(data, payloadStart, assembly.buffer, fragmentOffset, payloadLen)
            assembly.received[fragmentNumber] = true
            assembly.remaining--
        }
        if (assembly.remaining == 0) {
            ch.fragmentAssemblies.remove(startSeq)
            if (reliable) {
                val endSeq = (startSeq + fragmentCount - 1) and 0xFFFF
                val expected = (ch.incomingReliableSeq + 1) and 0xFFFF
                val diff = EnetConstants.seqDiff(startSeq, expected)
                if (diff == 0) {
                    ch.incomingReliableSeq = endSeq
                    events.addLast(EnetEvent(EnetEventType.RECEIVE, peer, channelId, assembly.buffer))
                    drainOrderedReliable(peer, channelId, ch)
                } else if (diff > 0) {
                    ch.pendingReliable[startSeq] = assembly.buffer
                    for (skip in 1 until fragmentCount) {
                        val seq = (startSeq + skip) and 0xFFFF
                        ch.pendingReliable.remove(seq)
                    }
                    ch.pendingReliable[endSeq] = assembly.buffer
                }
            } else {
                events.addLast(EnetEvent(EnetEventType.RECEIVE, peer, channelId, assembly.buffer))
            }
        }
    }

    private fun processTimeoutsAndPings(now: Long) {
        for (peer in peers) {
            if (peer.state == EnetPeerState.DISCONNECTED) continue
            var timedOut = false

            for (cmd in peer.sentReliable) {
                if (now - cmd.sentTime >= cmd.roundTripTimeout) {
                    if (peer.earliestTimeout == 0L) {
                        peer.earliestTimeout = cmd.sentTime
                    }
                    if (now - peer.earliestTimeout >= EnetConstants.PEER_TIMEOUT_MAXIMUM ||
                        (cmd.sendAttempts >= EnetConstants.PEER_TIMEOUT_LIMIT && now - peer.earliestTimeout >= EnetConstants.PEER_TIMEOUT_MINIMUM)
                    ) {
                        timedOut = true
                        break
                    }
                    cmd.roundTripTimeout = (cmd.roundTripTimeout * 2).coerceAtMost(8000L)
                    cmd.sentTime = 0L
                }
            }

            if (!timedOut && peer.state == EnetPeerState.CONNECTED && now - peer.lastReceiveTime >= EnetConstants.PEER_TIMEOUT_MAXIMUM) {
                timedOut = true
            }

            if (timedOut) {
                val wasConnected = peer.state == EnetPeerState.CONNECTED
                if (wasConnected) {
                    events.addLast(EnetEvent(EnetEventType.DISCONNECT_TIMEOUT, peer))
                }
                peer.reset()
                continue
            }

            if (peer.state == EnetPeerState.CONNECTED &&
                peer.sentReliable.isEmpty() &&
                peer.outgoingReliable.isEmpty() &&
                now - peer.lastReceiveTime >= EnetConstants.PEER_PING_INTERVAL &&
                now - peer.lastSendTime >= EnetConstants.PEER_PING_INTERVAL
            ) {
                peer.outgoingReliableSeqPeer = (peer.outgoingReliableSeqPeer + 1) and 0xFFFF
                val ping = EnetConstants.buildPing(peer.outgoingReliableSeqPeer)
                peer.outgoingReliable.addLast(
                    OutgoingReliableCommand(0xFF, peer.outgoingReliableSeqPeer, EnetConstants.COMMAND_PING, ping)
                )
            }
        }
    }

    private fun flushOutgoing(now: Long) {
        for (peer in peers) {
            if (peer.state == EnetPeerState.DISCONNECTED) continue
            flushPeerNow(peer, now)
        }
    }

    private fun flushPeerNow(peer: EnetPeer, now: Long) {
        val remote = peer.remoteAddress ?: return
        val maxPayload = peer.mtu.coerceAtMost(sendPacketBuf.size)
        val sentTimeLow = (now and 0xFFFFL).toInt()

        var pos = 0
        var hasTime = false
        var commandCount = 0

        fun beginPacket(includeTime: Boolean) {
            hasTime = includeTime
            pos = EnetConstants.encodePacketHeader(
                buf = sendPacketBuf,
                peerId = peer.outgoingPeerId,
                sessionId = peer.outgoingSessionId,
                sentTime = if (includeTime) sentTimeLow else null
            )
            commandCount = 0
        }

        fun sendCurrent() {
            if (commandCount == 0) return
            try {
                val bb = ByteBuffer.wrap(sendPacketBuf, 0, pos)
                channel.send(bb, remote)
                peer.lastSendTime = now
            } catch (_: Exception) {
            }
            commandCount = 0
            pos = 0
        }

        fun appendCommand(cmdBytes: ByteArray, requireTime: Boolean) {
            if (commandCount == 0) {
                beginPacket(requireTime)
            } else if (pos + cmdBytes.size > maxPayload ||
                commandCount >= EnetConstants.PROTOCOL_MAXIMUM_PACKET_COMMANDS ||
                (requireTime && !hasTime)
            ) {
                sendCurrent()
                beginPacket(requireTime)
            }
            System.arraycopy(cmdBytes, 0, sendPacketBuf, pos, cmdBytes.size)
            pos += cmdBytes.size
            commandCount++
        }

        while (peer.pendingAcks.isNotEmpty()) {
            val ack = peer.pendingAcks.removeFirst()
            val bytes = EnetConstants.buildAcknowledge(ack.channelId, 0, ack.receivedReliableSeq, ack.receivedSentTime)
            appendCommand(bytes, requireTime = false)
        }

        for (cmd in peer.sentReliable) {
            if (cmd.sentTime == 0L) {
                appendCommand(cmd.bytes, requireTime = true)
                cmd.sentTime = now
                cmd.sendAttempts++
            }
        }

        while (peer.outgoingReliable.isNotEmpty() && peer.sentReliable.size < 64) {
            val cmd = peer.outgoingReliable.removeFirst()
            cmd.sentTime = now
            cmd.roundTripTimeout = (peer.rtt * 2).coerceAtLeast(250).toLong()
            cmd.sendAttempts = 1
            peer.sentReliable.add(cmd)
            appendCommand(cmd.bytes, requireTime = true)
        }

        while (peer.outgoingUnreliable.isNotEmpty()) {
            val bytes = peer.outgoingUnreliable.removeFirst()
            appendCommand(bytes, requireTime = false)
        }

        sendCurrent()
    }

    companion object {
        fun createServer(host: String, port: Int, maxPeers: Int, channels: Int): EnetHost? {
            val bindAddr = NativeSocketUtil.resolveBindSocketAddress(host, port) ?: return null
            val datagram = DatagramChannel.open()
            return try {
                try {
                    datagram.setOption(StandardSocketOptions.SO_REUSEADDR, true)
                    datagram.setOption(StandardSocketOptions.SO_RCVBUF, 256 * 1024)
                    datagram.setOption(StandardSocketOptions.SO_SNDBUF, 256 * 1024)
                } catch (_: Exception) {
                    try {
                        datagram.socket().reuseAddress = true
                    } catch (_: Exception) {
                    }
                }
                datagram.bind(bindAddr)
                datagram.configureBlocking(false)
                EnetHost(datagram, maxPeers, channels)
            } catch (e: Exception) {
                ServerLogger.error(LogCategory.ENET, "enet_host_create failed on $host:$port (${e.message})")
                try {
                    datagram.close()
                } catch (_: Exception) {
                }
                null
            }
        }
    }
}
