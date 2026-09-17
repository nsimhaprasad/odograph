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
     * nothing in the log. This route brings the installer up directly and is what the box's own
     * browser does when a downloaded APK is tapped.
     *
     * The grant flag matters: without it the installer gets a URI it is not allowed to read and
     * fails with a parse error that says nothing useful.
     */
    private fun offerToInstaller(ctx: Context, staged: File): Result {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", staged)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)

        return Result(
            true,
            "staged; the installer is now on the box's screen. Approve it there and the app " +
                "restarts on the new build."
        )
    }
}
