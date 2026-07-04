package com.rokid.hud.phone

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Plain-JVM persistence tests for [SessionStore] (REC-06).
 *
 * SessionStore takes a java.io.File directory (not Context) precisely so these
 * tests run without Robolectric: TemporaryFolder provides the directory, and
 * android.util.Log returns defaults via isReturnDefaultValues=true.
 */
class SessionStoreTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var storeDir: File

    private fun newStore(): SessionStore {
        storeDir = File(tmpFolder.root, "activities")
        return SessionStore(storeDir)
    }

    private fun fullPoint() = TrackPoint(
        lat = 47.6062095,
        lng = -122.3320708,
        alt = 56.5,
        ts = 1_782_000_000_000L,
        speedMps = 5.25,
        accuracyM = 8.0,
        bearingDeg = 271.5
    )

    private fun sentinelPoint() = TrackPoint(
        lat = 47.6070001,
        lng = -122.3330002,
        alt = Double.NaN,
        ts = 1_782_000_001_000L,
        speedMps = Double.NaN,
        accuracyM = -1.0,
        bearingDeg = Double.NaN
    )

    private fun sampleSession(
        id: String = "20260703-154500-abc123",
        endTime: String? = "2026-07-03T16:45:00Z"
    ) = SessionData(
        id = id,
        sport = "ride",
        startTime = "2026-07-03T15:45:00Z",
        endTime = endTime,
        elapsedMs = 3_600_000L,
        movingMs = 3_200_000L,
        distanceM = 25_000.5,
        avgSpeedMps = 6.94,
        stravaUploaded = false,
        trackPoints = listOf(fullPoint(), sentinelPoint())
    )

    // ---------------------------------------------------------------
    // Task 1: JSON serialization + atomic write primitive
    // ---------------------------------------------------------------

    @Test
    fun roundTripPreservesAllFieldsAndPointOrder() {
        val store = newStore()
        val original = sampleSession()
        val restored = store.fromJson(store.toJson(original))
        // Kotlin data-class equals uses Double.compare, so NaN sentinels compare
        // equal to NaN — full structural equality covers every field and order.
        assertEquals(original, restored)
        assertEquals(2, restored!!.trackPoints.size)
        assertEquals(original.trackPoints[0], restored.trackPoints[0])
        assertEquals(original.trackPoints[1], restored.trackPoints[1])
    }

    @Test
    fun jsonContainsSchemaVersionOne() {
        val store = newStore()
        val obj = JSONObject(store.toJson(sampleSession()))
        assertEquals(1, obj.getInt("schemaVersion"))
    }

    @Test
    fun topLevelKeysAreExactlyTheLockedSchema() {
        val store = newStore()
        val obj = JSONObject(store.toJson(sampleSession()))
        val keys = mutableSetOf<String>()
        for (key in obj.keys()) keys.add(key)
        assertEquals(
            setOf(
                "schemaVersion", "id", "sport", "startTime", "endTime",
                "elapsedMs", "movingMs", "distanceM", "avgSpeedMps",
                "stravaUploaded", "trackPoints"
            ),
            keys
        )
    }

    @Test
    fun endTimeOmittedWhenNullAndRoundTripsToNull() {
        val store = newStore()
        val inProgress = sampleSession(endTime = null)
        val obj = JSONObject(store.toJson(inProgress))
        assertFalse("endTime key must be omitted when null", obj.has("endTime"))
        val restored = store.fromJson(store.toJson(inProgress))
        assertNull(restored!!.endTime)
    }

    @Test
    fun nanSentinelKeysOmittedAndRestoredAsNaN() {
        val store = newStore()
        val obj = JSONObject(store.toJson(sampleSession()))
        val points = obj.getJSONArray("trackPoints")

        val full = points.getJSONObject(0)
        val fullKeys = mutableSetOf<String>()
        for (key in full.keys()) fullKeys.add(key)
        assertEquals(setOf("lat", "lng", "alt", "ts", "speed", "acc", "brg"), fullKeys)

        val sentinel = points.getJSONObject(1)
        val sentinelKeys = mutableSetOf<String>()
        for (key in sentinel.keys()) sentinelKeys.add(key)
        // alt/speed/brg omitted when NaN (org.json throws on non-finite doubles);
        // acc uses the finite -1.0 sentinel and is ALWAYS written.
        assertEquals(setOf("lat", "lng", "ts", "acc"), sentinelKeys)
        assertEquals(-1.0, sentinel.getDouble("acc"), 1e-9)

        val restored = store.fromJson(store.toJson(sampleSession()))!!
        val restoredSentinel = restored.trackPoints[1]
        assertTrue(restoredSentinel.alt.isNaN())
        assertTrue(restoredSentinel.speedMps.isNaN())
        assertTrue(restoredSentinel.bearingDeg.isNaN())
        assertEquals(-1.0, restoredSentinel.accuracyM, 1e-9)
    }

    @Test
    fun fromJsonOnCorruptInputReturnsNull() {
        val store = newStore()
        assertNull(store.fromJson("{not json"))
    }

    @Test
    fun fromJsonMissingRequiredIdReturnsNull() {
        val store = newStore()
        val noId = JSONObject().apply {
            put("schemaVersion", 1)
            put("sport", "ride")
            put("startTime", "2026-07-03T15:45:00Z")
        }.toString()
        assertNull(store.fromJson(noId))
    }

    @Test
    fun writeAtomicWritesTargetAndLeavesNoTmpSibling() {
        val store = newStore()
        val json = store.toJson(sampleSession())
        val target = File(storeDir, "session.json")
        store.writeAtomic(target, json)
        assertTrue(target.exists())
        assertEquals(json, target.readText(Charsets.UTF_8))
        val tmpLeftovers = storeDir.listFiles { f -> f.name.endsWith(".tmp") } ?: emptyArray()
        assertEquals(0, tmpLeftovers.size)
    }

    @Test
    fun writeAtomicReplacesExistingTargetContent() {
        val store = newStore()
        val target = File(storeDir, "session.json")
        store.writeAtomic(target, "{\"old\":true}")
        val newJson = store.toJson(sampleSession())
        store.writeAtomic(target, newJson)
        assertEquals(newJson, target.readText(Charsets.UTF_8))
    }

    @Test
    fun stravaUploadedDefaultsToFalseInJson() {
        val store = newStore()
        val obj = JSONObject(store.toJson(sampleSession()))
        assertFalse(obj.getBoolean("stravaUploaded"))
    }

    // ---------------------------------------------------------------
    // Task 2: checkpoint lifecycle, finalize, and orphan recovery
    // ---------------------------------------------------------------

    @Test
    fun writeCheckpointSyncCreatesAndOverwritesSingleCheckpoint() {
        val store = newStore()
        val first = sampleSession(endTime = null)
        store.writeCheckpointSync(first)
        val cp = File(storeDir, first.id + ".checkpoint.json")
        assertTrue(cp.exists())

        val updated = first.copy(elapsedMs = 7_200_000L, distanceM = 50_000.0)
        store.writeCheckpointSync(updated)
        val checkpoints = storeDir.listFiles { f -> f.name.endsWith(".checkpoint.json") }!!
        assertEquals(1, checkpoints.size)
        assertEquals(updated, store.fromJson(checkpoints[0].readText(Charsets.UTF_8)))
    }

    @Test
    fun finalizeSyncWritesFinalAndDeletesCheckpoint() {
        val store = newStore()
        val inProgress = sampleSession(endTime = null)
        store.writeCheckpointSync(inProgress)
        val done = inProgress.copy(endTime = "2026-07-03T16:45:00Z")
        store.finalizeSync(done)

        val finalFile = File(storeDir, done.id + ".json")
        assertTrue(finalFile.exists())
        assertEquals(done, store.fromJson(finalFile.readText(Charsets.UTF_8)))
        assertFalse(File(storeDir, done.id + ".checkpoint.json").exists())
        assertTrue(store.findOrphanCheckpoints().isEmpty())
    }

    @Test
    fun findOrphanCheckpointsExcludesCheckpointsWithFinalFile() {
        val store = newStore()
        val orphan = sampleSession(id = "20260703-130000-orphan", endTime = null)
        val finished = sampleSession(id = "20260703-140000-finish")
        store.writeCheckpointSync(orphan)
        store.writeCheckpointSync(finished)
        // Construct checkpoint+final coexistence directly (finalizeSync would delete the checkpoint)
        store.writeAtomic(File(storeDir, finished.id + ".json"), store.toJson(finished))

        val orphans = store.findOrphanCheckpoints()
        assertEquals(1, orphans.size)
        assertEquals(orphan.id + ".checkpoint.json", orphans[0].name)
    }

    @Test
    fun recoverOrphansResumesCheckpointFresherThanTenMinutes() {
        val store = newStore()
        val data = sampleSession(endTime = null)
        store.writeCheckpointSync(data)
        val cp = File(storeDir, data.id + ".checkpoint.json")
        val now = System.currentTimeMillis()
        assertTrue(cp.setLastModified(now - 5 * 60_000L))

        val result = store.recoverOrphans(now)
        assertEquals(data, result.resumable)
        assertEquals(0, result.finalizedInterrupted)
        assertEquals(0, result.corrupt)
        assertTrue("checkpoint stays in place; the service finalizes via the normal stop path", cp.exists())
    }

    @Test
    fun recoverOrphansFinalizesStaleCheckpointAsInterrupted() {
        val store = newStore()
        val data = sampleSession(endTime = null)
        store.writeCheckpointSync(data)
        val cp = File(storeDir, data.id + ".checkpoint.json")
        val now = System.currentTimeMillis()
        assertTrue(cp.setLastModified(now - 11 * 60_000L))
        val mtime = cp.lastModified()

        val result = store.recoverOrphans(now)
        assertNull(result.resumable)
        assertEquals(1, result.finalizedInterrupted)
        assertEquals(0, result.corrupt)
        assertFalse("stale checkpoint must be deleted after finalize", cp.exists())

        val finalFile = File(storeDir, data.id + ".json")
        assertTrue(finalFile.exists())
        val restored = store.fromJson(finalFile.readText(Charsets.UTF_8))!!
        assertEquals(java.time.Instant.ofEpochMilli(mtime).toString(), restored.endTime)
    }

    @Test
    fun recoverOrphansWithTwoFreshResumesNewestOnly() {
        val store = newStore()
        val older = sampleSession(id = "20260703-100000-older1", endTime = null)
        val newer = sampleSession(id = "20260703-110000-newer1", endTime = null)
        store.writeCheckpointSync(older)
        store.writeCheckpointSync(newer)
        val now = System.currentTimeMillis()
        assertTrue(File(storeDir, older.id + ".checkpoint.json").setLastModified(now - 5 * 60_000L))
        assertTrue(File(storeDir, newer.id + ".checkpoint.json").setLastModified(now - 3 * 60_000L))

        val result = store.recoverOrphans(now)
        assertEquals(newer, result.resumable)
        assertEquals(1, result.finalizedInterrupted)
        assertEquals(0, result.corrupt)
        assertTrue(File(storeDir, older.id + ".json").exists())
        assertFalse(File(storeDir, older.id + ".checkpoint.json").exists())
        assertTrue(File(storeDir, newer.id + ".checkpoint.json").exists())
    }

    @Test
    fun recoverOrphansQuarantinesCorruptCheckpointWithoutThrowing() {
        val store = newStore()
        val cp = File(storeDir, "20260703-120000-bad001.checkpoint.json")
        cp.writeText("{not json at all", Charsets.UTF_8)

        val result = store.recoverOrphans(System.currentTimeMillis())
        assertNull(result.resumable)
        assertEquals(0, result.finalizedInterrupted)
        assertEquals(1, result.corrupt)
        assertFalse(cp.exists())

        val quarantined = File(storeDir, "20260703-120000-bad001.checkpoint.corrupt")
        assertTrue("corrupt bytes preserved for post-mortem", quarantined.exists())
        assertEquals("{not json at all", quarantined.readText(Charsets.UTF_8))
    }

    @Test
    fun listFinalSessionsReturnsOnlyFinalJsonNewestFirst() {
        val store = newStore()
        val a = sampleSession(id = "20260703-150000-aaaaaa")
        val b = sampleSession(id = "20260703-160000-bbbbbb")
        store.finalizeSync(a)
        store.finalizeSync(b)
        store.writeCheckpointSync(sampleSession(id = "20260703-170000-cccccc", endTime = null))
        File(storeDir, "stray.json.tmp").writeText("tmp", Charsets.UTF_8)
        File(storeDir, "20260703-180000-dddddd.checkpoint.corrupt").writeText("x", Charsets.UTF_8)

        val now = System.currentTimeMillis()
        assertTrue(File(storeDir, a.id + ".json").setLastModified(now - 60_000L))
        assertTrue(File(storeDir, b.id + ".json").setLastModified(now))

        val finals = store.listFinalSessions()
        assertEquals(listOf(b.id + ".json", a.id + ".json"), finals.map { it.name })
    }

    @Test
    fun writeCheckpointAsyncEventuallyWritesCheckpoint() {
        val store = newStore()
        val data = sampleSession(endTime = null)
        store.writeCheckpointAsync(data)
        val cp = File(storeDir, data.id + ".checkpoint.json")
        val deadline = System.currentTimeMillis() + 5_000L
        while (!cp.exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(cp.exists())
        assertEquals(data, store.fromJson(cp.readText(Charsets.UTF_8)))
    }

    @Test
    fun finalizeAsyncEventuallyWritesFinalAndDeletesCheckpoint() {
        val store = newStore()
        val data = sampleSession(endTime = null)
        store.writeCheckpointSync(data)
        val done = data.copy(endTime = "2026-07-03T16:45:00Z")
        store.finalizeAsync(done)
        val finalFile = File(storeDir, done.id + ".json")
        val cp = File(storeDir, done.id + ".checkpoint.json")
        val deadline = System.currentTimeMillis() + 5_000L
        while ((!finalFile.exists() || cp.exists()) && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(finalFile.exists())
        assertFalse(cp.exists())
        assertEquals(done, store.fromJson(finalFile.readText(Charsets.UTF_8)))
    }

    // ---------------------------------------------------------------
    // Phase 5 Task 3: readSession + updateUploadState (UPL-03/UPL-04)
    // ---------------------------------------------------------------

    @Test
    fun readSessionReturnsFinalizedSessionData() {
        val store = newStore()
        val data = sampleSession()
        store.finalizeSync(data)
        val restored = store.readSession(data.id)
        // Structural equality covers every field + trackPoint order (NaN compares equal).
        assertEquals(data, restored)
    }

    @Test
    fun readSessionReturnsNullForMissingSession() {
        val store = newStore()
        assertNull(store.readSession("nonexistent-session-id"))
    }

    @Test
    fun updateUploadStateSetsFlagAndWritesActivityIdAndPreservesAllTrackPoints() {
        val store = newStore()
        val data = sampleSession()
        store.finalizeSync(data)
        val activityId = 987654321L

        store.updateUploadStateSync(data.id, activityId)

        // stravaUploaded flips true; readSession sees it.
        val restored = store.readSession(data.id)!!
        assertTrue("stravaUploaded must be true after write-back", restored.stravaUploaded)

        // UPL-03: every original trackPoint is still present (count + a sample point).
        assertEquals(data.trackPoints.size, restored.trackPoints.size)
        assertEquals(data.trackPoints[0].lat, restored.trackPoints[0].lat, 1e-9)
        assertEquals(data.trackPoints[0].ts, restored.trackPoints[0].ts)
        assertEquals(data.trackPoints[1], restored.trackPoints[1])

        // The persisted JSON must contain the strava_activity_id key with the id.
        val rawJson = File(storeDir, data.id + ".json").readText(Charsets.UTF_8)
        assertTrue(
            "persisted JSON must carry strava_activity_id:987654321; got:\n$rawJson",
            rawJson.contains("\"strava_activity_id\":987654321")
        )
    }

    @Test
    fun preExistingFileWithoutActivityIdKeyReadsBackWithoutCrash() {
        val store = newStore()
        // Write an old-shape file (stravaUploaded=false, NO strava_activity_id key)
        // exactly as Phase-1 toJson produced it.
        val old = sampleSession()
        store.writeAtomic(File(storeDir, old.id + ".json"), store.toJson(old))
        assertFalse(store.toJson(old).contains("strava_activity_id"))

        // readSession must succeed (forward-compatible; absent key = not-uploaded-to-known-id).
        val restored = store.readSession(old.id)
        assertEquals(old, restored)
        assertFalse(restored!!.stravaUploaded)
    }

    @Test
    fun updateUploadStateLeavesExactlyOneFinalFileAndNoTmpResidue() {
        val store = newStore()
        val data = sampleSession()
        store.finalizeSync(data)
        store.updateUploadStateSync(data.id, 555L)

        val finalFile = File(storeDir, data.id + ".json")
        assertTrue("final file must exist after atomic write-back", finalFile.exists())
        // Atomic rename must leave no .tmp residue in the dir.
        val tmpLeftovers = storeDir.listFiles { f -> f.name.endsWith(".tmp") } ?: emptyArray()
        assertEquals(0, tmpLeftovers.size)
        // Exactly one final .json for this id.
        val finals = storeDir.listFiles { f -> f.name == data.id + ".json" } ?: emptyArray()
        assertEquals(1, finals.size)
    }

    @Test
    fun updateUploadStateSyncOnMissingSessionIsANoOpAndDoesNotThrow() {
        val store = newStore()
        // No file exists for this id — must not throw, must not create a file.
        store.updateUploadStateSync("no-such-id", 12345L)
        assertNull(store.readSession("no-such-id"))
        assertFalse(File(storeDir, "no-such-id.json").exists())
    }

    @Test
    fun updateUploadStateAsyncEventuallyWritesActivityId() {
        val store = newStore()
        val data = sampleSession()
        store.finalizeSync(data)
        store.updateUploadState(data.id, 42L)

        val finalFile = File(storeDir, data.id + ".json")
        val deadline = System.currentTimeMillis() + 5_000L
        while (!finalFile.readText(Charsets.UTF_8).contains("strava_activity_id") &&
            System.currentTimeMillis() < deadline
        ) Thread.sleep(20)
        val restored = store.readSession(data.id)!!
        assertTrue(restored.stravaUploaded)
        assertTrue(finalFile.readText(Charsets.UTF_8).contains("\"strava_activity_id\":42"))
    }

    @Test
    fun updateUploadStatePreservesAllOtherTopLevelMetrics() {
        val store = newStore()
        val data = sampleSession()
        store.finalizeSync(data)
        store.updateUploadStateSync(data.id, 100L)
        val restored = store.readSession(data.id)!!
        // Every metric other than stravaUploaded is untouched by the write-back.
        assertEquals(data.id, restored.id)
        assertEquals(data.sport, restored.sport)
        assertEquals(data.startTime, restored.startTime)
        assertEquals(data.endTime, restored.endTime)
        assertEquals(data.elapsedMs, restored.elapsedMs)
        assertEquals(data.movingMs, restored.movingMs)
        assertEquals(data.distanceM, restored.distanceM, 1e-9)
        assertEquals(data.avgSpeedMps, restored.avgSpeedMps, 1e-9)
    }
}
