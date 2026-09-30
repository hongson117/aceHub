package vn.lienson.acesport.g2probe

import android.content.Intent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.i(TAG, "Starting AceStream Hub (Headless Stream Proxy)...")

        // 1. Start Hub Service as Foreground Service
        startOrchestratorService()

        // 2. Setup Remote D-Pad Buttons
        setupControls()

        // 3. Initial Display
        updateDashboardMetrics()
    }

    override fun onResume() {
        super.onResume()
        if (!isUpdating) {
            isUpdating = true
            mainHandler.post(statusUpdateRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        isUpdating = false
        mainHandler.removeCallbacks(statusUpdateRunnable)
    }

    private fun startOrchestratorService() {
        val serviceIntent = Intent(this, G2OrchestratorService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun setupControls() {
        // Test Stream (Eleven Sports 1)
        binding.btnTestStream.setOnClickListener {
            val service = G2OrchestratorService.instance
            if (service == null) {
                Toast.makeText(this, "Dịch vụ Hub đang khởi động, vui lòng chờ...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "Đang kết nối luồng thử nghiệm Eleven Sports 1...", Toast.LENGTH_SHORT).show()
            binding.tvActiveChannel.text = "Đang kết nối Eleven Sports 1..."
            scope.launch(Dispatchers.IO) {
                val ok = service.proxyServer?.prewarmStream(TEST_INFOHASH, "infohash", persistent = true) ?: false
                launch(Dispatchers.Main) {
                    if (ok) {
                        Toast.makeText(this@MainActivity, "Luồng thử nghiệm sẵn sàng!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Chưa lấy được luồng từ Engine", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Restart Hub
        binding.btnRestartHub.setOnClickListener {
            Toast.makeText(this, "Đang khởi động lại AceStream Hub...", Toast.LENGTH_SHORT).show()
            scope.launch(Dispatchers.IO) {
                val service = G2OrchestratorService.instance
                service?.proxyServer?.stop()
                service?.engineManager?.unbind()
                launch(Dispatchers.Main) {
                    val restartIntent = Intent(this@MainActivity, G2OrchestratorService::class.java)
                    ContextCompat.startForegroundService(this@MainActivity, restartIntent)
                    Toast.makeText(this@MainActivity, "Hub đã được khởi động lại", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Hide to Background (Home)
        binding.btnHideBackground.setOnClickListener {
            Toast.makeText(this, "Hub tiếp tục chạy ngầm cổng 8000 phục vụ gia đình", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        }

        // Focus first button for TV Remote
        binding.btnTestStream.requestFocus()
    }

    fun showEngineStatus(ip: String, port: Int, status: String) {
        binding.tvServerUrl.text = "http://$ip:$port"
        binding.tvHubStatusBadge.text = "🟢 $status".uppercase()
    }

    private fun updateDashboardMetrics() {
        val localIp = getLocalIpAddress()
        val port = G2OrchestratorService.instance?.configManager?.proxyPort ?: 8000

        binding.tvServerUrl.text = "http://$localIp:$port"
        binding.tvSampleUrl.text = "http://$localIp:$port/?infohash=$TEST_INFOHASH"

        val service = G2OrchestratorService.instance
        if (service != null && service.proxyServer != null) {
            binding.tvHubStatusBadge.text = "🟢 ĐANG HOẠT ĐỘNG"
            binding.tvHubStatusBadge.setBackgroundColor(0xFF166534.toInt())
            binding.tvHubStatusBadge.setTextColor(0xFF4ADE80.toInt())
            binding.tvEngineStatus.text = "Sẵn sàng (Port 6878/62062)"
            binding.tvEngineStatus.setTextColor(0xFF4ADE80.toInt())

            val activeStream = service.proxyServer?.latestActiveStream
            if (activeStream != null && activeStream.clientCount > 0 || (activeStream != null && activeStream.speedKbps > 0)) {
                val chDisplay = if (activeStream.channelId == TEST_INFOHASH) {
                    "Eleven Sports 1 HD (4K/FHD)"
                } else {
                    "${activeStream.channelId.take(12)}...${activeStream.channelId.takeLast(6)}"
                }
                binding.tvActiveChannel.text = chDisplay
                binding.tvClientCount.text = "${activeStream.clientCount} thiết bị (Clients)"
                binding.tvBitrate.text = "${activeStream.speedKbps} KB/s (~${(activeStream.speedKbps * 8 / 1024.0).let { String.format("%.1f", it) }} Mbps)"
                binding.tvPeers.text = "${activeStream.peers} Peers"
            } else if (activeStream != null) {
                binding.tvActiveChannel.text = "${activeStream.channelId.take(12)}... (Đang chờ client)"
                binding.tvClientCount.text = "0 thiết bị"
                binding.tvBitrate.text = "${activeStream.speedKbps} KB/s"
                binding.tvPeers.text = "${activeStream.peers} Peers"
            } else {
                binding.tvActiveChannel.text = "Sẵn sàng (Chờ kết nối)"
                binding.tvClientCount.text = "0 thiết bị"
                binding.tvBitrate.text = "0 KB/s"
                binding.tvPeers.text = "0 Peers"
            }
        } else {
            binding.tvHubStatusBadge.text = "🟡 ĐANG KHỞI ĐỘNG..."
            binding.tvHubStatusBadge.setBackgroundColor(0xFF854D0E.toInt())
            binding.tvHubStatusBadge.setTextColor(0xFFFEF08A.toInt())
            binding.tvEngineStatus.text = "Đang khởi tạo Engine..."
            binding.tvEngineStatus.setTextColor(0xFFCBD5E1.toInt())
        }
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
