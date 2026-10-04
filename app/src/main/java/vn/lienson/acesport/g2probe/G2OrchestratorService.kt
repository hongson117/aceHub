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
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

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
    private var tailscaleWatchdogJob: Job? = null
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

        // 3. Khởi chạy Watchdog duy trì kết nối Tailscale 24/7 độc lập
        startTailscaleWatchdog()

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
        tailscaleWatchdogJob?.cancel()
        tailscaleWatchdogJob = null
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
            val testHash = configManager.defaultChannelId

            if (testHash.isNotEmpty()) {
                Log.i(TAG, "Starting Silent Deep Probe with default channel: $testHash...")
                val probeOk = proxyServer?.prewarmStream(testHash, "infohash", persistent = true) ?: false
                if (probeOk) {
                    val banner = "\n=========================================\n" +
                            "Engine IP: $lanIp\n" +
                            "Engine Port: $port\n" +
                            "Kết nối: Thành công ($testHash)\n" +
                            "========================================="
                    Log.i(TAG, banner)
                } else {
                    Log.w(TAG, "Silent Deep Probe: stream probe did not receive enough data within window")
                }
            } else {
                Log.i(TAG, "AceHub is ready in standby mode on http://$lanIp:$port (0 channel active)")
            }
        }
    }

    fun getLanIpAddress(): String {
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
        return "127.0.0.1"
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

    // --- Tailscale 24/7 Watchdog ---

    fun hasTailscaleIp(): Boolean {
        return getTailscaleIp() != null
    }

    fun getTailscaleIp(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in Collections.list(interfaces)) {
                if (!iface.isUp) continue
                for (addr in Collections.list(iface.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        val parts = ip.split(".")
                        if (parts.size == 4 && parts[0] == "100") {
                            val second = parts[1].toIntOrNull() ?: 0
                            if (second in 64..127) return ip
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun wakeTailscale(context: Context) {
        if (!configManager.isKeepTailscale) {
            Log.d(TAG, "Bỏ qua đánh thức Tailscale vì tự động giữ kết nối Tailscale đang TẮT.")
            return
        }
        try {
            Log.i(TAG, "🚀 Đang tự động đánh thức Tailscale (com.tailscale.ipn)...")
            try {
                Runtime.getRuntime().exec(arrayOf("am", "start", "-n", "com.tailscale.ipn/.MainActivity"))
            } catch (_: Exception) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
                launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (launchIntent != null) context.startActivity(launchIntent)
            }

            // Trả lại màn hình Home TV sau 3.5 giây để không làm gián đoạn người dùng
            scope.launch(Dispatchers.IO) {
                delay(3500)
                try {
                    Runtime.getRuntime().exec(arrayOf("input", "keyevent", "3"))
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi đánh thức Tailscale: ${e.message}")
        }
    }

    fun startTailscaleWatchdog() {
        tailscaleWatchdogJob?.cancel()
        if (!configManager.isKeepTailscale) {
            Log.i(TAG, "Tự động giữ kết nối Tailscale đang TẮT. Không khởi chạy Watchdog.")
            return
        }
        tailscaleWatchdogJob = scope.launch(Dispatchers.IO) {
            // Kiểm tra ngay khi khởi động
            if (!hasTailscaleIp()) {
                Log.w(TAG, "Tailscale chưa có IP 100.64.0.0/10 lúc khởi động, đang tự động đánh thức...")
                wakeTailscale(this@G2OrchestratorService)
            }
            // Vòng lặp giám sát định kỳ mỗi 60 giây
            while (isActive) {
                delay(60_000)
                if (!configManager.isKeepTailscale) {
                    Log.i(TAG, "Watchdog Tailscale dừng lại do cài đặt đã tắt.")
                    break
                }
                try {
                    if (!hasTailscaleIp()) {
                        Log.w(TAG, "Watchdog: Phát hiện mất kết nối Tailscale (100.64.0.0/10)! Đang tự động đánh thức...")
                        wakeTailscale(this@G2OrchestratorService)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Lỗi Tailscale watchdog: ${e.message}")
                }
            }
        }
    }

    fun stopTailscaleWatchdog() {
        tailscaleWatchdogJob?.cancel()
        tailscaleWatchdogJob = null
        Log.i(TAG, "Đã dừng tiến trình Tailscale Watchdog.")
    }
}
