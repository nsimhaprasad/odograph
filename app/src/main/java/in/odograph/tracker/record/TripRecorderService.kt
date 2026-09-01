package `in`.odograph.tracker.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import `in`.odograph.tracker.alert.AlertConfig
import `in`.odograph.tracker.alert.AlertSound
import `in`.odograph.tracker.alert.SpeedAlert
import `in`.odograph.tracker.core.Fix
import `in`.odograph.tracker.core.Geo
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.data.PointEntity
import `in`.odograph.tracker.server.DashboardServer
import `in`.odograph.tracker.sync.Outbound
import `in`.odograph.tracker.ui.theme.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TripRecorderService : Service() {

    data class LiveState(
        val hasFix: Boolean = false,
        val speedMps: Float = 0f,
        val distanceM: Double = 0.0,
        val elapsedS: Long = 0,
        val maxSpeedMps: Float = 0f,
        val movingS: Long = 0,
        val tripId: Long = -1,
        val overLimit: Boolean = false,
        val speedLimitKmh: Int = 0
    )

    companion object {
        const val CHANNEL_ID = "odograph_recording"
        const val NOTIFICATION_ID = 1
        private const val ACCURACY_LIMIT_M = 25f
        private const val SPEED_ACCURACY_LIMIT_M = 15f

        private val _state = MutableStateFlow(LiveState())
        val state: StateFlow<LiveState> = _state
    }

    private lateinit var source: LocationSource
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tripId: Long = -1
    private var lastFix: Fix? = null
    private var startedAt: Long? = null
    private lateinit var settings: Settings
    private lateinit var alertSound: AlertSound
    private var speedAlert = SpeedAlert(AlertConfig(limitKmh = 0f))
    private var configuredLimit = -1

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        source = GnssLocationSource(this)
        settings = Settings(this)
        alertSound = AlertSound(this)
        runCatching { alertSound.prepare(settings.alertMode) }

        io.launch {
            val dao = OdographDb.get(this@TripRecorderService).dao()
            // startedAt is patched by the first real fix; GNSS time is the authority.
            tripId = TripRecovery.recoverAndStart(dao, nowFromGnss = null)
            _state.value = LiveState(tripId = tripId)
            source.start { fix -> io.launch { record(fix) } }

            // Everything below is best-effort and entirely optional. The hotspot is usually up,
            // but recording must behave identically when it is not, so both are wrapped and
            // neither is on the capture path.
            runCatching { DashboardServer.start(this@TripRecorderService) }
            runCatching {
                val settings = Settings(this@TripRecorderService)
                if (settings.webhookUrl.isNotBlank()) {
                    Outbound.syncPending(
                        this@TripRecorderService, settings.webhookUrl, settings.deviceId
                    )
                }
            }
        }
    }

    private fun record(fix: Fix) {
        val dao = OdographDb.get(this).dao()
        dao.appendPoint(
            PointEntity(
                tripId = tripId, t = fix.t, lat = fix.lat, lon = fix.lon,
                speedMps = fix.speedMps, bearingDeg = null, altitudeM = null,
                accuracyM = fix.accuracyM, interpolated = fix.interpolated
            )
        )

        if (startedAt == null) {
            // First real fix: the trip row was created with a placeholder time because the
            // system clock is untrustworthy without NTP. Correct it now.
            startedAt = fix.t
            dao.setStartedAt(tripId, fix.t)
            if (dao.tripById(tripId)?.startLat == null) dao.setOrigin(tripId, fix.lat, fix.lon)
        }

        val prev = lastFix
        val added = if (prev != null &&
            prev.accuracyM <= ACCURACY_LIMIT_M && fix.accuracyM <= ACCURACY_LIMIT_M
        ) Geo.haversineMetres(prev.lat, prev.lon, fix.lat, fix.lon) else 0.0

        val speedKmh = fix.speedMps * 3.6f
        val limit = settings.speedLimitKmh
        if (limit != configuredLimit) {
            configuredLimit = limit
            speedAlert.reconfigure(AlertConfig(limitKmh = limit.toFloat()))
        }
        val alert = speedAlert.update(speedKmh, fix.t)
        if (alert.sound) runCatching { alertSound.play(settings.alertMode) }

        val cur = _state.value
        _state.value = cur.copy(
            hasFix = true,
            speedMps = fix.speedMps,
            distanceM = cur.distanceM + added,
            elapsedS = (fix.t - (startedAt ?: fix.t)) / 1000,
            maxSpeedMps = if (fix.accuracyM <= SPEED_ACCURACY_LIMIT_M)
                maxOf(cur.maxSpeedMps, fix.speedMps) else cur.maxSpeedMps,
            movingS = cur.movingS + if (fix.speedMps > 0.5f) 1 else 0,
            tripId = tripId,
            overLimit = alert.overLimit,
            speedLimitKmh = limit
        )
        lastFix = fix
    }

    private fun startInForeground() {
        val n = buildNotification()
        // The 3-arg overload only exists from API 29; minSdk is 26.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Odograph")
            .setContentText("Recording this drive")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { DashboardServer.stop() }
        if (this::alertSound.isInitialized) runCatching { alertSound.release() }
        if (this::source.isInitialized) source.stop()
        io.cancel()
        super.onDestroy()
    }
}
