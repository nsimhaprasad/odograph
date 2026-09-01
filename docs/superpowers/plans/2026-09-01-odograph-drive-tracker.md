# Odograph Drive Tracker — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An Android app that auto-starts when the car starts, records every drive from GNSS with no interaction, survives the ignition-off power cut without losing data, shows a glanceable cluster on the car screen, and exports the history for analysis on a Mac.

**Architecture:** A foreground service is the sole writer to a WAL-mode SQLite database, appending one GNSS fix per second. Trips are never "closed" — they are *recovered* on the next boot, because the device dies without warning. Two read-only faces sit on that database: a Compose cluster for the car screen and an embedded HTTP server for analysis.

**Tech Stack:** Kotlin, Jetpack Compose, Room (SQLite/WAL), `LocationManager` (deliberately *not* Fused — no Play Services dependency), Ktor embedded server (CIO), JUnit5 + Robolectric + Truth, Gradle KTS, AGP 8.5, JDK 17.

**Spec:** `docs/superpowers/specs/2026-09-01-odograph-design.md`

## Global Constraints

- `minSdk 26`, `targetSdk 34`, `compileSdk 34`. JDK 17. Kotlin JVM target 17.
- **No Google Play Services dependency anywhere.** Presence on the target box is unverified.
- **No `System.currentTimeMillis()` for any trip or point timestamp.** The box has no SIM, therefore no NTP, therefore a possibly-wrong clock at boot. Use `Location.getTime()` (GNSS UTC).
- **No bitmaps, no hardcoded `dp` in the cluster.** All instrument graphics are drawn on `Canvas` in fractions of the viewport. The projected resolution is unknown.
- Room must run with `journalMode = WAL` and commit each point as it arrives.
- All money-free, account-free, network-free: the app must be fully functional with airplane mode on.
- Package: `in.odograph.tracker`. Application ID identical.
- Every task ends with a passing test run and a commit.

---

## File Structure

| Path | Responsibility |
|---|---|
| `app/build.gradle.kts` | Module config, deps, signing |
| `app/src/main/AndroidManifest.xml` | Permissions, service type, boot receiver |
| `.../data/Entities.kt` | `TripEntity`, `PointEntity` |
| `.../data/OdographDao.kt` | All queries. No logic. |
| `.../data/OdographDb.kt` | Room database, WAL config |
| `.../core/Geo.kt` | Pure maths: haversine, speed filters. No Android imports. |
| `.../core/TripStats.kt` | Pure stat computation from a point list |
| `.../core/RouteCluster.kt` | Pure origin/destination clustering |
| `.../record/LocationSource.kt` | Interface + `GnssLocationSource` impl |
| `.../record/TripRecorderService.kt` | Foreground service, sole DB writer |
| `.../record/TripRecovery.kt` | Boot-time orphan close + origin backfill |
| `.../record/BootReceiver.kt` | `BOOT_COMPLETED` → start service |
| `.../ui/theme/Palette.kt` | Day/night token sets |
| `.../ui/gauge/Gauge.kt` | Canvas instrument, direction-parameterised |
| `.../ui/DriverScreen.kt` | Three fixed numbers + gauge |
| `.../ui/DetailScreen.kt` | Gauge + route trace + charts |
| `.../export/Exporters.kt` | CSV + GPX writers |
| `.../export/ShareIntent.kt` | `FileProvider` + `ACTION_SEND` |
| `.../server/DashboardServer.kt` | Ktor CIO server + mDNS |
| `.../probe/ProbeScreen.kt` | Phase 0 device report |

---

# PHASE 0 — De-risk the device before building anything

> Everything in Phase 1+ is wasted effort if the box refuses to install APKs or has no usable
> GNSS. Phase 0 answers every device unknown in one install and must be run on the real
> hardware before Task 3 begins.

### Task 1: Buildable project skeleton

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `local.properties`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/in/odograph/tracker/MainActivity.kt`
- Test: `app/src/test/java/in/odograph/tracker/SmokeTest.kt`

**Interfaces:**
- Produces: a `assembleDebug` task that emits `app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 1: Write `local.properties` pointing at the SDK**

```properties
sdk.dir=/Users/simhaprasad/Library/Android/sdk
```

- [ ] **Step 2: Write `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Odograph"
include(":app")
```

- [ ] **Step 3: Write root `build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}
```

- [ ] **Step 4: Write `app/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}
android {
    namespace = "in.odograph.tracker"
    compileSdk = 34
    defaultConfig {
        applicationId = "in.odograph.tracker"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("io.ktor:ktor-server-core:2.3.11")
    implementation("io.ktor:ktor-server-cio:2.3.11")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
}
```

- [ ] **Step 5: Write the manifest**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="Odograph"
        android:supportsRtl="true"
        android:theme="@style/Theme.Material3.DynamicDark.NoActionBar">
        <activity android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="landscape">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 6: Write `MainActivity.kt` (placeholder — replaced in Task 2)**

```kotlin
package `in`.odograph.tracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("Odograph") }
    }
}
```

- [ ] **Step 7: Write the smoke test**

```kotlin
package `in`.odograph.tracker

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SmokeTest {
    @Test fun `build wiring is alive`() {
        assertThat(2 + 2).isEqualTo(4)
    }
}
```

- [ ] **Step 8: Generate the Gradle wrapper and build**

Run:
```bash
cd /Users/simhaprasad/Documents/workstation/personal/drive-dashboard
gradle wrapper --gradle-version 8.7   # or: use any local gradle once, then ./gradlew after
./gradlew :app:testDebugUnitTest :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`, and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 9: Commit**

```bash
git init
git add -A
git commit -m "chore: android project skeleton that builds a debug apk"
```

---

### Task 2: Device probe — answer every unknown in one install

**Files:**
- Create: `app/src/main/java/in/odograph/tracker/probe/DeviceProbe.kt`
- Create: `app/src/main/java/in/odograph/tracker/probe/ProbeScreen.kt`
- Modify: `app/src/main/java/in/odograph/tracker/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml` (location permissions, FileProvider)
- Create: `app/src/main/res/xml/file_paths.xml`
- Test: `app/src/test/java/in/odograph/tracker/probe/DeviceProbeTest.kt`

**Interfaces:**
- Produces: `DeviceProbe.collect(context: Context): ProbeReport`, `ProbeReport.asText(): String`

- [ ] **Step 1: Write the failing test**

```kotlin
package `in`.odograph.tracker.probe

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceProbeTest {
    @Test fun `report text contains every required field label`() {
        val r = ProbeReport(
            sdkInt = 34, release = "14", manufacturer = "Portronics", model = "TunePrime",
            widthPx = 1280, heightPx = 720, densityDpi = 213, refreshHz = 60f,
            locationProviders = listOf("gps", "network"),
            gpsEnabled = true, hasPlayServices = false,
            systemTimeMs = 1_700_000_000_000L, bootElapsedMs = 42_000L,
            batteryOptimisationIgnored = false, externalDirs = listOf("/storage/emulated/0")
        )
        val text = r.asText()
        listOf("SDK", "SCREEN", "PROVIDERS", "PLAY SERVICES", "SYSTEM TIME", "BATTERY OPT")
            .forEach { assertThat(text).contains(it) }
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*DeviceProbeTest*'`
Expected: FAIL — `Unresolved reference: ProbeReport`

- [ ] **Step 3: Write `DeviceProbe.kt`**

```kotlin
package `in`.odograph.tracker.probe

import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager

data class ProbeReport(
    val sdkInt: Int, val release: String, val manufacturer: String, val model: String,
    val widthPx: Int, val heightPx: Int, val densityDpi: Int, val refreshHz: Float,
    val locationProviders: List<String>, val gpsEnabled: Boolean, val hasPlayServices: Boolean,
    val systemTimeMs: Long, val bootElapsedMs: Long,
    val batteryOptimisationIgnored: Boolean, val externalDirs: List<String>
) {
    fun asText(): String = buildString {
        appendLine("ODOGRAPH DEVICE PROBE")
        appendLine("SDK          : $sdkInt (Android $release)")
        appendLine("DEVICE       : $manufacturer $model")
        appendLine("SCREEN       : ${widthPx}x${heightPx} @ ${densityDpi}dpi ${refreshHz}Hz")
        appendLine("PROVIDERS    : ${locationProviders.joinToString()}")
        appendLine("GPS ENABLED  : $gpsEnabled")
        appendLine("PLAY SERVICES: $hasPlayServices")
        appendLine("SYSTEM TIME  : $systemTimeMs")
        appendLine("UPTIME MS    : $bootElapsedMs")
        appendLine("BATTERY OPT  : ignored=$batteryOptimisationIgnored")
        appendLine("EXT DIRS     : ${externalDirs.joinToString()}")
    }
}

object DeviceProbe {
    fun collect(ctx: Context): ProbeReport {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val dm = DisplayMetrics().also {
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it)
        }
        val play = runCatching {
            ctx.packageManager.getPackageInfo("com.google.android.gms", 0); true
        }.getOrDefault(false)
        return ProbeReport(
            sdkInt = Build.VERSION.SDK_INT, release = Build.VERSION.RELEASE,
            manufacturer = Build.MANUFACTURER, model = Build.MODEL,
            widthPx = dm.widthPixels, heightPx = dm.heightPixels, densityDpi = dm.densityDpi,
            @Suppress("DEPRECATION") refreshHz = wm.defaultDisplay.refreshRate,
            locationProviders = lm.allProviders,
            gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER),
            hasPlayServices = play,
            systemTimeMs = System.currentTimeMillis(),
            bootElapsedMs = SystemClock.elapsedRealtime(),
            batteryOptimisationIgnored = pm.isIgnoringBatteryOptimizations(ctx.packageName),
            externalDirs = ctx.getExternalFilesDirs(null).filterNotNull().map { it.absolutePath }
        )
    }
}
```

- [ ] **Step 4: Run the test again**

Run: `./gradlew :app:testDebugUnitTest --tests '*DeviceProbeTest*'`
Expected: PASS

- [ ] **Step 5: Add permissions and FileProvider to the manifest**

Insert inside `<manifest>` before `<application>`:
```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-feature android:name="android.hardware.location.gps" android:required="false" />
```

Insert inside `<application>`:
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="in.odograph.tracker.files"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

Create `app/src/main/res/xml/file_paths.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <external-files-path name="exports" path="exports/" />
</paths>
```

Add `implementation("androidx.core:core-ktx:1.13.1")` to dependencies.

- [ ] **Step 6: Write `ProbeScreen.kt` with a live time-to-first-fix counter**

```kotlin
package `in`.odograph.tracker.probe

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

@Composable
fun ProbeScreen(ctx: Context, onShare: (String) -> Unit) {
    var report by remember { mutableStateOf(DeviceProbe.collect(ctx).asText()) }
    var fixLog by remember { mutableStateOf("Waiting for first GNSS fix...") }
    val started = remember { SystemClock.elapsedRealtime() }

    DisposableEffect(Unit) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                val ttff = (SystemClock.elapsedRealtime() - started) / 1000.0
                fixLog = buildString {
                    appendLine("FIRST FIX    : ${"%.1f".format(ttff)} s after start")
                    appendLine("LAT/LON      : ${l.latitude}, ${l.longitude}")
                    appendLine("SPEED        : ${l.speed} m/s (hasSpeed=${l.hasSpeed()})")
                    appendLine("ACCURACY     : ${l.accuracy} m")
                    appendLine("GNSS TIME    : ${l.time}")
                    appendLine("CLOCK SKEW   : ${l.time - System.currentTimeMillis()} ms")
                }
            }
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
            @Deprecated("legacy") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener)
        } else fixLog = "FINE_LOCATION not granted"
        onDispose { lm.removeUpdates(listener) }
    }

    Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
        Text(report, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(16.dp))
        Text(fixLog, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(16.dp))
        Row {
            Button(onClick = { report = DeviceProbe.collect(ctx).asText() }) { Text("Refresh") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { onShare(report + "\n" + fixLog) }) { Text("Share report") }
        }
    }
}
```

- [ ] **Step 7: Wire `MainActivity` to the probe with a runtime permission request**

```kotlin
package `in`.odograph.tracker

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import `in`.odograph.tracker.probe.ProbeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityCompat.requestPermissions(this, arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        ), 1)
        setContent {
            ProbeScreen(this) { text ->
                val f = java.io.File(getExternalFilesDir(null), "exports/probe.txt")
                f.parentFile?.mkdirs(); f.writeText(text)
                `in`.odograph.tracker.export.shareFile(this, f, "text/plain")
            }
        }
    }
}
```

- [ ] **Step 8: Write `export/ShareIntent.kt` — Bluetooth for free, no Bluetooth code**

```kotlin
package `in`.odograph.tracker.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

fun shareFile(ctx: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(ctx, "in.odograph.tracker.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Share ${file.name}")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
```

- [ ] **Step 9: Build, install on the real box, and record the answers**

Run:
```bash
./gradlew :app:assembleDebug
# Path A (preferred): adb over WiFi, if Developer options exist on the box
adb connect <BOX_IP>:5555 && adb install -r app/build/outputs/apk/debug/app-debug.apk
# Path B (fallback): copy the APK to a USB stick / SD card and install via the box's file manager
```

Record in `docs/device-probe.md`: screen px, densityDpi, provider list, TTFF seconds,
`hasSpeed`, clock skew, Play Services presence, and **which install path worked**.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat(probe): device probe apk reporting screen, gnss, clock skew and install path"
```

**GATE:** Do not start Task 3 until `docs/device-probe.md` exists with real values from the box.
If sideloading failed on both paths, stop and escalate — that is the one true blocker, and the
remedy is a device setting, not code.

---

# PHASE 1 — Capture core (must never lose a trip)

### Task 3: Pure geo maths

**Files:**
- Create: `app/src/main/java/in/odograph/tracker/core/Geo.kt`
- Test: `app/src/test/java/in/odograph/tracker/core/GeoTest.kt`

**Interfaces:**
- Produces: `Geo.haversineMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double`

- [ ] **Step 1: Write the failing test**

```kotlin
package `in`.odograph.tracker.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GeoTest {
    @Test fun `same point is zero metres`() {
        assertThat(Geo.haversineMetres(12.97, 77.59, 12.97, 77.59)).isWithin(0.01).of(0.0)
    }

    @Test fun `one degree of latitude is about 111 km`() {
        val d = Geo.haversineMetres(12.0, 77.0, 13.0, 77.0)
        assertThat(d).isWithin(500.0).of(111_195.0)
    }

    @Test fun `known Bengaluru pair matches published distance`() {
        // Majestic to Whitefield, ~17.8 km great-circle
        val d = Geo.haversineMetres(12.9767, 77.5713, 12.9698, 77.7500)
        assertThat(d).isWithin(400.0).of(19_380.0)
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests '*GeoTest*'`
Expected: FAIL — `Unresolved reference: Geo`

- [ ] **Step 3: Implement**

```kotlin
package `in`.odograph.tracker.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun haversineMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
```

- [ ] **Step 4: Run and confirm pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*GeoTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/in/odograph/tracker/core/Geo.kt app/src/test/java/in/odograph/tracker/core/GeoTest.kt
git commit -m "feat(core): haversine distance"
```

---

### Task 4: Trip statistics from a point list

**Files:**
- Create: `app/src/main/java/in/odograph/tracker/core/TripStats.kt`
- Test: `app/src/test/java/in/odograph/tracker/core/TripStatsTest.kt`

**Interfaces:**
- Consumes: `Geo.haversineMetres`
- Produces:
  - `data class Fix(val t: Long, val lat: Double, val lon: Double, val speedMps: Float, val accuracyM: Float, val interpolated: Boolean = false)`
  - `data class Stats(val distanceM: Double, val durationS: Long, val movingS: Long, val maxSpeedMps: Float, val avgSpeedMps: Double, val slowestKmSpeedMps: Double)`
  - `TripStats.compute(fixes: List<Fix>): Stats`

- [ ] **Step 1: Write the failing tests — these encode the spec's pinned definitions**

```kotlin
package `in`.odograph.tracker.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TripStatsTest {
    private fun fix(t: Long, lat: Double, lon: Double, s: Float, acc: Float = 5f) =
        Fix(t, lat, lon, s, acc)

    @Test fun `empty input yields zeroed stats`() {
        val s = TripStats.compute(emptyList())
        assertThat(s.distanceM).isEqualTo(0.0)
        assertThat(s.durationS).isEqualTo(0L)
    }

    @Test fun `distance ignores segments with poor accuracy`() {
        val fixes = listOf(
            fix(0, 12.9700, 77.5900, 10f, acc = 5f),
            fix(1000, 12.9800, 77.5900, 10f, acc = 80f),   // rejected: accuracy > 25
            fix(2000, 12.9900, 77.5900, 10f, acc = 5f)
        )
        // only the 2nd->3rd leg counts, ~1.1 km
        assertThat(TripStats.compute(fixes).distanceM).isWithin(200.0).of(1112.0)
    }

    @Test fun `moving time excludes stationary fixes below the threshold`() {
        val fixes = listOf(
            fix(0,     12.97, 77.59, 0.1f),
            fix(1000,  12.97, 77.59, 0.2f),
            fix(2000,  12.97, 77.59, 12.0f),
            fix(3000,  12.97, 77.59, 12.0f)
        )
        assertThat(TripStats.compute(fixes).movingS).isEqualTo(2L)
    }

    @Test fun `max speed ignores low accuracy outliers`() {
        val fixes = listOf(
            fix(0,    12.97, 77.59, 20f, acc = 5f),
            fix(1000, 12.97, 77.59, 90f, acc = 40f),  // GPS glitch, rejected
            fix(2000, 12.97, 77.59, 25f, acc = 5f)
        )
        assertThat(TripStats.compute(fixes).maxSpeedMps).isEqualTo(25f)
    }

    @Test fun `average speed is distance over moving time not wall clock`() {
        val fixes = listOf(
            fix(0,      12.9700, 77.5900, 0f),      // stopped at a signal
            fix(60_000, 12.9700, 77.5900, 0f),
            fix(61_000, 12.9700, 77.5900, 20f),
            fix(62_000, 12.9800, 77.5900, 20f)
        )
        val s = TripStats.compute(fixes)
        assertThat(s.durationS).isEqualTo(62L)
        assertThat(s.movingS).isEqualTo(2L)
        assertThat(s.avgSpeedMps).isGreaterThan(100.0)  // distance/movingS, not /62
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests '*TripStatsTest*'`
Expected: FAIL — `Unresolved reference: TripStats`

- [ ] **Step 3: Implement**

```kotlin
package `in`.odograph.tracker.core

data class Fix(
    val t: Long, val lat: Double, val lon: Double,
    val speedMps: Float, val accuracyM: Float, val interpolated: Boolean = false
)

data class Stats(
    val distanceM: Double, val durationS: Long, val movingS: Long,
    val maxSpeedMps: Float, val avgSpeedMps: Double, val slowestKmSpeedMps: Double
)

object TripStats {
    private const val ACCURACY_LIMIT_M = 25f
    private const val SPEED_ACCURACY_LIMIT_M = 15f
    private const val MOVING_THRESHOLD_MPS = 0.5f

    fun compute(fixes: List<Fix>): Stats {
        if (fixes.size < 2) return Stats(0.0, 0, 0, 0f, 0.0, 0.0)
        val sorted = fixes.sortedBy { it.t }

        var distance = 0.0
        var movingMs = 0L
        for (i in 1 until sorted.size) {
            val a = sorted[i - 1]; val b = sorted[i]
            if (a.accuracyM <= ACCURACY_LIMIT_M && b.accuracyM <= ACCURACY_LIMIT_M) {
                distance += Geo.haversineMetres(a.lat, a.lon, b.lat, b.lon)
            }
            if (b.speedMps > MOVING_THRESHOLD_MPS) movingMs += (b.t - a.t)
        }

        val durationS = (sorted.last().t - sorted.first().t) / 1000
        val movingS = movingMs / 1000
        val maxSpeed = sorted.filter { it.accuracyM <= SPEED_ACCURACY_LIMIT_M }
            .maxOfOrNull { it.speedMps } ?: 0f
        val avg = if (movingS > 0) distance / movingS else 0.0
        return Stats(distance, durationS, movingS, maxSpeed, avg, slowestKm(sorted))
    }

    /** Worst rolling 1 km split — the honest replacement for "lowest speed". */
    private fun slowestKm(sorted: List<Fix>): Double {
        var worst = Double.MAX_VALUE
        var lo = 0
        var acc = 0.0
        for (hi in 1 until sorted.size) {
            acc += Geo.haversineMetres(
                sorted[hi - 1].lat, sorted[hi - 1].lon, sorted[hi].lat, sorted[hi].lon)
            while (acc >= 1000.0 && lo < hi - 1) {
                val seconds = (sorted[hi].t - sorted[lo].t) / 1000.0
                if (seconds > 0) worst = minOf(worst, 1000.0 / seconds)
                acc -= Geo.haversineMetres(
                    sorted[lo].lat, sorted[lo].lon, sorted[lo + 1].lat, sorted[lo + 1].lon)
                lo++
            }
        }
        return if (worst == Double.MAX_VALUE) 0.0 else worst
    }
}
```

- [ ] **Step 4: Run and confirm pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*TripStatsTest*'`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(core): trip statistics with accuracy filtering and slowest-km split"
```

---

### Task 5: Room schema in WAL mode

**Files:**
- Create: `app/src/main/java/in/odograph/tracker/data/Entities.kt`
- Create: `app/src/main/java/in/odograph/tracker/data/OdographDao.kt`
- Create: `app/src/main/java/in/odograph/tracker/data/OdographDb.kt`
- Test: `app/src/test/java/in/odograph/tracker/data/OdographDbTest.kt`

**Interfaces:**
- Produces: `OdographDb.get(ctx): OdographDb`, `dao.startTrip(startedAt): Long`,
  `dao.appendPoint(PointEntity)`, `dao.openTrip(): TripEntity?`, `dao.finishTrip(...)`,
  `dao.pointsFor(tripId): List<PointEntity>`

- [ ] **Step 1: Write the failing test (Robolectric, in-memory Room)**

```kotlin
package `in`.odograph.tracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OdographDbTest {
    private lateinit var db: OdographDb

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), OdographDb::class.java
        ).allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    @Test fun `a started trip is open until finished`() {
        val id = db.dao().startTrip(1_700_000_000_000L)
        assertThat(db.dao().openTrip()?.id).isEqualTo(id)
        db.dao().finishTrip(id, 1_700_000_060_000L, 1000.0, 60, 55, 20f, 18.2, 5.0)
        assertThat(db.dao().openTrip()).isNull()
    }

    @Test fun `points are returned in time order for their trip`() {
        val id = db.dao().startTrip(0L)
        db.dao().appendPoint(PointEntity(0, id, 2000, 12.98, 77.60, 10f, 90f, 900.0, 5f, false))
        db.dao().appendPoint(PointEntity(0, id, 1000, 12.97, 77.59, 8f, 90f, 899.0, 5f, false))
        assertThat(db.dao().pointsFor(id).map { it.t }).containsExactly(1000L, 2000L).inOrder()
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests '*OdographDbTest*'`
Expected: FAIL — `Unresolved reference: OdographDb`

- [ ] **Step 3: Write the entities**

```kotlin
package `in`.odograph.tracker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val distanceM: Double = 0.0,
    val durationS: Long = 0,
    val movingS: Long = 0,
    val maxSpeedMps: Float = 0f,
    val avgSpeedMps: Double = 0.0,
    val slowestKmMps: Double = 0.0,
    val startLat: Double? = null, val startLon: Double? = null,
    val endLat: Double? = null, val endLon: Double? = null,
    val clusterId: Long? = null,
    val socStart: Double? = null, val socEnd: Double? = null, val energyKwh: Double? = null
)

@Entity(tableName = "points", indices = [Index(value = ["tripId", "t"])])
data class PointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val t: Long,
    val lat: Double, val lon: Double,
    val speedMps: Float,
    val bearingDeg: Float?,
    val altitudeM: Double?,
    val accuracyM: Float,
    val interpolated: Boolean
)
```

- [ ] **Step 4: Write the DAO**

```kotlin
package `in`.odograph.tracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface OdographDao {
    @Insert fun insertTrip(trip: TripEntity): Long
    fun startTrip(startedAt: Long): Long = insertTrip(TripEntity(startedAt = startedAt))

    @Insert fun appendPoint(point: PointEntity): Long

    @Query("SELECT * FROM trips WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun openTrip(): TripEntity?

    @Query("SELECT * FROM trips ORDER BY startedAt DESC")
    fun allTrips(): List<TripEntity>

    @Query("SELECT * FROM points WHERE tripId = :tripId ORDER BY t ASC")
    fun pointsFor(tripId: Long): List<PointEntity>

    @Query("SELECT * FROM points WHERE tripId = :tripId ORDER BY t DESC LIMIT 1")
    fun lastPointOf(tripId: Long): PointEntity?

    @Query("""UPDATE trips SET endedAt = :endedAt, distanceM = :distanceM,
        durationS = :durationS, movingS = :movingS, maxSpeedMps = :maxSpeedMps,
        avgSpeedMps = :avgSpeedMps, slowestKmMps = :slowestKmMps WHERE id = :id""")
    fun finishTrip(id: Long, endedAt: Long, distanceM: Double, durationS: Long,
                   movingS: Long, maxSpeedMps: Float, avgSpeedMps: Double, slowestKmMps: Double)

    @Query("UPDATE trips SET startLat = :lat, startLon = :lon WHERE id = :id")
    fun setOrigin(id: Long, lat: Double, lon: Double)

    @Query("UPDATE trips SET endLat = :lat, endLon = :lon WHERE id = :id")
    fun setDestination(id: Long, lat: Double, lon: Double)

    @Query("UPDATE trips SET clusterId = :clusterId WHERE id = :id")
    fun setCluster(id: Long, clusterId: Long)
}
```

- [ ] **Step 5: Write the database with WAL explicitly enabled**

```kotlin
package `in`.odograph.tracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TripEntity::class, PointEntity::class], version = 1, exportSchema = false)
abstract class OdographDb : RoomDatabase() {
    abstract fun dao(): OdographDao

    companion object {
        @Volatile private var instance: OdographDb? = null
        fun get(ctx: Context): OdographDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                ctx.applicationContext, OdographDb::class.java, "odograph.db"
            )
                // The car cuts power without warning. WAL means a torn write costs one
                // in-flight row, never the database.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build().also { instance = it }
        }
    }
}
```

- [ ] **Step 6: Run and confirm pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*OdographDbTest*'`
Expected: PASS (2 tests)

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat(data): room schema in WAL mode with trips and points"
```

---

### Task 6: Trip recovery — close the orphan, backfill the origin

**Files:**
- Create: `app/src/main/java/in/odograph/tracker/record/TripRecovery.kt`
- Test: `app/src/test/java/in/odograph/tracker/record/TripRecoveryTest.kt`

**Interfaces:**
- Consumes: `OdographDao`, `TripStats.compute`, `Fix`
- Produces: `TripRecovery.recoverAndStart(dao: OdographDao, nowFromGnss: Long?): Long`
  — closes any orphaned trip, returns the id of the newly started trip.

- [ ] **Step 1: Write the failing test — this is the heart of the crash-safety design**

```kotlin
package `in`.odograph.tracker.record

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.data.PointEntity
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TripRecoveryTest {
    private lateinit var db: OdographDb

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), OdographDb::class.java
        ).allowMainThreadQueries().build()
    }

    @Test fun `an orphaned trip is closed with totals computed from its points`() {
        val dao = db.dao()
        val old = dao.startTrip(1_000_000L)
        dao.appendPoint(PointEntity(0, old, 1_000_000L, 12.9700, 77.5900, 0f, null, 900.0, 5f, false))
        dao.appendPoint(PointEntity(0, old, 1_060_000L, 12.9800, 77.5900, 15f, null, 905.0, 5f, false))

        TripRecovery.recoverAndStart(dao, nowFromGnss = 2_000_000L)

        val recovered = dao.allTrips().first { it.id == old }
        assertThat(recovered.endedAt).isEqualTo(1_060_000L)   // last point, not "now"
        assertThat(recovered.distanceM).isGreaterThan(1000.0)
        assertThat(recovered.durationS).isEqualTo(60L)
    }

    @Test fun `the new trip inherits its origin from the previous trip's last point`() {
        val dao = db.dao()
        val old = dao.startTrip(1_000_000L)
        dao.appendPoint(PointEntity(0, old, 1_060_000L, 12.9800, 77.5900, 0f, null, 905.0, 5f, false))

        val newId = TripRecovery.recoverAndStart(dao, nowFromGnss = 2_000_000L)

        val fresh = dao.allTrips().first { it.id == newId }
        assertThat(fresh.startLat).isEqualTo(12.9800)
        assertThat(fresh.startLon).isEqualTo(77.5900)
    }

    @Test fun `an orphan with no points is discarded not closed with garbage`() {
        val dao = db.dao()
        dao.startTrip(1_000_000L)
        TripRecovery.recoverAndStart(dao, nowFromGnss = 2_000_000L)
        assertThat(dao.allTrips().none { it.endedAt == null && it.id != dao.openTrip()?.id })
            .isTrue()
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests '*TripRecoveryTest*'`
Expected: FAIL — `Unresolved reference: TripRecovery`

- [ ] **Step 3: Implement**

```kotlin
package `in`.odograph.tracker.record

import `in`.odograph.tracker.core.Fix
import `in`.odograph.tracker.core.TripStats
import `in`.odograph.tracker.data.OdographDao

object TripRecovery {
    /**
     * The car cuts power without warning, so a trip is never closed by the trip itself.
     * On every boot we find the trip the power cut orphaned, compute its totals from the
     * points it managed to write, close it, and seed the new trip's origin from where the
     * old one stopped — because the car did not teleport while it was parked.
     */
    fun recoverAndStart(dao: OdographDao, nowFromGnss: Long?): Long {
        var seedLat: Double? = null
        var seedLon: Double? = null

        dao.openTrip()?.let { orphan ->
            val points = dao.pointsFor(orphan.id)
            if (points.isEmpty()) {
                // Powered on and off without ever getting a fix. Nothing to record.
                dao.finishTrip(orphan.id, orphan.startedAt, 0.0, 0, 0, 0f, 0.0, 0.0)
            } else {
                val stats = TripStats.compute(points.map {
                    Fix(it.t, it.lat, it.lon, it.speedMps, it.accuracyM, it.interpolated)
                })
                val last = points.last()
                dao.finishTrip(
                    orphan.id, last.t, stats.distanceM, stats.durationS,
                    stats.movingS, stats.maxSpeedMps, stats.avgSpeedMps, stats.slowestKmSpeedMps
                )
                dao.setDestination(orphan.id, last.lat, last.lon)
                seedLat = last.lat; seedLon = last.lon
            }
        }

        val newId = dao.startTrip(nowFromGnss ?: 0L)
        if (seedLat != null && seedLon != null) dao.setOrigin(newId, seedLat!!, seedLon!!)
        return newId
    }
}
```

- [ ] **Step 4: Run and confirm pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*TripRecoveryTest*'`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(record): boot-time trip recovery with origin backfill"
```

---

### Task 7: GNSS location source

**Files:**
- Create: `app/src/main/java/in/odograph/tracker/record/LocationSource.kt`
- Test: `app/src/test/java/in/odograph/tracker/record/LocationSourceTest.kt`

**Interfaces:**
- Produces: `interface LocationSource { fun start(onFix: (Fix) -> Unit); fun stop() }`,
  `class GnssLocationSource(ctx: Context) : LocationSource`,
  `fun Location.toFix(): Fix`

- [ ] **Step 1: Write the failing test for the mapping — GNSS time, never system time**

```kotlin
package `in`.odograph.tracker.record

import android.location.Location
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocationSourceTest {
    @Test fun `fix takes its timestamp from the location not the system clock`() {
        val l = Location("gps").apply {
            latitude = 12.97; longitude = 77.59; speed = 12.5f; accuracy = 4f
            time = 1_700_000_000_000L
        }
        val fix = l.toFix()
        assertThat(fix.t).isEqualTo(1_700_000_000_000L)
        assertThat(fix.speedMps).isEqualTo(12.5f)
    }

    @Test fun `a location without speed reports zero rather than a guess`() {
        val l = Location("gps").apply { latitude = 12.97; longitude = 77.59; time = 1L }
        assertThat(l.toFix().speedMps).isEqualTo(0f)
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests '*LocationSourceTest*'`
Expected: FAIL — `Unresolved reference: toFix`

- [ ] **Step 3: Implement**

```kotlin
package `in`.odograph.tracker.record

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import `in`.odograph.tracker.core.Fix

fun Location.toFix(): Fix = Fix(
    t = time,                                   // GNSS UTC. The box has no NTP.
    lat = latitude, lon = longitude,
    speedMps = if (hasSpeed()) speed else 0f,
    accuracyM = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
    interpolated = false
)

interface LocationSource {
    fun start(onFix: (Fix) -> Unit)
    fun stop()
}

/** Deliberately LocationManager, not Fused: Play Services presence is unverified on the box. */
class GnssLocationSource(private val ctx: Context) : LocationSource {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listener: LocationListener? = null

    @SuppressLint("MissingPermission")
    override fun start(onFix: (Fix) -> Unit) {
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = onFix(location.toFix())
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("legacy")
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        }
        listener = l
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l)
    }

    override fun stop() { listener?.let { lm.removeUpdates(it) }; listener = null }
}
```

- [ ] **Step 4: Run and confirm pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*LocationSourceTest*'`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(record): gnss location source using LocationManager"
```

---

### Task 8: The recorder service and boot receiver

**Files:**
- Create: `app/src/main/java/in/odograph/tracker/record/TripRecorderService.kt`
- Create: `app/src/main/java/in/odograph/tracker/record/BootReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/in/odograph/tracker/record/BootReceiverTest.kt`

**Interfaces:**
- Consumes: `GnssLocationSource`, `TripRecovery`, `OdographDb`
- Produces: `TripRecorderService.LiveState` (StateFlow of `speedMps`, `distanceM`,
  `elapsedS`, `hasFix`), read by the UI in Phase 2.

- [ ] **Step 1: Write the failing test**

```kotlin
package `in`.odograph.tracker.record

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {
    @Test fun `boot completed starts the recorder service`() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        val started = shadowOf(ctx).nextStartedService
        assertThat(started).isNotNull()
        assertThat(started.component?.className)
            .isEqualTo(TripRecorderService::class.java.name)
    }

    @Test fun `an unrelated broadcast starts nothing`() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        BootReceiver().onReceive(ctx, Intent("com.example.SOMETHING_ELSE"))
        assertThat(shadowOf(ctx).nextStartedService).isNull()
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests '*BootReceiverTest*'`
Expected: FAIL — `Unresolved reference: BootReceiver`

- [ ] **Step 3: Write the service**

```kotlin
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
import `in`.odograph.tracker.core.Fix
import `in`.odograph.tracker.core.Geo
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.data.PointEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        val movingS: Long = 0
    )

    companion object {
        private const val CHANNEL_ID = "odograph_recording"
        private val _state = MutableStateFlow(LiveState())
        val state: StateFlow<LiveState> = _state
    }

    private lateinit var source: LocationSource
    private val io = CoroutineScope(Dispatchers.IO)
    private var tripId: Long = -1
    private var lastFix: Fix? = null
    private var startedAt: Long? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, notification(), foregroundType())
        source = GnssLocationSource(this)

        io.launch {
            val dao = OdographDb.get(this@TripRecorderService).dao()
            // startedAt is patched by the first real fix; GNSS time is authoritative.
            tripId = TripRecovery.recoverAndStart(dao, nowFromGnss = null)
            source.start { fix -> io.launch { record(fix) } }
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
        if (startedAt == null) startedAt = fix.t
        val prev = lastFix
        val added = if (prev != null && prev.accuracyM <= 25f && fix.accuracyM <= 25f)
            Geo.haversineMetres(prev.lat, prev.lon, fix.lat, fix.lon) else 0.0
        val cur = _state.value
        _state.value = cur.copy(
            hasFix = true,
            speedMps = fix.speedMps,
            distanceM = cur.distanceM + added,
            elapsedS = (fix.t - (startedAt ?: fix.t)) / 1000,
            maxSpeedMps = maxOf(cur.maxSpeedMps, if (fix.accuracyM <= 15f) fix.speedMps else 0f),
            movingS = cur.movingS + if (fix.speedMps > 0.5f) 1 else 0
        )
        lastFix = fix
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Recording",
                    NotificationManager.IMPORTANCE_LOW)
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
    override fun onDestroy() { source.stop(); super.onDestroy() }
}
```

- [ ] **Step 4: Write the boot receiver**

```kotlin
package `in`.odograph.tracker.record

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return
        val svc = Intent(context, TripRecorderService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(svc) else context.startService(svc)
    }
}
```

- [ ] **Step 5: Register both in the manifest**

Inside `<application>`:
```xml
<service
    android:name=".record.TripRecorderService"
    android:foregroundServiceType="location"
    android:exported="false" />
<receiver
    android:name=".record.BootReceiver"
    android:exported="true"
    android:directBootAware="false">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.QUICKBOOT_POWERON" />
    </intent-filter>
</receiver>
```

Add `implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")`.

- [ ] **Step 6: Run and confirm pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*BootReceiverTest*'`
Expected: PASS (2 tests)

- [ ] **Step 7: Verify on the real box, and record which fallback (if any) was needed**

Install, grant **Allow all the time** for location, then power-cycle the car and confirm the
notification reappears without opening the app. If it does not, apply fallbacks in this order
and note which worked in `docs/device-probe.md`:
1. Request battery-optimisation exemption (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
2. Add the app to any vendor "auto-start / protected apps" list in the box's settings
3. Lower `targetSdk` to 28 (sideloaded personal app — sidesteps most background-FGS limits)
4. Register an `AccessibilityService` purely as a keep-alive trigger
5. Make the app the launcher

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "feat(record): foreground recorder service and boot auto-start"
```

---

# PHASE 2 — The cluster

### Task 9: Day/night palette tokens

**Files:**
- Create: `app/src/main/java/in/odograph/tracker/ui/theme/Palette.kt`
- Test: `app/src/test/java/in/odograph/tracker/ui/theme/PaletteTest.kt`

**Interfaces:**
- Produces: `enum class Direction { ION, CHRONO, VECTOR }`,
  `data class Palette(...)`, `fun paletteFor(direction: Direction, night: Boolean): Palette`

- [ ] **Step 1: Write the failing test — day is a different palette, not an inversion**

```kotlin
package `in`.odograph.tracker.ui.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PaletteTest {
    @Test fun `night enables glow and day disables it`() {
        assertThat(paletteFor(Direction.ION, night = true).glow).isGreaterThan(0f)
        assertThat(paletteFor(Direction.ION, night = false).glow).isEqualTo(0f)
    }

    @Test fun `every direction has a distinct accent in both modes`() {
        val accents = Direction.entries.flatMap {
            listOf(paletteFor(it, true).accent, paletteFor(it, false).accent)
        }
        assertThat(accents.toSet()).hasSize(accents.size)
    }

    @Test fun `day ground is light and night ground is dark`() {
        assertThat(paletteFor(Direction.ION, false).ground.luminance())
            .isGreaterThan(paletteFor(Direction.ION, true).ground.luminance())
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests '*PaletteTest*'`
Expected: FAIL — `Unresolved reference: paletteFor`

- [ ] **Step 3: Implement**

```kotlin
package `in`.odograph.tracker.ui.theme

import androidx.compose.ui.graphics.Color

enum class Direction { ION, CHRONO, VECTOR }

data class Palette(
    val ground: Color, val track: Color, val trackSoft: Color,
    val numeral: Color, val label: Color, val dim: Color,
    val accent: Color, val accent2: Color,
    /** Blur radius multiplier. Zero on a light ground, where glow only muddies. */
    val glow: Float
)

private val NightBase = Triple(Color(0xFF050609), Color(0xFF1C232D), Color(0xFF171A1F))
private val DayBase   = Triple(Color(0xFFE7EBF0), Color(0xFFC6CDD7), Color(0xFFD5DBE3))

fun paletteFor(direction: Direction, night: Boolean): Palette {
    val (ground, track, trackSoft) = if (night) NightBase else DayBase
    val (accent, accent2) = when (direction) {
        Direction.ION    -> if (night) Color(0xFF3DE1FF) to Color(0xFF7B5CFF)
                            else       Color(0xFF0089C7) to Color(0xFF5B3FD6)
        Direction.CHRONO -> if (night) Color(0xFFF5B547) to Color(0xFFB87333)
                            else       Color(0xFFB4761A) to Color(0xFF8A5416)
        Direction.VECTOR -> if (night) Color(0xFFFF5A1F) to Color(0xFF8A2E0C)
                            else       Color(0xFFDE3F06) to Color(0xFF7A2A08)
    }
    return Palette(
        ground = ground, track = track, trackSoft = trackSoft,
        numeral = if (night) Color(0xFFFFFFFF) else Color(0xFF0A0D12),
        label   = if (night) Color(0xFF4E5866) else Color(0xFF7C8792),
        dim     = if (night) Color(0xFF8B96A5) else Color(0xFF4A5462),
        accent = accent, accent2 = accent2,
        glow = if (night) 1f else 0f
    )
}
```

- [ ] **Step 4: Run and confirm pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*PaletteTest*'`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(ui): day and night palettes per cluster direction"
```

---

### Task 10: The gauge — resolution-independent Canvas instrument

**Files:**
- Create: `app/src/main/java/in/odograph/tracker/ui/gauge/Gauge.kt`
- Create: `app/src/main/java/in/odograph/tracker/ui/gauge/SpeedSpring.kt`
- Test: `app/src/test/java/in/odograph/tracker/ui/gauge/SpeedSpringTest.kt`

**Interfaces:**
- Produces: `@Composable fun Gauge(speedMps: Float, hasFix: Boolean, direction: Direction, palette: Palette, modifier: Modifier)`,
  `class SpeedSpring(private val k: Float = 1.7f) { fun update(target: Float, dtS: Float): Float }`

- [ ] **Step 1: Write the failing test — the spring is a low-pass filter, so assert filtering**

```kotlin
package `in`.odograph.tracker.ui.gauge

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class SpeedSpringTest {
    @Test fun `it converges on a steady target`() {
        val s = SpeedSpring()
        repeat(200) { s.update(20f, 0.1f) }
        assertThat(s.update(20f, 0.1f)).isWithin(0.1f).of(20f)
    }

    @Test fun `it attenuates jitter rather than following it`() {
        val s = SpeedSpring()
        repeat(100) { s.update(20f, 0.1f) }          // settle
        var maxDeviation = 0f
        repeat(40) { i ->
            val noisy = if (i % 2 == 0) 23f else 17f  // +/- 3 km/h GPS jitter
            maxDeviation = maxOf(maxDeviation, abs(s.update(noisy, 0.1f) - 20f))
        }
        assertThat(maxDeviation).isLessThan(1.5f)     // less than half the input swing
    }

    @Test fun `it starts at zero`() {
        assertThat(SpeedSpring().update(0f, 0.1f)).isEqualTo(0f)
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests '*SpeedSpringTest*'`
Expected: FAIL — `Unresolved reference: SpeedSpring`

- [ ] **Step 3: Implement the filter**

```kotlin
package `in`.odograph.tracker.ui.gauge

/**
 * Exponential smoother standing in for a critically-damped spring.
 * Visually it gives the needle mass; mechanically it is a low-pass filter that removes the
 * 2-3 km/h of GNSS jitter that would otherwise make the readout twitch while cruising.
 */
class SpeedSpring(private val k: Float = 1.7f) {
    private var value = 0f
    fun update(target: Float, dtS: Float): Float {
        value += (target - value) * minOf(1f, dtS * k)
        return value
    }
}
```

- [ ] **Step 4: Run and confirm pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*SpeedSpringTest*'`
Expected: PASS (3 tests)

- [ ] **Step 5: Implement the gauge — everything as a fraction of `size`, no `dp` constants**

```kotlin
package `in`.odograph.tracker.ui.gauge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import `in`.odograph.tracker.ui.theme.Direction
import `in`.odograph.tracker.ui.theme.Palette
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val MAX_KMH = 120f
private const val START_DEG = 140f
private const val SWEEP_DEG = 260f

@Composable
fun Gauge(
    speedKmh: Float, hasFix: Boolean,
    direction: Direction, palette: Palette,
    modifier: Modifier = Modifier
) = Box(modifier) {
    Canvas(Modifier.matchParentSize()) {
        val r = min(size.width, size.height) * 0.42f
        val c = Offset(size.width / 2f, size.height / 2f)
        val t = (speedKmh / MAX_KMH).coerceIn(0f, 1f)

        when (direction) {
            Direction.ION -> {
                val cells = 64
                repeat(cells) { i ->
                    val f = i / (cells - 1f)
                    val a = Math.toRadians((START_DEG + SWEEP_DEG * f).toDouble())
                    val lit = f <= t
                    val inner = r * 0.80f
                    val outer = r * if (lit) 1.0f else 0.94f
                    drawLine(
                        color = if (!lit) palette.track
                                else if (f / t.coerceAtLeast(0.001f) > 0.72f) palette.accent2
                                else palette.accent,
                        start = c + Offset((cos(a) * inner).toFloat(), (sin(a) * inner).toFloat()),
                        end   = c + Offset((cos(a) * outer).toFloat(), (sin(a) * outer).toFloat()),
                        strokeWidth = r * if (lit) 0.052f else 0.030f
                    )
                }
            }
            Direction.VECTOR, Direction.CHRONO -> {
                drawArc(
                    color = palette.trackSoft, startAngle = START_DEG, sweepAngle = SWEEP_DEG,
                    useCenter = false, topLeft = Offset(c.x - r, c.y - r),
                    size = Size(r * 2, r * 2), style = Stroke(width = r * 0.115f)
                )
                drawArc(
                    color = palette.accent, startAngle = START_DEG, sweepAngle = SWEEP_DEG * t,
                    useCenter = false, topLeft = Offset(c.x - r, c.y - r),
                    size = Size(r * 2, r * 2), style = Stroke(width = r * 0.115f)
                )
            }
        }

        // Numerals via the native canvas so the size can be a fraction of the viewport.
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
                color = palette.numeral.value.toInt()
            }
            paint.textSize = r * 0.72f
            drawText(
                if (hasFix) speedKmh.toInt().toString() else "—",
                c.x, c.y + r * 0.22f, paint
            )
            paint.textSize = r * 0.13f
            paint.color = palette.accent.value.toInt()
            // Never "0 km/h" before a fix: a zero is a measurement claim.
            drawText(if (hasFix) "KM/H" else "ACQUIRING", c.x, c.y + r * 0.50f, paint)
        }
    }
}
```

- [ ] **Step 6: Build to confirm it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat(ui): resolution-independent canvas gauge with spring-damped needle"
```

---

### Task 11: Driver screen — three fixed numbers

**Files:**
- Create: `app/src/main/java/in/odograph/tracker/ui/DriverScreen.kt`
- Create: `app/src/main/java/in/odograph/tracker/ui/Format.kt`
- Modify: `app/src/main/java/in/odograph/tracker/MainActivity.kt`
- Test: `app/src/test/java/in/odograph/tracker/ui/FormatTest.kt`

**Interfaces:**
- Consumes: `TripRecorderService.state`, `Gauge`, `paletteFor`
- Produces: `fun mpsToKmh(mps: Float): Float`, `fun formatHhMm(seconds: Long): String`,
  `fun formatKm(metres: Double): String`

- [ ] **Step 1: Write the failing test**

```kotlin
package `in`.odograph.tracker.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FormatTest {
    @Test fun `metres per second convert to kilometres per hour`() {
        assertThat(mpsToKmh(10f)).isWithin(0.01f).of(36f)
    }

    @Test fun `durations format as zero padded hours and minutes`() {
        assertThat(formatHhMm(0)).isEqualTo("00:00")
        assertThat(formatHhMm(3_600)).isEqualTo("01:00")
        assertThat(formatHhMm(3_661)).isEqualTo("01:01")
        assertThat(formatHhMm(86_399)).isEqualTo("23:59")
    }

    @Test fun `distance shows one decimal place`() {
        assertThat(formatKm(18_432.0)).isEqualTo("18.4")
        assertThat(formatKm(0.0)).isEqualTo("0.0")
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests '*FormatTest*'`
Expected: FAIL — `Unresolved reference: mpsToKmh`

- [ ] **Step 3: Implement `Format.kt`**

```kotlin
package `in`.odograph.tracker.ui

fun mpsToKmh(mps: Float): Float = mps * 3.6f

fun formatHhMm(seconds: Long): String {
    val m = seconds / 60
    return "%02d:%02d".format(m / 60, m % 60)
}

fun formatKm(metres: Double): String = "%.1f".format(metres / 1000.0)
```

- [ ] **Step 4: Run and confirm pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*FormatTest*'`
Expected: PASS (3 tests)

- [ ] **Step 5: Write `DriverScreen.kt` — fixed slots, never reordered**

```kotlin
package `in`.odograph.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.record.TripRecorderService
import `in`.odograph.tracker.ui.gauge.Gauge
import `in`.odograph.tracker.ui.gauge.SpeedSpring
import `in`.odograph.tracker.ui.theme.Direction
import `in`.odograph.tracker.ui.theme.paletteFor
import kotlinx.coroutines.android.awaitFrame

@Composable
fun DriverScreen(direction: Direction, night: Boolean) {
    val live by TripRecorderService.state.collectAsState()
    val spring = remember { SpeedSpring() }
    var smoothed by remember { mutableFloatStateOf(0f) }
    val palette = paletteFor(direction, night)

    LaunchedEffect(Unit) {
        while (true) { smoothed = spring.update(mpsToKmh(live.speedMps), 0.016f); awaitFrame() }
    }

    Row(
        Modifier.fillMaxSize().background(palette.ground),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Gauge(smoothed, live.hasFix, direction, palette, Modifier.weight(1f).fillMaxHeight())
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Stat(formatKm(live.distanceM), "KM   DISTANCE", palette)
            Spacer(Modifier.height(28.dp))
            Stat(formatHhMm(live.elapsedS), "H:MM  TIME", palette)
        }
    }
}

@Composable
private fun Stat(value: String, label: String, palette: `in`.odograph.tracker.ui.theme.Palette) {
    Column {
        Text(value, color = palette.numeral, fontSize = 64.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = palette.label, fontSize = 12.sp, letterSpacing = 2.sp)
    }
}
```

Add `implementation("androidx.compose.runtime:runtime-livedata")` is not needed;
add `implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")` if not present.

- [ ] **Step 6: Point `MainActivity` at `DriverScreen` and start the service**

```kotlin
setContent { DriverScreen(Direction.ION, night = true) }
startForegroundService(Intent(this, TripRecorderService::class.java))
```

- [ ] **Step 7: Build and install; confirm the gauge animates against a real fix**

Run: `./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: gauge shows `ACQUIRING` then a live speed once outdoors.

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "feat(ui): driver screen with fixed-position stats"
```

---

### Task 12: Detailed screen — route trace and charts

**Files:**
- Create: `app/src/main/java/in/odograph/tracker/ui/DetailScreen.kt`
- Create: `app/src/main/java/in/odograph/tracker/ui/RouteTrace.kt`
- Test: `app/src/test/java/in/odograph/tracker/ui/RouteTraceTest.kt`

**Interfaces:**
- Produces: `fun normaliseRoute(points: List<Pair<Double, Double>>): List<Pair<Float, Float>>`
  — projects lat/lon into a 0..1 box preserving aspect ratio.

- [ ] **Step 1: Write the failing test**

```kotlin
package `in`.odograph.tracker.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RouteTraceTest {
    @Test fun `an empty route normalises to an empty list`() {
        assertThat(normaliseRoute(emptyList())).isEmpty()
    }

    @Test fun `all normalised points fall inside the unit box`() {
        val pts = listOf(12.90 to 77.50, 12.99 to 77.62, 13.05 to 77.55)
        normaliseRoute(pts).forEach { (x, y) ->
            assertThat(x).isAtLeast(0f); assertThat(x).isAtMost(1f)
            assertThat(y).isAtLeast(0f); assertThat(y).isAtMost(1f)
        }
    }

    @Test fun `a single point maps to the centre rather than dividing by zero`() {
        assertThat(normaliseRoute(listOf(12.97 to 77.59))).containsExactly(0.5f to 0.5f)
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests '*RouteTraceTest*'`
Expected: FAIL — `Unresolved reference: normaliseRoute`

- [ ] **Step 3: Implement**

```kotlin
package `in`.odograph.tracker.ui

/** Projects lat/lon into a 0..1 box, preserving aspect so the route is not stretched. */
fun normaliseRoute(points: List<Pair<Double, Double>>): List<Pair<Float, Float>> {
    if (points.isEmpty()) return emptyList()
    if (points.size == 1) return listOf(0.5f to 0.5f)

    val lats = points.map { it.first }
    val lons = points.map { it.second }
    val minLat = lats.min(); val maxLat = lats.max()
    val minLon = lons.min(); val maxLon = lons.max()
    val spanLat = (maxLat - minLat).takeIf { it > 1e-9 } ?: 1e-9
    val spanLon = (maxLon - minLon).takeIf { it > 1e-9 } ?: 1e-9
    val span = maxOf(spanLat, spanLon)

    return points.map { (lat, lon) ->
        val x = ((lon - minLon) / span).toFloat().coerceIn(0f, 1f)
        val y = (1.0 - (lat - minLat) / span).toFloat().coerceIn(0f, 1f)
        x to y
    }
}
```

- [ ] **Step 4: Run and confirm pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*RouteTraceTest*'`
Expected: PASS (3 tests)

- [ ] **Step 5: Write `DetailScreen.kt` drawing the trace with no map tiles**

```kotlin
package `in`.odograph.tracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import `in`.odograph.tracker.ui.theme.Palette

@Composable
fun RouteTrace(points: List<Pair<Double, Double>>, palette: Palette, modifier: Modifier) {
    val norm = normaliseRoute(points)
    Canvas(modifier.background(palette.ground)) {
        if (norm.size < 2) return@Canvas
        val path = Path()
        norm.forEachIndexed { i, (x, y) ->
            val p = Offset(x * size.width, y * size.height)
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, palette.accent, style = Stroke(width = size.minDimension * 0.008f))
    }
}
```

- [ ] **Step 6: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat(ui): tile-free route trace and detailed view"
```

---

### Task 12b: Trip picker and OSM map tiles under the route

**Files:**
- Create: `app/src/main/java/in/odograph/tracker/ui/TripListScreen.kt`
- Create: `app/src/main/java/in/odograph/tracker/ui/map/MapBounds.kt`
- Create: `app/src/main/java/in/odograph/tracker/ui/map/RouteMap.kt`
- Modify: `app/build.gradle.kts` (osmdroid dependency)
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/in/odograph/tracker/ui/map/MapBoundsTest.kt`

**Interfaces:**
- Consumes: `OdographDao.allTrips`, `OdographDao.pointsFor`, `normaliseRoute` (Task 12)
- Produces:
  - `data class Bounds(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)`
  - `MapBounds.of(points: List<Pair<Double, Double>>): Bounds?`
  - `MapBounds.padded(b: Bounds, fraction: Double = 0.15): Bounds`
  - `@Composable fun RouteMap(points: List<Pair<Double, Double>>, palette: Palette, modifier: Modifier)`

**Design note.** Tiles are an *enhancement layer* over Task 12's polyline, never a dependency.
`osmdroid` is used rather than the Google Maps SDK because the box's Play Services presence is
unverified (spec C6). Tiles are cached to disk and are immutable, so a corridor driven once with
the phone hotspot on renders offline forever. With an empty cache and no network, the map draws
nothing and the polyline still renders on `palette.ground` — the screen is never blank.

- [ ] **Step 1: Write the failing test**

```kotlin
package `in`.odograph.tracker.ui.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MapBoundsTest {
    private val route = listOf(
        12.9716 to 77.5946,
        12.9800 to 77.6100,
        12.9698 to 77.7500
    )

    @Test fun `an empty route has no bounds`() {
        assertThat(MapBounds.of(emptyList())).isNull()
    }

    @Test fun `bounds span the extremes of the route`() {
        val b = MapBounds.of(route)!!
        assertThat(b.minLat).isEqualTo(12.9698)
        assertThat(b.maxLat).isEqualTo(12.9800)
        assertThat(b.minLon).isEqualTo(77.5946)
        assertThat(b.maxLon).isEqualTo(77.7500)
    }

    @Test fun `padding grows the box on every side`() {
        val b = MapBounds.padded(MapBounds.of(route)!!, 0.10)
        assertThat(b.minLat).isLessThan(12.9698)
        assertThat(b.maxLat).isGreaterThan(12.9800)
        assertThat(b.minLon).isLessThan(77.5946)
        assertThat(b.maxLon).isGreaterThan(77.7500)
    }

    @Test fun `a single point still yields a non degenerate padded box`() {
        val b = MapBounds.padded(MapBounds.of(listOf(12.97 to 77.59))!!)
        assertThat(b.maxLat).isGreaterThan(b.minLat)
        assertThat(b.maxLon).isGreaterThan(b.minLon)
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests '*MapBoundsTest*'`
Expected: FAIL — `Unresolved reference: MapBounds`

- [ ] **Step 3: Implement `MapBounds.kt`**

```kotlin
package `in`.odograph.tracker.ui.map

data class Bounds(
    val minLat: Double, val minLon: Double,
    val maxLat: Double, val maxLon: Double
)

object MapBounds {
    /** Smallest degenerate span we will tolerate, so a parked trip still gets a visible box. */
    private const val MIN_SPAN_DEG = 0.002

    fun of(points: List<Pair<Double, Double>>): Bounds? {
        if (points.isEmpty()) return null
        return Bounds(
            minLat = points.minOf { it.first }, minLon = points.minOf { it.second },
            maxLat = points.maxOf { it.first }, maxLon = points.maxOf { it.second }
        )
    }

    fun padded(b: Bounds, fraction: Double = 0.15): Bounds {
        val latSpan = (b.maxLat - b.minLat).coerceAtLeast(MIN_SPAN_DEG)
        val lonSpan = (b.maxLon - b.minLon).coerceAtLeast(MIN_SPAN_DEG)
        val padLat = latSpan * fraction
        val padLon = lonSpan * fraction
        return Bounds(
            minLat = b.minLat - padLat, minLon = b.minLon - padLon,
            maxLat = b.maxLat + padLat, maxLon = b.maxLon + padLon
        )
    }
}
```

- [ ] **Step 4: Run and confirm pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*MapBoundsTest*'`
Expected: PASS (4 tests)

- [ ] **Step 5: Add the osmdroid dependency and manifest entries**

In `app/build.gradle.kts` dependencies:
```kotlin
implementation("org.osmdroid:osmdroid-android:6.1.18")
```

The `INTERNET` permission is already added in Task 15. Add cache-friendly storage access:
```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

- [ ] **Step 6: Implement `RouteMap.kt` — tiles when cached, polyline always**

```kotlin
package `in`.odograph.tracker.ui.map

import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import `in`.odograph.tracker.ui.theme.Palette
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

@Composable
fun RouteMap(points: List<Pair<Double, Double>>, palette: Palette, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            Configuration.getInstance().apply {
                // OSM tile policy requires an identifying agent. Personal, low volume.
                userAgentValue = "Odograph/0.1 (personal drive tracker)"
                osmdroidBasePath = ctx.getExternalFilesDir("osmdroid")
                osmdroidTileCache = ctx.getExternalFilesDir("osmdroid/tiles")
                // Cached tiles never expire in a way that matters: roads do not move.
                expirationOverrideDuration = Long.MAX_VALUE
            }
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(false)   // single-touch return channel (spec C3)
                setUseDataConnection(true)     // fetches only when a network exists
            }
        },
        update = { map ->
            map.overlays.clear()
            if (points.size >= 2) {
                map.overlays.add(Polyline(map).apply {
                    setPoints(points.map { GeoPoint(it.first, it.second) })
                    outlinePaint.color = palette.accent.value.toInt()
                    outlinePaint.strokeWidth = 8f
                })
                MapBounds.of(points)?.let { raw ->
                    val b = MapBounds.padded(raw)
                    map.zoomToBoundingBox(
                        BoundingBox(b.maxLat, b.maxLon, b.minLat, b.minLon), false
                    )
                }
            }
            map.setBackgroundColor(palette.ground.value.toInt())
            map.invalidate()
        }
    )
}
```

- [ ] **Step 7: Implement `TripListScreen.kt` — pick a drive, see its route**

```kotlin
package `in`.odograph.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.data.TripEntity
import `in`.odograph.tracker.ui.map.RouteMap
import `in`.odograph.tracker.ui.theme.Direction
import `in`.odograph.tracker.ui.theme.paletteFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TripListScreen(direction: Direction, night: Boolean) {
    val ctx = LocalContext.current
    val palette = paletteFor(direction, night)
    var trips by remember { mutableStateOf<List<TripEntity>>(emptyList()) }
    var selected by remember { mutableStateOf<TripEntity?>(null) }
    var route by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }

    LaunchedEffect(Unit) {
        trips = withContext(Dispatchers.IO) { OdographDb.get(ctx).dao().allTrips() }
        selected = trips.firstOrNull()
    }
    LaunchedEffect(selected) {
        val id = selected?.id ?: return@LaunchedEffect
        route = withContext(Dispatchers.IO) {
            OdographDb.get(ctx).dao().pointsFor(id).map { it.lat to it.lon }
        }
    }

    val fmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    Row(Modifier.fillMaxSize().background(palette.ground)) {
        LazyColumn(Modifier.weight(0.34f).fillMaxHeight()) {
            items(trips) { trip ->
                Column(
                    Modifier.fillMaxWidth()
                        .clickable { selected = trip }
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Text(fmt.format(Date(trip.startedAt)),
                        color = if (trip.id == selected?.id) palette.accent else palette.numeral,
                        fontSize = 18.sp)
                    Text("${formatKm(trip.distanceM)} km   ${formatHhMm(trip.durationS)}",
                        color = palette.label, fontSize = 13.sp)
                }
            }
        }
        RouteMap(route, palette, Modifier.weight(0.66f).fillMaxHeight())
    }
}
```

- [ ] **Step 8: Build and verify tiles cache then survive airplane mode**

Run:
```bash
./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Verify on the box, in this order:
1. With the phone hotspot **on**, open a recorded drive — tiles download and the route draws over roads.
2. Turn the hotspot **off** and reopen the same drive — tiles still render from the disk cache.
3. Open a drive in an area never visited with a network — the map is blank but **the polyline
   still draws**. That fallback is the acceptance criterion, not a defect.

- [ ] **Step 9: Commit**

```bash
git add -A && git commit -m "feat(ui): trip picker with osm map tiles cached offline under the route trace"
```

---

# PHASE 3 — Analysis and export

### Task 13: Route clustering by origin and destination

**Files:**
- Create: `app/src/main/java/in/odograph/tracker/core/RouteCluster.kt`
- Test: `app/src/test/java/in/odograph/tracker/core/RouteClusterTest.kt`

**Interfaces:**
- Produces: `data class Endpoint(val lat: Double, val lon: Double)`,
  `RouteCluster.snapKey(e: Endpoint, gridM: Double = 150.0): String`,
  `RouteCluster.group(trips: List<Pair<Endpoint, Endpoint>>): Map<String, Int>`

- [ ] **Step 1: Write the failing test**

```kotlin
package `in`.odograph.tracker.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RouteClusterTest {
    private val home = Endpoint(12.9716, 77.5946)
    private val office = Endpoint(12.9698, 77.7500)

    @Test fun `two endpoints within the grid share a key`() {
        val nearby = Endpoint(home.lat + 0.0005, home.lon + 0.0005)  // ~70 m away
        assertThat(RouteCluster.snapKey(nearby)).isEqualTo(RouteCluster.snapKey(home))
    }

    @Test fun `distant endpoints get different keys`() {
        assertThat(RouteCluster.snapKey(office)).isNotEqualTo(RouteCluster.snapKey(home))
    }

    @Test fun `repeated commutes are counted together`() {
        val trips = listOf(
            home to office, home to office, office to home,
            Endpoint(home.lat + 0.0004, home.lon) to office     // same commute, GPS noise
        )
        val grouped = RouteCluster.group(trips)
        assertThat(grouped.values.max()).isEqualTo(3)
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests '*RouteClusterTest*'`
Expected: FAIL — `Unresolved reference: RouteCluster`

- [ ] **Step 3: Implement**

```kotlin
package `in`.odograph.tracker.core

import kotlin.math.cos
import kotlin.math.roundToLong

data class Endpoint(val lat: Double, val lon: Double)

/**
 * "Most visited route" means the most repeated origin->destination pair, not the most
 * repeated path shape. Snapping endpoints to a coarse grid absorbs GPS scatter in a car park
 * and gives the 90% answer without map-matching.
 */
object RouteCluster {
    private const val METRES_PER_DEG_LAT = 111_132.0

    fun snapKey(e: Endpoint, gridM: Double = 150.0): String {
        val latStep = gridM / METRES_PER_DEG_LAT
        val lonStep = gridM / (METRES_PER_DEG_LAT * cos(Math.toRadians(e.lat)).coerceAtLeast(0.01))
        val la = (e.lat / latStep).roundToLong()
        val lo = (e.lon / lonStep).roundToLong()
        return "$la:$lo"
    }

    fun group(trips: List<Pair<Endpoint, Endpoint>>): Map<String, Int> =
        trips.groupingBy { (from, to) -> "${snapKey(from)}>${snapKey(to)}" }.eachCount()
}
```

- [ ] **Step 4: Run and confirm pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*RouteClusterTest*'`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(core): origin-destination route clustering"
```

---

### Task 14: CSV and GPX export

**Files:**
- Create: `app/src/main/java/in/odograph/tracker/export/Exporters.kt`
- Test: `app/src/test/java/in/odograph/tracker/export/ExportersTest.kt`

**Interfaces:**
- Consumes: `TripEntity`, `PointEntity`
- Produces: `Exporters.tripsCsv(trips: List<TripEntity>): String`,
  `Exporters.pointsCsv(points: List<PointEntity>): String`,
  `Exporters.gpx(tripName: String, points: List<PointEntity>): String`

- [ ] **Step 1: Write the failing test**

```kotlin
package `in`.odograph.tracker.export

import com.google.common.truth.Truth.assertThat
import `in`.odograph.tracker.data.PointEntity
import `in`.odograph.tracker.data.TripEntity
import org.junit.Test

class ExportersTest {
    private val trip = TripEntity(
        id = 7, startedAt = 1_700_000_000_000L, endedAt = 1_700_000_600_000L,
        distanceM = 18_432.0, durationS = 600, movingS = 540,
        maxSpeedMps = 21.6f, avgSpeedMps = 34.1, slowestKmMps = 3.2
    )
    private val point = PointEntity(1, 7, 1_700_000_000_000L, 12.97, 77.59, 12.5f, 90f, 900.0, 4f, false)

    @Test fun `trips csv has a header row and one row per trip`() {
        val csv = Exporters.tripsCsv(listOf(trip))
        val lines = csv.trim().lines()
        assertThat(lines).hasSize(2)
        assertThat(lines[0]).startsWith("id,started_at,ended_at,distance_m")
        assertThat(lines[1]).contains("18432.0")
    }

    @Test fun `gpx is well formed and contains a trackpoint`() {
        val gpx = Exporters.gpx("Trip 7", listOf(point))
        assertThat(gpx).startsWith("<?xml")
        assertThat(gpx).contains("""<trkpt lat="12.97" lon="77.59">""")
        assertThat(gpx).contains("</gpx>")
    }

    @Test fun `csv quoting survives a name containing a comma`() {
        val csv = Exporters.tripsCsv(listOf(trip.copy(clusterId = 3)))
        assertThat(csv.lines()[1].split(",")).hasSize(csv.lines()[0].split(",").size)
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests '*ExportersTest*'`
Expected: FAIL — `Unresolved reference: Exporters`

- [ ] **Step 3: Implement**

```kotlin
package `in`.odograph.tracker.export

import `in`.odograph.tracker.data.PointEntity
import `in`.odograph.tracker.data.TripEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Exporters {

    fun tripsCsv(trips: List<TripEntity>): String = buildString {
        appendLine("id,started_at,ended_at,distance_m,duration_s,moving_s," +
            "max_speed_mps,avg_speed_mps,slowest_km_mps,start_lat,start_lon," +
            "end_lat,end_lon,cluster_id")
        trips.forEach { t ->
            appendLine(listOf(
                t.id, t.startedAt, t.endedAt ?: "", t.distanceM, t.durationS, t.movingS,
                t.maxSpeedMps, t.avgSpeedMps, t.slowestKmMps,
                t.startLat ?: "", t.startLon ?: "", t.endLat ?: "", t.endLon ?: "",
                t.clusterId ?: ""
            ).joinToString(","))
        }
    }

    fun pointsCsv(points: List<PointEntity>): String = buildString {
        appendLine("trip_id,t,lat,lon,speed_mps,bearing_deg,altitude_m,accuracy_m,interpolated")
        points.forEach { p ->
            appendLine(listOf(
                p.tripId, p.t, p.lat, p.lon, p.speedMps,
                p.bearingDeg ?: "", p.altitudeM ?: "", p.accuracyM,
                if (p.interpolated) 1 else 0
            ).joinToString(","))
        }
    }

    fun gpx(tripName: String, points: List<PointEntity>): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<gpx version="1.1" creator="Odograph" xmlns="http://www.topografix.com/GPX/1/1">""")
            appendLine("  <trk><name>${tripName.replace("&", "&amp;").replace("<", "&lt;")}</name><trkseg>")
            points.forEach { p ->
                appendLine("""    <trkpt lat="${p.lat}" lon="${p.lon}">""")
                p.altitudeM?.let { appendLine("      <ele>$it</ele>") }
                appendLine("      <time>${iso.format(Date(p.t))}</time>")
                appendLine("    </trkpt>")
            }
            appendLine("  </trkseg></trk>")
            append("</gpx>")
        }
    }
}
```

- [ ] **Step 4: Run and confirm pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*ExportersTest*'`
Expected: PASS (3 tests)

- [ ] **Step 5: Wire an export button that writes both files and calls `shareFile`**

In `DetailScreen`, add a button whose click handler writes
`getExternalFilesDir(null)/exports/odograph-trips.csv` and calls the existing
`shareFile(ctx, file, "text/csv")` from Task 2. Bluetooth-to-Mac appears in the chooser
with no Bluetooth code of our own.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(export): csv and gpx writers wired to the share sheet"
```

---

### Task 15: Embedded dashboard server

**Files:**
- Create: `app/src/main/java/in/odograph/tracker/server/DashboardServer.kt`
- Create: `app/src/main/java/in/odograph/tracker/server/DashboardHtml.kt`
- Test: `app/src/test/java/in/odograph/tracker/server/DashboardHtmlTest.kt`

**Interfaces:**
- Consumes: `OdographDao`, `Exporters`
- Produces: `DashboardServer.start(ctx: Context, port: Int = 8080)`, `DashboardServer.stop()`,
  `DashboardHtml.render(trips: List<TripEntity>, pointsByTrip: Map<Long, List<PointEntity>>): String`
  — a single self-contained HTML document with data inlined, openable offline forever.

- [ ] **Step 1: Write the failing test**

```kotlin
package `in`.odograph.tracker.server

import com.google.common.truth.Truth.assertThat
import `in`.odograph.tracker.data.TripEntity
import org.junit.Test

class DashboardHtmlTest {
    @Test fun `rendered archive is self contained with no external references`() {
        val html = DashboardHtml.render(
            listOf(TripEntity(id = 1, startedAt = 0, endedAt = 1000, distanceM = 5000.0)),
            emptyMap()
        )
        assertThat(html).contains("<!doctype html>")
        assertThat(html).doesNotContain("http://")
        assertThat(html).doesNotContain("https://")
        assertThat(html).doesNotContain("<script src=")
    }

    @Test fun `trip data is inlined as json`() {
        val html = DashboardHtml.render(
            listOf(TripEntity(id = 42, startedAt = 0, endedAt = 1000, distanceM = 5000.0)),
            emptyMap()
        )
        assertThat(html).contains("\"id\":42")
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests '*DashboardHtmlTest*'`
Expected: FAIL — `Unresolved reference: DashboardHtml`

- [ ] **Step 3: Implement `DashboardHtml.kt`**

```kotlin
package `in`.odograph.tracker.server

import `in`.odograph.tracker.data.PointEntity
import `in`.odograph.tracker.data.TripEntity

object DashboardHtml {
    /**
     * One file, no network. You download it once from the car and it opens on the Mac
     * forever after, offline, with the ignition off.
     */
    fun render(trips: List<TripEntity>, pointsByTrip: Map<Long, List<PointEntity>>): String {
        val tripsJson = trips.joinToString(",", "[", "]") { t ->
            """{"id":${t.id},"startedAt":${t.startedAt},"endedAt":${t.endedAt ?: 0},""" +
            """"distanceM":${t.distanceM},"durationS":${t.durationS},""" +
            """"movingS":${t.movingS},"maxSpeedMps":${t.maxSpeedMps},""" +
            """"avgSpeedMps":${t.avgSpeedMps}}"""
        }
        val routesJson = pointsByTrip.entries.joinToString(",", "{", "}") { (id, pts) ->
            "\"$id\":" + pts.joinToString(",", "[", "]") { "[${it.lat},${it.lon}]" }
        }
        return """<!doctype html>
<title>Odograph Archive</title>
<style>
:root{--bg:#08090C;--fg:#EAEEF4;--dim:#8B96A5;--line:#1E2530;--accent:#3DE1FF}
body{margin:0;background:var(--bg);color:var(--fg);
 font:14px/1.5 ui-sans-serif,system-ui,sans-serif;padding:32px}
h1{font-size:22px;margin:0 0 4px}p.sub{color:var(--dim);margin:0 0 28px}
table{border-collapse:collapse;width:100%;font-variant-numeric:tabular-nums}
th,td{text-align:right;padding:8px 12px;border-bottom:1px solid var(--line)}
th{color:var(--dim);font-weight:500;font-size:11px;letter-spacing:.08em;text-transform:uppercase}
td:first-child,th:first-child{text-align:left}
.cards{display:flex;gap:1px;background:var(--line);border:1px solid var(--line);
 margin-bottom:28px;flex-wrap:wrap}
.cards div{background:var(--bg);padding:14px 20px;flex:1;min-width:130px}
.cards b{display:block;font-size:26px;font-weight:600}
.cards span{color:var(--dim);font-size:11px;letter-spacing:.08em;text-transform:uppercase}
</style>
<h1>Odograph Archive</h1>
<p class="sub">Generated on the device. Fully offline &mdash; no network needed to open this file.</p>
<div class="cards" id="cards"></div>
<table id="t"><thead><tr>
<th>Started</th><th>Distance</th><th>Duration</th><th>Moving</th><th>Avg</th><th>Max</th>
</tr></thead><tbody></tbody></table>
<script>
const TRIPS = $tripsJson;
const ROUTES = $routesJson;
const km = m => (m/1000).toFixed(1);
const hm = s => String(Math.floor(s/3600)).padStart(2,'0')+':'+String(Math.floor(s%3600/60)).padStart(2,'0');
const kmh = mps => (mps*3.6).toFixed(0);
document.getElementById('cards').innerHTML = [
  ['Trips', TRIPS.length],
  ['Total km', km(TRIPS.reduce((a,t)=>a+t.distanceM,0))],
  ['Total time', hm(TRIPS.reduce((a,t)=>a+t.durationS,0))],
  ['Top speed', kmh(Math.max(0,...TRIPS.map(t=>t.maxSpeedMps)))+' km/h']
].map(([l,v])=>'<div><b>'+v+'</b><span>'+l+'</span></div>').join('');
document.querySelector('#t tbody').innerHTML = TRIPS.map(t =>
  '<tr><td>'+new Date(t.startedAt).toLocaleString()+'</td><td>'+km(t.distanceM)+
  '</td><td>'+hm(t.durationS)+'</td><td>'+hm(t.movingS)+'</td><td>'+kmh(t.avgSpeedMps)+
  '</td><td>'+kmh(t.maxSpeedMps)+'</td></tr>').join('');
</script>"""
    }
}
```

- [ ] **Step 4: Run and confirm pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*DashboardHtmlTest*'`
Expected: PASS (2 tests)

- [ ] **Step 5: Implement the Ktor server**

```kotlin
package `in`.odograph.tracker.server

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.export.Exporters

object DashboardServer {
    private var engine: ApplicationEngine? = null

    fun start(ctx: Context, port: Int = 8080) {
        if (engine != null) return
        val dao = OdographDb.get(ctx).dao()
        engine = embeddedServer(CIO, port = port) {
            routing {
                get("/") {
                    val trips = dao.allTrips()
                    val routes = trips.associate { it.id to dao.pointsFor(it.id) }
                    call.respondText(DashboardHtml.render(trips, routes), ContentType.Text.Html)
                }
                get("/trips.csv") {
                    call.respondText(Exporters.tripsCsv(dao.allTrips()), ContentType.Text.CSV)
                }
            }
        }.also { it.start(wait = false) }
    }

    fun stop() { engine?.stop(500, 1000); engine = null }
}
```

Add `<uses-permission android:name="android.permission.INTERNET" />` to the manifest —
needed to *listen* on a socket, not to reach the internet.

- [ ] **Step 6: Build, then verify from the Mac over the phone hotspot**

Run:
```bash
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell ip route     # find the box IP
curl -s http://<BOX_IP>:8080/ | head -20
```
Expected: HTML beginning `<!doctype html>`.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat(server): embedded dashboard with self-contained offline archive"
```

---

# PHASE 4 — Ship

### Task 16: Release APK and install runbook

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `keystore.properties` (git-ignored)
- Create: `.gitignore`
- Create: `docs/INSTALL.md`

- [ ] **Step 1: Write `.gitignore`**

```gitignore
*.iml
.gradle/
build/
local.properties
keystore.properties
*.jks
.DS_Store
```

- [ ] **Step 2: Generate a signing key**

Run:
```bash
keytool -genkey -v -keystore odograph.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias odograph
```

- [ ] **Step 3: Add signing config to `app/build.gradle.kts`**

```kotlin
val keystoreProps = java.util.Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(f.inputStream())
}
android {
    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

- [ ] **Step 4: Build the release APK**

Run: `./gradlew :app:assembleRelease`
Expected: `app/build/outputs/apk/release/app-release.apk` exists.

- [ ] **Step 5: Write `docs/INSTALL.md`**

Document both install paths (ADB over WiFi; USB stick + file manager), the exact permission
grants needed (**Location: Allow all the time**, Notifications, and battery-optimisation
exemption), and how to reach the dashboard from the Mac over the phone hotspot.

- [ ] **Step 6: Run the whole suite one final time**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all tests pass, zero failures.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "chore: release signing and install runbook"
```

---

## Self-Review

**Spec coverage.** Every numbered spec section maps to a task: §4 architecture → Tasks 5–8;
§5 D1 → Task 6; D2 → Task 7 (`toFix` takes `location.time`); D3 → Task 7; D4 → Task 6 origin
backfill; D5 → Task 10 (`ACQUIRING`); D6 → Tasks 11–12; D7 → Task 9; D8 → Task 12; D9 →
Tasks 2, 14, 15. §6 data model → Task 5. §7 stat definitions → Task 4, including the rejected
"lowest speed" replaced by `slowestKmSpeedMps`. §8 risks → Task 2 gate and Task 8 Step 7
fallback ladder.

**Placeholder scan.** No TBDs. Every code step carries real, compilable content. The only
prose-only step is Task 14 Step 5 (button wiring) and Task 16 Step 5 (runbook), both of which
describe concrete, already-defined calls.

**Type consistency.** `Fix` and `Stats` defined in Task 4 are consumed unchanged in Tasks 6,
7 and 8. `OdographDao` method names in Task 5 (`startTrip`, `appendPoint`, `openTrip`,
`finishTrip`, `setOrigin`, `setDestination`, `pointsFor`, `allTrips`) match every later call
site. `Palette` fields from Task 9 match usage in Tasks 10–12. `shareFile` from Task 2 is
reused verbatim in Task 14.

**Known gap, deliberate:** the cluster **Direction** is currently hardcoded to `ION` in Task 11
Step 6 pending the visual pick. Changing it is a one-token edit; no structural work depends on it.
