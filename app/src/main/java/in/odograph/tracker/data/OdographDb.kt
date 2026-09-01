package `in`.odograph.tracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TripEntity::class, PointEntity::class], version = 1, exportSchema = false)
abstract class OdographDb : RoomDatabase() {

    abstract fun dao(): OdographDao

    companion object {
        @Volatile
        private var instance: OdographDb? = null

        fun get(ctx: Context): OdographDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                ctx.applicationContext, OdographDb::class.java, "odograph.db"
            )
                // The car cuts power without warning. Write-ahead logging means a torn write
                // costs one in-flight row, never the database.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
                .also { instance = it }
        }
    }
}
