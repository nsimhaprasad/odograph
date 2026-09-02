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
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.export.Exporters
import `in`.odograph.tracker.sync.Outbound
import `in`.odograph.tracker.ui.theme.Settings

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
                        call.respondText(
                            DashboardHtml.render(
                                trips, routes, settings.webhookUrl.isNotBlank(), dao.monthlyTotals()
                            ),
                            ContentType.Text.Html
                        )
                    }
                    get("/archive.html") {
                        val dao = OdographDb.get(app).dao()
                        val trips = dao.allTrips()
                        val routes = trips.associate { it.id to dao.pointsFor(it.id) }
                        call.respondText(
                            DashboardHtml.render(
                                trips, routes, settings.webhookUrl.isNotBlank(), dao.monthlyTotals()
                            ),
                            ContentType.Text.Html
                        )
                    }
                    get("/trips.csv") {
                        call.respondText(
                            Exporters.tripsCsv(OdographDb.get(app).dao().allTrips()),
                            ContentType.Text.CSV
                        )
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
                            DashboardHtml.configPage(settings.webhookUrl, settings.deviceId),
                            ContentType.Text.Html
                        )
                    }
                    post("/config") {
                        val params = call.receiveParameters()
                        params["webhook"]?.let { settings.webhookUrl = it }
                        params["device"]?.let { if (it.isNotBlank()) settings.deviceId = it }
                        call.respondText(
                            DashboardHtml.configPage(
                                settings.webhookUrl, settings.deviceId, "Saved."
                            ),
                            ContentType.Text.Html
                        )
                    }
                    get("/sync") {
                        val r = Outbound.syncPending(app, settings.webhookUrl, settings.deviceId)
                        val msg = when {
                            r.error != null -> "Sync failed: ${r.error}"
                            r.attempted == 0 -> "Nothing to send."
                            else -> "Delivered ${r.delivered} of ${r.attempted} drives."
                        }
                        call.respondText(
                            DashboardHtml.configPage(settings.webhookUrl, settings.deviceId, msg),
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
}
