package vn.lienson.acesport.g2probe.engine

import android.content.Context
import android.util.Log
import vn.lienson.acesport.g2probe.AppLogger
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object AceEngineDownloader {

    private const val TAG = "AceEngineDownloader"

    // Primary & Fallback release URLs for the Headless Linux ARM Engine runtime
    // (Clean Core Option 1: 0 embedded proprietary binaries in APK, 0 Android GUI, 0 AdMob ads)
    const val PRIMARY_ENGINE_URL = "https://github.com/hongson117/aceHub/releases/download/v1.0.4/ace-engine-armv7.zip"
    const val MIRROR_ENGINE_URL = "https://ghproxy.net/https://github.com/hongson117/aceHub/releases/download/v1.0.4/ace-engine-armv7.zip"
    const val FALLBACK_ENGINE_URL = "https://github.com/hongson117/aceHub/releases/download/v1.0.3/ace-engine-armv7.zip"

    // Expected cryptographic SHA-256 hash for verified Linux ARM headless engine archive
    const val EXPECTED_SHA256 = "A0C0617A4C54D44210533AE0EC6FBCAC933BDFB065FDDF5AD4B2587BC5448D52"

    // Expected minimum file size for the verified Linux ARM headless engine archive (~42MB)
    const val MIN_EXPECTED_SIZE = 40_000_000L

    interface DownloadListener {
        fun onDownloadProgress(percent: Int, downloadedBytes: Long, totalBytes: Long, speedKbps: Long)
        fun onDownloadComplete(outputFile: File)
        fun onDownloadError(error: String)
    }

    @Throws(IOException::class)
    fun downloadEngineWithFallbacks(
        context: Context,
        listener: DownloadListener? = null
    ): File {
        val urls = listOf(
            PRIMARY_ENGINE_URL to "Máy chủ chính (GitHub CDN v1.0.3)",
            MIRROR_ENGINE_URL to "Máy chủ dự phòng (Asia Mirror CDN v1.0.3)",
            FALLBACK_ENGINE_URL to "Máy chủ dự phòng (GitHub CDN v1.0.2)"
        )
        var lastException: Exception? = null

        for ((index, pair) in urls.withIndex()) {
            val (url, label) = pair
            try {
                AppLogger.i("DOWNLOAD", "Bắt đầu tải Engine Linux sạch từ $label...")
                Log.i(TAG, "Trying download source [$index]: $url")
                return downloadFile(context, url, listener)
            } catch (e: Exception) {
                lastException = e
                AppLogger.w("DOWNLOAD", "Tải từ $label gặp sự cố (${e.message}). Đang chuyển hướng dự phòng...")
                Log.w(TAG, "Download attempt failed for $url: ${e.message}")
            }
        }

        val errMsg = "Không thể tải Engine Linux từ mọi nguồn khả dụng: ${lastException?.message}"
        AppLogger.e("DOWNLOAD", errMsg, lastException)
        throw IOException(errMsg, lastException)
    }

    @Throws(IOException::class)
    fun downloadFile(
        context: Context,
        downloadUrl: String,
        listener: DownloadListener? = null
    ): File {
        val destFile = File(context.cacheDir, "ace_engine_linux_armv7.download")
        if (destFile.exists()) destFile.delete()

        var connection: HttpURLConnection? = null
        try {
            var currentUrl = downloadUrl
            var redirectCount = 0
            while (redirectCount < 6) {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15000
                    readTimeout = 45000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android TV; AceHub-CleanCore)")
                    setRequestProperty("Accept", "*/*")
                }
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) {
                    val location = connection.getHeaderField("Location") ?: break
                    connection.disconnect()
                    currentUrl = if (location.startsWith("http")) location else URL(url, location).toString()
                    redirectCount++
                    Log.d(TAG, "Following redirect [$redirectCount] to: $currentUrl")
                    continue
                }
                break
            }

            val conn = connection ?: throw IOException("Không thể thiết lập kết nối tới $downloadUrl")
            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                throw IOException("Máy chủ trả lời mã HTTP lỗi: $responseCode")
            }

            val totalBytes = conn.contentLengthLong
            val totalMb = if (totalBytes > 0) totalBytes / (1024 * 1024) else 42
            AppLogger.i("DOWNLOAD", "Kết nối thành công. Kích thước gói Engine: ~$totalMb MB")

            var downloadedBytes = 0L
            val startTime = System.currentTimeMillis()
            var lastSpeedTime = startTime
            var lastSpeedBytes = 0L
            var currentSpeedKbps = 0L
            var lastPercent = -1

            BufferedInputStream(conn.inputStream, 64 * 1024).use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastSpeedTime >= 1000) {
                            val timeDiffSec = (now - lastSpeedTime) / 1000.0
                            val bytesDiff = downloadedBytes - lastSpeedBytes
                            currentSpeedKbps = ((bytesDiff / 1024.0) / timeDiffSec).toLong()
                            lastSpeedBytes = downloadedBytes
                            lastSpeedTime = now
                        }

                        if (totalBytes > 0) {
                            val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                listener?.onDownloadProgress(percent, downloadedBytes, totalBytes, currentSpeedKbps)
                            }
                        }
                    }
                    output.flush()
                }
            }

            if (destFile.length() < MIN_EXPECTED_SIZE) {
                destFile.delete()
                throw IOException("Tệp tải về kích thước không hợp lệ (${destFile.length()} bytes < $MIN_EXPECTED_SIZE bytes)")
            }

            AppLogger.i("DOWNLOAD", "Đang xác thực toàn vẹn mã băm SHA-256...")
            val actualHash = calculateSha256(destFile)
            if (!actualHash.equals(EXPECTED_SHA256, ignoreCase = true)) {
                destFile.delete()
                val errMsg = "Mã băm SHA-256 không khớp! Kỳ vọng: $EXPECTED_SHA256, Thực tế: $actualHash"
                AppLogger.e("DOWNLOAD", errMsg)
                throw IOException(errMsg)
            }
            AppLogger.s("DOWNLOAD", "Xác thực SHA-256 thành công (Trùng khớp 100%): ${actualHash.take(16)}...")

            val finalMb = String.format(java.util.Locale.US, "%.1f", destFile.length() / (1024.0 * 1024.0))
            AppLogger.s("DOWNLOAD", "Đã tải xong và xác thực toàn vẹn Engine Linux sạch ($finalMb MB)!")
            listener?.onDownloadComplete(destFile)
            return destFile
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            listener?.onDownloadError(e.message ?: "Unknown download error")
            throw IOException("Lỗi tải Engine Linux sạch: ${e.message}", e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val sb = StringBuilder()
        for (b in digest.digest()) {
            sb.append(String.format("%02X", b))
        }
        return sb.toString()
    }

    @Throws(IOException::class)
    fun unpackEngine(archiveFile: File, destinationDir: File) {
        if (!destinationDir.exists() && !destinationDir.mkdirs()) {
            throw IOException("Cannot create destination: ${destinationDir.absolutePath}")
        }
        val destinationPath = destinationDir.canonicalPath + File.separator

        ZipInputStream(FileInputStream(archiveFile)).use { input ->
            var entry: ZipEntry?
            val buffer = ByteArray(128 * 1024)
            while (input.nextEntry.also { entry = it } != null) {
                val name = entry!!.name
                // Strip legacy prefix if any
                val relativePath = when {
                    name.startsWith("assets/aceserve/") -> name.removePrefix("assets/aceserve/")
                    name.startsWith("assets/") -> name.removePrefix("assets/")
                    else -> name
                }

                val target = File(destinationDir, relativePath)
                val targetPath = target.canonicalPath
                if (!targetPath.startsWith(destinationPath)) {
                    continue // prevent zip slip
                }

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
        Log.i(TAG, "Engine unpacked cleanly to ${destinationDir.absolutePath}")
    }
}
