package `in`.odograph.tracker.core

import kotlin.math.max

/**
 * Vehicle parameters. Defaults describe an MG Windsor EV Pro with one occupant.
 */
data class Vehicle(
    val massKg: Double = 1650.0,
    /** Drag coefficient times frontal area, in square metres. */
    val cdA: Double = 0.74,
    val rollingResistance: Double = 0.011,
    /** Battery to wheels. */
    val driveEfficiency: Double = 0.88,
    /** Wheels back to battery under regenerative braking. */
    val regenEfficiency: Double = 0.60,
    /** Climate control, lights, electronics. */
    val auxiliaryWatts: Double = 600.0,
    val usableBatteryKwh: Double = 52.9
)

data class EnergyEstimate(
    val usedKwh: Double,
    val regeneratedKwh: Double,
    val distanceM: Double,
    val elevationGainM: Double,
    val elevationLossM: Double
) {
    val netKwh: Double get() = max(0.0, usedKwh - regeneratedKwh)
    val kmPerKwh: Double get() = if (netKwh > 0) (distanceM / 1000.0) / netKwh else 0.0
    val whPerKm: Double get() = if (distanceM > 0) netKwh * 1000.0 / (distanceM / 1000.0) else 0.0
    /** Range at this trip's efficiency, on a full usable pack. */
    fun projectedRangeKm(vehicle: Vehicle): Double = kmPerKwh * vehicle.usableBatteryKwh
}

/**
 * Estimates traction energy from the physics, with no vehicle bus access.
 *
 * Everything the road demands of a car is three forces: rolling resistance, aerodynamic drag and
 * gravity. Their inputs are mass, speed, distance and altitude change, all of which GNSS already
 * provides, so a useful figure is obtainable long before OBD is.
 *
 * It is an estimate and named as one. It cannot see headwind, cabin heating load, tyre pressure
 * or battery temperature. What it does capture is the term that dominates an EV's consumption on
 * an interesting route: elevation, and how much of a climb comes back on the way down.
 */
object EnergyModel {

    private const val GRAVITY = 9.80665
    private const val AIR_DENSITY = 1.20
    private const val JOULES_PER_KWH = 3_600_000.0

    fun estimate(
        fixes: List<Fix>,
        vehicle: Vehicle = Vehicle(),
        accuracyLimitM: Float = 25f
    ): EnergyEstimate {
        val usable = fixes.filter { it.accuracyM <= accuracyLimitM }.sortedBy { it.t }
        if (usable.size < 2) return EnergyEstimate(0.0, 0.0, 0.0, 0.0, 0.0)

        val elevation = Elevation.profile(usable)
        var tractionJoules = 0.0
        var regenJoules = 0.0
        var distance = 0.0

        for (i in 1 until usable.size) {
            val a = usable[i - 1]
            val b = usable[i]
            val segment = Geo.haversineMetres(a.lat, a.lon, b.lat, b.lon)
            if (segment <= 0.0) continue
            distance += segment

            val speed = ((a.speedMps + b.speedMps) / 2.0).coerceAtLeast(0.0)
            val rolling = vehicle.massKg * GRAVITY * vehicle.rollingResistance
            val drag = 0.5 * AIR_DENSITY * vehicle.cdA * speed * speed
            tractionJoules += (rolling + drag) * segment

            // Kinetic energy changes: accelerating costs, decelerating gives some back.
            val kinetic = 0.5 * vehicle.massKg *
                (b.speedMps * b.speedMps - a.speedMps * a.speedMps).toDouble()
            if (kinetic > 0) tractionJoules += kinetic else regenJoules += -kinetic
        }

        // Elevation is handled from the smoothed profile rather than per sample, because raw GNSS
        // altitude noise would otherwise dominate the gravity term completely.
        tractionJoules += vehicle.massKg * GRAVITY * elevation.gainM
        regenJoules += vehicle.massKg * GRAVITY * elevation.lossM

        val seconds = (usable.last().t - usable.first().t) / 1000.0
        val auxiliaryJoules = vehicle.auxiliaryWatts * max(0.0, seconds)

        val usedKwh = (tractionJoules / vehicle.driveEfficiency + auxiliaryJoules) / JOULES_PER_KWH
        val regenKwh = (regenJoules * vehicle.regenEfficiency) / JOULES_PER_KWH

        return EnergyEstimate(
            usedKwh = usedKwh,
            regeneratedKwh = regenKwh,
            distanceM = distance,
            elevationGainM = elevation.gainM,
            elevationLossM = elevation.lossM
        )
    }
}
