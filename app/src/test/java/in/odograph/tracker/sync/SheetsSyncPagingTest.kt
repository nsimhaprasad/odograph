package `in`.odograph.tracker.sync

import androidx.test.core.app.ApplicationProvider
import `in`.odograph.tracker.data.OdographDb
import `in`.odograph.tracker.data.PointEntity
import `in`.odograph.tracker.ui.theme.Settings
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The export goes to the sheet a page at a time.
 *
 * The first backup from a box that has never backed up owes the sheet its whole history, and
 * every drive brings its points. Sent as one document that was a 69 MB string on a 128 MB heap,
 * and it died before it reached the network — so the archive stayed empty for exactly as long as
 * the box had been recording. These tests pin the shape that fixes it: bounded pages, watermarks
 * that advance only when a page lands, and a run that keeps going until it has caught up.
 */
@RunWith(RobolectricTestRunner::class)
class SheetsSyncPagingTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val url = "https://script.google.com/macros/s/x/exec"

    @Before
    fun setUp() {
        OdographDb.resetForTests(ctx)
        Settings(ctx).apply { lastDocsTripId = 0; lastDocsBatteryId = 0; lastDocsChargeId = 0; lastDocsDay = 0 }
    }

    @After
    fun tearDown() {
        OdographDb.resetForTests(ctx)
    }

    /** [n] closed drives, each with [pointsEach] points, all comfortably in the past. */
    private fun history(n: Int, pointsEach: Int = 3) = onIo {
        val dao = OdographDb.get(ctx).dao()
        repeat(n) { i ->
            val at = 1_000_000L + i * 3_600_000L
            val id = dao.startTrip(at)
            repeat(pointsEach) { k ->
                dao.appendPoint(PointEntity(0, id, at + k * 1000L, 12.97 + k * 0.001, 77.59, 5f, null, 900.0, 5f, false))
            }
            dao.finishTrip(id, at + 600_000L, 1500.0, 600, 500, 10f, 5.0, 3.0, 0.0, 0.0)
        }
    }

    /** Captures every page a run sends, answering [status] to each. */
    private class Capture(private val status: Int = 200) {
        val pages = mutableListOf<JSONObject>()
        val post: (String, String) -> Int = { _, body -> pages += JSONObject(body); status }
    }

    private fun tripsIn(page: JSONObject) = page.getJSONArray("trips").length()
    private fun pointsIn(page: JSONObject) = page.getJSONArray("points").length()

    @Test
    fun `a history larger than one page goes in several, and all of it goes`() {
        history(n = 23)
        val net = Capture()

        val r = onIo { SheetsSync.exportDocs(ctx, url, "box", net.post) }

        assertThat(r.error).isNull()
        assertThat(net.pages).hasSize(3)
        assertThat(net.pages.map { tripsIn(it) }).containsExactly(10, 10, 3)
        assertThat(net.pages.sumOf { pointsIn(it) }).`as`("every point of every drive").isEqualTo(23 * 3)
        assertThat(r.delivered).isEqualTo(23 + 23 * 3)
    }

    /** Each page carries its own drives' points and nobody else's. */
    @Test
    fun `points travel with their drives`() {
        history(n = 12, pointsEach = 4)
        val net = Capture()

        onIo { SheetsSync.exportDocs(ctx, url, "box", net.post) }

        net.pages.forEach { page ->
            val ids = (0 until tripsIn(page)).map { page.getJSONArray("trips").getJSONObject(it).getLong("id") }.toSet()
            val pts = page.getJSONArray("points")
            (0 until pts.length()).forEach { k ->
                assertThat(pts.getJSONObject(k).getLong("tripId")).isIn(ids)
            }
            assertThat(pts.length()).isEqualTo(ids.size * 4)
        }
    }

    /**
     * The watermark moves only when a page lands. A refused page leaves everything where it was,
     * so the next run sends the same delta again — and the receiving script upserts by key, so a
     * page that landed but was not acknowledged is harmless to repeat.
     */
    @Test
    fun `a refused page stops the run and leaves the watermark where it was`() {
        history(n = 15)
        val net = Capture(status = 500)

        val r = onIo { SheetsSync.exportDocs(ctx, url, "box", net.post) }

        assertThat(r.error).contains("HTTP 500")
        assertThat(net.pages).`as`("it stopped at the first refusal").hasSize(1)
        assertThat(Settings(ctx).lastDocsTripId).isZero()
        assertThat(r.delivered).isZero()
    }

    @Test
    fun `a second run after a partial failure resumes from the first unsent drive`() {
        history(n = 15)
        var calls = 0
        val flaky = Capture()
        // First page lands, second is refused.
        val post: (String, String) -> Int = { u, b -> calls++; if (calls == 2) 503 else flaky.post(u, b) }
        onIo { SheetsSync.exportDocs(ctx, url, "box", post) }
        assertThat(Settings(ctx).lastDocsTripId).`as`("ten drives acknowledged").isEqualTo(10)

        val net = Capture()
        val r = onIo { SheetsSync.exportDocs(ctx, url, "box", net.post) }

        assertThat(r.error).isNull()
        assertThat(net.pages).hasSize(1)
        assertThat(tripsIn(net.pages[0])).isEqualTo(5)
        assertThat(net.pages[0].getJSONArray("trips").getJSONObject(0).getLong("id")).isEqualTo(11)
    }

    @Test
    fun `nothing new sends nothing and reports nothing`() {
        history(n = 4)
        onIo { SheetsSync.exportDocs(ctx, url, "box", Capture().post) }
        val net = Capture()

        val r = onIo { SheetsSync.exportDocs(ctx, url, "box", net.post) }

        assertThat(net.pages).isEmpty()
        assertThat(r.delivered).isZero()
        assertThat(r.error).isNull()
    }

    /** Places are small and every drive may point at one, so every page carries all of them. */
    @Test
    fun `every page carries every place`() {
        onIo {
            val dao = OdographDb.get(ctx).dao()
            dao.insertPlace(`in`.odograph.tracker.data.PlaceEntity(lat = 12.97, lon = 77.59, visits = 3, label = "Home"))
            dao.insertPlace(`in`.odograph.tracker.data.PlaceEntity(lat = 12.99, lon = 77.61, visits = 1, label = "Work"))
        }
        history(n = 12)
        val net = Capture()

        onIo { SheetsSync.exportDocs(ctx, url, "box", net.post) }

        assertThat(net.pages).hasSize(2)
        net.pages.forEach { assertThat(it.getJSONArray("places").length()).isEqualTo(2) }
    }

    private fun <T> onIo(block: () -> T): T {
        var result: Result<T>? = null
        val thread = Thread { result = runCatching(block) }
        thread.start()
        thread.join()
        return result!!.getOrThrow()
    }
}
