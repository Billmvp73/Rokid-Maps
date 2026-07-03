package com.rokid.hud.glasses

import com.rokid.hud.shared.protocol.SportStateMessage
import org.junit.Assert.assertEquals
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

    // --- Tap cycle (HUD-03, Phase 2 SC#1) ---

    @Test
    fun tapCycleFullCornerSportFull() {
        val s0 = HudState()
        assertEquals(MapLayoutMode.FULL_SCREEN, s0.layoutMode)
        val s1 = s0.toggleLayout()
        assertEquals(MapLayoutMode.SMALL_CORNER, s1.layoutMode)
        val s2 = s1.toggleLayout()
        assertEquals(MapLayoutMode.SPORT, s2.layoutMode)
        val s3 = s2.toggleLayout()
        assertEquals(MapLayoutMode.FULL_SCREEN, s3.layoutMode)
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
