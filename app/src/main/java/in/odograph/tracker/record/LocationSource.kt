package `in`.odograph.tracker.record

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import `in`.odograph.tracker.core.Fix

/**
 * Timestamps come from the fix, never the system clock: the box has no SIM, therefore no NTP,
 * therefore a possibly-wrong clock at boot. GNSS time is atomic-clock accurate and free.
 */
fun Location.toFix(): Fix = Fix(
    t = time,
    lat = latitude,
    lon = longitude,
    speedMps = if (hasSpeed()) speed else 0f,
    accuracyM = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
    interpolated = false,
    // Network positions carry no altitude; only GNSS does.
    altitudeM = if (hasAltitude()) altitude else null
)

interface LocationSource {
    fun start(onFix: (Fix) -> Unit)
    fun stop()
}

/**
 * Deliberately LocationManager, not Fused: Play Services presence on the box is unverified.
 *
 * Subscribes to GNSS *and*, when available, the network provider. GNSS is the one that matters
 * while driving - it is the only source with usable Doppler speed - but it needs line of sight to
 * satellites, so under a roof it yields nothing at all rather than something imprecise. The
 * network provider triangulates from WiFi and cell towers, which keeps the app honest indoors and
 * gives the first fix after ignition something to show while the satellites are still being found.
 *
 * No special-casing is needed downstream: network fixes carry a large accuracy figure, and the
 * existing 25 m filter already excludes them from distance while still letting them prove that
 * location works at all.
 */
class GnssLocationSource(ctx: Context) : LocationSource {

    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val listeners = mutableListOf<LocationListener>()

    @SuppressLint("MissingPermission")
    override fun start(onFix: (Fix) -> Unit) {
        val providers = buildList {
            if (isEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (isEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }

        providers.forEach { provider ->
            val l = object : LocationListener {
                override fun onLocationChanged(location: Location) = onFix(location.toFix())
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
                @Deprecated("Required by the legacy LocationListener interface")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            listeners += l
            // The four-argument overload delivers callbacks on the *calling* thread's Looper and
            // throws when it has none. The recorder starts this from Dispatchers.IO, a plain
            // worker thread, so the Looper is named explicitly. Callbacks land on the main thread
            // and hand the work straight back to IO.
            runCatching {
                lm.requestLocationUpdates(provider, 1000L, 0f, l, Looper.getMainLooper())
            }
        }

        // A cached position from any provider means the screen can stop saying ACQUIRING
        // immediately rather than waiting for the first live update.
        providers.firstNotNullOfOrNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()
        }?.let { onFix(it.toFix()) }
    }

    private fun isEnabled(provider: String): Boolean =
        runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)

    override fun stop() {
        listeners.forEach { l -> runCatching { lm.removeUpdates(l) } }
        listeners.clear()
    }
}
