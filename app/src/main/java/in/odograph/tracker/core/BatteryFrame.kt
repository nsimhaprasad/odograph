package `in`.odograph.tracker.core

import `in`.odograph.tracker.data.BatteryEntity
import io.windsor.telematics.ChargeStatus
import io.windsor.telematics.Status

/**
 * One telematics frame, written down.
 *
 * Every field the car sends that this app keeps, in one place. It used to be a sixty-line
 * constructor call in the middle of the poll loop, which meant the question "do we record X" was
 * answered by scrolling through the loop, and the mapping could not be tested without standing up
 * the whole service. Now it is a function of two values, and the real captured frames in the test
 * fixtures can be run through it directly.
 *
 * Recorded rather than reasoned about: a column is cheap and a frame that goes by unrecorded is
 * evidence destroyed, while deciding what any of it means is a separate question with a separate
 * bar of proof. Which trip a frame belongs to is that kind of question, and it is decided by the
 * caller — this only writes down what the car said.
 */
object BatteryFrame {

    /** The frame as a row, filed under [tripId] at time [t]. [powerKw] is the decoded charge power. */
    fun from(status: Status, ch: ChargeStatus, tripId: Long, t: Long, powerKw: Double) = BatteryEntity(
        tripId = tripId,
        t = t,
        socPercent = ch.soc,
        charging = ch.isCharging,
        rangeKm = ch.rangeKm,
        chargingPowerKw = powerKw,
        workingVoltage = ch.workingVoltage,
        workingCurrent = ch.workingCurrent,
        odometerKm = ch.odometerKm,
        batteryEnergyKwh = ch.batteryEnergyKwh,
        chargeTimeRemainingMin = ch.chargeTimeRemainingMin,
        distanceSinceLastChargeKm = ch.distanceSinceLastChargeKm,
        powerUsageSinceLastChargeKwh = ch.powerUsageSinceLastChargeKwh,
        exteriorTempC = status.exteriorTemperature,
        climateRunning = status.climateRunning,
        interiorTempC = status.interiorTemperature,
        chargingType = ch.chargingType,
        pluggedIn = ch.isPluggedIn,
        carCapacityKwh = ch.totalBatteryCapacityKwh,
        auxVoltage = status.auxBatteryVoltage,
        carJourneyId = status.currentJourneyId,
        carJourneyDistanceRaw = status.currentJourneyDistanceRaw,
        engineStatusRaw = status.engineStatusRaw,
        powerModeRaw = status.powerModeRaw,
        handbrake = status.handbrake,
        tyreFlPsi = status.frontLeftTyrePsi,
        tyreFrPsi = status.frontRightTyrePsi,
        tyreRlPsi = status.rearLeftTyrePsi,
        tyreRrPsi = status.rearRightTyrePsi,
        carGpsSatellites = status.gps?.satellites,
        carGpsStatus = status.gps?.gpsStatus?.name,
        carSpeedKmh = status.gps?.speedKmh,
        chargerId = ch.chargingPileId,
        chargerSupplier = ch.chargingPileSupplier,
        lastChargeEndKwh = ch.lastChargeEndingPowerKwh,
        staticDrainRaw = ch.staticEnergyConsumptionRaw,
        chargeElapsedS = ch.chargeTimeElapsedS,
        dayDistanceRaw = ch.mileageOfDayRaw,
        dayPowerRaw = ch.powerUsageOfDayRaw
    )
}
