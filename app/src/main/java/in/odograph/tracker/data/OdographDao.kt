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

    @Query("SELECT * FROM trips WHERE endedAt IS NOT NULL AND syncedAt IS NULL ORDER BY startedAt")
    fun unsyncedTrips(): List<TripEntity>

    @Query("UPDATE trips SET syncedAt = :at WHERE id = :id")
    fun markSynced(id: Long, at: Long)

    @Query("DELETE FROM trips WHERE id = :id")
    fun deleteTrip(id: Long)

    // ---- places ----

    @Insert
    fun insertPlace(place: PlaceEntity): Long

    @Query("SELECT * FROM places")
    fun allPlaces(): List<PlaceEntity>

    @Query("SELECT * FROM places WHERE id = :id")
    fun placeById(id: Long): PlaceEntity?

    @Query("SELECT * FROM places WHERE autoName IS NULL AND label IS NULL")
    fun placesNeedingNames(): List<PlaceEntity>

    @Query("UPDATE places SET lat = :lat, lon = :lon, visits = :visits WHERE id = :id")
    fun updatePlacePosition(id: Long, lat: Double, lon: Double, visits: Int)

    @Query("UPDATE places SET label = :label WHERE id = :id")
    fun setPlaceLabel(id: Long, label: String?)

    @Query("UPDATE places SET autoName = :autoName, geocodedAt = :at WHERE id = :id")
    fun setPlaceAutoName(id: Long, autoName: String?, at: Long)

    @Query("UPDATE trips SET startPlaceId = :startId, endPlaceId = :endId WHERE id = :id")
    fun setTripPlaces(id: Long, startId: Long?, endId: Long?)

    /** Most repeated origin-to-destination pairs. Direction matters: the return leg is its own route. */
    @Query(
        """SELECT startPlaceId AS startId, endPlaceId AS endId, COUNT(*) AS drives,
                  AVG(distanceM) AS avgDistanceM, AVG(durationS) AS avgDurationS,
                  AVG(movingS) AS avgMovingS, MAX(maxSpeedMps) AS bestMaxSpeedMps,
                  SUM(distanceM) AS totalDistanceM
           FROM trips
           WHERE endedAt IS NOT NULL AND startPlaceId IS NOT NULL AND endPlaceId IS NOT NULL
           GROUP BY startPlaceId, endPlaceId
           ORDER BY drives DESC, totalDistanceM DESC"""
    )
    fun routeSummaries(): List<RouteSummary>
}

data class RouteSummary(
    val startId: Long,
    val endId: Long,
    val drives: Int,
    val avgDistanceM: Double,
    val avgDurationS: Double,
    val avgMovingS: Double,
    val bestMaxSpeedMps: Float,
    val totalDistanceM: Double
)
