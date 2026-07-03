# Rokid HUD Maps

## What This Is

An Android-based heads-up display for Rokid AR glasses that provides turn-by-turn navigation for cycling and running. The phone handles GPS, routing, and activity recording, then streams everything to the glasses over Bluetooth. Users can import cycling/running routes from Strava, get turn-by-turn directions floating in their field of view, and see real-time performance metrics (speed, distance, elapsed time, pace) — no need to pull out a phone or look down at a bike computer.

## Core Value

Cyclists and runners see their route and live performance metrics floating in their field of view, keeping their eyes on the road and their phone in their pocket.

## Requirements

### Validated

- ✓ Bluetooth SPP communication between phone and glasses (line-delimited JSON protocol) — existing
- ✓ GPS tracking at 1Hz via Google FusedLocationProvider — existing
- ✓ OSRM-based turn-by-turn routing with auto-recalculation — existing
- ✓ Nominatim address/place search — existing
- ✓ Overpass API speed limit data — existing
- ✓ Dark-themed rotating HUD map on glasses (CartoDB Dark Matter tiles, green monochrome) — existing
- ✓ 4 layout modes: Full-screen, Corner, Mini-bottom-strip, Mini-bottom-split — existing
- ✓ Route line overlay with glowing green line — existing
- ✓ Compass with north indicator and bearing in degrees — existing
- ✓ Turn-by-turn directions with maneuver arrows and distance countdown — existing
- ✓ Turn alert overlay (200m proximity, auto-dismiss) — existing
- ✓ Speed display on glasses (mph/km/h, toggleable) — existing
- ✓ Speed limit display with overspeed warning — existing
- ✓ TTS voice navigation with audio routed to glasses via A2DP — existing
- ✓ Phone notification forwarding to glasses — existing
- ✓ APK OTA updates from phone to glasses over Bluetooth — existing
- ✓ Wi-Fi hotspot credential sharing from phone to glasses — existing
- ✓ Configurable disk tile cache (50/100/200/500 MB, clearable) — existing
- ✓ Background operation with WakeLock and battery optimization handling — existing
- ✓ Imperial/metric unit toggle — existing
- ✓ Temple-tap to close glasses app — existing

### Active

- [ ] **STRA-01**: User can authenticate with Strava account (OAuth) from the phone app
- [ ] **STRA-02**: User can browse their saved/starred Strava routes on the phone
- [ ] **STRA-03**: User can select a Strava route and start turn-by-turn navigation on the glasses
- [ ] **STRA-04**: Phone app converts Strava route (GPX/polyline) to waypoints compatible with existing OSRM navigation
- [ ] **STRA-05**: Phone app records activity (elapsed time, distance, current speed/pace) during navigation AND optionally as free-ride recording (without a route)
- [ ] **STRA-06**: Glasses display real-time sport metrics: elapsed time, current speed/pace, distance traveled
- [ ] **STRA-07**: Glasses render a dedicated sport HUD layout optimized for cycling/running (metrics overlay)
- [ ] **STRA-08**: Phone app optionally uploads completed activity to Strava after navigation ends
- [ ] **STRA-09**: User can view activity summary (total time, distance, avg speed/pace) on the phone after finishing

### Out of Scope

- Real-time Strava activity reading (Strava API doesn't provide live activity data) — use built-in recording instead
- Heart rate, cadence, power meter sensor integration — deferred, requires BLE sensor pairing
- Social features (kudos, comments, segment leaderboards) — not core to the HUD experience
- Music/podcast controls — separate concern
- iOS support — Android-only, matching existing architecture

## Context

**Existing codebase:** Rokid HUD Maps is a working Android app with ~15+ features across three modules (shared, phone, glasses). The phone app handles GPS, routing, and Bluetooth serving; the glasses render a HUD map. Communication is over Bluetooth SPP with a custom JSON line-delimited protocol.

**Technical landscape:**
- Android app targeting API 34, all modules use minSdk 28 (Android 9)
- Kotlin codebase using callback-heavy patterns, no coroutines or ViewModel
- No tests, no CI, no dependency injection
- Manual JSON encoding via org.json.JSONObject
- Bluetooth SPP with insecure + secure RFCOMM fallback chain
- OSRM/Nominatim/Overpass APIs all free and keyless

**User context:** The user is a Strava subscriber who cycles and runs. They want routes and performance data visible on their AR glasses without pulling out their phone mid-activity. The app already does navigation — adding sport metrics and Strava route import turns it into a full sport HUD.

**Strava API context:** The user's Strava subscription provides full API access. Key endpoints:
- `GET /athlete/routes` — list saved routes
- `GET /routes/{id}/export_gpx` — export route as GPX
- `POST /uploads` — upload completed activity as GPX (async — returns upload_id, requires polling `GET /uploads/{id}` for completion status)
- OAuth 2.0 Authorization Code Grant (no PKCE support — Strava does not implement PKCE; client_secret must be embedded in the APK via BuildConfig from local.properties; token storage uses EncryptedSharedPreferences)

## Constraints

- **Platform**: Android only (phone + Rokid glasses)
- **Language**: Kotlin (matching existing codebase)
- **Connectivity**: Bluetooth SPP for phone↔glasses, internet for Strava API + map tiles + routing
- **Battery**: Must work in background with screen off (WakeLock pattern already established)
- **Strava API**: OAuth 2.0 Authorization Code Grant (no PKCE — client_secret in APK via BuildConfig). Subscriber rate limits: 300 reads/15min, 1,000 writes/15min (verify current figures at developers.strava.com during Phase 3 research). Tokens stored in EncryptedSharedPreferences.
- **No cloud dependencies**: Existing architecture avoids cloud services; Strava integration is the first external auth-required API
- **Navigation engine**: Continue using OSRM (free, no API key) for route navigation; Strava routes provide the waypoints

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Build-in activity recording instead of reading Strava live data | Strava API has no real-time activity endpoint; own recording reuses existing GPS pipeline | — Pending |
| Strava route import via GPX export API | GPX is well-supported, maps cleanly to OSRM waypoints | — Pending |
| Add sport HUD as a new glasses layout mode | Fits existing layout-switching pattern, non-breaking addition | — Pending |
| OAuth flow on phone only, glasses receive processed data | Matches existing architecture — phone is the brain, glasses are the display | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-07-02 after initialization*
