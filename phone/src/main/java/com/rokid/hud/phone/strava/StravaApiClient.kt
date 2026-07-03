package com.rokid.hud.phone.strava

import android.util.Log
import com.google.gson.Gson
import com.rokid.hud.phone.BuildConfig
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor

/**
 * REACTIVE fallback layer (CONTEXT locked: proactive refresh is primary,
 * this is the demoted 401 net). Retries a 401'd request at most ONCE with
 * a fresh token; the responseCount guard is the official OkHttp give-up
 * pattern and prevents a refresh-loop DoS (T-03-06).
 *
 * Runs synchronously on the request's calling thread — always an app
 * background Thread{} in this codebase (all StravaApiClient methods are
 * blocking, OsrmClient convention). Blocking refresh here is the intended
 * OkHttp usage.
 */
class StravaAuthenticator(private val auth: StravaAuthManager) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.responseCount >= 2) return null
        val failed = response.request.header("Authorization")?.removePrefix("Bearer ")
        // Coordinator double-checks inside its lock: if another thread already
        // refreshed while this request was in flight, no second network refresh.
        val fresh = auth.retryTokenAfter401(failed) ?: return null
        return response.request.newBuilder()
            .header("Authorization", "Bearer $fresh")
            .build()
    }

    private val Response.responseCount: Int
        get() = generateSequence(this) { it.priorResponse }.count()
}

/**
 * Authenticated Strava API client (the ROADMAP "Delivers" artifact).
 * Every method is BLOCKING — call from Thread{} only
 * (NetworkOnMainThreadException otherwise).
 *
 * The /api/v3 asymmetry is CORRECT and load-bearing: API resource calls use
 * https://www.strava.com/api/v3/..., while the token endpoints live at the
 * bare /oauth/token path without that prefix.
 */
class StravaApiClient(private val auth: StravaAuthManager) {

    companion object {
        private const val TAG = "StravaApi"
        private const val BASE = "https://www.strava.com/api/v3"
    }

    // Wire logging DEBUG-only and Level.BASIC ONLY — the more verbose levels
    // would leak Authorization headers and token JSON (03-RESEARCH Anti-Patterns).
    private val client = OkHttpClient.Builder()
        .authenticator(StravaAuthenticator(auth))
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC)
                )
            }
        }
        .build()

    private val gson = Gson()

    /**
     * GET /athlete — proves the authenticated client (AUTH success criterion).
     * BLOCKING, Thread{} only. Proactive 30-min refresh happens inside
     * ensureFreshToken (the locked PRIMARY mechanism); null means no/invalid
     * tokens — caller surfaces the Reconnect state.
     */
    fun getAthlete(): StravaAthlete? {
        val token = auth.ensureFreshToken() ?: return null
        val req = Request.Builder()
            .url("$BASE/athlete")
            .header("Authorization", "Bearer $token")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                logRateLimits(resp)
                if (!resp.isSuccessful) {
                    Log.w(TAG, "GET /athlete ${resp.code}")
                    return null
                }
                gson.fromJson(resp.body?.string(), StravaAthlete::class.java)
            }
        } catch (e: Exception) {
            // Codebase convention: never rethrow.
            Log.e(TAG, "GET /athlete failed: ${e.message}", e)
            null
        }
    }

    /**
     * Logs BOTH rate-limit header pairs (Integration Gotchas row 9; current
     * verified limits: 200/15min + 2,000/day overall, 100 reads/15min +
     * 1,000 reads/day). Awareness now, enforcement Phase 4.
     */
    private fun logRateLimits(resp: Response) {
        val overall = resp.header("X-RateLimit-Usage")
        val read = resp.header("X-ReadRateLimit-Usage")
        if (overall != null || read != null) Log.i(TAG, "Rate usage overall=$overall read=$read")
    }
}
