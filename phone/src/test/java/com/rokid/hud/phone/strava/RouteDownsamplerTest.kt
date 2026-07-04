package com.rokid.hud.phone.strava

import com.rokid.hud.shared.protocol.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIMP-03b: equirectangular Douglas-Peucker downsampling with a <=200 raise-epsilon cap.
 *
 * Pure geometry, zero Android coupling. Covers: <3 passthrough, colinear collapse,
 * switchback preservation, endpoints always retained, the raise-epsilon <=200 cap on a
 * dense input, and equirectangular perpendicular-distance correctness vs a hand value.
 */
class RouteDownsamplerTest {

    private val R = 6_371_000.0

    /** Meters-per-degree latitude at the equator (~111,195 m). */
    private val mPerDegLat = Math.toRadians(1.0) * R

    @Test
    fun fewerThanThreePointsReturnedUnchanged() {
        val zero = emptyList<Waypoint>()
        assertEquals(zero, RouteDownsampler.simplify(zero, 15.0))

        val one = listOf(Waypoint(1.0, 2.0))
        assertEquals(one, RouteDownsampler.simplify(one, 15.0))

        val two = listOf(Waypoint(1.0, 2.0), Waypoint(3.0, 4.0))
        assertEquals(two, RouteDownsampler.simplify(two, 15.0))
    }

    @Test
    fun colinearPointsCollapseToEndpoints() {
        // Straight south->north line; all interior points sit exactly on the segment.
        val pts = (0..10).map { Waypoint(37.0 + it * 0.001, -122.0) }
        val out = RouteDownsampler.simplify(pts, 15.0)
        assertEquals(2, out.size)
        assertEquals(pts.first(), out.first())
        assertEquals(pts.last(), out.last())
    }

    @Test
    fun switchbackReversalVertexIsPreserved() {
        // North for ~55m, then a sharp reversal south-west — the apex must survive at eps=15m.
        // 0.0005 deg lat ~= 55.6m; the reversal vertex is far off the start->end chord.
        val apex = Waypoint(37.0005, -122.0)
        val pts = listOf(
            Waypoint(37.0000, -122.0),
            Waypoint(37.00025, -122.0),
            apex,
            Waypoint(37.00025, -122.0005),
            Waypoint(37.0000, -122.0010)
        )
        val out = RouteDownsampler.simplify(pts, 15.0)
        assertTrue("apex reversal vertex must be preserved", out.contains(apex))
        assertEquals(pts.first(), out.first())
        assertEquals(pts.last(), out.last())
    }

    @Test
    fun firstAndLastAlwaysPresent() {
        val pts = (0..50).map { Waypoint(37.0 + it * 0.0002, -122.0 + it * 0.0002) }
        val out = RouteDownsampler.simplify(pts, 15.0)
        assertEquals(pts.first(), out.first())
        assertEquals(pts.last(), out.last())
    }

    @Test
    fun downsampleForRouteCapsAt200OnDenseInput() {
        // ~1000-point sawtooth advancing north ~5m/point, each odd point offset ~30m EAST.
        // A ~30m lateral apex sits well above a 15m epsilon, so every apex survives at 15m
        // (>200 survivors); the raise-epsilon loop must then drive the count down to <=200.
        // longitude degrees per meter at ~37N: cos(37) shrinks the east span, so pre-divide.
        val mPerDegLng = mPerDegLat * Math.cos(Math.toRadians(37.0))
        val northStep = 5.0 / mPerDegLat       // ~5m north per point
        val eastOffset = 30.0 / mPerDegLng     // ~30m east on odd points
        val pts = ArrayList<Waypoint>(1000)
        for (i in 0 until 1000) {
            val lat = 37.0 + i * northStep
            val lng = -122.0 + (if (i % 2 == 0) 0.0 else eastOffset)
            pts.add(Waypoint(lat, lng))
        }
        // Sanity: at 15m this dense sawtooth is NOT already <=200 (otherwise the cap test is vacuous).
        assertTrue(
            "test precondition: 15m simplify should still exceed 200 on this input",
            RouteDownsampler.simplify(pts, 15.0).size > 200
        )
        val capped = RouteDownsampler.downsampleForRoute(pts)
        assertTrue("downsampleForRoute must return <=200 points, got ${capped.size}", capped.size <= 200)
        assertEquals(pts.first(), capped.first())
        assertEquals(pts.last(), capped.last())
    }

    @Test
    fun downsampleForRoutePreservesShortRouteEndpoints() {
        // A route already under the cap passes through with endpoints intact.
        val pts = listOf(
            Waypoint(37.0, -122.0),
            Waypoint(37.0005, -122.0001),
            Waypoint(37.0010, -122.0)
        )
        val out = RouteDownsampler.downsampleForRoute(pts)
        assertTrue(out.size <= 200)
        assertEquals(pts.first(), out.first())
        assertEquals(pts.last(), out.last())
    }

    @Test
    fun equirectangularPerpendicularDistanceMatchesHandValue() {
        // Right triangle near the equator: segment a->b runs due east along lat 0.
        // Point p sits due north of a by 0.001 deg (~111.195 m). Perpendicular distance
        // from p to the east-west segment is that north offset. simplify at an epsilon
        // JUST BELOW 111m must keep p (maxD > eps); JUST ABOVE must drop it.
        val a = Waypoint(0.0, 0.0)
        val p = Waypoint(0.001, 0.0005)   // north of the segment, halfway along in x
        val b = Waypoint(0.0, 0.001)
        val expectedPerp = 0.001 * mPerDegLat   // ~111.195 m
        assertEquals("hand-computed perpendicular ~= 111.2m", 111.195, expectedPerp, 0.5)

        // eps below the perpendicular -> p is kept (3 points out)
        val kept = RouteDownsampler.simplify(listOf(a, p, b), expectedPerp - 1.0)
        assertEquals(3, kept.size)
        assertTrue(kept.contains(p))

        // eps above the perpendicular -> p is dropped (endpoints only)
        val dropped = RouteDownsampler.simplify(listOf(a, p, b), expectedPerp + 1.0)
        assertEquals(2, dropped.size)
    }

    @Test
    fun longHighLatitudeSegmentUsesMidpointLongitudeScale() {
        // WR-03: on a LONG high-latitude segment the longitude scale must use the segment
        // MIDPOINT latitude, not the start latitude. Segment a->b runs ~111km north along the
        // meridian lng=10 at 60N; point p sits at the midpoint latitude offset ~0.02deg east.
        // The east ground offset is toRadians(dLng) * cos(latitude) * R, and cos differs
        // materially between the start (cos60=0.5) and the midpoint (cos60.5=0.49242):
        //   - buggy single-cos(startLat) scaling -> perpM ~= 1111.95 m
        //   - correct midpoint-cos scaling        -> perpM ~= 1095.10 m
        // We assert the CORRECT (midpoint) magnitude via the DP keep/drop boundary.
        val a = Waypoint(60.0, 10.0)
        val p = Waypoint(60.5, 10.02)
        val b = Waypoint(61.0, 10.0)

        val cosMid = Math.cos(Math.toRadians(60.5))
        val expectedPerpMidpoint = Math.toRadians(0.02) * cosMid * R  // ~1095.1 m
        assertEquals("midpoint-scaled perpendicular ~= 1095m", 1095.1, expectedPerpMidpoint, 2.0)

        // eps just BELOW the midpoint-scaled distance -> p kept (3 out).
        val kept = RouteDownsampler.simplify(listOf(a, p, b), expectedPerpMidpoint - 5.0)
        assertEquals(3, kept.size)
        assertTrue(kept.contains(p))

        // eps just ABOVE the midpoint-scaled distance (but BELOW the buggy start-lat value of
        // ~1112m) -> p dropped. The old single-cos(startLat) projection over-measured perpM and
        // would have KEPT p here, so this boundary specifically exercises the fix.
        val dropped = RouteDownsampler.simplify(listOf(a, p, b), expectedPerpMidpoint + 5.0)
        assertEquals(2, dropped.size)
    }
}
