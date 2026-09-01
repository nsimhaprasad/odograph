package `in`.odograph.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.export.Exporters
import `in`.odograph.tracker.export.shareFile
import `in`.odograph.tracker.export.writeExport
import `in`.odograph.tracker.probe.DeviceProbe
import `in`.odograph.tracker.ui.theme.Direction
import `in`.odograph.tracker.ui.theme.Palette
import `in`.odograph.tracker.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    var exportNote by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        probe = runCatching { DeviceProbe.collect(ctx).asText() }
            .getOrElse { "Probe unavailable: ${it.message}" }
    }

    Column(
        Modifier.fillMaxSize().background(palette.ground)
            .verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Section("CLUSTER", palette) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Direction.entries.forEach { d ->
                    Chip(d.name, d == direction, palette) { onDirection(d) }
                }
            }
        }

        Section("THEME", palette) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemeMode.entries.forEach { m ->
                    Chip(m.name, m == themeMode, palette) { onThemeMode(m) }
                }
            }
        }

        Section("MAP", palette) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("TILES", showTiles, palette) { onTiles(true) }
                Chip("TRACE ONLY", !showTiles, palette) { onTiles(false) }
            }
            Text(
                "Tiles download over your hotspot and are cached permanently. Trace only never " +
                    "touches the network.",
                color = palette.dim, fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Section("EXPORT", palette) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("SHARE TRIPS CSV", false, palette) {
                    val dao = OdographDb.get(ctx).dao()
                    Thread {
                        val csv = Exporters.tripsCsv(dao.allTrips())
                        val f = writeExport(ctx, "odograph-trips.csv", csv)
                        shareFile(ctx, f, "text/csv")
                    }.start()
                    exportNote = "Chooser opening — Bluetooth to your Mac is in the list."
                }
                Chip("SHARE ALL GPX", false, palette) {
                    val dao = OdographDb.get(ctx).dao()
                    Thread {
                        val trips = dao.allTrips()
                        val gpx = trips.firstOrNull()?.let {
                            Exporters.gpx("Trip ${it.id}", dao.pointsFor(it.id))
                        } ?: "<gpx/>"
                        val f = writeExport(ctx, "odograph-latest.gpx", gpx)
                        shareFile(ctx, f, "application/gpx+xml")
                    }.start()
                    exportNote = "Latest drive exported as GPX."
                }
            }
            if (exportNote.isNotEmpty()) {
                Text(exportNote, color = palette.accent, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }

        Section("DEVICE", palette) {
            Text(
                text = probe,
                color = palette.dim,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Row(Modifier.padding(top = 10.dp)) {
                Chip("SHARE PROBE", false, palette) {
                    val f = writeExport(ctx, "odograph-probe.txt", probe)
                    shareFile(ctx, f, "text/plain")
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, palette: Palette, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = palette.label,
            fontSize = 11.sp,
            letterSpacing = 2.5.sp,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        content()
    }
}
