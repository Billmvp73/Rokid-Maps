# Feature Research

**Domain:** Strava-integrated sport HUD for AR glasses (cycling + running)
**Researched:** 2026-07-02
**Confidence:** HIGH (verified against Garmin/Wahoo/Karoo product specs, Strava API docs, Engo/CYBERSIGHT AR sport glasses, and the existing codebase architecture)

## Feature Landscape

### Table Stakes (Users Expect These)

Features users assume exist. Missing these = product is not a usable sport app.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| **Activity recording** (elapsed time, distance, speed/pace) | Every cycling computer and running app records the basics. Without this, there's nothing to show on the HUD. | LOW | Reuses existing 1Hz GPS pipeline. Add elapsed time counter, distance accumulator, moving/stopped time. No new hardware needed. |
| **Strava OAuth 2.0 login** | Required for route import and activity upload. Users expect a single sign-in flow, not manual API keys. | MEDIUM | OAuth 2.0 with PKCE, refresh token management, token persistence across app restarts. Strava subscriber rate limit is generous (600 req/30min). |
| **Browse and import Strava saved routes** | Users come with routes already saved on Strava. The app must surface them and let the user pick one. | LOW | `GET /athlete/routes` lists routes, `GET /routes/{id}/export_gpx` downloads GPX. Simple list UI on phone. |
| **Convert Strava route (GPX) to navigable waypoints** | A GPX track isn't a turn-by-turn route. Must convert to waypoints compatible with OSRM routing. | LOW | Parse GPX `<trkpt>` coordinates, pass as waypoints to existing `OsrmClient.getRoute()`. OSRM already handles waypoint-to-turn-by-turn conversion. |
| **Navigate imported route on glasses** (turn-by-turn) | Users need to follow the route on the glasses HUD. Reuse existing navigation pipeline. | LOW | `NavigationManager.startNavigation()` already accepts waypoints. Strava routes feed directly in. Existing route line, maneuver arrows, and voice directions work unchanged. |
| **Real-time sport metrics on glasses HUD** (speed/pace, distance, elapsed time) | The HUD must show live metrics during activity. This is the core value prop of AR glasses over a handlebar phone mount. | LOW | New message type for sport state. Minor new rendering in `HudView` (text overlay for metrics). Speed already displayed; add pace (min/km, min/mi), moving time, distance. |
| **Activity summary on phone after completion** | Users want to see what they did: distance, duration, average speed/pace. | LOW | Simple summary screen on phone after activity ends. Display data already tracked during recording. |
| **Option to upload activity to Strava** | Users expect the activity to appear in their Strava feed automatically or via one tap. | MEDIUM | Generate GPX from recorded track points. `POST /uploads` with multipart/form-data. Poll for completion (`GET /uploads/{id}`). Handle failures gracefully. |

### Differentiators (Competitive Advantage)

Features that set this product apart from a phone-in-a-handlebar-mount or a dedicated bike computer.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| **AR HUD metrics overlay — no device glance needed** | Cyclists/runners keep eyes on the road. Speed, pace, distance float in their field of view. No phone or bike computer to glance at. This is the core reason AR glasses exist for sport. | LOW | Existing glasses render engine + new sport layout mode. Monochrome green display already handles high-contrast text. |
| **Sport HUD layout mode optimized for metrics** | A dedicated layout that prioritizes metrics (large pace/speed, elapsed time, distance) over map, optimized for cycling/running vs. car nav. | LOW | New `MapLayoutMode` entry. Existing 4-layout system (FULL_SCREEN, SMALL_CORNER, MINI_BOTTOM, MINI_SPLIT) can accommodate a fifth "SPORT" mode. |
| **Hands-free route import from Strava** | No manual GPX file transfer, no USB cable, no emailing files. Authenticate Strava, pick a route, start riding. | MEDIUM | OAuth + route list UI + GPX conversion. Entire flow is 3 taps on the phone. |
| **Phone stays in pocket during activity** | Set route on phone before leaving, then put phone away. All navigation and metrics are on glasses. Start/pause/stop could be handled later via touchpad on glasses. | MEDIUM | Existing architecture already supports this (phone is a background service). Add activity controls (start/pause/stop) that can be triggered from the glasses side. |
| **Strava activity upload without leaving the app** | Activity automatically (or with one confirm tap) shows up in Strava after completion. Users don't need to remember to export and upload manually. | MEDIUM | GPX upload to Strava API. Must handle upload queue, retry on failure, token expiry at upload time. |
| **Voice directions already work on glasses (existing)** | TTS navigation directions route to glasses via A2DP. Sport routes get the same voice guidance. | N/A (EXISTS) | Already built. Reuse for cycling/running routes. |
| **Speed limit + overspeed warning on glasses (existing)** | Useful for cyclists too — know when you're exceeding the road speed limit. | N/A (EXISTS) | Already built. Particularly useful for cyclists in urban areas. |
| **Turn alert overlay with auto-dismiss (existing)** | Large visual cue when approaching a turn. Works for cycling/running. | N/A (EXISTS) | Already built. Proximity-based (200m). Cycling/running speeds may need adjusted thresholds. |

### Anti-Features (Commonly Requested, Often Problematic)

Features likely to be requested that should be deferred or avoided.

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| **Heart rate / cadence / power meter sensor pairing** | Serious athletes train with these metrics. "All sport apps support sensors." | BLE sensor pairing adds significant complexity: device discovery, pairing, connection management, data parsing from multiple sensor profiles (HRM, CSC, Power). Each sensor type has different data formats and connection quirks. Already in Out of Scope. | Defer to v2. Start with GPS-only metrics (speed, distance, time) which the existing pipeline already provides. Sensors add marginal value for the initial HUD experience. |
| **Automatic activity detection** (auto-start when GPS detects movement) | "I shouldn't have to tap Start." | GPS false positives are common (driving a car to the ride start, walking to the trailhead). Auto-stop at traffic lights creates broken segments. Garmin/Wahoo have years of refinement here. | Let user manually start/pause/stop the activity. Add one-tap start from route selection screen. |
| **Automatic pause (auto-pause)** | "Pause when I stop at a light." | GPS-based auto-pause is unreliable for running (jogging in place at intersections). Cyclists at stoplights often don't want auto-pause. Every major app (Strava, Garmin) has had years of auto-pause complaints. | Manual pause via glasses touchpad or phone. Moving time vs. elapsed time are both tracked; display moving time as primary metric. |
| **Full Strava social feed** (feed, kudos, comments, clubs) | "I want to see what my friends are doing." | This is what the Strava app is for. Replicating the feed is a full app in itself, with infinite scroll, real-time updates, and notification management. It would bloat the phone app and distract from the HUD purpose. | Link to Strava app for social features. The phone app stays focused on route import + activity recording. |
| **Live Strava segments** | "Show me how I'm doing against my PR on this climb." | Strava's Live Segments API is available to API partners only (not public API). Even if accessible, it requires real-time comparison against leaderboard data, which Strava rate-limits heavily. Complex and fragile. | No live segments. Post-ride analysis in Strava. |
| **Built-in route planning on glasses** | "I want to create a route from scratch." | Route planning requires complex UI (map interaction, waypoint drag, elevation profile analysis). Existed apps (Komoot, RideWithGPS) are purpose-built for this. The glasses have no map interaction UI. | Users plan routes in Strava (or Komoot/RWGPS), import to app. The app is a navigation AND recording tool, not a route planner. |
| **Multi-day route / bikepacking mode** | "I need routes that span multiple days with overnight stops." | Multi-day routes require split-by-day logic, different battery strategies, separate activity files per day. Adds significant complexity to recording and navigation. | Defer. Single-day routes cover 95% of use cases for glasses-based navigation. |
| **Offline maps for route navigation** | "What if I have no signal?" | OSRM routing requires internet (public instance). Full offline maps require downloading vast tile sets. The existing tile cache is 50-500 MB, insufficient for full offline navigation. | Keep existing tile cache as a fallback (cached tiles from previous rides render). Route calculation still requires internet. Display cached map tiles when offline but show "no signal" for routing. |
| **Group ride / friend tracking** | "I want to see my friends on the map." | Real-time location sharing requires a server component (WebSocket/polling). Adds cloud dependency, contradicts existing no-cloud-services architecture. | Defer to far future. Not core to the solo HUD experience. |
| **Music/podcast controls on glasses** | "I want to control music without pulling out my phone." | Media controller requires AVRCP integration, media session management. Expands scope significantly. Separate concern from navigation/metrics. | Already in Out of Scope. User can use separate headphones or handle music on their phone. |

## Feature Dependencies

```
[Strava OAuth]
    └──requires──> [Strava Developer Application registration]

[Browse Strava Routes]
    └──requires──> [Strava OAuth]

[Import Strava Route]
    └──requires──> [Browse Strava Routes]
    └──requires──> [GPX-to-Waypoints conversion]

[Navigate Imported Route]
    └──requires──> [Import Strava Route]
    └──enhances──> [Existing NavigationManager] (reuse OSRM routing)
    └──enhances──> [Existing HudView] (reuse route line, maneuver arrows)

[Activity Recording]
    └──requires──> [Existing 1Hz GPS pipeline]
    └──requires──> [Elapsed time counter]
    └──requires──> [Distance accumulator]
    └──enhances──> [Speed/pace already displayed]

[Sport HUD Metrics Overlay]
    └──requires──> [Activity Recording]
    └──requires──> [New sport HUD layout mode in HudView]

[Activity Summary]
    └──requires──> [Activity Recording]

[Upload to Strava]
    └──requires──> [Activity Recording]
    └──requires──> [Strava OAuth] (refresh token may be needed after activity)
    └──requires──> [GPX generation from recorded track points]

[Start/Pause/Stop on Glasses]
    └──enhances──> [Activity Recording]
    └──requires──> [New BT message type for activity control]
```

### Dependency Notes

- **Import Strava Route** is the key dependency gateway. Once a route is imported, everything downstream (navigation, HUD display, recording) reuses existing infrastructure. This is the highest-leverage feature.
- **Activity Recording** and **Navigate Imported Route** are independent once the route is imported. A user could record an activity without navigating a route (free ride/run), or navigate a route without recording. They only converge for Strava upload (which needs both the route context and the recorded activity).
- **Upload to Strava** sits at the end of the chain and has the highest failure risk (token expiry mid-activity, network issues, Strava API errors). Design as an optional async step, not a blocking gate.
- **Strava OAuth** is a gateway dependency for ALL Strava API calls (route import and activity upload). Its reliability is critical.

## MVP Definition

### Launch With (v1)

The minimum for a sport HUD that is actually useful:

- [ ] **Strava OAuth login** — enables all Strava features.
- [ ] **Browse and import Strava routes** — core value prop: navigate Strava routes on glasses.
- [ ] **GPX-to-Waypoints conversion** — makes imported routes work with OSRM.
- [ ] **Navigate imported route on glasses** — reuse existing navigation pipeline. This works immediately once GPX is converted.
- [ ] **Activity recording** (elapsed time, distance, speed/pace) — the foundation of sport tracking.
- [ ] **Sport HUD metrics overlay on glasses** — displays pace/speed (large, prominent), elapsed time, distance. Differentiates from a simple phone mount.
- [ ] **Activity summary on phone** — shows distance, duration, average speed/pace after completion.
- [ ] **Optional upload to Strava** — one-tap upload so the activity appears in the user's Strava feed.

### Add After Validation (v1.x)

Features to add once the core loop (route -> navigate -> record -> upload) is solid:

- [ ] **Start/pause/stop activity from glasses touchpad** — reduces phone interaction during activity.
- [ ] **Moving time vs. elapsed time display** — shows both; user configures which is primary.
- [ ] **Lap/split tracking** — manual lap via glasses touchpad.
- [ ] **Imperial/metric unit toggle per sport** — existing unit toggle is global; cyclists and runners have different preferences.
- [ ] **Auto-stop recording when arrived at destination** — stops recording when navigation detects arrival.
- [ ] **Upload queue with retry** — queues failed uploads for retry when connectivity is restored.
- [ ] **Configurable data fields on sport HUD** — let users choose which 3-4 metrics to display (speed/pace, distance, time, plus one optional field).

### Future Consideration (v2+)

Features to defer until product-market fit is established:

- [ ] **BLE sensor pairing** (HR, cadence, power) — adds significant complexity. Revisit after confirming users want more than GPS-only metrics.
- [ ] **Auto-pause** — notoriously finicky. Implement as optional toggle, never default-on for running.
- [ ] **Structured workout display** (intervals, target pace zones) — requires workout import (Strava/FIT/TrainingPeaks).
- [ ] **Strava Beacon integration** — live location sharing. Requires constant internet and server infrastructure.
- [ ] **Route planning on phone** — elevation profile, waypoint editing, surface type detection. Komoot/RWGPS already do this well.
- [ ] **Offline route following with pre-downloaded tiles** — edge case, significant tile management complexity.

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| Strava OAuth login | HIGH | MEDIUM | P1 |
| Browse and import Strava routes | HIGH | LOW | P1 |
| GPX-to-Waypoints conversion | HIGH | LOW | P1 |
| Navigate imported route on glasses | HIGH | LOW | P1 |
| Activity recording (GPS-based) | HIGH | LOW | P1 |
| Sport HUD metrics overlay | HIGH | LOW | P1 |
| Activity summary on phone | MEDIUM | LOW | P1 |
| Upload to Strava | HIGH | MEDIUM | P1 |
| Start/pause/stop from glasses | MEDIUM | MEDIUM | P2 |
| Moving time vs. elapsed time | MEDIUM | LOW | P2 |
| Lap/split tracking | MEDIUM | LOW | P2 |
| Configurable data fields on HUD | MEDIUM | MEDIUM | P2 |
| Upload queue with retry | MEDIUM | LOW | P2 |
| BLE sensor pairing (HR, cadence, power) | HIGH | HIGH | P3 |
| Auto-pause | LOW | MEDIUM | P3 |
| Structured workout display | MEDIUM | HIGH | P3 |
| Route planning on phone | MEDIUM | HIGH | P3 |
| Offline navigation | LOW | HIGH | P3 |
| Group ride tracking | LOW | HIGH | P4 |

**Priority key:**
- P1: Must have for v1 launch
- P2: Should have, add in v1.x after core stabilizes
- P3: Nice to have for v2+, needs significant investment
- P4: Not building

## Competitor Feature Analysis

| Feature | Garmin Edge 840/1050 | Wahoo ELEMNT Bolt V3 | Engo 3 AR Glasses | Our Approach |
|---------|---------------------|----------------------|-------------------|--------------|
| GPS recording | Yes, built-in | Yes, built-in | No (relies on paired watch/phone) | Reuse existing 1Hz GPS pipeline |
| Turn-by-turn navigation | Yes, preloaded maps | Yes, offline maps | No | Reuse existing OSRM + Nominatim |
| Route import (GPX) | Yes, via USB/Connect app | Yes, via phone app | No | Via Strava API (GPX export) |
| Strava route import | Manual GPX sync | Via phone app | Not applicable | Direct Strava OAuth -> API -> route |
| Sport metrics display | On-device screen | On-device screen | AR HUD via watch | **AR HUD on glasses** (unique) |
| Speed/pace | Yes | Yes | Yes (from watch) | Display on glasses HUD |
| Heart rate / power / cadence | Yes (ANT+/BLE) | Yes (ANT+/BLE) | Yes (from watch) | **Deferred to v2** |
| Activity upload to Strava | Auto-sync via Connect app | Auto-sync via phone app | Via watch | Direct API upload from phone |
| Incident detection | Yes (Edge 1050 has built-in speaker) | Live Track | Not applicable | Not planned |
| On-device route planning | Yes (Edge 1050) | No (via phone app) | Not applicable | Not planned (Strava/phone for planning) |
| Safety / crash detection | Yes | With Live Track | Not applicable | Not planned |
| Built-in bell | Edge 1050 only | ACE/ROAM 3 only | Not applicable | Not planned |
| Glanceable HUD | On handlebar screen | On handlebar screen | In lens FOV | **In glasses FOV** (differentiator) |
| Weight | 79-162g | 84g | 38.5g | Phone in pocket + 75g glasses |
| Battery for sport use | 12-60h (device) | 20h (device) | 20h (glasses) | Phone battery (varies) + glasses battery |

**Our differentiators at a glance:**
1. **AR HUD overlay** -- metrics float in field of view, no glancing down at a handlebar computer
2. **Phone-as-brain architecture** -- no $400-600 bike computer needed; existing phone does all processing
3. **Strava-native route import** -- OAuth grab-and-go, no manual GPX file management
4. **Existing foundation** -- car navigation, tile caching, Bluetooth streaming, speed/speed-limit display are already built; sport features layer on top

**Our gaps vs. dedicated bike computers:**
1. No BLE sensor support (deferred)
2. No incident detection (out of scope)
3. Route planning requires external tool (by design)
4. Battery dependent on phone (mitigation: phone is already being used for other apps)

## Sources

### Competitor Products Analyzed
- Garmin Edge 840/850/1040/1050 series -- Outdoor Gear Lab (Nov 2025), Bicycling.com, Garmin firmware updates (Aug/Nov 2025)
- Wahoo ELEMNT Bolt V3 / Roam V3 / ACE -- Outdoor Gear Lab (Nov 2025), Cyql.app (May 2026), Upway.be (May 2026)
- Hammerhead Karoo 3 -- road.cc "Peak cycling computer" (Apr 2025)
- COROS DURA -- Upway.be (May 2026)
- Engo 2 / Engo 3 -- Yahoo Tech / Android Central reviews, ENGO official site
- CYBERSIGHT ZENITH -- Wedbush press release (Oct 2025)
- QIDI Vida -- Kickstarter

### Competitor App Products Analyzed
- Strava (mobile app + subscription features) -- Strava Help Center docs, Google Play listing
- Komoot -- SaasHub comparison, Flat Iron Bike review
- RideWithGPS -- SaasHub comparison, UK Velo sync docs
- Zwift running HUD -- Zwift Support pages
- o-synce SCREENEYE X -- Official product page
- DigiHUD Speedometer -- Google Play

### API / Platform Docs
- Strava API (developers.strava.com) -- upload endpoint docs, OAuth scopes, GPX format requirements
- GitHub: awesome-rokid -- curated list of Rokid sport HUD apps (hubu, rokid-Strava)

### Industry Analysis
- road.cc "Have we reached peak cycling computer?" (Apr 2025) -- feature maturity analysis
- Cyclist.co.uk "Bike computer vs phone" (2025) -- table stakes vs. differentiation
- ROUVY "Best Cycling Apps of 2026" (2026)

---
*Feature research for: Strava-integrated sport HUD on Rokid AR glasses*
*Researched: 2026-07-02*
