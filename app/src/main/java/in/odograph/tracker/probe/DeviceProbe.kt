package `in`.odograph.tracker.probe

import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Answers every device unknown in one place. The projected resolution, the GNSS providers, the
 * clock skew and the Play Services question cannot be looked up — they have to be measured on
 * the box itself, and everything else in the app is designed around the answers.
 */
data class ProbeReport(
    val sdkInt: Int,
    val release: String,
    val manufacturer: String,
    val model: String,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val refreshHz: Float,
    val locationProviders: List<String>,
    val gpsEnabled: Boolean,
    val hasPlayServices: Boolean,
    val systemTimeMs: Long,
    val bootElapsedMs: Long,
    val batteryOptimisationIgnored: Boolean,
    val externalDirs: List<String>
) {
    fun asText(): String = buildString {
        appendLine("ODOGRAPH DEVICE PROBE")
        appendLine("SDK           : $sdkInt (Android $release)")
        appendLine("DEVICE        : $manufacturer $model")
        appendLine("SCREEN        : ${widthPx}x$heightPx @ ${densityDpi}dpi ${refreshHz}Hz")
        appendLine("PROVIDERS     : ${locationProviders.joinToString()}")
        appendLine("GPS ENABLED   : $gpsEnabled")
        appendLine("PLAY SERVICES : $hasPlayServices")
        appendLine("SYSTEM TIME   : $systemTimeMs")
        appendLine("UPTIME MS     : $bootElapsedMs")
        appendLine("BATTERY OPT   : ignored=$batteryOptimisationIgnored")
        appendLine("EXT DIRS      : ${externalDirs.joinToString()}")
    }
}

object DeviceProbe {

    @Suppress("DEPRECATION")
    fun collect(ctx: Context): ProbeReport {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val metrics = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
        val play = runCatching {
            ctx.packageManager.getPackageInfo("com.google.android.gms", 0)
            true
        }.getOrDefault(false)

        return ProbeReport(
            sdkInt = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            refreshHz = wm.defaultDisplay.refreshRate,
            locationProviders = lm.allProviders,
            gpsEnabled = runCatching {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            }.getOrDefault(false),
            hasPlayServices = play,
            systemTimeMs = System.currentTimeMillis(),
            bootElapsedMs = SystemClock.elapsedRealtime(),
            batteryOptimisationIgnored = pm.isIgnoringBatteryOptimizations(ctx.packageName),
            externalDirs = ctx.getExternalFilesDirs(null).filterNotNull().map { it.absolutePath }
        )
    }
}
