package `in`.odograph.tracker.server

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures every body the MG servers reply with, so a frame can be inspected from the dashboard at
 * /frames. Each entry couples the decoded model with the raw payload it came from, which shows that
 * nothing the car actually sent was silently dropped by the decoder.
 *
 * Raw TAP frames echo the account's session UID and token in ASCII, so the log is sensitive: it
 * lives in private app storage and the dashboard page warns against sharing it.
 */
object RawFrames {

    private const val MAX_BLOCKS = 24
    private const val HEADER_PREFIX = ">> "

    /** One timestamped entry: a raw server response or the decoded model built from it. */
    data class Entry(val label: String, val content: String)

    /** Directory the log lives in, under the app's private storage. */
    fun directory(context: Context): File = File(context.filesDir, "raw_frames")

    /** Append a response, keeping only the most recent [MAX_BLOCKS] entries. */
    @Synchronized
    fun record(dir: File, label: String, content: String) {
        if (content.isBlank()) return
        dir.mkdirs()
        val kept = read(dir).takeLast(MAX_BLOCKS - 1)
        File(dir, "frames.log").writeText((kept + Entry(label, content)).joinToString("\n") { render(it) })
    }

    /** The dated log, newest last. */
    fun read(dir: File): List<Entry> {
        val log = File(dir, "frames.log")
        if (!log.exists()) return emptyList()

        val entries = mutableListOf<Entry>()
        var label: String? = null
        val content = StringBuilder()
        log.forEachLine { line ->
            if (line.startsWith(HEADER_PREFIX)) {
                if (label != null) entries.add(Entry(label!!, content.toString().trimEnd()))
                content.setLength(0)
                label = line.removePrefix(HEADER_PREFIX).substringAfter(' ')
            } else if (label != null) {
                if (content.isNotEmpty()) content.append('\n')
                content.append(line)
            }
        }
        if (label != null) entries.add(Entry(label!!, content.toString().trimEnd()))
        return entries
    }

    private fun render(entry: Entry): String =
        HEADER_PREFIX + stamp() + " " + entry.label + "\n" + entry.content

    private fun stamp(): String = SimpleDateFormat("MM-dd_HH:mm:ss", Locale.US).format(Date())
}