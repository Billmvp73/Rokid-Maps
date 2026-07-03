# Phase 1: Activity Recording Engine - Pattern Map

**Mapped:** 2026-07-03
**Files analyzed:** 15 new/modified files
**Analogs found:** 12 / 15 (3 test files have no analog — repo has zero tests; RESEARCH.md Code Examples fill the gap)

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `shared/src/main/java/com/rokid/hud/shared/protocol/Messages.kt` (modify: +`SportStateMessage`) | model | transform (protocol DTO) | `StateMessage`/`SettingsMessage` in same file | exact |
| `shared/src/main/java/com/rokid/hud/shared/protocol/ProtocolConstants.kt` (modify: +8 fields, +`MessageType.SPORT_STATE`) | config | — | existing constants in same file | exact |
| `shared/src/main/java/com/rokid/hud/shared/protocol/ProtocolCodec.kt` (modify: +`encodeSportState`, +decode case, +`ParsedMessage.SportState`) | utility (codec) | transform | `encodeState`/`encodeSettings` + decode cases in same file | exact |
| `phone/src/main/java/com/rokid/hud/phone/ActivitySessionManager.kt` (NEW) | service (state machine) | event-driven (GPS stream → metrics) | `NavigationManager.kt` (state machine + callback) + `OverpassSpeedLimitClient.kt` (@Volatile, haversine) | role-match (composite) |
| `phone/src/main/java/com/rokid/hud/phone/SessionStore.kt` (NEW) | service (persistence) | file-I/O | `DiskTileCache.kt` (single-thread executor, File I/O) + `SavedPlacesManager.kt` (org.json serialize/parse) | role-match (composite) |
| `phone/src/main/java/com/rokid/hud/phone/RecordingWatchdog.kt` (NEW) | middleware (recovery) | event-driven (alarms/timers) | `WifiShareManager.kt` (BroadcastReceiver) + `MainActivity.navMapUpdateRunnable` (Handler self-chain) + `HudStreamingService.buildNotification` (PendingIntent) | partial (AlarmManager has no precedent — use RESEARCH.md Pattern 7) |
| `phone/src/main/java/com/rokid/hud/phone/HudStreamingService.kt` (modify: LocationConsumer fan-out, sport_state broadcast, live notification, orphan scan) | service (FGS) | streaming (1Hz broadcast) | itself — existing `onLocationUpdate`/`broadcast`/`buildNotification`/`onStartCommand` | exact |
| `phone/src/main/java/com/rokid/hud/phone/MainActivity.kt` (modify: recording card, confirm dialog, prompts) | component (Activity UI) | request-response (UI → service binder) | itself — existing toggles, dialogs, permission flows, `routeCard` wiring | exact |
| `phone/src/main/res/layout/activity_main.xml` (modify: recording card) | config (layout) | — | `routeCard` + `navStatus` blocks in same file | exact |
| `phone/src/main/AndroidManifest.xml` (modify: permissions + watchdog receiver) | config | — | existing permission block + service declarations | exact |
| `phone/build.gradle.kts` (modify: testImplementation + testOptions) | config (build) | — | existing `dependencies {}` block | exact |
| `shared/build.gradle.kts` (modify: testImplementation) | config (build) | — | existing `dependencies {}` block | exact |
| `shared/src/test/java/com/rokid/hud/shared/protocol/ProtocolCodecTest.kt` (NEW) | test | — | none (zero tests in repo) | no analog |
| `phone/src/test/java/com/rokid/hud/phone/ActivitySessionManagerTest.kt` (NEW) | test | — | none | no analog |
| `phone/src/test/java/com/rokid/hud/phone/SessionStoreTest.kt` (NEW) | test | — | none | no analog |

## Pattern Assignments

### `shared/protocol/Messages.kt` — add `SportStateMessage` (model, transform)

**Analog:** `shared/src/main/java/com/rokid/hud/shared/protocol/Messages.kt` — `StateMessage` (lines 3-11) and `SettingsMessage` (lines 37-48)

**Data class pattern** (lines 3-11) — plain data class, one property per protocol field, defaults for optional fields, `Message` suffix, no methods:
```kotlin
data class StateMessage(
    val latitude: Double,
    val longitude: Double,
    val bearing: Float,
    val speed: Float,
    val accuracy: Float,
    val speedLimitKmh: Int = -1,
    val distToNextStep: Double = -1.0
)
```

New message follows this shape exactly (RESEARCH Pattern 2 field mapping):
```kotlin
data class SportStateMessage(
    val elapsedMs: Long,
    val movingMs: Long,
    val distanceM: Double,
    val currentSpeedMps: Double,
    val avgPaceMsPerKm: Long,
    val sessionState: String,   // "idle" | "tracking" | "finished"
    val sport: String           // "ride" | "run"
)
```
Append after `StepsListMessage` (line 69), before the Apk* messages, or at end of file — file is append-ordered by feature.

---

### `shared/protocol/ProtocolConstants.kt` — add sport_state constants (config)

**Analog:** same file, lines 4-8 (fields) and 46-59 (`MessageType` object)

**Field constant pattern** (lines 4-8) — `const val FIELD_*` with short JSON key strings:
```kotlin
object ProtocolConstants {
    const val FIELD_TYPE = "t"
    const val FIELD_LATITUDE = "lat"
    const val FIELD_LONGITUDE = "lng"
```

**Message type pattern** (lines 46-59) — nested object, UPPER_SNAKE const, snake_case string value:
```kotlin
    object MessageType {
        const val STATE = "state"
        const val ROUTE = "route"
        ...
        const val STEPS_LIST = "steps_list"
    }
```

Additions (locked JSON keys from CONTEXT `<specifics>`; append after `FIELD_SHOW_SPEED_LIMIT`, line 44, and after `STEPS_LIST`, line 58):
```kotlin
    const val FIELD_VERSION = "v"
    const val FIELD_ELAPSED = "et"
    const val FIELD_MOVING_TIME = "mt"
    const val FIELD_SPORT_DISTANCE = "d"
    const val FIELD_CURRENT_SPEED = "cs"
    const val FIELD_AVG_PACE = "ap"
    const val FIELD_SESSION_STATE = "st"
    const val FIELD_SPORT = "sp"
    // in MessageType:
    const val SPORT_STATE = "sport_state"
```
**Collision check performed:** none of `v/et/mt/d/cs/ap/st/sp` collide with existing key strings in lines 4-44 (existing keys are all ≥1-word camelCase or lat/lng/z/x/y/id/data/t within their own message scopes).

---

### `shared/protocol/ProtocolCodec.kt` — add sport_state codec (utility, transform)

**Analog:** same file — `ParsedMessage` sealed class (lines 6-20), `encodeState` (lines 24-33), decode `STATE` case (lines 133-143), decode `SETTINGS` case with `optXxx` (lines 176-189), catch-all (lines 239-243)

**Sealed variant pattern** (lines 6-19) — one-line data class wrapping the message; add before `Unknown`:
```kotlin
sealed class ParsedMessage {
    data class State(val msg: StateMessage) : ParsedMessage()
    ...
    data class StepsList(val msg: StepsListMessage) : ParsedMessage()
    data class Unknown(val raw: String) : ParsedMessage()
}
```

**Encode pattern** (lines 24-33) — `JSONObject().apply{}` with FIELD constants, type first, `.toString()`:
```kotlin
fun encodeState(msg: StateMessage): String = JSONObject().apply {
    put(ProtocolConstants.FIELD_TYPE, ProtocolConstants.MessageType.STATE)
    put(ProtocolConstants.FIELD_LATITUDE, msg.latitude)
    put(ProtocolConstants.FIELD_LONGITUDE, msg.longitude)
    put(ProtocolConstants.FIELD_BEARING, msg.bearing.toDouble())
    ...
}.toString()
```
For sport_state: `put(ProtocolConstants.FIELD_VERSION, 1)` hardcoded at encode (codec stays a dumb serializer — monotonic clamping happens in ActivitySessionManager, NOT here, per RESEARCH Pattern 2).

**Defensive decode pattern** (lines 176-189, SETTINGS case) — `optXxx(key, default)` for every field so missing keys never throw:
```kotlin
ProtocolConstants.MessageType.SETTINGS -> ParsedMessage.Settings(
    SettingsMessage(
        ttsEnabled = json.optBoolean(ProtocolConstants.FIELD_TTS_ENABLED, false),
        useImperial = json.optBoolean(ProtocolConstants.FIELD_USE_IMPERIAL, false),
        ...
        tileCacheSizeMb = json.optInt(ProtocolConstants.FIELD_TILE_CACHE_SIZE_MB, 100),
```
sport_state decode uses `optLong(…, 0L)` / `optDouble(…, 0.0)` / `optString(…, "idle")` / `optString(…, "ride")` — new `when` branch inside `decode()` before the `else ->` at line 239.

**Error handling pattern** (lines 129-131 + 241-243) — the whole `when` is already wrapped; malformed input can never throw:
```kotlin
fun decode(line: String): ParsedMessage {
    return try {
        val json = JSONObject(line)
        when (json.optString(ProtocolConstants.FIELD_TYPE)) {
            ...
            else -> ParsedMessage.Unknown(line)
        }
    } catch (e: Exception) {
        ParsedMessage.Unknown(line)
    }
}
```

---

### `phone/ActivitySessionManager.kt` (NEW — service/state machine, event-driven)

**Primary analog:** `phone/src/main/java/com/rokid/hud/phone/NavigationManager.kt`
**Secondary analog:** `phone/src/main/java/com/rokid/hud/phone/OverpassSpeedLimitClient.kt`

**Imports pattern** (NavigationManager.kt lines 1-7) — framework + shared protocol + `kotlin.math.*`, no third-party:
```kotlin
package com.rokid.hud.phone

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rokid.hud.shared.protocol.Waypoint
import kotlin.math.*
```

**Callback interface pattern** (NavigationManager.kt lines 9-15) — top-level interface in the same file, verb-phrase `onXxx` methods:
```kotlin
interface NavigationCallback {
    fun onRouteCalculated(waypoints: List<Waypoint>, totalDistance: Double, totalDuration: Double, steps: List<NavigationStep>)
    fun onStepChanged(instruction: String, maneuver: String, distance: Double)
    fun onNavigationError(message: String)
    fun onArrived()
    fun onRerouting()
}
```
ASM's `MetricsListener { fun onMetrics(snapshot: MetricsSnapshot) }` follows this shape (RESEARCH Pattern 3).

**Companion + state pattern** (NavigationManager.kt lines 17-37) — TAG, UPPER_SNAKE tuning constants, `var x = …; private set` for read-only-to-callers state:
```kotlin
class NavigationManager(private val callback: NavigationCallback) {

    companion object {
        private const val TAG = "NavManager"
        private const val STEP_ADVANCE_RADIUS_M = 150.0
        private const val OFF_ROUTE_RADIUS_M = 80.0
        private const val REROUTE_COOLDOWN_MS = 15000L
        private const val ARRIVAL_RADIUS_M = 30.0
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    var isNavigating = false; private set

    var steps: List<NavigationStep> = emptyList()
        private set
    var currentStepIndex = 0
        private set
```
ASM equivalents: `TAG = "ActivitySession"`, `ACCURACY_GATE_M = 20.0`, `HYSTERESIS_ENTER_MPS = 0.7`, `HYSTERESIS_EXIT_MPS = 0.3`, `SPEED_MA_WINDOW = 5`, `CHECKPOINT_INTERVAL_MS = 60_000L`, `CHECKPOINT_POINT_COUNT = 500`, `var state: SessionState = SessionState.IDLE; private set`.

**Main-thread callback dispatch** (NavigationManager.kt lines 65-70) — mutate state inline, post callbacks via `mainHandler`:
```kotlin
        val distToDest = haversineM(lat, lng, destLat, destLng)
        if (distToDest < ARRIVAL_RADIUS_M && currentStepIndex >= steps.size - 2) {
            isNavigating = false
            mainHandler.post { callback.onArrived() }
            return
        }
```
Note for ASM: since GPS callbacks already arrive on the main looper (see HudStreamingService line 468), ASM is main-thread-confined and can invoke listeners directly — `mainHandler.post` is only needed from background threads (RESEARCH Pattern 3).

**@Volatile cross-thread publication** (OverpassSpeedLimitClient.kt lines 20-26) — for fields read off-thread (watchdog reads `lastFixElapsedRealtimeMs`):
```kotlin
    @Volatile private var cachedSpeedLimitKmh: Int = -1
    @Volatile private var lastQueryTime: Long = 0
    @Volatile private var lastQueryLat: Double = 0.0
    @Volatile private var lastQueryLng: Double = 0.0
    @Volatile private var querying: Boolean = false

    private val executor = Executors.newSingleThreadExecutor()
```

**Haversine pattern** (NavigationManager.kt lines 149-156; identical copy in OverpassSpeedLimitClient.kt lines 91-98) — codebase precedent is a private per-class copy, NOT a shared utility. Replicate verbatim in ASM (locked: haversine on accepted consecutive points):
```kotlin
    private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return r * 2 * asin(sqrt(a))
    }
```

**Testability requirement (no analog — design constraint from RESEARCH Pattern 3):** core logic in `internal fun onFix(lat, lng, alt, ts, speedMps, accuracyM, elapsedRealtimeMs)` taking primitives; `fun recordLocation(loc: Location)` is a thin adapter. Tests drive `onFix` on plain JVM. `TrackPoint`/`MetricsSnapshot` are immutable data classes mirroring `HudState`'s copy() philosophy and `SavedPlace` (SavedPlacesManager.kt lines 7-12).

---

### `phone/SessionStore.kt` (NEW — persistence, file-I/O)

**Primary analog:** `shared/src/main/java/com/rokid/hud/shared/cache/DiskTileCache.kt`
**Secondary analog:** `phone/src/main/java/com/rokid/hud/phone/SavedPlacesManager.kt`

**Class skeleton + single-thread executor** (DiskTileCache.kt lines 1-20) — this is THE checkpoint-writer pattern (locked by CONTEXT):
```kotlin
import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

class DiskTileCache(context: Context, private var maxSizeBytes: Long = 100L * 1024 * 1024) {

    companion object {
        private const val TAG = "DiskTileCache"
        private const val CACHE_DIR_NAME = "map_tiles"
    }

    private val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
    private val executor = Executors.newSingleThreadExecutor()

    init {
        cacheDir.mkdirs()
    }
```
SessionStore: `File(context.filesDir, "activities")` (locked — filesDir, NOT cacheDir). For JVM testability, prefer a constructor taking `dir: File` directly (Context-free) — tests pass a TemporaryFolder; the service passes `File(filesDir, "activities")`.

**Serial async write + try/catch + Log.w** (DiskTileCache.kt lines 35-46):
```kotlin
    fun put(z: Int, x: Int, y: Int, bytes: ByteArray) {
        executor.execute {
            try {
                val file = tileFile(z, x, y)
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                evictIfNeeded()
            } catch (e: Exception) {
                Log.w(TAG, "Write tile $z/$x/$y failed: ${e.message}")
            }
        }
    }
```
Checkpoint writes go through the same `executor.execute { try/catch/Log }` shape, but the write body is the atomic temp+fsync+rename from RESEARCH Pattern 6 (must check `renameTo` boolean and `Log.e` on false — Pitfall 8; plain `writeBytes` is NOT atomic enough for crash-safe checkpoints).

**Executor lifecycle** (DiskTileCache.kt lines 69-71) — owner calls shutdown in `onDestroy` (see HudStreamingService.kt line 138 `diskTileCache?.shutdown()`):
```kotlin
    fun shutdown() {
        executor.shutdownNow()
    }
```

**org.json serialize/parse of data class lists** (SavedPlacesManager.kt lines 53-64 encode, 29-45 decode) — session JSON `trackPoints[]` array follows this:
```kotlin
    private fun persist(list: List<SavedPlace>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("lat", p.lat)
                put("lng", p.lng)
                put("savedAt", p.savedAt)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }
```
```kotlin
    fun getAll(): List<SavedPlace> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                SavedPlace(
                    name = obj.getString("name"),
                    lat = obj.getDouble("lat"),
                    lng = obj.getDouble("lng"),
                    savedAt = obj.optLong("savedAt", 0L)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
```
Failed reads return sentinel (`emptyList()`/`null`) — same convention for orphan-checkpoint reads (corrupt checkpoint → log + finalize-as-interrupted, never crash; note new code must LOG in catch blocks, so use `catch (e: Exception) { Log.w(TAG, "…: ${e.message}"); null }` rather than the silent `catch (_)` seen here).

---

### `phone/RecordingWatchdog.kt` (NEW — middleware, event-driven) — PARTIAL analog

AlarmManager has **no precedent in this codebase** — use RESEARCH.md Pattern 7 + Code Examples for the `setExactAndAllowWhileIdle` chain and `canScheduleExactAlarms()` gating. The surrounding conventions DO have analogs:

**BroadcastReceiver pattern** (WifiShareManager.kt lines 43-66) — anonymous/object receiver, `when(intent.action)`, API-branched extras:
```kotlin
    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    ...
                }
            }
        }
    }
```
The watchdog's manifest-declared receiver (needed to survive process death) must be `android:exported="false"` (security requirement; see manifest section below).

**Receiver registration with API branch + safe unregister** (WifiShareManager.kt lines 174-191):
```kotlin
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(p2pReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(p2pReceiver, filter)
        }
    ...
        try { context.unregisterReceiver(p2pReceiver) } catch (_: Exception) {}
```

**PendingIntent FLAG_IMMUTABLE** (HudStreamingService.kt lines 485-486):
```kotlin
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
```
Watchdog uses `PendingIntent.getBroadcast(context, REQ_WATCHDOG, Intent(context, WatchdogReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)`.

**Handler self-chain (L1 staleness timer)** (MainActivity.kt lines 129-138) — the repo's periodic-tick idiom:
```kotlin
    private val navMapHandler = Handler(Looper.getMainLooper())
    private val navMapUpdateRunnable = object : Runnable {
        override fun run() {
            if (!::navMapView.isInitialized || navStatus.visibility != View.VISIBLE) return
            val (lat, lng) = service?.getLastLocation() ?: return
            navMapView.controller.setCenter(GeoPoint(lat, lng))
            navMapView.controller.setZoom(17.0)
            navMapHandler.postDelayed(this, 2000L)
        }
    }
```
Start with `handler.postDelayed(runnable, …)`, stop with `handler.removeCallbacks(runnable)` (MainActivity.kt line 645).

**Android API version branching** (MainActivity.kt lines 758-767) — `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)` guards for the exact-alarm permission (API 31+ check per RESEARCH watchdog example).

---

### `phone/HudStreamingService.kt` (MODIFY — FGS, streaming)

**Analog:** itself. Extend, do not restructure. NavigationManager wiring stays untouched.

**Companion constants** (lines 41-48) — add new constants here (e.g., `SPORT_STATE_INTERVAL_MS`, notification-update cadence):
```kotlin
    companion object {
        private const val TAG = "HudStreaming"
        private const val SERVICE_NAME = "RokidHudSPP"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val NOTIFICATION_ID = 1
        private const val LOCATION_INTERVAL_MS = 1000L
        private const val MAX_TILE_BYTES = 512 * 1024
    }
```

**onStartCommand idempotent-init pattern** (lines 86-97) — orphan-checkpoint scan and ASM construction slot into this block; START_STICKY already returned:
```kotlin
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!running) {
            running = true
            diskTileCache = DiskTileCache(applicationContext)
            acquireWakeLock()
            initNavigation()
            startBluetoothServer()
            startLocationUpdates()
        }
        return START_STICKY
    }
```
Pitfall 1 hardening: wrap `startForeground` in try/catch(SecurityException) → log, never crash-loop (background restart on Android 14+).

**Location callback — INTEGRATION POINT** (lines 459-463 + 472-482). Current code takes only `lastLocation`; the fan-out must iterate `result.locations` (Pitfall 5):
```kotlin
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onLocationUpdate(it) }
            }
        }
```
```kotlin
    private fun onLocationUpdate(loc: Location) {
        lastLat = loc.latitude
        lastLng = loc.longitude
        val distToNext = navigationManager?.getDistanceToNextStep(loc.latitude, loc.longitude) ?: -1.0
        val speedLimit = speedLimitClient.getCachedSpeedLimit(loc.latitude, loc.longitude)
        broadcast(ProtocolCodec.encodeState(
            StateMessage(loc.latitude, loc.longitude, loc.bearing, loc.speed, loc.accuracy,
                speedLimitKmh = speedLimit, distToNextStep = distToNext)
        ))
        navigationManager?.onLocationUpdate(loc.latitude, loc.longitude)
    }
```
Add `activitySessionManager?.recordLocation(loc)` dispatch here (or via a `CopyOnWriteArrayList<LocationConsumer>` per RESEARCH Pattern 1 — the listener-list convention is `CopyOnWriteArrayList`, see line 56 `private val clients = CopyOnWriteArrayList<BufferedWriter>()`).

**Broadcast pattern** (lines 293-306) — `broadcast()` is private; the service's own metrics listener calls it for sport_state (~1Hz while TRACKING). Dead-client pruning is built in:
```kotlin
    private fun broadcast(json: String) {
        val dead = mutableListOf<BufferedWriter>()
        for (writer in clients) {
            try {
                writer.write(json)
                writer.newLine()
                writer.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Client write failed", e)
                dead.add(writer)
            }
        }
        clients.removeAll(dead.toSet())
    }
```
Public send-helper convention for typed messages (lines 263-265):
```kotlin
    fun sendStep(instruction: String, maneuver: String, distance: Double) {
        broadcast(ProtocolCodec.encodeStep(StepMessage(instruction, maneuver, distance)))
    }
```

**Notification pattern — INTEGRATION POINT** (lines 484-492). Recording state swaps the text/adds chronometer; restore this static form on stop:
```kotlin
    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, HudApplication.CHANNEL_ID)
            .setContentTitle("Rokid HUD Active")
            .setContentText("Streaming to glasses")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi).setOngoing(true).build()
    }
```
Live updates: `NotificationManager.notify(NOTIFICATION_ID, builder…)` + `setOnlyAlertOnce(true)` + `setUsesChronometer(true).setWhen(System.currentTimeMillis() - elapsedMs)`; distance text refresh ~10s cadence (RESEARCH Pattern 8, Pitfall 10). Channel is `HudApplication.CHANNEL_ID` (`"hud_streaming"`, IMPORTANCE_LOW — HudApplication.kt lines 12-15, 24-36).

**onDestroy teardown ordering** (lines 122-140) — new components join this list (finalize/checkpoint session, stop watchdog, `sessionStore.shutdown()`):
```kotlin
    override fun onDestroy() {
        running = false
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
        navigationManager?.stopNavigation()
        locationCallback?.let { fusedLocationClient?.removeLocationUpdates(it) }
        ...
        tileExecutor.shutdownNow()
        diskTileCache?.shutdown()
        super.onDestroy()
    }
```

**Binder surface for MainActivity** (lines 50-54, 167-178) — recording start/stop/query methods follow this shape:
```kotlin
    inner class LocalBinder : Binder() {
        fun getService(): HudStreamingService = this@HudStreamingService
    }
    ...
    fun startNavigation(destLat: Double, destLng: Double) {
        navigationManager?.startNavigation(destLat, destLng, lastLat, lastLng)
    }
    fun getLastLocation(): Pair<Double, Double> = Pair(lastLat, lastLng)
```

---

### `phone/MainActivity.kt` (MODIFY — Activity UI, request-response)

**Analog:** itself.

**Pref-key constants** (lines 38-55) — add `PREF_SPORT_TYPE`, first-run-prompt flags here:
```kotlin
    companion object {
        private const val TAG = "MainActivity"
        private const val RC_PERMISSIONS = 100
        ...
        private const val PREF_SHOW_SPEED_LIMIT = "show_speed_limit"
        private const val PREFS_GLASSES = "rokid_glasses"
        private const val PREFS_HUD = "rokid_hud_prefs"
    }
```
Two prefs stores in play: `getPreferences(MODE_PRIVATE)` (activity-local) and `getSharedPreferences(PREFS_HUD, MODE_PRIVATE)` (app-wide). Recording prefs (sport type, first-run flags) belong in `PREFS_HUD` since the service may read them.

**View binding pattern** (lines 212-228 in `bindViews()`) — `lateinit var` fields grouped by section (lines 59-112), bound with `findViewById`, camelCase IDs matching role:
```kotlin
    private fun bindViews() {
        btnStart = findViewById(R.id.btnStart)
        ...
        routeCard = findViewById(R.id.routeCard)
        routeDestText = findViewById(R.id.routeDestText)
        routeInfoText = findViewById(R.id.routeInfoText)
        btnNavigate = findViewById(R.id.btnNavigate)
```
Recording card: `recordingCard`, `btnStartRecording`, `btnStopRecording`, `recElapsedText`, `recDistanceText`, `recSpeedText`, `recPaceText`, `recBadge`, sport toggle views.

**Toggle listener pattern** (lines 348-351) — persist + propagate:
```kotlin
        switchTurnAlert.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_HUD, MODE_PRIVATE).edit().putBoolean(PREF_TURN_ALERT, isChecked).apply()
            sendCurrentSettings()
        }
```

**Confirm dialog pattern** (lines 369-378) — for "Finish recording?":
```kotlin
            AlertDialog.Builder(this)
                .setTitle("Support Rokid Maps")
                .setMessage("If you enjoy using Rokid Maps, consider buying me a coffee! ...")
                .setPositiveButton("Open Link") { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/charleshartmann")))
                }
                .setNegativeButton("Maybe Later", null)
                .show()
```

**Battery-exemption prompt** (lines 803-821) — EXACT template for the first-recording-start prompt (locked decision); reword message for recording:
```kotlin
    private fun promptBatteryOptimizationIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        AlertDialog.Builder(this)
            .setTitle("Keep running when screen is off")
            .setMessage("To keep maps and directions updating ... turn off battery optimization for this app.")
            .setPositiveButton("Allow") { _, _ ->
                try {
                    val i = Intent().apply {
                        action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(i)
                } catch (_: Exception) {}
            }
            .setNegativeButton("Not now", null)
            .show()
    }
```

**Permission collection pattern** (lines 754-773 + 775-790) — `mutableListOf` + batch request + result switch on request code; add a new `RC_` code for `ACCESS_BACKGROUND_LOCATION` (must be a SEPARATE sequential request on Android 11+, routed via settings — RESEARCH Pitfall 1):
```kotlin
    private fun checkPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            ...
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), RC_PERMISSIONS)
        } else {
            startStreaming()
        }
    }
```

**Service-call guard pattern** (lines 623-632) — check `bound`/`service` before acting, Toast on failure:
```kotlin
    private fun startNavigation() {
        val dest = selectedDest ?: return
        if (!bound || service == null) {
            Toast.makeText(this, "Start streaming first", Toast.LENGTH_SHORT).show()
            return
        }
        routeInfoText.text = "Calculating route..."
        btnNavigate.isEnabled = false
        service!!.startNavigation(dest.lat, dest.lng)
    }
```

**Cross-thread UI callback pattern** (lines 156-173, `navCallback`) — every method body wrapped in `runOnUiThread`; the ASM metrics listener registered by MainActivity does the same:
```kotlin
    private val navCallback = object : NavigationCallback {
        override fun onRouteCalculated(waypoints: List<Waypoint>, totalDistance: Double, totalDuration: Double, steps: List<NavigationStep>) {
            runOnUiThread {
                currentRouteWaypoints = waypoints
                ...
            }
        }
        override fun onStepChanged(instruction: String, maneuver: String, distance: Double) {
            runOnUiThread {
                navInstructionText.text = instruction
                navDistanceText.text = formatDist(distance)
                ...
            }
        }
```
Re-attach in `onResume` like line 452: `if (bound) service?.uiCallback = navCallback`.

**Button state UI pattern** (lines 485-497) — `backgroundTintList` color swap for start/recording states:
```kotlin
    private fun updateStreamingUi() {
        if (streaming) {
            btnStart.text = "Streaming"
            btnStart.isEnabled = false
            btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2E7D32.toInt())
            statusText.text = "Streaming to glasses — search a destination"
        } else {
            btnStart.text = "Start Streaming"
            btnStart.isEnabled = true
            btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00E676.toInt())
            ...
        }
    }
```

**Unit formatting pattern** (lines 892-906) — reuse `formatDist`/`isImperial` for the metrics card; add a pace formatter alongside:
```kotlin
    private fun isImperial(): Boolean = getPreferences(MODE_PRIVATE).getBoolean(PREF_IMPERIAL, false)

    private fun formatDist(m: Double): String = if (isImperial()) {
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
```
Note: `PREF_IMPERIAL` lives in activity-local `getPreferences(MODE_PRIVATE)` (line 260/327) — the recording card reads it the same way.

---

### `phone/src/main/res/layout/activity_main.xml` (MODIFY — recording card)

**Analog:** same file — section card wrapper (lines 122-134), `routeCard` (lines 200-263), `navStatus` live panel (lines 269-302)

**Section card wrapper** (lines 122-134) — vertical LinearLayout, `bg_card`, 16dp padding, 12dp bottom margin, `@style/SectionTitle` header (style defined in `phone/src/main/res/values/themes.xml` line 27):
```xml
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:background="@drawable/bg_card"
            android:padding="16dp"
            android:layout_marginBottom="12dp">

            <TextView
                style="@style/SectionTitle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="NAVIGATE" />
```

**State-swapped inner card + action button row** (lines 200-263, `routeCard`) — `visibility="gone"` default, `bg_card_accent`, bold white title + green info line, weighted button row with primary green button (`#00E676` bg / black text) and secondary dark button:
```xml
            <LinearLayout
                android:id="@+id/routeCard"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:background="@drawable/bg_card_accent"
                android:padding="14dp"
                android:layout_marginTop="10dp"
                android:visibility="gone">

                <TextView
                    android:id="@+id/routeDestText"
                    android:textColor="#FFFFFF"
                    android:textSize="15sp"
                    android:textStyle="bold" ... />

                <TextView
                    android:id="@+id/routeInfoText"
                    android:textColor="#81C784"
                    android:textSize="13sp"
                    android:layout_marginTop="4dp" ... />

                <LinearLayout android:orientation="horizontal" android:layout_marginTop="12dp" ...>
                    <Button
                        android:id="@+id/btnNavigate"
                        android:layout_width="0dp"
                        android:layout_height="44dp"
                        android:layout_weight="3"
                        android:text="Start Navigation"
                        android:backgroundTint="#00E676"
                        android:textColor="#000000"
                        android:textStyle="bold" ... />
                    <Button
                        android:id="@+id/btnSavePlace"
                        android:layout_weight="2"
                        android:backgroundTint="#2A2A2A"
                        android:textColor="#FFD600" ... />
                </LinearLayout>
            </LinearLayout>
```
Recording card goes after the SEARCH & NAVIGATE section (after line 264), before LIVE NAVIGATION (line 266). Live-metrics text uses the big-number style of `navInstructionText` (lines 294-302: `#FFFFFF`, 22sp, bold). Drawables available: `bg_card`, `bg_card_accent` (green-tinted `#0D2818` + `#1B5E20` stroke, 16dp radius), `bg_card_red` (for the REC badge), `bg_card_blue`.

---

### `phone/src/main/AndroidManifest.xml` (MODIFY — permissions + watchdog receiver)

**Analog:** same file, lines 4-22 (permissions) and 46-49 (service declaration)

**Permission block style** (lines 4-22) — one per line, API-conditional attributes inline:
```xml
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
        android:usesPermissionFlags="neverForLocation"
        android:minSdkVersion="33" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```
Additions per CONTEXT/RESEARCH: `ACCESS_BACKGROUND_LOCATION` (recovery paths, Pitfall 1), `SCHEDULE_EXACT_ALARM` + `USE_EXACT_ALARM` (watchdog; declare both — sideloaded app, RESEARCH Open Question 2). Already present (no action): `FOREGROUND_SERVICE_LOCATION`, `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `POST_NOTIFICATIONS`.

**Component declaration style** (lines 46-49) — `exported="false"` default; watchdog receiver MUST be `android:exported="false"` (ASVS V4):
```xml
        <service
            android:name=".HudStreamingService"
            android:exported="false"
            android:foregroundServiceType="location|connectedDevice" />
```

---

### `shared/build.gradle.kts` + `phone/build.gradle.kts` (MODIFY — test infra)

**Analog:** existing `dependencies {}` blocks (shared lines 24-26; phone lines 50-68) — quoted coordinate strings, no version catalog:
```kotlin
dependencies {
    implementation("org.json:json:20231013")
}
```
Additions (RESEARCH-verified, Wave 0):
```kotlin
// both modules — dependencies block
testImplementation("junit:junit:4.13.2")
testImplementation("org.json:json:20231013")

// phone/build.gradle.kts ONLY — inside android { } (after packaging block, line 47)
testOptions { unitTests.isReturnDefaultValues = true }
```
Build commands require `export JAVA_HOME=/opt/homebrew/opt/openjdk@17` (RESEARCH Environment Availability).

---

## Shared Patterns

### TAG + companion constants
**Source:** every class — e.g., `NavigationManager.kt` lines 19-25, `DiskTileCache.kt` lines 10-13, `HudStreamingService.kt` lines 41-48
**Apply to:** ActivitySessionManager, SessionStore, RecordingWatchdog
```kotlin
    companion object {
        private const val TAG = "ShortName"          // short class nickname
        private const val SOME_TUNING_MS = 15000L    // UPPER_SNAKE_CASE with unit suffix
    }
```

### Error handling: try/catch + Log, never propagate
**Source:** `DiskTileCache.kt` lines 42-44 (Log.w with message), `NavigationManager.kt` lines 130-133 (Log.e with exception), `HudStreamingService.kt` lines 124-126 (`catch (_: Exception) {}` cleanup-only)
**Apply to:** all new phone/shared code
```kotlin
// Recoverable I/O failure:
} catch (e: Exception) {
    Log.w(TAG, "Operation failed: ${e.message}")
}
// Serious failure with stack:
} catch (e: Exception) {
    Log.e(TAG, "Route calculation failed", e)
}
// Cleanup/release ONLY (existing convention; new non-cleanup code must always log — CONCERNS Pitfall 6):
try { session.socket.close() } catch (_: Exception) {}
```

### JSON encode/decode via org.json
**Source:** `ProtocolCodec.kt` lines 24-33 (`JSONObject().apply{}.toString()`), lines 176-189 (`optXxx` defaults), `SavedPlacesManager.kt` lines 29-45/53-64 (data-class lists)
**Apply to:** sport_state codec, session/checkpoint JSON in SessionStore. No Gson/Moshi anywhere.

### Threading: no coroutines
**Source:** `NavigationManager.kt` lines 116-135 (`Thread { … }.start()` + `mainHandler.post`), `DiskTileCache.kt` line 16 (`Executors.newSingleThreadExecutor()` for serial disk writes), `OverpassSpeedLimitClient.kt` lines 20-26 (`@Volatile` published fields), `HudStreamingService.kt` line 56 (`CopyOnWriteArrayList` for listener/client lists), line 468 (FLP on `Looper.getMainLooper()` — main-thread confinement basis)
**Apply to:** ASM (main-thread-confined + `@Volatile lastFixElapsedRealtimeMs`), SessionStore (single-thread executor), watchdog (Handler timer), LocationConsumer list (CopyOnWriteArrayList).

### SharedPreferences
**Source:** `MainActivity.kt` lines 314, 344 (`getSharedPreferences(PREFS_HUD, MODE_PRIVATE).edit().putBoolean(…).apply()`), line 259 (`getPreferences(MODE_PRIVATE)` activity-local)
**Apply to:** sport-type toggle persistence, first-run-prompt flags.

### Main-thread UI delivery
**Source:** `MainActivity.kt` navCallback lines 156-195 (`runOnUiThread` in every callback), `NavigationManager.kt` line 68 (`mainHandler.post`), `HudStreamingService.kt` lines 198, 221 (`Handler(Looper.getMainLooper()).post` for lambdas from worker threads)
**Apply to:** metrics card 1Hz updates, error/staleness surfacing.

### Haversine distance (private per-class copy)
**Source:** `NavigationManager.kt` lines 149-156 == `OverpassSpeedLimitClient.kt` lines 91-98 (verbatim duplicates — repo convention is private copies, no shared util)
**Apply to:** ActivitySessionManager distance accumulation.

### PendingIntent immutability
**Source:** `HudStreamingService.kt` lines 485-486 (`PendingIntent.FLAG_IMMUTABLE`)
**Apply to:** watchdog broadcast PendingIntent, notification content intents.

### API-level branching
**Source:** `MainActivity.kt` lines 758-767 (`Build.VERSION.SDK_INT >= Build.VERSION_CODES.S/TIRAMISU`), `WifiShareManager.kt` lines 47-55, 181-183
**Apply to:** exact-alarm gating (API 31+), background-location request (API 29/30+ split), `RECEIVER_NOT_EXPORTED` (API 33+).

## No Analog Found

Files with no close match in the codebase (planner should use RESEARCH.md patterns instead):

| File | Role | Data Flow | Reason | Fallback |
|------|------|-----------|--------|----------|
| `shared/src/test/java/com/rokid/hud/shared/protocol/ProtocolCodecTest.kt` | test | — | Repo has ZERO tests (verified: no `src/test` dirs exist) | RESEARCH.md "ProtocolCodec round-trip test" example (JUnit4, plain JVM) |
| `phone/src/test/java/com/rokid/hud/phone/ActivitySessionManagerTest.kt` | test | — | Same | RESEARCH.md Pattern 3/5 — drive `onFix(primitives)`; edge cases listed under Pattern 5 |
| `phone/src/test/java/com/rokid/hud/phone/SessionStoreTest.kt` | test | — | Same | JUnit `TemporaryFolder` + java.io per RESEARCH Pattern 6 |
| (partial) `RecordingWatchdog.kt` AlarmManager chain | middleware | event-driven | No AlarmManager usage anywhere in repo | RESEARCH.md Pattern 7 + "Exact-alarm watchdog schedule + gate" code example |
| (partial) atomic temp+fsync+rename write | file-I/O | — | `DiskTileCache` uses plain `writeBytes` (non-atomic — insufficient for crash-safe checkpoints) | RESEARCH.md Pattern 6 `writeAtomic` example |
| (partial) `setUsesChronometer` live notification | service | — | Existing notification is static text only | RESEARCH.md Pattern 8 |

## Integration Warnings for Planner

1. **`HudStreamingService.kt` line 461 uses `result.lastLocation`** — the recording fan-out must iterate `result.locations` (oldest→newest, RESEARCH Pitfall 5); keep StateMessage broadcast semantics unchanged if desired.
2. **`broadcast()` is private (line 293)** — sport_state emission belongs inside the service (service registers itself as ASM metrics listener), not via a public broadcast method.
3. **`NavigationManager` wiring (lines 142-165, 472-482) stays untouched** — its data race is Phase 4's; ASM is additive.
4. **`NOTIFICATION_ID = 1` is shared** — recording notification updates reuse this ID/channel and must restore the static "Streaming to glasses" text on stop (service keeps running for HUD streaming).
5. **Codec must stay a dumb serializer** — monotonic elapsed/distance clamps live in ASM (`maxElapsedMs`/`maxDistanceM`), never in `ProtocolCodec` (keeps round-trip tests trivial).
6. **Adding a sealed `ParsedMessage` variant REQUIRES a glasses when-branch** — `BluetoothClient.processMessage` (glasses, lines 157-261) is an exhaustive `when` over the sealed class with NO `else` branch, so adding `ParsedMessage.SportState` without a branch is a compile ERROR under Kotlin 2.1 (`:glasses:compileDebugKotlin` fails — both APKs compile the same shared module). Phase 1 adds a documented no-op branch `is ParsedMessage.SportState -> { /* Phase 2 consumes sport_state; dropped in Phase 1 */ }` placed before `is ParsedMessage.Unknown` — compile compatibility only, NOT glasses consumption; HUD-02 consumption of sport_state remains Phase 2.
7. **SessionStore constructor should take `File`, not `Context`** — small divergence from `DiskTileCache(context)` justified by the locked JVM-testability requirement (RESEARCH Pattern 6/Pitfall 7); the service passes `File(filesDir, "activities")`.

## Metadata

**Analog search scope:** `shared/src/main/java/**`, `phone/src/main/java/**`, `phone/src/main/res/**`, `phone/src/main/AndroidManifest.xml`, `*/build.gradle.kts` (glasses module excluded — out of scope this phase)
**Files scanned:** 16 read in full or targeted (13 Kotlin, 1 manifest, 2 gradle) + layout/drawable excerpts
**Pattern extraction date:** 2026-07-03
