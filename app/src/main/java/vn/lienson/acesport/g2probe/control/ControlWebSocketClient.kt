package vn.lienson.acesport.g2probe.control

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import vn.lienson.acesport.g2probe.BuildConfig
import vn.lienson.acesport.g2probe.G2ConfigManager
import vn.lienson.acesport.g2probe.G2OrchestratorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ControlWebSocketClient(
    private val context: Context,
    private val commandRouter: CommandRouter
) {
    companion object {
        private const val TAG = "ControlWSClient"
        private val BACKOFF_STEPS = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
    }

    private val configManager = G2ConfigManager(context)
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite for WebSocket
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var currentWebSocket: WebSocket? = null
    @Volatile private var isConnected = false
    private val isRunning = AtomicBoolean(false)
    private var backoffIndex = 0
    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null

    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            Log.i(TAG, "Starting ControlWebSocketClient...")
            scheduleReconnect(0L)
        }
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            Log.i(TAG, "Stopping ControlWebSocketClient...")
            connectJob?.cancel()
            heartbeatJob?.cancel()
            currentWebSocket?.close(1000, "Service stopping")
            currentWebSocket = null
            isConnected = false
        }
    }

    fun triggerReconnect() {
        if (!isRunning.get()) return
        Log.i(TAG, "Network state changed: triggering instant reconnect...")
        connectJob?.cancel()
        currentWebSocket?.cancel()
        currentWebSocket = null
        isConnected = false
        scheduleReconnect(500L)
    }

    private fun scheduleReconnect(delayMs: Long) {
        if (!isRunning.get()) return
        connectJob?.cancel()
        connectJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            doConnect()
        }
    }

    private var urlIndex = 0

    private fun doConnect() {
        if (!isRunning.get()) return
        val deviceId = configManager.deviceId
        val deviceToken = configManager.controlToken
        if (deviceToken.isEmpty()) {
            Log.w(TAG, "Device token is empty. WebSocket standby until token set via /config (device_id=$deviceId).")
            scheduleReconnect(60_000L)
            return
        }
        // Configured URL wins; otherwise rotate through the built-in Workers on each failed attempt.
        val serverUrl = configManager.controlServerUrl.ifEmpty {
            ControlConfig.FALLBACK_WS_URLS.getOrElse(urlIndex % ControlConfig.FALLBACK_WS_URLS.size) { ControlConfig.DEFAULT_SERVER_WS_URL }
        }
        if (serverUrl.isEmpty() || serverUrl.contains("example.com")) {
            Log.d(TAG, "Cloud Control standby: configure control_server via /config to connect.")
            scheduleReconnect(60_000L)
            return
        }
        val url = "$serverUrl?device_id=$deviceId&token=$deviceToken&agent_version=${ControlConfig.AGENT_VERSION}"
        Log.i(TAG, "Connecting to Cloud Control Endpoint: $serverUrl (device_id=$deviceId)...")

        val request = Request.Builder()
            .url(url)
            .addHeader("x-device-id", deviceId)
            .addHeader("x-device-token", deviceToken)
            .addHeader("x-agent-version", ControlConfig.AGENT_VERSION)
            .build()

        currentWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "✅ WebSocket CONNECTED to Cloud Control Endpoint!")
                isConnected = true
                backoffIndex = 0

                // 1. Send device identification packet
                sendConnectPacket(webSocket)

                // 2. Start heartbeat loop
                startHeartbeatLoop(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closing by server: $code / $reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closed: $code / $reason")
                onDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message} (response code: ${response?.code})")
                onDisconnected()
            }
        })
    }

    private fun onDisconnected() {
        isConnected = false
        currentWebSocket = null
        heartbeatJob?.cancel()
        if (isRunning.get()) {
            urlIndex++
            val delayMs = BACKOFF_STEPS[backoffIndex.coerceAtMost(BACKOFF_STEPS.lastIndex)]
            backoffIndex = (backoffIndex + 1).coerceAtMost(BACKOFF_STEPS.lastIndex)
            Log.w(TAG, "WebSocket disconnected. Will reconnect in ${delayMs / 1000}s (backoff step $backoffIndex)...")
            scheduleReconnect(delayMs)
        }
    }

    private fun sendConnectPacket(ws: WebSocket) {
        val lanIp = G2OrchestratorService.instance?.getLanIpAddress() ?: ""
        val connectPacket = JsonObject().apply {
            addProperty("type", "connect")
            addProperty("device_id", configManager.deviceId)
            addProperty("device_token", configManager.controlToken)
            addProperty("agent_version", ControlConfig.AGENT_VERSION)
            addProperty("app_version", BuildConfig.VERSION_NAME)
            addProperty("versionCode", BuildConfig.VERSION_CODE)
            addProperty("version_code", BuildConfig.VERSION_CODE)
            addProperty("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
            addProperty("android_version", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            addProperty("lan_ip", lanIp)
            addProperty("timestamp", System.currentTimeMillis())
        }
        ws.send(connectPacket.toString())
        Log.i(TAG, "Sent device identification handshake for ${configManager.deviceId} (lan_ip=$lanIp)")
    }

    private fun startHeartbeatLoop(ws: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && isConnected) {
                try {
                    val status = commandRouter.getAceHubStatusJson()
                    val isHot = status.get("always_hot")?.asBoolean ?: false
                    val st = status.get("status")?.asString ?: "IDLE"
                    val ch = status.get("channel")?.asString ?: ""
                    val speed = status.get("speed_kbps")?.asLong ?: 0L
                    val clients = status.get("clients")?.asInt ?: 0
                    val uptime = SystemClock.elapsedRealtime() / 1000

                    val heartbeat = JsonObject().apply {
                        addProperty("type", "heartbeat")
                        addProperty("device_id", configManager.deviceId)
                        addProperty("agent_status", "ONLINE")
                        addProperty("acehub_status", st)
                        addProperty("channel", ch)
                        addProperty("speed_kbps", speed)
                        addProperty("clients", clients)
                        addProperty("always_hot", isHot)
                        addProperty("uptime_sec", uptime)
                        addProperty("timestamp", System.currentTimeMillis())
                        add("details", status)
                    }

                    ws.send(heartbeat.toString())
                    Log.d(TAG, "Heartbeat sent: status=$st channel=${ch.take(8)}... speed=$speed KB/s")
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat build/send error: ${e.message}")
                }
                delay(ControlConfig.HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private fun handleIncomingMessage(ws: WebSocket, text: String) {
        scope.launch {
            try {
                val json = JsonParser.parseString(text).asJsonObject
                val type = json.get("type")?.asString ?: ""

                if (type == "command") {
                    val result = commandRouter.handleCommand(json)
                    ws.send(result.toString())
                    Log.i(TAG, "Command result sent back for ${result.get("command_id")?.asString}")
                } else if (type == "ping") {
                    val pong = JsonObject().apply {
                        addProperty("type", "pong")
                        addProperty("device_id", configManager.deviceId)
                        addProperty("timestamp", System.currentTimeMillis())
                    }
                    ws.send(pong.toString())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing incoming message: ${e.message}", e)
            }
        }
    }

    fun sendEvent(eventName: String, details: JsonObject? = null) {
        val ws = currentWebSocket
        if (ws != null && isConnected) {
            val eventObj = JsonObject().apply {
                addProperty("type", "event")
                addProperty("device_id", configManager.deviceId)
                addProperty("event", eventName)
                addProperty("timestamp", System.currentTimeMillis())
                if (details != null) {
                    add("details", details)
                }
            }
            ws.send(eventObj.toString())
            Log.i(TAG, "Sent telemetry event: $eventName")
        }
    }
}
