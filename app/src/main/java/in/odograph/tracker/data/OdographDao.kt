package `in`.odograph.tracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface OdographDao {

    @Insert
    fun insertTrip(trip: TripEntity): Long

    fun startTrip(startedAt: Long): Long = insertTrip(TripEntity(startedAt = startedAt))

    @Insert
    fun appendPoint(point: PointEntity): Long

    @Query("SELECT * FROM trips WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun openTrip(): TripEntity?

    @Query("SELECT * FROM trips ORDER BY startedAt DESC")
    fun allTrips(): List<TripEntity>

    @Query("SELECT * FROM trips WHERE id = :id")
    fun tripById(id: Long): TripEntity?

    @Query("SELECT * FROM points WHERE tripId = :tripId ORDER BY t ASC")
    fun pointsFor(tripId: Long): List<PointEntity>

    @Query("SELECT * FROM points WHERE tripId = :tripId ORDER BY t DESC LIMIT 1")
    fun lastPointOf(tripId: Long): PointEntity?

    @Query("SELECT COUNT(*) FROM points WHERE tripId = :tripId")
    fun pointCount(tripId: Long): Int

    @Query(
        """UPDATE trips SET endedAt = :endedAt, distanceM = :distanceM,
           durationS = :durationS, movingS = :movingS, maxSpeedMps = :maxSpeedMps,
           avgSpeedMps = :avgSpeedMps, slowestKmMps = :slowestKmMps WHERE id = :id"""
    )
    fun finishTrip(
        id: Long, endedAt: Long, distanceM: Double, durationS: Long,
        movingS: Long, maxSpeedMps: Float, avgSpeedMps: Double, slowestKmMps: Double
    )

    @Query("UPDATE trips SET startLat = :lat, startLon = :lon WHERE id = :id")
    fun setOrigin(id: Long, lat: Double, lon: Double)

    @Query("UPDATE trips SET endLat = :lat, endLon = :lon WHERE id = :id")
    fun setDestination(id: Long, lat: Double, lon: Double)

    @Query("UPDATE trips SET startedAt = :startedAt WHERE id = :id")
    fun setStartedAt(id: Long, startedAt: Long)

    @Query("UPDATE trips SET clusterId = :clusterId WHERE id = :id")
    fun setCluster(id: Long, clusterId: Long)

    @Query("DELETE FROM trips WHERE id = :id")
    fun deleteTrip(id: Long)
}
