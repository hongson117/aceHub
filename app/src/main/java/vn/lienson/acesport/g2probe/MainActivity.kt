package vn.lienson.acesport.g2probe

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import vn.lienson.acesport.g2probe.databinding.ActivityMainBinding
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AceHubMain"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var configManager: G2ConfigManager
    private var pollJob: Job? = null
    private var deviceIp: String = "127.0.0.1"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configManager = G2ConfigManager(this)
        deviceIp = resolveDeviceIp()
        setupUI()
        setupListeners()
        startAceHubService()
    }

    override fun onResume() {
        super.onResume()
        startPollingStatus()
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Keep server running in background when pressing Back
        moveTaskToBack(true)
    }

    private fun setupUI() {
        binding.tvServerAddress.text = "http://$deviceIp:8000"
        binding.tvGuideLinkGetstream.text = "http://$deviceIp:8000/ace/getstream?infohash={mã_kênh}"
        binding.tvGuideLinkMp4.text = "http://$deviceIp:8000/infohash/{mã_kênh}/stream.mp4"
        updateTailscaleUI(configManager.isKeepTailscale)
        binding.btnExitApp.requestFocus()
    }

    private fun updateTailscaleUI(enabled: Boolean) {
        if (enabled) {
            binding.tvTailscaleKeepStatus.text = "ĐANG BẬT (Tự động giữ 24/7)"
            binding.tvTailscaleKeepStatus.setTextColor(Color.parseColor("#4ADE80"))
            binding.btnToggleTailscale.text = "Tự Giữ Tailscale: BẬT"
            binding.btnToggleTailscale.setBackgroundColor(Color.parseColor("#059669"))
        } else {
            binding.tvTailscaleKeepStatus.text = "ĐÃ TẮT (Không can thiệp)"
            binding.tvTailscaleKeepStatus.setTextColor(Color.parseColor("#94A3B8"))
            binding.btnToggleTailscale.text = "Tự Giữ Tailscale: TẮT"
            binding.btnToggleTailscale.setBackgroundColor(Color.parseColor("#334155"))
        }
    }

    private fun setupListeners() {
        binding.btnStopStream.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val conn = URL("http://127.0.0.1:8000/stop").openConnection() as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Đã gửi lệnh dừng luồng!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Lỗi dừng luồng: ${e.message}")
                }
            }
        }

        binding.btnRestartServer.setOnClickListener {
            Toast.makeText(this, "Đang khởi động lại dịch vụ AceHub...", Toast.LENGTH_SHORT).show()
            stopService(Intent(this, G2OrchestratorService::class.java))
            Handler(Looper.getMainLooper()).postDelayed({
                startAceHubService()
                Toast.makeText(this, "AceHub đã khởi động lại!", Toast.LENGTH_SHORT).show()
            }, 1500)
        }

        binding.btnToggleTailscale.setOnClickListener {
            val newState = !configManager.isKeepTailscale
            configManager.isKeepTailscale = newState
            updateTailscaleUI(newState)
            if (newState) {
                G2OrchestratorService.instance?.startTailscaleWatchdog()
                Toast.makeText(this, "Đã BẬT tự động giữ kết nối Tailscale 24/7!", Toast.LENGTH_SHORT).show()
            } else {
                G2OrchestratorService.instance?.stopTailscaleWatchdog()
                Toast.makeText(this, "Đã TẮT tự động giữ kết nối Tailscale!", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnExitApp.setOnClickListener {
            // Put activity in background so server keeps running 24/7
            moveTaskToBack(true)
        }
    }

    private fun startAceHubService() {
        val serviceIntent = Intent(this, G2OrchestratorService::class.java)
        val controlIntent = Intent(this, vn.lienson.acesport.g2probe.control.AceHubControlService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
                startForegroundService(controlIntent)
            } else {
                startService(serviceIntent)
                startService(controlIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Không thể khởi chạy Services: ${e.message}", e)
        }
    }

    private fun startPollingStatus() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val conn = URL("http://127.0.0.1:8000/status").openConnection() as HttpURLConnection
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2000
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().readText()
                        val json = JSONObject(body)
                        withContext(Dispatchers.Main) {
                            updateStatusUI(json)
                        }
                    }
                    conn.disconnect()
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) {
                        binding.tvStatusBadge.text = "KHỞI ĐỘNG CỔNG 8000..."
                        binding.tvStatusBadge.setTextColor(Color.parseColor("#F59E0B"))
                    }
                }
                delay(2000)
            }
        }
    }

    private fun updateStatusUI(json: JSONObject) {
        if (json.has("keep_tailscale")) {
            val keepTs = json.optBoolean("keep_tailscale", false)
            updateTailscaleUI(keepTs)
        }
        val status = json.optString("status", "STANDBY")
        if (status.equals("ACTIVE", ignoreCase = true)) {
            val ch = json.optString("channel", "")
            val peers = json.optInt("peers", 0)
            val speed = json.optInt("speed_kbps", 0)
            val clients = json.optInt("clients", 0)

            binding.tvStatusBadge.text = "ĐANG PHÁT (ACTIVE)"
            binding.tvStatusBadge.setTextColor(Color.parseColor("#4ADE80"))
            binding.tvCurrentChannel.text = if (ch.length > 20) "${ch.take(16)}...${ch.takeLast(4)}" else ch
            binding.tvP2pStats.text = "$peers Peers • $speed KB/s"
            binding.tvClientCount.text = "$clients Thiết bị"
        } else {
            val defCh = json.optString("default_channel", "")
            binding.tvStatusBadge.text = "SẴN SÀNG (STANDBY)"
            binding.tvStatusBadge.setTextColor(Color.parseColor("#38BDF8"))
            binding.tvCurrentChannel.text = if (defCh.isNotEmpty()) "Kênh chờ: ${defCh.take(12)}..." else "Không có (Chế độ chờ)"
            binding.tvP2pStats.text = "0 Peers • 0 KB/s"
            binding.tvClientCount.text = "0 Client(s)"
        }
    }

    private fun resolveDeviceIp(): String {
        try {
            val candidateIps = mutableListOf<String>()
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
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
}
