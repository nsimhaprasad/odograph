package `in`.odograph.tracker.geocode

import android.util.Log
import `in`.odograph.tracker.data.OdographDao
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gives a place a human-readable default name.
 *
 * Android's built-in Geocoder has no backend without Play Services, whose presence on the box is
 * unverified, so lookups go straight to OSM Nominatim. The economics work because we geocode
 * *places*, not trips: a hundred commutes to the same office cost one request, cached forever.
 * Over the app's life that is a few dozen calls, comfortably inside Nominatim's usage policy.
 *
 * A user's own label always wins over whatever comes back here.
 */
object PlaceNamer {

    private const val TAG = "PlaceNamer"
    private const val ENDPOINT = "https://nominatim.openstreetmap.org/reverse"

    /**
     * Picks the most locally-meaningful part of a Nominatim response.
     *
     * The full display_name is a postal address and far too long for a car screen; what people
     * recognise is the neighbourhood, optionally with the city when it disambiguates.
     */
    fun shortNameFrom(json: String): String? = runCatching {
        val address = JSONObject(json).optJSONObject("address") ?: return null
        val local = listOf(
            "neighbourhood", "suburb", "quarter", "residential",
            "hamlet", "village", "town", "city_district", "road"
        ).firstNotNullOfOrNull { address.optString(it, "").takeIf { v -> v.isNotBlank() } }

        val city = listOf("city", "town", "state_district", "county")
            .firstNotNullOfOrNull { address.optString(it, "").takeIf { v -> v.isNotBlank() } }

        when {
            local == null && city == null -> null
            local == null -> city
            city == null || city == local -> local
            else -> "$local, $city"
        }
    }.getOrNull()

    private fun fetch(lat: Double, lon: Double): String? {
        val url = URL("$ENDPOINT?format=json&zoom=16&lat=$lat&lon=$lon")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            // Nominatim's policy requires an identifying agent.
            setRequestProperty("User-Agent", "Odograph/0.1 (personal drive tracker)")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Names any places that still lack one. Best-effort: no network, no names, no consequences.
     * @return how many were named.
     */
    fun nameMissing(dao: OdographDao, max: Int = 5): Int {
        var named = 0
        dao.placesNeedingNames().take(max).forEach { place ->
            runCatching {
                val body = fetch(place.lat, place.lon) ?: return@runCatching
                shortNameFrom(body)?.let {
                    dao.setPlaceAutoName(place.id, it, System.currentTimeMillis())
                    named++
                }
                // Nominatim asks for at most one request per second.
                Thread.sleep(1_100)
            }.onFailure { Log.w(TAG, "reverse geocode failed for place ${place.id}", it) }
        }
        return named
    }
}
