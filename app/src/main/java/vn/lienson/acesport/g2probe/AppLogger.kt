package vn.lienson.acesport.g2probe

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object AppLogger {

    private const val MAX_LOGS = 400
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val mainHandler = Handler(Looper.getMainLooper())

    enum class Level {
        DEBUG, INFO, SUCCESS, WARN, ERROR
    }

    data class LogEntry(
        val timestamp: String,
        val level: Level,
        val tag: String,
        val message: String
    ) {
        fun toDisplayString(): String {
            val levelIcon = when (level) {
                Level.SUCCESS -> "🟢 [OK]"
                Level.ERROR -> "🔴 [ERR]"
                Level.WARN -> "🟡 [WARN]"
                Level.DEBUG -> "⚙️ [DBG]"
                Level.INFO -> "ℹ️ [INFO]"
            }
            return "[$timestamp] $levelIcon [$tag] $message"
        }
    }

    private val logList = CopyOnWriteArrayList<LogEntry>()
    private val listeners = CopyOnWriteArrayList<(LogEntry) -> Unit>()

    fun log(level: Level, tag: String, message: String) {
        val ts = timeFormat.format(Date())
        val entry = LogEntry(ts, level, tag, message)

        when (level) {
            Level.ERROR -> Log.e(tag, message)
            Level.WARN -> Log.w(tag, message)
            Level.SUCCESS, Level.INFO -> Log.i(tag, message)
            Level.DEBUG -> Log.d(tag, message)
        }

        logList.add(entry)
        while (logList.size > MAX_LOGS) {
            logList.removeAt(0)
        }

        mainHandler.post {
            for (listener in listeners) {
                try {
                    listener(entry)
                } catch (_: Exception) {}
            }
        }
    }

    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg)
    fun s(tag: String, msg: String) = log(Level.SUCCESS, tag, msg)
    fun w(tag: String, msg: String) = log(Level.WARN, tag, msg)
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        val fullMsg = if (tr != null) "$msg: ${tr.message}\n${Log.getStackTraceString(tr)}" else msg
        log(Level.ERROR, tag, fullMsg)
    }
    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg)

    fun addListener(listener: (LogEntry) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (LogEntry) -> Unit) {
        listeners.remove(listener)
    }

    fun getAllLogs(): List<LogEntry> = logList.toList()

    fun getFullLogText(): String {
        val sb = StringBuilder()
        sb.append("=== ACESTREAM HUB DIAGNOSTIC LOG ===\n")
        sb.append("Generated at: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
        sb.append("Total entries: ${logList.size}\n\n")
        for (entry in logList) {
            sb.append(entry.toDisplayString()).append("\n")
        }
        return sb.toString()
    }

    fun clear() {
        logList.clear()
        i("SYSTEM", "Nhật ký hệ thống đã được làm sạch")
    }
}
