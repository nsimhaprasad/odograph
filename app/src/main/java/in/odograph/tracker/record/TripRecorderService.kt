package `in`.odograph.tracker.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import `in`.odograph.tracker.alert.AlertConfig
import `in`.odograph.tracker.alert.AlertSound
import `in`.odograph.tracker.alert.SpeedAlert
import `in`.odograph.tracker.core.Fix
import `in`.odograph.tracker.core.Geo
import `in`.odograph.tracker.core.LiveTrack
import `in`.odograph.tracker.core.BatteryMath
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.data.BatteryEntity
import `in`.odograph.tracker.data.PointEntity
import `in`.odograph.tracker.diag.Diagnostics
import `in`.odograph.tracker.geocode.PlaceNamer
import `in`.odograph.tracker.server.DashboardServer
import `in`.odograph.tracker.server.RawFrames
import `in`.odograph.tracker.sync.Outbound
import `in`.odograph.tracker.ui.theme.Settings
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
         * A fast charge the driver should price, surfaced to the UI as a prompt. Set from the
         * poller when a fast session opens (pre-price) or closes (correct with the real bill);
         * the dialog that shows it clears it. A slow session never prompts — the home rate stands.
         */
        val pendingChargePrompt: ChargePrompt? = null
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

        /** The dialog that shows a charge prompt clears it once handled. */
        fun clearChargePrompt() {
            _state.update { it.copy(pendingChargePrompt = null) }
        }
    }

    private lateinit var source: LocationSource
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tripId: Long = -1
    private var lastFix: Fix? = null
    private var startedAt: Long? = null
    private var track = LiveTrack()
    private lateinit var settings: Settings
    private lateinit var alertSound: AlertSound
    private var speedAlert = SpeedAlert(AlertConfig(limitKmh = 0f))
    private var configuredLimit = -1

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
            tripId = TripRecovery.recoverAndStart(
                dao,
                nowFromGnss = null,
                capacityKwh = settings.batteryCapacityKwh,
                homeRateInr = settings.homeRateInr,
                outsideRateInr = settings.outsideRateInr
            )
            Diagnostics.crumb("recovery done trip=$tripId")
            _state.value = LiveState(tripId = tripId)
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

            runCatching {
                val settings = Settings(this@TripRecorderService)
                if (settings.webhookUrl.isNotBlank()) {
                    Outbound.syncPending(
                        this@TripRecorderService, settings.webhookUrl, settings.deviceId
                    )
                }
            }

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
                _state.value = _state.value.copy(batterySocPercent = null, batteryCharging = null, telematicsConnected = null)
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
                val powerKw = if (ch != null)
                    ch.chargingVoltage * 0.25 * (ch.chargingCurrent - 1000) * 0.05 / 1000
                else 0.0
                // A snapshot without a SOC reading is not charge data — it is noise that would
                // make a battery-less trip look instrumented. Only rows from a real charging
                // frame, on a trip that actually exists, are stored.
                if (ch != null && ch.soc != null && tripId >= 0) {
                    val t = lastFix?.t ?: now
                    dao.insertBattery(
                        BatteryEntity(
                            tripId = tripId,
                            t = t,
                            socPercent = ch.soc,
                            charging = ch.isCharging,
                            rangeKm = ch.rangeKm,
                            chargingPowerKw = powerKw,
                            workingVoltage = ch.workingVoltage?.let { it * 0.25 },
                            workingCurrent = ch.workingCurrent?.let { (it - 1000) * 0.05 }
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
                when (change) {
                    is ChargeLedger.Change.Opened -> {
                        val e = change.event
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

                // Coverage honesty: note that the poller ran at all, so the dashboard can show
                // the days it did not.
                recordDailyCoverage(dao, settings.zone, now)

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
                            ch.rangeKm?.let { r -> if (soc > 0) r / soc * 100.0 else null }
                        }
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
                            batteryTotalKwh = dao.totalEnergyKwh(),
                            tripEnergyKwh = energy,
                            tripCostInr = tripCost
                        )
                    } else {
                        _state.value = _state.value.copy(
                            batterySocPercent = soc,
                            batteryCharging = ch.isCharging,
                            tripEnergyKwh = null,
                            tripCostInr = null
                        )
                    }
                } else {
                    _state.value = _state.value.copy(batterySocPercent = null, batteryCharging = null)
                }

                // Whatever the frame carried, a round-trip that returned without throwing means
                // the MG link is up — the corner indicator can go green.
                _state.update { it.copy(telematicsConnected = true) }
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
    }

    private fun record(fix: Fix) {
        val dao = OdographDb.get(this).dao()
        dao.appendPoint(
            PointEntity(
                tripId = tripId, t = fix.t, lat = fix.lat, lon = fix.lon,
                speedMps = fix.speedMps, bearingDeg = null, altitudeM = fix.altitudeM,
                accuracyM = fix.accuracyM, interpolated = fix.interpolated
            )
        )

        if (startedAt == null) {
            // First real fix: the trip row was created with a placeholder time because the
            // system clock is untrustworthy without NTP. Correct it now.
            startedAt = fix.t
            dao.setStartedAt(tripId, fix.t)
            if (dao.tripById(tripId)?.startLat == null) dao.setOrigin(tripId, fix.lat, fix.lon)
        }

        // Anchor-based distance and derived speed, shared with the stored-trip maths so the live
        // readout and the saved totals cannot disagree. The network provider supplies no speed at
        // all, so without derivation the gauge sits at zero for an entire drive.
        track.add(fix)
        val effectiveSpeedMps = track.speedMps

        val speedKmh = effectiveSpeedMps * 3.6f
        val limit = settings.speedLimitKmh
        if (limit != configuredLimit) {
            configuredLimit = limit
            speedAlert.reconfigure(AlertConfig(limitKmh = limit.toFloat()))
        }
        val alert = speedAlert.update(speedKmh, fix.t)
        if (alert.sound) runCatching { alertSound.play(settings.alertMode) }

        val cur = _state.value
        _state.value = cur.copy(
            hasFix = true,
            speedMps = effectiveSpeedMps,
            distanceM = track.distanceM,
            elapsedS = (fix.t - (startedAt ?: fix.t)) / 1000,
            maxSpeedMps = maxOf(cur.maxSpeedMps, effectiveSpeedMps),
            movingS = cur.movingS + if (effectiveSpeedMps > 0.5f) 1 else 0,
            elevGainM = track.elevGainM,
            elevLossM = track.elevLossM,
            tripId = tripId,
            overLimit = alert.overLimit,
            speedLimitKmh = limit
        )
        lastFix = fix
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
