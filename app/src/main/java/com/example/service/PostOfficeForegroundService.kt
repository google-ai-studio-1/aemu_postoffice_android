package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.core.common.LogCategory
import com.example.core.common.ServerLogger
import java.util.concurrent.Executors

class PostOfficeForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.aemupostoffice.port.action.START"
        const val ACTION_STOP = "com.aemupostoffice.port.action.STOP"
        const val ACTION_RESTART = "com.aemupostoffice.port.action.RESTART"
        const val ACTION_SET_WAKELOCK = "com.aemupostoffice.port.action.SET_WAKELOCK"
        const val EXTRA_WAKELOCK_ENABLED = "extra_wakelock_enabled"

        private const val CHANNEL_ID = "aemu_postoffice_server_channel"
        private const val NOTIFICATION_ID = 27312

        fun startServer(context: Context) {
            val intent = Intent(context, PostOfficeForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopServer(context: Context) {
            val intent = Intent(context, PostOfficeForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun restartServer(context: Context) {
            val intent = Intent(context, PostOfficeForegroundService::class.java).apply {
                action = ACTION_RESTART
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun setWakeLockEnabled(context: Context, enabled: Boolean) {
            ConfigStorage.setWakeLockPreferred(context, enabled)
            PostOfficeCoordinator.updateWakeLockState(enabled)
            if (PostOfficeCoordinator.state.value.status == ServerRunStatus.RUNNING) {
                val intent = Intent(context, PostOfficeForegroundService::class.java).apply {
                    action = ACTION_SET_WAKELOCK
                    putExtra(EXTRA_WAKELOCK_ENABLED, enabled)
                }
                context.startService(intent)
            }
        }
    }

    private val controlExecutor = Executors.newSingleThreadExecutor()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        PostOfficeCoordinator.onStatusNotificationUpdate = { runtimeState ->
            if (runtimeState.status == ServerRunStatus.RUNNING) {
                updateNotification(runtimeState)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                controlExecutor.execute {
                    PostOfficeCoordinator.stop()
                    releaseLocks()
                    stopForegroundCompat()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                promoteToForeground(PostOfficeCoordinator.state.value)
                controlExecutor.execute {
                    val ok = PostOfficeCoordinator.restart(this)
                    if (ok) {
                        applyWakeLockPreference(ConfigStorage.isWakeLockPreferred(this))
                        updateNotification(PostOfficeCoordinator.state.value)
                    } else {
                        releaseLocks()
                        stopForegroundCompat()
                        stopSelf()
                    }
                }
                return START_STICKY
            }
            ACTION_SET_WAKELOCK -> {
                val enabled = intent.getBooleanExtra(EXTRA_WAKELOCK_ENABLED, ConfigStorage.isWakeLockPreferred(this))
                applyWakeLockPreference(enabled)
                return START_STICKY
            }
            else -> {
                promoteToForeground(PostOfficeCoordinator.state.value)
                controlExecutor.execute {
                    val ok = PostOfficeCoordinator.start(this)
                    if (ok) {
                        applyWakeLockPreference(ConfigStorage.isWakeLockPreferred(this))
                        updateNotification(PostOfficeCoordinator.state.value)
                    } else {
                        releaseLocks()
                        stopForegroundCompat()
                        stopSelf()
                    }
                }
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        PostOfficeCoordinator.onStatusNotificationUpdate = null
        PostOfficeCoordinator.stop()
        releaseLocks()
        controlExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun promoteToForeground(state: ServerRuntimeState) {
        val notification = buildNotification(state)
        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgType)
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun updateNotification(state: ServerRuntimeState) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: ServerRuntimeState): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val immutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag
        )

        val stopIntent = Intent(this, PostOfficeForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag
        )

        val players = state.adhocctlSnapshot.clients.size
        val sessions = state.relaySnapshot.sessions.size
        val contentText = if (state.status == ServerRunStatus.RUNNING) {
            "Players: $players | Relay sessions: $sessions | ${state.localAddresses.primaryIpv4}:${state.activeConfig.adhocctlPort}"
        } else {
            getString(R.string.status_starting)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_server)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPending)
            .addAction(
                R.drawable.ic_power_stop,
                getString(R.string.notification_stop_action),
                stopPending
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun applyWakeLockPreference(enabled: Boolean) {
        if (enabled) {
            acquireLocks()
        } else {
            releaseLocks()
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aemu_postoffice:ServerWakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
            }

            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(mode, "aemu_postoffice:ServerWifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
            ServerLogger.info(LogCategory.SYSTEM, "Service: CPU WakeLock and Wi-Fi Lock acquired")
        } catch (e: Exception) {
            ServerLogger.warn(LogCategory.SYSTEM, "Service: failed acquiring power locks (${e.message})")
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Exception) {
        }
    }
}
