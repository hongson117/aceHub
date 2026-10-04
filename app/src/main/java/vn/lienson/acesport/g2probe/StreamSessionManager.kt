package vn.lienson.acesport.g2probe

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

class StreamSessionManager(
    private val httpPortProvider: () -> Int,
    private val listener: SessionListener
) {
    companion object {
        private const val TAG = "StreamSessionManager"
        private const val API_PORT = 62062
    }

    interface SessionListener {
        fun onSessionStarting(requestId: Int, source: TestSource, format: String)
        fun onSessionReady(requestId: Int, playbackUrl: String, sessionId: String)
        fun onSessionStats(requestId: Int, peers: Int, speedDownKbps: Long, status: String)
        fun onSessionFailed(requestId: Int, errorCode: String, message: String)
        fun onSessionStopped(requestId: Int)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val requestIdGenerator = AtomicInteger(0)

    private data class CachedSession(
        val sourceId: String,
        val playbackUrl: String,
        val apiClient: AceApiClient?,
        val sessionId: String,
        var expireJob: Job? = null
    )

    private val sessionCache = java.util.concurrent.ConcurrentHashMap<String, CachedSession>()

    private var activeRequestId: Int = 0
    private var activeSourceId: String? = null
    private var activeApiClient: AceApiClient? = null
    private var activePlaybackUrl: String? = null
    private var activeSessionId: String? = null

    fun startSession(source: TestSource, isHls: Boolean): Int {
        val reqId = requestIdGenerator.incrementAndGet()
        activeRequestId = reqId

        // 1. Check Grace Period Cache (Instant Zap-back matching Ubuntu orchestrator)
        val cached = sessionCache[source.id]
        if (cached != null) {
            cached.expireJob?.cancel()
            cached.expireJob = null
            activeSourceId = source.id
            activeApiClient = cached.apiClient
            activePlaybackUrl = cached.playbackUrl
            activeSessionId = cached.sessionId

            Log.i(TAG, "[$reqId] [CACHE_HIT] Rapid zap-back to ${source.id}! Reusing session ${cached.sessionId}")
            scope.launch(Dispatchers.Main) {
                listener.onSessionReady(reqId, cached.playbackUrl, cached.sessionId)
            }
            return reqId
        }

        // 2. Put old active session into grace cache with 5s expiration (Non-blocking cleanup)
        val oldSourceId = activeSourceId
        val oldClient = activeApiClient
        val oldPlay = activePlaybackUrl
        val oldSess = activeSessionId
        if (!oldSourceId.isNullOrEmpty() && oldClient != null && !oldPlay.isNullOrEmpty()) {
            val entry = CachedSession(oldSourceId, oldPlay, oldClient, oldSess ?: "prev")
            entry.expireJob = scope.launch {
                delay(5000L) // 5s grace period matching Ubuntu orchestrator ChannelShutdownDelay
                sessionCache.remove(oldSourceId)
                oldClient.close()
                Log.d(TAG, "Grace period expired for $oldSourceId, session closed.")
            }
            sessionCache[oldSourceId] = entry
        }

        activeSourceId = source.id
        activeApiClient = null
        activePlaybackUrl = null
        activeSessionId = null

        scope.launch {
            listener.onSessionStarting(reqId, source, if (isHls) "HLS" else "TS")

            if (source.sourceType == "local_file") {
                val fileUri = Uri.parse("file://${source.value}").toString()
                Log.d(TAG, "[$reqId] Playing local media: $fileUri")
                withContext(Dispatchers.Main) {
                    listener.onSessionReady(reqId, fileUri, "local-file-session")
                }
                return@launch
            }

            if (source.sourceType == "http_url" || source.sourceType == "direct_url") {
                Log.d(TAG, "[$reqId] Playing direct URL: ${source.value}")
                withContext(Dispatchers.Main) {
                    listener.onSessionReady(reqId, source.value, "direct-url-session")
                }
                return@launch
            }

            Log.d(TAG, "[$reqId] Requesting engine session via AceApiClient (Telnet API bypass)...")

            val apiClient = AceApiClient(host = "127.0.0.1", apiPort = API_PORT) { peers, speed, downloaded ->
                if (activeRequestId == reqId) {
                    scope.launch(Dispatchers.Main) {
                        listener.onSessionStats(reqId, peers, speed, "dl")
                    }
                }
            }
            activeApiClient = apiClient

            try {
                val startResult = apiClient.startStream(source.sourceType, source.value)

                if (activeRequestId != reqId) {
                    Log.w(TAG, "[$reqId] Stale request received, active is $activeRequestId. Discarding.")
                    apiClient.close()
                    return@launch
                }

                activePlaybackUrl = startResult.playbackUrl
                activeSessionId = startResult.infohash

                Log.i(TAG, "[$reqId] AceStream session ready: ${startResult.playbackUrl}")
                withContext(Dispatchers.Main) {
                    listener.onSessionReady(reqId, startResult.playbackUrl, startResult.infohash)
                }

            } catch (e: Exception) {
                if (activeRequestId == reqId) {
                    Log.e(TAG, "[$reqId] Session request exception", e)
                    apiClient.close()
                    withContext(Dispatchers.Main) {
                        listener.onSessionFailed(reqId, "TELNET_API_ERROR", e.message ?: "Unknown error")
                    }
                }
            }
        }

        return reqId
    }

    fun stopSession() {
        val reqId = activeRequestId
        val client = activeApiClient
        val srcId = activeSourceId

        activeRequestId = 0
        activeSourceId = null
        activeApiClient = null
        activePlaybackUrl = null
        activeSessionId = null

        scope.launch {
            client?.close()
            sessionCache.values.forEach { it.expireJob?.cancel(); it.apiClient?.close() }
            sessionCache.clear()
            withContext(Dispatchers.Main) {
                listener.onSessionStopped(reqId)
            }
        }
    }
}
