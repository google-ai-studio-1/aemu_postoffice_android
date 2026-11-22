package com.example.core.common

import org.json.JSONObject
import java.io.File

data class ServerConfig(
    val targetTickIntervalMs: Long = 8L,
    val targetTickIntervalIdleMs: Long = 500L,
    val ipAddr: String = "::FFFF:0.0.0.0",
    val enableEnet: Boolean = true,
    val port: Int = 27313,
    val numThreads: Int = 4,
    val sessionInitTimeLimitMs: Long = 5000L,
    val dataQueueSizeLimitByte: Long = 512000L,
    val connectTimeLimitMs: Long = 5000L,
    val maxNumSessions: Int = 4000,
    val strictMode: Boolean = true,
    val maxNumSessionsPerMac: Int = 20,
    val enableAdhocctl: Boolean = true,
    val adhocctlTargetTickIntervalMs: Long = 33L,
    val adhocctlTargetTickIntervalIdleMs: Long = 500L,
    val adhocctlPort: Int = 27312,
    val adhocctlNumThreads: Int = 4,
    val adhocctlDataQueueSizeLimitByte: Int = 512000,
    val adhocctlRelayOnly: Boolean = true,
    val adhocctlTimeoutMs: Long = 30000L,
    val adhocctlMaxNumSessions: Int = 1000,
    val adhocctlMaxNumSessionsPerIp: Int = 16,
    val httpStatusServerPort: Int = 8888,
    val metadataName: String = "PSP multiplayer server",
    val metadataLocation: String = "On the internet",
    val metadataWebsite: String = "http://website.psp/",
    val metadataAdminEmail: String = "admin@website.psp",
    val metadataOwnerName: String = "Admin"
)

data class ConfigParseResult(
    val isValid: Boolean,
    val config: ServerConfig,
    val defaultedKeys: Set<String>,
    val forcedKeys: Set<String>,
    val parseError: String? = null
)

data class ConfigMergeResult(
    val merged: ServerConfig,
    val runtimeChanged: Boolean,
    val restartRequiredKeys: Set<String>
)

object ServerConfigCodec {
    fun parseFromFile(file: File): ConfigParseResult {
        if (!file.exists()) {
            ServerLogger.error(LogCategory.CONFIG, "parse_config_from_json: failed reading config from ${file.absolutePath}")
            return ConfigParseResult(false, ServerConfig(), emptySet(), emptySet(), "File not found")
        }
        val raw = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            ServerLogger.error(LogCategory.CONFIG, "parse_config_from_json: failed reading config from ${file.absolutePath}")
            return ConfigParseResult(false, ServerConfig(), emptySet(), emptySet(), e.message)
        }
        if (raw.isEmpty()) {
            ServerLogger.error(LogCategory.CONFIG, "parse_config_from_json: failed reading config from ${file.absolutePath}")
            return ConfigParseResult(false, ServerConfig(), emptySet(), emptySet(), "Empty config file")
        }
        return parseFromJsonString(raw)
    }

    fun parseFromJsonString(rawJson: String): ConfigParseResult {
        val json = try {
            JSONObject(rawJson)
        } catch (e: Exception) {
            ServerLogger.error(LogCategory.CONFIG, "parse_config_from_json: failed parsing JSON (${e.message})")
            return ConfigParseResult(false, ServerConfig(), emptySet(), emptySet(), "Invalid JSON: ${e.message}")
        }

        val defaults = ServerConfig()
        var goodConfig = true
        val defaultedKeys = LinkedHashSet<String>()
        val forcedKeys = LinkedHashSet<String>()

        fun parseLong(name: String, defaultValue: Long): Long {
            if (!json.has(name)) {
                ServerLogger.warn(LogCategory.CONFIG, "parse_config_from_json: $name is missing from config, using default $defaultValue")
                goodConfig = false
                defaultedKeys.add(name)
                return defaultValue
            }
            val raw = json.opt(name)
            if (raw is Int || raw is Long) {
                return (raw as Number).toLong()
            }
            ServerLogger.warn(LogCategory.CONFIG, "parse_config_from_json: $name is not an integer value, using default $defaultValue")
            goodConfig = false
            defaultedKeys.add(name)
            return defaultValue
        }

        fun parseInt(name: String, defaultValue: Int): Int {
            return parseLong(name, defaultValue.toLong()).toInt()
        }

        fun parseString(name: String, defaultValue: String): String {
            if (!json.has(name)) {
                ServerLogger.warn(LogCategory.CONFIG, "parse_config_from_json: $name is missing from config, using default $defaultValue")
                goodConfig = false
                defaultedKeys.add(name)
                return defaultValue
            }
            val raw = json.opt(name)
            if (raw is String) {
                return raw
            }
            ServerLogger.warn(LogCategory.CONFIG, "parse_config_from_json: $name is not a string value, using default $defaultValue")
            goodConfig = false
            defaultedKeys.add(name)
            return defaultValue
        }

        fun parseBool(name: String, defaultValue: Boolean): Boolean {
            if (!json.has(name)) {
                ServerLogger.warn(LogCategory.CONFIG, "parse_config_from_json: $name is missing from config, using default $defaultValue")
                goodConfig = false
                defaultedKeys.add(name)
                return defaultValue
            }
            val raw = json.opt(name)
            if (raw is Boolean) {
                return raw
            }
            ServerLogger.warn(LogCategory.CONFIG, "parse_config_from_json: $name is not a boolean value, using default $defaultValue")
            goodConfig = false
            defaultedKeys.add(name)
            return defaultValue
        }

        var targetTickIntervalMs = parseLong("target_tick_interval_ms", defaults.targetTickIntervalMs)
        var targetTickIntervalIdleMs = parseLong("target_tick_interval_idle_ms", defaults.targetTickIntervalIdleMs)
        var ipAddr = parseString("ip_addr", defaults.ipAddr)
        var enableEnet = parseBool("enable_enet", defaults.enableEnet)
        var port = parseInt("port", defaults.port)
        var numThreads = parseInt("num_threads", defaults.numThreads)
        var sessionInitTimeLimitMs = parseLong("session_init_time_limit_ms", defaults.sessionInitTimeLimitMs)
        var dataQueueSizeLimitByte = parseLong("data_queue_size_limit_byte", defaults.dataQueueSizeLimitByte)
        var connectTimeLimitMs = parseLong("connect_time_limit_ms", defaults.connectTimeLimitMs)
        var maxNumSessions = parseInt("max_num_sessions", defaults.maxNumSessions)
        var strictMode = parseBool("strict_mode", defaults.strictMode)
        var maxNumSessionsPerMac = parseInt("max_num_sessions_per_mac", defaults.maxNumSessionsPerMac)
        var enableAdhocctl = parseBool("enable_adhocctl", defaults.enableAdhocctl)
        var adhocctlTargetTickIntervalMs = parseLong("adhocctl_target_tick_interval_ms", defaults.adhocctlTargetTickIntervalMs)
        var adhocctlTargetTickIntervalIdleMs = parseLong("adhocctl_target_tick_interval_idle_ms", defaults.adhocctlTargetTickIntervalIdleMs)
        var adhocctlPort = parseInt("adhocctl_port", defaults.adhocctlPort)
        var adhocctlNumThreads = parseInt("adhocctl_num_threads", defaults.adhocctlNumThreads)
        var adhocctlDataQueueSizeLimitByte = parseInt("adhocctl_data_queue_size_limit_byte", defaults.adhocctlDataQueueSizeLimitByte)
        var adhocctlRelayOnly = parseBool("adhocctl_relay_only", defaults.adhocctlRelayOnly)
        var adhocctlTimeoutMs = parseLong("adhocctl_timeout_ms", defaults.adhocctlTimeoutMs)
        var adhocctlMaxNumSessions = parseInt("adhocctl_max_num_sessions", defaults.adhocctlMaxNumSessions)
        var adhocctlMaxNumSessionsPerIp = parseInt("adhocctl_max_num_sessions_per_ip", defaults.adhocctlMaxNumSessionsPerIp)
        var httpStatusServerPort = parseInt("http_status_server_port", defaults.httpStatusServerPort)
        val metadataName = parseString("metadata_name", defaults.metadataName)
        val metadataLocation = parseString("metadata_location", defaults.metadataLocation)
        val metadataWebsite = parseString("metadata_website", defaults.metadataWebsite)
        val metadataAdminEmail = parseString("metadata_admin_email", defaults.metadataAdminEmail)
        val metadataOwnerName = parseString("metadata_owner_name", defaults.metadataOwnerName)

        fun checkLong(name: String, value: Long, defaultValue: Long, invalid: Boolean): Long {
            if (invalid) {
                ServerLogger.warn(LogCategory.CONFIG, "parse_config_from_json: bad $name $value, using default $defaultValue instead")
                goodConfig = false
                defaultedKeys.add(name)
                return defaultValue
            }
            return value
        }

        fun checkInt(name: String, value: Int, defaultValue: Int, invalid: Boolean): Int {
            return checkLong(name, value.toLong(), defaultValue.toLong(), invalid).toInt()
        }

        targetTickIntervalMs = checkLong("target_tick_interval_ms", targetTickIntervalMs, defaults.targetTickIntervalMs, targetTickIntervalMs <= 0L)
        targetTickIntervalIdleMs = checkLong("target_tick_interval_idle_ms", targetTickIntervalIdleMs, defaults.targetTickIntervalIdleMs, targetTickIntervalIdleMs <= 0L)

        var addrFamily = NativeSocketUtil.getAddrFamily(ipAddr)
        if (addrFamily == AddrFamily.UNKNOWN) {
            ServerLogger.warn(LogCategory.CONFIG, "parse_config_from_json: bad ip_addr $ipAddr, using default ${defaults.ipAddr} instead")
            ipAddr = defaults.ipAddr
            addrFamily = NativeSocketUtil.getAddrFamily(ipAddr)
            goodConfig = false
            defaultedKeys.add("ip_addr")
        }

        port = checkInt("port", port, defaults.port, port <= 0 || port > 65535)
        numThreads = checkInt("num_threads", numThreads, defaults.numThreads, numThreads <= 0)
        sessionInitTimeLimitMs = checkLong("session_init_time_limit_ms", sessionInitTimeLimitMs, defaults.sessionInitTimeLimitMs, sessionInitTimeLimitMs < 500L)
        dataQueueSizeLimitByte = checkLong("data_queue_size_limit_byte", dataQueueSizeLimitByte, defaults.dataQueueSizeLimitByte, dataQueueSizeLimitByte < 1024L)
        connectTimeLimitMs = checkLong("connect_time_limit_ms", connectTimeLimitMs, defaults.connectTimeLimitMs, connectTimeLimitMs < 1000L)
        maxNumSessions = checkInt("max_num_sessions", maxNumSessions, defaults.maxNumSessions, maxNumSessions < 2)
        maxNumSessionsPerMac = checkInt("max_num_sessions_per_mac", maxNumSessionsPerMac, defaults.maxNumSessionsPerMac, maxNumSessionsPerMac < 1)
        adhocctlTargetTickIntervalMs = checkLong("adhocctl_target_tick_interval_ms", adhocctlTargetTickIntervalMs, defaults.adhocctlTargetTickIntervalMs, adhocctlTargetTickIntervalMs <= 0L)
        adhocctlTargetTickIntervalIdleMs = checkLong("adhocctl_target_tick_interval_idle_ms", adhocctlTargetTickIntervalIdleMs, defaults.adhocctlTargetTickIntervalIdleMs, adhocctlTargetTickIntervalIdleMs <= 0L)
        adhocctlPort = checkInt("adhocctl_port", adhocctlPort, defaults.adhocctlPort, adhocctlPort <= 0 || adhocctlPort > 65535)
        adhocctlNumThreads = checkInt("adhocctl_num_threads", adhocctlNumThreads, defaults.adhocctlNumThreads, adhocctlNumThreads <= 0)
        adhocctlDataQueueSizeLimitByte = checkInt("adhocctl_data_queue_size_limit_byte", adhocctlDataQueueSizeLimitByte, defaults.adhocctlDataQueueSizeLimitByte, adhocctlDataQueueSizeLimitByte < 512)
        adhocctlTimeoutMs = checkLong("adhocctl_timeout_ms", adhocctlTimeoutMs, defaults.adhocctlTimeoutMs, adhocctlTimeoutMs < 30000L)
        adhocctlMaxNumSessions = checkInt("adhocctl_max_num_sessions", adhocctlMaxNumSessions, defaults.adhocctlMaxNumSessions, adhocctlMaxNumSessions < 2)
        adhocctlMaxNumSessionsPerIp = checkInt("adhocctl_max_num_sessions_per_ip", adhocctlMaxNumSessionsPerIp, defaults.adhocctlMaxNumSessionsPerIp, adhocctlMaxNumSessionsPerIp < 1)
        httpStatusServerPort = checkInt("http_status_server_port", httpStatusServerPort, defaults.httpStatusServerPort, httpStatusServerPort <= 0 || httpStatusServerPort > 65535)

        if (addrFamily == AddrFamily.IPV6 && !adhocctlRelayOnly) {
            ServerLogger.warn(LogCategory.CONFIG, "parse_config_from_json: ipv6 dual stack mode is used, adhocctl_relay_only must also be true")
            adhocctlRelayOnly = true
            goodConfig = false
            forcedKeys.add("adhocctl_relay_only")
        }

        if (addrFamily == AddrFamily.IPV4 && enableEnet) {
            ServerLogger.warn(LogCategory.CONFIG, "parse_config_from_json: enet is disabled in ipv4 only mode")
            enableEnet = false
            goodConfig = false
            forcedKeys.add("enable_enet")
        }

        if (!enableAdhocctl && strictMode) {
            ServerLogger.warn(LogCategory.CONFIG, "parse_config_from_json: enable_adhocctl is set to false, strict_mode must also be false")
            strictMode = false
            goodConfig = false
            forcedKeys.add("strict_mode")
        }

        val parsed = ServerConfig(
            targetTickIntervalMs = targetTickIntervalMs,
            targetTickIntervalIdleMs = targetTickIntervalIdleMs,
            ipAddr = ipAddr,
            enableEnet = enableEnet,
            port = port,
            numThreads = numThreads,
            sessionInitTimeLimitMs = sessionInitTimeLimitMs,
            dataQueueSizeLimitByte = dataQueueSizeLimitByte,
            connectTimeLimitMs = connectTimeLimitMs,
            maxNumSessions = maxNumSessions,
            strictMode = strictMode,
            maxNumSessionsPerMac = maxNumSessionsPerMac,
            enableAdhocctl = enableAdhocctl,
            adhocctlTargetTickIntervalMs = adhocctlTargetTickIntervalMs,
            adhocctlTargetTickIntervalIdleMs = adhocctlTargetTickIntervalIdleMs,
            adhocctlPort = adhocctlPort,
            adhocctlNumThreads = adhocctlNumThreads,
            adhocctlDataQueueSizeLimitByte = adhocctlDataQueueSizeLimitByte,
            adhocctlRelayOnly = adhocctlRelayOnly,
            adhocctlTimeoutMs = adhocctlTimeoutMs,
            adhocctlMaxNumSessions = adhocctlMaxNumSessions,
            adhocctlMaxNumSessionsPerIp = adhocctlMaxNumSessionsPerIp,
            httpStatusServerPort = httpStatusServerPort,
            metadataName = metadataName,
            metadataLocation = metadataLocation,
            metadataWebsite = metadataWebsite,
            metadataAdminEmail = metadataAdminEmail,
            metadataOwnerName = metadataOwnerName
        )

        return ConfigParseResult(goodConfig, parsed, defaultedKeys, forcedKeys)
    }

    fun toJsonString(config: ServerConfig): String {
        val obj = JSONObject()
        obj.put("target_tick_interval_ms", config.targetTickIntervalMs)
        obj.put("target_tick_interval_idle_ms", config.targetTickIntervalIdleMs)
        obj.put("ip_addr", config.ipAddr)
        obj.put("enable_enet", config.enableEnet)
        obj.put("port", config.port)
        obj.put("num_threads", config.numThreads)
        obj.put("session_init_time_limit_ms", config.sessionInitTimeLimitMs)
        obj.put("data_queue_size_limit_byte", config.dataQueueSizeLimitByte)
        obj.put("connect_time_limit_ms", config.connectTimeLimitMs)
        obj.put("max_num_sessions", config.maxNumSessions)
        obj.put("strict_mode", config.strictMode)
        obj.put("max_num_sessions_per_mac", config.maxNumSessionsPerMac)
        obj.put("enable_adhocctl", config.enableAdhocctl)
        obj.put("adhocctl_target_tick_interval_ms", config.adhocctlTargetTickIntervalMs)
        obj.put("adhocctl_target_tick_interval_idle_ms", config.adhocctlTargetTickIntervalIdleMs)
        obj.put("adhocctl_port", config.adhocctlPort)
        obj.put("adhocctl_num_threads", config.adhocctlNumThreads)
        obj.put("adhocctl_data_queue_size_limit_byte", config.adhocctlDataQueueSizeLimitByte)
        obj.put("adhocctl_relay_only", config.adhocctlRelayOnly)
        obj.put("adhocctl_timeout_ms", config.adhocctlTimeoutMs)
        obj.put("adhocctl_max_num_sessions", config.adhocctlMaxNumSessions)
        obj.put("adhocctl_max_num_sessions_per_ip", config.adhocctlMaxNumSessionsPerIp)
        obj.put("http_status_server_port", config.httpStatusServerPort)
        obj.put("metadata_name", config.metadataName)
        obj.put("metadata_location", config.metadataLocation)
        obj.put("metadata_website", config.metadataWebsite)
        obj.put("metadata_admin_email", config.metadataAdminEmail)
        obj.put("metadata_owner_name", config.metadataOwnerName)
        return obj.toString(4) + "\n"
    }

    fun mergeConfig(current: ServerConfig, incoming: ServerConfig): ConfigMergeResult {
        var changed = false
        val restartKeys = LinkedHashSet<String>()

        fun <T> checkNoRuntime(name: String, oldVal: T, newVal: T): T {
            if (oldVal != newVal) {
                ServerLogger.warn(LogCategory.CONFIG, "merge_config: $name cannot be changed runtime, please relaunch")
                restartKeys.add(name)
            }
            return oldVal
        }

        fun <T> copyField(oldVal: T, newVal: T): T {
            if (oldVal != newVal) {
                changed = true
            }
            return newVal
        }

        fun <T> copyWarn(name: String, oldVal: T, newVal: T): T {
            if (oldVal != newVal) {
                ServerLogger.warn(LogCategory.CONFIG, "merge_config: relaunch is recommended when changing $name")
                changed = true
            }
            return newVal
        }

        val merged = ServerConfig(
            targetTickIntervalMs = copyField(current.targetTickIntervalMs, incoming.targetTickIntervalMs),
            targetTickIntervalIdleMs = copyField(current.targetTickIntervalIdleMs, incoming.targetTickIntervalIdleMs),
            ipAddr = checkNoRuntime("ip_addr", current.ipAddr, incoming.ipAddr),
            enableEnet = checkNoRuntime("enable_enet", current.enableEnet, incoming.enableEnet),
            port = checkNoRuntime("port", current.port, incoming.port),
            numThreads = checkNoRuntime("num_threads", current.numThreads, incoming.numThreads),
            sessionInitTimeLimitMs = copyField(current.sessionInitTimeLimitMs, incoming.sessionInitTimeLimitMs),
            dataQueueSizeLimitByte = copyField(current.dataQueueSizeLimitByte, incoming.dataQueueSizeLimitByte),
            connectTimeLimitMs = copyField(current.connectTimeLimitMs, incoming.connectTimeLimitMs),
            maxNumSessions = copyWarn("max_num_sessions", current.maxNumSessions, incoming.maxNumSessions),
            strictMode = checkNoRuntime("strict_mode", current.strictMode, incoming.strictMode),
            maxNumSessionsPerMac = copyField(current.maxNumSessionsPerMac, incoming.maxNumSessionsPerMac),
            enableAdhocctl = checkNoRuntime("enable_adhocctl", current.enableAdhocctl, incoming.enableAdhocctl),
            adhocctlTargetTickIntervalMs = copyField(current.adhocctlTargetTickIntervalMs, incoming.adhocctlTargetTickIntervalMs),
            adhocctlTargetTickIntervalIdleMs = copyField(current.adhocctlTargetTickIntervalIdleMs, incoming.adhocctlTargetTickIntervalIdleMs),
            adhocctlPort = checkNoRuntime("adhocctl_port", current.adhocctlPort, incoming.adhocctlPort),
            adhocctlNumThreads = checkNoRuntime("adhocctl_num_threads", current.adhocctlNumThreads, incoming.adhocctlNumThreads),
            adhocctlDataQueueSizeLimitByte = copyField(current.adhocctlDataQueueSizeLimitByte, incoming.adhocctlDataQueueSizeLimitByte),
            adhocctlRelayOnly = checkNoRuntime("adhocctl_relay_only", current.adhocctlRelayOnly, incoming.adhocctlRelayOnly),
            adhocctlTimeoutMs = copyField(current.adhocctlTimeoutMs, incoming.adhocctlTimeoutMs),
            adhocctlMaxNumSessions = copyWarn("adhocctl_max_num_sessions", current.adhocctlMaxNumSessions, incoming.adhocctlMaxNumSessions),
            adhocctlMaxNumSessionsPerIp = copyField(current.adhocctlMaxNumSessionsPerIp, incoming.adhocctlMaxNumSessionsPerIp),
            httpStatusServerPort = checkNoRuntime("http_status_server_port", current.httpStatusServerPort, incoming.httpStatusServerPort),
            metadataName = copyField(current.metadataName, incoming.metadataName),
            metadataLocation = copyField(current.metadataLocation, incoming.metadataLocation),
            metadataWebsite = copyField(current.metadataWebsite, incoming.metadataWebsite),
            metadataAdminEmail = copyField(current.metadataAdminEmail, incoming.metadataAdminEmail),
            metadataOwnerName = copyField(current.metadataOwnerName, incoming.metadataOwnerName)
        )

        return ConfigMergeResult(merged, changed, restartKeys)
    }
}
