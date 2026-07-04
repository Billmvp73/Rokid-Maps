package com.rokid.hud.phone.strava

import android.util.Log
import com.rokid.hud.shared.protocol.Waypoint
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Pure GPX <trkpt> extractor. String in -> ordered List<Waypoint> out.
 *
 * Uses [XmlPullParserFactory] rather than the framework Xml helper (which throws
 * `RuntimeException: Stub!` on the JVM test classpath — 04-RESEARCH Anti-Pattern),
 * so kxml2-on-test-classpath and Android's built-in kxml2 (prod) both satisfy it.
 * This is what makes the parser JVM-unit-testable.
 *
 * Security (V5, untrusted XML from the network):
 * - isNamespaceAware = false; DOCTYPE/external-entity processing is NEVER enabled
 *   (XmlPullParser resolves no external entities by default — defends against XXE).
 *   The doc-declaration-processing feature is left at its safe default (off).
 * - Every <trkpt> is coordinate-validated (finite, lat in -90..90, lng in -180..180);
 *   invalid points are skipped so NaN/out-of-range values never reach OSRM or the map.
 *
 * Never throws (CLAUDE.md convention): any parse failure is logged via Log.w and
 * whatever was collected so far is returned (graceful empty on malformed input).
 *
 * Multi-<trkseg> is handled implicitly: every <trkpt> anywhere is collected in
 * document order (v1 continuous-track assumption — 04-RESEARCH A6).
 */
object GpxParser {

    private const val TAG = "GpxParser"

    /** Extracts ordered (lat, lng) from all <trkpt> across all <trkseg>. Never throws. */
    fun parse(gpx: String): List<Waypoint> {
        val out = ArrayList<Waypoint>()
        try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            val parser = factory.newPullParser()
            parser.setInput(StringReader(gpx))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "trkpt") {
                    val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                    val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                    if (isValidCoordinate(lat, lon)) {
                        out.add(Waypoint(lat!!, lon!!))
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "GPX parse failed: ${e.message}")
        }
        return out
    }

    /** Finite and within WGS84 range. Rejects null, NaN, +/-Infinity, out-of-bounds. */
    private fun isValidCoordinate(lat: Double?, lon: Double?): Boolean {
        if (lat == null || lon == null) return false
        if (!lat.isFinite() || !lon.isFinite()) return false
        return lat in -90.0..90.0 && lon in -180.0..180.0
    }
}
