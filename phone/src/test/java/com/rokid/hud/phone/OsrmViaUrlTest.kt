package com.rokid.hud.phone

import com.rokid.hud.shared.protocol.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NAVV-02 seams: the verified multi-waypoint OSRM via-point mechanics, extracted as
 * PURE functions so they unit-test with zero network (same discipline as Phase-3's
 * pure StravaOAuth.buildAuthorizeUrl). The only untestable line in getRouteVia is the
 * HTTP execute()/read.
 *
 * Covers (04-RESEARCH):
 *   - NAVV-02a: buildViaUrl → waypoints=0;{last}, lng,lat coordinate order, exact params
 *   - NAVV-02b: filterArriveSteps → drops non-final zero-distance arrive, keeps the final one
 *   - NAVV-02c: buildFollowRouteResult → exactly one non-empty synthetic "Follow route" step
 *
 * Pure JVM — no android.* anywhere. Mirrors StravaAuthUrlTest / ActivitySessionManagerTest:
 * JUnit4, exact-value assertEquals, hand-built input lists.
 */
class OsrmViaUrlTest {

    // ------------------------------------------------------------------
    // buildViaUrl — the verified waypoints=0;{last} single-leg URL (NAVV-02a)
    // ------------------------------------------------------------------

    @Test
    fun buildViaUrlFourPointsHasWaypointsZeroToThree() {
        val url = OsrmClient.buildViaUrl(
            listOf(
                Waypoint(latitude = 1.0, longitude = 2.0),
                Waypoint(latitude = 3.0, longitude = 4.0),
                Waypoint(latitude = 5.0, longitude = 6.0),
                Waypoint(latitude = 7.0, longitude = 8.0)
            )
        )
        // lastIndex = size - 1 = 3 → the load-bearing single-leg param.
        assertTrue("URL must contain the exact waypoints=0;3 param, got: $url", url.contains("waypoints=0;3"))
    }

    @Test
    fun buildViaUrlTwoPointsHasWaypointsZeroToOne() {
        val url = OsrmClient.buildViaUrl(
            listOf(
                Waypoint(latitude = 1.0, longitude = 2.0),
                Waypoint(latitude = 3.0, longitude = 4.0)
            )
        )
        assertTrue("URL must contain the exact waypoints=0;1 param, got: $url", url.contains("waypoints=0;1"))
    }

    @Test
    fun buildViaUrlUsesLngLatCoordinateOrder() {
        // OSRM coordinate order is lng,lat (NOT lat,lng) — matches existing getRoute.
        // Waypoint(lat=1.0, lng=2.0) → "2.0,1.0"; Waypoint(lat=3.0, lng=4.0) → "4.0,3.0".
        val url = OsrmClient.buildViaUrl(
            listOf(
                Waypoint(latitude = 1.0, longitude = 2.0),
                Waypoint(latitude = 3.0, longitude = 4.0)
            )
        )
        assertTrue("coords must be lng,lat joined by ';', got: $url", url.contains("/2.0,1.0;4.0,3.0?"))
    }

    @Test
    fun buildViaUrlHasFullParamSetAndDrivingProfile() {
        val url = OsrmClient.buildViaUrl(
            listOf(
                Waypoint(latitude = 1.0, longitude = 2.0),
                Waypoint(latitude = 3.0, longitude = 4.0)
            )
        )
        // Exact URL — driving profile stays (public host ignores profile — VERIFIED; do NOT switch on sport).
        assertEquals(
            "https://router.project-osrm.org/route/v1/driving/2.0,1.0;4.0,3.0" +
                "?overview=full&geometries=geojson&steps=true&waypoints=0;1",
            url
        )
    }

    // ------------------------------------------------------------------
    // filterArriveSteps — non-final zero-distance arrive filter (NAVV-02b)
    // ------------------------------------------------------------------

    @Test
    fun filterArriveStepsDropsMidZeroDistanceArriveKeepsFinal() {
        val steps = listOf(
            NavigationStep("Head out", "depart", 100.0, 10.0, 1.0, 2.0),
            NavigationStep("Arrive at destination", "arrive", 0.0, 0.0, 3.0, 4.0), // mid, zero-distance → drop
            NavigationStep("Turn left", "left", 200.0, 20.0, 5.0, 6.0),
            NavigationStep("Arrive at destination", "arrive", 0.0, 0.0, 7.0, 8.0)  // final, zero-distance → keep
        )
        val filtered = OsrmClient.filterArriveSteps(steps)
        // Count decreases by exactly 1 (the mid arrive dropped).
        assertEquals(3, filtered.size)
        // The final zero-distance arrive is kept.
        assertEquals("arrive", filtered.last().maneuver)
        assertEquals(0.0, filtered.last().distance, 0.0)
        // The mid arrive is gone: no arrive step survives except the last.
        assertEquals(1, filtered.count { it.maneuver == "arrive" })
    }

    @Test
    fun filterArriveStepsKeepsAllNonArriveSteps() {
        val steps = listOf(
            NavigationStep("Head out", "depart", 100.0, 10.0, 1.0, 2.0),
            NavigationStep("Turn left", "left", 200.0, 20.0, 3.0, 4.0),
            NavigationStep("Continue straight", "straight", 300.0, 30.0, 5.0, 6.0)
        )
        val filtered = OsrmClient.filterArriveSteps(steps)
        assertEquals(steps, filtered)
    }

    @Test
    fun filterArriveStepsKeepsFinalArriveOnHappyPath() {
        // waypoints=0;last yields exactly one final arrive — the filter is a no-op here.
        val steps = listOf(
            NavigationStep("Head out", "depart", 100.0, 10.0, 1.0, 2.0),
            NavigationStep("Turn right", "right", 250.0, 25.0, 3.0, 4.0),
            NavigationStep("Arrive at destination", "arrive", 0.0, 0.0, 5.0, 6.0)
        )
        val filtered = OsrmClient.filterArriveSteps(steps)
        assertEquals(steps, filtered)
    }

    @Test
    fun filterArriveStepsKeepsNonZeroDistanceArrive() {
        // Only zero-distance non-final arrives are dropped; a non-zero arrive is preserved.
        val steps = listOf(
            NavigationStep("Head out", "depart", 100.0, 10.0, 1.0, 2.0),
            NavigationStep("Arrive at destination", "arrive", 50.0, 5.0, 3.0, 4.0), // non-zero → keep
            NavigationStep("Arrive at destination", "arrive", 0.0, 0.0, 5.0, 6.0)
        )
        val filtered = OsrmClient.filterArriveSteps(steps)
        assertEquals(3, filtered.size)
    }

    // ------------------------------------------------------------------
    // buildFollowRouteResult — non-empty synthetic step (NAVV-02c, Pitfall 1)
    // ------------------------------------------------------------------

    @Test
    fun buildFollowRouteResultHasExactlyOneFollowRouteStep() {
        val downsampled = listOf(
            Waypoint(latitude = 10.0, longitude = 20.0),
            Waypoint(latitude = 30.0, longitude = 40.0),
            Waypoint(latitude = 50.0, longitude = 60.0)
        )
        val result = OsrmClient.buildFollowRouteResult(downsampled)
        // Exactly one step — an empty steps list makes sendStepsList early-return (Pitfall 1).
        assertEquals(1, result.steps.size)
        val step = result.steps.first()
        assertEquals("Follow route", step.instruction)
        assertEquals("straight", step.maneuver)
        // Located at the first waypoint.
        assertEquals(10.0, step.locationLat, 0.0)
        assertEquals(20.0, step.locationLng, 0.0)
    }

    @Test
    fun buildFollowRouteResultCarriesDownsampledWaypoints() {
        val downsampled = listOf(
            Waypoint(latitude = 10.0, longitude = 20.0),
            Waypoint(latitude = 30.0, longitude = 40.0)
        )
        val result = OsrmClient.buildFollowRouteResult(downsampled)
        assertEquals(downsampled, result.waypoints)
    }

    @Test
    fun buildFollowRouteResultNeverEmptyStepsForNonEmptyInput() {
        val result = OsrmClient.buildFollowRouteResult(
            listOf(Waypoint(latitude = 1.0, longitude = 2.0))
        )
        assertTrue("follow-route must never have empty steps for non-empty input", result.steps.isNotEmpty())
    }

    @Test
    fun buildFollowRouteResultOnEmptyInputReturnsEmptyResult() {
        // Guard: an empty RouteResult only when there are literally no points.
        val result = OsrmClient.buildFollowRouteResult(emptyList())
        assertTrue(result.waypoints.isEmpty())
        assertTrue(result.steps.isEmpty())
    }
}
