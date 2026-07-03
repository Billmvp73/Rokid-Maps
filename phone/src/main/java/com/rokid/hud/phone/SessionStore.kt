package com.rokid.hud.phone

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Outcome of the orphan-checkpoint scan at service start (REC-06 recovery).
 *
 * [resumable] is the freshest orphan (<10 min stale) to continue recording
 * into, or null; [finalizedInterrupted] counts stale/older orphans finalized
 * as interrupted sessions; [corrupt] counts unreadable checkpoints
 * quarantined as .corrupt.
 */
data class RecoveryResult(
    val resumable: SessionData?,
    val finalizedInterrupted: Int,
    val corrupt: Int
)

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
     * Atomic file replace: write a UNIQUE temp file in the SAME directory,
     * fsync, then rename over [target]. Same-directory rename = same
     * filesystem = atomic POSIX rename(2) — never write the temp file to
     * another dir. The per-write unique temp name keeps concurrent writers
     * collision-free (WR-01): each renames an intact file and the last atomic
     * rename wins, instead of two threads interleaving bytes on a shared
     * fixed .tmp path. renameTo returns false instead of throwing
     * (Pitfall 8), so the boolean is checked, logged, and the stray temp
     * deleted rather than stranded.
     */
    internal fun writeAtomic(target: File, json: String) {
        val tmp = try {
            File.createTempFile(target.name + ".", ".tmp", dir)
        } catch (e: Exception) {
            Log.e(TAG, "Temp file create failed for ${target.name}: ${e.message}", e)
            return
        }
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(json.toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                Log.e(TAG, "Atomic rename failed: ${tmp.name} -> ${target.name}")
                try { tmp.delete() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Atomic write failed for ${target.name}: ${e.message}", e)
            try { tmp.delete() } catch (_: Exception) {}
        }
    }

    private fun checkpointFile(id: String) = File(dir, id + CHECKPOINT_SUFFIX)

    private fun finalFile(id: String) = File(dir, id + FINAL_SUFFIX)

    /**
     * Writes/overwrites the single {id}.checkpoint.json for this session
     * atomically. Called every 60s or 500 points by the session manager.
     */
    fun writeCheckpointSync(data: SessionData) {
        writeAtomic(checkpointFile(data.id), toJson(data))
    }

    /** Async wrapper: serialization happens ON the executor ([data] is immutable). */
    fun writeCheckpointAsync(data: SessionData) {
        executor.execute {
            try {
                writeCheckpointSync(data)
            } catch (e: Exception) {
                Log.w(TAG, "Async checkpoint failed for ${data.id}: ${e.message}")
            }
        }
    }

    /**
     * Finalizes a session: writes {id}.json atomically FIRST, then deletes
     * the checkpoint (order matters — never a window with neither file,
     * threat T-03-03). Checked delete with a log on failure.
     */
    fun finalizeSync(data: SessionData) {
        writeAtomic(finalFile(data.id), toJson(data))
        val checkpoint = checkpointFile(data.id)
        if (checkpoint.exists() && !checkpoint.delete()) {
            Log.w(TAG, "Checkpoint delete failed after finalize: ${checkpoint.name}")
        }
    }

    /** Async wrapper for [finalizeSync] on the serial executor. */
    fun finalizeAsync(data: SessionData) {
        executor.execute {
            try {
                finalizeSync(data)
            } catch (e: Exception) {
                Log.w(TAG, "Async finalize failed for ${data.id}: ${e.message}")
            }
        }
    }

    /**
     * Lists *.checkpoint.json files that lack a matching {id}.json final file
     * — sessions whose recording never reached the normal stop path.
     */
    fun findOrphanCheckpoints(): List<File> {
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { f ->
            f.isFile && f.name.endsWith(CHECKPOINT_SUFFIX) &&
                !finalFile(f.name.removeSuffix(CHECKPOINT_SUFFIX)).exists()
        }
    }

    /**
     * Orphan recovery at service start (locked <10-minute resume rule).
     * Synchronous — runs once in onStartCommand before location updates begin.
     *
     * Per orphan: unreadable JSON is quarantined as {id}.checkpoint.corrupt
     * (bytes preserved for post-mortem, never resumed, never silently
     * deleted — threat T-03-02); readable checkpoints fresher than
     * [MAX_RESUME_AGE_MS] are resume candidates (newest wins, file left in
     * place for the normal stop path); everything else is finalized as an
     * interrupted session with endTime derived from the checkpoint's
     * lastModified.
     */
    fun recoverOrphans(nowWallMs: Long = System.currentTimeMillis()): RecoveryResult {
        var corrupt = 0
        var finalizedInterrupted = 0
        val freshCandidates = mutableListOf<Pair<File, SessionData>>()
        for (file in findOrphanCheckpoints()) {
            val data = try {
                fromJson(file.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                Log.w(TAG, "Checkpoint read failed: ${file.name}: ${e.message}")
                null
            }
            if (data == null) {
                val quarantine = File(dir, file.name.removeSuffix(CHECKPOINT_SUFFIX) + ".checkpoint.corrupt")
                if (!file.renameTo(quarantine)) {
                    Log.w(TAG, "Corrupt checkpoint quarantine rename failed: ${file.name}")
                } else {
                    Log.w(TAG, "Corrupt checkpoint quarantined: ${file.name} -> ${quarantine.name}")
                }
                corrupt++
                continue
            }
            val ageMs = nowWallMs - file.lastModified()
            if (ageMs < MAX_RESUME_AGE_MS) {
                freshCandidates.add(file to data)
            } else {
                finalizeInterrupted(file, data)
                finalizedInterrupted++
            }
        }
        // Newest fresh candidate resumes; older fresh ones finalize as interrupted.
        freshCandidates.sortByDescending { it.first.lastModified() }
        val resumable = freshCandidates.firstOrNull()?.second
        for (i in 1 until freshCandidates.size) {
            finalizeInterrupted(freshCandidates[i].first, freshCandidates[i].second)
            finalizedInterrupted++
        }
        return RecoveryResult(resumable, finalizedInterrupted, corrupt)
    }

    private fun finalizeInterrupted(checkpoint: File, data: SessionData) {
        val endTime = Instant.ofEpochMilli(checkpoint.lastModified()).toString()
        finalizeSync(data.copy(endTime = endTime))
    }

    /**
     * Final session files only (no checkpoints, no .tmp, no .corrupt),
     * newest first — the Phase-5 history/upload seam (UPL-04), no UI in v1.
     * No purge/deletion API: keep all sessions in v1 (locked decision).
     */
    fun listFinalSessions(): List<File> {
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.endsWith(FINAL_SUFFIX) && !it.name.endsWith(CHECKPOINT_SUFFIX) }
            .sortedByDescending { it.lastModified() }
    }

    /**
     * Graceful bounded executor shutdown: shutdown() NOT shutdownNow() — a
     * queued final checkpoint must be allowed to complete during service
     * teardown (documented divergence from DiskTileCache.shutdownNow) — then
     * await the drain for up to [timeoutMs] so onDestroy never returns while
     * the teardown checkpoint is still mid-write (WR-01). Logs on timeout.
     */
    fun shutdownAndAwait(timeoutMs: Long) {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Write executor drain timed out after ${timeoutMs}ms — a queued write may be incomplete")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
