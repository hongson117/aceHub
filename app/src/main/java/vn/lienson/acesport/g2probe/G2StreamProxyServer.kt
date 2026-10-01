package vn.lienson.acesport.g2probe

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

class G2StreamProxyServer(
    private val port: Int = 8000,
    private val apiPort: Int = 62062,
    private val configManager: G2ConfigManager? = null,
    private val tokenProvider: (() -> String?)? = null,
    private val onStreamStateChanged: ((channel: String, peers: Int, speedKbps: Long) -> Unit)? = null
) {
    companion object {
        private const val TAG = "G2StreamProxy"
        private const val GRACE_PERIOD_MS = 5000L
    }

    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    data class ActiveStream(
        val channelId: String,
        val sourceType: String,
        val client: AceApiClient?,
        val playbackUrl: String,
        val commandUrl: String? = null,
        val statUrl: String? = null,
        var peers: Int = 0,
        var speedKbps: Long = 0L,
        var downloaded: Long = 0L,
        var clientCount: Int = 0,
        var isPersistent: Boolean = false,
        var expireJob: Job? = null,
        var dummyReaderJob: Job? = null,
        var statsJob: Job? = null
    )

    private val streamMutex = kotlinx.coroutines.sync.Mutex()
    private val streamMap = ConcurrentHashMap<String, ActiveStream>()
    @Volatile var latestActiveStream: ActiveStream? = null
        private set

    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            try {
                val channel = java.nio.channels.ServerSocketChannel.open()
                channel.socket().reuseAddress = true
                channel.socket().bind(java.net.InetSocketAddress(java.net.Inet4Address.getByAddress(byteArrayOf(0, 0, 0, 0)), port), 50)
                serverSocket = channel.socket()
                Log.i(TAG, "G2StreamProxyServer started and listening on 0.0.0.0:$port (AF_INET IPv4)")
                AppLogger.s("PROXY", "Trạm phát Proxy cổng $port đã sẵn sàng lắng nghe trên 0.0.0.0:$port (IPv4)")

                while (isActive && isRunning) {
                    val clientSock = serverSocket?.accept() ?: break
                    scope.launch {
                        handleClient(clientSock)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Server socket error: ${e.message}", e)
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        streamMap.values.forEach {
            it.dummyReaderJob?.cancel()
            it.expireJob?.cancel()
            it.statsJob?.cancel()
            it.client?.close()
            if (!it.commandUrl.isNullOrEmpty()) {
                val cUrl = it.commandUrl
                scope.launch(Dispatchers.IO) {
                    try {
                        val stopConn = URL("${cUrl}/stop").openConnection() as HttpURLConnection
                        stopConn.connectTimeout = 2000
                        stopConn.readTimeout = 2000
                        stopConn.inputStream.read()
                    } catch (_: Exception) {}
                }
            }
        }
        streamMap.clear()
        latestActiveStream = null
        scope.cancel()
    }

    suspend fun prewarmStream(channelId: String, sourceType: String, persistent: Boolean = false): Boolean {
        AppLogger.i("PROXY", "⚡ Bắt đầu yêu cầu nạp luồng: ${channelId.take(12)}... ($sourceType)")
        return try {
            val stream = getOrCreateStream(channelId, sourceType, persistent)
            if (stream != null) {
                stream.isPersistent = persistent
                if (stream.clientCount == 0 && persistent) {
                    startDummyReader(stream)
                }
                Log.i(TAG, "Prewarmed channel $channelId successfully (persistent=$persistent)")
                AppLogger.s("PROXY", "🟢 Luồng sẵn sàng tại: ${stream.playbackUrl}")
                true
            } else {
                Log.w(TAG, "Failed to prewarm channel $channelId")
                AppLogger.e("PROXY", "🔴 Không thể khởi tạo luồng từ Engine cho kênh: ${channelId.take(12)}...")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Prewarm exception: ${e.message}")
            AppLogger.e("PROXY", "🔴 Lỗi ngoại lệ khi nạp luồng: ${e.message}", e)
            false
        }
    }

    private fun startDummyReader(stream: ActiveStream) {
        stream.dummyReaderJob?.cancel()
        stream.dummyReaderJob = scope.launch(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                Log.i(TAG, "Dummy hot-stream reader starting for ${stream.channelId} to keep P2P swarm warm...")
                val url = URL(stream.playbackUrl)
                conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                val inStream = conn.inputStream
                val buffer = ByteArray(32768)
                while (isActive && stream.clientCount == 0 && stream.isPersistent) {
                    val n = inStream.read(buffer)
                    if (n == -1) break
                    delay(50) // smooth pacing so it keeps engine feeding chunks
                }
            } catch (e: Exception) {
                Log.d(TAG, "Dummy reader ended: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
    }

    private suspend fun handleClient(clientSock: Socket) = withContext(Dispatchers.IO) {
        try {
            clientSock.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(clientSock.getInputStream()))
            val out = clientSock.getOutputStream()

            val requestLine = reader.readLine() ?: return@withContext
            Log.d(TAG, "Incoming HTTP: $requestLine")

            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                sendHttpError(out, 400, "Bad Request")
                clientSock.close()
                return@withContext
            }

            if (parts[0] == "HEAD") {
                val headResp = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: video/mp2t\r\n" +
                        "Connection: close\r\n" +
                        "Access-Control-Allow-Origin: *\r\n\r\n"
                out.write(headResp.toByteArray(Charsets.UTF_8))
                out.flush()
                clientSock.close()
                return@withContext
            }

            if (parts[0] != "GET") {
                sendHttpError(out, 400, "Bad Request")
                clientSock.close()
                return@withContext
            }

            val rawUri = parts[1]

            // 1. Dashboard UI
            if (rawUri == "/" || rawUri == "/index.html" || rawUri == "/dashboard") {
                val html = renderDashboardHtml()
                sendHttpResponse(out, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
                clientSock.close()
                return@withContext
            }

            // 1b. Realtime Diagnostics Log Export
            if (rawUri == "/log" || rawUri == "/log.txt" || rawUri.startsWith("/api/log")) {
                val text = AppLogger.getFullLogText()
                sendHttpResponse(out, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))
                clientSock.close()
                return@withContext
            }

            // 2. Health & Status JSON
            if (rawUri.startsWith("/status") || rawUri.startsWith("/proxy/health")) {
                val current = latestActiveStream
                val defId = configManager?.defaultChannelId ?: ""
                val alwaysHot = configManager?.isAlwaysHotStream ?: false
                val json = if (current != null) {
                    """{"status":"ACTIVE","channel":"${current.channelId}","source_type":"${current.sourceType}","peers":${current.peers},"speed_kbps":${current.speedKbps},"downloaded_bytes":${current.downloaded},"clients":${current.clientCount},"is_persistent":${current.isPersistent},"default_channel":"$defId","always_hot":$alwaysHot}"""
                } else {
                    """{"status":"IDLE","default_channel":"$defId","always_hot":$alwaysHot}"""
                }
                sendHttpResponse(out, "application/json", json.toByteArray(Charsets.UTF_8))
                clientSock.close()
                return@withContext
            }

            // 3. Prewarm Trigger API
            if (rawUri.startsWith("/prewarm")) {
                val queryParams = parseQueryParams(rawUri)
                val chId = queryParams["id"] ?: queryParams["infohash"] ?: queryParams["content_id"] ?: configManager?.defaultChannelId ?: ""
                val sType = queryParams["type"] ?: if (queryParams.containsKey("infohash") || chId.length == 40) "infohash" else "content_id"
                val persistent = queryParams["persistent"]?.toBoolean() ?: true

                if (chId.isNotEmpty()) {
                    configManager?.let { cfg ->
                        cfg.defaultChannelId = chId
                        cfg.defaultSourceType = sType
                    }
                    val ok = prewarmStream(chId, sType, persistent)
                    val resp = if (ok) """{"success":true,"message":"Channel $chId prewarmed successfully","saved":true}"""
                               else """{"success":false,"message":"Failed to prewarm channel"}"""
                    sendHttpResponse(out, "application/json", resp.toByteArray(Charsets.UTF_8))
                } else {
                    sendHttpError(out, 400, "Missing id or infohash parameter")
                }
                clientSock.close()
                return@withContext
            }

            // 4. Config API
            if (rawUri.startsWith("/config")) {
                val queryParams = parseQueryParams(rawUri)
                configManager?.let { cfg ->
                    queryParams["default_channel"]?.let { cfg.defaultChannelId = it }
                    queryParams["default_type"]?.let { cfg.defaultSourceType = it }
                    queryParams["always_hot"]?.let { cfg.isAlwaysHotStream = (it == "1" || it.equals("true", ignoreCase = true)) }
                    queryParams["auto_boot"]?.let { cfg.isAutoStartBoot = (it == "1" || it.equals("true", ignoreCase = true)) }
                }
                val resp = """{"success":true,"default_channel":"${configManager?.defaultChannelId}","default_type":"${configManager?.defaultSourceType}","always_hot":${configManager?.isAlwaysHotStream},"auto_boot":${configManager?.isAutoStartBoot}}"""
                sendHttpResponse(out, "application/json", resp.toByteArray(Charsets.UTF_8))
                clientSock.close()
                return@withContext
            }

            // 5. Stop API
            if (rawUri.startsWith("/stop")) {
                streamMap.values.forEach {
                    it.dummyReaderJob?.cancel()
                    it.expireJob?.cancel()
                    it.statsJob?.cancel()
                    it.client?.close()
                    if (!it.commandUrl.isNullOrEmpty()) {
                        val cUrl = it.commandUrl
                        scope.launch(Dispatchers.IO) {
                            try {
                                val stopConn = URL("${cUrl}/stop").openConnection() as HttpURLConnection
                                stopConn.connectTimeout = 2000
                                stopConn.readTimeout = 2000
                                stopConn.inputStream.read()
                            } catch (_: Exception) {}
                        }
                    }
                }
                streamMap.clear()
                latestActiveStream = null
                sendHttpResponse(out, "application/json", """{"success":true,"message":"All streams stopped"}""".toByteArray(Charsets.UTF_8))
                clientSock.close()
                return@withContext
            }

            // 6. Stream Dispatch: Parse /ace/getstream, /pid/..., /infohash/..., /stream/...
            var channelId = ""
            var sourceType = "content_id"

            if (rawUri.contains("?")) {
                val queryParams = parseQueryParams(rawUri)
                if (queryParams.containsKey("id")) {
                    channelId = queryParams["id"]!!
                    sourceType = "content_id"
                } else if (queryParams.containsKey("content_id")) {
                    channelId = queryParams["content_id"]!!
                    sourceType = "content_id"
                } else if (queryParams.containsKey("pid")) {
                    channelId = queryParams["pid"]!!
                    sourceType = "content_id"
                } else if (queryParams.containsKey("infohash")) {
                    channelId = queryParams["infohash"]!!
                    sourceType = "infohash"
                }
            } else if (rawUri.startsWith("/pid/")) {
                channelId = rawUri.substringAfter("/pid/").substringBefore("/").substringBefore("?")
                sourceType = "content_id"
            } else if (rawUri.startsWith("/infohash/")) {
                channelId = rawUri.substringAfter("/infohash/").substringBefore("/").substringBefore("?")
                sourceType = "infohash"
            } else if (rawUri.startsWith("/stream/")) {
                channelId = rawUri.substringAfter("/stream/").substringBefore("/").substringBefore("?")
                sourceType = if (channelId.length == 40) "infohash" else "content_id"
            }

            if (channelId.isEmpty()) {
                val def = configManager?.defaultChannelId ?: ""
                if (def.isNotEmpty()) {
                    channelId = def
                    sourceType = configManager?.defaultSourceType ?: "infohash"
                    Log.i(TAG, "Request without explicit channel, serving preserved stream: $channelId")
                } else {
                    sendHttpError(out, 400, "Missing id or infohash parameter")
                    clientSock.close()
                    return@withContext
                }
            }

            // AUTO-PERSIST: Keep the current/last stream as default channel so it is preserved across reboots!
            configManager?.let { cfg ->
                if (cfg.defaultChannelId != channelId) {
                    cfg.defaultChannelId = channelId
                    cfg.defaultSourceType = sourceType
                    AppLogger.i("CONFIG", "💾 Đã tự động ghi nhớ luồng cũ vào bộ nhớ: $channelId ($sourceType)")
                }
            }

            val isDefault = (channelId == configManager?.defaultChannelId)
            val stream = getOrCreateStream(channelId, sourceType, persistent = isDefault && (configManager?.isAlwaysHotStream == true))
            if (stream == null) {
                sendHttpError(out, 502, "Failed to start stream with AceStream Engine")
                clientSock.close()
                return@withContext
            }

            synchronized(stream) {
                stream.expireJob?.cancel()
                stream.expireJob = null
                stream.dummyReaderJob?.cancel()
                stream.dummyReaderJob = null
                stream.clientCount++
            }

            Log.i(TAG, "Streaming $channelId to client ${clientSock.inetAddress.hostAddress} (active clients=${stream.clientCount})...")

            // Send 200 OK headers
            val headerStr = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: video/mp2t\r\n" +
                    "Connection: close\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "\r\n"
            out.write(headerStr.toByteArray(Charsets.UTF_8))
            out.flush()

            // Stream data from raw playback URL
            clientSock.soTimeout = 0
            var sourceConn: HttpURLConnection? = null
            try {
                val url = URL(stream.playbackUrl)
                sourceConn = url.openConnection() as HttpURLConnection
                sourceConn.instanceFollowRedirects = true
                sourceConn.connectTimeout = 10000
                sourceConn.readTimeout = 15000
                val inStream = sourceConn.inputStream

                val buffer = ByteArray(65536)
                var bytesRead: Int
                while (inStream.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Client disconnected or pipe closed: ${e.message}")
            } finally {
                sourceConn?.disconnect()
                synchronized(stream) {
                    stream.clientCount--
                    if (stream.clientCount <= 0) {
                        if (stream.isPersistent) {
                            Log.i(TAG, "Persistent stream client left; starting dummy reader to keep stream hot 24/7.")
                            startDummyReader(stream)
                        } else {
                            // Start 5-second grace period timer
                            stream.expireJob = scope.launch {
                                delay(GRACE_PERIOD_MS)
                                Log.i(TAG, "Grace period expired for $channelId, shutting down stream.")
                                streamMap.remove(channelId)
                                if (latestActiveStream?.channelId == channelId) {
                                    latestActiveStream = null
                                }
                                stream.statsJob?.cancel()
                                stream.client?.close()
                                if (!stream.commandUrl.isNullOrEmpty()) {
                                    val cUrl = stream.commandUrl
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val stopConn = URL("${cUrl}/stop").openConnection() as HttpURLConnection
                                            stopConn.connectTimeout = 2000
                                            stopConn.readTimeout = 2000
                                            stopConn.inputStream.read()
                                        } catch (_: Exception) {}
                                    }
                                }

                                // If always hot stream is enabled, resume default channel after cooldown!
                                if (configManager?.isAlwaysHotStream == true) {
                                    val defId = configManager.defaultChannelId
                                    val defType = configManager.defaultSourceType
                                    if (defId.isNotEmpty() && defId != channelId) {
                                        Log.i(TAG, "Re-activating default hot stream after cooldown: $defId")
                                        delay(1500)
                                        prewarmStream(defId, defType, persistent = true)
                                    }
                                }
                            }
                        }
                    }
                }
                try {
                    clientSock.close()
                } catch (_: Exception) {}
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling client: ${e.message}")
            try {
                clientSock.close()
            } catch (_: Exception) {}
        }
    }

    private suspend fun getOrCreateStream(channelId: String, sourceType: String, persistent: Boolean = false): ActiveStream? = streamMutex.withLock {
        val existing = streamMap[channelId]
        if (existing != null && (existing.client?.isConnected() == true || existing.playbackUrl.isNotEmpty())) {
            existing.expireJob?.cancel()
            existing.expireJob = null
            existing.dummyReaderJob?.cancel()
            existing.dummyReaderJob = null
            latestActiveStream = existing
            Log.i(TAG, "Reusing existing warm stream session for $channelId")
            return@withLock existing
        }

        // Clean up and STOP all other active streams to adhere to Engine 1-stream policy
        var hadPrevious = false
        streamMap.forEach { (id, s) ->
            if (id != channelId) {
                hadPrevious = true
                s.dummyReaderJob?.cancel()
                s.expireJob?.cancel()
                s.statsJob?.cancel()
                s.client?.close()
                if (!s.commandUrl.isNullOrEmpty()) {
                    val cUrl = s.commandUrl
                    scope.launch(Dispatchers.IO) {
                        try {
                            val stopConn = URL("${cUrl}/stop").openConnection() as HttpURLConnection
                            stopConn.connectTimeout = 2000
                            stopConn.readTimeout = 2000
                            stopConn.inputStream.read()
                        } catch (_: Exception) {}
                    }
                }
                streamMap.remove(id)
            }
        }
        if (hadPrevious) {
            delay(500) // Allow engine to complete teardown
        }

        // 1. Primary Solver: AceStream Telnet API (Standard Protocol Handshake, 0-Transcode, 4K UHD Support)
        val apiClient = AceApiClient(host = "127.0.0.1", apiPort = apiPort) { peers, speed, downloaded ->
            streamMap[channelId]?.let {
                it.peers = peers
                it.speedKbps = speed
                it.downloaded = downloaded
                onStreamStateChanged?.invoke(channelId, peers, speed)
            }
        }

        try {
            val res = try {
                apiClient.startStream(sourceType, channelId)
            } catch (e: Exception) {
                if (channelId.length == 40) {
                    val altType = if (sourceType == "infohash") "content_id" else "infohash"
                    Log.w(TAG, "First start attempt failed with $sourceType (${e.message}), retrying with $altType...")
                    apiClient.startStream(altType, channelId)
                } else {
                    throw e
                }
            }

            val stream = ActiveStream(
                channelId = channelId,
                sourceType = sourceType,
                client = apiClient,
                playbackUrl = res.playbackUrl,
                isPersistent = persistent
            )
            streamMap[channelId] = stream
            latestActiveStream = stream
            Log.i(TAG, "AceApiClient Telnet session ready! playbackUrl=${res.playbackUrl}")
            return@withLock stream
        } catch (e: Exception) {
            Log.w(TAG, "AceApiClient Telnet failed: ${e.message}, falling back to Tokenized HTTP API...")
            apiClient.close()
        }

        // 2. Secondary Fallback: Tokenized HTTP API
        val token = tokenProvider?.invoke()
        Log.i(TAG, "Trying fallback HTTP API: channelId=$channelId, sourceType=$sourceType, token=${if (!token.isNullOrEmpty()) "PRESENT" else "NULL"}")

        if (!token.isNullOrEmpty()) {
            try {
                val isInfohash = sourceType == "infohash"
                val param = if (isInfohash) "infohash=${channelId}" else "id=${channelId}"
                val reqUrl = "http://127.0.0.1:6878/ace/getstream?format=json&token=${token}&${param}"
                Log.d(TAG, "Requesting getstream via HTTP API: $reqUrl")

                val conn = URL(reqUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 12000
                val body = conn.inputStream.bufferedReader().readText()
                val json = org.json.JSONObject(body)
                val resp = json.optJSONObject("response")

                if (resp != null) {
                    val pUrl = resp.getString("playback_url")
                    val sUrl = resp.optString("stat_url", "")
                    val cUrl = resp.optString("command_url", "")
                    Log.i(TAG, "AceStream HTTP session ready! playback_url=$pUrl")

                    val stream = ActiveStream(
                        channelId = channelId,
                        sourceType = sourceType,
                        client = null,
                        playbackUrl = pUrl,
                        commandUrl = cUrl,
                        statUrl = sUrl,
                        isPersistent = persistent
                    )

                    if (sUrl.isNotEmpty()) {
                        stream.statsJob = scope.launch(Dispatchers.IO) {
                            while (isActive) {
                                delay(1500)
                                try {
                                    val sConn = URL(sUrl).openConnection() as HttpURLConnection
                                    sConn.connectTimeout = 2000
                                    sConn.readTimeout = 2000
                                    val sText = sConn.inputStream.bufferedReader().readText()
                                    val sJson = org.json.JSONObject(sText).optJSONObject("response")
                                    if (sJson != null) {
                                        val p = sJson.optInt("peers", 0)
                                        val sp = sJson.optLong("speed_down", 0L)
                                        val dl = sJson.optLong("downloaded", 0L)
                                        stream.peers = p
                                        stream.speedKbps = sp
                                        stream.downloaded = dl
                                        onStreamStateChanged?.invoke(channelId, p, sp)
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }

                    streamMap[channelId] = stream
                    latestActiveStream = stream
                    return@withLock stream
                } else {
                    val err = json.optString("error", "Unknown error")
                    Log.e(TAG, "HTTP getstream returned error: $err")
                }
            } catch (e: Exception) {
                Log.e(TAG, "HTTP getstream request failed: ${e.message}")
            }
        }

        // 3. Tertiary Fallback: Local HTTPAceProxy (:8888)
        try {
            val hapParam = if (sourceType == "infohash" || channelId.length == 40) "infohash" else "pid"
            val hapUrl = "http://127.0.0.1:8888/$hapParam/$channelId/stream.mp4"
            Log.i(TAG, "Trying fallback HTTPAceProxy (:8888): $hapUrl")
            val testConn = URL(hapUrl).openConnection() as HttpURLConnection
            testConn.requestMethod = "HEAD"
            testConn.connectTimeout = 3000
            testConn.readTimeout = 3000
            val code = testConn.responseCode
            testConn.disconnect()
            if (code in 200..399) {
                Log.i(TAG, "HTTPAceProxy :8888 stream verified (HTTP $code)! url=$hapUrl")
                val stream = ActiveStream(
                    channelId = channelId,
                    sourceType = sourceType,
                    client = null,
                    playbackUrl = hapUrl,
                    isPersistent = persistent
                )
                streamMap[channelId] = stream
                latestActiveStream = stream
                return@withLock stream
            }
        } catch (e: Exception) {
            Log.d(TAG, "HTTPAceProxy fallback check skipped: ${e.message}")
        }

        null
    }

    private fun parseQueryParams(uri: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val query = uri.substringAfter("?", "")
        if (query.isNotEmpty()) {
            for (param in query.split("&")) {
                val kv = param.split("=")
                if (kv.isNotEmpty()) {
                    val k = URLDecoder.decode(kv[0], "UTF-8")
                    val v = if (kv.size > 1) URLDecoder.decode(kv[1], "UTF-8") else ""
                    map[k] = v
                }
            }
        }
        return map
    }

    private fun renderDashboardHtml(): String {
        val active = latestActiveStream
        val defId = configManager?.defaultChannelId ?: ""
        val alwaysHot = configManager?.isAlwaysHotStream ?: false
        val autoBoot = configManager?.isAutoStartBoot ?: true

        val statusBadge = if (active != null) {
            """<span style="background:#16a34a;color:#fff;padding:4px 10px;border-radius:9999px;font-weight:600;">ACTIVE (${active.peers} Peers | ${active.speedKbps} KB/s)</span>"""
        } else {
            """<span style="background:#475569;color:#fff;padding:4px 10px;border-radius:9999px;font-weight:600;">IDLE / STANDBY</span>"""
        }

        return """
<!DOCTYPE html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AceSport G2 Hub - Orchestrator 8000</title>
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #0b0f19; color: #f1f5f9; margin: 0; padding: 20px; }
        .card { background: #1e293b; border-radius: 12px; padding: 20px; margin-bottom: 20px; box-shadow: 0 4px 6px -1px rgba(0,0,0,0.3); border: 1px solid #334155; }
        h1, h2, h3 { margin-top: 0; color: #38bdf8; }
        .row { display: flex; justify-content: space-between; align-items: center; padding: 8px 0; border-bottom: 1px solid #334155; }
        .btn { background: #0284c7; color: white; border: none; padding: 10px 16px; border-radius: 8px; cursor: pointer; text-decoration: none; font-weight: 600; display: inline-block; }
        .btn:hover { background: #0369a1; }
        .btn-stop { background: #dc2626; }
        .btn-stop:hover { background: #b91c1c; }
        .code-box { background: #0f172a; padding: 12px; border-radius: 8px; font-family: monospace; word-break: break-all; color: #a5f3fc; margin: 8px 0; }
        input[type="text"] { background: #0f172a; border: 1px solid #475569; color: #fff; padding: 10px; border-radius: 6px; width: calc(100% - 24px); margin-bottom: 10px; }
    </style>
</head>
<body>
    <div style="max-width: 800px; margin: 0 auto;">
        <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom: 20px;">
            <div>
                <h1 style="margin-bottom:4px;">AceStream Hub</h1>
                <p style="margin:0;color:#94a3b8;">Cổng phát luồng Universal Proxy 24/7 &bull; Port 8000</p>
            </div>
            <div>$statusBadge</div>
        </div>

        <div class="card">
            <h2>Trạng Thái Trực Chiến</h2>
            <div class="row"><span>Tự động khởi động cùng G2 (Boot):</span><strong>${if (autoBoot) "BẬT (Active)" else "TẮT"}</strong></div>
            <div class="row"><span>Luôn mở sẵn luồng (Always Hot):</span><strong>${if (alwaysHot) "BẬT (Active 24/7)" else "TẮT (On-Demand)"}</strong></div>
            <div class="row"><span>Kênh mặc định:</span><span style="font-family:monospace;color:#38bdf8;">$defId</span></div>
            <div class="row"><span>Luồng đang chạy:</span><span style="font-family:monospace;color:#4ade80;">${active?.channelId ?: "Không có"}</span></div>
            <div class="row"><span>Kết nối P2P Swarm:</span><strong>${active?.peers ?: 0} Peers &bull; ${active?.speedKbps ?: 0} KB/s</strong></div>
            <div class="row"><span>Số thiết bị đang xem:</span><strong>${active?.clientCount ?: 0} Client(s)</strong></div>
            <div style="margin-top: 15px; display:flex; gap:10px;">
                <a href="/status" class="btn" target="_blank">Xem JSON Status</a>
                <a href="/stop" class="btn btn-stop">Dừng Luồng</a>
            </div>
        </div>

        <div class="card">
            <h2>Cấu Hình Kênh Mặc Định (Always Hot)</h2>
            <p style="color:#94a3b8;font-size:14px;">Khi G2 khởi động hoặc sau khi xem xong kênh khác, hệ thống sẽ tự động mở sẵn kênh này để xem tức thì.</p>
            <form action="/config" method="GET">
                <label style="display:block;margin-bottom:6px;font-weight:600;">Mã Kênh (Infohash hoặc Content ID):</label>
                <input type="text" name="default_channel" value="$defId" placeholder="Nhập 40 ký tự infohash hoặc id">
                <label style="display:block;margin-bottom:6px;font-weight:600;">Loại mã:</label>
                <select name="default_type" style="background:#0f172a;color:#fff;border:1px solid #475569;padding:8px;border-radius:6px;margin-bottom:12px;">
                    <option value="infohash" selected>infohash</option>
                    <option value="content_id">content_id</option>
                </select>
                <br>
                <label><input type="checkbox" name="always_hot" value="true" ${if (alwaysHot) "checked" else ""}> Luôn giữ luồng này mở sẵn 24/7 (Hot Stream)</label>
                <br><br>
                <button type="submit" class="btn">Lưu Cấu Hình</button>
            </form>
        </div>

        <div class="card">
            <h2>Đường Dẫn Phát Cho Thiết Bị Khác (LAN)</h2>
            <p style="color:#94a3b8;font-size:14px;">Cổng phát chuẩn cho Samsung Smart TV, Apple TV, PC VLC qua G2:</p>
            <div class="code-box">http://192.168.1.172:8000/ace/getstream?infohash=$defId</div>
            <div class="code-box">http://192.168.1.172:8000/infohash/$defId/stream.mp4</div>
        </div>
    </div>
</body>
</html>
        """.trimIndent()
    }

    private fun sendHttpResponse(out: OutputStream, contentType: String, body: ByteArray) {
        val resp = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        out.write(resp.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private fun sendHttpError(out: OutputStream, code: Int, message: String) {
        val resp = "HTTP/1.1 $code $message\r\n" +
                "Content-Type: text/plain\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "\r\n" +
                message
        out.write(resp.toByteArray(Charsets.UTF_8))
        out.flush()
    }
}
