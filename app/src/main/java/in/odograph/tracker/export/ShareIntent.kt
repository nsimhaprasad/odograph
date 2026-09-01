package `in`.odograph.tracker.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Sharing over Bluetooth to a Mac needs no Bluetooth code at all. Write the file, expose it
 * through a FileProvider, and fire ACTION_SEND: the system chooser then offers Bluetooth
 * alongside Drive, Gmail and everything else installed, and the OS handles pairing and transfer.
 */
fun shareFile(ctx: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(ctx, "in.odograph.tracker.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(
        Intent.createChooser(send, "Share ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

fun writeExport(ctx: Context, name: String, content: String): File {
    val dir = File(ctx.getExternalFilesDir(null), "exports").apply { mkdirs() }
    return File(dir, name).apply { writeText(content) }
}
