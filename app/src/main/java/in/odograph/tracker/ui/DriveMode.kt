package `in`.odograph.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.ui.theme.Palette

/**
 * The drive modes a driver picks by hand.
 *
 * The MG telematics never reports which mode the car is in (the one field that looks like it is
 * just the power state), so the only way to tag a drive with ECO+/ECO/DRIVE/SPORT is to ask. The
 * order is the order of frugality, which is also roughly how often each is used here.
 */
val DRIVE_MODES = listOf("ECO+", "ECO", "DRIVE", "SPORT")

/** How long "Not now" holds the popup back before it asks again, milliseconds. */
const val DRIVE_MODE_SNOOZE_MS = 3 * 60_000L

/**
 * Asks which mode the car is being driven in, once a drive has started and until it is answered.
 *
 * The last mode picked is highlighted so the usual choice is a single tap. "Not now" holds the ask
 * back for a few minutes; "Don't ask" stops it for good until it is turned back on. The same dialog
 * edits a drive's mode later, from the trip list, where [current] is whatever was stored.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DriveModeDialog(
    current: String?,
    lastMode: String?,
    palette: Palette,
    m: Metrics,
    onPick: (String) -> Unit,
    onSnooze: (() -> Unit)? = null,
    onDontAsk: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(m.pad * 0.8f)
    val highlight = current ?: lastMode
    Column(
        modifier = Modifier
            .widthIn(max = 520.dp)
            .clip(shape)
            .background(palette.ground)
            .border(1.dp, palette.track, shape)
            .padding(m.pad),
        verticalArrangement = Arrangement.spacedBy(m.gap)
    ) {
        Text("DRIVE MODE", color = palette.accent, fontSize = m.stat, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text(
            if (current == null) "Which mode are you driving in?" else "Change the mode for this drive.",
            color = palette.dim, fontSize = m.body
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(m.gap / 2),
            verticalArrangement = Arrangement.spacedBy(m.gap / 2)
        ) {
            DRIVE_MODES.forEach { mode ->
                ModeChip(mode, selected = mode == highlight, palette, m) { onPick(mode) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(m.gap), verticalAlignment = Alignment.CenterVertically) {
            onSnooze?.let { TextAction("NOT NOW", palette, m, it) }
            onDontAsk?.let { TextAction("DON'T ASK", palette, m, it) }
            onClose?.let { TextAction("CLOSE", palette, m, it) }
        }
    }
}

/** The four mode chips as an inline row, for editing a drive's mode in the trip list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DriveModeRow(current: String?, palette: Palette, m: Metrics, onPick: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(m.gap / 2),
        verticalArrangement = Arrangement.spacedBy(m.gap / 2)
    ) {
        DRIVE_MODES.forEach { mode -> ModeChip(mode, selected = mode == current, palette, m) { onPick(mode) } }
    }
}

@Composable
private fun ModeChip(text: String, selected: Boolean, palette: Palette, m: Metrics, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text = text,
        color = if (selected) palette.ground else palette.numeral,
        fontSize = m.read,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) palette.accent else palette.trackSoft)
            .border(1.dp, if (selected) palette.accent else palette.track, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = m.pad * 0.8f, vertical = m.pad * 0.4f)
    )
}

@Composable
private fun TextAction(text: String, palette: Palette, m: Metrics, onClick: () -> Unit) {
    Text(
        text = text,
        color = palette.dim,
        fontSize = m.body,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = m.gap / 2, horizontal = m.gap / 2)
    )
}
