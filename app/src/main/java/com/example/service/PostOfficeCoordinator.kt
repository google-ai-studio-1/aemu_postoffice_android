package com.example.service

import android.content.Context
import android.os.SystemClock
import com.example.core.adhocctl.AdhocctlServer
import com.example.core.adhocctl.AdhocctlServerPumpStatus
import com.example.core.adhocctl.AdhocctlSnapshot
import com.example.core.adhocctl.GameDb
import com.example.core.adhocctl.GameDbCodec
import com.example.core.common.LogCategory
import com.example.core.common.ServerConfig
import com.example.core.common.ServerConfigCodec
import com.example.core.common.ServerLogger
import com.example.core.http.HttpStatusServer
import com.example.core.relay.RelayServer
import com.example.core.relay.RelayServerPumpStatus
import com.example.core.relay.RelaySnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class ServerRunStatus {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

data class ServerRuntimeState(
    val status: ServerRunStatus = ServerRunStatus.STOPPED,
    val startedAtElapsedMs: Long = 0L,
    val uptimeSeconds: Long = 0L,
    val activeConfig: ServerConfig = ServerConfig(),
    val activeGameDb: GameDb = GameDb(),
    val adhocctlSnapshot: AdhocctlSnapshot = AdhocctlSnapshot(),
    val relaySnapshot: RelaySnapshot = RelaySnapshot(),
    val adhocctlPendingCount: Int = 0,
    val httpRunning: Boolean = false,
    val pendingRestartKeys: Set<String> = emptySet(),
    val wakeLockEnabled: Boolean = true,
    val localAddresses: LocalNetworkAddresses = LocalNetworkAddresses("127.0.0.1", emptyList(), emptyList()),
    val errorMessage: String? = null
)

object PostOfficeCoordinator {
    private val lifecycleLock = ReentrantLock()
    private val configStateLock = ReentrantLock()
    private val stopSignal = AtomicBoolean(false)

    private val _state = MutableStateFlow(ServerRuntimeState())
    val state: StateFlow<ServerRuntimeState> = _state.asStateFlow()

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var activeConfig: ServerConfig = ServerConfig()
    @Volatile
    private var activeGameDb: GameDb = GameDb()
    @Volatile
    private var lastAdhocctlSnapshot: AdhocctlSnapshot = AdhocctlSnapshot()
    @Volatile
    private var lastRelaySnapshot: RelaySnapshot = RelaySnapshot()
    @Volatile
    private var lastAdhocctlPendingCount: Int = 0
    @Volatile
    private var pendingRestartKeys: Set<String> = emptySet()
    @Volatile
    private var startedAtElapsedMs: Long = 0L

    private var relayServer: RelayServer? = null
    private var adhocctlServer: AdhocctlServer? = null
    private var httpStatusServer: HttpStatusServer? = null

    private var relayMainThread: Thread? = null
    private var adhocctlMainThread: Thread? = null
    private var watchdogThread: Thread? = null
    private var configReloadThread: Thread? = null

    @Volatile
    var onStatusNotificationUpdate: ((ServerRuntimeState) -> Unit)? = null

    fun refreshStaticState(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        ConfigStorage.ensureInitialized(ctx)
        val parsedConfig = ServerConfigCodec.parseFromFile(ConfigStorage.getConfigFile(ctx)).config
        val parsedDb = GameDbCodec.parseFromFile(ConfigStorage.getGameDbFile(ctx)).gameDb
        val wakeLock = ConfigStorage.isWakeLockPreferred(ctx)
        val addresses = ConfigStorage.detectLocalAddresses()
        val current = _state.value
        if (current.status == ServerRunStatus.STOPPED || current.status == ServerRunStatus.ERROR) {
            activeConfig = parsedConfig
            activeGameDb = parsedDb
            _state.value = current.copy(
                activeConfig = parsedConfig,
                activeGameDb = parsedDb,
                wakeLockEnabled = wakeLock,
                localAddresses = addresses
            )
        } else {
            _state.value = current.copy(
                wakeLockEnabled = wakeLock,
                localAddresses = addresses
            )
        }
    }

    fun updateWakeLockState(enabled: Boolean) {
        _state.value = _state.value.copy(wakeLockEnabled = enabled)
    }

    fun start(context: Context): Boolean = lifecycleLock.withLock {
        val ctx = context.applicationContext
        appContext = ctx
        if (_state.value.status == ServerRunStatus.RUNNING || _state.value.status == ServerRunStatus.STARTING) {
            return true
        }

        ConfigStorage.ensureInitialized(ctx)
        val addresses = ConfigStorage.detectLocalAddresses()
        val wakeLock = ConfigStorage.isWakeLockPreferred(ctx)

        _state.value = _state.value.copy(
            status = ServerRunStatus.STARTING,
            errorMessage = null,
            pendingRestartKeys = emptySet(),
            localAddresses = addresses,
            wakeLockEnabled = wakeLock
        )

        val configFile = ConfigStorage.getConfigFile(ctx)
        val gameDbFile = ConfigStorage.getGameDbFile(ctx)
        val httpAssetsDir = ConfigStorage.getHttpAssetsDir(ctx)

        val configResult = ServerConfigCodec.parseFromFile(configFile)
        val loadedConfig = configResult.config
        if (!configResult.isValid) {
            ServerLogger.warn(LogCategory.CONFIG, "main: failed parsing config, default values have been used")
        }

        val gameDbResult = GameDbCodec.parseFromFile(gameDbFile)
        val loadedGameDb = if (gameDbResult.isValid) {
            gameDbResult.gameDb
        } else {
            ServerLogger.warn(LogCategory.CONFIG, "main: failed parsing game db, using empty game db")
            GameDb()
        }

        activeConfig = loadedConfig
        activeGameDb = loadedGameDb
        lastAdhocctlSnapshot = AdhocctlSnapshot()
        lastRelaySnapshot = RelaySnapshot()
        lastAdhocctlPendingCount = 0
        pendingRestartKeys = emptySet()
        stopSignal.set(false)

        val createdRelay = RelayServer(loadedConfig)
        if (createdRelay.isDead()) {
            createdRelay.shutdown()
            val msg = "Failed starting Relay server on ${loadedConfig.ipAddr}:${loadedConfig.port}"
            ServerLogger.error(LogCategory.SYSTEM, msg)
            _state.value = _state.value.copy(
                status = ServerRunStatus.ERROR,
                activeConfig = loadedConfig,
                activeGameDb = loadedGameDb,
                errorMessage = msg
            )
            onStatusNotificationUpdate?.invoke(_state.value)
            return false
        }

        var createdAdhocctl: AdhocctlServer? = null
        var createdHttp: HttpStatusServer? = null

        if (loadedConfig.enableAdhocctl) {
            val adhoc = AdhocctlServer(loadedConfig, loadedGameDb)
            if (adhoc.isDead()) {
                adhoc.shutdown()
                createdRelay.shutdown()
                val msg = "Failed starting Adhocctl server on ${loadedConfig.ipAddr}:${loadedConfig.adhocctlPort}"
                ServerLogger.error(LogCategory.SYSTEM, msg)
                _state.value = _state.value.copy(
                    status = ServerRunStatus.ERROR,
                    activeConfig = loadedConfig,
                    activeGameDb = loadedGameDb,
                    errorMessage = msg
                )
                onStatusNotificationUpdate?.invoke(_state.value)
                return false
            }
            createdAdhocctl = adhoc

            val http = HttpStatusServer(loadedConfig, loadedGameDb, httpAssetsDir)
            if (!http.isServerRunning()) {
                http.shutdown()
                adhoc.shutdown()
                createdRelay.shutdown()
                val msg = "Failed starting HTTP status server on ${loadedConfig.ipAddr}:${loadedConfig.httpStatusServerPort}"
                ServerLogger.error(LogCategory.SYSTEM, msg)
                _state.value = _state.value.copy(
                    status = ServerRunStatus.ERROR,
                    activeConfig = loadedConfig,
                    activeGameDb = loadedGameDb,
                    errorMessage = msg
                )
                onStatusNotificationUpdate?.invoke(_state.value)
                return false
            }
            createdHttp = http
        }

        relayServer = createdRelay
        adhocctlServer = createdAdhocctl
        httpStatusServer = createdHttp
        startedAtElapsedMs = SystemClock.elapsedRealtime()

        ServerLogger.info(LogCategory.SYSTEM, "aemu_postoffice server started")

        relayMainThread = Thread({ runRelayMainLoop(createdRelay, createdHttp) }, "relay main").apply { start() }
        if (createdAdhocctl != null) {
            adhocctlMainThread = Thread({ runAdhocctlMainLoop(createdAdhocctl, createdRelay, createdHttp) }, "adhocctl main").apply { start() }
        }
        configReloadThread = Thread({ runConfigReloadLoop(ctx) }, "config reload").apply { start() }
        watchdogThread = Thread({ runWatchdogLoop(createdRelay, createdAdhocctl, createdHttp) }, "status watchdog").apply { start() }

        val runningState = ServerRuntimeState(
            status = ServerRunStatus.RUNNING,
            startedAtElapsedMs = startedAtElapsedMs,
            uptimeSeconds = 0L,
            activeConfig = loadedConfig,
            activeGameDb = loadedGameDb,
            adhocctlSnapshot = lastAdhocctlSnapshot,
            relaySnapshot = lastRelaySnapshot,
            adhocctlPendingCount = 0,
            httpRunning = createdHttp?.isServerRunning() == true,
            pendingRestartKeys = emptySet(),
            wakeLockEnabled = wakeLock,
            localAddresses = addresses,
            errorMessage = null
        )
        _state.value = runningState
        onStatusNotificationUpdate?.invoke(runningState)
        return true
    }

    fun stop(): Unit = lifecycleLock.withLock {
        if (relayServer == null && adhocctlServer == null && httpStatusServer == null) {
            if (_state.value.status != ServerRunStatus.STOPPED) {
                _state.value = _state.value.copy(
                    status = ServerRunStatus.STOPPED,
                    uptimeSeconds = 0L,
                    adhocctlSnapshot = AdhocctlSnapshot(),
                    relaySnapshot = RelaySnapshot(),
                    adhocctlPendingCount = 0,
                    httpRunning = false,
                    pendingRestartKeys = emptySet()
                )
                onStatusNotificationUpdate?.invoke(_state.value)
            }
            return
        }

        ServerLogger.info(LogCategory.SYSTEM, "Stopping aemu_postoffice server...")
        stopSignal.set(true)

        joinQuietly(watchdogThread)
        joinQuietly(configReloadThread)
        joinQuietly(adhocctlMainThread)
        joinQuietly(relayMainThread)
        watchdogThread = null
        configReloadThread = null
        adhocctlMainThread = null
        relayMainThread = null

        httpStatusServer?.shutdown()
        httpStatusServer = null

        adhocctlServer?.shutdown()
        adhocctlServer = null

        relayServer?.shutdown()
        relayServer = null

        lastAdhocctlSnapshot = AdhocctlSnapshot()
        lastRelaySnapshot = RelaySnapshot()
        lastAdhocctlPendingCount = 0
        pendingRestartKeys = emptySet()
        startedAtElapsedMs = 0L

        ServerLogger.info(LogCategory.SYSTEM, "aemu_postoffice server stopped")

        val stoppedState = _state.value.copy(
            status = ServerRunStatus.STOPPED,
            startedAtElapsedMs = 0L,
            uptimeSeconds = 0L,
            adhocctlSnapshot = AdhocctlSnapshot(),
            relaySnapshot = RelaySnapshot(),
            adhocctlPendingCount = 0,
            httpRunning = false,
            pendingRestartKeys = emptySet()
        )
        _state.value = stoppedState
        onStatusNotificationUpdate?.invoke(stoppedState)
    }

    fun restart(context: Context): Boolean {
        stop()
        return start(context)
    }

    fun reloadConfigAndGameDbNow(context: Context) {
        val ctx = context.applicationContext
        if (_state.value.status != ServerRunStatus.RUNNING) {
            refreshStaticState(ctx)
            return
        }
        performHotReload(ctx)
    }

    private fun runRelayMainLoop(relay: RelayServer, http: HttpStatusServer?) {
        var lastSnapshotNs = System.nanoTime()
        var lastUiSnapNs = System.nanoTime()
        while (!stopSignal.get()) {
            val tickBeginNs = System.nanoTime()
            val status = relay.pump()
            if (status == RelayServerPumpStatus.LISTEN_SOCK_DEAD) {
                ServerLogger.error(LogCategory.RELAY, "relay main: server socket dead, signaling stop")
                stopSignal.set(true)
                break
            }

            val nowNs = System.nanoTime()
            val needHttpSnap = http != null && (nowNs - lastSnapshotNs) / 1_000_000_000L >= 5L
            val needUiSnap = (nowNs - lastUiSnapNs) / 1_000_000L >= 1000L

            if (needHttpSnap || needUiSnap) {
                val snap = relay.getSnapshot()
                lastRelaySnapshot = snap
                if (needHttpSnap) {
                    http?.updateRelaySnapshot(snap)
                    lastSnapshotNs = nowNs
                }
                if (needUiSnap) {
                    lastUiSnapNs = nowNs
                }
            }

            val currentConfig = activeConfig
            val targetIntervalNs = if (status == RelayServerPumpStatus.IDLE) {
                currentConfig.targetTickIntervalIdleMs * 1_000_000L
            } else {
                currentConfig.targetTickIntervalMs * 1_000_000L
            }
            val elapsedNs = System.nanoTime() - tickBeginNs
            if (elapsedNs < targetIntervalNs) {
                val sleepMs = (targetIntervalNs - elapsedNs) / 1_000_000L
                val sleepNanos = ((targetIntervalNs - elapsedNs) % 1_000_000L).toInt()
                if (sleepMs > 0L || sleepNanos > 0) {
                    try {
                        Thread.sleep(sleepMs, sleepNanos)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    private fun runAdhocctlMainLoop(
        adhocctl: AdhocctlServer,
        relay: RelayServer,
        http: HttpStatusServer?
    ) {
        var lastRelaySyncNs = System.nanoTime()
        var lastHttpSyncNs = System.nanoTime()
        while (!stopSignal.get()) {
            val tickBeginNs = System.nanoTime()
            val status = adhocctl.pump()
            if (status == AdhocctlServerPumpStatus.ERROR) {
                ServerLogger.error(LogCategory.ADHOCCTL, "adhocctl main: server error, signaling stop")
                stopSignal.set(true)
                break
            }

            val nowNs = System.nanoTime()
            val needRelaySync = (nowNs - lastRelaySyncNs) / 1_000_000L >= 100L
            val needHttpSync = http != null && (nowNs - lastHttpSyncNs) / 1_000_000_000L >= 5L

            if (needRelaySync || needHttpSync) {
                val snap = adhocctl.getSnapshot()
                lastAdhocctlSnapshot = snap
                lastAdhocctlPendingCount = adhocctl.getPendingCount()
                if (needRelaySync) {
                    relay.updateAdhocctlData(snap)
                    lastRelaySyncNs = nowNs
                }
                if (needHttpSync) {
                    http?.updateAdhocctlSnapshot(snap)
                    lastHttpSyncNs = nowNs
                }
            }

            val currentConfig = activeConfig
            val targetIntervalNs = if (status == AdhocctlServerPumpStatus.IDLE) {
                currentConfig.adhocctlTargetTickIntervalIdleMs * 1_000_000L
            } else {
                currentConfig.adhocctlTargetTickIntervalMs * 1_000_000L
            }
            val elapsedNs = System.nanoTime() - tickBeginNs
            if (elapsedNs < targetIntervalNs) {
                val sleepMs = (targetIntervalNs - elapsedNs) / 1_000_000L
                val sleepNanos = ((targetIntervalNs - elapsedNs) % 1_000_000L).toInt()
                if (sleepMs > 0L || sleepNanos > 0) {
                    try {
                        Thread.sleep(sleepMs, sleepNanos)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    private fun runConfigReloadLoop(context: Context) {
        var lastReloadNs = System.nanoTime()
        while (!stopSignal.get()) {
            val nowNs = System.nanoTime()
            if ((nowNs - lastReloadNs) / 1_000_000_000L >= 5L) {
                lastReloadNs = nowNs
                performHotReload(context)
            }
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun performHotReload(context: Context): Unit = configStateLock.withLock {
        val configFile = ConfigStorage.getConfigFile(context)
        val parsedCfg = ServerConfigCodec.parseFromFile(configFile)
        if (parsedCfg.parseError == null) {
            val mergeResult = ServerConfigCodec.mergeConfig(activeConfig, parsedCfg.config)
            pendingRestartKeys = mergeResult.restartRequiredKeys
            if (mergeResult.runtimeChanged) {
                activeConfig = mergeResult.merged
                relayServer?.setConfig(mergeResult.merged)
                adhocctlServer?.setConfig(mergeResult.merged)
                httpStatusServer?.setConfig(mergeResult.merged)
                ServerLogger.info(LogCategory.CONFIG, "config reload: reloaded config.json")
            }
        }

        val gameDbFile = ConfigStorage.getGameDbFile(context)
        val parsedDb = GameDbCodec.parseFromFile(gameDbFile)
        if (parsedDb.isValid && !GameDbCodec.gameDbSame(activeGameDb, parsedDb.gameDb)) {
            activeGameDb = parsedDb.gameDb
            adhocctlServer?.setGameDb(parsedDb.gameDb)
            httpStatusServer?.setGameDb(parsedDb.gameDb)
            ServerLogger.info(LogCategory.CONFIG, "config reload: reloaded game_db.json")
        }
    }

    private fun runWatchdogLoop(
        relay: RelayServer,
        adhocctl: AdhocctlServer?,
        http: HttpStatusServer?
    ) {
        var lastPlayerCount = -1
        var lastSessionCount = -1
        var lastIpRefreshSec = 0L

        while (!stopSignal.get()) {
            if (relay.isDead()) {
                ServerLogger.error(LogCategory.SYSTEM, "status watchdog: relay server is dead")
                stopSignal.set(true)
                break
            }
            if (adhocctl != null && adhocctl.isDead()) {
                ServerLogger.error(LogCategory.SYSTEM, "status watchdog: adhocctl server is dead")
                stopSignal.set(true)
                break
            }
            if (http != null && !http.isServerRunning()) {
                ServerLogger.error(LogCategory.SYSTEM, "status watchdog: http status server stopped")
                stopSignal.set(true)
                break
            }

            val uptimeSec = if (startedAtElapsedMs > 0L) {
                ((SystemClock.elapsedRealtime() - startedAtElapsedMs) / 1000L).coerceAtLeast(0L)
            } else {
                0L
            }

            val currentAdhocSnap = lastAdhocctlSnapshot
            val currentRelaySnap = lastRelaySnapshot
            val currentAddresses = if (uptimeSec - lastIpRefreshSec >= 10L) {
                lastIpRefreshSec = uptimeSec
                ConfigStorage.detectLocalAddresses()
            } else {
                _state.value.localAddresses
            }

            val updated = _state.value.copy(
                status = ServerRunStatus.RUNNING,
                uptimeSeconds = uptimeSec,
                activeConfig = activeConfig,
                activeGameDb = activeGameDb,
                adhocctlSnapshot = currentAdhocSnap,
                relaySnapshot = currentRelaySnap,
                adhocctlPendingCount = lastAdhocctlPendingCount,
                httpRunning = http?.isServerRunning() == true,
                pendingRestartKeys = pendingRestartKeys,
                localAddresses = currentAddresses
            )
            _state.value = updated

            val playerCount = currentAdhocSnap.clients.size
            val sessionCount = currentRelaySnap.sessions.size
            if (playerCount != lastPlayerCount || sessionCount != lastSessionCount) {
                lastPlayerCount = playerCount
                lastSessionCount = sessionCount
                onStatusNotificationUpdate?.invoke(updated)
            }

            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun joinQuietly(thread: Thread?) {
        if (thread == null || thread === Thread.currentThread()) return
        thread.interrupt()
        try {
            thread.join(3000)
        } catch (_: InterruptedException) {
        }
    }
}
