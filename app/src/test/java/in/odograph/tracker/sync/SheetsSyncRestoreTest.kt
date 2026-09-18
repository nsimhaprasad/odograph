package `in`.odograph.tracker.sync

import androidx.test.core.app.ApplicationProvider
import `in`.odograph.tracker.data.OdographDb
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The guards in front of a restore.
 *
 * Everything here is about the same failure: a restore that refuses must refuse *before* it
 * clears anything. The action is destructive by design, so the gap between "this cannot work" and
 * "this has emptied your history" is one `return` statement, and nothing afterwards would tell
 * the driver which of the two happened.
 */
@RunWith(RobolectricTestRunner::class)
class SheetsSyncRestoreTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        OdographDb.resetForTests(ctx)
        onIo { OdographDb.get(ctx).dao().startTrip(1_000L) }
    }

    @After
    fun tearDown() {
        OdographDb.resetForTests(ctx)
    }

    @Test
    fun `no configured link refuses without touching the history`() {
        val result = onIo { SheetsSync.restoreFromSheet(ctx, "") }

        assertThat(result.ok).isFalse()
        assertThat(result.message).contains("No docs link")
        assertThat(onIo { OdographDb.get(ctx).dao().allTrips() })
            .`as`("a restore that never ran must leave the history alone")
            .hasSize(1)
    }

    /**
     * The commonest misconfiguration by a wide margin: the sheet's own URL is what a person has to
     * hand, and it looks like the right thing to paste. It serves a web page rather than the
     * backup, so a restore from it would read HTML and find no drives in it.
     */
    @Test
    fun `a spreadsheet link refuses with the fix and keeps the history`() {
        val link = "https://docs.google.com/spreadsheets/d/EXAMPLE_SHEET_ID/edit?usp=sharing"

        val result = onIo { SheetsSync.restoreFromSheet(ctx, link) }

        assertThat(result.ok).isFalse()
        assertThat(result.message).contains("Apps Script")
        assertThat(onIo { OdographDb.get(ctx).dao().allTrips() }).hasSize(1)
    }

    /** The help is shared by upload and restore, so it must not promise either one specifically. */
    @Test
    fun `the spreadsheet help does not claim the sheet link can still be read`() {
        val message = onIo {
            SheetsSync.restoreFromSheet(ctx, "https://docs.google.com/spreadsheets/d/EXAMPLE_SHEET_ID/edit")
        }.message
        assertThat(message).doesNotContain("stays valid for reading")
        assertThat(message).contains("/exec")
    }

    /**
     * Room refuses database work on the main thread, and rightly: this is how the app actually
     * calls it, off the UI dispatcher.
     */
    private fun <T> onIo(block: () -> T): T {
        var result: Result<T>? = null
        val thread = Thread { result = runCatching(block) }
        thread.start()
        thread.join()
        return result!!.getOrThrow()
    }
}
