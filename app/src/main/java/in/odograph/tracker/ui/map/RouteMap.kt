package `in`.odograph.tracker.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import `in`.odograph.tracker.ui.normaliseRoute
import `in`.odograph.tracker.ui.theme.Palette
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

/**
 * Tiles are an enhancement layer, never a dependency. With a hotspot up they download and cache;
 * cached tiles are then permanent, because roads do not move. With an empty cache and no network
 * the map is blank and [BareRouteTrace] still draws the shape of the drive.
 *
 * osmdroid rather than the Google Maps SDK: Play Services presence on the box is unverified.
 */
@Composable
fun RouteMap(
    points: List<Pair<Double, Double>>,
    palette: Palette,
    modifier: Modifier = Modifier
) {
    AndroidView(
        // osmdroid's MapView has a habit of drawing past the panel it sits in unless the host
        // clips it. Clip explicitly so the route map can never bleed over the trip list, the
        // period chips, or anything else sharing the screen.
        modifier = modifier.clipToBounds(),
        factory = { ctx ->
            configuredMap(ctx)
        },
        update = { map ->
            map.overlays.clear()
            map.setBackgroundColor(palette.ground.toArgb())
            if (points.size >= 2) {
                map.overlays.add(
                    Polyline(map).apply {
                        setPoints(points.map { GeoPoint(it.first, it.second) })
                        outlinePaint.color = palette.accent.toArgb()
                        outlinePaint.strokeWidth = 9f
                        outlinePaint.isAntiAlias = true
                    }
                )
                MapBounds.of(points)?.let { raw ->
                    val b = MapBounds.padded(raw)
                    map.post {
                        map.zoomToBoundingBox(
                            BoundingBox(b.maxLat, b.maxLon, b.minLat, b.minLon), false
                        )
                    }
                }
            }
            map.invalidate()
        }
    )
}

/**
 * The pin the PLACES search drops on the tile map. A bare MapView with one marker, re-centreable
 * every time the selection changes, so a driver only needs to recognise a location she typed.
 */
@Composable
fun PlacePin(
    pin: Pair<Double, Double>?,
    palette: Palette,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = { ctx -> configuredMap(ctx) },
        update = { map ->
            map.overlays.clear()
            map.setBackgroundColor(palette.ground.toArgb())
            if (pin != null) {
                val point = GeoPoint(pin.first, pin.second)
                map.overlays.add(org.osmdroid.views.overlay.Marker(map).apply {
                    position = point
                    setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER,
                        org.osmdroid.views.overlay.Marker.ANCHOR_CENTER)
                    icon = map.context.getDrawable(android.R.drawable.ic_menu_mylocation)
                })
                map.post {
                    map.controller.setZoom(17.0)
                    map.controller.setCenter(point)
                    map.invalidate()
                }
            }
            map.invalidate()
        }
    )
}

/** One shared osmdroid pump, so every map surface behaves and is identified identically. */
private fun configuredMap(ctx: android.content.Context) = org.osmdroid.views.MapView(ctx).apply {
    org.osmdroid.config.Configuration.getInstance().apply {
        // OSM's tile policy requires an identifying agent. Personal use, low volume.
        userAgentValue = "Odograph/0.1 (personal drive tracker)"
        osmdroidBasePath = ctx.getExternalFilesDir("osmdroid")
        osmdroidTileCache = ctx.getExternalFilesDir("osmdroid/tiles")
    }
    setTileSource(TileSourceFactory.MAPNIK)
    // The projection link returns single touches only, so pinch zoom is unusable.
    setMultiTouchControls(false)
    setUseDataConnection(true)
    zoomController.setVisibility(
        org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
    )
}

/** The always-available fallback: the drive's shape, drawn from our own points, no network. */
@Composable
fun BareRouteTrace(
    points: List<Pair<Double, Double>>,
    palette: Palette,
    modifier: Modifier = Modifier
) {
    val norm = normaliseRoute(points)
    Canvas(modifier) {
        drawRect(palette.ground, size = size)
        if (norm.size < 2) return@Canvas
        val inset = size.minDimension * 0.08f
        val w = size.width - inset * 2
        val h = size.height - inset * 2
        val path = Path()
        norm.forEachIndexed { i, (x, y) ->
            val px = inset + x * w
            val py = inset + y * h
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        if (palette.glow > 0f) {
            drawPath(path, palette.accent.copy(alpha = 0.25f),
                style = Stroke(width = size.minDimension * 0.022f))
        }
        drawPath(path, palette.accent, style = Stroke(width = size.minDimension * 0.008f))
        val startPt = norm.first()
        val endPt = norm.last()
        drawCircle(palette.dim, size.minDimension * 0.010f,
            Offset(inset + startPt.first * w, inset + startPt.second * h))
        drawCircle(palette.accent2, size.minDimension * 0.014f,
            Offset(inset + endPt.first * w, inset + endPt.second * h))
    }
}
