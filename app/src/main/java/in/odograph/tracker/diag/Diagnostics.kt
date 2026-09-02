package `in`.odograph.tracker.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bring-up instrumentation.
 *
 * The target box has no ADB and therefore no logcat, so the app has to report its own death.
 * A breadcrumb trail records how far start-up got, and an uncaught-exception handler ships the
 * trail plus the stack trace to a collector on the development machine, and writes both to a file
 * so they survive with no network.
 *
 * Deliberately dependency-free: anything this touches could be the thing that is broken.
 */
object Diagnostics {

    /** Development machine on the local network. Harmless if unreachable. */
    private const val COLLECTOR = "http://10.82.127.144:8000"

    private val trail = StringBuilder()
    private const val CRASH_FILE = "last-crash.txt"

    fun crumb(step: String) {
        synchronized(trail) {
            trail.append(System.currentTimeMillis()).append("  ").append(step).append('\n')
        }
        android.util.Log.i("Odograph", "crumb: $step")
    }

    fun install(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val report = runCatching { buildReport(ctx, thread, error) }
                .getOrElse { "report generation failed: $it" }
            runCatching { writeCrashFile(ctx, report) }
            runCatching { postBlocking("$COLLECTOR/crash", report) }
            previous?.uncaughtException(thread, error)
        }
        crumb("crash handler installed")
    }

    /** Sends the trail so far even without a crash, so a silent kill still leaves a trace. */
    fun shipTrail() {
        val body = synchronized(trail) { trail.toString() }
        Thread { runCatching { postBlocking("$COLLECTOR/trail", body) } }.start()
    }

    /**
     * The operating system's own record of why this process died last time.
     *
     * Strictly better than our handler for diagnosing start-up failures, because it also covers
     * the cases where the handler cannot run at all: a failure during class loading, a native
     * crash, or the system killing the process outright.
     */
    fun exitReasons(ctx: Context, limit: Int = 5): String = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "exit reasons need API 30+"
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val reasons = am.getHistoricalProcessExitReasons(ctx.packageName, 0, limit)
        if (reasons.isEmpty()) return "no recorded process exits"
        buildString {
            reasons.forEach { info ->
                append("--- exit ---\n")
                append("reason      : ").append(reasonName(info.reason))
                append(" (").append(info.reason).append(")\n")
                append("description : ").append(info.description ?: "-").append('\n')
                append("status      : ").append(info.status).append('\n')
                append("importance  : ").append(info.importance).append('\n')
                append("when        : ").append(info.timestamp).append('\n')
                if (info.reason == ApplicationExitInfo.REASON_CRASH ||
                    info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    info.reason == ApplicationExitInfo.REASON_ANR
                ) {
                    val trace = runCatching {
                        info.traceInputStream?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                    if (!trace.isNullOrBlank()) {
                        append("trace       :\n").append(trace.take(8000)).append('\n')
                    }
                }
            }
        }
    }.getOrElse { "exit reasons unavailable: $it" }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH (uncaught exception)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE CRASH"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE RESOURCES"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER STOPPED"
        else -> "UNKNOWN"
    }

    /** Ships the OS exit record immediately, before anything risky runs. */
    fun shipExitReasons(ctx: Context) {
        val body = "ODOGRAPH EXIT REASONS\ndevice: ${Build.MANUFACTURER} ${Build.MODEL} " +
            "sdk=${Build.VERSION.SDK_INT}\n\n" + exitReasons(ctx)
        Thread { runCatching { postBlocking("$COLLECTOR/trail", body) } }.start()
    }

    fun lastCrash(ctx: Context): String? =
        runCatching { File(ctx.filesDir, CRASH_FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clearCrash(ctx: Context) {
        runCatching { File(ctx.filesDir, CRASH_FILE).delete() }
    }

    private fun buildReport(ctx: Context, thread: Thread, error: Throwable): String {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            append("ODOGRAPH CRASH\n")
            append("when      : ").append(System.currentTimeMillis()).append('\n')
            append("thread    : ").append(thread.name).append('\n')
            append("device    : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append("  sdk=").append(Build.VERSION.SDK_INT).append('\n')
            append("package   : ").append(ctx.packageName).append('\n')
            append("\n--- breadcrumbs ---\n")
            append(synchronized(trail) { trail.toString() })
            append("\n--- stack ---\n")
            append(stack)
        }
    }

    private fun writeCrashFile(ctx: Context, report: String) {
        File(ctx.filesDir, CRASH_FILE).writeText(report)
    }

    /**
     * Runs the request on its own thread and waits briefly: the process is about to die, but a
     * network call on the main thread would throw NetworkOnMainThreadException inside the very
     * handler that is trying to report the problem.
     */
    private fun postBlocking(url: String, body: String) {
        val worker = Thread {
            runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 2_500
                    readTimeout = 2_500
                    doOutput = true
                    setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
                conn.responseCode
                conn.disconnect()
            }
        }
        worker.start()
        worker.join(4_000)
    }
}
