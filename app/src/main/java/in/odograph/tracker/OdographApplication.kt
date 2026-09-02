package `in`.odograph.tracker

import android.app.Application
import `in`.odograph.tracker.diag.Diagnostics

class OdographApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this)
        // Ask the OS why we died last time, before anything that might die again runs.
        Diagnostics.shipExitReasons(this)
        Diagnostics.crumb("Application.onCreate complete")
    }
}
