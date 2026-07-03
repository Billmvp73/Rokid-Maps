# Technology Stack

**Analysis Date:** 2026-07-02

## Languages & Runtime

**Primary Language:** Kotlin 2.1.0
- All application code is written in Kotlin. No Java source files exist.
- Kotlin compiler plugin: `org.jetbrains.kotlin.android` version 2.1.0

**JVM Target:** Java 17
- All modules compile to JVM target 17 (`jvmTarget = "17"`)
- Source/target compatibility: `JavaVersion.VERSION_17`

**No coroutines or Kotlin Flow usage detected.** The project uses raw `Thread` objects, `Executors`, and `Handler` for asynchronous work throughout.

## Build System

| Component | Version | Source |
|-----------|---------|--------|
| Gradle | 8.11.1 | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 8.7.3 | `build.gradle.kts` |
| Kotlin Plugin | 2.1.0 | `build.gradle.kts` |

**Build Script Style:** Kotlin DSL (all `build.gradle.kts` files)
- Root project name: `RokidHudMaps` (defined in `settings.gradle.kts`)
- Repository: `https://maven.rokid.com/repository/maven-public/` added for Rokid SDK modules

**Key Gradle Properties** (`gradle.properties`):
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

**Package Names:**
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
| `com.squareup.retrofit2:converter-gson` | 2.9.0 | `phone` | Gson converter (transitive) |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | `phone` | OkHttp (transitive, not used directly - app uses `HttpURLConnection`) |
| `com.squareup.okhttp3:logging-interceptor` | 4.12.0 | `phone` | Logging interceptor (transitive) |
| `com.google.code.gson:gson` | 2.10.1 | `phone` | Gson (transitive, not used directly - app uses `org.json`) |

**No testing dependencies** (JUnit, Mockito, etc.) are declared in any module. There are no test source files in the project.

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

**Singleton Objects (heavy use):**
- `OsrmClient` (`phone/src/.../OsrmClient.kt`) — stateless HTTP client as a Kotlin `object`
- `NominatimClient` (`phone/src/.../NominatimClient.kt`) — stateless HTTP client as a Kotlin `object`
- `ProtocolCodec` (`shared/src/.../protocol/ProtocolCodec.kt`) — message encoding/decoding as a `object`
- `ProtocolConstants` (`shared/src/.../protocol/ProtocolConstants.kt`) — constants as a `object`
- `RokidSdkHelper` (`phone/src/.../RokidSdkHelper.kt`) — SDK bridge as a `object`

**Data Classes:** All message types (`StateMessage`, `RouteMessage`, `StepMessage`, etc.), `HudState`, `SearchResult`, `RouteResult`, `NavigationStep`, `Waypoint`, `StepInfo`, `NotificationItem`, `SavedPlace` — used extensively for immutable data transport.

**Sealed Classes:**
- `ParsedMessage` (`shared/src/.../ProtocolCodec.kt`) — sealed class hierarchy for decoded Bluetooth messages with 12 variants + `Unknown`

**Concurrency:**
- **No coroutines.** All async work uses raw `Thread()`, `Executors.newSingleThreadExecutor()`, `Executors.newFixedThreadPool(4)`, and `Handler`/`HandlerThread`
- `CopyOnWriteArrayList` for thread-safe client lists in `HudStreamingService`
- `ConcurrentHashMap` for pending tile requests in `TileManager`
- `@Volatile` annotations on shared mutable state fields
- `synchronized` blocks used in `BluetoothClient.sendTileRequest`

**Immutability Pattern:**
- `HudState` provides `copy()` and `withNotification()` methods returning a new instance
- No mutable state flows; state is replaced wholesale via `copy()`

**No dependency injection framework.** No Hilt, Dagger, Koin, or manual DI. Dependencies are created inline using `object` singletons or constructor instantiation with `applicationContext`.

**Networking:**
- All HTTP calls use `java.net.HttpURLConnection` directly (not OkHttp, despite it being a transitive dependency)
- No Retrofit usage in application code (only as transitive dependency for CXR SDK)
- JSON parsing uses `org.json.JSONObject/JSONArray` (not Gson, despite it being a transitive dependency)

**UI:** No Jetpack Compose. Standard Android View system with XML layouts, `Canvas` custom drawing (`HudView`), and `ListView`/`LinearLayout` composition programmatically in `DeviceScanActivity`.

**TTS:** Android `TextToSpeech` engine used on both phone and glasses side for spoken navigation instructions.

---

*Stack analysis: 2026-07-02*
