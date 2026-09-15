package `in`.odograph.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.geocode.PlaceNamer
import `in`.odograph.tracker.record.PlaceRepo
import `in`.odograph.tracker.ui.map.PlacePin
import `in`.odograph.tracker.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class KnownPlace(val id: Long, val name: String, val visits: Int)

/**
 * Name a place without driving there: type an area or landmark, pick a search hit, pin it on the
 * tile map, and it becomes a named place the moment the car starts treating it as home or office.
 *
 * Naming a place here is the same move as labelling an auto-cluster on the ROUTES tab — this is
 * just the "I want it before I ever drive there" entry point. The phone hotspot is the search
 * engine's door, so the empty-network case says so instead of pretending.
 */
@Composable
fun PlacesScreen(palette: Palette) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlaceNamer.GeocodedSuggestion>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var name by remember { mutableStateOf("") }
    var savedNote by remember { mutableStateOf("") }
    var known by remember { mutableStateOf<List<KnownPlace>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        val places = withContext(Dispatchers.IO) {
            runCatching { OdographDb.get(ctx).dao().allPlaces() }.getOrDefault(emptyList())
        }
        known = places
            .sortedByDescending { it.visits }
            .map { KnownPlace(it.id, it.displayName, it.visits) }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(palette.ground)) {
        val m = rememberMetrics(maxWidth, maxHeight)

        Column(Modifier.fillMaxSize().padding(m.pad), verticalArrangement = Arrangement.spacedBy(m.gap)) {
            Text(
                text = "NAME A PLACE  ·  SEARCH, PIN, SAVE",
                color = palette.label,
                fontSize = m.label,
                letterSpacing = 2.2.sp
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Find a place…", fontSize = m.body) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    searching = true
                    searched = false
                    scope.launch {
                        val hits = withContext(Dispatchers.IO) {
                            PlaceNamer.search(query)
                        }
                        results = hits
                        searching = false
                        searched = true
                    }
                }),
                modifier = Modifier.fillMaxWidth()
            )

            val showResults = results.isNotEmpty() || searching || searched
            if (showResults) {
                Text(
                    text = if (searching) "SEARCHING…" else "SEARCH RESULTS",
                    color = palette.label,
                    fontSize = m.label,
                    letterSpacing = 1.6.sp
                )
                LazyColumn(Modifier.height(if (m.veryCompact) 0.dp else 160.dp)) {
                    items(results) { hit ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pin = hit.lat to hit.lon
                                    name = hit.name.substringBefore(',')
                                }
                                .padding(vertical = m.gap / 3),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = hit.name.substringBefore(','),
                                    color = palette.numeral,
                                    fontSize = m.body,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                                Text(
                                    text = "%.4f, %.4f  ·  tap to pin".format(hit.lat, hit.lon),
                                    color = palette.dim,
                                    fontSize = m.label,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    if (!searching && searched && results.isEmpty()) {
                        item {
                            Text(
                                "No matches — is the phone hotspot on? Search needs it.",
                                color = palette.dim,
                                fontSize = m.body
                            )
                        }
                    }
                }
            }

            PlacePin(
                pin = pin,
                palette = palette,
                modifier = Modifier.fillMaxWidth().height(170.dp)
            )

            if (pin != null) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("Label it — Home, Office, Farm…", fontSize = m.body) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
                    Chip("SAVE PLACE", true, palette, m) {
                        val p = pin ?: return@Chip
                        savedNote = runCatching {
                            val dao = OdographDb.get(ctx).dao()
                            val saved = PlaceRepo(dao).createOrReposition(p.first, p.second, name)
                            "${saved.displayName} pinned."
                        }.getOrElse { "Could not save now — try again." }
                        reload++
                    }
                    if (savedNote.isNotEmpty()) {
                        Text(savedNote, color = palette.accent, fontSize = m.body)
                    }
                }
            }

            Text(
                text = "KNOWN PLACES",
                color = palette.label,
                fontSize = m.label,
                letterSpacing = 2.2.sp
            )
            Column {
                if (known.isEmpty()) {
                    Text(
                        "None yet. A named place counts visits once drives start or end near it — " +
                            "use the search above to add one the car hasn't visited.",
                        color = palette.dim,
                        fontSize = m.body,
                        lineHeight = m.body * 1.6f
                    )
                } else {
                    known.forEach { place ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = m.gap / 3),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = place.name,
                                    color = palette.numeral,
                                    fontSize = m.stat,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                                Text(
                                    text = "drive-placed, auto-counting",
                                    color = palette.label,
                                    fontSize = m.body
                                )
                            }
                            Text(
                                text = "${place.visits}",
                                color = palette.accent2,
                                fontSize = m.stat,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}