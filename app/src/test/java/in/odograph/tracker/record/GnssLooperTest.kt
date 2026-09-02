package `in`.odograph.tracker.record

import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The recorder starts location updates from a coroutine on Dispatchers.IO, which is a plain
 * worker thread with no Looper. The four-argument requestLocationUpdates delivers callbacks on
 * the calling thread's Looper and throws when there isn't one, so the listener must be registered
 * against an explicit Looper instead.
 */
@RunWith(RobolectricTestRunner::class)
class GnssLooperTest {

    @Test
    fun `starting location updates from a thread with no looper does not throw`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = GnssLocationSource(ctx)

        var failure: Throwable? = null
        val done = CountDownLatch(1)

        // Deliberately a bare thread: no Looper, exactly like Dispatchers.IO.
        Thread {
            try {
                source.start { }
            } catch (t: Throwable) {
                failure = t
            } finally {
                done.countDown()
            }
        }.start()

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(failure)
            .`as`("registering location updates off a Looper thread must not throw")
            .isNull()
    }

    @Test
    fun `it subscribes to the network provider as well, so a fix is possible indoors`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE)
            as android.location.LocationManager
        val shadow = org.robolectric.Shadows.shadowOf(lm)
        shadow.setProviderEnabled(android.location.LocationManager.GPS_PROVIDER, true)
        shadow.setProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER, true)

        GnssLocationSource(ctx).start { }

        assertThat(shadow.getRequestLocationUpdateListeners())
            .`as`("one listener per enabled provider")
            .hasSizeGreaterThanOrEqualTo(2)
    }

    @Test
    fun `a disabled provider is skipped rather than throwing`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE)
            as android.location.LocationManager
        val shadow = org.robolectric.Shadows.shadowOf(lm)
        shadow.setProviderEnabled(android.location.LocationManager.GPS_PROVIDER, false)
        shadow.setProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER, true)

        GnssLocationSource(ctx).start { }

        assertThat(shadow.getRequestLocationUpdateListeners()).hasSize(1)
    }

    @Test
    fun `stopping removes every listener it registered`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE)
            as android.location.LocationManager
        val shadow = org.robolectric.Shadows.shadowOf(lm)
        shadow.setProviderEnabled(android.location.LocationManager.GPS_PROVIDER, true)
        shadow.setProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER, true)

        val source = GnssLocationSource(ctx)
        source.start { }
        source.stop()

        assertThat(shadow.getRequestLocationUpdateListeners()).isEmpty()
    }
}
