package vn.lienson.acesport.g2probe.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

object AceServeAndroidInfo {
    private const val PREFS_NAME = "aceserve_android_info"
    private const val KEY_DEVICE_ID = "device_id"

    @Throws(IOException::class)
    fun write(context: Context, abi: String, root: File, cache: File, output: File) {
        try {
            val info = build(context.applicationContext, abi, root, cache)
            val parent = output.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw IOException("Cannot create $parent")
            }
            OutputStreamWriter(FileOutputStream(output), StandardCharsets.UTF_8).use { writer ->
                writer.write(info.toString())
                writer.write("\n")
            }
        } catch (e: Exception) {
            if (e is IOException) throw e
            throw IOException("Cannot write Android runtime info", e)
        }
    }

    private fun build(context: Context, abi: String, root: File, cache: File): JSONObject {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val memoryClass = am?.memoryClass ?: 64
        val cacheStats = StatFs(cache.absolutePath)
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val locale = Locale.getDefault()

        val paths = JSONObject()
            .put("aceStreamHome", cache.absolutePath)
            .put("cacheDir", cache.absolutePath)
            .put("logsDir", cache.absolutePath)
            .put("androidDataDir", File(root, "android-data").absolutePath)
            .put("tempDir", File(root, "tmp").absolutePath)

        val memory = JSONObject()
            .put("totalBytes", if (memInfo.totalMem > 0) memInfo.totalMem else memoryClass * 1024L * 1024L)
            .put("availableBytes", memInfo.availMem)
            .put("javaMaxBytes", Runtime.getRuntime().maxMemory())
            .put("memoryClassMb", memoryClass)
            .put("lowMemory", memInfo.lowMemory)

        val storage = JSONObject()
            .put("cacheAvailableBytes", cacheStats.availableBytes)
            .put("cacheTotalBytes", cacheStats.totalBytes)
            .put("cacheBlockSizeBytes", cacheStats.blockSizeLong)
            .put("cacheBlockCount", cacheStats.blockCountLong)
            .put("cacheAvailableBlocks", cacheStats.availableBlocksLong)

        val device = JSONObject()
            .put("deviceId", deviceId(context))
            .put("appId", context.packageName)
            .put("arch", abi)
            .put("deviceAbi", abi)
            .put("supportedAbis", Build.SUPPORTED_ABIS.joinToString(","))
            .put("manufacturer", Build.MANUFACTURER ?: "Android")
            .put("model", Build.MODEL ?: "Android")
            .put("deviceName", Build.DEVICE ?: "Android")
            .put("productName", Build.PRODUCT ?: "Android")
            .put("androidRelease", Build.VERSION.RELEASE ?: "")
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("displayLanguage", locale.language)
            .put("locale", locale.toLanguageTag())
            .put("isAndroidTv", true)
            .put("hasBrowser", false)
            .put("hasWebView", true)

        val app = JSONObject()
            .put("packageName", context.packageName)
            .put("versionName", pInfo.versionName ?: "1.0.0")
            .put("versionCode", 302131302)
            .put("targetSdkVersion", context.applicationInfo.targetSdkVersion)

        return JSONObject()
            .put("schemaVersion", 1)
            .put("paths", paths)
            .put("memory", memory)
            .put("storage", storage)
            .put("device", device)
            .put("app", app)
    }

    private fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }
}
