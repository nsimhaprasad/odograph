package `in`.odograph.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shown instead of the app when the previous run died.
 *
 * The box has no logcat, so without this a crash loop is completely opaque: the app would just
 * relaunch into the same failure with nothing to read.
 */
@Composable
fun CrashScreen(report: String, onDismiss: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF120A0B))
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "PREVIOUS RUN CRASHED",
            color = Color(0xFFFF6B6B),
            fontSize = 14.sp,
            letterSpacing = 2.sp
        )
        Text(
            text = "TAP ANYWHERE TO DISMISS AND RETRY",
            color = Color(0xFF8B96A5),
            fontSize = 10.sp,
            letterSpacing = 1.5.sp,
            modifier = Modifier
                .padding(top = 4.dp, bottom = 14.dp)
                .clickable { onDismiss() }
        )
        Text(
            text = report,
            color = Color(0xFFE6E9EE),
            fontSize = 10.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
