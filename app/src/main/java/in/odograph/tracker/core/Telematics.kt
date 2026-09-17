package `in`.odograph.tracker.core

import io.windsor.telematics.ChargeStatus
import io.windsor.telematics.Status

/**
 * What an MG frame means, separated from what the recorder does about it.
 *
 * The poll loop reads a frame, writes rows, moves the charge ledger on and updates the live state
 * in one pass, so the small readings the screen depends on — the charging power, which odometer to
 * believe, whether the frame carries a battery reading at all — were arithmetic buried inside a
 * suspending function that needs a database and a network. None of it could be checked without
 * running the whole loop, which is how a double-applied scale factor once clamped every 30 kW
 * public charger to SLOW and stayed clamped until someone noticed on the glass.
 *
 * As pure functions over the decoded types they can be fed the library's own captured frames.
 */
object Telematics {

    /**
     * Charging power, kW.
     *
     * The decoder has already applied the confirmed scales — `chargingVoltage` is real volts and
     * `chargingCurrent` real amps — so this is straight V × I and nothing may re-scale it. The
     * regression worth remembering: applying the raw scale factors a second time here divided
     * every reading by enough to put a 30 kW DC charger under the fast threshold.
     */
    fun chargePowerKw(ch: ChargeStatus?): Double =
        if (ch == null) 0.0 else ch.chargingVoltage * ch.chargingCurrent / 1000.0

    /**
     * The car's own dash odometer, km, or null when this frame quotes none.
     *
     * The status frame is preferred and the charging frame stands in, because a normal MG frame
     * carries an odometer in one or the other — which is what makes anchoring to the dash viable
     * rather than a calibration exercise. See [Odometer.adoptCarOdo].
     */
    fun carOdometerKm(status: Status): Double? = status.odometerKm ?: status.charge?.odometerKm

    /**
     * Whether this frame carries a real battery reading.
     *
     * A charging block without a SOC is not charge data, it is noise — recording it would make a
     * battery-less drive look instrumented and would let a null SOC through into the energy
     * arithmetic. Both the battery row and every derived readout gate on this.
     */
    fun hasBatteryReading(ch: ChargeStatus?): Boolean = ch?.soc != null

    /**
     * The car's own remaining-range estimate, km, or null when it quotes none.
     *
     * Treated as nullable on purpose even though the decoded field is not: a frame that has no
     * charge block at all has no range either, and a zero is the car saying "empty", not "unknown".
     */
    fun carRangeKm(ch: ChargeStatus?): Double? = ch?.rangeKm

    /**
     * What a full charge would carry, km, extrapolated from the car's own quote at this SOC.
     *
     * Only honest while the box has too few instrumented drives to measure efficiency itself; once
     * it can, [BatteryMath.rangeAtFullKwh] supersedes this. Guarded against a zero SOC, where the
     * extrapolation is a division by zero rather than an estimate.
     */
    fun carRangeAtFullKm(ch: ChargeStatus?): Double? {
        val soc = ch?.soc ?: return null
        val range = ch.rangeKm
        return if (soc > 0.0) range / soc * 100.0 else null
    }

    /**
     * The pack's usable capacity as this frame implies it, kWh.
     *
     * The car reports the energy currently in the pack and the percentage that represents, so the
     * two together size the pack. Useful as a cross-check against the configured capacity — the
     * captured frames put the Windsor at about 37.3 kWh. Null at a zero SOC, where the division
     * says nothing.
     */
    fun impliedCapacityKwh(ch: ChargeStatus?): Double? {
        val soc = ch?.soc ?: return null
        return if (soc > 0.0) ch.batteryEnergyKwh / soc * 100.0 else null
    }
}
