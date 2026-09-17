package `in`.odograph.tracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * The sizing policy, as a pure function of the viewport.
 *
 * Kept free of Compose types on purpose: the interesting question — "does the content still fit
 * at 427x240?" — is arithmetic, and arithmetic can be tested directly and cheaply. Asserting it
 * through a rendered layout does not work, because Compose clips over-sized text rather than
 * letting it escape its parent, so an oversized font is structurally invisible to a bounds check.
 */
data class MetricSpec(
    val heroSp: Float,
    val statSp: Float,
    /**
     * Reference readings on the driving screen. A driver reads these at arm's length, in motion,
     * in one glance — so they get their own step between [statSp] and [labelSp] rather than being
     * rendered at label size, which is what made the range and ride figures unreadable at speed.
     */
    val readSp: Float,
    val bodySp: Float,
    val labelSp: Float,
    val chipTextSp: Float,
    val padDp: Float,
    val gapDp: Float,
    val chipPadHDp: Float,
    val chipPadVDp: Float,
    val compact: Boolean,
    val veryCompact: Boolean
) {
    /** Line box of a run of text at [sp], including leading. */
    private fun line(sp: Float) = sp * 1.25f

    /** Height the driver view's right-hand column needs to render without truncation. */
    val driverColumnHeightDp: Float
        get() = line(heroSp) + line(labelSp) +
            gapDp + line(heroSp) + line(labelSp) +
            gapDp + line(labelSp)

    /** Height the detailed view's bottom stat strip needs. */
    val statStripHeightDp: Float
        get() = line(statSp) + line(labelSp)

    /** Height of the top navigation bar. */
    val topBarHeightDp: Float
        get() = line(chipTextSp) + chipPadVDp * 2 + gapDp
}

fun metricsFor(widthDp: Float, heightDp: Float): MetricSpec {
    val h = heightDp
    val w = widthDp

    fun scaled(fraction: Float, min: Float, max: Float) = (h * fraction).coerceIn(min, max)

    return MetricSpec(
        heroSp = scaled(0.135f, 20f, 74f),
        statSp = scaled(0.068f, 13f, 34f),
        readSp = scaled(0.046f, 15f, 24f),
        bodySp = scaled(0.036f, 10f, 16f),
        labelSp = scaled(0.026f, 8f, 12f),
        chipTextSp = scaled(0.030f, 9f, 13f),
        padDp = scaled(0.030f, 6f, 22f),
        gapDp = scaled(0.026f, 5f, 20f),
        chipPadHDp = scaled(0.040f, 9f, 22f),
        chipPadVDp = scaled(0.030f, 15f, 20f),
        compact = h < 420f || w < 700f,
        veryCompact = h < 330f || w < 560f
    )
}

/** Compose-facing view of [MetricSpec]. */
data class Metrics(
    val spec: MetricSpec,
    val hero: TextUnit,
    val stat: TextUnit,
    val read: TextUnit,
    val body: TextUnit,
    val label: TextUnit,
    val chipText: TextUnit,
    val pad: Dp,
    val gap: Dp,
    val chipPadH: Dp,
    val chipPadV: Dp
) {
    val compact: Boolean get() = spec.compact
    val veryCompact: Boolean get() = spec.veryCompact
}

@Composable
fun rememberMetrics(maxWidth: Dp, maxHeight: Dp): Metrics {
    val density = LocalDensity.current
    return remember(maxWidth, maxHeight, density) {
        val s = metricsFor(maxWidth.value, maxHeight.value)
        with(density) {
            Metrics(
                spec = s,
                hero = s.heroSp.dp.toSp(),
                stat = s.statSp.dp.toSp(),
                read = s.readSp.dp.toSp(),
                body = s.bodySp.dp.toSp(),
                label = s.labelSp.dp.toSp(),
                chipText = s.chipTextSp.dp.toSp(),
                pad = s.padDp.dp,
                gap = s.gapDp.dp,
                chipPadH = s.chipPadHDp.dp,
                chipPadV = s.chipPadVDp.dp
            )
        }
    }
}
