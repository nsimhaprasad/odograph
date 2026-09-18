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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.core.BatteryMath
import `in`.odograph.tracker.core.Geo
import `in`.odograph.tracker.ui.theme.Settings
import `in`.odograph.tracker.core.Reachability
import `in`.odograph.tracker.data.PlaceEntity
import `in`.odograph.tracker.record.TripRecorderService
import androidx.compose.runtime.collectAsState
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.geocode.PlaceNamer
import `in`.odograph.tracker.record.PlaceRepo
import `in`.odograph.tracker.ui.map.PlacePin
import `in`.odograph.tracker.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How close the car has to be to a saved place to count as standing at it.
 *
 * Generous, because a car park is not a point: the entrance, the far corner and the road outside
 * are all "at the office" as far as the drive ahead is concerned, and the route history was built
 * from arrivals scattered over exactly that spread.
 */
private const val AT_PLACE_RADIUS_M = 250.0

private data class KnownPlace(
    val id: Long,
    val name: String,
    val visits: Int,
    val lat: Double,
    val lon: Double,
    /** What the charge would be on arrival, when the car has said where it is and how full. */
    val reach: Reachability.Estimate? = null
)

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
    var renamingId by remember { mutableStateOf<Long?>(null) }
    var renameText by remember { mutableStateOf("") }

    // Live, so the answer moves with the car rather than with the screen being reopened.
    val live by TripRecorderService.state.collectAsState()

    // Keyed on a coarse position, not the raw one. A fix arrives about once a second, and this
    // effect reads every place and every instrumented drive to rebuild the route profiles — so
    // keying it on live.lat meant a full pass over the history every second the screen was open
    // while driving. Rounded to roughly a hundred metres, it runs when the answer could actually
    // have changed and not merely when the car moved a wheel's width.
    val here = live.lat?.let { lat ->
        live.lon?.let { lon -> Math.round(lat * 1_000) to Math.round(lon * 1_000) }
    }

    LaunchedEffect(reload, here, live.batterySocPercent?.let { Math.round(it) }) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val dao = OdographDb.get(ctx).dao()
                // Two answers, in order of preference: what this exact route has actually cost the
                // times it was driven, and the rolling average for everywhere else.
                val rolling = BatteryMath.rollingKwhPer100Km(
                    dao.tripEnergies().mapNotNull {
                        BatteryMath.kwhPer100Km(it.energyKwh, it.distanceM)
                    }
                )
                Triple(
                    dao.allPlaces(),
                    rolling,
                    Reachability.routeProfiles(dao.routeTripsForEfficiency())
                )
            }.getOrDefault(Triple(emptyList(), null, emptyMap()))
        }
        val (places, rolling, profiles) = loaded

        val hereLat = live.lat
        val hereLon = live.lon
        val soc = live.batterySocPercent
        val capacity = Settings(ctx).batteryCapacityKwh

        // Which saved place the car is standing at, if any. Only a route that starts where the car
        // actually is can lend its measured distance and cost to the drive ahead.
        val origin = if (hereLat != null && hereLon != null) {
            places.minByOrNull { Geo.haversineMetres(hereLat, hereLon, it.lat, it.lon) }
                ?.takeIf { Geo.haversineMetres(hereLat, hereLon, it.lat, it.lon) <= AT_PLACE_RADIUS_M }
        } else null

        known = places
            .sortedByDescending { it.visits }
            .map { place ->
                val profile = origin?.let { profiles[it.id to place.id] }
                val efficiency = Reachability.efficiencyKwhPer100Km(profile?.kwhPer100Km, rolling)
                val reach = if (hereLat != null && hereLon != null && soc != null && efficiency != null) {
                    val (km, measured) = Reachability.distanceKm(
                        measuredRouteM = profile?.distanceM,
                        fromLat = hereLat, fromLon = hereLon,
                        toLat = place.lat, toLon = place.lon
                    )
                    Reachability.estimate(km, soc, capacity, efficiency, measured)
                } else null
                KnownPlace(place.id, place.displayName, place.visits, place.lat, place.lon, reach)
            }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(palette.ground)) {
        val m = rememberMetrics(maxWidth, maxHeight)

        // Scrolls, because this screen has never fitted a short window. A Column measures each
        // child against the height that is left, so on the box's quarter-height band the search
        // block consumed all of it and KNOWN PLACES rendered at zero height — present in the tree,
        // invisible on the glass, and with no gesture that could reach it.
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(m.pad),
            verticalArrangement = Arrangement.spacedBy(m.gap)
        ) {
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
                placeholder = { Text("Find a place…", color = palette.dim, fontSize = m.body) },
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
                colors = textFieldColors(palette),
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
                    placeholder = { Text("Label it — Home, Office, Farm…", color = palette.dim, fontSize = m.body) },
                    colors = textFieldColors(palette),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
                    Chip("SAVE PLACE", true, palette, m) {
                        val p = pin ?: return@Chip
                        scope.launch {
                            savedNote = withContext(Dispatchers.IO) {
                                runCatching {
                                    val dao = OdographDb.get(ctx).dao()
                                    val saved = PlaceRepo(dao).createOrReposition(p.first, p.second, name)
                                    "${saved.displayName} pinned."
                                }.getOrElse { "Could not save now — try again." }
                            }
                        }
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
                        if (renamingId == place.id) {
                            OutlinedTextField(
                                value = renameText,
                                onValueChange = { renameText = it },
                                singleLine = true,
                                placeholder = { Text("Label it — Home, Office, Farm…", color = palette.dim, fontSize = m.body) },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    val dao = OdographDb.get(ctx).dao()
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            dao.setPlaceLabel(place.id, renameText.trim().ifBlank { null })
                                        }
                                        renamingId = null
                                        reload++
                                    }
                                }),
                                colors = textFieldColors(palette),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
                                Chip("SAVE LABEL", true, palette, m) {
                                    val dao = OdographDb.get(ctx).dao()
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            dao.setPlaceLabel(place.id, renameText.trim().ifBlank { null })
                                        }
                                        renamingId = null
                                        reload++
                                    }
                                }
                                Chip("CANCEL", false, palette, m) { renamingId = null }
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        renamingId = place.id
                                        renameText = place.name
                                    }
                                    .padding(vertical = m.gap / 3),
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
                                        text = place.reach?.let { reachLine(it) }
                                            ?: "drive-placed, auto-counting · tap to label",
                                        color = place.reach?.let { reachColor(it, palette) }
                                            ?: palette.label,
                                        fontSize = m.body,
                                        maxLines = 1
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
}

/**
 * What the drive to a place would leave in the battery.
 *
 * Says how far and what is left, and marks an estimate that came from a straight line rather than
 * a road the car has driven — the difference is easily thirty percent, and a number that hides
 * which one it is invites being trusted equally.
 */
private fun reachLine(e: Reachability.Estimate): String {
    val about = if (e.measured) "" else "~"
    val verdict = when (e.verdict) {
        Reachability.Verdict.COMFORTABLE -> ""
        Reachability.Verdict.TIGHT -> "  ·  tight"
        Reachability.Verdict.UNREACHABLE -> "  ·  needs a charge"
    }
    return "$about%.0f km  ·  arrive %d%%%s".format(e.distanceKm, e.shownPercent, verdict)
}

/** The same three states the drive screen colours a charge with, for the same reason. */
private fun reachColor(e: Reachability.Estimate, palette: Palette) = when (e.verdict) {
    Reachability.Verdict.COMFORTABLE -> palette.good
    Reachability.Verdict.TIGHT -> palette.caution
    Reachability.Verdict.UNREACHABLE -> palette.warn
}
