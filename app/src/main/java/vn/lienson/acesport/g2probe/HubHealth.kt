package vn.lienson.acesport.g2probe

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * v1.4.4 hardening helpers.
 *
 * 1.4.3 on the FPT Play 650 (Android 11, 1.8 GB) wedged after ~2-3 h: the process stayed alive with the
 * :8000 listen socket open but nothing accepted (LAN + localhost connects timed out), so START_STICKY never
 * fired and startService()/activity launches were no-ops. Only a full force-stop cleared it. These helpers
 * let the app detect that on its own and restart itself as a fresh process.
 */
object HubHealth {
    private const val TAG = "HubHealth"

    /** Real HTTP probe of our own server: connect + GET /status + first response line within ~8 s. */
    fun probe(port: Int, connectMs: Int = 3000, readMs: Int = 5000): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), connectMs)
            s.soTimeout = readMs
            s.getOutputStream().apply {
                write("GET /status HTTP/1.0\r\nHost: 127.0.0.1\r\nX-AceHub-SelfProbe: 1\r\n\r\n".toByteArray())
                flush()
            }
            val line = s.getInputStream().bufferedReader().readLine() ?: ""
            line.startsWith("HTTP/1.") && line.contains(" 200")
        }
    } catch (e: Exception) {
        Log.w(TAG, "self-probe :$port failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }
}

object HubRestarter {
    private const val TAG = "HubRestarter"
    private const val PREFS = "acehub_health"
    const val ACTION_HEARTBEAT = "vn.lienson.acesport.g2probe.HEARTBEAT"
    private const val RC_RESTART = 4401
    private const val RC_HEARTBEAT = 4402
    private const val HEARTBEAT_MS = 10 * 60_000L
    /** Never hard-restart more than this many times per window (no restart storms when the network is down). */
    private const val MAX_RESTARTS = 4
    private const val WINDOW_MS = 30 * 60_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun startIntent(ctx: Context, action: String, rc: Int): PendingIntent {
        val i = Intent(ctx, G2OrchestratorService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            PendingIntent.getForegroundService(ctx, rc, i, flags)
        else PendingIntent.getService(ctx, rc, i, flags)
    }

    private fun setAlarm(ctx: Context, atElapsed: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            val exactOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (exactOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsed, pi)
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsed, pi)
            else am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsed, pi)
        } catch (e: Exception) {
            Log.w(TAG, "alarm failed: ${e.message}")
        }
    }

    /** Wake the hub service in [delayMs] (survives our own process being killed). */
    fun scheduleRestart(ctx: Context, delayMs: Long) =
        setAlarm(ctx, SystemClock.elapsedRealtime() + delayMs, startIntent(ctx, "ACTION_START_HUB", RC_RESTART))

    /** Self-heartbeat: re-arms itself every run; if the process ever dies without a sticky restart, it comes back. */
    fun armHeartbeat(ctx: Context) =
        setAlarm(ctx, SystemClock.elapsedRealtime() + HEARTBEAT_MS, startIntent(ctx, ACTION_HEARTBEAT, RC_HEARTBEAT))

    fun restartsInWindow(ctx: Context): Int {
        val now = System.currentTimeMillis()
        return (prefs(ctx).getString("restarts", "") ?: "").split(",").mapNotNull { it.toLongOrNull() }
            .count { now - it < WINDOW_MS }
    }

    fun lastRestart(ctx: Context): JSONObject = JSONObject().apply {
        val p = prefs(ctx)
        put("reason", p.getString("last_reason", "") ?: "")
        put("at_ms", p.getLong("last_at", 0L))
        put("in_last_30min", restartsInWindow(ctx))
    }

    /** True when another hard restart is allowed right now. */
    fun allowed(ctx: Context) = restartsInWindow(ctx) < MAX_RESTARTS

    /**
     * Fresh-process restart: arm the alarm, stop the engine (and any orphaned engine), then kill our own
     * process. START_STICKY / the alarm / BoxStation's supervisor bring the hub back with clean state.
     */
    fun hardRestart(ctx: Context, reason: String) {
        val now = System.currentTimeMillis()
        val p = prefs(ctx)
        val kept = (p.getString("restarts", "") ?: "").split(",").mapNotNull { it.toLongOrNull() }
            .filter { now - it < WINDOW_MS } + now
        p.edit().putString("restarts", kept.joinToString(",")).putString("last_reason", reason)
            .putLong("last_at", now).commit()
        Log.e(TAG, "HARD RESTART: $reason")
        scheduleRestart(ctx, 4_000)
        try { G2OrchestratorService.instance?.engineManager?.unbind() } catch (_: Throwable) {}
        EngineReaper.killOrphans()
        Process.killProcess(Process.myPid())
    }
}

/** Finds embedded AceStream engine processes of OUR uid (left behind by a killed parent) and kills them. */
object EngineReaper {
    private const val TAG = "EngineReaper"
    fun enginePids(): List<Int> {
        val me = Process.myPid()
        val uid = Process.myUid()
        val out = ArrayList<Int>()
        File("/proc").listFiles()?.forEach { f ->
            val pid = f.name.toIntOrNull() ?: return@forEach
            if (pid == me) return@forEach
            try {
                val cmd = File(f, "cmdline").readText()
                if (!cmd.contains("main_android.py")) return@forEach
                val st = File(f, "status").readLines().firstOrNull { it.startsWith("Uid:") } ?: return@forEach
                if (st.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() == uid) out += pid
            } catch (_: Exception) {}
        }
        return out
    }

    fun killOrphans(): Int {
        val pids = enginePids()
        pids.forEach { Log.w(TAG, "killing engine pid $it"); Process.killProcess(it) }
        return pids.size
    }
}

object HubDiagnostics {
    private val tokenRe = Regex("(?i)(token[\"'=:\\s]+|access-token\\s+)[A-Za-z0-9._\\-]{6,}")

    fun uptimeSec(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000 else -1

    private fun count(dir: String) = File(dir).list()?.size ?: -1

    fun build(svc: G2OrchestratorService?, proxy: G2StreamProxyServer?, withLog: Boolean): JSONObject {
        val rt = Runtime.getRuntime()
        val j = JSONObject()
        j.put("app_version", BuildConfig.VERSION_NAME)
        j.put("version_code", BuildConfig.VERSION_CODE)
        j.put("pid", Process.myPid())
        j.put("uptime_s", uptimeSec())
        j.put("fds", count("/proc/self/fd"))
        j.put("threads", count("/proc/self/task"))
        j.put("heap_used_kb", (rt.totalMemory() - rt.freeMemory()) / 1024)
        j.put("heap_max_kb", rt.maxMemory() / 1024)
        proxy?.let { j.put("server", it.diagJson()) }
        svc?.let { j.put("health", it.healthJson()) }
        j.put("engine_pids", JSONArray(EngineReaper.enginePids()))
        svc?.let { j.put("last_self_restart", HubRestarter.lastRestart(it)) }
        if (svc != null && Build.VERSION.SDK_INT >= 30) {
            try {
                val am = svc.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val arr = JSONArray()
                am.getHistoricalProcessExitReasons(svc.packageName, 0, 6).forEach { e ->
                    arr.put(JSONObject().apply {
                        put("time_ms", e.timestamp); put("reason", e.reason); put("status", e.status)
                        put("importance", e.importance); put("pss_kb", e.pss); put("rss_kb", e.rss)
                        put("description", e.description ?: "")
                    })
                }
                j.put("exit_reasons", arr)
            } catch (e: Exception) { j.put("exit_reasons_error", e.message ?: "") }
        }
        if (withLog) {
            try {
                val p = ProcessBuilder("logcat", "-d", "-t", "150", "--pid=${Process.myPid()}").redirectErrorStream(true).start()
                val lines = p.inputStream.bufferedReader().readLines().takeLast(150).map { tokenRe.replace(it) { m -> m.groupValues[1] + "***" } }
                p.waitFor()
                j.put("logcat", JSONArray(lines))
            } catch (e: Exception) { j.put("logcat_error", e.message ?: "") }
        }
        return j
    }
}
