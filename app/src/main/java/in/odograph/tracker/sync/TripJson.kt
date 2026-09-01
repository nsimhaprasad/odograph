package `in`.odograph.tracker.sync

import `in`.odograph.tracker.data.TripEntity

/**
 * Hand-rolled rather than pulling in a serialisation library: this is one flat object and the
 * receiving end is a fifteen-line Apps Script.
 */
object TripJson {

    fun encode(trip: TripEntity, deviceId: String): String = buildString {
        append("{")
        append(""""device":"${esc(deviceId)}",""")
        append(""""id":${trip.id},""")
        append(""""startedAt":${trip.startedAt},""")
        append(""""endedAt":${trip.endedAt ?: 0},""")
        append(""""distanceM":${trip.distanceM},""")
        append(""""durationS":${trip.durationS},""")
        append(""""movingS":${trip.movingS},""")
        append(""""maxSpeedMps":${trip.maxSpeedMps},""")
        append(""""avgSpeedMps":${trip.avgSpeedMps},""")
        append(""""slowestKmMps":${trip.slowestKmMps},""")
        append(""""startLat":${trip.startLat ?: "null"},""")
        append(""""startLon":${trip.startLon ?: "null"},""")
        append(""""endLat":${trip.endLat ?: "null"},""")
        append(""""endLon":${trip.endLon ?: "null"}""")
        append("}")
    }

    fun encodeBatch(trips: List<TripEntity>, deviceId: String): String =
        trips.joinToString(",", "[", "]") { encode(it, deviceId) }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
