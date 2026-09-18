package `in`.odograph.tracker.sync

import `in`.odograph.tracker.data.PlaceEntity
import `in`.odograph.tracker.data.BatteryEntity
import `in`.odograph.tracker.data.ChargeEventEntity
import `in`.odograph.tracker.data.DailyTelemetryEntity
import `in`.odograph.tracker.data.PointEntity
import `in`.odograph.tracker.data.TripEntity
import `in`.odograph.tracker.core.BatteryMath

/**
 * The whole-dataset snapshot the box posts to the Google Docs link, once or twice a day.
 *
 * Hand-rolled like [TripJson]: one flat JSON document, no serialization library on either side.
 * The receiving Apps Script replaces each sheet's tab from this document, so a power cut or a
 * crashed box can never leave the spreadsheet part-way: whatever the device held at sync time is
 * what the sheet ends up showing, no appends that sit half-written.
 */
object SheetsJson {

    fun stats(
        deviceId: String,
        trips: List<TripEntity>,
        points: List<PointEntity>,
        charges: List<ChargeEventEntity>,
        days: List<DailyTelemetryEntity>,
        places: List<PlaceEntity>,
        battery: List<BatteryEntity>,
        capacityKwh: Double,
        homeRateInr: Double,
        outsideRateInr: Double,
        gstRatePct: Double
    ): String {
        val meta = "{" +
            // The schema this data came out of. A restore that cannot tell which shape it is
            // reading is a restore that will one day put the wrong columns in the right names.
            "\"schema\":$SCHEMA_VERSION," +
            "\"capacityKwh\":${num(capacityKwh)}," +
            "\"homeRateInr\":${num(homeRateInr)}," +
            "\"outsideRateInr\":${num(outsideRateInr)}," +
            "\"gstRatePct\":${num(gstRatePct)}" +
            "}"
        return "{" +
            "\"kind\":\"odograph\"," +
            "\"device\":\"${esc(deviceId)}\"," +
            "\"syncAt\":${System.currentTimeMillis()}," +
            "\"meta\":$meta," +
            "\"trips\":[${trips.joinToString(",") { trip(it) }}]," +
            "\"points\":[${points.joinToString(",") { point(it) }}]," +
            "\"charges\":[${charges.joinToString(",") { charge(it) }}]," +
            "\"telemetry\":[${days.joinToString(",") { day(it) }}]," +
            // Places and battery frames are what turn a readable report into a restorable backup.
            // Without places a restored trip knows where it went but not what that place is
            // called, and every route grouping is lost; without battery frames the pack has no
            // measured health and no charge summaries to recompute from.
            "\"places\":[${places.joinToString(",") { place(it) }}]," +
            "\"battery\":[${battery.joinToString(",") { batterySample(it) }}]" +
            "}"
    }

    /** The database shape this export was produced from. Bumped with every Room migration. */
    const val SCHEMA_VERSION = 9

    private fun place(p: PlaceEntity): String = "{" +
        "\"id\":${p.id}," +
        "\"lat\":${num(p.lat)}," +
        "\"lon\":${num(p.lon)}," +
        "\"visits\":${p.visits}," +
        "\"label\":${p.label?.let { "\"${esc(it)}\"" } ?: "null"}," +
        "\"autoName\":${p.autoName?.let { "\"${esc(it)}\"" } ?: "null"}," +
        "\"geocodedAt\":${p.geocodedAt ?: "null"}" +
        "}"

    private fun batterySample(b: BatteryEntity): String = "{" +
        "\"id\":${b.id}," +
        "\"tripId\":${b.tripId}," +
        "\"t\":${b.t}," +
        "\"socPercent\":${numOrNull(b.socPercent)}," +
        "\"charging\":${b.charging?.toString() ?: "null"}," +
        "\"rangeKm\":${numOrNull(b.rangeKm)}," +
        "\"chargingPowerKw\":${numOrNull(b.chargingPowerKw)}," +
        "\"odometerKm\":${numOrNull(b.odometerKm)}," +
        "\"batteryEnergyKwh\":${numOrNull(b.batteryEnergyKwh)}," +
        "\"exteriorTempC\":${b.exteriorTempC ?: "null"}" +
        "}"

    private fun trip(t: TripEntity): String = "{" +
        "\"id\":${t.id}," +
        "\"startedAt\":${t.startedAt}," +
        "\"endedAt\":${t.endedAt ?: 0}," +
        "\"distanceM\":${t.distanceM}," +
        "\"durationS\":${t.durationS}," +
        "\"movingS\":${t.movingS}," +
        "\"maxSpeedMps\":${t.maxSpeedMps}," +
        "\"avgSpeedMps\":${t.avgSpeedMps}," +
        "\"slowestKmMps\":${t.slowestKmMps}," +
        "\"elevGainM\":${t.elevGainM}," +
        "\"elevLossM\":${t.elevLossM}," +
        "\"startLat\":${t.startLat ?: "null"}," +
        "\"startLon\":${t.startLon ?: "null"}," +
        "\"endLat\":${t.endLat ?: "null"}," +
        "\"endLon\":${t.endLon ?: "null"}," +
        "\"startPlaceId\":${t.startPlaceId ?: "null"}," +
        "\"endPlaceId\":${t.endPlaceId ?: "null"}," +
        "\"socStart\":${t.socStart ?: "null"}," +
        "\"socEnd\":${t.socEnd ?: "null"}," +
        "\"energyKwh\":${t.energyKwh ?: "null"}," +
        "\"costInr\":${t.costInr ?: "null"}" +
        "}"

    private fun point(p: PointEntity): String = "{" +
        "\"tripId\":${p.tripId}," +
        "\"t\":${p.t}," +
        "\"lat\":${p.lat}," +
        "\"lon\":${p.lon}," +
        "\"speedMps\":${p.speedMps}," +
        "\"bearingDeg\":${p.bearingDeg ?: "null"}," +
        "\"altitudeM\":${p.altitudeM ?: "null"}," +
        "\"interpolated\":${if (p.interpolated) 1 else 0}" +
        "}"

    private fun charge(c: ChargeEventEntity): String = "{" +
        "\"id\":${c.id}," +
        "\"startTime\":${c.startTime}," +
        "\"endTime\":${c.endTime ?: 0}," +
        "\"startSoc\":${c.startSoc ?: "null"}," +
        "\"endSoc\":${c.endSoc ?: "null"}," +
        "\"energyKwh\":${c.energyKwh}," +
        "\"peakPowerKw\":${c.peakPowerKw ?: "null"}," +
        "\"kind\":${c.kind?.let { if (it == BatteryMath.ChargeKind.FAST.ordinal) "\"fast\"" else "\"slow\"" } ?: "\"open\""}," +
        "\"enteredRateInr\":${c.enteredRateInr ?: "null"}," +
        "\"enteredBillInr\":${c.enteredBillInr ?: "null"}," +
        "\"gstRatePct\":${c.gstRatePct ?: "null"}," +
        "\"costInr\":${c.costInr ?: "null"}" +
        "}"

    private fun day(d: DailyTelemetryEntity): String = "{" +
        "\"day\":${d.day}," +
        "\"firstPollAt\":${d.firstPollAt}," +
        "\"lastPollAt\":${d.lastPollAt}" +
        "}"

    /** A number, or a JSON null. "No reading" and "a reading of zero" are different facts. */
    private fun numOrNull(d: Double?): String = d?.let { num(it) } ?: "null"

    private fun num(d: Double): String {
        val s = "%.4f".format(d).trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}