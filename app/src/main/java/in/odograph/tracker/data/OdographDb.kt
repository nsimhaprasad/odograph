package `in`.odograph.tracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

@Database(
    entities = [TripEntity::class, PointEntity::class, PlaceEntity::class, BatteryEntity::class, ChargeEventEntity::class, DailyTelemetryEntity::class, PriceReminderEntity::class],
    version = 8,
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

        /** Per-trip climb and descent, the elevation context battery consumption depends on. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `trips` ADD COLUMN `elevGainM` REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `trips` ADD COLUMN `elevLossM` REAL NOT NULL DEFAULT 0")
            }
        }

        /**
         * Consistent fast-charge detection and driver-entered session costs: charge sessions now
         * keep the power-reading evidence that decides fast vs slow, and whatever the driver
         * entered to price the session (a tariff + GST or a total bill).
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `charge_events` ADD COLUMN `samplesTotal` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `charge_events` ADD COLUMN `samplesAbove` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `charge_events` ADD COLUMN `enteredRateInr` REAL")
                db.execSQL("ALTER TABLE `charge_events` ADD COLUMN `enteredBillInr` REAL")
                db.execSQL("ALTER TABLE `charge_events` ADD COLUMN `gstRatePct` REAL")
            }
        }

        /**
         * Persistent fast-charge price reminders and richer MG charge capture into battery rows.
         * The reminder survives whatever the app did with its live prompt: it is the durable "you
         * still owe this answer" record that surfaces at the next drive until APPLY or IGNORE.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `price_reminders` (
                        `eventId` INTEGER NOT NULL PRIMARY KEY,
                        `raisedAt` INTEGER NOT NULL,
                        `ignoredAt` INTEGER)"""
                )
                db.execSQL("ALTER TABLE `battery` ADD COLUMN `odometerKm` REAL")
                db.execSQL("ALTER TABLE `battery` ADD COLUMN `batteryEnergyKwh` REAL")
                db.execSQL("ALTER TABLE `battery` ADD COLUMN `chargeTimeRemainingMin` INTEGER")
                db.execSQL("ALTER TABLE `battery` ADD COLUMN `distanceSinceLastChargeKm` REAL")
                db.execSQL("ALTER TABLE `battery` ADD COLUMN `powerUsageSinceLastChargeKwh` REAL")
            }
        }

        /**
         * Wall-meter kWh, charge location, and historical misclassification fix: the power
         * calculation bug (v0.1.0) double-scaled already-decoded values, marking every 30 kW
         * public session as SLOW. Historical sessions are reclassified from their stored
         * energyKwh and time window (average power ≥ 10 kW → FAST), which is the closest we
         * can get — the raw-frame log only keeps the last 24 captures and per-frame charging
         * power was also corrupted.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `charge_events` ADD COLUMN `deliveredKwh` REAL")
                db.execSQL("ALTER TABLE `charge_events` ADD COLUMN `placeId` INTEGER")
                db.execSQL("ALTER TABLE `charge_events` ADD COLUMN `lat` REAL")
                db.execSQL("ALTER TABLE `charge_events` ADD COLUMN `lon` REAL")
                // Historical reclassification: sessions that have a valid time window and energy
                // are re-evaluated from their average power alone (ignoring the corrupt per-frame
                // samplesTotal/samplesAbove that the power bug miscalculated).
                db.execSQL(
                    """UPDATE charge_events
                       SET kind = CASE WHEN energyKwh * 3600000.0 / ((endTime - startTime) + 1) >= 10
                                       THEN 1 ELSE 0 END
                       WHERE kind IS NOT NULL AND endTime IS NOT NULL
                         AND endTime > startTime AND energyKwh > 0"""
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .build()
                .also { instance = it }
        }

        /**
         * WAL-checkpointed copy of the live database. Call on IO; do not use while the service
         * writes. TRUNCATE folds the WAL into the main file so the copy is a single .db.
         */
        fun snapshotTo(ctx: Context, target: File) {
            val db = get(ctx).openHelper.writableDatabase
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
            val src = ctx.getDatabasePath(NAME)
            src.copyTo(target, overwrite = true)
        }

        /**
         * Swaps the live database for a backup file. The original is kept as .prev for debugging.
         * Must be called while [in.odograph.tracker.record.TripRecorderService] is paused.
         * The swap is validated by reopening the database, so a corrupt upload fails here rather
         * than poisoning the next read.
         */
        fun replaceWith(ctx: Context, backup: File) {
            val bytes = backup.readBytes()
            require(bytes.size >= 16) { "not a SQLite database: ${backup.name}" }
            require(String(bytes, 0, 15, Charsets.US_ASCII) == "SQLite format 3") {
                "not a SQLite database: ${backup.name}"
            }
            val dbFile = ctx.getDatabasePath(NAME)
            val prev = File(dbFile.parentFile, NAME + ".prev")
            synchronized(this) {
                instance?.close()
                instance = null
                if (dbFile.exists()) dbFile.copyTo(prev, overwrite = true)
                backup.copyTo(dbFile, overwrite = true)
                File(dbFile.parentFile, NAME + "-wal").delete()
                File(dbFile.parentFile, NAME + "-shm").delete()
                val opened = get(ctx)
                opened
            }
        }
    }
}
