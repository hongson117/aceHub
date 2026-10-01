package vn.lienson.acesport.g2probe.engine

import android.content.Context
import android.os.Build
import android.util.Log
import vn.lienson.acesport.g2probe.AppLogger
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
        private const val ABI_ARM32 = "armeabi-v7a"
    }

    private val appContext = context.applicationContext
    // Always use armeabi-v7a to match 32-bit Linux engine & bundled libacepython.so
    val abi: String = ABI_ARM32
    private var process: Process? = null
    private var logThread: Thread? = null
    @Volatile private var stopping = false

    fun rootDir(): File {
        return File(appContext.filesDir, "aceserve/$abi")
    }

    fun cacheDir(): File {
        return File(appContext.cacheDir, "aceserve")
    }

    private fun zipName(): String {
        return "ace-armeabi-v7a.zip"
    }

    @Synchronized
    @Throws(IOException::class)
    fun prepare(progressListener: AceEngineDownloader.DownloadListener? = null) {
        val root = rootDir()
        val marker = File(root, ".prepared-ace-$abi-v6")

        // 1. Strict Fast Check: Verify marker AND all essential binary components
        if (marker.exists() && isRuntimeIntact(root)) {
            AppLogger.s("ENGINE", "Runtime Engine Linux sạch đã có sẵn và toàn vẹn (Khởi động tức thì).")
            updateMainScript(root)
            return
        }

        AppLogger.i("ENGINE", "Chuẩn bị nạp mới môi trường AceStream Linux sạch ($abi)...")
        val staging = File(appContext.filesDir, "aceserve/${abi}_staging")
        deleteRecursively(staging)
        if (!staging.mkdirs() && !staging.isDirectory) {
            throw IOException("Cannot create staging directory: ${staging.absolutePath}")
        }

        try {
            AppLogger.i("ENGINE", "Bắt đầu tải Engine Linux sạch (0 quảng cáo) từ máy chủ phát hành...")
            val downloaded = AceEngineDownloader.downloadEngineWithFallbacks(appContext, progressListener)

            AppLogger.i("ENGINE", "Đang giải nén Engine Linux vào thư mục tạm...")
            AceEngineDownloader.unpackEngine(downloaded, staging)
            downloaded.delete()

            // Verify integrity of unpacked staging files
            if (!isRuntimeIntact(staging)) {
                deleteRecursively(staging)
                throw IOException("Gói giải nén bị thiếu các tệp thành phần bắt buộc (acestreamengine, Core.so, CoreApp.so, cacert.pem)")
            }

            // Atomic switch from staging to root
            deleteRecursively(root)
            if (!staging.renameTo(root)) {
                staging.copyRecursively(root, overwrite = true)
                deleteRecursively(staging)
            }

            marker.createNewFile()
            AppLogger.s("ENGINE", "Runtime Engine Linux sạch đã sẵn sàng tại: ${root.absolutePath}")
        } catch (e: Exception) {
            deleteRecursively(staging)
            AppLogger.e("ENGINE", "Lỗi nạp Engine Linux: ${e.message}", e)
            throw IOException("Could not prepare engine: ${e.message}", e)
        }

        updateMainScript(root)
    }

    private fun isRuntimeIntact(dir: File): Boolean {
        return File(dir, "acestreamengine").exists() &&
               File(dir, "Core.so").exists() &&
               File(dir, "CoreApp.so").exists() &&
               File(dir, "cacert.pem").exists() &&
               File(dir, "python/lib/stdlib").exists()
    }

    private fun updateMainScript(root: File) {
        try {
            AssetCopier.copyFile(appContext, "aceserve/main_android.py", File(root, "main_android.py"))
            AppLogger.d("ENGINE", "Đã cập nhật script điều phối main_android.py")
        } catch (e: Exception) {
            AppLogger.w("ENGINE", "Không thể chép main_android.py: ${e.message}")
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        if (process != null && process?.isAlive == true) {
            AppLogger.i("ENGINE", "Tiến trình Engine đang chạy sẵn (Alive)")
            return
        }

        val root = rootDir()
        val cache = cacheDir()
        if (!cache.exists() && !cache.mkdirs()) throw IOException("Cannot create $cache")
        val androidInfo = File(root, "android-runtime.json")
        AceServeAndroidInfo.write(appContext, abi, root, cache, androidInfo)

        val runner = nativeRunner()
        AppLogger.i("ENGINE", "Kiểm tra runner thực thi: ${runner.name} (${if (runner.exists()) "CÓ SẴN" else "THIẾU!"})")
        if (!runner.exists()) {
            AppLogger.e("ENGINE", "THIẾU runner libacepython.so tại: ${runner.absolutePath}")
            throw IOException("Missing native Python runner at: ${runner.absolutePath}")
        }

        val mainPy = File(root, "main_android.py")
        if (!mainPy.exists()) {
            AppLogger.e("ENGINE", "THIẾU main_android.py tại: ${mainPy.absolutePath}")
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
        AppLogger.i("ENGINE", "Khởi chạy lệnh Engine: libacepython.so main_android.py (Cache 100MB)")

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
        AppLogger.s("ENGINE", "Tiến trình Engine đã khởi động thành công!")

        logThread = Thread {
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: continue
                        Log.d("EmbeddedAceEngine", l)
                        if (l.contains("started on port") || l.contains("ready") || l.contains("Traceback") || l.contains("Error")) {
                            AppLogger.d("ENGINE_PROC", l)
                        }
                    }
                }
            } catch (e: Exception) {
                if (!stopping) AppLogger.w("ENGINE", "Đóng luồng đọc log Engine: ${e.message}")
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
        AppLogger.i("ENGINE", "Đang dừng tiến trình Engine...")
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
        AppLogger.s("ENGINE", "Tiến trình Engine đã dừng hoàn toàn.")
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
                    if (target.name.endsWith(".so") || target.name == "acestreamengine") {
                        target.setExecutable(true, false)
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
