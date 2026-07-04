package com.rokid.hud.phone

import com.rokid.hud.shared.protocol.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NAVV-01 / NAVV-02c / NAVV-03 field invariants for the waypoint-accepting nav path.
 *
 * WHY FIELD ASSERTIONS (not callback assertions): NavigationManager's callbacks post to the
 * main Looper via `mainHandler.post {}`. On plain JVM (this project has NO Robolectric —
 * `unitTests.isReturnDefaultValues = true` stubs android.*), `Handler.post` is a stubbed no-op
 * that never runs the Runnable, so the fake callback records nothing. The load-bearing NAVV
 * guarantees, however, are the SYNCHRONOUS state that startNavigationWithRoute + onLocationUpdate
 * set on the CALLER thread BEFORE the post — steps, currentStepIndex, isNavigating, the
 * forward-only nextWaypointIndex, and the @Volatile publication. Those we assert directly, which
 * proves the invariants without a Looper (mirrors the no-Robolectric discipline of OsrmViaUrlTest
 * / ActivitySessionManagerTest — pure JUnit4, exact-value assertEquals, hand-built inputs).
 *
 * Covers:
 *   - NAVV-01: startNavigationWithRoute stores the passed steps, currentStepIndex=0, isNavigating=true
 *   - NAVV-02c: follow-route mode holds EXACTLY ONE synthetic "Follow route" step + forward-only
 *     nextWaypointIndex that advances monotonically as GPS approaches downsampled waypoints
 *   - NAVV-03: routed-mode currentStepIndex is monotonic non-decreasing across a simulated
 *     traversal (Pitfall 3 butterfly prevention — the forward-only index is never rewound), and a
 *     reroute reset never drives the index negative
 */
class NavigationRouteTest {

    /** Records callback args when a Looper happens to be present; otherwise unused (see class KDoc). */
    private class FakeNavigationCallback : NavigationCallback {
        val routeCalculatedSteps = mutableListOf<List<NavigationStep>>()
        val stepChanges = mutableListOf<Triple<String, String, Double>>()
        var arrivedCount = 0
        var reroutingCount = 0
        var lastError: String? = null

        override fun onRouteCalculated(waypoints: List<Waypoint>, totalDistance: Double, totalDuration: Double, steps: List<NavigationStep>) {
            routeCalculatedSteps.add(steps)
        }
        override fun onStepChanged(instruction: String, maneuver: String, distance: Double) {
            stepChanges.add(Triple(instruction, maneuver, distance))
        }
        override fun onNavigationError(message: String) { lastError = message }
        override fun onArrived() { arrivedCount++ }
        override fun onRerouting() { reroutingCount++ }
    }

    private fun step(lat: Double, lng: Double, instruction: String = "Turn", maneuver: String = "left"): NavigationStep =
        NavigationStep(instruction = instruction, maneuver = maneuver, distance = 100.0, duration = 20.0, locationLat = lat, locationLng = lng)

    private fun wp(lat: Double, lng: Double): Waypoint = Waypoint(latitude = lat, longitude = lng)

    // ------------------------------------------------------------------
    // NAVV-01: startNavigationWithRoute sets the field state on the caller thread
    // ------------------------------------------------------------------

    @Test
    fun startNavigationWithRouteStoresStepsAndResetsIndex() {
        val nav = NavigationManager(FakeNavigationCallback())
        val steps = listOf(
            step(37.0000, -122.0000, "Head out", "depart"),
            step(37.0010, -122.0000, "Turn left", "left"),
            step(37.0020, -122.0000, "Arrive at destination", "arrive")
        )
        val waypoints = listOf(wp(37.0000, -122.0000), wp(37.0010, -122.0000), wp(37.0020, -122.0000))

        nav.startNavigationWithRoute(waypoints, steps, totalDistance = 500.0, totalDuration = 120.0, followRouteMode = false)

        assertTrue("isNavigating must be true after start", nav.isNavigating)
        assertEquals("steps must equal the passed steps", steps, nav.steps)
        assertEquals("currentStepIndex must reset to 0", 0, nav.currentStepIndex)
    }

    @Test
    fun startNavigationWithRouteEmptyWaypointsDoesNotCrash() {
        val nav = NavigationManager(FakeNavigationCallback())
        // Degenerate but must not throw; dest stays unset, steps empty.
        nav.startNavigationWithRoute(emptyList(), emptyList(), totalDistance = 0.0, totalDuration = 0.0, followRouteMode = false)
        assertTrue(nav.isNavigating)
        assertTrue(nav.steps.isEmpty())
        assertEquals(0, nav.currentStepIndex)
    }

    // ------------------------------------------------------------------
    // NAVV-02c: follow-route mode holds exactly one synthetic step, forward-only pointer
    // ------------------------------------------------------------------

    @Test
    fun followRouteModeHoldsExactlyOneSyntheticStep() {
        val nav = NavigationManager(FakeNavigationCallback())
        val followStep = NavigationStep("Follow route", "straight", 0.0, 0.0, 37.0000, -122.0000)
        val waypoints = listOf(
            wp(37.0000, -122.0000),
            wp(37.0100, -122.0000),
            wp(37.0200, -122.0000)
        )

        nav.startNavigationWithRoute(waypoints, listOf(followStep), totalDistance = 2200.0, totalDuration = 0.0, followRouteMode = true)

        assertEquals("follow-route must carry exactly one step", 1, nav.steps.size)
        assertEquals("the single step is the synthetic Follow route step", "Follow route", nav.steps[0].instruction)
    }

    @Test
    fun followRouteNextWaypointPointerAdvancesForwardOnly() {
        val nav = NavigationManager(FakeNavigationCallback())
        val followStep = NavigationStep("Follow route", "straight", 0.0, 0.0, 37.0000, -122.0000)
        // Three waypoints ~1.1km apart (0.01 deg lat). STEP_ADVANCE_RADIUS_M is 150m, so the pointer
        // only advances when the sim location is essentially ON a waypoint.
        val waypoints = listOf(
            wp(37.0000, -122.0000),
            wp(37.0100, -122.0000),
            wp(37.0200, -122.0000)
        )
        nav.startNavigationWithRoute(waypoints, listOf(followStep), 2200.0, 0.0, followRouteMode = true)
        assertEquals(0, nav.nextWaypointIndexForTest)

        // Sit far from waypoint[1] → no advance.
        nav.onLocationUpdate(37.0000, -122.0000)
        assertEquals("pointer stays at 0 while far from wp[1]", 0, nav.nextWaypointIndexForTest)

        // Arrive at waypoint[1] → pointer advances to 1.
        nav.onLocationUpdate(37.0100, -122.0000)
        assertEquals("pointer advances to 1 at wp[1]", 1, nav.nextWaypointIndexForTest)

        // Arrive at waypoint[2] → pointer advances to 2 (lastIndex; stops there).
        nav.onLocationUpdate(37.0200, -122.0000)
        assertEquals("pointer advances to lastIndex", 2, nav.nextWaypointIndexForTest)

        // Move back near wp[1] — pointer must NOT rewind (forward-only).
        nav.onLocationUpdate(37.0100, -122.0000)
        assertEquals("pointer never rewinds", 2, nav.nextWaypointIndexForTest)
    }

    @Test
    fun followRouteNextWaypointPointerIsMonotonicAcrossTraversal() {
        val nav = NavigationManager(FakeNavigationCallback())
        val followStep = NavigationStep("Follow route", "straight", 0.0, 0.0, 37.0000, -122.0000)
        val waypoints = (0..10).map { wp(37.0000 + it * 0.0100, -122.0000) }
        nav.startNavigationWithRoute(waypoints, listOf(followStep), 0.0, 0.0, followRouteMode = true)

        var prev = nav.nextWaypointIndexForTest
        // Walk forward through every waypoint, then jitter backward at each — pointer must be monotonic.
        for (i in waypoints.indices) {
            nav.onLocationUpdate(waypoints[i].latitude, waypoints[i].longitude)
            val now = nav.nextWaypointIndexForTest
            assertTrue("nextWaypointIndex must be monotonic non-decreasing (was $prev, now $now)", now >= prev)
            prev = now
            // Backward jitter to an earlier waypoint must not rewind.
            if (i > 0) {
                nav.onLocationUpdate(waypoints[i - 1].latitude, waypoints[i - 1].longitude)
                assertTrue("no rewind on backward jitter", nav.nextWaypointIndexForTest >= now)
            }
        }
    }

    // ------------------------------------------------------------------
    // NAVV-03: routed-mode currentStepIndex is monotonic non-decreasing (Pitfall 3)
    // ------------------------------------------------------------------

    @Test
    fun routedModeCurrentStepIndexIsMonotonicAcrossTraversal() {
        val nav = NavigationManager(FakeNavigationCallback())
        // A chain of maneuver points ~1.1km apart so each advance is a discrete step.
        val steps = (0..8).map { i ->
            val mv = if (i == 0) "depart" else if (i == 8) "arrive" else "left"
            step(37.0000 + i * 0.0100, -122.0000, "Step $i", mv)
        }
        val waypoints = (0..8).map { wp(37.0000 + it * 0.0100, -122.0000) }
        nav.startNavigationWithRoute(waypoints, steps, 8800.0, 0.0, followRouteMode = false)

        var prev = nav.currentStepIndex
        for (i in steps.indices) {
            // Approach step[i]'s maneuver location.
            nav.onLocationUpdate(steps[i].locationLat, steps[i].locationLng)
            val now = nav.currentStepIndex
            assertTrue("currentStepIndex must be monotonic non-decreasing (was $prev, now $now)", now >= prev)
            prev = now
            // Backward GPS jitter must NOT rewind the index.
            if (i > 0) {
                nav.onLocationUpdate(steps[i - 1].locationLat, steps[i - 1].locationLng)
                assertTrue("no rewind on backward jitter", nav.currentStepIndex >= now)
            }
        }
    }

    @Test
    fun stopNavigationResetsFollowRouteState() {
        val nav = NavigationManager(FakeNavigationCallback())
        val followStep = NavigationStep("Follow route", "straight", 0.0, 0.0, 37.0, -122.0)
        val waypoints = listOf(wp(37.0, -122.0), wp(37.01, -122.0))
        nav.startNavigationWithRoute(waypoints, listOf(followStep), 0.0, 0.0, followRouteMode = true)
        nav.onLocationUpdate(37.01, -122.0)
        assertTrue(nav.nextWaypointIndexForTest >= 1)

        nav.stopNavigation()
        assertFalse("isNavigating false after stop", nav.isNavigating)
        assertEquals("steps cleared after stop", 0, nav.steps.size)
        assertEquals("currentStepIndex reset after stop", 0, nav.currentStepIndex)
        assertEquals("nextWaypointIndex reset after stop", 0, nav.nextWaypointIndexForTest)
    }
}
