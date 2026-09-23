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
    /**
     * How many drives travel in one POST.
     *
     * Small on purpose. Every drive brings its points — an hour's driving is about 3,600 of them
     * — and the payload is built as one string on a heap of 128 MB. Ten drives is a few
     * megabytes; the whole history in one go was 69 MB and never reached the network.
     */
    const val TRIPS_PER_PAGE = 10

    /** Battery frames per POST. The frame table is the largest, and this was already bounded. */
    const val BATTERY_PER_PAGE = 2_000

    /**
     * The most pages one run will send before yielding.
     *
     * A box that has never backed up has months to send, and the schedule only runs this once
     * an hour — so one run must be allowed to catch up, or the archive stays weeks behind for
     * weeks. But the box is alive only for the length of a drive, and a run must not hold the
     * whole of one hostage: a thousand drives is more than enough to clear any backlog, and
     * whatever remains goes next time.
     */
    const val MAX_PAGES_PER_RUN = 100

    /**
     * Uploads everything unseen since the last export, a page at a time.
     *
     * Each page is a complete, self-consistent document — drives with their points, and every
     * place every time — posted on its own, with the watermarks advanced only when it lands. A
     * page that fails leaves the watermarks where they were, so the next run retries the same
     * delta; the receiving script upserts by key, so a page that landed but was not acknowledged
     * is harmless to send twice. That is what makes it safe to resume an export that the ignition
     * cut short.
     *
     * [post] is how a page reaches the network; it is a parameter so the paging can be exercised
     * against the real database without one.
     */
    fun exportDocs(
        ctx: Context,
        url: String,
        deviceId: String,
        post: (url: String, body: String) -> Int = Outbound::postJson
    ): Outbound.Result {
        if (url.isBlank()) return Outbound.Result(0, 0, "no docs link configured")
        if (Outbound.isSpreadsheetLink(url)) {
            return Outbound.Result(0, 0, spreadLinkHelp())
        }
        return runCatching {
            val dao = OdographDb.get(ctx).dao()
            val s = Settings(ctx)
            val zone = s.zone
            val today = dayOf(today(), zone)
            // Every place, every time: the table is small, and a backup carrying trips that
            // reference places it does not contain restores a history with no route names in it.
            val places = dao.allPlaces()
            var sent = 0

            repeat(MAX_PAGES_PER_RUN) {
                val now = System.currentTimeMillis()
                val trips = dao.docsNewTrips(s.lastDocsTripId, now - SETTLE_GRACE_MS, TRIPS_PER_PAGE)
                val charges = dao.docsNewCharges(s.lastDocsChargeId)
                val days = DocsDelta.selectDays(dao.telemetryDaysAfter(s.lastDocsDay), today)
                val battery = dao.docsNewBattery(s.lastDocsBatteryId, BATTERY_PER_PAGE)

                if (trips.isEmpty() && charges.isEmpty() && days.isEmpty() && battery.isEmpty()) {
                    return Outbound.Result(sent, sent)
                }

                val points = trips.flatMap { dao.pointsFor(it.id) }
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
                val code = post(url, body)
                if (code !in 200..299) {
                    return Outbound.Result(
                        sent + trips.size + points.size + charges.size + days.size, sent,
                        "endpoint returned HTTP $code. Is the bundled Apps Script deployed at this URL?"
                    )
                }
                trips.maxOfOrNull { it.id }?.let { s.lastDocsTripId = it }
                charges.maxOfOrNull { it.id }?.let { s.lastDocsChargeId = it }
                battery.maxOfOrNull { it.id }?.let { s.lastDocsBatteryId = it }
                s.lastDocsDay = DocsDelta.nextDayWatermark(s.lastDocsDay, days, today)
                sent += trips.size + points.size + charges.size + days.size

                // A page that was not full was the last one; the loop above would only find it
                // empty next time round, at the cost of five more queries.
                if (trips.size < TRIPS_PER_PAGE && battery.size < BATTERY_PER_PAGE) {
                    return Outbound.Result(sent, sent)
                }
            }
            Outbound.Result(sent, sent)
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

    /**
     * Rebuilds this box's database from the workbook.
     *
     * The reason the sheet carries places and battery frames at all. A box that has died, been
     * reflashed or been replaced has a URL and nothing else, and this is the path from that back
     * to a history — which is the difference between the sheet being a report and being a backup.
     *
     * Destructive by design and by necessity: see [SheetsRestore.apply] for why merging two
     * histories that reference each other by row id cannot be made to work. The caller is
     * responsible for asking first.
     *
     * The watermarks are moved to the restored maxima afterwards. Without that the next export
     * would treat the entire restored history as new and push all of it back to the sheet it just
     * came from — harmless, because the script overwrites by key, but a pointless upload of
     * everything on a connection that is often a phone hotspot.
     */
    fun restoreFromSheet(ctx: Context, url: String): SheetsRestore.Result {
        if (url.isBlank()) return SheetsRestore.Result(false, "No docs link configured.")
        if (Outbound.isSpreadsheetLink(url)) return SheetsRestore.Result(false, spreadLinkHelp())

        val body = runCatching { Outbound.getBody(exportUrl(url), RESTORE_READ_TIMEOUT_MS) }
            .getOrElse {
                return SheetsRestore.Result(
                    false, "Could not read the sheet: ${it.message ?: it::class.java.simpleName}"
                )
            }
        val snapshot = SheetsRestore.parse(body).getOrElse {
            return SheetsRestore.Result(false, "That sheet cannot be restored: ${it.message}")
        }

        val db = OdographDb.get(ctx)
        val result = SheetsRestore.apply(db.dao(), snapshot) { db.clearAllTables() }
        if (result.ok) advanceWatermarks(Settings(ctx), snapshot)
        return result
    }

    /** A full backup can be tens of megabytes of history; Apps Script builds it a tab at a time. */
    private const val RESTORE_READ_TIMEOUT_MS = 180_000

    /** Appends the export parameter to whatever query the configured /exec URL already carries. */
    internal fun exportUrl(url: String): String =
        url + (if (url.contains('?')) "&" else "?") + "export=all"

    private fun advanceWatermarks(settings: Settings, snapshot: SheetsRestore.Snapshot) {
        snapshot.trips.maxOfOrNull { it.id }?.let { settings.lastDocsTripId = it }
        snapshot.charges.maxOfOrNull { it.id }?.let { settings.lastDocsChargeId = it }
        snapshot.battery.maxOfOrNull { it.id }?.let { settings.lastDocsBatteryId = it }
        snapshot.telemetry.maxOfOrNull { it.day }?.let { settings.lastDocsDay = it }
    }

    /**
     * The one fixable cause behind most sync failures, phrased for someone at a keyboard.
     *
     * Shared by every path, so it says nothing about direction. It used to end by reassuring the
     * reader that the sheet link "stays valid for reading", which stopped being true the moment a
     * restore also went through the script — a backup cannot be read back from a link that serves
     * a web page.
     */
    private fun spreadLinkHelp(): String =
        "This is the spreadsheet itself, not the script that serves it — the box cannot talk to " +
            "it directly. Open the sheet → Extensions → Apps Script, paste the bundled script, " +
            "Deploy → New deployment → Web app → Execute as me, access Anyone, then paste " +
            "the /exec URL into this box. Uploads and restores both go through that one URL."
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