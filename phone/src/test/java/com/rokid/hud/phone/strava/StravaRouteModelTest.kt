package com.rokid.hud.phone.strava

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RIMP-01/02: StravaRoute Gson models + pure routes/export URL builders.
 *
 * Guards the two documented Strava contract sharp edges (04-RESEARCH Pitfalls 4 & 6):
 *  - type / sub_type are INTEGERS (1=ride 2=run; 1=road..5=mixed), NOT strings
 *  - id_str (String) is preserved verbatim (no 64-bit truncation vs the numeric id)
 *  - routes-list URL is the SINGULAR /athlete/routes (the by-id form 403s)
 *  - export URL uses id_str in the path
 *
 * Mirrors StravaModelsTest discipline: all fields nullable, Gson-via-Unsafe caveat,
 * partial JSON parses to nulls without throwing. Pure JVM, no android.*
 */
class StravaRouteModelTest {

    private val gson = Gson()

    /** A representative single-route object (a huge 64-bit id to expose truncation). */
    private val singleRouteJson = """
        {
          "id": 41234567890123456,
          "id_str": "41234567890123456",
          "name": "Sunday Loop",
          "distance": 42195.0,
          "elevation_gain": 512.5,
          "type": 1,
          "sub_type": 4,
          "private": false,
          "starred": true,
          "map": { "summary_polyline": "abc_def~123" }
        }
    """.trimIndent()

    @Test
    fun singleRouteParsesAllFields() {
        val r = gson.fromJson(singleRouteJson, StravaRoute::class.java)
        assertNotNull(r)
        assertEquals(41234567890123456L, r.id)
        assertEquals("41234567890123456", r.idStr)
        assertEquals("Sunday Loop", r.name)
        assertEquals(42195.0, r.distance!!, 1e-6)
        assertEquals(512.5, r.elevationGain!!, 1e-6)
        assertEquals(false, r.isPrivate)
        assertEquals(true, r.starred)
        assertNotNull(r.map)
        assertEquals("abc_def~123", r.map!!.summaryPolyline)
    }

    @Test
    fun typeAndSubTypeAreIntegersNotStrings() {
        val r = gson.fromJson(singleRouteJson, StravaRoute::class.java)
        // Deserialize as Int? — 1=ride, sub 4=trail. A String model would fail this.
        assertEquals(1, r.type)
        assertEquals(4, r.subType)
    }

    @Test
    fun idStrPreservedVerbatimNoTruncation() {
        // The 64-bit id and the id_str string must agree; id_str is the safe one for URLs.
        val r = gson.fromJson(singleRouteJson, StravaRoute::class.java)
        assertEquals("41234567890123456", r.idStr)
        assertEquals(r.id.toString(), r.idStr)
    }

    @Test
    fun partialJsonParsesToNullsWithoutThrowing() {
        val partial = """{ "id_str": "999", "name": "Bare" }"""
        val r = gson.fromJson(partial, StravaRoute::class.java)
        assertNotNull(r)
        assertEquals("999", r.idStr)
        assertEquals("Bare", r.name)
        assertNull(r.distance)
        assertNull(r.type)
        assertNull(r.subType)
        assertNull(r.map)
        assertNull(r.starred)
    }

    @Test
    fun jsonArrayParsesToListOfRoutes() {
        val arrJson = """
            [
              { "id_str": "1", "name": "A", "type": 1 },
              { "id_str": "2", "name": "B", "type": 2 }
            ]
        """.trimIndent()
        val routes = gson.fromJson(arrJson, Array<StravaRoute>::class.java).toList()
        assertEquals(2, routes.size)
        assertEquals("A", routes[0].name)
        assertEquals(1, routes[0].type)
        assertEquals("B", routes[1].name)
        assertEquals(2, routes[1].type)
    }

    @Test
    fun typeLabelMapsRideRunAndFallback() {
        assertEquals("Ride", StravaRoute(null, null, null, null, null, 1, null, null, null, null).typeLabel())
        assertEquals("Run", StravaRoute(null, null, null, null, null, 2, null, null, null, null).typeLabel())
        assertEquals("Route", StravaRoute(null, null, null, null, null, null, null, null, null, null).typeLabel())
        assertEquals("Route", StravaRoute(null, null, null, null, null, 9, null, null, null, null).typeLabel())
    }

    // ------------------------------------------------------------------
    // Pure URL builders (single source of truth Plan 04's network calls reuse)
    // ------------------------------------------------------------------

    @Test
    fun buildRoutesUrlIsSingularAthleteRoutesWithPaging() {
        // Singular /athlete/routes (NOT /athletes/{id}/routes — that 403s; Pitfall 6).
        assertEquals(
            "https://www.strava.com/api/v3/athlete/routes?per_page=30&page=1",
            buildRoutesUrl(1, 30)
        )
        assertEquals(
            "https://www.strava.com/api/v3/athlete/routes?per_page=200&page=3",
            buildRoutesUrl(3, 200)
        )
    }

    @Test
    fun buildExportGpxUrlUsesIdStrInPath() {
        // Uses the id_str argument verbatim (64-bit safe; Pitfall 4).
        assertEquals(
            "https://www.strava.com/api/v3/routes/41234567890123456/export_gpx",
            buildExportGpxUrl("41234567890123456")
        )
        assertEquals(
            "https://www.strava.com/api/v3/routes/999/export_gpx",
            buildExportGpxUrl("999")
        )
    }
}
