package com.example.core.adhocctl

import com.example.core.common.AddrFamily
import com.example.core.common.ByteKey
import com.example.core.common.LogCategory
import com.example.core.common.NativeSocketUtil
import com.example.core.common.NativeTcpListener
import com.example.core.common.ServerConfig
import com.example.core.common.ServerLogger
import com.example.core.enet.BasicEnetServer
import com.example.core.enet.EnetAcceptStatus
import java.io.IOException
import java.util.concurrent.Semaphore

enum class AdhocctlServerPumpStatus {
    SUCCESS,
    ERROR,
    IDLE
}

private class AdhocctlGroup(
    val channel: Int,
    val name: String,
    val leaderMac: ByteKey
) {
    val members = LinkedHashMap<ByteKey, AdhocctlClient>()
}

private class AdhocctlGame {
    val groups = LinkedHashMap<String, AdhocctlGroup>()
}

class AdhocctlServer(
    initialConfig: ServerConfig,
    initialGameDb: GameDb
) {
    @Volatile
    private var config: ServerConfig = initialConfig
    @Volatile
    private var gameDb: GameDb = initialGameDb
    @Volatile
    private var dead: Boolean = false
    @Volatile
    private var stopping: Boolean = false

    private var tcpListener: NativeTcpListener? = null
    private var enetServer: BasicEnetServer? = null
    private val addrFamily: AddrFamily = NativeSocketUtil.getAddrFamily(initialConfig.ipAddr)

    private val pendingClients = LinkedHashMap<String, AdhocctlClient>()
    private val clients = LinkedHashMap<ByteKey, AdhocctlClient>()
    private val perIpClientCount = HashMap<String, Int>()
    private val games = LinkedHashMap<String, AdhocctlGame>()
    private var nextRelayClientId: Int = 0

    private val workerCount: Int
    private val pendingStartSemas: List<Semaphore>
    private val pendingFinishSemas: List<Semaphore>
    private val pendingWorkQueues: List<ArrayList<AdhocctlClient>>
    private val pendingToRemove: List<ArrayList<String>>
    private val pendingToUpgrade: List<ArrayList<String>>
    private val pendingWorkers = ArrayList<Thread>()

    private val recvStartSemas: List<Semaphore>
    private val recvFinishSemas: List<Semaphore>
    private val recvWorkQueues: List<ArrayList<AdhocctlGroup>>
    private val clientsToRemove: List<ArrayList<ByteKey>>
    private val connectDisconnectOps: List<ArrayList<ClientOp>>
    private val recvWorkers = ArrayList<Thread>()

    private val sendStartSemas: List<Semaphore>
    private val sendFinishSemas: List<Semaphore>
    private val sendWorkQueues: List<ArrayList<AdhocctlClient>>
    private val sendWorkers = ArrayList<Thread>()

    init {
        when (addrFamily) {
            AddrFamily.IPV6 -> {
                ServerLogger.info(
                    LogCategory.ADHOCCTL,
                    "Server: starting adhocctl server in ipv4 ipv6 mixed mode, only relay mode is supported"
                )
                config = config.copy(adhocctlRelayOnly = true)
            }
            AddrFamily.IPV4 -> {
                ServerLogger.info(LogCategory.ADHOCCTL, "Server: starting adhocctl server in ipv4 mode")
            }
            AddrFamily.UNKNOWN -> {
                ServerLogger.error(LogCategory.ADHOCCTL, "Server: invalid address ${config.ipAddr}, not starting adhocctl server")
                dead = true
            }
        }

        if (!dead) {
            tcpListener = NativeTcpListener.listen(config.ipAddr, config.adhocctlPort)
            if (tcpListener == null) {
                ServerLogger.error(LogCategory.ADHOCCTL, "Server: failed creating adhocctl listen socket on ${config.ipAddr}:${config.adhocctlPort}")
                dead = true
            }
        }

        if (!dead) {
            if (addrFamily != AddrFamily.IPV6) {
                ServerLogger.info(LogCategory.ADHOCCTL, "Server: not enabling enet in ipv4 only mode")
            } else if (config.enableEnet) {
                val enet = BasicEnetServer()
                if (enet.listen(config.ipAddr, config.adhocctlPort, config.adhocctlMaxNumSessions, 1) != 0) {
                    ServerLogger.error(LogCategory.ADHOCCTL, "Server: failed creating adhocctl enet socket")
                    enet.reset()
                    dead = true
                } else {
                    enetServer = enet
                }
            }
        }

        workerCount = config.adhocctlNumThreads.coerceAtLeast(1)
        pendingStartSemas = List(workerCount) { Semaphore(0) }
        pendingFinishSemas = List(workerCount) { Semaphore(0) }
        pendingWorkQueues = List(workerCount) { ArrayList() }
        pendingToRemove = List(workerCount) { ArrayList() }
        pendingToUpgrade = List(workerCount) { ArrayList() }

        recvStartSemas = List(workerCount) { Semaphore(0) }
        recvFinishSemas = List(workerCount) { Semaphore(0) }
        recvWorkQueues = List(workerCount) { ArrayList() }
        clientsToRemove = List(workerCount) { ArrayList() }
        connectDisconnectOps = List(workerCount) { ArrayList() }

        sendStartSemas = List(workerCount) { Semaphore(0) }
        sendFinishSemas = List(workerCount) { Semaphore(0) }
        sendWorkQueues = List(workerCount) { ArrayList() }

        if (!dead) {
            startWorkerThreads()
            ServerLogger.info(
                LogCategory.ADHOCCTL,
                "Server: adhocctl server listening on ${config.ipAddr}:${config.adhocctlPort}"
            )
        }
    }

    private fun startWorkerThreads() {
        for (i in 0 until workerCount) {
            pendingWorkers.add(Thread({
                while (true) {
                    try {
                        pendingStartSemas[i].acquire()
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (stopping) break
                    for (c in pendingWorkQueues[i]) {
                        val status = c.pumpFromClient()
                        if (status != ClientPumpStatus.SUCCESS) {
                            pendingToRemove[i].add(c.socketName)
                            continue
                        }
                        if (c.loggedIn) {
                            pendingToUpgrade[i].add(c.socketName)
                        }
                    }
                    pendingFinishSemas[i].release()
                }
            }, "adhocctl_$i").apply { start() })

            recvWorkers.add(Thread({
                while (true) {
                    try {
                        recvStartSemas[i].acquire()
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (stopping) break
                    for (group in recvWorkQueues[i]) {
                        val firstMember = group.members.values.firstOrNull() ?: continue
                        val game = games[firstMember.gameCode] ?: continue
                        for ((macKey, client) in group.members) {
                            val status = client.pumpFromClient()
                            if (status != ClientPumpStatus.SUCCESS) {
                                clientsToRemove[i].add(macKey)
                                continue
                            }
                            val ops = client.drainClientOps()
                            for (op in ops) {
                                when (op.type) {
                                    ClientOpType.CONNECT, ClientOpType.DISCONNECT -> {
                                        connectDisconnectOps[i].add(op)
                                    }
                                    ClientOpType.SCAN -> {
                                        for (g in game.groups.values) {
                                            if (g.channel < 0) continue
                                            if (client.scanResult(g.name, g.leaderMac) != ServerToClientOpQueueStatus.SUCCESS) {
                                                clientsToRemove[i].add(macKey)
                                            }
                                        }
                                        if (client.scanComplete() != ServerToClientOpQueueStatus.SUCCESS) {
                                            clientsToRemove[i].add(macKey)
                                        }
                                    }
                                    ClientOpType.CHAT -> {
                                        if (group.channel < 0) continue
                                        for ((peerMac, peerClient) in group.members) {
                                            if (peerMac == macKey) continue
                                            if (peerClient.chat(client.nickname, op.message) != ServerToClientOpQueueStatus.SUCCESS) {
                                                clientsToRemove[i].add(macKey)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    recvFinishSemas[i].release()
                }
            }, "adhocctl_recv_$i").apply { start() })

            sendWorkers.add(Thread({
                while (true) {
                    try {
                        sendStartSemas[i].acquire()
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (stopping) break
                    for (client in sendWorkQueues[i]) {
                        val status = client.pumpToClient()
                        if (status != ClientPumpStatus.SUCCESS) {
                            clientsToRemove[i].add(client.mac)
                        }
                    }
                    sendFinishSemas[i].release()
                }
            }, "adhocctl_send_$i").apply { start() })
        }
    }

    fun shutdown() {
        tcpListener?.close()
        tcpListener = null

        stopping = true
        for (sema in pendingStartSemas) sema.release()
        for (sema in recvStartSemas) sema.release()
        for (sema in sendStartSemas) sema.release()

        for (t in pendingWorkers) {
            try {
                t.join(2000)
            } catch (_: InterruptedException) {
            }
        }
        for (t in recvWorkers) {
            try {
                t.join(2000)
            } catch (_: InterruptedException) {
            }
        }
        for (t in sendWorkers) {
            try {
                t.join(2000)
            } catch (_: InterruptedException) {
            }
        }

        for (client in pendingClients.values) {
            client.closeSocket()
        }
        pendingClients.clear()

        for (client in clients.values) {
            client.closeSocket()
        }
        clients.clear()
        games.clear()
        perIpClientCount.clear()

        enetServer?.reset()
        enetServer = null
    }

    fun isDead(): Boolean = dead

    private fun makeSocketName(ip: String, port: Int): String = "$ip:$port"

    private fun disconnectClient(mac: ByteKey) {
        val target = clients[mac] ?: return
        val groupKey = target.group
        target.group = ""
        val game = games[target.gameCode] ?: return
        val group = game.groups[groupKey] ?: return
        if (group.members.remove(mac) == null) return

        if (group.channel >= 1) {
            for (member in group.members.values) {
                if (member.disconnectNotify(target) != ServerToClientOpQueueStatus.SUCCESS) {
                    clientsToRemove[0].add(member.mac)
                }
            }
        }
        if (group.members.isEmpty()) {
            game.groups.remove(groupKey)
        }
        if (game.groups.isEmpty()) {
            games.remove(target.gameCode)
        }
    }

    private fun removeClient(mac: ByteKey) {
        val target = clients[mac] ?: return
        disconnectClient(mac)
        target.closeSocket()
        decrementPerIpClientCount(target.ip)
        clients.remove(mac)
    }

    private fun connectClient(mac: ByteKey, rawGroupName: String, grouplessGroup: Boolean) {
        disconnectClient(mac)
        val target = clients[mac] ?: return
        var resolvedGameCode = target.gameCode

        val crosslinkTarget = gameDb.crosslinks[resolvedGameCode]
        if (crosslinkTarget != null) {
            ServerLogger.info(
                LogCategory.ADHOCCTL,
                "connect_client: client ${target.socketName} ${target.mac.toMacString()} crosslinked from $resolvedGameCode to $crosslinkTarget"
            )
            resolvedGameCode = crosslinkTarget
            target.gameCode = crosslinkTarget
        }

        var groupName = rawGroupName
        if (gameDb.codQuirkGames.contains(resolvedGameCode) && !grouplessGroup) {
            groupName = "cod_quirk_fix_applied"
        }

        val game = games.getOrPut(resolvedGameCode) { AdhocctlGame() }
        var channel = target.channel
        if (grouplessGroup) {
            channel = -channel
        }
        val groupKey = AdhocctlSnapshot.makeGroupKey(channel, groupName)
        val group = game.groups.getOrPut(groupKey) {
            AdhocctlGroup(channel = channel, name = groupName, leaderMac = mac)
        }

        if (!grouplessGroup) {
            for (member in group.members.values) {
                if (member.connectNotify(target) != ServerToClientOpQueueStatus.SUCCESS) {
                    clientsToRemove[0].add(member.mac)
                }
                if (target.connectNotify(member) != ServerToClientOpQueueStatus.SUCCESS) {
                    clientsToRemove[0].add(target.mac)
                }
            }
            if (target.connectAck(group.leaderMac) != ServerToClientOpQueueStatus.SUCCESS) {
                clientsToRemove[0].add(target.mac)
            }
        }

        group.members[mac] = target
        target.group = groupKey
    }

    fun pump(): AdhocctlServerPumpStatus {
        if (dead) return AdhocctlServerPumpStatus.ERROR
        val listener = tcpListener ?: return AdhocctlServerPumpStatus.ERROR

        while (true) {
            val accepted = try {
                listener.accept()
            } catch (e: IOException) {
                ServerLogger.error(LogCategory.ADHOCCTL, "pump: accept call failed (${e.message})")
                dead = true
                return AdhocctlServerPumpStatus.ERROR
            } ?: break

            if (pendingClients.size + clients.size >= config.adhocctlMaxNumSessions) {
                ServerLogger.warn(
                    LogCategory.ADHOCCTL,
                    "pump: new connection from ${accepted.peerAddr} dropped as maximum number of adhocctl sessions (${config.adhocctlMaxNumSessions}) has been reached"
                )
                accepted.socket.close()
                continue
            }

            val socketName = makeSocketName(accepted.peerAddr, accepted.peerPort)
            val newClient = AdhocctlClient(
                tcpSocket = accepted.socket,
                enetPeerRef = -1,
                enetServer = null,
                ip = accepted.peerAddr,
                port = accepted.peerPort,
                socketName = socketName,
                protocolRevision = 1,
                config = config
            )
            pendingClients.remove(socketName)?.closeSocket()
            pendingClients[socketName] = newClient
        }

        val currentEnet = enetServer
        while (currentEnet != null) {
            val accepted = currentEnet.accept()
            if (accepted.peerRef == -1) {
                if (accepted.status == EnetAcceptStatus.NO_PEER) break
                ServerLogger.error(LogCategory.ADHOCCTL, "pump: enet accept call failed (${accepted.status})")
                dead = true
                return AdhocctlServerPumpStatus.ERROR
            }

            if (pendingClients.size + clients.size >= config.adhocctlMaxNumSessions) {
                ServerLogger.warn(
                    LogCategory.ADHOCCTL,
                    "pump: new connection from ${accepted.peerAddr} dropped as maximum number of adhocctl sessions (${config.adhocctlMaxNumSessions}) has been reached"
                )
                currentEnet.close(accepted.peerRef)
                continue
            }

            val socketName = makeSocketName(accepted.peerAddr, accepted.peerPort)
            val newClient = AdhocctlClient(
                tcpSocket = null,
                enetPeerRef = accepted.peerRef,
                enetServer = currentEnet,
                ip = accepted.peerAddr,
                port = accepted.peerPort,
                socketName = socketName,
                protocolRevision = 1,
                config = config
            )
            pendingClients.remove(socketName)?.closeSocket()
            pendingClients[socketName] = newClient
        }

        if (pendingClients.isEmpty() && clients.isEmpty()) {
            return AdhocctlServerPumpStatus.IDLE
        }

        var workerIdx = 0
        for (client in pendingClients.values) {
            pendingWorkQueues[workerIdx].add(client)
            workerIdx = (workerIdx + 1) % workerCount
        }
        for (sema in pendingStartSemas) sema.release()
        for (sema in pendingFinishSemas) sema.acquire()

        for (list in pendingToRemove) {
            for (key in list) {
                pendingClients.remove(key)?.closeSocket()
            }
        }

        for (list in pendingToUpgrade) {
            for (key in list) {
                val target = pendingClients[key] ?: continue
                val macKey = target.mac
                val existing = clients[macKey]
                if (existing != null) {
                    ServerLogger.info(
                        LogCategory.ADHOCCTL,
                        "pump: session with mac address ${macKey.toMacString()} owned by ${existing.socketName} now owned by ${target.socketName}"
                    )
                    removeClient(macKey)
                }

                if (config.adhocctlRelayOnly) {
                    while (true) {
                        var isUnique = true
                        for (c in clients.values) {
                            if (nextRelayClientId == c.id) {
                                isUnique = false
                                nextRelayClientId++
                                break
                            }
                        }
                        if (isUnique) break
                    }
                    target.id = nextRelayClientId
                } else {
                    val parsedIpv4 = NativeSocketUtil.parseIpv4ToNetworkUint32(target.ip)
                    if (parsedIpv4 == 0xFFFFFFFFL) {
                        ServerLogger.error(LogCategory.ADHOCCTL, "pump: cannot parse ipv4 ${target.ip}")
                        target.closeSocket()
                        pendingClients.remove(key)
                        continue
                    }
                    target.id = parsedIpv4.toInt()
                }

                if (getPerIpClientCount(target.ip) >= config.adhocctlMaxNumSessionsPerIp) {
                    target.closeSocket()
                    pendingClients.remove(key)
                    continue
                }

                incrementPerIpClientCount(target.ip)
                clients[macKey] = target
                connectClient(macKey, "", true)
                pendingClients.remove(key)
            }
        }

        val numClientsPerWorker = IntArray(workerCount)
        for (game in games.values) {
            for (group in game.groups.values) {
                var leastUsed = 0
                for (i in 1 until workerCount) {
                    if (numClientsPerWorker[leastUsed] > numClientsPerWorker[i]) {
                        leastUsed = i
                    }
                }
                recvWorkQueues[leastUsed].add(group)
                numClientsPerWorker[leastUsed] += group.members.size
            }
        }

        for (sema in recvStartSemas) sema.release()
        for (sema in recvFinishSemas) sema.acquire()

        for (ops in connectDisconnectOps) {
            for (op in ops) {
                when (op.type) {
                    ClientOpType.CONNECT -> connectClient(op.mac, op.group, false)
                    ClientOpType.DISCONNECT -> connectClient(op.mac, "", true)
                    else -> Unit
                }
            }
        }

        workerIdx = 0
        for (client in clients.values) {
            sendWorkQueues[workerIdx].add(client)
            workerIdx = (workerIdx + 1) % workerCount
        }
        for (sema in sendStartSemas) sema.release()
        for (sema in sendFinishSemas) sema.acquire()

        for (removeList in clientsToRemove) {
            for (macKey in removeList) {
                removeClient(macKey)
            }
        }

        for (i in 0 until workerCount) {
            pendingWorkQueues[i].clear()
            pendingToRemove[i].clear()
            pendingToUpgrade[i].clear()
            recvWorkQueues[i].clear()
            clientsToRemove[i].clear()
            connectDisconnectOps[i].clear()
            sendWorkQueues[i].clear()
        }

        return AdhocctlServerPumpStatus.SUCCESS
    }

    fun getSnapshot(): AdhocctlSnapshot {
        val snapClients = LinkedHashMap<ByteKey, AdhocctlSnapshotClient>()
        val snapGames = LinkedHashMap<String, AdhocctlSnapshotGame>()
        for ((gameCode, game) in games) {
            val snapGroups = LinkedHashMap<String, AdhocctlSnapshotGroup>()
            for ((groupKey, group) in game.groups) {
                val memberMacs = ArrayList<ByteKey>(group.members.size)
                for ((macKey, member) in group.members) {
                    memberMacs.add(macKey)
                    snapClients[macKey] = AdhocctlSnapshotClient(
                        mac = macKey,
                        ip = member.ip,
                        port = member.port,
                        nickname = member.nickname,
                        gameCode = gameCode,
                        groupKey = groupKey
                    )
                }
                snapGroups[groupKey] = AdhocctlSnapshotGroup(
                    channel = group.channel,
                    name = group.name,
                    members = memberMacs
                )
            }
            snapGames[gameCode] = AdhocctlSnapshotGame(
                gameCode = gameCode,
                groups = snapGroups
            )
        }
        return AdhocctlSnapshot(clients = snapClients, games = snapGames)
    }

    fun getPendingCount(): Int = pendingClients.size

    fun setConfig(newConfig: ServerConfig) {
        val old = config
        val updated = newConfig.copy(
            ipAddr = old.ipAddr,
            adhocctlRelayOnly = old.adhocctlRelayOnly,
            adhocctlNumThreads = old.adhocctlNumThreads,
            adhocctlPort = old.adhocctlPort,
            enableEnet = old.enableEnet
        )
        config = updated
        for (c in pendingClients.values) c.config = updated
        for (c in clients.values) c.config = updated
    }

    fun setGameDb(newGameDb: GameDb) {
        gameDb = newGameDb
    }

    private fun getPerIpClientCount(ip: String): Int = perIpClientCount[ip] ?: 0

    private fun incrementPerIpClientCount(ip: String) {
        perIpClientCount[ip] = (perIpClientCount[ip] ?: 0) + 1
    }

    private fun decrementPerIpClientCount(ip: String) {
        val current = perIpClientCount[ip] ?: return
        if (current <= 1) {
            perIpClientCount.remove(ip)
        } else {
            perIpClientCount[ip] = current - 1
        }
    }
}
