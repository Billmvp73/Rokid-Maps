package com.rokid.hud.phone

/**
 * Recording data contracts for the activity recording engine.
 *
 * This file is PURE Kotlin (no android.* imports) so plain-JVM unit tests can
 * use these types directly. ActivitySessionManager produces them, SessionStore
 * persists them, and HudStreamingService / MainActivity consume them.
 */

/**
 * Recording session state machine.
 *
 * Owner: ActivitySessionManager (single source of truth for transitions).
 * v1 is IDLE -> TRACKING -> FINISHED only; a pause state is deferred to v2.
 */
enum class SessionState {
    IDLE,
    TRACKING,
    FINISHED
}

/**
 * One accepted GPS fix in a recording session.
 *
 * Owner: ActivitySessionManager appends accepted fixes; SessionStore persists
 * them into the session JSON `trackPoints[]` array.
 *
 * Sentinel conventions:
 * - [alt], [speedMps], [bearingDeg] are [Double.NaN] when the fix lacks them
 * - [accuracyM] is -1.0 when unknown
 * - [ts] is epoch milliseconds from location.time (wall clock, GPX-ready)
 *
 * bearingDeg is additive beyond the locked CONTEXT schema list because REC-01
 * explicitly requires bearing per track point.
 */
data class TrackPoint(
    val lat: Double,
    val lng: Double,
    val alt: Double,
    val ts: Long,
    val speedMps: Double,
    val accuracyM: Double,
    val bearingDeg: Double
)

/**
 * Immutable live-metrics snapshot handed across threads at ~1Hz.
 *
 * Owner: ActivitySessionManager computes and publishes it wholesale (same
 * copy-on-publish philosophy as the glasses HudState); the service broadcasts
 * it as sport_state and the UI renders it. Never mutated after construction.
 *
 * [sessionState] is "idle" | "tracking" | "finished"; [sport] is "ride" | "run".
 */
data class MetricsSnapshot(
    val elapsedMs: Long,
    val movingMs: Long,
    val distanceM: Double,
    val currentSpeedMps: Double,
    val avgPaceMsPerKm: Long,
    val sessionState: String,
    val sport: String,
    val trackPointCount: Int
)

/**
 * Callback for live metrics updates.
 *
 * Owner: ActivitySessionManager invokes registered listeners with each new
 * [MetricsSnapshot]; HudStreamingService (BT broadcast) and MainActivity (UI)
 * implement it, following the NavigationCallback convention.
 */
interface MetricsListener {
    fun onMetrics(snapshot: MetricsSnapshot)
}

/**
 * A complete recorded session — the locked v1 session schema.
 *
 * Owner: ActivitySessionManager assembles it; SessionStore serializes it to
 * `{yyyyMMdd-HHmmss}-{shortUuid}.json` under filesDir/activities/.
 *
 * [startTime]/[endTime] are ISO-8601 UTC strings; [endTime] is null while the
 * session is in progress (checkpoint writes). schemaVersion is intentionally
 * NOT a field here — it is a JSON-layer constant written by SessionStore.
 */
data class SessionData(
    val id: String,
    val sport: String,
    val startTime: String,
    val endTime: String?,
    val elapsedMs: Long,
    val movingMs: Long,
    val distanceM: Double,
    val avgSpeedMps: Double,
    val stravaUploaded: Boolean = false,
    val trackPoints: List<TrackPoint>
)
