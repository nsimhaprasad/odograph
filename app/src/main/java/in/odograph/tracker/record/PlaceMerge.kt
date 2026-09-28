package `in`.odograph.tracker.record

import `in`.odograph.tracker.core.Geo
import `in`.odograph.tracker.data.OdographDao

/**
 * Folding auto-clusters into the named place that swallows them.
 *
 * A real neighbourhood produces a dozen near-identical 150 m auto-places — every spot the car
 * happened to stop within a street of the last. The moment one of them is given a name, the rest
 * are noise: this pulls every *unnamed* place within [radiusM] into the named one, re-points the
 * trips and charges that referenced them, and deletes the emptied fragments. Named places are
 * never absorbed — only the driver retires a name, by clearing it.
 *
 * Idempotent: run it again and there is nothing left within the radius to fold, so it does
 * nothing. That is what lets it run every time a label arrives, from the app or the sheet.
 */
object PlaceMerge {

    /** Absorbs unnamed neighbours of [placeId] within [radiusM]. Returns how many were folded in. */
    fun absorbNearby(dao: OdographDao, placeId: Long, radiusM: Double): Int {
        val target = dao.placeById(placeId) ?: return 0
        if (target.label == null) return 0
        var folded = 0
        var addedVisits = 0
        for (p in dao.allPlaces()) {
            if (p.id == placeId || p.label != null) continue
            if (Geo.haversineMetres(target.lat, target.lon, p.lat, p.lon) > radiusM) continue
            dao.repointTripStarts(p.id, placeId)
            dao.repointTripEnds(p.id, placeId)
            dao.repointChargePlaces(p.id, placeId)
            addedVisits += p.visits.coerceAtLeast(0)
            dao.deletePlace(p.id)
            folded++
        }
        if (folded > 0) {
            dao.updatePlacePosition(placeId, target.lat, target.lon, target.visits + addedVisits)
        }
        return folded
    }
}
