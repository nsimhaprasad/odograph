package `in`.odograph.tracker.core

import java.util.Calendar
import java.util.TimeZone

/**
 * The conditions a drive happened in, which is most of why two identical routes cost different
 * energy.
 *
 * The rolling figure averages the last ten drives whatever they were: a midnight run down an empty
 * ring road and a 2 p.m. crawl across town with the air conditioning fighting 38 °C go into the
 * same mean and come out as one number that describes neither. Splitting them is what lets the
 * range estimate say something specific about the drive actually being made.
 */
object DriveContext {

    /**
     * Night, by the clock rather than by the sun.
     *
     * Sunrise and sunset would be more correct and need a latitude, a date and an almanac to be
     * wrong about at the edges. What is actually being separated here is traffic and heat, and
     * those follow the working day closely enough that the hour is the better predictor: the roads
     * are empty and the air is cool from ten at night until six in the morning, whatever the sun
     * is doing.
     */
    const val NIGHT_FROM_HOUR = 22
    const val NIGHT_TO_HOUR = 6

    enum class TimeOfDay { DAY, NIGHT }

    /**
     * How the drive was actually driven.
     *
     * By average moving speed, not by distance. Distance is the obvious proxy and the wrong one: a
     * sixty-kilometre crawl through traffic is not a highway run, and a ten-kilometre stretch of
     * empty ring road is not city driving. What changes the energy is the speed held and the
     * stopping done, and average moving speed measures both at once — it excludes the time spent
     * stationary, so a drive that crawls reads slow rather than merely short.
     */
    enum class Character { CITY, MIXED, HIGHWAY }

    /**
     * Below this average moving speed a drive is stop-start, km/h.
     *
     * Lower than it looks, because the figure excludes time spent stationary: a crawl through
     * dense traffic still reaches thirty between the halts, and it is the halts rather than the
     * speed between them that the driver experiences as a crawl.
     */
    const val CITY_MAX_KMH = 30.0

    /**
     * At or above this a drive is sustained running, km/h.
     *
     * Forty-five, and deliberately below the speed a driver would name.
     *
     * Average moving speed is not the reading on the dial. It excludes time stationary but still
     * carries every deceleration, junction and slow stretch, so a drive held at a steady fifty to
     * sixty averages in the high forties. A threshold set at the dial reading files most long
     * drives as mixed and leaves the bucket that matters almost empty — which is exactly what
     * fifty did, and what a test written from the driver's own description of a long drive caught.
     */
    const val HIGHWAY_MIN_KMH = 45.0

    /**
     * Temperature bands, chosen for what they do to the pack rather than for round numbers.
     *
     * Below [MILD_MAX_C] is the comfortable middle where neither heater nor air conditioning does
     * much. Above it the cooling load climbs steeply and keeps climbing, which is why the hot band
     * is split rather than left open: in this climate most of the year sits in it, and lumping
     * 30 °C with 42 °C would hide the difference that matters most.
     */
    const val MILD_MAX_C = 28.0
    const val WARM_MAX_C = 35.0

    enum class TempBand { COOL, MILD, WARM, HOT }

    /**
     * Above this share of a drive, the climate control was meaningfully on.
     *
     * A quarter, not a half. The question is not "was the cabin being cooled the whole way" but
     * "did this drive pay for cooling at all", and a compressor running for a quarter of a journey
     * has already cost most of what one running throughout would — the heavy pull is bringing a
     * parked car down from forty degrees, which happens at the start and then tapers.
     */
    const val CLIMATE_ON_SHARE = 0.25

    enum class Climate { OFF, ON }

    /**
     * Whether a drive paid for climate control, from the share of its frames that reported it.
     *
     * Null when nothing was recorded either way, which is not the same as off and must never be
     * folded into it: on this box most drives happen with the telematics link down, and treating
     * every unobserved drive as air-conditioning-free would fill the OFF bucket with drives that
     * were nothing of the sort.
     */
    fun climate(share: Double?): Climate? = when {
        share == null -> null
        share >= CLIMATE_ON_SHARE -> Climate.ON
        else -> Climate.OFF
    }

    /** Everything about a drive that is not the route itself. */
    data class Context(
        val timeOfDay: TimeOfDay,
        val character: Character,
        val tempBand: TempBand?
    )

    fun timeOfDay(startedAt: Long, zone: TimeZone): TimeOfDay {
        val hour = Calendar.getInstance(zone).apply { timeInMillis = startedAt }
            .get(Calendar.HOUR_OF_DAY)
        return if (hour >= NIGHT_FROM_HOUR || hour < NIGHT_TO_HOUR) TimeOfDay.NIGHT else TimeOfDay.DAY
    }

    /**
     * Classifies a drive from the speed it actually held while moving.
     *
     * Returns null when there is too little movement to say anything — a drive that never really
     * got going has no character, and guessing one would put a fictional classification into the
     * averages everything else is learned from.
     */
    fun character(distanceM: Double, movingS: Long): Character? {
        if (movingS <= 0L || distanceM <= 0.0) return null
        val kmh = distanceM / movingS * 3.6
        if (!kmh.isFinite() || kmh <= 0.0) return null
        return when {
            kmh < CITY_MAX_KMH -> Character.CITY
            kmh < HIGHWAY_MIN_KMH -> Character.MIXED
            else -> Character.HIGHWAY
        }
    }

    fun tempBand(celsius: Double?): TempBand? = when {
        celsius == null -> null
        celsius < 20.0 -> TempBand.COOL
        celsius < MILD_MAX_C -> TempBand.MILD
        celsius < WARM_MAX_C -> TempBand.WARM
        else -> TempBand.HOT
    }

    fun of(startedAt: Long, zone: TimeZone, distanceM: Double, movingS: Long, tempC: Double?): Context =
        Context(
            timeOfDay = timeOfDay(startedAt, zone),
            character = character(distanceM, movingS) ?: Character.MIXED,
            tempBand = tempBand(tempC)
        )
}
