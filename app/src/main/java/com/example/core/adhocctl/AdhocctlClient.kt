package com.example.core.adhocctl

import com.example.core.common.ByteKey
import com.example.core.common.LogCategory
import com.example.core.common.MutableByteQueue
import com.example.core.common.NativeTcpSocket
import com.example.core.common.ServerConfig
import com.example.core.common.ServerLogger
import com.example.core.common.extractNullTerminatedString
import com.example.core.enet.BasicEnetServer
import com.example.core.enet.EnetRecvStatus
import com.example.core.enet.EnetSendStatus

enum class ClientOpType {
    CONNECT,
    CHAT,
    SCAN,
    DISCONNECT
}

data class ClientOp(
    val mac: ByteKey,
    val type: ClientOpType,
    val channel: Int = 0,
    val group: String = "",
    val message: String = ""
)

enum class ClientPumpStatus {
    SUCCESS,
    DISCONNECT,
    ERROR,
    OVERFLOW,
    TIMEDOUT
}

enum class ServerToClientOpQueueStatus {
    SUCCESS,
    OVERFLOW
}

class AdhocctlClient(
    private val tcpSocket: NativeTcpSocket?,
    private val enetPeerRef: Int,
    private val enetServer: BasicEnetServer?,
    val ip: String,
    val port: Int,
    val socketName: String,
    val protocolRevision: Int,
    @Volatile var config: ServerConfig
) {
    var loggedIn: Boolean = false
        private set
    var mac: ByteKey = ByteKey(ByteArray(6))
        private set
    var nickname: String = ""
        private set
    var channel: Int = AdhocctlPackets.V1_PROTOCOL_DEFAULT_CHANNEL
        private set
    var gameCode: String = ""
    var group: String = ""
    var id: Int = 0

    private val recvBuf = MutableByteQueue(4096)
    private val sendBuf = MutableByteQueue(4096)
    private val clientOps = ArrayList<ClientOp>()
    private val createTimeNs = System.nanoTime()
    private var lastSeenNs = System.nanoTime()
    private val tempRecv = ByteArray(4096)

    init {
        ServerLogger.info(LogCategory.ADHOCCTL, "Client: client with socket name $socketName created")
    }

    fun closeSocket() {
        if (enetServer == null) {
            tcpSocket?.close()
        } else {
            enetServer.close(enetPeerRef)
        }
    }

    private fun checkSendQueueSize(): ServerToClientOpQueueStatus {
        if (sendBuf.size > config.adhocctlDataQueueSizeLimitByte) {
            ServerLogger.warn(
                LogCategory.ADHOCCTL,
                "check_send_queue_size: client ${mac.toMacString()} $socketName is not receiving data timely"
            )
            return ServerToClientOpQueueStatus.OVERFLOW
        }
        return ServerToClientOpQueueStatus.SUCCESS
    }

    fun connectAck(groupMac: ByteKey): ServerToClientOpQueueStatus {
        if (protocolRevision == 1) {
            sendBuf.append(AdhocctlPackets.buildConnectBssidS2C(groupMac.bytes))
        }
        return checkSendQueueSize()
    }

    fun disconnectNotify(peer: AdhocctlClient): ServerToClientOpQueueStatus {
        if (protocolRevision == 1) {
            sendBuf.append(AdhocctlPackets.buildDisconnectS2C(peer.id))
        }
        return checkSendQueueSize()
    }

    fun chat(senderNickname: String, message: String): ServerToClientOpQueueStatus {
        if (protocolRevision == 1) {
            sendBuf.append(AdhocctlPackets.buildChatS2C(senderNickname, message))
        }
        return checkSendQueueSize()
    }

    fun scanResult(groupName: String, leaderMac: ByteKey): ServerToClientOpQueueStatus {
        if (protocolRevision == 1) {
            sendBuf.append(AdhocctlPackets.buildScanS2C(groupName, leaderMac.bytes))
        }
        return checkSendQueueSize()
    }

    fun scanComplete(): ServerToClientOpQueueStatus {
        if (protocolRevision == 1) {
            sendBuf.append(AdhocctlPackets.buildScanCompleteS2C())
        }
        return checkSendQueueSize()
    }

    fun connectNotify(peer: AdhocctlClient): ServerToClientOpQueueStatus {
        if (protocolRevision == 1) {
            sendBuf.append(AdhocctlPackets.buildConnectNotifyS2C(peer.nickname, peer.mac.bytes, peer.id))
        }
        return checkSendQueueSize()
    }

    fun pumpFromClient(): ClientPumpStatus {
        val nowNs = System.nanoTime()
        if (!loggedIn) {
            val elapsedSec = (nowNs - createTimeNs) / 1_000_000_000L
            if (elapsedSec > 5L) {
                ServerLogger.warn(LogCategory.ADHOCCTL, "pump_from_client: pending client $socketName has timed out")
                return ClientPumpStatus.TIMEDOUT
            }
        }

        val idleMs = (nowNs - lastSeenNs) / 1_000_000L
        if (idleMs > config.adhocctlTimeoutMs) {
            ServerLogger.warn(
                LogCategory.ADHOCCTL,
                "pump_from_client: client $socketName ${mac.toMacString()} has timed out"
            )
            return ClientPumpStatus.TIMEDOUT
        }

        while (true) {
            if (enetServer == null) {
                val readLen = tcpSocket?.recv(tempRecv, 0, tempRecv.size) ?: -2
                if (readLen == 0) {
                    ServerLogger.info(
                        LogCategory.ADHOCCTL,
                        "pump_from_client: client $socketName ${mac.toMacString()} has disconnected"
                    )
                    return ClientPumpStatus.DISCONNECT
                }
                if (readLen == -1) {
                    break
                }
                if (readLen < -1) {
                    ServerLogger.warn(
                        LogCategory.ADHOCCTL,
                        "pump_from_client: client $socketName ${mac.toMacString()} has socket error"
                    )
                    return ClientPumpStatus.ERROR
                }
                recvBuf.append(tempRecv, 0, readLen)
            } else {
                val res = enetServer.recv(enetPeerRef, tempRecv, tempRecv.size, 0)
                if (res.length == -1) {
                    if (res.status == EnetRecvStatus.WOULD_BLOCK) {
                        break
                    }
                    ServerLogger.warn(
                        LogCategory.ADHOCCTL,
                        "pump_from_client: client $socketName ${mac.toMacString()} has enet error ${res.status}"
                    )
                    return ClientPumpStatus.ERROR
                }
                recvBuf.append(tempRecv, 0, res.length)
            }
        }

        if (recvBuf.size > config.adhocctlDataQueueSizeLimitByte) {
            ServerLogger.warn(
                LogCategory.ADHOCCTL,
                "client_sent_too_much: client ${mac.toMacString()} $socketName is sending too much data"
            )
            return ClientPumpStatus.OVERFLOW
        }

        return if (protocolRevision == 1) {
            processRecvBufV1()
        } else {
            ClientPumpStatus.ERROR
        }
    }

    private fun processRecvBufV1(): ClientPumpStatus {
        var processedPacket = false
        while (true) {
            if (recvBuf.size < AdhocctlPackets.SIZE_BASE) {
                if (processedPacket) {
                    lastSeenNs = System.nanoTime()
                }
                return ClientPumpStatus.SUCCESS
            }

            val opcode = recvBuf.peekByte(0).toInt() and 0xFF
            val packetSize = when (opcode) {
                AdhocctlPackets.OPCODE_LOGIN -> AdhocctlPackets.SIZE_LOGIN_C2S
                AdhocctlPackets.OPCODE_PING -> AdhocctlPackets.SIZE_BASE
                AdhocctlPackets.OPCODE_CONNECT -> {
                    if (!loggedIn) {
                        ServerLogger.warn(LogCategory.ADHOCCTL, "process_recv_buf_v1: client $socketName sending OPCODE_CONNECT before logging in")
                        return ClientPumpStatus.ERROR
                    }
                    AdhocctlPackets.SIZE_CONNECT_C2S
                }
                AdhocctlPackets.OPCODE_DISCONNECT -> {
                    if (!loggedIn) {
                        ServerLogger.warn(LogCategory.ADHOCCTL, "process_recv_buf_v1: client $socketName sending OPCODE_DISCONNECT before logging in")
                        return ClientPumpStatus.ERROR
                    }
                    AdhocctlPackets.SIZE_BASE
                }
                AdhocctlPackets.OPCODE_SCAN -> {
                    if (!loggedIn) {
                        ServerLogger.warn(LogCategory.ADHOCCTL, "process_recv_buf_v1: client $socketName sending OPCODE_SCAN before logging in")
                        return ClientPumpStatus.ERROR
                    }
                    AdhocctlPackets.SIZE_BASE
                }
                AdhocctlPackets.OPCODE_CHAT -> {
                    if (!loggedIn) {
                        ServerLogger.warn(LogCategory.ADHOCCTL, "process_recv_buf_v1: client $socketName sending OPCODE_CHAT before logging in")
                        return ClientPumpStatus.ERROR
                    }
                    AdhocctlPackets.SIZE_CHAT_C2S
                }
                else -> {
                    ServerLogger.warn(LogCategory.ADHOCCTL, "process_recv_buf_v1: client $socketName sent invalid opcode $opcode")
                    return ClientPumpStatus.ERROR
                }
            }

            if (recvBuf.size < packetSize) {
                if (processedPacket) {
                    lastSeenNs = System.nanoTime()
                }
                return ClientPumpStatus.SUCCESS
            }

            processedPacket = true
            val backing = recvBuf.backingArray()
            val head = recvBuf.headOffset()

            when (opcode) {
                AdhocctlPackets.OPCODE_LOGIN -> {
                    val macBytes = ByteArray(AdhocctlPackets.ETHER_ADDR_LEN)
                    System.arraycopy(backing, head + 1, macBytes, 0, AdhocctlPackets.ETHER_ADDR_LEN)
                    mac = ByteKey(macBytes)

                    val nickRawOffset = head + 1 + AdhocctlPackets.ETHER_ADDR_LEN
                    nickname = extractNullTerminatedString(backing, nickRawOffset, AdhocctlPackets.NICKNAME_LEN)
                    val gameRawOffset = nickRawOffset + AdhocctlPackets.NICKNAME_LEN
                    gameCode = extractNullTerminatedString(backing, gameRawOffset, AdhocctlPackets.PRODUCT_CODE_LEN)
                    channel = AdhocctlPackets.V1_PROTOCOL_DEFAULT_CHANNEL
                    loggedIn = true

                    var badMac = true
                    for (i in 1 until 6) {
                        if (macBytes[i].toInt() != 0) {
                            badMac = false
                            break
                        }
                    }
                    if (badMac) {
                        ServerLogger.warn(
                            LogCategory.ADHOCCTL,
                            "process_recv_buf_v1: client $socketName $nickname tries to use xx:00:00:00:00:00 as mac, rejecting"
                        )
                        return ClientPumpStatus.ERROR
                    }

                    if (gameCode.length < 9) {
                        ServerLogger.warn(
                            LogCategory.ADHOCCTL,
                            "process_recv_buf_v1: client $socketName $nickname logs in with bad game code $gameCode (len ${gameCode.length}), rejecting"
                        )
                        return ClientPumpStatus.ERROR
                    }

                    for (i in 0 until 9) {
                        val c = gameCode[i]
                        if (c in '0'..'9' || c in 'A'..'Z') {
                            continue
                        }
                        ServerLogger.warn(
                            LogCategory.ADHOCCTL,
                            "process_recv_buf_v1: client $socketName $nickname logs in with bad game code $gameCode (bad character $c), rejecting"
                        )
                        return ClientPumpStatus.ERROR
                    }

                    if (nickname.toByteArray(Charsets.UTF_8).size >= 128) {
                        ServerLogger.warn(
                            LogCategory.ADHOCCTL,
                            "process_recv_buf_v1: client $socketName $nickname has bad nickname, rejecting"
                        )
                        return ClientPumpStatus.ERROR
                    }

                    ServerLogger.info(
                        LogCategory.ADHOCCTL,
                        "process_recv_buf_v1: client $socketName $nickname logged in game $gameCode with mac address ${mac.toMacString()} channel $channel"
                    )
                }
                AdhocctlPackets.OPCODE_PING -> Unit
                AdhocctlPackets.OPCODE_CONNECT -> {
                    val grpName = extractNullTerminatedString(backing, head + 1, AdhocctlPackets.GROUPNAME_LEN)
                    clientOps.add(
                        ClientOp(
                            mac = mac,
                            type = ClientOpType.CONNECT,
                            channel = channel,
                            group = grpName
                        )
                    )
                    ServerLogger.info(
                        LogCategory.ADHOCCTL,
                        "process_recv_buf_v1: client $socketName ${mac.toMacString()} on channel $channel joining group $gameCode $grpName"
                    )
                }
                AdhocctlPackets.OPCODE_DISCONNECT -> {
                    clientOps.add(
                        ClientOp(
                            mac = mac,
                            type = ClientOpType.DISCONNECT,
                            channel = channel
                        )
                    )
                    ServerLogger.info(
                        LogCategory.ADHOCCTL,
                        "process_recv_buf_v1: client $socketName ${mac.toMacString()} disconnecting from group $gameCode $group"
                    )
                }
                AdhocctlPackets.OPCODE_SCAN -> {
                    clientOps.add(
                        ClientOp(
                            mac = mac,
                            type = ClientOpType.SCAN
                        )
                    )
                    ServerLogger.info(
                        LogCategory.ADHOCCTL,
                        "process_recv_buf_v1: client $socketName ${mac.toMacString()} on channel $channel requesting scan"
                    )
                }
                AdhocctlPackets.OPCODE_CHAT -> {
                    val msg = extractNullTerminatedString(backing, head + 1, AdhocctlPackets.CHAT_MESSAGE_LEN)
                    clientOps.add(
                        ClientOp(
                            mac = mac,
                            type = ClientOpType.CHAT,
                            message = msg
                        )
                    )
                    ServerLogger.info(
                        LogCategory.ADHOCCTL,
                        "process_recv_buf_v1: client $socketName ${mac.toMacString()} sending message [$msg] to group $gameCode $group"
                    )
                }
            }

            recvBuf.consume(packetSize)
        }
    }

    fun pumpToClient(): ClientPumpStatus {
        while (true) {
            if (sendBuf.isEmpty()) {
                return ClientPumpStatus.SUCCESS
            }
            if (enetServer == null) {
                val written = tcpSocket?.send(sendBuf.backingArray(), sendBuf.headOffset(), sendBuf.size) ?: -2
                if (written == -1) {
                    return ClientPumpStatus.SUCCESS
                }
                if (written < -1) {
                    ServerLogger.warn(LogCategory.ADHOCCTL, "pump_to_client: failed sending to client $socketName")
                    return ClientPumpStatus.ERROR
                }
                sendBuf.consume(written)
            } else {
                val toSend = minOf(sendBuf.size, 500)
                val (sentLen, status) = enetServer.send(
                    peerRef = enetPeerRef,
                    buf = sendBuf.backingArray(),
                    offset = sendBuf.headOffset(),
                    len = toSend,
                    channel = 0,
                    reliable = true
                )
                if (sentLen == -1 || status != EnetSendStatus.SUCCESS) {
                    ServerLogger.warn(LogCategory.ADHOCCTL, "pump_to_client: failed sending to enet client $socketName ($status)")
                    return ClientPumpStatus.ERROR
                }
                sendBuf.consume(sentLen)
            }
        }
    }

    fun drainClientOps(): List<ClientOp> {
        if (clientOps.isEmpty()) return emptyList()
        val copy = ArrayList(clientOps)
        clientOps.clear()
        return copy
    }
}
