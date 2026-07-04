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

**Shipped in v1.0 MVP (2026-07-03, device-verified on OPPO + Rokid glasses):**

- ✓ Strava OAuth 2.0 login (Authorization Code Grant, no PKCE, EncryptedSharedPreferences, auto-refresh) — v1.0 (AUTH-01/02/03; live-verified, redirect-URI bug fixed on device)
- ✓ Strava route browse + GPX import + Douglas-Peucker downsample + phone-map preview — v1.0 (RIMP-01/02/03/04)
- ✓ Turn-by-turn navigation of imported Strava routes on glasses via OSRM via-point routing, with follow-route graceful degrade + off-route reroute — v1.0 (NAVV-01/02/03)
- ✓ Phone activity recording: GPS track log, live metrics, accuracy filtering, moving-state hysteresis, robust background operation, JSON persistence + checkpoints, `sport_state` 1Hz broadcast — v1.0 (REC-01..07)
- ✓ Glasses SPORT HUD: elapsed / speed-or-pace / distance in monochrome green, ~1Hz, reachable via tap cycle — v1.0 (HUD-01/02/03/04)
- ✓ Activity summary + one-tap Strava upload (GPX → POST /uploads → poll) with local-data-safety + history list — v1.0 (UPL-01/02/03/04; real upload confirmed in feed, activity 19170698786)

### Active

*(None — all v1.0 Active requirements shipped and moved to Validated below. Next milestone v1.x requirements to be defined via `/gsd-new-milestone`; the two v1.x enhancements already shipped this session are logged under Context.)*

Note: STRA-01..09 were the original capture; they were superseded by the AUTH/RIMP/NAVV/REC/HUD/UPL requirement IDs (traceability was in REQUIREMENTS.md, now archived to `.planning/milestones/v1.0-REQUIREMENTS.md`). All 25 v1 requirements shipped and device-verified in v1.0 — see Validated.

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

**Current state (after v1.0 MVP, shipped & device-verified 2026-07-03):**
- The Sport HUD milestone shipped all 25 v1 requirements across 5 phases (25 plans), device-verified end-to-end on the real OPPO phone `3B164G01Y7L00000` + Rokid glasses `1901092544802583`.
- The repo now has its **first test suite** — 219 tests green (was "no tests" at project start) across the shared/phone/glasses modules; both APKs build clean (`assembleDebug` exit 0). Codebase ~14.7k Kotlin LOC (+~22.7k insertions over the milestone).
- Strava is the app's first external auth-required API; OkHttp + Gson are now used for the Strava client (legacy paths still use HttpURLConnection + org.json). Still no coroutines, no DI, no CI — the callback/Thread conventions held.
- **v1.x enhancements already shipped this session** (start of v1.x, not yet planned as a milestone): (1) whole-route bird's-eye page + 4-page swipeable glasses HUD (FULL→CORNER→SPORT→WHOLE_ROUTE, DPAD_LEFT/RIGHT swipe; `full` route flag preserves the birdview across reroutes — D4 proven on-device at 58km off-route); (2) off-route-at-start "Head to route" fix (defers reroute until the rider joins the imported route; device-verified "Head to route 59.9km" → "Joined route (nearest 32m)" + real turn-by-turn on rejoin).

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
- **Strava API**: OAuth 2.0 Authorization Code Grant (no PKCE — client_secret in APK via BuildConfig). Rate limits (verified live 2026-07-03, new-app defaults): 200 requests/15min + 2,000/day overall; 100 reads/15min + 1,000 reads/day. Tokens stored in EncryptedSharedPreferences.
- **No cloud dependencies**: Existing architecture avoids cloud services; Strava integration is the first external auth-required API
- **Navigation engine**: Continue using OSRM (free, no API key) for route navigation; Strava routes provide the waypoints

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Build-in activity recording instead of reading Strava live data | Strava API has no real-time activity endpoint; own recording reuses existing GPS pipeline | ✓ Good — v1.0 shipped; recording + summary + upload device-verified (real activity 19170698786 in feed) |
| Strava route import via GPX export API | GPX is well-supported, maps cleanly to OSRM waypoints | ✓ Good — v1.0; real routes (Milpitas 25.4km) imported + navigated on glasses |
| Add sport HUD as a new glasses layout mode | Fits existing layout-switching pattern, non-breaking addition | ✓ Good — v1.0; SPORT mode in the tap cycle, monochrome-green, ~1Hz, screencap-verified |
| OAuth flow on phone only, glasses receive processed data | Matches existing architecture — phone is the brain, glasses are the display | ✓ Good — v1.0; live OAuth on phone, redirect-URI bug caught & fixed on device (host callback→rokidhud) |
| GPX routes navigate via OSRM via-point routing (`waypoints=0;{last}` single leg) with follow-route fallback | Public OSRM builds 2-point URLs only; via-points restore real turn-by-turn for multi-waypoint Strava routes without spurious mid-route "Arrived" | ✓ Good — v1.0; single-leg confirmed on device, follow-route degrade confirmed off-route |
| Raw-Doppler moving-state hysteresis (0.7/0.3), 5-point speed MA removed; >50 m/s seam gate | On-device measurement showed the MA exit-lag leaked ~6.7m of jitter distance per stop; seam gate rejects GPS teleports | ✓ Good — v1.0; device-verified flat distance at stops, no teleport distance |
| Tokens in EncryptedSharedPreferences; client_secret in APK via BuildConfig (no PKCE) | Strava does not support PKCE; secure at-rest storage is the available mitigation | ✓ Good — v1.0; persistence + auto-rotation proven across app restart on device |

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
*Last updated: 2026-07-04 after v1.0 MVP milestone (shipped & device-verified 2026-07-03)*
