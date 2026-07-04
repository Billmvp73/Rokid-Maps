package com.rokid.hud.phone.strava

import android.util.Log
import com.rokid.hud.phone.TrackPoint
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.io.StringWriter
import java.time.Instant

/**
 * Pure GPX 1.1 writer + upload-payload guards. List<TrackPoint> -> GPX string.
 *
 * The *writing* mirror of [GpxParser]: it obtains a serializer from
 * [XmlPullParserFactory.newInstance] rather than the framework Xml helper (which
 * throws `RuntimeException: Stub!` on the JVM test classpath — 04-RESEARCH
 * Anti-Pattern), so kxml2-on-test-classpath and Android's built-in kxml2 (prod)
 * both satisfy it. This is what makes GPX WRITING JVM-unit-testable.
 *
 * Pitfall-4 (upload data-loss) checklist, payload side:
 * - EVERY <trkpt> gets a <time> in ISO-8601 UTC via Instant.ofEpochMilli(ts).toString()
 *   (ts is location.time, always present — a missing <time> is a hard Strava reject).
 * - <ele> is emitted ONLY when altitude is finite (NaN -> omit).
 * - The serializer's text() auto-escapes XML special chars (no hand-rolled concat).
 * - [isValidForUpload] fails fast BEFORE any network cost: empty / no-trkpt /
 *   any-trkpt-missing-<time> all reject.
 * - [parseDuplicateActivityId] recovers the activity id from a FILENAME-PREFIXED
 *   duplicate error (unanchored regex — the id is at the END).
 * - [sportType] maps the session's lowercase sport to Strava's PascalCase
 *   sport_type (activity_type is deprecated).
 *
 * Never throws (CLAUDE.md convention): any serializer/parse failure is logged via
 * Log.w and whatever was collected is returned. NO GPX/coordinate content is ever
 * logged (threat T-05-03) — only e.message on failure.
 */
object GpxWriter {

    private const val TAG = "GpxWriter"
    private const val NS = "http://www.topografix.com/GPX/1/1"

    // Unanchored on purpose: the Strava error is "<filename> duplicate of activity <id>"
    // — the filename comes FIRST, the numeric id is at the end (Pitfall 2 / 05-RESEARCH).
    private val DUPLICATE = Regex("duplicate of activity (\\d+)")

    /**
     * Serializes [points] to a GPX 1.1 string. Every <trkpt> carries a <time>
     * (ISO-8601 UTC from [TrackPoint.ts]); <ele> only when altitude is finite.
     * [sport] is written as an informational <type> under <trk>. Never throws —
     * on failure returns whatever the StringWriter holds so far.
     *
     * [startTimeIso] is part of the caller contract (the session's start instant)
     * but Strava derives activity time from the per-trkpt <time> values, so it is
     * NOT emitted as a separate <metadata><time> — the trkpt times are the source
     * of truth and the point count == the <time> count (round-trip guard).
     */
    fun write(points: List<TrackPoint>, sport: String, startTimeIso: String): String {
        val writer = StringWriter()
        try {
            val serializer = XmlPullParserFactory.newInstance().newSerializer()
            serializer.setOutput(writer)
            serializer.startDocument("UTF-8", true)
            serializer.setPrefix("", NS) // default namespace, no prefix
            serializer.startTag(NS, "gpx")
            serializer.attribute(null, "version", "1.1")
            serializer.attribute(null, "creator", "RokidHudMaps")
            serializer.startTag(NS, "trk")
            // Informational sport label; text() auto-escapes any special chars.
            serializer.startTag(NS, "type").text(sport).endTag(NS, "type")
            serializer.startTag(NS, "trkseg")
            for (p in points) {
                serializer.startTag(NS, "trkpt")
                serializer.attribute(null, "lat", p.lat.toString())
                serializer.attribute(null, "lon", p.lng.toString())
                if (p.alt.isFinite()) { // NaN altitude -> omit <ele> (locked)
                    serializer.startTag(NS, "ele").text(p.alt.toString()).endTag(NS, "ele")
                }
                // REQUIRED on EVERY point (Pitfall 1). ts is epoch ms, always present.
                serializer.startTag(NS, "time")
                    .text(Instant.ofEpochMilli(p.ts).toString())
                    .endTag(NS, "time")
                serializer.endTag(NS, "trkpt")
            }
            serializer.endTag(NS, "trkseg")
            serializer.endTag(NS, "trk")
            serializer.endTag(NS, "gpx")
            serializer.endDocument()
        } catch (e: Exception) {
            Log.w(TAG, "GPX write failed: ${e.message}")
        }
        return writer.toString()
    }

    /**
     * Fail-fast validity guard run BEFORE the upload POST (no network cost on a
     * bad recording). Returns false if [gpx] is empty/blank, has zero <trkpt>, or
     * if ANY <trkpt> has no <time> child. Never throws -> false on parse failure.
     *
     * XXE-safe: isNamespaceAware=false, no DOCTYPE/external-entity processing
     * (XmlPullParser resolves no external entities by default — mirrors GpxParser).
     */
    fun isValidForUpload(gpx: String): Boolean {
        if (gpx.isBlank()) return false
        return try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            val parser = factory.newPullParser()
            parser.setInput(StringReader(gpx))
            var trkptCount = 0
            var inTrkpt = false
            var currentHasTime = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "trkpt" -> {
                                inTrkpt = true
                                currentHasTime = false
                                trkptCount++
                            }
                            "time" -> if (inTrkpt) currentHasTime = true
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "trkpt") {
                            // Every trkpt MUST have seen a <time> before it closed.
                            if (!currentHasTime) return false
                            inTrkpt = false
                        }
                    }
                }
                event = parser.next()
            }
            trkptCount > 0
        } catch (e: Exception) {
            Log.w(TAG, "GPX validity check failed: ${e.message}")
            false
        }
    }

    /**
     * Extracts the activity id from a Strava duplicate error such as
     * "ride-20260620.gpx duplicate of activity 21234316" -> 21234316. The regex
     * is UNANCHORED (filename first) and captures only \d+ -> toLongOrNull, so a
     * non-numeric/malicious string yields null, never an arbitrary side effect
     * (threat T-05-02). Null-safe.
     */
    fun parseDuplicateActivityId(error: String?): Long? =
        error?.let { DUPLICATE.find(it)?.groupValues?.get(1)?.toLongOrNull() }

    /**
     * Maps the session's lowercase sport to Strava's PascalCase `sport_type`
     * field (activity_type is deprecated). Unknown values default to Ride.
     */
    fun sportType(sport: String): String = when (sport) {
        "run" -> "Run"
        else -> "Ride"
    }
}
