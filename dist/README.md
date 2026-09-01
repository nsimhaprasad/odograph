# Odograph APKs

| File | Use |
|---|---|
| `odograph-0.1.0.apk` | **Install this one.** Release build, signed, 8.4 MB. |
| `odograph-0.1.0-debug.apk` | Debug build, only if you want logcat-friendly output. |

Do not install both — they share an application ID and will conflict.

## Getting it onto the Portronics Tune Prime

**USB stick / microSD** — copy the APK across, open it with the box's file manager, allow
*Install unknown apps* when prompted.

**ADB over WiFi** — with the box and this Mac on the same network (phone hotspot is fine):

```bash
adb connect <BOX_IP>:5555
adb install -r odograph-0.1.0.apk
```

## After installing

1. Grant **Location → Allow all the time**. This is a separate second grant on Android 10+; the
   app cannot ask for it until the foreground grant is held.
2. Allow notifications (the recorder runs as a foreground service).
3. Settings → Battery → Odograph → **don't optimise**.

4. Optional: SETUP → **SPEED ALERT** → pick a limit. The gauge turns red the instant you pass
   it; the chime waits 3 seconds so a brief overtake stays silent, and repeats at most every
   25 seconds. Choose SILENT, CHIME or VOICE.

Full detail, including what to do if it does not auto-start after ignition, is in
`docs/INSTALL.md`.
