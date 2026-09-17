package `in`.odograph.tracker.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import `in`.odograph.tracker.core.MgFixtures
import `in`.odograph.tracker.ui.theme.Direction
import `in`.odograph.tracker.ui.theme.paletteFor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every state an MG frame can put the car in, rendered at every window the box can give us.
 *
 * The screen's optional readings are the problem. Range, charge, ride energy, cost, mileage, climb
 * and the odometer each appear or vanish on their own, so the number of distinct shapes the layout
 * has to survive is the product of those, not the sum — and a layout only ever breaks on the
 * combination nobody thought to open. Rather than guess at the combination, this drives the screen
 * with the states the car actually produces (see [MgFixtures]) across the viewports the split
 * screen actually produces, and asserts the same two things every time.
 *
 * One composition is reused across the scenarios rather than one per test. Handing the screen a
 * new state and letting it recompose is also a fairer test than building it fresh each time: it
 * exercises the transitions, which is where a remembered animation or a stale measurement shows
 * up, and it keeps a hundred-odd renders inside a few seconds.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriverScenarioTest {

    @get:Rule
    val compose = createComposeRule()

    private fun renderEveryScenario(night: Boolean, direction: Direction, where: String) {
        val scenarios = MgFixtures.SCENARIOS
        val state = mutableStateOf(scenarios.first().live)
        val palette = paletteFor(direction, night)

        compose.setContent {
            DriverScreen(
                live = state.value,
                smoothedKmh = mpsToKmh(state.value.speedMps),
                direction = direction,
                palette = palette
            )
        }

        scenarios.forEach { scenario ->
            state.value = scenario.live
            compose.waitForIdle()
            val root = compose.onRoot().fetchSemanticsNode()
            val label = "$where / ${scenario.name}"
            LayoutAssertions.assertNothingOverflows(root, label)
            LayoutAssertions.assertNoTextIsSqueezedAway(root, label)
        }
    }

    // ---------- the viewports the box's split-screen divider can produce ----------

    @Test
    @Config(qualifiers = "w1291dp-h726dp-land")
    fun `every scenario fits the full box window`() {
        renderEveryScenario(night = true, direction = Direction.ION, where = "box full 1291x726")
    }

    @Test
    @Config(qualifiers = "w1291dp-h363dp-land")
    fun `every scenario fits the half-height split`() {
        renderEveryScenario(night = true, direction = Direction.ION, where = "box half 1291x363")
    }

    @Test
    @Config(qualifiers = "w1291dp-h242dp-land")
    fun `every scenario fits the third-height split`() {
        renderEveryScenario(night = true, direction = Direction.CHRONO, where = "box third 1291x242")
    }

    @Test
    @Config(qualifiers = "w1291dp-h181dp-land")
    fun `every scenario fits the quarter-height band`() {
        renderEveryScenario(night = true, direction = Direction.ION, where = "box quarter 1291x181")
    }

    @Test
    @Config(qualifiers = "w645dp-h726dp")
    fun `every scenario fits the vertical split column`() {
        renderEveryScenario(night = true, direction = Direction.VECTOR, where = "vertical 645x726")
    }

    // ---------- other head units the projection might negotiate ----------

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `every scenario fits a common 1280x720 head unit`() {
        renderEveryScenario(night = true, direction = Direction.ION, where = "1280x720")
    }

    @Test
    @Config(qualifiers = "w960dp-h360dp-land")
    fun `every scenario fits a wide short head unit`() {
        renderEveryScenario(night = true, direction = Direction.CHRONO, where = "wide short 960x360")
    }

    @Test
    @Config(qualifiers = "w533dp-h300dp-land")
    fun `every scenario fits a small 800x480 head unit`() {
        renderEveryScenario(night = true, direction = Direction.ION, where = "800x480")
    }

    @Test
    @Config(qualifiers = "w427dp-h240dp-land")
    fun `every scenario survives the smallest viewport`() {
        renderEveryScenario(night = true, direction = Direction.VECTOR, where = "extreme 427x240")
    }

    // ---------- the day ground, and the face whose accent is red ----------

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `every scenario fits on the day palette`() {
        renderEveryScenario(night = false, direction = Direction.ION, where = "day 1280x720")
    }

    /**
     * AUDI is the face whose accent is needle red, which is what made a normally-charging battery
     * and an on-track odometer render in the danger colour. Worth its own pass over every state.
     */
    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `every scenario fits on the red-accented face`() {
        renderEveryScenario(night = false, direction = Direction.AUDI, where = "audi day 1280x720")
    }
}
