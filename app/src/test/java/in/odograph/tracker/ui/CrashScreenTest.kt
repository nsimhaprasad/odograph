package `in`.odograph.tracker.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrashScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val report = "ODOGRAPH CRASH\nthread: main\n\n--- stack ---\njava.lang.IllegalStateException: x"

    private fun render(onDismiss: () -> Unit) {
        compose.setContent { CrashScreen(report, onDismiss) }
        compose.waitForIdle()
    }

    @Test
    fun `pressing the report text itself dismisses the crash screen`() {
        var dismissed = false
        render { dismissed = true }
        compose.onNodeWithText(report).performClick()
        compose.waitForIdle()
        assertTrue("tapping the report body must dismiss", dismissed)
    }

    @Test
    fun `tapping the title text with no clickable modifier of its own still reaches the screen tap`() {
        var dismissed = false
        render { dismissed = true }
        compose.onNodeWithText("PREVIOUS RUN CRASHED").performClick()
        compose.waitForIdle()
        assertTrue("tapping anywhere on the crash screen must dismiss", dismissed)
    }
}