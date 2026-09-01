package `in`.odograph.tracker.ui.theme

import androidx.compose.ui.graphics.luminance
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class PaletteTest {

    @Test
    fun `night enables glow and day disables it`() {
        assertThat(paletteFor(Direction.ION, night = true).glow).isGreaterThan(0f)
        assertThat(paletteFor(Direction.ION, night = false).glow).isEqualTo(0f)
    }

    @Test
    fun `every direction has a distinct accent in both modes`() {
        val accents = Direction.entries.flatMap {
            listOf(paletteFor(it, true).accent, paletteFor(it, false).accent)
        }
        assertThat(accents.toSet()).hasSize(accents.size)
    }

    @Test
    fun `day ground is lighter than night ground`() {
        assertThat(paletteFor(Direction.ION, false).ground.luminance())
            .isGreaterThan(paletteFor(Direction.ION, true).ground.luminance())
    }

    @Test
    fun `numerals contrast against their own ground in both modes`() {
        Direction.entries.forEach { d ->
            listOf(true, false).forEach { night ->
                val p = paletteFor(d, night)
                val delta = kotlin.math.abs(p.numeral.luminance() - p.ground.luminance())
                assertThat(delta).isGreaterThan(0.5f)
            }
        }
    }

    @Test
    fun `auto theme treats evening and early morning as night`() {
        assertThat(isNight(ThemeMode.AUTO, 22)).isTrue()
        assertThat(isNight(ThemeMode.AUTO, 3)).isTrue()
        assertThat(isNight(ThemeMode.AUTO, 12)).isFalse()
    }

    @Test
    fun `explicit theme choices override the clock`() {
        assertThat(isNight(ThemeMode.DAY, 23)).isFalse()
        assertThat(isNight(ThemeMode.NIGHT, 12)).isTrue()
    }
}
