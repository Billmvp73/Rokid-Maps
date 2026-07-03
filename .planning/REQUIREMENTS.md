# Requirements: Rokid HUD Maps — Sport HUD

**Defined:** 2026-07-02
**Core Value:** Cyclists and runners see their route and live performance metrics floating in their field of view, keeping their eyes on the road and their phone in their pocket.

## v1 Requirements

Requirements for the Strava + sport HUD release.

### Strava Authentication

- [ ] **AUTH-01**: User can log in with Strava account via OAuth 2.0 (Chrome Custom Tab, mobile authorization endpoint)
- [ ] **AUTH-02**: OAuth tokens (access_token, refresh_token, expires_at) persist securely across app restarts using EncryptedSharedPreferences
- [ ] **AUTH-03**: Auth token is automatically refreshed before expiry (6-hour window) when making Strava API calls

### Route Import

- [ ] **RIMP-01**: User can browse their saved/starred Strava routes in a list on the phone, showing route name, distance, and elevation
- [ ] **RIMP-02**: User can select a Strava route and import it as GPX data via Strava API
- [ ] **RIMP-03**: Imported GPX track points are parsed and downsampled (Douglas-Peucker algorithm) into OSRM-compatible waypoints
- [ ] **RIMP-04**: User can preview the imported route on the phone map before starting navigation

### Navigation

- [ ] **NAVV-01**: User can start turn-by-turn navigation following an imported Strava route
- [ ] **NAVV-02**: Existing navigation features (route line, maneuver arrows, voice directions via TTS/A2DP) work for Strava-routed navigation
- [ ] **NAVV-03**: Off-route detection and auto-recalculation work for Strava routes

### Activity Recording

- [ ] **REC-01**: Phone app automatically records a GPS track log while navigating (lat, lng, altitude, speed, bearing, timestamp per point)
- [ ] **REC-02**: Phone app computes live metrics: elapsed time, moving time, distance traveled, current speed/pace
- [ ] **REC-03**: GPS accuracy filtering rejects points with accuracy >20m to prevent phantom distance
- [ ] **REC-04**: Speed filtering rejects points below 0.5 m/s when accumulating moving distance
- [ ] **REC-05**: Recording continues reliably in background (WakeLock, foreground service notification, battery optimization handling)

### Sport HUD

- [ ] **HUD-01**: Glasses display a new SPORT layout mode showing: elapsed time, current speed/pace (in selected units), and distance traveled
- [ ] **HUD-02**: Sport metrics update in real-time (~1Hz) via a new `sport_state` Bluetooth protocol message from phone
- [ ] **HUD-03**: User can cycle through layout modes on glasses to reach SPORT mode (tap to cycle: Full → Corner → Mini Strip → Mini Split → Sport)
- [ ] **HUD-04**: Sport HUD uses monochrome green rendering consistent with existing HUD style

### Activity Upload

- [ ] **UPL-01**: After navigation ends, phone displays an activity summary: total time, distance, average speed/pace, route map
- [ ] **UPL-02**: User can upload the completed activity to Strava with one tap (generates GPX, POSTs to Strava, polls for completion)
- [ ] **UPL-03**: Activity track data is persisted locally as JSON before upload (data survives upload failures, app restarts)
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
| HUD-01 | Phase 2 | Pending |
| HUD-02 | Phase 2 | Pending |
| HUD-03 | Phase 2 | Pending |
| HUD-04 | Phase 2 | Pending |
| UPL-01 | Phase 5 | Pending |
| UPL-02 | Phase 5 | Pending |
| UPL-03 | Phase 5 | Pending |
| UPL-04 | Phase 5 | Pending |

**Coverage:**
- v1 requirements: 23 total
- Mapped to phases: 23
- Unmapped: 0

---
*Requirements defined: 2026-07-02*
*Last updated: 2026-07-02 after roadmap creation*
