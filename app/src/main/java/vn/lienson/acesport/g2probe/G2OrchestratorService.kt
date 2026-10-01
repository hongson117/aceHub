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
import java.net.Socket

class G2OrchestratorService : Service(), AceEngineManager.EngineListener {

    companion object {
        private const val TAG = "G2OrchestratorService"
        private const val NOTIFICATION_ID = 8000
        private const val CHANNEL_ID = "acesport_orchestrator_channel"
        private const val CHANNEL_NAME = "AceStream Hub Orchestrator"

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
    private var watchdogJob: Job? = null
    private var stallCounter = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "Creating G2OrchestratorService...")

        System.setProperty("java.net.preferIPv4Stack", "true")
        System.setProperty("java.net.preferIPv6Addresses", "false")

        configManager = G2ConfigManager(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()
        startAsForeground("Đang khởi động AceStream Hub & Engine...")

        if (configManager.isAlwaysOn247) {
            acquireLocks()
        }

        // Initialize Engine Manager
        engineManager = AceEngineManager(this, this)

        if (configManager.isHubEnabled) {
            startHubEntirely()
        } else {
            updateNotification("Trạm đang tắt • FPT Box được giải phóng 100% tài nguyên")
            AppLogger.i("SYSTEM", "Trạm phát đang ở chế độ TẮT (FPT Box rảnh rỗi)")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand received intent: ${intent?.action}")

        when (intent?.action) {
            "ACTION_START_HUB" -> {
                if (intent.getBooleanExtra("is_boot", false)) {
                    AppLogger.s("BOOT", "⚡ Khởi động trạm phát tự động sau khi bật nguồn (Headless Auto-Boot)")
                }
                startHubEntirely()
            }
            "ACTION_STOP_HUB" -> stopHubEntirely()
            "ACTION_RESTART_HUB" -> {
                scope.launch(Dispatchers.IO) {
                    stopHubEntirely()
                    delay(1500)
                    launch(Dispatchers.Main) { startHubEntirely() }
                }
            }
            "ACTION_PREWARM" -> {
                val chId = intent.getStringExtra("channel_id") ?: configManager.defaultChannelId
                val sType = intent.getStringExtra("source_type") ?: configManager.defaultSourceType
                scope.launch(Dispatchers.IO) {
                    proxyServer?.prewarmStream(chId, sType, persistent = true)
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "G2OrchestratorService onDestroy called!")
        instance = null
        scope.cancel()
        watchdogJob?.cancel()
        proxyServer?.stop()
        proxyServer = null
        engineManager.unbind()
        releaseLocks()
    }

    // --- 3 Operational Modes Support ---

    fun stopHubEntirely() {
        configManager.isHubEnabled = false
        watchdogJob?.cancel()
        watchdogJob = null
        proxyServer?.stop()
        proxyServer = null
        engineManager.unbind()
        releaseLocks()
        updateNotification("Trạm đã tắt • FPT Box giải phóng 100% RAM & CPU")
        AppLogger.s("SYSTEM", "🛑 ĐÃ TẮT TOÀN BỘ TRẠM PHÁT. FPT Box đã được giải phóng hoàn toàn 100% RAM & CPU để làm việc khác!")
    }

    fun startHubEntirely() {
        configManager.isHubEnabled = true
        if (configManager.isAlwaysOn247) {
            acquireLocks()
        }

        AppLogger.i("SYSTEM", "🚀 Đang khởi động Engine và Trạm phát Proxy cổng ${configManager.proxyPort}...")
        engineManager.bindAndStart()

        proxyServer?.stop()
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

        startWatchdogLoop()
        wakeUpTailscaleIfInstalled()
        updateNotification("AceStream Hub Đang Hoạt Động (Port ${configManager.proxyPort})")
        AppLogger.s("SYSTEM", "🟢 TRẠM PHÁT ĐANG HOẠT ĐỘNG (24/7 Mode: ${if (configManager.isAlwaysOn247) "BẬT" else "TẮT"})")
    }

    private fun startWatchdogLoop() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch(Dispatchers.IO) {
            AppLogger.i("WATCHDOG", "Bảo vệ Watchdog tự phục hồi đã kích hoạt (Tự restart khi nghẽn)")
            // Đợi 25 giây ban đầu để Engine hoàn tất khởi động bình thường
            delay(25000)
            var consecutiveEngineDownCount = 0

            while (isActive) {
                delay(5000)
                if (!configManager.isHubEnabled) continue

                // Nếu Engine đang trong tiến trình boot, không can thiệp
                if (engineManager.isStarting) {
                    consecutiveEngineDownCount = 0
                    continue
                }

                // 1. Kiểm tra Engine có bị crash/treo không (Kiểm tra cả socket lẫn HTTP response)
                val isPort62062Alive = isSocketAlive("127.0.0.1", 62062)
                val isEngineAlive = isPort62062Alive || checkEngineHttpHealthy()

                if (!isEngineAlive) {
                    consecutiveEngineDownCount++
                    // Chỉ coi là chết nếu mất kết nối liên tục 3 lần (15 giây)
                    if (consecutiveEngineDownCount >= 3) {
                        consecutiveEngineDownCount = 0
                        if (configManager.isWatchdogAutoRecover) {
                            AppLogger.w("WATCHDOG", "⚠️ Engine không phản hồi hoặc bị đơ liên tục 15s! Tự động khởi động lại Engine...")
                            forceRestartEngine()
                        }
                    }
                } else {
                    consecutiveEngineDownCount = 0
                }

                // 2. Kiểm tra luồng có bị nghẽn (0 KB/s khi có client kết nối)
                val activeStream = proxyServer?.latestActiveStream
                if (activeStream != null && activeStream.clientCount > 0) {
                    if (activeStream.speedKbps == 0L) {
                        stallCounter++
                        if (stallCounter >= 3) { // 3 * 5s = 15 giây đứng luồng
                            if (configManager.isWatchdogAutoRecover) {
                                AppLogger.w("WATCHDOG", "⚠️ Luồng ${activeStream.channelId.take(8)} bị nghẽn 15s (0 KB/s)! Tự động khởi động lại luồng...")
                                stallCounter = 0
                                proxyServer?.prewarmStream(activeStream.channelId, activeStream.sourceType, persistent = activeStream.isPersistent)
                            }
                        }
                    } else {
                        stallCounter = 0
                    }
                } else {
                    stallCounter = 0
                }
            }
        }
    }

    private fun checkEngineHttpHealthy(): Boolean {
        return try {
            val url = java.net.URL("http://127.0.0.1:6878/server/api?method=get_version")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            val code = conn.responseCode
            conn.disconnect()
            code in 200..403
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun forceRestartEngine() {
        engineManager.unbind()
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -9 -f libacepython || killall -9 libacepython.so"))
        } catch (_: Exception) {}
        delay(2000)
        engineManager.bindAndStart()
        delay(15000)
    }

    private fun isSocketAlive(host: String, port: Int): Boolean {
        return try {
            Socket().use { sock ->
                sock.connect(java.net.InetSocketAddress(host, port), 800)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun wakeUpTailscaleIfInstalled() {
        if (!configManager.isAutoWakeTailscale) return
        scope.launch(Dispatchers.IO) {
            try {
                // Đợi 6 giây cho hệ thống mạng LAN và Wi-Fi sẵn sàng
                delay(6000)
                AppLogger.i("TAILSCALE", "🔄 Đang kiểm tra và đánh thức ứng dụng Tailscale tự động...")
                val tailscalePkg = "com.tailscale.ipn"
                var launched = false

                // 1. Thử gọi trực tiếp qua ComponentName (vượt qua hạn chế Android 11)
                try {
                    val directIntent = Intent().apply {
                        component = android.content.ComponentName(tailscalePkg, "$tailscalePkg.MainActivity")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    }
                    startActivity(directIntent)
                    launched = true
                    AppLogger.i("TAILSCALE", "🚀 Đã phát lệnh khởi chạy trực tiếp tới Tailscale MainActivity")
                } catch (e1: Exception) {
                    Log.d(TAG, "Direct launch failed: ${e1.message}")
                }

                // 2. Fallback qua PackageManager nếu chưa chạy
                if (!launched) {
                    val pm = packageManager
                    val launchIntent = pm.getLaunchIntentForPackage(tailscalePkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        startActivity(launchIntent)
                        launched = true
                        AppLogger.i("TAILSCALE", "🚀 Đã phát lệnh khởi chạy Tailscale qua PackageManager")
                    }
                }

                if (launched) {
                    AppLogger.s("TAILSCALE", "🟢 Đã đánh thức Tailscale tự động kết nối ngầm cùng AceHub")
                    // Sau 3 giây, tự động trả màn hình về Home để không che màn hình TV
                    delay(3000)
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(homeIntent)
                } else {
                    AppLogger.w("TAILSCALE", "⚠️ Không tìm thấy gói cài đặt Tailscale (com.tailscale.ipn) trên máy")
                }
            } catch (e: Exception) {
                AppLogger.e("TAILSCALE", "⚠️ Lỗi khi khởi động Tailscale: ${e.message}")
            }
        }
    }

    // --- AceEngineManager.EngineListener ---

    override fun onEngineStateChanged(state: String, details: String) {
        AppLogger.i("ENGINE", "Trạng thái Engine: $state ($details)")
        updateNotification("Engine: $details")
        MainActivity.instance?.updateEngineSetupStatus(state, details)
    }

    override fun onEngineReady(httpPort: Int, enginePort: Int, packageName: String, version: String) {
        AppLogger.s("ENGINE", "Engine READY: $packageName v$version | HTTP :$httpPort | Api :$enginePort | Proxy :${configManager.proxyPort}")
        updateNotification("AceStream Hub Sẵn Sàng (Port ${configManager.proxyPort})")

        val lanIp = getLanIpAddress()
        val banner = "=========================================\n" +
                "Engine IP: $lanIp\n" +
                "Engine Port: ${configManager.proxyPort}\n" +
                "Kết nối: Thành công\n" +
                "========================================="
        Log.i(TAG, banner)
        AppLogger.s("SYSTEM", "Kết nối Engine thành công: http://$lanIp:${configManager.proxyPort}")
        MainActivity.instance?.showEngineStatus(lanIp, configManager.proxyPort, "Thành công")
        MainActivity.instance?.updateEngineSetupStatus("READY", "Sẵn sàng (Port $httpPort/$enginePort)")

        // Chỉ khôi phục luồng nếu người dùng đã chủ động kích hoạt và cấu hình lưu trước đó
        val savedChannel = configManager.defaultChannelId
        val savedType = configManager.defaultSourceType
        if (configManager.isAlwaysHotStream && savedChannel.isNotEmpty()) {
            AppLogger.i("SYSTEM", "🔄 Khôi phục luồng do người dùng chỉ định: ${savedChannel.take(12)}... ($savedType)")
            scope.launch(Dispatchers.IO) {
                delay(2000)
                val ok = proxyServer?.prewarmStream(savedChannel, savedType, persistent = true) ?: false
                if (ok) {
                    AppLogger.s("SYSTEM", "🟢 Đã kết nối lại tín hiệu luồng thành công: http://$lanIp:${configManager.proxyPort}/live")
                } else {
                    AppLogger.w("SYSTEM", "⚠️ Chưa thu được tín hiệu luồng từ nguồn phát.")
                }
            }
        } else {
            AppLogger.s("SYSTEM", "🟢 Trạm phát sẵn sàng ở chế độ rảnh rỗi (Idle). Đang chờ tín hiệu luồng từ thiết bị trong mạng LAN...")
        }
    }

    override fun onEngineError(error: String) {
        AppLogger.e("ENGINE", "Lỗi Engine: $error")
        updateNotification("Engine Error: $error")
        MainActivity.instance?.updateEngineSetupStatus("ERROR", error)
    }

    private fun getLanIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress
                        if (ip != null && !ip.startsWith("127.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    private fun startAsForeground(contentText: String) {
        val notification = buildNotification(contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(contentText: String) {
        try {
            notificationManager?.notify(NOTIFICATION_ID, buildNotification(contentText))
        } catch (_: Exception) {}
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AceStream Hub 24/7")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.app_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "AceStream Hub 24/7 Foreground Service"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AceStreamHub::CpuWakeLock").apply {
                    setReferenceCounted(false)
                    acquire(24 * 60 * 60 * 1000L)
                }
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AceStreamHub::WifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            AppLogger.d("SYSTEM", "Đã khóa CPU & Wi-Fi giữ trạng thái 24/7 không ngủ")
        } catch (e: Exception) {
            Log.w(TAG, "Error acquiring locks: ${e.message}")
        }
    }

    fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
            AppLogger.d("SYSTEM", "Đã giải phóng khóa CPU & Wi-Fi")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing locks: ${e.message}")
        }
    }
}
