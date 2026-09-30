package vn.lienson.acesport.g2probe

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest

class AceApiClient(
    private val host: String = "127.0.0.1",
    private val apiPort: Int = 62062,
    private val onStats: ((peers: Int, speedKbps: Long, downloaded: Long) -> Unit)? = null
) {
    companion object {
        private const val TAG = "AceApiClient"
        private const val PRODUCT_KEY = "n51LvQoTlJzNGaFxseRK-uvnvX-sD4Vm5Axwmc4UcoD-jruxmKsuJaH0eVgE"
    }

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null
    private var keepAliveJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class StartResult(
        val playbackUrl: String,
        val infohash: String,
        val rawResponse: String
    )

    @Synchronized
    fun isConnected(): Boolean = socket?.isConnected == true && socket?.isClosed == false

    suspend fun startStream(sourceType: String, value: String): StartResult = withContext(Dispatchers.IO) {
        close() // Close any existing session on this client instance

        Log.d(TAG, "Connecting to AceStream Telnet API at $host:$apiPort")
        AppLogger.i("TELNET", "Đang mở Socket kết nối tới Engine tại $host:$apiPort...")
        val s = Socket(host, apiPort)
        s.soTimeout = 60000
        socket = s
        val r = BufferedReader(InputStreamReader(s.getInputStream()))
        val w = PrintWriter(OutputStreamWriter(s.getOutputStream()), true)
        reader = r
        writer = w

        // 1. Handshake HELLOBG
        AppLogger.i("TELNET", "Gửi bắt tay HELLOBG version=4...")
        w.print("HELLOBG version=4\r\n")
        w.flush()

        var authenticated = false
        var engineHttpPort = 6878

        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline && !authenticated) {
            val line = r.readLine() ?: break
            Log.d(TAG, "RECV [AUTH]: $line")
            if (line.startsWith("HELLOTS")) {
                val keyMatch = Regex("key=([^\\s]+)").find(line)
                val portMatch = Regex("http_port=(\\d+)").find(line)
                if (portMatch != null) {
                    engineHttpPort = portMatch.groupValues[1].toIntOrNull() ?: 6878
                }
                if (keyMatch != null) {
                    val reqKey = keyMatch.groupValues[1]
                    val readyKey = computeReadyKey(reqKey, PRODUCT_KEY)
                    Log.d(TAG, "Sending READY key=$readyKey")
                    w.print("READY key=$readyKey\r\n")
                    w.flush()
                }
            } else if (line.startsWith("AUTH")) {
                authenticated = true
                Log.i(TAG, "AceStream Telnet API Authenticated successfully: $line")
                AppLogger.s("TELNET", "Xác thực Engine thành công! (Auth 0, 0 Ads)")
                w.print("STOP\r\n")
                w.print("STOPDL\r\n")
                w.print("SETOPTIONS use_stop_notifications=1\r\n")
                w.flush()
                Thread.sleep(200)
                break
            } else if (line.startsWith("NOTREADY")) {
                AppLogger.e("TELNET", "Engine trả về NOTREADY trong quá trình xác thực")
                throw IllegalStateException("Engine returned NOTREADY during auth")
            }
        }

        if (!authenticated) {
            close()
            AppLogger.e("TELNET", "Xác thực thất bại với AceStream Engine qua Telnet")
            throw IllegalStateException("Failed to authenticate with AceStream Telnet API")
        }

        // 2. LoadAsync
        val isInfohash = sourceType == "infohash"
        val loadCmd = if (isInfohash) {
            "LOADASYNC 0 INFOHASH $value 0 0 0\r\n"
        } else {
            "LOADASYNC 0 PID $value\r\n"
        }
        w.print(loadCmd)
        w.flush()

        var loaded = false
        var canonicalInfohash = if (isInfohash) value else ""
        val loadDeadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < loadDeadline && !loaded) {
            val line = r.readLine() ?: break
            Log.d(TAG, "RECV [LOAD]: $line")
            if (line.startsWith("LOADRESP")) {
                if (line.contains("\"status\": 100") || line.contains("Cannot load transport file") || line.contains("\"status\": 0")) {
                    throw IllegalStateException("Cannot load transport file: $line")
                }
                val hashMatch = Regex("\"infohash\"\\s*:\\s*\"([a-fA-F0-9]{40})\"").find(line)
                if (hashMatch != null) {
                    canonicalInfohash = hashMatch.groupValues[1]
                    Log.i(TAG, "Resolved canonical infohash: $canonicalInfohash")
                }
                loaded = true
                break
            }
        }

        // 3. Start: Canonicalize to START INFOHASH <hash> 0 0 0 0 output_format=http
        val targetHash = if (canonicalInfohash.isNotEmpty()) canonicalInfohash else value
        val startCmd = if (targetHash.length == 40) {
            "START INFOHASH $targetHash 0 0 0 0 output_format=http\r\n"
        } else {
            "START PID $value 0 output_format=http\r\n"
        }
        Log.i(TAG, "Sending start command: ${startCmd.trim()}")
        AppLogger.i("TELNET", "Gửi lệnh kích hoạt luồng: ${startCmd.trim()}")
        w.print(startCmd)
        w.flush()

        var playbackUrl: String? = null
        var infohash = ""
        val startDeadline = System.currentTimeMillis() + 60000
        while (System.currentTimeMillis() < startDeadline && playbackUrl == null) {
            val line = r.readLine() ?: break
            Log.d(TAG, "RECV [START]: $line")
            if (line.startsWith("START")) {
                val urlMatch = Regex("url=([^\\s]+)").find(line)
                val hashMatch = Regex("infohash=([^\\s]+)").find(line)
                if (urlMatch != null) {
                    val encodedUrl = urlMatch.groupValues[1]
                    playbackUrl = URLDecoder.decode(encodedUrl, "UTF-8")
                }
                if (hashMatch != null) {
                    infohash = hashMatch.groupValues[1]
                }
                break
            } else if (line.startsWith("EVENT showdialog")) {
                close()
                AppLogger.e("TELNET", "Engine yêu cầu Premium/Dialog: $line")
                throw IllegalStateException("Engine returned premium dialog: $line")
            } else if (line.startsWith("EVENT download_stopped")) {
                close()
                AppLogger.e("TELNET", "Engine dừng nạp: $line")
                throw IllegalStateException("Engine stopped download: $line")
            }
        }

        if (playbackUrl == null) {
            close()
            AppLogger.e("TELNET", "Không nhận được phản hồi START từ AceStream Engine")
            throw IllegalStateException("Did not receive START response from AceStream")
        }

        Log.i(TAG, "Stream started successfully: $playbackUrl")
        AppLogger.s("TELNET", "🟢 Nhận luồng thành công: $playbackUrl")

        // 4. Background Keepalive & Stats loop
        s.soTimeout = 0 // Remove timeout for continuous read
        var lastLoggedStatTime = 0L
        keepAliveJob = scope.launch {
            try {
                while (isActive) {
                    val line = r.readLine() ?: break
                    if (line.startsWith("STATUS") && line.contains("main:dl")) {
                        val parts = line.split(";")
                        if (parts.size >= 9) {
                            val speed = parts[3].toLongOrNull() ?: 0L
                            val peers = parts[4].toIntOrNull() ?: 0
                            val downloaded = parts[8].toLongOrNull() ?: 0L
                            onStats?.invoke(peers, speed, downloaded)

                            val now = System.currentTimeMillis()
                            if (now - lastLoggedStatTime > 4000L && (peers > 0 || speed > 0)) {
                                lastLoggedStatTime = now
                                AppLogger.i("SWARM", "👥 P2P Swarm: $peers Peers | Tốc độ tải: $speed KB/s (~${String.format(java.util.Locale.US, "%.1f", speed * 8 / 1024.0)} Mbps)")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Keepalive loop closed: ${e.message}")
            }
        }

        StartResult(playbackUrl, infohash, "OK")
    }

    @Synchronized
    fun close() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        try {
            writer?.print("STOP\r\n")
            writer?.print("STOPDL\r\n")
            writer?.flush()
        } catch (_: Exception) {}
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        reader = null
        writer = null
    }

    private fun computeReadyKey(reqKey: String, productKey: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest((reqKey + productKey).toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        val prefix = productKey.substringBefore("-")
        return "$prefix-$hex"
    }
}
