# Installing Odograph on the Portronics Tune Prime

## Build

```bash
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

`./gradlew` downloads Gradle 8.7 on first run. The only prerequisites are a JDK 17 and the
Android SDK at the path in `local.properties`.

## Install — two paths

**Path A, ADB over WiFi** (preferred; needs Developer options on the box)

1. On the box: Settings → About → tap *Build number* seven times.
2. Developer options → enable **USB debugging** and, if present, **Wireless debugging**.
3. Put the Mac and the box on the same network (your phone's hotspot works).

```bash
adb connect <BOX_IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Path B, file manager** (works even with Developer options locked)

Copy the APK to a USB stick or the microSD card, open it with the box's file manager, and allow
*Install unknown apps* when prompted.

## Permissions to grant

| Permission | Why | Where |
|---|---|---|
| **Location — Allow all the time** | Recording continues while Maps or Spotify are in front | Settings → Apps → Odograph → Permissions → Location |
| Notifications | The foreground service needs a visible notification | Prompted on first run |
| Battery optimisation — **don't optimise** | Stops the system trimming the recorder | Settings → Battery → Odograph |

"Allow all the time" is a **separate second grant** on Android 10+; the in-app prompt can only ask
for it after the foreground grant is held.

## If it does not auto-start after ignition

Work down this ladder, and note which rung worked:

1. Grant the battery-optimisation exemption above.
2. Add Odograph to any vendor *auto-start* / *protected apps* list in the box's settings.
3. Lower `targetSdk` to 28 in `app/build.gradle.kts` — a sideloaded personal app sidesteps most
   background foreground-service restrictions.
4. Register an `AccessibilityService` as a keep-alive. The system binds these deliberately, so
   vendor app-killers generally leave them alone.
5. Set Odograph as the launcher.

## Reaching the dashboard from your Mac

With both on the same network (phone hotspot is fine):

```
http://<box-ip>:8080/          dashboard
http://<box-ip>:8080/config    paste the optional webhook URL here
http://<box-ip>:8080/trips.csv raw export
```

The box also advertises itself over Bonjour as **Odograph**, so it should appear in Safari's
Bonjour list without hunting for the IP.

## Getting data out without any network

Three independent paths, in order of least effort:

1. **Share sheet** — SETUP → *Share trips CSV*. The chooser offers **Bluetooth to your Mac**
   alongside Drive and Gmail. No Bluetooth code exists in this app; the OS does it all.
2. **Files on disk** — everything is written to the app's external files directory under
   `exports/`, readable over USB or by any file manager.
3. **Offline archive** — `/archive.html` is a single self-contained file. Save it once and it
   opens on the Mac forever, offline, with the car parked.
