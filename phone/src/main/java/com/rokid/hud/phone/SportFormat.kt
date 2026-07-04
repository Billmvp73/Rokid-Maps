package com.rokid.hud.phone

/**
 * Single source of truth for the imperial-aware activity metric formatters.
 *
 * Extracted VERBATIM from the four private MainActivity helpers so the
 * activity-summary + history surfaces (Phase 5) and the existing MainActivity
 * card render identical strings. This object has NO Context — the imperial flag
 * is threaded in by the caller (Activity.getPreferences / getSharedPreferences),
 * keeping the formatters pure and JVM-testable. MainActivity keeps its private
 * wrappers (which read isImperial() then delegate here) so every existing call
 * site is untouched.
 *
 * Pace is DERIVED (SummaryMath.avgPaceMsPerKm), not persisted; avg speed is read
 * straight from SessionData.avgSpeedMps (moving-based — never recomputed).
 */
object SportFormat {

    /** Distance: miles/feet (imperial) vs km/meters (metric). */
    fun formatDist(m: Double, imperial: Boolean): String = if (imperial) {
        val feet = m * 3.28084
        val miles = m / 1609.344
        when {
            miles >= 0.1 -> String.format("%.1f mi", miles)
            else -> String.format("%.0f ft", feet)
        }
    } else {
        when {
            m >= 1000 -> String.format("%.1f km", m / 1000)
            else -> String.format("%.0f m", m)
        }
    }

    /** Elapsed/moving time as H:MM:SS. Unit-independent. */
    fun formatElapsed(ms: Long): String {
        val totalSec = ms / 1000
        return String.format("%d:%02d:%02d", totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)
    }

    /** Speed: mph (imperial) vs km/h (metric). */
    fun formatSpeed(mps: Double, imperial: Boolean): String = if (imperial) {
        String.format("%.1f mph", mps * 2.23694)
    } else {
        String.format("%.1f km/h", mps * 3.6)
    }

    /** Pace: min/mi (imperial) vs min/km (metric); "–:–– /km|/mi" when unknown (msPerKm ≤ 0). */
    fun formatPace(msPerKm: Long, imperial: Boolean): String {
        val unit = if (imperial) "/mi" else "/km"
        if (msPerKm <= 0L) return "–:–– $unit"
        val msPerUnit = if (imperial) (msPerKm * 1.609344).toLong() else msPerKm
        val totalSec = msPerUnit / 1000
        return String.format("%d:%02d %s", totalSec / 60, totalSec % 60, unit)
    }
}
