package com.rokid.hud.phone.strava

/**
 * Persistence seam for stored Strava tokens. Implemented by StravaTokenStore
 * (EncryptedSharedPreferences) in Wave 2 and by in-memory fakes in tests.
 */
interface TokenPersistence {
    fun load(): StoredTokens?
    fun save(tokens: StoredTokens)
    fun clear()
}

/**
 * Transport seam for the POST /oauth/token grant_type=refresh_token call.
 * Implemented over OkHttp in Wave 2 (StravaAuthManager), by counting fakes
 * in tests.
 */
interface RefreshTransport {
    fun refresh(refreshToken: String): RefreshOutcome
}

/**
 * Result of one transport-level refresh attempt.
 * Wipe policy lives in the coordinator, not the transport.
 */
sealed class RefreshOutcome {
    /** HTTP 2xx with a parsed body (coordinator still validates the fields). */
    data class Success(val response: TokenResponse) : RefreshOutcome()

    /** Any non-2xx HTTP from the token endpoint. */
    data class Rejected(val httpCode: Int) : RefreshOutcome()

    /** IOException and friends — the request never got a definitive answer. */
    data class TransientError(val message: String?) : RefreshOutcome()
}

/**
 * Single-flight token refresh (AUTH-03). All refresh decisions happen inside
 * one lock: concurrent callers serialize, and the second caller re-loads the
 * store inside the lock, sees the freshly-saved token, and skips the network.
 *
 * Pure JVM — logging via an injected lambda so JVM tests stay silent and
 * Wave 2 plugs android.util.Log.
 */
class TokenRefreshCoordinator(
    private val store: TokenPersistence,
    private val transport: RefreshTransport,
    private val nowSec: () -> Long = { System.currentTimeMillis() / 1000L },
    private val log: (String) -> Unit = {}
) {
    private val lock = Any()

    /**
     * Blocking. Returns a currently-valid access token, refreshing under the
     * lock if the proactive 30-minute window has been tripped.
     * Null => no tokens or refresh failed => caller surfaces Reconnect state.
     */
    fun ensureFreshToken(): String? = synchronized(lock) {
        val t = store.load() ?: return null
        if (!StravaOAuth.needsRefresh(t.expiresAt, nowSec())) t.accessToken else refreshLocked(t)
    }

    /**
     * Refresh regardless of the window — powers the Wave-3 debug rotation
     * hook (03-VALIDATION forced-refresh proof).
     */
    fun forceRefresh(): String? = synchronized(lock) {
        val t = store.load() ?: return null
        refreshLocked(t)
    }

    /**
     * The OkHttp Authenticator's double-check (RESEARCH Pattern 5): if another
     * thread already refreshed while this request was in flight, retry with
     * the fresh stored token WITHOUT a second network refresh.
     */
    fun retryTokenAfter401(failedAccessToken: String?): String? = synchronized(lock) {
        val t = store.load() ?: return null
        if (failedAccessToken != null && t.accessToken != failedAccessToken) t.accessToken
        else refreshLocked(t)
    }

    /**
     * Must hold [lock]. Persists the ROTATED refresh_token on success
     * (Strava: "once a new refresh token code has been returned, the older
     * code will no longer work"), preserving athlete identity. Wipes tokens
     * ONLY on definitive rejection (HTTP 400/401); transient failures keep
     * tokens so a flaky network never forces re-auth.
     */
    private fun refreshLocked(t: StoredTokens): String? {
        return when (val outcome = transport.refresh(t.refreshToken)) {
            is RefreshOutcome.Success -> {
                val r = outcome.response
                if (r.accessToken.isNullOrBlank() || r.refreshToken.isNullOrBlank() || r.expiresAt == null) {
                    log("refresh returned malformed body — keeping existing tokens")
                    null
                } else {
                    store.save(StoredTokens(r.accessToken, r.refreshToken, r.expiresAt, t.athleteId, t.athleteName))
                    // Expiry + unsigned-hex hash suffix ONLY — never token material (Security Domain V7).
                    log("refresh ok expires_at=${r.expiresAt} rt#=${Integer.toHexString(r.refreshToken.hashCode())}")
                    r.accessToken
                }
            }
            is RefreshOutcome.Rejected -> {
                if (outcome.httpCode == 400 || outcome.httpCode == 401) {
                    store.clear()
                    log("refresh rejected http=${outcome.httpCode} — tokens wiped, reconnect required")
                } else {
                    log("refresh failed http=${outcome.httpCode} — keeping tokens")
                }
                null
            }
            is RefreshOutcome.TransientError -> {
                log("refresh transient failure: ${outcome.message} — keeping tokens")
                null
            }
        }
    }
}
