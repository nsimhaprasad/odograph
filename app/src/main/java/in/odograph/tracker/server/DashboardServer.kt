package `in`.odograph.tracker.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
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
import `in`.odograph.tracker.record.TripRecorderService
import `in`.odograph.tracker.sync.Outbound
import `in`.odograph.tracker.sync.SheetsSync
import `in`.odograph.tracker.ui.theme.Settings
import io.windsor.telematics.TelematicsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
                        respondWeb(call, app, "index.html", ContentType.Text.Html)
                    }
                    get("/app.js") {
                        respondWeb(call, app, "app.js", ContentType.Application.JavaScript)
                    }
                    get("/style.css") {
                        respondWeb(call, app, "style.css", ContentType.Text.CSS)
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
                    get("/backup") {
                        if (settings.lanExportEnabled) {
                            val tmp = File(app.cacheDir, "odograph-backup.db")
                            OdographDb.snapshotTo(app, tmp)
                            call.respondFile(tmp)
                        } else {
                            call.respondText(
                                "LAN export is off.\nTurn it on under SETUP → LAN DATA.\n",
                                ContentType.Text.Plain
                            )
                        }
                    }
                    post("/restore") {
                        if (!settings.lanExportEnabled) {
                            call.respondText("LAN export is off.", ContentType.Text.Plain)
                        } else if (!`in`.odograph.tracker.record.TripRecorderService.pauseForRestore()) {
                            call.respondText(
                                "Restore refused — move the car away from the hotspot first.",
                                ContentType.Text.Plain
                            )
                        } else {
                            val body = run {
                            val ch = call.receiveChannel()
                            val out = java.io.ByteArrayOutputStream()
                            val buf = ByteArray(8 * 1024)
                            while (true) {
                                val n = ch.readAvailable(buf, 0, buf.size)
                                if (n == -1) break
                                out.write(buf, 0, n)
                            }
                            out.toByteArray()
                        }
                            val tmp = File(app.cacheDir, "uploaded-backup.db")
                            tmp.writeBytes(body)
                            val result = runCatching { OdographDb.replaceWith(app, tmp) }
                            `in`.odograph.tracker.record.TripRecorderService.resumeAfterRestore()
                            call.respondText(
                                if (result.isSuccess) "restored"
                                else "restore failed: ${result.exceptionOrNull()?.message}",
                                ContentType.Text.Plain
                            )
                        }
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
                    get("/api/live") {
                        api(call, settings) {
                            ApiJson.live(TripRecorderService.state.value)
                        }
                    }
                    get("/api/trips") {
                        api(call, settings) {
                            val dao = OdographDb.get(app).dao()
                            ApiJson.trips(dao.allTrips())
                        }
                    }
                    get("/api/charges") {
                        api(call, settings) {
                            ApiJson.charges(OdographDb.get(app).dao().allChargeEvents())
                        }
                    }
                    get("/api/places") {
                        api(call, settings) {
                            ApiJson.places(OdographDb.get(app).dao().allPlaces())
                        }
                    }
                    get("/api/routes") {
                        api(call, settings) {
                            val dao = OdographDb.get(app).dao()
                            val places = dao.allPlaces().associateBy { it.id }
                            ApiJson.routes(dao.routeTripsForEfficiency(), places)
                        }
                    }
                    get("/api/cost") {
                        api(call, settings) {
                            val bucket = call.request.queryParameters["bucket"] ?: "30d"
                            val now = System.currentTimeMillis()
                            val dao = OdographDb.get(app).dao()
                            val fromMs = when (bucket) {
                                "today" -> {
                                    val cal = java.util.Calendar.getInstance(settings.zone)
                                    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                                    cal.set(java.util.Calendar.MINUTE, 0)
                                    cal.set(java.util.Calendar.SECOND, 0)
                                    cal.set(java.util.Calendar.MILLISECOND, 0)
                                    cal.timeInMillis
                                }
                                "7d" -> now - 7 * 24 * 3_600_000L
                                else -> now - 30 * 24 * 3_600_000L
                            }
                            ApiJson.cost(
                                bucket,
                                dao.periodCost(fromMs),
                                dao.periodCharges(fromMs)
                            )
                        }
                    }
                    get("/api/range") {
                        api(call, settings) {
                            val from = System.currentTimeMillis() - 366 * 24 * 3_600_000L
                            ApiJson.range(OdographDb.get(app).dao().dailyEfficiency(from))
                        }
                    }
                    get("/api/drain") {
                        api(call, settings) {
                            ApiJson.drain(OdographDb.get(app).dao().parkedBatteryFrames())
                        }
                    }
                    get("/api/telemetry") {
                        api(call, settings) {
                            ApiJson.telemetry(OdographDb.get(app).dao().allTelemetryDays())
                        }
                    }
                    get("/config") {
                        call.respondText(
                            configPageView(settings, dao = OdographDb.get(app).dao()),
                            ContentType.Text.Html
                        )
                    }
                    post("/config") {
                        val params = call.receiveParameters()
                        params["webhook"]?.let { settings.webhookUrl = it }
                        params["hours"]?.toIntOrNull()?.let { settings.docsSyncHours = it }
                        params["device"]?.let { if (it.isNotBlank()) settings.deviceId = it }
                        params["capacity"]?.toDoubleOrNull()?.let { settings.batteryCapacityKwh = it }
                        params["home_rate"]?.toDoubleOrNull()?.let { settings.homeRateInr = it }
                        params["out_rate"]?.toDoubleOrNull()?.let { settings.outsideRateInr = it }
                        params["current_odo"]?.toDoubleOrNull()?.let { current ->
                            // Calibrate: with meaningful tracking this re-solves the factor, so
                            // the drift is shared across every trip proportionally rather than
                            // rewriting any row or moving the 20,000 km seed.
                            val dao = OdographDb.get(app).dao()
                            `in`.odograph.tracker.core.Odometer
                                .calibrate(settings, current, dao.trackedDistanceM() / 1000.0)
                        }

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
                        val r = SheetsSync.exportDocs(app, settings.webhookUrl, settings.deviceId)
                        val msg = when {
                            r.error != null -> "Export failed: ${r.error}"
                            r.attempted == 0 -> "Nothing new since the last export."
                            else -> "Uploaded ${r.delivered} of ${r.attempted} new rows to the docs workbook."
                        }
                        call.respondText(configPageView(settings, msg, dao = OdographDb.get(app).dao()), ContentType.Text.Html)
                    }
                    get("/import") {
                        val (msg, error) = SheetsSync.importControl(settings, settings.webhookUrl)
                        call.respondText(configPageView(settings, msg, error, OdographDb.get(app).dao()), ContentType.Text.Html)
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

    private fun configPageView(
        settings: Settings,
        message: String? = null,
        error: Boolean = false,
        dao: OdographDao? = null
    ): String {
        val trackedKm = dao?.trackedDistanceM()?.div(1000.0) ?: 0.0
        return DashboardHtml.configPage(
            settings.webhookUrl, settings.deviceId,
            settings.telematicsPhone, settings.telematicsPassword, settings.telematicsVin,
            message, error,
            batteryCapacityKwh = "%.2f".format(settings.batteryCapacityKwh),
            homeRateInr = "%.2f".format(settings.homeRateInr),
            outsideRateInr = "%.2f".format(settings.outsideRateInr),
            docsSyncHours = settings.docsSyncHours,
            lastDocsSyncAt = settings.lastDocsSyncAt,
            odoCurrentKm = `in`.odograph.tracker.core.Odometer.appOdoKm(settings, trackedKm),
            odoBaselineKm = settings.odoBaselineKm,
            odoCalibratedAt = settings.odoCalibratedAt
        )
    }

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

    /** Serves the SPA assets, which live in the APK's assets folder. */
    private suspend fun respondWeb(
        call: io.ktor.server.application.ApplicationCall,
        ctx: Context,
        asset: String,
        contentType: ContentType
    ) {
        val bytes = runCatching { ctx.assets.open("web/$asset").use { it.readBytes() } }.getOrNull()
        if (bytes != null) {
            call.respondBytes(bytes, contentType)
        } else {
            call.respond(HttpStatusCode.NotFound, "not found")
        }
    }

    /**
     * Gated JSON for the REST API. Same LAN-only rule as [exportResponse]: the toggle is re-read
     * per call, so enabling/disabling applies immediately.
     */
    private suspend fun api(
        call: io.ktor.server.application.ApplicationCall,
        settings: Settings,
        block: suspend () -> String
    ) {
        if (settings.lanExportEnabled) {
            call.respondText(block(), ContentType.Application.Json)
        } else {
            call.respondText(
                "LAN export is off.\nTurn it on under SETUP → LAN DATA.\n",
                ContentType.Text.Plain
            )
        }
    }

    /**
     * One live login + vehicle + status round-trip against the real MG servers, for the "Try my
     * connection" button. Throws on failure so the caller renders the error; a returned string
     * describes what was found. Public so the on-device setup screen can offer the same test.
     */
    suspend fun testTelematics(phone: String, password: String, vin: String): String =
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
