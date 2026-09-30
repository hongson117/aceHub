package vn.lienson.acesport.g2probe

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
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class G2OrchestratorService : Service(), AceEngineManager.EngineListener {

    companion object {
        private const val TAG = "G2OrchestratorService"
        private const val NOTIFICATION_ID = 8000
        private const val CHANNEL_ID = "acesport_orchestrator_channel"
        private const val CHANNEL_NAME = "AceSport G2 Orchestrator"

        @Volatile
        var instance: G2OrchestratorService? = null
            private set
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    lateinit var configManager: G2ConfigManager
        private set
    lateinit var engineManager: AceEngineManager
        private set
    var proxyServer: G2StreamProxyServer? = null
        private set

    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "Creating G2OrchestratorService...")

        System.setProperty("java.net.preferIPv4Stack", "true")
        System.setProperty("java.net.preferIPv6Addresses", "false")

        configManager = G2ConfigManager(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()
        startAsForeground("Đang khởi động AceSport Hub & Engine...")

        acquireLocks()

        // 1. Bind & Start AceStream Engine
        engineManager = AceEngineManager(this, this)
        engineManager.bindAndStart()

        // 2. Start G2StreamProxyServer on port 8000 with Token Provider
        proxyServer = G2StreamProxyServer(
            port = configManager.proxyPort,
            apiPort = 62062,
            configManager = configManager,
            tokenProvider = {
                val tok = engineManager.getOrResolveToken()
                if (!tok.isNullOrEmpty()) tok else configManager.accessToken
            }
        ) { channel, peers, speed ->
            updateNotification("Đang phát: ${channel.take(8)}... | $peers Peers | $speed KB/s")
        }.apply {
            start()
        }

        Log.i(TAG, "G2OrchestratorService initialized successfully on port ${configManager.proxyPort}.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand received intent: ${intent?.action}")

        if (intent?.action == "ACTION_PREWARM") {
            val chId = intent.getStringExtra("channel_id") ?: configManager.defaultChannelId
            val sType = intent.getStringExtra("source_type") ?: configManager.defaultSourceType
            scope.launch(Dispatchers.IO) {
                proxyServer?.prewarmStream(chId, sType, persistent = true)
            }
        } else if (intent?.action == "ACTION_STOP_STREAM") {
            // Stop stream
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "G2OrchestratorService onDestroy called!")
        instance = null
        scope.cancel()
        proxyServer?.stop()
        proxyServer = null
        engineManager.unbind()
        releaseLocks()
    }

    // --- AceEngineManager.EngineListener ---

    override fun onEngineStateChanged(state: String, details: String) {
        Log.i(TAG, "Engine state: $state ($details)")
        updateNotification("Engine: $state ($details)")
    }

    override fun onEngineReady(httpPort: Int, enginePort: Int, packageName: String, version: String) {
        Log.i(TAG, "Engine READY: $packageName v$version | HTTP :$httpPort | Api :$enginePort | Proxy :${configManager.proxyPort}")
        updateNotification("AceSport Hub Sẵn Sàng (Port ${configManager.proxyPort}) &bull; Engine v$version")

        // Run Silent Deep Probe with Eleven Sports 1 4K to verify actual video data streaming
        runSilentDeepProbe()
    }

    private fun runSilentDeepProbe() {
        scope.launch(Dispatchers.IO) {
            delay(1200)
            val lanIp = getLanIpAddress()
            val port = configManager.proxyPort
            Log.i(TAG, "Starting Silent Deep Probe with Eleven Sports 1 4K...")

            val testHash = "f25b57322b5337df43bfde801e03f70363737581" // Eleven Sports 1 4K
            val fallbackHash = "fc702b72e42792a13c1f531004c2df2ac8242b69" // Canal+ Sport 1

            var probeOk = proxyServer?.prewarmStream(testHash, "infohash", persistent = true) ?: false
            if (!probeOk) {
                Log.w(TAG, "4K stream probe timeout or waiting seed, checking fallback Canal+ Sport 1...")
                probeOk = proxyServer?.prewarmStream(fallbackHash, "infohash", persistent = true) ?: false
            }

            if (probeOk) {
                val banner = "\n=========================================\n" +
                        "Engine IP: $lanIp\n" +
                        "Engine Port: $port\n" +
                        "Kết nối: Thành công\n" +
                        "========================================="
                Log.i(TAG, banner)

                withContext(Dispatchers.Main) {
                    MainActivity.instance?.showEngineStatus(lanIp, port, "Thành công")
                }
            } else {
                Log.w(TAG, "Silent Deep Probe completed: stream probe did not receive enough data within window")
            }
        }
    }

    private fun getLanIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            val candidateIps = mutableListOf<String>()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("192.168.")) return host
                        if (!host.startsWith("127.") && !host.startsWith("172.16.")) {
                            candidateIps.add(host)
                        }
                    }
                }
            }
            if (candidateIps.isNotEmpty()) return candidateIps.first()
        } catch (_: Exception) {}
        return "192.168.1.172"
    }

    override fun onEngineError(error: String) {
        Log.e(TAG, "Engine ERROR: $error")
        updateNotification("Lỗi Engine: $error")
    }

    // --- Foreground Notification & Locks ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "AceSport LAN Stream Orchestrator on Port 8000"
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AceSport G2 Hub (Port 8000)")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
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

    private fun updateNotification(text: String) {
        try {
            val notification = buildNotification(text)
            notificationManager?.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.d(TAG, "Failed to update notification: ${e.message}")
        }
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AceSport::G2OrchestratorWakeLock").apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // 24 hours lock renewed
            }

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AceSport::G2WifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "Acquired WakeLock and WifiLock for 24/7 background operation.")
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring locks: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing locks: ${e.message}")
        }
    }
}
