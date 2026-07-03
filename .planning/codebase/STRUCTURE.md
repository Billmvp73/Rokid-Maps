# Codebase Structure

**Analysis Date:** 2026-07-02

## Directory Tree

```
rokid-maps/
├── build.gradle.kts                    # Root build config: AGP 8.7.3, Kotlin 2.1.0
├── settings.gradle.kts                 # Module definitions: :shared, :phone, :glasses
├── gradle.properties                   # JVM args, AndroidX, Kotlin style
├── local.properties.template           # Template for SDK path + Rokid credentials
├── gradlew.bat                         # Gradle wrapper (Windows)
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── .gitignore
├── README.md
├── screenshots/
│   ├── phone/                          # Phone app screenshots
│   └── glasses/                        # Glasses HUD screenshots
├── .github/
│   └── FUNDING.yml                     # GitHub sponsorship config
│
├── shared/                             # shared library module
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml         # <manifest /> (library manifest)
│       └── java/com/rokid/hud/shared/
│           ├── protocol/
│           │   ├── Messages.kt         # Data classes: StateMessage, RouteMessage, etc.
│           │   ├── ProtocolConstants.kt # JSON field names + message type strings
│           │   └── ProtocolCodec.kt    # JSON encode/decode for all message types
│           └── cache/
│               └── DiskTileCache.kt    # File-system LRU tile cache
│
├── phone/                              # phone app module
│   ├── build.gradle.kts                # Credentials from local.properties via BuildConfig
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml         # Permissions, activities, services, receivers
│       ├── java/com/rokid/hud/phone/
│       │   ├── HudApplication.kt       # Application class: osmdroid init, notification channel, CXR init
│       │   ├── MainActivity.kt         # Main UI: search, nav, settings, streaming control (912 lines)
│       │   ├── HudStreamingService.kt  # Foreground service: BT SPP server, GPS, tile proxy (493 lines)
│       │   ├── NavigationManager.kt    # Route tracking: step advancement, off-route detection, rerouting
│       │   ├── OsrmClient.kt           # OSRM HTTP client for route calculation
│       │   ├── NominatimClient.kt      # Nominatim search API client
│       │   ├── OverpassSpeedLimitClient.kt # Overpass API client for speed limits
│       │   ├── DeviceScanActivity.kt   # BLE + classic BT device scanner
│       │   ├── RokidConnectionManager.kt # Rokid CXR SDK wrapper for glasses pairing
│       │   ├── RokidSdkHelper.kt       # Stub for optional Rokid Mobile SDK
│       │   ├── BluetoothAudioRouter.kt # A2DP/SCO audio routing + TTS for voice directions
│       │   ├── WifiShareManager.kt     # Wi-Fi Direct P2P group owner management
│       │   ├── SavedPlacesManager.kt   # Saved places persistence (SharedPreferences JSON)
│       │   └── HudNotificationListenerService.kt # NotificationListenerService for forwarding
│       └── res/
│           ├── drawable/               # bg_card.xml, bg_card_accent.xml, bg_card_blue.xml, etc.
│           ├── layout/
│           │   ├── activity_main.xml   # Main phone UI layout (1005 lines)
│           │   └── item_search_result.xml
│           ├── values/
│           │   ├── colors.xml
│           │   ├── strings.xml         # app_name = "Rokid HUD Maps"
│           │   └── themes.xml          # Dark theme styles
│
└── glasses/                            # glasses app module
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml         # Permissions, activity, FileProvider
        ├── java/com/rokid/hud/glasses/
        │   ├── HudActivity.kt          # Main activity: BT, TTS, lifecycle, shutdown (318 lines)
        │   ├── HudView.kt              # Custom View: HUD rendering, 4 layout modes (610 lines)
        │   ├── HudState.kt             # Immutable state data class + MapLayoutMode enum
        │   ├── BluetoothClient.kt      # BT SPP client: connect, read, process messages
        │   ├── TileManager.kt          # Two-level tile cache (LruCache + DiskTileCache)
        │   └── WifiConnector.kt        # Auto-connect to phone hotspot (multi-strategy)
        └── res/
            ├── drawable/               # ic_launcher.xml
            ├── layout/
            │   └── activity_hud.xml    # FrameLayout wrapping custom HudView
            ├── values/
            │   ├── strings.xml         # app_name = "Rokid HUD"
            │   └── themes.xml          # Black fullscreen theme
            └── xml/
                ├── network_security_config.xml  # Cleartext permitted for tile HTTP
                └── file_paths.xml               # FileProvider paths for APK install
```

## Package Organization

### `shared/` — `com.rokid.hud.shared`

| Package | Purpose | Files |
|---------|---------|-------|
| `shared.protocol` | Bluetooth message types, JSON field constants, codec (encode/decode) | `Messages.kt`, `ProtocolConstants.kt`, `ProtocolCodec.kt` |
| `shared.cache` | Disk-based LRU map tile cache (file system) | `DiskTileCache.kt` |

### `phone/` — `com.rokid.hud.phone`

| Package | Purpose | Files |
|---------|---------|-------|
| (root) | All phone code in flat package | 14 files |

Classes are organized by function (not by sub-package):
- **Application lifecycle**: `HudApplication`
- **UI**: `MainActivity`, `DeviceScanActivity`
- **Services**: `HudStreamingService`, `HudNotificationListenerService`
- **Navigation**: `NavigationManager`, `NavigationCallback`, `NavigationStep`, `RouteResult`
- **Network clients**: `OsrmClient`, `NominatimClient`, `OverpassSpeedLimitClient`
- **Hardware/Connectivity**: `RokidConnectionManager`, `RokidSdkHelper`, `BluetoothAudioRouter`, `WifiShareManager`
- **Data**: `SavedPlacesManager`, `SavedPlace`, `SearchResult`

### `glasses/` — `com.rokid.hud.glasses`

| Package | Purpose | Files |
|---------|---------|-------|
| (root) | All glasses code in flat package | 6 files |

- **Lifecycle UI**: `HudActivity`
- **Rendering**: `HudView` (custom `View`), `HudState`, `MapLayoutMode`, `NotificationItem`
- **Connectivity**: `BluetoothClient`, `WifiConnector`
- **Caching**: `TileManager`

## Key Source Files

| File | Module | Lines | Purpose |
|------|--------|-------|---------|
| `phone/MainActivity.kt` | phone | 912 | Main UI: search, navigation panel, settings, glass pairing, APK push, WiFi sharing |
| `phone/HudStreamingService.kt` | phone | 493 | Foreground service: BT SPP server, GPS streaming, tile proxy, APK chunk send |
| `phone/DeviceScanActivity.kt` | phone | 457 | BLE scan (Rokid UUID) + classic BT discovery with full programmatic UI |
| `phone/BluetoothAudioRouter.kt` | phone | 277 | A2DP/SCO audio routing + TTS voice guidance |
| `phone/WifiShareManager.kt` | phone | 207 | Wi-Fi Direct P2P group creation for phone-as-hotspot |
| `phone/NavigationManager.kt` | phone | 157 | Step tracking, off-route detection (80m), rerouting, arrival (30m) |
| `phone/RokidConnectionManager.kt` | phone | 148 | Rokid CXR SDK BLE pairing -> classic BT connection wrapper |
| `phone/OsrmClient.kt` | phone | 132 | OSRM route parsing (GeoJSON -> Waypoint + NavigationStep) |
| `phone/OverpassSpeedLimitClient.kt` | phone | 99 | Overpass API speed limit queries with caching |
| `phone/HudApplication.kt` | phone | 55 | Application init: osmdroid, notification channel, CXR SDK |
| `phone/SavedPlacesManager.kt` | phone | 65 | SharedPreferences JSON persistence for saved places |
| `phone/HudNotificationListenerService.kt` | phone | 63 | NotificationListenerService forwarding to streaming service |
| `phone/NominatimClient.kt` | phone | 42 | HTTP client for OSM Nominatim search |
| `phone/RokidSdkHelper.kt` | phone | 45 | Stub wrapper for optional Rokid Mobile SDK |
| `glasses/HudView.kt` | glasses | 610 | Custom View: all HUD rendering (tiles, route, directions, status bar, 4 layouts, turn alert) |
| `glasses/WifiConnector.kt` | glasses | 332 | Multi-strategy Wi-Fi auto-connect (specifier, legacy, suggestion) |
| `glasses/HudActivity.kt` | glasses | 318 | Main activity: BT lifecycle, TTS, shutdown logic, fullscreen |
| `glasses/BluetoothClient.kt` | glasses | 278 | BT SPP client: connect loop, message dispatch, APK receive |
| `glasses/TileManager.kt` | glasses | 130 | LRU cache (200 entries) + DiskTileCache + proxy/direct fetch |
| `glasses/HudState.kt` | glasses | 71 | Immutable state data class with copy(), toggleLayout(), withNotification() |
| `shared/ProtocolCodec.kt` | shared | 245 | JSON encode/decode for all 11 message types |
| `shared/DiskTileCache.kt` | shared | 96 | File-system LRU cache with eviction, max size config |
| `shared/Messages.kt` | shared | 73 | 12 data classes for all message payloads |
| `shared/ProtocolConstants.kt` | shared | 60 | 44 JSON field name constants + 11 message type constants |

**Total: ~5,365 lines of Kotlin across 24 source files.**

## Resource Files

### Phone App Resources

| Resource | Path | Purpose |
|----------|------|---------|
| `activity_main.xml` | `phone/.../res/layout/` | Main scrollable layout (1005 lines): header, search, nav panel, settings section |
| `item_search_result.xml` | `phone/.../res/layout/` | Single search result row layout |
| `themes.xml` | `phone/.../res/values/` | Dark theme `Theme.RokidHud` with green accents + reusable styles (CardSection, SectionTitle, ToggleRow, PrimaryButton, SecondaryButton) |
| `colors.xml` | `phone/.../res/values/` | Primary green color definitions |
| `strings.xml` | `phone/.../res/values/` | App name string only |
| `bg_card.xml` | `phone/.../res/drawable/` | Dark card background drawable |
| `bg_card_accent.xml` | `phone/.../res/drawable/` | Green accent card background |
| `bg_card_blue.xml` | `phone/.../res/drawable/` | Blue accent card background (WiFi info) |
| `bg_card_red.xml` | `phone/.../res/drawable/` | Red accent card background |
| `bg_search.xml` | `phone/.../res/drawable/` | Search bar background |
| `bg_status_dot_connected.xml` | `phone/.../res/drawable/` | Green dot (connected) |
| `bg_status_dot_disconnected.xml` | `phone/.../res/drawable/` | Gray dot (disconnected) |
| `ic_launcher.xml` | `phone/.../res/drawable/` | App launcher icon |
| `ic_launcher_foreground.xml` | `phone/.../res/drawable/` | Launcher foreground |
| `ic_launcher_simple.xml` | `phone/.../res/drawable/` | Simplified launcher icon |

### Glasses App Resources

| Resource | Path | Purpose |
|----------|------|---------|
| `activity_hud.xml` | `glasses/.../res/layout/` | FrameLayout wrapping `HudView` (12 lines) |
| `themes.xml` | `glasses/.../res/values/` | Black fullscreen theme (`Theme.RokidGlasses.Fullscreen`) |
| `strings.xml` | `glasses/.../res/values/` | App name string only |
| `network_security_config.xml` | `glasses/.../res/xml/` | Allows cleartext HTTP (for map tile fetching) |
| `file_paths.xml` | `glasses/.../res/xml/` | FileProvider cache path for APK install |
| `ic_launcher.xml` | `glasses/.../res/drawable/` | App launcher icon |
| `ic_launcher_glasses.xml` | `glasses/.../res/drawable/` | Alternative glasses app icon |

## Build Configuration

### Root: `build.gradle.kts`
```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}
```

### Root: `settings.gradle.kts`
```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
        google()
        mavenCentral()
    }
}
rootProject.name = "RokidHudMaps"
include(":shared", ":phone", ":glasses")
```

- Rokid Maven repository added for `com.rokid.cxr:client-m:1.0.4` SDK

### Module build files

| Module | Type | Notable Dependencies |
|--------|------|---------------------|
| `shared/` | android library | `org.json:json:20231013` |
| `phone/` | android application | `:shared`, Google Play Services Location, osmdroid, Rokid CXR-M SDK, Retrofit/Gson/OkHttp |
| `glasses/` | android application | `:shared`, AndroidX core/appcompat only |

### `gradle.properties`
```
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

### `local.properties.template`
- Template for `sdk.dir`, `rokid.client.id`, `rokid.client.secret`, `rokid.access.key`
- Rokid credentials built into `BuildConfig` via `localProperties` in `phone/build.gradle.kts`

## Complexity Hotspots

| File | Lines | Complexity Risk |
|------|-------|----------------|
| `phone/MainActivity.kt` | 912 | Monolithic activity with all UI logic, settings, search, navigation panels, permissions, callbacks. Heavy use of `lateinit` for dozens of view bindings. |
| `phone/HudStreamingService.kt` | 493 | Single class handling BT server, GPS streaming, tile proxy, APK push, client management. Mixes concerns of protocol, transport, and business logic. |
| `glasses/HudView.kt` | 610 | All rendering in one file: tile map math (Web Mercator projection), 4 layout modes, compass, route drawing, turn alert overlay, status bar, text truncation. |
| `phone/DeviceScanActivity.kt` | 457 | Programmatically-built UI (no XML) mixed with BLE scanning, classic BT discovery, pairing, and persistence. |
| `glasses/WifiConnector.kt` | 332 | Three connection strategies + Wi-Fi radio management through multiple Android APIs, reflection fallback, and polling loops. |
| `shared/ProtocolCodec.kt` | 245 | JSON encode/decode for 11 message types. Maintainable but the decode `when` block is long. |

## Where to Add New Code

### New Feature (Phone side)
- New network client: Add to `phone/src/main/java/com/rokid/hud/phone/`
- New UI component: Add layout XML to `phone/src/main/res/layout/`, wire in `phone/.../MainActivity.kt`
- New service: Define in `phone/.../AndroidManifest.xml`, implement in `phone/.../`

### New Feature (Glasses side)
- New HUD element: Add drawing code to `glasses/.../HudView.kt`, add state field to `glasses/.../HudState.kt`
- New connectivity: Add class to `glasses/src/main/java/com/rokid/hud/glasses/`

### New Message Type
1. Add data class to `shared/.../Messages.kt`
2. Add field constants to `shared/.../ProtocolConstants.kt`
3. Add encode/decode to `shared/.../ProtocolCodec.kt`
4. Add `ParsedMessage` subclass to `ProtocolCodec.kt`
5. Handle in `BluetoothClient.processMessage()` (glasses)
6. Create/send in `HudStreamingService` or `NavigationManager` (phone)

### New Settings Toggle
1. Add UI toggle to `phone/.../res/layout/activity_main.xml`
2. Wire `setOnCheckedChangeListener` + persistence in `phone/.../MainActivity.kt`
3. Add field to `SettingsMessage` in `shared/.../Messages.kt`
4. Handle in `BluetoothClient.processMessage()` when `ParsedMessage.Settings` received
5. Add to `HudState` data class
6. Render in `HudView` as needed

### Testing (not yet present)
- No test directory or test files exist in any module
- Tests should go in `shared/src/test/`, `phone/src/test/`, `glasses/src/test/`

---

*Structure analysis: 2026-07-02*
