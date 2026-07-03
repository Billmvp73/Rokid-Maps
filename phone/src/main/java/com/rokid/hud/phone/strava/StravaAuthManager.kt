package com.rokid.hud.phone.strava

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.rokid.hud.phone.BuildConfig
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Outcome of one rokidhud://callback attempt — Wave 3's MainActivity maps
 * each variant to card state + toast.
 */
sealed class CallbackResult {
    data class Connected(val athleteName: String) : CallbackResult()
    object StateMismatch : CallbackResult()
    object Denied : CallbackResult()
    object ScopesIncomplete : CallbackResult()
    object ExchangeFailed : CallbackResult()
}

/**
 * Android integration for the Strava OAuth flow (AUTH-01/03): Custom Tab
 * launch, pending-state lifecycle, code exchange, and the OkHttp refresh
 * transport feeding [TokenRefreshCoordinator]. All token/network methods
 * are BLOCKING — call from Thread{} only (codebase convention, no coroutines).
 *
 * LOGGING DISCIPLINE (Security Domain V7): never log token material, the
 * client secret, the authorization code, or the full callback URI. Events,
 * HTTP status codes, expires_at, and hashCode hex suffixes only.
 */
class StravaAuthManager(
    private val context: Context,
    private val tokenStore: StravaTokenStore
) {

    companion object {
        private const val TAG = "StravaAuth"
        private const val STATE_PREFS = "strava_oauth"
        private const val KEY_PENDING_STATE = "pending_state"
    }

    /**
     * PLAIN client for BOTH token-endpoint calls (exchange + refresh): no
     * authenticator, so a token-endpoint 401 can never re-enter the
     * Authenticator (03-RESEARCH Anti-Patterns — refresh recursion).
     */
    private val tokenClient = OkHttpClient()

    /**
     * POST grant_type=refresh_token on the plain client. Never logs the form
     * body. Non-2xx maps to Rejected (the coordinator owns the wipe policy);
     * I/O failure maps to TransientError (tokens kept).
     */
    private val refreshTransport = object : RefreshTransport {
        override fun refresh(refreshToken: String): RefreshOutcome {
            val body = FormBody.Builder()
                .add("client_id", BuildConfig.STRAVA_CLIENT_ID)
                .add("client_secret", BuildConfig.STRAVA_CLIENT_SECRET)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build()
            val req = Request.Builder().url(StravaOAuth.TOKEN_URL).post(body).build()
            return try {
                tokenClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        parseTokenResponse(resp.body?.string())
                            ?.let { RefreshOutcome.Success(it) }
                            ?: RefreshOutcome.TransientError("malformed body")
                    } else {
                        RefreshOutcome.Rejected(resp.code)
                    }
                }
            } catch (e: IOException) {
                RefreshOutcome.TransientError(e.message)
            } catch (e: Exception) {
                RefreshOutcome.TransientError(e.message)
            }
        }
    }

    /** Single-flight refresh policy lives entirely inside the coordinator (Wave 1, tested). */
    val coordinator = TokenRefreshCoordinator(
        store = tokenStore,
        transport = refreshTransport,
        log = { Log.i(TAG, it) }
    )

    // --- pending-state lifecycle (Pitfall 5: single-use nonce) --------------
    // Plain SharedPreferences: the nonce is not a secret (it rides in the
    // browser URL); keeping it out of the ESP avoids main-thread Keystore
    // work at CONNECT tap, and a backup-restored stale nonce is harmless —
    // validation just fails.

    private fun persistState(state: String) {
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PENDING_STATE, state).apply()
    }

    /** Read then IMMEDIATELY remove — the nonce dies after one attempt regardless of outcome. */
    private fun consumePendingState(): String? {
        val prefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        val state = prefs.getString(KEY_PENDING_STATE, null)
        prefs.edit().remove(KEY_PENDING_STATE).apply()
        return state
    }

    // --- authorize ----------------------------------------------------------

    /**
     * Launches the Strava consent surface in a Custom Tab (or the default
     * browser / native Strava app — every surface ends at rokidhud://callback,
     * 03-RESEARCH A7). Returns false if keys are missing (Pitfall 7 — never
     * launch with an empty client_id) or no browser exists.
     */
    fun launchAuthorize(activity: Activity): Boolean {
        if (BuildConfig.STRAVA_CLIENT_ID.isEmpty() || BuildConfig.STRAVA_CLIENT_SECRET.isEmpty()) {
            Log.w(TAG, "Strava keys not configured")
            return false
        }
        val state = StravaOAuth.newState()
        persistState(state)
        Log.i(TAG, "state generated, launching authorize")
        return try {
            CustomTabsIntent.Builder().build().launchUrl(
                activity,
                Uri.parse(StravaOAuth.buildAuthorizeUrl(BuildConfig.STRAVA_CLIENT_ID, state))
            )
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No browser available: ${e.message}")
            false
        }
    }

    // --- callback -----------------------------------------------------------

    /**
     * BLOCKING — call from Thread{} only. Validation order matters:
     * state is consumed FIRST (single-use) and checked BEFORE any network
     * call (T-03-01); granted scopes are checked BEFORE spending the
     * single-use code (Pitfall 4, T-03-08).
     */
    fun handleCallback(uriString: String?): CallbackResult {
        val expected = consumePendingState()
        val params = StravaOAuth.parseCallback(uriString)
        if (!StravaOAuth.validateState(expected, params["state"])) {
            Log.w(TAG, "state mismatch — rejecting callback")
            return CallbackResult.StateMismatch
        }
        val code = params["code"]
        if (code == null) {
            // Missing code = failure signal, robust to Strava's exact error param (RESEARCH A2).
            Log.w(TAG, "callback without code (user denied or error)")
            return CallbackResult.Denied
        }
        if (!StravaOAuth.grantedScopesComplete(params["scope"])) {
            Log.w(TAG, "granted scopes incomplete — not persisting tokens")
            return CallbackResult.ScopesIncomplete
        }
        val name = exchangeCode(code) ?: return CallbackResult.ExchangeFailed
        return CallbackResult.Connected(name)
    }

    /**
     * POST the authorization-code grant. The form carries EXACTLY these four
     * fields — Strava's token endpoint deviates from RFC 6749 §4.1.3 and
     * takes no callback-URI field. Returns the athlete display name on
     * success (tokens persisted), null on any failure. Never logs the body
     * or the code value.
     */
    private fun exchangeCode(code: String): String? {
        val body = FormBody.Builder()
            .add("client_id", BuildConfig.STRAVA_CLIENT_ID)
            .add("client_secret", BuildConfig.STRAVA_CLIENT_SECRET)
            .add("code", code)
            .add("grant_type", "authorization_code")
            .build()
        val req = Request.Builder().url(StravaOAuth.TOKEN_URL).post(body).build()
        return try {
            tokenClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "exchange failed http=${resp.code}")
                    return null
                }
                val r = parseTokenResponse(resp.body?.string())
                if (r == null) {
                    Log.w(TAG, "exchange returned malformed body")
                    return null
                }
                val athlete = r.athlete
                val name = athlete?.displayName() ?: "Strava athlete"
                // Non-null assertions safe: parseTokenResponse validated all three.
                tokenStore.save(
                    StoredTokens(r.accessToken!!, r.refreshToken!!, r.expiresAt!!, athlete?.id, name)
                )
                // Unsigned-hex hash suffix only — the 03-04 rotation gate compares
                // this initial rt# against the post-refresh rt# (T-03-04).
                val rtHash = Integer.toHexString(r.refreshToken!!.hashCode())
                Log.i(TAG, "exchange ok, token stored, expires_at=${r.expiresAt} rt#=$rtHash")
                name
            }
        } catch (e: IOException) {
            Log.e(TAG, "exchange failed: ${e.message}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "exchange failed: ${e.message}", e)
            null
        }
    }

    // --- token access (thin delegation — policy lives in the coordinator) ---

    /** BLOCKING. Proactively-fresh access token, or null => Reconnect state. */
    fun ensureFreshToken(): String? = coordinator.ensureFreshToken()

    /** BLOCKING. Window-ignoring refresh (Wave-3 debug rotation hook). */
    fun forceRefresh(): String? = coordinator.forceRefresh()

    /** BLOCKING. Authenticator double-check after a 401. */
    fun retryTokenAfter401(failed: String?): String? = coordinator.retryTokenAfter401(failed)

    /** BLOCKING (ESP) — background threads only. */
    fun isConnected(): Boolean = tokenStore.load() != null

    /** BLOCKING (ESP) — background threads only. */
    fun connectedAthleteName(): String? = tokenStore.load()?.athleteName

    /** Local wipe only — no remote deauthorize in v1 (CONTEXT locked). */
    fun disconnect() {
        tokenStore.clear()
        Log.i(TAG, "disconnected — local tokens wiped")
    }
}
