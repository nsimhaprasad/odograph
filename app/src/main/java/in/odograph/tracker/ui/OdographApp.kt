package `in`.odograph.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.core.Fix
import `in`.odograph.tracker.core.TripStats
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.record.TripRecorderService
import `in`.odograph.tracker.ui.gauge.SpeedSpring
import `in`.odograph.tracker.ui.theme.Settings
import `in`.odograph.tracker.ui.theme.ThemeMode
import `in`.odograph.tracker.ui.theme.currentHour
import `in`.odograph.tracker.ui.theme.isNight
import `in`.odograph.tracker.ui.theme.paletteFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Tab { DRIVE, TRIPS, ROUTES, CHARGE, SETUP }

@Composable
fun OdographApp() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val gstRatePct = settings.gstRatePct

    var tab by remember { mutableStateOf(Tab.DRIVE) }
    var detailed by remember { mutableStateOf(false) }
    var direction by remember { mutableStateOf(settings.direction) }
    var themeMode by remember { mutableStateOf(settings.themeMode) }
    var showTiles by remember { mutableStateOf(true) }
    var telematics by remember { mutableStateOf(settings.telematicsEnabled) }
    var hour by remember { mutableStateOf(currentHour(settings.zone)) }

    val live by TripRecorderService.state.collectAsState()
    val spring = remember { SpeedSpring() }
    var smoothed by remember { mutableFloatStateOf(0f) }
    var route by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var slowestKmMps by remember { mutableStateOf(0.0) }

    val night = isNight(themeMode, hour)
    val palette = paletteFor(direction, night)
    val scope = rememberCoroutineScope()

    // One frame-paced loop drives the needle. The spring both gives it mass and filters the
    // 2-3 km/h of GNSS jitter that would otherwise make the readout twitch.
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
                last = now
                smoothed = spring.update(mpsToKmh(live.speedMps), dt)
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            hour = currentHour(settings.zone)
            val id = TripRecorderService.state.value.tripId
            if (id > 0) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val points = OdographDb.get(ctx).dao().pointsFor(id)
                        val fixes = points.map {
                            Fix(
                                it.t, it.lat, it.lon, it.speedMps, it.accuracyM,
                                it.interpolated, it.altitudeM
                            )
                        }
                        route = buildSmoothRoute(fixes)
                        slowestKmMps = TripStats.compute(fixes).slowestKmSpeedMps
                    }
                }
            }
            delay(5_000)
        }
    }

    // The MG battery tile lives on the drive screen. While it is visible the poller may run its
    // normal cadence; entering the screen also asks for a fresh sample immediately.
    LaunchedEffect(tab) {
        TripRecorderService.setTelematicsScreenVisible(tab == Tab.DRIVE)
        if (tab == Tab.DRIVE) TripRecorderService.requestTelematicsRefresh()
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(palette.ground)) {
      val m = rememberMetrics(maxWidth, maxHeight)
      Box(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = m.pad, vertical = m.gap / 2),
            horizontalArrangement = Arrangement.spacedBy(m.gap / 2),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Chip("DRIVE", tab == Tab.DRIVE, palette, m) { tab = Tab.DRIVE }
            Chip("TRIPS", tab == Tab.TRIPS, palette, m) { tab = Tab.TRIPS }
            Chip("ROUTES", tab == Tab.ROUTES, palette, m) { tab = Tab.ROUTES }
            Chip("CHARGE", tab == Tab.CHARGE, palette, m) { tab = Tab.CHARGE }
            Chip("SETUP", tab == Tab.SETUP, palette, m) { tab = Tab.SETUP }
            if (tab == Tab.DRIVE) {
                Chip(if (detailed) "DETAILED" else "DRIVER", true, palette, m) {
                    detailed = !detailed
                }
            }
            Text(
                text = if (live.hasFix) "REC" else "ACQUIRING",
                color = if (live.hasFix) palette.accent else palette.label,
                fontSize = m.label,
                letterSpacing = 1.8.sp,
                maxLines = 1,
                modifier = Modifier.padding(start = m.gap / 2)
            )
        }

        when (tab) {
            Tab.DRIVE ->
                if (detailed) {
                    DetailScreen(live, smoothed, route, slowestKmMps, showTiles, direction, palette)
                } else {
                    DriverScreen(live, smoothed, direction, palette)
                }
            Tab.TRIPS -> TripListScreen(showTiles, palette)
            Tab.ROUTES -> RoutesScreen(palette)
            Tab.CHARGE -> ChargingScreen(palette)
            Tab.SETUP -> SetupScreen(
                direction = direction,
                themeMode = themeMode,
                showTiles = showTiles,
                telematics = telematics,
                palette = palette,
                onDirection = { direction = it; settings.direction = it },
                onThemeMode = { themeMode = it; settings.themeMode = it },
                onTiles = { showTiles = it },
                onTelematics = { telematics = it; settings.telematicsEnabled = it }
            )
        }
      }

        // A fast charge the driver should price floats over everything: when one starts (enter the
        // tariff you agreed to pay) and when it finishes (correct it with the actual bill). A slow
        // session never prompts — the home rate stands.
        val prompt = live.pendingChargePrompt
        if (prompt != null) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f)),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                ChargeCostDialog(
                    title = if (prompt.isOpen) "FAST CHARGE STARTED" else "FAST CHARGE FINISHED",
                    subtitle = if (prompt.isOpen) {
                        "Enter the tariff you agreed to pay. It is applied to whatever this session delivers."
                    } else {
                        "%.2f kWh · default ₹%.2f".format(prompt.energyKwh, prompt.currentCostInr ?: 0.0) +
                            " — correct it with the bill."
                    },
                    gstRatePct = gstRatePct,
                    palette = palette,
                    m = m,
                    onSave = { rate, bill ->
                        TripRecorderService.clearChargePrompt()
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val dao = OdographDb.get(ctx).dao()
                                if (prompt.isOpen) {
                                    dao.setChargeCostLedger(prompt.sessionId, rate, bill, gstRatePct)
                                } else {
                                    saveChargeCost(dao, prompt.sessionId, rate, bill, gstRatePct)
                                }
                            }
                        }
                    },
                    onDismiss = { TripRecorderService.clearChargePrompt() }
                )
            }
        }
      }
    }
}
