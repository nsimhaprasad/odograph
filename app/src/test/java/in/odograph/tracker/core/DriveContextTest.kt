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
        // 38 km/h: faster than a crawl, short of sustained running.
        assertThat(DriveContext.character(38_000.0, movingS = 3_600))
            .isEqualTo(DriveContext.Character.MIXED)
    }

    /**
     * The driver's own description of a long drive: "held at fifty to sixty, consistently". The
     * threshold has to meet that, and the trap is that it is a dial reading rather than an
     * average. Average moving speed still carries every deceleration and junction, so a drive
     * cruising at fifty to sixty averages in the high forties — which a threshold set at the dial
     * would file as mixed, leaving the bucket that matters almost empty.
     */
    @Test
    fun `a drive held at fifty to sixty is a long drive, not a mixed one`() {
        // Cruising at 55 with the usual slowing: 48 km/h average moving speed.
        assertThat(DriveContext.character(48_000.0, movingS = 3_600))
            .isEqualTo(DriveContext.Character.HIGHWAY)
        // And squarely at 55 average.
        assertThat(DriveContext.character(55_000.0, movingS = 3_600))
            .isEqualTo(DriveContext.Character.HIGHWAY)
    }

    /** Stop-and-go still reaches thirty between the halts; it is the halts that make it a crawl. */
    @Test
    fun `stop and go traffic is a city drive`() {
        assertThat(DriveContext.character(22_000.0, movingS = 3_600))
            .isEqualTo(DriveContext.Character.CITY)
    }

    @Test
    fun `the thresholds match how the driving is described`() {
        assertThat(DriveContext.CITY_MAX_KMH).isEqualTo(30.0)
        assertThat(DriveContext.HIGHWAY_MIN_KMH).isEqualTo(45.0)
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

    // ------------------------------------------- the air conditioning

    /**
     * A share rather than a flag, because the compressor is not on or off across half an hour: it
     * cycles, it is switched off once the cabin settles, it comes back at a traffic light.
     */
    @Test
    fun `a drive that ran the climate for a quarter of itself counts as cooled`() {
        assertThat(DriveContext.climate(0.25)).isEqualTo(DriveContext.Climate.ON)
        assertThat(DriveContext.climate(0.9)).isEqualTo(DriveContext.Climate.ON)
    }

    /**
     * A quarter, not a half: the question is whether the drive paid for cooling at all, and the
     * heavy pull is bringing a parked car down from forty degrees, which happens at the start and
     * then tapers.
     */
    @Test
    fun `a brief burst of cooling is not a cooled drive`() {
        assertThat(DriveContext.climate(0.05)).isEqualTo(DriveContext.Climate.OFF)
        assertThat(DriveContext.climate(0.0)).isEqualTo(DriveContext.Climate.OFF)
    }

    /**
     * Never observed is not the same as off, and folding the two together would be quietly
     * ruinous: on this box most drives happen with the telematics link down, so the OFF bucket
     * would fill with drives that were nothing of the sort and the comparison would be with
     * itself.
     */
    @Test
    fun `a drive nobody observed has no climate answer`() {
        assertThat(DriveContext.climate(null)).isNull()
    }
}
