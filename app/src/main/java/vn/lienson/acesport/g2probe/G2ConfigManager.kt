package vn.lienson.acesport.g2probe

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import java.util.Locale

class G2ConfigManager(private val context: Context) {

    companion object {
        private const val PREF_NAME = "acesport_g2_config"

        private const val KEY_AUTO_START_BOOT = "auto_start_boot"
        private const val KEY_ALWAYS_HOT_STREAM = "always_hot_stream"
        private const val KEY_DEFAULT_CHANNEL_ID = "default_channel_id"
        private const val KEY_DEFAULT_SOURCE_TYPE = "default_source_type"
        private const val KEY_PROXY_PORT = "proxy_port"
        private const val KEY_GRACE_PERIOD_MS = "grace_period_ms"
        private const val KEY_ACCESS_TOKEN = "engine_access_token"
        const val DEFAULT_ACCESS_TOKEN = "YA0WKoM9ov"

        // Default: 100% Clean Core - No commercial pay-TV channels or streams
        const val DEFAULT_CHANNEL_ID = ""
        const val DEFAULT_SOURCE_TYPE = "infohash"
        private const val KEY_KEEP_TAILSCALE = "keep_tailscale"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_CONTROL_TOKEN = "control_token"
        private const val KEY_CONTROL_SERVER_URL = "control_server_url"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var isAutoStartBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_BOOT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START_BOOT, value).apply()

    var isAlwaysHotStream: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_HOT_STREAM, true)
        set(value) = prefs.edit().putBoolean(KEY_ALWAYS_HOT_STREAM, value).apply()

    var defaultChannelId: String
        get() = prefs.getString(KEY_DEFAULT_CHANNEL_ID, DEFAULT_CHANNEL_ID) ?: DEFAULT_CHANNEL_ID
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

    var isKeepTailscale: Boolean
        get() = prefs.getBoolean(KEY_KEEP_TAILSCALE, false)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_TAILSCALE, value).apply()

    fun getDefaultDeviceId(): String {
        val cleanModel = Build.MODEL.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "").take(10)
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        } catch (_: Exception) { "" }
        val suffix = if (androidId.length >= 6) androidId.takeLast(6) else "000000"
        val prefix = if (cleanModel.isNotEmpty()) cleanModel else "device"
        return "acehub-$prefix-$suffix"
    }

    var deviceId: String
        get() {
            val saved = prefs.getString(KEY_DEVICE_ID, "") ?: ""
            return if (saved.isNotBlank()) saved else getDefaultDeviceId()
        }
        set(value) {
            prefs.edit().putString(KEY_DEVICE_ID, value.trim()).apply()
        }

    var controlToken: String
        get() = prefs.getString(KEY_CONTROL_TOKEN, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_CONTROL_TOKEN, value.trim()).apply()
        }

    var controlServerUrl: String
        get() = prefs.getString(KEY_CONTROL_SERVER_URL, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_CONTROL_SERVER_URL, value.trim()).apply()
        }

    val isTokenSet: Boolean
        get() = controlToken.isNotBlank()
}
