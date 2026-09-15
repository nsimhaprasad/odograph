package `in`.odograph.tracker.sync

import android.content.Context
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.ui.theme.Settings
import org.json.JSONObject

/**
 * Whole-dataset sync with the Google Docs workbook.
 *
 * Write path: one POST of every trip, point, charge session and coverage day to the configured
 * link, which the bundled Apps Script turns into replaced tabs plus a refreshed analytics sheet.
 * Read path: a GET that returns the workbook's control values (rates, capacity) which the box
 * picks up so a number meant to be tweaked on a keyboard can be changed in one place.
 *
 * Both paths are best-effort and off the capture path, exactly like the rest of sync.
 */
object SheetsSync {

    /**
     * Exports the entire local dataset. Returns a per-row count on success, or a message that
     * explains the one fixable cause — most often: the configured value is a spreadsheet link,
     * and the bundled Apps Script still needs deploying.
     */
    fun exportAll(ctx: Context, url: String, deviceId: String): Outbound.Result {
        if (url.isBlank()) return Outbound.Result(0, 0, "no docs link configured")
        if (Outbound.isSpreadsheetLink(url)) {
            return Outbound.Result(0, 0, spreadLinkHelp())
        }
        return runCatching {
            val dao = OdographDb.get(ctx).dao()
            val trips = dao.allTrips()
            val s = Settings(ctx)
            val body = SheetsJson.stats(
                deviceId = deviceId,
                trips = trips,
                points = trips.flatMap { dao.pointsFor(it.id) },
                charges = dao.allChargeEvents(),
                days = dao.allTelemetryDays(),
                capacityKwh = s.batteryCapacityKwh,
                homeRateInr = s.homeRateInr,
                outsideRateInr = s.outsideRateInr,
                gstRatePct = s.gstRatePct
            )
            val code = Outbound.postJson(url, body)
            if (code in 200..299) {
                val rows = trips.size + 1 // +1 for the meta row the sheet appends.
                Outbound.Result(rows, rows)
            } else {
                Outbound.Result(0, 0, "endpoint returned HTTP $code. Is the bundled Apps Script deployed at this URL?")
            }
        }.getOrElse { Outbound.Result(0, 0, it.message ?: it::class.java.simpleName) }
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