package `in`.odograph.tracker.export

import `in`.odograph.tracker.data.PointEntity
import `in`.odograph.tracker.data.TripEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Exporters {

    fun tripsCsv(trips: List<TripEntity>): String = buildString {
        appendLine(
            "id,started_at,ended_at,distance_m,duration_s,moving_s," +
                "max_speed_mps,avg_speed_mps,slowest_km_mps,elev_gain_m,elev_loss_m," +
                "start_lat,start_lon," +
                "end_lat,end_lon,cluster_id,soc_start_pct,soc_end_pct,energy_kwh,cost_inr"
        )
        trips.forEach { t ->
            appendLine(
                listOf(
                    t.id, t.startedAt, t.endedAt ?: "", t.distanceM, t.durationS, t.movingS,
                    t.maxSpeedMps, t.avgSpeedMps, t.slowestKmMps,
                    t.elevGainM, t.elevLossM,
                    t.startLat ?: "", t.startLon ?: "", t.endLat ?: "", t.endLon ?: "",
                    t.clusterId ?: "", t.socStart ?: "", t.socEnd ?: "", t.energyKwh ?: "", t.costInr ?: ""
                ).joinToString(",")
            )
        }
    }

    fun pointsCsv(points: List<PointEntity>): String = buildString {
        appendLine("trip_id,t,lat,lon,speed_mps,bearing_deg,altitude_m,accuracy_m,interpolated")
        points.forEach { p ->
            appendLine(
                listOf(
                    p.tripId, p.t, p.lat, p.lon, p.speedMps,
                    p.bearingDeg ?: "", p.altitudeM ?: "", p.accuracyM,
                    if (p.interpolated) 1 else 0
                ).joinToString(",")
            )
        }
    }

    fun gpx(tripName: String, points: List<PointEntity>): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine(
                """<gpx version="1.1" creator="Odograph" """ +
                    """xmlns="http://www.topografix.com/GPX/1/1">"""
            )
            appendLine("  <trk><name>${escape(tripName)}</name><trkseg>")
            points.forEach { p ->
                appendLine("""    <trkpt lat="${p.lat}" lon="${p.lon}">""")
                p.altitudeM?.let { appendLine("      <ele>$it</ele>") }
                appendLine("      <time>${iso.format(Date(p.t))}</time>")
                appendLine("    </trkpt>")
            }
            appendLine("  </trkseg></trk>")
            append("</gpx>")
        }
    }

    private fun escape(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
