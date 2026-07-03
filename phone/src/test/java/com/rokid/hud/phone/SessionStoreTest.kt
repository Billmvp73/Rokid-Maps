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
}
