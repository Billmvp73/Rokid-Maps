package com.rokid.hud.phone.strava

import android.util.Log
import com.google.gson.Gson
import com.rokid.hud.phone.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Outcome of [StravaUploader.startUpload] (the POST /uploads half of UPL-02). A
 * small sealed type — same three-way discipline as [RoutesResult]/[GpxResult] —
 * so the Wave-3 driver can tell the outcomes APART and map each into the pure
 * Plan-01 `StartOutcome`:
 *  - [Started]     — 201; the async upload is enqueued. [idStr] is the String
 *                    upload id the poll path uses (64-bit safe — Pitfall 3).
 *  - [RateLimited] — HTTP 429; Wave 3 surfaces the "Strava busy — retry shortly"
 *                    state (no auto-retry loop).
 *  - [Failed]      — a non-2xx, an error in the body, a missing upload id, or a
 *                    thrown exception (never rethrown).
 */
sealed class StartResult {
    data class Started(val idStr: String) : StartResult()
    object RateLimited : StartResult()
    data class Failed(val message: String) : StartResult()
}

/**
 * Outcome of one [StravaUploader.poll] call (the GET /uploads/{id_str} half of
 * UPL-02). Maps 1:1 onto the pure Plan-01 `PollOutcome` the `driveUpload` state
 * machine interprets:
 *  - [Ready]      — status "…ready." and [activityId] set — the terminal success.
 *  - [Duplicate]  — Strava rejected as a duplicate but the existing activity id was
 *                   recovered from the error string (no re-upload, no data loss —
 *                   Pitfall 4 recovery).
 *  - [Processing] — still queued; the caller keeps polling within its 2-min deadline.
 *  - [Error]      — a non-duplicate error, a rate limit, or a thrown exception. NOT
 *                   a success — the Wave-3 driver never marks the session uploaded
 *                   on an [Error] (a transient poll drop must not optimistically
 *                   claim success — Pitfall 4).
 */
sealed class PollResult {
    data class Ready(val activityId: Long) : PollResult()
    data class Duplicate(val activityId: Long) : PollResult()
    object Processing : PollResult()
    data class Error(val message: String) : PollResult()
}

/**
 * The Strava upload network layer (UPL-02/03): POST the recorded GPX as an OkHttp
 * multipart body, then poll the async upload result. Mirrors [StravaApiClient]
 * exactly — proactive [StravaAuthManager.ensureFreshToken] is the PRIMARY refresh,
 * the reactive 401 [StravaAuthenticator] is the net on [client], both rate-limit
 * header pairs are logged, a 429 is signalled distinctly, and nothing is ever
 * rethrown (CLAUDE.md convention).
 *
 * Every method is BLOCKING — call from Thread{} only (NetworkOnMainThreadException
 * otherwise, the OsrmClient/StravaApiClient convention). The uploader is STATELESS
 * PER CALL by design: the 2s poll spacing and the 2-min deadline live in the Wave-3
 * Activity's Thread{} driving the pure Plan-01 `driveUpload` state machine — so a
 * network drop mid-poll never optimistically marks success (Pitfall 4).
 *
 * The /api/v3 base is load-bearing and matches [StravaApiClient]: resource calls
 * (uploads) use the prefix; the token endpoints (Phase 3) do not.
 */
class StravaUploader(private val auth: StravaAuthManager) {

    companion object {
        private const val TAG = "StravaUpload"
        private const val BASE = "https://www.strava.com/api/v3"
        private val GPX_MEDIA = "application/gpx+xml".toMediaType()
    }

    // Wire logging DEBUG-only and Level.BASIC ONLY (mirrors StravaApiClient) — a more
    // verbose level would leak the Authorization header AND the GPX body (which carries
    // precise location+time history). BASIC logs the request line only, never the body
    // (threats T-05-05/T-05-06).
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
     * POST /uploads (UPL-02). BLOCKING, Thread{} only. Uploads [gpx] as an OkHttp
     * multipart body — OkHttp OWNS the `multipart/form-data; boundary=...` header;
     * setting a Content-Type by hand is the documented `"data":"empty"` failure
     * (05-RESEARCH Anti-Patterns / T-05-07), so this method sets ONLY the
     * Authorization header on the request.
     *
     * Parts: `data_type=gpx`, `name`, `sport_type` (PascalCase Ride/Run from
     * [GpxWriter.sportType]), `external_id` (= the session id, Strava's secondary
     * dedup guard), and the `file` part named "$externalId.gpx".
     *
     * Proactive [StravaAuthManager.ensureFreshToken] is PRIMARY — a 6h+ ride's
     * expired token refreshes transparently here (Pitfall 4 token-expiry). A 429 is
     * [StartResult.RateLimited]; the upload id is [UploadResponse.idStr] (falling
     * back to the numeric id only if id_str is somehow absent — 64-bit path, Pitfall
     * 3). Never rethrows.
     */
    fun startUpload(gpx: String, name: String, externalId: String, sportType: String): StartResult {
        val token = auth.ensureFreshToken() ?: return StartResult.Failed("Not connected to Strava")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM) // OkHttp sets multipart/form-data + the boundary
            .addFormDataPart("data_type", "gpx")
            .addFormDataPart("name", name)
            .addFormDataPart("sport_type", sportType)
            .addFormDataPart("external_id", externalId)
            .addFormDataPart("file", "$externalId.gpx", gpx.toRequestBody(GPX_MEDIA))
            .build()
        val req = Request.Builder()
            .url("$BASE/uploads")
            .header("Authorization", "Bearer $token") // NO Content-Type — OkHttp owns it
            .post(body)
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                logRateLimits(resp)
                if (resp.code == 429) {
                    Log.w(TAG, "POST /uploads rate limited")
                    return StartResult.RateLimited
                }
                val parsed = gson.fromJson(resp.body?.string(), UploadResponse::class.java)
                if (!resp.isSuccessful || parsed?.error != null) {
                    Log.w(TAG, "POST /uploads ${resp.code}")
                    return StartResult.Failed(parsed?.error ?: "HTTP ${resp.code}")
                }
                // id_str is the safe poll-path id; fall back to the numeric id only if absent.
                val idStr = parsed?.idStr ?: parsed?.id?.toString()
                    ?: return StartResult.Failed("Upload returned no id")
                StartResult.Started(idStr)
            }
        } catch (e: Exception) {
            // Codebase convention: never rethrow. No GPX/coordinate/token content logged.
            Log.e(TAG, "startUpload failed: ${e.message}", e)
            StartResult.Failed(e.message ?: "Upload failed")
        }
    }

    /**
     * GET /uploads/{id_str} — one stateless poll of the async upload (UPL-02/03).
     * BLOCKING, Thread{} only. [idStr] goes in the PATH (never the numeric id —
     * Pitfall 3). Same auth/log discipline as [startUpload]: proactive
     * [StravaAuthManager.ensureFreshToken] PRIMARY, both rate-limit headers logged.
     *
     * Interprets the [UploadResponse]:
     *  - `activity_id` set AND status contains "ready" -> [PollResult.Ready].
     *  - else an `error`: [GpxWriter.parseDuplicateActivityId] recovers a duplicate's
     *    existing activity id -> [PollResult.Duplicate] (no re-upload); a non-duplicate
     *    error -> [PollResult.Error].
     *  - else -> [PollResult.Processing] (still queued).
     *
     * A 429 or a thrown exception is [PollResult.Error] — a transient poll failure is
     * NOT a success, so the Wave-3 driver keeps polling within its deadline and never
     * marks the session uploaded on an [Error] (Pitfall 4). Never rethrows.
     */
    fun poll(idStr: String): PollResult {
        val token = auth.ensureFreshToken() ?: return PollResult.Error("Not connected to Strava")
        val req = Request.Builder()
            .url("$BASE/uploads/$idStr") // id_str in the path — 64-bit safe (Pitfall 3)
            .header("Authorization", "Bearer $token")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                logRateLimits(resp)
                if (resp.code == 429) {
                    // Transient — NOT a success. The caller keeps polling within its deadline.
                    Log.w(TAG, "GET /uploads/{id} rate limited")
                    return PollResult.Error("Strava busy")
                }
                val parsed = gson.fromJson(resp.body?.string(), UploadResponse::class.java)
                if (parsed == null) {
                    if (!resp.isSuccessful) return PollResult.Error("HTTP ${resp.code}")
                    // Empty/unparseable but 2xx — treat as still processing (keep polling).
                    return PollResult.Processing
                }
                val activityId = parsed.activityId
                val status = parsed.status.orEmpty()
                when {
                    activityId != null && status.contains("ready", ignoreCase = true) ->
                        PollResult.Ready(activityId)
                    parsed.error != null -> {
                        val dupId = GpxWriter.parseDuplicateActivityId(parsed.error)
                        if (dupId != null) PollResult.Duplicate(dupId)
                        else PollResult.Error(parsed.error)
                    }
                    else -> PollResult.Processing // still queued
                }
            }
        } catch (e: Exception) {
            // Codebase convention: never rethrow. Transient failure -> Error (no success).
            Log.e(TAG, "poll failed: ${e.message}", e)
            PollResult.Error(e.message ?: "Poll failed")
        }
    }

    /**
     * Logs BOTH rate-limit header pairs (identical to [StravaApiClient.logRateLimits]).
     * The POST is a WRITE (verified limits: 200/15min overall + 2,000/day; a single
     * user is never near the cap). Logs ONLY the header usage — never the token, the
     * GPX body, or any coordinate (threat T-05-06).
     */
    private fun logRateLimits(resp: Response) {
        val overall = resp.header("X-RateLimit-Usage")
        val read = resp.header("X-ReadRateLimit-Usage")
        if (overall != null || read != null) Log.i(TAG, "Rate usage overall=$overall read=$read")
    }
}
