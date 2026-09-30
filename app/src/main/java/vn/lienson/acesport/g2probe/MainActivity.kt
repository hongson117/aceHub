package vn.lienson.acesport.g2probe

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import vn.lienson.acesport.g2probe.databinding.ActivityMainBinding
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AceStreamHub"
        private const val TEST_INFOHASH = "73d24aeff6515abb236ea8a3e77d89fe0b04b665" // Eleven Sports 1 HD
        @Volatile
        var instance: MainActivity? = null
            private set
    }

    private lateinit var binding: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isUpdating = false

    private val statusUpdateRunnable = object : Runnable {
        override fun run() {
            updateDashboardMetrics()
            mainHandler.postDelayed(this, 1500)
        }
    }

    private val logListener: (AppLogger.LogEntry) -> Unit = { entry ->
        runOnUiThread {
            binding.tvLiveLogConsole.append("\n" + entry.toDisplayString())
            binding.logScrollView.post {
                val child = binding.logScrollView.getChildAt(0)
                if (child != null) {
                    binding.logScrollView.scrollTo(0, child.bottom)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.i(TAG, "Starting AceStream Hub (Headless Stream Proxy)...")
        AppLogger.i("SYSTEM", "Khởi động ứng dụng AceStream Hub trên Android TV")

        // 1. Ensure Hub Service is started
        startOrchestratorService()

        // 2. Setup Controls & 3 Modes
        setupControls()

        // 3. Connect Live Log Console
        initLogConsole()

        // 4. Initial Display
        updateDashboardMetrics()
    }

    override fun onResume() {
        super.onResume()
        AppLogger.addListener(logListener)
        if (!isUpdating) {
            isUpdating = true
            mainHandler.post(statusUpdateRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        AppLogger.removeListener(logListener)
        isUpdating = false
        mainHandler.removeCallbacks(statusUpdateRunnable)
    }

    private fun startOrchestratorService() {
        val serviceIntent = Intent(this, G2OrchestratorService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun initLogConsole() {
        val allLogs = AppLogger.getAllLogs()
        if (allLogs.isNotEmpty()) {
            val sb = java.lang.StringBuilder()
            for (l in allLogs) {
                sb.append(l.toDisplayString()).append("\n")
            }
            binding.tvLiveLogConsole.text = sb.toString().trimEnd()
            binding.logScrollView.post {
                val child = binding.logScrollView.getChildAt(0)
                if (child != null) {
                    binding.logScrollView.scrollTo(0, child.bottom)
                }
            }
        } else {
            binding.tvLiveLogConsole.text = "[KHỞI TẠO] Nhật ký hệ thống đang ghi nhận..."
        }

        binding.btnClearLog.setOnClickListener {
            AppLogger.clear()
            binding.tvLiveLogConsole.text = "[HỆ THỐNG] Nhật ký đã được làm sạch."
        }
    }

    private fun setupControls() {
        val config = G2OrchestratorService.instance?.configManager ?: G2ConfigManager(this)

        // MODE 0: Toggle Auto-Boot on Power-on / Reboot (Headless Box)
        binding.btnToggleAutoBoot.setOnClickListener {
            config.isAutoStartBoot = !config.isAutoStartBoot
            if (config.isAutoStartBoot) {
                AppLogger.s("SYSTEM", "Đã BẬT Tự khởi động cùng hệ thống (Auto-Boot khi bật nguồn)")
                Toast.makeText(this, "Tự khởi động khi bật nguồn: ĐÃ BẬT", Toast.LENGTH_SHORT).show()
            } else {
                AppLogger.w("SYSTEM", "Đã TẮT Tự khởi động cùng hệ thống")
                Toast.makeText(this, "Tự khởi động khi bật nguồn: ĐÃ TẮT", Toast.LENGTH_SHORT).show()
            }
            updateModeButtons(config)
        }

        // MODE 1: Toggle 24/7 Always-On
        binding.btnToggle247.setOnClickListener {
            config.isAlwaysOn247 = !config.isAlwaysOn247
            if (config.isAlwaysOn247) {
                G2OrchestratorService.instance?.acquireLocks()
                AppLogger.s("SYSTEM", "Đã BẬT Chế độ 24/7 (Khóa CPU & Wi-Fi không bao giờ ngủ)")
                Toast.makeText(this, "Chế độ 24/7: ĐÃ BẬT", Toast.LENGTH_SHORT).show()
            } else {
                G2OrchestratorService.instance?.releaseLocks()
                AppLogger.w("SYSTEM", "Đã TẮT Chế độ 24/7 (Cho phép box ngủ khi tắt màn hình)")
                Toast.makeText(this, "Chế độ 24/7: ĐÃ TẮT", Toast.LENGTH_SHORT).show()
            }
            updateModeButtons(config)
        }

        // MODE 2: Toggle Watchdog Auto-Restart on stall
        binding.btnToggleWatchdog.setOnClickListener {
            config.isWatchdogAutoRecover = !config.isWatchdogAutoRecover
            if (config.isWatchdogAutoRecover) {
                AppLogger.s("WATCHDOG", "Đã BẬT Chế độ Tự Restart khi luồng bị nghẽn (Auto-Recovery)")
                Toast.makeText(this, "Tự Restart khi nghẽn: ĐÃ BẬT", Toast.LENGTH_SHORT).show()
            } else {
                AppLogger.w("WATCHDOG", "Đã TẮT Chế độ Tự Restart khi nghẽn")
                Toast.makeText(this, "Tự Restart khi nghẽn: ĐÃ TẮT", Toast.LENGTH_SHORT).show()
            }
            updateModeButtons(config)
        }

        // MODE 3: Toggle Hub Power (Start / Stop Hub entirely to release FPT Box)
        binding.btnToggleHubPower.setOnClickListener {
            val service = G2OrchestratorService.instance
            if (config.isHubEnabled) {
                // Currently running -> STOP completely
                service?.stopHubEntirely()
                Toast.makeText(this, "ĐÃ TẮT TRẠM PHÁT - FPT Box giải phóng 100% RAM!", Toast.LENGTH_LONG).show()
            } else {
                // Currently stopped -> START
                service?.startHubEntirely()
                Toast.makeText(this, "ĐÃ BẬT LẠI TRẠM PHÁT ACESTREAM!", Toast.LENGTH_SHORT).show()
            }
            updateModeButtons(config)
        }

        // Test Stream (Default or custom Infohash)
        binding.btnTestStream.setOnClickListener {
            val service = G2OrchestratorService.instance
            if (service == null || !config.isHubEnabled) {
                Toast.makeText(this, "Trạm phát đang tắt. Vui lòng bấm BẬT TRẠM trước!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val inputHash = binding.etTestInfohash.text?.toString()?.trim() ?: ""
            val targetHash = if (inputHash.isNotEmpty()) inputHash else config.defaultChannelId
            val isCustom = inputHash.isNotEmpty() && inputHash != TEST_INFOHASH
            val testLabel = if (isCustom) "Infohash ${targetHash.take(8)}..." else "Luồng mặc định"

            // Ghi nhớ link cũ / mặc định vào SharedPreferences để bảo lưu khi reboot
            config.defaultChannelId = targetHash
            config.defaultSourceType = "infohash"
            AppLogger.i("CONFIG", "💾 Đã ghi nhớ luồng phát: $targetHash (Bảo lưu khi khởi động lại)")

            Toast.makeText(this, "Đang nạp $testLabel...", Toast.LENGTH_SHORT).show()
            binding.tvActiveChannel.text = "Đang nạp $testLabel..."
            AppLogger.i("TEST", "👉 Kiểm tra luồng ($testLabel): $targetHash")

            scope.launch(Dispatchers.IO) {
                val ok = service.proxyServer?.prewarmStream(targetHash, "infohash", persistent = true) ?: false
                launch(Dispatchers.Main) {
                    if (ok) {
                        Toast.makeText(this@MainActivity, "$testLabel đã sẵn sàng!", Toast.LENGTH_SHORT).show()
                        AppLogger.s("TEST", "🟢 $testLabel đã sẵn sàng phát và được bảo lưu khi reboot!")
                    } else {
                        Toast.makeText(this@MainActivity, "Chưa lấy được luồng từ Engine (xem log bên dưới)", Toast.LENGTH_LONG).show()
                        AppLogger.e("TEST", "🔴 Không thể lấy luồng $testLabel từ Engine. Xem chi tiết trong log.")
                    }
                }
            }
        }

        // Force Restart Hub
        binding.btnRestartHub.setOnClickListener {
            Toast.makeText(this, "Đang cưỡng bức khởi động lại Trạm & Engine...", Toast.LENGTH_SHORT).show()
            AppLogger.i("SYSTEM", "👉 Người dùng bấm nút: Khởi động lại ngay (Force Restart)")
            scope.launch(Dispatchers.IO) {
                val service = G2OrchestratorService.instance
                service?.stopHubEntirely()
                kotlinx.coroutines.delay(1500)
                launch(Dispatchers.Main) {
                    service?.startHubEntirely()
                    Toast.makeText(this@MainActivity, "Trạm phát đã được làm mới hoàn toàn", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Hide to Background (Home)
        binding.btnHideBackground.setOnClickListener {
            Toast.makeText(this, "Trạm tiếp tục chạy ngầm cổng 8000 phục vụ gia đình", Toast.LENGTH_SHORT).show()
            AppLogger.i("SYSTEM", "Chuyển giao diện về nền (Chạy ngầm 24/7)")
            moveTaskToBack(true)
        }

        // Focus animation for Android TV Remote D-Pad navigation
        val interactiveViews = listOf(
            binding.btnToggleAutoBoot,
            binding.btnToggle247,
            binding.btnToggleWatchdog,
            binding.btnToggleHubPower,
            binding.etTestInfohash,
            binding.btnTestStream,
            binding.btnRestartHub,
            binding.btnHideBackground,
            binding.btnClearLog
        )
        for (v in interactiveViews) {
            v.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).start()
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                }
            }
        }

        // Focus first button for TV Remote
        binding.btnTestStream.requestFocus()
        updateModeButtons(config)
    }

    private fun updateModeButtons(config: G2ConfigManager) {
        if (config.isAutoStartBoot) {
            binding.btnToggleAutoBoot.text = "⚡ Tự khởi động khi bật nguồn: ĐANG BẬT"
            binding.btnToggleAutoBoot.backgroundTintList = ColorStateList.valueOf(0xFF059669.toInt())
        } else {
            binding.btnToggleAutoBoot.text = "⚪ Tự khởi động khi bật nguồn: ĐÃ TẮT"
            binding.btnToggleAutoBoot.backgroundTintList = ColorStateList.valueOf(0xFF334155.toInt())
        }

        if (config.isAlwaysOn247) {
            binding.btnToggle247.text = "🟢 Chế độ 24/7: ĐANG BẬT (Không ngủ)"
            binding.btnToggle247.backgroundTintList = ColorStateList.valueOf(0xFF0F766E.toInt())
        } else {
            binding.btnToggle247.text = "⚪ Chế độ 24/7: ĐÃ TẮT (Tiết kiệm điện)"
            binding.btnToggle247.backgroundTintList = ColorStateList.valueOf(0xFF334155.toInt())
        }

        if (config.isWatchdogAutoRecover) {
            binding.btnToggleWatchdog.text = "🛡️ Tự Restart khi nghẽn: ĐANG BẬT"
            binding.btnToggleWatchdog.backgroundTintList = ColorStateList.valueOf(0xFF1D4ED8.toInt())
        } else {
            binding.btnToggleWatchdog.text = "⚪ Tự Restart khi nghẽn: ĐÃ TẮT"
            binding.btnToggleWatchdog.backgroundTintList = ColorStateList.valueOf(0xFF334155.toInt())
        }

        if (config.isHubEnabled) {
            binding.btnToggleHubPower.text = "🛑 TẮT TRẠM (Giải phóng FPT Box)"
            binding.btnToggleHubPower.backgroundTintList = ColorStateList.valueOf(0xFF991B1B.toInt())
            binding.tvHubStatusBadge.text = "🟢 ĐANG HOẠT ĐỘNG"
            binding.tvHubStatusBadge.setBackgroundColor(0xFF166534.toInt())
            binding.tvHubStatusBadge.setTextColor(0xFF4ADE80.toInt())
        } else {
            binding.btnToggleHubPower.text = "🚀 BẬT LẠI TRẠM PHÁT"
            binding.btnToggleHubPower.backgroundTintList = ColorStateList.valueOf(0xFF166534.toInt())
            binding.tvHubStatusBadge.text = "🔴 TRẠM ĐÃ TẮT (RẢNH RỖI 100%)"
            binding.tvHubStatusBadge.setBackgroundColor(0xFF7F1D1D.toInt())
            binding.tvHubStatusBadge.setTextColor(0xFFFCA5A5.toInt())
        }
    }

    fun showEngineStatus(ip: String, port: Int, status: String) {
        binding.tvServerUrl.text = "http://$ip:$port"
        binding.tvHubStatusBadge.text = "🟢 $status".uppercase()
    }

    private fun updateDashboardMetrics() {
        val service = G2OrchestratorService.instance
        val config = service?.configManager ?: G2ConfigManager(this)
        val localIp = getLocalIpAddress()
        val port = config.proxyPort

        binding.tvServerUrl.text = "http://$localIp:$port"
        binding.tvBrowserLogHint.text = "Xem trên web: http://$localIp:$port/log"
        if (binding.etTestInfohash.text.isNullOrEmpty()) {
            binding.etTestInfohash.hint = "Ghi nhớ boot: ${config.defaultChannelId.take(12)}... (nhập mới để đổi)"
        }

        if (config.isHubEnabled && service != null && service.proxyServer != null) {
            binding.tvEngineStatus.text = "Sẵn sàng (Port 6878/62062)"
            binding.tvEngineStatus.setTextColor(0xFF4ADE80.toInt())

            val activeStream = service.proxyServer?.latestActiveStream
            if (activeStream != null && (activeStream.clientCount > 0 || activeStream.speedKbps > 0)) {
                val chDisplay = if (activeStream.channelId == TEST_INFOHASH) {
                    "Luồng mặc định (${activeStream.channelId.take(8)}...)"
                } else {
                    "Infohash: ${activeStream.channelId.take(12)}...${activeStream.channelId.takeLast(6)}"
                }
                binding.tvActiveChannel.text = chDisplay
                binding.tvClientCount.text = "${activeStream.clientCount} thiết bị (Clients)"
                binding.tvBitrate.text = "${activeStream.speedKbps} KB/s (~${String.format(java.util.Locale.US, "%.1f", activeStream.speedKbps * 8 / 1024.0)} Mbps)"
                binding.tvPeers.text = "${activeStream.peers} Peers"
            } else if (activeStream != null) {
                binding.tvActiveChannel.text = "${activeStream.channelId.take(12)}... (Đang chờ kết nối)"
                binding.tvClientCount.text = "0 thiết bị"
                binding.tvBitrate.text = "${activeStream.speedKbps} KB/s"
                binding.tvPeers.text = "${activeStream.peers} Peers"
            } else {
                binding.tvActiveChannel.text = "Sẵn sàng (Chờ kết nối)"
                binding.tvClientCount.text = "0 thiết bị"
                binding.tvBitrate.text = "0 KB/s"
                binding.tvPeers.text = "0 Peers"
            }
        } else if (!config.isHubEnabled) {
            binding.tvEngineStatus.text = "Đã tắt (FPT Box được giải phóng RAM)"
            binding.tvEngineStatus.setTextColor(0xFF94A3B8.toInt())
            binding.tvActiveChannel.text = "Trạm đang tắt"
            binding.tvClientCount.text = "0 thiết bị"
            binding.tvBitrate.text = "0 KB/s"
            binding.tvPeers.text = "0 Peers"
        } else {
            binding.tvHubStatusBadge.text = "🟡 ĐANG KHỞI ĐỘNG..."
            binding.tvHubStatusBadge.setBackgroundColor(0xFF854D0E.toInt())
            binding.tvHubStatusBadge.setTextColor(0xFFFEF08A.toInt())
            binding.tvEngineStatus.text = "Đang khởi tạo Engine..."
            binding.tvEngineStatus.setTextColor(0xFFCBD5E1.toInt())
        }

        updateModeButtons(config)
    }

    private fun getLocalIpAddress(): String {
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
}
