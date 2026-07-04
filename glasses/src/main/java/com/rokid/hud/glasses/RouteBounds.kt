package com.rokid.hud.glasses

import com.rokid.hud.shared.protocol.Waypoint
import kotlin.math.cos
import kotlin.math.min

/**
 * Pure fit-to-bounds projection for the WHOLE_ROUTE birdview (D1).
 *
 * Android-free (no android.graphics.*) so plain-JVM tests cover it — same discipline as
 * [SportFormat]. Uses an equirectangular projection adequate for a city-scale route: longitude is
 * projected directly and latitude is scaled by cos(midLat) so the route shape is not distorted.
 * One uniform scale is chosen (aspect-preserving) and the route bounding box is centered in the
 * view. North is up: larger longitude -> larger x, larger latitude -> SMALLER y (screen y grows
 * downward).
 *
 * Degenerate bounding boxes (a single point, or a purely vertical/horizontal line) are guarded so
 * the projector never divides by zero — the degenerate axis is simply centered.
 */
object RouteBounds {

    /** Screen coordinate result — a tiny android-free pair (avoids android.graphics.PointF). */
    data class ScreenXY(val x: Float, val y: Float)

    /** Projects a route waypoint to view-space. Immutable + stateless once produced by [fit]. */
    class Projector internal constructor(
        private val midLatCos: Double,
        private val minPx: Double,
        private val minPy: Double,
        private val scale: Double,
        private val offsetX: Double,
        private val offsetY: Double,
        private val viewH: Float
    ) {
        fun project(lat: Double, lng: Double): ScreenXY {
            val px = lng * midLatCos
            val py = lat
            val x = offsetX + (px - minPx) * scale
            // Invert y so north (larger lat) maps to a smaller screen y.
            val yFromTop = offsetY + (py - minPy) * scale
            val y = viewH - yFromTop
            return ScreenXY(x.toFloat(), y.toFloat())
        }
    }

    /**
     * Builds a [Projector] that fits [points] inside [padding, viewW-padding] x
     * [padding, viewH-padding], aspect-preserving. Returns null for empty input (the caller draws
     * the empty-state hint instead of crashing).
     */
    fun fit(points: List<Waypoint>, viewW: Float, viewH: Float, padding: Float): Projector? {
        if (points.isEmpty()) return null

        val midLat = (points.minOf { it.latitude } + points.maxOf { it.latitude }) / 2.0
        val midLatCos = cos(Math.toRadians(midLat))

        var minPx = Double.MAX_VALUE
        var maxPx = -Double.MAX_VALUE
        var minPy = Double.MAX_VALUE
        var maxPy = -Double.MAX_VALUE
        for (wp in points) {
            val px = wp.longitude * midLatCos
            val py = wp.latitude
            if (px < minPx) minPx = px
            if (px > maxPx) maxPx = px
            if (py < minPy) minPy = py
            if (py > maxPy) maxPy = py
        }

        val availW = (viewW - 2 * padding).toDouble()
        val availH = (viewH - 2 * padding).toDouble()
        val spanX = maxPx - minPx
        val spanY = maxPy - minPy

        // Uniform scale from whichever axis is the tighter fit; a zero span on an axis contributes
        // no constraint (it is centered), so guard each divisor to avoid divide-by-zero.
        val scaleX = if (spanX > 0.0) availW / spanX else Double.MAX_VALUE
        val scaleY = if (spanY > 0.0) availH / spanY else Double.MAX_VALUE
        val scale = when {
            scaleX == Double.MAX_VALUE && scaleY == Double.MAX_VALUE -> 1.0 // single point: any finite scale; it centers
            else -> min(scaleX, scaleY)
        }

        // Center the (possibly degenerate) bbox within the padded area.
        val offsetX = padding + (availW - spanX * scale) / 2.0
        val offsetY = padding + (availH - spanY * scale) / 2.0

        return Projector(midLatCos, minPx, minPy, scale, offsetX, offsetY, viewH)
    }
}
