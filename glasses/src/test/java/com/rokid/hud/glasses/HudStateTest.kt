package com.rokid.hud.glasses

import com.rokid.hud.shared.protocol.SportStateMessage
import com.rokid.hud.shared.protocol.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pure-JVM tests for the Sport HUD seams on [HudState]: the 3-way tap cycle
 * (HUD-03), the sport_state field mapping (HUD-02), and the staleness
 * precedence ladder. All clock inputs are injected nowMs values — HudState
 * never calls SystemClock, so no android.jar stubs are needed.
 */
class HudStateTest {

    private fun sampleSportState() = SportStateMessage(
        elapsedMs = 945_000L,
        movingMs = 823_000L,
        distanceM = 8420.0,
        currentSpeedMps = 6.2,
        avgPaceMsPerKm = 294_000L,
        sessionState = "tracking",
        sport = "ride"
    )

    // --- Tap/swipe cycle (HUD-03 + UF4-D2/D3: now 4-way with WHOLE_ROUTE) ---

    /** Forward 4-way cycle (D2): FULL_SCREEN -> SMALL_CORNER -> SPORT -> WHOLE_ROUTE -> FULL_SCREEN. */
    @Test
    fun tapCycleFullCornerSportFull() {
        val s0 = HudState()
        assertEquals(MapLayoutMode.FULL_SCREEN, s0.layoutMode)
        val s1 = s0.toggleLayout()
        assertEquals(MapLayoutMode.SMALL_CORNER, s1.layoutMode)
        val s2 = s1.toggleLayout()
        assertEquals(MapLayoutMode.SPORT, s2.layoutMode)
        val s3 = s2.toggleLayout()
        assertEquals(MapLayoutMode.WHOLE_ROUTE, s3.layoutMode)
        val s4 = s3.toggleLayout()
        assertEquals(MapLayoutMode.FULL_SCREEN, s4.layoutMode)
    }

    /** Same forward cycle asserted step-by-step from a fresh state (D2 must-have). */
    @Test
    fun fourPageForwardCycle() {
        var s = HudState()
        s = s.toggleLayout(); assertEquals(MapLayoutMode.SMALL_CORNER, s.layoutMode)
        s = s.toggleLayout(); assertEquals(MapLayoutMode.SPORT, s.layoutMode)
        s = s.toggleLayout(); assertEquals(MapLayoutMode.WHOLE_ROUTE, s.layoutMode)
        s = s.toggleLayout(); assertEquals(MapLayoutMode.FULL_SCREEN, s.layoutMode)
    }

    /** Reverse 4-way cycle (D3): FULL_SCREEN -> WHOLE_ROUTE -> SPORT -> SMALL_CORNER -> FULL_SCREEN. */
    @Test
    fun fourPageReverseCycle() {
        var s = HudState()
        s = s.toggleLayoutBack(); assertEquals(MapLayoutMode.WHOLE_ROUTE, s.layoutMode)
        s = s.toggleLayoutBack(); assertEquals(MapLayoutMode.SPORT, s.layoutMode)
        s = s.toggleLayoutBack(); assertEquals(MapLayoutMode.SMALL_CORNER, s.layoutMode)
        s = s.toggleLayoutBack(); assertEquals(MapLayoutMode.FULL_SCREEN, s.layoutMode)
    }

    /** toggleLayout() then toggleLayoutBack() (and vice-versa) returns to the same mode for all four cycle modes. */
    @Test
    fun forwardThenBackIsIdentity() {
        val cycleModes = listOf(
            MapLayoutMode.FULL_SCREEN,
            MapLayoutMode.SMALL_CORNER,
            MapLayoutMode.SPORT,
            MapLayoutMode.WHOLE_ROUTE
        )
        for (mode in cycleModes) {
            assertEquals(
                "forward-then-back from $mode must be identity",
                mode,
                HudState(layoutMode = mode).toggleLayout().toggleLayoutBack().layoutMode
            )
            assertEquals(
                "back-then-forward from $mode must be identity",
                mode,
                HudState(layoutMode = mode).toggleLayoutBack().toggleLayout().layoutMode
            )
        }
    }

    @Test
    fun miniModesTapReturnToFull() {
        assertEquals(
            MapLayoutMode.FULL_SCREEN,
            HudState(layoutMode = MapLayoutMode.MINI_BOTTOM).toggleLayout().layoutMode
        )
        assertEquals(
            MapLayoutMode.FULL_SCREEN,
            HudState(layoutMode = MapLayoutMode.MINI_SPLIT).toggleLayout().layoutMode
        )
    }

    /** MINI_* must also collapse to FULL_SCREEN on the reverse cycle (never strand a phone mode). */
    @Test
    fun miniModesReverseToFull() {
        assertEquals(
            MapLayoutMode.FULL_SCREEN,
            HudState(layoutMode = MapLayoutMode.MINI_BOTTOM).toggleLayoutBack().layoutMode
        )
        assertEquals(
            MapLayoutMode.FULL_SCREEN,
            HudState(layoutMode = MapLayoutMode.MINI_SPLIT).toggleLayoutBack().layoutMode
        )
    }

    // --- D4 no-clobber: full=true seeds wholeRoute; a differing full=false reroute never overwrites it ---

    @Test
    fun fullFlaggedRouteSetsWholeRouteAndRerouteDoesNotClobber() {
        val routeA = listOf(Waypoint(47.0, -122.0), Waypoint(47.1, -122.1))
        val routeB = listOf(Waypoint(48.0, -121.0), Waypoint(48.5, -120.5)) // DIFFERENT from routeA

        val s0 = HudState()
        // full=true store — the exact copy() the BluetoothClient Route(full=true) branch performs.
        val s1 = s0.copy(waypoints = routeA, wholeRoute = routeA)
        assertEquals("full=true seeds wholeRoute", routeA, s1.wholeRoute)
        assertEquals("full=true also updates the live route", routeA, s1.waypoints)

        // full=false reroute store — keeps the prior wholeRoute (BluetoothClient full=false branch).
        val s2 = s1.copy(waypoints = routeB, wholeRoute = s1.wholeRoute)
        assertEquals("reroute updates the live route", routeB, s2.waypoints)
        assertEquals("reroute leaves the birdview source unchanged", routeA, s2.wholeRoute)
        assertNotEquals("birdview source is NOT the reroute waypoints", routeB, s2.wholeRoute)
    }

    // --- sport_state mapping (HUD-02) ---

    @Test
    fun applySportStateMapsAllFieldsAndStampsReceipt() {
        val s = HudState().applySportState(sampleSportState(), 12_345L)
        assertEquals(945_000L, s.elapsedMs)
        assertEquals(823_000L, s.movingMs)
        assertEquals(8420.0, s.distanceM, 1e-9)
        assertEquals(6.2, s.currentSpeedMps, 1e-9)
        assertEquals(294_000L, s.avgPaceMsPerKm)
        assertEquals("tracking", s.sessionState)
        assertEquals("ride", s.sport)
        assertEquals(12_345L, s.lastSportStateAtMs)
    }

    @Test
    fun sportFieldsSurviveUnrelatedCopies() {
        val s = HudState()
            .applySportState(sampleSportState(), 111L)
            .copy(btConnected = true)
            .withNotification(NotificationItem("t", "x", "p", 1L))
        assertEquals(111L, s.lastSportStateAtMs)
        assertEquals(8420.0, s.distanceM, 1e-9)
    }

    // --- Staleness precedence ladder ---

    @Test
    fun stalenessLadder() {
        val s = HudState().applySportState(sampleSportState(), 1_000L)
        // 2999ms old -> LIVE
        assertEquals(SportDisplayMode.LIVE, s.sportDisplayMode(3_999L))
        // 3001ms old -> STALE_DIM
        assertEquals(SportDisplayMode.STALE_DIM, s.sportDisplayMode(4_001L))
        // exactly 10000ms old -> still STALE_DIM (strict >)
        assertEquals(SportDisplayMode.STALE_DIM, s.sportDisplayMode(11_000L))
        // 10001ms old -> STALE_NO_DATA
        assertEquals(SportDisplayMode.STALE_NO_DATA, s.sportDisplayMode(11_001L))
    }

    @Test
    fun finishedBeatsStaleness() {
        // Phone stops the ticker after the final finished broadcast —
        // staleness must never dim FINISHED (Pitfall 4)
        val s = HudState().applySportState(
            sampleSportState().copy(sessionState = "finished"), 1_000L
        )
        assertEquals(SportDisplayMode.FINISHED, s.sportDisplayMode(999_999L))
    }

    @Test
    fun neverReceivedIsNotRecording() {
        assertEquals(SportDisplayMode.NOT_RECORDING, HudState().sportDisplayMode(99_999L))
    }

    @Test
    fun idleIsNotRecording() {
        val s = HudState().applySportState(
            sampleSportState().copy(sessionState = "idle"), 1_000L
        )
        assertEquals(SportDisplayMode.NOT_RECORDING, s.sportDisplayMode(1_500L))
    }
}
