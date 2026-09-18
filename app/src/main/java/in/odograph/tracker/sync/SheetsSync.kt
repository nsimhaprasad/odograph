package `in`.odograph.tracker.sync

import android.content.Context
import `in`.odograph.tracker.data.DailyTelemetryEntity
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.ui.theme.Settings
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * Incremental sync with the Google Docs workbook.
 *
 * Write path: one POST of only what has appeared since the last successful export — closed
 * trips (delayed a short grace so their energy/cost is settled), their points, closed charge
 * sessions, and coverage days — plus today's still-mutable row. Watermarks on the box make a
 * useful payload constant-sized no matter how many years of history accumulate; the workbook is
 * the archive, not a mirror that has to be re-uploaded to keep existing.
 *
 * The Apps Script appends those rows idempotently (a retried POST never duplicates) and rebuilds
 * the analytics sheet from the workbook's own history. Read path: a GET that returns the
 * workbook's control values (rates, capacity) which the box picks up so a number meant to be
 * tweaked on a keyboard can be changed in one place.
 *
 * Both paths are best-effort and off the capture path, exactly like the rest of sync.
 */
object SheetsSync {

    /** Closed a moment ago but still settling its final energy/cost; wait this long before export. */
    private const val SETTLE_GRACE_MS = 2 * 60_000L

    /**
     * Uploads everything unseen since the last export. Returns a per-row count on success, or a
     * message that explains the one fixable cause — most often: the configured value is a
     * spreadsheet link, and the bundled Apps Script still needs deploying. Watermarks only
     * advance on a healthy response, so a failed run simply retries the same delta next time.
     */
    fun exportDocs(ctx: Context, url: String, deviceId: String): Outbound.Result {
        if (url.isBlank()) return Outbound.Result(0, 0, "no docs link configured")
        if (Outbound.isSpreadsheetLink(url)) {
            return Outbound.Result(0, 0, spreadLinkHelp())
        }
        return runCatching {
            val dao = OdographDb.get(ctx).dao()
            val s = Settings(ctx)
            val now = System.currentTimeMillis()
            val zone = s.zone
            val trips = dao.docsNewTrips(s.lastDocsTripId, now - SETTLE_GRACE_MS)
            val charges = dao.docsNewCharges(s.lastDocsChargeId)
            val today = dayOf(today(), zone)
            val days = DocsDelta.selectDays(dao.telemetryDaysAfter(s.lastDocsDay), today)
            val battery = dao.docsNewBattery(s.lastDocsBatteryId)

            if (trips.isEmpty() && charges.isEmpty() && days.isEmpty() && battery.isEmpty()) {
                return Outbound.Result(0, 0, null)
            }

            val points = trips.flatMap { dao.pointsFor(it.id) }
            // Every place, every time: the table is small, and a backup carrying trips that
            // reference places it does not contain restores a history with no route names in it.
            val places = dao.allPlaces()
            val body = SheetsJson.stats(
                deviceId = deviceId,
                trips = trips,
                points = points,
                charges = charges,
                days = days,
                places = places,
                battery = battery,
                capacityKwh = s.batteryCapacityKwh,
                homeRateInr = s.homeRateInr,
                outsideRateInr = s.outsideRateInr,
                gstRatePct = s.gstRatePct
            )
            val code = Outbound.postJson(url, body)
            if (code !in 200..299) {
                return Outbound.Result(
                    0, 0, "endpoint returned HTTP $code. Is the bundled Apps Script deployed at this URL?"
                )
            }
            trips.maxOfOrNull { it.id }?.let { s.lastDocsTripId = it }
            charges.maxOfOrNull { it.id }?.let { s.lastDocsChargeId = it }
            battery.maxOfOrNull { it.id }?.let { s.lastDocsBatteryId = it }
            s.lastDocsDay = DocsDelta.nextDayWatermark(s.lastDocsDay, days, today)
            val rows = trips.size + points.size + charges.size + days.size
            Outbound.Result(rows, rows)
        }.getOrElse { Outbound.Result(0, 0, it.message ?: it::class.java.simpleName) }
    }

    private fun today(): Long = System.currentTimeMillis()

    private fun dayOf(ms: Long, zone: TimeZone): Int {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = ms
        return cal.get(Calendar.YEAR) * 10_000 +
            (cal.get(Calendar.MONTH) + 1) * 100 +
            cal.get(Calendar.DAY_OF_MONTH)
    }

    /**
     * Pulls the workbook's control sheet and applies it to the box (capacity, electricity
     * rates). Nothing is overwritten unless the sheet actually says so: absent values are
     * ignored, so a fresh workbook with only defaults still imports cleanly.
     */
    fun importControl(settings: Settings, url: String): Pair<String, Boolean> {
        if (url.isBlank()) return "No docs link configured." to true
        if (Outbound.isSpreadsheetLink(url)) return spreadLinkHelp() to true
        return runCatching {
            val body = Outbound.getBody(url)
            val json = JSONObject(body)
            var applied = 0
            json.optDouble("capacityKwh", Double.NaN).takeIf { it > 0 }?.let {
                settings.batteryCapacityKwh = it; applied++
            }
            json.optDouble("homeRateInr", Double.NaN).takeIf { it >= 0 }?.let {
                settings.homeRateInr = it; applied++
            }
            json.optDouble("outsideRateInr", Double.NaN).takeIf { it >= 0 }?.let {
                settings.outsideRateInr = it; applied++
            }
            json.optDouble("gstRatePct", Double.NaN).takeIf { !it.isNaN() }?.let {
                settings.gstRatePct = it; applied++
            }
            val message = "Imported $applied of 4 control values (capacity, home rate, outside rate, GST)."
            val nothingApplied = applied == 0
            Pair(message, nothingApplied)
        }.getOrElse { e ->
            Pair("Import failed: ${e.message ?: e::class.java.simpleName}", true)
        }
    }

    private fun spreadLinkHelp(): String =
        "This is a spreadsheet link, and a spreadsheet cannot receive posts directly. " +
            "Open the sheet → Extensions → Apps Script, paste the bundled script, " +
            "Deploy → New deployment → Web app → Execute as me, access Anyone, then paste " +
            "the /exec URL into this box. The sheet link stays valid for reading once that is done."
}

/**
 * The pure day-selection rules behind an export — kept separate so the watermark behaviour is
 * unit-testable without a device or a database.
 */
object DocsDelta {

    /**
     * What a run sends: finalized history newer than the watermark (days fully behind today, so
     * they will never change) plus today's mutable row, which is re-sent every run and
     * overwritten on the sheet.
     */
    fun selectDays(days: List<DailyTelemetryEntity>, today: Int): List<DailyTelemetryEntity> {
        val finalized = days.filter { it.day > 0 && it.day < today }
        return finalized + listOfNotNull(days.firstOrNull { it.day == today })
    }

    /**
     * The new watermark after a successful export. Only days fully behind today count as
     * delivered; today itself is never marked delivered (it mutates until midnight).
     */
    fun nextDayWatermark(prev: Int, sent: List<DailyTelemetryEntity>, today: Int): Int {
        val newestFinalized = sent.map { it.day }.filter { it < today }.maxOrNull() ?: 0
        return maxOf(prev, newestFinalized, today - 1)
    }
}