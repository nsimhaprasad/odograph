package `in`.odograph.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RouteRow(
    val from: String,
    val to: String,
    val drives: Int,
    val avgDistanceM: Double,
    val avgDurationS: Long,
    val totalDistanceM: Double
)

/** Answers "where do I actually drive", which is the whole reason for clustering endpoints. */
@Composable
fun RoutesScreen(palette: Palette) {
    val ctx = LocalContext.current
    var rows by remember { mutableStateOf<List<RouteRow>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        rows = withContext(Dispatchers.IO) {
            val dao = OdographDb.get(ctx).dao()
            val places = dao.allPlaces().associateBy { it.id }
            dao.routeSummaries().map { s ->
                RouteRow(
                    from = places[s.startId]?.displayName ?: "Unknown",
                    to = places[s.endId]?.displayName ?: "Unknown",
                    drives = s.drives,
                    avgDistanceM = s.avgDistanceM,
                    avgDurationS = s.avgDurationS.toLong(),
                    totalDistanceM = s.totalDistanceM
                )
            }
        }
        loaded = true
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(palette.ground)) {
        val m = rememberMetrics(maxWidth, maxHeight)
        val countColumnWidth = maxWidth * 0.18f
        Column(Modifier.fillMaxSize().padding(m.pad)) {
            Text(
                text = "MOST VISITED ROUTES",
                color = palette.label,
                fontSize = m.label,
                letterSpacing = 2.2.sp,
                modifier = Modifier.padding(bottom = m.gap)
            )

            if (loaded && rows.isEmpty()) {
                Text(
                    text = "No completed drives yet.\n\nRoutes appear once you have driven " +
                        "somewhere and switched the car off — a trip is only grouped after it " +
                        "ends, and it ends when the ignition does.",
                    color = palette.dim,
                    fontSize = m.body,
                    lineHeight = m.body * 1.6f
                )
            }

            LazyColumn {
                items(rows) { r ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = m.gap / 2),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "${r.from}  →  ${r.to}",
                                color = palette.numeral,
                                fontSize = m.stat,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Text(
                                text = "avg ${formatKm(r.avgDistanceM)} km · " +
                                    "${formatHhMm(r.avgDurationS)} · " +
                                    "${formatKm(r.totalDistanceM)} km total",
                                color = palette.dim,
                                fontSize = m.body,
                                maxLines = 1
                            )
                        }
                        Column(
                            Modifier.width(countColumnWidth),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                text = "${r.drives}",
                                color = palette.accent,
                                fontSize = m.stat,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (r.drives == 1) "DRIVE" else "DRIVES",
                                color = palette.label,
                                fontSize = m.label,
                                letterSpacing = 1.4.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
