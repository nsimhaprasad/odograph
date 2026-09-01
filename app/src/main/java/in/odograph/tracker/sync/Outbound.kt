package `in`.odograph.tracker.sync

import android.content.Context
import `in`.odograph.tracker.data.OdographDb
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional off-device delivery.
 *
 * The whole feature is inert until a webhook URL is configured, and every failure is swallowed:
 * recording must work identically with the hotspot off, in a tunnel, or with the endpoint
 * deleted. Nothing here is ever called from the capture path.
 *
 * The URL is a capability token — typically a Google Apps Script web app that appends to a
 * spreadsheet the owner controls. Holding a URL rather than a credential means the device stores
 * nothing that grants access to an account, and revoking is a single click.
 */
object Outbound {

    data class Result(val attempted: Int, val delivered: Int, val error: String? = null)

    fun syncPending(ctx: Context, url: String, deviceId: String, limit: Int = 200): Result {
        if (url.isBlank()) return Result(0, 0, "no webhook configured")

        val dao = OdographDb.get(ctx).dao()
        val pending = dao.unsyncedTrips().take(limit)
        if (pending.isEmpty()) return Result(0, 0)

        return runCatching {
            val body = """{"trips":${TripJson.encodeBatch(pending, deviceId)}}"""
            val code = post(url, body)
            if (code in 200..299) {
                val now = System.currentTimeMillis()
                pending.forEach { dao.markSynced(it.id, now) }
                Result(pending.size, pending.size)
            } else {
                Result(pending.size, 0, "endpoint returned HTTP $code")
            }
        }.getOrElse { Result(pending.size, 0, it.message ?: it::class.java.simpleName) }
    }

    private fun post(url: String, body: String): Int {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            // Apps Script redirects the POST to a googleusercontent URL; follow it.
            instanceFollowRedirects = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "Odograph/0.1")
        }
        return try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }
}
