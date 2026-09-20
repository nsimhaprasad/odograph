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

    /** Memory ceiling for the breadcrumb trail; past it, the oldest half is dropped. */
    private const val TRAIL_MAX_CHARS = 16_384

    /**
     * Records a step, and cannot itself fail.
     *
     * Diagnostics are called from the places that are already going wrong — inside catch blocks,
     * inside the crash handler, inside the recovery path of a failed load — so a crumb that throws
     * converts a contained failure into an uncontained one and destroys the very report that would
     * have explained it. Every part of this is therefore optional: the trail if the buffer
     * misbehaves, the log line if the platform is not there to take it.
     */
    fun crumb(step: String) {
        try {
            synchronized(trail) {
                trail.append(System.currentTimeMillis()).append("  ").append(step).append('\n')
                if (trail.length > TRAIL_MAX_CHARS) trail.delete(0, trail.length / 2)
            }
        } catch (_: Throwable) {
            // A breadcrumb is never worth a failure.
        }
        try {
            android.util.Log.i("Odograph", "crumb: $step")
        } catch (_: Throwable) {
            // No logger here (a plain JVM test, a stripped runtime). The trail above still has it.
        }
    }

    fun install(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val report = runCatching { buildReport(ctx, thread, error) }
                .getOrElse { "report generation failed: $it" }
            runCatching { writeCrashFile(ctx, report) }
            runCatching { postBlocking("$COLLECTOR/crash", report) }
            runCatching { scheduleRestart(ctx) }
            previous?.uncaughtException(thread, error)
        }
        crumb("crash handler installed")
    }

    /**
     * How many crashes in a row may be answered by relaunching before the app is left down.
     *
     * A crash the app can come back from is worth coming back from. A crash it meets again
     * immediately is not: relaunching into it turns one failure into a strobing loop that the
     * driver cannot escape and that flattens the car's battery overnight. Three attempts is enough
     * to ride out something incidental and few enough to notice when it is not.
     */
    private const val MAX_RESTARTS = 3

    /** Crashes further apart than this are unrelated, and the count starts again. */
    private const val RESTART_WINDOW_MS = 3 * 60_000L

    /**
     * Offers the driver a way back after the app dies.
     *
     * This is a car's instrument cluster, not a phone app: there is no home screen worth returning
     * to at seventy in traffic, and no reasonable expectation that the driver hunts for an icon.
     * The obvious answer — relaunch the activity ourselves — is not available. Android has blocked
     * background activity starts since 10, and it blocks this one specifically: a foreground
     * service is not an exemption, and the attempt comes back as BAL_BLOCK with the launch
     * discarded. So the app cannot put itself back on screen, and code that pretends otherwise
     * would only look like it worked.
     *
     * A notification can, because tapping one is the driver's own action and that start is
     * allowed. One tap instead of none, which is the best that is actually on offer.
     *
     * The part that matters most needs neither: the recorder is a foreground service, so the
     * system restarts it within seconds, and it now resumes the drive it was interrupted in the
     * middle of rather than abandoning it. The recording survives a crash whether or not anybody
     * touches the screen.
     */
    private fun scheduleRestart(ctx: Context) {
        val prefs = ctx.getSharedPreferences("odograph-crash", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val since = now - prefs.getLong("first_at", 0L)
        val streak = if (since in 0..RESTART_WINDOW_MS) prefs.getInt("streak", 0) + 1 else 1
        prefs.edit()
            .putInt("streak", streak)
            .putLong("first_at", if (streak == 1) now else prefs.getLong("first_at", now))
            .apply()

        if (streak > MAX_RESTARTS) {
            crumb("crash $streak in $RESTART_WINDOW_MS ms — leaving the app down rather than looping")
            return
        }

        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = android.app.PendingIntent.getActivity(
            ctx, 0, launch,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val manager =
            ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    RECOVERY_CHANNEL, "Odograph recovery",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val notification = android.app.Notification.Builder(ctx, RECOVERY_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Odograph stopped")
            .setContentText("The drive is still being recorded. Tap to bring the display back.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        manager.notify(RECOVERY_NOTIFICATION, notification)
        crumb("recovery offered (crash $streak)")
    }

    private const val RECOVERY_CHANNEL = "odograph_recovery"
    private const val RECOVERY_NOTIFICATION = 91_001

    /**
     * Forgets the crash streak once the app has plainly recovered.
     *
     * Called when a run has been up long enough to be considered healthy. Without it the streak
     * only ever climbs, and the fourth unrelated crash in a month would be met with a dead app.
     */
    fun markHealthy(ctx: Context) {
        runCatching {
            ctx.getSharedPreferences("odograph-crash", Context.MODE_PRIVATE)
                .edit().remove("streak").remove("first_at").apply()
        }
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
