package com.rokid.hud.shared.protocol

data class StateMessage(
    val latitude: Double,
    val longitude: Double,
    val bearing: Float,
    val speed: Float,
    val accuracy: Float,
    val speedLimitKmh: Int = -1,
    val distToNextStep: Double = -1.0
)

data class Waypoint(
    val latitude: Double,
    val longitude: Double
)

data class RouteMessage(
    val waypoints: List<Waypoint>,
    val totalDistance: Double,
    val totalDuration: Double,
    /**
     * True only on the FIRST route broadcast of each navigation start (both the destination and
     * imported-route paths); false on all reroute rebroadcasts. Marks the preserved ORIGINAL route
     * so the glasses can store it as the birdview (WHOLE_ROUTE) source without reroutes clobbering
     * it (D4). Default false keeps every existing call site and legacy decode backward-compatible.
     */
    val full: Boolean = false
)

data class StepMessage(
    val instruction: String,
    val maneuver: String,
    val distance: Double
)

data class NotificationMessage(
    val title: String?,
    val text: String?,
    val packageName: String?,
    val timeMs: Long
)

data class SettingsMessage(
    val ttsEnabled: Boolean,
    val useImperial: Boolean = false,
    val useMiniMap: Boolean = false,
    val miniMapStyle: String = "strip",
    val streamNotifications: Boolean = true,
    val showUpcomingSteps: Boolean = false,
    val showTurnAlert: Boolean = false,
    val tileCacheSizeMb: Int = 100,
    val showSpeed: Boolean = true,
    val showSpeedLimit: Boolean = true
)

data class WifiCredsMessage(
    val ssid: String,
    val passphrase: String,
    val enabled: Boolean
)

data class TileRequestMessage(val id: String, val z: Int, val x: Int, val y: Int)

data class TileResponseMessage(val id: String, val data: String?)

data class StepInfo(
    val instruction: String,
    val maneuver: String,
    val distance: Double
)

data class StepsListMessage(
    val steps: List<StepInfo>,
    val currentIndex: Int
)

data class ApkStartMessage(val totalSize: Long, val totalChunks: Int)
data class ApkChunkMessage(val index: Int, val data: String)
data class ApkEndMessage(val placeholder: Boolean = true)

data class SportStateMessage(
    val elapsedMs: Long,
    val movingMs: Long,
    val distanceM: Double,
    val currentSpeedMps: Double,
    val avgPaceMsPerKm: Long,
    val sessionState: String,
    val sport: String
)
