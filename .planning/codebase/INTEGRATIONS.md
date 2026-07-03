# External Integrations

**Analysis Date:** 2026-07-02

## Bluetooth SPP Communication

### Protocol Overview

Phone and glasses communicate over Bluetooth Serial Port Profile (SPP) using the standard SPP UUID `00001101-0000-1000-8000-00805F9B34FB`. Messages are JSON-encoded, one per line (newline-delimited), sent over a UTF-8 `BufferedWriter`/`BufferedReader` pair.

**Phone side** (`phone/src/.../HudStreamingService.kt`):
- Acts as SPP server: listens on both insecure and secure RFCOMM sockets simultaneously
- Uses `listenUsingInsecureRfcommWithServiceRecord()` (primary) and `listenUsingRfcommWithServiceRecord()` (fallback, suffix `_S`)
- Accepts connections in a loop on background threads
- Stores connected clients in `CopyOnWriteArrayList<BufferedWriter>` and `CopyOnWriteArrayList<ClientSession>`

**Glasses side** (`glasses/src/.../BluetoothClient.kt`):
- Acts as SPP client: loops over bonded Bluetooth devices and attempts connection
- Connection fallback chain: insecure SPP -> secure SPP -> RFCOMM channel 1 (reflection fallback)
- Automatically reconnects with 3-second delay on disconnection
- Filters bonded devices: prefers devices with major class `0x200` (phone category), otherwise uses any bonded device

### Message Types

| Type Field (`t`) | Direction | Content | Frequency |
|-----------------|-----------|---------|-----------|
| `state` | Phone -> Glasses | GPS location, bearing, speed, accuracy, speed limit, distance to next step | Every 1s (location update interval) |
| `route` | Phone -> Glasses | Waypoint list (GeoJSON coordinates), total distance, total duration | On route calculation |
| `step` | Phone -> Glasses | Current turn instruction, maneuver type, step distance | On step change |
| `steps_list` | Phone -> Glasses | Full ordered step list with current index | On route calc and step change |
| `notification` | Phone -> Glasses | Android notification title, text, package name, timestamp | On notification posted (if enabled) |
| `settings` | Phone -> Glasses | TTS enabled, imperial units, mini-map mode/style, notification streaming toggle, turn alert toggle, tile cache size, speed display toggles | On settings change, on new client connect |
| `wifi_creds` | Phone -> Glasses | Wi-Fi SSID, passphrase, enabled flag | On Wi-Fi share start, on new client connect |
| `tile_req` | Glasses -> Phone | Tile ID, zoom level, x, y coordinates | On tile cache miss during render |
| `tile_resp` | Phone -> Glasses | Tile ID, base64-encoded PNG data (or empty/null for no tile) | On tile request fulfillment |
| `apk_start` | Phone -> Glasses | Total APK file size, total chunk count | Before APK transfer |
| `apk_chunk` | Phone -> Glasses | Chunk index, base64-encoded chunk data (3072 bytes raw each) | During APK transfer |
| `apk_end` | Phone -> Glasses | (no fields) | After last chunk sent |

### Message Format

All messages are single-line JSON objects with a type discriminator field `"t"`. Example state message:
```json
{"t":"state","lat":37.7749,"lng":-122.4194,"bearing":180.0,"speed":13.5,"accuracy":8.0,"spdLim":65,"distNext":150.0}
```

### Connection Lifecycle

1. Phone app starts `HudStreamingService` as a foreground service
2. Phone opens SPP server sockets (insecure + secure) and begins accepting
3. Glasses app starts `BluetoothClient` which loops trying to connect to bonded devices
4. On connection, phone sends cached settings and Wi-Fi creds to the new client
5. Phone spawns a reader thread per client to handle inbound tile requests
6. On disconnection, the client is removed from the lists and the socket is closed
7. Glasses automatically attempt reconnection after 3-second delay

### Error Handling

- Phone broadcasts fail silently: failed writes remove the dead client from the list
- Glasses try 3 connection methods before declaring failure
- Glasses handle parsing errors by logging and ignoring malformed messages
- Tile requests are capped at 512KB response bytes to prevent OOM
- Inbound messages over 1024 characters are discarded

### APK Transfer Over Bluetooth

The phone can send APK files to the glasses for OTA updates:
- File is read via `ContentResolver` from a user-picked URI
- Split into 3072-byte raw chunks, base64-encoded
- Sent as `apk_start` -> N x `apk_chunk` -> `apk_end`
- Glasses reassemble chunks to `glasses_update.apk` in cache directory
- On completion, glasses launch the system package installer via `ACTION_VIEW` intent and `FileProvider`

## OSRM Routing

### Endpoint

`https://router.project-osrm.org/route/v1/driving/{fromLng},{fromLat};{toLng},{toLat}?overview=full&geometries=geojson&steps=true`

- Coordinates use longitude,latitude order (OSRM standard)
- Overview mode: `full` (full geometry for route rendering)
- Geometries: `geojson` (GeoJSON coordinate arrays)
- Steps: `true` (detailed turn-by-turn instructions)

### Implementation

File: `phone/src/.../OsrmClient.kt`
- Singleton `object` with no state
- `getRoute()` method called on a background `Thread` by `NavigationManager`
- Returns `RouteResult` containing: waypoints (downsampled to max 500), navigation steps, total distance (meters), total duration (seconds)
- Waypoint downsampling: stride = `maxOf(1, totalPoints / 500)` to prevent oversized route data
- Uses `HttpURLConnection` (no OkHttp) with 10s connect/read timeouts
- User-Agent: `RokidHudMaps/1.0`

### Response Parsing

- Checks `code` field equals `"Ok"`; otherwise throws `RuntimeException`
- Extracts waypoints from `geometry.coordinates` (GeoJSON `[lng, lat]` array)
- Extracts navigation steps from `legs[*].steps` including:
  - `maneuver.type` and `maneuver.modifier` for turn direction
  - `maneuver.location` for step position
  - `name` for street name
  - `distance` and `duration` for step metrics

### Maneuver Mapping

The client maps OSRM maneuver types to a simplified key set used by the glasses for arrow rendering:
- `arrive`, `depart`, `left`, `right`, `straight`, `slight left`, `slight right`, `sharp left`, `sharp right`, `uturn`
- Natural language instructions are generated from type + modifier + street name

### Error Handling

- Connection failures and parse errors are caught by `NavigationManager` and surfaced via `NavigationCallback.onNavigationError()`
- No retry logic; each route request is a single attempt

## Nominatim Geocoding

### Endpoint

`https://nominatim.openstreetmap.org/search?q={query}&format=json&limit={limit}&addressdetails=0`

### Implementation

File: `phone/src/.../NominatimClient.kt`
- Singleton `object` with no state
- `search()` method takes query string and optional limit (default 6)
- URL-encodes query parameter
- Uses `HttpURLConnection` with 8s connect/read timeouts
- User-Agent: `RokidHudMaps/1.0`

### Rate Limiting

Nominatim has a usage policy (max 1 request per second). The current implementation does **not** enforce any rate limiting. Searches are user-initiated (manual search button press), so natural human pacing provides implicit rate limiting in practice.

### Response

Returns `List<SearchResult>` with `displayName`, `lat`, `lng`. Parsed from JSON array: `[{"display_name": "...", "lat": "37.77", "lon": "-122.41"}, ...]`.

### Error Handling

- Any parsing or connection exception is caught in `MainActivity.performSearch()` and displayed to the user as `"Search error: {message}"`
- Empty results list is handled gracefully with `"No results found"` status

## Overpass API (Speed Limits)

### Endpoint

`https://overpass-api.de/api/interpreter` (POST method)

### Query Format

```
[out:json];way(around:30,{lat},{lng})[maxspeed];out tags;
```

Queries OpenStreetMap ways within 30 meters of the current location that have a `maxspeed` tag.

### Implementation

File: `phone/src/.../OverpassSpeedLimitClient.kt`
- Class with per-instance caches (`cachedSpeedLimitKmh`, `lastQueryLat`, `lastQueryLng`, `lastQueryTime`)
- Uses `Executors.newSingleThreadExecutor()` for async queries
- Uses `HttpURLConnection` with POST and `application/x-www-form-urlencoded`
- User-Agent: `RokidHudMaps/1.0`
- 10s connect/read timeouts

### Polling Strategy

- Interval: 15 seconds between queries (`QUERY_INTERVAL_MS`)
- Distance trigger: 200 meters movement (`QUERY_DISTANCE_M`)
- Thread safety: `@Volatile` flags with `querying` guard to prevent concurrent queries
- `getCachedSpeedLimit()` returns the cached value immediately (or -1 if no data) and triggers a new query on background thread if conditions are met

### Maxspeed Parsing

The `parseMaxspeed()` method handles multiple formats:
- Plain number: `"50"` -> 50 km/h
- With unit: `"30 mph"` -> converts to km/h (48), `"60 km/h"` -> 60
- Special values: `"none"`, `"walk"` -> -1
- Uses regex `(\d+)` to extract the numeric portion

### Error Handling

- Query failures are logged (Log.w) and silently ignored; cached speed limit persists until the next successful query
- No retry logic

## Map Tile System

### Tile Sources

Both phone and glasses maintain the same fallback chain of tile URLs:

1. `https://basemaps.cartocdn.com/dark_all/%d/%d/%d@2x.png` (HiDPI dark tiles, primary)
2. `https://basemaps.cartocdn.com/dark_all/%d/%d/%d.png` (Standard dark tiles, fallback)
3. `https://tile.openstreetmap.org/%d/%d/%d.png` (OSM default, last fallback)

The dark CartoDB tiles are specifically chosen for the green-monochrome display of the Rokid glasses.

### Phone-Side Tile Proxy

File: `phone/src/.../HudStreamingService.kt`
- Phone acts as a tile proxy for the glasses over Bluetooth
- Tile fetches run on a 4-thread `Executors.newFixedThreadPool(4)`
- Phone checks its own `DiskTileCache` first, then tries tile URLs with 8s timeouts
- Tile data is base64-encoded and sent as `tile_resp` message
- Response capped at 512KB to prevent OOM

### Glasses-Side Tile Manager

File: `glasses/src/.../TileManager.kt`
- 200-slot LRU memory cache (`LruCache<String, Bitmap>`)
- Fallback chain: memory cache -> disk cache -> Bluetooth proxy (if connected) -> direct HTTP fetch (if no proxy)
- Deduplication via `ConcurrentHashMap` pending set to avoid duplicate tile requests
- 4 background threads for fetching

### Disk Caching Strategy

File: `shared/src/.../cache/DiskTileCache.kt`
- Shared module, used by both phone and glasses
- Cache directory: `Context.cacheDir/map_tiles/`
- File naming: `{z}/{x}/{y}.png`
- Default max size: 100 MB (configurable via settings, values: 50/100/200/500 MB)
- LRU eviction: sorts files by `lastModified` ascending, deletes oldest until under limit
- Single-threaded executor for writes and eviction
- Read operations touch `lastModified` for LRU tracking
- `clear()` deletes the entire cache directory recursively

## Android System Integrations

### GPS/Location

- **Provider:** Google Play Services Fused Location Provider (`com.google.android.gms:play-services-location:21.1.0`)
- **Priority:** `PRIORITY_HIGH_ACCURACY`
- **Interval:** 1 second (`LOCATION_INTERVAL_MS`), with min update interval of 500ms
- **Implementation:** `HudStreamingService.startLocationUpdates()` requests location updates on main looper
- **Permissions:** `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`

### Notifications

**Foreground Service Notification:**
- Channel: `hud_streaming` (created in `HudApplication.onCreate()`)
- Title: "Rokid HUD Active"
- Text: "Streaming to glasses"
- Importance: `IMPORTANCE_LOW`
- Ongoing: true (not dismissable)

**Notification Forwarding (Phone to Glasses):**
- `HudNotificationListenerService` extends `NotificationListenerService`
- Listens for all notifications from other apps
- Forwards `android.title` and `android.text` to glasses via `sendNotification()`
- Respects `stream_notifications` setting toggle
- Ignores own app's notifications to prevent feedback loops
- Sent over Bluetooth as `notification` messages

### WakeLock

- **Type:** `PARTIAL_WAKE_LOCK` (keeps CPU running when screen off)
- **Tag:** `{packageName}:streaming`
- **Reference counted:** `false`
- **Acquired:** On service start
- **Released:** On service destroy
- **Purpose:** Ensures GPS and BT streaming continue when phone screen turns off

### Battery Optimization

- `MainActivity.promptBatteryOptimizationIfNeeded()` checks if the app is ignoring battery optimizations
- If not, prompts user to open system settings via `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent
- Declared permission: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

### Wi-Fi Direct (Phone Side)

File: `phone/src/.../WifiShareManager.kt`
- Creates a Wi-Fi Direct group with phone as group owner
- Connected glasses get internet via phone's cellular data
- Uses `WifiP2pManager.createGroup()` and `requestGroupInfo()`
- Sends SSID/passphrase to glasses over Bluetooth via `wifi_creds` message
- Tracks group state (OFF, CREATING, ACTIVE, FAILED)

### Wi-Fi Client (Glasses Side)

File: `glasses/src/.../WifiConnector.kt`
- Receives Wi-Fi credentials via Bluetooth `wifi_creds` message
- Uses 3 connection methods in parallel:
  - `WifiNetworkSpecifier` (API 29+)
  - Legacy `WifiManager.addNetwork()` / `enableNetwork()`
  - `WifiNetworkSuggestion` (API 29+)
- Enables Wi-Fi radio via `Settings.Global.WIFI_ON` and reflection `IWifiManager.setWifiEnabled()`
- Falls back to opening Wi-Fi settings intent if enabling fails
- Monitors connection via `ConnectivityManager.NetworkCallback`
- Disconnects and disables Wi-Fi when `wifi_creds` with `enabled=false` is received

### Keep Screen On (Glasses)

- `HudActivity` applies `FLAG_KEEP_SCREEN_ON` to ensure the display stays on during navigation
- Fullscreen immersive mode using `SYSTEM_UI_FLAG_IMMERSIVE_STICKY` (pre-API 30) and `WindowInsetsController` (API 30+)

### Audio Routing (Phone Side)

File: `phone/src/.../BluetoothAudioRouter.kt`
- Attempts A2DP connection for high-quality audio to glasses
- Fallback: SCO (Synchronous Connection Oriented) for voice-quality audio
- Uses reflection to call `BluetoothA2dp.connect()` and `BluetoothHeadset.connect()`
- Monitors connection state via `BroadcastReceiver`
- Uses `AudioManager` for SCO management (`MODE_IN_COMMUNICATION`, `startBluetoothSco()`)

## Rokid SDK

### Rokid CXR-M SDK (Required Dependency)

**Maven:** `com.rokid.cxr:client-m:1.0.4`
**Repository:** `https://maven.rokid.com/repository/maven-public/`

This is the actively used SDK for BLE-based device discovery and connection management.

**Features Used:**
- `CxrApi.initBluetooth()` — initiates Rokid-specific BLE pairing handshake (`RokidConnectionManager.kt`)
- `CxrApi.connectBluetooth()` — connects to paired glasses using socket UUID and MAC address
- `CxrApi.deinitBluetooth()` — disconnects and cleans up
- `CxrApi.isBluetoothConnected` — connection state check
- `BluetoothStatusCallback` — async callbacks for pairing/connection lifecycle

**Transitive Dependencies (pulled by client-m):**
- Retrofit 2.9.0
- OkHttp 4.12.0
- Gson 2.10.1

**Connection Flow:**
1. BLE scan discovers Rokid glasses by service UUID `00009100-0000-1000-8000-00805f9b34fb`
2. `CxrApi.initBluetooth()` performs the Rokid pairing handshake
3. On success, `BluetoothStatusCallback.onConnectionInfo()` provides socket UUID and MAC
4. `CxrApi.connectBluetooth()` connects via classic Bluetooth using those credentials
5. Regular SPP communication begins over the established RFCOMM socket

### Rokid Mobile SDK (Optional Stub)

**Status:** Stub only — AAR not yet included
**File:** `phone/src/.../RokidSdkHelper.kt`

A placeholder that logs credentials but does nothing. The `TODO` comment indicates it should be replaced with:
```
RokidMobileSDK.init(context.applicationContext, clientId, clientSecret, accessKey)
RokidMobileSDK.setDeviceType(DeviceType.GLASS)
```

Credentials are loaded from `local.properties` (`rokid.client.id`, `rokid.client.secret`, `rokid.access.key`) but the real SDK is not yet integrated.

### Rokid Double-Back Gesture (Glasses)

File: `glasses/src/.../HudActivity.kt`
- Listens for broadcast action `com.rokid.glass.homekey.doubleback`
- On double-back, triggers app shutdown with a "Rokid Maps is closing" overlay

---

*Integration audit: 2026-07-02*
