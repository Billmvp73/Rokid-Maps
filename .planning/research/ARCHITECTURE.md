# Architecture Research: Sport HUD / Activity Recording

**Domain:** Strava integration + activity recording for AR glasses navigation app
**Researched:** 2026-07-02
**Confidence:** HIGH

> **NOTE:** Phase numbers in this document predate the final roadmap. Mapping: Research P1(Protocol)+P2(Recording)→Roadmap Phase 1; P3(Glasses)→Phase 2; P4(Auth+Import)→Phase 3 (Auth) + Phase 4 (Import+Nav); P5(Upload)→Phase 5.

## Standard Architecture for This Domain

### Key Architectural Pattern: Session-Based Activity Recording

The industry-standard pattern for athletic activity tracking on Android is a **Foreground Service with a Session Manager**:

- A **foreground service** (with `foregroundServiceType="location"`) hosts the GPS pipeline and BT server — this already exists in the codebase as `HudStreamingService`.
- A **Session Manager** entity tracks the lifecycle of each activity: idle -> tracking -> paused -> finished (this project defers PAUSED to v2 — see Session State Machine below).
- GPS points are accumulated into a **track log** (in-memory for live metrics, persisted to disk for upload).
- Metrics (elapsed time, distance, pace/speed) are computed incrementally from the accumulated track, not from Strava's API (which has no real-time activity endpoint).

### Strava API Integration Pattern

Strava uses OAuth 2.0 with **Authorization Code Grant** and **no PKCE support**. The client secret is required for token exchange and refresh, but cannot be stored securely on a mobile device. This is a known tension in mobile-only Strava integrations:

- The common approach for Android-only apps is to embed the client secret in the APK (via BuildConfig from `local.properties`), accept the reverse-engineering risk, and use `EncryptedSharedPreferences` for token storage.
- A more secure BFF (Backend-for-Frontend) pattern would require a server — not justified for a single-user app with no cloud dependency.
- Strava routes are fetched via `GET /athlete/routes` (list) and `GET /routes/{id}/export_gpx` (download).
- Activity upload is `POST /uploads` with the GPX file + metadata. Strava processes asynchronously; poll `GET /uploads/{id}` for status.

### Industry References

- **Open-source references:** `PathTrackerApp` (Daanfb) and `RunTrack` (sDevPrem) both use ForegroundService + Room + MVVM for tracking apps on GitHub.
- **Android official guidance:** `ExerciseClient` from Health Services recommends a foreground service with session lifecycle management, batched GPS writes, and pause/resume support.
- **Strava API docs (2026):** v3 current, 6-hour token lifetime, write rate limit 1,000/15min, read limit 300/15min.

---

## Recommended Architecture: Sport HUD Within 3-Module Structure

### Principle: Extend Existing Boundaries, Don't Add Modules

The sport HUD features fit **entirely within the existing 3-module architecture**. The phone remains the brain (OAuth, API calls, GPS recording, BT broadcast), the glasses remain the display (new layout mode renders sport metrics), and `shared/` gains new protocol message types.

No new module is needed. No new process is needed. The existing `HudStreamingService` foreground service already runs GPS at 1Hz and broadcasts state over BT — the activity recorder plugs directly into that pipeline.

### System Overview

```
                          PHONE MODULE (com.rokid.hud.phone)
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                          │
│  ┌──────────────────────┐    ┌────────────────────────┐    ┌──────────────────────────┐ │
│  │  StravaAuthManager   │    │  StravaRouteImporter   │    │  StravaUploader          │ │
│  │  - OAuth flow (intent)│    │  - GET /athlete/routes │    │  - POST /uploads         │ │
│  │  - token lifecycle    │    │  - GET /routes/{id}/   │    │  - poll upload status    │ │
│  │  - EncryptedSP storage│    │    export_gpx          │    │  - user-initiated        │ │
│  │  - OkHttp interceptor │    │  - GPX -> waypoints    │    │  - after nav ends        │ │
│  └──────────┬───────────┘    └──────────┬─────────────┘    └──────────┬───────────────┘ │
│             │                           │                              │                 │
│             │                           ▼                              │                 │
│             │              ┌────────────────────────────┐              │                 │
│             │              │  NavigationManager         │              │                 │
│             └──────────────│  (existing: waypoints,     │──────────────┘                 │
│                            │   step tracking, reroute)  │                                │
│                            └────────────┬───────────────┘                                │
│                                         │                                                 │
│                                         │ recording is user-initiated (opt-in, REC-01)    │
│                                         ▼                                                 │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐  │
│  │                     HudStreamingService (Foreground Service, existing)               │  │
│  │                                                                                     │  │
│  │  ┌────────────────┐   ┌───────────────────┐   ┌──────────────────────────────┐    │  │
│  │  │  FusedLocation │   │  NavigationManager │   │  Bluetooth SPP Server       │    │  │
│  │  │  Provider      │   │  (routing state)   │   │  (broadcast to glasses)     │    │  │
│  │  │  (1Hz GPS)     │   └─────────┬─────────┘   └──────────────┬───────────────┘    │  │
│  │  └───────┬───────┘              │                            │                     │  │
│  │          │                      │  location + route info     │                     │  │
│  │          ▼                      ▼                            │                     │  │
│  │  ┌────────────────────────────────────────────────────────┐  │                     │  │
│  │  │             ActivitySessionManager (NEW)               │  │                     │  │
│  │  │                                                        │  │                     │  │
│  │  │  - Session lifecycle: IDLE -> TRACKING -> FINISHED     │  │                     │  │
│  │  │  - Accumulates track points (lat/lng/alt/timestamp)    │  │                     │  │
│  │  │  - Computes metrics: elapsed time, distance, pace      │  │                     │  │
│  │  │  - Every GPS tick: broadcasts SportStateMessage        │──┼──► BT to glasses    │  │
│  │  │  - Manual stop support (pause/resume deferred to v2)   │  │                     │  │
│  │  │  - On finish: persists session + track to JSON file    │  │                     │  │
│  │  └────────────────────────────────────────────────────────┘  │                     │  │
│  └─────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                          │
│  ┌──────────────────────────────────────────────────────────────┐                       │
│  │  ActivitySummaryActivity (NEW)                               │                       │
│  │  - Shows: total time, distance, avg speed/pace               │                       │
│  │  - "Upload to Strava" button -> StravaUploader               │                       │
│  │  - List of past activities (from session JSON files)         │                       │
│  └──────────────────────────────────────────────────────────────┘                       │
│                                                                                          │
│  Phone UI additions:                                                                     │
│  - Strava auth button (in settings)                                                      │
│  - Route list picker (after search area, when Strava authed)                             │
│  - Activity recording indicator + stop button (in navigation panel)                     │
│  - Activity summary screen (separate activity)                                          │
│                                                                                          │
└──────────────────────────────────────────────────────────────────────────────────────────┘
                                    │  Bluetooth SPP (new: sport_state messages)
                                    ▼
                          GLASSES MODULE (com.rokid.hud.glasses)
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                          │
│  ┌──────────────────────────────────────────┐    ┌─────────────────────────┐             │
│  │  BluetoothClient (existing)               │    │  HudState (existing)    │             │
│  │  processMessage() handles new             │───►│  - new fields:         │             │
│  │  ParsedMessage.SportState                 │    │    elapsedTime          │             │
│  │                                          │    │    totalDistance         │             │
│  │                                          │    │    currentSpeed          │             │
│  │                                          │    │    averagePace           │             │
│  │                                          │    │    sessionState          │             │
│  │                                          │    └──────────┬──────────────┘             │
│  └──────────────────────────────────────────┘               │                            │
│                                                              │                            │
│                                                              ▼                            │
│  ┌─────────────────────────────────────────────────────────────────────────────────┐    │
│  │  HudView (existing, new layout mode)                                            │    │
│  │                                                                                 │    │
│  │  MapLayoutMode.SPORT:                                                           │    │
│  │  ┌────────────────────────────────────────────────────────────────┐            │    │
│  │  │  [Map fills background, dimmed to ~50% opacity]                │            │    │
│  │  │                                                                 │            │    │
│  │  │  ┌──────────────┐  ┌────────────────────────────────────────┐ │            │    │
│  │  │  │  Route line   │  │  Metrics overlay (semi-transparent)    │ │            │    │
│  │  │  │  Player arrow │  │                                       │ │            │    │
│  │  │  │  bearing      │  │  ⏱ 00:32:15       (elapsed time)     │ │            │    │
│  │  │  │  (existing)   │  │  📍 8.42 km        (distance)        │ │            │    │
│  │  │  │               │  │  ⚡ 22.3 km/h      (current speed)   │ │            │    │
│  │  │  │               │  │  ⏰ 5:12 /km       (average pace)    │ │            │    │
│  │  │  │               │  │  ◉ RECORDING       (session state)   │ │            │    │
│  │  │  └──────────────┘  └────────────────────────────────────────┘ │            │    │
│  │  └────────────────────────────────────────────────────────────────┘            │    │
│  └─────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                          │
└──────────────────────────────────────────────────────────────────────────────────────────┘

                    SHARED MODULE (com.rokid.hud.shared)
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│  Protocol additions:                                                                     │
│  ┌───────────────────────┐   ┌───────────────────────┐   ┌────────────────────────────┐  │
│  │  Messages.kt          │   │  ProtocolConstants.kt │   │  ProtocolCodec.kt          │  │
│  │  - SportState         │   │  - FIELD_ELAPSED_TIME │   │  - encodeSportState()      │  │
│  │    Message            │   │  - FIELD_DISTANCE     │   │  - decode ->               │  │
│  │  - SessionState       │   │  - FIELD_CURRENT_SPEED│   │    ParsedMessage.          │  │
│  │    Message: NOT in v1 │   │  - FIELD_AVG_PACE     │   │    SportState              │  │
│  │    (state rides in    │   │  - FIELD_SESSION_STATE│   │                            │  │
│  │    sport_state)       │   │  - MessageType.       │   │                            │  │
│  │                       │   │    SPORT_STATE        │   │                            │  │
│  └───────────────────────┘   └───────────────────────┘   └────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

### Component Boundaries and Responsibilities

| Component | Module | Responsibility | Communicates With |
|-----------|--------|----------------|-------------------|
| `StravaAuthManager` | phone | OAuth 2.0 flow, token storage (EncryptedSharedPreferences), token refresh via OkHttp Authenticator | Strava API (OAuth endpoints), `MainActivity` (UI callbacks) |
| `StravaApiClient` | phone | OkHttp-based client for Strava API v3 endpoints (direct OkHttp usage, not Retrofit — avoids coroutine dependency) | Strava API (HTTPS), `StravaRouteImporter`, `StravaUploader` |
| `StravaRouteImporter` | phone | Fetches route list, downloads GPX, converts GPX waypoints to OSRM-compatible waypoints | `StravaApiClient`, `NavigationManager` |
| `ActivitySessionManager` | phone | Session lifecycle (IDLE/TRACKING/FINISHED), metric computation, track accumulation | `HudStreamingService.onLocationUpdate()`, user action (start/stop via phone UI) |
| `StravaUploader` | phone | GPX export + POST /uploads to Strava, poll status | `StravaApiClient`, `ActivitySessionManager` (finished sessions) |
| `SportStateMessage` | shared | Protocol data class for sport metrics (elapsed time, distance, speed, pace, state) | `ProtocolCodec` encode/decode |
| `BluetoothClient` (processMessage) | glasses | Parses `SportStateMessage`, updates `HudState` fields | `HudState` |
| `HudState` (new fields) | glasses | Holds current sport metrics + session state for rendering | `HudView` |
| `HudView` (SPORT layout) | glasses | Renders map background + semi-transparent metrics overlay | `HudState` |

### Data Flow

#### Flow 1: Strava OAuth Login

```
User taps "Connect Strava" in MainActivity
    │
    ▼
StravaAuthManager opens Intent: https://www.strava.com/oauth/mobile/authorize?
    client_id={id}&redirect_uri={custom_scheme}://callback&scope=read,activity:read_all,activity:write
    │
    ▼
Browser/Strava app: user logs in, grants scopes
    │
    ▼
Strava redirects to custom scheme URL: {scheme}://callback?code={auth_code}&scope=...
    │
    ▼
MainActivity receives through intent-filter -> forwards to StravaAuthManager
    │
    ▼
StravaAuthManager POST https://www.strava.com/oauth/token with client_secret
    │
    ▼
Response: access_token (expires 6h), refresh_token, athlete info
    │
    ▼
StravaAuthManager stores tokens in EncryptedSharedPreferences
```

**Key constraint:** Strava does NOT support PKCE. The client_secret must be in the APK (via BuildConfig from local.properties). This is standard for mobile-only Strava apps but means the secret is extractable via reverse engineering — acceptable for a single-user app, flagged as a known risk.

#### Flow 2: Strava Route Import -> Navigation

```
User is authenticated -> sees "My Routes" list in MainActivity
    │
    ▼
StravaRouteImporter: GET /api/v3/athlete/routes (paginated)
    │
    ▼
User taps a route -> StravaRouteImporter: GET /api/v3/routes/{id}/export_gpx
    │
    ▼
Parse GPX -> extract track points -> Douglas-Peucker downsample (epsilon ~10-20m, target ≤200 points)
    │
    ▼
OSRM /route with ALL downsampled points as via-waypoints (steps=true)
    -> road-snapped route geometry + real turn-by-turn steps
    (fallback: if OSRM via-point routing fails/unavailable, use raw GPX waypoints in follow-route mode)
    │
    ▼
Pass route + steps to a new waypoint-accepting NavigationManager path
    │
    ▼
RouteMessage + StepsListMessage broadcast to glasses via existing BT mechanism
```

**Architecture decision:** When navigating a Strava route, the phone downsamples the GPX (Douglas-Peucker, epsilon ~10-20m, target ≤200 points) and calls OSRM /route with all downsampled points as via-waypoints (steps=true). This returns a road-snapped route AND real turn-by-turn steps, so the existing route line, maneuver arrows, and TTS voice directions all work for Strava routes. It requires extending OsrmClient (currently builds a 2-point URL only) and adding a waypoint-accepting NavigationManager path (startNavigation() currently accepts only a destination). Graceful fallback: if OSRM via-point routing fails or is unavailable, navigate the raw GPX waypoints in follow-route mode — route line + distance to next waypoint, no turn arrows or TTS.

#### Flow 3: Activity Recording (Primary Data Path)

```
User explicitly starts recording (manual opt-in, independent of navigation)
    │
    ▼
ActivitySessionManager.startSession() called by user action in MainActivity
    Session state: IDLE -> TRACKING
    (Navigation start does NOT auto-trigger recording — per REC-01)
    │
    ▼
HudStreamingService.onLocationUpdate() receives GPS Location (1Hz)
    │
    ├──► (existing) NavigationManager.onLocationUpdate() -> step tracking, reroute
    │
    └──► ActivitySessionManager.recordLocation(Location)
            │
            ▼
         ActivitySession accumulates TrackPoint(lat, lng, alt, timestamp, speed, accuracy)
            │
            ▼
         Every GPS tick: compute live metrics
            - elapsedTime = now - sessionStartTime
            - totalDistance = sum of haversine distances between consecutive points
            - currentSpeed = location.speed (from GPS)
            - averagePace = elapsedTime / totalDistance (or 0 if < 100m)
            │
            ▼
         Build SportStateMessage -> broadcast to glasses via BT
            │
            ▼
         Glasses: BluetoothClient.processMessage() -> HudState update -> postInvalidate()
```

#### Flow 4: Activity Finish and Upload

```
User taps "Stop Recording" (arrival may surface a stop prompt — auto-stop deferred to v1.x)
    │
    ▼
User action -> ActivitySessionManager.stopSession()
    Session state: TRACKING -> FINISHED
    │
    ▼
ActivitySessionManager finalizes session:
    - Writes session JSON to phone storage:
      {
        "id": "uuid",
        "startTime": "...",
        "endTime": "...",
        "elapsedTimeMs": 945000,
        "totalDistanceM": 8420,
        "trackPoints": [{"lat":..., "lng":..., "ts":...}, ...],
        "averageSpeed": 22.3,
        "routeName": "Morning Ride",
        "stravaUploaded": false
      }
    - If connected to Strava route, stores route name + metadata
    │
    ▼
Phone shows ActivitySummaryActivity with activity data
    │
    ▼
User taps "Upload to Strava" -> StravaUploader:
    1. Export activity as GPX string from track points
    2. POST /api/v3/uploads with multipart file + metadata
    3. Poll GET /api/v3/uploads/{id} until status = "Your activity is ready."
    4. Update session JSON: stravaUploaded = true
    5. Notify user of success/failure
```

### Session State Machine

```
         ┌─────────────────────────────────────────────────────┐
         │                                                     │
         │  [IDLE] ──── startSession() ────► [TRACKING]       │
         │    ▲                                │               │
         │    │                                │               │
         │    │                                │ stopSession() │
         │    │                                ────► [FINISHED]│
         │    │                                        │       │
         │    └──── resetSession() ◄────────────────────┘       │
         │                                                     │
         └─────────────────────────────────────────────────────┘
```

**Transition rules:**
- `startSession()`: Called when user manually starts recording (explicit action, not auto-triggered by navigation). Requires: GPS available, not already tracking.
- `stopSession()`: Called on user stop action. Finalizes session, persists, cannot resume.
- `resetSession()`: Called after viewing summary. Clears session data.

**Note:** Pause/resume (PAUSED state) is deferred to v2. The v1 state machine is deliberately simple: IDLE → TRACKING → FINISHED (with reset). Moving time is computed from a speed threshold (<0.5 m/s = stopped), not from a paused state. Adding PAUSED in v2 will insert between TRACKING and FINISHED.

## Patterns to Follow

### Pattern 1: Plugin GPS Pipeline (Observer)

**What:** The ActivitySessionManager observes the existing GPS location stream via a callback interface, without modifying the GPS acquisition code.

**When to use:** Any time a new consumer of GPS data is needed.

**Why this fits:** `HudStreamingService.onLocationUpdate()` already dispatches to `NavigationManager`. Adding another observer preserves the existing flow.

```kotlin
// In HudStreamingService:
interface LocationConsumer {
    fun onLocationUpdate(location: Location)
}

// Existing consumer -> NavigationManager
// New consumer -> ActivitySessionManager

// Service holds a list of consumers
private val locationConsumers = mutableListOf<LocationConsumer>()

// In onLocationUpdate():
fun onLocationUpdate(location: Location) {
    for (consumer in locationConsumers) {
        consumer.onLocationUpdate(location)
    }
    // ... existing broadcast logic ...
}
```

### Pattern 2: State Message on Every Tick (Same as Existing)

**What:** Just like `StateMessage` is broadcast every GPS tick (1Hz), `SportStateMessage` is broadcast on the same cadence when recording is active.

**When to use:** When glasses need real-time updates of a rapidly changing value.

**Why this fits:** Exactly matches the existing pattern — glasses are stateless, phone pushes state every tick.

```kotlin
// In HudStreamingService broadcast loop:
private fun broadcastSportState() {
    if (activitySessionManager.state != SessionState.TRACKING) return
    
    val metrics = activitySessionManager.currentMetrics
    val message = SportStateMessage(
        type = MessageType.SPORT_STATE,
        elapsedTimeMs = metrics.elapsedTimeMs,
        totalDistanceM = metrics.totalDistanceM,
        currentSpeedMps = metrics.currentSpeedMps,
        averagePaceMsPerKm = metrics.averagePaceMsPerKm,
        sessionState = metrics.sessionState
    )
    broadcast(ProtocolCodec.encode(message))
}
```

### Pattern 3: OAuth with OkHttp Authenticator for Token Refresh

**What:** A custom OkHttp `Authenticator` that catches 401 responses and transparently refreshes the access token using the refresh token before retrying the request.

**When to use:** Any external API with short-lived access tokens and refresh token support.

```kotlin
class StravaAuthenticator(
    private val authManager: StravaAuthManager
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.code == 401) {
            synchronized(this) {
                val newToken = authManager.refreshAccessToken() // POST /oauth/token
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $newToken")
                    .build()
            }
        }
        return null
    }
}
```

### Pattern 4: GPX Routes via OSRM Via-Point Routing (Follow-Route Fallback)

**What:** Downsample GPX track points with Douglas-Peucker, then request OSRM /route using the downsampled points as via-waypoints with steps=true — producing a road-snapped route and real turn-by-turn steps. If OSRM via-point routing fails or is unavailable, fall back to navigating the raw GPX waypoints in follow-route mode.

**When to use:** For any pre-planned route (e.g., Strava GPX) that is not a simple origin-to-destination OSRM query.

```kotlin
// In StravaRouteImporter:
fun gpxToWaypoints(gpxContent: String): List<LatLng> {
    // Parse GPX XML using XmlPullParser
    // Extract <trkpt lat="..." lon="..."> elements
    // Douglas-Peucker simplification (epsilon ~10-20m) down to ≤200 points
    // Strava GPX may have thousands of points -> downsample
    // Result feeds an extended multi-waypoint OsrmClient call (via-waypoints, steps=true)
}
```

**Key decision:** Trade off density vs accuracy. A dense GPX (thousands of points) is a more faithful trace but exceeds OSRM via-point practicality and makes off-route detection less useful (waypoint spacing too tight). Downsample to ≤200 points; these become the OSRM via-waypoints. The displayed route line uses OSRM's returned geometry (or the full GPX in fallback mode); navigation logic uses OSRM's returned steps (or the downsampled points in fallback mode).

## Anti-Patterns to Avoid

### Anti-Pattern 1: Two Parallel Foreground Services

**What people do:** Create a separate `ActivityRecordingService` foreground service alongside the existing `HudStreamingService`.

**Why it's wrong:** Two foreground services means two persistent notifications, twice the battery overhead, duplicate GPS subscriptions, and potential for the system to kill one while the other runs — creating an inconsistent state (recording without BT broadcast, or navigating without recording).

**Do this instead:** Add activity recording as a concern of the existing `HudStreamingService`. The service already holds the WakeLock, subscribes to GPS, and runs the BT server. The `ActivitySessionManager` is an in-process component within the service. One service, one notification.

### Anti-Pattern 2: Trying to Produce Turn-by-Turn Instructions from GPX Points

**What people do:** Attempt to infer turn maneuvers from GPX track angle changes (e.g., "if bearing changes > 45 degrees, it's a turn").

**Why it's wrong:** Without road network data, you cannot distinguish between a real intersection turn, a switchback on a climbing road, a U-turn, or GPS noise. The result is unreliable and frustrates users.

**Do this instead:** If turn-by-turn is critical for Strava routes, optionally call OSRM with the GPX waypoints as the route to get proper step data. Otherwise, display a "Follow route" instruction with distance to next waypoint and rely on the route line + map for guidance.

### Anti-Pattern 3: Writing Every GPS Point to Disk in Real-Time

**What people do:** Write each track point individually to a SQLite database or JSON file as it arrives (IO on every GPS tick, 3600 writes per hour).

**Why it's wrong:** 1Hz disk writes will trash battery life and wear storage. On low-end devices it may cause jank on the main thread if writes block.

**Do this instead:** Accumulate points in memory during the session. Only persist on session stop. For crash resilience, save a checkpoint every 60 seconds or every 500 points, whichever comes first.

### Anti-Pattern 4: Strava Upload Without Local Persistence

**What people do:** Upload the activity immediately when recording stops, then discard local data.

**Why it's wrong:** If the upload fails (network, rate limit, Strava processing error), the activity data is lost. The user has no fallback. "We lost your ride" is the worst UX outcome.

**Do this instead:** Always persist the session JSON locally first. Upload is a separate, user-initiated action. Mark sessions as `stravaUploaded: true/false` in the JSON. Provide a "Retry Upload" option. The local file is the source of truth; Strava is a copy.

### Anti-Pattern 5: Separate Metric Computation from GPS Pipeline

**What people do:** Duplicate metric calculation code across ActivitySessionManager and HudStreamingService's state broadcast logic.

**Why it's wrong:** Distance and speed calculations must be consistent between what's saved and what's broadcast. If they diverge, the glasses show different metrics than what gets uploaded to Strava.

**Do this instead:** `ActivitySessionManager` is the single source of truth for metrics. The BT broadcast reads from it. The uploader reads from it. No other component computes distance or pace.

## Scaling Considerations

This is a single-user app on physical hardware (phone + glasses). There are no server-side scaling concerns. The relevant scaling is **within-device** as activity duration increases:

| Concern | 1-hour ride | 8-hour ride | Mitigation |
|---------|-------------|-------------|------------|
| Track points in memory | ~3,600 points | ~28,800 points | ~28K LatLng + timestamp pairs ~ 1MB, fine for modern phones |
| Session JSON size | ~200KB | ~1.6MB | Acceptable, written once on stop |
| GPX file for upload | ~300KB | ~2.4MB | Acceptable for single upload |
| Strava rate limit | 1 upload | 1 upload | Rate limit is per-app, 1,000 writes/15min — single-user won't hit it |
| Token lifetime | 6h < session | 6h > session | Token will expire mid-ride; need to refresh at start or mid-session |

**The token expiry problem:** A long ride (>6 hours) means the access token expires while recording. This doesn't affect recording (no API calls needed). It only matters at upload time. The refresh flow must handle the case where the refresh token itself needs to be used to get a new access token. The OkHttp Authenticator pattern handles this transparently at upload time.

## Integration Points

### External Services

| Service | Integration Pattern | Notes |
|---------|---------------------|-------|
| Strava OAuth | Intent-based auth + deep link callback + co-located secret | Client secret from BuildConfig; no PKCE |
| Strava API v3 | OkHttp with Authenticator interceptor for token injection | 300 read / 1,000 write per 15min limits |
| Strava GPX export | HTTP GET, parse with XmlPullParser | No rate limit separate from read quota |
| Strava upload | HTTP POST multipart, then poll GET for status | Asynchronous processing, poll every 1-2s for up to 2min |

### Internal Boundaries

| Boundary | Communication | Notes |
|----------|---------------|-------|
| `StravaRouteImporter` -> `NavigationManager` | Direct method call via service instance | new waypoint-accepting NavigationManager path (added in Phase 4 — existing `startNavigation()` is destination-only) |
| `ActivitySessionManager` -> `HudStreamingService.broadcast()` | Callback interface | Phone builds + broadcasts `SportStateMessage` every GPS tick when recording |
| `ActivitySessionManager` -> JSON file | Direct write on session stop | Persists to app-specific storage (no permissions needed) |
| `StravaUploader` -> `StravaApiClient` | OkHttp call | Upload via POST multipart with GPX file bytes |

### Bluetooth Protocol Additions

**New message type:** `sport_state` (per REC-07)

```json
{
  "t": "sport_state",
  "et": 945000,       // elapsed time in ms
  "mt": 823000,       // moving time in ms (excludes stopped periods; feeds summary, not HUD display in v1)
  "d": 8420,          // total distance in meters
  "cs": 6.2,          // current speed in m/s
  "ap": 294000,       // average pace in ms/km (for running: 294000 = 4:54/km)
  "st": "tracking"    // session state: "idle", "tracking", "finished"
}
```

This is a **phone -> glasses** message only. No glasses -> phone messages needed for sport features (consistent with the existing architecture where glasses only send tile requests).

## Suggested Build Order

The features have natural dependencies that dictate build order:

### Phase 1: Shared Protocol + Storage Foundation
**No external dependencies.**
- Add `SportStateMessage` to `Messages.kt`
- Add field constants to `ProtocolConstants.kt`
- Add encode/decode to `ProtocolCodec.kt`
- Add `ParsedMessage.SportState` to codec
- Result: Protocol supports sport metrics, but nothing sends or consumes it yet.

### Phase 2: ActivitySessionManager (Phone-Side Core)
**Depends on: Phase 1.**
- Implement `ActivitySession` data model (track points list, start/end time, computed metrics)
- Implement `ActivitySessionManager` with state machine (IDLE/TRACKING/FINISHED; PAUSED deferred to v2)
- Hook into `HudStreamingService.onLocationUpdate()` as new `LocationConsumer`
- Every GPS tick: record point, compute metrics, broadcast `SportStateMessage` to glasses
- On session stop: serialize session to JSON file
- Recording start/stop is user-initiated (REC-01 opt-in) — do NOT wire NavigationManager lifecycle to auto-start/stop recording; on arrival, surface a stop prompt (auto-stop deferred to v1.x)
- Result: Activity recording works end-to-end on the phone side, broadcasts metrics to glasses (glasses ignore unrecognized message type — graceful degradation)

### Phase 3: Glasses Sport Layout
**Depends on: Phase 1.**
- Add sport metric fields to `HudState` (`elapsedTime`, `totalDistance`, `currentSpeed`, `averagePace`, `sessionState`)
- Add `ParsedMessage.SportState` handling to `BluetoothClient.processMessage()`
- Add `MapLayoutMode.SPORT` to layout enum
- Add sport metrics overlay rendering to `HudView` (if `layoutMode == SPORT` and session is tracking)
- SPORT mode is reachable via the glasses tap cycle only (Full → Corner → Sport → Full) per HUD-03; auto-switch on message arrival is deferred
- Result: Glasses display real-time sport metrics during navigation.

### Phase 4: Strava Auth + Route Import
**Depends on: Phase 1 (protocol not needed, but protocol already done).**
- OkHttp, logging-interceptor, and Gson are already explicitly declared in phone/build.gradle.kts — verify versions only; no build changes needed (no Retrofit for Strava — see STACK.md rationale)
- Implement `StravaAuthManager`: OAuth intent, deep link handling, token storage in EncryptedSharedPreferences, OkHttp Authenticator
- Implement `StravaApiClient`: OkHttp-based client for athlete routes + GPX export (direct HTTP, no Retrofit)
- Implement `StravaRouteImporter`: GPX parsing, downsampling to waypoints
- Wire route list UI into `MainActivity` (below search area, shown when authenticated)
- Wire route selection -> the new waypoint-accepting NavigationManager path (OSRM via-point route + steps; Phase 4 adds it)
- Result: User can auth with Strava, browse routes, and navigate them.

### Phase 5: Activity Summary + Strava Upload
**Depends on: Phase 2 (recording works), Phase 4 (Strava auth done).**
- Implement `ActivitySummaryActivity` (or fragment in MainActivity): show completed activity stats
- Add "Past Activities" list to phone UI
- Implement `StravaUploader`: GPX export from session, POST multipart, poll status
- Wire "Upload to Strava" button in activity summary
- Handle edge cases: upload failure, rate limit, token expiry during upload
- Result: Completed activities can be uploaded to Strava.

### Dependency Graph

```
Phase 1 (Protocol)
    ├──► Phase 2 (ActivitySessionManager)
    │        └──► Phase 3 (Glasses Sport Layout)
    │
    └──► Phase 4 (Strava Auth + Route Import)
             └──► Phase 5 (Summary + Upload)
```

Phases 2 and 4 are **parallelizable** — they have no dependency on each other. The ActivityRecorder doesn't need Strava to work (it can record any GPS session). Strava route import doesn't need the recorder.

Phase 3 depends on Phase 1 but not on Phase 2 — the glasses sport layout doesn't care where the metrics come from, just that they arrive as `SportStateMessage`. This means Phase 3 can be built and tested against hardcoded test data even before Phase 2 is complete.

---

*Architecture research for: Sport HUD and activity recording integration into phone/glasses AR navigation.*
*Researched: 2026-07-02*
