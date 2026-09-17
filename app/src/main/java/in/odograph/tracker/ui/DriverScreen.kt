package `in`.odograph.tracker.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.record.TripRecorderService
import `in`.odograph.tracker.ui.gauge.Gauge
import `in`.odograph.tracker.ui.theme.Direction
import `in`.odograph.tracker.ui.theme.Palette
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The window this draws into is not fixed. The box supports split screen with a divider the
 * driver can put anywhere, so the same 1920x1080 panel yields anything from 1291x726 dp down to
 * 1291x181 dp, or a narrow 645x726 dp column when split the other way.
 *
 * Scaling every dimension from the viewport keeps text and padding sane, but it cannot fix a
 * layout whose *structure* is wrong for the shape: a gauge beside a stats column is right at 2:1,
 * stranded either side of a void at 7:1, and worse than a vertical stack at 0.9:1. So the
 * arrangement branches on aspect ratio, and only the arrangement does — every size still comes
 * from the same metrics.
 *
 * What the arrangement does *not* decide is rank. A driving screen is read in glances of well
 * under a second, so the order of size is the order of urgency: speed, then how far the charge
 * still goes, then the trip, then everything else. The screen used to be sized the other way
 * about — trip distance was the largest number on the glass and remaining range was drawn at
 * label size, smaller than its own caption — which is a readable dashboard only when parked.
 */
@Composable
fun DriverScreen(
    live: TripRecorderService.LiveState,
    smoothedKmh: Float,
    direction: Direction,
    palette: Palette
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(palette.ground)) {
        val m = rememberMetrics(maxWidth, maxHeight)
        val aspect = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
        // The strip does not get the whole panel in every arrangement, and sizing it as though it
        // did is how a row ends up one stat wider than the space it has to render in.
        val stripWidth = maxWidth.value * when {
            aspect < TALL_ASPECT -> 1f
            aspect > WIDE_ASPECT -> WIDE_STRIP_SHARE
            else -> BALANCED_COLUMN_SHARE
        }
        val perRow = secondaryPerRow(m.spec, stripWidth)
        val secondary = secondaryStats(live).take(secondaryCapacity(m.spec, stripWidth))

        val gauge: @Composable (Modifier) -> Unit = { mod ->
            Gauge(
                speedKmh = smoothedKmh,
                hasFix = live.hasFix,
                direction = direction,
                palette = palette,
                modifier = mod,
                overLimit = live.overLimit
            )
        }

        when {
            aspect < TALL_ASPECT -> TallLayout(live, palette, m, secondary, perRow, gauge)
            aspect > WIDE_ASPECT -> WideLayout(live, palette, m, secondary, perRow, gauge)
            else -> BalancedLayout(live, palette, m, secondary, perRow, gauge)
        }
    }
}

/** Below this the window is a column, and a gauge beside a stats block stops fitting. */
private const val TALL_ASPECT = 1.2f

/** Above this it is a band, and a two-column arrangement strands its halves either side of a void. */
private const val WIDE_ASPECT = 3.0f

/** The stats column's share of the balanced arrangement, from the weights the Row is given. */
private const val BALANCED_COLUMN_SHARE = 1.15f / 2.15f

/** Roughly what the band has left once the gauge and the primary readings have taken their share. */
private const val WIDE_STRIP_SHARE = 0.55f

/** Narrow column: stack the instrument above the numbers rather than squeezing them side by side. */
@Composable
private fun TallLayout(
    live: TripRecorderService.LiveState,
    palette: Palette,
    m: Metrics,
    secondary: List<DriverStat>,
    perRow: Int,
    gauge: @Composable (Modifier) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(m.pad),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        gauge(Modifier.fillMaxWidth().weight(1.4f))
        RangePanel(live, palette, m, Modifier.fillMaxWidth())
        TripStats(live, palette, m, Modifier.fillMaxWidth())
        SecondaryStrip(secondary, perRow, palette, m, Modifier.fillMaxWidth())
    }
}

/** A thin band across the top or bottom of the screen: spread the numbers along the width. */
@Composable
private fun WideLayout(
    live: TripRecorderService.LiveState,
    palette: Palette,
    m: Metrics,
    secondary: List<DriverStat>,
    perRow: Int,
    gauge: @Composable (Modifier) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(m.pad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.gap)
    ) {
        gauge(Modifier.fillMaxHeight().weight(0.9f))
        RangePanel(live, palette, m, Modifier.weight(1.8f).padding(end = m.gap))
        Stat(formatKm(live.distanceM), "KM   DISTANCE", palette, m, size = m.stat,
            modifier = Modifier.weight(1f))
        Stat(formatHhMm(live.elapsedS), "H:MM   TIME", palette, m, size = m.stat,
            modifier = Modifier.weight(1f))
        live.odoKm?.let {
            Stat(
                odoValue(it), odoCaption(live.odoDriftKm), palette, m, size = m.stat,
                modifier = Modifier.weight(1.2f), contentColor = driftColor(live.odoDriftKm, palette)
            )
        }
        secondary.forEach {
            ReadStat(it, palette, m, Modifier.weight(1f))
        }
    }
}

/** The ordinary case: instrument on the left, the readings that matter stacked on the right. */
@Composable
private fun BalancedLayout(
    live: TripRecorderService.LiveState,
    palette: Palette,
    m: Metrics,
    secondary: List<DriverStat>,
    perRow: Int,
    gauge: @Composable (Modifier) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(m.pad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        gauge(Modifier.weight(1f).fillMaxHeight())
        Column(
            modifier = Modifier.weight(1.15f).fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            RangePanel(live, palette, m, Modifier.fillMaxWidth())
            TripStats(live, palette, m, Modifier.fillMaxWidth().padding(top = m.gap))
            SecondaryStrip(secondary, perRow, palette, m, Modifier.fillMaxWidth().padding(top = m.gap))
        }
    }
}

/**
 * Distance, time and the odometer: the trip's own account of itself.
 *
 * One rank below the charge, because none of it changes what the driver does in the next minute.
 */
@Composable
private fun TripStats(
    live: TripRecorderService.LiveState,
    palette: Palette,
    m: Metrics,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(m.gap),
        verticalAlignment = Alignment.Bottom
    ) {
        Stat(formatKm(live.distanceM), "KM   DISTANCE", palette, m, size = m.stat,
            modifier = Modifier.weight(1f))
        Stat(formatHhMm(live.elapsedS), "H:MM   TIME", palette, m, size = m.stat,
            modifier = Modifier.weight(1f))
        live.odoKm?.let {
            Stat(
                odoValue(it), odoCaption(live.odoDriftKm), palette, m, size = m.stat,
                modifier = Modifier.weight(1.4f),
                contentColor = driftColor(live.odoDriftKm, palette)
            )
        }
    }
}

/**
 * The reference stats, laid out so they wrap instead of running off the edge.
 *
 * [FlowRow] is the load-bearing choice. The previous screen put every optional stat into a plain
 * [Row], which silently clipped whatever did not fit — on a 640dp panel the last two entries were
 * cut mid-word and the two after that never drew at all, so the screen quietly lied about how
 * much it was showing. A wrap cannot do that: the worst case is a second line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecondaryStrip(
    stats: List<DriverStat>,
    perRow: Int,
    palette: Palette,
    m: Metrics,
    modifier: Modifier = Modifier
) {
    if (stats.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(m.gap),
        verticalArrangement = Arrangement.spacedBy(m.gap / 2),
        maxItemsInEachRow = perRow
    ) {
        stats.forEach { ReadStat(it, palette, m) }
    }
}

/**
 * One reference stat, at a size a driver can actually read.
 *
 * The value carries the page's numeral colour at the dedicated read step; the caption sits under
 * it, dimmer and smaller. The screen used to render both at label size in the same dim grey, so
 * "2.87 kWh" and the word "THIS RIDE" were typographically indistinguishable and neither was
 * legible at arm's length.
 */
@Composable
private fun ReadStat(
    stat: DriverStat,
    palette: Palette,
    m: Metrics,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(
            text = stat.value,
            color = palette.numeral,
            fontSize = m.read,
            lineHeight = m.read * 1.1f,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
        Text(
            text = stat.label,
            color = palette.label,
            fontSize = m.label,
            letterSpacing = 1.1.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Remaining range and state of charge — the one reading an EV driver actually plans around.
 *
 * Given the rank it deserves: the range is set at the same size as the speed's siblings and
 * coloured by how much charge is left, so the decision "do I stop now" is answerable from colour
 * alone before the number is even read. The battery fill and percentage ride beside it as the
 * supporting detail, and the car's own estimate appears in the caption only when it disagrees.
 *
 * Tapping anywhere in the panel asks the poller for a fresh telematics frame.
 */
@Composable
private fun RangePanel(
    live: TripRecorderService.LiveState,
    palette: Palette,
    m: Metrics,
    modifier: Modifier = Modifier
) {
    val soc = live.batterySocPercent
    val readout = rangeReadout(live.batteryRangeKm, live.mgBatteryRangeKm)
    if (soc == null && readout == null) return

    val level = soc?.let { socLevel(it) } ?: SocLevel.HEALTHY
    val stateColor by animateColorAsState(
        targetValue = socColor(level, palette),
        animationSpec = tween(CHARGE_ANIMATION_MS),
        label = "socColor"
    )

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp)
            .clickable { TripRecorderService.requestTelematicsRefresh() }
            .semantics { contentDescription = rangeDescription(soc, readout, live.batteryCharging) },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(m.gap)
    ) {
        if (readout != null) {
            // Deliberately unweighted. Giving the range the row's spare width pinned the charge
            // meter to the far edge, so on the full 1291dp window the two halves of one reading —
            // how far it goes, and how much is left — sat five hundred pixels apart and stopped
            // reading as a pair.
            Stat(
                value = "${readout.km.roundToInt()}",
                label = rangeCaption(readout),
                palette = palette,
                m = m,
                size = m.hero,
                contentColor = stateColor
            )
        }
        if (soc != null) {
            ChargeMeter(soc, live.batteryCharging, stateColor, palette, m)
        }
    }
}

/** The battery fill, its percentage, and the bolt that marks a charger being plugged in. */
@Composable
private fun ChargeMeter(
    socPercent: Double,
    charging: Boolean?,
    stateColor: Color,
    palette: Palette,
    m: Metrics
) {
    val target = (socPercent / 100.0).coerceIn(0.0, 1.0).toFloat()
    val fill by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(CHARGE_ANIMATION_MS),
        label = "batteryFill"
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.gap / 3)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(m.gap * BATTERY_WIDTH_GAPS)
                        .height(m.pad * BATTERY_HEIGHT_PADS)
                        .border(1.5.dp, stateColor, RoundedCornerShape(2.dp))
                        .padding(1.dp)
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(stateColor))
                }
                Box(
                    Modifier
                        .width(3.dp)
                        .height(m.pad * BATTERY_TERMINAL_PADS)
                        .background(stateColor.copy(alpha = 0.9f), RoundedCornerShape(1.dp))
                )
            }
            Text(
                text = if (charging == true) "⚡${socPercent.roundToInt()}%" else "${socPercent.roundToInt()}%",
                color = stateColor,
                fontSize = m.stat,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
        Text(
            text = if (charging == true) "CHARGING" else "BATTERY",
            color = palette.label,
            fontSize = m.label,
            letterSpacing = 1.1.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/** How long the fill and the state colour take to settle after a poll. */
private const val CHARGE_ANIMATION_MS = 900

/** Android's minimum comfortable touch target, and a moving car deserves no less. */
private const val MIN_TOUCH_TARGET_DP = 48

private const val BATTERY_WIDTH_GAPS = 4f
private const val BATTERY_HEIGHT_PADS = 1.7f
private const val BATTERY_TERMINAL_PADS = 0.85f

/**
 * Charge state to colour, from the palette's state tokens rather than its accents.
 *
 * The tokens darken on a light ground and mean the same thing on every instrument face. The
 * literals this replaced — a mid-green and a mid-red written into the screen — washed out badly
 * against the day palette and sat too close to the ION accent at night.
 */
private fun socColor(level: SocLevel, palette: Palette): Color = when (level) {
    SocLevel.HEALTHY -> palette.good
    SocLevel.LOW -> palette.caution
    SocLevel.CRITICAL -> palette.warn
}

/**
 * On track: the ordinary numeral. Drifting past what the recorder itself considers worth raising:
 * the warn colour.
 *
 * The threshold is the service's own [TripRecorderService.ODO_DRIFT_FLAG_KM], so the screen and
 * the notification agree about what counts as a problem — colouring at the much lower threshold
 * the caption uses would paint the odometer amber for a drift the app has already decided is
 * noise. Kept clear of the accents for the same reason as [socColor]: tinting a healthy odometer
 * with the instrument accent painted it alarm red on the AUDI face while it was perfectly on track.
 */
private fun driftColor(driftKm: Double?, palette: Palette): Color =
    if (driftKm != null && abs(driftKm) >= TripRecorderService.ODO_DRIFT_FLAG_KM) palette.warn
    else palette.numeral

/** What a screen reader announces for the charge panel, which is otherwise a wall of glyphs. */
private fun rangeDescription(
    socPercent: Double?,
    readout: RangeReadout?,
    charging: Boolean?
): String = buildString {
    readout?.let { append("Range ${it.km.roundToInt()} kilometres. ") }
    socPercent?.let { append("Battery ${it.roundToInt()} percent") }
    if (charging == true) append(", charging")
    append(". Tap to refresh from the car.")
}
