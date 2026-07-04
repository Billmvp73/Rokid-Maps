package com.rokid.hud.phone.strava

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Gson models for Strava token-endpoint responses (STACK.md: Gson for Strava
 * responses only; org.json everywhere else).
 *
 * ALL FIELDS NULLABLE — Gson instantiates Kotlin data classes via Unsafe,
 * skipping constructor defaults: missing JSON fields land as null even in
 * non-null Kotlin types, deferring the crash to first use (03-RESEARCH
 * Pattern 6). Validate explicitly after parse via [parseTokenResponse].
 */
data class TokenResponse(
    @SerializedName("token_type") val tokenType: String?,
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("refresh_token") val refreshToken: String?,
    /** Epoch seconds. */
    @SerializedName("expires_at") val expiresAt: Long?,
    @SerializedName("expires_in") val expiresIn: Long?,
    /** Present on the initial code exchange ONLY; refresh responses carry no athlete (RESEARCH A1). */
    @SerializedName("athlete") val athlete: StravaAthlete?
)

/** Summary athlete from the initial exchange. [id] is Long: Strava IDs exceed Int range. */
data class StravaAthlete(
    @SerializedName("id") val id: Long?,
    @SerializedName("username") val username: String?,
    @SerializedName("firstname") val firstname: String?,
    @SerializedName("lastname") val lastname: String?
) {
    /** "First Last" -> username -> "Strava athlete" fallback chain for the phone-UI card. */
    fun displayName(): String =
        listOfNotNull(
            firstname?.takeIf { it.isNotBlank() },
            lastname?.takeIf { it.isNotBlank() }
        ).joinToString(" ").ifBlank {
            username?.takeIf { it.isNotBlank() } ?: "Strava athlete"
        }
}

/**
 * The NON-NULL persisted shape — only constructed after [parseTokenResponse]
 * (or the coordinator's post-refresh validation) has proven the fields exist.
 * Wave 2's StravaTokenStore serializes exactly these five fields.
 */
data class StoredTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val athleteId: Long?,
    val athleteName: String?
)

/**
 * Parses and validates a token-endpoint body. Returns null on null/blank
 * input, malformed JSON, or any missing required field (access_token,
 * refresh_token, expires_at) — never throws.
 */
fun parseTokenResponse(json: String?): TokenResponse? {
    if (json.isNullOrBlank()) return null
    val parsed = try {
        Gson().fromJson(json, TokenResponse::class.java)
    } catch (e: Exception) {
        null
    } ?: return null
    // Explicit validation after parse — Pattern 6 prescription.
    if (parsed.accessToken.isNullOrBlank()) return null
    if (parsed.refreshToken.isNullOrBlank()) return null
    if (parsed.expiresAt == null) return null
    return parsed
}

// ---------------------------------------------------------------------------
// Strava Route models (RIMP-01/02) — GET /athlete/routes + /routes/{id}/export_gpx
// ---------------------------------------------------------------------------

/**
 * A saved/starred Strava route. ALL FIELDS NULLABLE (Gson-via-Unsafe caveat, same
 * discipline as [TokenResponse]): missing JSON fields land as null even in non-null
 * Kotlin types.
 *
 * Two contract sharp edges are encoded in the types (04-RESEARCH Pitfall 4):
 *  - [type] / [subType] are INTEGERS (1=ride 2=run; sub 1=road 2=mtb 3=cx 4=trail
 *    5=mixed), NOT strings — a String model silently mis-parses.
 *  - [idStr] (String) is used for the export_gpx URL to avoid 64-bit id truncation
 *    (Strava added id_str in 2020 specifically because apps mishandle 64-bit ids).
 *
 * Field order (id, idStr, name, distance, elevationGain, type, subType, isPrivate,
 * starred, map) is API-load-bearing for positional construction in tests — keep it.
 */
data class StravaRoute(
    @SerializedName("id") val id: Long?,
    @SerializedName("id_str") val idStr: String?,
    @SerializedName("name") val name: String?,
    /** Meters. */
    @SerializedName("distance") val distance: Double?,
    /** Meters. */
    @SerializedName("elevation_gain") val elevationGain: Double?,
    /** 1=ride, 2=run. */
    @SerializedName("type") val type: Int?,
    /** 1=road, 2=mtb, 3=cx, 4=trail, 5=mixed. */
    @SerializedName("sub_type") val subType: Int?,
    @SerializedName("private") val isPrivate: Boolean?,
    @SerializedName("starred") val starred: Boolean?,
    @SerializedName("map") val map: StravaRouteMap?
) {
    /** Int type -> UI label ("Ride"/"Run"), "Route" for unknown/null (Plan 05 consumes it). */
    fun typeLabel(): String = when (type) {
        1 -> "Ride"
        2 -> "Run"
        else -> "Route"
    }
}

/** The `map` object of a route; only the encoded overview polyline is needed. */
data class StravaRouteMap(
    @SerializedName("summary_polyline") val summaryPolyline: String?
)

/** Shared Strava REST base — single source of truth for the route URL builders. */
private const val STRAVA_API_BASE = "https://www.strava.com/api/v3"

/**
 * Routes-list URL. SINGULAR /athlete/routes (NOT /athletes/{id}/routes — the by-id
 * form 403s even for your own athlete; 04-RESEARCH Pitfall 6). `per_page` max is 200.
 * Pure — Plan 04's network method AND the unit test both call this.
 */
fun buildRoutesUrl(page: Int, perPage: Int): String =
    "$STRAVA_API_BASE/athlete/routes?per_page=$perPage&page=$page"

/**
 * GPX-export URL. Uses [idStr] (String) in the path for 64-bit safety (Pitfall 4).
 * Requires the read_all scope for private routes (granted in Phase 3). Pure.
 */
fun buildExportGpxUrl(idStr: String): String =
    "$STRAVA_API_BASE/routes/$idStr/export_gpx"
