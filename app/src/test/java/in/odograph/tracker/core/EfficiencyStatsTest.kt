package `in`.odograph.tracker.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * What the car costs under particular conditions.
 *
 * A single figure for the whole history answers "what does this car cost" and nothing after that.
 * These split the same drives by when, how and how hot, which is what lets a range estimate say
 * something about the drive actually being made.
 */
class EfficiencyStatsTest {

    private val ist = TimeZone.getTimeZone("Asia/Kolkata")

    private fun at(day: Int, hour: Int): Long = Calendar.getInstance(ist).apply {
        set(2026, Calendar.SEPTEMBER, day, hour, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun drive(
        day: Int, hour: Int, km: Double, kwhPer100: Double,
        movingS: Long = 1_800, tempC: Double? = 28.0
    ) = EfficiencyStats.Sample(
        startedAt = at(day, hour),
        distanceM = km * 1_000.0,
        movingS = movingS,
        energyKwh = kwhPer100 * km / 100.0,
        tempC = tempC
    )

    // ---------------------------------------------------------------- day against night

    /** The question asked: does the car cost more in the afternoon heat than at midnight? */
    @Test
    fun `day and night are reported separately`() {
        val hotAfternoons = (1..5).map { drive(it, 14, 20.0, 19.0, tempC = 38.0) }
        val coolNights = (1..5).map { drive(it, 23, 20.0, 14.0, tempC = 24.0) }

        val split = EfficiencyStats.byTimeOfDay(hotAfternoons + coolNights, ist)

        assertThat(split[DriveContext.TimeOfDay.DAY]!!.kwhPer100Km).isEqualTo(19.0, within(0.5))
        assertThat(split[DriveContext.TimeOfDay.NIGHT]!!.kwhPer100Km).isEqualTo(14.0, within(0.5))
        assertThat(split[DriveContext.TimeOfDay.DAY]!!.kwhPer100Km)
            .`as`("the afternoon costs more")
            .isGreaterThan(split[DriveContext.TimeOfDay.NIGHT]!!.kwhPer100Km)
    }

    @Test
    fun `a bucket with too few drives says nothing rather than something unreliable`() {
        val lonely = listOf(drive(1, 23, 20.0, 14.0))
        assertThat(EfficiencyStats.byTimeOfDay(lonely, ist)[DriveContext.TimeOfDay.NIGHT]).isNull()
    }

    // ---------------------------------------------------------------- crawl against run

    @Test
    fun `city and highway driving are reported separately`() {
        // 20 km in an hour of movement is 20 km/h: a crawl.
        val crawls = (1..4).map { drive(it, 10, 20.0, 21.0, movingS = 3_600) }
        // 80 km in an hour is 80 km/h: sustained running.
        val runs = (1..4).map { drive(it, 10, 80.0, 15.0, movingS = 3_600) }

        val split = EfficiencyStats.byCharacter(crawls + runs)

        assertThat(split[DriveContext.Character.CITY]!!.kwhPer100Km).isEqualTo(21.0, within(0.5))
        assertThat(split[DriveContext.Character.HIGHWAY]!!.kwhPer100Km).isEqualTo(15.0, within(0.5))
    }

    @Test
    fun `range follows the character of the drive`() {
        val crawls = (1..4).map { drive(it, 10, 20.0, 21.0, movingS = 3_600) }
        val runs = (1..4).map { drive(it, 10, 80.0, 15.0, movingS = 3_600) }
        val split = EfficiencyStats.byCharacter(crawls + runs)

        val cityRange = split[DriveContext.Character.CITY]!!.rangeAtFullKm(52.9)
        val highwayRange = split[DriveContext.Character.HIGHWAY]!!.rangeAtFullKm(52.9)

        assertThat(highwayRange).`as`("the thriftier drive goes further").isGreaterThan(cityRange)
        assertThat(cityRange).isEqualTo(252.0, within(5.0))
    }

    // ---------------------------------------------------------------- heat

    @Test
    fun `temperature bands are reported separately`() {
        val mild = (1..4).map { drive(it, 9, 20.0, 14.0, tempC = 24.0) }
        val hot = (1..4).map { drive(it, 15, 20.0, 20.0, tempC = 40.0) }

        val split = EfficiencyStats.byTemperature(mild + hot)

        assertThat(split[DriveContext.TempBand.MILD]!!.kwhPer100Km).isEqualTo(14.0, within(0.5))
        assertThat(split[DriveContext.TempBand.HOT]!!.kwhPer100Km).isEqualTo(20.0, within(0.5))
    }

    /** Drives from before the temperature was kept have none and must not form a band. */
    @Test
    fun `drives with no recorded temperature form no band`() {
        val unknown = (1..6).map { drive(it, 9, 20.0, 16.0, tempC = null) }
        assertThat(EfficiencyStats.byTemperature(unknown)).isEmpty()
    }

    // ---------------------------------------------------------------- picking a figure to use

    /**
     * Specific where there is evidence, general where there is not. A precise answer from two
     * drives is worse than a vague one from fifty.
     */
    @Test
    fun `the most specific bucket with enough behind it is used`() {
        val crawls = (1..6).map { drive(it, 10, 20.0, 21.0, movingS = 3_600) }
        val runs = (1..6).map { drive(it, 10, 80.0, 15.0, movingS = 3_600) }

        val forHighway = EfficiencyStats.expectedKwhPer100Km(
            crawls + runs, DriveContext.Character.HIGHWAY, DriveContext.TimeOfDay.DAY, ist
        )
        assertThat(forHighway).isEqualTo(15.0, within(0.5))
    }

    @Test
    fun `it falls back when the specific bucket is too thin`() {
        val mostly = (1..8).map { drive(it, 10, 30.0, 17.0, movingS = 3_600) }  // mixed, 30 km/h
        val oneRun = listOf(drive(9, 10, 80.0, 15.0, movingS = 3_600))

        val forHighway = EfficiencyStats.expectedKwhPer100Km(
            mostly + oneRun, DriveContext.Character.HIGHWAY, DriveContext.TimeOfDay.DAY, ist
        )
        assertThat(forHighway)
            .`as`("one highway drive is not a highway figure, so the general one stands")
            .isEqualTo(17.0, within(1.0))
    }

    @Test
    fun `no history at all yields nothing rather than a guess`() {
        assertThat(EfficiencyStats.expectedKwhPer100Km(emptyList(), null, null, ist)).isNull()
        assertThat(EfficiencyStats.bucket(emptyList())).isNull()
    }

    /**
     * Efficiency has a long tail one way — a drive can cost far more than usual for a hundred
     * reasons and can never cost much less than physics allows — so the mean is dragged up by
     * exactly the drives that were unrepresentative.
     */
    @Test
    fun `one dreadful drive does not re-price the bucket`() {
        val normal = (1..9).map { drive(it, 9, 20.0, 16.0) }
        val dreadful = listOf(drive(10, 9, 20.0, 60.0))
        assertThat(EfficiencyStats.bucket(normal + dreadful)!!.kwhPer100Km)
            .isEqualTo(16.0, within(1.0))
    }

    @Test
    fun `drives too short to measure are excluded`() {
        val tiny = (1..8).map { drive(it, 9, 0.5, 16.0) }
        assertThat(EfficiencyStats.bucket(tiny)).isNull()
    }
}
