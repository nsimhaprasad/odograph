package `in`.odograph.tracker.core

import `in`.odograph.tracker.data.TripEntity
import `in`.odograph.tracker.ui.theme.Settings
import org.json.JSONArray

/**
 * Odometer accounting.
 *
 * The car began untracked (say at 20,000 km), and the driver typed that dash reading in once.
 * That is the seed [CalPoint] — the physical odometer at the moment tracking started. Everything
 * the app has measured since is GNSS distance, a slightly wrong ruler: wheel revolutions and GPS
 * disagree by a low single-digit percentage.
 *
 * Every later entry of the dash reading is another [CalPoint] (measured km, physical dash km).
 * Two points bracket a segment, and that segment's factor — the real world's ratio to ours across
 * it — corrects exactly the trips driven inside it:
 *
 *     factor(i) = (physical[i+1] - physical[i]) / (measured[i+1] - measured[i])
 *
 * So a new reading recalculates only the trips since the last reading and shares the drift across
 * them proportionally. Older trips keep the factor their own segment produced; nothing is ever
 * rewritten. The app odometer is the piecewise line through the points, so at a calibration moment
 * it is *exactly* the dash number the driver typed.
 *
 * Two guards keep a wrong keystroke from poisoning the books:
 *  - an off-the-expected reading is surfaced (see [CalibrationPreview]) instead of silently
 *    rescaling a whole window of trips;
 *  - once [RECORD_EVERY_KM] have passed since the last entry the service politely asks the driver
 *    to record the odometer again.
 */
object Odometer {

    /** One bracket on the calibration line: what we'd measured, and what the dash said. */
    data class CalPoint(
        val measuredKm: Double,
        val physicalKm: Double,
        val atMs: Long
    )

    /** Tracking this short after a seed is still seeding — no drift to measure yet. */
    const val MIN_FACTOR_KM = 5.0

    /** Beyond this much freshly tracked distance without a reading, ask the driver to record one. */
    const val RECORD_EVERY_KM = 450.0

    /** A reading this far past what the app expected is a red flag worth a warning, not a silent rescale. */
    const val OFF_BY_WARN_KM = 20.0

    /** A reading that moves the odometer by more than this fraction of the window is also suspect. */
    const val OFF_BY_WARN_PCT = 0.10

    // ------------------------------------------------------------------ state

    /** The full calibration line, oldest first. Falls back to the legacy singleton baseline. */
    fun points(settings: Settings): List<CalPoint> {
        val json = settings.odoPointsJson
        if (json.isNotBlank()) {
            runCatching {
                val arr = JSONArray(json)
                return (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    CalPoint(
                        o.getDouble("m"),
                        o.getDouble("p"),
                        o.getLong("t")
                    )
                }
            }
        }
        val seed = settings.odoBaselineKm
        return if (seed > 0.0) listOf(CalPoint(0.0, seed, settings.odoCalibratedAt)) else emptyList()
    }

    /** Persists the line. The legacy baseline stays in sync so old readers see the same number. */
    private fun save(settings: Settings, pts: List<CalPoint>) {
        val arr = JSONArray()
        pts.forEach { arr.put(org.json.JSONObject().put("m", it.measuredKm).put("p", it.physicalKm).put("t", it.atMs)) }
        settings.odoPointsJson = arr.toString()
        if (pts.isNotEmpty()) {
            settings.odoBaselineKm = pts.first().physicalKm
            settings.odoCalibratedAt = pts.last().atMs
        }
    }

    // ------------------------------------------------------------------ api

    /** The stored interface ratio of the open segment (1.0 until a second point exists). */
    fun factor(settings: Settings): Double {
        val pts = points(settings)
        return when {
            pts.size < 2 -> 1.0
            else -> segmentFactor(pts[pts.size - 2], pts.last())
        }
    }

    /** App odometer at [measuredKm], piecewise-linear through the calibration points. */
    fun appOdoKm(settings: Settings, measuredKm: Double): Double {
        val pts = points(settings)
        if (pts.isEmpty()) return measuredKm
        var i = pts.size - 1
        while (i > 0 && pts[i].measuredKm > measuredKm) i--
        val base = pts[i]
        if (i < pts.size - 1) {
            val f = segmentFactor(base, pts[i + 1])
            return base.physicalKm + (measuredKm - base.measuredKm) * f
        }
        return base.physicalKm + (measuredKm - base.measuredKm) * factor(settings)
    }

    /** The car-vs-app disagreement, km. Positive = car ahead (we under-measure). */
    fun drift(carOdoKm: Double, appOdoKm: Double): Double = carOdoKm - appOdoKm

    /**
     * Whether MG telematics has anchored the odometer: the car's dash reading is ground truth, so
     * once it has been quoted the app shows that number plus whatever it has measured since.
     */
    fun anchored(settings: Settings): Boolean = settings.odoMgAnchorCarKm > 0.0

    /**
     * The live odometer at the app's current measured total. When MG has quoted the car's dash, the
     * odometer is that dash number plus the measured km since the quote (scaled by the open segment
     * factor). Otherwise it is the calibration line as before. The 18,000 km a car carried before
     * tracking began is absorbed by the anchor — an offset, never spread over recorded trips.
     */
    fun liveOdoKm(settings: Settings, measuredKm: Double): Double {
        val anchorCarKm = settings.odoMgAnchorCarKm
        if (anchorCarKm > 0.0) {
            val measured = (measuredKm - settings.odoMgAnchorMeasuredKm).coerceAtLeast(0.0)
            return anchorCarKm + measured * factor(settings)
        }
        return appOdoKm(settings, measuredKm)
    }

    /**
     * New dash reading from the car itself (telematics). Adopts it as the anchor: the odometer
     * snaps to the car's real number from now on. The previous anchor is only ever replaced by a
     * plausible forward reading — a car's odometer cannot go backwards — except for the very first
     * adoption, which may be any distance from where the app had been counting.
     */
    fun adoptCarOdo(settings: Settings, carOdoKm: Double, measuredKm: Double): Boolean {
        if (carOdoKm <= 0.0) return false
        val prev = settings.odoMgAnchorCarKm
        if (prev > 0.0 && carOdoKm < prev - 0.5) return false
        settings.odoMgAnchorCarKm = carOdoKm
        settings.odoMgAnchorMeasuredKm = measuredKm
        return true
    }

    /**
     * What a calibration would look like before it is applied: the expected reading and how far
     * the typed one is off. Lets the UI warn instead of silently rewriting a window of trips.
     */
    fun preview(settings: Settings, typedKm: Double, measuredKm: Double): CalibrationPreview {
        val pts = points(settings)
        val expected = appOdoKm(settings, measuredKm)
        val offBy = typedKm - expected
        val last = pts.lastOrNull()
        val suspect = offBy >= OFF_BY_WARN_KM ||
            offBy <= -OFF_BY_WARN_KM ||
            (last != null && measuredKm - last.measuredKm > 0 &&
                kotlin.math.abs(offBy) / (measuredKm - last.measuredKm) > OFF_BY_WARN_PCT)
        return CalibrationPreview(expected, offBy, suspect)
    }

    /**
     * Applies a freshly typed dash reading. Reseeds when there is essentially no tracking; with a
     * real window on record it appends a calibration point so only since-last-reading trips gain
     * the new factor. A reading at or below the last recorded dash is a keystroke error unless we
     * are still reseeding — it is rejected via the returned [CalibrationResult].
     */
    fun calibrate(
        settings: Settings,
        typedKm: Double,
        measuredKm: Double,
        atMs: Long = System.currentTimeMillis()
    ): CalibrationResult {
        val prev = points(settings)
        val p = preview(settings, typedKm, measuredKm)
        val last = prev.lastOrNull()

        // Going backwards is only allowed while reseeding — once trips are on record the odometer
        // can only advance, so a lower reading is a typo, not a correction.
        val reseeding = prev.size < 2 && measuredKm - (last?.measuredKm ?: 0.0) < MIN_FACTOR_KM
        if (!reseeding && last != null && typedKm < last.physicalKm) {
            return CalibrationResult(
                prev, p, applied = false,
                reason = "below the last recorded %d km".format(last.physicalKm.toLong())
            )
        }

        val next: List<CalPoint> = when {
            // Seed: nothing worth measuring yet — (re)set the baseline.
            reseeding -> listOf(CalPoint(measuredKm, typedKm, atMs))
            // The last point was typed at this same measured position (double-tap, no driving): replace it.
            last != null && measuredKm <= last.measuredKm -> {
                prev.dropLast(1) + CalPoint(measuredKm, typedKm, atMs)
            }
            else -> prev + CalPoint(measuredKm, typedKm, atMs)
        }
        save(settings, next)
        return CalibrationResult(next, p, applied = true, reason = null)
    }

    /**
     * Whether the driver is overdue for a reading: more than [RECORD_EVERY_KM] of fresh tracking
     * since the last entry. Drives the "record the odometer" prompt.
     */
    fun recordDueAt(settings: Settings, measuredKm: Double): Double? {
        val last = points(settings).lastOrNull() ?: return null
        val fresh = measuredKm - last.measuredKm
        return if (fresh >= RECORD_EVERY_KM) fresh else null
    }

    /** Corrected distance for a chronologically ordered list of trips: split the drift across the trips it touches. */
    fun correctedDistances(settings: Settings, tripsAsc: List<TripEntity>): Map<Long, Double> {
        val pts = points(settings)
        if (pts.size < 2) return emptyMap()
        var cumulative = 0.0
        val out = HashMap<Long, Double>(tripsAsc.size)
        for (t in tripsAsc) {
            val seg = segmentAt(pts, cumulative)
            out[t.id] = t.distanceM / 1000.0 * seg
            cumulative += t.distanceM / 1000.0
        }
        return out
    }

    // ------------------------------------------------------------------ internals

    private fun segmentFactor(a: CalPoint, b: CalPoint): Double {
        val spanM = b.measuredKm - a.measuredKm
        val spanP = b.physicalKm - a.physicalKm
        if (spanM <= 0.0) return 1.0
        return (spanP / spanM).coerceIn(0.0, 10.0)
    }

    private fun segmentAt(pts: List<CalPoint>, measuredKm: Double): Double {
        if (pts.size < 2) return 1.0
        var i = pts.size - 1
        while (i > 0 && pts[i].measuredKm > measuredKm) i--
        return if (i < pts.size - 1) {
            segmentFactor(pts[i], pts[i + 1])
        } else {
            segmentFactor(pts[pts.size - 2], pts.last())
        }
    }
}

/** What a calibration would do before it happens, so the UI can flag a suspicious reading. */
data class CalibrationPreview(
    val expectedKm: Double,
    val offByKm: Double,
    val suspect: Boolean
)

/** The outcome of applying a calibration — rejected readings carry a human reason. */
data class CalibrationResult(
    val points: List<Odometer.CalPoint>,
    val preview: CalibrationPreview,
    val applied: Boolean,
    val reason: String?
)