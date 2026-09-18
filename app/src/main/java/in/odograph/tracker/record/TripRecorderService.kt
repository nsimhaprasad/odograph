package `in`.odograph.tracker.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import `in`.odograph.tracker.alert.AlertConfig
import `in`.odograph.tracker.alert.AlertSound
import `in`.odograph.tracker.alert.SpeedAlert
import `in`.odograph.tracker.core.Fix
import `in`.odograph.tracker.data.OdographDao
import `in`.odograph.tracker.core.LiveTrack
import `in`.odograph.tracker.core.BatteryMath
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.data.BatteryEntity
import `in`.odograph.tracker.data.PointEntity
import `in`.odograph.tracker.data.PriceReminderEntity
import `in`.odograph.tracker.diag.Diagnostics
import `in`.odograph.tracker.geocode.PlaceNamer
import `in`.odograph.tracker.server.DashboardServer
import `in`.odograph.tracker.server.RawFrames
import `in`.odograph.tracker.sync.Outbound
import `in`.odograph.tracker.sync.SheetsSync
import `in`.odograph.tracker.ui.theme.Settings
import `in`.odograph.tracker.core.Arrival
import `in`.odograph.tracker.core.Departure
import `in`.odograph.tracker.core.SpeedSanity
import `in`.odograph.tracker.core.TripRepair
import `in`.odograph.tracker.core.Telematics
import io.windsor.telematics.TelematicsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TripRecorderService : Service() {

    data class LiveState(
        val hasFix: Boolean = false,
        val speedMps: Float = 0f,
        val distanceM: Double = 0.0,
        val elapsedS: Long = 0,
        val maxSpeedMps: Float = 0f,
        val movingS: Long = 0,
        val tripId: Long = -1,
        val overLimit: Boolean = false,
        val speedLimitKmh: Int = 0,
        val batterySocPercent: Double? = null,
        val batteryCharging: Boolean? = null,
        /**
         * The MG telematics link. True after a poll round-trip (login + status) succeeded, false
         * after any step failed, null before the first attempt or while the poller is switched
         * off. This is what the corner indicator draws — a green tick, a red cross, or nothing.
         */
        val telematicsConnected: Boolean? = null,
        /** Real-world mileage this trip, km·kWh⁻¹. Null until the trip is long enough to trust. */
        val batteryMileageKmPerKwh: Double? = null,
        /** What a 100% charge would carry you, from real consumption (or the car's estimate). */
        val batteryRangeAtFullKm: Double? = null,
        /**
         * Range remaining at the current SOC, km, from the box's own consumption history. Null
         * until enough instrumented drives exist to trust real-world efficiency over the car.
         */
        val batteryRangeKm: Double? = null,
        /** Range remaining that the car itself quotes, km. The MG telematics cross-check. */
        val mgBatteryRangeKm: Double? = null,
        /** Lifetime energy the car has consumed over instrumented drives, kW·h. */
        val batteryTotalKwh: Double = 0.0,
        /** Energy this drive has consumed so far, kW·h. Null until a usable SOC swing is known. */
        val tripEnergyKwh: Double? = null,
        /**
         * What this drive's energy costs, billed the same way the closing trip will be: the
         * blended rate of the fills that began before it. Null when nothing has priced it yet.
         */
        val tripCostInr: Double? = null,
        /** Metres climbed this drive, deadbanded — the context battery consumption depends on. */
        val elevGainM: Double = 0.0,
        /** Metres descended this drive, never netted against the climb because descent regenerates. */
        val elevLossM: Double = 0.0,
        /**
         * Lifetime odometer, km: the seeded baseline plus every closed trip plus this drive so
         * far. Null until the baseline is set and the DB has been read once at boot.
         */
        val odoKm: Double? = null,
        /**
         * The car's own quoted odometer (telematics) minus ours, km. Zero means on track;
         * a persistent positive/negative value means GPS drift — the setup page's calibrate
         * button snaps ours back to the car's. Null until a telematics frame with an odo arrives.
         */
        val odoDriftKm: Double? = null,
        /**
         * A fast charge the driver should price, surfaced to the UI as a prompt. Set from the
         * poller when a fast session opens (pre-price) or closes (correct with the real bill);
         * the dialog that shows it clears it. A slow session never prompts — the home rate stands.
         */
        val pendingChargePrompt: ChargePrompt? = null,
        /**
         * Where the car is, from the last fix. Carried on the live state so a screen can answer
         * "how far is that from here" without reaching into the recorder or re-reading the points
         * table. Null before the first fix.
         */
        val lat: Double? = null,
        val lon: Double? = null
    )

    /** What the charger the driver just used expects to be paid. */
    data class ChargePrompt(
        val sessionId: Long,
        val energyKwh: Double,
        val isOpen: Boolean,
        val currentCostInr: Double? = null
    )

    companion object {
        const val CHANNEL_ID = "odograph_recording"
        const val NOTIFICATION_ID = 1

        /** The "MG is offline" heads-up, separate from the ongoing recording notice. */
        private const val MG_ALERT_NOTIFICATION_ID = 2

        /** The "odometer drifted" heads-up; the setup page can recalibrate in one tap. */
        private const val ODO_ALERT_NOTIFICATION_ID = 3

        /** The "record the odometer, it's been a while" reminder. */
        private const val ODO_RECORD_NOTIFICATION_ID = 4

        /** A drift this big (km, car vs ours) is worth interrupting for — GPS error is normal below it. */
        const val ODO_DRIFT_FLAG_KM = 5.0

        /**
         * How long a single outage may keep nagging before another heads-up fires: one reminder
         * per sustained failure, never a bark on every failed poll.
         */
        private const val MG_LOST_NOTIFY_GAP_MS = 10 * 60_000L
        private const val ACCURACY_LIMIT_M = 25f
        private const val SPEED_ACCURACY_LIMIT_M = 15f
        /**
         * Cadence while the MG screen is being looked at. Being on-screen is one of the three
         * things that may warrant a call — see [telematicsLoop] for the guard.
         */
        private const val TELEMATICS_POLL_MS = 30_000L

        /** Freshness floor when the MG screen is not visible: even idle, at most one call per 5 min. */
        private const val TELEMATICS_HEARTBEAT_MS = 5 * 60_000L

        /**
         * Hard floor between any two MG server calls. Every trigger — explicit refresh, screen
         * visible, heartbeat — funnels through here, so bursts of taps coalesce into at most one
         * request per floor. This is what keeps us from looking like a DoS source.
         */
        private const val TELEMATICS_MIN_INTERVAL_MS = 30_000L

        /** How often the guard re-evaluates. A local tick, no network involved. */
        private const val TELEMATICS_WAKE_MS = 1_000L

        /**
         * Frames older than this since the box last saw the car move are "parked": the parker is
         * still wearing the battery down overnight, so those frames meter the vampire drain a
         * parked window is made of. Shorter than a traffic-light wait survives this test.
         */
        private const val PARKED_MOVE_GAP_MS = 10 * 60_000L

        /**
         * A fix this fast counts as the car genuinely driving. A parked car plugged into a charger
         * never legitimately produces sustained speed, so once [DRIVE_START_CONFIRM_FIXES]
         * consecutive fixes exceed it, any open charge session is assumed over — see [endChargeForDriveStart].
         */
        private const val DRIVE_START_SPEED_MPS = 1.0f

        /** Consecutive driving fixes that confirm a real pull-away before a charge ends. */
        private const val DRIVE_START_CONFIRM_FIXES = 3

        /**
         * The revision of the stored-history repair this build carries.
         *
         * Bumped when a new correction is added, which reruns the pass over drives an earlier
         * revision already visited.
         */
        private const val REPAIR_REVISION = 1

        /** No trip is open. Battery frames recorded under it are parked readings, not a drive. */
        const val NO_TRIP = -1L

        /**
         * How much of the approach to a departure is kept while waiting to be sure of it.
         *
         * About a minute at one fix a second: long enough that a trip opened on the third moving
         * fix still starts from where the car was standing, short enough that a car parked for the
         * night does not hoard fixes or date its next departure to the previous evening.
         */
        private const val MAX_PENDING_FIXES = 60


        private val _state = MutableStateFlow(LiveState())
        val state: StateFlow<LiveState> = _state

        /** True while the trip's battery tile is visible, so polling may run at [TELEMATICS_POLL_MS]. */
        private val telematicsVisible = MutableStateFlow(false)

        /** A one-shot "go now" flag. Coalesced by the min-interval floor, so it can never burst. */
        private val telematicsRefresh = MutableStateFlow(false)

        /** The driver screen is where the MG battery tile lives; being there justifies live data. */
        fun setTelematicsScreenVisible(visible: Boolean) {
            telematicsVisible.value = visible
        }

        /** User-triggered freshness (e.g. tapping the battery tile). Honours the min-interval floor. */
        fun requestTelematicsRefresh() {
            telematicsRefresh.value = true
        }

        /**
         * After a Setup-page calibration the stored factor/baseline changed, but the live state was
         * computed at boot. Recompute the odometer against the current settings so the driver
         * screen shows the corrected reading immediately instead of after the next reboot.
         */
        fun refreshCalibratedOdo(dao: `in`.odograph.tracker.data.OdographDao?, settings: Settings) {
            if (dao == null) return
            val base = `in`.odograph.tracker.core.Odometer.liveOdoKm(
                settings, dao.trackedDistanceM() / 1000.0
            )
            _state.update { it.copy(odoKm = base + it.distanceM / 1000.0 *
                `in`.odograph.tracker.core.Odometer.factor(settings)) }
        }

        @Volatile
        private var restoring = false

        fun isRestoring(): Boolean = restoring

        /** Pauses capture so a restore can swap the database. Refuses if a moving trip is open. */
        fun pauseForRestore(): Boolean {
            val state = _state.value
            if (state.tripId >= 0 && state.distanceM > 50.0) return false
            restoring = true
            return true
        }

        fun resumeAfterRestore() { restoring = false }

        /** The dialog that shows a charge prompt clears it once handled. */
        fun clearChargePrompt() {
            _state.update { it.copy(pendingChargePrompt = null) }
        }

        /**
         * Surfaces the newest unpriced fast charge, if the driver never priced or ignored it. The
         * live prompt is transient — this re-arms it from the durable reminder row the poller
         * left, so an ask dismissed at the car after a locked-door session comes back at the next
         * drive start or app open instead of silently dying.
         */
        fun raisePendingPriceReminder(dao: `in`.odograph.tracker.data.OdographDao?) {
            if (dao == null) return
            val state = _state.value
            if (state.pendingChargePrompt != null) return
            runCatching {
                val pending = dao.newestPendingReminder() ?: return
                val event = dao.chargeEvent(pending.eventId) ?: return
                _state.update {
                    if (it.pendingChargePrompt == null) {
                        it.copy(
                            pendingChargePrompt = ChargePrompt(
                                sessionId = event.id,
                                energyKwh = event.energyKwh,
                                isOpen = false,
                                currentCostInr = event.costInr
                            )
                        )
                    } else it
                }
            }
        }

        /** Accepted a persistent reminder: the session is priced, so the ask is answered. */
        fun acceptChargePrompt(sessionId: Long, dao: `in`.odograph.tracker.data.OdographDao?) {
            _state.update { it.copy(pendingChargePrompt = null) }
            if (dao == null) return
            io.launch {
                runCatching {
                    dao.deleteReminder(PriceReminderEntity(eventId = sessionId, raisedAt = 0L))
                }
            }
        }

        /** Declined a persistent reminder for now: it will not nag again. */
        fun ignoreChargePrompt(sessionId: Long, dao: `in`.odograph.tracker.data.OdographDao?) {
            _state.update { it.copy(pendingChargePrompt = null) }
            if (dao == null) return
            io.launch {
                runCatching { dao.ignoreReminder(sessionId, System.currentTimeMillis()) }
            }
        }

        private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private lateinit var source: LocationSource
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tripId: Long = -1
    private var lastFix: Fix? = null
    private var startedAt: Long? = null
    /** Wall-clock the car last moved over a fix. When stale, the car is parked and may be draining. */
    private var lastMovedAt = Long.MIN_VALUE
    /** Consecutive driving fixes, so a single GPS blip can't end a real charge. */
    private var drivingFixStreak = 0
    /** True once the current drive has already ended whatever charge was open. */
    private var driveEndedCharge = false
    private var track = LiveTrack()
    /**
     * The last speed that passed the plausibility test, so a rejected spike never becomes the
     * reference the next reading is judged against.
     */
    private var lastAcceptedSpeedMps: Float? = null
    /**
     * Fixes seen before the car was judged to be moving.
     *
     * Held in memory rather than written, because until this window proves a departure there is no
     * trip for them to belong to. Bounded: a car parked overnight must not accumulate a night's
     * worth of fixes, and an origin a whole night old is worse than one a minute old.
     */
    private val pendingFixes = ArrayDeque<Fix>()
    /** What the last run left behind, held until there is a trip to attach it to. */
    private var recovery = TripRecovery.Recovery()
    /**
     * The car's own account of being shut down, from the most recent telematics frame.
     *
     * Lets a drive end the moment the car is locked instead of waiting out the stationary timer.
     * Stays empty when telematics is off or unreachable, and the timer carries it alone.
     */
    private var carState = Arrival.CarState()
    /** Lifetime odometer at boot: seeded baseline + every closed trip's distance. */
    private var odoBaseKm: Double = 0.0
    private lateinit var settings: Settings
    private lateinit var alertSound: AlertSound
    private var speedAlert = SpeedAlert(AlertConfig(limitKmh = 0f))
    private var configuredLimit = -1
    private var lastMgLostNotifyAt = Long.MIN_VALUE

    override fun onCreate() {
        super.onCreate()
        Diagnostics.crumb("service onCreate start")
        startInForeground()
        Diagnostics.crumb("startForeground ok")
        source = GnssLocationSource(this)
        settings = Settings(this)
        alertSound = AlertSound(this)
        Diagnostics.crumb("service deps built")
        runCatching { alertSound.prepare(settings.alertMode) }

        io.launch {
            val dao = OdographDb.get(this@TripRecorderService).dao()
            // startedAt is patched by the first real fix; GNSS time is the authority.
            Diagnostics.crumb("db opened")
            track = LiveTrack()
            // Closes whatever the last run left open, but opens nothing: a trip begins when the
            // car does. Booting into an open 0 km row is what put a dummy drive at the top of the
            // history every time the box powered up in a car that then sat still.
            recovery = TripRecovery.recover(
                dao,
                capacityKwh = settings.batteryCapacityKwh,
                homeRateInr = settings.homeRateInr,
                outsideRateInr = settings.outsideRateInr
            )
            tripId = NO_TRIP
            Diagnostics.crumb("recovery done, awaiting movement")
            // One-off, and only once: every drive recorded before the plausibility rules existed
            // kept whatever the worst single fix claimed. The points are still on disk, so the
            // honest figure can be worked out again. Guarded by a revision so a later correction
            // can run over the same drives without a second flag, and so this does not walk the
            // whole history on every boot.
            if (settings.repairRevision < REPAIR_REVISION) {
                val outcome = runCatching { TripRepair.repairMaxSpeeds(dao) }.getOrNull()
                if (outcome != null) {
                    settings.repairRevision = REPAIR_REVISION
                    Diagnostics.crumb(
                        "repair: examined ${outcome.examined} drives, corrected ${outcome.corrected}" +
                            (if (outcome.corrected > 0)
                                ", worst was %.0f km/h".format(outcome.worstBeforeMps * 3.6f)
                            else "")
                    )
                }
            }

            odoBaseKm = `in`.odograph.tracker.core.Odometer.liveOdoKm(settings, dao.trackedDistanceM() / 1000.0)
            _state.value = LiveState(tripId = tripId, odoKm = odoBaseKm)
            // A charge owed an answer is the first thing a boot should ask again.
            raisePendingPriceReminder(dao)
            source.start { fix -> io.launch { record(fix) } }

            // Everything below is best-effort and entirely optional. The hotspot is usually up,
            // but recording must behave identically when it is not, so both are wrapped and
            // neither is on the capture path.
            Diagnostics.crumb("starting dashboard server")
            runCatching { DashboardServer.start(this@TripRecorderService) }
                .onFailure { Diagnostics.crumb("dashboard server FAILED: $it") }
            Diagnostics.crumb("dashboard server step done")
            // Give any new places a readable default name. Best-effort, rate-limited, and a
            // user's own label always wins over whatever comes back.
            runCatching { PlaceNamer.nameMissing(dao) }
                .onFailure { Diagnostics.crumb("place naming failed: $it") }

            // Whole-dataset export to the Google Docs link, on its own clock so it can never
            // hold up telematics or recording.
            io.launch { sheetsSyncLoop() }

            telematicsLoop()
        }
    }

    /**
     * Best-effort MG iSMART battery poller. Entirely out of the capture path: a network outage,
     * expired credentials, or the vehicle being out of range must never affect recording. It
     * calls the real MG servers over the hotspot, so it also fails quietly on purpose.
     *
     * These servers belong to someone else, so we must not hammer them, or our login looks like
     * an attack and the account gets blocked. A status call only goes out when at least one of
     * these holds:
     *   1. the MG battery screen is visible (up to once every [TELEMATICS_POLL_MS]),
     *   2. an explicit refresh was requested (coalesced by the floor below),
     *   3. [TELEMATICS_HEARTBEAT_MS] elapsed without any call (freshness floor in the background).
     * Whatever the trigger, no two calls happen closer than [TELEMATICS_MIN_INTERVAL_MS].
     *
     * Credentials are re-read every round so the box can be reconfigured over the dashboard
     * without a restart; a blank phone disables the poller entirely.
     */
    private suspend fun telematicsLoop() {
        val dao = OdographDb.get(this).dao()
        var client: TelematicsClient? = null
        var creds: Triple<String, String, String>? = null
        var lastCallElapsed = Long.MIN_VALUE

        while (true) {
            delay(TELEMATICS_WAKE_MS)

            val sinceLast = SystemClock.elapsedRealtime() - lastCallElapsed
            val neverCalled = lastCallElapsed == Long.MIN_VALUE
            val demanded =
                telematicsVisible.value ||
                    telematicsRefresh.value ||
                    sinceLast >= TELEMATICS_HEARTBEAT_MS
            val cooled = neverCalled || sinceLast >= TELEMATICS_MIN_INTERVAL_MS
            if (!demanded || !cooled) continue
            telematicsRefresh.value = false

            val settings = Settings(this)
            val phone = settings.telematicsPhone
            val password = settings.telematicsPassword
            // The on/off switch is authoritative: off means no MG calls at all and no stale
            // battery data on the driving screen, even if credentials exist.
            if (!settings.telematicsEnabled || phone.isBlank() || password.isBlank()) {
                client = null
                creds = null
                _state.value = _state.value.copy(batterySocPercent = null, batteryCharging = null, telematicsConnected = null, odoDriftKm = null)
                // Deliberately off is not an outage; drop any reminder so it cannot nag on.
                clearMgLostNotification()
                continue
            }
            // No point calling a car server on a dead link, and it would cost us the account:
            // a box blocked for hammering is a box with no range numbers at all. Skip the call,
            // flag the connection down, and let the reminder nag — this is the one case the
            // driver can actually do something about (switch the hotspot back on).
            if (!hasValidatedNetwork()) {
                Diagnostics.crumb("mg: no validated network, poll skipped")
                _state.update { it.copy(telematicsConnected = false) }
                notifyMgLost()
                continue
            }
            val want = Triple(phone, password, settings.telematicsVin)
            if (client == null || creds != want) {
                val framesDir = RawFrames.directory(this)
                val fresh = TelematicsClient.create(
                    phone, password, settings.telematicsVin.takeIf { it.isNotBlank() },
                    onRawResponse = { label, hex ->
                        val kind = when (label) {
                            "Status" -> "status.raw"
                            "Charge status" -> "charge.raw"
                            else -> label
                        }
                        RawFrames.record(framesDir, kind, hex)
                    },
                )
                client = fresh
                creds = want
                runCatching { fresh.login() }
                    .onFailure {
                        Diagnostics.crumb("telematics login failed: $it")
                        _state.update { it.copy(telematicsConnected = false) }
                    }
                runCatching { fresh.vehicles() }
                    .onFailure {
                        Diagnostics.crumb("telematics vehicles() failed: $it")
                        _state.update { it.copy(telematicsConnected = false) }
                    }
            }
            val c = client

            runCatching {
                val status = c.status(includeCharge = true)
                val framesDir = RawFrames.directory(this)
                RawFrames.record(framesDir, "status.decoded", status.toString())
                status.charge?.let { RawFrames.record(framesDir, "charge.decoded", it.toString()) }
                val ch = status.charge
                val now = System.currentTimeMillis()
                // What the car says about being shut down. Only the CAN bus: a locked car is not a
                // parked one, because the doors lock themselves above walking pace.
                carState = Arrival.CarState(canBusActive = status.canBusActive)
                val powerKw = Telematics.chargePowerKw(ch)
                // A snapshot without a SOC reading is not charge data — it is noise that would
                // make a battery-less trip look instrumented. The car can cut power any moment,
                // so every frame that does carry a SOC is written to disk immediately and never
                // held in memory. It also never lingers under the wrong owner: while the car is
                // moving it lands under the open trip, and once it has been parked (unplugged,
                // no motion for [PARKED_MOVE_GAP_MS]) it carries trip -1 instead — invisible to
                // every trip-scoped query but never lost, and source of the overnight drain read.
                if (Telematics.hasBatteryReading(ch) && ch != null) {
                    val t = lastFix?.t ?: now
                    val parked = ch.isCharging == false &&
                        (now - lastMovedAt) > PARKED_MOVE_GAP_MS
                    dao.insertBattery(
                        BatteryEntity(
                            tripId = if (parked) -1 else tripId,
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
                            powerUsageSinceLastChargeKwh = ch.powerUsageSinceLastChargeKwh
                        )
                    )
                }

                // Charging sessions survive the drives they happened under. Whatever this frame
                // says, the ledger lands it in charge_events or closes the session it ends, and
                // fast sessions surface a prompt so the driver can price the kWh they actually
                // paid for. A slow session never prompts — the home rate stands.
                val capacity = settings.batteryCapacityKwh
                val change = ChargeLedger(dao, capacity, settings.homeRateInr, settings.outsideRateInr)
                    .observe(ch?.isCharging, ch?.soc, powerKw, now)
                applyChargeChange(dao, change)

                // Coverage honesty: note that the poller ran at all, so the dashboard can show
                // the days it did not.
                recordDailyCoverage(dao, settings.zone, now)

                // The car quotes its own odometer on telematics frames; the dash is ground truth,
                // so adopt it as the odometer anchor. Existing trips are never rewritten — the gap
                // between a car that had already covered 18k km before tracking began and the app's
                // count is an offset, not a measurement error, so it is absorbed here in one number.
                val carOdoKm = Telematics.carOdometerKm(status)
                adoptMgOdometerAnchor(carOdoKm, dao)
                // Anything ours counts up against that is drift (GNSS distance error, a wrong seed,
                // a wheel-off). Compare live so the setup page can offer a one-tap recalibrate, and
                // nag once a day when the mismatch grows past the noise floor.
                checkOdoDrift(carOdoKm, settings)

                // If a long stretch has gone by without a dash reading, the odometer window keeps
                // stretching uncalibrated — ask the driver to record one (at most once a day).
                checkOdoRecordDue(dao, settings)

                // Per-trip energy and the live efficiency readouts the drive screen shows.
                val soc = ch?.soc
                if (soc != null) {
                    val state = _state.value
                    if (tripId >= 0) {
                        val samples = dao.batteryRangeFor(tripId)
                        val energy = BatteryMath.consumedKwh(samples, capacity)
                            ?.let { BatteryMath.round2(it) }
                        if (samples.isNotEmpty()) {
                            dao.setChargeSummary(tripId, samples.first().socPercent, soc, energy)
                        }
                        val kmPerKwh = BatteryMath.kmPerKwh(energy, state.distanceM)
                        val effs = dao.tripEnergies()
                            .mapNotNull { BatteryMath.kwhPer100Km(it.energyKwh, it.distanceM) }
                        // Feed this drive's live efficiency into the rolling window too, so RANGE@100
                        // and MILEAGE move on every poll instead of freezing until more trips close,
                        // and a long single drive keeps correcting the estimate on the drive screen.
                        val liveEff = kmPerKwh?.let { 100.0 / it }
                        val rollingEffs = if (liveEff != null) listOf(liveEff) + effs else effs
                        val rolling = BatteryMath.rollingKwhPer100Km(rollingEffs)
                        val rangeAtFull = if (
                            rollingEffs.size >= BatteryMath.MIN_TRIPS_FOR_REAL_ESTIMATE &&
                            rolling != null
                        ) {
                            BatteryMath.rangeAtFullKwh(capacity, rolling)
                        } else {
                            Telematics.carRangeAtFullKm(ch)
                        }
                        // Same gate as range-at-full: only real measured efficiency gets to quote a
                        // remaining range. Before that the car's own number is the only honest one.
                        val smartRange = if (
                            rollingEffs.size >= BatteryMath.MIN_TRIPS_FOR_REAL_ESTIMATE && rolling != null
                        ) {
                            BatteryMath.rangeAtSocKwh(capacity, soc, rolling)
                        } else null
                        // "The total for this ride" is billed exactly like the closing trip will be —
                        // the same blended fill rate, so what the screen quotes is what shows up next
                        // week in the archive. No fills yet, no price yet — the honest unknown.
                        val tripCost = TripRecovery.driveCost(
                            dao, energy, dao.tripById(tripId)?.startedAt ?: 0L
                        )
                        _state.value = state.copy(
                            batterySocPercent = soc,
                            batteryCharging = ch.isCharging,
                            batteryMileageKmPerKwh = kmPerKwh,
                            batteryRangeAtFullKm = rangeAtFull,
                            batteryRangeKm = smartRange,
                            mgBatteryRangeKm = ch.rangeKm,
                            batteryTotalKwh = dao.totalEnergyKwh(),
                            tripEnergyKwh = energy,
                            tripCostInr = tripCost
                        )
                    } else {
                        _state.value = _state.value.copy(
                            batterySocPercent = soc,
                            batteryCharging = ch.isCharging,
                            tripEnergyKwh = null,
                            tripCostInr = null,
                            batteryRangeAtFullKm = null,
                            batteryRangeKm = null,
                            mgBatteryRangeKm = ch.rangeKm
                        )
                    }
                } else {
                    _state.value = _state.value.copy(
                        batterySocPercent = null, batteryCharging = null,
                        batteryRangeAtFullKm = null, batteryRangeKm = null, mgBatteryRangeKm = null,
                        odoDriftKm = null
                    )
                }

                // Whatever the frame carried, a round-trip that returned without throwing means
                // the MG link is up — the corner indicator can go green, and any "MG is offline"
                // reminder is cancelled: the connection it was nagging about is back.
                _state.update { it.copy(telematicsConnected = true) }
                clearMgLostNotification()
            }.onFailure {
                Diagnostics.crumb("telematics poll failed: $it")
                _state.update { it.copy(telematicsConnected = false) }
                // A stale session is the usual culprit; the next round logs in again.
                runCatching { c.login() }
            }
            lastCallElapsed = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Scheduled incremental export to the Google Docs link, on a selectable hourly cadence
     * (default: every hour).
     *
     * Only what has appeared since the last successful export is uploaded, so the payload stays
     * constant-sized no matter how much history the workbook holds; the workbook is the archive,
     * not a mirror that must be re-uploaded to keep existing. A run is due when none has succeeded
     * for the configured period; a failed run (no network, script not deployed yet) simply retries
     * the same delta on the next loop.
     */
    private suspend fun sheetsSyncLoop() {
        while (true) {
            delay(2 * 60_000L)
            val s = Settings(this)
            val periodMs = s.docsSyncHours * 3_600_000L
            if (s.webhookUrl.isBlank()) continue
            if (System.currentTimeMillis() - s.lastDocsSyncAt < periodMs) continue
            val r = SheetsSync.exportDocs(this, s.webhookUrl, s.deviceId)
            Diagnostics.crumb(
                "docs export: " + (r.error ?: "${r.delivered}/${r.attempted} rows")
            )
            if (r.error == null) s.lastDocsSyncAt = System.currentTimeMillis()
        }
    }

    /**
     * Marks today as a day the poller reached the MG servers, widening the captured window from
     * the first poll of the day to the latest. Any local day without a row was simply not
     * captured — which is exactly the honesty the archive's coverage display needs.
     */
    private fun recordDailyCoverage(dao: `in`.odograph.tracker.data.OdographDao, zone: java.util.TimeZone, now: Long) {
        val cal = java.util.Calendar.getInstance(zone)
        val day = cal.get(java.util.Calendar.YEAR) * 10_000 +
            (cal.get(java.util.Calendar.MONTH) + 1) * 100 +
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        if (dao.telemetryDay(day) == null) {
            dao.insertTelemetryDayIfAbsent(day, first = now, last = now)
        } else {
            dao.setTelemetryDayLast(day, now)
        }
        // Parked frames are meter records, not history: anything past 60 days is unneeded for a
        // drain read and would otherwise pile up forever under trip -1. Indexed, so this is cheap.
        runCatching { dao.pruneParkedFrames(now - 60L * 24 * 3_600_000L) }
    }

    /**
     * Has the car actually set off?
     *
     * Two ways to say yes, matching [TripStats.moved] so that what opens a trip and what keeps one
     * cannot disagree: a sustained speed, or simply having ended up somewhere else. The streak is
     * what stops a single GPS blip from opening a drive at a parked car, and the displacement is
     * what stops a slow crawl out of a car park from being dismissed as one.
     */
    private fun departed(): Boolean = Departure.departed(pendingFixes.toList(), track.speedMps)

    /**
     * Opens the trip the held fixes turned out to belong to, and writes them into it.
     *
     * Back-dated to the first held fix so the drive starts where the car was standing rather than
     * wherever it had got to by the time three fixes agreed it was moving.
     */
    private fun openTripFromPending(dao: OdographDao) {
        val first = pendingFixes.first()
        tripId = TripRecovery.startOnMove(dao, first.t, first.lat, first.lon, recovery)
        recovery = TripRecovery.Recovery()
        startedAt = first.t
        Diagnostics.crumb("trip opened on movement trip=$tripId")

        // All but the last: the caller writes that one itself, and a point written twice would
        // put a zero-length segment into the middle of the departure.
        pendingFixes.dropLast(1).forEach {
            dao.appendPoint(
                PointEntity(
                    tripId = tripId, t = it.t, lat = it.lat, lon = it.lon,
                    speedMps = it.speedMps, bearingDeg = null, altitudeM = it.altitudeM,
                    accuracyM = it.accuracyM, interpolated = it.interpolated
                )
            )
        }
        pendingFixes.clear()
    }

    /**
     * Ends the open drive and returns the recorder to waiting for the next one.
     *
     * The trip is written through the same path a boot would have used, so a drive closed by
     * parking and a drive closed by a power cut are recorded identically — and one that turns out
     * not to have moved is discarded by that path rather than surfacing as a 0 km row.
     */
    private fun closeTripOnArrival(dao: OdographDao) {
        val closed = tripId
        recovery = TripRecovery.close(
            dao, closed,
            capacityKwh = settings.batteryCapacityKwh,
            homeRateInr = settings.homeRateInr,
            outsideRateInr = settings.outsideRateInr
        )
        tripId = NO_TRIP
        startedAt = null
        pendingFixes.clear()
        track = LiveTrack()
        Diagnostics.crumb("trip closed on arrival trip=$closed")
    }

    /** Gap since the previous fix, seconds. Zero when this is the first one. */
    private fun secondsSinceLastFix(fix: Fix): Float =
        lastFix?.let { (fix.t - it.t) / 1000f } ?: 0f

    /** The live readout, which a parked car still gets — it just is not recording a drive. */
    private fun publishLiveState(
        fix: Fix,
        speedMps: Float,
        moving: Boolean,
        overLimit: Boolean = false,
        speedLimitKmh: Int = settings.speedLimitKmh
    ) {
        val cur = _state.value
        _state.value = cur.copy(
            hasFix = true,
            speedMps = speedMps,
            distanceM = if (moving) track.distanceM else 0.0,
            elapsedS = if (moving) (fix.t - (startedAt ?: fix.t)) / 1000 else 0,
            // The same test the stored trip applies, so the live readout and the saved figure
            // cannot disagree about what the car was doing — and a spike cannot park itself at the
            // top of the screen for the rest of the drive.
            maxSpeedMps = when {
                !moving -> 0f
                SpeedSanity.isPlausible(lastAcceptedSpeedMps, speedMps, secondsSinceLastFix(fix)) ->
                    maxOf(cur.maxSpeedMps, speedMps).also { lastAcceptedSpeedMps = speedMps }
                else -> cur.maxSpeedMps
            },
            movingS = if (moving && speedMps > 0.5f) cur.movingS + 1 else if (moving) cur.movingS else 0,
            elevGainM = if (moving) track.elevGainM else 0.0,
            elevLossM = if (moving) track.elevLossM else 0.0,
            tripId = tripId,
            overLimit = overLimit,
            speedLimitKmh = speedLimitKmh,
            odoKm = odoBaseKm + liveTripKm() * `in`.odograph.tracker.core.Odometer.factor(settings),
            lat = fix.lat,
            lon = fix.lon
        )
    }

    private fun record(fix: Fix) {
        if (restoring) return
        val dao = OdographDb.get(this).dao()

        if (tripId == NO_TRIP) {
            // No trip yet, so nothing to write these against. They are held instead, and the
            // window is rebuilt into the track each time so a car standing still under GPS jitter
            // accumulates at most a minute of wander rather than a whole night of it.
            pendingFixes.addLast(fix)
            while (pendingFixes.size > MAX_PENDING_FIXES) pendingFixes.removeFirst()
            track = LiveTrack().also { t -> pendingFixes.forEach(t::add) }

            if (departed()) {
                openTripFromPending(dao)
            } else {
                // The instrument still works while parked; it just is not recording a drive.
                publishLiveState(fix, track.speedMps, moving = false)
                lastFix = fix
                return
            }
        } else {
            // Anchor-based distance and derived speed, shared with the stored-trip maths so the
            // live readout and the saved totals cannot disagree. The network provider supplies no
            // speed at all, so without derivation the gauge sits at zero for an entire drive.
            track.add(fix)
        }

        dao.appendPoint(
            PointEntity(
                tripId = tripId, t = fix.t, lat = fix.lat, lon = fix.lon,
                speedMps = fix.speedMps, bearingDeg = null, altitudeM = fix.altitudeM,
                accuracyM = fix.accuracyM, interpolated = fix.interpolated
            )
        )

        val effectiveSpeedMps = track.speedMps

        val speedKmh = effectiveSpeedMps * 3.6f

        // A moving car cannot be charging. The plug is unavoidably out the moment a real drive
        // begins, so end any open charge on confirmed motion even if the MG "goodbye" frame never
        // arrives (network down, API stale). A single GPS blip must not end a charge, hence the
        // streak confirm; a fresh movement streak after parking re-arms the guard for the next drive.
        if (effectiveSpeedMps >= DRIVE_START_SPEED_MPS) {
            drivingFixStreak++
            if (drivingFixStreak >= DRIVE_START_CONFIRM_FIXES && !driveEndedCharge) {
                driveEndedCharge = true
                endChargeForDriveStart(dao, fix.t)
            }
        } else {
            drivingFixStreak = 0
            driveEndedCharge = false
        }

        if (effectiveSpeedMps >= Arrival.STILL_SPEED_MPS) lastMovedAt = fix.t

        // A drive that has arrived is written now rather than at the next boot, so an outing with
        // a stop in the middle is two trips rather than one that begins and ends at home.
        if (tripId != NO_TRIP && Arrival.arrived(Arrival.stillForMs(lastMovedAt, fix.t), carState)) {
            closeTripOnArrival(dao)
            publishLiveState(fix, effectiveSpeedMps, moving = false)
            lastFix = fix
            return
        }
        val limit = settings.speedLimitKmh
        if (limit != configuredLimit) {
            configuredLimit = limit
            speedAlert.reconfigure(AlertConfig(limitKmh = limit.toFloat()))
        }
        val alert = speedAlert.update(speedKmh, fix.t)
        if (alert.sound) runCatching { alertSound.play(settings.alertMode) }

        publishLiveState(
            fix, effectiveSpeedMps, moving = true,
            overLimit = alert.overLimit, speedLimitKmh = limit
        )
        lastFix = fix
    }

    /**
     * Routes a ChargeLedger outcome to wherever the driver should see it: a fresh fast session
     * prompts for a price at close, a fast session that closed unpriced leaves a durable reminder
     * so the ask survives the car door, and a slow charge never interrupts. Shared by the
     * telematics frame path and the drive-start close so both end a session identically.
     */
    private fun applyChargeChange(dao: `in`.odograph.tracker.data.OdographDao, change: ChargeLedger.Change) {
        when (change) {
            is ChargeLedger.Change.Opened -> {
                val e = change.event
                // Every session earns a charge location: the driveway it was plugged into,
                // whether that is a labelled home or a nameless public spot. Resolved at
                // session start so the Charging screen can group fills by where they
                // happened and report the per-location kWh.
                lastFix?.let { fix ->
                    if (fix.lat.isFinite() && fix.lon.isFinite()) {
                        val placeId = PlaceResolver(dao).resolve(fix.lat, fix.lon)
                        dao.setChargePlace(e.id, placeId, fix.lat, fix.lon)
                    }
                }
                val fastLooking = (e.peakPowerKw ?: 0.0) >= `in`.odograph.tracker.core.BatteryMath.FAST_CHARGE_KW
                if (fastLooking) {
                    _state.update {
                        if (it.pendingChargePrompt == null) {
                            it.copy(pendingChargePrompt = ChargePrompt(e.id, 0.0, isOpen = true))
                        } else it
                    }
                }
            }
            is ChargeLedger.Change.Closed -> {
                val e = change.event
                if (e.kind == `in`.odograph.tracker.core.BatteryMath.ChargeKind.FAST.ordinal) {
                    // Never silence the ask twice: a fast session that closes unpriced gets
                    // a durable reminder row the moment it closes, so a charge made after
                    // locking the car can surface at the next drive instead of vanishing.
                    if (e.enteredRateInr == null && e.enteredBillInr == null) {
                        dao.upsertReminder(
                            PriceReminderEntity(
                                eventId = e.id, raisedAt = System.currentTimeMillis()
                            )
                        )
                    }
                    _state.update {
                        if (it.pendingChargePrompt == null) {
                            it.copy(
                                pendingChargePrompt = ChargePrompt(
                                    e.id, e.energyKwh, isOpen = false, currentCostInr = e.costInr
                                )
                            )
                        } else it
                    }
                }
            }
            ChargeLedger.Change.None -> {}
        }
    }

    /**
     * Closes an open charge session because the car demonstrably started driving. A moving car is
     * physically not plugged in, so motion is as authoritative an end as the telematics "goodbye"
     * frame — and works when the MG link is down or the frame stale. It books energy from the last
     * SOC the car reported and prices the session normally, so nothing is lost except the "in
     * progress" limbo.
     */
    private fun endChargeForDriveStart(dao: `in`.odograph.tracker.data.OdographDao, at: Long) {
        val capacity = settings.batteryCapacityKwh
        val change = ChargeLedger(dao, capacity, settings.homeRateInr, settings.outsideRateInr)
            .endByDriving(at)
        applyChargeChange(dao, change)
    }

    private fun startInForeground() {
        val n = buildNotification()
        // The 3-arg overload only exists from API 29; minSdk is 26.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    /**
     * True when the device currently has an internet link the OS has validated — the exact test
     * for "is the hotspot actually up". A network that is up but unvalidated (e.g. a captive
     * portal) would burn a login attempt and could block the account, so it counts as down.
     */
    private fun hasValidatedNetwork(): Boolean = runCatching {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    /**
     * One heads-up per sustained outage that the MG link is down, never a bark every poll. The
     * driver's reminder: the car is fine, the hotspot just needs reconnecting. Deliberately
     * silent when credentials are missing or telemetry is switched off — that is a choice, not
     * an outage, and the corner pill already shows it.
     */
    private fun notifyMgLost() {
        val now = System.currentTimeMillis()
        if (now - lastMgLostNotifyAt < MG_LOST_NOTIFY_GAP_MS) return
        lastMgLostNotifyAt = now
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(
                MG_ALERT_NOTIFICATION_ID,
                Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Odograph")
                    .setContentText("MG link is down — reconnect the hotspot")
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    /**
     * Adopts the car's own dash odometer (reported over MG telematics) as the authoritative source.
     * The 18,000 km a car carried before the app started counting is a baseline offset — it must not
     * be distributed across the recorded trips, which are real travelled kilometres. So instead of
     * calibrating (which would rescale a window of trips), this snapshots the anchor: the car's dash
     * number plus however much the app has measured since that quote. The base shifts once, live;
     * trips are untouched.
     */
    /**
     * The open drive's distance, as the odometer readout counts it.
     *
     * One definition, used by both the anchor and the screen. They used to have their own: the
     * anchor read the live state's distance, which is zero whenever no trip is open, while the
     * screen added the track's. A telematics frame landing in that window anchored as though the
     * drive had not happened, and the screen then added it a second time — the odometer ran ahead
     * of the dash by exactly the distance of the trip in progress.
     */
    private fun liveTripKm(): Double =
        if (tripId == NO_TRIP) 0.0 else track.distanceM / 1000.0

    private fun adoptMgOdometerAnchor(carOdoKm: Double?, dao: `in`.odograph.tracker.data.OdographDao) {
        if (carOdoKm == null || carOdoKm <= 0.0) return
        val tripKm = liveTripKm()
        val measuredNow = dao.trackedDistanceM() / 1000.0 + tripKm
        if (!`in`.odograph.tracker.core.Odometer.adoptCarOdo(settings, carOdoKm, measuredNow)) return
        // Run the screen's own arithmetic backwards, so the reading it produces is the car's dash.
        val factor = `in`.odograph.tracker.core.Odometer.factor(settings)
        odoBaseKm = `in`.odograph.tracker.core.Odometer.baseForCarOdo(carOdoKm, tripKm, factor)
        _state.update { it.copy(odoKm = odoBaseKm + tripKm * factor) }
    }

    /**
     * Compares the car's own odometer against the app's computed one and surfaces the result.
     *
     * The car is ground truth: its dash reading ticks up with real wheel revolutions, while ours
     * is a GNSS count-up from a seeded 20,000 km baseline. A small |drift| is normal — GPS
     * distance error lives in the low single digits of kilometres. A persistent mismatch past
     * [ODO_DRIFT_FLAG_KM] is worth a once-a-day heads-up (never a bark on every poll), and the
     * setup page offers a one-tap recalibrate that snaps our baseline to the car's number.
     */
    private fun checkOdoDrift(carOdoKm: Double?, settings: Settings) {
        if (carOdoKm == null || carOdoKm <= 0.0) return
        val ours = _state.value.odoKm ?: return
        val drift = carOdoKm - ours
        _state.update { it.copy(odoDriftKm = drift) }
        if (kotlin.math.abs(drift) >= ODO_DRIFT_FLAG_KM) {
            val now = System.currentTimeMillis()
            if (now - settings.odoDriftNaggedAt < 24 * 60 * 60_000L) return
            settings.odoDriftNaggedAt = now
            runCatching {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(
                    ODO_ALERT_NOTIFICATION_ID,
                    Notification.Builder(this, CHANNEL_ID)
                        .setContentTitle("Odograph")
                        .setContentText(
                            "Odometer drift %+.1f km vs the car — recalibrate in Settings".format(drift)
                        )
                        .setSmallIcon(android.R.drawable.ic_menu_compass)
                        .setAutoCancel(true)
                        .build()
                )
            }
        }
    }

    private fun clearMgLostNotification() {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(MG_ALERT_NOTIFICATION_ID)
        }
    }

    /**
     * Nags the driver to type in the current dash reading when a long stretch of tracking has
     * gone without one. The odometer window grows uncalibrated in the meantime, so a single
     * prompt a day is the difference between a tiny correction and a big one.
     */
    private fun checkOdoRecordDue(dao: `in`.odograph.tracker.data.OdographDao?, settings: Settings) {
        if (dao == null) return
        // The car reports its own dash read over telematics, so once it has been quoted the
        // odometer self-corrects and there is nothing left for the driver to type in.
        if (`in`.odograph.tracker.core.Odometer.anchored(settings)) return
        val freshKm = `in`.odograph.tracker.core.Odometer.recordDueAt(
            settings, dao.trackedDistanceM() / 1000.0
        ) ?: return
        val now = System.currentTimeMillis()
        if (now - settings.odoRecordDueNaggedAt < 24 * 60 * 60_000L) return
        settings.odoRecordDueNaggedAt = now
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(
                ODO_RECORD_NOTIFICATION_ID,
                Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Odograph")
                    .setContentText(
                        "%.0f km since the last odometer reading — record it in Settings".format(freshKm)
                    )
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Odograph")
            .setContentText("Recording this drive")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { DashboardServer.stop() }
        if (this::alertSound.isInitialized) runCatching { alertSound.release() }
        if (this::source.isInitialized) source.stop()
        io.cancel()
        super.onDestroy()
    }
}
