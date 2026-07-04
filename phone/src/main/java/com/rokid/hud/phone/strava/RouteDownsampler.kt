package com.rokid.hud.phone.strava

import com.rokid.hud.shared.protocol.Waypoint

/**
 * Pure equirectangular Douglas-Peucker route simplification. Points in -> subset out.
 * Zero Android / network coupling (JVM-unit-testable).
 *
 * Why equirectangular (not haversine cross-track): at city scale the flat-projection
 * point-to-segment distance differs from haversine by ~0.0009% (sub-millimeter on a
 * 400m segment) — far under a 15m epsilon — and it is faster (one cos(lat0) per
 * segment, no per-point trig). 04-RESEARCH Pattern 4.
 *
 * Why iterative (explicit ArrayDeque, not recursion): Strava tracks can be 10k+
 * points; deep recursion risks a stack overflow.
 *
 * The <=200 output cap is a hard ceiling for the OSRM GET URL (well under its ~500
 * limit) and for navigation sanity. It is enforced by RAISING epsilon and re-running
 * DP (which keeps the shape proportional and preserves switchbacks), NOT by a naive
 * every-Nth stride (which destroys switchback fidelity). 04-RESEARCH Open Q1 / A4.
 */
object RouteDownsampler {

    private const val R = 6_371_000.0

    /** Perpendicular distance (meters) from p to segment a->b via local equirectangular projection. */
    private fun perpM(
        pLat: Double, pLng: Double,
        aLat: Double, aLng: Double,
        bLat: Double, bLng: Double
    ): Double {
        // WR-03: scale longitude deltas by cos at the segment MIDPOINT latitude, not a single
        // fixed origin cos(aLat). The early DP passes bridge the whole track (lo=0, hi=size-1),
        // so a segment can span tens of km of latitude; a single cos(aLat) warps the far end,
        // under-measuring perpendicular distance and letting DP prematurely drop a switchback.
        // The midpoint latitude makes the linearization error symmetric across the segment.
        // Kept pure/JVM (Math.cos, Math.toRadians); `a` remains the projection origin.
        val lat0 = Math.toRadians((aLat + bLat) / 2.0)
        val cos0 = Math.cos(lat0)
        fun x(lng: Double) = Math.toRadians(lng - aLng) * cos0 * R
        fun y(lat: Double) = Math.toRadians(lat - aLat) * R
        val px = x(pLng); val py = y(pLat)
        val bx = x(bLng); val by = y(bLat)
        val seg2 = bx * bx + by * by
        if (seg2 == 0.0) return Math.hypot(px, py)
        val t = ((px * bx + py * by) / seg2).coerceIn(0.0, 1.0)
        return Math.hypot(px - t * bx, py - t * by)
    }

    /**
     * Iterative Ramer-Douglas-Peucker. [epsilonM] in meters. Returns a subset of the
     * input (endpoints always kept). Inputs of < 3 points are returned unchanged.
     * Public so direct-epsilon behavior can be unit-tested.
     */
    fun simplify(points: List<Waypoint>, epsilonM: Double): List<Waypoint> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (lo, hi) = stack.removeLast()
            if (hi - lo < 2) continue
            var idx = -1
            var maxD = -1.0
            for (i in lo + 1 until hi) {
                val d = perpM(
                    points[i].latitude, points[i].longitude,
                    points[lo].latitude, points[lo].longitude,
                    points[hi].latitude, points[hi].longitude
                )
                if (d > maxD) { maxD = d; idx = i }
            }
            if (maxD > epsilonM) {
                keep[idx] = true
                stack.addLast(lo to idx)
                stack.addLast(idx to hi)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /**
     * The caller-facing entry point (MainActivity / route importer). Runs [simplify] at
     * [startEpsilonM]; while the result exceeds [maxPoints], multiplies epsilon by 1.5
     * and re-runs (raise-epsilon loop — shape-preserving, unlike an every-Nth stride).
     * Iteration is capped to avoid an infinite loop; the last result is returned even if
     * still marginally over the cap.
     */
    fun downsampleForRoute(
        points: List<Waypoint>,
        startEpsilonM: Double = 15.0,
        maxPoints: Int = 200
    ): List<Waypoint> {
        var epsilon = startEpsilonM
        var result = simplify(points, epsilon)
        var iterations = 0
        while (result.size > maxPoints && iterations < 40) {
            epsilon *= 1.5
            result = simplify(points, epsilon)
            iterations++
        }
        return result
    }
}
