package com.rokid.hud.phone

import android.location.Location
import android.os.SystemClock
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Single source of truth for activity recording: session state machine
 * (IDLE -> TRACKING -> FINISHED), GPS filtering, and live metrics
 * (REC-01..REC-04 + the REC-07 monotonicity contract).
 *
 * Threading model — MAIN-THREAD-CONFINED (no locks):
 * ALL mutators run on the main looper. FusedLocationProvider callbacks are
 * requested on the main looper (HudStreamingService) and UI start/stop calls
 * arrive there too, so [startSession], [stopSession], [resumeFrom],
 * [recordLocation]/[onFix], [pollCheckpoint], and [reset] must only be invoked
 * from the main thread. Everything handed outward ([MetricsSnapshot],
 * [SessionData]) is an immutable copy safe to pass to any thread.
 * [lastFixElapsedRealtimeMs] is the ONLY cross-thread published field — the
 * recording watchdog reads it off-thread for GPS-staleness detection.
 *
 * Emission model — NO listener list here: the service pulls [currentSnapshot]
 * at 1Hz and fans it out (BT sport_state broadcast, UI card, notification).
 * One pipeline keeps saved data equal to broadcast data (ARCHITECTURE
 * anti-pattern 5: exactly one metrics implementation).
 *
 * Time base (RESEARCH Pattern 4): elapsed derives from a monotonic
 * elapsedRealtime anchor captured at start; the wall clock is captured ONCE
 * for the ISO-8601 startTime. Every public entry taking "now" has default
 * parameters so plain-JVM tests inject explicit clocks and never touch
 * SystemClock.
 *
 * This class does no I/O; Log.w is used only for contract violations.
 */
class ActivitySessionManager {

    companion object {
        private const val TAG = "ActivitySession"
        private const val ACCURACY_GATE_M = 20.0
        private const val HYSTERESIS_ENTER_MPS = 0.7
        private const val HYSTERESIS_EXIT_MPS = 0.3
        private const val SPEED_MA_WINDOW = 5
        private const val CHECKPOINT_INTERVAL_MS = 60_000L
        private const val CHECKPOINT_POINT_COUNT = 500
        private const val PACE_MIN_DISTANCE_M = 100.0
    }

    /** Current lifecycle state. Main-thread-confined; consumers use snapshots. */
    var state: SessionState = SessionState.IDLE
        private set

    /**
     * Monotonic timestamp of the most recent fix processed while TRACKING
     * (re-anchored at start/resume so staleness is measured from session
     * begin, not epoch). The ONLY cross-thread published field — the watchdog
     * thread reads it for the >30s GPS-staleness warning (RESEARCH Pitfall 6).
     */
    @Volatile
    var lastFixElapsedRealtimeMs: Long = 0L
        private set

    // --- session identity (main-thread-confined) ---
    private var sessionId: String = ""
    private var sport: String = "ride"
    private var startTimeIso: String = ""
    private var endTimeIso: String? = null

    // --- monotonic time base (RESEARCH Pattern 4) ---
    private var anchorElapsedRealtimeMs: Long = 0L
    private var baseElapsedMs: Long = 0L
    private var frozenElapsedMs: Long = 0L

    // --- metric accumulators ---
    private var movingMs: Long = 0L
    private var distanceM: Double = 0.0
    private var currentSpeedMps: Double = 0.0
    private var moving: Boolean = false
    private var prevFixElapsedRealtimeMs: Long = -1L
    private val speedWindow = ArrayDeque<Double>()
    private var lastAcceptedPoint: TrackPoint? = null

    // --- track buffer (REC-01: every fix while TRACKING, even gate-rejected) ---
    private val trackPoints = ArrayList<TrackPoint>()

    // --- monotonic clamps (REC-07: clamp at the source, never in the codec) ---
    private var maxElapsedMs: Long = 0L
    private var maxDistanceM: Double = 0.0

    // --- checkpoint arming (60s OR 500 points, whichever first) ---
    private var lastCheckpointElapsedMs: Long = 0L
    private var pointsSinceCheckpoint: Int = 0

    /**
     * Begin a new session. Only valid from IDLE (call [reset] after a
     * finished session). Captures the wall clock once for startTime and
     * anchors elapsed on the injected monotonic clock.
     *
     * @return true when the session started, false on contract violation.
     */
    fun startSession(
        sport: String,
        nowElapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
        nowWallMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (state != SessionState.IDLE) {
            Log.w(TAG, "startSession ignored: state is $state (reset() first)")
            return false
        }
        clearSessionFields()
        sessionId = generateId(nowWallMs)
        this.sport = sport
        startTimeIso = Instant.ofEpochMilli(nowWallMs).toString()
        anchorElapsedRealtimeMs = nowElapsedRealtimeMs
        lastFixElapsedRealtimeMs = nowElapsedRealtimeMs
        state = SessionState.TRACKING
        return true
    }

    /**
     * Finish the current session. Freezes elapsed at the injected clock,
     * stamps endTime from the wall clock, and returns the finalized
     * [SessionData] (defensive track copy) — or null if not TRACKING.
     */
    fun stopSession(
        nowElapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
        nowWallMs: Long = System.currentTimeMillis()
    ): SessionData? {
        if (state != SessionState.TRACKING) {
            Log.w(TAG, "stopSession ignored: state is $state")
            return null
        }
        frozenElapsedMs = currentElapsedMs(nowElapsedRealtimeMs)
        endTimeIso = Instant.ofEpochMilli(nowWallMs).toString()
        state = SessionState.FINISHED
        return buildSessionData(frozenElapsedMs, endTimeIso)
    }

    /**
     * Resume a checkpointed session after process death (only from IDLE).
     *
     * Semantics (planner decision): elapsed/moving/distance restore as BASES
     * and re-anchor at [nowElapsedRealtimeMs] — the process-dead gap is
     * EXCLUDED from elapsed and moving time (metrics reflect observed
     * activity). The moving flag resets to false, the speed-MA window clears,
     * and lastAcceptedPoint resets to null so the FIRST accepted post-resume
     * fix starts a fresh distance segment (no phantom teleport distance).
     * [nowWallMs] is unused because startTime is preserved from [data]; the
     * parameter exists for signature symmetry with start/stop.
     */
    fun resumeFrom(
        data: SessionData,
        nowElapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
        nowWallMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (state != SessionState.IDLE) {
            Log.w(TAG, "resumeFrom ignored: state is $state")
            return false
        }
        clearSessionFields()
        sessionId = data.id
        sport = data.sport
        startTimeIso = data.startTime
        anchorElapsedRealtimeMs = nowElapsedRealtimeMs
        baseElapsedMs = data.elapsedMs
        movingMs = data.movingMs
        distanceM = data.distanceM
        trackPoints.addAll(data.trackPoints)
        maxElapsedMs = data.elapsedMs
        maxDistanceM = data.distanceM
        lastCheckpointElapsedMs = data.elapsedMs
        lastFixElapsedRealtimeMs = nowElapsedRealtimeMs
        state = SessionState.TRACKING
        return true
    }

    /** Return to IDLE after FINISHED, clearing all session state. Refused while TRACKING. */
    fun reset() {
        if (state == SessionState.TRACKING) {
            Log.w(TAG, "reset ignored while TRACKING — stopSession first")
            return
        }
        clearSessionFields()
        state = SessionState.IDLE
    }

    /**
     * Main-source adapter: maps a platform [Location] to primitive [onFix]
     * parameters (NaN / -1.0 sentinels for absent fields) and stamps the
     * monotonic clock. Contains ZERO logic — everything lives in [onFix] so
     * plain-JVM tests never need android.location.Location.
     */
    fun recordLocation(loc: Location) = onFix(
        lat = loc.latitude,
        lng = loc.longitude,
        alt = if (loc.hasAltitude()) loc.altitude else Double.NaN,
        ts = loc.time,
        speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else Double.NaN,
        accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else -1.0,
        bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else Double.NaN,
        elapsedRealtimeMs = SystemClock.elapsedRealtime()
    )

    /**
     * Process one GPS fix (all recording logic lives here; primitive
     * parameters keep it JVM-testable). No-op unless TRACKING. Every fix is
     * appended to the track log regardless of accuracy (REC-03 logs
     * everything); accuracy only gates DISTANCE accumulation.
     */
    internal fun onFix(
        lat: Double,
        lng: Double,
        alt: Double,
        ts: Long,
        speedMps: Double,
        accuracyM: Double,
        bearingDeg: Double,
        elapsedRealtimeMs: Long
    ) {
        if (state != SessionState.TRACKING) return

        // (1) append unconditionally — REC-03: log everything
        trackPoints.add(TrackPoint(lat, lng, alt, ts, speedMps, accuracyM, bearingDeg))
        pointsSinceCheckpoint++

        // (2) staleness anchor for the watchdog (only cross-thread field)
        lastFixElapsedRealtimeMs = elapsedRealtimeMs

        // (3)-(10) filtering pipeline: accuracy gate, speed MA, hysteresis,
        // haversine distance, Doppler display speed — implemented in Task 2.

        prevFixElapsedRealtimeMs = elapsedRealtimeMs
    }

    /**
     * Build the immutable live-metrics snapshot the service pulls at 1Hz.
     * Elapsed and distance are clamped monotonic non-decreasing at this
     * source (REC-07) — never in the codec.
     */
    fun currentSnapshot(nowElapsedRealtimeMs: Long = SystemClock.elapsedRealtime()): MetricsSnapshot {
        val elapsed = currentElapsedMs(nowElapsedRealtimeMs)
        val dist = clampedDistanceM()
        return MetricsSnapshot(
            elapsedMs = elapsed,
            movingMs = movingMs,
            distanceM = dist,
            currentSpeedMps = currentSpeedMps,
            avgPaceMsPerKm = avgPaceMsPerKm(dist),
            sessionState = stateString(),
            sport = sport,
            trackPointCount = trackPoints.size
        )
    }

    /**
     * Assemble the full [SessionData] (checkpoint or final). The trackPoints
     * list is a defensive copy — later mutations never leak into it. Returns
     * null while IDLE. [endTimeIso] overrides the stored end time (which is
     * null until [stopSession]).
     */
    fun snapshotSession(
        nowElapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
        endTimeIso: String? = null
    ): SessionData? {
        if (state == SessionState.IDLE) {
            Log.w(TAG, "snapshotSession ignored: no session")
            return null
        }
        return buildSessionData(currentElapsedMs(nowElapsedRealtimeMs), endTimeIso ?: this.endTimeIso)
    }

    /**
     * Checkpoint trigger, called by the service ticker at 1Hz: returns a
     * [SessionData] once 60s elapsed OR 500 points appended since the last
     * checkpoint (whichever first), then re-arms; otherwise null.
     */
    fun pollCheckpoint(nowElapsedRealtimeMs: Long = SystemClock.elapsedRealtime()): SessionData? {
        if (state != SessionState.TRACKING) return null
        val elapsed = currentElapsedMs(nowElapsedRealtimeMs)
        if (elapsed - lastCheckpointElapsedMs >= CHECKPOINT_INTERVAL_MS ||
            pointsSinceCheckpoint >= CHECKPOINT_POINT_COUNT
        ) {
            lastCheckpointElapsedMs = elapsed
            pointsSinceCheckpoint = 0
            return buildSessionData(elapsed, null)
        }
        return null
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /** Elapsed for the current state, clamped monotonic non-decreasing. */
    private fun currentElapsedMs(nowElapsedRealtimeMs: Long): Long {
        val raw = when (state) {
            SessionState.IDLE -> 0L
            SessionState.TRACKING -> baseElapsedMs + (nowElapsedRealtimeMs - anchorElapsedRealtimeMs)
            SessionState.FINISHED -> frozenElapsedMs
        }
        val clamped = max(raw, maxElapsedMs)
        maxElapsedMs = clamped
        return clamped
    }

    private fun clampedDistanceM(): Double {
        val clamped = max(distanceM, maxDistanceM)
        maxDistanceM = clamped
        return clamped
    }

    private fun avgPaceMsPerKm(dist: Double): Long =
        if (dist < PACE_MIN_DISTANCE_M) 0L else (movingMs / (dist / 1000.0)).roundToLong()

    private fun stateString(): String = when (state) {
        SessionState.IDLE -> "idle"
        SessionState.TRACKING -> "tracking"
        SessionState.FINISHED -> "finished"
    }

    private fun buildSessionData(elapsedMs: Long, endTime: String?): SessionData {
        val dist = clampedDistanceM()
        return SessionData(
            id = sessionId,
            sport = sport,
            startTime = startTimeIso,
            endTime = endTime,
            elapsedMs = elapsedMs,
            movingMs = movingMs,
            distanceM = dist,
            avgSpeedMps = if (movingMs == 0L) 0.0 else dist / (movingMs / 1000.0),
            stravaUploaded = false,
            trackPoints = ArrayList(trackPoints)
        )
    }

    /** `{yyyyMMdd-HHmmss}-{first 8 UUID chars}` (system zone stamp, GPX-friendly). */
    private fun generateId(nowWallMs: Long): String {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(nowWallMs))
        return "$stamp-${UUID.randomUUID().toString().substring(0, 8)}"
    }

    private fun clearSessionFields() {
        sessionId = ""
        sport = "ride"
        startTimeIso = ""
        endTimeIso = null
        anchorElapsedRealtimeMs = 0L
        baseElapsedMs = 0L
        frozenElapsedMs = 0L
        movingMs = 0L
        distanceM = 0.0
        currentSpeedMps = 0.0
        moving = false
        prevFixElapsedRealtimeMs = -1L
        speedWindow.clear()
        lastAcceptedPoint = null
        trackPoints.clear()
        maxElapsedMs = 0L
        maxDistanceM = 0.0
        lastCheckpointElapsedMs = 0L
        pointsSinceCheckpoint = 0
    }
}
