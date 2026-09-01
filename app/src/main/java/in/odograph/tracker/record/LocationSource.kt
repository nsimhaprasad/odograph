package `in`.odograph.tracker.record

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
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
    interpolated = false
)

interface LocationSource {
    fun start(onFix: (Fix) -> Unit)
    fun stop()
}

/** Deliberately LocationManager, not Fused: Play Services presence on the box is unverified. */
class GnssLocationSource(ctx: Context) : LocationSource {

    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listener: LocationListener? = null

    @SuppressLint("MissingPermission")
    override fun start(onFix: (Fix) -> Unit) {
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = onFix(location.toFix())
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Required by the legacy LocationListener interface")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        listener = l
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l)
    }

    override fun stop() {
        listener?.let { lm.removeUpdates(it) }
        listener = null
    }
}
