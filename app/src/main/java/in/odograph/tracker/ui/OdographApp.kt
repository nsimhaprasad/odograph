package `in`.odograph.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.withContext

private enum class Tab { DRIVE, TRIPS, ROUTES, SETUP }

@Composable
fun OdographApp() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }

    var tab by remember { mutableStateOf(Tab.DRIVE) }
    var detailed by remember { mutableStateOf(false) }
    var direction by remember { mutableStateOf(settings.direction) }
    var themeMode by remember { mutableStateOf(settings.themeMode) }
    var showTiles by remember { mutableStateOf(true) }
    var hour by remember { mutableStateOf(currentHour(settings.zone)) }

    val live by TripRecorderService.state.collectAsState()
    val spring = remember { SpeedSpring() }
    var smoothed by remember { mutableFloatStateOf(0f) }
    var route by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var slowestKmMps by remember { mutableStateOf(0.0) }

    val night = isNight(themeMode, hour)
    val palette = paletteFor(direction, night)

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
                        route = points.map { it.lat to it.lon }
                        slowestKmMps = TripStats.compute(
                            points.map {
                                Fix(it.t, it.lat, it.lon, it.speedMps, it.accuracyM, it.interpolated)
                            }
                        ).slowestKmSpeedMps
                    }
                }
            }
            delay(5_000)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(palette.ground)) {
      val m = rememberMetrics(maxWidth, maxHeight)
      Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = m.pad, vertical = m.gap / 2),
            horizontalArrangement = Arrangement.spacedBy(m.gap / 2),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Chip("DRIVE", tab == Tab.DRIVE, palette, m) { tab = Tab.DRIVE }
            Chip("TRIPS", tab == Tab.TRIPS, palette, m) { tab = Tab.TRIPS }
            Chip("ROUTES", tab == Tab.ROUTES, palette, m) { tab = Tab.ROUTES }
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
            Tab.SETUP -> SetupScreen(
                direction = direction,
                themeMode = themeMode,
                showTiles = showTiles,
                palette = palette,
                onDirection = { direction = it; settings.direction = it },
                onThemeMode = { themeMode = it; settings.themeMode = it },
                onTiles = { showTiles = it }
            )
        }
    }
  }
}
