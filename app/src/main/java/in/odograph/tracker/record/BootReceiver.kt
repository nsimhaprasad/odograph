package `in`.odograph.tracker.record

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import `in`.odograph.tracker.diag.Diagnostics

/**
 * Brings recording back on its own after the box powers on, before anyone opens the app: a
 * location-typed foreground service cannot start without the fine-location grant, and the grant
 * only happens the first time the app is opened, so this quietly does nothing until then — after
 * that it is what makes the drive start the moment the ignition does, no icon taps involved.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != QUICKBOOT_POWERON
        ) return

        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Diagnostics.crumb("boot: no fine location yet, recorder stays off")
            return
        }

        Diagnostics.crumb("boot: auto-starting recorder")
        val svc = Intent(context, TripRecorderService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        }.onFailure {
            Diagnostics.crumb("boot: recorder start failed: $it")
        }
    }

    private companion object {
        const val QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
