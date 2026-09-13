package `in`.odograph.tracker.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.data.PlaceEntity
import `in`.odograph.tracker.ui.theme.Direction
import `in`.odograph.tracker.ui.theme.paletteFor
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards an invariant that broke once: the headline totals and the routes beneath them are two
 * reads of the same rows, so they must never disagree on screen.
 *
 * An earlier version of this test only checked that a route row existed, and passed happily while
 * the headline read zero. Asserting the actual numbers is what makes it able to fail.
 */
@RunWith(RobolectricTestRunner::class)
class RoutesScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val drives = 7

    @Before
    fun clean() {
        OdographDb.resetForTests(ctx)
    }

    private fun seed() {
        val t = Thread {
            val dao = OdographDb.get(ctx).dao()
            val home = dao.insertPlace(PlaceEntity(lat = 12.9716, lon = 77.5946, label = "Home"))
            val office = dao.insertPlace(PlaceEntity(lat = 12.9698, lon = 77.7500, label = "Office"))
            repeat(drives) { n ->
                val startedAt = System.currentTimeMillis() - (n + 1) * 60_000L
                val id = dao.startTrip(startedAt)
                dao.finishTrip(id, startedAt + 600_000, 10_000.0, 600, 500, 25f, 20.0, 3.0, 0.0, 0.0)
                dao.setTripPlaces(id, home, office)
            }
        }
        t.start(); t.join()
    }

    private fun render() {
        compose.setContent { RoutesScreen(paletteFor(Direction.ION, night = true)) }
        repeat(40) { compose.waitForIdle(); Thread.sleep(25) }
    }

    @Test
    fun `the headline distance is not zero when drives exist`() {
        seed(); render()
        // 7 drives x 10 km. Zero here is the exact bug this test exists to catch.
        compose.onNodeWithText("70.0").assertExists()
    }

    @Test
    fun `the headline elapsed time is not zero when drives exist`() {
        seed(); render()
        // 7 x 600 s = 4200 s
        compose.onNodeWithText("01:10").assertExists()
    }

    @Test
    fun `the route is listed with its place names`() {
        seed(); render()
        compose.onNodeWithText("Home  →  Office").assertExists()
    }

    @Test
    fun `an empty period says so rather than showing a blank screen`() {
        render()
        compose.onNodeWithText("No drives in this period.").assertExists()
    }
}
