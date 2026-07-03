package com.rokid.hud.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

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

    // ------------------------------------------------------------------
    // Task 2: filtering pipeline — accuracy gate, speed MA, hysteresis,
    // haversine distance, pace (REC-02/REC-03/REC-04)
    // ------------------------------------------------------------------

    /** Meters per 0.0001 deg of latitude on the r=6371000 haversine sphere. */
    private val mPerLatStep = 11.1195

    /**
     * Scenario builder: feed [count] fixes at [stepMs] cadence, advancing
     * [latStepDeg] northward per fix, with per-fix speed from [speedAt].
     * Returns the timestamp of the tick AFTER the last fix.
     */
    private fun driveScenario(
        asm: ActivitySessionManager,
        count: Int,
        startAtMs: Long = 2000L,
        stepMs: Long = 1000L,
        latStepDeg: Double = 0.0,
        accuracyM: Double = 5.0,
        speedAt: (Int) -> Double
    ): Long {
        var t = startAtMs
        var lat = LAT0
        for (i in 0 until count) {
            fix(asm, t, lat = lat, speedMps = speedAt(i), accuracyM = accuracyM)
            t += stepMs
            lat += latStepDeg
        }
        return t
    }

    @Test
    fun accuracyExactly20IsAcceptedForDistance() {
        val asm = startedAsm()
        fix(asm, 2000L, speedMps = 5.0, accuracyM = 5.0)
        // REC-03 excludes only ">20m" — exactly 20.0 is an inclusive accept
        fix(asm, 3000L, lat = LAT0 + 0.0001, speedMps = 5.0, accuracyM = 20.0)
        val d = asm.currentSnapshot(3500L).distanceM
        assertEquals(mPerLatStep, d, mPerLatStep * 0.05)
    }

    @Test
    fun accuracyJustOver20AddsNoDistanceButIsLogged() {
        val asm = startedAsm()
        fix(asm, 2000L, speedMps = 5.0, accuracyM = 5.0)
        fix(asm, 3000L, lat = LAT0 + 0.0001, speedMps = 5.0, accuracyM = 20.1)
        assertEquals(0.0, asm.currentSnapshot(3500L).distanceM, 1e-9)
        // the rejected fix must NOT become the last accepted point: the next
        // accepted fix bridges from the FIRST point (2 lat steps, ~22.2m)
        fix(asm, 4000L, lat = LAT0 + 0.0002, speedMps = 5.0, accuracyM = 5.0)
        val snap = asm.currentSnapshot(4500L)
        assertEquals(2 * mPerLatStep, snap.distanceM, 2 * mPerLatStep * 0.05)
        assertEquals("rejected fixes still logged", 3, snap.trackPointCount)
    }

    @Test
    fun unknownAndZeroAccuracyAddNoDistanceButAreLogged() {
        val asm = startedAsm()
        fix(asm, 2000L, speedMps = 5.0, accuracyM = 5.0)
        fix(asm, 3000L, lat = LAT0 + 0.0001, speedMps = 5.0, accuracyM = -1.0)
        fix(asm, 4000L, lat = LAT0 + 0.0002, speedMps = 5.0, accuracyM = 0.0)
        val snap = asm.currentSnapshot(4500L)
        assertEquals(0.0, snap.distanceM, 1e-9)
        assertEquals(3, snap.trackPointCount)
    }

    @Test
    fun nanSpeedDoesNotEnterWindowOrChangeFlag() {
        val asm = startedAsm()
        // NaN speeds only: window stays empty, flag stays down
        fix(asm, 2000L, speedMps = Double.NaN)
        fix(asm, 3000L, speedMps = Double.NaN)
        assertEquals(0L, asm.currentSnapshot(3500L).movingMs)
        // one valid speed enters moving (window [5.0], MA 5.0 > 0.7)
        fix(asm, 4000L, speedMps = 5.0)
        val m1 = asm.currentSnapshot(4500L).movingMs
        // NaN fix while moving: window/MA unchanged, flag holds, time accrues
        fix(asm, 5000L, speedMps = Double.NaN)
        assertEquals(m1 + 1000L, asm.currentSnapshot(5500L).movingMs)
    }

    @Test
    fun speedWindowHoldsExactlyFiveValidSpeeds() {
        val asm = startedAsm()
        // 4 zero-speed fixes, then one 5.0: window [0,0,0,0,5] -> MA 1.0 -> enter
        for (i in 1..4) fix(asm, 1000L + i * 1000L, speedMps = 0.0)
        fix(asm, 6000L, speedMps = 5.0)
        // five more zeros: MA stays 1.0 while the 5.0 remains in one of the 5
        // slots; only the 5th zero flushes it (MA 0.0 -> exit). movingMs of
        // exactly 5000 proves the window holds exactly 5 valid speeds.
        for (i in 1..5) fix(asm, 6000L + i * 1000L, speedMps = 0.0)
        assertEquals(5000L, asm.currentSnapshot(11_500L).movingMs)
    }

    @Test
    fun hysteresisEnterBoundaryIsExclusive() {
        // MA exactly 0.7 must NOT enter moving (REC-04: "above 0.7")
        val asm = startedAsm()
        fix(asm, 2000L, speedMps = 0.7)
        fix(asm, 3000L, speedMps = 0.7)
        assertEquals(0L, asm.currentSnapshot(3500L).movingMs)
        // MA 0.71 enters
        val asm2 = startedAsm()
        fix(asm2, 2000L, speedMps = 0.71)
        fix(asm2, 3000L, speedMps = 0.71)
        assertEquals(1000L, asm2.currentSnapshot(3500L).movingMs)
    }

    @Test
    fun hysteresisExitBoundaryIsExclusive() {
        val asm = startedAsm()
        fix(asm, 2000L, speedMps = 5.0)   // enter moving
        // five 0.3 fixes flush the window to [0.3 x5] -> MA exactly 0.3,
        // which must NOT exit (REC-04: "below 0.3")
        for (i in 1..5) fix(asm, 2000L + i * 1000L, speedMps = 0.3)
        val mHeld = asm.currentSnapshot(7500L).movingMs
        fix(asm, 8000L, speedMps = 0.3)   // still MA 0.3 -> still moving
        assertEquals(mHeld + 1000L, asm.currentSnapshot(8500L).movingMs)
        // 0.29 pulls the MA below 0.3 -> exit; moving time stops accruing
        for (i in 1..5) fix(asm, 8000L + i * 1000L, speedMps = 0.29)
        val mExit = asm.currentSnapshot(13_500L).movingMs
        fix(asm, 14_000L, speedMps = 0.29)
        assertEquals("no moving time once exited", mExit, asm.currentSnapshot(14_500L).movingMs)
        assertEquals("held while MA == 0.3, stopped after", mHeld + 1000L, mExit)
    }

    @Test
    fun oneFlagGatesBothMovingTimeAndDistance() {
        val asm = startedAsm()
        // accurate fixes advancing position, but speed MA (0.5) never crosses
        // 0.7: NEITHER moving time NOR distance may accumulate (one flag, REC-04)
        driveScenario(asm, count = 10, latStepDeg = 0.0001) { 0.5 }
        val snap = asm.currentSnapshot(13_000L)
        assertEquals(0L, snap.movingMs)
        assertEquals(0.0, snap.distanceM, 1e-9)
        assertEquals(10, snap.trackPointCount)
    }

    @Test
    fun stationaryDriftAccumulatesZeroDistance() {
        val asm = startedAsm()
        // ~20 fixes jittering around a point at 5m accuracy with speeds
        // oscillating 0.0–0.4 m/s: classic parked-at-a-cafe GPS drift (R5)
        val jitterLat = listOf(0.0001, -0.0001, 0.00005, -0.00008, 0.0)
        val jitterLng = listOf(-0.00006, 0.0001, 0.0, -0.0001, 0.00007)
        val speeds = listOf(0.0, 0.2, 0.4, 0.1, 0.3)
        var t = 2000L
        for (i in 0 until 20) {
            fix(
                asm, t,
                lat = LAT0 + jitterLat[i % 5],
                lng = LNG0 + jitterLng[i % 5],
                speedMps = speeds[i % 5],
                accuracyM = 5.0
            )
            t += 1000L
        }
        val snap = asm.currentSnapshot(t)
        assertEquals("phantom drift distance (R5)", 0.0, snap.distanceM, 1e-9)
        assertEquals(0L, snap.movingMs)
        assertEquals(20, snap.trackPointCount)
    }

    @Test
    fun movingScenarioAccumulatesHaversineDistanceAndMovingTime() {
        val asm = startedAsm(startNow = 1000L)
        // 12 fixes advancing 0.0001 deg latitude per second at 5 m/s
        val endT = driveScenario(asm, count = 12, latStepDeg = 0.0001) { 5.0 }
        val snap = asm.currentSnapshot(endT)
        // 11 accepted consecutive segments of ~11.12m each
        val expected = 11 * mPerLatStep
        assertEquals(expected, snap.distanceM, expected * 0.05)
        // flag entered on fix 1 (no prev fix): deltas accrue from fix 2..12
        assertEquals(11_000L, snap.movingMs)
        // avg speed on stop = distance / moving seconds
        val data = asm.stopSession(endT, WALL_T0 + endT)!!
        assertEquals(data.distanceM / 11.0, data.avgSpeedMps, 1e-6)
    }

    @Test
    fun firstAcceptedFixAddsNoDistance() {
        val asm = startedAsm()
        fix(asm, 2000L, speedMps = 5.0, accuracyM = 5.0)
        assertEquals(0.0, asm.currentSnapshot(2500L).distanceM, 1e-9)
    }

    @Test
    fun currentSpeedIsRawDopplerNotMovingAverage() {
        val asm = startedAsm()
        for (i in 1..4) fix(asm, 1000L + i * 1000L, speedMps = 5.0)
        fix(asm, 6000L, speedMps = 1.0)   // window MA 4.2; raw Doppler 1.0
        assertEquals(1.0, asm.currentSnapshot(6500L).currentSpeedMps, 1e-9)
        // NaN raw speed displays as 0.0
        fix(asm, 7000L, speedMps = Double.NaN)
        assertEquals(0.0, asm.currentSnapshot(7500L).currentSpeedMps, 1e-9)
    }

    @Test
    fun avgPaceFloorsAt100Meters() {
        val asm = startedAsm()
        // 6 fixes -> 5 segments (~55.6m): below the floor, pace must be 0
        val midT = driveScenario(asm, count = 6, latStepDeg = 0.0001) { 5.0 }
        assertEquals(0L, asm.currentSnapshot(midT).avgPaceMsPerKm)
        // continue past 100m: pace = movingMs / (distanceM / 1000), rounded
        var t = midT
        var lat = LAT0 + 6 * 0.0001
        for (i in 0 until 6) {
            fix(asm, t, lat = lat, speedMps = 5.0, accuracyM = 5.0)
            t += 1000L
            lat += 0.0001
        }
        val snap = asm.currentSnapshot(t)
        assertTrue("expected >100m, got ${snap.distanceM}", snap.distanceM > 100.0)
        assertEquals((snap.movingMs / (snap.distanceM / 1000.0)).roundToLong(), snap.avgPaceMsPerKm)
    }

    @Test
    fun distanceAndElapsedMonotonicAcrossMixedScenario() {
        val asm = startedAsm(startNow = 1000L)
        val speeds = listOf(5.0, Double.NaN, 0.2, 5.0, 0.29, 5.0, 0.0, 5.0)
        val accs = listOf(5.0, 25.0, 20.0, -1.0, 0.0, 15.0, 20.1, 5.0)
        var t = 2000L
        var lat = LAT0
        var prevD = 0.0
        var prevE = 0L
        for (i in 0 until 40) {
            fix(asm, t, lat = lat, speedMps = speeds[i % speeds.size], accuracyM = accs[i % accs.size])
            val snap = asm.currentSnapshot(t + 500L)
            assertTrue("distance decreased at fix $i", snap.distanceM >= prevD)
            assertTrue("elapsed decreased at fix $i", snap.elapsedMs >= prevE)
            prevD = snap.distanceM
            prevE = snap.elapsedMs
            t += 1000L
            lat += 0.0001
        }
        assertTrue("mixed scenario should accumulate some distance", prevD > 0.0)
    }
}
