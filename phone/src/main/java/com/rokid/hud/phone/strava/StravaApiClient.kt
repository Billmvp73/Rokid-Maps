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
 * Result of [StravaApiClient.getRoutes] (RIMP-01). A small sealed type so the caller
 * can tell three outcomes APART (CONTEXT locked decision — the UI shows a *distinct*
 * "rate limit — try again shortly" toast on 429, "No routes found" on an empty
 * [Success], and a generic error toast on [Failed]):
 *  - [Success]     — the list (possibly empty) of the athlete's routes.
 *  - [RateLimited] — HTTP 429; Wave 3 surfaces the locked rate-limit toast (no retry loop, T-04-16).
 *  - [Failed]      — any other non-2xx or a thrown exception (never rethrown).
 */
sealed class RoutesResult {
    data class Success(val routes: List<StravaRoute>) : RoutesResult()
    object RateLimited : RoutesResult()
    object Failed : RoutesResult()
}

/**
 * Result of [StravaApiClient.exportGpx] (RIMP-02) — same three-way discipline as
 * [RoutesResult] so a 429 is distinguishable from a generic import failure.
 *  - [Success]     — the raw GPX body (application/gpx+xml).
 *  - [RateLimited] — HTTP 429.
 *  - [Failed]      — any other non-2xx or a thrown exception (never rethrown).
 */
sealed class GpxResult {
    data class Success(val gpx: String) : GpxResult()
    object RateLimited : GpxResult()
    object Failed : GpxResult()
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
     * GET /athlete/routes — the athlete's saved/starred routes (RIMP-01). BLOCKING,
     * Thread{} only. Mirrors [getAthlete] precisely: proactive [StravaAuthManager.ensureFreshToken]
     * is the PRIMARY refresh (no token -> [RoutesResult.Failed], caller surfaces Reconnect);
     * the reactive 401 net lives on [client]'s [StravaAuthenticator].
     *
     * URL comes from the Plan-01 pure routes-list builder — the SINGULAR /athlete/routes form
     * (the by-id /athletes/{id}/routes form 403s even for your own id; 04-RESEARCH Pitfall 6).
     * A 429 is returned as [RoutesResult.RateLimited] so the UI shows the locked rate-limit
     * toast distinctly from an empty list (no auto-retry — T-04-16). Never rethrows.
     *
     * @param page 1-based page index. @param perPage page size (Strava max 200, default 30).
     */
    fun getRoutes(page: Int = 1, perPage: Int = 30): RoutesResult {
        val token = auth.ensureFreshToken() ?: return RoutesResult.Failed
        val req = Request.Builder()
            .url(buildRoutesUrl(page, perPage))
            .header("Authorization", "Bearer $token")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                logRateLimits(resp)
                if (resp.code == 429) {
                    Log.w(TAG, "GET /athlete/routes rate limited")
                    return RoutesResult.RateLimited
                }
                if (!resp.isSuccessful) {
                    Log.w(TAG, "GET /athlete/routes ${resp.code}")
                    return RoutesResult.Failed
                }
                val arr = gson.fromJson(resp.body?.string(), Array<StravaRoute>::class.java)
                RoutesResult.Success(arr?.toList() ?: emptyList())
            }
        } catch (e: Exception) {
            // Codebase convention: never rethrow.
            Log.e(TAG, "getRoutes failed: ${e.message}", e)
            RoutesResult.Failed
        }
    }

    /**
     * GET /routes/{id_str}/export_gpx — the raw GPX body for a route (RIMP-02). BLOCKING,
     * Thread{} only. Same auth/log discipline as [getRoutes]. URL comes from the Plan-01
     * pure export-gpx builder, which puts [routeIdStr] (the String id_str) in the path for
     * 64-bit safety (Pitfall 4). The read_all scope granted in Phase 3 covers private routes (A3).
     *
     * A 429 is [GpxResult.RateLimited]; a 2xx returns the raw application/gpx+xml body as
     * [GpxResult.Success]; anything else is [GpxResult.Failed]. Never rethrows.
     */
    fun exportGpx(routeIdStr: String): GpxResult {
        val token = auth.ensureFreshToken() ?: return GpxResult.Failed
        val req = Request.Builder()
            .url(buildExportGpxUrl(routeIdStr))
            .header("Authorization", "Bearer $token")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                logRateLimits(resp)
                if (resp.code == 429) {
                    Log.w(TAG, "export_gpx rate limited")
                    return GpxResult.RateLimited
                }
                if (!resp.isSuccessful) {
                    Log.w(TAG, "export_gpx ${resp.code}")
                    return GpxResult.Failed
                }
                val gpx = resp.body?.string()
                if (gpx.isNullOrEmpty()) GpxResult.Failed else GpxResult.Success(gpx)
            }
        } catch (e: Exception) {
            // Codebase convention: never rethrow.
            Log.e(TAG, "exportGpx failed: ${e.message}", e)
            GpxResult.Failed
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
