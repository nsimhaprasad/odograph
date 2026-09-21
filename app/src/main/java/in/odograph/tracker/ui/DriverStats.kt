package `in`.odograph.tracker.ui

import `in`.odograph.tracker.record.TripRecorderService
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the driving screen decides, kept free of Compose.
 *
 * The screen's real content is a series of judgements — which range figure to trust, whether the
 * car's second opinion is worth the space, how a charge level reads, which of a dozen optional
 * numbers earn a slot on a 240dp-tall band. Making those judgements inside composables put them
 * out of reach of a JVM test: the only way to assert them was to render pixels and look. As plain
 * functions over [TripRecorderService.LiveState] they are ordinary arithmetic, and the composables
 * are left doing nothing but layout.
 */

/** A reference reading: real information, but not what a glance at speed is for. */
data class DriverStat(val value: String, val label: String)

/**
 * How the charge reads at a glance. Colour is chosen from this, so it carries no Compose type.
 *
 * Deliberately says nothing about whether a charger is plugged in. The screen used to colour a
 * charging battery with the instrument's accent, which on the AUDI face is needle red — so a car
 * charging normally lit up in the same colour the screen uses for danger. Level and charging are
 * independent facts: this one drives colour, and the bolt and caption carry the other.
 */
enum class SocLevel { HEALTHY, LOW, CRITICAL }

/** Remaining range, whose estimate it came from, and the car's figure when worth cross-checking. */
data class RangeReadout(val km: Double, val fromCar: Boolean, val crossCheckKm: Double?)

/** At or below this the fill turns amber — close enough that the next charge needs planning. */
const val SOC_LOW_PERCENT = 30.0

/** At or below this it turns red: range anxiety is now a real constraint on where the car goes. */
const val SOC_CRITICAL_PERCENT = 12.0

/**
 * Below this the two range estimates are quoting the same number and printing both reads as a
 * fault rather than a cross-check. The previous screen compared them with `!=` on raw doubles, so
 * 208.0 and 208.4 counted as a disagreement and rendered "208 km · car 208".
 */
const val RANGE_AGREEMENT_KM = 1.0

fun socLevel(socPercent: Double): SocLevel = when {
    socPercent <= SOC_CRITICAL_PERCENT -> SocLevel.CRITICAL
    socPercent <= SOC_LOW_PERCENT -> SocLevel.LOW
    else -> SocLevel.HEALTHY
}

/**
 * Which range to put on the glass. The box's own measured efficiency wins once enough instrumented
 * drives exist to trust it; the car's quote stands in until then. When both are known the car's
 * figure rides along as a small cross-check — but only when the two genuinely disagree.
 */
fun rangeReadout(smartKm: Double?, carKm: Double?): RangeReadout? {
    val main = smartKm ?: carKm ?: return null
    val disagrees = smartKm != null && carKm != null && abs(smartKm - carKm) >= RANGE_AGREEMENT_KM
    return RangeReadout(
        km = main,
        fromCar = smartKm == null,
        crossCheckKm = if (disagrees) carKm else null
    )
}

/**
 * The reference stats, most useful to a driver first.
 *
 * Order is the whole point: a viewport that can only carry four of these should carry the four
 * that matter in motion, not the four that happen to be non-null first. The speed limit leads
 * because it is the only entry that bears on how the car is being driven right now; lifetime
 * energy trails because it never changes within a drive.
 */
fun secondaryStats(live: TripRecorderService.LiveState): List<DriverStat> = buildList {
    if (live.speedLimitKmh > 0) add(DriverStat("${live.speedLimitKmh}", "KM/H LIMIT"))
    add(DriverStat("${mpsToKmh(live.maxSpeedMps).roundToInt()}", "KM/H MAX"))
    add(DriverStat(formatHhMm(live.movingS), "MOVING"))
    live.tripEnergyKwh?.let { add(DriverStat("%.1f".format(it), "KWH USED")) }
    live.tripCostInr?.let { add(DriverStat("₹%.0f".format(it), "RIDE COST")) }
    live.batteryMileageKmPerKwh?.let { add(DriverStat("%.1f".format(it), "KM/KWH")) }
    // The same charge read two other ways. They sit next to each other on purpose: where they
    // disagree is the information — a live figure well above the lifetime one is a gentle hour,
    // well below it is a hint to ease off — and neither means much without the other for scale.
    live.liveRangeKm?.let { add(DriverStat("%.0f".format(it), "KM THIS DRIVE")) }
    live.lifetimeRangeKm?.let { add(DriverStat("%.0f".format(it), "KM LIFETIME")) }
    live.batteryRangeAtFullKm?.let { add(DriverStat("%.0f".format(it), "KM AT FULL")) }
    if (live.elevGainM > 0 || live.elevLossM > 0) {
        add(DriverStat("↑%.0f ↓%.0f".format(live.elevGainM, live.elevLossM), "CLIMB M"))
    }
    add(DriverStat("%.0f".format(live.batteryTotalKwh), "KWH LIFETIME"))
}

/** Roughly what one reference stat advances horizontally, in multiples of its own type size. */
private const val SECONDARY_STAT_WIDTH_FACTOR = 5.5f

/**
 * How many reference stats a viewport can carry without crowding the readings that matter.
 *
 * Width decides how many sit side by side, height decides how many rows they may wrap onto, and
 * compactness caps the lot. A cap rather than a scrollbar or an ellipsis: the driving screen is
 * glanced at, never browsed, so a number that cannot be rendered legibly is better dropped than
 * shrunk. The strip this feeds also wraps, so the cap governs clutter while the wrap is what
 * guarantees nothing is ever clipped — the previous screen had neither and lost the tail of its
 * last row off the right edge.
 */
fun secondaryCapacity(spec: MetricSpec, widthDp: Float): Int {
    val rows = if (spec.veryCompact) 1 else 2
    val ceiling = if (spec.compact) 6 else 9
    return (secondaryPerRow(spec, widthDp) * rows).coerceAtMost(ceiling)
}

/**
 * How many reference stats sit on one line of the strip.
 *
 * Handed to the wrap as a fixed row width so the rows come out even. Left to itself the wrap fills
 * each line greedily, which turns six stats into five and a lone straggler — tidy enough, but it
 * reads as something having gone wrong rather than as a deliberate second row.
 */
fun secondaryPerRow(spec: MetricSpec, widthDp: Float): Int {
    val perStatDp = spec.readSp * SECONDARY_STAT_WIDTH_FACTOR + spec.gapDp
    return (widthDp / perStatDp).toInt().coerceAtLeast(1)
}

/** Drift smaller than this is measurement noise, not a disagreement worth marking. */
const val ODO_DRIFT_SHOW_KM = 1.0

/** The odometer reading itself, kept clean so the number stays the widest thing it has to be. */
fun odoValue(odoKm: Double): String = "%.0f".format(odoKm)

/**
 * The odometer caption, carrying the drift mark when the car disagrees meaningfully.
 *
 * The mark used to be appended to the number, which made the odometer the widest reading on the
 * screen and got its tail clipped in the two-column arrangement — the drift, the one part worth
 * noticing, was the part that disappeared. In the caption it always has room.
 */
fun odoCaption(driftKm: Double?): String = buildString {
    append("KM   ODO")
    driftKm?.takeIf { abs(it) >= ODO_DRIFT_SHOW_KM }?.let {
        append("  ·  ").append("%+.1f".format(it))
    }
}

/** The range line: the trusted figure, and the car's own when it is worth a second look. */
fun rangeCaption(readout: RangeReadout): String = buildString {
    append(if (readout.fromCar) "KM RANGE · CAR" else "KM RANGE")
    readout.crossCheckKm?.let { append("  ·  CAR ").append(it.roundToInt()) }
}
