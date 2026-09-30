package vn.lienson.acesport.g2probe.engine

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class EmbeddedAceRuntime(private val context: Context) {

    companion object {
        private const val TAG = "EmbeddedAceRuntime"
        private const val ABI_ARM64 = "arm64-v8a"
        private const val ABI_ARM32 = "armeabi-v7a"
    }

    private val appContext = context.applicationContext
    val abi: String = selectSupportedAbi() ?: ABI_ARM32
    private var process: Process? = null
    private var logThread: Thread? = null
    @Volatile private var stopping = false

    private fun selectSupportedAbi(): String? {
        for (supported in Build.SUPPORTED_ABIS) {
            if (ABI_ARM32 == supported) return ABI_ARM32
            if (ABI_ARM64 == supported) return ABI_ARM64
        }
        return null
    }

    fun rootDir(): File {
        return File(appContext.filesDir, "aceserve/$abi")
    }

    fun cacheDir(): File {
        return File(appContext.cacheDir, "aceserve")
    }

    private fun zipName(): String {
        return if (ABI_ARM32 == abi) "ace-armeabi-v7a.zip" else "ace-arm64-v8a.zip"
    }

    @Synchronized
    @Throws(IOException::class)
    fun prepare() {
        val root = rootDir()
        val marker = File(root, ".prepared-ace-$abi-v4")
        if (!marker.exists()) {
            Log.i(TAG, "Preparing embedded AceStream engine for ABI $abi...")
            deleteRecursively(root)
            if (!root.mkdirs() && !root.isDirectory) {
                throw IOException("Cannot create $root")
            }

            // Check if zip exists in assets; otherwise download directly from official source
            val assetZipPath = "aceserve/$abi/${zipName()}"
            val tempZip = File(appContext.cacheDir, zipName())
            var unpacked = false
            try {
                AssetCopier.copyFile(appContext, assetZipPath, tempZip)
                unzip(tempZip, root)
                tempZip.delete()
                unpacked = true
                Log.i(TAG, "Unpacked embedded AceStream runtime from assets")
            } catch (_: Exception) {
                Log.i(TAG, "No embedded engine asset found. Downloading official runtime...")
            }

            if (!unpacked) {
                try {
                    val downloaded = AceEngineDownloader.downloadOfficialEngine(appContext)
                    AceEngineDownloader.unpackEngine(downloaded, root)
                    downloaded.delete()
                    unpacked = true
                    Log.i(TAG, "Official engine downloaded and unpacked successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to download official engine: ${e.message}", e)
                    throw IOException("Could not prepare engine: ${e.message}", e)
                }
            }

            if (unpacked) {
                marker.createNewFile()
                Log.i(TAG, "AceStream runtime prepared successfully at $root")
            }
        }

        // Always copy main_android.py
        try {
            AssetCopier.copyFile(appContext, "aceserve/main_android.py", File(root, "main_android.py"))
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy main_android.py from assets: ${e.message}")
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        if (process != null && process?.isAlive == true) {
            Log.i(TAG, "Engine process already running")
            return
        }

        val root = rootDir()
        val cache = cacheDir()
        if (!cache.exists() && !cache.mkdirs()) throw IOException("Cannot create $cache")
        val androidInfo = File(root, "android-runtime.json")
        AceServeAndroidInfo.write(appContext, abi, root, cache, androidInfo)

        val runner = nativeRunner()
        if (!runner.exists()) {
            throw IOException("Missing native Python runner at: ${runner.absolutePath}")
        }

        val mainPy = File(root, "main_android.py")
        if (!mainPy.exists()) {
            throw IOException("Missing main_android.py at: ${mainPy.absolutePath}")
        }

        val command = listOf(
            runner.absolutePath,
            mainPy.absolutePath,
            "--bind-all",
            "--live-cache-type", "memory",
            "--live-mem-cache-size", "104857600",
            "--disable-sentry",
            "--log-stdout",
            "--disable-upnp"
        )
        Log.i(TAG, "Starting engine command: $command")

        val builder = ProcessBuilder(command)
        builder.directory(root)
        builder.redirectErrorStream(true)

        val env = builder.environment()
        env["ACE_ROOT"] = root.absolutePath
        env["ACE_CACHE_DIR"] = cache.absolutePath
        env["ACE_ANDROID_INFO"] = androidInfo.absolutePath
        env["ACESTREAM_HOME"] = root.absolutePath
        env["ANDROID_ROOT"] = "/system"
        env["ANDROID_DATA"] = File(root, "android-data").absolutePath
        env["PYTHONHOME"] = File(root, "python").absolutePath
        env["PYTHONPATH"] = pythonPath(root)
        env["LD_LIBRARY_PATH"] = ldLibraryPath(root)
        env["FROZENLIST_NO_EXTENSIONS"] = "1"
        env["MULTIDICT_NO_EXTENSIONS"] = "1"
        env["YARL_NO_EXTENSIONS"] = "1"
        env["TEMP"] = File(root, "tmp").absolutePath
        env["PATH"] = File(root, "python/bin").absolutePath + ":/system/bin"

        stopping = false
        val proc = builder.start()
        process = proc

        logThread = Thread {
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.d("EmbeddedAceEngine", line ?: "")
                    }
                }
            } catch (e: Exception) {
                if (!stopping) Log.w(TAG, "Log stream ended: ${e.message}")
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        val proc = process ?: return
        stopping = true
        proc.destroy()
        try {
            if (!proc.waitFor(3000, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            proc.destroyForcibly()
        }
        process = null
    }

    @Synchronized
    fun isRunning(): Boolean {
        return process?.isAlive == true
    }

    private fun nativeRunner(): File {
        return File(appContext.applicationInfo.nativeLibraryDir, "libacepython.so")
    }

    private fun pythonPath(root: File): String {
        return File(root, "python/lib/stdlib").absolutePath + ":" +
                File(root, "python/lib/modules").absolutePath + ":" +
                File(root, "data").absolutePath + ":" +
                File(root, "modules.zip").absolutePath + ":" +
                File(root, "eggs-unpacked").absolutePath + ":" +
                File(root, "lib").absolutePath
    }

    private fun ldLibraryPath(root: File): String {
        return File(root, "python/lib").absolutePath + ":" +
                File(root, "lib").absolutePath + ":" +
                File(root, "acestreamengine").absolutePath + ":" +
                appContext.applicationInfo.nativeLibraryDir +
                ":/system/lib64:/system/lib"
    }

    private fun unzip(zip: File, destination: File) {
        val destinationPath = destination.canonicalPath + File.separator
        ZipInputStream(FileInputStream(zip)).use { input ->
            var entry: ZipEntry?
            val buffer = ByteArray(128 * 1024)
            while (input.nextEntry.also { entry = it } != null) {
                val target = File(destination, entry!!.name)
                val targetPath = target.canonicalPath
                if (!targetPath.startsWith(destinationPath)) throw IOException("Unsafe zip entry: ${entry!!.name}")
                if (entry!!.isDirectory) {
                    if (!target.exists() && !target.mkdirs()) throw IOException("Cannot create $target")
                } else {
                    val parent = target.parentFile
                    if (parent != null && !parent.exists() && !parent.mkdirs()) throw IOException("Cannot create $parent")
                    FileOutputStream(target).use { output ->
                        var read: Int
                        while (input.read(buffer).also { read = it } >= 0) {
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
        }
    }

    private fun deleteRecursively(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }
}
