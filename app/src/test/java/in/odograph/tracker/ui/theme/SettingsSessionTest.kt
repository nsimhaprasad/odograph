package `in`.odograph.tracker.ui.theme

import androidx.test.core.app.ApplicationProvider
import `in`.odograph.tracker.record.VehicleLink
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The saved MG session, and why it is bound to the credentials it was issued for.
 *
 * Reusing a token across restarts is what stops the box signing in on every drive. But a token is
 * only valid for the account it was issued to, so it must not survive a change of phone, password,
 * VIN or protocol — otherwise a new account would ride in on an old session and fail confusingly.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsSessionTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var s: Settings

    @Before
    fun setUp() {
        s = Settings(ctx)
        s.telematicsPhone = "9876543210"
        s.telematicsPassword = "secret"
        s.telematicsVin = "VIN1"
        s.telematicsApi = "tap"
        s.telematicsSession = null
    }

    @After
    fun tearDown() { s.telematicsSession = null }

    @Test
    fun `a saved session round-trips`() {
        s.telematicsSession = VehicleLink.SavedSession("uid-1", "token-1")

        assertThat(Settings(ctx).telematicsSession)
            .isEqualTo(VehicleLink.SavedSession("uid-1", "token-1"))
    }

    @Test
    fun `changing the password invalidates the saved session`() {
        s.telematicsSession = VehicleLink.SavedSession("uid-1", "token-1")

        s.telematicsPassword = "different"

        assertThat(s.telematicsSession).`as`("a token from another password is not reused").isNull()
    }

    @Test
    fun `changing the phone or protocol invalidates it too`() {
        s.telematicsSession = VehicleLink.SavedSession("uid-1", "token-1")
        s.telematicsPhone = "9999999999"
        assertThat(s.telematicsSession).isNull()

        s.telematicsPhone = "9876543210"
        s.telematicsSession = VehicleLink.SavedSession("uid-2", "token-2")
        s.telematicsApi = "iov2"
        assertThat(s.telematicsSession).isNull()
    }

    @Test
    fun `null clears it`() {
        s.telematicsSession = VehicleLink.SavedSession("u", "t")
        s.telematicsSession = null

        assertThat(s.telematicsSession).isNull()
    }
}
