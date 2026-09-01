package `in`.odograph.tracker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val distanceM: Double = 0.0,
    val durationS: Long = 0,
    val movingS: Long = 0,
    val maxSpeedMps: Float = 0f,
    val avgSpeedMps: Double = 0.0,
    val slowestKmMps: Double = 0.0,
    val startLat: Double? = null,
    val startLon: Double? = null,
    val endLat: Double? = null,
    val endLon: Double? = null,
    val clusterId: Long? = null,
    /** When this trip was accepted by the optional webhook. Null means never sent. */
    val syncedAt: Long? = null,
    // Reserved for v2 (OBD-II). Always null in v1 — a nullable column costs nothing today
    // and saves a migration later.
    val socStart: Double? = null,
    val socEnd: Double? = null,
    val energyKwh: Double? = null
)

@Entity(tableName = "points", indices = [Index(value = ["tripId", "t"])])
data class PointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val t: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Float,
    val bearingDeg: Float?,
    val altitudeM: Double?,
    val accuracyM: Float,
    val interpolated: Boolean
)
