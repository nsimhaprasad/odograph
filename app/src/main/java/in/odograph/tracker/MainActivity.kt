package `in`.odograph.tracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import `in`.odograph.tracker.record.TripRecorderService
import `in`.odograph.tracker.ui.OdographApp

class MainActivity : ComponentActivity() {

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            startRecordingIfPermitted()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A driver should never have to poke the screen to keep the cluster visible.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startRecordingIfPermitted() else requestPermissions.launch(missing.toTypedArray())

        setContent { OdographApp() }
    }

    /**
     * Android 14 refuses to start a location-typed foreground service without the location
     * permission already granted, so this is deliberately gated rather than fired on create.
     */
    private fun startRecordingIfPermitted() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val svc = Intent(this, TripRecorderService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }

        // Background location is a separate, second grant on Android 10+ and can only be asked
        // for once the foreground one is held.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        }
    }
}
