package com.rokid.hud.phone.strava

import com.rokid.hud.shared.protocol.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RIMP-03a: GPX <trkpt> extraction via XmlPullParserFactory (JVM-testable with
 * kxml2 on the test classpath — NOT android.util.Xml, which throws Stub! on the
 * JVM, per 04-RESEARCH Anti-Pattern).
 *
 * Covers: single-trkseg document order, multi-trkseg flatten, <ele> present/absent,
 * malformed/empty → emptyList (never throws), coordinate range/finite validation
 * (Security V5 — untrusted XML from the network).
 *
 * Pure JVM, no android.* references in the test.
 */
class GpxParserTest {

    private val singleSeg = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="StravaGPX">
          <trk>
            <name>Ride</name>
            <trkseg>
              <trkpt lat="37.7749" lon="-122.4194"><ele>10.0</ele></trkpt>
              <trkpt lat="37.7750" lon="-122.4195"><ele>11.0</ele></trkpt>
              <trkpt lat="37.7751" lon="-122.4196"><ele>12.0</ele></trkpt>
            </trkseg>
          </trk>
        </gpx>
    """.trimIndent()

    private val multiSeg = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1">
          <trk>
            <trkseg>
              <trkpt lat="10.0" lon="20.0"></trkpt>
              <trkpt lat="10.1" lon="20.1"></trkpt>
            </trkseg>
            <trkseg>
              <trkpt lat="10.2" lon="20.2"></trkpt>
              <trkpt lat="10.3" lon="20.3"></trkpt>
            </trkseg>
          </trk>
        </gpx>
    """.trimIndent()

    /** No <ele> children at all — parse must still succeed. */
    private val noEle = """
        <gpx version="1.1"><trk><trkseg>
          <trkpt lat="48.8584" lon="2.2945"/>
          <trkpt lat="48.8585" lon="2.2946"/>
        </trkseg></trk></gpx>
    """.trimIndent()

    @Test
    fun singleSegExtractsAllPointsInDocumentOrder() {
        val pts = GpxParser.parse(singleSeg)
        assertEquals(3, pts.size)
        assertEquals(Waypoint(37.7749, -122.4194), pts.first())
        assertEquals(Waypoint(37.7751, -122.4196), pts.last())
        // Attribute order is lat then lon; verify lng did not get swapped in.
        assertEquals(37.7750, pts[1].latitude, 1e-9)
        assertEquals(-122.4195, pts[1].longitude, 1e-9)
    }

    @Test
    fun multiSegFlattensEveryTrkptInOrder() {
        val pts = GpxParser.parse(multiSeg)
        assertEquals(4, pts.size)
        assertEquals(Waypoint(10.0, 20.0), pts.first())
        assertEquals(Waypoint(10.3, 20.3), pts.last())
        // Segment 2's first point must follow segment 1's last (continuous track).
        assertEquals(Waypoint(10.2, 20.2), pts[2])
    }

    @Test
    fun elePresentOrAbsentBothParse() {
        assertEquals(3, GpxParser.parse(singleSeg).size)
        val noEleParsed = GpxParser.parse(noEle)
        assertEquals(2, noEleParsed.size)
        assertEquals(Waypoint(48.8584, 2.2945), noEleParsed.first())
    }

    @Test
    fun malformedXmlReturnsEmptyListNeverThrows() {
        // Unclosed tags / garbage — must not throw, returns whatever parsed (empty here).
        assertTrue(GpxParser.parse("<gpx><trk><trkseg><trkpt lat=").isEmpty())
        assertTrue(GpxParser.parse("this is not xml at all {}{}{}").isEmpty())
        assertTrue(GpxParser.parse("<<<>>>").isEmpty())
    }

    @Test
    fun emptyOrNoTrkptReturnsEmptyList() {
        assertTrue(GpxParser.parse("").isEmpty())
        assertTrue(GpxParser.parse("<gpx version=\"1.1\"></gpx>").isEmpty())
        assertTrue(GpxParser.parse("<gpx><wpt lat=\"1.0\" lon=\"2.0\"/></gpx>").isEmpty())
    }

    @Test
    fun outOfRangeOrNonFiniteTrkptIsSkipped() {
        val bad = """
            <gpx><trk><trkseg>
              <trkpt lat="91.0" lon="10.0"/>
              <trkpt lat="45.0" lon="181.0"/>
              <trkpt lat="NaN" lon="10.0"/>
              <trkpt lat="45.0" lon="Infinity"/>
              <trkpt lat="-91.0" lon="10.0"/>
              <trkpt lat="45.0" lon="-181.0"/>
              <trkpt lat="notanumber" lon="10.0"/>
              <trkpt lat="45.0" lon="10.0"/>
            </trkseg></trk></gpx>
        """.trimIndent()
        val pts = GpxParser.parse(bad)
        // Only the final in-range, finite point survives.
        assertEquals(1, pts.size)
        assertEquals(Waypoint(45.0, 10.0), pts.first())
    }

    @Test
    fun boundaryCoordinatesAreAccepted() {
        val edge = """
            <gpx><trk><trkseg>
              <trkpt lat="90.0" lon="180.0"/>
              <trkpt lat="-90.0" lon="-180.0"/>
              <trkpt lat="0.0" lon="0.0"/>
            </trkseg></trk></gpx>
        """.trimIndent()
        val pts = GpxParser.parse(edge)
        assertEquals(3, pts.size)
        assertEquals(Waypoint(90.0, 180.0), pts.first())
        assertEquals(Waypoint(-90.0, -180.0), pts[1])
    }
}
