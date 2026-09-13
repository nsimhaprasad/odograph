package `in`.odograph.tracker.ui

import `in`.odograph.tracker.core.Fix
import `in`.odograph.tracker.core.Geo

/** Projects lat/lon into a 0..1 box, preserving aspect so the route is not stretched. */
fun normaliseRoute(points: List<Pair<Double, Double>>): List<Pair<Float, Float>> {
    if (points.isEmpty()) return emptyList()
    if (points.size == 1) return listOf(0.5f to 0.5f)

    val minLat = points.minOf { it.first }
    val maxLat = points.maxOf { it.first }
    val minLon = points.minOf { it.second }
    val maxLon = points.maxOf { it.second }
    val span = maxOf(maxLat - minLat, maxLon - minLon).takeIf { it > 1e-9 }
        ?: return points.map { 0.5f to 0.5f }

    return points.map { (lat, lon) ->
        val x = ((lon - minLon) / span).toFloat().coerceIn(0f, 1f)
        val y = (1.0 - (lat - minLat) / span).toFloat().coerceIn(0f, 1f)
        x to y
    }
}

/**
 * Drops the signature of a GPS teleport: a fix that jumps far from the previous one and then
 * jumps straight back. Real cars do not teleport, so the stray point is removed and the line
 * bridges across it. A single leg faster than any car (plus a big displacement) is treated the
 * same way.
 */
fun filterOutlierFixes(fixes: List<Fix>): List<Fix> {
    if (fixes.size < 3) return fixes
    val sorted = fixes.sortedBy { it.t }
    val out = ArrayList<Fix>(sorted.size)
    out += sorted.first()
    for (i in 1 until sorted.lastIndex) {
        val prev = sorted[i - 1]
        val cur = sorted[i]
        val next = sorted[i + 1]
        val a = Geo.haversineMetres(prev.lat, prev.lon, cur.lat, cur.lon)
        val b = Geo.haversineMetres(cur.lat, cur.lon, next.lat, next.lon)
        val c = Geo.haversineMetres(prev.lat, prev.lon, next.lat, next.lon)

        val backtracks = c > 0 && a >= MIN_JUMP_M && b >= MIN_JUMP_M &&
            (a + b) > SPIKE_BACKTRACK * c
        val dtS = maxOf(1, (cur.t - prev.t) / 1000)
        val teleport = a / dtS > MAX_PLAUSIBLE_MPS && a >= MIN_JUMP_M
        if (backtracks || teleport) continue
        out += cur
    }
    out += sorted.last()
    return out
}

/** One pass of Chaikin corner cutting: each edge is pulled to 1/4 and 3/4 along itself, easing
 *  the GPS zigzag into a smooth line while staying inside the original points' track. */
fun smoothRoute(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
    if (points.size < 3) return points
    val out = ArrayList<Pair<Double, Double>>(points.size * 2 + 1)
    out += points.first()
    for (i in 0 until points.size - 1) {
        val (lat0, lon0) = points[i]
        val (lat1, lon1) = points[i + 1]
        out += (0.75 * lat0 + 0.25 * lat1) to (0.75 * lon0 + 0.25 * lon1)
        out += (0.25 * lat0 + 0.75 * lat1) to (0.25 * lon0 + 0.75 * lon1)
    }
    out += points.last()
    return out
}

/** The pipeline both maps and traces use: outliers out, smooth line in. */
fun buildSmoothRoute(fixes: List<Fix>): List<Pair<Double, Double>> {
    val clean = filterOutlierFixes(fixes)
    return smoothRoute(clean.map { it.lat to it.lon })
}

private const val MIN_JUMP_M = 30.0
private const val SPIKE_BACKTRACK = 2.5
private const val MAX_PLAUSIBLE_MPS = 60.0