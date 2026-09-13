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

    // The v2 DDL is the exported 2.json schema. Running MIGRATION_2_3 against it proves the
    // upgrade is purely additive: nothing here is touched once the battery table exists.
    private val v2Trips = """
        CREATE TABLE IF NOT EXISTS `trips` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `startedAt` INTEGER NOT NULL, `endedAt` INTEGER,
            `distanceM` REAL NOT NULL, `durationS` INTEGER NOT NULL,
            `movingS` INTEGER NOT NULL, `maxSpeedMps` REAL NOT NULL,
            `avgSpeedMps` REAL NOT NULL, `slowestKmMps` REAL NOT NULL,
            `startLat` REAL, `startLon` REAL, `endLat` REAL, `endLon` REAL,
            `clusterId` INTEGER, `startPlaceId` INTEGER, `endPlaceId` INTEGER,
            `syncedAt` INTEGER, `socStart` REAL, `socEnd` REAL, `energyKwh` REAL)
    """.trimIndent()

    private val v2Points = """
        CREATE TABLE IF NOT EXISTS `points` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `tripId` INTEGER NOT NULL, `t` INTEGER NOT NULL,
            `lat` REAL NOT NULL, `lon` REAL NOT NULL, `speedMps` REAL NOT NULL,
            `bearingDeg` REAL, `altitudeM` REAL, `accuracyM` REAL NOT NULL,
            `interpolated` INTEGER NOT NULL)
    """.trimIndent()

    private val v2Places = """
        CREATE TABLE IF NOT EXISTS `places` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `lat` REAL NOT NULL, `lon` REAL NOT NULL, `visits` INTEGER NOT NULL,
            `label` TEXT, `autoName` TEXT, `geocodedAt` INTEGER)
    """.trimIndent()

    private var helper: SupportSQLiteOpenHelper? = null

    private fun openV4(): SupportSQLiteDatabase {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase("migration-test.db")
        val h = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name("migration-test.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // The v4 trips table is the v2 shape plus the cost column 3->4 added.
                        db.execSQL(v2Trips.replace("`energyKwh` REAL)", "`energyKwh` REAL, `costInr` REAL)"))
                        db.execSQL(v2Points)
                        db.execSQL(v2Places)
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                })
                .build()
        )
        helper = h
        return h.writableDatabase
    }

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

    private fun openV2(): SupportSQLiteDatabase {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase("migration-test.db")
        val h = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name("migration-test.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(v2Trips)
                        db.execSQL(v2Points)
                        db.execSQL(v2Places)
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

    @Test
    fun `migrating from v2 keeps existing drives, points, and places intact`() {
        val db = openV2()
        db.execSQL(
            """INSERT INTO trips (startedAt, endedAt, distanceM, durationS, movingS,
               maxSpeedMps, avgSpeedMps, slowestKmMps, startPlaceId, endPlaceId)
               VALUES (1000, 2000, 5000.0, 60, 55, 20.0, 18.0, 4.0, 1, 1)"""
        )
        db.execSQL(
            """INSERT INTO points (tripId, t, lat, lon, speedMps, accuracyM, interpolated)
               VALUES (1, 1000, 12.97, 77.59, 0.0, 5.0, 0)"""
        )
        db.execSQL("INSERT INTO places (lat, lon, visits) VALUES (12.97, 77.59, 3)")

        OdographDb.MIGRATION_2_3.migrate(db)

        db.query("SELECT startedAt, distanceM, startPlaceId FROM trips").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getLong(0)).isEqualTo(1000L)
            assertThat(c.getDouble(1)).isEqualTo(5000.0)
            assertThat(c.getLong(2)).isEqualTo(1L)
        }
        db.query("SELECT lat, lon FROM points").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getDouble(0)).isEqualTo(12.97)
            assertThat(c.getDouble(1)).isEqualTo(77.59)
        }
        db.query("SELECT visits FROM places").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(3)
        }
    }

    @Test
    fun `migrating from v4 adds the elevation columns for battery-context totals`() {
        val db = openV4()
        db.execSQL(
            """INSERT INTO trips (startedAt, endedAt, distanceM, durationS, movingS,
               maxSpeedMps, avgSpeedMps, slowestKmMps)
               VALUES (1000, 2000, 5000.0, 60, 55, 20.0, 18.0, 4.0)"""
        )

        OdographDb.MIGRATION_4_5.migrate(db)

        // Existing drives survive with a honest flat-road default of zero climb.
        db.query("SELECT distanceM, elevGainM, elevLossM FROM trips").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getDouble(0)).isEqualTo(5000.0)
            assertThat(c.getDouble(1)).isEqualTo(0.0)
            assertThat(c.getDouble(2)).isEqualTo(0.0)
        }
        // The new columns accept the values recovery writes.
        db.execSQL("UPDATE trips SET elevGainM = 214.0, elevLossM = 98.0 WHERE startedAt = 1000")
    }

    @Test
    fun `migrating from v2 adds the battery table the telematics poller writes to`() {
        val db = openV2()
        db.execSQL(
            """INSERT INTO trips (startedAt, endedAt, distanceM, durationS, movingS,
               maxSpeedMps, avgSpeedMps, slowestKmMps)
               VALUES (1000, 2000, 5000.0, 60, 55, 20.0, 18.0, 4.0)"""
        )

        OdographDb.MIGRATION_2_3.migrate(db)

        // The battery table accepts a row for a trip and the index it was built with works.
        db.execSQL(
            """INSERT INTO battery (tripId, t, socPercent, charging, rangeKm)
               VALUES (1, 1500, 82.0, 1, 210)"""
        )
        db.query(
            """SELECT tripId, socPercent, charging, rangeKm FROM battery
               WHERE tripId = ? AND t = ?""",
            arrayOf(1L, 1500L)
        ).use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getLong(0)).isEqualTo(1L)
            assertThat(c.getDouble(1)).isEqualTo(82.0)
            assertThat(c.getInt(2)).isEqualTo(1)
            assertThat(c.getDouble(3)).isEqualTo(210.0)
        }
    }
}
