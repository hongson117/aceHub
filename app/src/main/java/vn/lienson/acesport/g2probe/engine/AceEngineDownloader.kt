package vn.lienson.acesport.g2probe.engine

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object AceEngineDownloader {

    private const val TAG = "AceEngineDownloader"

    // Official AceStream download endpoint (Clean-Room / Open-Source compliance)
    const val OFFICIAL_ENGINE_URL = "https://download.acestream.media/products/acestream-engine/android/armv7/latest"

    interface DownloadListener {
        fun onDownloadProgress(percent: Int, downloadedBytes: Long, totalBytes: Long)
        fun onDownloadComplete(outputFile: File)
        fun onDownloadError(error: String)
    }

    @Throws(IOException::class)
    fun downloadOfficialEngine(
        context: Context,
        downloadUrl: String = OFFICIAL_ENGINE_URL,
        listener: DownloadListener? = null
    ): File {
        val destFile = File(context.cacheDir, "official_acestream_engine.download")
        Log.i(TAG, "Starting clean download from official source: $downloadUrl")

        var connection: HttpURLConnection? = null
        try {
            var currentUrl = downloadUrl
            var redirectCount = 0
            while (redirectCount < 5) {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android TV; AceStreamSolver)")
                }
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) {
                    val location = connection.getHeaderField("Location") ?: break
                    connection.disconnect()
                    currentUrl = if (location.startsWith("http")) location else URL(url, location).toString()
                    redirectCount++
                    Log.d(TAG, "Following redirect to: $currentUrl")
                    continue
                }
                break
            }

            val conn = connection ?: throw IOException("Could not establish connection to $downloadUrl")
            val totalBytes = conn.contentLengthLong
            Log.i(TAG, "Connected to official engine. Content-Length: $totalBytes bytes")

            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    var totalRead: Long = 0
                    var lastPercent = -1

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0) {
                            val percent = ((totalRead * 100) / totalBytes).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                listener?.onDownloadProgress(percent, totalRead, totalBytes)
                            }
                        }
                    }
                    output.flush()
                }
            }

            Log.i(TAG, "Engine download finished successfully (${destFile.length()} bytes)")
            listener?.onDownloadComplete(destFile)
            return destFile
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            listener?.onDownloadError(e.message ?: "Unknown download error")
            throw IOException("Failed to download official engine: ${e.message}", e)
        } finally {
            connection?.disconnect()
        }
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
                // Handle both raw engine zip and official APK structure
                val relativePath = if (name.startsWith("assets/aceserve/")) {
                    name.removePrefix("assets/aceserve/")
                } else if (name.startsWith("assets/")) {
                    name.removePrefix("assets/")
                } else {
                    name
                }

                val target = File(destinationDir, relativePath)
                val targetPath = target.canonicalPath
                if (!targetPath.startsWith(destinationPath)) {
                    continue // skip directory traversal
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
                    // If native executable, set execute bits
                    if (target.name.endsWith(".so") || target.name == "acestreamengine") {
                        target.setExecutable(true, false)
                    }
                }
            }
        }
        Log.i(TAG, "Engine unpacked cleanly to ${destinationDir.absolutePath}")
    }
}
