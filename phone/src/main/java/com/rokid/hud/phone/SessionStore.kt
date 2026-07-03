package com.rokid.hud.phone

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Crash-safe local JSON persistence for recording sessions (REC-06).
 *
 * One file per session under a caller-supplied directory: {id}.checkpoint.json
 * while recording (overwritten atomically each cycle), finalized to {id}.json
 * on stop. All writes are temp file + fsync + atomic same-directory rename.
 *
 * The constructor takes a plain [java.io.File], NOT Context — a deliberate
 * divergence from DiskTileCache(context) so every decision path here is
 * testable on a plain JVM (PATTERNS Integration Warning 7). The service binds
 * it to File(filesDir, "activities"): app-internal storage only, and with no
 * Context this class cannot reach external storage at all (threat T-03-01).
 */
class SessionStore(private val dir: File) {

    companion object {
        private const val TAG = "SessionStore"
        const val SCHEMA_VERSION = 1
        const val CHECKPOINT_SUFFIX = ".checkpoint.json"
        const val FINAL_SUFFIX = ".json"
        const val MAX_RESUME_AGE_MS = 10 * 60_000L
    }

    // Serial disk writes off the caller thread (DiskTileCache pattern). Async
    // wrappers serialize ON this executor: SessionData is immutable, so the
    // hand-off is safe and multi-thousand-point JSON never blocks the caller.
    private val executor = Executors.newSingleThreadExecutor()

    init {
        dir.mkdirs()
    }

    /**
     * Serializes [data] to the locked v1 session JSON schema.
     *
     * Top-level keys: schemaVersion, id, sport, startTime, endTime (omitted
     * when null — org.json has no nullable put), elapsedMs, movingMs,
     * distanceM, avgSpeedMps, stravaUploaded, trackPoints.
     *
     * Per-point keys: lat, lng, alt, ts, speed, acc, brg. alt/speed/brg are
     * OMITTED when NaN — JSONObject.put throws "Forbidden numeric value" on
     * non-finite doubles, so key omission IS the sentinel encoding; acc uses
     * the finite -1.0 unknown sentinel and is always written. brg is additive
     * per REC-01's bearing-per-point requirement (TrackPoint.bearingDeg).
     */
    internal fun toJson(data: SessionData): String = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("id", data.id)
        put("sport", data.sport)
        put("startTime", data.startTime)
        data.endTime?.let { put("endTime", it) }
        put("elapsedMs", data.elapsedMs)
        put("movingMs", data.movingMs)
        put("distanceM", data.distanceM)
        put("avgSpeedMps", data.avgSpeedMps)
        put("stravaUploaded", data.stravaUploaded)
        put("trackPoints", JSONArray().apply {
            for (p in data.trackPoints) {
                put(JSONObject().apply {
                    put("lat", p.lat)
                    put("lng", p.lng)
                    if (!p.alt.isNaN()) put("alt", p.alt)
                    put("ts", p.ts)
                    if (!p.speedMps.isNaN()) put("speed", p.speedMps)
                    put("acc", p.accuracyM)
                    if (!p.bearingDeg.isNaN()) put("brg", p.bearingDeg)
                })
            }
        })
    }.toString()

    /**
     * Parses session JSON back to [SessionData]; returns null on any parse
     * failure (corrupt bytes, missing required id/sport/startTime) — never
     * throws. Optional fields fall back to sentinels: omitted alt/speed/brg
     * read back as Double.NaN, acc as -1.0, numerics as 0.
     */
    internal fun fromJson(json: String): SessionData? {
        return try {
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("trackPoints") ?: JSONArray()
            val points = ArrayList<TrackPoint>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                points.add(
                    TrackPoint(
                        lat = p.optDouble("lat", 0.0),
                        lng = p.optDouble("lng", 0.0),
                        alt = p.optDouble("alt", Double.NaN),
                        ts = p.optLong("ts", 0L),
                        speedMps = p.optDouble("speed", Double.NaN),
                        accuracyM = p.optDouble("acc", -1.0),
                        bearingDeg = p.optDouble("brg", Double.NaN)
                    )
                )
            }
            SessionData(
                id = obj.getString("id"),
                sport = obj.getString("sport"),
                startTime = obj.getString("startTime"),
                endTime = obj.optString("endTime", "").takeIf { it.isNotEmpty() },
                elapsedMs = obj.optLong("elapsedMs", 0L),
                movingMs = obj.optLong("movingMs", 0L),
                distanceM = obj.optDouble("distanceM", 0.0),
                avgSpeedMps = obj.optDouble("avgSpeedMps", 0.0),
                stravaUploaded = obj.optBoolean("stravaUploaded", false),
                trackPoints = points
            )
        } catch (e: Exception) {
            Log.w(TAG, "Session JSON parse failed: ${e.message}")
            null
        }
    }

    /**
     * Atomic file replace: write {target}.tmp in the SAME directory, fsync,
     * then rename over [target]. Same-directory rename = same filesystem =
     * atomic POSIX rename(2) — never write the temp file to another dir.
     * renameTo returns false instead of throwing (Pitfall 8), so the boolean
     * is checked and logged.
     */
    internal fun writeAtomic(target: File, json: String) {
        val tmp = File(dir, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(json.toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                Log.e(TAG, "Atomic rename failed: ${tmp.name} -> ${target.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Atomic write failed for ${target.name}: ${e.message}", e)
            try { tmp.delete() } catch (_: Exception) {}
        }
    }

    /**
     * Graceful executor shutdown: shutdown() NOT shutdownNow() — a queued
     * final checkpoint must be allowed to complete during service teardown
     * (documented divergence from DiskTileCache.shutdownNow).
     */
    fun shutdown() {
        executor.shutdown()
    }
}
