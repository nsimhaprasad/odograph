package `in`.odograph.tracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TripEntity::class, PointEntity::class, PlaceEntity::class],
    version = 2,
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
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
