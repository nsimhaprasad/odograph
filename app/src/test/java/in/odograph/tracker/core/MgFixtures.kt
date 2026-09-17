package `in`.odograph.tracker.core

import `in`.odograph.tracker.record.TripRecorderService.LiveState
import io.windsor.telematics.ChargeStatus
import io.windsor.telematics.Status

/**
 * MG Windsor telematics frames, shaped the way the car really sends them.
 *
 * The values are anchored to the frames the telematics library keeps as goldens (`CHARGING_70`,
 * `CHARGING_74`, `CHARGING_11A`, `CHARGING_17A_SOC45`, `IDLE_100` in TapV21Test): a slow charger
 * pushing 382 V at 4.2 A, a fast one at 17.1 A, ranges of 223 km at 70% and 320 km at 100%, an
 * odometer in the 23,000s. Invented numbers would test against a car that does not exist — 8 km of
 * range at 95%, a five-digit power figure — and would pass while the real thing broke.
 *
 * Those goldens are NOT from this project's car. Their fixture set carries a `device_id` beginning
 * `haos-mg-ismart-india`, so they are Home Assistant community captures from some other Windsor —
 * which matters, because that car's frames imply a ~37 kWh pack while this one is the 52.9 kWh
 * Pro. Use them for frame *shape*, scales and field presence. Never infer this car's capacity from
 * them; [Telematics.impliedCapacityKwh] explains why.
 *
 * Nothing here calls MG. The frames are constructed locally, which keeps the suite offline, makes
 * it deterministic, and avoids hammering an account that can be rate-limited or blocked.
 */
object MgFixtures {

    /**
     * What the *captured* frames imply their pack holds, kWh: batteryEnergyKwh / soc × 100.
     *
     * A property of the community-captured car, not of this one. Kept because every golden agrees
     * on it, which is what makes it useful as a decode check.
     */
    const val CAPTURED_PACK_KWH = 37.3

    /** The odometer the real captured frames carry. */
    const val ODO_KM = 23_344.5

    /**
     * A charging frame with the car's real defaults. Only what a case cares about is overridden,
     * so a scenario reads as its own difference from a working car rather than as thirty fields.
     */
    fun charge(
        soc: Double? = 70.0,
        isCharging: Boolean = false,
        isPluggedIn: Boolean = false,
        rangeKm: Double = 223.0,
        chargingVoltage: Double = 382.0,
        chargingCurrent: Double = 0.0,
        batteryEnergyKwh: Double = 26.0,
        odometerKm: Double = ODO_KM,
        chargingType: Int = 2,
        chargeTimeRemainingMin: Int? = null,
        distanceSinceLastChargeKm: Double? = 138.5,
        powerUsageSinceLastChargeKwh: Double? = 8.8
    ) = ChargeStatus(
        isCharging = isCharging,
        isPluggedIn = isPluggedIn,
        chargingType = chargingType,
        soc = soc,
        rangeKm = rangeKm,
        chargingVoltage = chargingVoltage,
        chargingCurrent = chargingCurrent,
        batteryEnergyKwh = batteryEnergyKwh,
        workingVoltage = chargingVoltage,
        workingCurrent = chargingCurrent,
        chargeTimeRemainingMin = chargeTimeRemainingMin,
        odometerKm = odometerKm,
        distanceSinceLastChargeKm = distanceSinceLastChargeKm,
        powerUsageSinceLastChargeKwh = powerUsageSinceLastChargeKwh,
        statusTime = 1_786_729_051
    )

    /** A status frame, optionally carrying a charging block. */
    fun status(
        odometerKm: Double? = ODO_KM,
        rangeKm: Double? = 223.0,
        charge: ChargeStatus? = charge()
    ) = Status(
        statusTime = 1_786_729_051,
        locked = true,
        rangeKm = rangeKm,
        odometerKm = odometerKm,
        charge = charge
    )

    // ------------------------------------------------------------------ the real captured frames

    /** Slow AC at home: 382 V × 4.2 A = 1.6 kW. */
    val CHARGING_70 = charge(
        soc = 70.0, isCharging = true, isPluggedIn = true, rangeKm = 223.0,
        chargingVoltage = 382.0, chargingCurrent = 4.2, batteryEnergyKwh = 26.0,
        chargeTimeRemainingMin = 276
    )

    /** The same session four points later. */
    val CHARGING_74 = charge(
        soc = 74.0, isCharging = true, isPluggedIn = true, rangeKm = 233.0,
        chargingVoltage = 382.25, chargingCurrent = 4.1, batteryEnergyKwh = 27.3
    )

    /** A wallbox at 11 A: about 4.1 kW. */
    val CHARGING_11A = charge(
        soc = 80.0, isCharging = true, isPluggedIn = true, rangeKm = 256.0,
        chargingVoltage = 382.0, chargingCurrent = 10.8, batteryEnergyKwh = 29.8
    )

    /** The fast case, and the one the double-scaling bug used to mis-class as SLOW. */
    val CHARGING_17A_SOC45 = charge(
        soc = 45.0, isCharging = true, isPluggedIn = true, rangeKm = 144.0,
        chargingVoltage = 382.0, chargingCurrent = 17.1, batteryEnergyKwh = 16.6
    )

    /** Full, still plugged in, drawing nothing — charging has finished but the cable is in. */
    val IDLE_100 = charge(
        soc = 100.0, isCharging = false, isPluggedIn = true, rangeKm = 320.0,
        chargingVoltage = 395.5, chargingCurrent = 0.0, batteryEnergyKwh = 37.3,
        distanceSinceLastChargeKm = 0.0, powerUsageSinceLastChargeKwh = 0.0
    )

    // ------------------------------------------------------------------ the awkward frames

    /** A charging block with no SOC. Not charge data — noise that must not reach the battery rows. */
    val NO_SOC = charge(soc = null)

    /** Flat. A zero SOC is a reading, not a missing one, and every extrapolation must survive it. */
    val EMPTY = charge(soc = 0.0, rangeKm = 0.0, batteryEnergyKwh = 0.0)

    /** Nearly flat, unplugged: the state the screen has to shout about. */
    val CRITICAL = charge(soc = 6.0, rangeKm = 19.0, batteryEnergyKwh = 2.2)

    /** Low enough to plan the next charge around. */
    val LOW = charge(soc = 22.0, rangeKm = 70.0, batteryEnergyKwh = 8.2)

    /** An ordinary drive. */
    val HEALTHY = charge(soc = 63.0, rangeKm = 201.0, batteryEnergyKwh = 23.5)

    // ------------------------------------------------------------------ the full combination space

    /**
     * The readings that appear and vanish independently of each other.
     *
     * Each is present or absent on its own — energy needs a usable SOC swing, cost needs a priced
     * fill behind it, mileage and range-at-full need enough instrumented drives, climb needs
     * terrain, a limit needs a road that has one. Six independent switches is sixty-four different
     * strips, which is why hand-listing "the important states" cannot cover this: the one that
     * breaks is the one nobody thought to open.
     */
    enum class Optional { ENERGY, COST, MILEAGE, RANGE_AT_FULL, CLIMB, LIMIT }

    /** Every subset of [Optional] — the full 2^6 presence space. */
    fun everyOptionalCombination(): List<Set<Optional>> {
        val all = Optional.entries
        return (0 until (1 shl all.size)).map { mask ->
            all.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }.toSet()
        }
    }

    /** The SOC bands the screen colours differently, plus "no reading at all". */
    val SOC_BANDS: List<Double?> = listOf(null, 0.0, 6.0, 22.0, 30.0, 63.0, 100.0)

    /** Whether a charger is attached, unknown, or known not to be. */
    val CHARGING_STATES: List<Boolean?> = listOf(null, true, false)

    /**
     * Which range estimates exist: neither, only the car's, only the box's, both agreeing, and
     * both disagreeing. These drive every branch in [Telematics] and in the range readout.
     */
    val RANGE_SOURCES: List<Pair<Double?, Double?>> = listOf(
        null to null,
        null to 201.0,
        195.0 to null,
        201.0 to 201.4,
        195.0 to 223.0
    )

    /**
     * A state built from one point of the combination space, at the widest values the screen has
     * to render.
     *
     * Worst-case on purpose: a six-figure odometer, a five-figure cost, four-digit climb figures.
     * If the widest strings fit then every narrower one does, so the sweep does not also have to
     * multiply by value length. Proving 64 layouts with realistic values would leave the layout
     * one long trip away from breaking.
     */
    fun combination(
        present: Set<Optional>,
        soc: Double? = 63.0,
        charging: Boolean? = false,
        smartRangeKm: Double? = 195.0,
        carRangeKm: Double? = 223.0,
        odoDriftKm: Double? = 1.8,
        wide: Boolean = true
    ): LiveState = driving.copy(
        batterySocPercent = soc,
        batteryCharging = charging,
        batteryRangeKm = smartRangeKm,
        mgBatteryRangeKm = carRangeKm,
        odoDriftKm = odoDriftKm,
        odoKm = if (wide) 999_999.0 else ODO_KM,
        batteryTotalKwh = if (wide) 99_999.9 else 1_284.36,
        tripEnergyKwh = if (Optional.ENERGY in present) (if (wide) 999.9 else 2.87) else null,
        tripCostInr = if (Optional.COST in present) (if (wide) 99_999.0 else 23.4) else null,
        batteryMileageKmPerKwh =
            if (Optional.MILEAGE in present) (if (wide) 99.99 else 6.42) else null,
        batteryRangeAtFullKm =
            if (Optional.RANGE_AT_FULL in present) (if (wide) 1_234.0 else 310.0) else null,
        elevGainM = if (Optional.CLIMB in present) (if (wide) 9_999.0 else 184.0) else 0.0,
        elevLossM = if (Optional.CLIMB in present) (if (wide) 9_999.0 else 142.0) else 0.0,
        speedLimitKmh = if (Optional.LIMIT in present) 120 else 0
    )

    // ------------------------------------------------------------------ scenarios for the screen

    /**
     * One thing the driving screen has to render without breaking.
     *
     * [live] is what the recorder would put on the screen for [frame]. It is written out rather
     * than derived, because deriving it here would only re-implement the poll loop and then agree
     * with itself; the mapping that *is* pure is asserted separately against [Telematics].
     */
    data class Scenario(
        val name: String,
        val frame: Status,
        val live: LiveState
    )

    internal val driving = LiveState(
        hasFix = true, speedMps = 24.6f, distanceM = 18_432.0,
        elapsedS = 1_484, maxSpeedMps = 31.9f, movingS = 1_219, tripId = 1,
        telematicsConnected = true, odoKm = ODO_KM, batteryTotalKwh = 1_284.36
    )

    /**
     * Every state the screen is expected to survive, including the ones with nothing to show.
     *
     * The list is deliberately wider than "a car that is working": a first poll knows nothing, a
     * frame can arrive without a SOC, the link drops mid-drive, and the box can have measured too
     * few drives to quote a range of its own. Each of those renders a different set of the screen's
     * optional readings, and a layout only breaks on the combination nobody thought to look at.
     */
    val SCENARIOS: List<Scenario> = listOf(
        Scenario(
            "first poll, nothing known yet",
            status(odometerKm = null, charge = null),
            LiveState(hasFix = false)
        ),
        Scenario(
            "driving, no telematics configured",
            status(charge = null),
            driving.copy(telematicsConnected = null, odoKm = null, batteryTotalKwh = 0.0)
        ),
        Scenario(
            "driving, healthy charge, box quotes its own range",
            status(charge = HEALTHY),
            driving.copy(
                batterySocPercent = 63.0, batteryCharging = false,
                batteryRangeKm = 195.0, mgBatteryRangeKm = 201.0,
                batteryRangeAtFullKm = 310.0, batteryMileageKmPerKwh = 6.42,
                tripEnergyKwh = 2.87, tripCostInr = 23.4,
                elevGainM = 184.0, elevLossM = 142.0, speedLimitKmh = 80, odoDriftKm = 1.8
            )
        ),
        Scenario(
            "driving, only the car's range is trustworthy yet",
            status(charge = HEALTHY),
            driving.copy(
                batterySocPercent = 63.0, batteryCharging = false,
                batteryRangeKm = null, mgBatteryRangeKm = 201.0,
                batteryRangeAtFullKm = 319.0, speedLimitKmh = 60
            )
        ),
        Scenario(
            "driving, both estimates agree to the kilometre",
            status(charge = HEALTHY),
            driving.copy(
                batterySocPercent = 63.0, batteryCharging = false,
                batteryRangeKm = 201.0, mgBatteryRangeKm = 201.4
            )
        ),
        Scenario(
            "driving, low charge",
            status(charge = LOW),
            driving.copy(
                batterySocPercent = 22.0, batteryCharging = false,
                batteryRangeKm = 66.0, mgBatteryRangeKm = 70.0,
                batteryRangeAtFullKm = 300.0, tripEnergyKwh = 6.1, tripCostInr = 49.8
            )
        ),
        Scenario(
            "driving, critically low",
            status(charge = CRITICAL),
            driving.copy(
                batterySocPercent = 6.0, batteryCharging = false,
                batteryRangeKm = 17.0, mgBatteryRangeKm = 19.0, tripEnergyKwh = 9.4
            )
        ),
        Scenario(
            "flat battery",
            status(charge = EMPTY),
            driving.copy(
                batterySocPercent = 0.0, batteryCharging = false,
                batteryRangeKm = 0.0, mgBatteryRangeKm = 0.0
            )
        ),
        Scenario(
            "charging slowly at home, 70%",
            status(charge = CHARGING_70),
            driving.copy(
                speedMps = 0f, distanceM = 0.0, maxSpeedMps = 0f, movingS = 0,
                batterySocPercent = 70.0, batteryCharging = true, mgBatteryRangeKm = 223.0
            )
        ),
        Scenario(
            "charging fast, 45%",
            status(charge = CHARGING_17A_SOC45),
            driving.copy(
                speedMps = 0f, distanceM = 0.0, maxSpeedMps = 0f, movingS = 0,
                batterySocPercent = 45.0, batteryCharging = true, mgBatteryRangeKm = 144.0
            )
        ),
        Scenario(
            "full and still plugged in",
            status(charge = IDLE_100),
            driving.copy(
                speedMps = 0f, distanceM = 0.0, maxSpeedMps = 0f, movingS = 0,
                batterySocPercent = 100.0, batteryCharging = false,
                batteryRangeKm = 305.0, mgBatteryRangeKm = 320.0, batteryRangeAtFullKm = 320.0
            )
        ),
        Scenario(
            "frame arrived without a SOC",
            status(charge = NO_SOC),
            driving.copy(batterySocPercent = null, batteryCharging = null, mgBatteryRangeKm = null)
        ),
        Scenario(
            "telematics link dropped mid-drive",
            status(charge = null),
            driving.copy(telematicsConnected = false, batterySocPercent = null, odoDriftKm = null)
        ),
        Scenario(
            "odometer only on the charging frame",
            status(odometerKm = null, charge = HEALTHY),
            driving.copy(batterySocPercent = 63.0, mgBatteryRangeKm = 201.0, odoKm = ODO_KM)
        ),
        Scenario(
            "odometer drifted past the nag threshold",
            status(charge = HEALTHY),
            driving.copy(
                batterySocPercent = 63.0, mgBatteryRangeKm = 201.0, odoDriftKm = -12.4
            )
        ),
        Scenario(
            "overspeeding with every reading present",
            status(charge = HEALTHY),
            driving.copy(
                overLimit = true, speedLimitKmh = 80, speedMps = 29.2f,
                batterySocPercent = 63.0, batteryCharging = false,
                batteryRangeKm = 195.0, mgBatteryRangeKm = 201.0,
                batteryRangeAtFullKm = 310.0, batteryMileageKmPerKwh = 6.42,
                tripEnergyKwh = 2.87, tripCostInr = 23.4,
                elevGainM = 184.0, elevLossM = 142.0, odoDriftKm = 1.8
            )
        ),
        Scenario(
            "a long drive with implausibly large readings",
            status(charge = HEALTHY),
            driving.copy(
                distanceM = 987_654.0, elapsedS = 359_999, movingS = 359_999,
                maxSpeedMps = 55f, speedMps = 55f,
                batterySocPercent = 99.9, batteryRangeKm = 999.0, mgBatteryRangeKm = 1_234.0,
                batteryRangeAtFullKm = 1_234.0, batteryMileageKmPerKwh = 99.99,
                batteryTotalKwh = 99_999.9, tripEnergyKwh = 999.9, tripCostInr = 99_999.0,
                elevGainM = 9_999.0, elevLossM = 9_999.0, odoKm = 999_999.0,
                odoDriftKm = 123.4, speedLimitKmh = 120
            )
        )
    )
}
