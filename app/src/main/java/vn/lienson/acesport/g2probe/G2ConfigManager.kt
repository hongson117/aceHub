package vn.lienson.acesport.g2probe

import android.content.Context
import android.content.SharedPreferences

class G2ConfigManager(context: Context) {

    companion object {
        private const val PREF_NAME = "acesport_g2_config"

        private const val KEY_AUTO_START_BOOT = "auto_start_boot"
        private const val KEY_ALWAYS_ON_247 = "always_on_247"
        private const val KEY_WATCHDOG_AUTO_RECOVER = "watchdog_auto_recover"
        private const val KEY_HUB_ENABLED = "hub_enabled"
        private const val KEY_ALWAYS_HOT_STREAM = "always_hot_stream"
        private const val KEY_DEFAULT_CHANNEL_ID = "default_channel_id"
        private const val KEY_DEFAULT_SOURCE_TYPE = "default_source_type"
        private const val KEY_PROXY_PORT = "proxy_port"
        private const val KEY_GRACE_PERIOD_MS = "grace_period_ms"
        private const val KEY_ACCESS_TOKEN = "engine_access_token"
        const val DEFAULT_ACCESS_TOKEN = "YA0WKoM9ov"

        // Default: Eleven Sports 1 HD [PL]
        const val DEFAULT_4K_INFOHASH = "73d24aeff6515abb236ea8a3e77d89fe0b04b665"
        const val DEFAULT_SOURCE_TYPE = "infohash"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var isAutoStartBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_BOOT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START_BOOT, value).apply()

    var isAlwaysOn247: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_ON_247, true)
        set(value) = prefs.edit().putBoolean(KEY_ALWAYS_ON_247, value).apply()

    var isWatchdogAutoRecover: Boolean
        get() = prefs.getBoolean(KEY_WATCHDOG_AUTO_RECOVER, true)
        set(value) = prefs.edit().putBoolean(KEY_WATCHDOG_AUTO_RECOVER, value).apply()

    var isHubEnabled: Boolean
        get() = prefs.getBoolean(KEY_HUB_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_HUB_ENABLED, value).apply()

    var isAlwaysHotStream: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_HOT_STREAM, true)
        set(value) = prefs.edit().putBoolean(KEY_ALWAYS_HOT_STREAM, value).apply()

    var defaultChannelId: String
        get() = prefs.getString(KEY_DEFAULT_CHANNEL_ID, DEFAULT_4K_INFOHASH) ?: DEFAULT_4K_INFOHASH
        set(value) = prefs.edit().putString(KEY_DEFAULT_CHANNEL_ID, value).apply()

    var defaultSourceType: String
        get() = prefs.getString(KEY_DEFAULT_SOURCE_TYPE, DEFAULT_SOURCE_TYPE) ?: DEFAULT_SOURCE_TYPE
        set(value) = prefs.edit().putString(KEY_DEFAULT_SOURCE_TYPE, value).apply()

    var accessToken: String
        get() = prefs.getString(KEY_ACCESS_TOKEN, DEFAULT_ACCESS_TOKEN) ?: DEFAULT_ACCESS_TOKEN
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var proxyPort: Int
        get() = prefs.getInt(KEY_PROXY_PORT, 8000)
        set(value) = prefs.edit().putInt(KEY_PROXY_PORT, value).apply()

    var gracePeriodMs: Long
        get() = prefs.getLong(KEY_GRACE_PERIOD_MS, 5000L)
        set(value) = prefs.edit().putLong(KEY_GRACE_PERIOD_MS, value).apply()
}
