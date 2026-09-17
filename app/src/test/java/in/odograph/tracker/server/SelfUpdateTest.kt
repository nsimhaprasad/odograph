package `in`.odograph.tracker.server

import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

/**
 * What the update endpoint refuses before it bothers Android with it.
 *
 * The checks matter more here than they look. Nobody is watching the box's screen when a deploy
 * runs, so a bad upload that is accepted, committed and then silently fails leaves the laptop
 * believing it shipped. Rejecting early gives the person at the other end of the curl a sentence
 * that says what went wrong.
 */
@RunWith(RobolectricTestRunner::class)
class SelfUpdateTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun body(bytes: ByteArray) = ByteArrayInputStream(bytes)

    /** A zip, and therefore APK-shaped, of a given size. */
    private fun zipOf(size: Int) = ByteArray(size).also {
        it[0] = 0x50; it[1] = 0x4B; it[2] = 0x03; it[3] = 0x04
    }

    @Test
    fun `an empty upload is refused`() {
        val r = SelfUpdate.install(ctx, body(ByteArray(0)))
        assertThat(r.ok).isFalse()
        assertThat(r.message).contains("0 bytes")
    }

    /**
     * The case this guard exists for: a connection dropped mid-POST leaves a file that is a
     * perfectly valid zip prefix and a useless APK.
     */
    @Test
    fun `a truncated upload is refused rather than installed`() {
        val r = SelfUpdate.install(ctx, body(zipOf(64 * 1024)))
        assertThat(r.ok).isFalse()
        assertThat(r.message).contains("not a complete APK")
    }

    @Test
    fun `something that is not a zip is refused`() {
        val notAnApk = ByteArray(2_000_000) { 'x'.code.toByte() }
        val r = SelfUpdate.install(ctx, body(notAnApk))
        assertThat(r.ok).isFalse()
        assertThat(r.message).contains("not a zip archive")
    }

    @Test
    fun `the refusal says which problem it was`() {
        val truncated = SelfUpdate.install(ctx, body(zipOf(1024))).message
        val wrongType = SelfUpdate.install(ctx, body(ByteArray(2_000_000))).message
        assertThat(truncated).isNotEqualTo(wrongType)
    }

    /** A rejected upload must not be left lying in the cache. */
    @Test
    fun `a refused upload leaves nothing staged`() {
        SelfUpdate.install(ctx, body(zipOf(1024)))
        val staged = ctx.cacheDir.listFiles()?.filter { it.name.endsWith(".apk") }.orEmpty()
        assertThat(staged).`as`("nothing left behind").isEmpty()
    }

    @Test
    fun `the plausibility floor is large enough to catch a stub and small enough for a real build`() {
        // The shipping APK is around 9 MB; a truncated transfer is typically kilobytes.
        val r = SelfUpdate.install(ctx, body(zipOf(900_000)))
        assertThat(r.ok).`as`("900 KB is not a plausible build of this app").isFalse()
    }
}
