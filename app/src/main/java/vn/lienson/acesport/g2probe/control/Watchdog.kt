package vn.lienson.acesport.g2probe.control

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import vn.lienson.acesport.g2probe.G2ConfigManager
import java.util.concurrent.atomic.AtomicBoolean

class Watchdog(
    private val context: Context,
    private val commandRouter: CommandRouter,
    private val webSocketClient: ControlWebSocketClient
) {
    companion object {
        private const val TAG = "AceHubWatchdog"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)
    private var watchdogJob: Job? = null

    private var idleStartTime: Long = 0L
    private var currentRecoveryLevel: Int = 0
    private var lastLevel1Time: Long = 0L
    private var lastLevel2Time: Long = 0L
    private var lastLevel3Time: Long = 0L

    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            Log.i(TAG, "Starting AceHub Self-Heal Watchdog...")
            watchdogJob?.cancel()
            watchdogJob = scope.launch {
                while (isActive && isRunning.get()) {
                    delay(ControlConfig.WATCHDOG_CHECK_INTERVAL_MS)
                    try {
                        checkAndHeal()
                    } catch (e: Exception) {
                        Log.e(TAG, "Watchdog loop exception: ${e.message}", e)
                    }
                }
            }
        }
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            Log.i(TAG, "Stopping AceHub Self-Heal Watchdog...")
            watchdogJob?.cancel()
            watchdogJob = null
            idleStartTime = 0L
            currentRecoveryLevel = 0
        }
    }

    private suspend fun checkAndHeal() {
        val status = commandRouter.getAceHubStatusJson()
        val alwaysHot = status.get("always_hot")?.asBoolean ?: false
        val aceHubStatus = status.get("status")?.asString ?: "IDLE"
        val now = System.currentTimeMillis()

        if (!alwaysHot) {
            idleStartTime = 0L
            currentRecoveryLevel = 0
            return
        }

        if (aceHubStatus == "ACTIVE") {
            if (idleStartTime != 0L) {
                Log.i(TAG, "Watchdog: AceHub returned to ACTIVE state! Swarm healthy.")
                idleStartTime = 0L
                currentRecoveryLevel = 0
            }
            return
        }

        // AceHub is IDLE while always_hot is true
        if (idleStartTime == 0L) {
            idleStartTime = now
            Log.i(TAG, "Watchdog: Detected AceHub IDLE state (always_hot=true). Starting idle timer...")
            return
        }

        val idleDuration = now - idleStartTime
        Log.d(TAG, "Watchdog: AceHub has been IDLE for ${idleDuration / 1000}s (Current Tier: $currentRecoveryLevel)")

        // --- Tier 1: Idle >= 60s -> AUTO_RECOVERY_PREWARM ---
        if (idleDuration >= ControlConfig.WATCHDOG_IDLE_THRESHOLD_MS && currentRecoveryLevel == 0) {
            if (now - lastLevel1Time >= ControlConfig.LEVEL1_COOLDOWN_MS) {
                lastLevel1Time = now
                currentRecoveryLevel = 1
                Log.w(TAG, "⚠️ [Watchdog Level 1] AceHub idle for ${idleDuration / 1000}s. Triggering AUTO_RECOVERY_PREWARM...")

                val evtDetails = JsonObject().apply {
                    addProperty("idle_duration_sec", idleDuration / 1000)
                    addProperty("recovery_level", 1)
                }
                webSocketClient.sendEvent("AUTO_RECOVERY_PREWARM", evtDetails)

                val config = G2ConfigManager(context)
                commandRouter.restartStreamInternal(config.defaultChannelId, config.defaultSourceType)
            }
            return
        }

        // --- Tier 2: Idle >= 90s -> AUTO_RECOVERY_STREAM_RESTART ---
        val level2Threshold = ControlConfig.WATCHDOG_IDLE_THRESHOLD_MS + ControlConfig.WATCHDOG_LEVEL2_WAIT_MS
        if (idleDuration >= level2Threshold && currentRecoveryLevel == 1) {
            if (now - lastLevel2Time >= ControlConfig.LEVEL2_COOLDOWN_MS) {
                lastLevel2Time = now
                currentRecoveryLevel = 2
                Log.w(TAG, "⚠️⚠️ [Watchdog Level 2] Prewarm did not recover stream. Triggering AUTO_RECOVERY_STREAM_RESTART...")

                val evtDetails = JsonObject().apply {
                    addProperty("idle_duration_sec", idleDuration / 1000)
                    addProperty("recovery_level", 2)
                }
                webSocketClient.sendEvent("AUTO_RECOVERY_STREAM_RESTART", evtDetails)

                val config = G2ConfigManager(context)
                commandRouter.restartStreamInternal(config.defaultChannelId, config.defaultSourceType)
            }
            return
        }

        // --- Tier 3: Idle >= 135s -> AUTO_RECOVERY_PROCESS_RESTART ---
        val level3Threshold = level2Threshold + ControlConfig.WATCHDOG_LEVEL3_WAIT_MS
        if (idleDuration >= level3Threshold && currentRecoveryLevel == 2) {
            if (now - lastLevel3Time >= ControlConfig.LEVEL3_COOLDOWN_MS) {
                lastLevel3Time = now
                currentRecoveryLevel = 3
                Log.e(TAG, "🚨 [Watchdog Level 3] Stream restart failed. Triggering AUTO_RECOVERY_PROCESS_RESTART...")

                val evtDetails = JsonObject().apply {
                    addProperty("idle_duration_sec", idleDuration / 1000)
                    addProperty("recovery_level", 3)
                }
                webSocketClient.sendEvent("AUTO_RECOVERY_PROCESS_RESTART", evtDetails)

                commandRouter.restartProcessInternal()
            } else {
                Log.w(TAG, "Watchdog Level 3 cooldown active (${(ControlConfig.LEVEL3_COOLDOWN_MS - (now - lastLevel3Time)) / 1000}s remaining). Skipping.")
            }
            return
        }

        // --- Alert: Failed all tiers ---
        if (currentRecoveryLevel == 3 && idleDuration >= (level3Threshold + 60_000L)) {
            Log.e(TAG, "🚨 [Watchdog Alert] All 3 recovery tiers exhausted! Sending AUTO_RECOVERY_FAILED.")
            val evtDetails = JsonObject().apply {
                addProperty("idle_duration_sec", idleDuration / 1000)
                addProperty("error", "Exhausted all recovery levels (Prewarm, Stream Restart, Process Restart)")
            }
            webSocketClient.sendEvent("AUTO_RECOVERY_FAILED", evtDetails)

            // Reset after cooldown so it can attempt a fresh cycle
            if (now - lastLevel3Time >= ControlConfig.LEVEL3_COOLDOWN_MS) {
                currentRecoveryLevel = 0
                idleStartTime = now
            }
        }
    }
}
