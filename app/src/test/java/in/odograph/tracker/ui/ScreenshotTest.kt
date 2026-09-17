package `in`.odograph.tracker.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import `in`.odograph.tracker.record.TripRecorderService
import `in`.odograph.tracker.ui.theme.Direction
import `in`.odograph.tracker.ui.theme.ThemeMode
import `in`.odograph.tracker.ui.theme.paletteFor
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real composables to PNGs so the UI can be reviewed without a device.
 *
 * Robolectric stubs graphics by default, which is why a bounds-based layout check can pass while
 * the pixels are nonsense. NATIVE graphics mode swaps in a real Skia backend, so what comes out
 * here is the shipping Gauge.kt drawing, not an approximation of it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    private val live = TripRecorderService.LiveState(
        hasFix = true, speedMps = 24.6f, distanceM = 18_432.0,
        elapsedS = 1_484, maxSpeedMps = 31.9f, movingS = 1_219, tripId = 1
    )

    /**
     * Everything the drive screen can show, all at once: SOC, both range estimates, the odometer
     * with drift, this ride's energy and cost, lifetime energy and a climb. The sparse fixture
     * above leaves all of these null, so it renders perhaps a third of the shipping screen —
     * a screenshot of it cannot show a crowding or overflow problem in the stat rows.
     */
    private val liveFull = live.copy(
        batterySocPercent = 63.0,
        batteryCharging = false,
        telematicsConnected = true,
        batteryMileageKmPerKwh = 6.42,
        batteryRangeAtFullKm = 331.0,
        batteryRangeKm = 208.0,
        mgBatteryRangeKm = 214.0,
        batteryTotalKwh = 1_284.36,
        tripEnergyKwh = 2.87,
        tripCostInr = 23.40,
        elevGainM = 184.0,
        elevLossM = 142.0,
        odoKm = 20_431.0,
        odoDriftKm = 1.8,
        speedLimitKmh = 80
    )

    private val route = List(140) { i ->
        val t = i / 18.0
        (12.9716 + t * 0.010 + kotlin.math.sin(t * 2.1) * 0.004) to
            (77.5946 + t * 0.016 + kotlin.math.cos(t * 1.6) * 0.005)
    }

    /**
     * captureToImage() goes through PixelCopy, which needs a real window Robolectric does not
     * have. Drawing the decor view into a software canvas sidesteps that and still exercises the
     * genuine Compose draw path.
     */
    private fun shoot(name: String) {
        compose.waitForIdle()
        val view: View = compose.activity.window.decorView
        val metrics = compose.activity.resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, w, h)
        compose.waitForIdle()

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))

        val f = File(outDir, "$name.png")
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertThat(f.length()).`as`("$name.png should not be empty").isGreaterThan(1_000L)
        println("SHOT ${f.absolutePath} ${w}x$h ${f.length()}b")
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `driver ion night`() {
        compose.setContent {
            DriverScreen(live, 88.6f, Direction.ION, paletteFor(Direction.ION, night = true))
        }
        shoot("01-driver-ion-night")
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `driver chrono night`() {
        compose.setContent {
            DriverScreen(live, 88.6f, Direction.CHRONO, paletteFor(Direction.CHRONO, night = true))
        }
        shoot("02-driver-chrono-night")
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `driver vector night`() {
        compose.setContent {
            DriverScreen(live, 88.6f, Direction.VECTOR, paletteFor(Direction.VECTOR, night = true))
        }
        shoot("03-driver-vector-night")
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `driver audi night`() {
        compose.setContent {
            DriverScreen(live, 88.6f, Direction.AUDI, paletteFor(Direction.AUDI, night = true))
        }
        shoot("03b-driver-audi-night")
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `driver audi day`() {
        compose.setContent {
            DriverScreen(live, 88.6f, Direction.AUDI, paletteFor(Direction.AUDI, night = false))
        }
        shoot("04b-driver-audi-day")
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `driver audi overspeeding`() {
        compose.setContent {
            DriverScreen(
                live.copy(overLimit = true, speedLimitKmh = 80, speedMps = 29.4f),
                104f, Direction.AUDI, paletteFor(Direction.AUDI, night = true)
            )
        }
        shoot("05b-driver-audi-overspeed")
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `driver ion day`() {
        compose.setContent {
            DriverScreen(live, 88.6f, Direction.ION, paletteFor(Direction.ION, night = false))
        }
        shoot("04-driver-ion-day")
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `driver overspeeding`() {
        compose.setContent {
            DriverScreen(
                live.copy(overLimit = true, speedLimitKmh = 80, speedMps = 29.4f),
                104f, Direction.ION, paletteFor(Direction.ION, night = true)
            )
        }
        shoot("05-driver-overspeed")
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `driver acquiring gps`() {
        compose.setContent {
            DriverScreen(
                TripRecorderService.LiveState(hasFix = false),
                0f, Direction.ION, paletteFor(Direction.ION, night = true)
            )
        }
        shoot("06-driver-acquiring")
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `detailed with route`() {
        compose.setContent {
            DetailScreen(
                live = live, smoothedKmh = 88.6f, route = route, slowestKmMps = 3.4,
                showTiles = false,
                direction = Direction.ION, palette = paletteFor(Direction.ION, night = true)
            )
        }
        shoot("07-detailed-ion-night")
    }



    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `detailed vector day`() {
        compose.setContent {
            DetailScreen(
                live = live, smoothedKmh = 88.6f, route = route, slowestKmMps = 3.4,
                showTiles = false,
                direction = Direction.VECTOR, palette = paletteFor(Direction.VECTOR, night = false)
            )
        }
        shoot("08-detailed-vector-day")
    }

    @Test
    @Config(qualifiers = "w533dp-h300dp-land")
    fun `driver on the small 800x480 viewport`() {
        compose.setContent {
            DriverScreen(live, 88.6f, Direction.ION, paletteFor(Direction.ION, night = true))
        }
        shoot("09-driver-small-800x480")
    }

    @Test
    @Config(qualifiers = "w960dp-h360dp-land")
    fun `driver on a wide short head unit`() {
        compose.setContent {
            DriverScreen(live, 88.6f, Direction.CHRONO, paletteFor(Direction.CHRONO, night = true))
        }
        shoot("10-driver-wide-1920x720")
    }

    // ---------- the full instrument: every optional stat present ----------

    /**
     * Every state an MG frame can put the car in, shot at the box's own window.
     *
     * One PNG per scenario, named after it, so the whole range — flat, critical, charging fast,
     * full and plugged in, a frame with no SOC, the link dropped — can be reviewed side by side
     * without a device. [DriverScenarioTest] asserts these same states do not break; this is for
     * looking at them.
     */
    @Test
    @Config(qualifiers = "w1291dp-h726dp-land")
    fun `every mg scenario on the box window`() {
        val scenarios = `in`.odograph.tracker.core.MgFixtures.SCENARIOS
        val state = androidx.compose.runtime.mutableStateOf(scenarios.first().live)
        compose.setContent {
            DriverScreen(
                state.value, mpsToKmh(state.value.speedMps),
                Direction.ION, paletteFor(Direction.ION, night = true)
            )
        }
        scenarios.forEachIndexed { i, scenario ->
            state.value = scenario.live
            compose.waitForIdle()
            shoot("mg-%02d-%s".format(i, scenario.name.replace(Regex("[^a-z0-9]+"), "-")))
        }
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `driver fully populated balanced`() {
        compose.setContent {
            DriverScreen(liveFull, 88.6f, Direction.ION, paletteFor(Direction.ION, night = true))
        }
        shoot("11-driver-full-balanced")
    }

    @Test
    @Config(qualifiers = "w1291dp-h181dp-land")
    fun `driver fully populated wide band`() {
        compose.setContent {
            DriverScreen(liveFull, 88.6f, Direction.ION, paletteFor(Direction.ION, night = true))
        }
        shoot("12-driver-full-wide")
    }

    @Test
    @Config(qualifiers = "w645dp-h726dp")
    fun `driver fully populated tall`() {
        compose.setContent {
            DriverScreen(liveFull, 88.6f, Direction.ION, paletteFor(Direction.ION, night = true))
        }
        shoot("13-driver-full-tall")
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `driver fully populated day`() {
        compose.setContent {
            DriverScreen(
                liveFull.copy(batteryCharging = true, batterySocPercent = 22.0),
                88.6f, Direction.AUDI, paletteFor(Direction.AUDI, night = false)
            )
        }
        shoot("14-driver-full-day-charging")
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `routes screen`() {
        val ctx = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()

        // Room refuses main-thread access, and the screen loads on Dispatchers.IO, so seeding
        // and waiting both have to happen off the test thread.
        val seeder = Thread {
            val dao = `in`.odograph.tracker.data.OdographDb.get(ctx).dao()
            val home = dao.insertPlace(
                `in`.odograph.tracker.data.PlaceEntity(
                    lat = 12.9716, lon = 77.5946, visits = 47, label = "Home"
                )
            )
            val office = dao.insertPlace(
                `in`.odograph.tracker.data.PlaceEntity(
                    lat = 12.9698, lon = 77.7500, visits = 44, label = "Office"
                )
            )
            val airport = dao.insertPlace(
                `in`.odograph.tracker.data.PlaceEntity(
                    lat = 13.1986, lon = 77.7066, visits = 6, autoName = "Devanahalli"
                )
            )
            fun seed(from: Long, to: Long, times: Int, metres: Double, seconds: Long) {
                repeat(times) { n ->
                    val startedAt = System.currentTimeMillis() - (n + 1) * 3_600_000L
                    val id = dao.startTrip(startedAt)
                    dao.finishTrip(
                        id, startedAt + seconds * 1000, metres, seconds,
                        (seconds * 0.82).toLong(), 27.5f, metres / seconds, 3.4,
                        0.0, 0.0
                    )
                    dao.setTripPlaces(id, from, to)
                }
            }
            // Stamp them inside the current calendar month so the default MONTH filter shows them.
            seed(home, office, 47, 18_432.0, 1_484)
            seed(office, home, 44, 19_010.0, 1_702)
            seed(home, airport, 6, 41_200.0, 2_940)
        }
        seeder.start()
        seeder.join()


        compose.setContent { RoutesScreen(paletteFor(Direction.ION, night = true)) }
        repeat(40) {
            compose.waitForIdle()
            Thread.sleep(25)
        }
        shoot("12-routes")
    }

    // The box is 1920x1080 at 238 dpi, so its full window is 1291 x 726 dp and its split-screen
    // divider can put Odograph in anything down to a quarter of that height.
    @Test
    @Config(qualifiers = "w1291dp-h726dp-land")
    fun `box full window`() {
        compose.setContent {
            DriverScreen(live, 88.6f, Direction.AUDI, paletteFor(Direction.AUDI, night = true))
        }
        shoot("20-box-full-1291x726")
    }

    @Test
    @Config(qualifiers = "w1291dp-h363dp-land")
    fun `box split half height`() {
        compose.setContent {
            DriverScreen(live, 88.6f, Direction.ION, paletteFor(Direction.ION, night = true))
        }
        shoot("21-box-split-half")
    }

    @Test
    @Config(qualifiers = "w1291dp-h242dp-land")
    fun `box split third height`() {
        compose.setContent {
            DriverScreen(live, 88.6f, Direction.ION, paletteFor(Direction.ION, night = true))
        }
        shoot("22-box-split-third")
    }

    @Test
    @Config(qualifiers = "w1291dp-h181dp-land")
    fun `box split quarter height`() {
        compose.setContent {
            DriverScreen(live, 88.6f, Direction.ION, paletteFor(Direction.ION, night = true))
        }
        shoot("23-box-split-quarter")
    }

    @Test
    @Config(qualifiers = "w645dp-h726dp")
    fun `box split vertical`() {
        compose.setContent {
            DriverScreen(live, 88.6f, Direction.ION, paletteFor(Direction.ION, night = true))
        }
        shoot("24-box-split-vertical")
    }

    @Test
    @Config(qualifiers = "w1291dp-h242dp-land")
    fun `box split third detailed`() {
        compose.setContent {
            DetailScreen(
                live = live, smoothedKmh = 88.6f, route = route, slowestKmMps = 3.4,
                showTiles = false, direction = Direction.ION,
                palette = paletteFor(Direction.ION, night = true)
            )
        }
        shoot("25-box-split-third-detailed")
    }

    @Test
    @Config(qualifiers = "w1291dp-h726dp-land")
    fun `detailed full window`() {
        compose.setContent {
            DetailScreen(
                live = live, smoothedKmh = 88.6f, route = route, slowestKmMps = 3.4,
                showTiles = false, direction = Direction.ION,
                palette = paletteFor(Direction.ION, night = true)
            )
        }
        shoot("26-detailed-full")
    }

    @Test
    @Config(qualifiers = "w1291dp-h181dp-land")
    fun `detailed split quarter`() {
        compose.setContent {
            DetailScreen(
                live = live, smoothedKmh = 88.6f, route = route, slowestKmMps = 3.4,
                showTiles = false, direction = Direction.ION,
                palette = paletteFor(Direction.ION, night = true)
            )
        }
        shoot("27-detailed-split-quarter")
    }

    @Test
    @Config(qualifiers = "w645dp-h726dp")
    fun `detailed split vertical`() {
        compose.setContent {
            DetailScreen(
                live = live, smoothedKmh = 88.6f, route = route, slowestKmMps = 3.4,
                showTiles = false, direction = Direction.ION,
                palette = paletteFor(Direction.ION, night = true)
            )
        }
        shoot("28-detailed-split-vertical")
    }

    @Test
    @Config(qualifiers = "w1291dp-h726dp-land")
    fun `trips screen with trace`() {
        val ctx = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val seeder = Thread {
            val dao = `in`.odograph.tracker.data.OdographDb.get(ctx).dao()
            val id = dao.startTrip(1_700_000_000_000L)
            route.forEachIndexed { i, (lat, lon) ->
                dao.appendPoint(
                    `in`.odograph.tracker.data.PointEntity(
                        tripId = id, t = 1_700_000_000_000L + i * 1000L,
                        lat = lat, lon = lon, speedMps = 20f,
                        bearingDeg = null, altitudeM = null, accuracyM = 5f,
                        interpolated = false
                    )
                )
            }
            dao.finishTrip(id, 1_700_000_000_000L + route.size * 1000L, 18_432.0, 1_484, 1_219, 27.5f, 12.4, 3.4, 0.0, 0.0)
        }
        seeder.start()
        seeder.join()

        compose.setContent { TripListScreen(showTiles = false, paletteFor(Direction.ION, night = true)) }
        repeat(40) {
            compose.waitForIdle()
            Thread.sleep(25)
        }
        shoot("13-trips-trace")
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun `setup screen`() {
        compose.setContent {
            SetupScreen(
                direction = Direction.ION, themeMode = ThemeMode.AUTO, showTiles = true,
                telematics = true,
                palette = paletteFor(Direction.ION, night = true),
                onDirection = {}, onThemeMode = {}, onTiles = {}, onTelematics = {}
            )
        }
        shoot("11-setup")
    }
}
