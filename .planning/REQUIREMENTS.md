# Requirements: Rokid HUD Maps — Sport HUD

**Defined:** 2026-07-02
**Core Value:** Cyclists and runners see their route and live performance metrics floating in their field of view, keeping their eyes on the road and their phone in their pocket.

## v1 Requirements

Requirements for the Strava + sport HUD release.

### Strava Authentication

- [ ] **AUTH-01**: User can log in with Strava account via OAuth 2.0 Authorization Code Grant (Chrome Custom Tab, authorization endpoint `https://www.strava.com/oauth/mobile/authorize`). Required scopes: `read,activity:read_all,activity:write` (scope set to be confirmed against developers.strava.com during Phase 3 research — private-route listing may require read_all). No PKCE — client_secret is embedded in APK via BuildConfig.
- [ ] **AUTH-02**: OAuth tokens (access_token, refresh_token, expires_at) persist securely across app restarts using EncryptedSharedPreferences
- [ ] **AUTH-03**: Auth token is automatically refreshed before expiry (6-hour window) when making Strava API calls

### Route Import

- [ ] **RIMP-01**: User can browse their saved/starred Strava routes in a list on the phone, showing route name, distance, and elevation
- [ ] **RIMP-02**: User can select a Strava route and import it as GPX data via Strava API
- [ ] **RIMP-03**: Imported GPX track points are parsed and downsampled (Douglas-Peucker algorithm) into OSRM-compatible waypoints
- [ ] **RIMP-04**: User can preview the imported route on the phone map before starting navigation

### Navigation

- [ ] **NAVV-01**: User can start turn-by-turn navigation following an imported Strava route
- [ ] **NAVV-02**: Existing navigation features (route line, maneuver arrows, voice directions via TTS/A2DP) work for Strava-routed navigation via OSRM via-point routing; when OSRM routing is unavailable, navigation degrades gracefully to follow-route mode (route line + distance to next waypoint, no turn instructions)
- [ ] **NAVV-03**: Off-route detection and auto-recalculation work for Strava routes

### Activity Recording

- [ ] **REC-01**: Phone app records a GPS track log during navigation with explicit user action to start recording (lat, lng, altitude, speed, bearing, timestamp per point). Recording is opt-in, not auto-triggered by navigation start. Free-ride recording (without a route) is also supported.
- [ ] **REC-02**: Phone app computes live metrics: elapsed time, moving time, distance traveled, current speed/pace
- [ ] **REC-03**: GPS accuracy filtering: points with accuracy >20m are recorded in the track log but excluded from distance accumulation to prevent phantom distance. Points with accuracy <= 0 or unknown accuracy are also excluded from distance calculation
- [ ] **REC-04**: Moving-state hysteresis governs distance accumulation: enter moving above 0.7 m/s, exit moving below 0.3 m/s (nominal 0.5 m/s threshold); distance accumulates only while in the moving state. Apply a 5-point moving-average filter on GPS speed values to reduce noise.
- [ ] **REC-05**: Recording continues reliably in background. Includes: WakeLock, foreground service notification, battery optimization exemption, ACCESS_BACKGROUND_LOCATION permission (required on Android 10+ for GPS when screen is off), and AlarmManager watchdog to detect silent GPS stoppage, and GPS-staleness detection (warn when no fix received for >30s)
- [ ] **REC-06**: Session data (track points + computed metrics) persists to local JSON on recording stop, with a periodic checkpoint (every 60s or 500 points, whichever comes first) for crash resilience; persisted sessions survive app restart
- [ ] **REC-07**: Phone defines a `sport_state` Bluetooth protocol message (shared module: message type + codec) and broadcasts it at ~1Hz during recording with elapsed time, moving time, distance, current speed/pace, and session state; elapsed time and distance are monotonic non-decreasing within a session

### Sport HUD

- [ ] **HUD-01**: Glasses display a new SPORT layout mode showing: elapsed time, current speed/pace (in selected units), and distance traveled
- [ ] **HUD-02**: Sport metrics update in real-time (~1Hz) on glasses by consuming the `sport_state` Bluetooth message (message type + phone-side broadcast delivered in Phase 1 via REC-07)
- [ ] **HUD-03**: User can reach SPORT mode via glasses tap. The existing toggleLayout() cycles between two primary modes (Full ↔ Corner). SPORT is added as a third mode in the tap cycle. Mini modes (Mini Strip, Mini Split) remain phone-triggered only and reset to Full on glasses tap (preserving existing behavior). Tap cycle: Full → Corner → Sport → Full.
- [ ] **HUD-04**: Sport HUD uses monochrome green rendering consistent with existing HUD style

### Activity Upload

- [ ] **UPL-01**: After recording stops, phone displays an activity summary: total time, moving time, distance, average speed/pace, route map
- [ ] **UPL-02**: User can upload the completed activity to Strava with one tap (generates GPX, POSTs to Strava, polls for completion)
- [ ] **UPL-03**: Upload failure never deletes local activity data (persisted per REC-06); failed uploads can be retried
- [ ] **UPL-04**: User can view past recorded activities on the phone (local history list)

## v2 Requirements

Deferred to future release. Not in current roadmap.

### Sensors

- **SENS-01**: Bluetooth heart rate monitor (HRM) sensor pairing and data display on glasses
- **SENS-02**: Cycling cadence/speed sensor (CSC) pairing and data display
- **SENS-03**: Cycling power meter sensor pairing and data display

### Advanced Recording

- **RECV-01**: Auto-pause when movement stops (configurable threshold)
- **RECV-02**: Lap/split tracking with manual lap button
- **RECV-03**: Elevation gain/loss tracking during activity

### Social

- **SOCL-01**: View Strava segment results after activity completion
- **SOCL-02**: Auto-sync activities to Strava without manual upload confirmation

## Out of Scope

| Feature | Reason |
|---------|--------|
| Real-time Strava activity reading | Strava API has no real-time activity endpoint. Built-in recording is the correct approach. |
| Live Strava segments during ride | Segments API is partners-only, not public. Complex and rate-limited even if accessible. |
| Heart rate / cadence / power meter sensors (v1) | BLE sensor pairing adds significant complexity. Deferred to v2. GPS-only metrics cover core HUD value. |
| Automatic activity detection (auto-start) | GPS false positives common. Manual start is more reliable for v1. |
| Full Strava social feed (feed, kudos, comments) | This is what the Strava app is for. Replicating it bloats the phone app. |
| Built-in route planning/creation | Use Strava (or Komoot/RideWithGPS) for route planning. This app navigates and records. |
| Multi-day / bikepacking route support | Splits, overnight stops, battery strategy add major complexity. Single-day covers 95% of use cases. |
| Full offline maps + offline routing | OSRM requires internet for routing. Tile cache handles cached areas only. |
| Group ride / friend location tracking | Requires server component (WebSocket/polling). Contradicts no-cloud-services architecture. |
| Music/podcast controls on glasses | Separate concern from navigation/metrics. |
| iOS support | Android-only, matching existing architecture and hardware target (Rokid glasses). |
| Running-specific metrics (cadence, stride length) | Requires additional sensor data or ML-based gait analysis. GPS-only metrics work for both cycling and running. |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| AUTH-01 | Phase 3 | Pending |
| AUTH-02 | Phase 3 | Pending |
| AUTH-03 | Phase 3 | Pending |
| RIMP-01 | Phase 4 | Pending |
| RIMP-02 | Phase 4 | Pending |
| RIMP-03 | Phase 4 | Pending |
| RIMP-04 | Phase 4 | Pending |
| NAVV-01 | Phase 4 | Pending |
| NAVV-02 | Phase 4 | Pending |
| NAVV-03 | Phase 4 | Pending |
| REC-01 | Phase 1 | Pending |
| REC-02 | Phase 1 | Pending |
| REC-03 | Phase 1 | Pending |
| REC-04 | Phase 1 | Pending |
| REC-05 | Phase 1 | Pending |
| REC-06 | Phase 1 | Pending |
| REC-07 | Phase 1 | Pending |
| HUD-01 | Phase 2 | Pending |
| HUD-02 | Phase 2 | Pending |
| HUD-03 | Phase 2 | Pending |
| HUD-04 | Phase 2 | Pending |
| UPL-01 | Phase 5 | Pending |
| UPL-02 | Phase 5 | Pending |
| UPL-03 | Phase 5 | Pending |
| UPL-04 | Phase 5 | Pending |

**Coverage:**
- v1 requirements: 25 total
- Mapped to phases: 25
- Unmapped: 0

---
*Requirements defined: 2026-07-02*
*Last updated: 2026-07-02 after design-doc review fixes*
