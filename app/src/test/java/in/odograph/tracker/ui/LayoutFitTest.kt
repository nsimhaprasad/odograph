package `in`.odograph.tracker.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
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
 */
@RunWith(RobolectricTestRunner::class)
class LayoutFitTest {

    @get:Rule
    val compose = createComposeRule()

    private val live = TripRecorderService.LiveState(
        hasFix = true, speedMps = 24.6f, distanceM = 18_432.0,
        elapsedS = 1_484, maxSpeedMps = 31.9f, movingS = 1_219, tripId = 1
    )

    private val route = List(60) { i ->
        (12.9716 + i * 0.0008) to (77.5946 + i * 0.0011)
    }

    private fun assertNothingOverflows() {
        val root = compose.onRoot().fetchSemanticsNode()
        val rootW = root.size.width.toFloat()
        val rootH = root.size.height.toFloat()
        val offenders = mutableListOf<String>()

        fun walk(node: SemanticsNode) {
            val left = node.positionInRoot.x
            val top = node.positionInRoot.y
            val right = left + node.size.width
            val bottom = top + node.size.height
            // One pixel of slack absorbs rounding in the layout pass.
            if (right > rootW + 1f || bottom > rootH + 1f || left < -1f || top < -1f) {
                offenders += "${node.config}: [$left,$top,$right,$bottom] outside ${rootW}x$rootH"
            }
            node.children.forEach { walk(it) }
        }
        walk(root)

        assertThat(offenders).`as`("nodes outside the viewport").isEmpty()
    }

    private fun renderDriver() {
        compose.setContent {
            DriverScreen(live, 88.6f, Direction.ION, paletteFor(Direction.ION, night = true))
        }
    }

    private fun renderDetail(direction: Direction) {
        compose.setContent {
            DetailScreen(
                live = live, smoothedKmh = 88.6f, route = route, slowestKmMps = 3.4,
                elevation = `in`.odograph.tracker.core.ElevationProfile(918.0, 214.0, 186.0, 890.0, 1004.0, 3.4),
                energy = `in`.odograph.tracker.core.EnergyEstimate(3.21, 0.74, 18_432.0, 214.0, 186.0),
                showEvMetrics = true, showTiles = false,
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

    @Test
    @Config(qualifiers = "w533dp-h300dp-land")
    fun `detailed view fits with no route recorded yet`() {
        compose.setContent {
            DetailScreen(
                live = TripRecorderService.LiveState(hasFix = false),
                smoothedKmh = 0f, route = emptyList(), slowestKmMps = 0.0,
                elevation = `in`.odograph.tracker.core.ElevationProfile(null, 0.0, 0.0, null, null, 0.0),
                energy = `in`.odograph.tracker.core.EnergyEstimate(0.0, 0.0, 0.0, 0.0, 0.0),
                showEvMetrics = true, showTiles = false,
                direction = Direction.ION, palette = paletteFor(Direction.ION, night = true)
            )
        }
        assertNothingOverflows()
    }
}
