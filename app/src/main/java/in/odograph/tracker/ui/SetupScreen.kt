package `in`.odograph.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.alert.AlertMode
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.export.Exporters
import `in`.odograph.tracker.export.shareFile
import `in`.odograph.tracker.export.writeExport
import `in`.odograph.tracker.probe.DeviceProbe
import `in`.odograph.tracker.record.TripRecorderService
import `in`.odograph.tracker.server.DashboardServer
import `in`.odograph.tracker.server.LanInfo
import `in`.odograph.tracker.sync.SheetsSync
import `in`.odograph.tracker.ui.theme.Direction
import `in`.odograph.tracker.ui.theme.Palette
import `in`.odograph.tracker.ui.theme.Settings
import `in`.odograph.tracker.ui.theme.ThemeMode
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(
    direction: Direction,
    themeMode: ThemeMode,
    showTiles: Boolean,
    telematics: Boolean,
    palette: Palette,
    onDirection: (Direction) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onTiles: (Boolean) -> Unit,
    onTelematics: (Boolean) -> Unit
) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    var probe by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf(settings.speedLimitKmh) }
    var zoneId by remember { mutableStateOf(settings.timeZoneId) }
    var alertMode by remember { mutableStateOf(settings.alertMode) }
    var lanExport by remember { mutableStateOf(settings.lanExportEnabled) }

    // The fields /config once held on a laptop; now typed on this screen, still one device.
    var webhook by remember { mutableStateOf(settings.webhookUrl) }
    var docsHours by remember { mutableStateOf(settings.docsSyncHours) }
    var deviceName by remember { mutableStateOf(settings.deviceId) }
    var mgPhone by remember { mutableStateOf(settings.telematicsPhone) }
    var mgPassword by remember { mutableStateOf(settings.telematicsPassword) }
    var mgVin by remember { mutableStateOf(settings.telematicsVin) }
    var capacity by remember { mutableStateOf("%.2f".format(settings.batteryCapacityKwh)) }
    var homeRate by remember { mutableStateOf("%.2f".format(settings.homeRateInr)) }
    var outsideRate by remember { mutableStateOf("%.2f".format(settings.outsideRateInr)) }
    var odoReading by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val dao = remember { OdographDb.get(ctx).dao() }

    // The odometer stats come from Room, which is off-limits on the main thread (the box has no
    // allowMainThreadQueries). Load them once on IO; the ODOMETER section recomputes its derived
    // numbers from these states whenever they arrive.
    var trackedKm by remember { mutableStateOf(0.0) }
    var carOdoKm by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            trackedKm = dao.trackedDistanceM() / 1000.0
            carOdoKm = dao.latestCarOdoKm()
        }
    }
    val currentOdoKm = remember(trackedKm) { `in`.odograph.tracker.core.Odometer.liveOdoKm(settings, trackedKm) }
    val driftKm = remember(carOdoKm, currentOdoKm) {
        carOdoKm?.let { `in`.odograph.tracker.core.Odometer.drift(it, currentOdoKm) }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        Thread {
            runCatching {
                val input = ctx.contentResolver.openInputStream(uri) ?: return@Thread
                val tmp = File(ctx.cacheDir, "uploaded-backup.db")
                tmp.outputStream().use { out -> input.copyTo(out) }
                input.close()
                if (!TripRecorderService.pauseForRestore()) {
                    note = "Restore refused — a moving trip is open. Try again after parking."
                    return@Thread
                }
                try {
                    OdographDb.replaceWith(ctx, tmp)
                    note = "Restored successfully."
                } catch (e: Exception) {
                    note = "Restore failed: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    TripRecorderService.resumeAfterRestore()
                }
            }.onFailure { note = "Restore failed: ${it.message}" }
        }.start()
    }

    LaunchedEffect(Unit) {
        probe = runCatching { DeviceProbe.collect(ctx).asText() }
            .getOrElse { "Probe unavailable: ${it.message}" }
    }

    var memory by remember { mutableStateOf(memorySnapshot(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            memory = memorySnapshot(ctx)
            delay(5_000)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(palette.ground)) {
        val m = rememberMetrics(maxWidth, maxHeight)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(m.pad),
            verticalArrangement = Arrangement.spacedBy(m.gap)
        ) {
            Section("CLUSTER", palette, m) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
                    Direction.entries.forEach { d ->
                        Chip(d.name, d == direction, palette, m) { onDirection(d) }
                    }
                }
            }

            Section("THEME", palette, m) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
                    ThemeMode.entries.forEach { t ->
                        Chip(t.name, t == themeMode, palette, m) { onThemeMode(t) }
                    }
                }
            }

            Section("SPEED ALERT", palette, m) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
                    listOf(0, 50, 60, 80, 100, 120).forEach { kmh ->
                        Chip(
                            text = if (kmh == 0) "OFF" else "$kmh",
                            selected = kmh == limit,
                            palette = palette,
                            m = m
                        ) { limit = kmh; settings.speedLimitKmh = kmh }
                    }
                }
                if (limit > 0) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(m.gap / 2),
                        modifier = Modifier.padding(top = m.gap / 2)
                    ) {
                        AlertMode.entries.forEach { mode ->
                            Chip(
                                text = when (mode) {
                                    AlertMode.VISUAL_ONLY -> "SILENT"
                                    AlertMode.CHIME -> "CHIME"
                                    AlertMode.VOICE -> "VOICE"
                                },
                                selected = mode == alertMode,
                                palette = palette,
                                m = m
                            ) { alertMode = mode; settings.alertMode = mode }
                        }
                    }
                }
                Text(
                    if (limit == 0) {
                        "Off. No overspeed warning of any kind."
                    } else {
                        "The gauge turns red the moment you pass $limit km/h. The sound waits " +
                            "3 seconds, so a brief overtake stays silent, and repeats at most " +
                            "once every 25 seconds."
                    },
                    color = palette.dim, fontSize = m.body,
                    modifier = Modifier.padding(top = m.gap / 2)
                )
            }

            Section("TIME ZONE", palette, m) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
                    listOf("" to "DEVICE", "Asia/Kolkata" to "IST", "UTC" to "UTC").forEach {
                        (id, label) ->
                        Chip(label, id == zoneId, palette, m) { zoneId = id; settings.timeZoneId = id }
                    }
                }
                Text(
                    "This box has no SIM, so it never receives a timezone from a mobile network " +
                        "and may sit at UTC no matter how accurate its clock is. Recorded times " +
                        "are always correct; this only changes how they are displayed. Currently " +
                        "showing ${settings.zone.id}.",
                    color = palette.dim, fontSize = m.body,
                    modifier = Modifier.padding(top = m.gap / 2)
                )
            }

            Section("MAP", palette, m) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
                    Chip("TILES", showTiles, palette, m) { onTiles(true) }
                    Chip("TRACE ONLY", !showTiles, palette, m) { onTiles(false) }
                }
                Text(
                    "Tiles download over your hotspot and cache permanently. Trace only never " +
                        "touches the network.",
                    color = palette.dim, fontSize = m.body,
                    modifier = Modifier.padding(top = m.gap / 2)
                )
            }

            Section("MG TELEMETRY", palette, m) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
                    Chip("ON", telematics, palette, m) { onTelematics(true) }
                    Chip("OFF", !telematics, palette, m) { onTelematics(false) }
                }
                Text(
                    if (telematics) {
                        "Live battery and charge readings from your MG server are fetched while " +
                            "this screen is up — gently, never faster than every 30 seconds."
                    } else {
                        "Off. No MG server calls at all, and the driving screen shows the " +
                            "speedometer only."
                    },
                    color = palette.dim, fontSize = m.body,
                    modifier = Modifier.padding(top = m.gap / 2)
                )
                // The old "typed at /config from your Mac" hint is gone: these fields live here,
                // on the same screen as the on/off switch, because the driver stopped visiting
                // the web page. All four are required for the poller to run; a blank phone
                // disables telemetry entirely even with the switch ON.
                SetupField(
                    "iSMART phone number", mgPhone,
                    { mgPhone = it }, palette, m,
                    placeholder = "10-digit mobile on the iSmart account",
                    keyboardType = KeyboardType.Phone
                )
                SetupField(
                    "iSMART password", mgPassword,
                    { mgPassword = it }, palette, m,
                    placeholder = "left blank keeps the saved password",
                    isPassword = true
                )
                SetupField(
                    "VIN (optional)", mgVin,
                    { mgVin = it }, palette, m,
                    placeholder = "blank uses the account's first vehicle"
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
                    Chip("SAVE", false, palette, m) {
                        settings.telematicsPhone = mgPhone
                        settings.telematicsPassword = mgPassword
                        settings.telematicsVin = mgVin
                        note = "MG credentials saved."
                    }
                    var testing by remember { mutableStateOf(false) }
                    Chip(if (testing) "TESTING…" else "TEST CONNECTION", testing, palette, m) {
                        if (!testing) {
                            testing = true
                            scope.launch {
                                note = try {
                                    DashboardServer.testTelematics(
                                        mgPhone, mgPassword, mgVin
                                    )
                                } catch (e: Exception) {
                                    "Connection failed: ${e.message ?: e.javaClass.simpleName}"
                                }
                                testing = false
                            }
                        }
                    }
                }
            }

            Section("ODOMETER", palette, m) {
                // The car's own dash reading comes over MG telematics and is ground truth. When it
                // has been quoted, the app anchors to it: the odometer shows that number plus
                // whatever the app has measured since. Recorded trips are never touched — a gap
                // between a car that already carried kilometres and the app's count is a baseline
                // offset, not a measurement error, so it is absorbed in this one number instead of
                // being spread across the trips.
                Text(
                    "App: %.0f km · car: %s km · drift: %s".format(
                        currentOdoKm,
                        carOdoKm?.let { "%.0f".format(it) } ?: "—",
                        driftKm?.let {
                            if (kotlin.math.abs(it) >= TripRecorderService.ODO_DRIFT_FLAG_KM) {
                                "%+.1f km (anchored to car)".format(it)
                            } else {
                                "%+.1f km (on track)".format(it)
                            }
                        } ?: "—"
                    ),
                    color = if (driftKm != null &&
                        kotlin.math.abs(driftKm) >= TripRecorderService.ODO_DRIFT_FLAG_KM
                    ) palette.warn else palette.dim,
                    fontSize = m.body,
                    modifier = Modifier.padding(top = m.gap / 2)
                )

                val fresh = remember(trackedKm) { `in`.odograph.tracker.core.Odometer.recordDueAt(settings, trackedKm) }
                if (fresh != null && !`in`.odograph.tracker.core.Odometer.anchored(settings)) {
                    Text(
                        "%.0f km travelled since your last odometer reading — the car reports its " +
                            "own dash read over telematics, so this self-corrects once connected.".format(fresh),
                        color = palette.warn,
                        fontSize = m.body,
                        modifier = Modifier.padding(top = m.gap / 2)
                    )
                }

                val carKm = carOdoKm
                if (carKm != null) {
                    val carPreview = remember(carKm, trackedKm) {
                        `in`.odograph.tracker.core.Odometer.preview(settings, carKm, trackedKm)
                    }
                    if (carPreview.suspect) {
                        Text(
                            "Warning: the car's %.0f km is %+.1f km off what the app measured — " +
                                "RECORD FROM CAR anchors the odometer to the dash and trips are " +
                                "never rewritten, so this is safe to apply.".format(carKm, carPreview.offByKm),
                            color = palette.warn, fontSize = m.body,
                            modifier = Modifier.padding(top = m.gap / 2)
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
                        Chip("RECORD FROM CAR", false, palette, m) {
                            scope.launch {
                                val msg = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val ok = `in`.odograph.tracker.core.Odometer
                                            .adoptCarOdo(settings, carKm, trackedKm)
                                        if (ok) {
                                            TripRecorderService.refreshCalibratedOdo(dao, settings)
                                            "Odometer anchored to the car's %.0f km — trips untouched.".format(carKm)
                                        } else {
                                            "Not recorded: the dashboard reading is ahead of its last known value."
                                        }
                                    }.getOrElse { "Calibration failed: ${it.message}" }
                                }
                                note = msg
                            }
                        }
                    }
                }
                SetupField(
                    "Current dash reading (km)", odoReading,
                    { odoReading = it }, palette, m,
                    placeholder = "type the dash number once, calibration happens automatically",
                    keyboardType = KeyboardType.Decimal
                )
                val manualPreview = odoReading.toDoubleOrNull()?.let {
                    remember(it, trackedKm) {
                        `in`.odograph.tracker.core.Odometer.preview(settings, it, trackedKm)
                    }
                }
                if (manualPreview?.suspect == true) {
                    Text(
                        "%s %+.1f km off what the app expected — it will anchor the odometer there " +
                            "rather than rescale trips. Double-check the number.".format(
                            odoReading, manualPreview.offByKm
                        ),
                        color = palette.warn, fontSize = m.body,
                        modifier = Modifier.padding(top = m.gap / 2)
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
                    Chip("SET ODOMETER", odoReading.isNotBlank(), palette, m) {
                        odoReading.toDoubleOrNull()?.let { reading ->
                            scope.launch {
                                val msg = withContext(Dispatchers.IO) {
                                    val ok = `in`.odograph.tracker.core.Odometer
                                        .adoptCarOdo(settings, reading, trackedKm)
                                    if (ok) {
                                        TripRecorderService.refreshCalibratedOdo(dao, settings)
                                        "Odometer set to %.0f km — trips untouched.".format(reading)
                                    } else {
                                        "Not recorded: that reading is behind the anchored odometer."
                                    }
                                }
                                note = msg
                            }
                        } ?: run { note = "Enter a number." }
                    }
                }
            }

            Section("POWER & RATES", palette, m) {
                SetupField(
                    "Battery, kWh", capacity,
                    { capacity = it }, palette, m,
                    placeholder = "usable capacity e.g. 52.9",
                    keyboardType = KeyboardType.Decimal
                )
                SetupField(
                    "Home rate, ₹/kWh", homeRate,
                    { homeRate = it }, palette, m,
                    placeholder = "slow charge rate e.g. 8",
                    keyboardType = KeyboardType.Decimal
                )
                SetupField(
                    "Fast charge rate, ₹/kWh", outsideRate,
                    { outsideRate = it }, palette, m,
                    placeholder = "fast charger rate e.g. 25",
                    keyboardType = KeyboardType.Decimal
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
                    Chip("SAVE", false, palette, m) {
                        capacity.toDoubleOrNull()?.let { settings.batteryCapacityKwh = it }
                        homeRate.toDoubleOrNull()?.let { settings.homeRateInr = it }
                        outsideRate.toDoubleOrNull()?.let { settings.outsideRateInr = it }
                        note = "Power settings saved."
                    }
                }
                Text(
                    "Capacity turns SOC into kW·h (default 52.9 for the Windsor). Under 10 kW is " +
                        "a slow/home charge at the home rate; 10 kW and up is fast at the outside " +
                        "rate. Blank fields keep the current values.",
                    color = palette.dim, fontSize = m.body,
                    modifier = Modifier.padding(top = m.gap / 2)
                )
            }

            Section("CLOUD SYNC", palette, m) {
                SetupField(
                    "Google Docs link / webhook", webhook,
                    { webhook = it }, palette, m,
                    placeholder = "spreadsheet link or its /exec URL"
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
                    listOf(1 to "HOURLY", 12 to "12 H", 24 to "DAILY").forEach { (h, label) ->
                        Chip(label, h == docsHours, palette, m) { docsHours = h }
                    }
                }
                SetupField(
                    "Device name", deviceName,
                    { deviceName = it }, palette, m,
                    placeholder = "how this box signs its uploads e.g. windsor"
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
                    var syncing by remember { mutableStateOf(false) }
                    Chip("SAVE", false, palette, m) {
                        settings.webhookUrl = webhook
                        settings.docsSyncHours = docsHours
                        if (deviceName.isNotBlank()) settings.deviceId = deviceName.trim()
                        note = "Cloud sync settings saved."
                    }
                    var importing by remember { mutableStateOf(false) }
                    Chip(if (syncing) "SYNCING…" else "EXPORT NOW", syncing, palette, m) {
                        if (!syncing) {
                            syncing = true
                            scope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    SheetsSync.exportDocs(ctx, settings.webhookUrl, settings.deviceId)
                                }
                                note = when {
                                    r.error != null -> "Export failed: ${r.error}"
                                    r.attempted == 0 -> "Nothing new since the last export."
                                    else -> "Uploaded ${r.delivered} of ${r.attempted} new rows."
                                }
                                syncing = false
                            }
                        }
                    }
                    Chip(if (importing) "IMPORTING…" else "IMPORT NOW", importing, palette, m) {
                        if (!importing) {
                            importing = true
                            scope.launch {
                                val msg = withContext(Dispatchers.IO) {
                                    SheetsSync.importControl(settings, settings.webhookUrl).first
                                }
                                note = msg
                                importing = false
                            }
                        }
                    }
                }
            }

            Section("LAN DATA", palette, m) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
                    Chip("ON", lanExport, palette, m) { lanExport = true; settings.lanExportEnabled = true }
                    Chip("OFF", !lanExport, palette, m) { lanExport = false; settings.lanExportEnabled = false }
                }
                val ip = remember { LanInfo.lanIpv4() }
                val base = ip?.let { "http://$it:${DashboardServer.PORT}" }
                    ?: "http://<this device>:${DashboardServer.PORT}"
                Text(
                    "LAN address — open this from your laptop on the same Wi-Fi:",
                    color = palette.dim, fontSize = m.body,
                    modifier = Modifier.padding(top = m.gap / 2)
                )
                Text(
                    base,
                    fontFamily = FontFamily.Monospace,
                    color = palette.accent, fontSize = m.body,
                    modifier = Modifier.padding(top = m.gap / 4)
                )
                Text(
                    "Stats & CSVs: $base/export",
                    fontFamily = FontFamily.Monospace,
                    color = palette.dim, fontSize = m.body,
                    modifier = Modifier.padding(top = m.gap / 4)
                )
                Text(
                    if (lanExport) {
                        "Every drive, point, charge and coverage row is published at that /export " +
                            "page — open it from your Mac, or pull the CSVs with curl."
                    } else {
                        "The /export stats endpoint is OFF, so only the dashboard homepage is " +
                            "served. Turn ON above to publish machine-readable data."
                    },
                    color = palette.dim, fontSize = m.body,
                    modifier = Modifier.padding(top = m.gap / 2)
                )
            }

            Section("MEMORY", palette, m) {
                Text(
                    String.format(Locale.US, "Heap: %.1f MB used of %.1f MB max",
                        memory.heapUsedMb, memory.heapMaxMb),
                    fontFamily = FontFamily.Monospace,
                    color = palette.accent, fontSize = m.body
                )
                Text(
                    String.format(Locale.US, "App data on disk: %.1f MB", memory.diskMb),
                    fontFamily = FontFamily.Monospace,
                    color = palette.dim, fontSize = m.body,
                    modifier = Modifier.padding(top = m.gap / 4)
                )
                Text(
                    "Live heap the box is using now, plus everything it keeps in private " +
                        "storage (database, tile cache, raw frames). Readable steady growth as " +
                        "drives pile up is normal; a climb that never settles back is worth chasing.",
                    color = palette.dim, fontSize = m.body,
                    modifier = Modifier.padding(top = m.gap / 2)
                )
            }

            Section("EXPORT", palette, m) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
                    Chip("SHARE CSV", false, palette, m) {
                        Thread {
                            val db = OdographDb.get(ctx).dao()
                            val f = writeExport(
                                ctx, "odograph-trips.csv", Exporters.tripsCsv(db.allTrips())
                            )
                            shareFile(ctx, f, "text/csv")
                        }.start()
                        note = "Chooser opening. Bluetooth to your Mac is in the list."
                    }
                    Chip("SHARE GPX", false, palette, m) {
                        Thread {
                            val db = OdographDb.get(ctx).dao()
                            val latest = db.allTrips().firstOrNull()
                            val gpx = latest?.let {
                                Exporters.gpx("Trip ${it.id}", db.pointsFor(it.id))
                            } ?: "<gpx/>"
                            shareFile(ctx, writeExport(ctx, "odograph-latest.gpx", gpx),
                                "application/gpx+xml")
                        }.start()
                        note = "Latest drive exported as GPX."
                    }
                    Chip("SHARE PROBE", false, palette, m) {
                        shareFile(ctx, writeExport(ctx, "odograph-probe.txt", probe), "text/plain")
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
                    Chip("BACKUP DB", false, palette, m) {
                        Thread {
                            runCatching {
                                val dir = ctx.getExternalFilesDir(null) ?: return@Thread
                                val dest = File(dir, "odograph-backup-${System.currentTimeMillis()}.db")
                                OdographDb.snapshotTo(ctx, dest)
                                shareFile(ctx, dest, "application/octet-stream")
                            }
                        }.start()
                        note = "Backup saved — the share dialog is opening."
                    }
                    Chip("RESTORE DB", false, palette, m) {
                        restoreLauncher.launch(arrayOf("application/octet-stream"))
                    }
                }
                Text(
                    "Dashboard: http://<this device>:${DashboardServer.PORT}/  " +
                        "· paste the optional webhook URL at /config from your Mac.",
                    color = palette.dim, fontSize = m.body,
                    modifier = Modifier.padding(top = m.gap / 2)
                )
                if (note.isNotEmpty()) {
                    Text(note, color = palette.accent, fontSize = m.body,
                        modifier = Modifier.padding(top = m.gap / 3))
                }
            }

            Section("DEVICE", palette, m) {
                Text(
                    text = probe,
                    color = palette.dim,
                    fontSize = m.body,
                    lineHeight = m.body * 1.5f,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

private data class MemorySnapshot(val heapUsedMb: Double, val heapMaxMb: Double, val diskMb: Double)

/**
 * Heap the process is currently using, what it could grow to, and everything it has written to
 * private storage. The disk walk also covers the tile cache and raw-frames log, so this is the
 * real footprint, not just the database file.
 */
private fun memorySnapshot(ctx: android.content.Context): MemorySnapshot {
    val runtime = Runtime.getRuntime()
    val mb = 1024.0 * 1024.0
    val heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / mb
    val heapMaxMb = runtime.maxMemory() / mb
    val diskMb = ctx.filesDir.walkBottomUp()
        .filter { it.isFile }
        .sumOf { it.length() }.toDouble() / mb
    return MemorySnapshot(heapUsedMb, heapMaxMb, diskMb)
}

@Composable
private fun Section(
    title: String,
    palette: Palette,
    m: Metrics,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = palette.label,
            fontSize = m.label,
            letterSpacing = 2.2.sp,
            modifier = Modifier.padding(bottom = m.gap / 2)
        )
        content()
    }
}

/** A labelled text input on the setup page, matched to the instrument palette so it is readable. */
@Composable
private fun SetupField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    palette: Palette,
    m: Metrics,
    placeholder: String = "",
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (placeholder.isNotEmpty()) {
            { Text(placeholder, color = palette.label) }
        } else null,
        singleLine = true,
        isError = false,
        visualTransformation = if (isPassword) {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = textFieldColors(palette),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = m.gap / 4)
    )
}
