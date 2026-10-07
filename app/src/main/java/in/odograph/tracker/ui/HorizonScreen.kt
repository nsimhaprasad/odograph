package `in`.odograph.tracker.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.record.TripRecorderService
import `in`.odograph.tracker.ui.theme.Palette
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Horizon: the linear face.
 *
 * The other four faces are dials; this one is not. Speed is a bare hero numeral over a thin line
 * that fills toward the limit, the charge is a column that fills from the floor, and the reference
 * figures are glass chips along the base — no needle, no arc. It reads the same data as every
 * other face and makes the same judgements (which range to trust, how a charge level colours);
 * only the shape is new.
 *
 * Day and night are the palette's job, not this layout's. The Horizon accent is a soft desaturated
 * teal at night — low glare on a dark road — and a deep saturated teal by day, where a bright cabin
 * needs the contrast. The state colours (healthy, low, critical) are the same ones the app has
 * always used, so a low battery reads the same wherever the eye has learned to look for it.
 */
@Composable
fun HorizonScreen(
    live: TripRecorderService.LiveState,
    smoothedKmh: Float,
    palette: Palette
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(palette.ground)) {
        val m = rememberMetrics(maxWidth, maxHeight)
        val aspect = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
        val stripWidth = maxWidth.value * when {
            aspect < HZ_TALL_ASPECT -> 1f
            aspect > HZ_WIDE_ASPECT -> 0.42f
            else -> 0.52f
        }
        // The dial faces carry distance, the drive's start and total time, and the odometer in a
        // dedicated row; Horizon has only chips, so those lead the strip. MOVING (which
        // secondaryStats carries) is not the total, and neither the total nor the start was shown.
        val core = buildList {
            add(DriverStat(formatKm(live.distanceM), "KM DISTANCE"))
            if (live.elapsedS > 0) add(DriverStat(startClock(live.elapsedS), "STARTED"))
            add(DriverStat(formatHhMm(live.elapsedS), "H:MM TIME"))
            live.odoKm?.let { add(DriverStat(odoValue(it), "KM ODO")) }
            live.exteriorTempC?.let { add(DriverStat("$it°", "OUTSIDE")) }
        }
        val perRow = secondaryPerRow(m.spec, stripWidth)
        val kept = secondaryStats(live).filterNot { it.label in HZ_DROP }
        val soh = kept.filter { it.label == HZ_SOH_LABEL }
        val rest = kept.filterNot { it.label == HZ_SOH_LABEL }
        val secondary = (core + rest + soh).take(horizonCapacity(m.spec, perRow))

        when {
            aspect < HZ_TALL_ASPECT -> HzTall(live, smoothedKmh, palette, m, secondary, perRow)
            aspect > HZ_WIDE_ASPECT -> HzWide(live, smoothedKmh, palette, m, secondary, perRow)
            else -> HzBalanced(live, smoothedKmh, palette, m, secondary, perRow)
        }
    }
}

/** Below this the window is a column; speed stacks above the charge and chips. */
private const val HZ_TALL_ASPECT = 1.2f

/** Above this it is a thin band; the three blocks sit in one row. */
private const val HZ_WIDE_ASPECT = 3.0f

/** The speed the limit line reads as full. Above a motorway's worst, so the bar never pins. */
private const val HZ_MAX_KMH = 160f

/** State of health is read in months, not drives, so its chip is drawn small and dim. */
private const val HZ_SOH_LABEL = "BATTERY SOH"

/**
 * Figures the dial faces carry that Horizon drops to make room for both range readings.
 *
 * Horizon folds the trip row into one chip strip, so it cannot show everything the dial faces do.
 * Top speed, the two lifetime totals and the climb are the least glanced at in motion, and cutting
 * them lets "KM THIS DRIVE" and "KM AT FULL" both stay on with a small state-of-health at the tail.
 */
/** Nothing is dropped now — Horizon shows every figure the dial faces do; the cap wraps them. */
private val HZ_DROP = emptySet<String>()

/**
 * How many chips Horizon shows. More than the dial faces allow: their strip shares the screen with
 * a dedicated trip row, so Horizon folds that row into the chips and has the height — up to three
 * rows — to carry them. The cap still governs clutter; the [FlowRow] wrap still guarantees nothing
 * clips.
 */
private fun horizonCapacity(spec: MetricSpec, perRow: Int): Int {
    val rows = if (spec.veryCompact) 1 else 4
    val ceiling = if (spec.compact) 10 else 16
    return (perRow * rows).coerceAtMost(ceiling)
}

/** The wall-clock time a drive began, worked back from how long it has been running. */
private fun startClock(elapsedS: Long): String {
    val startMs = System.currentTimeMillis() - elapsedS * 1000L
    return java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(startMs))
}

@Composable
private fun HzBalanced(
    live: TripRecorderService.LiveState, smoothedKmh: Float, palette: Palette,
    m: Metrics, secondary: List<DriverStat>, perRow: Int
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(m.pad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HzSpeed(live, smoothedKmh, palette, m, heroScale = 2.1f, modifier = Modifier.weight(1f).fillMaxHeight())
        Column(
            modifier = Modifier.weight(1.05f).fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            HzCharge(live, palette, m, Modifier.fillMaxWidth())
            HzChips(secondary, perRow, palette, m, Modifier.fillMaxWidth().padding(top = m.pad))
        }
    }
}

@Composable
private fun HzWide(
    live: TripRecorderService.LiveState, smoothedKmh: Float, palette: Palette,
    m: Metrics, secondary: List<DriverStat>, perRow: Int
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(m.pad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.gap)
    ) {
        HzSpeed(live, smoothedKmh, palette, m, heroScale = 1.5f, modifier = Modifier.weight(1.1f).fillMaxHeight())
        HzCharge(live, palette, m, Modifier.weight(1.3f))
        HzChips(secondary, perRow, palette, m, Modifier.weight(1.6f))
    }
}

@Composable
private fun HzTall(
    live: TripRecorderService.LiveState, smoothedKmh: Float, palette: Palette,
    m: Metrics, secondary: List<DriverStat>, perRow: Int
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(m.pad),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HzSpeed(live, smoothedKmh, palette, m, heroScale = 1.9f, modifier = Modifier.fillMaxWidth().weight(1f))
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
            HzCharge(live, palette, m, Modifier.fillMaxWidth())
            HzChips(secondary, perRow, palette, m, Modifier.fillMaxWidth().padding(top = m.gap))
        }
    }
}

/**
 * Speed as a bare numeral over a line that fills toward the limit.
 *
 * The one reading taken in a glance, so it is the largest thing on the glass. The line beneath
 * answers the next question — am I over — without a second number: it fills to the current speed,
 * carries a mark at the limit, and turns to the warn colour the moment it passes that mark. A tap
 * anywhere asks the poller for a fresh frame, the same as every other face.
 */
@Composable
private fun HzSpeed(
    live: TripRecorderService.LiveState, smoothedKmh: Float, palette: Palette, m: Metrics,
    heroScale: Float, modifier: Modifier = Modifier
) {
    val over = live.overLimit
    Column(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clickable { TripRecorderService.requestTelematicsRefresh() },
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (live.hasFix) smoothedKmh.roundToInt().toString() else "--",
            color = if (over) palette.warn else palette.numeral,
            fontSize = m.hero * heroScale,
            lineHeight = m.hero * heroScale,
            fontWeight = FontWeight.Black,
            letterSpacing = (-2).sp,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(m.gap)) {
            Text("KM/H", color = palette.accent, fontSize = m.stat, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            if (live.speedLimitKmh > 0) {
                Text(
                    "LIMIT ${live.speedLimitKmh}",
                    color = if (over) palette.warn else palette.dim,
                    fontSize = m.label, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp
                )
            }
        }
        HzLimitLine(smoothedKmh, live.speedLimitKmh, over, palette, m, Modifier.fillMaxWidth().padding(top = m.pad))
    }
}

@Composable
private fun HzLimitLine(
    kmh: Float, limitKmh: Int, over: Boolean, palette: Palette, m: Metrics, modifier: Modifier = Modifier
) {
    val frac by animateFloatAsState(
        targetValue = (kmh / HZ_MAX_KMH).coerceIn(0f, 1f),
        animationSpec = tween(500), label = "hzSpeedFill"
    )
    val fillColor by animateColorAsState(if (over) palette.warn else palette.accent, tween(400), label = "hzFill")
    val limitFrac = (limitKmh / HZ_MAX_KMH).coerceIn(0f, 1f)
    Canvas(modifier.height(m.pad * 0.6f)) {
        val h = size.height; val w = size.width; val r = h / 2f
        drawRoundRect(palette.trackSoft, size = Size(w, h), cornerRadius = CornerRadius(r, r))
        val fw = (w * frac)
        if (fw > 0f) drawRoundRect(fillColor, size = Size(fw, h), cornerRadius = CornerRadius(r, r))
        if (limitKmh > 0) {
            val tx = w * limitFrac
            val tw = h * 0.22f
            drawRoundRect(
                palette.dim,
                topLeft = Offset(tx - tw / 2f, -h * 0.5f),
                size = Size(tw, h * 2f),
                cornerRadius = CornerRadius(tw, tw)
            )
        }
    }
}

/**
 * The charge, read two ways at once: how far it goes, and how full it is.
 *
 * A column that fills from the floor sits beside the range, both carrying the same colour — green
 * while there is room to plan, amber when the next charge needs thinking about, red when it is a
 * constraint on where the car goes. The percentage and the mode ride underneath; the bolt on the
 * column is the only thing that says a charger is plugged in.
 */
@Composable
private fun HzCharge(
    live: TripRecorderService.LiveState, palette: Palette, m: Metrics, modifier: Modifier = Modifier
) {
    val soc = live.batterySocPercent
    val readout = rangeReadout(live.batteryRangeKm, live.mgBatteryRangeKm)
    if (soc == null && readout == null) return
    val level = soc?.let { socLevel(it) } ?: SocLevel.HEALTHY
    val stateColor by animateColorAsState(socColor(level, palette), tween(700), label = "hzState")

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clickable { TripRecorderService.requestTelematicsRefresh() }
            .semantics { contentDescription = hzDescription(soc, readout, live.batteryCharging) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.gap * 1.4f)
    ) {
        HzBattery(
            socFrac = ((soc ?: 0.0) / 100.0).toFloat(),
            charging = live.batteryCharging == true,
            color = stateColor, palette = palette, m = m
        )
        Column {
            Text(
                text = readout?.km?.roundToInt()?.toString() ?: "--",
                color = stateColor,
                fontSize = m.hero,
                lineHeight = m.hero,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
            Text(
                text = readout?.let { rangeCaption(it) } ?: "KM RANGE",
                color = palette.label, fontSize = m.label, fontWeight = FontWeight.Medium,
                letterSpacing = 1.4.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.padding(top = m.gap / 2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(m.gap / 2)
            ) {
                if (soc != null) {
                    Text("${soc.roundToInt()}%", color = palette.numeral, fontSize = m.stat, fontWeight = FontWeight.Bold)
                }
                HzPill(hzMode(live, level), stateColor, m)
            }
        }
    }
}

/** The vertical charge column: a capsule on a soft track, filled from the floor, capped on top. */
@Composable
private fun HzBattery(socFrac: Float, charging: Boolean, color: Color, palette: Palette, m: Metrics) {
    val fill by animateFloatAsState(socFrac.coerceIn(0f, 1f), tween(800), label = "hzFill")
    val w = m.pad * 2.4f
    val h = m.pad * 7.5f
    val shape = RoundedCornerShape(w * 0.34f)
    Box(Modifier.width(w).height(h)) {
        // terminal cap
        Box(
            Modifier.align(Alignment.TopCenter)
                .width(w * 0.42f).height(m.pad * 0.5f)
                .background(palette.dim, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
        )
        // body
        Box(
            Modifier.fillMaxWidth().fillMaxHeight()
                .padding(top = m.pad * 0.5f)
                .clip(shape).background(palette.trackSoft).border(1.5.dp, palette.track, shape)
                .padding(w * 0.14f)
        ) {
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(fill.coerceIn(0.045f, 1f))
                    .clip(RoundedCornerShape(w * 0.2f))
                    .background(color)
            ) {
                // Ions drifting up through the charge — a slow, soft rise, lighter than the fill.
                HzIons(Modifier.fillMaxSize())
            }
            if (charging) {
                Text(
                    "⚡", color = palette.ground, fontSize = m.stat,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

/** One rising ion: where across the width it sits, its phase offset, how fast it climbs, its size. */
private class Ion(val x: Float, val phase0: Float, val speed: Float, val radius: Float)

/**
 * Charge drawn as ions drifting upward through the fill, like bubbles in water.
 *
 * A single looping phase moves every ion; each starts at its own offset and climbs at its own
 * speed, drifting sideways on a sine as it goes, and fades in from the floor and out at the top so
 * none pops. Decorative only — it reads the charge colour behind it and adds nothing a glance needs.
 */
@Composable
private fun HzIons(modifier: Modifier) {
    val ions = remember {
        val r = Random(7)
        List(9) { Ion(x = r.nextFloat(), phase0 = r.nextFloat(), speed = 0.55f + r.nextFloat() * 0.8f, radius = 0.05f + r.nextFloat() * 0.06f) }
    }
    val t = rememberInfiniteTransition(label = "ions")
    val phase by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4600, easing = LinearEasing), RepeatMode.Restart),
        label = "ionPhase"
    )
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val unit = if (w < h) w else h
        ions.forEach { ion ->
            val y = (ion.phase0 + phase * ion.speed) % 1f           // 0 at floor, 1 at top
            val cy = h * (1f - y)
            val rad = unit * ion.radius
            val drift = sin(((y + ion.phase0) * 6.2832f).toDouble()).toFloat() * w * 0.12f
            val cx = (w * ion.x + drift).coerceIn(rad, w - rad)
            val alpha = sin((y * 3.1416f).toDouble()).toFloat().coerceIn(0f, 1f) * 0.45f
            if (alpha > 0.01f) drawCircle(Color.White.copy(alpha = alpha), rad, Offset(cx, cy))
        }
    }
}

@Composable
private fun HzPill(text: String, color: Color, m: Metrics) {
    Text(
        text = text,
        color = color,
        fontSize = m.label,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = m.gap / 2, vertical = m.pad * 0.18f)
    )
}

/**
 * The reference figures as glass chips, wrapped so they can never run off the edge.
 *
 * Same data and same order as every other face ([secondaryStats]); only the chip is Horizon's.
 * [FlowRow] with a fixed row width is what guarantees the strip wraps to a second line instead of
 * clipping its tail, exactly as the dial faces' strip does.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HzChips(
    stats: List<DriverStat>, perRow: Int, palette: Palette, m: Metrics, modifier: Modifier = Modifier
) {
    if (stats.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(m.gap / 2),
        verticalArrangement = Arrangement.spacedBy(m.gap / 2),
        maxItemsInEachRow = perRow
    ) {
        stats.forEach { HzChip(it, palette, m) }
    }
}

@Composable
private fun HzChip(stat: DriverStat, palette: Palette, m: Metrics) {
    // The health figure is deliberately the quiet one: a smaller, dimmer value, because it is
    // checked across months and does not earn a driver's glance on every trip.
    val minor = stat.label == HZ_SOH_LABEL
    val valueSize = if (minor) m.body else m.read
    val shape = RoundedCornerShape(m.pad * 0.5f)
    Column(
        Modifier.clip(shape).background(palette.trackSoft).border(1.dp, palette.track, shape)
            .padding(horizontal = m.gap / 2, vertical = m.pad * 0.35f)
    ) {
        Text(
            stat.value, color = if (minor) palette.dim else palette.numeral,
            fontSize = valueSize, lineHeight = valueSize * 1.05f,
            fontWeight = if (minor) FontWeight.SemiBold else FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Clip
        )
        Text(
            stat.label, color = palette.label, fontSize = m.label, letterSpacing = 1.1.sp,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/** The one-word mode beside the charge: what the car is doing, in the charge's own colour. */
private fun hzMode(live: TripRecorderService.LiveState, level: SocLevel): String = when {
    live.batteryCharging == true -> "CHARGING"
    level == SocLevel.CRITICAL -> "CRITICAL"
    level == SocLevel.LOW -> "LOW"
    else -> "DRIVE"
}

/** What a screen reader announces for the charge block, which is otherwise a wall of glyphs. */
private fun hzDescription(soc: Double?, readout: RangeReadout?, charging: Boolean?): String = buildString {
    readout?.let { append("Range ${it.km.roundToInt()} kilometres. ") }
    soc?.let { append("Battery ${it.roundToInt()} percent") }
    if (charging == true) append(", charging")
    append(". Tap to refresh from the car.")
}
