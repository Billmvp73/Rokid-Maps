<!-- refreshed: 2026-07-02 -->
# Architecture

**Analysis Date:** 2026-07-02

## System Overview

```
                      ┌──────────────────────────────────────────────────────────────────┐
                      │                      PHONE (com.rokid.hud.phone)                  │
                      │                                                                  │
                      │  ┌─────────────┐   ┌──────────────┐   ┌──────────────────────┐  │
                      │  │ GPS (Google  │   │ OSRM Client  │   │ Nominatim Search     │  │
                      │  │ FusedLocation│   │ (router.     │   │ (nominatim.          │  │
                      │  │ Provider)    │   │ project-osrm │   │ openstreetmap.org)   │  │
                      │  └──────┬──────┘   │ .org)        │   └──────────────────────┘  │
                      │         │          └──────┬───────┘                              │
                      │         │                 │                                      │
                      │         ▼                 ▼                                      │
                      │  ┌──────────────────────────────────────────────────────────┐   │
                      │  │           HudStreamingService (Foreground Service)       │   │
                      │  │  ┌────────────┐  ┌─────────────┐  ┌────────────────┐    │   │
                      │  │  │ Navigation │  │ Speed Limit │  │  Bluetooth SPP │    │   │
                      │  │  │ Manager    │  │ (Overpass)  │  │  Server (2x)   │    │   │
                      │  │  └────────────┘  └─────────────┘  └───────┬────────┘    │   │
                      │  │  ┌──────────────┐  ┌────────────────┐     │             │   │
                      │  │  │ DiskTileCache│  │ WiFi Share Mgr │     │             │   │
                      │  │  └──────────────┘  └────────────────┘     │             │   │
                      │  └───────────────────────────────────────────┼─────────────┘   │
                      │                    MainActivity (UI)         │                 │
                      └──────────────────────────────────────────────┼─────────────────┘
                                                                     │
                          Bluetooth SPP (line-delimited JSON)         │
                          RFCOMM UUID: 00001101-0000-1000-8000-00805F9B34FB
                                                                     │
                      ┌──────────────────────────────────────────────┼─────────────────┐
                      │                    GLASSES (com.rokid.hud.glasses)              │
                      │                                              │                 │
                      │  ┌──────────────────────────────────────────┐ │                 │
                      │  │           BluetoothClient                ◄┘                 │
                      │  │   - connectLoop() -> reads BT socket     │                   │
                      │  │   - processMessage() -> updates HudState  │                   │
                      │  │   - sendTileRequest()                     │                   │
                      │  └────────────┬─────────────────────────────┘                   │
                      │               │                                                  │
                      │               ▼                                                  │
                      │  ┌──────────────────────┐    ┌────────────────────┐             │
                      │  │ HudState (immutable   │◄───│  HudActivity      │             │
                      │  │ data class, triggers  │    │  lifecycle, TTS,  │             │
                      │  │ postInvalidate())     │    │  BT permissions,  │             │
                      │  └──────────┬───────────┘    │  shutdown logic   │             │
                      │             │                └────────┬──────────┘             │
                      │             ▼                         │                        │
                      │  ┌──────────────────────┐             │                        │
                      │  │ HudView (Custom View) │◄────────────┘                        │
                      │  │  onDraw() with 4      │                                      │
                      │  │  layout modes:        │  ┌────────────────────┐             │
                      │  │  - FULL_SCREEN        │  │  TileManager       │             │
                      │  │  - SMALL_CORNER       │  │  - LruCache(200)   │             │
                      │  │  - MINI_BOTTOM        │  │  - DiskTileCache   │             │
                      │  │  - MINI_SPLIT         │  │  - BT proxy fetch  │             │
                      │  └──────────────────────┘  └────────────────────┘             │
                      │                                              ┌────────────────┐│
                      │                                              │ WifiConnector   ││
                      │                                              │ (auto-connects  ││
                      │                                              │  to phone       ││
                      │                                              │  hotspot for    ││
                      │                                              │  internet)      ││
                      │                                              └────────────────┘│
                      └────────────────────────────────────────────────────────────────┘

                          SHARED LIBRARY (com.rokid.hud.shared)
                      ┌────────────────────────────────────────────────────────────┐
                      │  protocol/  Messages.kt  ProtocolConstants.kt  ProtocolCodec.kt  │
                      │  cache/     DiskTileCache.kt                                    │
                      └────────────────────────────────────────────────────────────┘
```

The architecture follows a **client-server pattern over Bluetooth SPP**, where the **phone is the server** and the **glasses are the client**. The phone accumulates all data (GPS, routing, speed limits, notifications) and streams it to the glasses in real-time as JSON line-delimited messages.

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

1. **GPS Location** (every 1 second):
   - `FusedLocationProviderClient.requestLocationUpdates()` in `HudStreamingService.onLocationUpdate()` receives a `Location`
   - Current lat/lng/bearing/speed/accuracy stored in `lastLat`/`lastLng`
   - Speed limit queried from `OverpassSpeedLimitClient.getCachedSpeedLimit()`
   - Distance to next step queried from `NavigationManager.getDistanceToNextStep()`
   - `StateMessage` constructed and encoded via `ProtocolCodec.encodeState()`
   - Message broadcast to all connected BT clients via `broadcast()` -> `BufferedWriter.write()` + `newLine()` + `flush()`

2. **Bluetooth Transmission**:
   - Phone side: `HudStreamingService.startBluetoothServer()` listens on an insecure and a secure `BluetoothServerSocket`
   - Each connected client gets a `ClientSession` with a `BufferedWriter` and a reader thread
   - Glasses side: `BluetoothClient.connectLoop()` iterates bonded devices, tries each connection method, on success spawns `readFromSocket()`
   - JSON lines are read via `BufferedReader.readLine()`

3. **Message Processing on Glasses**:
   - `BluetoothClient.processMessage()` calls `ProtocolCodec.decode(line)` which parses the JSON
   - Matches the message type and updates `currentState` (an immutable `HudState`) with the relevant field(s)
   - Calls `onStateUpdate(currentState)` which calls `hudView.state = newState` -> sets the property -> calls `postInvalidate()`

4. **Rendering**:
   - `HudView.onDraw()` checks `state.layoutMode`, calls the appropriate layout function
   - `drawLiveMap()` rotates canvas to match bearing, draws tiles via `TileManager.getTile()`, draws route line via `drawRouteOnTiles()`, draws player arrow
   - `drawDirections()` shows current instruction + maneuver arrow + distance
   - `drawInfoArea()` shows notifications or upcoming steps
   - `drawStatusBar()` shows BT/WiFi status, speed, speed limit, battery
   - `drawTurnAlertOverlay()` shows large turn popup when approaching a turn

### Tile Request Flow (Glasses -> Phone -> Internet -> Phone -> Glasses)

1. `TileManager.getTile()` is called from `HudView.drawTiles()` for each tile needed
2. If not in memory cache (`LruCache`), schedules a background fetch
3. If a BT proxy is available (`onTileRequestViaProxy`), sends `TileRequestMessage` via Bluetooth
4. `HudStreamingService.handleTileRequest()` receives the request on the phone
5. Checks `DiskTileCache` first; if miss, tries three tile URL templates (CartoDB dark @2x, CartoDB dark, OSM)
6. Fetches tile bytes, stores in `DiskTileCache`, encodes as `TileResponseMessage` (base64), sends back over BT
7. `BluetoothClient.processMessage()` matches `ParsedMessage.TileResp`, calls `onTileReceived` -> `TileManager.deliverTile()`
8. Tile decoded from base64 to `Bitmap`, stored in memory + disk cache, `onTileLoaded()` triggers invalidation

### Navigation Flow

1. User searches in `MainActivity` -> `NominatimClient.search()` on background thread -> results displayed
2. User selects destination -> taps "Start Navigation" -> `HudStreamingService.startNavigation()`
3. `NavigationManager.startNavigation()` calls `calculateRoute()` which calls `OsrmClient.getRoute()` on background thread
4. Route result (waypoints + steps) returned -> `callback.onRouteCalculated()` -> phone encodes `RouteMessage` + `StepsListMessage`, broadcasts to glasses
5. GPS updates advance steps: `NavigationManager.onLocationUpdate()` checks distance to next step waypoint
6. When within 150m, advances `currentStepIndex`, calls `callback.onStepChanged()` -> phone broadcasts `StepMessage` + `StepsListMessage`
7. If within 30m of destination and near last step, fires `onArrived()`
8. If more than 80m from nearest route waypoint and cooldown elapsed (15s), triggers `onRerouting()` -> recalculates route

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

The vast majority of messages flow from phone to glasses:
- GPS state: 1Hz stream
- Route, step: on change
- Notifications: on arrival
- Settings, WiFi creds: on change
- APK chunk: during firmware update

The only message that flows glasses -> phone is `tile_req`.

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

**Startup flow:**
1. `HudApplication.onCreate()`: Initializes osmdroid config, creates notification channel, attempts Rokid CXR SDK init
2. `MainActivity.onCreate()`: Binds views, sets up WiFi manager, listeners
3. User taps "Start Streaming":
   - `checkPermissionsAndStart()` requests location/BT/notification permissions
   - `startStreaming()` creates explicit intent, calls `ContextCompat.startForegroundService()` for `HudStreamingService`
   - Binds to service via `ServiceConnection`
   - `HudStreamingService.onStartCommand()`: Acquires `PARTIAL_WAKE_LOCK`, initializes navigation, starts BT server (both sockets), starts `FusedLocationProviderClient` location updates

**Navigation flow:**
1. User searches, selects destination, taps "Start Navigation"
2. `HudStreamingService.startNavigation()` calls `NavigationManager.startNavigation()`
3. `NavigationManager.calculateRoute()` on background thread via `OsrmClient.getRoute()`
4. Route result arrives, callback fires, message encoded and broadcast to glasses
5. GPS updates flow to `NavigationManager.onLocationUpdate()` which handles step advancement, rerouting, arrival

**Background service:**
- `HudStreamingService` is a foreground service with `foregroundServiceType="location|connectedDevice"`
- Holds `PARTIAL_WAKE_LOCK` so CPU stays on when phone screen is off
- `START_STICKY` return value means service restarts if killed
- Requests user to disable battery optimization for the app

**Shutdown flow:**
- `MainActivity.onDestroy()`: unbinds service
- `HudStreamingService.onDestroy()`: releases WakeLock, stops navigation, removes location updates, closes BT server and client sockets, shuts down tile executor, clears tile cache

### Glasses App

**Startup flow:**
1. `HudActivity.onCreate()`:
   - Sets fullscreen immersive mode (API 30+ `WindowInsetsController` or legacy `SYSTEM_UI_FLAG_IMMERSIVE_STICKY`)
   - Enables `FLAG_KEEP_SCREEN_ON`
   - Instantiates `HudView`, `TileManager`, `BluetoothClient`, `WifiConnector`, TTS
   - Registers battery and double-back broadcast receivers
   - Requests BT/location permissions then starts `BluetoothClient.start()`

**BT connection flow:**
1. `BluetoothClient.start()` spawns `connectLoop()` on a dedicated thread
2. Iterates bonded devices, filters for phones (major device class 0x200)
3. Tries three connection methods per device: insecure SPP, secure SPP, channel 1 reflection fallback
4. On success, enters `readFromSocket()` loop reading JSON lines
5. If disconnected, sleeps 3s and retries all bonded devices

**Shutdown flow:**
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
  - `getPreferences(MODE_PRIVATE)` for per-user settings: TTS, imperial units, mini-map mode, mini-map style
  - `"rokid_hud_prefs"` for HUD-specific settings: notification streaming, full steps toggle, turn alert, tile cache size, speed display toggles
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

Defined in `MapLayoutMode` enum:
| Mode | Description | Activation |
|------|-------------|------------|
| `FULL_SCREEN` | Map 72% top, text 28% bottom | Default, or tap to cycle from corner |
| `SMALL_CORNER` | Text left 62%, map bottom-right 38% | Tap once from full screen |
| `MINI_BOTTOM` | Map strip 25% bottom, directions above | Phone toggle: `useMiniMap=true, miniMapStyle=strip` |
| `MINI_SPLIT` | Bottom 25%: map left, directions right | Phone toggle: `useMiniMap=true, miniMapStyle=split` |

The phone-controlled mini-map modes (`MINI_BOTTOM`, `MINI_SPLIT`) bypass notifications entirely. The local tap-to-toggle only switches between `FULL_SCREEN` and `SMALL_CORNER`.

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

---

*Architecture analysis: 2026-07-02*
