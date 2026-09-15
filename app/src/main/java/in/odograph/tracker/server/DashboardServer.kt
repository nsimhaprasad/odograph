package `in`.odograph.tracker.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import `in`.odograph.tracker.core.BatteryMath
import `in`.odograph.tracker.data.OdographDao
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.data.TripEnergy
import `in`.odograph.tracker.export.Exporters
import `in`.odograph.tracker.sync.Outbound
import `in`.odograph.tracker.sync.SheetsSync
import `in`.odograph.tracker.ui.theme.Settings
import io.windsor.telematics.TelematicsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Serves the analysis dashboard on the local network.
 *
 * The car screen gets a glanceable instrument; a browser on a laptop gets density, charts and a
 * keyboard. Splitting them lets each be good at one thing over one shared database, and it means
 * pasting a secret URL happens on a real keyboard rather than a car touchscreen.
 */
object DashboardServer {

    const val PORT = 8080
    private const val TAG = "OdographServer"

    private var engine: ApplicationEngine? = null
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    fun start(ctx: Context) {
        if (engine != null) return
        val app = ctx.applicationContext
        val settings = Settings(app)

        engine = runCatching {
            embeddedServer(CIO, port = PORT) {
                routing {
                    get("/") {
                        val dao = OdographDb.get(app).dao()
                        val trips = dao.allTrips()
                        val routes = trips.associate { it.id to dao.pointsFor(it.id) }
                        val live = `in`.odograph.tracker.record.TripRecorderService.state.value
                        call.respondText(
                            DashboardHtml.render(
                                trips, routes,
                                webhookConfigured = settings.webhookUrl.isNotBlank(),
                                months = dao.monthlyTotals(),
                                chargeEvents = dao.allChargeEvents(),
                                telemetryDays = dao.allTelemetryDays(),
                                capacityKwh = settings.batteryCapacityKwh,
                                homeRateInr = settings.homeRateInr,
                                outsideRateInr = settings.outsideRateInr,
                                socPercent = live.batterySocPercent,
                                liveRangeKm = live.batteryRangeAtFullKm
                            ),
                            ContentType.Text.Html
                        )
                    }
                    get("/archive.html") {
                        val dao = OdographDb.get(app).dao()
                        val trips = dao.allTrips()
                        val routes = trips.associate { it.id to dao.pointsFor(it.id) }
                        val live = `in`.odograph.tracker.record.TripRecorderService.state.value
                        call.respondText(
                            DashboardHtml.render(
                                trips, routes,
                                webhookConfigured = settings.webhookUrl.isNotBlank(),
                                months = dao.monthlyTotals(),
                                chargeEvents = dao.allChargeEvents(),
                                telemetryDays = dao.allTelemetryDays(),
                                capacityKwh = settings.batteryCapacityKwh,
                                homeRateInr = settings.homeRateInr,
                                outsideRateInr = settings.outsideRateInr,
                                socPercent = live.batterySocPercent,
                                liveRangeKm = live.batteryRangeAtFullKm
                            ),
                            ContentType.Text.Html
                        )
                    }
                    // Lets the device report be pulled over the network instead of read off a
                    // car screen, which is the only practical way to get it from a box with no ADB.
                    get("/probe") {
                        call.respondText(
                            `in`.odograph.tracker.probe.DeviceProbe.collect(app).asText() +
                                "\n--- live ---\n" +
                                `in`.odograph.tracker.record.TripRecorderService.state.value + "\n",
                            ContentType.Text.Plain
                        )
                    }
                    get("/frames") {
                        call.respondText(
                            DashboardHtml.framesPage(RawFrames.read(RawFrames.directory(app))),
                            ContentType.Text.Html
                        )
                    }
                    get("/trips.csv") {
                        exportResponse(call, settings) {
                            Exporters.tripsCsv(OdographDb.get(app).dao().allTrips())
                        }
                    }
                    // Raw machine-readable exports for a laptop on the same LAN. All gated by the
                    // SETUP → LAN DATA toggle; the HTML dashboard itself is never gated because it
                    // is the one surface a car touchscreen can read without another device.
                    get("/export") {
                        exportResponse(call, settings, ContentType.Text.Html) {
                            DashboardHtml.exportPage(
                                baseUrl = LanInfo.baseUrl(),
                                counts = listOf(
                                    "trips.csv" to "Every drive: SOC swing, energy, cost, elevation",
                                    "points.csv" to "Every GPS sample, one row per point",
                                    "charges.csv" to "Every plug-in session and its price",
                                    "telemetry.csv" to "Days the box was alive and polling"
                                )
                            )
                        }
                    }
                    get("/points.csv") {
                        exportResponse(call, settings) {
                            val dao = OdographDb.get(app).dao()
                            Exporters.pointsCsv(dao.allTrips().flatMap { dao.pointsFor(it.id) })
                        }
                    }
                    get("/charges.csv") {
                        exportResponse(call, settings) {
                            Exporters.chargesCsv(OdographDb.get(app).dao().allChargeEvents())
                        }
                    }
                    get("/telemetry.csv") {
                        exportResponse(call, settings) {
                            Exporters.telemetryCsv(OdographDb.get(app).dao().allTelemetryDays())
                        }
                    }
                    get("/places") {
                        val dao = OdographDb.get(app).dao()
                        call.respondText(
                            DashboardHtml.placesPage(dao.allPlaces(), dao.routeSummaries()),
                            ContentType.Text.Html
                        )
                    }
                    post("/places") {
                        val params = call.receiveParameters()
                        val id = params["id"]?.toLongOrNull()
                        val label = params["label"]?.trim()
                        val dao = OdographDb.get(app).dao()
                        if (id != null) dao.setPlaceLabel(id, label?.takeIf { it.isNotBlank() })
                        call.respondText(
                            DashboardHtml.placesPage(
                                dao.allPlaces(), dao.routeSummaries(), "Saved."
                            ),
                            ContentType.Text.Html
                        )
                    }
                    get("/config") {
                        call.respondText(
                            configPageView(settings),
                            ContentType.Text.Html
                        )
                    }
                    post("/config") {
                        val params = call.receiveParameters()
                        params["webhook"]?.let { settings.webhookUrl = it }
                        params["twice"]?.let { settings.docsSyncTwiceDaily = it == "2x" }
                        params["device"]?.let { if (it.isNotBlank()) settings.deviceId = it }
                        params["capacity"]?.toDoubleOrNull()?.let { settings.batteryCapacityKwh = it }
                        params["home_rate"]?.toDoubleOrNull()?.let { settings.homeRateInr = it }
                        params["out_rate"]?.toDoubleOrNull()?.let { settings.outsideRateInr = it }

                        val phone = params["tl_phone"]?.trim().orEmpty()
                        val password = params["tl_password"].orEmpty()
                        val vin = params["tl_vin"]?.trim().orEmpty()

                        // "Try my connection" logs into the real account before anything is kept;
                        // only a successful round-trip stores the credentials. "Save" (or a plain
                        // submit, matching the old behaviour) stores immediately without testing.
                        val wantTest = params["op"] == "test"
                        val message: Pair<String, Boolean>
                        if (wantTest) {
                            if (phone.isEmpty() || password.isEmpty()) {
                                message = "Enter the phone number and password before testing." to true
                            } else {
                                message = try {
                                    testTelematics(phone, password, vin) to false
                                } catch (e: Exception) {
                                    "Connection failed: ${e.message ?: e.javaClass.simpleName}" to true
                                }
                                if (!message.second) {
                                    settings.telematicsPhone = phone
                                    settings.telematicsPassword = password
                                    if (vin.isNotEmpty()) settings.telematicsVin = vin
                                }
                            }
                        } else {
                            if (phone.isNotEmpty()) settings.telematicsPhone = phone
                            if (password.isNotEmpty()) settings.telematicsPassword = password
                            if (vin.isNotEmpty()) settings.telematicsVin = vin
                            val sheetId = Outbound.docsSheetId(settings.webhookUrl)
                            message = if (sheetId != null) {
                                "Saved. This is a spreadsheet link ($sheetId) — it imports, but cannot " +
                                    "receive exports until the bundled Apps Script is deployed (Extensions → Apps " +
                                    "Script, paste tools/odograph_sheets_apps_script.js, Deploy → Web app)." to false
                            } else {
                                "Saved." to false
                            }
                        }
                        call.respondText(
                            configPageView(
                                settings, message.first, message.second
                            ),
                            ContentType.Text.Html
                        )
                    }
                    get("/sync") {
                        val r = SheetsSync.exportAll(app, settings.webhookUrl, settings.deviceId)
                        val msg = when {
                            r.error != null -> "Export failed: ${r.error}"
                            else -> "Exported ${r.delivered} of ${r.attempted} rows to the docs workbook."
                        }
                        call.respondText(configPageView(settings, msg), ContentType.Text.Html)
                    }
                    get("/import") {
                        val (msg, error) = SheetsSync.importControl(settings, settings.webhookUrl)
                        call.respondText(configPageView(settings, msg, error), ContentType.Text.Html)
                    }
                    get("/planner") {
                        val dao = OdographDb.get(app).dao()
                        val live = `in`.odograph.tracker.record.TripRecorderService.state.value
                        val (cityEff, longEff) = cityAndLongEfficiency(dao)
                        val lastPoll = dao.allTelemetryDays().maxOfOrNull { it.lastPollAt }
                        call.respondText(
                            DashboardHtml.plannerPage(
                                socPercent = live.batterySocPercent,
                                capacityKwh = settings.batteryCapacityKwh,
                                homeRateInr = settings.homeRateInr,
                                outsideRateInr = settings.outsideRateInr,
                                cityEfficiencyKwhPer100Km = cityEff,
                                longEfficiencyKwhPer100Km = longEff,
                                totalKwh = dao.totalEnergyKwh(),
                                lastPollAt = lastPoll
                            ),
                            ContentType.Text.Html
                        )
                    }
                }
            }.also { it.start(wait = false) }
        }.onFailure { Log.w(TAG, "dashboard server did not start", it) }.getOrNull()

        registerBonjour(app)
    }

    /** Lets a Mac find the box as odograph.local instead of hunting for a DHCP address. */
    private fun registerBonjour(ctx: Context) {
        runCatching {
            val manager = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = "Odograph"
                serviceType = "_http._tcp."
                port = PORT
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.i(TAG, "registered ${info.serviceName}")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                    Log.w(TAG, "registration failed: $code")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
            }
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsd = manager
            nsdListener = listener
        }.onFailure { Log.w(TAG, "bonjour unavailable", it) }
    }

    fun stop() {
        runCatching { nsdListener?.let { nsd?.unregisterService(it) } }
        nsdListener = null
        nsd = null
        engine?.stop(500, 1000)
        engine = null
    }

    /**
     * Mean real-world efficiency the box has measured, split by the city/long (50 km) line so
     * the planner can price a short errand differently from an outstation run. Neither bucket is
     * quoted until [BatteryMath.MIN_TRIPS_FOR_REAL_ESTIMATE] drives have filled it.
     */
    private fun cityAndLongEfficiency(dao: OdographDao): Pair<Double?, Double?> {
        fun mean(list: List<TripEnergy>): Double? {
            val effs = list.mapNotNull { BatteryMath.kwhPer100Km(it.energyKwh, it.distanceM) }
            return if (effs.size >= BatteryMath.MIN_TRIPS_FOR_REAL_ESTIMATE) {
                effs.sum() / effs.size
            } else {
                null
            }
        }
        val all = dao.tripEnergies()
        return mean(all.filter { it.distanceM <= BatteryMath.CITY_MAX_DISTANCE_M }) to
            mean(all.filter { it.distanceM > BatteryMath.CITY_MAX_DISTANCE_M })
    }

    private fun configPageView(settings: Settings, message: String? = null, error: Boolean = false) =
        DashboardHtml.configPage(
            settings.webhookUrl, settings.deviceId,
            settings.telematicsPhone, settings.telematicsPassword, settings.telematicsVin,
            message, error,
            batteryCapacityKwh = "%.2f".format(settings.batteryCapacityKwh),
            homeRateInr = "%.2f".format(settings.homeRateInr),
            outsideRateInr = "%.2f".format(settings.outsideRateInr),
            syncTwiceDaily = settings.docsSyncTwiceDaily,
            lastDocsSyncAt = settings.lastDocsSyncAt
        )

    /**
     * Serves the machine-readable exports while the SETUP → LAN DATA toggle is on, and an
     * explicit "off" otherwise rather than a confusing 404. Re-reads the setting on every request
     * so toggling applies immediately, no restart needed.
     */
    private suspend fun exportResponse(
        call: io.ktor.server.application.ApplicationCall,
        settings: Settings,
        contentType: io.ktor.http.ContentType = io.ktor.http.ContentType.Text.CSV,
        block: suspend () -> String
    ) {
        if (settings.lanExportEnabled) {
            call.respondText(block(), contentType)
        } else {
            call.respondText(
                "LAN export is off.\nTurn it on under SETUP → LAN DATA on the device.\n",
                ContentType.Text.Plain
            )
        }
    }

    /**
     * One live login + vehicle + status round-trip against the real MG servers, for the "Try my
     * connection" button. Throws on failure so the caller renders the error; a returned string
     * describes what was found.
     */
    private suspend fun testTelematics(phone: String, password: String, vin: String): String =
        withContext(Dispatchers.IO) {
            val client = TelematicsClient.create(
                phone, password, vin.takeIf { it.isNotBlank() }
            )
            client.login()
            val vehicles = client.vehicles()
            val status = client.status(includeCharge = true)
            buildString {
                append("Connected. Login and data both work.")
                vehicles.firstOrNull()?.let { v ->
                    append(" Found ")
                    append(v.name)
                    v.model?.let { append(" ").append(it) }
                    append(", VIN ").append(v.vin).append(".")
                }
                if (vehicles.size > 1) append(" ${vehicles.size} vehicles on the account.")
                status.charge?.let { ch ->
                    ch.soc?.let { soc ->
                        append(if (ch.isCharging) " Currently charging." else " Not charging now.")
                        append(" Battery ").append("%.0f".format(soc)).append("%")
                        append(", range ").append("%.0f".format(ch.rangeKm)).append(" km.")
                    } ?: append(" No charge reading yet.")
                } ?: append(" No live battery frame yet \u2014 the car may be off.")
                status.gps?.takeIf { g -> g.hasFix }?.let { g ->
                    append(" Car is at ")
                    append("%.4f".format(g.latitude)).append(", ").append("%.4f".format(g.longitude)).append(".")
                }
            }
        }
}
