package com.rokid.hud.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM tests for [ActivitySessionManager] (REC-01..REC-04 + the REC-07
 * monotonicity contract).
 *
 * Every test drives the primitive-parameter [ActivitySessionManager.onFix]
 * entry directly with explicit injected clocks — no android.location.Location
 * and no SystemClock anywhere (RESEARCH Pattern 3 / Pitfall 7). Doubles are
 * always compared with an explicit tolerance.
 */
class ActivitySessionManagerTest {

    companion object {
        /** Fixed wall clock: 2023-11-14T22:13:20Z — exact second, ISO ends in Z. */
        private const val WALL_T0 = 1_700_000_000_000L
        private const val LAT0 = 47.6062
        private const val LNG0 = -122.3321
    }

    /** Scenario helper: fresh manager already TRACKING (started at [startNow]). */
    private fun startedAsm(sport: String = "ride", startNow: Long = 1000L): ActivitySessionManager {
        val asm = ActivitySessionManager()
        assertTrue("startSession should succeed from IDLE", asm.startSession(sport, startNow, WALL_T0))
        return asm
    }

    /** Scenario helper: feed one fix with test-friendly defaults. */
    private fun fix(
        asm: ActivitySessionManager,
        atMs: Long,
        lat: Double = LAT0,
        lng: Double = LNG0,
        speedMps: Double = Double.NaN,
        accuracyM: Double = 5.0,
        alt: Double = Double.NaN,
        bearingDeg: Double = Double.NaN
    ) {
        asm.onFix(lat, lng, alt, WALL_T0 + atMs, speedMps, accuracyM, bearingDeg, atMs)
    }

    // ------------------------------------------------------------------
    // Task 1: state machine, lifecycle, track buffer, and time base
    // ------------------------------------------------------------------

    @Test
    fun initialStateIsIdle() {
        val asm = ActivitySessionManager()
        assertEquals(SessionState.IDLE, asm.state)
        val snap = asm.currentSnapshot(0L)
        assertEquals("idle", snap.sessionState)
        assertEquals(0, snap.trackPointCount)
        assertEquals(0L, snap.elapsedMs)
    }

    @Test
    fun startSessionMovesToTracking() {
        val asm = ActivitySessionManager()
        assertTrue(asm.startSession("ride", 1000L, WALL_T0))
        assertEquals(SessionState.TRACKING, asm.state)
        val snap = asm.currentSnapshot(1000L)
        assertEquals("tracking", snap.sessionState)
        assertEquals("ride", snap.sport)
    }

    @Test
    fun secondStartWhileTrackingReturnsFalseAndChangesNothing() {
        val asm = startedAsm(sport = "ride")
        val idBefore = asm.snapshotSession(2000L)!!.id
        assertFalse(asm.startSession("run", 5000L, WALL_T0 + 4000L))
        assertEquals(SessionState.TRACKING, asm.state)
        val after = asm.snapshotSession(2000L)!!
        assertEquals(idBefore, after.id)
        assertEquals("ride", after.sport)
    }

    @Test
    fun sessionIdAndStartTimeFormats() {
        val data = startedAsm().snapshotSession(2000L)
        assertNotNull(data)
        assertTrue(
            "id was ${data!!.id}",
            Regex("^\\d{8}-\\d{6}-[0-9a-f]{8}$").matches(data.id)
        )
        assertTrue("startTime was ${data.startTime}", data.startTime.endsWith("Z"))
        assertTrue("startTime was ${data.startTime}", data.startTime.contains("T"))
    }

    @Test
    fun fixesIgnoredWhileIdle() {
        val asm = ActivitySessionManager()
        fix(asm, 1000L)
        assertEquals(0, asm.currentSnapshot(2000L).trackPointCount)
    }

    @Test
    fun fixesIgnoredAfterStop() {
        val asm = startedAsm()
        fix(asm, 2000L)
        assertNotNull(asm.stopSession(3000L, WALL_T0 + 2000L))
        fix(asm, 4000L)
        fix(asm, 5000L)
        assertEquals(1, asm.currentSnapshot(6000L).trackPointCount)
    }

    @Test
    fun fixWhileTrackingAlwaysAppendsEvenTerribleAccuracy() {
        val asm = startedAsm()
        // accuracy 99.0 is way past the 20m gate — must STILL be logged (REC-03)
        fix(asm, 2000L, accuracyM = 99.0)
        assertEquals(1, asm.currentSnapshot(2500L).trackPointCount)
        val tp = asm.snapshotSession(3000L)!!.trackPoints[0]
        assertEquals(LAT0, tp.lat, 1e-9)
        assertEquals(LNG0, tp.lng, 1e-9)
        assertEquals(WALL_T0 + 2000L, tp.ts)
        assertEquals(99.0, tp.accuracyM, 1e-9)
        assertTrue("alt should stay NaN", tp.alt.isNaN())
        assertTrue("speed should stay NaN", tp.speedMps.isNaN())
        assertTrue("bearing should stay NaN", tp.bearingDeg.isNaN())
    }

    @Test
    fun fixPreservesAllProvidedFields() {
        val asm = startedAsm()
        asm.onFix(47.61, -122.34, 120.5, WALL_T0 + 2000L, 3.2, 8.0, 270.0, 2000L)
        val tp = asm.snapshotSession(3000L)!!.trackPoints[0]
        assertEquals(47.61, tp.lat, 1e-9)
        assertEquals(-122.34, tp.lng, 1e-9)
        assertEquals(120.5, tp.alt, 1e-9)
        assertEquals(3.2, tp.speedMps, 1e-9)
        assertEquals(8.0, tp.accuracyM, 1e-9)
        assertEquals(270.0, tp.bearingDeg, 1e-9)
    }

    @Test
    fun elapsedDerivesFromInjectedMonotonicClock() {
        val asm = startedAsm(startNow = 1000L)
        assertEquals(60_000L, asm.currentSnapshot(61_000L).elapsedMs)
    }

    @Test
    fun elapsedNeverDecreasesAcrossSnapshots() {
        val asm = startedAsm(startNow = 1000L)
        assertEquals(60_000L, asm.currentSnapshot(61_000L).elapsedMs)
        // a backwards clock reading must be clamped, never emitted lower (REC-07)
        assertEquals(60_000L, asm.currentSnapshot(50_000L).elapsedMs)
        assertEquals(70_000L, asm.currentSnapshot(71_000L).elapsedMs)
    }

    @Test
    fun stopSessionFinalizesAndFreezes() {
        val asm = startedAsm(startNow = 1000L)
        fix(asm, 2000L)
        val data = asm.stopSession(61_000L, WALL_T0 + 60_000L)
        assertNotNull(data)
        assertEquals(SessionState.FINISHED, asm.state)
        assertTrue("endTime was ${data!!.endTime}", data.endTime!!.endsWith("Z"))
        assertEquals(60_000L, data.elapsedMs)
        // no moving time accumulated -> avg speed must be 0.0, not NaN/Infinity
        assertEquals(0.0, data.avgSpeedMps, 1e-9)
        // elapsed is frozen after stop, even for a much later snapshot
        val snap = asm.currentSnapshot(999_999L)
        assertEquals("finished", snap.sessionState)
        assertEquals(60_000L, snap.elapsedMs)
    }

    @Test
    fun stopWhileIdleReturnsNull() {
        assertNull(ActivitySessionManager().stopSession(1000L, WALL_T0))
    }

    @Test
    fun resetFromFinishedReturnsToIdle() {
        val asm = startedAsm()
        fix(asm, 2000L)
        asm.stopSession(3000L, WALL_T0 + 2000L)
        asm.reset()
        assertEquals(SessionState.IDLE, asm.state)
        val snap = asm.currentSnapshot(10_000L)
        assertEquals("idle", snap.sessionState)
        assertEquals(0L, snap.elapsedMs)
        assertEquals(0L, snap.movingMs)
        assertEquals(0.0, snap.distanceM, 1e-9)
        assertEquals(0, snap.trackPointCount)
    }

    private fun priorSession() = SessionData(
        id = "20260703-120000-abcdef01",
        sport = "run",
        startTime = "2026-07-03T12:00:00Z",
        endTime = null,
        elapsedMs = 600_000L,
        movingMs = 500_000L,
        distanceM = 1500.0,
        avgSpeedMps = 3.0,
        stravaUploaded = false,
        trackPoints = listOf(
            TrackPoint(LAT0, LNG0, Double.NaN, WALL_T0, 3.0, 5.0, Double.NaN),
            TrackPoint(LAT0 + 0.001, LNG0, Double.NaN, WALL_T0 + 1000L, 3.0, 5.0, Double.NaN)
        )
    )

    @Test
    fun resumeFromRestoresBases() {
        val asm = ActivitySessionManager()
        assertTrue(asm.resumeFrom(priorSession(), 100_000L, WALL_T0 + 600_000L))
        assertEquals(SessionState.TRACKING, asm.state)
        // elapsed continues from the restored base using the injected clock
        val snap = asm.currentSnapshot(105_000L)
        assertEquals(605_000L, snap.elapsedMs)
        assertEquals(500_000L, snap.movingMs)
        assertEquals(1500.0, snap.distanceM, 1e-9)
        assertEquals(2, snap.trackPointCount)
        assertEquals("run", snap.sport)
        val data = asm.snapshotSession(105_000L)!!
        assertEquals("20260703-120000-abcdef01", data.id)
        assertEquals("run", data.sport)
        assertEquals("2026-07-03T12:00:00Z", data.startTime)
    }

    @Test
    fun resumeFromWhileTrackingReturnsFalse() {
        val asm = startedAsm()
        assertFalse(asm.resumeFrom(priorSession(), 50_000L, WALL_T0 + 49_000L))
    }

    @Test
    fun firstAcceptedFixAfterResumeAddsNoDistance() {
        val asm = ActivitySessionManager()
        assertTrue(asm.resumeFrom(priorSession(), 100_000L, WALL_T0 + 600_000L))
        // an accurate, fast fix FAR from the last pre-death point — bridging it
        // would teleport ~1.1km of phantom distance into the session
        fix(asm, 101_000L, lat = LAT0 + 0.01, speedMps = 5.0, accuracyM = 5.0)
        assertEquals(1500.0, asm.currentSnapshot(101_500L).distanceM, 1e-6)
    }

    @Test
    fun pollCheckpointTimeThreshold() {
        val asm = startedAsm(startNow = 1000L)
        assertNull(asm.pollCheckpoint(31_000L))
        val cp = asm.pollCheckpoint(61_000L)   // elapsed 60s since last checkpoint
        assertNotNull(cp)
        assertNull("endTime must be null on a mid-session checkpoint", cp!!.endTime)
        // re-armed: immediately polling again yields nothing
        assertNull(asm.pollCheckpoint(61_000L))
        assertNotNull(asm.pollCheckpoint(121_000L))
    }

    @Test
    fun pollCheckpointPointThreshold() {
        val asm = startedAsm(startNow = 1000L)
        for (i in 1..499) fix(asm, 1000L + i)
        assertNull(asm.pollCheckpoint(2000L))
        fix(asm, 1500L)
        val cp = asm.pollCheckpoint(2100L)     // 500 points appended
        assertNotNull(cp)
        assertEquals(500, cp!!.trackPoints.size)
        // re-armed after firing
        assertNull(asm.pollCheckpoint(2200L))
    }

    @Test
    fun pollCheckpointNullOutsideTracking() {
        val asm = ActivitySessionManager()
        assertNull(asm.pollCheckpoint(70_000L))
        asm.startSession("ride", 1000L, WALL_T0)
        asm.stopSession(2000L, WALL_T0 + 1000L)
        assertNull(asm.pollCheckpoint(70_000L))
    }

    @Test
    fun snapshotSessionReturnsDefensiveCopy() {
        val asm = startedAsm()
        fix(asm, 2000L)
        val snap = asm.snapshotSession(3000L)!!
        assertEquals(1, snap.trackPoints.size)
        fix(asm, 4000L)
        fix(asm, 5000L)
        assertEquals("snapshot must not see later mutations", 1, snap.trackPoints.size)
    }

    @Test
    fun snapshotSessionNullWhileIdle() {
        assertNull(ActivitySessionManager().snapshotSession(1000L))
    }
}
