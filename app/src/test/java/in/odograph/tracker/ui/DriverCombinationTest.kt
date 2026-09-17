package `in`.odograph.tracker.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import `in`.odograph.tracker.core.MgFixtures
import `in`.odograph.tracker.record.TripRecorderService.LiveState
import `in`.odograph.tracker.ui.theme.Direction
import `in`.odograph.tracker.ui.theme.paletteFor
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The whole combination space, walked rather than sampled.
 *
 * [DriverScenarioTest] renders the states the car actually produces, which is what catches a wrong
 * reading. This catches a wrong *shape*: the readings on the driving screen appear and vanish
 * independently, so the layouts it must survive are the product of those switches, not a list
 * somebody wrote down. Six optional stats alone are sixty-four different strips, and the failure
 * that shipped was exactly a combination nobody had opened.
 *
 * Runs in NATIVE graphics mode, and that is load-bearing. Robolectric's default mode stubs font
 * metrics so every glyph measures about a pixel: nine stats come back a tenth of their real width
 * and a row that visibly runs off a real screen passes. Only a real text measurement can tell
 * whether a strip fits, which is why this class is slower than the rest of the suite and worth it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriverCombinationTest {

    @get:Rule
    val compose = createComposeRule()

    private fun sweep(states: List<Pair<String, LiveState>>, where: String) {
        assertThat(states).`as`("the sweep must actually cover something").isNotEmpty
        val state = mutableStateOf(states.first().second)
        compose.setContent {
            DriverScreen(
                live = state.value,
                smoothedKmh = mpsToKmh(state.value.speedMps),
                direction = Direction.ION,
                palette = paletteFor(Direction.ION, night = true)
            )
        }
        states.forEach { (name, live) ->
            state.value = live
            compose.waitForIdle()
            val root = compose.onRoot().fetchSemanticsNode()
            val label = "$where / $name"
            LayoutAssertions.assertNothingOverflows(root, label)
            LayoutAssertions.assertNoTextIsSqueezedAway(root, label)
        }
    }

    /** All 2^6 subsets of the optional readings, at the widest values each can take. */
    private fun everyStrip(): List<Pair<String, LiveState>> =
        MgFixtures.everyOptionalCombination().map { present ->
            val name = if (present.isEmpty()) "no optional readings"
            else present.joinToString("+") { it.name }
            name to MgFixtures.combination(present)
        }

    /** Every charge state crossed with every combination of range estimates. */
    private fun everyChargeState(): List<Pair<String, LiveState>> =
        MgFixtures.SOC_BANDS.flatMap { soc ->
            MgFixtures.CHARGING_STATES.flatMap { charging ->
                MgFixtures.RANGE_SOURCES.map { (smart, car) ->
                    "soc=$soc charging=$charging smart=$smart car=$car" to
                        MgFixtures.combination(
                            present = MgFixtures.Optional.entries.toSet(),
                            soc = soc,
                            charging = charging,
                            smartRangeKm = smart,
                            carRangeKm = car
                        )
                }
            }
        }

    // ---------------------------------------------------------------- every strip, every window

    @Test
    @Config(qualifiers = "w427dp-h240dp-land")
    fun `every combination of optional readings fits the smallest viewport`() {
        sweep(everyStrip(), "extreme 427x240")
    }

    @Test
    @Config(qualifiers = "w533dp-h300dp-land")
    fun `every combination of optional readings fits a small head unit`() {
        sweep(everyStrip(), "800x480")
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `every combination of optional readings fits a common head unit`() {
        sweep(everyStrip(), "1280x720")
    }

    @Test
    @Config(qualifiers = "w1291dp-h181dp-land")
    fun `every combination of optional readings fits the quarter-height band`() {
        sweep(everyStrip(), "box quarter 1291x181")
    }

    @Test
    @Config(qualifiers = "w645dp-h726dp")
    fun `every combination of optional readings fits the vertical split`() {
        sweep(everyStrip(), "vertical 645x726")
    }

    @Test
    @Config(qualifiers = "w1291dp-h726dp-land")
    fun `every combination of optional readings fits the full box window`() {
        sweep(everyStrip(), "box full 1291x726")
    }

    // ------------------------------------------- every charge and range state, in the tight windows

    @Test
    @Config(qualifiers = "w427dp-h240dp-land")
    fun `every charge and range state fits the smallest viewport`() {
        sweep(everyChargeState(), "extreme 427x240")
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `every charge and range state fits a common head unit`() {
        sweep(everyChargeState(), "1280x720")
    }

    @Test
    @Config(qualifiers = "w1291dp-h181dp-land")
    fun `every charge and range state fits the quarter-height band`() {
        sweep(everyChargeState(), "box quarter 1291x181")
    }

    // ---------------------------------------------------------------- the sweep is what it claims

    /**
     * Guards the guard. A generator that quietly produced one state, or states that never differ,
     * would make every sweep above pass while testing nothing at all.
     */
    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `the sweeps cover the space they claim to`() {
        assertThat(MgFixtures.everyOptionalCombination())
            .`as`("2^6 subsets of the optional readings")
            .hasSize(64)
        assertThat(MgFixtures.everyOptionalCombination().toSet())
            .`as`("every subset distinct")
            .hasSize(64)
        assertThat(everyStrip().map { it.second }.toSet())
            .`as`("each subset must produce a different state")
            .hasSize(64)
        assertThat(everyChargeState())
            .`as`("SOC bands x charging states x range sources")
            .hasSize(MgFixtures.SOC_BANDS.size * MgFixtures.CHARGING_STATES.size * MgFixtures.RANGE_SOURCES.size)
    }
}
