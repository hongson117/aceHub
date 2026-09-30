package vn.lienson.acesport.g2probe

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.acestream.engine.service.v0.IAceStreamEngine
import org.acestream.engine.service.v0.IAceStreamEngineCallback
import org.acestream.engine.service.v0.IStartEngineResponse

import vn.lienson.acesport.g2probe.engine.EmbeddedAceRuntime

class AceEngineManager(private val context: Context, private val listener: EngineListener) {

    companion object {
        private const val TAG = "AceEngineManager"
        private val KNOWN_PACKAGES = listOf(
            "org.acestream.core.web",
            "org.acestream.core.atv",
            "org.acestream.media.atv",
            "org.acestream.core",
            "org.acestream.media"
        )
    }

    interface EngineListener {
        fun onEngineStateChanged(state: String, details: String)
        fun onEngineReady(httpPort: Int, enginePort: Int, packageName: String, version: String)
        fun onEngineError(error: String)
    }

    private var engineService: IAceStreamEngine? = null
    private var isBound = false
    private var embeddedRuntime: EmbeddedAceRuntime? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var boundPackage: String = "UNKNOWN"
        private set
    var boundVersion: String = "UNKNOWN"
        private set
    var httpApiPort: Int = 6878
        private set
    var engineApiPort: Int = 0
        private set
    var accessToken: String? = null
        private set
    @Volatile var isEngineReady: Boolean = false
        private set
    @Volatile var isStarting: Boolean = false
        private set

    private val engineCallback = object : IAceStreamEngineCallback.Stub() {
        override fun onReady(port: Int) {
            Log.d(TAG, "onReady received from engine callback, port=$port")
            updateReadyState(port != -1)
        }

        override fun onUnpacking() {
            Log.d(TAG, "Engine unpacking...")
            mainHandler.post { listener.onEngineStateChanged("UNPACKING", "Unpacking engine assets...") }
        }

        override fun onStarting() {
            Log.d(TAG, "Engine starting...")
            mainHandler.post { listener.onEngineStateChanged("STARTING", "Engine service starting...") }
        }

        override fun onStopped() {
            Log.d(TAG, "Engine stopped")
            mainHandler.post { listener.onEngineStateChanged("STOPPED", "Engine service stopped") }
        }

        override fun onPlaylistUpdated() {}
        override fun onEPGUpdated() {}
        override fun onRestartPlayer() {}
        override fun onSettingsUpdated() {}
        override fun onAuthUpdated() {}
        override fun onWaitForNetworkConnection() {}
    }

    private val startEngineCallback = object : IStartEngineResponse.Stub() {
        override fun onResult(success: Boolean) {
            Log.d(TAG, "startEngineCallback onResult=$success")
            updateReadyState(success)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Connected to service: $name")
            mainHandler.post { listener.onEngineStateChanged("BIND_CONNECTED", "Service bound: ${name?.packageName}") }
            try {
                engineService = IAceStreamEngine.Stub.asInterface(service)
                engineService?.registerCallbackExt(engineCallback, true)
                engineService?.startEngineWithCallback(startEngineCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing bound service", e)
                mainHandler.post { listener.onEngineError("Service init error: ${e.message}") }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Service disconnected: $name")
            engineService = null
            isBound = false
            mainHandler.post { listener.onEngineStateChanged("DISCONNECTED", "Service disconnected: $name") }
        }
    }

    @Synchronized
    fun bindAndStart() {
        if (isStarting) {
            Log.w(TAG, "bindAndStart already in progress, skipping duplicate call")
            return
        }
        isStarting = true
        isEngineReady = false

        Thread {
            try {
                // 1. Check if headless engine is already listening on loopback
                if (isSocketAlive("127.0.0.1", 62062) || isSocketAlive("127.0.0.1", 6878)) {
                    boundPackage = "org.acestream.engine"
                    boundVersion = "AceStream Headless Engine 3.2.17 (0 Ads)"
                    httpApiPort = 6878
                    engineApiPort = 62062
                    Log.i(TAG, "Discovered active headless AceStream on 127.0.0.1:62062!")
                    isEngineReady = true
                    isStarting = false
                    mainHandler.post {
                        listener.onEngineStateChanged("READY", "Connected to AceStream Headless Engine (0 Ads)")
                        listener.onEngineReady(httpApiPort, engineApiPort, boundPackage, boundVersion)
                    }
                    return@Thread
                }

                // 2. Launch embedded AceStream Linux engine directly
                try {
                    val runtime = EmbeddedAceRuntime(context)
                    embeddedRuntime = runtime
                    mainHandler.post { listener.onEngineStateChanged("PREPARING", "Extracting embedded AceStream Linux Engine...") }
                    runtime.prepare()
                    mainHandler.post { listener.onEngineStateChanged("STARTING", "Launching embedded AceStream Linux Engine...") }
                    runtime.start()
                    AppLogger.i("ENGINE_MGR", "Đang đợi Engine mở cổng 62062 / 6878...")
                    for (i in 1..40) {
                        Thread.sleep(500)
                        if (isSocketAlive("127.0.0.1", 62062) || isSocketAlive("127.0.0.1", 6878)) {
                            boundPackage = context.packageName
                            boundVersion = "AceStream Embedded Engine 3.2.17 (0 Ads)"
                            httpApiPort = 6878
                            engineApiPort = 62062
                            Log.i(TAG, "Embedded AceStream engine running on 127.0.0.1:62062!")
                            AppLogger.s("ENGINE_MGR", "🟢 Engine đã mở cổng 127.0.0.1:62062 thành công! (Thử $i/40)")
                            isEngineReady = true
                            isStarting = false
                            mainHandler.post {
                                listener.onEngineStateChanged("READY", "Embedded AceStream Engine ready (0 Ads)")
                                listener.onEngineReady(httpApiPort, engineApiPort, boundPackage, boundVersion)
                            }
                            return@Thread
                        }
                    }
                    AppLogger.w("ENGINE_MGR", "⚠️ Engine chưa mở cổng sau 20 giây, kiểm tra phương án dự phòng...")
                } catch (e: Exception) {
                    Log.w(TAG, "Embedded engine startup failed: ${e.message}, falling back...")
                    AppLogger.e("ENGINE_MGR", "Khởi động Embedded Engine thất bại: ${e.message}", e)
                }

                // 3. Fallback: Wake up HaP if installed
                try {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage("com.streamvault.plugin.hap")
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        for (i in 1..10) {
                            Thread.sleep(600)
                            if (isSocketAlive("127.0.0.1", 62062) || isSocketAlive("127.0.0.1", 6878)) {
                                boundPackage = "com.streamvault.plugin.hap"
                                boundVersion = "AceServe 3.2.17 Headless (0 Ads)"
                                httpApiPort = 6878
                                engineApiPort = 62062
                                Log.i(TAG, "AceServe woke up on 127.0.0.1:62062!")
                                isEngineReady = true
                                isStarting = false
                                mainHandler.post {
                                    listener.onEngineStateChanged("READY", "AceServe Headless Engine ready")
                                    listener.onEngineReady(httpApiPort, engineApiPort, boundPackage, boundVersion)
                                }
                                return@Thread
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start HaP: ${e.message}")
                }

                // 4. Fallback: Legacy AIDL bind
                mainHandler.post { doLegacyBindAndStart() }
            } catch (e: Exception) {
                isStarting = false
                Log.e(TAG, "Error in bindAndStart thread", e)
            }
        }.start()
    }

    private fun isSocketAlive(host: String, port: Int): Boolean {
        return try {
            java.net.Socket().use { sock ->
                sock.connect(java.net.InetSocketAddress(host, port), 800)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun doLegacyBindAndStart() {
        mainHandler.post { listener.onEngineStateChanged("DISCOVERING", "Locating AceStream package...") }
        val pkg = resolveBestPackage()
        if (pkg == null) {
            mainHandler.post { listener.onEngineError("No compatible AceStream package found on G2") }
            return
        }
        boundPackage = pkg

        try {
            val pInfo = context.packageManager.getPackageInfo(pkg, 0)
            boundVersion = "${pInfo.versionName} (${pInfo.versionCode})"
        } catch (e: Exception) {
            boundVersion = "UNKNOWN"
        }

        val serviceIntent = Intent("org.acestream.engine.service.v0.IAceStreamEngine").apply {
            setPackage(pkg)
        }

        mainHandler.post {
            listener.onEngineStateChanged("BINDING", "Binding to $boundPackage ($boundVersion)...")
        }

        try {
            isBound = context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            Log.d(TAG, "bindService returned: $isBound for $pkg")
            if (!isBound) {
                mainHandler.post { listener.onEngineError("bindService returned false for $pkg") }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during bindService", e)
            mainHandler.post { listener.onEngineError("bindService exception: ${e.message}") }
        }
    }

    private fun updateReadyState(success: Boolean) {
        isStarting = false
        if (!success) {
            isEngineReady = false
            mainHandler.post { listener.onEngineError("Engine reported start failed (port -1)") }
            return
        }
        isEngineReady = true

        try {
            val service = engineService
            if (service != null) {
                val httpPort = service.httpApiPort
                val enginePort = service.engineApiPort
                val token = service.accessToken

                httpApiPort = if (httpPort > 0) httpPort else 6878
                engineApiPort = enginePort
                accessToken = token

                Log.d(TAG, "Engine READY! httpPort=$httpApiPort enginePort=$engineApiPort token=$accessToken")
                mainHandler.post {
                    listener.onEngineReady(httpApiPort, engineApiPort, boundPackage, boundVersion)
                }
            } else {
                mainHandler.post {
                    listener.onEngineReady(6878, 8621, boundPackage, boundVersion)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying ports after onReady", e)
            mainHandler.post {
                listener.onEngineReady(6878, 8621, boundPackage, boundVersion)
            }
        }
    }

    fun getOrResolveToken(): String? {
        if (!accessToken.isNullOrEmpty()) return accessToken
        try {
            val token = engineService?.accessToken
            if (!token.isNullOrEmpty()) {
                accessToken = token
                return token
            }
        } catch (_: Exception) {}

        // Fallback: read from process table
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ps -ef | grep libpython38"))
            val output = process.inputStream.bufferedReader().readText()
            val match = Regex("--access-token\\s+([^\\s]+)").find(output)
            if (match != null) {
                val tok = match.groupValues[1]
                accessToken = tok
                Log.i(TAG, "Resolved accessToken from process table: $tok")
                return tok
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve token from ps: ${e.message}")
        }
        return null
    }

    private fun resolveBestPackage(): String? {
        val pm = context.packageManager
        for (pkg in KNOWN_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                Log.d(TAG, "Found known package: $pkg")
                return pkg
            } catch (_: PackageManager.NameNotFoundException) {}
        }

        val intent = Intent("org.acestream.engine.service.v0.IAceStreamEngine")
        val resolves = pm.queryIntentServices(intent, 0)
        if (resolves.isNotEmpty()) {
            val found = resolves[0].serviceInfo.packageName
            Log.d(TAG, "Resolved package via intent: $found")
            return found
        }
        return null
    }

    fun unbind() {
        isEngineReady = false
        isStarting = false
        embeddedRuntime?.stop()
        embeddedRuntime = null
        if (isBound) {
            try {
                engineService?.unregisterCallback(engineCallback)
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding", e)
            }
            isBound = false
            engineService = null
        }
    }
}
