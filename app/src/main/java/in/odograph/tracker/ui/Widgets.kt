package `in`.odograph.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.ui.theme.Palette

/** A stat in a fixed slot. Position never changes, so the glance is a jump, not a search. */
@Composable
fun Stat(value: String, label: String, palette: Palette, size: Int = 60) {
    Column {
        Text(
            text = value,
            color = palette.numeral,
            fontSize = size.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = label,
            color = palette.label,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun SmallStat(value: String, label: String, palette: Palette) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = palette.label, fontSize = 11.sp, letterSpacing = 1.4.sp)
        Text(value, color = palette.dim, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/** Deliberately large: the projection link returns a single, imprecise touch. */
@Composable
fun Chip(text: String, selected: Boolean, palette: Palette, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (selected) palette.ground else palette.dim,
        fontSize = 13.sp,
        letterSpacing = 1.5.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(if (selected) palette.accent else palette.trackSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp)
    )
}
