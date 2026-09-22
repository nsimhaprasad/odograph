package `in`.odograph.tracker.server

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream

/**
 * Installing a new build of the app over the LAN.
 *
 * The box has no ADB, no cable and no screen worth typing on, so every deploy so far has meant
 * carrying a URL to its browser by hand. It does however already run an HTTP server that the
 * laptop can reach, which is enough: the new APK can simply be posted to the running app and
 * handed to Android's package installer.
 *
 * What this cannot do is install silently. Android reserves that for device owners and
 * system-signed apps, so the last step is a confirmation dialog on the box's own screen. That is
 * one tap on the device instead of a browser, a download and a file manager — and it is the floor,
 * not an implementation shortcut.
 */
object SelfUpdate {

    /** Where a posted build is staged. Private to the app, so no storage permission is involved. */
    private const val STAGED = "update.apk"

    /** The smallest thing that could plausibly be an APK. Guards against a truncated upload. */
    private const val MIN_PLAUSIBLE_APK_BYTES = 1_000_000L

    data class Result(val ok: Boolean, val message: String)

    /**
     * Stages [body] and asks Android to install it.
     *
     * The upload is checked before it is offered to the installer: a zip magic number and a
     * plausible size. Handing a truncated file to PackageInstaller fails later and less clearly,
     * and on a box with no screen in front of you "the POST succeeded" followed by silence is the
     * worst possible outcome.
     */
    fun install(ctx: Context, body: InputStream): Result {
        val staged = File(ctx.cacheDir, STAGED)
        val written = runCatching {
            staged.outputStream().use { out -> body.copyTo(out) }
            staged.length()
        }.getOrElse { return Result(false, "could not stage the upload: ${it.message}") }

        if (written < MIN_PLAUSIBLE_APK_BYTES) {
            staged.delete()
            return Result(false, "only $written bytes arrived — that is not a complete APK")
        }
        if (!looksLikeApk(staged)) {
            staged.delete()
            return Result(false, "the upload is not a zip archive, so it is not an APK")
        }

        return runCatching { offerToInstaller(ctx, staged) }
            .getOrElse { Result(false, "the installer refused it: ${it.message}") }
    }

    /** An APK is a zip, and every zip starts "PK". */
    private fun looksLikeApk(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(4)
            input.read(head) == 4 &&
                head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
        }
    }.getOrDefault(false)

    /**
     * Hands the staged build to Android's package installer UI.
     *
     * ACTION_VIEW on a FileProvider URI rather than a PackageInstaller session. The session API is
     * the modern one and it commits perfectly well from here, but it answers with
     * STATUS_PENDING_USER_ACTION and expects *something* to put its dialog on screen — which from
     * a background HTTP handler on a head unit never materialised: no dialog, no result broadcast,
     * nothing in the log. This route is what the box's own browser does when a downloaded APK is
     * tapped.
     *
     * The grant flag matters: without it the installer gets a URI it is not allowed to read and
     * fails with a parse error that says nothing useful.
     *
     * And it is offered twice, because once is not enough. Starting the activity works only while
     * the app is in the foreground: Android has blocked background activity starts since 10, this
     * handler runs on an HTTP thread with nothing on screen, and the start is discarded with
     * BAL_BLOCK. The push reported success, said the installer was waiting, and nothing appeared —
     * twice, on two different builds, before the reason was looked up rather than guessed at.
     *
     * A notification is the sanctioned path, because tapping one is the driver's own action and
     * that start is allowed. So the direct attempt stays for the case where the screen is already
     * showing the app, and the notification is there for every other case.
     */
    private fun offerToInstaller(ctx: Context, staged: File): Result {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", staged)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val launched = runCatching { ctx.startActivity(intent); true }.getOrElse { false }
        val notified = runCatching { notifyInstaller(ctx, intent); true }.getOrElse { false }

        return Result(
            true,
            when {
                launched && notified ->
                    "staged. If the installer did not open on the box, tap the " +
                        "\"Odograph update ready\" notification."
                notified ->
                    "staged. Tap the \"Odograph update ready\" notification on the box to install."
                launched -> "staged; the installer is on the box's screen."
                else -> "staged at ${staged.name}, but the box could not be prompted."
            }
        )
    }

    /**
     * The reliable half: a notification carrying the same install intent.
     *
     * High importance so it surfaces on a head unit without the driver going looking for it, and
     * auto-cancelling so a stale offer does not sit there after the update is done.
     */
    private fun notifyInstaller(ctx: Context, intent: Intent) {
        val manager =
            ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    UPDATE_CHANNEL, "Odograph updates",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val pending = android.app.PendingIntent.getActivity(
            ctx, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            UPDATE_NOTIFICATION,
            android.app.Notification.Builder(ctx, UPDATE_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Odograph update ready")
                .setContentText("Tap to install the build that was just pushed.")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        )
    }

    private const val UPDATE_CHANNEL = "odograph_update"
    private const val UPDATE_NOTIFICATION = 91_002
}
