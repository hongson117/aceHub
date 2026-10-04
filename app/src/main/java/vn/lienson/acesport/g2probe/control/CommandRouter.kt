package vn.lienson.acesport.g2probe.control

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import vn.lienson.acesport.g2probe.BuildConfig
import vn.lienson.acesport.g2probe.G2ConfigManager
import vn.lienson.acesport.g2probe.G2OrchestratorService
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.LinkedHashMap

class CommandRouter(private val context: Context) {

    companion object {
        private const val TAG = "CommandRouter"
        private const val MAX_CACHE_SIZE = 100
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Command Idempotency Cache: command_id -> result JsonObject
    private val executedCommands = Collections.synchronizedMap(
        object : LinkedHashMap<String, JsonObject>(MAX_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonObject>?): Boolean {
                return size > MAX_CACHE_SIZE
            }
        }
    )

    suspend fun handleCommand(cmdJson: JsonObject): JsonObject {
        val cmdId = cmdJson.get("command_id")?.asString ?: "cmd_${System.currentTimeMillis()}"
        val command = (cmdJson.get("command")?.asString ?: cmdJson.get("action")?.asString ?: "").trim().lowercase()
        val args = if (cmdJson.has("args") && cmdJson.get("args").isJsonObject) {
            cmdJson.getAsJsonObject("args")
        } else {
            JsonObject()
        }

        Log.i(TAG, "[$cmdId] Received command: '$command' with args: $args")

        // 1. Idempotency Check: Return cached response if recently executed
        synchronized(executedCommands) {
            if (executedCommands.containsKey(cmdId)) {
                Log.i(TAG, "[$cmdId] Duplicate command detected. Returning cached response.")
                return executedCommands[cmdId]!!
            }
        }

        // 2. Dispatch whitelisted command
        val result = try {
            when (command) {
                "ping" -> handlePing(cmdId)
                "status" -> handleStatus(cmdId)
                "start" -> handleStart(cmdId, args)
                "stop" -> handleStop(cmdId)
                "restart_stream" -> handleRestartStream(cmdId, args)
                "restart_process" -> handleRestartProcess(cmdId)
                "prewarm" -> handlePrewarm(cmdId, args)
                "health" -> handleHealth(cmdId)
                "reboot_device" -> handleRebootDevice(cmdId)
                "config", "get_config" -> handleGetConfig(cmdId)
                "set_config", "set_tailscale" -> handleSetConfig(cmdId, args)
                else -> {
                    buildResult(cmdId, false, "Unknown or unsupported command: '$command'", null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$cmdId] Error executing '$command': ${e.message}", e)
            buildResult(cmdId, false, "Exception during execution: ${e.message}", null)
        }

        // 3. Cache result for deduplication
        synchronized(executedCommands) {
            executedCommands[cmdId] = result
        }

        return result
    }

    private fun handlePing(cmdId: String): JsonObject {
        val config = G2ConfigManager(context)
        val data = JsonObject().apply {
            addProperty("device_online", true)
            addProperty("agent_version", ControlConfig.AGENT_VERSION)
            addProperty("app_version", BuildConfig.VERSION_NAME)
            addProperty("version_code", BuildConfig.VERSION_CODE)
            addProperty("versionCode", BuildConfig.VERSION_CODE)
            addProperty("device_id", config.deviceId)
            addProperty("token_set", config.isTokenSet)
            addProperty("android_version", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            addProperty("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
            addProperty("lan_ip", G2OrchestratorService.instance?.getLanIpAddress() ?: "")
            addProperty("uptime_sec", SystemClock.elapsedRealtime() / 1000)
        }
        return buildResult(cmdId, true, "pong", data)
    }

    suspend fun getAceHubStatusJson(): JsonObject = withContext(Dispatchers.IO) {
        val orch = G2OrchestratorService.instance
        val config = G2ConfigManager(context)
        val defId = config.defaultChannelId
        val alwaysHot = config.isAlwaysHotStream
        val keepTs = config.isKeepTailscale
        val tsIp = orch?.getTailscaleIp() ?: ""
        val tsRunning = tsIp.isNotEmpty()

        val stream = orch?.proxyServer?.latestActiveStream
        val statusObj = JsonObject()

        if (stream != null) {
            statusObj.addProperty("status", "ACTIVE")
            statusObj.addProperty("channel", stream.channelId)
            statusObj.addProperty("source_type", stream.sourceType)
            statusObj.addProperty("peers", stream.peers)
            statusObj.addProperty("speed_kbps", stream.speedKbps)
            statusObj.addProperty("downloaded_bytes", stream.downloaded)
            statusObj.addProperty("clients", stream.clientCount)
            statusObj.addProperty("is_persistent", stream.isPersistent)
        } else {
            statusObj.addProperty("status", "IDLE")
            statusObj.addProperty("channel", "")
            statusObj.addProperty("source_type", "")
            statusObj.addProperty("peers", 0)
            statusObj.addProperty("speed_kbps", 0)
            statusObj.addProperty("downloaded_bytes", 0)
            statusObj.addProperty("clients", 0)
            statusObj.addProperty("is_persistent", false)
        }

        statusObj.addProperty("default_channel", defId)
        statusObj.addProperty("always_hot", alwaysHot)
        statusObj.addProperty("keep_tailscale", keepTs)
        statusObj.addProperty("engine_package", orch?.engineManager?.boundPackage ?: "UNKNOWN")
        statusObj.addProperty("engine_version", orch?.engineManager?.boundVersion ?: "UNKNOWN")

        val tailscaleObj = JsonObject().apply {
            addProperty("running", tsRunning)
            addProperty("ip", tsIp)
            addProperty("keep_alive", keepTs)
        }
        statusObj.add("tailscale", tailscaleObj)

        return@withContext statusObj
    }

    private suspend fun handleStatus(cmdId: String): JsonObject {
        val statusData = getAceHubStatusJson()
        return buildResult(cmdId, true, "Status retrieved successfully", statusData)
    }

    private suspend fun handleStart(cmdId: String, args: JsonObject): JsonObject {
        val config = G2ConfigManager(context)
        val channelId = if (args.has("channel_id") && !args.get("channel_id").asString.isNullOrEmpty()) {
            args.get("channel_id").asString
        } else {
            config.defaultChannelId
        }
        val sourceType = if (args.has("source_type") && !args.get("source_type").asString.isNullOrEmpty()) {
            args.get("source_type").asString
        } else {
            config.defaultSourceType
        }

        ensureOrchestratorRunning()

        val proxy = G2OrchestratorService.instance?.proxyServer
        val ok = proxy?.prewarmStream(channelId, sourceType, persistent = true) ?: run {
            callLocalHttp("/prewarm?id=$channelId&type=$sourceType")
        }

        val status = getAceHubStatusJson()
        return buildResult(cmdId, ok, if (ok) "AceHub started and stream prewarmed" else "Failed to start stream", status)
    }

    private suspend fun handleStop(cmdId: String): JsonObject {
        val ok = callLocalHttp("/stop")
        val status = getAceHubStatusJson()
        return buildResult(cmdId, ok, if (ok) "AceHub stream stopped" else "Failed to stop stream", status)
    }

    suspend fun restartStreamInternal(channelId: String? = null, sourceType: String? = null): Boolean {
        val config = G2ConfigManager(context)
        val chId = channelId?.takeIf { it.isNotEmpty() } ?: config.defaultChannelId
        val sType = sourceType?.takeIf { it.isNotEmpty() } ?: config.defaultSourceType

        ensureOrchestratorRunning()

        // 1. Call /stop or proxy stop
        val orch = G2OrchestratorService.instance
        if (orch != null && !orch.hasTailscaleIp()) {
            orch.wakeTailscale(context)
        }

        callLocalHttp("/stop")
        delay(600)

        // 2. Prewarm again
        val proxy = orch?.proxyServer
        return if (proxy != null) {
            proxy.prewarmStream(chId, sType, persistent = true)
        } else {
            callLocalHttp("/prewarm?id=$chId&type=$sType")
        }
    }

    private suspend fun handleRestartStream(cmdId: String, args: JsonObject): JsonObject {
        val chId = args.get("channel_id")?.asString
        val sType = args.get("source_type")?.asString
        val ok = restartStreamInternal(chId, sType)
        val status = getAceHubStatusJson()
        return buildResult(cmdId, ok, if (ok) "Stream restarted successfully" else "Stream restart failed", status)
    }

    suspend fun restartProcessInternal(): Boolean {
        // v1.4.4: a REAL process restart. Re-creating the service inside the same (possibly wedged) process
        // did not help on FPT; a fresh process does. Reply first, then die; alarm/START_STICKY bring us back.
        if (vn.lienson.acesport.g2probe.HubRestarter.allowed(context)) {
            Log.w(TAG, "Executing restart_process: hard restart of the AceHub process in 1.5 s")
            Thread {
                try { Thread.sleep(1500) } catch (_: InterruptedException) {}
                vn.lienson.acesport.g2probe.HubRestarter.hardRestart(context, "restart_process command / watchdog L3")
            }.apply { isDaemon = true; start() }
            return true
        }
        Log.w(TAG, "Hard-restart budget exhausted: soft reinit of the streaming layer instead")
        return softRestartProcessInternal()
    }

    private suspend fun softRestartProcessInternal(): Boolean {
        Log.w(TAG, "Executing soft restart (Reinitializing Streaming Layer)...")
        return withContext(Dispatchers.IO) {
            try {
                val serviceIntent = Intent(context, G2OrchestratorService::class.java)
                try {
                    context.stopService(serviceIntent)
                } catch (e: Exception) {
                    Log.w(TAG, "stopService warning: ${e.message}")
                }
                delay(1200)
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                    Log.i(TAG, "G2OrchestratorService restarted.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to startForegroundService for orchestrator: ${e.message}", e)
                    return@withContext false
                }
                delay(2000)
                val config = G2ConfigManager(context)
                val ok = restartStreamInternal(config.defaultChannelId, config.defaultSourceType)
                Log.i(TAG, "Streaming layer reinitialized. Prewarm success: $ok")
                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "Exception during softRestartProcessInternal: ${e.message}", e)
                return@withContext false
            }
        }
    }

    private suspend fun handleRestartProcess(cmdId: String): JsonObject {
        val ok = restartProcessInternal()
        val status = getAceHubStatusJson()
        return buildResult(cmdId, ok, if (ok) "Streaming process reinitialized successfully" else "Streaming process reinit failed", status)
    }

    private suspend fun handlePrewarm(cmdId: String, args: JsonObject): JsonObject {
        val config = G2ConfigManager(context)
        val chId = args.get("channel_id")?.asString ?: args.get("id")?.asString ?: config.defaultChannelId
        val sType = args.get("source_type")?.asString ?: args.get("type")?.asString ?: config.defaultSourceType

        ensureOrchestratorRunning()

        val proxy = G2OrchestratorService.instance?.proxyServer
        val ok = proxy?.prewarmStream(chId, sType, persistent = true) ?: run {
            callLocalHttp("/prewarm?id=$chId&type=$sType")
        }

        val status = getAceHubStatusJson()
        return buildResult(cmdId, ok, if (ok) "Channel $chId prewarmed" else "Prewarm failed", status)
    }

    private suspend fun handleHealth(cmdId: String): JsonObject = withContext(Dispatchers.IO) {
        val orch = G2OrchestratorService.instance
        val serviceRunning = orch != null
        val proxyPortAlive = isPortOpen("127.0.0.1", 8000)
        val engineApiAlive = isPortOpen("127.0.0.1", 62062) || isPortOpen("127.0.0.1", 6878)
        val stream = orch?.proxyServer?.latestActiveStream
        val streamActive = stream != null && stream.speedKbps > 0

        val healthData = JsonObject().apply {
            addProperty("control_agent", "HEALTHY")
            addProperty("orchestrator_service", if (serviceRunning) "RUNNING" else "STOPPED")
            addProperty("http_proxy_port_8000", if (proxyPortAlive) "LISTENING" else "DOWN")
            addProperty("engine_loopback", if (engineApiAlive) "ALIVE" else "DOWN")
            addProperty("active_channel", stream?.channelId ?: "NONE")
            addProperty("stream_speed_kbps", stream?.speedKbps ?: 0)
            addProperty("tailscale_ip", orch?.getTailscaleIp() ?: "NONE")
        }

        val isHealthy = serviceRunning && proxyPortAlive && engineApiAlive
        return@withContext buildResult(cmdId, isHealthy, if (isHealthy) "AceHub system is healthy" else "System has degraded components", healthData)
    }

    private fun handleRebootDevice(cmdId: String): JsonObject {
        return buildResult(cmdId, false, "reboot_device is not permitted by policy for Control Agent", null)
    }

    private fun handleGetConfig(cmdId: String): JsonObject {
        val config = G2ConfigManager(context)
        val data = JsonObject().apply {
            addProperty("device_id", config.deviceId)
            addProperty("token_set", config.isTokenSet)
            addProperty("app_version", BuildConfig.VERSION_NAME)
            addProperty("versionCode", BuildConfig.VERSION_CODE)
            addProperty("version_code", BuildConfig.VERSION_CODE)
            addProperty("keep_tailscale", config.isKeepTailscale)
            addProperty("default_channel_id", config.defaultChannelId)
            addProperty("default_source_type", config.defaultSourceType)
            addProperty("always_hot_stream", config.isAlwaysHotStream)
            addProperty("proxy_port", config.proxyPort)
        }
        return buildResult(cmdId, true, "Configuration retrieved successfully", data)
    }

    private fun handleSetConfig(cmdId: String, args: JsonObject): JsonObject {
        val config = G2ConfigManager(context)
        val orch = G2OrchestratorService.instance

        if (args.has("device_id")) {
            val newId = args.get("device_id").asString.trim()
            if (newId.isNotEmpty()) {
                config.deviceId = newId
            }
        }

        if (args.has("keep_tailscale") || args.has("tailscale") || args.has("auto_tailscale")) {
            val el = args.get("keep_tailscale") ?: args.get("tailscale") ?: args.get("auto_tailscale")
            val newKeep = when {
                el.isJsonPrimitive && el.asJsonPrimitive.isBoolean -> el.asBoolean
                el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asInt == 1
                else -> {
                    val s = el.asString.trim().lowercase()
                    s == "true" || s == "1" || s == "on" || s == "yes"
                }
            }
            config.isKeepTailscale = newKeep
            if (newKeep) {
                orch?.startTailscaleWatchdog()
            } else {
                orch?.stopTailscaleWatchdog()
            }
        }

        if (args.has("default_channel_id")) {
            config.defaultChannelId = args.get("default_channel_id").asString
        }
        if (args.has("default_source_type")) {
            config.defaultSourceType = args.get("default_source_type").asString
        }
        if (args.has("always_hot_stream")) {
            config.isAlwaysHotStream = args.get("always_hot_stream").asBoolean
        }

        val data = JsonObject().apply {
            addProperty("device_id", config.deviceId)
            addProperty("token_set", config.isTokenSet)
            addProperty("app_version", BuildConfig.VERSION_NAME)
            addProperty("versionCode", BuildConfig.VERSION_CODE)
            addProperty("version_code", BuildConfig.VERSION_CODE)
            addProperty("keep_tailscale", config.isKeepTailscale)
            addProperty("default_channel_id", config.defaultChannelId)
            addProperty("default_source_type", config.defaultSourceType)
            addProperty("always_hot_stream", config.isAlwaysHotStream)
            addProperty("proxy_port", config.proxyPort)
        }
        return buildResult(cmdId, true, "Configuration updated successfully", data)
    }

    private fun ensureOrchestratorRunning() {
        if (G2OrchestratorService.instance == null) {
            try {
                val intent = Intent(context, G2OrchestratorService::class.java)
                ContextCompat.startForegroundService(context, intent)
                Log.i(TAG, "Dispatched startForegroundService to launch G2OrchestratorService")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting G2OrchestratorService: ${e.message}")
            }
        }
    }

    private fun isPortOpen(host: String, port: Int): Boolean {
        return try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, port), 600)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun callLocalHttp(path: String): Boolean {
        return try {
            val url = URL("http://127.0.0.1:8000$path")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 4000
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "callLocalHttp $path error: ${e.message}")
            false
        }
    }

    private fun buildResult(cmdId: String, success: Boolean, message: String, data: JsonObject?): JsonObject {
        return JsonObject().apply {
            addProperty("type", "command_result")
            addProperty("command_id", cmdId)
            addProperty("success", success)
            addProperty("message", message)
            if (data != null) {
                add("data", data)
            }
        }
    }
}
