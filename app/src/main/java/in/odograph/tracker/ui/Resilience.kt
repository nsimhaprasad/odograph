package `in`.odograph.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.diag.Diagnostics
import `in`.odograph.tracker.ui.theme.Palette

/**
 * Containment: one broken feature must not take the car's instrument cluster with it.
 *
 * This app is not a phone app that can be closed and reopened. It is the display in a moving
 * vehicle, and the process that draws it is the same process that records the drive — so a
 * divide-by-zero in a statistics panel does not merely blank a screen, it stops the recording,
 * drops the trip, and leaves the driver watching a dead display at seventy on a motorway.
 *
 * The rule, therefore: a feature that fails is switched off and says so. The tabs keep working,
 * the drive keeps recording, and the driver is told which part is unavailable rather than being
 * shown a system dialog offering to close the app.
 *
 * There is deliberately no catch-all around composition, because Compose does not permit one —
 * `try` around a composable call is a compile error, by design: a half-built subtree cannot be
 * safely resumed or discarded from inside itself. Containment therefore has to come from the
 * failure never reaching composition in the first place, which is the honest place for it anyway.
 * Every screen loads its data through [loaded], which cannot throw, and renders a
 * [FeatureUnavailable] panel when the load did not work. Composition is then handed values that
 * already exist, and its only remaining job is to not do arithmetic that can throw on them.
 */

/**
 * What the driver sees instead of the feature.
 *
 * Names the part that failed and nothing else on the screen, because the point is that everything
 * else still works. The reason is shown because the box has no logcat and the driver is the only
 * one in a position to report it.
 */
@Composable
fun FeatureUnavailable(
    name: String,
    error: Throwable,
    palette: Palette,
    m: Metrics,
    onRetry: (() -> Unit)? = null
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.ground)
            .let { if (onRetry != null) it.clickable(onClick = onRetry) else it }
            .padding(m.pad),
        verticalArrangement = Arrangement.spacedBy(m.gap / 2)
    ) {
        Text(
            text = "$name IS UNAVAILABLE",
            color = palette.warn,
            fontSize = m.label,
            letterSpacing = 2.sp
        )
        Text(
            text = "Everything else still works, and the drive is still being recorded." +
                if (onRetry != null) " Tap to try this screen again." else "",
            color = palette.dim,
            fontSize = m.body,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "${error.javaClass.simpleName}: ${error.message ?: "no detail"}",
            color = palette.label,
            fontSize = m.label,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().padding(top = m.gap / 2)
        )
    }
}

/**
 * A value a screen loads, which may simply not have worked.
 *
 * Loading is where the exceptions actually are — a database that has just migrated, a row written
 * by an older build, an arithmetic edge in a statistic nobody has hit yet — and none of it passes
 * through [FeatureBoundary], because by then the coroutine has left that frame behind.
 */
sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>
    data class Ready<T>(val value: T) : Loaded<T>
    data class Failed(val error: Throwable) : Loaded<Nothing>
}

/**
 * Runs a load and never throws.
 *
 * The failure is kept rather than swallowed: a panel that is empty because there is nothing to
 * show and a panel that is empty because it broke look identical, and only one of them is worth
 * telling anybody about.
 */
inline fun <T> loaded(block: () -> T): Loaded<T> =
    try {
        Loaded.Ready(block())
    } catch (t: Throwable) {
        // The bookkeeping is itself guarded. A recovery path that can throw is not a recovery
        // path, and this one runs precisely when something has already gone wrong — the moment it
        // is least acceptable to add a second failure on top of the first.
        try {
            Diagnostics.crumb("load failed: ${t.javaClass.simpleName}: ${t.message}")
        } catch (_: Throwable) {
        }
        Loaded.Failed(t)
    }
