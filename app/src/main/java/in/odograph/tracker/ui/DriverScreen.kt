package `in`.odograph.tracker.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.record.TripRecorderService
import `in`.odograph.tracker.ui.gauge.Gauge
import `in`.odograph.tracker.ui.theme.Direction
import `in`.odograph.tracker.ui.theme.Palette
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
            aspect < 1.2f -> TallLayout(live, palette, m, gauge)
            aspect > 3.0f -> WideLayout(live, palette, m, gauge)
            else -> BalancedLayout(live, palette, m, gauge)
        }
    }
}

/** Narrow column: stack the instrument above the numbers rather than squeezing them side by side. */
@Composable
private fun TallLayout(
    live: TripRecorderService.LiveState,
    palette: Palette,
    m: Metrics,
    gauge: @Composable (Modifier) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(m.pad),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        gauge(Modifier.fillMaxWidth().weight(1.4f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Stat(formatKm(live.distanceM), "KM   DISTANCE", palette, m, size = m.stat)
            Stat(formatHhMm(live.elapsedS), "H:MM   TIME", palette, m, size = m.stat)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SmallStat("${mpsToKmh(live.maxSpeedMps).toInt()}", "MAX", palette, m)
            SmallStat(formatHhMm(live.movingS), "MOVING", palette, m)
            if (live.speedLimitKmh > 0) {
                SmallStat("${live.speedLimitKmh}", "LIMIT", palette, m)
            }
            live.batterySocPercent?.let {
                BatteryMeter(
                    it, live.batteryCharging, palette, m,
                    onClick = { TripRecorderService.requestTelematicsRefresh() },
                    smartRangeKm = live.batteryRangeKm,
                    carRangeKm = live.mgBatteryRangeKm
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            live.tripEnergyKwh?.let {
                SmallStat("%.2f kWh".format(it), "THIS RIDE", palette, m)
            }
            live.tripCostInr?.let {
                SmallStat("₹%.2f".format(it), "THIS RIDE", palette, m)
            }
            live.batteryRangeAtFullKm?.let {
                SmallStat("%.0f km".format(it), "RANGE@100", palette, m)
            }
            live.batteryMileageKmPerKwh?.let {
                SmallStat("%.2f km/kWh".format(it), "MILEAGE", palette, m)
            }
            SmallStat("%.2f kWh".format(live.batteryTotalKwh), "LIFETIME", palette, m)
            if (live.elevGainM > 0 || live.elevLossM > 0) {
                SmallStat(
                    "↑%.0f ↓%.0f".format(live.elevGainM, live.elevLossM), "CLIMB  M", palette, m
                )
            }
        }
    }
}

/** A thin band across the top or bottom of the screen: spread the numbers along the width. */
@Composable
private fun WideLayout(
    live: TripRecorderService.LiveState,
    palette: Palette,
    m: Metrics,
    gauge: @Composable (Modifier) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(m.pad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.gap)
    ) {
        gauge(Modifier.fillMaxHeight().weight(0.9f))
        Stat(formatKm(live.distanceM), "KM   DISTANCE", palette, m, size = m.stat,
            modifier = Modifier.weight(1f))
        Stat(formatHhMm(live.elapsedS), "H:MM   TIME", palette, m, size = m.stat,
            modifier = Modifier.weight(1f))
        Stat("${mpsToKmh(live.maxSpeedMps).toInt()}", "KM/H   MAX", palette, m, size = m.stat,
            modifier = Modifier.weight(1f))
        Stat(formatHhMm(live.movingS), "H:MM   MOVING", palette, m, size = m.stat,
            modifier = Modifier.weight(1f))
        live.batterySocPercent?.let {
            BatteryMeter(
                it, live.batteryCharging, palette, m,
                modifier = Modifier.weight(1f),
                onClick = { TripRecorderService.requestTelematicsRefresh() },
                smartRangeKm = live.batteryRangeKm,
                carRangeKm = live.mgBatteryRangeKm
            )
        }
        live.batteryRangeAtFullKm?.let {
            Stat("%.0f".format(it), "KM RANGE@100", palette, m, size = m.stat,
                modifier = Modifier.weight(1f))
        }
        live.batteryMileageKmPerKwh?.let {
            Stat("%.1f".format(it), "KM/KWH", palette, m, size = m.stat,
                modifier = Modifier.weight(1f))
        }
        live.tripEnergyKwh?.let {
            Stat("%.1f".format(it), "KWH   THIS RIDE", palette, m, size = m.stat,
                modifier = Modifier.weight(1f))
        }
        live.tripCostInr?.let {
            Stat("₹%.1f".format(it), "RS   THIS RIDE", palette, m, size = m.stat,
                modifier = Modifier.weight(1f))
        }
        Stat("%.1f".format(live.batteryTotalKwh), "KWH   TOTAL", palette, m, size = m.stat,
            modifier = Modifier.weight(1f))
        if (live.elevGainM > 0 || live.elevLossM > 0) {
            Stat(
                "↑%.0f ↓%.0f".format(live.elevGainM, live.elevLossM), "M  CLIMB",
                palette, m, size = m.stat, modifier = Modifier.weight(1f)
            )
        }
        if (live.speedLimitKmh > 0) {
            Stat("${live.speedLimitKmh}", "KM/H   LIMIT", palette, m, size = m.stat,
                modifier = Modifier.weight(1f))
        }
    }
}

/** The ordinary case: instrument on the left, the two numbers that matter stacked on the right. */
@Composable
private fun BalancedLayout(
    live: TripRecorderService.LiveState,
    palette: Palette,
    m: Metrics,
    gauge: @Composable (Modifier) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(m.pad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        gauge(Modifier.weight(1f).fillMaxHeight())
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Stat(formatKm(live.distanceM), "KM   DISTANCE", palette, m)
            Column(Modifier.padding(top = m.gap)) {
                Stat(formatHhMm(live.elapsedS), "H:MM   TIME", palette, m)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = m.gap),
                horizontalArrangement = Arrangement.spacedBy(m.gap)
            ) {
                SmallStat("${mpsToKmh(live.maxSpeedMps).toInt()}", "MAX", palette, m)
                SmallStat(formatHhMm(live.movingS), "MOVING", palette, m)
                if (live.speedLimitKmh > 0) {
                    SmallStat("${live.speedLimitKmh}", "LIMIT", palette, m)
                }
                live.batterySocPercent?.let {
                    BatteryMeter(
                        it, live.batteryCharging, palette, m,
                        onClick = { TripRecorderService.requestTelematicsRefresh() },
                        smartRangeKm = live.batteryRangeKm,
                        carRangeKm = live.mgBatteryRangeKm
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = m.gap),
                horizontalArrangement = Arrangement.spacedBy(m.gap)
            ) {
                live.tripEnergyKwh?.let {
                    SmallStat("%.2f kWh".format(it), "THIS RIDE", palette, m)
                }
                live.tripCostInr?.let {
                    SmallStat("₹%.2f".format(it), "THIS RIDE", palette, m)
                }
                live.batteryRangeAtFullKm?.let {
                    SmallStat("%.0f km".format(it), "RANGE@100", palette, m)
                }
                live.batteryMileageKmPerKwh?.let {
                    SmallStat("%.2f km/kWh".format(it), "MILEAGE", palette, m)
                }
                SmallStat("%.2f kWh".format(live.batteryTotalKwh), "LIFETIME", palette, m)
                if (live.elevGainM > 0 || live.elevLossM > 0) {
                    SmallStat(
                        "↑%.0f ↓%.0f".format(live.elevGainM, live.elevLossM), "CLIMB  M", palette, m
                    )
                }
            }
        }
    }
}

/**
 * The battery the drive screen now shows SOC against: a phone-style fill that animates to the
 * current charge level on every poll, turning green when there is enough charge, red when there
 * is little, and glowing the accent colour while a charger is plugged in. The percentage rides
 * beside the fill, and the little bolt marks the charging state. Sized from the same metrics as
 * everything else, so the split-screen band and the full panel get the same treatment.
 */
@Composable
private fun BatteryMeter(
    socPercent: Double,
    charging: Boolean?,
    palette: Palette,
    m: Metrics,
    size: TextUnit = m.stat,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    smartRangeKm: Double? = null,
    carRangeKm: Double? = null
) {
    val target = (socPercent / 100.0).coerceIn(0.0, 1.0).toFloat()
    val fill by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 900),
        label = "batteryFill"
    )
    val fillColor = when {
        charging == true -> palette.accent
        fill > 0.30f -> Color(0xFF2ECC71)
        else -> Color(0xFFE74C3C)
    }
    val click = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Column(
        modifier = modifier.then(click),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.gap / 3)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(m.gap * 4f)
                        .height(m.pad * 1.7f)
                        .border(1.5.dp, fillColor, RoundedCornerShape(2.dp))
                        .padding(1.dp)
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fill)
                            .background(fillColor)
                    )
                }
                Box(
                    Modifier
                        .width(3.dp)
                        .height(m.pad * 0.85f)
                        .background(fillColor.copy(alpha = 0.9f), RoundedCornerShape(1.dp))
                )
            }
            Text(
                text = if (charging == true) "⚡ ${socPercent.roundToInt()}%" else "${socPercent.roundToInt()}%",
                color = fillColor,
                fontSize = size,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
        Text(
            text = if (charging == true) "BATTERY CHARGING" else "BATTERY",
            color = palette.label,
            fontSize = m.label,
            letterSpacing = 1.1.sp,
            maxLines = 1
        )
        // Remaining range: the box's own efficiency when enough drives are measured, the car's
        // quoted figure otherwise, and the car's figure as a small cross-check when both agree
        // the box should be trusted.
        val mainKm = smartRangeKm ?: carRangeKm
        val crossKm = if (smartRangeKm != null && carRangeKm != null) carRangeKm else null
        if (mainKm != null) {
            Text(
                text = buildString {
                    append("≈ ").append(mainKm.roundToInt()).append(" km")
                    crossKm?.takeIf { it != smartRangeKm }?.let {
                        append("  ·  car ").append(it.roundToInt())
                    }
                },
                color = palette.dim,
                fontSize = m.label,
                letterSpacing = 0.4.sp,
                maxLines = 1
            )
        }
    }
}
