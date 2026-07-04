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

        override fun onRouteCalculated(waypoints: List<Waypoint>, totalDistance: Double, totalDuration: Double, steps: List<NavigationStep>, full: Boolean) {
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
        // Four waypoints ~1.1km apart (0.01 deg lat). STEP_ADVANCE_RADIUS_M is 150m, so the pointer
        // (which names the waypoint we're HEADING TOWARD, starting at index 0) only advances once
        // the sim location is essentially ON the current target waypoint.
        val waypoints = listOf(
            wp(37.0000, -122.0000),
            wp(37.0100, -122.0000),
            wp(37.0200, -122.0000),
            wp(37.0300, -122.0000)
        )
        nav.startNavigationWithRoute(waypoints, listOf(followStep), 3300.0, 0.0, followRouteMode = true)
        assertEquals(0, nav.nextWaypointIndexForTest)

        // Sit ~550m south of wp[0] → far from target wp[0], no advance.
        nav.onLocationUpdate(36.9950, -122.0000)
        assertEquals("pointer stays at 0 while far from target wp[0]", 0, nav.nextWaypointIndexForTest)

        // Reach wp[0] → pointer advances past it to target wp[1].
        nav.onLocationUpdate(37.0000, -122.0000)
        assertEquals("pointer advances to 1 after reaching wp[0]", 1, nav.nextWaypointIndexForTest)

        // Reach wp[1] → pointer advances to target wp[2].
        nav.onLocationUpdate(37.0100, -122.0000)
        assertEquals("pointer advances to 2 after reaching wp[1]", 2, nav.nextWaypointIndexForTest)

        // Reach wp[2] → pointer advances to lastIndex (3) and stops there.
        nav.onLocationUpdate(37.0200, -122.0000)
        assertEquals("pointer advances to lastIndex", 3, nav.nextWaypointIndexForTest)

        // Move back near wp[1] — pointer must NOT rewind (forward-only).
        nav.onLocationUpdate(37.0100, -122.0000)
        assertEquals("pointer never rewinds", 3, nav.nextWaypointIndexForTest)
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
    fun offRouteRerouteNeverDrivesIndicesNegative() {
        // NAVV-03 forward-only invariant through an off-route reroute reset. The actual reroute
        // runs on a Thread{} → OsrmClient.getRouteVia (network); on plain JVM it will not complete,
        // but the SYNCHRONOUS off-route branch that dispatches it must never drive the caller-thread
        // indices negative. We drive the manager far off the route (>80m from every waypoint) and
        // assert currentStepIndex / nextWaypointIndex stay >= 0.
        val nav = NavigationManager(FakeNavigationCallback())
        val steps = (0..4).map { i ->
            val mv = if (i == 0) "depart" else if (i == 4) "arrive" else "left"
            step(37.0000 + i * 0.0100, -122.0000, "Step $i", mv)
        }
        val waypoints = (0..4).map { wp(37.0000 + it * 0.0100, -122.0000) }
        nav.startNavigationWithRoute(waypoints, steps, 4400.0, 0.0, followRouteMode = false)

        // Advance a couple of steps so currentStepIndex > 0 before we go off-route.
        nav.onLocationUpdate(steps[1].locationLat, steps[1].locationLng)
        nav.onLocationUpdate(steps[2].locationLat, steps[2].locationLng)
        val idxBefore = nav.currentStepIndex
        assertTrue("advanced before off-route", idxBefore >= 1)

        // Jump ~11km east of the route (far beyond OFF_ROUTE_RADIUS_M=80m) → triggers reroute
        // dispatch on a background thread. The caller-thread indices must not go negative.
        nav.onLocationUpdate(37.0200, -121.9000)
        assertTrue("currentStepIndex never negative after off-route", nav.currentStepIndex >= 0)
        assertTrue("nextWaypointIndex never negative after off-route", nav.nextWaypointIndexForTest >= 0)
    }

    // ------------------------------------------------------------------
    // NAVV-03 (deferred off-route reroute): approach-vs-reroute gating on hasBeenOnRoute
    // ------------------------------------------------------------------
    //
    // As documented in the class KDoc: onRerouting()/onStepChanged() post to the main Looper and
    // are NO-OPs on plain JVM, so reroutingCount/stepChanges stay empty. The reroute DECISION and
    // the approach DECISION are witnessed SYNCHRONOUSLY via hasBeenOnRouteForTest /
    // lastRerouteTimeForTest (lastRerouteTime advances on the caller thread ONLY when a real
    // reroute is dispatched; the approach emit uses a separate timestamp and never touches it).

    @Test
    fun startOffRouteNeverJoinedDoesNotReroute() {
        // Fix 1+3: tapping START far from an imported Strava route must NOT reroute. The manager
        // takes the approach branch ("Head to route → dist"), leaving the imported route intact.
        val nav = NavigationManager(FakeNavigationCallback())
        val importedSteps = listOf(
            step(37.0000, -122.0000, "Head out", "depart"),
            step(37.0100, -122.0000, "Turn left", "left"),
            step(37.0200, -122.0000, "Arrive at destination", "arrive")
        )
        val waypoints = listOf(wp(37.0000, -122.0000), wp(37.0100, -122.0000), wp(37.0200, -122.0000))
        nav.startNavigationWithRoute(waypoints, importedSteps, totalDistance = 2200.0, totalDuration = 0.0, followRouteMode = false)

        // A FAR fix: tens of km from every waypoint (and from the stored dest) — well beyond
        // OFF_ROUTE_RADIUS_M=80m, and far from any maneuver point so no step-advance/arrival fires.
        nav.onLocationUpdate(37.5000, -121.0000)

        assertFalse("never joined the route", nav.hasBeenOnRouteForTest)
        // No real reroute was dispatched — proves the APPROACH branch ran, not the reroute branch,
        // so the "Head to route" emit path (not rerouteThroughRemainingWaypoints) was taken.
        assertEquals("no real reroute dispatched at start", 0L, nav.lastRerouteTimeForTest)
        // The imported route + steps stay untouched (same reference, index not advanced) — the
        // synchronous proxy for "still in routed mode, not follow-route".
        assertTrue("imported steps unchanged (same reference)", nav.steps === importedSteps)
        assertEquals("currentStepIndex not advanced", 0, nav.currentStepIndex)
    }

    @Test
    fun joinThenDeviateReroutesOnlyAfterJoining() {
        // Fix 2+3: hasBeenOnRoute latches on join, and ONLY a post-join off-route fix dispatches a
        // real reroute. 5 waypoints/steps so joining at wp[0] neither advances a step (~1.1km to the
        // next maneuver) nor triggers arrival (dest = far last waypoint).
        val nav = NavigationManager(FakeNavigationCallback())
        val steps = (0..4).map { i ->
            val mv = if (i == 0) "depart" else if (i == 4) "arrive" else "left"
            step(37.0000 + i * 0.0100, -122.0000, "Step $i", mv)
        }
        val waypoints = (0..4).map { wp(37.0000 + it * 0.0100, -122.0000) }
        nav.startNavigationWithRoute(waypoints, steps, 4400.0, 0.0, followRouteMode = false)
        assertFalse("not joined at start", nav.hasBeenOnRouteForTest)

        // ON-route fix: exactly on wp[0] (nearestDist ~0m <= 80m) → latches hasBeenOnRoute, but a
        // pure join must NOT dispatch a reroute.
        nav.onLocationUpdate(37.0000, -122.0000)
        assertTrue("joined after an on-route fix", nav.hasBeenOnRouteForTest)
        assertEquals("joining alone does not reroute", 0L, nav.lastRerouteTimeForTest)

        // FAR fix (~11km east): now that the rider has joined, a genuine deviation DISPATCHES a
        // real reroute. The dispatch spawns a Thread → OsrmClient.getRouteVia (network) that will
        // not complete on plain JVM; we assert only the synchronous DISPATCH decision + non-negative
        // caller-thread indices, never the OSRM result.
        nav.onLocationUpdate(37.0200, -121.9000)
        assertTrue("real reroute dispatched only after joining", nav.lastRerouteTimeForTest > 0L)
        assertTrue("currentStepIndex never negative after reroute", nav.currentStepIndex >= 0)
        assertTrue("nextWaypointIndex never negative after reroute", nav.nextWaypointIndexForTest >= 0)
    }

    @Test
    fun capRerouteWaypointsBoundsSizeAndKeepsEndpoints() {
        // Fix 4: a large remaining slice is capped to <= MAX_REROUTE_WAYPOINTS (25, documented
        // literal — the const is private), always keeping the first and last waypoint.
        val nav = NavigationManager(FakeNavigationCallback())
        val large = (0 until 200).map { wp(37.0 + it * 0.001, -122.0) }

        val capped = nav.capRerouteWaypoints(large)
        assertTrue("capped size <= 25 (was ${capped.size})", capped.size <= 25)
        assertEquals("first waypoint preserved", large.first(), capped.first())
        assertEquals("last waypoint preserved", large.last(), capped.last())

        // Pass-through: a list already <= 25 is returned unchanged (equal to input).
        val small = (0 until 25).map { wp(37.0 + it * 0.001, -122.0) }
        assertEquals("<=25 list returned unchanged", small, nav.capRerouteWaypoints(small))
    }

    @Test
    fun stopNavigationResetsFollowRouteState() {
        val nav = NavigationManager(FakeNavigationCallback())
        val followStep = NavigationStep("Follow route", "straight", 0.0, 0.0, 37.0, -122.0)
        // Three waypoints so reaching wp[0] advances the pointer to 1 WITHOUT hitting the last
        // waypoint (which would trigger arrival and reset isNavigating on its own).
        val waypoints = listOf(wp(37.0, -122.0), wp(37.01, -122.0), wp(37.02, -122.0))
        nav.startNavigationWithRoute(waypoints, listOf(followStep), 0.0, 0.0, followRouteMode = true)
        nav.onLocationUpdate(37.0, -122.0) // reach wp[0] → pointer advances to 1
        assertTrue("pointer advanced before stop", nav.nextWaypointIndexForTest >= 1)
        assertTrue("still navigating (not at last waypoint)", nav.isNavigating)

        nav.stopNavigation()
        assertFalse("isNavigating false after stop", nav.isNavigating)
        assertEquals("steps cleared after stop", 0, nav.steps.size)
        assertEquals("currentStepIndex reset after stop", 0, nav.currentStepIndex)
        assertEquals("nextWaypointIndex reset after stop", 0, nav.nextWaypointIndexForTest)
    }
}
