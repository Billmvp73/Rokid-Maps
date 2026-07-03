# Technical Concerns & Technical Debt

**Analysis Date:** 2026-07-02

## Summary

This codebase is a well-structured Android multi-module project with clear separation of concerns between phone and glasses apps. However, it contains significant technical debt in the form of duplicated utility functions, hardcoded API endpoints, broad exception swallowing patterns, and several thread-safety gaps in shared mutable state. API rate limiting is absent for public OSM services, and Bluetooth communication has edge cases around reconnection, message size limits, and unencrypted data transmission. Several Android lifecycle and permission handling patterns are fragile.

---

## Technical Debt

### TODO/FIXME/HACK Items

| File | Line | Comment | Severity |
|------|------|---------|----------|
| `phone/src/main/java/.../RokidSdkHelper.kt` | 38 | `// TODO: Replace with actual SDK init when AAR is available:` | MEDIUM |

This is the only explicit TODO in the codebase. The `RokidSdkHelper` is a compile-safe stub -- it reads credentials from BuildConfig but never calls the actual Rokid SDK. The method body logs credentials but does nothing functional. If the Rokid SDK AAR is never added, this class should be removed; if it is added, the stub needs actual calls uncommented.

### Hardcoded Values

| File | Value | Issue | Severity |
|------|-------|-------|----------|
| `phone/src/main/java/.../OsrmClient.kt` | `BASE_URL = "https://router.project-osrm.org"` | Public demo server, subject to rate limits and deprecation | MEDIUM |
| `phone/src/main/java/.../NominatimClient.kt` | `BASE_URL = "https://nominatim.openstreetmap.org"` | Public server, usage policy requires max 1 req/sec | MEDIUM |
| `phone/src/main/java/.../OverpassSpeedLimitClient.kt` | `OVERPASS_URL = "https://overpass-api.de/api/interpreter"` | Public endpoint, no failover to mirrors | MEDIUM |
| `phone/src/main/java/.../HudStreamingService.kt` | `TILE_URLS` array (lines 59-63) | Duplicated identically in `glasses/.../TileManager.kt` lines 20-24 | MEDIUM |
| `phone/src/main/java/.../HudStreamingService.kt` | `USER_AGENT = "RokidHudMaps/1.0 (Phone proxy)"` | 5 different User-Agent strings across 5 files, all slightly different | LOW |
| `shared/src/main/java/.../Messages.kt` | Default `tileCacheSizeMb: Int = 100` | Default value in message class is also the UI default; if changed in one place, must update both | LOW |
| `glasses/src/main/java/.../TileManager.kt` | `CACHE_SIZE = 200` | LRU cache counts entries, not bytes (sizeOf returns 1) -- can OOM with large bitmaps | HIGH |
| `phone/src/main/java/.../NavigationManager.kt` | Magic constants `STEP_ADVANCE_RADIUS_M`, `OFF_ROUTE_RADIUS_M`, etc. | Reasonable values but undocumented rationale | LOW |

**Route waypoint downsampling** -- `phone/src/main/java/.../OsrmClient.kt` line 56: `val stride = maxOf(1, coords.length() / 500)` trims waypoints to ~500 regardless of route length. For very long routes (>1000km) this loses significant detail in the polyline.

---

## Error Handling Gaps

**Broad exception swallowing with empty catch blocks.** The pattern `catch (_: Exception) {}` appears 30+ times across the codebase. While some cases are justified (best-effort cleanup in finally blocks), many swallow errors with no logging at all:

- `glasses/src/main/java/.../BluetoothClient.kt` line 91 -- socket read error silently swallowed with no logging
- `glasses/src/main/java/.../WifiConnector.kt` lines 88-95, 183, 238, 279, 288, 323, 330 -- cleanup failures swallowed
- `phone/src/main/java/.../HudStreamingService.kt` lines 126, 130, 132, 135 -- cleanup silently fails
- `phone/src/main/java/.../RokidConnectionManager.kt` lines 87, 127, 131 -- SDK calls silently catch

**NominatimClient has no error handling at all.** `phone/src/main/java/.../NominatimClient.kt` -- if the HTTP request fails or the JSON is malformed, the exception propagates unhandled to the `MainActivity.performSearch` thread, where it is caught generically and displayed as a generic error message.

**OsrmClient throws RuntimeException.** `phone/src/main/java/.../OsrmClient.kt` line 47: `throw RuntimeException("OSRM error: ...")` -- this is caught by the caller in NavigationManager, but `RuntimeException` is too broad; a specific `RouteException` would be cleaner.

**null propagation through NavigationManager.** `phone/src/main/java/.../NavigationManager.kt` -- `navigationManager` is nullable throughout `HudStreamingService`. Every access uses `?.` safe calls, which means failures are silently ignored. If `initNavigation()` fails or is never called, navigation methods silently do nothing.

---

## Thread Safety Concerns

**Data race in NavigationManager.** `phone/src/main/java/.../NavigationManager.kt` -- `steps` (line 32) and `currentStepIndex` (line 35) are mutable non-volatile properties. `steps` is written by `calculateRoute` (called from `Thread{...}` at line 117) and read by `onLocationUpdate` (called from the location callback thread at line 62). There is no synchronization, volatile, or atomic reference protecting these. This is a genuine data race -- the glasses could receive stale or partially-constructed step data.

```
Thread A (calculateRoute): steps = result.steps; currentStepIndex = 0
Thread B (onLocationUpdate): if (!isNavigating || steps.isEmpty()) return
```

**RokidConnectionManager.isConnected() unsafe.** `phone/src/main/java/.../RokidConnectionManager.kt` line 131: `fun isConnected(): Boolean = try { cxrApi.isBluetoothConnected } catch (_: Exception) { false }` -- calls SDK method on caller's thread without any synchronization guarantee. If called from UI thread while SDK callback is processing, could see inconsistent state.

**lastLat/lastLng not volatile (defensive hardening).** `phone/src/main/java/.../HudStreamingService.kt` lines 71-72: `private var lastLat = 0.0` and `lastLng = 0.0`. The location callback is registered on the main looper (`Looper.getMainLooper()`, line 468), so both write (location callback) and read (`getLastLocation()`) occur on the main thread — no active data race. However, adding `@Volatile` is recommended as defensive hardening in case these fields are later accessed from background threads (e.g., by a future component such as an activity recorder).

**broadcast thread-safety reliance on CopyOnWriteArrayList.** `phone/src/main/java/.../HudStreamingService.kt` line 293-306 -- `broadcast()` iterates `clients` (a `CopyOnWriteArrayList`) and removes dead clients. This is safe for iteration, but the `removeAll` call at line 305 makes the iteration snapshot potentially stale when determining write failures.

---

## Memory Concerns

**LRU cache counts entries, not bytes.** `glasses/src/main/java/.../TileManager.kt` lines 29-31: `override fun sizeOf(key: String, value: Bitmap) = 1` with `CACHE_SIZE = 200`. A 256x256 PNG decoded to ARGB_8888 Bitmap is ~256KB. 200 entries = ~50MB. This could cause OOM on memory-constrained glasses hardware. Should use `value.byteCount` for `sizeOf`.

**DiskTileCache sizeBytes walks all files synchronously.** `shared/src/main/java/.../DiskTileCache.kt` line 61: `cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }` -- this traverses the entire tile directory on every cache size display update. With a 500MB cache of thousands of small tile files, this blocks the calling thread and is called on the UI thread from `MainActivity.updateCacheSizeText()` (via `service?.tileCacheSizeBytes()`).

**apkOutputStream not finalized on disconnect.** `glasses/src/main/java/.../BluetoothClient.kt` line 39-40 -- if Bluetooth disconnects mid-APK transfer, `apkOutputStream` is not closed. The only cleanup is when a new APK_START message arrives (line 230) or when `stop()` is called. A stale open file descriptor leaks until the next APK transfer.

**Notification list unbounded growth (minor).** `glasses/src/main/java/.../HudState.kt` line 55: `MAX_NOTIFICATIONS = 8` -- this is bounded, but notifications are prepended to the list every time a new one arrives. If all 8 slots are full, the oldest is dropped. This is acceptable but means there is no deduplication -- repeated notifications from the same app fill all slots.

**Tile fetch connections not always closed.** `phone/src/main/java/.../HudStreamingService.kt` lines 329-349 -- the tile fetch loop iterates over `TILE_URLS` templates. If `conn.responseCode != 200`, `conn.disconnect()` is called. But if `readBounded` (line 337) throws an exception, `conn.disconnect()` is called inside the inner catch (line 346). However, the outer catch at line 356 would catch the `Throwable` if the inner catch somehow misses -- this is acceptable but fragile.

---

## Bluetooth Reliability

**No reconnection backoff.** `glasses/src/main/java/.../BluetoothClient.kt` line 28: `RECONNECT_DELAY_MS = 3000L` is a fixed 3-second retry interval. If the phone is out of range, the glasses burn CPU polling bonded devices every 3 seconds indefinitely. There is no exponential backoff or max retry limit.

**No connection timeout for socket.connect().** `glasses/src/main/java/.../BluetoothClient.kt` lines 125-146: `tryConnect()` calls `s.connect()` (line 138) without setting a timeout. On some Bluetooth stacks, `connect()` can block for 30+ seconds or indefinitely if the remote device is unreachable. This blocks the entire reconnect loop.

**Long message lines silently dropped.** `phone/src/main/java/.../HudStreamingService.kt` line 382: `if (line.length > 1024) continue` -- messages longer than 1024 characters are silently dropped with no logging. APK chunk data or large tile responses could theoretically exceed this. The line length filter is a minor injection guard but also silently drops legitimate large messages.

**APK transfer has no resume capability.** `phone/src/main/java/.../HudStreamingService.kt` lines 201-251 -- if Bluetooth disconnects mid-APK transfer, the entire transfer must restart from the beginning. There is no chunk acknowledgment or offset tracking. On a slow BT connection, this makes large APK updates unreliable.

**getBondedDevices heuristic is fragile.** `glasses/src/main/java/.../BluetoothClient.kt` lines 111-122 -- filters bonded devices by major device class `0x200` (phone), but falls back to all bonded devices if none match. On glasses that have multiple paired phones, this could connect to the wrong device. There is no mechanism to select a specific paired device.

**No keepalive/ping between phone and glasses.** If the SPP connection silently drops (e.g. the glasses go out of range), the phone only discovers this on the next `broadcast()` write failure. The glasses detect it immediately because `readLine()` returns null. The phone side has a ~1-second window (location update interval) where it thinks the client is still connected.

**Thread-per-connection without thread pool for BT readers.** `phone/src/main/java/.../HudStreamingService.kt` line 405: `Thread { ... }.start()` spawns a new OS thread for each client reader. With multiple concurrent clients, this could create many threads. The accept loop also spawns a thread per socket type (secure + insecure).

---

## API Rate Limiting

**OSRM -- no rate limiting or caching.** `phone/src/main/java/.../OsrmClient.kt` -- `router.project-osrm.org` is a public demo server with no SLA. Every off-route detection triggers `calculateRoute()` (NavigationManager.kt line 111). If the user is driving in circles near a junction, multiple reroute requests fire within `REROUTE_COOLDOWN_MS = 15000` (15 seconds), but one request per cooldown period still hits the public server each time. There is no response caching.

**Nominatim -- no rate limiting.** `phone/src/main/java/.../NominatimClient.kt` -- Nominatim's usage policy explicitly requires max 1 request/second. The `performSearch` in MainActivity fires each search as a separate thread without any delay or queuing. A user rapidly typing search queries violates the terms of service.

**Overpass API -- no HTTP 429 handling.** `phone/src/main/java/.../OverpassSpeedLimitClient.kt` -- the 15-second interval (line 16) provides some rate limiting, but the code does not check for HTTP 429 (Too Many Requests) responses. If the server throttles, the error is silently caught in the broad `catch (e: Exception)` at line 68 and the speed limit cache stays stale.

**No circuit breaker pattern.** None of the three API clients implement circuit breaking. If any API becomes unavailable, the code keeps retrying on every access (or on every location update for Overpass), wasting battery and network.

---

## Configuration Management

**All API URLs hardcoded.** Every external service URL is a string constant in Kotlin source code. There is no mechanism to point at different environments (staging, self-hosted OSRM, local dev). Changing the OSRM endpoint requires a rebuild.

**Tile URL list duplicated across modules.** `phone/src/main/java/.../HudStreamingService.kt` lines 59-63 and `glasses/src/main/java/.../TileManager.kt` lines 20-24 define identical `TILE_URLS` arrays. If a tile source URL changes or is added, both files must be updated. This belongs in the `shared` module.

**User-Agent strings inconsistent across 5 locations.** Each HTTP client defines its own User-Agent string with different suffixes. For rate limiting analysis and server logging, these should be unified in the shared module.

**local.properties credentials require rebuild.** Rokid SDK credentials (`ROKID_CLIENT_ID`, `ROKID_CLIENT_SECRET`, `ROKID_ACCESS_KEY`) are compiled into BuildConfig at build time. Changing credentials requires a rebuild rather than loading at runtime.

**No protocol version negotiation.** The phone and glasses communicate via SPP JSON messages but there is no version field or capability negotiation in the protocol. If the protocol evolves (new message types, field changes), there is no backward compatibility mechanism. The glasses are expected to upgrade in lockstep with the phone.

---

## Unhandled Error States

**No GPS -- no timeout or recovery.** `phone/src/main/java/.../HudStreamingService.kt` -- if GPS is lost mid-navigation, `onLocationUpdate` stops being called. The last known lat/lng is held in `lastLat`/`lastLng` but never cleared or marked as stale. The glasses continue to show the last position frozen. There is no timeout mechanism to detect GPS loss.

**No internet -- no user-visible error indicator.** All HTTP clients silently catch exceptions. If the phone loses internet:
- OSRM routing fails -> NavigationManager logs "Route calculation failed" but retries indefinitely on each location update
- Tile downloads silently fail -> empty tiles shown on glasses
- Overpass queries fail -> speed limits stay at -1
- Nomination search fails -> generic "Search error" toast

**Service killed and recreated state restoration.** `phone/src/main/java/.../HudStreamingService.kt` -- `onStartCommand` returns `START_STICKY`, so Android restarts the service if killed. On restart, `onStartCommand` runs again, calling `initNavigation()`, `startBluetoothServer()`, and `startLocationUpdates()`. But if the user was mid-navigation, the destination is lost -- `startNavigation()` is not called with the previous destination. The service restarts but navigation does not resume.

**Permission revocation while streaming.** If the user revokes `ACCESS_FINE_LOCATION` in Settings while the service is running, `FusedLocationProviderClient` silently stops delivering updates. The service does not monitor for permission changes and continues operating with stale location data.

**Disk full in cache directory.** `shared/src/main/java/.../DiskTileCache.kt` -- if the disk is full, `file.writeBytes(bytes)` throws an IOException caught by the broad catch at line 42. The tile is simply not cached. No warning or cleanup is triggered.

---

## Code Duplication

**Haversine formula duplicated.** `phone/src/main/java/.../NavigationManager.kt` lines 149-156 and `phone/src/main/java/.../OverpassSpeedLimitClient.kt` lines 91-98 implement the identical `haversineM` function. This should be extracted to the `shared` module as a utility.

**Distance formatting duplicated.** `phone/src/main/java/.../MainActivity.kt` lines 894-906 (`formatDist`) and `glasses/src/main/java/.../HudView.kt` lines 581-596 (`formatDistance`) implement the same imperial/metric distance formatting with identical logic.

**TTS distance speech formatting duplicated.** `phone/src/main/java/.../BluetoothAudioRouter.kt` lines 112-130 and `glasses/src/main/java/.../HudActivity.kt` lines 198-214 format TTS speech text identically. This is the same distance-to-speech conversion in two places.

**Tile URL arrays and fetch logic duplicated.** `phone/src/main/java/.../HudStreamingService.kt` lines 59-63 and `glasses/src/main/java/.../TileManager.kt` lines 20-24 define the same tile source URLs. The fallback fetch loop logic is also similar.

**User-Agent strings across 5 files:**
- `OsrmClient.kt:27` -- `"RokidHudMaps/1.0"`
- `NominatimClient.kt:17` -- `"RokidHudMaps/1.0"`
- `OverpassSpeedLimitClient.kt:48` -- `"RokidHudMaps/1.0"`
- `HudStreamingService.kt:64` -- `"RokidHudMaps/1.0 (Phone proxy)"`
- `TileManager.kt:25` -- `"RokidHudMaps/1.0 (Android; Rokid Glasses)"`

**Permission checking boilerplate.** The pattern of building a `needed` list of permissions, checking with `ContextCompat.checkSelfPermission`, and calling `ActivityCompat.requestPermissions` is repeated in `MainActivity.kt:754-772`, `HudActivity.kt:264-281`, and `DeviceScanActivity.kt:221-237`.

---

## Android Lifecycle Issues

**DeviceScanActivity broadcast receiver lifecycle gap.** `phone/src/main/java/.../DeviceScanActivity.kt` -- `classicReceiver` is registered in `onCreate` (line 141) but unregistered in `onDestroy` (line 152). If the activity is recreated due to a configuration change (e.g., orientation), the old activity's `onDestroy` is called after the new `onCreate`. During the overlap period, both old and new instances have the same receiver registered, causing duplicate broadcasts. The `registerReceiver` at line 141 does not check if it was previously registered.

**HudNotificationListenerService binding lifecycle.** `phone/src/main/java/.../HudNotificationListenerService.kt` -- binds to `HudStreamingService` in `onListenerConnected` (line 36) with `Context.BIND_AUTO_CREATE`. If `HudStreamingService` is destroyed and recreated (START_STICKY), the listener's `connection` is not automatically rebound. The listener stays bound to the old dead service reference until `onServiceDisconnected` fires, which may be delayed.

**HudActivity start/stop timer race.** `glasses/src/main/java/.../HudActivity.kt` lines 142-158 -- the `exitWhenStoppedRunnable` posts with a 400ms delay in `onStop`. If the activity is quickly restarted (e.g., user navigates to settings and immediately returns), `onStart` removes the callback. But if the activity is recreated (not just restarted), `onCreate` runs first, and `onStart`/`onStop` timings could cause the shutdown to fire during the transition.

**btAudioRouter.init called on every onCreate.** `phone/src/main/java/.../MainActivity.kt` line 202 -- `btAudioRouter.init()` is called in `onCreate`. This creates a new `TextToSpeech` instance. If the activity is recreated, `onCreate` runs before `onDestroy` of the old instance. Two TTS instances briefly coexist. The old TTS is released in `onDestroy` of the old instance, but `onResume` of the new instance calls `btAudioRouter.connectAudio()` which might interact with the old releasing TTS.

---

## Permissions Handling

**HudActivity starts Bluetooth client before permission grant.** `glasses/src/main/java/.../HudActivity.kt` lines 279-283: `btClient.start()` is called regardless of whether the permission request succeeded or not, with the comment "on Rokid glasses standard permissions may not apply or dialogs may not show". On non-Rokid devices running Android 12+, this will crash with `SecurityException` when attempting Bluetooth operations.

**No ACCESS_BACKGROUND_LOCATION request on Android 10+.** `phone/src/main/java/.../MainActivity.kt` -- the app requests `ACCESS_FINE_LOCATION` but never requests `ACCESS_BACKGROUND_LOCATION`. On Android 10+, if the app goes into the background (screen off), location updates may stop unless background location is granted. The WakeLock keeps the CPU running, but location access is a separate permission.

**FORMER_SDK < 31 doesn't request BLUETOOTH_CONNECT proper.** `phone/src/main/java/.../HudStreamingService.kt` line 407: `@SuppressLint("MissingPermission")` on `startBluetoothServer()` suppresses the lint warning but does not check or request the `BLUETOOTH_CONNECT` permission on SDK 31+. The SPP server listen call (line 438) requires `BLUETOOTH_CONNECT` on Android 12+.

---

## Security Concerns

**Wi-Fi credentials transmitted in plaintext over Bluetooth SPP.** `phone/src/main/java/.../HudStreamingService.kt` line 192-196: `sendWifiCreds()` sends the SSID and passphrase as a JSON string over unencrypted Bluetooth RFCOMM. Any device within Bluetooth range (~10m) could pair and sniff the SPP traffic. While SPP pairing provides some authentication, the data channel itself is not encrypted. On open Bluetooth environments, this is a risk.

**APK transferred in plaintext without verification.** `phone/src/main/java/.../HudStreamingService.kt` lines 201-251 -- APK files are sent over Bluetooth SPP in Base64-encoded chunks. The receiving end (`glasses/src/main/java/.../BluetoothClient.kt` lines 244-256) writes the file to `context.cacheDir/glasses_update.apk` and immediately passes it to the Android package installer. There is no signature verification, checksum validation, or source authentication. A man-in-the-middle on Bluetooth could inject a malicious APK.

**Reflection used to access hidden APIs.** Multiple reflection-based calls bypass Android API restrictions:
- `glasses/src/main/java/.../WifiConnector.kt` lines 176-181: `IWifiManager.setWifiEnabled()` via reflection
- `phone/src/main/java/.../BluetoothAudioRouter.kt` lines 222-224: `BluetoothA2dp.connect()` via reflection
- `glasses/src/main/java/.../WifiConnector.kt` lines 129-131: `BluetoothDevice.createRfcommSocket(int channel)` via reflection

These may break with future Android versions and are not guarded by try-catch in some cases.

**No certificate pinning for HTTPS.** None of the HTTP clients implement certificate pinning. While the endpoints (OSRM, Nominatim, Overpass, CartoDB) are public services, a compromised CA or network-level attack could intercept traffic.

---

## Scalability Concerns

**nearestRouteDistance O(n) per second.** `phone/src/main/java/.../NavigationManager.kt` line 144-147: `routeWaypoints.minOf { wp -> haversineM(...) }` iterates all waypoints (up to ~500) on every location update (1 Hz). For each waypoint, it computes a haversine distance. This is called on the location callback thread and could contribute to CPU wake time on long trips. A spatial index or distance-threshold early exit would be more efficient.

**Disk tile cache walkTopDown for every size check.** `shared/src/main/java/.../DiskTileCache.kt` -- `sizeBytes()` (line 61) and `evictIfNeeded()` (line 77) both call `cacheDir.walkTopDown().filter { it.isFile }` which lists all files and file stats. On a 500MB cache with 10,000+ small tile files, this creates significant I/O. `sizeBytes()` is called from the UI thread via `updateCacheSizeText()`.

**Multiple fallback tile fetches per tile.** `glasses/src/main/java/.../TileManager.kt` lines 93-124 -- if the proxy is unavailable, `fetchTile` tries 3 tile URLs in sequence. Each failed attempt has an 8-second timeout. If all 3 fail, the user waits up to 24 seconds before the tile slot is abandoned. For zones with many tiles visible (MAP_ZOOM=16, ~20 tiles on screen), this means up to 60 sequential HTTP connections and 480 seconds of potential waiting.

**Thread-per-connection BT reader pattern.** `phone/src/main/java/.../HudStreamingService.kt` line 405 -- each client spawns a raw `Thread`. With 2 clients (secure + insecure socket), plus the accept loop threads, plus 4 tile executor threads, plus the location update callback thread, the service uses at least 8+ threads. On Android, this is acceptable but not optimal.

---

## Prioritized Action Items

1. **[HIGH] Fix data race in NavigationManager.** `phone/src/main/java/.../NavigationManager.kt` -- `steps` and `currentStepIndex` are accessed from multiple threads without synchronization. Make them `@Volatile` or use `AtomicInteger`/`AtomicReference`.

2. **[HIGH] Add LRU cache byte-based sizing.** `glasses/src/main/java/.../TileManager.kt` -- change `sizeOf` to return `value.byteCount` instead of `1`. With the current entry-count-based limit, 200 large bitmaps (~50MB) on memory-constrained glasses hardware can cause OOM.

3. **[HIGH] Fix lastLat/lastLng visibility.** `phone/src/main/java/.../HudStreamingService.kt` lines 71-72 -- add `@Volatile` to `lastLat` and `lastLng` to guarantee cross-thread visibility.

4. **[MEDIUM] Extract shared constants to shared module.** Move `TILE_URLS`, `USER_AGENT`, `SPP_UUID`, `haversineM`, and `formatDistance` into the `shared` module to eliminate duplication across `phone` and `glasses`.

5. **[MEDIUM] Add Nominatim rate limiting.** `phone/src/main/java/.../NominatimClient.kt` -- enforce 1 request/second minimum interval to comply with Nominatim usage policy.

6. **[MEDIUM] Add socket connect timeout for Bluetooth.** `glasses/src/main/java/.../BluetoothClient.kt` -- set a connect timeout of ~10 seconds on `BluetoothSocket.connect()` to prevent the reconnection loop from blocking indefinitely.

7. **[MEDIUM] Add reconnection backoff for Bluetooth.** `glasses/src/main/java/.../BluetoothClient.kt` -- implement exponential backoff (3s, 6s, 12s, max 30s) and a maximum retry limit to avoid burning CPU when phone is out of range.

8. **[MEDIUM] Handle protocol versioning.** Add a version field to the protocol messages so the phone and glasses can negotiate compatibility, preventing silent data corruption from protocol drift.

9. **[MEDIUM] Request ACCESS_BACKGROUND_LOCATION.** `phone/src/main/java/.../MainActivity.kt` -- add background location permission request for Android 10+ to ensure GPS works when screen is off.

10. **[LOW] Add HTTP 429 handling to HTTP clients.** Check response codes in `OsrmClient`, `NominatimClient`, and `OverpassSpeedLimitClient` for rate-limit responses (429) and implement backoff.

11. **[LOW] Add APK transfer resume capability.** Track chunk acknowledgments so interrupted APK transfers can resume from the last confirmed chunk rather than restarting.

12. **[LOW] Replace `catch (_: Exception) {}` with logged warnings.** Audit all empty catch blocks and add logging for unexpected errors, particularly in `BluetoothClient.kt`, `WifiConnector.kt`, and `RokidConnectionManager.kt`.

13. **[LOW] Add GPS-loss timeout detection.** Monitor timestamp of last location update and notify user/switch to offline mode if GPS is lost for more than 30 seconds.

14. **[LOW] Add request deduplication for notification forwarding.** `glasses/src/main/java/.../HudState.kt` -- deduplicate notification items from the same packageName to prevent a single app from filling all 8 notification slots.

---

*Concerns audit: 2026-07-02*
