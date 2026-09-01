package `in`.odograph.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.export.Exporters
import `in`.odograph.tracker.export.shareFile
import `in`.odograph.tracker.export.writeExport
import `in`.odograph.tracker.probe.DeviceProbe
import `in`.odograph.tracker.server.DashboardServer
import `in`.odograph.tracker.ui.theme.Direction
import `in`.odograph.tracker.ui.theme.Palette
import `in`.odograph.tracker.ui.theme.ThemeMode

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(
    direction: Direction,
    themeMode: ThemeMode,
    showTiles: Boolean,
    palette: Palette,
    onDirection: (Direction) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onTiles: (Boolean) -> Unit
) {
    val ctx = LocalContext.current
    var probe by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        probe = runCatching { DeviceProbe.collect(ctx).asText() }
            .getOrElse { "Probe unavailable: ${it.message}" }
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

            Section("EXPORT", palette, m) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
                    Chip("SHARE CSV", false, palette, m) {
                        Thread {
                            val dao = OdographDb.get(ctx).dao()
                            val f = writeExport(
                                ctx, "odograph-trips.csv", Exporters.tripsCsv(dao.allTrips())
                            )
                            shareFile(ctx, f, "text/csv")
                        }.start()
                        note = "Chooser opening. Bluetooth to your Mac is in the list."
                    }
                    Chip("SHARE GPX", false, palette, m) {
                        Thread {
                            val dao = OdographDb.get(ctx).dao()
                            val latest = dao.allTrips().firstOrNull()
                            val gpx = latest?.let {
                                Exporters.gpx("Trip ${it.id}", dao.pointsFor(it.id))
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
