package `in`.odograph.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.core.TripSegments
import `in`.odograph.tracker.ui.theme.Palette

// The efficiency scale is semantic, not part of the theme accent, and reads in both palettes:
// a drive's worst stretch is red, its best green, the rest orange between.
private val EFF_BAD = Color(0xFFE8625C)
private val EFF_MID = Color(0xFFE3A53B)
private val EFF_GOOD = Color(0xFF53C77C)
private val ON_BAR = Color(0xFF0A1410)

/** Where [k] falls between this drive's worst ([lo]) and best ([hi]) stretch, as a colour. */
fun efficiencyColor(k: Double, lo: Double, hi: Double): Color {
    val t = if (hi > lo) (((k - lo) / (hi - lo)).toFloat()).coerceIn(0f, 1f) else 1f
    return if (t <= 0.5f) lerp(EFF_BAD, EFF_MID, t / 0.5f) else lerp(EFF_MID, EFF_GOOD, (t - 0.5f) / 0.5f)
}

/**
 * The consumption-by-stretch block: a colour ribbon across the whole drive, then a row per
 * stretch. A stretch with no trustworthy figure (too short, or the counter barely ticked) shows
 * its distance and speed in muted grey and never sets the best, the worst, or the scale.
 */
@Composable
fun ConsumptionSection(breakdown: TripSegments.Breakdown, palette: Palette, m: Metrics) {
    val segs = breakdown.segments
    val measured = segs.mapNotNull { it.kmPerKwh }
    val lo = measured.minOrNull() ?: 0.0
    val hi = measured.maxOrNull() ?: 1.0
    val maxK = (measured.maxOrNull() ?: 1.0) * 1.05
    val totalKm = segs.lastOrNull()?.toKm ?: 0.0

    Column(verticalArrangement = Arrangement.spacedBy(m.gap / 2)) {
        // One plain line of what the stretches say, when there is a spread worth naming.
        if (breakdown.bestIndex >= 0 && breakdown.worstIndex >= 0 && breakdown.bestIndex != breakdown.worstIndex) {
            val b = segs[breakdown.bestIndex]; val w = segs[breakdown.worstIndex]
            Text(
                "Best %.1f km/kWh at %d km/h · thirstiest %.1f at %d km/h".format(
                    b.kmPerKwh, b.avgSpeedKmh.toInt(), w.kmPerKwh, w.avgSpeedKmh.toInt()
                ),
                color = palette.dim, fontSize = m.body,
                modifier = Modifier.padding(bottom = m.gap / 3)
            )
        }

        // The whole drive as one ribbon: each slice as wide as its distance, coloured by efficiency.
        Row(
            Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(5.dp)).background(palette.trackSoft)
        ) {
            segs.forEach { s ->
                val c = s.kmPerKwh?.let { efficiencyColor(it, lo, hi) } ?: palette.track
                Box(Modifier.weight(s.distanceKm.toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(c))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0 km", color = palette.dim, fontSize = m.label)
            Text("%.0f km".format(totalKm), color = palette.dim, fontSize = m.label)
        }

        segs.forEachIndexed { i, s ->
            SegmentRow(s, maxK, lo, hi, i == breakdown.bestIndex, i == breakdown.worstIndex, palette, m)
        }
    }
}

@Composable
private fun SegmentRow(
    s: TripSegments.Segment, maxK: Double, lo: Double, hi: Double,
    best: Boolean, worst: Boolean, palette: Palette, m: Metrics
) {
    Row(
        Modifier.fillMaxWidth().padding(top = m.gap / 3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.gap / 2)
    ) {
        Text(
            "%d–%d".format(s.fromKm.toInt(), s.toKm.toInt()),
            color = palette.dim, fontSize = m.label,
            modifier = Modifier.width(52.dp)
        )
        // Bar: length is the km/kWh (a withheld figure shows a muted full bar so the stretch is
        // still visible), colour is where it sits between this drive's worst and best.
        val frac = s.kmPerKwh?.let { (it / maxK).toFloat().coerceIn(0.08f, 1f) } ?: 1f
        val barColor = s.kmPerKwh?.let { efficiencyColor(it, lo, hi) } ?: palette.track
        Box(
            Modifier.weight(1f).height(22.dp).clip(RoundedCornerShape(6.dp)).background(palette.trackSoft)
        ) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(barColor))
        }
        Text(
            s.kmPerKwh?.let { "%.1f".format(it) } ?: "—",
            color = if (s.kmPerKwh != null) palette.numeral else palette.dim,
            fontSize = m.body, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(38.dp)
        )
        Text(
            "%d".format(s.avgSpeedKmh.toInt()),
            color = palette.dim, fontSize = m.label, modifier = Modifier.width(30.dp)
        )
        when {
            best -> Dot(EFF_GOOD, "BEST", m)
            worst -> Dot(EFF_BAD, "SLOW", m)
            else -> Box(Modifier.width(34.dp))
        }
    }
}

@Composable
private fun Dot(color: Color, label: String, m: Metrics) {
    Text(
        label, color = color, fontSize = m.label, fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
