package com.rokid.hud.phone

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rokid.hud.shared.protocol.Waypoint
import kotlin.math.*

interface NavigationCallback {
    /**
     * @param full true only on the FIRST route broadcast of a navigation start (destination or
     *   imported-route path); false on every reroute rebroadcast. Threaded to the glasses so a
     *   full=true route is preserved as the WHOLE_ROUTE birdview source (D4). UI overriders may
     *   ignore it.
     */
    fun onRouteCalculated(waypoints: List<Waypoint>, totalDistance: Double, totalDuration: Double, steps: List<NavigationStep>, full: Boolean)
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
        // Cap on the remaining-waypoint slice fed to the OSRM via-reroute URL. A long imported
        // Strava route can leave ~200 waypoints ahead; a mid-ride reroute through all of them
        // builds an over-long GET URL that can fail. capRerouteWaypoints() even-stride downsamples
        // the remaining slice to at most this many points (first + last always kept).
        private const val MAX_REROUTE_WAYPOINTS = 25
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
    // Independent cooldown for the "Head to route" approach emit (Fix 3). Kept SEPARATE from
    // lastRerouteTime so (a) throttling the approach readout never advances the real-reroute
    // cooldown clock — a genuine reroute right after a join must not be suppressed by a recent
    // approach emit — and (b) lastRerouteTime stays a clean synchronous witness of REAL reroute
    // dispatch only (it is 0 during pure approach, which the tests rely on).
    private var lastApproachEmitTime = 0L

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
    // Latches true once the rider comes within OFF_ROUTE_RADIUS_M of any imported route waypoint.
    // It gates the off-route reroute so a start-of-nav "approaching the route from home" position
    // (never yet on the route) is never mistaken for a mid-ride deviation — while !hasBeenOnRoute
    // the manager only emits a "Head to route" readout instead of rerouting through ~200 waypoints.
    @Volatile
    private var hasBeenOnRoute = false

    /** Test-only view of the forward-only follow-route pointer (see NavigationRouteTest). */
    val nextWaypointIndexForTest: Int
        get() = nextWaypointIndex

    /** Test-only view of the join latch — see NavigationRouteTest (posts are no-ops on plain JVM). */
    val hasBeenOnRouteForTest: Boolean
        get() = hasBeenOnRoute

    /**
     * Test-only view of the real-reroute timestamp. Advances synchronously on the caller thread
     * ONLY when a genuine reroute is dispatched, so a test can witness the reroute DECISION
     * (0L = no dispatch, >0L = dispatched) without a Looper. The approach emit uses a separate
     * timestamp and never advances this.
     */
    val lastRerouteTimeForTest: Long
        get() = lastRerouteTime

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
        hasBeenOnRoute = false // new nav start: treat as "not yet on route" until a fix joins it
        // full = true: FIRST broadcast of this nav start marks the preserved original route (D4).
        calculateRoute(currentLat, currentLng, destLat, destLng, full = true)
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
     * FULL FLAG (D4): this is a navigation START, so onRouteCalculated fires with full = true —
     * the glasses preserve this ORIGINAL imported route as the WHOLE_ROUTE birdview source; later
     * reroute rebroadcasts pass full = false so they never clobber it.
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
        hasBeenOnRoute = false // new nav start: reroute is deferred until the rider joins the route
        if (waypoints.isNotEmpty()) {
            destLat = waypoints.last().latitude
            destLng = waypoints.last().longitude
        }
        mainHandler.post {
            callback.onRouteCalculated(waypoints, totalDistance, totalDuration, steps, full = true)
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
        hasBeenOnRoute = false
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

        // Single nearest-waypoint distance, reused for BOTH the join latch (Fix 2) and the
        // off-route decision (Fix 3) — do NOT recompute nearestRouteDistance below.
        val nearestDist = nearestRouteDistance(lat, lng)

        // Fix 2 — join latch (waypoint routes only): the first fix that comes within
        // OFF_ROUTE_RADIUS_M of any route waypoint flips hasBeenOnRoute true. The !hasBeenOnRoute
        // guard makes this fire exactly once, on the transition. Approach fixes are always far
        // from maneuver points (>STEP_ADVANCE_RADIUS_M), so onLocationUpdate always reaches here
        // without the arrival/step-advance early-returns swallowing the fix that finally joins.
        if (isWaypointRoute && !hasBeenOnRoute && nearestDist <= OFF_ROUTE_RADIUS_M) {
            hasBeenOnRoute = true
            Log.i(TAG, "Joined route (nearest ${nearestDist.toInt()}m)")
        }

        if (nearestDist > OFF_ROUTE_RADIUS_M) {
            // Fix 3 — approach vs. reroute. A waypoint route the rider has NOT yet joined is
            // "heading to the route from home", not a mid-ride deviation: do not reroute, do not
            // enter follow-route, do not spawn a Thread, do not call OSRM — keep the imported
            // routeWaypoints + steps + followRoute untouched and just show "Head to route → {dist}".
            // A non-waypoint (destination-only) route can never enter this branch — isWaypointRoute
            // is false there — so its behavior is byte-for-byte unchanged.
            if (isWaypointRoute && !hasBeenOnRoute) {
                val now = System.currentTimeMillis()
                // Gate on an INDEPENDENT approach cooldown so the emit is not chatty AND so it
                // never advances lastRerouteTime (which must stay 0 until a real reroute — see the
                // lastApproachEmitTime comment / NavigationRouteTest).
                if (now - lastApproachEmitTime > REROUTE_COOLDOWN_MS) {
                    lastApproachEmitTime = now
                    Log.i(TAG, "Approaching route (${nearestDist.toInt()}m), not rerouting")
                    mainHandler.post { callback.onStepChanged("Head to route", "straight", nearestDist) }
                }
            } else {
                // Genuine deviation: the rider joined the route and drifted off (hasBeenOnRoute),
                // OR this is a destination-only route. Existing behavior EXACTLY, unchanged.
                val now = System.currentTimeMillis()
                if (now - lastRerouteTime > REROUTE_COOLDOWN_MS) {
                    lastRerouteTime = now
                    Log.i(TAG, "Off route (${nearestDist.toInt()}m), rerouting...")
                    mainHandler.post { callback.onRerouting() }
                    if (isWaypointRoute) {
                        rerouteThroughRemainingWaypoints(lat, lng)
                    } else {
                        // Destination-only navigation (non-Strava): the original 2-point reroute is
                        // correct here — there is no route shape to preserve. full = false: a reroute
                        // must never overwrite the glasses birdview source (D4).
                        calculateRoute(lat, lng, destLat, destLng, full = false)
                    }
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

    private fun calculateRoute(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, full: Boolean) {
        Thread {
            try {
                val result = OsrmClient.getRoute(fromLat, fromLng, toLat, toLng)
                routeWaypoints = result.waypoints
                steps = result.steps
                currentStepIndex = 0

                mainHandler.post {
                    callback.onRouteCalculated(result.waypoints, result.totalDistance, result.totalDuration, result.steps, full)
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
            // full = false: reroute degrade never overwrites the glasses birdview source (D4).
            calculateRoute(lat, lng, destLat, destLng, full = false)
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
        // Fix 4: cap the remaining slice (first + last kept) so the OSRM via GET URL stays short —
        // a mid-ride reroute through ~200 waypoints can otherwise build an over-long URL that fails.
        val cappedRemaining = capRerouteWaypoints(remaining)
        val reroutePoints = listOf(Waypoint(latitude = lat, longitude = lng)) + cappedRemaining

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
                    // full = false: this is a reroute — do not clobber the birdview source (D4).
                    callback.onRouteCalculated(result.waypoints, result.totalDistance, result.totalDuration, result.steps, full = false)
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
                    // full = false: follow-route reroute degrade — birdview source unchanged (D4).
                    callback.onRouteCalculated(fallback.waypoints, fallback.totalDistance, fallback.totalDuration, fallback.steps, full = false)
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

    /**
     * Pure, deterministic cap on a remaining-waypoint slice for the OSRM via-reroute URL (Fix 4).
     * Returns [list] unchanged when its size is already <= [MAX_REROUTE_WAYPOINTS]; otherwise
     * even-stride downsamples to at most MAX_REROUTE_WAYPOINTS points, ALWAYS keeping the first
     * and last waypoint. Guarantees on any input:
     *   result.size <= MAX_REROUTE_WAYPOINTS
     *   result.first() == list.first()  (when list is non-empty)
     *   result.last()  == list.last()   (when list is non-empty)
     *
     * Even-stride (not Douglas-Peucker) is deliberate here: this is a reliability cap on an
     * already-downsampled slice, and an evenly-spaced pick gives a trivially-asserted size bound.
     * No field reads beyond the const, no Android, no network — kept JVM-unit-testable.
     */
    internal fun capRerouteWaypoints(list: List<Waypoint>): List<Waypoint> {
        if (list.size <= MAX_REROUTE_WAYPOINTS) return list
        val lastIndex = list.size - 1
        // Pick MAX_REROUTE_WAYPOINTS evenly-spaced indices across [0, lastIndex] inclusive:
        // i=0 -> 0 and i=MAX-1 -> lastIndex, so first + last are always included. LinkedHashSet
        // preserves ascending order and dedupes any rounding collisions (result stays <= MAX).
        val picked = LinkedHashSet<Int>()
        for (i in 0 until MAX_REROUTE_WAYPOINTS) {
            val idx = Math.round(i.toDouble() * lastIndex / (MAX_REROUTE_WAYPOINTS - 1)).toInt()
            picked.add(idx)
        }
        return picked.map { list[it] }
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
