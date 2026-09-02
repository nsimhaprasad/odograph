package `in`.odograph.tracker.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Version 1 was never exported, so MigrationTestHelper cannot drive this. Building a v1-shaped
 * database from the original DDL and running the real migration against it tests the same thing:
 * that the statements apply and that existing drives survive.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val v1Trips = """
        CREATE TABLE IF NOT EXISTS `trips` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `startedAt` INTEGER NOT NULL, `endedAt` INTEGER,
            `distanceM` REAL NOT NULL, `durationS` INTEGER NOT NULL,
            `movingS` INTEGER NOT NULL, `maxSpeedMps` REAL NOT NULL,
            `avgSpeedMps` REAL NOT NULL, `slowestKmMps` REAL NOT NULL,
            `startLat` REAL, `startLon` REAL, `endLat` REAL, `endLon` REAL,
            `clusterId` INTEGER, `socStart` REAL, `socEnd` REAL, `energyKwh` REAL,
            `syncedAt` INTEGER)
    """.trimIndent()

    private var helper: SupportSQLiteOpenHelper? = null

    private fun openV1(): SupportSQLiteDatabase {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase("migration-test.db")
        val h = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name("migration-test.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(v1Trips)
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                })
                .build()
        )
        helper = h
        return h.writableDatabase
    }

    @After
    fun tearDown() {
        helper?.close()
    }

    @Test
    fun `migrating from v1 keeps existing drives and adds the new columns`() {
        val db = openV1()
        db.execSQL(
            """INSERT INTO trips (startedAt, endedAt, distanceM, durationS, movingS,
               maxSpeedMps, avgSpeedMps, slowestKmMps)
               VALUES (1000, 2000, 5000.0, 60, 55, 20.0, 18.0, 4.0)"""
        )

        OdographDb.MIGRATION_1_2.migrate(db)

        db.query("SELECT startedAt, distanceM, startPlaceId FROM trips").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getLong(0)).isEqualTo(1000L)
            assertThat(c.getDouble(1)).isEqualTo(5000.0)
            assertThat(c.isNull(2)).`as`("startPlaceId defaults to null").isTrue()
        }
    }

    @Test
    fun `migrating from v1 creates the places table`() {
        val db = openV1()
        OdographDb.MIGRATION_1_2.migrate(db)

        db.execSQL("INSERT INTO places (lat, lon, visits) VALUES (12.97, 77.59, 3)")
        db.query("SELECT lat, lon, visits, label, autoName FROM places").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getDouble(0)).isEqualTo(12.97)
            assertThat(c.getInt(2)).isEqualTo(3)
            assertThat(c.isNull(3)).isTrue()
        }
    }
}
