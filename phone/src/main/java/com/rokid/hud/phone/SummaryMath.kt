package com.rokid.hud.phone

import kotlin.math.roundToLong

/**
 * Pure summary math for the activity-summary screen (UPL-01).
 *
 * Avg PACE is DERIVED here because SessionData persists only [avgSpeedMps], never
 * pace (Pitfall 6). The formula MIRRORS ActivitySessionManager.avgPaceMsPerKm
 * EXACTLY — same [PACE_MIN_DISTANCE_M] 100m floor, same roundToLong — so the
 * summary shows the identical number the live HUD showed during the ride
 * (Pitfall 5). The floor makes a sub-100m test walk render "–:––" instead of a
 * garbage pace.
 *
 * Avg SPEED is NOT recomputed here: read SessionData.avgSpeedMps directly (it is
 * already moving-time-based). [avgSpeedMps] is a documenting passthrough so both
 * call sites (HUD + summary) demonstrably use one moving-based number.
 */
object SummaryMath {

    /** Mirrors ActivitySessionManager.PACE_MIN_DISTANCE_M so the floor visibly matches the recorder. */
    const val PACE_MIN_DISTANCE_M = 100.0

    /**
     * Average pace in ms per km, derived from moving time and distance. Below the
     * [PACE_MIN_DISTANCE_M] floor returns 0L (identical to the recorder), else
     * `(movingMs / (distanceM / 1000.0)).roundToLong()`.
     */
    fun avgPaceMsPerKm(movingMs: Long, distanceM: Double): Long =
        if (distanceM < PACE_MIN_DISTANCE_M) 0L
        else (movingMs / (distanceM / 1000.0)).roundToLong()

    /**
     * Passthrough for avg speed: the summary reads SessionData.avgSpeedMps
     * (moving-based) verbatim and does NOT recompute it (Pitfall 5). Present so
     * the "read, don't recompute" contract is explicit and testable.
     */
    fun avgSpeedMps(persistedAvgSpeedMps: Double): Double = persistedAvgSpeedMps
}
