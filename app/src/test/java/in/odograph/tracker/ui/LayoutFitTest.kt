package `in`.odograph.tracker.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import `in`.odograph.tracker.record.TripRecorderService
import `in`.odograph.tracker.ui.theme.Direction
import `in`.odograph.tracker.ui.theme.paletteFor
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layout regression guard across the viewports a projected CarPlay session might negotiate.
 *
 * The resolution the head unit gives us is not knowable in advance, so "it looks right on my
 * screen" is not a check. Instead every screen is laid out at each candidate viewport and every
 * node's unclipped bounds are asserted to sit inside the root. That is a mechanical definition of
 * "nothing overflows", it runs on the JVM in seconds, and it keeps holding as the UI changes.
 *
 * What it does NOT establish is that text fits horizontally. This class runs in Robolectric's
 * default graphics mode, where font metrics are stubbed and every glyph measures about one pixel:
 * "KWH LIFETIME" comes back 13px wide, so a row of stats that would visibly run off a real screen
 * measures a tenth of its true width here and passes. Vertical starvation is still caught, because
 * a line box comes from the specified sp rather than from glyph metrics — which is how the
 * original clipped stat row showed up, as text rendered at zero height.
 *
 * For genuine text fit see [DriverScenarioTest], which opts into NATIVE graphics and a real Skia
 * text measurement, and pays for it in runtime.
 */
@RunWith(RobolectricTestRunner::class)
class LayoutFitTest {

    @get:Rule
    val compose = createComposeRule()

    private val live = TripRecorderService.LiveState(
        hasFix = true, speedMps = 24.6f, distanceM = 18_432.0,
        elapsedS = 1_484, maxSpeedMps = 31.9f, movingS = 1_219, tripId = 1
    )

    /**
     * Every optional reading present at once.
     *
     * The sparse fixture above leaves battery, odometer, ride energy, cost and climb null, so the
     * driver cases composed roughly a third of the shipping screen and the rows that actually
     * overflowed were never built. This check was always able to catch that — a Row with no
     * weights places its overflowing children past the parent's edge, which is exactly what the
     * walker below looks for — but it never had the state to build them from.
     */
    private val liveFull = live.copy(
        batterySocPercent = 63.0,
        batteryCharging = false,
        telematicsConnected = true,
        batteryMileageKmPerKwh = 6.42,
        batteryRangeAtFullKm = 331.0,
        batteryRangeKm = 208.0,
        mgBatteryRangeKm = 214.0,
        batteryTotalKwh = 1_284.36,
        tripEnergyKwh = 2.87,
        tripCostInr = 23.40,
        elevGainM = 184.0,
        elevLossM = 142.0,
        odoKm = 20_431.0,
        odoDriftKm = 1.8,
        speedLimitKmh = 80
    )

    private val route = List(60) { i ->
        (12.9716 + i * 0.0008) to (77.5946 + i * 0.0011)
    }

    private fun assertNothingOverflows() {
        LayoutAssertions.assertNothingOverflows(compose.onRoot().fetchSemanticsNode(), "this viewport")
    }

    private fun assertNoTextIsSqueezedAway() {
        LayoutAssertions.assertNoTextIsSqueezedAway(compose.onRoot().fetchSemanticsNode(), "this viewport")
    }

    private fun renderDriver() {
        compose.setContent {
            DriverScreen(live, 88.6f, Direction.ION, paletteFor(Direction.ION, night = true))
        }
    }

    private fun renderFullDriver(night: Boolean = true) {
        compose.setContent {
            DriverScreen(liveFull, 88.6f, Direction.ION, paletteFor(Direction.ION, night))
        }
    }

    private fun renderDetail(direction: Direction) {
        compose.setContent {
            DetailScreen(
                live = live, smoothedKmh = 88.6f, route = route, slowestKmMps = 3.4,
                showTiles = false,
                direction = direction, palette = paletteFor(direction, night = true)
            )
        }
    }

    // ---------- driver view across candidate head-unit viewports ----------

    @Test
    @Config(qualifiers = "w960dp-h360dp-land")
    fun `driver view fits a wide short head unit`() {
        renderDriver(); assertNothingOverflows()
    }

    @Test
    @Config(qualifiers = "w800dp-h480dp-land")
    fun `driver view fits a large viewport`() {
        renderDriver(); assertNothingOverflows()
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `driver view fits a common 1280x720 viewport`() {
        renderDriver(); assertNothingOverflows()
    }

    @Test
    @Config(qualifiers = "w533dp-h300dp-land")
    fun `driver view fits a small 800x480 viewport`() {
        renderDriver(); assertNothingOverflows()
    }

    @Test
    @Config(qualifiers = "w427dp-h240dp-land")
    fun `driver view survives an extremely small viewport`() {
        renderDriver(); assertNothingOverflows()
    }

    // ---------- detailed view, and every cluster direction ----------

    @Test
    @Config(qualifiers = "w960dp-h360dp-land")
    fun `detailed view fits a wide short head unit`() {
        renderDetail(Direction.ION); assertNothingOverflows()
    }

    @Test
    @Config(qualifiers = "w533dp-h300dp-land")
    fun `detailed view fits a small viewport`() {
        renderDetail(Direction.VECTOR); assertNothingOverflows()
    }

    @Test
    @Config(qualifiers = "w427dp-h240dp-land")
    fun `detailed view survives an extremely small viewport`() {
        renderDetail(Direction.CHRONO); assertNothingOverflows()
    }

    // ---------- the state that is easy to forget ----------

    @Test
    @Config(qualifiers = "w533dp-h300dp-land")
    fun `driver view fits before the first fix`() {
        compose.setContent {
            DriverScreen(
                TripRecorderService.LiveState(hasFix = false),
                0f, Direction.ION, paletteFor(Direction.ION, night = true)
            )
        }
        assertNothingOverflows()
    }

    @Test
    @Config(qualifiers = "w533dp-h300dp-land")
    fun `driver view fits with implausibly large values`() {
        compose.setContent {
            DriverScreen(
                TripRecorderService.LiveState(
                    hasFix = true, speedMps = 55f, distanceM = 987_654.0,
                    elapsedS = 359_999, maxSpeedMps = 55f, movingS = 359_999
                ),
                198f, Direction.VECTOR, paletteFor(Direction.VECTOR, night = false)
            )
        }
        assertNothingOverflows()
    }

    @Test
    @Config(qualifiers = "w533dp-h300dp-land")
    fun `driver view fits while overspeeding with a limit badge shown`() {
        compose.setContent {
            DriverScreen(
                live.copy(overLimit = true, speedLimitKmh = 80),
                104f, Direction.ION, paletteFor(Direction.ION, night = true)
            )
        }
        assertNothingOverflows()
    }

    @Test
    @Config(qualifiers = "w427dp-h240dp-land")
    fun `driver view fits the limit badge on the smallest viewport`() {
        compose.setContent {
            DriverScreen(
                live.copy(overLimit = true, speedLimitKmh = 120),
                131f, Direction.VECTOR, paletteFor(Direction.VECTOR, night = false)
            )
        }
        assertNothingOverflows()
    }

    // ---------- the driver view with every optional reading present ----------

    @Test
    @Config(qualifiers = "w1291dp-h726dp-land")
    fun `fully populated driver view fits the full box window`() {
        renderFullDriver(); assertNothingOverflows(); assertNoTextIsSqueezedAway()
    }

    @Test
    @Config(qualifiers = "w960dp-h360dp-land")
    fun `fully populated driver view fits a wide short head unit`() {
        renderFullDriver(); assertNothingOverflows(); assertNoTextIsSqueezedAway()
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `fully populated driver view fits a common 1280x720 viewport`() {
        renderFullDriver(); assertNothingOverflows(); assertNoTextIsSqueezedAway()
    }

    @Test
    @Config(qualifiers = "w533dp-h300dp-land")
    fun `fully populated driver view fits a small 800x480 viewport`() {
        renderFullDriver(); assertNothingOverflows(); assertNoTextIsSqueezedAway()
    }

    @Test
    @Config(qualifiers = "w427dp-h240dp-land")
    fun `fully populated driver view survives an extremely small viewport`() {
        renderFullDriver(); assertNothingOverflows(); assertNoTextIsSqueezedAway()
    }

    @Test
    @Config(qualifiers = "w1291dp-h181dp-land")
    fun `fully populated driver view fits the quarter-height split band`() {
        renderFullDriver(); assertNothingOverflows(); assertNoTextIsSqueezedAway()
    }

    @Test
    @Config(qualifiers = "w645dp-h726dp")
    fun `fully populated driver view fits the vertical split column`() {
        renderFullDriver(); assertNothingOverflows(); assertNoTextIsSqueezedAway()
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `fully populated driver view fits on the day palette`() {
        renderFullDriver(night = false); assertNothingOverflows(); assertNoTextIsSqueezedAway()
    }

    @Test
    @Config(qualifiers = "w533dp-h300dp-land")
    fun `driver view fits while charging on a low battery`() {
        compose.setContent {
            DriverScreen(
                liveFull.copy(batterySocPercent = 8.0, batteryCharging = true),
                0f, Direction.AUDI, paletteFor(Direction.AUDI, night = true)
            )
        }
        assertNothingOverflows()
    }

    @Test
    @Config(qualifiers = "w533dp-h300dp-land")
    fun `driver view fits with only the car's range estimate`() {
        compose.setContent {
            DriverScreen(
                liveFull.copy(batteryRangeKm = null),
                88.6f, Direction.ION, paletteFor(Direction.ION, night = true)
            )
        }
        assertNothingOverflows()
    }

    @Test
    @Config(qualifiers = "w533dp-h300dp-land")
    fun `driver view fits with a battery reading but no range at all`() {
        compose.setContent {
            DriverScreen(
                liveFull.copy(batteryRangeKm = null, mgBatteryRangeKm = null),
                88.6f, Direction.ION, paletteFor(Direction.ION, night = true)
            )
        }
        assertNothingOverflows()
    }

    @Test
    @Config(qualifiers = "w533dp-h300dp-land")
    fun `detailed view fits with no route recorded yet`() {
        compose.setContent {
            DetailScreen(
                live = TripRecorderService.LiveState(hasFix = false),
                smoothedKmh = 0f, route = emptyList(), slowestKmMps = 0.0,
                showTiles = false,
                direction = Direction.ION, palette = paletteFor(Direction.ION, night = true)
            )
        }
        assertNothingOverflows()
    }
}
