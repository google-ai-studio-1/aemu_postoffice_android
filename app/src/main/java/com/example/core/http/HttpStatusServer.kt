package com.example.core.http

import com.example.core.adhocctl.AdhocctlSnapshot
import com.example.core.adhocctl.GameDb
import com.example.core.common.LogCategory
import com.example.core.common.NativeSocketUtil
import com.example.core.common.ServerConfig
import com.example.core.common.ServerLogger
import com.example.core.relay.RelaySnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.LinkedHashSet
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class HttpStatusServer(
    initialConfig: ServerConfig,
    initialGameDb: GameDb,
    private val httpAssetsDir: File
) {
    private val configLock = ReentrantLock()
    private var config: ServerConfig = initialConfig

    private val gameDbLock = ReentrantLock()
    private var gameDb: GameDb = initialGameDb

    private val relaySnapshotLock = ReentrantLock()
    private var relaySnapshot: RelaySnapshot = RelaySnapshot()

    private val adhocctlSnapshotLock = ReentrantLock()
    private var adhocctlSnapshot: AdhocctlSnapshot = AdhocctlSnapshot()

    private val crunchedLock = ReentrantLock()
    private var crunchedSnapshot: String = "{}"
    private var crunchedMetadata: String = "{}"

    @Volatile
    private var stopping = false
    @Volatile
    private var serverRunning = false

    private var serverSocket: ServerSocket? = null
    private var dataCrunchThread: Thread? = null
    private var acceptThread: Thread? = null
    private val requestExecutor = Executors.newCachedThreadPool()

    init {
        crunchSnapshotsNow()

        dataCrunchThread = Thread({
            var lastCrunchNs = System.nanoTime()
            while (!stopping) {
                val nowNs = System.nanoTime()
                if ((nowNs - lastCrunchNs) / 1_000_000_000L < 5L) {
                    try {
                        Thread.sleep(250)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                lastCrunchNs = nowNs
                crunchSnapshotsNow()
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "status prep").apply { start() }

        val bindAddr = NativeSocketUtil.resolveBindSocketAddress(initialConfig.ipAddr, initialConfig.httpStatusServerPort)
        if (bindAddr == null) {
            ServerLogger.error(LogCategory.HTTP, "HttpStatusServer: invalid bind address ${initialConfig.ipAddr}")
            serverRunning = false
        } else {
            try {
                val sock = ServerSocket()
                sock.reuseAddress = true
                sock.bind(bindAddr, 128)
                sock.soTimeout = 500
                serverSocket = sock
                serverRunning = true
                ServerLogger.info(
                    LogCategory.HTTP,
                    "HttpStatusServer: listening on ${initialConfig.ipAddr}:${initialConfig.httpStatusServerPort}"
                )
                acceptThread = Thread({
                    try {
                        while (!stopping) {
                            val client = try {
                                sock.accept()
                            } catch (_: SocketTimeoutException) {
                                continue
                            } catch (_: Exception) {
                                break
                            }
                            requestExecutor.execute { handleClient(client) }
                        }
                    } finally {
                        serverRunning = false
                    }
                }, "http status").apply { start() }
            } catch (e: Exception) {
                ServerLogger.error(
                    LogCategory.HTTP,
                    "HttpStatusServer: failed binding ${initialConfig.ipAddr}:${initialConfig.httpStatusServerPort} (${e.message})"
                )
                serverRunning = false
            }
        }
    }

    private fun crunchSnapshotsNow() {
        val relayCopy = relaySnapshotLock.withLock { relaySnapshot }
        val adhocCopy = adhocctlSnapshotLock.withLock { adhocctlSnapshot }
        val gameDbCopy = gameDbLock.withLock { gameDb }
        val configCopy = configLock.withLock { config }

        val output = JSONObject()
        val gamesArray = JSONArray()

        for (game in adhocCopy.games.values) {
            val gameEntry = JSONObject()
            val gameIds = JSONArray()
            gameIds.put(game.gameCode)
            for ((fromId, toId) in gameDbCopy.crosslinks) {
                if (toId == game.gameCode) {
                    gameIds.put(fromId)
                }
            }
            gameEntry.put("game_ids", gameIds)
            gameEntry.put("name", gameDbCopy.names[game.gameCode] ?: game.gameCode)

            var gameUserCount = 0
            val groupsArray = JSONArray()
            for (group in game.groups.values) {
                gameUserCount += group.members.size
                if (group.channel > 0) {
                    val groupEntry = JSONObject()
                    groupEntry.put("name", group.name)
                    groupEntry.put("channel", group.channel)
                    groupEntry.put("usercount", group.members.size)

                    val usersArray = JSONArray()
                    for (memberMac in group.members) {
                        val userEntry = JSONObject()
                        val adhocClient = adhocCopy.clients[memberMac]
                        userEntry.put("name", adhocClient?.nickname ?: "")
                        val pdpPortsArr = JSONArray()
                        val ptpPortsArr = JSONArray()

                        val relayClient = relayCopy.clients[memberMac]
                        if (relayClient != null) {
                            val uniquePdp = LinkedHashSet<Int>()
                            uniquePdp.addAll(relayClient.pdpPorts)
                            for (p in uniquePdp) pdpPortsArr.put(p)

                            val uniquePtp = LinkedHashSet<Int>()
                            uniquePtp.addAll(relayClient.ptpListenPorts)
                            uniquePtp.addAll(relayClient.ptpConnectPorts)
                            uniquePtp.addAll(relayClient.ptpAcceptPorts)
                            for (p in uniquePtp) ptpPortsArr.put(p)
                        }
                        userEntry.put("pdp_ports", pdpPortsArr)
                        userEntry.put("ptp_ports", ptpPortsArr)
                        usersArray.put(userEntry)
                    }
                    groupEntry.put("users", usersArray)
                    groupsArray.put(groupEntry)
                }
            }

            gameEntry.put("usercount", gameUserCount)
            gameEntry.put("groups", groupsArray)
            gamesArray.put(gameEntry)
        }
        output.put("games", gamesArray)

        val capabilities = JSONArray()
        capabilities.put("RELAY_TRANSPORT_TCP")
        if (configCopy.enableEnet) {
            capabilities.put("RELAY_TRANSPORT_ENET")
        }
        capabilities.put("RELAY_PDP_PROTOCOL_V1")
        capabilities.put("RELAY_PTP_PROTOCOL_V1")
        if (configCopy.enableAdhocctl) {
            capabilities.put("ADHOCCTL_TRANSPORT_TCP")
            if (configCopy.enableEnet) {
                capabilities.put("ADHOCCTL_TRANSPORT_ENET")
            }
            capabilities.put("ADHOCCTL_PROTOCOL_V1")
            if (configCopy.adhocctlRelayOnly) {
                capabilities.put("ADHOCCTL_RELAY_ONLY")
            }
        }

        val outputMetadata = JSONObject()
        output.put("capabilities", capabilities)
        outputMetadata.put("capabilities", capabilities)

        fun setMeta(key: String, value: String) {
            output.put(key, value)
            outputMetadata.put(key, value)
        }
        setMeta("name", configCopy.metadataName)
        setMeta("location", configCopy.metadataLocation)
        setMeta("website", configCopy.metadataWebsite)
        setMeta("admin-email", configCopy.metadataAdminEmail)
        setMeta("owner-name", configCopy.metadataOwnerName)

        val snapStr = output.toString()
        val metaStr = outputMetadata.toString()
        crunchedLock.withLock {
            crunchedSnapshot = snapStr
            crunchedMetadata = metaStr
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 3000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                writeResponse(socket, 400, "Bad Request", "text/plain;charset=UTF-8", "Bad Request".toByteArray(), false)
                return
            }
            val method = parts[0]
            val rawPath = parts[1].substringBefore('?')

            if (method == "OPTIONS") {
                writeResponse(socket, 204, "No Content", "text/plain;charset=UTF-8", ByteArray(0), true)
                return
            }
            if (method != "GET" && method != "HEAD") {
                writeResponse(socket, 405, "Method Not Allowed", "text/plain;charset=UTF-8", ByteArray(0), false)
                return
            }

            val isHead = method == "HEAD"
            when {
                rawPath == "/data.json" -> {
                    val body = crunchedLock.withLock { crunchedSnapshot }.toByteArray(Charsets.UTF_8)
                    writeResponse(socket, 200, "OK", "application/json;charset=UTF-8", if (isHead) ByteArray(0) else body, true, body.size)
                }
                rawPath == "/metadata.json" -> {
                    val body = crunchedLock.withLock { crunchedMetadata }.toByteArray(Charsets.UTF_8)
                    writeResponse(socket, 200, "OK", "application/json;charset=UTF-8", if (isHead) ByteArray(0) else body, true, body.size)
                }
                rawPath == "/" -> {
                    val statusFile = File(httpAssetsDir, "status.html")
                    if (!statusFile.exists() || !statusFile.isFile) {
                        val msg = "status.html is missing...".toByteArray(Charsets.UTF_8)
                        writeResponse(socket, 400, "Bad Request", "text/plain", if (isHead) ByteArray(0) else msg, false, msg.size)
                    } else {
                        val bytes = statusFile.readBytes()
                        writeResponse(socket, 200, "OK", "text/html;charset=UTF-8", if (isHead) ByteArray(0) else bytes, false, bytes.size)
                    }
                }
                rawPath.startsWith("/assets/") -> {
                    val rel = rawPath.removePrefix("/assets/")
                    val baseCanonical = httpAssetsDir.canonicalFile
                    val target = File(baseCanonical, rel).canonicalFile
                    if (!target.path.startsWith(baseCanonical.path + File.separator) || !target.exists() || !target.isFile) {
                        val msg = "Not Found".toByteArray(Charsets.UTF_8)
                        writeResponse(socket, 404, "Not Found", "text/plain", if (isHead) ByteArray(0) else msg, false, msg.size)
                    } else {
                        val bytes = target.readBytes()
                        writeResponse(socket, 200, "OK", contentTypeFor(target.name), if (isHead) ByteArray(0) else bytes, false, bytes.size)
                    }
                }
                else -> {
                    val msg = "Not Found".toByteArray(Charsets.UTF_8)
                    writeResponse(socket, 404, "Not Found", "text/plain", if (isHead) ByteArray(0) else msg, false, msg.size)
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun contentTypeFor(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".css") -> "text/css;charset=UTF-8"
            lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html;charset=UTF-8"
            lower.endsWith(".js") -> "application/javascript;charset=UTF-8"
            lower.endsWith(".json") -> "application/json;charset=UTF-8"
            lower.endsWith(".svg") -> "image/svg+xml"
            lower.endsWith(".png") -> "image/png"
            else -> "application/octet-stream"
        }
    }

    private fun writeResponse(
        socket: Socket,
        statusCode: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        cors: Boolean,
        contentLength: Int = body.size
    ) {
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(statusCode).append(' ').append(reason).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(contentLength).append("\r\n")
        if (cors) {
            sb.append("Access-Control-Allow-Origin: *\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        val out = socket.getOutputStream()
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) {
            out.write(body)
        }
        out.flush()
    }

    fun updateRelaySnapshot(snapshot: RelaySnapshot) {
        relaySnapshotLock.withLock {
            relaySnapshot = snapshot
        }
    }

    fun updateAdhocctlSnapshot(snapshot: AdhocctlSnapshot) {
        adhocctlSnapshotLock.withLock {
            adhocctlSnapshot = snapshot
        }
    }

    fun setGameDb(newGameDb: GameDb) {
        gameDbLock.withLock {
            gameDb = newGameDb
        }
    }

    fun setConfig(newConfig: ServerConfig) {
        configLock.withLock {
            val old = config
            config = newConfig.copy(enableEnet = old.enableEnet)
        }
    }

    fun isServerRunning(): Boolean = serverRunning

    fun shutdown() {
        stopping = true
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        acceptThread?.let {
            try {
                it.join(2000)
            } catch (_: InterruptedException) {
            }
        }
        dataCrunchThread?.let {
            try {
                it.join(2000)
            } catch (_: InterruptedException) {
            }
        }
        requestExecutor.shutdownNow()
        serverRunning = false
    }
}
