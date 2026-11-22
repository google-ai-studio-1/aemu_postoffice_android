package com.example.core.enet

import com.example.core.common.LogCategory
import com.example.core.common.ServerLogger
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

enum class EnetAcceptStatus {
    SUCCESS,
    NO_PEER,
    ERRORED
}

enum class EnetRecvStatus {
    SUCCESS,
    WOULD_BLOCK,
    BUFFER_TOO_SMALL,
    PEER_CLOSED,
    PEER_NOT_FOUND
}

enum class EnetSendStatus {
    SUCCESS,
    PEER_CLOSED,
    PEER_NOT_FOUND
}

enum class WorkerTickStatus {
    IDLE,
    SUCCESS,
    ERRORED
}

enum class BasicEnetClientState {
    INACTIVE,
    LISTEN
}

data class EnetAcceptedPeer(
    val peerRef: Int,
    val peerAddr: String,
    val peerPort: Int,
    val status: EnetAcceptStatus
)

data class EnetRecvResult(
    val length: Int,
    val status: EnetRecvStatus
)

private data class EnetSendOp(
    val data: ByteArray,
    val reliable: Boolean,
    val channel: Int
)

private class ManagedEnetPeer(
    val enetPeer: EnetPeer
) {
    var disconnected: Boolean = false
    var closed: Boolean = false
    val sendBufLock = ReentrantLock()
    val sendBuf = ArrayDeque<EnetSendOp>()
    val recvBufLock = ReentrantLock()
    val recvBuf = HashMap<Int, ArrayDeque<ByteArray>>()
}

class BasicEnetServer {
    @Volatile
    private var stopping = false
    @Volatile
    private var serverDead = true
    private var state = BasicEnetClientState.INACTIVE

    private var inWorker: Thread? = null
    private var outWorker: Thread? = null

    private val serverLock = ReentrantLock()
    private var host: EnetHost? = null

    private val peerRwLock = ReentrantReadWriteLock()
    private val newPeers = HashMap<EnetPeer, ManagedEnetPeer>()
    private val newPeersOrdered = ArrayDeque<EnetPeer>()
    private val peers = HashMap<Int, ManagedEnetPeer>()
    private val peersLookup = HashMap<EnetPeer, Int>()
    private var nextRef = 1

    fun listen(bindHost: String, port: Int, maxPeers: Int, channels: Int): Int {
        serverLock.withLock {
            if (state != BasicEnetClientState.INACTIVE) {
                ServerLogger.error(LogCategory.ENET, "listen: enet client is not inactive")
                return -1
            }
            val created = EnetHost.createServer(bindHost, port, maxPeers, channels)
            if (created == null) {
                resetInternal()
                return -1
            }
            host = created
            serverDead = false
            stopping = false
            state = BasicEnetClientState.LISTEN
            startWorkers()
            return 0
        }
    }

    private fun startWorkers() {
        inWorker = Thread({
            val targetMs = 1L
            while (!stopping) {
                val begin = System.nanoTime()
                val tickStatus = workerInTick()
                val elapsedMs = (System.nanoTime() - begin) / 1_000_000L
                if (elapsedMs < targetMs && tickStatus == WorkerTickStatus.IDLE) {
                    try {
                        Thread.sleep(targetMs - elapsedMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                if (serverDead) break
            }
        }, "enet in").apply { start() }

        outWorker = Thread({
            val activeMs = 1L
            val idleMs = 8L
            while (!stopping) {
                val begin = System.nanoTime()
                val tickStatus = workerOutTick()
                val elapsedMs = (System.nanoTime() - begin) / 1_000_000L
                val target = if (tickStatus == WorkerTickStatus.IDLE) idleMs else activeMs
                if (elapsedMs < target) {
                    try {
                        Thread.sleep(target - elapsedMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                if (serverDead) break
            }
        }, "enet out").apply { start() }
    }

    private fun workerInTick(): WorkerTickStatus {
        serverLock.withLock {
            if (serverDead) return WorkerTickStatus.ERRORED
            val currentHost = host ?: return WorkerTickStatus.ERRORED
            val event = currentHost.service()
            if (event.type == EnetEventType.NONE) {
                return WorkerTickStatus.IDLE
            }
            val eventPeer = event.peer ?: return WorkerTickStatus.SUCCESS
            when (event.type) {
                EnetEventType.CONNECT -> {
                    peerRwLock.writeLock().withLock {
                        if (peersLookup.containsKey(eventPeer) || newPeers.containsKey(eventPeer)) {
                            return WorkerTickStatus.SUCCESS
                        }
                        newPeers[eventPeer] = ManagedEnetPeer(eventPeer)
                        newPeersOrdered.addLast(eventPeer)
                    }
                    return WorkerTickStatus.SUCCESS
                }
                EnetEventType.DISCONNECT,
                EnetEventType.DISCONNECT_TIMEOUT -> {
                    peerRwLock.writeLock().withLock {
                        if (newPeers.remove(eventPeer) != null) {
                            newPeersOrdered.remove(eventPeer)
                            return WorkerTickStatus.SUCCESS
                        }
                        val ref = peersLookup.remove(eventPeer)
                        if (ref != null) {
                            val managed = peers[ref]
                            if (managed != null) {
                                if (!managed.closed) {
                                    managed.disconnected = true
                                } else {
                                    peers.remove(ref)
                                }
                            }
                        }
                    }
                    return WorkerTickStatus.SUCCESS
                }
                EnetEventType.RECEIVE -> {
                    val payload = event.data ?: return WorkerTickStatus.SUCCESS
                    peerRwLock.readLock().withLock {
                        val managed = newPeers[eventPeer] ?: peersLookup[eventPeer]?.let { peers[it] } ?: return WorkerTickStatus.SUCCESS
                        managed.recvBufLock.withLock {
                            val queue = managed.recvBuf.getOrPut(event.channelId) { ArrayDeque() }
                            queue.addLast(payload)
                        }
                    }
                    return WorkerTickStatus.SUCCESS
                }
                EnetEventType.NONE -> return WorkerTickStatus.IDLE
            }
        }
    }

    private fun workerOutTick(): WorkerTickStatus {
        serverLock.withLock {
            if (serverDead) return WorkerTickStatus.ERRORED
            val currentHost = host ?: return WorkerTickStatus.ERRORED
            peerRwLock.readLock().withLock {
                if (peers.isEmpty()) return WorkerTickStatus.IDLE
                var idle = true
                for (managed in peers.values) {
                    managed.sendBufLock.withLock {
                        if (managed.disconnected) return@withLock
                        while (managed.sendBuf.isNotEmpty()) {
                            idle = false
                            val op = managed.sendBuf.peekFirst() ?: break
                            val ok = currentHost.sendPacket(managed.enetPeer, op.channel, op.data, op.reliable)
                            if (!ok) break
                            managed.sendBuf.removeFirst()
                        }
                    }
                }
                return if (idle) WorkerTickStatus.IDLE else WorkerTickStatus.SUCCESS
            }
        }
    }

    fun accept(): EnetAcceptedPeer {
        peerRwLock.writeLock().withLock {
            if (newPeers.isEmpty() || newPeersOrdered.isEmpty()) {
                return EnetAcceptedPeer(-1, "", 0, EnetAcceptStatus.NO_PEER)
            }
            val rawPeer = newPeersOrdered.removeFirst()
            val managed = newPeers.remove(rawPeer) ?: return EnetAcceptedPeer(-1, "", 0, EnetAcceptStatus.NO_PEER)
            val peerAddr = rawPeer.remoteIp
            val peerPort = rawPeer.remotePort
            val ref = allocateRef()
            peers[ref] = managed
            peersLookup[rawPeer] = ref
            return EnetAcceptedPeer(ref, peerAddr, peerPort, EnetAcceptStatus.SUCCESS)
        }
    }

    private fun allocateRef(): Int {
        while (peers.containsKey(nextRef)) {
            nextRef++
            if (nextRef < 0) nextRef = 1
        }
        val assigned = nextRef
        nextRef++
        if (nextRef < 0) nextRef = 1
        return assigned
    }

    fun recv(peerRef: Int, buf: ByteArray, bufLen: Int, channel: Int): EnetRecvResult {
        peerRwLock.readLock().withLock {
            val managed = peers[peerRef]
            if (managed == null || managed.closed) {
                return EnetRecvResult(-1, EnetRecvStatus.PEER_NOT_FOUND)
            }
            val emptyStatus = if (managed.disconnected) EnetRecvStatus.PEER_CLOSED else EnetRecvStatus.WOULD_BLOCK
            managed.recvBufLock.withLock {
                val queue = managed.recvBuf[channel]
                if (queue == null || queue.isEmpty()) {
                    return EnetRecvResult(-1, emptyStatus)
                }
                val packet = queue.peekFirst() ?: return EnetRecvResult(-1, emptyStatus)
                if (packet.size > bufLen) {
                    return EnetRecvResult(-1, EnetRecvStatus.BUFFER_TOO_SMALL)
                }
                System.arraycopy(packet, 0, buf, 0, packet.size)
                queue.removeFirst()
                return EnetRecvResult(packet.size, EnetRecvStatus.SUCCESS)
            }
        }
    }

    fun send(peerRef: Int, buf: ByteArray, offset: Int, len: Int, channel: Int, reliable: Boolean): Pair<Int, EnetSendStatus> {
        peerRwLock.readLock().withLock {
            val managed = peers[peerRef]
            if (managed == null || managed.closed) {
                return Pair(-1, EnetSendStatus.PEER_NOT_FOUND)
            }
            if (managed.disconnected) {
                return Pair(-1, EnetSendStatus.PEER_CLOSED)
            }
            val copy = buf.copyOfRange(offset, offset + len)
            managed.sendBufLock.withLock {
                managed.sendBuf.addLast(EnetSendOp(copy, reliable, channel))
            }
            return Pair(len, EnetSendStatus.SUCCESS)
        }
    }

    fun getIncomingData(peerRef: Int, channel: Int): Int {
        peerRwLock.readLock().withLock {
            val managed = peers[peerRef] ?: return -1
            if (managed.disconnected || managed.closed) return -1
            managed.recvBufLock.withLock {
                val queue = managed.recvBuf[channel] ?: return 0
                var sum = 0
                for (p in queue) sum += p.size
                return sum
            }
        }
    }

    fun getOutgoingData(peerRef: Int, channel: Int): Int {
        peerRwLock.readLock().withLock {
            val managed = peers[peerRef] ?: return -1
            if (managed.disconnected || managed.closed) return -1
            managed.sendBufLock.withLock {
                var sum = 0
                for (op in managed.sendBuf) {
                    if (op.channel == channel) sum += op.data.size
                }
                return sum
            }
        }
    }

    fun close(peerRef: Int) {
        serverLock.withLock {
            peerRwLock.writeLock().withLock {
                val managed = peers[peerRef] ?: return
                if (!managed.disconnected) {
                    managed.closed = true
                    host?.disconnectPeer(managed.enetPeer, 0)
                    return
                }
                peers.remove(peerRef)
            }
        }
    }

    fun reset() {
        stopping = true
        inWorker?.let {
            try {
                it.join(2000)
            } catch (_: InterruptedException) {
            }
        }
        inWorker = null
        outWorker?.let {
            try {
                it.join(2000)
            } catch (_: InterruptedException) {
            }
        }
        outWorker = null
        serverLock.withLock {
            resetInternal()
        }
    }

    private fun resetInternal() {
        host?.destroy()
        host = null
        serverDead = true
        peerRwLock.writeLock().withLock {
            newPeers.clear()
            newPeersOrdered.clear()
            peers.clear()
            peersLookup.clear()
        }
        state = BasicEnetClientState.INACTIVE
    }
}
