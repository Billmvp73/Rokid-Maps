# Code Conventions & Patterns

**Analysis Date:** 2026-07-02

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
  - General code has few comments. Section dividers use comment art: `// ── Section name ──`.
  - KDoc (`/** */`) used sparingly: only on `RokidConnectionManager`, `WifiShareManager`, and `RokidSdkHelper` with class-level description.
  - No inline KDocs on methods or properties outside those files.
  - `// TODO:` found once in `RokidSdkHelper.kt:38` for placeholder SDK init.
- **Trailing commas:** Not used consistently.
- **String formatting:** `String.format()` for numeric formatting; string templates with `$` for simple interpolation.
- **Null handling:** Kotlin `?.` safe-call and `?:` elvis operator used extensively. `!!` rarely used (only in `service!!` in `MainActivity.kt`).

## Architecture Patterns

- **Module-level (multi-module Android):**
  - `:shared` — pure library module (no Android framework dependencies in logic, but uses `android.content.Context` and `android.util.Log` in `DiskTileCache`).
  - `:phone` — application module, hybrid Android Service + Activity.
  - `:glasses` — application module, single Activity + custom View.
- **No architectural framework:** No MVVM, MVP, MVI, or Clean Architecture. No Jetpack ViewModel, LiveData, or Compose.
- **No dependency injection:** No Hilt/Dagger/Koin. Dependencies wired manually in `onCreate()`.
- **Data flow:**
  - Phone: HTTP clients (NominatimClient, OsrmClient) are `object` singletons called from `Thread` blocks.
  - Phone: `HudStreamingService` owns `NavigationManager`, `OverpassSpeedLimitClient`, and `DiskTileCache` — state is held in service fields and streamed via Bluetooth.
  - Glasses: `BluetoothClient` receives JSON lines and builds `HudState` via `copy()` calls, then pushes via `onStateUpdate` callback.
  - `HudView` (custom View) renders directly from `HudState` — no ViewModel layer.
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
  - `Log.e()` — errors that impact functionality (connection failures, parse errors, SDK failures).
  - `Log.w()` — warnings, retryable failures, unexpected states.
  - `Log.i()` — lifecycle events (connected, started, scanning, group created).
  - `Log.d()` — verbose debug (A2DP state changes, bonded devices).
- **No structured logging** (no SLF4J, Timber, or external logging library).
- **No Kotlin extension functions** for logging.
- **Format:** `"Context description: ${value}"` or `"Operation failed: ${e.message}"`.

## Threading & Async

- **No coroutines** used anywhere in the codebase. All async is thread-based.
- **`Thread { ... }.start()` pattern:** Used for long-running I/O: `BluetoothClient.connectLoop()`, `NavigationManager.calculateRoute()`, `WifiConnector.connect()`, `HudStreamingService.sendApkToGlasses()`, `runClientReader()`.
- **`Executors.newSingleThreadExecutor()`:** Used in `DiskTileCache` (serial disk writes), `OverpassSpeedLimitClient` (serial Overpass queries).
- **`Executors.newFixedThreadPool(4)`:** Used in `HudStreamingService` (tile fetching), `TileManager` (tile loading).
- **Main thread posting:**
  - `Handler(Looper.getMainLooper())` — used in services, managers, and BluetoothClient.
  - `runOnUiThread { }` — used in `HudActivity` and `MainActivity`.
  - `View.postInvalidate()` — for triggering canvas redraw from background threads.
- **Thread safety:** `@Volatile` on `running`, `connected`, `currentState`, `cachedSpeedLimitKmh`, `querying`, `lastQuery*` fields. `CopyOnWriteArrayList` for client lists. `ConcurrentHashMap` for pending tile requests.
- **Bluetooth I/O threading:**
  - Phone: Accept thread per server socket, reader thread per client, tile executor pool.
  - Glasses: Single connection thread with blocking read loop.

## JSON Conventions

- **Library:** `org.json:json:20231013` (Android's built-in `org.json.JSONObject`, `JSONArray`, `JSONArray`).
- **No Gson/Moshi/Kotlinx.serialization:** All JSON is manually constructed/parsed.
- **Encoding pattern:** `JSONObject().apply { put(key, value) }.toString()`
- **Decoding pattern:** `JSONObject(line)`, then `json.getXxx(key)` / `json.optXxx(key, default)`.
- **Field naming:** Short abbreviations defined in `ProtocolConstants` as `const val` strings:
  - `"t"` for type, `"lat"` for latitude, `"lng"` for longitude, `"spdLim"` for speed limit, `"curIdx"` for current index, `"distNext"` for distance to next step.
  - Exceptions use full camelCase: `"waypoints"`, `"instruction"`, `"maneuver"`.
- **Nullable fields:** Encoded as empty string, decoded with `optString(key, null).takeIf { it?.isNotEmpty() == true }`.
- **Base64 encoding:** `Base64.getEncoder().encodeToString(bytes)` / `Base64.decode(string, Base64.DEFAULT)` for binary data (tiles, APK chunks).

## Bluetooth Message Conventions

- **Transport:** Bluetooth SPP (RFCOMM) over serial port profile UUID `00001101-0000-1000-8000-00805F9B34FB`.
- **Format:** Newline-delimited JSON. One message per line. `BufferedWriter.write()` + `newLine()` + `flush()`.
- **Message type field:** Always `"t"` as first-level key. Types defined in `ProtocolConstants.MessageType` object.
- **Versioning:** No protocol version field. Messages are assumed compatible.
- **Message types:**
  - `"state"` — GPS position, bearing, speed, accuracy, speed limit, distance to next step.
  - `"route"` — Full route waypoints + total distance/duration.
  - `"step"` — Current navigation step instruction/maneuver/distance.
  - `"steps_list"` — Full list of remaining steps with current index.
  - `"notification"` — Phone notification forwarded to glasses.
  - `"settings"` — UI/behavior settings from phone to glasses.
  - `"wifi_creds"` — Wi-Fi credentials for glasses to connect.
  - `"tile_req"` / `"tile_resp"` — Map tile request/response (binary via Base64).
  - `"apk_start"` / `"apk_chunk"` / `"apk_end"` — OTA APK update protocol.

## UI Patterns

- **Phone app:**
  - `AppCompatActivity` with XML layout (`activity_main.xml`). No Fragments, no Compose.
  - View binding: manual `findViewById(R.id.xxx)` calls in `bindViews()`.
  - Single `ScrollView` layout with all sections: header, search, navigation, settings, status.
  - Dark theme (background `#121212`), green accent (`#00E676`).
  - Programmatic UI: `DeviceScanActivity` builds entire UI in `buildUi()` using `LinearLayout`, `TextView`, `Button` constructors.
- **Glasses app:**
  - `AppCompatActivity` with minimal XML layout (`activity_hud.xml`) — just a `FrameLayout` wrapping `HudView`.
  - `HudView` extends `View` — all rendering via `Canvas.onDraw()` with pre-allocated `Paint` objects.
  - Monochrome green color palette (five shades: bright, regular, dim, dark, faint).
  - Four layout modes: `FULL_SCREEN`, `SMALL_CORNER`, `MINI_BOTTOM`, `MINI_SPLIT`.
  - Tap single to toggle layout, double-tap to quit.
- **No Fragment usage** anywhere.
- **No ViewBinding / DataBinding** — all view references via `findViewById` or `requireViewById`.

## Resource Naming

- **Layout files:** `activity_*.xml` for Activity layouts, `item_*.xml` for list items.
  - Examples: `activity_main.xml`, `activity_hud.xml`, `item_search_result.xml`.
- **Drawable files:** `bg_*.xml` for background shapes, `ic_*.xml` for icons.
  - Examples: `bg_card.xml`, `bg_card_accent.xml`, `bg_status_dot_connected.xml`, `ic_launcher.xml`.
- **String resources:** Minimal usage. `app_name` only in each module.
  - Phone: `"Rokid HUD Maps"`
  - Glasses: `"Rokid HUD"`
- **Color resources:** `snake_case` (`primary`, `primary_dark`, `white`). Defined in `phone/src/main/res/values/colors.xml`.
- **Theme resources:** `PascalCase` with parent prefix. `Theme.RokidHud`, `Theme.RokidGlasses`, `Theme.RokidGlasses.Fullscreen`.
- **ID naming:** `camelCase` matching the view's role (`btnStart`, `searchInput`, `navInstructionText`, `switchTts`).

## Git Practices

- **Commit style:** Imperative present tense, title only (no body), typically prefixed with type:
  - Feature: `"Add speed display, speed limits, turn alerts, disk tile cache, and BMC support"`
  - Fix: `"Fix route steps list: show 4 items in scrollable container"`, `"Fix scrollable route steps, monochrome battery, persistent battery display"`
  - Docs: `"Docs: update README with all features, protocol, install; mini map toggle"`, `"README: use renamed glasses screenshots ..."`
  - Refactor: No explicit refactor prefix — folded into feature commits.
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
