package com.rokid.hud.phone.strava

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pure JVM (no android.*) so plain unit tests cover the OAuth decision
 * surface — 03-RESEARCH Validation Architecture.
 *
 * Every AUTH-01/02/03 rule that can be a pure function lives here: the
 * mobile-authorize URL builder, the CSRF state nonce lifecycle primitives,
 * the callback parser, the granted-scope validator, and the 30-minute
 * proactive refresh window math. Wave 2 (StravaAuthManager/StravaApiClient)
 * supplies I/O around these seams without reinterpreting OAuth rules.
 */
object StravaOAuth {

    /** Android-specific authorize endpoint (Integration Gotchas row 2 — never the plain /oauth/authorize). */
    const val AUTHORIZE_URL = "https://www.strava.com/oauth/mobile/authorize"

    /** NO api-v3 path prefix on the token endpoint — Strava quirk (PITFALLS Integration Gotchas). */
    const val TOKEN_URL = "https://www.strava.com/oauth/token"

    /** Locked decision; Strava app settings register the bare word `rokidhud`. */
    const val REDIRECT_URI = "rokidhud://callback"

    /**
     * Comma-delimited (NOT space) — CONTEXT locked. read_all covers Phase-4
     * private routes; activity:write pre-granted for Phase 5.
     */
    const val SCOPES = "read,read_all,activity:write"

    /** The grant is incomplete unless every one of these was actually granted. */
    val REQUIRED_SCOPES = setOf("read", "read_all", "activity:write")

    /** Proactive refresh threshold — CONTEXT locked at 30 minutes. */
    const val REFRESH_WINDOW_SEC = 30L * 60L

    /**
     * Exact param order: client_id, redirect_uri, response_type, approval_prompt,
     * scope, state. Commas URL-encode to %2C which Strava accepts as standard
     * form encoding.
     */
    fun buildAuthorizeUrl(clientId: String, state: String): String =
        AUTHORIZE_URL +
            "?client_id=" + urlEncode(clientId) +
            "&redirect_uri=" + urlEncode(REDIRECT_URI) +
            "&response_type=code" +
            "&approval_prompt=auto" +
            "&scope=" + urlEncode(SCOPES) +
            "&state=" + urlEncode(state)

    /**
     * 32 bytes of [SecureRandom] as 64 lowercase-hex chars (Don't Hand-Roll:
     * never Random()/timestamp — CSRF defense is only as strong as the entropy).
     */
    fun newState(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Constant-time comparison via [MessageDigest.isEqual] (Don't Hand-Roll
     * row 6). Null or empty on either side is a rejection, never a match.
     */
    fun validateState(expected: String?, received: String?): Boolean {
        if (expected.isNullOrEmpty() || received.isNullOrEmpty()) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            received.toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * Extracts query params from a rokidhud://callback URI using [java.net.URI]
     * (android.net.Uri would be null-stubbed under isReturnDefaultValues).
     * Any malformed input yields an empty map — never a throw.
     */
    fun parseCallback(uriString: String?): Map<String, String> {
        if (uriString == null) return emptyMap()
        return try {
            val query = URI(uriString).rawQuery ?: return emptyMap()
            query.split("&").mapNotNull { pair ->
                val i = pair.indexOf('=')
                if (i <= 0) null
                else URLDecoder.decode(pair.substring(0, i), "UTF-8") to
                    URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Fail-closed granted-scope check (Pitfall 4): Strava's consent screen lets
     * the athlete uncheck individual scopes; a missing/partial grant must be
     * treated as failure so Wave 2 refuses to persist tokens on it.
     */
    fun grantedScopesComplete(scopeParam: String?): Boolean {
        if (scopeParam.isNullOrBlank()) return false
        val granted = scopeParam.split(',').map { it.trim() }.toSet()
        return granted.containsAll(REQUIRED_SCOPES)
    }

    /**
     * True when [REFRESH_WINDOW_SEC] or less remains before [expiresAtSec]
     * (inclusive at exactly 30:00), including any past expiry (AUTH-03).
     */
    fun needsRefresh(expiresAtSec: Long, nowSec: Long): Boolean =
        (expiresAtSec - nowSec) <= REFRESH_WINDOW_SEC

    private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
