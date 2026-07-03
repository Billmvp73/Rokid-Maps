package com.rokid.hud.glasses

import java.util.Locale

/**
 * Formatters for the SPORT layout mode's metric strings.
 *
 * Pure Kotlin (no android.*) so plain-JVM tests cover it — SessionModels.kt
 * convention. DateUtils.formatElapsedTime is deliberately NOT used: it is an
 * android.jar stub that throws in JVM unit tests, so elapsed time is
 * hand-rolled here. Conversion constants equal the values already used in
 * HudView.drawStatusBar so the SPORT numerals never disagree with the status
 * strip. All String.format calls pin Locale.US: comma-decimal locales would
 * break the worked-vector tests, and Arabic-family device locales would emit
 * non-Latin digits the glasses typeface cannot render (02-REVIEW WR-02).
 */
object SportFormat {

    private const val MPS_TO_KMH = 3.6
    private const val MPS_TO_MPH = 2.23694
    private const val KM_TO_MI = 1.609344

    /**
     * Moving/stopped dot threshold in m/s. Owned here because only the dot
     * formatter consumes it — the phone owns the real hysteresis.
     */
    const val MOVING_DOT_THRESHOLD_MPS = 0.7

    /** Elapsed activity time as H:MM:SS; hours roll unbounded past 9:59:59. */
    fun formatElapsed(ms: Long): String {
        val totalSec = ms / 1000
        return String.format(Locale.US, "%d:%02d:%02d", totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)
    }

    /**
     * Average pace as M:SS per km (or per mile when [imperial]). The phone
     * sends ap=0 while distance is below its 100m floor — that unset sentinel
     * (and any negative value) renders as "--:--".
     */
    fun formatPace(msPerKm: Long, imperial: Boolean): String {
        if (msPerKm <= 0L) return "--:--"
        val msPerUnit = if (imperial) (msPerKm * KM_TO_MI).toLong() else msPerKm
        val totalSec = msPerUnit / 1000
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
    }

    fun paceUnit(imperial: Boolean): String = if (imperial) "/mi" else "/km"

    /** Current speed with one decimal, km/h or mph. Locale.US-pinned (WR-02). */
    fun formatSpeed(mps: Double, imperial: Boolean): String =
        String.format(Locale.US, "%.1f", mps * if (imperial) MPS_TO_MPH else MPS_TO_KMH)

    fun speedUnit(imperial: Boolean): String = if (imperial) "mph" else "km/h"

    /** Filled dot while moving (strictly above threshold), hollow when stopped. */
    fun movingDot(currentSpeedMps: Double): String =
        if (currentSpeedMps > MOVING_DOT_THRESHOLD_MPS) "●" else "○"
}
