package `in`.odograph.tracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TripEntity::class, PointEntity::class, PlaceEntity::class, BatteryEntity::class, ChargeEventEntity::class, DailyTelemetryEntity::class],
    version = 4,
    exportSchema = true
)
abstract class OdographDb : RoomDatabase() {

    abstract fun dao(): OdographDao

    companion object {

        /** Adds places, and the two columns linking a trip to where it began and ended. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `places` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `lat` REAL NOT NULL,
                        `lon` REAL NOT NULL,
                        `visits` INTEGER NOT NULL,
                        `label` TEXT,
                        `autoName` TEXT,
                        `geocodedAt` INTEGER)"""
                )
                db.execSQL("ALTER TABLE `trips` ADD COLUMN `startPlaceId` INTEGER")
                db.execSQL("ALTER TABLE `trips` ADD COLUMN `endPlaceId` INTEGER")
            }
        }

        /** Adds the per-trip battery snapshot table the iSMART poller writes to. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `battery` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `tripId` INTEGER NOT NULL,
                        `t` INTEGER NOT NULL,
                        `socPercent` REAL,
                        `charging` INTEGER,
                        `rangeKm` REAL,
                        `chargingPowerKw` REAL,
                        `workingVoltage` REAL,
                        `workingCurrent` REAL)"""
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_battery_tripId_t` ON `battery` (`tripId`, `t`)"
                )
            }
        }

        /**
         * Battery intelligence: trip cost, charge sessions (which survive their trip being
         * culled), and per-day capture windows.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `trips` ADD COLUMN `costInr` REAL")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `charge_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `startTime` INTEGER NOT NULL,
                        `startSoc` REAL,
                        `endTime` INTEGER,
                        `endSoc` REAL,
                        `energyKwh` REAL NOT NULL,
                        `peakPowerKw` REAL,
                        `kind` INTEGER,
                        `costInr` REAL)"""
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_charge_events_startTime` ON `charge_events` (`startTime`)"
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `daily_telemetry` (
                        `day` INTEGER NOT NULL PRIMARY KEY,
                        `firstPollAt` INTEGER NOT NULL,
                        `lastPollAt` INTEGER NOT NULL)"""
                )
            }
        }

        private const val NAME = "odograph.db"

        @Volatile
        private var instance: OdographDb? = null

        /**
         * Drops the singleton and the file behind it. Tests share one JVM, so without this a
         * previous test's rows leak into the next one and assertions quietly become meaningless.
         */
        @androidx.annotation.VisibleForTesting
        fun resetForTests(ctx: Context) = synchronized(this) {
            instance?.close()
            instance = null
            ctx.applicationContext.deleteDatabase(NAME)
        }

        fun get(ctx: Context): OdographDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                ctx.applicationContext, OdographDb::class.java, NAME
            )
                // The car cuts power without warning. Write-ahead logging means a torn write
                // costs one in-flight row, never the database.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { instance = it }
        }
    }
}
