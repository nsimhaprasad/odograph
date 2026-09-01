package `in`.odograph.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.ui.theme.Palette

/** A stat in a fixed slot. Position never changes, so the glance is a jump, not a search. */
@Composable
fun Stat(
    value: String,
    label: String,
    palette: Palette,
    m: Metrics,
    size: TextUnit = m.hero,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(
            text = value,
            color = palette.numeral,
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

@Composable
fun SmallStat(value: String, label: String, palette: Palette, m: Metrics) {
    Row(horizontalArrangement = Arrangement.spacedBy(m.gap / 3)) {
        Text(label, color = palette.label, fontSize = m.label, letterSpacing = 1.1.sp, maxLines = 1)
        Text(
            text = value,
            color = palette.dim,
            fontSize = m.label,
            fontWeight = FontWeight.Medium,
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
