package com.rokid.hud.phone.strava

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * UPL-02: [UploadResponse] Gson model for POST /uploads + GET /uploads/{id_str}.
 *
 * Parses the three documented Strava upload response shapes (05-RESEARCH Code
 * Examples) with a plain `Gson().fromJson`:
 *  - (a) initial "still being processed" (activity_id null, id_str verbatim, error null)
 *  - (b) terminal ready (status "Your activity is ready.", activity_id set)
 *  - (c) terminal duplicate error (status error, error string carries the trailing
 *        activity id, activity_id null)
 *
 * Guards the contract sharp edges (Pitfall 3):
 *  - id_str parses VERBATIM (a 64-bit id must not be truncated on the poll path)
 *  - activity_id is nullable (absent until Ready)
 *  - a JSON missing a field yields null without throwing (Gson-via-Unsafe caveat,
 *    same discipline as [StravaRouteModelTest]/[StravaModelsTest]).
 *
 * Pure JVM, no android.*.
 */
class StravaUploadModelTest {

    private val gson = Gson()

    /** (a) Initial POST /uploads 201 body: enqueued, still processing, no activity yet. */
    private val processingJson = """
        {
          "id": 41234567890123456,
          "id_str": "41234567890123456",
          "external_id": "ride-20260703.gpx",
          "error": null,
          "status": "Your activity is still being processed.",
          "activity_id": null
        }
    """.trimIndent()

    /** (b) Terminal ready poll body: processing finished, activity_id populated. */
    private val readyJson = """
        {
          "id": 41234567890123456,
          "id_str": "41234567890123456",
          "external_id": "ride-20260703.gpx",
          "error": null,
          "status": "Your activity is ready.",
          "activity_id": 987654321012345
        }
    """.trimIndent()

    /** (c) Terminal duplicate-error poll body: filename-prefixed error, no activity_id. */
    private val duplicateJson = """
        {
          "id": 41234567890123456,
          "id_str": "41234567890123456",
          "external_id": "ride-20260703.gpx",
          "error": "Test_Walk.gpx duplicate of activity 21234316",
          "status": "There was an error processing your activity.",
          "activity_id": null
        }
    """.trimIndent()

    @Test
    fun processingBodyParsesWithNullActivityIdAndVerbatimIdStr() {
        val r = gson.fromJson(processingJson, UploadResponse::class.java)
        assertNotNull(r)
        // id_str is preserved verbatim (this is what the poll URL uses — Pitfall 3).
        assertEquals("41234567890123456", r.idStr)
        assertEquals(41234567890123456L, r.id)
        assertEquals("ride-20260703.gpx", r.externalId)
        assertNull(r.error)
        assertEquals("Your activity is still being processed.", r.status)
        // activity_id is absent until Ready.
        assertNull(r.activityId)
    }

    @Test
    fun readyBodyCarriesActivityId() {
        val r = gson.fromJson(readyJson, UploadResponse::class.java)
        assertNotNull(r)
        assertEquals("Your activity is ready.", r.status)
        assertNotNull(r.activityId)
        // A 64-bit activity id must not truncate.
        assertEquals(987654321012345L, r.activityId)
        assertNull(r.error)
    }

    @Test
    fun duplicateErrorBodyCarriesTrailingActivityIdInErrorString() {
        val r = gson.fromJson(duplicateJson, UploadResponse::class.java)
        assertNotNull(r)
        assertEquals("There was an error processing your activity.", r.status)
        assertNull(r.activityId)
        assertNotNull(r.error)
        // The activity id is at the END of the (filename-prefixed) error string —
        // GpxWriter.parseDuplicateActivityId recovers it (unanchored regex).
        assertEquals(21234316L, GpxWriter.parseDuplicateActivityId(r.error))
    }

    @Test
    fun idStrParsesVerbatimNoTruncation() {
        // The 64-bit id and id_str must agree; id_str is the safe one for the poll URL.
        val r = gson.fromJson(processingJson, UploadResponse::class.java)
        assertEquals("41234567890123456", r.idStr)
        assertEquals(r.id.toString(), r.idStr)
    }

    @Test
    fun missingFieldParsesToNullWithoutThrowing() {
        // A body with no external_id (and no activity_id/error) must not throw — the
        // missing keys land as null (Gson-via-Unsafe; a field rename returns null).
        val partial = """{ "id_str": "999", "status": "Your activity is still being processed." }"""
        val r = gson.fromJson(partial, UploadResponse::class.java)
        assertNotNull(r)
        assertEquals("999", r.idStr)
        assertEquals("Your activity is still being processed.", r.status)
        assertNull(r.externalId)
        assertNull(r.error)
        assertNull(r.activityId)
        assertNull(r.id)
    }
}
