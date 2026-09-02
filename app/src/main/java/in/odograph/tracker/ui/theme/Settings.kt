package `in`.odograph.tracker.ui.theme

import android.content.Context
import `in`.odograph.tracker.alert.AlertMode
import java.util.Calendar
import java.util.TimeZone

enum class ThemeMode { AUTO, DAY, NIGHT }

/**
 * Persisted look-and-feel choices. SharedPreferences rather than DataStore: this is two enums,
 * and the service must be able to read them synchronously on a cold start.
 */
class Settings(ctx: Context) {

    private val prefs = ctx.applicationContext.getSharedPreferences("odograph", Context.MODE_PRIVATE)

    var direction: Direction
        get() = runCatching { Direction.valueOf(prefs.getString(KEY_DIRECTION, null) ?: "") }
            .getOrDefault(Direction.ION)
        set(value) = prefs.edit().putString(KEY_DIRECTION, value.name).apply()

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }
            .getOrDefault(ThemeMode.AUTO)
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    /**
     * A capability token, not a credential. Typically a Google Apps Script web app URL that
     * appends to a spreadsheet the owner controls; revoking it is one click and it grants
     * nothing else. Blank means off-device delivery is entirely disabled.
     */
    var webhookUrl: String
        get() = prefs.getString(KEY_WEBHOOK, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_WEBHOOK, value.trim()).apply()

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE, "").orEmpty().ifBlank { "windsor" }
        set(value) = prefs.edit().putString(KEY_DEVICE, value.trim()).apply()

    /** Kilometres per hour. Zero disables overspeed alerting entirely. */
    var speedLimitKmh: Int
        get() = prefs.getInt(KEY_LIMIT, 0)
        set(value) = prefs.edit().putInt(KEY_LIMIT, value.coerceIn(0, 200)).apply()

    var alertMode: AlertMode
        get() = runCatching { AlertMode.valueOf(prefs.getString(KEY_ALERT, null) ?: "") }
            .getOrDefault(AlertMode.CHIME)
        set(value) = prefs.edit().putString(KEY_ALERT, value.name).apply()

    /**
     * Display timezone. Blank means follow the device.
     *
     * A box with no SIM receives no NITZ, so it can learn correct UTC from NTP but never learns
     * its offset and sits at UTC. Carrying our own setting keeps timestamps readable regardless.
     */
    var timeZoneId: String
        get() = prefs.getString(KEY_TZ, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TZ, value).apply()

    val zone: TimeZone
        get() = timeZoneId.takeIf { it.isNotBlank() }
            ?.let { id -> runCatching { TimeZone.getTimeZone(id) }.getOrNull() }
            ?: TimeZone.getDefault()

    private companion object {
        const val KEY_TZ = "time_zone_id"
        const val KEY_LIMIT = "speed_limit_kmh"
        const val KEY_ALERT = "alert_mode"
        const val KEY_DIRECTION = "direction"
        const val KEY_THEME = "theme_mode"
        const val KEY_WEBHOOK = "webhook_url"
        const val KEY_DEVICE = "device_id"
    }
}

/**
 * A bright cluster at night constricts the driver's pupils and night vision takes around twenty
 * minutes to recover, so AUTO errs towards night: day only between 06:00 and 18:00.
 */
fun isNight(mode: ThemeMode, hourOfDay: Int): Boolean = when (mode) {
    ThemeMode.DAY -> false
    ThemeMode.NIGHT -> true
    ThemeMode.AUTO -> hourOfDay < 6 || hourOfDay >= 18
}

fun currentHour(zone: TimeZone = TimeZone.getDefault()): Int =
    Calendar.getInstance(zone).get(Calendar.HOUR_OF_DAY)
