package `in`.odograph.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.ui.theme.Palette

/**
 * OutlinedTextField colors matched to the instrument palette, with the input always readable:
 * typed text is the page's bright numeral, the caret and focused border the accent, and the label
 * a stepped-up gray (readable on both night and day grounds) while unfocused. Without this the
 * Material default (near-black on a white field) renders as dark-on-dark text against the dark
 * track, so typed input is effectively invisible — the "camouflaged" bug the driver reported: the
 * caret moved, the field looked empty. Shared by every field in the app (charge edits, setup).
 */
@Composable
fun textFieldColors(palette: Palette) =
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = palette.numeral,
        unfocusedTextColor = palette.numeral,
        cursorColor = palette.accent,
        focusedLabelColor = palette.accent,
        unfocusedLabelColor = palette.dim,
        focusedBorderColor = palette.accent.copy(alpha = 0.9f),
        unfocusedBorderColor = palette.track,
        focusedContainerColor = palette.ground,
        unfocusedContainerColor = palette.ground
    )

/** A stat in a fixed slot. Position never changes, so the glance is a jump, not a search. */
@Composable
fun Stat(
    value: String,
    label: String,
    palette: Palette,
    m: Metrics,
    size: TextUnit = m.hero,
    modifier: Modifier = Modifier,
    contentColor: Color = palette.numeral
) {
    Column(modifier) {
        Text(
            text = value,
            color = contentColor,
            fontSize = size,
            lineHeight = size * 1.05f,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
        Text(
            text = label,
            color = palette.label,
            fontSize = m.label,
            letterSpacing = 1.6.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * A dense caption-and-value pair for the reference screens.
 *
 * The value carries the page's numeral colour so it outranks its own caption — it used to be
 * drawn in the same dim grey at the same size, which left the number and the word describing it
 * typographically indistinguishable. The size is deliberately unchanged: these screens are read
 * parked, and their layouts are sized against this metric. The driving screen does not use this;
 * it has its own step, because none of this is legible at speed.
 */
@Composable
fun SmallStat(
    value: String,
    label: String,
    palette: Palette,
    m: Metrics,
    onClick: (() -> Unit)? = null
) {
    val click = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(horizontalArrangement = Arrangement.spacedBy(m.gap / 3), modifier = click) {
        Text(label, color = palette.label, fontSize = m.label, letterSpacing = 1.1.sp, maxLines = 1)
        Text(
            text = value,
            color = palette.numeral,
            fontSize = m.label,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/** Deliberately large: the projection link returns a single, imprecise touch. */
@Composable
fun Chip(text: String, selected: Boolean, palette: Palette, m: Metrics, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (selected) palette.ground else palette.dim,
        fontSize = m.chipText,
        letterSpacing = 1.2.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (selected) palette.accent else palette.trackSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = m.chipPadH, vertical = m.chipPadV)
    )
}
