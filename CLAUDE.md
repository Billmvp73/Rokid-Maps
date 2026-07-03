<!-- GSD:project-start source:PROJECT.md -->
## Project

**Rokid HUD Maps**

An Android-based heads-up display for Rokid AR glasses that provides turn-by-turn navigation for cycling and running. The phone handles GPS, routing, and activity recording, then streams everything to the glasses over Bluetooth. Users can import cycling/running routes from Strava, get turn-by-turn directions floating in their field of view, and see real-time performance metrics (speed, distance, elapsed time, pace) — no need to pull out a phone or look down at a bike computer.

**Core Value:** Cyclists and runners see their route and live performance metrics floating in their field of view, keeping their eyes on the road and their phone in their pocket.

### Constraints

- **Platform**: Android only (phone + Rokid glasses)
- **Language**: Kotlin (matching existing codebase)
- **Connectivity**: Bluetooth SPP for phone↔glasses, internet for Strava API + map tiles + routing
- **Battery**: Must work in background with screen off (WakeLock pattern already established)
- **Strava API**: Requires OAuth 2.0 Authorization Code Grant (Strava does NOT support PKCE — client_secret must be embedded in APK via BuildConfig). Subscriber rate limits: 300 reads/15min, 1,000 writes/15min. Tokens stored in EncryptedSharedPreferences.
- **No cloud dependencies**: Existing architecture avoids cloud services; Strava integration is the first external auth-required API
- **Navigation engine**: Continue using OSRM (free, no API key) for route navigation; Strava routes provide the waypoints
<!-- GSD:project-end -->

<!-- GSD:stack-start source:codebase/STACK.md -->
## Technology Stack

## Languages & Runtime
- All application code is written in Kotlin. No Java source files exist.
- Kotlin compiler plugin: `org.jetbrains.kotlin.android` version 2.1.0
- All modules compile to JVM target 17 (`jvmTarget = "17"`)
- Source/target compatibility: `JavaVersion.VERSION_17`
## Build System
| Component | Version | Source |
|-----------|---------|--------|
| Gradle | 8.11.1 | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 8.7.3 | `build.gradle.kts` |
| Kotlin Plugin | 2.1.0 | `build.gradle.kts` |
- Root project name: `RokidHudMaps` (defined in `settings.gradle.kts`)
- Repository: `https://maven.rokid.com/repository/maven-public/` added for Rokid SDK modules
- `org.gradle.jvmargs=-Xmx2048m`
- `android.useAndroidX=true`
- `kotlin.code.style=official`
- `android.nonTransitiveRClass=true`
## Android SDK Targets
| Module | Type | Min SDK | Target SDK | Compile SDK |
|--------|------|---------|------------|-------------|
| `shared/` | Library | 28 (Android 9) | N/A | 34 (Android 14) |
| `phone/` | Application | 28 (Android 9) | 34 | 34 |
| `glasses/` | Application | 28 (Android 9) | 34 | 34 |
- `shared`: `com.rokid.hud.shared`
- `phone`: `com.rokid.hud.phone` (application ID: `com.rokid.hud.phone`)
- `glasses`: `com.rokid.hud.glasses` (application ID: `com.rokid.hud.glasses`)
## Key Dependencies
| Dependency | Version | Module | Purpose |
|-----------|---------|--------|---------|
| `org.json:json` | 20231013 | `shared` | JSON encode/decode for Bluetooth protocol messages |
| `androidx.core:core-ktx` | 1.12.0 | `phone`, `glasses` | AndroidX core extensions |
| `androidx.appcompat:appcompat` | 1.6.1 | `phone`, `glasses` | App compat support |
| `com.google.android.material:material` | 1.11.0 | `phone` | Material Design components |
| `androidx.constraintlayout:constraintlayout` | 2.1.4 | `phone` | Layout engine |
| `com.google.android.gms:play-services-location` | 21.1.0 | `phone` | Fused Location Provider for GPS |
| `org.osmdroid:osmdroid-android` | 6.1.18 | `phone` | Map rendering in phone app's navigation preview |
| `com.rokid.cxr:client-m` | 1.0.4 | `phone` | Rokid CXR-M SDK for BLE pairing and glasses connection |
| `com.squareup.retrofit2:retrofit` | 2.9.0 | `phone` | Retrofit (transitive dependency of CXR SDK, not used directly by app code) |
| `com.squareup.retrofit2:converter-gson` | 2.9.0 | `phone` | Gson converter (explicitly declared in phone/build.gradle.kts; previously transitive via CXR SDK) |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | `phone` | OkHttp (explicitly declared in phone/build.gradle.kts; previously transitive via CXR SDK — legacy app code uses `HttpURLConnection`; the Strava client will use OkHttp) |
| `com.squareup.okhttp3:logging-interceptor` | 4.12.0 | `phone` | Logging interceptor (explicitly declared in phone/build.gradle.kts; previously transitive via CXR SDK) |
| `com.google.code.gson:gson` | 2.10.1 | `phone` | Gson (explicitly declared in phone/build.gradle.kts; previously transitive via CXR SDK — legacy app code uses `org.json`; the Strava client will use Gson) |
## External APIs & Services
| Service | Endpoint | Purpose | Auth |
|---------|----------|---------|------|
| OSRM (Open Source Routing Machine) | `https://router.project-osrm.org/route/v1/driving` | Turn-by-turn driving route calculation | None |
| Nominatim | `https://nominatim.openstreetmap.org/search` | Address search / geocoding | None (User-Agent identifies app) |
| Overpass API | `https://overpass-api.de/api/interpreter` | Speed limit queries via `[maxspeed]` tag lookup | None |
| CartoDB Dark Tiles | `https://basemaps.cartocdn.com/dark_all/%d/%d/%d@2x.png` | Primary dark-themed map tiles (high-DPI) | None |
| CartoDB Dark Tiles (1x) | `https://basemaps.cartocdn.com/dark_all/%d/%d/%d.png` | Dark map tiles (standard resolution, fallback) | None |
| OpenStreetMap Tiles | `https://tile.openstreetmap.org/%d/%d/%d.png` | Map tile fallback when CartoDB unavailable | None |
## Language Features & Patterns
- `OsrmClient` (`phone/src/.../OsrmClient.kt`) — stateless HTTP client as a Kotlin `object`
- `NominatimClient` (`phone/src/.../NominatimClient.kt`) — stateless HTTP client as a Kotlin `object`
- `ProtocolCodec` (`shared/src/.../protocol/ProtocolCodec.kt`) — message encoding/decoding as a `object`
- `ProtocolConstants` (`shared/src/.../protocol/ProtocolConstants.kt`) — constants as a `object`
- `RokidSdkHelper` (`phone/src/.../RokidSdkHelper.kt`) — SDK bridge as a `object`
- `ParsedMessage` (`shared/src/.../ProtocolCodec.kt`) — sealed class hierarchy for decoded Bluetooth messages with 12 variants + `Unknown`
- **No coroutines.** All async work uses raw `Thread()`, `Executors.newSingleThreadExecutor()`, `Executors.newFixedThreadPool(4)`, and `Handler`/`HandlerThread`
- `CopyOnWriteArrayList` for thread-safe client lists in `HudStreamingService`
- `ConcurrentHashMap` for pending tile requests in `TileManager`
- `@Volatile` annotations on shared mutable state fields
- `synchronized` blocks used in `BluetoothClient.sendTileRequest`
- `HudState` provides `copy()` and `withNotification()` methods returning a new instance
- No mutable state flows; state is replaced wholesale via `copy()`
- All HTTP calls use `java.net.HttpURLConnection` directly (not OkHttp, despite it being a transitive dependency)
- No Retrofit usage in application code (only as transitive dependency for CXR SDK)
- JSON parsing uses `org.json.JSONObject/JSONArray` (not Gson, despite it being a transitive dependency)
<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->
## Conventions

## Naming Conventions
| Element | Convention | Example |
|---------|-----------|---------|
| Classes | PascalCase, descriptive nouns | `HudStreamingService`, `NavigationManager`, `OverpassSpeedLimitClient` |
| Methods | camelCase, verb phrases | `startNavigation()`, `sendSettings()`, `onLocationUpdate()` |
| Properties | camelCase | `lastLat`, `lastLng`, `currentStepIndex` |
| Constants | UPPER_SNAKE_CASE in companion object | `TAG`, `SPP_UUID`, `RECONNECT_DELAY_MS` |
| Enums | PascalCase | `MapLayoutMode.FULL_SCREEN`, `WifiShareManager.State.ACTIVE` |
| Enum values | UPPER_SNAKE_CASE | `DISCONNECTED`, `CONNECTING`, `ACTIVE` |
| Interfaces | PascalCase, noun or -able suffix | `NavigationCallback` |
| Data classes | PascalCase with Message/Item suffix | `StateMessage`, `TileResponseMessage`, `NotificationItem` |
| Sealed class variants | PascalCase, contextual | `ParsedMessage.State`, `ParsedMessage.Route` |
| Package names | `com.rokid.hud.{module}[.{sub}]` | `com.rokid.hud.shared.protocol`, `com.rokid.hud.phone`, `com.rokid.hud.glasses` |
| Private vars with public getter | Trailing comment `private set` | `var state: State = State.OFF; private set` |
| Boolean properties | `is` prefix or standard adjective | `isNavigating`, `running`, `ttsReady`, `a2dpConnected` |
## Code Style
- **Language:** Kotlin only (no Java files in source).
- **Indentation:** 4 spaces. No tabs.
- **Kotlin code style:** `official` (declared in `gradle.properties`).
- **Braces:** K&R style (opening brace on same line). `if`/`when` body on same line when short.
- **Comments:**
- **Trailing commas:** Not used consistently.
- **String formatting:** `String.format()` for numeric formatting; string templates with `$` for simple interpolation.
- **Null handling:** Kotlin `?.` safe-call and `?:` elvis operator used extensively. `!!` rarely used (only in `service!!` in `MainActivity.kt`).
## Architecture Patterns
- **Module-level (multi-module Android):**
- **No architectural framework:** No MVVM, MVP, MVI, or Clean Architecture. No Jetpack ViewModel, LiveData, or Compose.
- **No dependency injection:** No Hilt/Dagger/Koin. Dependencies wired manually in `onCreate()`.
- **Data flow:**
- **Callback pattern:** Used for async results: `NavigationCallback` interface, `onStateChanged` lambdas, `onTileReceived` lambdas.
- **`object` singletons:** Used for stateless utilities (`ProtocolCodec`, `NominatimClient`, `OsrmClient`, `RokidSdkHelper`, `ProtocolConstants`).
## Error Handling
- **Exception strategy:** Wrap every I/O or SDK call in `try/catch`. Never propagate exceptions to callers.
- **Logging on failure:** `Log.w(TAG, message)` or `Log.e(TAG, message, exception)`.
- **User-facing errors:** `Toast.makeText(...)` in Activities. Service uses `NavigationCallback.onNavigationError()`.
- **Cleanup pattern:** `try { ... } catch (_: Exception) {}` — underscores the caught exception, used in `close()`, `release()`, `unregisterReceiver()`, `stopBleScan()`.
- **Default values:** Failed reads return `null` or sentinel values (`-1`, `emptyList()`).
- **JSON parse errors:** `ProtocolCodec.decode()` catches `Exception` and returns `ParsedMessage.Unknown(raw)` — never throws.
- **Network errors:** All `HttpURLConnection` I/O wrapped. Timeout exceptions logged but not rethrown.
## Logging
- **Framework:** `android.util.Log`.
- **TAG convention:** `companion object { private const val TAG = "ShortName" }` — short descriptive class nickname.
- **Log levels:**
- **No structured logging** (no SLF4J, Timber, or external logging library).
- **No Kotlin extension functions** for logging.
- **Format:** `"Context description: ${value}"` or `"Operation failed: ${e.message}"`.
## Threading & Async
- **No coroutines** used anywhere in the codebase. All async is thread-based.
- **`Thread { ... }.start()` pattern:** Used for long-running I/O: `BluetoothClient.connectLoop()`, `NavigationManager.calculateRoute()`, `WifiConnector.connect()`, `HudStreamingService.sendApkToGlasses()`, `runClientReader()`.
- **`Executors.newSingleThreadExecutor()`:** Used in `DiskTileCache` (serial disk writes), `OverpassSpeedLimitClient` (serial Overpass queries).
- **`Executors.newFixedThreadPool(4)`:** Used in `HudStreamingService` (tile fetching), `TileManager` (tile loading).
- **Main thread posting:**
- **Thread safety:** `@Volatile` on `running`, `connected`, `currentState`, `cachedSpeedLimitKmh`, `querying`, `lastQuery*` fields. `CopyOnWriteArrayList` for client lists. `ConcurrentHashMap` for pending tile requests.
- **Bluetooth I/O threading:**
## JSON Conventions
- **Library:** `org.json:json:20231013` (Android's built-in `org.json.JSONObject`, `JSONArray`, `JSONArray`).
- **No Gson/Moshi/Kotlinx.serialization:** All JSON is manually constructed/parsed.
- **Encoding pattern:** `JSONObject().apply { put(key, value) }.toString()`
- **Decoding pattern:** `JSONObject(line)`, then `json.getXxx(key)` / `json.optXxx(key, default)`.
- **Field naming:** Short abbreviations defined in `ProtocolConstants` as `const val` strings:
- **Nullable fields:** Encoded as empty string, decoded with `optString(key, null).takeIf { it?.isNotEmpty() == true }`.
- **Base64 encoding:** `Base64.getEncoder().encodeToString(bytes)` / `Base64.decode(string, Base64.DEFAULT)` for binary data (tiles, APK chunks).
## Bluetooth Message Conventions
- **Transport:** Bluetooth SPP (RFCOMM) over serial port profile UUID `00001101-0000-1000-8000-00805F9B34FB`.
- **Format:** Newline-delimited JSON. One message per line. `BufferedWriter.write()` + `newLine()` + `flush()`.
- **Message type field:** Always `"t"` as first-level key. Types defined in `ProtocolConstants.MessageType` object.
- **Versioning:** No protocol version field. Messages are assumed compatible.
- **Message types:**
## UI Patterns
- **Phone app:**
- **Glasses app:**
- **No Fragment usage** anywhere.
- **No ViewBinding / DataBinding** — all view references via `findViewById` or `requireViewById`.
## Resource Naming
- **Layout files:** `activity_*.xml` for Activity layouts, `item_*.xml` for list items.
- **Drawable files:** `bg_*.xml` for background shapes, `ic_*.xml` for icons.
- **String resources:** Minimal usage. `app_name` only in each module.
- **Color resources:** `snake_case` (`primary`, `primary_dark`, `white`). Defined in `phone/src/main/res/values/colors.xml`.
- **Theme resources:** `PascalCase` with parent prefix. `Theme.RokidHud`, `Theme.RokidGlasses`, `Theme.RokidGlasses.Fullscreen`.
- **ID naming:** `camelCase` matching the view's role (`btnStart`, `searchInput`, `navInstructionText`, `switchTts`).
## Git Practices
- **Commit style:** Imperative present tense, title only (no body), typically prefixed with type:
- **Branching:** No feature branches visible in git history (21 commits, all on `main`).
- **Commit granularity:** Medium-to-large single commits. Each commit encapsulates a coherent feature set.
- **No commit bodies or co-authors** in existing history (only `Co-Authored-By: Claude <noreply@anthropic.com>` on recent additions).
- **Author:** Single author throughout.
## Cross-Cutting Patterns
- **Permission handling:** `checkPermissionsAndStart()` pattern — collect needed permissions into `mutableListOf()`, request all at once, check results in `onRequestPermissionsResult()`.
- **Preferences:** `context.getSharedPreferences(name, MODE_PRIVATE)` for app-wide prefs, `getPreferences(MODE_PRIVATE)` for activity-only prefs. Manual `edit().putXxx().apply()` pattern.
- **Weak/soft references:** Not used despite callback-heavy architecture (risk of leaked Activity references).
- **Reflection:** Used in `BluetoothAudioRouter` for `connect()` method on hidden `BluetoothA2dp`/`BluetoothHeadset` proxies. Used in `WifiConnector` for `IWifiManager.setWifiEnabled()`.
- **Android API version branching:** `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R/Q/TIRAMISU)` pattern extensively for API-specific behavior.
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->
## Architecture

## System Overview
```
```
## Module Responsibilities
### `shared/` (Android Library)
- **Bluetooth Protocol**: Defines all message types (`StateMessage`, `RouteMessage`, `StepMessage`, etc.) and the JSON codec (`ProtocolCodec`) that serializes/deserializes them.
- **Protocol Constants**: Single source of truth for JSON field names (`ProtocolConstants.FIELD_*`) and message type strings (`ProtocolConstants.MessageType.*`).
- **Disk Tile Cache**: `DiskTileCache` — file-system based LRU cache for map tiles stored under `context.cacheDir/map_tiles/` with configurable max size.
- **No Android framework dependencies beyond Context** (for cache). The protocol layer uses only `org.json`.
### `phone/` (Android Application)
- **Main UI** (`MainActivity`): Dark-themed interface with search bar, saved places, route card, live navigation panel (osmdroid `MapView`), and settings toggles (TTS, units, mini-map style, notifications, speed display, cache size).
- **Streaming Service** (`HudStreamingService`): Android foreground service that runs a Bluetooth SPP server (insecure + secure), acquires a `PARTIAL_WAKE_LOCK`, collects GPS locations via `FusedLocationProviderClient`, manages tile proxy requests, and broadcasts state messages to all connected BT clients.
- **Navigation Engine** (`NavigationManager`): Tracks current position against route waypoints, advances steps when within 150m of next step turn point, detects off-route condition (80m) for auto-rerouting, and detects arrival (30m radius).
- **Routing Client** (`OsrmClient`): HTTP client for OSRM (Open Source Routing Machine) at `router.project-osrm.org`. Parses GeoJSON geometry waypoints and turn-by-turn steps.
- **Search Client** (`NominatimClient`): HTTP client for OpenStreetMap's Nominatim geocoding API at `nominatim.openstreetmap.org/search`.
- **Speed Limit Client** (`OverpassSpeedLimitClient`): Queries Overpass API (`overpass-api.de`) for road speed limits using OSM `maxspeed` tags. Caches results with 15-second/meter cooldown.
- **Glasses Connection** (`RokidConnectionManager`): Wraps the Rokid CXR-M SDK to handle BLE pairing -> classic BT connection using SDK-provided socket UUID and MAC address.
- **Bluetooth Device Scanner** (`DeviceScanActivity`): BLE scan (Rokid service UUID `00009100`) + classic BT discovery + bonded device list UI. Saves selected glasses address to `SharedPreferences`.
- **Notification Forwarding** (`HudNotificationListenerService`): `NotificationListenerService` that captures phone notifications and forwards them to the streaming service for broadcast to glasses.
- **Wi-Fi Sharing** (`WifiShareManager`): Manages Wi-Fi Direct P2P group creation (phone as group owner) so glasses get internet via the phone's cellular data.
- **Audio Routing** (`BluetoothAudioRouter`): Manages A2DP/SCO audio routing for TTS navigation instructions. Uses reflection to force A2DP connect. Falls back to SCO if A2DP unavailable.
- **Saved Places** (`SavedPlacesManager`): Persists user-saved locations as JSON in `SharedPreferences`.
- **Rokid SDK Helper** (`RokidSdkHelper`): Stub wrapper for the optional Rokid Mobile SDK (requires separate AAR). Currently logs credentials but does nothing.
- **Wi-Fi Hotspot Sending**: Glasses can receive hotspot credentials via BT, then auto-connect via `WifiConnector`.
### `glasses/` (Android Application)
- **Main HUD Activity** (`HudActivity`): Fullscreen singleTask activity. Manages BT connection lifecycle, TTS initialization, battery receiver, Wi-Fi auto-connect, APK installation (from received chunks), and `HudView` state updates. Monitors `Rokid double-back broadcast` and touchpad `KEYCODE_ENTER` for shutdown. Auto-exits after `EXIT_ON_STOP_DELAY_MS` (400ms) when moved to background.
- **Custom HUD Rendering** (`HudView`): Custom `View` with `onDraw()` that renders four layout modes, each combining a live tile map (rotated to bearing), a compass, direction text, and an info area (notifications or upcoming steps). All rendering uses only green-scale colors for monochrome displays. Map tiles are fetched lazily through `TileManager` and rendered with a green luminance filter.
- **Bluetooth Client** (`BluetoothClient`): Runs a connect loop that iterates bonded devices, tries three connection methods (insecure SPP, secure SPP, channel 1 fallback), reads JSON lines from the socket, and updates `HudState` by dispatching parsed messages. Can send tile requests and receive APK chunk transfers.
- **Layout State** (`HudState`): Immutable data class holding all rendering state (location, waypoints, step instructions, notifications, settings, speed, etc.). Setting a new state triggers `postInvalidate()` on `HudView`.
- **Tile Manager** (`TileManager`): Two-level tile cache — in-memory `LruCache(200)` + `DiskTileCache`. Falls back to BT proxy fetch (phone fetches tiles) or direct HTTP from CartoDB/OSM if no BT proxy. Tiles are decoded to `Bitmap` and stored for `HudView.drawLiveMap()`.
- **Wi-Fi Connector** (`WifiConnector`): Auto-connects to a Wi-Fi network (typically the phone's hotspot) using multiple connection strategies: `WifiNetworkSpecifier` (API 29+), legacy `WifiConfiguration`, and `WifiNetworkSuggestion`. Monitors connectivity state.
## Data Flow
### Primary Data Path: GPS -> Phone -> Bluetooth -> Glasses -> Display
### Tile Request Flow (Glasses -> Phone -> Internet -> Phone -> Glasses)
### Navigation Flow
## Communication Patterns
### Bluetooth SPP Message Protocol
- **Transport**: RFCOMM Bluetooth SPP over UUID `00001101-0000-1000-8000-00805F9B34FB` (standard SPP)
- **Server**: Phone listens on both insecure and secure server sockets simultaneously
- **Client**: Glasses connect to any bonded phone; tries three connection methods (insecure SPP, secure SPP, channel 1 reflection fallback)
- **Connection lifecycle**: Phone accepts connections, stores `ClientSession`, re-sends cached state (settings + WiFi creds) to new clients. Glasses maintain a single socket, disconnect triggers reconnect loop.
- **Concurrency**: Phone uses `CopyOnWriteArrayList` for thread-safe client management. Glasses use a single-threaded reader.
### JSON Line-Delimited Format
- Each message is a single line of JSON terminated by `\n`
- Maximum line length enforced: phone ignores lines > 1024 bytes (`HudStreamingService.runClientReader()`)
- Message type identified by field `"t"` (see `ProtocolConstants.FIELD_TYPE`)
- 12 message types: `state`, `route`, `step`, `notification`, `settings`, `wifi_creds`, `tile_req`, `tile_resp`, `apk_start`, `apk_chunk`, `apk_end`, `steps_list`
- Unknown or malformed lines produce `ParsedMessage.Unknown` (logged, ignored)
### Broadcast Pattern (Phone -> Multiple Glasses)
- `HudStreamingService.broadcast()` writes to all connected `BufferedWriter` instances
- Dead clients are removed on write failure
- State, route, step messages are broadcast to all connected clients
- Settings and WiFi creds are cached and re-sent to new clients on connect
### Request/Response Pattern (Glasses -> Phone -> Glasses)
- Tile requests: Glasses send `tile_req` over BT, phone processes, returns `tile_resp`
- Phone handles each tile request in a fixed thread pool (4 threads)
- Tile response includes base64-encoded PNG data (up to 512KB cap to avoid OOM)
- APK update: Phone sends `apk_start` (total size/chunks), N x `apk_chunk` (3KB raw chunks, base64 encoded), `apk_end`. Glasses reassemble to `glasses_update.apk` in cache dir, install via `FileProvider`.
### Dominant Flow Direction: Phone -> Glasses
- GPS state: 1Hz stream
- Route, step: on change
- Notifications: on arrival
- Settings, WiFi creds: on change
- APK chunk: during firmware update
## Key Architectural Decisions
### Why 3 Modules?
- **`shared/`** is an Android library consumed by both `phone/` and `glasses/`. This ensures the protocol types and serialization are compiled identically on both sides, preventing decode failures from version mismatch. The disk tile cache is also shared so glasses can cache tiles directly even when fetching them without the BT proxy.
- **`phone/`** and **`glasses/`** are separate APKs because they run on different hardware (phone vs. Rokid glasses) with different Android manifests, permissions, and lifecycle requirements. They are developed in a single repo for coordinated releases.
### Why JSON over Bluetooth SPP?
- **Simplicity**: No binary protocol definition, no protobuf/MessagePack codegen. JSON is human-readable for debugging and trivially parsable by `org.json`.
- **Debuggable**: Messages can be viewed as raw text on the BT stream
- **Performance adequate**: GPS state messages are small (~120 bytes). Tile responses are larger (up to ~700KB base64 for a 512KB tile) but infrequent.
- **Trade-off**: Base64 encoding inflates tile data by ~33%, but avoids binary framing complexity. Line-delimited JSON avoids fragmentation issues.
### Why OSRM (not Google Maps)?
- **Cost**: OSRM is free and self-hostable. Google Maps Directions API has per-request costs.
- **Offline potential**: OSRM can be self-hosted on a LAN server for offline navigation.
- **OpenStreetMap data**: Tiles from CartoDB/OSM are free. Speed limits from Overpass API are free.
- **Drawback**: OSRM's public instance (router.project-osrm.org) has rate limits and no SLA.
### Why a Phone-as-Proxy Architecture?
- **No internet on glasses**: Many Rokid glasses lack reliable internet connectivity (Wi-Fi may be unavailable). The phone bridges internet access.
- **Tile proxying**: Glasses request tiles through the phone's BT connection. The phone downloads tiles from CartoDB/OSM and caches them. This works even when the glasses have no Wi-Fi.
- **Power management**: Phone handles power-intensive GPS, routing, and network I/O. Glasses only render the HUD, conserving battery on the glasses.
### Why Green-Only Rendering?
- Some Rokid AR glasses have monochrome green OLED displays. `HudView` uses a `ColorMatrixColorFilter` that converts all tile luminance to green values.
- UI elements use a hierarchy of green shades: bright green (`#00FF00`), medium (`#00CC00`), dim (`#008800`), dark (`#004400`), faint (`#003300`).
## App Lifecycle
### Phone App
- `HudStreamingService` is a foreground service with `foregroundServiceType="location|connectedDevice"`
- Holds `PARTIAL_WAKE_LOCK` so CPU stays on when phone screen is off
- `START_STICKY` return value means service restarts if killed
- Requests user to disable battery optimization for the app
- `MainActivity.onDestroy()`: unbinds service
- `HudStreamingService.onDestroy()`: releases WakeLock, stops navigation, removes location updates, closes BT server and client sockets, shuts down tile executor, clears tile cache
### Glasses App
- Double-tap on touchpad `KEYCODE_ENTER` or Rokid double-back broadcast triggers `shutdownApp()`
- Shows "Rokid Maps is closing" message for 1.8 seconds, then calls `finishAndRemoveTask()` + `Process.killProcess()`
- If app is moved to background (onStop), a 400ms delay runnable triggers auto-shutdown (glasses have no standard Android task switcher)
- On `onDestroy()`: stops BT client, disconnects WiFi, shuts down TileManager, releases TTS
## State Management
### Navigation State (Phone)
- **Source of truth**: `NavigationManager` holds `isNavigating`, `steps`, `routeWaypoints`, `currentStepIndex`
- **Callback interface**: `NavigationCallback` interface with methods for route calculated, step changed, error, arrived, rerouting
- **Dual delivery**: Callbacks go to both the BT broadcast system (to glasses) and the UI callback (phone UI)
### Settings State (Phone)
- Persisted in two `SharedPreferences` files:
- On any setting change (`setOnCheckedChangeListener`), `sendCurrentSettings()` is called to broadcast the full `SettingsMessage` to glasses
- Settings are also cached in `HudStreamingService.cachedSettings` and re-sent to newly connected BT clients
### UI State (Phone)
- **Layout state**: `MainActivity` fields track `streaming`, `searchResultsList`, `selectedDest`, `showingSaved`, `currentRouteWaypoints`, `fullRouteSteps`
- **Glasses status**: Stored in `"rokid_glasses"` prefs (glasses name, MAC address)
- All UI updates happen via `runOnUiThread` callbacks from background threads
### HUD State (Glasses)
- `HudState` is an immutable data class in `glasses/src/main/java/com/rokid/hud/glasses/HudState.kt`
- Contains: GPS position, bearing, speed, waypoints, instruction, maneuvers, notifications, layout mode, settings, connectivity status, battery level, speed limit, closing message
- State transitions are purely functional: `BluetoothClient.processMessage()` creates new `HudState` copies using Kotlin `data class copy()`
- `HudView.state` setter triggers `postInvalidate()` to redraw
- `HudState.toggleLayout()` cycles through layout modes: FULL_SCREEN -> SMALL_CORNER -> FULL_SCREEN (or MINI_BOTTOM/SPLIT -> FULL_SCREEN on phone toggle)
### Layout Modes (Glasses)
| Mode | Description | Activation |
|------|-------------|------------|
| `FULL_SCREEN` | Map 72% top, text 28% bottom | Default, or tap to cycle from corner |
| `SMALL_CORNER` | Text left 62%, map bottom-right 38% | Tap once from full screen |
| `MINI_BOTTOM` | Map strip 25% bottom, directions above | Phone toggle: `useMiniMap=true, miniMapStyle=strip` |
| `MINI_SPLIT` | Bottom 25%: map left, directions right | Phone toggle: `useMiniMap=true, miniMapStyle=split` |
## Background Processing
### Phone Side
- **`HudStreamingService`**: Android foreground service with WAKE_LOCK. Runs indefinitely.
- **`FusedLocationProviderClient`**: Main-thread location updates at 1-second interval
- **Bluetooth server**: Two listener threads (insecure + secure sockets)
- **Per-client reader**: Dedicated thread per connected client
- **Tile fetcher**: Fixed thread pool (4 threads) handles tile downloads
- **Navigation routing**: Raw `Thread` for OSRM HTTP requests
- **Speed limit queries**: Single-thread executor for Overpass API
### Glasses Side
- **BT connection loop**: Single dedicated thread (`connectLoop()`)
- **BT reader**: Runs on the connection thread (`readFromSocket()`)
- **Tile fetcher**: Fixed thread pool (4 threads) in `TileManager`
- **WiFi connect**: Runs on a raw `Thread` in `WifiConnector.connect()`
- **TTS**: Runs on main thread (TextToSpeech library handles its own background)
<!-- GSD:architecture-end -->

<!-- GSD:skills-start source:skills/ -->
## Project Skills

No project skills found. Add skills to any of: `.claude/skills/`, `.agents/skills/`, `.cursor/skills/`, `.github/skills/`, or `.codex/skills/` with a `SKILL.md` index file.
<!-- GSD:skills-end -->

<!-- GSD:workflow-start source:GSD defaults -->
## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:
- `/gsd-quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd-debug` for investigation and bug fixing
- `/gsd-execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->



<!-- GSD:profile-start -->
## Developer Profile

> Profile not yet configured. Run `/gsd-profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->
