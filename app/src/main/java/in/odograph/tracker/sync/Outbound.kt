package `in`.odograph.tracker.sync

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional off-device delivery.
 *
 * The whole feature is inert until a docs URL is configured, and every failure is swallowed:
 * recording must work identically with the hotspot off, in a tunnel, or with the endpoint
 * deleted. Nothing here is ever called from the capture path.
 *
 * The URL is a capability token — typically a Google Apps Script web app that rewrites a
 * spreadsheet the owner controls. Holding a URL rather than a credential means the device stores
 * nothing that grants access to an account, and revoking is a single click.
 */
object Outbound {

    data class Result(val attempted: Int, val delivered: Int, val error: String? = null)

    /** Shared HTTP POST for every sync path. */
    fun postJson(url: String, body: String): Int = post(url, body)

    /**
     * Reads a body over HTTP GET, used by the docs link import. Throws on transport failure.
     *
     * The timeout is a parameter because the two readers want very different patience. A control
     * read is four numbers and should fail fast; a full backup is the entire history, which Apps
     * Script assembles a tab at a time and can take minutes on a long one. Twenty seconds applied
     * to the second would report a broken sheet every time and be believed.
     */
    fun getBody(url: String, readTimeoutMs: Int = 20_000): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Odograph/0.1")
        }
        return try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("endpoint returned HTTP ${conn.responseCode}")
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** A shareable spreadsheet link, not a script endpoint. It cannot receive POSTs. */
    fun isSpreadsheetLink(url: String): Boolean =
        url.contains("docs.google.com") && url.contains("/spreadsheets/")

    /** The spreadsheet id out of a docs link, for the config hint. Null for non-sheet URLs. */
    fun docsSheetId(url: String): String? =
        Regex("""spreadsheets/d/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)

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
