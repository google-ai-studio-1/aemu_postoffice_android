package com.example.service

import android.content.Context
import com.example.core.adhocctl.GameDb
import com.example.core.adhocctl.GameDbCodec
import com.example.core.common.LogCategory
import com.example.core.common.ServerConfig
import com.example.core.common.ServerConfigCodec
import com.example.core.common.ServerLogger
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

data class LocalNetworkAddresses(
    val primaryIpv4: String,
    val ipv4Addresses: List<String>,
    val ipv6Addresses: List<String>
)

object ConfigStorage {
    private const val CONFIG_FILE_NAME = "config.json"
    private const val GAME_DB_FILE_NAME = "game_db.json"
    private const val HTTP_ASSETS_DIR_NAME = "http_assets"
    private const val GPL_ASSET_NAME = "gpl-3.0.txt"
    private const val PREFS_NAME = "aemu_postoffice_prefs"
    private const val KEY_WAKELOCK_ENABLED = "wakelock_enabled"

    fun ensureInitialized(context: Context) {
        val appCtx = context.applicationContext
        copyAssetIfMissing(appCtx, CONFIG_FILE_NAME, getConfigFile(appCtx))
        copyAssetIfMissing(appCtx, GAME_DB_FILE_NAME, getGameDbFile(appCtx))

        val httpDir = getHttpAssetsDir(appCtx)
        if (!httpDir.exists()) {
            httpDir.mkdirs()
        }
        copyAssetIfMissing(appCtx, "$HTTP_ASSETS_DIR_NAME/status.html", File(httpDir, "status.html"))
        copyAssetIfMissing(appCtx, "$HTTP_ASSETS_DIR_NAME/style.css", File(httpDir, "style.css"))
    }

    fun getConfigFile(context: Context): File = File(context.applicationContext.filesDir, CONFIG_FILE_NAME)

    fun getGameDbFile(context: Context): File = File(context.applicationContext.filesDir, GAME_DB_FILE_NAME)

    fun getHttpAssetsDir(context: Context): File = File(context.applicationContext.filesDir, HTTP_ASSETS_DIR_NAME)

    fun readConfigRaw(context: Context): String {
        ensureInitialized(context)
        return try {
            getConfigFile(context).readText(Charsets.UTF_8)
        } catch (_: Exception) {
            ServerConfigCodec.toJsonString(ServerConfig())
        }
    }

    fun writeConfigRaw(context: Context, json: String) {
        ensureInitialized(context)
        atomicWrite(getConfigFile(context), json)
    }

    fun writeConfig(context: Context, config: ServerConfig) {
        writeConfigRaw(context, ServerConfigCodec.toJsonString(config))
    }

    fun resetConfigToDefault(context: Context): String {
        val appCtx = context.applicationContext
        val bundled = readAssetText(appCtx, CONFIG_FILE_NAME)
            ?: ServerConfigCodec.toJsonString(ServerConfig())
        atomicWrite(getConfigFile(appCtx), bundled)
        return bundled
    }

    fun readGameDbRaw(context: Context): String {
        ensureInitialized(context)
        return try {
            getGameDbFile(context).readText(Charsets.UTF_8)
        } catch (_: Exception) {
            GameDbCodec.toJsonString(GameDb())
        }
    }

    fun writeGameDbRaw(context: Context, json: String) {
        ensureInitialized(context)
        atomicWrite(getGameDbFile(context), json)
    }

    fun writeGameDb(context: Context, gameDb: GameDb) {
        writeGameDbRaw(context, GameDbCodec.toJsonString(gameDb))
    }

    fun resetGameDbToDefault(context: Context): String {
        val appCtx = context.applicationContext
        val bundled = readAssetText(appCtx, GAME_DB_FILE_NAME)
            ?: GameDbCodec.toJsonString(GameDb())
        atomicWrite(getGameDbFile(appCtx), bundled)
        return bundled
    }

    fun readGplLicenseText(context: Context): String {
        return readAssetText(context.applicationContext, GPL_ASSET_NAME)
            ?: "GNU General Public License v3.0"
    }

    fun isWakeLockPreferred(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_WAKELOCK_ENABLED, true)
    }

    fun setWakeLockPreferred(context: Context, enabled: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_WAKELOCK_ENABLED, enabled).apply()
    }

    fun detectLocalAddresses(): LocalNetworkAddresses {
        val ipv4List = ArrayList<String>()
        val ipv6List = ArrayList<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr.isLoopbackAddress) continue
                    when (addr) {
                        is Inet4Address -> {
                            val host = addr.hostAddress
                            if (!host.isNullOrEmpty() && !ipv4List.contains(host)) {
                                ipv4List.add(host)
                            }
                        }
                        is Inet6Address -> {
                            if (!addr.isLinkLocalAddress) {
                                val host = addr.hostAddress?.substringBefore('%')
                                if (!host.isNullOrEmpty() && !ipv6List.contains(host)) {
                                    ipv6List.add(host)
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        val preferred = ipv4List.firstOrNull {
            it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.")
        } ?: ipv4List.firstOrNull() ?: "127.0.0.1"
        return LocalNetworkAddresses(
            primaryIpv4 = preferred,
            ipv4Addresses = ipv4List,
            ipv6Addresses = ipv6List
        )
    }

    private fun copyAssetIfMissing(context: Context, assetPath: String, destFile: File) {
        if (destFile.exists() && destFile.length() > 0L) return
        try {
            destFile.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            ServerLogger.error(LogCategory.CONFIG, "ConfigStorage: failed extracting $assetPath (${e.message})")
        }
    }

    private fun readAssetText(context: Context, assetPath: String): String? {
        return try {
            context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    private fun atomicWrite(target: File, content: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(content, Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            temp.delete()
        }
    }
}
