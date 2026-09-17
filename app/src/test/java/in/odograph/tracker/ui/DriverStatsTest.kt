package `in`.odograph.tracker.ui

import `in`.odograph.tracker.record.TripRecorderService.LiveState
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * The driving screen's judgements, asserted directly.
 *
 * These used to live inside composables, where the only way to check them was to render the screen
 * and look at the pixels — which is how a range readout that quoted the same number twice, and a
 * stat row that ran off the right edge, both shipped. As plain functions they are just arithmetic.
 */
class DriverStatsTest {

    private val live = LiveState(
        hasFix = true, speedMps = 24.6f, distanceM = 18_432.0,
        elapsedS = 1_484, maxSpeedMps = 31.9f, movingS = 1_219, tripId = 1
    )

    // ---------------------------------------------------------------- charge level

    @Test
    fun `a healthy charge reads healthy`() {
        assertThat(socLevel(63.0)).isEqualTo(SocLevel.HEALTHY)
    }

    @Test
    fun `a charge at or below the low mark needs planning around`() {
        assertThat(socLevel(SOC_LOW_PERCENT)).isEqualTo(SocLevel.LOW)
        assertThat(socLevel(22.0)).isEqualTo(SocLevel.LOW)
    }

    @Test
    fun `a charge at or below the critical mark is critical`() {
        assertThat(socLevel(SOC_CRITICAL_PERCENT)).isEqualTo(SocLevel.CRITICAL)
        assertThat(socLevel(4.0)).isEqualTo(SocLevel.CRITICAL)
    }

    @Test
    fun `an empty battery is critical rather than healthy`() {
        assertThat(socLevel(0.0)).isEqualTo(SocLevel.CRITICAL)
    }

    /**
     * The regression that made a charging AUDI cluster light up alarm red: charging used to be a
     * level of its own, coloured with the instrument accent, and that face's accent is needle red.
     * Level now answers only "how much charge", so a plugged-in car at 22% still reads LOW.
     */
    @Test
    fun `charging does not change how much charge there is`() {
        assertThat(socLevel(22.0)).isEqualTo(SocLevel.LOW)
        assertThat(socLevel(63.0)).isEqualTo(SocLevel.HEALTHY)
    }

    // ---------------------------------------------------------------- range readout

    @Test
    fun `nothing to show when neither estimate exists`() {
        assertThat(rangeReadout(null, null)).isNull()
    }

    @Test
    fun `the box's own estimate wins when it has one`() {
        val r = rangeReadout(208.0, 214.0)!!
        assertThat(r.km).isEqualTo(208.0)
        assertThat(r.fromCar).isFalse()
    }

    @Test
    fun `the car's estimate stands in until the box has measured enough`() {
        val r = rangeReadout(null, 214.0)!!
        assertThat(r.km).isEqualTo(214.0)
        assertThat(r.fromCar).isTrue()
        assertThat(r.crossCheckKm).`as`("nothing to cross-check against").isNull()
    }

    @Test
    fun `the car's figure rides along when the two genuinely disagree`() {
        assertThat(rangeReadout(208.0, 214.0)!!.crossCheckKm).isEqualTo(214.0)
    }

    /**
     * The old screen compared the two with `!=` on raw doubles, so estimates that round to the
     * same displayed integer still counted as a disagreement and rendered "208 km · car 208",
     * which reads as an instrument fault rather than a cross-check.
     */
    @Test
    fun `a disagreement under a kilometre is not worth printing twice`() {
        assertThat(rangeReadout(208.0, 208.4)!!.crossCheckKm).isNull()
        assertThat(rangeReadout(208.0, 208.0)!!.crossCheckKm).isNull()
    }

    @Test
    fun `a disagreement of exactly the threshold is worth printing`() {
        assertThat(rangeReadout(208.0, 209.0)!!.crossCheckKm).isEqualTo(209.0)
    }

    @Test
    fun `the car reading lower than the box still counts as a disagreement`() {
        assertThat(rangeReadout(214.0, 208.0)!!.crossCheckKm).isEqualTo(208.0)
    }

    // ---------------------------------------------------------------- captions

    @Test
    fun `the range caption names the car when the figure came from it`() {
        assertThat(rangeCaption(rangeReadout(null, 214.0)!!)).contains("CAR")
    }

    @Test
    fun `the range caption carries the cross-check figure`() {
        assertThat(rangeCaption(rangeReadout(208.0, 214.0)!!)).isEqualTo("KM RANGE  ·  CAR 214")
    }

    @Test
    fun `the odometer value stays a bare number so it cannot be clipped by its own drift mark`() {
        assertThat(odoValue(20_431.0)).isEqualTo("20431")
    }

    @Test
    fun `the odometer caption carries the drift once it is worth noticing`() {
        assertThat(odoCaption(1.8)).isEqualTo("KM   ODO  ·  +1.8")
        assertThat(odoCaption(-2.4)).isEqualTo("KM   ODO  ·  -2.4")
    }

    @Test
    fun `drift under the show threshold is left off the caption entirely`() {
        assertThat(odoCaption(0.4)).isEqualTo("KM   ODO")
        assertThat(odoCaption(0.0)).isEqualTo("KM   ODO")
        assertThat(odoCaption(null)).isEqualTo("KM   ODO")
    }

    // ---------------------------------------------------------------- which stats appear

    @Test
    fun `a bare trip still reports the readings that always exist`() {
        val labels = secondaryStats(live).map { it.label }
        assertThat(labels).containsExactly("KM/H MAX", "MOVING", "KWH LIFETIME")
    }

    @Test
    fun `the speed limit leads when one is known`() {
        assertThat(secondaryStats(live.copy(speedLimitKmh = 80)).first().label)
            .isEqualTo("KM/H LIMIT")
    }

    @Test
    fun `an unknown speed limit is omitted rather than shown as zero`() {
        assertThat(secondaryStats(live.copy(speedLimitKmh = 0)).map { it.label })
            .doesNotContain("KM/H LIMIT")
    }

    @Test
    fun `optional readings appear only once they are known`() {
        val full = live.copy(
            tripEnergyKwh = 2.87, tripCostInr = 23.4, batteryMileageKmPerKwh = 6.42,
            batteryRangeAtFullKm = 331.0, elevGainM = 184.0, elevLossM = 142.0
        )
        assertThat(secondaryStats(full).map { it.label })
            .contains("KWH USED", "RIDE COST", "KM/KWH", "KM AT FULL", "CLIMB M")
        assertThat(secondaryStats(live).map { it.label })
            .doesNotContain("KWH USED", "RIDE COST", "KM/KWH", "KM AT FULL", "CLIMB M")
    }

    /**
     * The two energy readings used to share the caption "THIS RIDE", so the screen showed the same
     * label over two different numbers and neither could be identified at a glance.
     */
    @Test
    fun `no two stats share a caption`() {
        val labels = secondaryStats(
            live.copy(
                speedLimitKmh = 80, tripEnergyKwh = 2.87, tripCostInr = 23.4,
                batteryMileageKmPerKwh = 6.42, batteryRangeAtFullKm = 331.0,
                elevGainM = 184.0, elevLossM = 142.0
            )
        ).map { it.label }
        assertThat(labels.toSet()).`as`("captions must be unique").hasSize(labels.size)
    }

    @Test
    fun `a flat drive reports no climb`() {
        assertThat(secondaryStats(live.copy(elevGainM = 0.0, elevLossM = 0.0)).map { it.label })
            .doesNotContain("CLIMB M")
    }

    @Test
    fun `descent alone still counts as terrain worth reporting`() {
        assertThat(secondaryStats(live.copy(elevGainM = 0.0, elevLossM = 96.0)).map { it.label })
            .contains("CLIMB M")
    }

    // ---------------------------------------------------------------- how many fit

    /** width x height in dp, spanning the viewports the head unit or split screen can hand us. */
    private val viewports = listOf(
        "extreme small" to (427f to 240f),
        "small 800x480" to (533f to 300f),
        "common 1280x720" to (640f to 360f),
        "wide short" to (960f to 360f),
        "box full" to (1291f to 726f),
        "box split quarter" to (1291f to 181f),
        "box split vertical" to (645f to 726f)
    )

    @Test
    fun `every viewport can carry at least one reference stat`() {
        viewports.forEach { (name, size) ->
            val (w, h) = size
            assertThat(secondaryCapacity(metricsFor(w, h), w))
                .`as`("capacity at $name")
                .isGreaterThan(0)
        }
    }

    @Test
    fun `no viewport is asked to carry more stats than exist`() {
        val most = secondaryStats(
            live.copy(
                speedLimitKmh = 80, tripEnergyKwh = 2.87, tripCostInr = 23.4,
                batteryMileageKmPerKwh = 6.42, batteryRangeAtFullKm = 331.0,
                elevGainM = 184.0, elevLossM = 142.0
            )
        ).size
        viewports.forEach { (name, size) ->
            val (w, h) = size
            assertThat(secondaryCapacity(metricsFor(w, h), w))
                .`as`("capacity at $name should stay within the $most stats that exist")
                .isLessThanOrEqualTo(most)
        }
    }

    @Test
    fun `a roomier panel carries at least as many stats as a cramped one`() {
        val cramped = secondaryCapacity(metricsFor(427f, 240f), 427f)
        val roomy = secondaryCapacity(metricsFor(1291f, 726f), 1291f)
        assertThat(roomy).isGreaterThanOrEqualTo(cramped)
    }

    @Test
    fun `a narrower strip carries fewer stats per row`() {
        val spec = metricsFor(640f, 360f)
        assertThat(secondaryPerRow(spec, 640f)).isGreaterThan(secondaryPerRow(spec, 320f))
    }

    /**
     * A row width of zero would make the wrap throw rather than simply render nothing, and a
     * vanishing column is exactly what a split-screen divider can produce.
     */
    @Test
    fun `a vanishing strip still asks for at least one stat per row`() {
        assertThat(secondaryPerRow(metricsFor(427f, 240f), 0f)).isGreaterThanOrEqualTo(1)
        assertThat(secondaryPerRow(metricsFor(427f, 240f), 1f)).isGreaterThanOrEqualTo(1)
    }
}
