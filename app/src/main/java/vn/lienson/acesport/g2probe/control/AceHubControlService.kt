package vn.lienson.acesport.g2probe.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import vn.lienson.acesport.g2probe.G2ConfigManager
import vn.lienson.acesport.g2probe.MainActivity

class AceHubControlService : Service() {

    companion object {
        private const val TAG = "AceHubControlService"
        private const val NOTIFICATION_ID = 8001
        private const val CHANNEL_ID = "acehub_control_agent_channel"
        private const val CHANNEL_NAME = "AceHub Control Agent"

        @Volatile
        var instance: AceHubControlService? = null
            private set
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationManager: NotificationManager? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    lateinit var commandRouter: CommandRouter
        private set
    lateinit var webSocketClient: ControlWebSocketClient
        private set
    lateinit var watchdog: Watchdog
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "Initializing AceHubControlService...")

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        createNotificationChannel()
        startAsForeground("AceHub Control Agent đang hoạt động...")
        acquireWakeLock()

        // 1. Initialize Subsystems
        commandRouter = CommandRouter(this)
        webSocketClient = ControlWebSocketClient(this, commandRouter)
        watchdog = Watchdog(this, commandRouter, webSocketClient)

        // 2. Register Network Callback for instant reconnect
        registerNetworkCallback()

        // 3. Start WebSocket Client and Watchdog
        webSocketClient.start()
        watchdog.start()

        Log.i(TAG, "AceHubControlService started successfully. Remote Control Agent ACTIVE.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand received: action=${intent?.action}")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "AceHubControlService onDestroy called!")
        instance = null

        unregisterNetworkCallback()
        watchdog.stop()
        webSocketClient.stop()
        releaseWakeLock()
    }

    private fun registerNetworkCallback() {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Network connection became AVAILABLE. Triggering WebSocket reconnect...")
                    webSocketClient.triggerReconnect()
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "Network connection LOST.")
                }
            }

            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NetworkCallback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
            networkCallback = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister NetworkCallback: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Outbound WebSocket Remote Control Agent for AceHub"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val configManager = G2ConfigManager(this)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AceHub Control Agent (${configManager.deviceId})")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startAsForeground(initialText: String) {
        val notification = buildNotification(initialText)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startForeground: ${e.message}", e)
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AceHub::ControlWakeLock").apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L)
            }
            Log.i(TAG, "Acquired WakeLock for AceHubControlService.")
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock: ${e.message}")
        }
    }
}
