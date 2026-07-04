package com.rokid.hud.phone.strava

import com.rokid.hud.phone.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UPL-02: GPX 1.1 WRITING via XmlPullParserFactory.newSerializer() (JVM-testable
 * with kxml2 on the test classpath — NOT android.util.Xml, which throws Stub! on
 * the JVM, per 04-RESEARCH Anti-Pattern; the *writing* mirror of GpxParserTest).
 *
 * Covers the Pitfall-4 upload-data-loss checklist for the payload side:
 * - EVERY <trkpt> carries a <time> in ISO-8601 UTC (Instant.ofEpochMilli(ts).toString(),
 *   trailing Z / T separator) — a missing time is a hard Strava rejection.
 * - NaN altitude omits <ele> but still emits <time>.
 * - Output round-trips through the shipped GpxParser with the same point count (well-formed).
 * - XML special chars are escaped (serializer.text() auto-escapes) so re-parse never breaks.
 * - isValidForUpload rejects empty / no-trkpt / missing-<time> GPX BEFORE any network cost.
 * - parseDuplicateActivityId extracts the id from a FILENAME-PREFIXED duplicate error (unanchored).
 * - sportType maps ride/run to PascalCase Ride/Run (activity_type is deprecated).
 *
 * Pure JVM, no android.* references in the test.
 */
class GpxWriterTest {

    private fun point(
        lat: Double = 37.7749,
        lng: Double = -122.4194,
        alt: Double = 10.0,
        ts: Long = 1_782_000_000_000L,
        speedMps: Double = 5.0,
        accuracyM: Double = 8.0,
        bearingDeg: Double = 90.0
    ) = TrackPoint(lat, lng, alt, ts, speedMps, accuracyM, bearingDeg)

    private val threePoints = listOf(
        point(lat = 37.7749, lng = -122.4194, ts = 1_782_000_000_000L, alt = 10.0),
        point(lat = 37.7750, lng = -122.4195, ts = 1_782_000_001_000L, alt = 11.0),
        point(lat = 37.7751, lng = -122.4196, ts = 1_782_000_002_000L, alt = 12.0)
    )

    // -------- write: <time> on every trkpt, ISO-8601 UTC shape --------

    @Test
    fun everyTrkptGetsATimeElement() {
        val gpx = GpxWriter.write(threePoints, "ride", "2026-06-20T13:20:00Z")
        // Count the <time> opening tags. 3 points -> 3 <time> elements.
        val timeCount = Regex("<time>").findAll(gpx).count()
        assertEquals(3, timeCount)
    }

    @Test
    fun timeTextIsExactIso8601UtcForAFixedTs() {
        // Instant.ofEpochMilli(1_782_000_000_000L).toString() -> "2026-06-21T00:00:00Z"
        // (verified with the JDK — 1782000000000 ms = 2026-06-21T00:00:00Z UTC).
        val one = listOf(point(ts = 1_782_000_000_000L))
        val gpx = GpxWriter.write(one, "ride", "2026-06-21T00:00:00Z")
        assertTrue(
            "expected the fixed-ts trkpt <time> to be 2026-06-21T00:00:00Z; got:\n$gpx",
            gpx.contains("<time>2026-06-21T00:00:00Z</time>")
        )
        // Assert the ISO shape explicitly: a T separator and a trailing Z.
        val timeText = Regex("<time>([^<]+)</time>").find(gpx)!!.groupValues[1]
        assertTrue("time must contain a T separator: $timeText", timeText.contains("T"))
        assertTrue("time must end in Z (UTC): $timeText", timeText.endsWith("Z"))
    }

    @Test
    fun nanAltitudeOmitsEleButStillEmitsTime() {
        val pts = listOf(
            point(lat = 45.0, lng = 9.0, alt = Double.NaN, ts = 1_782_000_000_000L),
            point(lat = 45.1, lng = 9.1, alt = 123.5, ts = 1_782_000_001_000L)
        )
        val gpx = GpxWriter.write(pts, "run", "2026-06-20T13:20:00Z")
        // Exactly one <ele> (only the finite-alt point), its value written.
        assertEquals(1, Regex("<ele>").findAll(gpx).count())
        assertTrue("finite-alt point must emit its <ele> value", gpx.contains("<ele>123.5</ele>"))
        // Both points still get a <time>.
        assertEquals(2, Regex("<time>").findAll(gpx).count())
    }

    // -------- round-trip through the shipped GpxParser --------

    @Test
    fun outputRoundTripsThroughGpxParserWithSamePointCount() {
        val gpx = GpxWriter.write(threePoints, "ride", "2026-06-20T13:20:00Z")
        val parsed = GpxParser.parse(gpx)
        assertEquals(3, parsed.size)
        // All coordinates finite and in the same order (well-formed output).
        assertEquals(37.7749, parsed.first().latitude, 1e-9)
        assertEquals(-122.4194, parsed.first().longitude, 1e-9)
        assertEquals(37.7751, parsed.last().latitude, 1e-9)
        assertEquals(-122.4196, parsed.last().longitude, 1e-9)
    }

    @Test
    fun specialCharacterInSportIsXmlEscapedAndOutputStaysWellFormed() {
        // An ampersand in the sport string must be escaped by serializer.text(),
        // so the produced GPX still round-trips through the parser (no raw & break).
        val gpx = GpxWriter.write(threePoints, "ride & run", "2026-06-20T13:20:00Z")
        assertFalse("raw unescaped ampersand would break re-parse", gpx.contains("ride & run"))
        assertTrue("ampersand must be XML-escaped", gpx.contains("ride &amp; run"))
        // The output is still well-formed: GpxParser recovers all 3 points.
        assertEquals(3, GpxParser.parse(gpx).size)
    }

    // -------- isValidForUpload guard --------

    @Test
    fun isValidForUploadRejectsEmpty() {
        assertFalse(GpxWriter.isValidForUpload(""))
        assertFalse(GpxWriter.isValidForUpload("   "))
    }

    @Test
    fun isValidForUploadRejectsTrkptWithoutTime() {
        val noTime = """
            <gpx version="1.1"><trk><trkseg>
              <trkpt lat="45.0" lon="9.0"><ele>10.0</ele></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()
        assertFalse(GpxWriter.isValidForUpload(noTime))
    }

    @Test
    fun isValidForUploadRejectsGpxWithNoTrkpt() {
        assertFalse(GpxWriter.isValidForUpload("<gpx version=\"1.1\"></gpx>"))
    }

    @Test
    fun isValidForUploadAcceptsWellFormedMultiPointGpx() {
        val gpx = GpxWriter.write(threePoints, "ride", "2026-06-20T13:20:00Z")
        assertTrue(GpxWriter.isValidForUpload(gpx))
    }

    @Test
    fun isValidForUploadRejectsWhenAnySingleTrkptLacksTime() {
        // First point has time, second does not -> the whole GPX is invalid.
        val mixed = """
            <gpx version="1.1"><trk><trkseg>
              <trkpt lat="45.0" lon="9.0"><time>2026-06-20T13:20:00Z</time></trkpt>
              <trkpt lat="45.1" lon="9.1"><ele>5.0</ele></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()
        assertFalse(GpxWriter.isValidForUpload(mixed))
    }

    // -------- parseDuplicateActivityId (unanchored) --------

    @Test
    fun parseDuplicateActivityIdExtractsFromFilenamePrefixedError() {
        // Filename FIRST — the regex must be unanchored (Pitfall 2 / 05-RESEARCH catch).
        assertEquals(
            21234316L,
            GpxWriter.parseDuplicateActivityId("ride-20260620.gpx duplicate of activity 21234316")
        )
    }

    @Test
    fun parseDuplicateActivityIdReturnsNullForNonDuplicateError() {
        assertNull(GpxWriter.parseDuplicateActivityId("There was an error processing your activity."))
    }

    @Test
    fun parseDuplicateActivityIdReturnsNullForNull() {
        assertNull(GpxWriter.parseDuplicateActivityId(null))
    }

    // -------- sportType map (PascalCase) --------

    @Test
    fun sportTypeMapsRunAndRideToPascalCase() {
        assertEquals("Run", GpxWriter.sportType("run"))
        assertEquals("Ride", GpxWriter.sportType("ride"))
    }

    @Test
    fun sportTypeDefaultsToRideForUnknown() {
        assertEquals("Ride", GpxWriter.sportType("anything-else"))
    }
}
