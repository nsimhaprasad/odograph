package `in`.odograph.tracker.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Splitting drives by the conditions that actually change what they cost. */
class DriveContextTest {

    private val ist = TimeZone.getTimeZone("Asia/Kolkata")

    private fun at(hour: Int): Long = Calendar.getInstance(ist).apply {
        set(2026, Calendar.SEPTEMBER, 18, hour, 30, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ---------------------------------------------------------------- day and night

    @Test
    fun `the working day is day`() {
        listOf(7, 9, 13, 17, 21).forEach {
            assertThat(DriveContext.timeOfDay(at(it), ist))
                .`as`("$it:30").isEqualTo(DriveContext.TimeOfDay.DAY)
        }
    }

    @Test
    fun `the small hours are night`() {
        listOf(22, 23, 0, 3, 5).forEach {
            assertThat(DriveContext.timeOfDay(at(it), ist))
                .`as`("$it:30").isEqualTo(DriveContext.TimeOfDay.NIGHT)
        }
    }

    @Test
    fun `the boundaries fall where the roads and the heat change`() {
        assertThat(DriveContext.timeOfDay(at(DriveContext.NIGHT_FROM_HOUR), ist))
            .isEqualTo(DriveContext.TimeOfDay.NIGHT)
        assertThat(DriveContext.timeOfDay(at(DriveContext.NIGHT_TO_HOUR), ist))
            .isEqualTo(DriveContext.TimeOfDay.DAY)
    }

    @Test
    fun `the zone decides, not the device`() {
        val utc = TimeZone.getTimeZone("UTC")
        val t = at(23)
        assertThat(DriveContext.timeOfDay(t, ist)).isEqualTo(DriveContext.TimeOfDay.NIGHT)
        // 23:30 IST is 18:00 UTC, which is still daytime there.
        assertThat(DriveContext.timeOfDay(t, utc)).isEqualTo(DriveContext.TimeOfDay.DAY)
    }

    // ---------------------------------------------------------------- how it was driven

    /** The case distance gets wrong: long, and still a crawl. */
    @Test
    fun `sixty kilometres of traffic is city driving, not a highway run`() {
        // 60 km taking three hours of movement is 20 km/h.
        assertThat(DriveContext.character(60_000.0, movingS = 10_800))
            .isEqualTo(DriveContext.Character.CITY)
    }

    /** And its mirror: short, and still sustained. */
    @Test
    fun `ten kilometres of open road is not city driving`() {
        // 10 km in six minutes of movement is 100 km/h.
        assertThat(DriveContext.character(10_000.0, movingS = 360))
            .isEqualTo(DriveContext.Character.HIGHWAY)
    }

    @Test
    fun `a mixed drive lands in the middle`() {
        // 45 km/h.
        assertThat(DriveContext.character(45_000.0, movingS = 3_600))
            .isEqualTo(DriveContext.Character.MIXED)
    }

    @Test
    fun `a drive that never got going has no character`() {
        assertThat(DriveContext.character(0.0, 100)).isNull()
        assertThat(DriveContext.character(1_000.0, 0)).isNull()
        assertThat(DriveContext.character(-5.0, 10)).isNull()
    }

    // ---------------------------------------------------------------- how hot it was

    @Test
    fun `temperature bands separate the comfortable middle from the cooling load`() {
        assertThat(DriveContext.tempBand(16.0)).isEqualTo(DriveContext.TempBand.COOL)
        assertThat(DriveContext.tempBand(25.0)).isEqualTo(DriveContext.TempBand.MILD)
        assertThat(DriveContext.tempBand(32.0)).isEqualTo(DriveContext.TempBand.WARM)
        assertThat(DriveContext.tempBand(41.0)).isEqualTo(DriveContext.TempBand.HOT)
    }

    /** Drives recorded before the temperature was kept have none, and must not be given one. */
    @Test
    fun `an unknown temperature stays unknown`() {
        assertThat(DriveContext.tempBand(null)).isNull()
    }

    @Test
    fun `the hot band is split off rather than left open`() {
        assertThat(DriveContext.WARM_MAX_C).isGreaterThan(DriveContext.MILD_MAX_C)
        assertThat(DriveContext.tempBand(DriveContext.WARM_MAX_C))
            .isEqualTo(DriveContext.TempBand.HOT)
    }
}
