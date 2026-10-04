package vn.lienson.acesport.g2probe.control

import vn.lienson.acesport.g2probe.BuildConfig

/**
 * Cấu hình kết nối Cloud Control Endpoint cho AceHub.
 * Người dùng có thể cấu hình URL thông qua API /config?control_server=...
 */
object ControlConfig {
    // 1.4.4: control endpoints are injected at build time (gradle property / local.properties
    // `acehub.controlWsUrls`, comma separated; first = default, rest = fallback rotation) so the public
    // source stays clean-room. Without it the agent stays in standby until /config?control_server=... is set.
    const val PLACEHOLDER_WS_URL = "wss://your-control-hub.example.com/device/connect"
    val FALLBACK_WS_URLS: List<String> = BuildConfig.CONTROL_WS_URLS.split(',')
        .map { it.trim() }.filter { it.isNotEmpty() }
        .ifEmpty { listOf(PLACEHOLDER_WS_URL) }
    val DEFAULT_SERVER_WS_URL: String = FALLBACK_WS_URLS.first()
    const val AGENT_VERSION = "0.1.0"
    const val HEARTBEAT_INTERVAL_MS = 25_000L

    // Watchdog configuration
    const val WATCHDOG_CHECK_INTERVAL_MS = 15_000L
    const val WATCHDOG_IDLE_THRESHOLD_MS = 60_000L      // 60s idle triggers Level 1
    const val WATCHDOG_LEVEL2_WAIT_MS = 30_000L         // wait 30s after L1 before L2
    const val WATCHDOG_LEVEL3_WAIT_MS = 45_000L         // wait 45s after L2 before L3

    const val LEVEL1_COOLDOWN_MS = 30_000L
    const val LEVEL2_COOLDOWN_MS = 60_000L
    const val LEVEL3_COOLDOWN_MS = 180_000L             // 3 minutes cooldown for process restart
}
