package `in`.odograph.tracker.export

import `in`.odograph.tracker.core.BatteryMath
import `in`.odograph.tracker.data.ChargeEventEntity
import `in`.odograph.tracker.data.DailyTelemetryEntity
import `in`.odograph.tracker.data.PointEntity
import `in`.odograph.tracker.data.TripEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Exporters {

    /** Money and energy to two decimals, so the CSV and the screens never disagree on the last paisa. */
    private fun num(v: Double): String = "%.2f".format(v)

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
                    t.clusterId ?: "", t.socStart ?: "", t.socEnd ?: "",
                    t.energyKwh?.let { num(it) } ?: "", t.costInr?.let { num(it) } ?: ""
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

    /** One row per plugin charging session, for a laptop-side cost and charge analysis. */
    fun chargesCsv(charges: List<ChargeEventEntity>): String = buildString {
        appendLine(
            "id,start_time,end_time,start_soc_pct,end_soc_pct,energy_kwh,peak_power_kw," +
                "kind,entered_rate_inr,entered_bill_inr,gst_rate_pct,cost_inr,delivered_kwh,loss_pct"
        )
        charges.forEach { c ->
            appendLine(
                listOf(
                    c.id, c.startTime, c.endTime ?: "", c.startSoc ?: "", c.endSoc ?: "",
                    num(c.energyKwh), c.peakPowerKw ?: "",
                    c.kind?.let {
                            if (it == BatteryMath.ChargeKind.FAST.ordinal) "fast" else "slow"
                        } ?: "open",
                    c.enteredRateInr ?: "", c.enteredBillInr ?: "", c.gstRatePct ?: "",
                    c.costInr?.let { num(it) } ?: "",
                    c.deliveredKwh?.let { num(it) } ?: "",
                    BatteryMath.lossPct(c.energyKwh, c.deliveredKwh)?.let { "%.1f".format(it) } ?: ""
                ).joinToString(",")
            )
        }
    }

    /** One row per local day the box was alive and polling. The raw form of the coverage chart. */
    fun telemetryCsv(days: List<DailyTelemetryEntity>): String = buildString {
        appendLine("day,first_poll_at,last_poll_at")
        days.forEach { d ->
            appendLine(listOf(d.day, d.firstPollAt, d.lastPollAt).joinToString(","))
        }
    }

    private fun escape(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
