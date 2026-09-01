package `in`.odograph.tracker.ui.gauge

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import `in`.odograph.tracker.ui.theme.Direction
import `in`.odograph.tracker.ui.theme.Palette
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

const val GAUGE_MAX_KMH = 120f

/**
 * Every dimension is a fraction of the drawn size and nothing is a bitmap, because the resolution
 * CarPlay negotiates on the head unit is not known in advance. That is the only way to be "pixel
 * perfect" on an unknown canvas.
 */
@Composable
fun Gauge(
    speedKmh: Float,
    hasFix: Boolean,
    direction: Direction,
    palette: Palette,
    modifier: Modifier = Modifier,
    overLimit: Boolean = false
) {
    // Recolouring the whole instrument costs the driver no attention, which is why this channel
    // fires the instant the limit is crossed while the audible one waits out the sustain window.
    val p = if (overLimit) palette.copy(accent = palette.warn, accent2 = palette.warn) else palette
    Canvas(modifier) {
        val r = min(size.width, size.height) * 0.40f
        val c = Offset(size.width / 2f, size.height / 2f)
        val t = (speedKmh / GAUGE_MAX_KMH).coerceIn(0f, 1f)

        when (direction) {
            Direction.ION -> drawIon(c, r, t, p)
            Direction.CHRONO -> drawChrono(c, r, t, p)
            Direction.VECTOR -> drawVector(c, r, t, p)
        }
        drawReadout(c, r, speedKmh, hasFix, p, direction)
    }
}

/** Layered strokes fake a bloom. Cheaper and more reliable than a blur mask under HW accel. */
private fun DrawScope.glowArc(
    c: Offset, radius: Float, startDeg: Float, sweepDeg: Float,
    width: Float, color: Color, palette: Palette
) {
    if (palette.glow > 0f) {
        for (i in 3 downTo 1) {
            drawArc(
                color = color.copy(alpha = 0.10f * i * palette.glow),
                startAngle = startDeg, sweepAngle = sweepDeg, useCenter = false,
                topLeft = Offset(c.x - radius, c.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = width * (1f + i * 0.55f))
            )
        }
    }
    drawArc(
        color = color, startAngle = startDeg, sweepAngle = sweepDeg, useCenter = false,
        topLeft = Offset(c.x - radius, c.y - radius),
        size = Size(radius * 2, radius * 2), style = Stroke(width = width)
    )
}

/** Segmented ring that lights cell by cell, cyan bleeding to violet. */
private fun DrawScope.drawIon(c: Offset, r: Float, t: Float, palette: Palette) {
    val start = 140.0
    val sweep = 260.0
    val cells = 64
    for (i in 0 until cells) {
        val f = i / (cells - 1f)
        val a = Math.toRadians(start + sweep * f)
        val lit = f <= t
        val inner = r * 0.80f
        val outer = r * if (lit) 1.00f else 0.94f
        val color = when {
            !lit -> palette.track
            f / t.coerceAtLeast(0.001f) > 0.72f -> palette.accent2
            else -> palette.accent
        }
        val p1 = Offset(c.x + (cos(a) * inner).toFloat(), c.y + (sin(a) * inner).toFloat())
        val p2 = Offset(c.x + (cos(a) * outer).toFloat(), c.y + (sin(a) * outer).toFloat())
        if (lit && palette.glow > 0f) {
            drawLine(color.copy(alpha = 0.25f), p1, p2, strokeWidth = r * 0.13f)
        }
        drawLine(color, p1, p2, strokeWidth = r * if (lit) 0.052f else 0.030f)
    }
    val at = Math.toRadians(start + sweep * t)
    val tip = Offset(c.x + (cos(at) * r * 0.90).toFloat(), c.y + (sin(at) * r * 0.90).toFloat())
    if (palette.glow > 0f) drawCircle(palette.accent.copy(alpha = 0.30f), r * 0.14f, tip)
    drawCircle(if (palette.glow > 0f) Color.White else palette.accent2, r * 0.045f, tip)
}

/** Machined dial with brass ticks and a counterweighted needle. */
private fun DrawScope.drawChrono(c: Offset, r: Float, t: Float, palette: Palette) {
    val start = 144.0
    val sweep = 252.0
    drawCircle(palette.trackSoft, r * 1.02f, c)
    for (ring in 1..3) {
        drawCircle(
            color = palette.track.copy(alpha = 0.5f), radius = r * (0.62f + ring * 0.13f),
            center = c, style = Stroke(width = 1f)
        )
    }
    var kmh = 0
    while (kmh <= GAUGE_MAX_KMH.toInt()) {
        val f = kmh / GAUGE_MAX_KMH
        val a = Math.toRadians(start + sweep * f)
        val major = kmh % 20 == 0
        val ri = r * if (major) 0.74f else 0.84f
        drawLine(
            color = if (major) palette.accent else palette.dim,
            start = Offset(c.x + (cos(a) * ri).toFloat(), c.y + (sin(a) * ri).toFloat()),
            end = Offset(c.x + (cos(a) * r * 0.94f).toFloat(), c.y + (sin(a) * r * 0.94f).toFloat()),
            strokeWidth = if (major) r * 0.020f else r * 0.008f
        )
        kmh += 5
    }
    val at = Math.toRadians(start + sweep * t)
    val tipX = c.x + (cos(at) * r * 0.88f).toFloat()
    val tipY = c.y + (sin(at) * r * 0.88f).toFloat()
    val tailX = c.x - (cos(at) * r * 0.20f).toFloat()
    val tailY = c.y - (sin(at) * r * 0.20f).toFloat()
    if (palette.glow > 0f) {
        drawLine(palette.accent.copy(alpha = 0.25f), Offset(tailX, tailY), Offset(tipX, tipY), r * 0.10f)
    }
    drawLine(palette.accent, Offset(tailX, tailY), Offset(tipX, tipY), strokeWidth = r * 0.030f)
    drawCircle(palette.accent2, r * 0.055f, Offset(tailX, tailY))
    drawCircle(palette.accent, r * 0.075f, c)
    drawCircle(palette.ground, r * 0.032f, c)
}

/** One thick arc, no ornament. Braun rather than Blade Runner. */
private fun DrawScope.drawVector(c: Offset, r: Float, t: Float, palette: Palette) {
    val start = 170f
    val sweep = 200f
    drawArc(
        color = palette.trackSoft, startAngle = start, sweepAngle = sweep, useCenter = false,
        topLeft = Offset(c.x - r * 0.92f, c.y - r * 0.92f),
        size = Size(r * 1.84f, r * 1.84f), style = Stroke(width = r * 0.115f)
    )
    drawArc(
        color = palette.accent, startAngle = start, sweepAngle = sweep * t, useCenter = false,
        topLeft = Offset(c.x - r * 0.92f, c.y - r * 0.92f),
        size = Size(r * 1.84f, r * 1.84f), style = Stroke(width = r * 0.115f)
    )
    var kmh = 0
    while (kmh <= GAUGE_MAX_KMH.toInt()) {
        val a = Math.toRadians((start + sweep * (kmh / GAUGE_MAX_KMH)).toDouble())
        drawLine(
            color = palette.label,
            start = Offset(c.x + (cos(a) * r * 0.99f).toFloat(), c.y + (sin(a) * r * 0.99f).toFloat()),
            end = Offset(c.x + (cos(a) * r * 1.10f).toFloat(), c.y + (sin(a) * r * 1.10f).toFloat()),
            strokeWidth = r * 0.010f
        )
        kmh += 20
    }
}

private fun DrawScope.drawReadout(
    c: Offset, r: Float, speedKmh: Float, hasFix: Boolean,
    palette: Palette, direction: Direction
) {
    drawContext.canvas.nativeCanvas.apply {
        val numeral = android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            color = palette.numeral.toArgb()
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD
            )
            textSize = r * if (direction == Direction.CHRONO) 0.34f else 0.70f
        }
        val label = android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            color = palette.accent.toArgb()
            textSize = r * 0.115f
            letterSpacing = 0.28f
        }
        val yOffset = if (direction == Direction.CHRONO) r * 0.52f else r * 0.22f
        drawText(if (hasFix) speedKmh.toInt().toString() else "--", c.x, c.y + yOffset, numeral)
        // Never "0 km/h" before a fix: a zero is a measurement claim, and an instrument that
        // fakes confidence stops being believable.
        drawText(
            if (hasFix) "KM/H" else "ACQUIRING",
            c.x, c.y + yOffset + r * 0.26f, label
        )
    }
}
