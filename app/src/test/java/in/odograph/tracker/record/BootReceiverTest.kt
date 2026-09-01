package `in`.odograph.tracker.record

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `boot completed starts the recorder service`() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

        val started = shadowOf(app).nextStartedService
        assertThat(started).isNotNull()
        assertThat(started.component?.className)
            .isEqualTo(TripRecorderService::class.java.name)
    }

    @Test
    fun `the vendor quickboot broadcast also starts it`() {
        BootReceiver().onReceive(app, Intent("android.intent.action.QUICKBOOT_POWERON"))
        assertThat(shadowOf(app).nextStartedService).isNotNull()
    }

    @Test
    fun `an unrelated broadcast starts nothing`() {
        BootReceiver().onReceive(app, Intent("com.example.SOMETHING_ELSE"))
        assertThat(shadowOf(app).nextStartedService).isNull()
    }
}
