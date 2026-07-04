package com.rokid.hud.phone

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rokid.hud.shared.protocol.Waypoint
import kotlin.math.*

interface NavigationCallback {
    fun onRouteCalculated(waypoints: List<Waypoint>, totalDistance: Double, totalDuration: Double, steps: List<NavigationStep>)
    fun onStepChanged(instruction: String, maneuver: String, distance: Double)
    fun onNavigationError(message: String)
    fun onArrived()
    fun onRerouting()
}

class NavigationManager(private val callback: NavigationCallback) {

    companion object {
        private const val TAG = "NavManager"
        private const val STEP_ADVANCE_RADIUS_M = 150.0
        private const val OFF_ROUTE_RADIUS_M = 80.0
        private const val REROUTE_COOLDOWN_MS = 15000L
        private const val ARRIVAL_RADIUS_M = 30.0
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    var isNavigating = false; private set

    private var destLat = 0.0
    private var destLng = 0.0

    // steps / currentStepIndex / routeWaypoints are written on the calculateRoute Thread{} (and,
    // for the waypoint path, on the caller thread in startNavigationWithRoute) and read on the
    // main-thread onLocationUpdate + HudStreamingService.sendStepsList. @Volatile is the
    // STATE-assigned data-race fix (04-RESEARCH; T-04-09) — it publishes each assignment across
    // threads with happens-before visibility. matches the ActivitySessionManager thread-safety
    // discipline from Phase 1.
    @Volatile
    var steps: List<NavigationStep> = emptyList()
        private set
    @Volatile
    private var routeWaypoints: List<Waypoint> = emptyList()
    @Volatile
    var currentStepIndex = 0
        private set
    private var lastRerouteTime = 0L

    // Follow-route mode (OSRM unavailable / off-road): the route carries ONE synthetic
    // "Follow route" step (Pitfall 1) and onLocationUpdate advances a forward-only pointer toward
    // the next downsampled waypoint instead of maneuver points.
    @Volatile
    private var followRoute = false
    @Volatile
    private var nextWaypointIndex = 0
    // true for a Strava/waypoint route (set by startNavigationWithRoute); the off-route reroute
    // (Task 2) reads this to choose shape-preserving via-routing over the 2-point degrade.
    @Volatile
    private var isWaypointRoute = false

    /** Test-only view of the forward-only follow-route pointer (see NavigationRouteTest). */
    val nextWaypointIndexForTest: Int
        get() = nextWaypointIndex

    val currentInstruction: String
        get() = steps.getOrNull(currentStepIndex)?.instruction ?: ""

    val currentManeuver: String
        get() = steps.getOrNull(currentStepIndex)?.maneuver ?: ""

    val currentStepDistance: Double
        get() = steps.getOrNull(currentStepIndex)?.distance ?: 0.0

    fun startNavigation(destLat: Double, destLng: Double, currentLat: Double, currentLng: Double) {
        this.destLat = destLat
        this.destLng = destLng
        isNavigating = true
        isWaypointRoute = false // destination-only path: off-route reroute stays 2-point (Task 2)
        followRoute = false
        nextWaypointIndex = 0
        calculateRoute(currentLat, currentLng, destLat, destLng)
    }

    /**
     * Waypoint-accepting nav path (NAVV-01): accept a PRE-COMPUTED route + steps and skip the
     * internal OSRM A→B call that [startNavigation] makes. Feeds the SAME
     * [NavigationCallback.onRouteCalculated] the glasses pipeline already consumes, so
     * HudStreamingService.sendRoute + sendStepsList broadcast unchanged.
     *
     * THREADING (T-04-09 race fix): the (steps, currentStepIndex, routeWaypoints, followRoute,
     * nextWaypointIndex) writes happen on the CALLER thread — there is NO Thread{} here — so they
     * happen-before the [mainHandler] post; @Volatile on those fields then guarantees the
     * main-thread onLocationUpdate reader observes them. This is why the field state is fully
     * published before onRouteCalculated fires.
     *
     * @param followRouteMode true when [steps] is the single synthetic "Follow route" step
     *   (OSRM unavailable) — onLocationUpdate then uses forward-only next-waypoint advancement.
     */
    fun startNavigationWithRoute(
        waypoints: List<Waypoint>,
        steps: List<NavigationStep>,
        totalDistance: Double,
        totalDuration: Double,
        followRouteMode: Boolean
    ) {
        isNavigating = true
        isWaypointRoute = true
        followRoute = followRouteMode
        routeWaypoints = waypoints
        this.steps = steps
        currentStepIndex = 0
        nextWaypointIndex = 0
        if (waypoints.isNotEmpty()) {
            destLat = waypoints.last().latitude
            destLng = waypoints.last().longitude
        }
        mainHandler.post {
            callback.onRouteCalculated(waypoints, totalDistance, totalDuration, steps)
            if (steps.isNotEmpty()) {
                callback.onStepChanged(steps[0].instruction, steps[0].maneuver, steps[0].distance)
            }
        }
    }

    fun stopNavigation() {
        isNavigating = false
        steps = emptyList()
        routeWaypoints = emptyList()
        currentStepIndex = 0
        followRoute = false
        nextWaypointIndex = 0
        isWaypointRoute = false
    }

    fun onLocationUpdate(lat: Double, lng: Double) {
        if (!isNavigating || steps.isEmpty()) return

        // Follow-route mode (OSRM unavailable): no maneuver points exist (one synthetic step), so
        // advance a FORWARD-ONLY pointer toward the next downsampled waypoint and re-emit the live
        // distance. Never rewinds (Pitfall 3 butterfly prevention). Arrival = last waypoint reached.
        if (followRoute) {
            onFollowRouteUpdate(lat, lng)
            return
        }

        val distToDest = haversineM(lat, lng, destLat, destLng)
        if (distToDest < ARRIVAL_RADIUS_M && currentStepIndex >= steps.size - 2) {
            isNavigating = false
            mainHandler.post { callback.onArrived() }
            return
        }

        if (currentStepIndex < steps.size - 1) {
            val next = steps[currentStepIndex + 1]
            val distToNext = haversineM(lat, lng, next.locationLat, next.locationLng)
            if (distToNext < STEP_ADVANCE_RADIUS_M) {
                currentStepIndex++
                Log.i(TAG, "Advanced to step $currentStepIndex: ${steps[currentStepIndex].instruction}")
                mainHandler.post {
                    callback.onStepChanged(
                        steps[currentStepIndex].instruction,
                        steps[currentStepIndex].maneuver,
                        steps[currentStepIndex].distance
                    )
                }

                if (currentStepIndex < steps.size - 1) {
                    val afterNext = steps[currentStepIndex + 1]
                    val distAfter = haversineM(lat, lng, afterNext.locationLat, afterNext.locationLng)
                    if (distAfter < STEP_ADVANCE_RADIUS_M) {
                        currentStepIndex++
                        mainHandler.post {
                            callback.onStepChanged(
                                steps[currentStepIndex].instruction,
                                steps[currentStepIndex].maneuver,
                                steps[currentStepIndex].distance
                            )
                        }
                    }
                }
                return
            }
        }

        val nearestDist = nearestRouteDistance(lat, lng)
        if (nearestDist > OFF_ROUTE_RADIUS_M) {
            val now = System.currentTimeMillis()
            if (now - lastRerouteTime > REROUTE_COOLDOWN_MS) {
                lastRerouteTime = now
                Log.i(TAG, "Off route (${nearestDist.toInt()}m), rerouting...")
                mainHandler.post { callback.onRerouting() }
                if (isWaypointRoute) {
                    rerouteThroughRemainingWaypoints(lat, lng)
                } else {
                    // Destination-only navigation (non-Strava): the original 2-point reroute is
                    // correct here — there is no route shape to preserve. UNCHANGED (no regression).
                    calculateRoute(lat, lng, destLat, destLng)
                }
            }
        }
    }

    /**
     * Follow-route advancement (NAVV-02c). Advances [nextWaypointIndex] FORWARD-ONLY toward the
     * next downsampled waypoint (never decrements — Pitfall 3), sets the synthetic step's live
     * distance to the haversine distance to that waypoint, and re-emits onStepChanged so the
     * glasses show a live "Follow route → Nm" readout. Detects arrival at the last waypoint.
     */
    private fun onFollowRouteUpdate(lat: Double, lng: Double) {
        val waypoints = routeWaypoints
        if (waypoints.isEmpty()) return

        // Forward-only pointer: advance while we are within reach of the current target waypoint
        // and more remain. Only increments — a backward GPS jitter can never rewind it.
        while (nextWaypointIndex < waypoints.lastIndex &&
            haversineM(lat, lng, waypoints[nextWaypointIndex].latitude, waypoints[nextWaypointIndex].longitude) < STEP_ADVANCE_RADIUS_M) {
            nextWaypointIndex++
        }

        val target = waypoints[nextWaypointIndex]
        val distToNext = haversineM(lat, lng, target.latitude, target.longitude)

        // Arrival: at (or past) the last waypoint and within the arrival radius.
        if (nextWaypointIndex >= waypoints.lastIndex && distToNext < ARRIVAL_RADIUS_M) {
            isNavigating = false
            mainHandler.post { callback.onArrived() }
            return
        }

        mainHandler.post { callback.onStepChanged("Follow route", "straight", distToNext) }
    }

    private fun calculateRoute(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double) {
        Thread {
            try {
                val result = OsrmClient.getRoute(fromLat, fromLng, toLat, toLng)
                routeWaypoints = result.waypoints
                steps = result.steps
                currentStepIndex = 0

                mainHandler.post {
                    callback.onRouteCalculated(result.waypoints, result.totalDistance, result.totalDuration, result.steps)
                    if (steps.isNotEmpty()) {
                        callback.onStepChanged(steps[0].instruction, steps[0].maneuver, steps[0].distance)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Route calculation failed", e)
                mainHandler.post { callback.onNavigationError(e.message ?: "Route failed") }
            }
        }.start()
    }

    /**
     * Shape-preserving off-route reroute for a WAYPOINT (Strava) route (NAVV-03; T-04-10).
     *
     * CHOSEN STRATEGY (resolves 04-RESEARCH Open Question 3 — the "best fidelity" option):
     * re-run via-point routing from the current position through the REMAINING downsampled
     * waypoints. This keeps the Strava route SHAPE — Pitfall 2 warns that the legacy 2-point
     * `calculateRoute(lat,lng,destLat,destLng)` degrade "throws away the whole route shape",
     * collapsing a curvy loop into a straight-ish A→B for the rest of the ride. We can afford
     * the full via-reroute because [OsrmClient.getRouteVia] already exists this phase (no extra
     * host, no new dependency). The REROUTE_COOLDOWN_MS gate (caller) + the forward-only index
     * reset below prevent butterfly/switchback thrash (Pitfall 3).
     *
     * INDEXING SAFETY (checker WR-1): the reroute slice is taken against [routeWaypoints], NOT
     * [steps]. In routed mode [currentStepIndex] indexes the DIFFERENT-cardinality [steps] list,
     * so using it to slice [routeWaypoints] could IndexOutOfBounds on a switchback. Instead we
     * derive a FRESH nearest-forward waypoint index at reroute time, clamped to
     * routeWaypoints.lastIndex — always a valid slice start. In follow-route mode we take
     * maxOf(nextWaypointIndex, progressIndex) so the pointer stays forward-only (never rewinds).
     *
     * FAILURE PATH (T-04-12): getRouteVia is wrapped in try/catch on the reroute Thread{}; on
     * failure it degrades to [OsrmClient.buildFollowRouteResult] (a non-empty synthetic route),
     * NEVER a 2-point collapse and never a rethrow (CLAUDE.md: never propagate I/O exceptions).
     */
    private fun rerouteThroughRemainingWaypoints(lat: Double, lng: Double) {
        val waypoints = routeWaypoints
        if (waypoints.isEmpty()) {
            // No shape to preserve — fall back to the 2-point reroute toward the stored dest.
            calculateRoute(lat, lng, destLat, destLng)
            return
        }
        // Fresh nearest-forward waypoint, clamped to a valid routeWaypoints index (never uses
        // currentStepIndex / steps — checker WR-1). Guaranteed non-null via elvis on minByOrNull.
        val nearestIndex = (waypoints.indices.minByOrNull {
            haversineM(lat, lng, waypoints[it].latitude, waypoints[it].longitude)
        } ?: 0).coerceIn(0, waypoints.lastIndex)
        // In follow-route mode keep the pointer forward-only across the reroute.
        val progressIndex = if (followRoute) maxOf(nextWaypointIndex, nearestIndex) else nearestIndex
        val remaining = waypoints.subList(progressIndex, waypoints.size)
        val reroutePoints = listOf(Waypoint(latitude = lat, longitude = lng)) + remaining

        Thread {
            try {
                val result = OsrmClient.getRouteVia(reroutePoints)
                // WR-01: publish the 5 interdependent route fields (routeWaypoints, steps,
                // currentStepIndex, nextWaypointIndex, followRoute) on the MAIN looper so
                // they are serialized with the main-thread onLocationUpdate reader — @Volatile
                // gives per-field visibility but NOT atomic group publication, so a GPS fix
                // landing mid-write could observe a torn route (new steps + stale followRoute,
                // etc.). The network getRouteVia call stays on this background Thread; only the
                // field mutation + emission post to main (same pattern startNavigationWithRoute
                // uses). Same forward-only-reset semantics: the new route starts at the current
                // position, so index/pointer reset to 0 relative to it (never a rewind).
                mainHandler.post {
                    routeWaypoints = result.waypoints
                    steps = result.steps
                    currentStepIndex = 0
                    nextWaypointIndex = 0
                    followRoute = false
                    callback.onRouteCalculated(result.waypoints, result.totalDistance, result.totalDuration, result.steps)
                    if (result.steps.isNotEmpty()) {
                        callback.onStepChanged(result.steps[0].instruction, result.steps[0].maneuver, result.steps[0].distance)
                    }
                }
            } catch (e: Exception) {
                // OSRM reroute failed → follow-route degrade (never a 2-point collapse). The
                // synthetic step keeps sendStepsList broadcasting (Pitfall 1).
                Log.w(TAG, "Via reroute failed, falling back to follow-route: ${e.message}")
                val fallback = OsrmClient.buildFollowRouteResult(reroutePoints)
                // WR-01: same atomic-publish-on-main discipline as the success branch — the
                // fallback writes the identical 5 fields read by onLocationUpdate.
                mainHandler.post {
                    routeWaypoints = fallback.waypoints
                    steps = fallback.steps
                    currentStepIndex = 0
                    nextWaypointIndex = 0
                    followRoute = true
                    callback.onRouteCalculated(fallback.waypoints, fallback.totalDistance, fallback.totalDuration, fallback.steps)
                    if (fallback.steps.isNotEmpty()) {
                        callback.onStepChanged(fallback.steps[0].instruction, fallback.steps[0].maneuver, fallback.steps[0].distance)
                    }
                }
            }
        }.start()
    }

    fun getDistanceToNextStep(lat: Double, lng: Double): Double {
        if (!isNavigating || steps.isEmpty()) return -1.0
        val idx = if (currentStepIndex < steps.size - 1) currentStepIndex + 1 else currentStepIndex
        val step = steps[idx]
        return haversineM(lat, lng, step.locationLat, step.locationLng)
    }

    private fun nearestRouteDistance(lat: Double, lng: Double): Double {
        if (routeWaypoints.isEmpty()) return Double.MAX_VALUE
        return routeWaypoints.minOf { wp -> haversineM(lat, lng, wp.latitude, wp.longitude) }
    }

    private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return r * 2 * asin(sqrt(a))
    }
}
