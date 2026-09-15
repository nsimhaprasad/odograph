package `in`.odograph.tracker.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.ui.theme.Direction
import `in`.odograph.tracker.ui.theme.paletteFor
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlacesScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clean() { OdographDb.resetForTests(ctx) }

    private fun render() {
        compose.setContent { PlacesScreen(palette = paletteFor(Direction.ION, night = true)) }
        repeat(40) { compose.waitForIdle(); Thread.sleep(25) }
    }

    @Test
    fun `places screen renders the search input and an empty places list`() {
        render()
        compose.onNodeWithText("NAME A PLACE  ·  SEARCH, PIN, SAVE").assertExists()
        compose.onNodeWithText("KNOWN PLACES").assertExists()
    }

    @Test
    fun `toggling the PLACES chip in the routes screen shows the places screen`() {
        compose.setContent { RoutesScreen(palette = paletteFor(Direction.ION, night = true)) }
        repeat(40) { compose.waitForIdle(); Thread.sleep(25) }
        compose.onNodeWithText("PLACES").performClick()
        repeat(40) { compose.waitForIdle(); Thread.sleep(25) }
        compose.onNodeWithText("NAME A PLACE  ·  SEARCH, PIN, SAVE").assertExists()
    }
}