package com.rokid.hud.glasses

import com.rokid.hud.shared.protocol.SportStateMessage
import com.rokid.hud.shared.protocol.StepInfo
import com.rokid.hud.shared.protocol.Waypoint

enum class MapLayoutMode {
    FULL_SCREEN,
    SMALL_CORNER,
    /** Tap-cycle only: pure sport metrics, no map (HUD-01..HUD-04) */
    SPORT,
    /** Tap/swipe-cycle only: bird's-eye view of the entire route fit-to-bounds (D1). */
    WHOLE_ROUTE,
    /** Phone-controlled: 25% map strip at bottom, direction+distance text, no notifications */
    MINI_BOTTOM,
    /** Phone-controlled: bottom 25% split — map on left, directions on right, no notifications */
    MINI_SPLIT
}

/**
 * What the SPORT layout renders for the sport metrics, decided by the
 * precedence ladder in [HudState.sportDisplayMode]:
 * NOT_RECORDING (never received, or st == idle) > FINISHED (immune to
 * staleness) > STALE_NO_DATA (last sport_state older than 10s) >
 * STALE_DIM (older than 3s) > LIVE.
 */
enum class SportDisplayMode {
    NOT_RECORDING,
    FINISHED,
    LIVE,
    STALE_DIM,
    STALE_NO_DATA
}

data class NotificationItem(
    val title: String?,
    val text: String?,
    val packageName: String?,
    val timeMs: Long
)

data class HudState(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val bearing: Float = 0f,
    val speed: Float = 0f,
    val accuracy: Float = 0f,
    val waypoints: List<Waypoint> = emptyList(),
    /**
     * The ORIGINAL imported route, set once from a full-flagged route message; rendered only by
     * WHOLE_ROUTE. NOT overwritten by reroutes (D4) — the live [waypoints] keep updating while
     * this birdview source stays pinned to the route the ride started with.
     */
    val wholeRoute: List<Waypoint> = emptyList(),
    val totalDistance: Double = 0.0,
    val totalDuration: Double = 0.0,
    val instruction: String = "",
    val maneuver: String = "",
    val stepDistance: Double = 0.0,
    val notifications: List<NotificationItem> = emptyList(),
    val layoutMode: MapLayoutMode = MapLayoutMode.FULL_SCREEN,
    val ttsEnabled: Boolean = false,
    val useImperial: Boolean = false,
    val streamNotifications: Boolean = true,
    val showUpcomingSteps: Boolean = false,
    val allSteps: List<StepInfo> = emptyList(),
    val currentStepIndex: Int = 0,
    val batteryLevel: Int = -1,
    val btConnected: Boolean = false,
    val wifiConnected: Boolean = false,
    val speedLimitKmh: Int = -1,
    val distToNextStep: Double = -1.0,
    val showTurnAlert: Boolean = false,
    val tileCacheSizeMb: Int = 100,
    val showSpeed: Boolean = true,
    val showSpeedLimit: Boolean = true,
    /** When set, shown prominently (e.g. "Rokid Maps is closing") before app exits. */
    val closingMessage: String? = null,
    // Sport HUD (Phase 2) — fed only by sport_state messages
    val elapsedMs: Long = 0L,
    /** Carried for protocol completeness; NOT rendered in v1 (moving time is summary-only). */
    val movingMs: Long = 0L,
    val distanceM: Double = 0.0,
    val currentSpeedMps: Double = 0.0,
    /** 0 = unset (the phone's below-100m pace floor). */
    val avgPaceMsPerKm: Long = 0L,
    val sessionState: String = "idle",
    val sport: String = "ride",
    /** SystemClock.elapsedRealtime() at receipt, injected by the call site; 0 = never received. */
    val lastSportStateAtMs: Long = 0L
) {
    companion object {
        const val MAX_NOTIFICATIONS = 8
        /** Last sport_state older than this while in SPORT mode -> values dim. */
        const val SPORT_STALE_DIM_MS = 3_000L
        /** Older than this -> "NO DATA" replaces the primary numeral. */
        const val SPORT_STALE_NODATA_MS = 10_000L
    }

    fun withNotification(item: NotificationItem): HudState {
        val updated = (listOf(item) + notifications).take(MAX_NOTIFICATIONS)
        return copy(notifications = updated)
    }

    /**
     * Maps a decoded sport_state message into the HUD state and stamps the
     * receipt clock. [nowMs] is INJECTED (SystemClock.elapsedRealtime() at the
     * BluetoothClient call site) so this stays a pure, JVM-testable function.
     */
    fun applySportState(msg: SportStateMessage, nowMs: Long): HudState = copy(
        elapsedMs = msg.elapsedMs,
        movingMs = msg.movingMs,
        distanceM = msg.distanceM,
        currentSpeedMps = msg.currentSpeedMps,
        avgPaceMsPerKm = msg.avgPaceMsPerKm,
        sessionState = msg.sessionState,
        sport = msg.sport,
        lastSportStateAtMs = nowMs
    )

    /**
     * Precedence ladder for the SPORT layout (see [SportDisplayMode]).
     * Unknown future sessionState strings deliberately fall through to the
     * staleness branches — lenient posture matching ProtocolCodec.
     */
    fun sportDisplayMode(nowMs: Long): SportDisplayMode = when {
        lastSportStateAtMs == 0L || sessionState == "idle" -> SportDisplayMode.NOT_RECORDING
        sessionState == "finished" -> SportDisplayMode.FINISHED
        nowMs - lastSportStateAtMs > SPORT_STALE_NODATA_MS -> SportDisplayMode.STALE_NO_DATA
        nowMs - lastSportStateAtMs > SPORT_STALE_DIM_MS -> SportDisplayMode.STALE_DIM
        else -> SportDisplayMode.LIVE
    }

    /**
     * Forward tap/swipe cycle (D2): FULL_SCREEN -> SMALL_CORNER -> SPORT -> WHOLE_ROUTE ->
     * FULL_SCREEN. The phone-driven MINI_* modes are not part of the cycle — a tap from either
     * returns to FULL_SCREEN so they never strand the cycle. Exhaustive `when` (no else).
     */
    fun toggleLayout(): HudState = copy(
        layoutMode = when (layoutMode) {
            MapLayoutMode.FULL_SCREEN -> MapLayoutMode.SMALL_CORNER
            MapLayoutMode.SMALL_CORNER -> MapLayoutMode.SPORT
            MapLayoutMode.SPORT -> MapLayoutMode.WHOLE_ROUTE
            MapLayoutMode.WHOLE_ROUTE -> MapLayoutMode.FULL_SCREEN
            MapLayoutMode.MINI_BOTTOM -> MapLayoutMode.FULL_SCREEN
            MapLayoutMode.MINI_SPLIT -> MapLayoutMode.FULL_SCREEN
        }
    )

    /**
     * Reverse tap/swipe cycle (D3), the exact inverse of [toggleLayout]:
     * FULL_SCREEN -> WHOLE_ROUTE -> SPORT -> SMALL_CORNER -> FULL_SCREEN. MINI_* also collapse to
     * FULL_SCREEN so a phone mode is never stranded in the cycle. Exhaustive `when` (no else).
     */
    fun toggleLayoutBack(): HudState = copy(
        layoutMode = when (layoutMode) {
            MapLayoutMode.FULL_SCREEN -> MapLayoutMode.WHOLE_ROUTE
            MapLayoutMode.WHOLE_ROUTE -> MapLayoutMode.SPORT
            MapLayoutMode.SPORT -> MapLayoutMode.SMALL_CORNER
            MapLayoutMode.SMALL_CORNER -> MapLayoutMode.FULL_SCREEN
            MapLayoutMode.MINI_BOTTOM -> MapLayoutMode.FULL_SCREEN
            MapLayoutMode.MINI_SPLIT -> MapLayoutMode.FULL_SCREEN
        }
    )
}
