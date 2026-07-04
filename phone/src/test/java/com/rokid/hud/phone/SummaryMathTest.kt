package com.rokid.hud.phone

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UPL-01: pure summary math. Avg PACE is DERIVED here (SessionData persists only
 * avgSpeedMps, never pace — Pitfall 6), and the formula must MIRROR
 * ActivitySessionManager.avgPaceMsPerKm EXACTLY so the summary screen shows the
 * same number the live HUD showed, including the sub-100m 0L floor (a 20m test
 * walk renders "–:––", not garbage — Pitfall 5).
 *
 * Avg SPEED is read straight from SessionData.avgSpeedMps (moving-based) and is
 * NOT recomputed — asserted here via the passthrough that returns its input.
 *
 * Pure JVM, no android.* references.
 */
class SummaryMathTest {

    @Test
    fun avgPaceMatchesActivitySessionManagerFormula() {
        // (600000 / (2000/1000)).roundToLong() == 300000
        assertEquals(300_000L, SummaryMath.avgPaceMsPerKm(movingMs = 600_000L, distanceM = 2000.0))
    }

    @Test
    fun avgPaceBelowHundredMetreFloorIsZero() {
        // distanceM < PACE_MIN_DISTANCE_M (100.0) -> 0L regardless of movingMs.
        assertEquals(0L, SummaryMath.avgPaceMsPerKm(movingMs = 500_000L, distanceM = 50.0))
        assertEquals(0L, SummaryMath.avgPaceMsPerKm(movingMs = 1L, distanceM = 99.999))
    }

    @Test
    fun avgPaceAtExactlyHundredMetresIsComputedNotFloored() {
        // 100.0 is NOT below the floor (the recorder uses `dist < 100.0`), so it computes.
        // (60000 / (100/1000)).roundToLong() == 600000
        assertEquals(600_000L, SummaryMath.avgPaceMsPerKm(movingMs = 60_000L, distanceM = 100.0))
    }

    @Test
    fun avgPaceRoundsToNearestMillisecondLikeTheRecorder() {
        // Force a fractional result to prove roundToLong (not truncation).
        // 500000 / (3000/1000) = 166666.666... -> rounds to 166667
        assertEquals(166_667L, SummaryMath.avgPaceMsPerKm(movingMs = 500_000L, distanceM = 3000.0))
    }

    @Test
    fun avgSpeedIsReadNotRecomputed_passthroughReturnsInputUnchanged() {
        // The summary reads SessionData.avgSpeedMps verbatim (moving-based, Pitfall 5).
        // SummaryMath exposes only a passthrough to document "do not recompute here".
        assertEquals(6.94, SummaryMath.avgSpeedMps(6.94), 1e-9)
        assertEquals(0.0, SummaryMath.avgSpeedMps(0.0), 1e-9)
    }
}
