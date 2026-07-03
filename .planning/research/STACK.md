# Stack Research

**Domain:** Strava OAuth 2.0 + REST API integration + activity recording/upload for Android Kotlin app
**Researched:** 2026-07-02
**Confidence:** HIGH

## Recommended Stack

### Core Technologies

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| OkHttp | 4.12.0 | HTTP client for Strava REST API calls | Already a transitive dependency (via Rokid CXR-M SDK 1.0.4); interceptor-based auth token injection is the cleanest pattern for OAuth bearer tokens; built-in multipart body support for GPX upload; explicit declaration avoids version conflicts with CXR SDK |
| Gson | 2.10.1 | JSON parsing for Strava API responses | Already a transitive dependency (via CXR SDK); simple, battle-tested, no new JSON library needed; Strava API responses are straightforward JSON that Gson maps directly to Kotlin data classes; does not require kotlinx.serialization compiler plugin setup |
| EncryptedSharedPreferences (Jetpack Security) | 1.1.0 | Secure storage for OAuth access/refresh tokens | AES256-GCM encryption backed by Android Keystore; stores access_token, refresh_token, expires_at, and athlete metadata; deprecated but stable (final release, no further versions), works correctly on API 28+ |
| Chrome Custom Tabs | AndroidX Browser 1.8.x | Strava OAuth authorization UI | RFC 8252 best practice for mobile OAuth: uses system browser (not WebView) so Strava credentials are never visible to the app; no extra library needed beyond `androidx.browser:browser` which is likely already present or very lightweight |
| Android XmlPullParser / XmlSerializer | Built-in (Android SDK) | GPX parsing (import) and generation (upload) | GPX 1.1 is a trivial XML format (trk > trkseg > trkpt[lat, lon, ele, time]); the only GPX-specific Kotlin library (kotlin-yellowduck-gpx) was archived by its owner Dec 2025 and is not maintained; XmlPullParser parses without loading whole document into memory, critical for large GPX files |
| FusedLocationProvider | play-services-location 21.1.0 | GPS data source for activity recording | Already implemented at 1Hz in HudStreamingService; activity recording is a consumer of the same GPS pipeline, not a new GPS system; records lat/lng/altitude/bearing/speed/timestamp per point |
| Strava API v3 (REST) | N/A | Routes listing, GPX export, activity upload | No SDK or client library needed; Strava has no official Android/Kotlin SDK; the v3 API has only 6 relevant endpoints, all REST over HTTPS; better to write a focused client than wrap an unmaintained third-party SDK |

### Supporting Libraries

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `com.squareup.okhttp3:okhttp` | 4.12.0 | HTTP client (explicit declaration) | Always -- declare explicitly in phone module build.gradle.kts even though it's transitive, to pin the version |
| `com.squareup.okhttp3:logging-interceptor` | 4.12.0 | Debug logging for Strava API calls | During development only; wrap with `if (BuildConfig.DEBUG)` |
| `com.google.code.gson:gson` | 2.10.1 | JSON deserialization (explicit declaration) | Always -- declare explicitly to control version and make the dependency visible |
| `androidx.security:security-crypto` | 1.1.0 | OAuth token encryption | Always; this is the final stable release (deprecated but functional); stores access_token, refresh_token, expires_at |
| `androidx.browser:browser` | 1.8.x | Chrome Custom Tab for OAuth redirect | Required if not already declared; lightweight library for opening Strava's OAuth consent page |
| `com.google.android.gms:play-services-location` | 21.1.0 | FusedLocationProvider (already present) | Already declared; no version change needed; activity recording reads from the existing location subscription |

### Development Tools

| Tool | Purpose | Notes |
|------|---------|-------|
| Strava API Swagger spec | API contract reference | Available at `https://developers.strava.com/swagger/swagger.json`; generate data classes from the spec to ensure correctness |
| Chrome DevTools / strava.com/oauth/authorize | OAuth flow debugging | Test the authorization URL parameters manually in a browser first to verify scopes and redirect URI |
| GPX validator | Validate generated GPX before upload | Use `https://www.gpsvisualizer.com/gpx_validator` or `xmllint --schema gpx.xsd` during development |

## Installation

Add these to the `phone/` module's `build.gradle.kts`:

```kotlin
// Explicit declarations (already transitive, pinning for version control)
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
implementation("com.google.code.gson:gson:2.10.1")

// Token storage (final stable version, deprecated but functional)
implementation("androidx.security:security-crypto:1.1.0")

// Custom Tabs for OAuth (if not already present)
implementation("androidx.browser:browser:1.8.0")

// GPS (already present, no change)
// implementation("com.google.android.gms:play-services-location:21.1.0")
```

**No new dependencies for:**
- GPX parsing -- uses `android.util.XmlPullParser` (built into Android SDK)
- GPX generation -- uses `android.util.XmlSerializer` (built into Android SDK)
- OAuth authorization -- uses Chrome Custom Tabs + manual redirect handling (no AppAuth library)
- Activity recording -- uses existing FusedLocationProvider subscription
- OAuth redirect -- intent filter on a custom scheme URI in AndroidManifest.xml

**Zero new library dependencies beyond what's listed above.**

## Alternatives Considered

| Recommended | Alternative | When to Use Alternative |
|-------------|-------------|-------------------------|
| OkHttp 4.12.0 (direct usage) | Retrofit 2.11.0 + OkHttp | If you want type-safe API interfaces and can adopt coroutines (Retrofit 3.0.0's suspend functions). Restrofit adds build-time annotation processing and another abstraction layer. For 6 endpoints it is unnecessary complexity. |
| OkHttp 4.12.0 | OkHttp 5.0.0 | When no CXR SDK dependency pins OkHttp 4.x. The CXR SDK transitively depends on OkHttp 4.12.0. Using OkHttp 5.0.0 would require verifying CXR compatibility; 4.12.0 is already present and working. |
| Gson 2.10.1 | kotlinx.serialization 1.8.0 | When starting a new Kotlin project from scratch (no existing dependencies). kotlinx.serialization is reflection-free and Kotlin-first, but requires the serialization compiler plugin and version-matching with Kotlin 2.1.x. For adding to an existing project where Gson is already a transitive dep, Gson is simpler. |
| EncryptedSharedPreferences (security-crypto 1.1.0) | DataStore + Tink | When building a new app from scratch. DataStore + Tink is the official non-deprecated path, but requires more setup (serializers, coroutines). For a single use case (3-4 token values), EncryptedSharedPreferences is simpler and the deprecation only means no future releases, not removal. |
| Manual GPX parsing (XmlPullParser) | kotlin-yellowduck-gpx 1.0.2 | If you need full GPX 1.1 schema validation or write support beyond track points. Library was archived Dec 2025 by its owner, so no future maintenance. For Strava route GPX (simple waypoints list), manual parsing is 30 lines of code. |
| Manual OAuth (Chrome Custom Tabs) | AppAuth-Android 0.11.1 | When integrating with multiple OAuth providers (Google, Facebook, Strava, etc.) or when OpenID Connect discovery is needed. AppAuth is the gold standard for generic OAuth but adds ~300KB and significant configuration. For a single provider (Strava) with known, static endpoints, manual flow is simpler and more transparent. |

## What NOT to Use

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| `kotlin-yellowduck-gpx` | Repo was archived (read-only) by its owner on Dec 2, 2025. No longer maintained. JitPack-only distribution. Minimal community (3 stars, 1 fork). | Android `XmlPullParser` (built-in) for GPX parsing |
| `AppAuth-Android` | Overkill for a single OAuth provider. Requires registering an intent filter for the redirect scheme, managing `AuthorizationService` lifecycle, and handling `PendingIntent` callbacks. Adds ~300KB to APK. | Chrome Custom Tab + manual `code` extraction + OkHttp `POST` for token exchange |
| Retrofit 3.0.0 | Requires coroutines (suspend functions), which the codebase does not use anywhere. Adopting coroutines for a single feature would create an awkward pattern mismatch across the project. The existing Executor-based threading works fine. | OkHttp 4.12.0 directly, with `Callback` + existing `ExecutorService` patterns |
| kotlinx.serialization | Requires Gradle compiler plugin (`org.jetbrains.kotlin.plugin.serialization`) that must match the Kotlin version (2.1.0). New build complexity for a single feature. Gson already transitive. | Gson 2.10.1 (already in the classpath) |
| HttpURLConnection (for Strava) | Used by existing clients (OSRM, Nominatim, Overpass) but lacks interceptor support for auth token injection, multipart body for file upload, and connection pooling for multiple concurrent requests. OkHttp does all three. | OkHttp 4.12.0 |
| `scribejava` / `strava-java-client` | Third-party Strava SDK wrappers are not officially maintained. The Strava API is simple enough (6 endpoints) that a library adds abstraction cost without real benefit. When they break (API changes), you're stuck. | Write a focused `StravaClient` class using OkHttp + Gson |

## Stack Patterns by Variant

**If you later adopt coroutines project-wide:**
- Keep OkHttp as-is (it has native coroutine suspending extension functions via `kotlinx.coroutines`)
- Replace Gson with `kotlinx.serialization` for type-safe Kotlin data classes
- Do NOT add Retrofit for Strava specifically -- OkHttp's `suspend fun` extension is sufficient

**If you later add BLE sensor support (HR monitor, cadence, power meter):**
- Add `kotlinx-coroutines-play-services` for bridging FusedLocationProvider callbacks to coroutines
- Add Room database for recording multi-sensor time series (GPS + HR + cadence) for structured offline storage
- Keep OkHttp for the network layer (Strava upload remains the same)

**If you need to support uploading to multiple platforms (Strava, Komoot, RideWithGPS):**
- Extract the upload logic into a common interface: `ActivityUploader { fun upload(gpx: File, auth: AuthToken): Result<UploadStatus> }`
- Keep OkHttp as the shared HTTP layer; each platform gets its own implementation class
- The GPX generation is shared (same XmlSerializer code)

## Version Compatibility

| Package | Compatible With | Notes |
|---------|-----------------|-------|
| OkHttp 4.12.0 | Rokid CXR-M SDK 1.0.4 | CXR SDK 1.0.4 transitively depends on OkHttp 4.12.0 and OkHttp 3.14; 4.12.0 is the resolved version and is confirmed working |
| Gson 2.10.1 | CXR SDK (transitive), Retrofit (skipping) | Gson 2.10.1 is transitively pulled by CXR SDK; no conflict with any other dep |
| security-crypto 1.1.0 | Targeted API 34, min SDK 28 | Works on API 23+; minSdk 28 is well above the floor. On Android 10+ (API 29), file-based encryption provides additional OS-level protection |
| play-services-location 21.1.0 | minSdk 28 | Already resolved and working; no update needed for activity recording |
| GPX format (Strava export) | GPX 1.1 | Strava exports routes as GPX 1.1 with `trk > trkseg > trkpt[lat, lon, ele, time]`. The Strava route GPX does NOT include extensions (no heart rate, no cadence). Simple parsing. |
| Android minSdk 28 | All listed libraries | All libraries support API 28+. Chrome Custom Tabs requires API 16+. |

## Architecture Impact Summary

Adding Strava integration introduces:

- **1 new class** for OAuth flow management (`StravaAuthManager`)
- **1 new class** for API calls (`StravaApiClient` uses OkHttp, Gson)
- **1 new class** for activity recording (`ActivityRecorder` reads from FusedLocationProvider)
- **1 new class** for GPX operations (`GpxImporter` / `GpxExporter`)
- **1 new data class file** for Strava API models (`StravaRoute`, `StravaUpload`, `StravaToken`, etc.)
- **1 new service or manager** to orchestrate the above (`StravaManager`)

All Strava code lives under `com.rokid.hud.phone.strava.*` package in the existing `phone/` module. No new Gradle modules, no build system changes, no architectural pattern shifts.

## Sources

- [Strava OAuth Documentation](https://developers.strava.com/docs/authentication/) -- verified OAuth flow, token refresh, scopes (MEDIUM: official, confirms 6-hour expiry, refresh rotation)
- [Strava Upload Documentation](https://developers.strava.com/docs/uploads/) -- verified upload endpoint, multipart form parameters, async processing, GPX/FIT/TCX support (MEDIUM: official)
- [Strava API Overview](https://developers.strava.com/docs/) -- confirmed routes listing and GPX export endpoints exist (LOW: overview page, no endpoint detail)
- [OpenID AppAuth-Android](https://github.com/openid/AppAuth-Android) -- verified it's the standard OAuth library but decided against it (MEDIUM: official repo)
- [Retrofit 3.0.0 Migration](https://proandroiddev.com/retrofit-3-0-0-detailed-migration-guide-0d2c043d43e3) -- confirmed Retrofit 3.0.0 features (suspend functions, kotlinx.serialization converter) but chose OkHttp direct (MEDIUM: article by Square engineer)
- [OkHttp 4.12 vs 5.0](https://square.github.io/okhttp/) -- confirmed 4.12.0 is last 4.x, 5.0.0 is current but not compatible with CXR SDK's transitive deps (MEDIUM: official)
- [kotlinx.serialization 1.8.0 for Kotlin 2.1](https://github.com/Kotlin/kotlinx.serialization/releases) -- confirmed 1.8.0+ required for Kotlin 2.1.0 compatibility (MEDIUM: official changelog)
- [AndroidX Security Crypto deprecated](https://developer.android.com/reference/kotlin/androidx/security/crypto/package-summary) -- confirmed deprecation of EncryptedSharedPreferences, final version is 1.1.0 (MEDIUM: official Android docs)
- [kotlin-yellowduck-gpx archived](https://github.com/pieterclaerhout/kotlin-yellowduck-gpx) -- confirmed repo archived Dec 2025 (HIGH: visible on GitHub)
- [GPX 1.1 Schema](http://www.topografix.com/GPX/1/1/gpx.xsd) -- confirmed GPX 1.1 structure for track points (MEDIUM: official XSD)

---
*Stack research for: Strava integration into Rokid HUD Maps Android app*
*Researched: 2026-07-02*
