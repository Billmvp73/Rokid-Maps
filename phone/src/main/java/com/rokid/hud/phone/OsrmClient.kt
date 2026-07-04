package com.rokid.hud.phone

import com.rokid.hud.shared.protocol.Waypoint
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class NavigationStep(
    val instruction: String,
    val maneuver: String,
    val distance: Double,
    val duration: Double,
    val locationLat: Double,
    val locationLng: Double
)

data class RouteResult(
    val waypoints: List<Waypoint>,
    val steps: List<NavigationStep>,
    val totalDistance: Double,
    val totalDuration: Double
)

object OsrmClient {

    private const val BASE_URL = "https://router.project-osrm.org/route/v1/driving"
    private const val USER_AGENT = "RokidHudMaps/1.0"

    fun getRoute(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): RouteResult {
        val url = URL("$BASE_URL/$fromLng,$fromLat;$toLng,$toLat?overview=full&geometries=geojson&steps=true")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        return try {
            val body = conn.inputStream.bufferedReader().readText()
            parseRouteBody(body)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Multi-waypoint via-point route. BLOCKING (call from a `Thread{}` only — the OsrmClient
     * convention; matches getRoute). Builds the URL with [buildViaUrl] (`waypoints=0;{last}`
     * single leg), performs the same HttpURLConnection GET as [getRoute], reuses the shared
     * [parseRouteBody] parse, then applies [filterArriveSteps] as belt-and-braces against any
     * host that ignores the waypoints param.
     *
     * Throws on OSRM error / non-200 / parse failure (like getRoute). The follow-route fallback
     * is the CALLER's responsibility (Plan 03 NavigationManager / Plan 05 importer) via
     * try/catch → [buildFollowRouteResult] — keeping this a pure "route or throw".
     *
     * URL size: ~4KB at 200 coords (VERIFIED HTTP 200 <1s; the upstream ≤200 downsample cap
     * keeps a 2.5x margin under the ~500-coord host ceiling). If a future >~500-coord request
     * is rejected, the caller must reduce the via-point count.
     */
    fun getRouteVia(points: List<Waypoint>): RouteResult {
        val url = URL(buildViaUrl(points))
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        return try {
            val body = conn.inputStream.bufferedReader().readText()
            val parsed = parseRouteBody(body)
            parsed.copy(steps = filterArriveSteps(parsed.steps))
        } finally {
            conn.disconnect()
        }
    }

    private fun parseRouteBody(body: String): RouteResult {
        val json = JSONObject(body)
        if (json.getString("code") != "Ok") {
            throw RuntimeException("OSRM error: ${json.optString("message", json.getString("code"))}")
        }

        val route = json.getJSONArray("routes").getJSONObject(0)
        val totalDistance = route.getDouble("distance")
        val totalDuration = route.getDouble("duration")

        val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
        val waypoints = mutableListOf<Waypoint>()
        val stride = maxOf(1, coords.length() / 500)
        for (i in 0 until coords.length() step stride) {
            val c = coords.getJSONArray(i)
            waypoints.add(Waypoint(latitude = c.getDouble(1), longitude = c.getDouble(0)))
        }
        if (waypoints.size > 1) {
            val last = coords.getJSONArray(coords.length() - 1)
            val lastWp = Waypoint(latitude = last.getDouble(1), longitude = last.getDouble(0))
            if (waypoints.last() != lastWp) waypoints.add(lastWp)
        }

        val steps = mutableListOf<NavigationStep>()
        val legs = route.getJSONArray("legs")
        for (li in 0 until legs.length()) {
            val legSteps = legs.getJSONObject(li).getJSONArray("steps")
            for (si in 0 until legSteps.length()) {
                val s = legSteps.getJSONObject(si)
                val m = s.getJSONObject("maneuver")
                val loc = m.getJSONArray("location")
                val type = m.getString("type")
                val modifier = m.optString("modifier", "")
                val name = s.optString("name", "")

                steps.add(NavigationStep(
                    instruction = buildInstruction(type, modifier, name),
                    maneuver = toManeuverKey(type, modifier),
                    distance = s.getDouble("distance"),
                    duration = s.getDouble("duration"),
                    locationLat = loc.getDouble(1),
                    locationLng = loc.getDouble(0)
                ))
            }
        }

        return RouteResult(waypoints, steps, totalDistance, totalDuration)
    }

    // ------------------------------------------------------------------
    // Multi-waypoint via-point routing seams (NAVV-02) — PURE (zero network).
    // Verified live against router.project-osrm.org this session (04-RESEARCH Pattern 1-3).
    // ------------------------------------------------------------------

    /**
     * Builds the multi-coordinate OSRM URL from all downsampled points, with the
     * intermediate points as SILENT via points via `waypoints=0;{lastIndex}`.
     *
     * VERIFIED (04-RESEARCH Pattern 1): `waypoints=0;{last}` collapses an N-coordinate
     * request from N-1 legs (N-1 spurious arrive/depart pairs) to a single leg with
     * exactly one depart + one final zero-distance arrive. Without it, a 200-point route
     * emits ~199 spurious mid-route "Arrived!" banners.
     *
     * Coordinate order is lng,lat (NOT lat,lng) — matches the existing 2-point getRoute.
     * The `driving` profile stays: the public host ignores the profile path segment
     * (driving/cycling/foot return byte-identical routes — VERIFIED), so the follow-route
     * fallback is the only real bike-path mitigation, not a profile switch.
     */
    fun buildViaUrl(points: List<Waypoint>): String {
        val coords = points.joinToString(";") { "${it.longitude},${it.latitude}" }
        val last = points.size - 1
        return "$BASE_URL/$coords?overview=full&geometries=geojson&steps=true&waypoints=0;$last"
    }

    /**
     * Defensively drops any `arrive` step with zero distance that is NOT the final step
     * (04-RESEARCH Pattern 2). With `waypoints=0;{last}` this is a no-op on the happy path
     * (exactly one final arrive), but it keeps the code correct even if a host ignores the
     * param or a typo reintroduces mid-route arrives. Keeps the final zero-distance arrive
     * and every non-arrive step.
     */
    fun filterArriveSteps(steps: List<NavigationStep>): List<NavigationStep> =
        steps.filterIndexed { i, s -> !(s.maneuver == "arrive" && s.distance == 0.0 && i != steps.lastIndex) }

    /**
     * Builds a follow-route fallback RouteResult from the downsampled waypoints with a
     * SINGLE synthetic "Follow route" step (04-RESEARCH Pattern 3, Pitfall 1).
     *
     * The synthetic step is MANDATORY: an empty steps list makes
     * HudStreamingService.sendStepsList early-return, so the glasses render the route line
     * with no guidance. The step's distance is 0.0 here and updated live to the
     * distance-to-next-downsampled-waypoint by NavigationManager's follow-route mode.
     *
     * Returns an empty RouteResult ONLY when there are literally no points.
     */
    fun buildFollowRouteResult(downsampled: List<Waypoint>): RouteResult {
        if (downsampled.isEmpty()) {
            return RouteResult(emptyList(), emptyList(), 0.0, 0.0)
        }
        val followStep = NavigationStep(
            instruction = "Follow route",
            maneuver = "straight",
            distance = 0.0,
            duration = 0.0,
            locationLat = downsampled.first().latitude,
            locationLng = downsampled.first().longitude
        )
        return RouteResult(
            waypoints = downsampled,
            steps = listOf(followStep),
            totalDistance = followRouteDistanceM(downsampled),
            totalDuration = 0.0
        )
    }

    /** Sum of haversine leg lengths (meters) across the downsampled waypoints; 0.0 for <2 points. */
    private fun followRouteDistanceM(points: List<Waypoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until points.size) {
            total += haversineM(
                points[i - 1].latitude, points[i - 1].longitude,
                points[i].latitude, points[i].longitude
            )
        }
        return total
    }

    private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    private fun buildInstruction(type: String, modifier: String, name: String): String {
        val street = if (name.isBlank()) "" else " onto $name"
        return when (type) {
            "depart" -> "Head${street.ifEmpty { " out" }}"
            "arrive" -> "Arrive at destination"
            "turn" -> "${modifierLabel(modifier)}$street"
            "new name" -> "Continue$street"
            "merge" -> "Merge$street"
            "on ramp" -> "Take ramp$street"
            "off ramp" -> "Exit$street"
            "fork" -> "${modifierLabel(modifier)} at fork$street"
            "end of road" -> "${modifierLabel(modifier)}$street"
            "continue" -> "Continue$street"
            "roundabout", "rotary" -> "Enter roundabout, exit$street"
            "roundabout turn" -> "${modifierLabel(modifier)} at roundabout$street"
            "notification" -> name.ifBlank { "Continue" }
            else -> "Continue$street"
        }
    }

    private fun modifierLabel(modifier: String): String = when (modifier) {
        "left" -> "Turn left"
        "right" -> "Turn right"
        "straight" -> "Continue straight"
        "slight left" -> "Slight left"
        "slight right" -> "Slight right"
        "sharp left" -> "Sharp left"
        "sharp right" -> "Sharp right"
        "uturn" -> "Make a U-turn"
        else -> "Continue"
    }

    private fun toManeuverKey(type: String, modifier: String): String = when {
        type == "arrive" -> "arrive"
        type == "depart" -> "depart"
        type == "roundabout" || type == "rotary" -> modifier.ifBlank { "straight" }
        modifier.isNotBlank() -> modifier
        else -> "straight"
    }
}
