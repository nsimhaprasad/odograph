package `in`.odograph.tracker.ui.theme

import androidx.compose.ui.graphics.Color

enum class Direction { ION, CHRONO, VECTOR, AUDI }

data class Palette(
    val ground: Color,
    val track: Color,
    val trackSoft: Color,
    val numeral: Color,
    val label: Color,
    val dim: Color,
    val accent: Color,
    val accent2: Color,
    /** Overspeed. Deliberately outside the direction's own palette so it cannot be mistaken. */
    val warn: Color,
    /** Blur radius multiplier. Zero on a light ground, where glow only muddies. */
    val glow: Float
)

private val NIGHT_GROUND = Color(0xFF050609)
private val NIGHT_TRACK = Color(0xFF1C232D)
private val NIGHT_TRACK_SOFT = Color(0xFF171A1F)
private val DAY_GROUND = Color(0xFFE7EBF0)
private val DAY_TRACK = Color(0xFFC6CDD7)
private val DAY_TRACK_SOFT = Color(0xFFD5DBE3)

fun paletteFor(direction: Direction, night: Boolean): Palette {
    val accents = when (direction) {
        Direction.ION ->
            if (night) Color(0xFF3DE1FF) to Color(0xFF7B5CFF)
            else Color(0xFF0089C7) to Color(0xFF5B3FD6)
        Direction.CHRONO ->
            if (night) Color(0xFFF5B547) to Color(0xFFB87333)
            else Color(0xFFB4761A) to Color(0xFF8A5416)
        Direction.VECTOR ->
            if (night) Color(0xFFFF5A1F) to Color(0xFF8A2E0C)
            else Color(0xFFDE3F06) to Color(0xFF7A2A08)
        // Needle red plus a cool machined silver for the bezel and graduations.
        Direction.AUDI ->
            if (night) Color(0xFFE8112D) to Color(0xFFC2C8D0)
            else Color(0xFFB3000F) to Color(0xFF5A616B)
    }
    return Palette(
        ground = if (night) NIGHT_GROUND else DAY_GROUND,
        track = if (night) NIGHT_TRACK else DAY_TRACK,
        trackSoft = if (night) NIGHT_TRACK_SOFT else DAY_TRACK_SOFT,
        numeral = if (night) Color(0xFFFFFFFF) else Color(0xFF0A0D12),
        label = if (night) Color(0xFF4E5866) else Color(0xFF7C8792),
        dim = if (night) Color(0xFF8B96A5) else Color(0xFF4A5462),
        accent = accents.first,
        accent2 = accents.second,
        warn = if (night) Color(0xFFFF4D4F) else Color(0xFFC81E14),
        glow = if (night) 1f else 0f
    )
}
