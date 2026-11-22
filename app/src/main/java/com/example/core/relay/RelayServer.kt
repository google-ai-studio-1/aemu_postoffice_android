package com.example.core.relay

import com.example.core.adhocctl.AdhocctlSnapshot
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

enum class RelayServerPumpStatus {
    SUCCESS,
    IDLE,
    LISTEN_SOCK_DEAD
}

class RelayServer(initialConfig: ServerConfig) {
    @Volatile
    private var config: ServerConfig = initialConfig
    @Volatile
    private var dead: Boolean = false
    @Volatile
    private var stopping: Boolean = false
    @Volatile
    private var adhocctlSnapshot: AdhocctlSnapshot = AdhocctlSnapshot()

    private var tcpListener: NativeTcpListener? = null
    private var enetServer: BasicEnetServer? = null
    private val addrFamily: AddrFamily = NativeSocketUtil.getAddrFamily(initialConfig.ipAddr)

    private val pendingSessions = ArrayList<PendingSession>()
    private val sessions = LinkedHashMap<String, RelaySession>()
    private val perMacSessionCount = HashMap<ByteKey, Int>()

    private val setCount: Int
    private val useWorkers: Boolean
    private val pendingStartSemas = ArrayList<Semaphore>()
    private val pendingDoneSemas = ArrayList<Semaphore>()
    private val pendingWorkers = ArrayList<Thread>()
    private val toPumpPendingSessions: List<ArrayList<PendingSession>>
    private val pendingSessionsToRemove: List<ArrayList<PendingSession>>
    private val readyPendingSessions: List<ArrayList<PendingSession>>

    private val fromStartSemas = ArrayList<Semaphore>()
    private val fromDoneSemas = ArrayList<Semaphore>()
    private val fromWorkers = ArrayList<Thread>()
    private val sessionsToPump: List<ArrayList<RelaySession>>
    private val sessionsToRemove: List<LinkedHashSet<String>>
    private val sendLists: List<HashMap<String, ArrayList<SendListItem>>>
    private val recvScratchBuffers: List<ByteArray>

    private val toStartSemas = ArrayList<Semaphore>()
    private val toDoneSemas = ArrayList<Semaphore>()
    private val toWorkers = ArrayList<Thread>()

    init {
        tcpListener = NativeTcpListener.listen(config.ipAddr, config.port)
        if (tcpListener == null) {
            ServerLogger.error(LogCategory.RELAY, "Server: failed creating listen socket on ${config.ipAddr}:${config.port}")
            dead = true
        }

        if (!dead) {
            if (addrFamily == AddrFamily.IPV4) {
                ServerLogger.info(LogCategory.RELAY, "Server: not initializing enet in ipv4 only mode")
            } else if (config.enableEnet) {
                val enet = BasicEnetServer()
                if (enet.listen(config.ipAddr, config.port, config.maxNumSessions, 1) != 0) {
                    ServerLogger.error(LogCategory.RELAY, "Server: failed initializing enet")
                    enet.reset()
                    dead = true
                } else {
                    enetServer = enet
                }
            }
        }

        useWorkers = config.numThreads > 0
        setCount = if (useWorkers) config.numThreads else 1

        val scratchSize = maxOf(RelayPackets.PDP_BLOCK_MAX, RelayPackets.PTP_BLOCK_MAX) * 2 +
            maxOf(RelayPackets.SIZE_PDP, RelayPackets.SIZE_PTP_DATA)

        toPumpPendingSessions = List(setCount) { ArrayList() }
        pendingSessionsToRemove = List(setCount) { ArrayList() }
        readyPendingSessions = List(setCount) { ArrayList() }

        sessionsToPump = List(setCount) { ArrayList() }
        sessionsToRemove = List(setCount) { LinkedHashSet() }
        sendLists = List(setCount) { HashMap() }
        recvScratchBuffers = List(setCount) { ByteArray(scratchSize) }

        if (!dead && useWorkers) {
            for (i in 0 until setCount) {
                pendingStartSemas.add(Semaphore(0))
                pendingDoneSemas.add(Semaphore(0))
                fromStartSemas.add(Semaphore(0))
                fromDoneSemas.add(Semaphore(0))
                toStartSemas.add(Semaphore(0))
                toDoneSemas.add(Semaphore(0))
            }
            startWorkerThreads()
        }

        if (!dead) {
            ServerLogger.info(LogCategory.RELAY, "Server: created server listening on ${config.ipAddr} ${config.port}")
        }
    }

    private fun startWorkerThreads() {
        for (i in 0 until setCount) {
            pendingWorkers.add(Thread({
                while (true) {
                    try {
                        pendingStartSemas[i].acquire()
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (stopping) {
                        pendingDoneSemas[i].release()
                        break
                    }
                    pumpPendingSessions(i)
                    pendingDoneSemas[i].release()
                }
            }, "pending_$i").apply { start() })

            fromWorkers.add(Thread({
                while (true) {
                    try {
                        fromStartSemas[i].acquire()
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (stopping) {
                        fromDoneSemas[i].release()
                        break
                    }
                    pumpConnectAndFromClients(i)
                    fromDoneSemas[i].release()
                }
            }, "from_client_$i").apply { start() })

            toWorkers.add(Thread({
                while (true) {
                    try {
                        toStartSemas[i].acquire()
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (stopping) {
                        toDoneSemas[i].release()
                        break
                    }
                    pumpToClients(i)
                    toDoneSemas[i].release()
                }
            }, "to_client_$i").apply { start() })
        }
    }

    fun shutdown() {
        tcpListener?.close()
        tcpListener = null

        stopping = true
        for (s in pendingStartSemas) s.release()
        for (s in fromStartSemas) s.release()
        for (s in toStartSemas) s.release()

        for (t in pendingWorkers) {
            try {
                t.join(2000)
            } catch (_: InterruptedException) {
            }
        }
        for (t in fromWorkers) {
            try {
                t.join(2000)
            } catch (_: InterruptedException) {
            }
        }
        for (t in toWorkers) {
            try {
                t.join(2000)
            } catch (_: InterruptedException) {
            }
        }

        for (session in sessions.values) {
            session.closeSocket()
        }
        sessions.clear()

        for (pending in pendingSessions) {
            pending.closeSocket()
        }
        pendingSessions.clear()
        perMacSessionCount.clear()

        enetServer?.reset()
        enetServer = null
    }

    fun isDead(): Boolean = dead

    private fun pumpPendingSessions(set: Int) {
        val currentAdhoc = adhocctlSnapshot
        for (pending in toPumpPendingSessions[set]) {
            when (pending.pump(sessions, currentAdhoc)) {
                PendingSessionPumpStatus.SUCCESS -> Unit
                PendingSessionPumpStatus.SOCKET_CLOSED,
                PendingSessionPumpStatus.TIMEOUT,
                PendingSessionPumpStatus.BAD_INIT -> pendingSessionsToRemove[set].add(pending)
                PendingSessionPumpStatus.SESSION_READY -> readyPendingSessions[set].add(pending)
            }
        }
    }

    private fun pumpConnectAndFromClients(set: Int) {
        val scratch = recvScratchBuffers[set]
        for (session in sessionsToPump[set]) {
            val connectStatus = session.pumpConnect()
            if (connectStatus == SessionPumpStatus.TIMEOUT) {
                sessionsToRemove[set].add(session.identifier)
                continue
            }

            when (session.pumpFromClient(scratch)) {
                SessionPumpStatus.SOCKET_CLOSED,
                SessionPumpStatus.BAD_DATA_SIZE -> sessionsToRemove[set].add(session.identifier)
                else -> Unit
            }

            val items = session.drainSendList()
            for (item in items) {
                val bucket = sendLists[set].getOrPut(item.sessionName) { ArrayList() }
                bucket.add(item)
            }
        }
    }

    private fun pumpToClients(set: Int) {
        val currentConfig = config
        val currentAdhoc = adhocctlSnapshot
        for (session in sessionsToPump[set]) {
            val id = session.identifier
            val toClient = currentAdhoc.clients[session.fromMac]

            if (currentConfig.strictMode) {
                if (toClient == null) {
                    sessionsToRemove[set].add(id)
                    continue
                }
                val toGame = currentAdhoc.games[toClient.gameCode]
                val toGroup = toGame?.groups?.get(toClient.groupKey)
                if (toGroup == null || toGroup.channel < 0) {
                    continue
                }
            }

            var queueStatus = DataQueueStatus.SUCCESS
            for (sendListSet in sendLists) {
                val myList = sendListSet[id] ?: continue
                for (send in myList) {
                    if (currentConfig.strictMode) {
                        val fromClient = currentAdhoc.clients[send.fromMac] ?: continue
                        if (toClient == null ||
                            fromClient.gameCode != toClient.gameCode ||
                            fromClient.groupKey != toClient.groupKey
                        ) {
                            continue
                        }
                    }
                    queueStatus = session.queueSend(send.data)
                    if (queueStatus == DataQueueStatus.MAX_DATA_REACHED) {
                        sessionsToRemove[set].add(id)
                        break
                    }
                }
                if (queueStatus == DataQueueStatus.MAX_DATA_REACHED) {
                    break
                }
            }
            if (queueStatus == DataQueueStatus.MAX_DATA_REACHED) {
                continue
            }

            if (session.pumpToClient() == SessionPumpStatus.SOCKET_CLOSED) {
                sessionsToRemove[set].add(id)
            }
        }
    }

    fun pump(): RelayServerPumpStatus {
        if (dead) return RelayServerPumpStatus.LISTEN_SOCK_DEAD
        val listener = tcpListener ?: return RelayServerPumpStatus.LISTEN_SOCK_DEAD

        while (true) {
            val accepted = try {
                listener.accept()
            } catch (e: IOException) {
                ServerLogger.error(LogCategory.RELAY, "pump: failed accepting connection (${e.message})")
                dead = true
                return RelayServerPumpStatus.LISTEN_SOCK_DEAD
            } ?: break

            if (pendingSessions.size + sessions.size >= config.maxNumSessions) {
                ServerLogger.warn(
                    LogCategory.RELAY,
                    "pump: new relay connection from ${accepted.peerAddr} dropped as maximum number of sessions (${config.maxNumSessions}) has been reached"
                )
                accepted.socket.close()
                continue
            }

            pendingSessions.add(
                PendingSession(
                    tcpSocket = accepted.socket,
                    enetPeerRef = -1,
                    clientAddr = accepted.peerAddr,
                    clientPort = accepted.peerPort,
                    config = config,
                    enetServer = null
                )
            )
        }

        val currentEnet = enetServer
        while (currentEnet != null) {
            val accepted = currentEnet.accept()
            if (accepted.status == EnetAcceptStatus.NO_PEER) break
            if (accepted.status != EnetAcceptStatus.SUCCESS) {
                ServerLogger.error(LogCategory.RELAY, "pump: enet accept error ${accepted.status}")
                dead = true
                return RelayServerPumpStatus.LISTEN_SOCK_DEAD
            }
            if (pendingSessions.size + sessions.size >= config.maxNumSessions) {
                ServerLogger.warn(
                    LogCategory.RELAY,
                    "pump: new relay connection from ${accepted.peerAddr} dropped as maximum number of sessions (${config.maxNumSessions}) has been reached"
                )
                currentEnet.close(accepted.peerRef)
                continue
            }
            pendingSessions.add(
                PendingSession(
                    tcpSocket = null,
                    enetPeerRef = accepted.peerRef,
                    clientAddr = accepted.peerAddr,
                    clientPort = accepted.peerPort,
                    config = config,
                    enetServer = currentEnet
                )
            )
        }

        if (pendingSessions.isEmpty() && sessions.isEmpty()) {
            return RelayServerPumpStatus.IDLE
        }

        for (i in 0 until setCount) {
            toPumpPendingSessions[i].clear()
            pendingSessionsToRemove[i].clear()
            readyPendingSessions[i].clear()
            sessionsToRemove[i].clear()
        }

        for (idx in pendingSessions.indices) {
            toPumpPendingSessions[idx % setCount].add(pendingSessions[idx])
        }

        if (!useWorkers) {
            pumpPendingSessions(0)
        } else {
            for (s in pendingStartSemas) s.release()
            for (s in pendingDoneSemas) s.acquire()
        }

        for (readySet in readyPendingSessions) {
            for (readyPending in readySet) {
                val newSession = readyPending.createSession(sessions)
                if (newSession == null) {
                    readyPending.closeSocket()
                    pendingSessionsToRemove[0].add(readyPending)
                    continue
                }
                val id = newSession.identifier
                val oldSession = sessions[id]
                var replaced = false
                if (oldSession != null) {
                    ServerLogger.info(
                        LogCategory.RELAY,
                        "pump: replacing session $id from ${oldSession.clientAddr} by session from ${newSession.clientAddr}"
                    )
                    oldSession.closeSocket()
                    replaced = true
                }

                val mac = newSession.fromMac
                val existingCount = perMacSessionCount[mac]
                if (existingCount == null) {
                    perMacSessionCount[mac] = 1
                } else if (!replaced) {
                    if (existingCount >= config.maxNumSessionsPerMac) {
                        pendingSessionsToRemove[0].add(readyPending)
                        newSession.closeSocket()
                        if (newSession.peerIdentifier.isNotEmpty()) {
                            sessionsToRemove[0].add(newSession.peerIdentifier)
                        }
                        continue
                    }
                    perMacSessionCount[mac] = existingCount + 1
                }

                sessions[id] = newSession
                pendingSessionsToRemove[0].add(readyPending)
            }
        }

        for (removeSet in pendingSessionsToRemove) {
            for (toRemove in removeSet) {
                pendingSessions.remove(toRemove)
            }
        }

        for (i in 0 until setCount) {
            sessionsToPump[i].clear()
        }

        var sessionIdx = 0
        for (session in sessions.values) {
            sessionsToPump[sessionIdx % setCount].add(session)
            sessionIdx++
        }

        if (!useWorkers) {
            pumpConnectAndFromClients(0)
        } else {
            for (s in fromStartSemas) s.release()
            for (s in fromDoneSemas) s.acquire()
        }

        if (!useWorkers) {
            pumpToClients(0)
        } else {
            for (s in toStartSemas) s.release()
            for (s in toDoneSemas) s.acquire()
        }

        for (i in 0 until setCount) {
            sendLists[i].clear()
        }

        for (removeSet in sessionsToRemove) {
            for (sessionName in removeSet) {
                val session = sessions.remove(sessionName) ?: continue
                session.closeSocket()
                reduceMacCount(session.fromMac)
                ServerLogger.info(LogCategory.RELAY, "pump: removing $sessionName of ${session.clientAddr}")

                val peerId = session.peerIdentifier
                if (peerId.isNotEmpty()) {
                    val peerSession = sessions.remove(peerId)
                    if (peerSession != null) {
                        peerSession.closeSocket()
                        reduceMacCount(peerSession.fromMac)
                        ServerLogger.info(LogCategory.RELAY, "pump: removing $peerId of ${peerSession.clientAddr} by peer relation")
                    }
                }
            }
        }

        return RelayServerPumpStatus.SUCCESS
    }

    private fun reduceMacCount(mac: ByteKey) {
        val current = perMacSessionCount[mac] ?: return
        if (current <= 1) {
            perMacSessionCount.remove(mac)
        } else {
            perMacSessionCount[mac] = current - 1
        }
    }

    fun updateAdhocctlData(snapshot: AdhocctlSnapshot) {
        adhocctlSnapshot = snapshot
    }

    fun setConfig(newConfig: ServerConfig) {
        val old = config
        val updated = newConfig.copy(
            ipAddr = old.ipAddr,
            port = old.port,
            numThreads = old.numThreads,
            enableEnet = old.enableEnet
        )
        config = updated
        for (p in pendingSessions) p.config = updated
        for (s in sessions.values) s.config = updated
    }

    fun getSnapshot(): RelaySnapshot {
        val clientBuilders = LinkedHashMap<ByteKey, MutableRelayClientBuilder>()
        val infos = ArrayList<RelaySessionInfo>(sessions.size)

        for (session in sessions.values) {
            infos.add(session.toInfo())
            val builder = clientBuilders.getOrPut(session.fromMac) {
                MutableRelayClientBuilder(session.fromMac, session.clientAddr, session.clientPort)
            }
            when (session.mode) {
                SessionMode.PDP -> builder.pdpPorts.add(session.fromPort)
                SessionMode.PTP_LISTEN -> builder.ptpListenPorts.add(session.fromPort)
                SessionMode.PTP_CONNECT -> builder.ptpConnectPorts.add(session.fromPort)
                SessionMode.PTP_ACCEPT -> builder.ptpAcceptPorts.add(session.fromPort)
            }
        }

        val clientsMap = LinkedHashMap<ByteKey, RelaySnapshotClient>(clientBuilders.size)
        for ((mac, builder) in clientBuilders) {
            clientsMap[mac] = RelaySnapshotClient(
                mac = builder.mac,
                ip = builder.ip,
                port = builder.port,
                pdpPorts = builder.pdpPorts,
                ptpListenPorts = builder.ptpListenPorts,
                ptpConnectPorts = builder.ptpConnectPorts,
                ptpAcceptPorts = builder.ptpAcceptPorts
            )
        }

        return RelaySnapshot(
            clients = clientsMap,
            sessions = infos,
            pendingCount = pendingSessions.size
        )
    }

    private class MutableRelayClientBuilder(
        val mac: ByteKey,
        val ip: String,
        val port: Int
    ) {
        val pdpPorts = ArrayList<Int>()
        val ptpListenPorts = ArrayList<Int>()
        val ptpConnectPorts = ArrayList<Int>()
        val ptpAcceptPorts = ArrayList<Int>()
    }
}
