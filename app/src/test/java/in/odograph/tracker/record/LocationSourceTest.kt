package `in`.odograph.tracker.record

import android.location.Location
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocationSourceTest {

    @Test
    fun `a fix takes its timestamp from the location not the system clock`() {
        val l = Location("gps").apply {
            latitude = 12.97
            longitude = 77.59
            speed = 12.5f
            accuracy = 4f
            time = 1_700_000_000_000L
        }

        val fix = l.toFix()

        assertThat(fix.t).isEqualTo(1_700_000_000_000L)
        assertThat(fix.speedMps).isEqualTo(12.5f)
        assertThat(fix.accuracyM).isEqualTo(4f)
    }

    @Test
    fun `a location without speed reports zero rather than a guess`() {
        val l = Location("gps").apply { latitude = 12.97; longitude = 77.59; time = 1L }
        assertThat(l.toFix().speedMps).isEqualTo(0f)
    }

    @Test
    fun `a location without accuracy is treated as unusable rather than perfect`() {
        val l = Location("gps").apply { latitude = 12.97; longitude = 77.59; time = 1L }
        assertThat(l.toFix().accuracyM).isEqualTo(Float.MAX_VALUE)
    }
}
