package `in`.odograph.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.core.BatteryMath
import `in`.odograph.tracker.core.BatteryMath.ChargeKind
import `in`.odograph.tracker.data.ChargeEventEntity
import `in`.odograph.tracker.data.ChargePlaceStatsRow
import `in`.odograph.tracker.data.OdographDao
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.data.PlaceEntity
import `in`.odograph.tracker.ui.theme.Palette
import `in`.odograph.tracker.ui.theme.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Both ways into pricing a charge — the auto prompt on a fast charger, and tapping a session in
 * the Charging screen — funnel through this dialog. The driver enters either a per-kW·h tariff
 * (GST is added on top) or the total bill (already includes GST); whichever they type wins.
 *
 * Kept deliberately simple for the live fast-charge prompt: tariff and bill only, no session data
 * editing — the richer [ChargeEditDialog] handles the Charging screen's tap-to-correct flow.
 */
@Composable
fun ChargeCostDialog(
    title: String,
    subtitle: String,
    gstRatePct: Double,
    palette: Palette,
    m: Metrics,
    onSave: (rateInr: Double?, billInr: Double?) -> Unit,
    onDismiss: () -> Unit
) {
    var rateText by remember { mutableStateOf("") }
    var billText by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().background(palette.trackSoft).padding(m.pad),
        verticalArrangement = Arrangement.spacedBy(m.gap / 2)
    ) {
        Text(
            text = title,
            color = palette.numeral,
            fontSize = m.stat,
            fontWeight = FontWeight.Medium
        )
        Text(text = subtitle, color = palette.dim, fontSize = m.body)
        OutlinedTextField(
            value = rateText,
            onValueChange = { rateText = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text("₹ per kW·h (tariff)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = textFieldColors(palette),
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "GST %.0f%% added on top of the tariff".format(gstRatePct),
            color = palette.label,
            fontSize = m.label
        )
        OutlinedTextField(
            value = billText,
            onValueChange = { billText = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text("Total bill ₹ (GST included)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = textFieldColors(palette),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
            Chip("APPLY", true, palette, m) {
                // A bill is the actual charge paid, so it beats a tariff if both were typed.
                if (billText.isNotBlank()) onSave(null, billText.toDoubleOrNull())
                else onSave(rateText.toDoubleOrNull(), null)
            }
            Chip("SKIP", false, palette, m, onClick = onDismiss)
        }
    }
}

/**
 * Writes a driver-entered price onto a closed session. Returns the session's new cost, or null
 * when nothing useful was entered (the configured rate stays). Kind is never relabelled by price.
 * A wall-meter [deliveredKwh] shifts the tariff basis to what the grid actually delivered.
 */
fun saveChargeCost(
    dao: OdographDao,
    sessionId: Long,
    deliveredKwh: Double?,
    rateInr: Double?,
    billInr: Double?,
    gstRatePct: Double
): Double? {
    if (rateInr == null && billInr == null) return null
    val event = dao.chargeEvent(sessionId) ?: return null
    val cost = BatteryMath.sessionCostInr(event.energyKwh, deliveredKwh ?: event.deliveredKwh, rateInr, billInr, gstRatePct)
    dao.setChargeCost(sessionId, rateInr, billInr, gstRatePct, cost)
    return cost
}

// ---- full edit dialog (Charging screen row tap) ----

/**
 * Writes an edit-applied session wholesale: battery kWh, wall kWh, tariff/bill, FAST/SLOW kind,
 * and optionally renames the charge location. The caller owns the coroutine and the list reload.
 */
fun saveChargeEdit(
    dao: OdographDao,
    sessionId: Long,
    energyKwh: Double,
    deliveredKwh: Double?,
    kind: Int,
    rateInr: Double?,
    billInr: Double?,
    gstRatePct: Double,
    homeRate: Double,
    outsideRate: Double,
    placeLabel: String?
) {
    val cost = BatteryMath.round2(
        billInr
            ?: (rateInr?.let {
                val basis = deliveredKwh ?: energyKwh
                basis * it.coerceAtLeast(0.0) * (1.0 + gstRatePct / 100.0)
            }
                ?: (deliveredKwh ?: energyKwh) * if (kind == ChargeKind.FAST.ordinal) outsideRate else homeRate)
    )
    dao.setChargeEdit(
        sessionId, energyKwh, deliveredKwh, kind,
        rateInr, billInr, gstRatePct, cost
    )
    // Renaming the charge spot is a quick convenience so the locations table stays meaningful
    // without a round-trip through the Places screen.
    val ev = dao.chargeEvent(sessionId)
    ev?.placeId?.let { pid -> placeLabel?.let { dao.setPlaceLabel(pid, it.trim().ifBlank { null }) } }
}

/**
 * Inline session editor, surfaced when the driver taps a charge row. Prefills every field from
 * the existing session so typing is never needed just to verify, and enables the driver to add
 * the wall-meter kWh (from the charger company app or a Qubo smart plug) at any point — weeks
 * or months after the charge itself, keyed to the date and time of the session.
 */
@Composable
fun ChargeEditDialog(
    title: String,
    subtitle: String,
    e: ChargeEventEntity,
    placeLabel: String?,
    gstRatePct: Double,
    palette: Palette,
    m: Metrics,
    onSave: (energyKwh: Double, deliveredKwh: Double?, rateInr: Double?, billInr: Double?, kind: Int, placeLabel: String?) -> Unit,
    onDismiss: () -> Unit
) {
    // Keyed by the session id so each tap starts from a clean slate; edits are abandoned cleanly.
    var rateText by remember(e.id) { mutableStateOf(e.enteredRateInr?.let { "%.2f".format(it) } ?: "") }
    var billText by remember(e.id) { mutableStateOf(e.enteredBillInr?.let { "%.2f".format(it) } ?: "") }
    var energyText by remember(e.id) { mutableStateOf("%.2f".format(e.energyKwh)) }
    var deliveredText by remember(e.id) { mutableStateOf(e.deliveredKwh?.let { "%.2f".format(it) } ?: "") }
    var placeText by remember(e.id) { mutableStateOf(placeLabel ?: "") }
    var kindState by remember(e.id) { mutableStateOf(e.kind ?: ChargeKind.SLOW.ordinal) }

    val loss = BatteryMath.lossPct(e.energyKwh, e.deliveredKwh)

    Column(
        Modifier.fillMaxWidth().background(palette.trackSoft).padding(m.pad),
        verticalArrangement = Arrangement.spacedBy(m.gap / 2)
    ) {
        Text(
            text = title,
            color = palette.numeral,
            fontSize = m.stat,
            fontWeight = FontWeight.Medium
        )
        Text(text = subtitle, color = palette.dim, fontSize = m.body)

        // ---- kind toggle (FAST / SLOW) ----
        Text("CHARGE TYPE", color = palette.label, fontSize = m.label, letterSpacing = 1.2.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
            val fast = kindState == ChargeKind.FAST.ordinal
            Box(
                Modifier
                    .background(if (fast) palette.accent2 else palette.track, RoundedCornerShape(4.dp))
                    .clickable { kindState = ChargeKind.FAST.ordinal }
                    .padding(horizontal = m.gap, vertical = m.gap / 4)
            ) {
                Text(
                    "FAST", color = if (fast) Color.White else palette.dim,
                    fontSize = m.label, fontWeight = if (fast) FontWeight.Bold else FontWeight.Normal
                )
            }
            Box(
                Modifier
                    .background(if (!fast) palette.accent else palette.track, RoundedCornerShape(4.dp))
                    .clickable { kindState = ChargeKind.SLOW.ordinal }
                    .padding(horizontal = m.gap, vertical = m.gap / 4)
            ) {
                Text(
                    "SLOW", color = if (!fast) Color.White else palette.dim,
                    fontSize = m.label, fontWeight = if (!fast) FontWeight.Bold else FontWeight.Normal
                )
            }
        }

        OutlinedTextField(
            value = energyText,
            onValueChange = { energyText = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text("kWh from battery (SOC)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = textFieldColors(palette),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = deliveredText,
            onValueChange = { deliveredText = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text("kWh from wall (charger / Qubo)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = textFieldColors(palette),
            modifier = Modifier.fillMaxWidth()
        )
        if (e.placeId != null) {
            OutlinedTextField(
                value = placeText,
                onValueChange = { placeText = it },
                label = { Text("Where (CHANGE)") },
                singleLine = true,
                colors = textFieldColors(palette),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (loss != null) {
            Text(
                text = "charging loss: %.1f%%".format(loss),
                color = if (loss > 25) palette.warn else palette.dim,
                fontSize = m.label
            )
        }

        OutlinedTextField(
            value = rateText,
            onValueChange = { rateText = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text("₹ per kW·h (tariff)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = textFieldColors(palette),
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "GST %.0f%% added on top of the tariff".format(gstRatePct),
            color = palette.label,
            fontSize = m.label
        )
        OutlinedTextField(
            value = billText,
            onValueChange = { billText = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text("Total bill ₹ (GST included)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = textFieldColors(palette),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(m.gap / 2)) {
            Chip("SAVE", true, palette, m) {
                val energy = energyText.toDoubleOrNull() ?: e.energyKwh
                val delivered = deliveredText.toDoubleOrNull()
                val bill = if (billText.isNotBlank()) billText.toDoubleOrNull() else null
                val rate = if (bill == null && rateText.isNotBlank()) rateText.toDoubleOrNull() else null
                onSave(energy, delivered, rate, bill, kindState, placeText)
            }
            Chip("DISMISS", false, palette, m, onClick = onDismiss)
        }
    }
}

// ---- stats ----

/** Everything the Charging screen's header needs to say in one pass over the sessions. */
data class ChargeStats(
    val sessions: Int,
    val fast: Int,
    val slow: Int,
    val open: Int,
    val kwhFast: Double,
    val kwhSlow: Double,
    val costFast: Double,
    val costSlow: Double,
    /** Total kWh the wall meters reported for sessions that have a reading. */
    val kwhDelivered: Double,
    /** Weighted-average charging loss % across sessions that supplied both numbers. */
    val avgLossPct: Double?
) {
    val kwhTotal: Double get() = kwhFast + kwhSlow
    val costTotal: Double get() = costFast + costSlow
    val fastSharePct: Double get() = if (kwhTotal > 0) kwhFast / kwhTotal * 100.0 else 0.0
    val avgFastPerKwh: Double? get() = if (kwhFast > 0) costFast / kwhFast else null
    val avgSlowPerKwh: Double? get() = if (kwhSlow > 0) costSlow / kwhSlow else null
}

fun chargeStats(events: List<ChargeEventEntity>): ChargeStats {
    var fast = 0
    var slow = 0
    var open = 0
    var kwhFast = 0.0
    var kwhSlow = 0.0
    var costFast = 0.0
    var costSlow = 0.0
    var deliveredSum = 0.0
    var lossNum = 0.0
    var lossDenom = 0.0
    for (e in events) {
        when {
            e.kind == null -> open++
            e.kind == ChargeKind.FAST.ordinal -> {
                fast++
                kwhFast += e.energyKwh
                e.costInr?.let { costFast += it }
            }
            else -> {
                slow++
                kwhSlow += e.energyKwh
                e.costInr?.let { costSlow += it }
            }
        }
        e.deliveredKwh?.let { d ->
            deliveredSum += d
            if (d > 0 && e.energyKwh > 0) {
                val loss = (1.0 - e.energyKwh / d) * 100.0
                lossNum += loss * d
                lossDenom += d
            }
        }
    }
    val avgLoss = if (lossDenom > 0) lossNum / lossDenom else null
    return ChargeStats(fast + slow + open, fast, slow, open, kwhFast, kwhSlow, costFast, costSlow, deliveredSum, avgLoss)
}

// ---- main screen ----

/**
 * The whole battery story in one tab: every plug-in session, split into fast and slow, how much
 * energy each kind delivered, what it cost, and the average price per kind. Closed sessions are
 * tappable to enter or correct the real bill and wall-meter kWh.
 */
@Composable
fun ChargingScreen(palette: Palette) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { Settings(ctx) }
    val gst = settings.gstRatePct
    var events by remember { mutableStateOf<List<ChargeEventEntity>>(emptyList()) }
    var editing by remember { mutableStateOf<ChargeEventEntity?>(null) }
    var placeStats by remember { mutableStateOf<List<ChargePlaceStatsRow>>(emptyList()) }

    val fmt = remember {
        SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).apply { timeZone = settings.zone }
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val dao = OdographDb.get(ctx).dao()
            events = dao.allChargeEvents().sortedByDescending { it.startTime }
            placeStats = dao.chargePlaceStats()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(palette.ground)) {
    val m = rememberMetrics(maxWidth, maxHeight)

    if (events.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No charge sessions yet.\nPlug in and the poller will record every fill.",
                color = palette.dim,
                fontSize = m.body,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        return@BoxWithConstraints
    }

    Column(Modifier.fillMaxSize().padding(horizontal = m.pad, vertical = m.pad)) {
        val s = chargeStats(events)
        Text(
            text = "CHARGING  ·  ${s.sessions}",
            color = palette.label,
            fontSize = m.label,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Medium
        )
        // Fast/slow share as a bar, then the headline split numbers below it.
        Box(
            Modifier.fillMaxWidth().height(6.dp).background(palette.track).padding(0.dp)
        ) {
            Box(
                Modifier.fillMaxWidth((s.fastSharePct / 100.0).toFloat())
                    .fillMaxHeight().background(palette.accent2)
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = m.gap),
            horizontalArrangement = Arrangement.spacedBy(m.gap * 2)
        ) {
            Stat("%.0f%%".format(s.fastSharePct), "FAST kWh", palette, m, size = m.stat)
            Stat("%.2f kWh".format(s.kwhTotal), "TOTAL", palette, m, size = m.stat)
            Stat("₹ %.2f".format(s.costTotal), "TOTAL COST", palette, m, size = m.stat)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = m.gap / 2),
            horizontalArrangement = Arrangement.spacedBy(m.gap * 2)
        ) {
            s.avgFastPerKwh?.let {
                SmallStat("₹ %.2f/kWh".format(it), "${s.fast} FAST", palette, m)
            }
            s.avgSlowPerKwh?.let {
                SmallStat("₹ %.2f/kWh".format(it), "${s.slow} SLOW", palette, m)
            }
            if (s.open > 0) SmallStat("charging", "$s.open OPEN", palette, m)
            s.avgLossPct?.let {
                SmallStat("−%.1f%% loss".format(it), "CHARGE LOSS", palette, m)
            }
        }

        // Per-location totals: quick summary of where fills happen.
        if (placeStats.isNotEmpty()) {
            Text(
                text = "LOCATIONS",
                color = palette.label,
                fontSize = m.label,
                letterSpacing = 1.8.sp,
                modifier = Modifier.padding(top = m.gap)
            )
            placeStats.forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(top = m.gap / 2),
                    horizontalArrangement = Arrangement.spacedBy(m.gap * 2)
                ) {
                    Text(
                        text = (row.label?.takeIf { it.isNotBlank() } ?: " unnamed ").trim().uppercase(Locale.getDefault()),
                        color = palette.numeral,
                        fontSize = m.label,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "%d×  ·  %.1f kWh  ·  ₹%.0f".format(row.sessions, row.kwh, row.costInr),
                        color = palette.dim,
                        fontSize = m.label
                    )
                }
            }
        }

        LazyColumn(Modifier.fillMaxWidth().padding(top = m.gap)) {
            items(events, key = { it.id }) { e ->
                ChargeRow(e, fmt, palette, m) { if (e.kind != null) editing = e }
            }
        }
    }

    editing?.let { e ->
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            val kindLabel = if (e.kind == ChargeKind.FAST.ordinal) "fast" else "slow"
            val place = e.placeId?.let { pid ->
                runCatching { OdographDb.get(ctx).dao().placeById(pid) }.getOrNull()
            }
            val placeName = place?.displayName ?: "unknown"
            ChargeEditDialog(
                title = "CHARGE  ·  ${kindLabel.uppercase(Locale.getDefault())}  ·  ${placeName}",
                subtitle = "%.2f kWh · current ₹%.2f".format(e.energyKwh, e.costInr ?: 0.0),
                e = e,
                placeLabel = place?.label,
                gstRatePct = gst,
                palette = palette,
                m = m,
                onSave = { energy, delivered, rate, bill, kind, newLabel ->
                    editing = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val dao = OdographDb.get(ctx).dao()
                            saveChargeEdit(
                                dao, e.id, energy, delivered, kind, rate, bill, gst,
                                settings.homeRateInr, settings.outsideRateInr, newLabel
                            )
                        }
                        withContext(Dispatchers.IO) {
                            events = OdographDb.get(ctx).dao().allChargeEvents().sortedByDescending { it.startTime }
                            placeStats = OdographDb.get(ctx).dao().chargePlaceStats()
                        }
                    }
                },
                onDismiss = { editing = null }
            )
        }
    }
    }
}

@Composable
private fun ChargeRow(
    e: ChargeEventEntity,
    fmt: SimpleDateFormat,
    palette: Palette,
    m: Metrics,
    onClick: () -> Unit
) {
    val kindColor = when (e.kind) {
        null -> palette.accent
        ChargeKind.FAST.ordinal -> palette.accent2
        else -> palette.dim
    }
    val kindLabel = when (e.kind) {
        null -> "IN PROGRESS"
        ChargeKind.FAST.ordinal -> "FAST"
        else -> "SLOW"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = m.gap / 2)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = fmt.format(Date(e.startTime)),
                color = palette.numeral,
                fontSize = m.stat,
                fontWeight = FontWeight.Medium
            )
            Text(text = kindLabel, color = kindColor, fontSize = m.label, letterSpacing = 1.3.sp)
        }
        val cells = mutableListOf(
            e.startSoc?.let { "%.0f%%".format(it) } ?: "—",
            e.endSoc?.let { "%.0f%%".format(it) } ?: "—"
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = "${cells[0]}→${cells[1]} " +
                    "· %.2f kWh".format(e.energyKwh) +
                    " · ↑%.0f kW".format(e.peakPowerKw ?: 0.0),
                color = palette.dim,
                fontSize = m.body
            )
            if (e.costInr != null) {
                Text("₹ %.2f".format(e.costInr), color = palette.accent, fontSize = m.body)
            }
        }
        // Wall-meter line: show delivered kWh and charging loss when the driver recorded them.
        val loss = BatteryMath.lossPct(e.energyKwh, e.deliveredKwh)
        if (e.deliveredKwh != null || loss != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (e.deliveredKwh != null) {
                    Text(
                        text = "wall: %.1f kWh".format(e.deliveredKwh),
                        color = palette.dim,
                        fontSize = m.label
                    )
                }
                if (loss != null) {
                    Text(
                        text = "−%.1f%% loss".format(loss),
                        color = if (loss > 25) palette.warn else palette.label,
                        fontSize = m.label
                    )
                }
            }
        }
        if (e.kind == null) {
            Text("Priced when the plug comes out", color = palette.label, fontSize = m.label)
        }
    }
}
