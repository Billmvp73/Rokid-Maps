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
