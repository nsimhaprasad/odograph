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
    fun `the warning colour is distinct from every direction's own accent`() {
        Direction.entries.forEach { d ->
            listOf(true, false).forEach { night ->
                val p = paletteFor(d, night)
                assertThat(p.warn).isNotEqualTo(p.accent)
                assertThat(p.warn).isNotEqualTo(p.accent2)
            }
        }
    }

    @Test
    fun `the warning colour reads against its own ground`() {
        Direction.entries.forEach { d ->
            listOf(true, false).forEach { night ->
                val p = paletteFor(d, night)
                val delta = kotlin.math.abs(p.warn.luminance() - p.ground.luminance())
                assertThat(delta).`as`("warn vs ground for $d night=$night").isGreaterThan(0.05f)
            }
        }
    }

    /**
     * The drive screen colours a charge level and an odometer's health from these, so an accent
     * standing in for either is a bug waiting on the right instrument face: AUDI's accent is
     * needle red, which painted a normally-charging battery and an on-track odometer in the same
     * colour the screen uses for danger.
     */
    @Test
    fun `the state colours are distinct from every direction's own accent`() {
        Direction.entries.forEach { d ->
            listOf(true, false).forEach { night ->
                val p = paletteFor(d, night)
                assertThat(p.good).`as`("good vs accent for $d night=$night").isNotEqualTo(p.accent)
                assertThat(p.good).isNotEqualTo(p.accent2)
                assertThat(p.caution).isNotEqualTo(p.accent)
                assertThat(p.caution).isNotEqualTo(p.accent2)
            }
        }
    }

    @Test
    fun `the state colours mean the same thing on every instrument face`() {
        val goods = Direction.entries.map { paletteFor(it, night = true).good }
        val cautions = Direction.entries.map { paletteFor(it, night = true).caution }
        assertThat(goods.toSet()).`as`("good must not vary by direction").hasSize(1)
        assertThat(cautions.toSet()).`as`("caution must not vary by direction").hasSize(1)
    }

    @Test
    fun `the state colours are distinguishable from each other and from the warning`() {
        listOf(true, false).forEach { night ->
            val p = paletteFor(Direction.ION, night)
            assertThat(setOf(p.good, p.caution, p.warn))
                .`as`("healthy, low and danger must never collide (night=$night)")
                .hasSize(3)
        }
    }

    @Test
    fun `the state colours read against their own ground`() {
        Direction.entries.forEach { d ->
            listOf(true, false).forEach { night ->
                val p = paletteFor(d, night)
                listOf("good" to p.good, "caution" to p.caution).forEach { (name, c) ->
                    val delta = kotlin.math.abs(c.luminance() - p.ground.luminance())
                    assertThat(delta).`as`("$name vs ground for $d night=$night")
                        .isGreaterThan(0.05f)
                }
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
