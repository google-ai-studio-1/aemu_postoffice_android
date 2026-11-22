package com.example.core.relay

import com.example.core.adhocctl.AdhocctlSnapshot
import com.example.core.common.ByteKey
import com.example.core.common.LogCategory
import com.example.core.common.MutableByteQueue
import com.example.core.common.NativeTcpSocket
import com.example.core.common.ServerConfig
import com.example.core.common.ServerLogger
import com.example.core.common.macBytesToString
import com.example.core.common.readLeUInt32
import com.example.core.enet.BasicEnetServer
import com.example.core.enet.EnetRecvStatus
import com.example.core.enet.EnetSendStatus

enum class PendingSessionPumpStatus {
    SUCCESS,
    SOCKET_CLOSED,
    SESSION_READY,
    TIMEOUT,
    BAD_INIT
}

enum class SessionMode {
    PDP,
    PTP_LISTEN,
    PTP_CONNECT,
    PTP_ACCEPT
}

enum class DataQueueStatus {
    SUCCESS,
    MAX_DATA_REACHED
}

enum class SessionPumpStatus {
    SUCCESS,
    SOCKET_CLOSED,
    BAD_DATA_SIZE,
    TIMEOUT,
    CONNECTED
}

enum class SessionPhase {
    PTP_CONNECTING,
    HEADER,
    DATA
}

data class SendListItem(
    val sessionName: String,
    val fromMac: ByteKey,
    val data: ByteArray
)

object RelaySessionNaming {
    fun listenSessionName(mac: ByteArray, port: Int): String {
        return "PTP_LISTEN ${macBytesToString(mac)} $port"
    }

    fun connectSessionName(srcMac: ByteArray, sport: Int, dstMac: ByteArray, dport: Int): String {
        return "PTP_CONNECT ${macBytesToString(srcMac)} $sport ${macBytesToString(dstMac)} $dport"
    }

    fun acceptSessionName(srcMac: ByteArray, sport: Int, dstMac: ByteArray, dport: Int): String {
        return "PTP_ACCEPT ${macBytesToString(srcMac)} $sport ${macBytesToString(dstMac)} $dport"
    }

    fun pdpSessionName(mac: ByteArray, port: Int): String {
        return "PDP ${macBytesToString(mac)} $port"
    }
}

class PendingSession(
    private var tcpSocket: NativeTcpSocket?,
    private var enetPeerRef: Int,
    val clientAddr: String,
    val clientPort: Int,
    @Volatile var config: ServerConfig,
    private val enetServer: BasicEnetServer?
) {
    private val initDataBuffer = MutableByteQueue(64)
    private val createTimeNs = System.nanoTime()
    private val recvTmp = ByteArray(RelayPackets.SIZE_INIT)
    private var closed = false

    init {
        ServerLogger.info(LogCategory.RELAY, "PendingSession: $clientAddr connecting")
    }

    fun pump(
        globalSessions: Map<String, RelaySession>,
        adhocctlSnapshot: AdhocctlSnapshot
    ): PendingSessionPumpStatus {
        val elapsedMs = (System.nanoTime() - createTimeNs) / 1_000_000L
        if (elapsedMs > config.sessionInitTimeLimitMs) {
            ServerLogger.warn(LogCategory.RELAY, "pump: session creation for $clientAddr timed out")
            closeSocket()
            return PendingSessionPumpStatus.TIMEOUT
        }

        if (enetServer == null) {
            val readLen = tcpSocket?.recv(recvTmp, 0, recvTmp.size) ?: -2
            if (readLen == 0) {
                ServerLogger.info(LogCategory.RELAY, "pump: client $clientAddr closed socket during init")
                closeSocket()
                return PendingSessionPumpStatus.SOCKET_CLOSED
            }
            if (readLen < -1) {
                ServerLogger.warn(LogCategory.RELAY, "pump: client $clientAddr has socket error during init")
                closeSocket()
                return PendingSessionPumpStatus.SOCKET_CLOSED
            }
            if (readLen > 0) {
                initDataBuffer.append(recvTmp, 0, readLen)
            }
        } else if (initDataBuffer.size < RelayPackets.SIZE_INIT) {
            val res = enetServer.recv(enetPeerRef, recvTmp, recvTmp.size, 0)
            if (res.length == -1) {
                if (res.status != EnetRecvStatus.WOULD_BLOCK) {
                    ServerLogger.warn(LogCategory.RELAY, "pump: client $clientAddr has enet recv error ${res.status} during init")
                    closeSocket()
                    return PendingSessionPumpStatus.SOCKET_CLOSED
                }
            } else {
                initDataBuffer.append(recvTmp, 0, res.length)
            }
        }

        if (initDataBuffer.size >= RelayPackets.SIZE_INIT) {
            val init = RelayPackets.parseInit(initDataBuffer.backingArray(), initDataBuffer.headOffset())
            fun strictModeCheck(): Boolean {
                if (!config.strictMode) return true
                val srcMac = ByteKey(init.srcAddr)
                val srcClient = adhocctlSnapshot.clients[srcMac] ?: return false
                if (clientAddr != srcClient.ip) return false
                val game = adhocctlSnapshot.games[srcClient.gameCode] ?: return false
                val group = game.groups[srcClient.groupKey] ?: return false
                if (group.channel < 0) return false
                if (init.initType == RelayPackets.INIT_PTP_ACCEPT || init.initType == RelayPackets.INIT_PTP_CONNECT) {
                    val dstMac = ByteKey(init.dstAddr)
                    if (!group.members.contains(dstMac)) return false
                }
                return true
            }

            return when (init.initType) {
                RelayPackets.INIT_PTP_LISTEN,
                RelayPackets.INIT_PDP_UNRELIABLE,
                RelayPackets.INIT_PDP -> {
                    if (strictModeCheck()) PendingSessionPumpStatus.SESSION_READY else PendingSessionPumpStatus.SUCCESS
                }
                RelayPackets.INIT_PTP_CONNECT -> {
                    val listenName = RelaySessionNaming.listenSessionName(init.dstAddr, init.dport)
                    if (!globalSessions.containsKey(listenName)) {
                        PendingSessionPumpStatus.SUCCESS
                    } else {
                        if (strictModeCheck()) PendingSessionPumpStatus.SESSION_READY else PendingSessionPumpStatus.SUCCESS
                    }
                }
                RelayPackets.INIT_PTP_ACCEPT -> {
                    val connectName = RelaySessionNaming.connectSessionName(init.dstAddr, init.dport, init.srcAddr, init.sport)
                    val connectSession = globalSessions[connectName]
                    if (connectSession == null || connectSession.phase != SessionPhase.PTP_CONNECTING) {
                        ServerLogger.warn(
                            LogCategory.RELAY,
                            "pump: peer session $connectName not found, not creating ptp accept session for $clientAddr"
                        )
                        closeSocket()
                        PendingSessionPumpStatus.SOCKET_CLOSED
                    } else {
                        if (strictModeCheck()) PendingSessionPumpStatus.SESSION_READY else PendingSessionPumpStatus.SUCCESS
                    }
                }
                else -> {
                    ServerLogger.warn(LogCategory.RELAY, "pump: unknown init type ${init.initType}, not creating session for $clientAddr")
                    closeSocket()
                    PendingSessionPumpStatus.BAD_INIT
                }
            }
        }
        return PendingSessionPumpStatus.SUCCESS
    }

    fun createSession(globalSessions: Map<String, RelaySession>): RelaySession? {
        val init = RelayPackets.parseInit(initDataBuffer.backingArray(), initDataBuffer.headOffset())
        initDataBuffer.consume(RelayPackets.SIZE_INIT)
        val remaining = initDataBuffer.toByteArray()

        return when (init.initType) {
            RelayPackets.INIT_PDP,
            RelayPackets.INIT_PDP_UNRELIABLE -> {
                RelaySession(
                    mode = SessionMode.PDP,
                    fromMacBytes = init.srcAddr,
                    fromPort = init.sport,
                    toMacBytes = null,
                    toPort = 0,
                    initialDataBuffer = remaining,
                    tcpSocket = tcpSocket,
                    enetPeerRef = enetPeerRef,
                    peerSession = null,
                    clientAddr = clientAddr,
                    clientPort = clientPort,
                    config = config,
                    enetServer = enetServer,
                    enetReliable = init.initType == RelayPackets.INIT_PDP
                )
            }
            RelayPackets.INIT_PTP_LISTEN -> {
                RelaySession(
                    mode = SessionMode.PTP_LISTEN,
                    fromMacBytes = init.srcAddr,
                    fromPort = init.sport,
                    toMacBytes = null,
                    toPort = 0,
                    initialDataBuffer = ByteArray(0),
                    tcpSocket = tcpSocket,
                    enetPeerRef = enetPeerRef,
                    peerSession = null,
                    clientAddr = clientAddr,
                    clientPort = clientPort,
                    config = config,
                    enetServer = enetServer,
                    enetReliable = true
                )
            }
            RelayPackets.INIT_PTP_CONNECT -> {
                val listenName = RelaySessionNaming.listenSessionName(init.dstAddr, init.dport)
                val listenSession = globalSessions[listenName] ?: return null
                RelaySession(
                    mode = SessionMode.PTP_CONNECT,
                    fromMacBytes = init.srcAddr,
                    fromPort = init.sport,
                    toMacBytes = init.dstAddr,
                    toPort = init.dport,
                    initialDataBuffer = remaining,
                    tcpSocket = tcpSocket,
                    enetPeerRef = enetPeerRef,
                    peerSession = listenSession,
                    clientAddr = clientAddr,
                    clientPort = clientPort,
                    config = config,
                    enetServer = enetServer,
                    enetReliable = true
                )
            }
            RelayPackets.INIT_PTP_ACCEPT -> {
                val connectName = RelaySessionNaming.connectSessionName(init.dstAddr, init.dport, init.srcAddr, init.sport)
                val connectSession = globalSessions[connectName] ?: return null
                RelaySession(
                    mode = SessionMode.PTP_ACCEPT,
                    fromMacBytes = init.srcAddr,
                    fromPort = init.sport,
                    toMacBytes = init.dstAddr,
                    toPort = init.dport,
                    initialDataBuffer = remaining,
                    tcpSocket = tcpSocket,
                    enetPeerRef = enetPeerRef,
                    peerSession = connectSession,
                    clientAddr = clientAddr,
                    clientPort = clientPort,
                    config = config,
                    enetServer = enetServer,
                    enetReliable = true
                )
            }
            else -> null
        }
    }

    fun closeSocket() {
        if (closed) return
        closed = true
        if (enetServer == null) {
            tcpSocket?.close()
            tcpSocket = null
        } else if (enetPeerRef != -1) {
            enetServer.close(enetPeerRef)
            enetPeerRef = -1
        }
    }
}

class RelaySession(
    val mode: SessionMode,
    fromMacBytes: ByteArray,
    val fromPort: Int,
    toMacBytes: ByteArray?,
    val toPort: Int,
    initialDataBuffer: ByteArray,
    private var tcpSocket: NativeTcpSocket?,
    private var enetPeerRef: Int,
    peerSession: RelaySession?,
    val clientAddr: String,
    val clientPort: Int,
    @Volatile var config: ServerConfig,
    private val enetServer: BasicEnetServer?,
    val enetReliable: Boolean
) {
    val fromMac = ByteKey(fromMacBytes.copyOf(6))
    val toMac: ByteKey? = toMacBytes?.let { ByteKey(it.copyOf(6)) }
    val identifier: String
    val peerIdentifier: String
    var phase: SessionPhase = SessionPhase.HEADER
        private set

    private val sendList = ArrayList<SendListItem>()
    private val fromClientDataBuffer = MutableByteQueue(4096)
    private val toClientDataBuffer = MutableByteQueue(4096)
    private val createTimeNs = System.nanoTime()
    private var pdpDataTarget: String = ""
    private var dataSize: Long = 0L
    private var ptpInitSent: Boolean = false
    private var closed = false

    val isEnet: Boolean
        get() = enetServer != null

    val queuedSendBytes: Int
        get() = toClientDataBuffer.size

    init {
        identifier = when (mode) {
            SessionMode.PDP -> RelaySessionNaming.pdpSessionName(fromMac.bytes, fromPort)
            SessionMode.PTP_LISTEN -> RelaySessionNaming.listenSessionName(fromMac.bytes, fromPort)
            SessionMode.PTP_CONNECT -> RelaySessionNaming.connectSessionName(fromMac.bytes, fromPort, toMac!!.bytes, toPort)
            SessionMode.PTP_ACCEPT -> RelaySessionNaming.acceptSessionName(fromMac.bytes, fromPort, toMac!!.bytes, toPort)
        }
        peerIdentifier = when (mode) {
            SessionMode.PDP, SessionMode.PTP_LISTEN -> ""
            SessionMode.PTP_CONNECT -> RelaySessionNaming.acceptSessionName(toMac!!.bytes, toPort, fromMac.bytes, fromPort)
            SessionMode.PTP_ACCEPT -> RelaySessionNaming.connectSessionName(toMac!!.bytes, toPort, fromMac.bytes, fromPort)
        }

        if (mode != SessionMode.PTP_LISTEN && initialDataBuffer.isNotEmpty()) {
            fromClientDataBuffer.append(initialDataBuffer)
        }

        if (mode == SessionMode.PTP_CONNECT && peerSession != null) {
            val notify = RelayPackets.buildPtpConnectPacket(fromMac.bytes, fromPort)
            sendList.add(SendListItem(peerSession.identifier, fromMac, notify))
            phase = SessionPhase.PTP_CONNECTING
        }

        if (mode == SessionMode.PTP_ACCEPT && peerSession != null) {
            val toConnect = RelayPackets.buildPtpConnectPacket(fromMac.bytes, fromPort)
            sendList.add(SendListItem(peerSession.identifier, fromMac, toConnect))

            val toAccept = RelayPackets.buildPtpConnectPacket(peerSession.fromMac.bytes, peerSession.fromPort)
            peerSession.sendList.add(SendListItem(identifier, peerSession.fromMac, toAccept))

            phase = SessionPhase.HEADER
            peerSession.phase = SessionPhase.HEADER
            ServerLogger.info(LogCategory.RELAY, "Session: bonding ${peerSession.identifier} with $identifier")
        }

        ServerLogger.info(LogCategory.RELAY, "Session: created session $identifier for $clientAddr")
        if (enetServer != null && !enetReliable) {
            ServerLogger.info(LogCategory.RELAY, "Session: session $identifier of $clientAddr uses unreliable enet")
        }
    }

    fun pumpConnect(): SessionPumpStatus {
        if (mode != SessionMode.PTP_CONNECT) {
            return SessionPumpStatus.SUCCESS
        }
        if (phase != SessionPhase.PTP_CONNECTING) {
            return SessionPumpStatus.CONNECTED
        }
        val elapsedMs = (System.nanoTime() - createTimeNs) / 1_000_000L
        if (elapsedMs > config.connectTimeLimitMs) {
            return SessionPumpStatus.TIMEOUT
        }
        return SessionPumpStatus.SUCCESS
    }

    fun pumpFromClient(recvScratch: ByteArray): SessionPumpStatus {
        var ret = SessionPumpStatus.SUCCESS
        while (true) {
            if (enetServer == null) {
                val readLen = tcpSocket?.recv(recvScratch, 0, recvScratch.size) ?: -2
                if (readLen == 0) {
                    ServerLogger.info(LogCategory.RELAY, "pump_from_client: client $clientAddr of session $identifier has closed the socket")
                    ret = SessionPumpStatus.SOCKET_CLOSED
                    break
                }
                if (readLen == -1) {
                    ret = SessionPumpStatus.SUCCESS
                    break
                }
                if (readLen < -1) {
                    ServerLogger.warn(LogCategory.RELAY, "pump_from_client: socket error on session $identifier with client $clientAddr")
                    ret = SessionPumpStatus.SOCKET_CLOSED
                    break
                }
                if (mode == SessionMode.PTP_LISTEN) {
                    continue
                }
                fromClientDataBuffer.append(recvScratch, 0, readLen)
            } else {
                val res = enetServer.recv(enetPeerRef, recvScratch, recvScratch.size, 0)
                if (res.status == EnetRecvStatus.WOULD_BLOCK) {
                    break
                }
                if (res.status != EnetRecvStatus.SUCCESS) {
                    ret = SessionPumpStatus.SOCKET_CLOSED
                    break
                }
                if (mode == SessionMode.PTP_LISTEN) {
                    continue
                }
                fromClientDataBuffer.append(recvScratch, 0, res.length)
            }
        }

        while (mode != SessionMode.PTP_LISTEN && phase != SessionPhase.PTP_CONNECTING) {
            if (phase == SessionPhase.HEADER) {
                if (mode == SessionMode.PDP) {
                    if (fromClientDataBuffer.size >= RelayPackets.SIZE_PDP) {
                        val header = RelayPackets.parsePdpHeader(fromClientDataBuffer.backingArray(), fromClientDataBuffer.headOffset())
                        pdpDataTarget = RelaySessionNaming.pdpSessionName(header.addr, header.port)
                        dataSize = header.size
                        if (dataSize > RelayPackets.PDP_BLOCK_MAX * 2L) {
                            ServerLogger.warn(LogCategory.RELAY, "pump_from_client: bad data size $dataSize on session $identifier with client $clientAddr")
                            return SessionPumpStatus.BAD_DATA_SIZE
                        }
                        fromClientDataBuffer.consume(RelayPackets.SIZE_PDP)
                        phase = SessionPhase.DATA
                        continue
                    }
                } else {
                    if (fromClientDataBuffer.size >= RelayPackets.SIZE_PTP_DATA) {
                        dataSize = RelayPackets.parsePtpDataSize(fromClientDataBuffer.backingArray(), fromClientDataBuffer.headOffset())
                        if (dataSize > RelayPackets.PTP_BLOCK_MAX * 2L) {
                            ServerLogger.warn(LogCategory.RELAY, "pump_from_client: bad data size $dataSize on session $identifier with client $clientAddr")
                            return SessionPumpStatus.BAD_DATA_SIZE
                        }
                        fromClientDataBuffer.consume(RelayPackets.SIZE_PTP_DATA)
                        phase = SessionPhase.DATA
                        continue
                    }
                }
            } else {
                val needed = dataSize.toInt()
                if (mode == SessionMode.PDP) {
                    if (fromClientDataBuffer.size >= needed) {
                        val framed = RelayPackets.buildPdpPacket(
                            fromMac = fromMac.bytes,
                            fromPort = fromPort,
                            payload = fromClientDataBuffer.backingArray(),
                            payloadOffset = fromClientDataBuffer.headOffset(),
                            payloadLen = needed
                        )
                        fromClientDataBuffer.consume(needed)
                        sendList.add(SendListItem(pdpDataTarget, fromMac, framed))
                        phase = SessionPhase.HEADER
                        continue
                    }
                } else {
                    if (fromClientDataBuffer.size >= needed) {
                        val framed = RelayPackets.buildPtpDataPacket(
                            payload = fromClientDataBuffer.backingArray(),
                            payloadOffset = fromClientDataBuffer.headOffset(),
                            payloadLen = needed
                        )
                        fromClientDataBuffer.consume(needed)
                        sendList.add(SendListItem(peerIdentifier, fromMac, framed))
                        phase = SessionPhase.HEADER
                        continue
                    }
                }
            }
            break
        }

        return ret
    }

    fun drainSendList(): List<SendListItem> {
        if (sendList.isEmpty()) return emptyList()
        val out = ArrayList(sendList)
        sendList.clear()
        return out
    }

    fun queueSend(data: ByteArray): DataQueueStatus {
        toClientDataBuffer.append(data)
        if (toClientDataBuffer.size.toLong() >= config.dataQueueSizeLimitByte) {
            ServerLogger.warn(
                LogCategory.RELAY,
                "queue_send: session $identifier from $clientAddr has reached receive data buffer limit ${config.dataQueueSizeLimitByte}b"
            )
            return DataQueueStatus.MAX_DATA_REACHED
        }
        return DataQueueStatus.SUCCESS
    }

    fun pumpToClient(): SessionPumpStatus {
        while (true) {
            if (toClientDataBuffer.isEmpty()) {
                return SessionPumpStatus.SUCCESS
            }

            if (enetServer == null) {
                val written = tcpSocket?.send(
                    toClientDataBuffer.backingArray(),
                    toClientDataBuffer.headOffset(),
                    toClientDataBuffer.size
                ) ?: -2
                if (written == -1) {
                    return SessionPumpStatus.SUCCESS
                }
                if (written < -1) {
                    ServerLogger.warn(LogCategory.RELAY, "pump_to_client: sock error on session $identifier with client $clientAddr")
                    return SessionPumpStatus.SOCKET_CLOSED
                }
                toClientDataBuffer.consume(written)
            } else {
                val packetSize = when (mode) {
                    SessionMode.PDP -> {
                        if (toClientDataBuffer.size < RelayPackets.SIZE_PDP) return SessionPumpStatus.SUCCESS
                        val sizeField = readLeUInt32(toClientDataBuffer.backingArray(), toClientDataBuffer.headOffset() + 10)
                        RelayPackets.SIZE_PDP + sizeField.toInt()
                    }
                    SessionMode.PTP_LISTEN -> {
                        if (toClientDataBuffer.size < RelayPackets.SIZE_PTP_CONNECT) return SessionPumpStatus.SUCCESS
                        RelayPackets.SIZE_PTP_CONNECT
                    }
                    SessionMode.PTP_CONNECT,
                    SessionMode.PTP_ACCEPT -> {
                        if (!ptpInitSent) {
                            if (toClientDataBuffer.size < RelayPackets.SIZE_PTP_CONNECT) return SessionPumpStatus.SUCCESS
                            RelayPackets.SIZE_PTP_CONNECT
                        } else {
                            if (toClientDataBuffer.size < RelayPackets.SIZE_PTP_DATA) return SessionPumpStatus.SUCCESS
                            val sizeField = readLeUInt32(toClientDataBuffer.backingArray(), toClientDataBuffer.headOffset())
                            RelayPackets.SIZE_PTP_DATA + sizeField.toInt()
                        }
                    }
                }
                if (toClientDataBuffer.size < packetSize) {
                    return SessionPumpStatus.SUCCESS
                }
                val (_, status) = enetServer.send(
                    peerRef = enetPeerRef,
                    buf = toClientDataBuffer.backingArray(),
                    offset = toClientDataBuffer.headOffset(),
                    len = packetSize,
                    channel = 0,
                    reliable = enetReliable
                )
                if (status != EnetSendStatus.SUCCESS) {
                    ServerLogger.warn(LogCategory.RELAY, "pump_to_client: enet error $status on session $identifier with client $clientAddr")
                    return SessionPumpStatus.SOCKET_CLOSED
                }
                if ((mode == SessionMode.PTP_CONNECT || mode == SessionMode.PTP_ACCEPT) && !ptpInitSent) {
                    ptpInitSent = true
                }
                toClientDataBuffer.consume(packetSize)
            }
        }
    }

    fun closeSocket() {
        if (closed) return
        closed = true
        if (enetServer == null) {
            tcpSocket?.close()
            tcpSocket = null
        } else if (enetPeerRef != -1) {
            enetServer.close(enetPeerRef)
            enetPeerRef = -1
        }
    }

    fun toInfo(): RelaySessionInfo {
        return RelaySessionInfo(
            identifier = identifier,
            mode = mode,
            phase = phase,
            clientAddr = clientAddr,
            clientPort = clientPort,
            fromMac = fromMac.toMacString(),
            fromPort = fromPort,
            toMac = toMac?.toMacString() ?: "",
            toPort = toPort,
            transport = if (isEnet) {
                if (enetReliable) "ENet" else "ENet-Unreliable"
            } else {
                "TCP"
            },
            queuedSendBytes = queuedSendBytes
        )
    }
}
