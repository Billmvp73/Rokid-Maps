package com.rokid.hud.glasses

import com.rokid.hud.shared.protocol.Waypoint
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [RouteBounds] — the fit-to-bounds projection seam for the WHOLE_ROUTE
 * birdview (D1). RouteBounds is android-free, so this runs on plain JVM like SportFormatTest
 * (no android.jar stubs). The actual drawWholeRoute path needs a Canvas and is screencap-verified
 * on hardware by the orchestrator; the projection MATH is the extracted testable part.
 */
class RouteBoundsTest {

    private fun inRange(v: Float, lo: Float, hi: Float): Boolean =
        v.isFinite() && v >= lo && v <= hi

    @Test
    fun singlePointCentersWithoutNaN() {
        val projector = RouteBounds.fit(listOf(Waypoint(37.0, -122.0)), 400f, 400f, 20f)
        assertNotNull("single-point fit must not be null", projector)
        val xy = projector!!.project(37.0, -122.0)
        assertTrue("x must be finite", xy.x.isFinite())
        assertTrue("y must be finite", xy.y.isFinite())
        assertTrue("x within padded bounds, was ${xy.x}", inRange(xy.x, 20f, 380f))
        assertTrue("y within padded bounds, was ${xy.y}", inRange(xy.y, 20f, 380f))
    }

    @Test
    fun twoPointsFitInsideBoundsAndNorthUp() {
        val lowLatEastLng = Waypoint(37.0, -122.0)
        val highLatWestLng = Waypoint(37.1, -121.9) // larger lat, larger lng
        val projector = RouteBounds.fit(listOf(lowLatEastLng, highLatWestLng), 400f, 400f, 20f)
        assertNotNull(projector)

        val a = projector!!.project(lowLatEastLng.latitude, lowLatEastLng.longitude)
        val b = projector.project(highLatWestLng.latitude, highLatWestLng.longitude)

        // Both extremes fit inside the padded view.
        assertTrue("a.x in bounds, was ${a.x}", inRange(a.x, 20f, 380f))
        assertTrue("a.y in bounds, was ${a.y}", inRange(a.y, 20f, 380f))
        assertTrue("b.x in bounds, was ${b.x}", inRange(b.x, 20f, 380f))
        assertTrue("b.y in bounds, was ${b.y}", inRange(b.y, 20f, 380f))

        // North up: the LARGER-latitude point (b) has the SMALLER screen y.
        assertTrue("larger latitude must map to smaller y (north up): a.y=${a.y} b.y=${b.y}", b.y < a.y)
        // The LARGER-longitude point (b) has the LARGER x.
        assertTrue("larger longitude must map to larger x: a.x=${a.x} b.x=${b.x}", b.x > a.x)
    }

    @Test
    fun emptyReturnsNull() {
        assertNull(RouteBounds.fit(emptyList(), 400f, 400f, 20f))
    }
}
