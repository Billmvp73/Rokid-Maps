# Roadmap: Rokid HUD Maps -- Sport HUD

**Core Value:** Cyclists and runners see their route and live performance metrics floating in their field of view, keeping their eyes on the road and their phone in their pocket.

**Granularity:** Standard
**Project Mode:** mvp

## Phases

- [x] **Phase 1: Activity Recording Engine** - Phone records GPS activity with live metrics and robust background operation (completed 2026-07-03)
- [ ] **Phase 2: Glasses Sport HUD** - Glasses display real-time sport metrics during activity recording
- [ ] **Phase 3: Strava Authentication** - User authenticates with Strava; tokens managed securely
- [ ] **Phase 4: Strava Route Import + Navigation** - User imports Strava routes and navigates them on glasses
- [ ] **Phase 5: Activity Summary + Strava Upload** - User views activity summaries and uploads completed activities to Strava

## Phase Details

### Phase 1: Activity Recording Engine
**Goal:** Phone records GPS activity with live metrics and robust background operation
**Mode:** mvp
**Depends on:** Nothing
**Requirements:** REC-01, REC-02, REC-03, REC-04, REC-05, REC-06, REC-07
**Success Criteria** (what must be TRUE):
  1. `sport_state` protocol message type is defined in shared module (Messages.kt, ProtocolCodec.kt) and broadcast by HudStreamingService at ~1Hz with elapsed time, distance, speed/pace, moving time, and recording state; broadcast occurs while recording and is verifiable via logs
  2. Distance accumulation excludes GPS drift: accuracy >20m points are rejected from distance calculation; moving-state hysteresis (enter moving above 0.7 m/s, exit below 0.3 m/s — nominal 0.5 m/s threshold per REC-04) governs moving-distance accumulation, so no phantom distance accrues while stopped
  3. Recording survives phone screen-off for at least 30 minutes of continuous tracking on the project's test device (OPPO Find X9 Ultra / ColorOS — an aggressive battery-management OEM); a 2-hour pre-release validation run is tracked in STATE.md Pending Todos
  4. Session data (track points + computed metrics) persists as local JSON on recording stop and survives app restart, with a periodic checkpoint (every 60s or 500 points) for crash resilience
  5. The new `sport_state` message carries a protocol version field (`"v": 1`); versioning of the existing message set is deferred (explicitly not Phase 1 scope)
  6. New recording components own their mutable state with explicit thread-safety (ActivitySessionManager confines its state; @Volatile/synchronized where shared with the service); the pre-existing NavigationManager steps/currentStepIndex race is fixed in Phase 4 (scope item b), where that class is rebuilt
  7. Unit tests exist and pass (first tests in the repo; JUnit in shared/phone modules) covering ProtocolCodec `sport_state` encode/decode round-trip and ActivitySessionManager state machine transitions (IDLE → TRACKING → FINISHED) + metric computation (distance, pace, elapsed time)
**Plans:** 7/7 plans complete

Plans:
- [x] 01-01-PLAN.md — Test infrastructure (both modules) + recording data contracts + sport_state protocol codec with tests
- [x] 01-02-PLAN.md — ActivitySessionManager: state machine, accuracy gate, hysteresis + 5-pt speed MA, metrics (JVM-tested)
- [x] 01-03-PLAN.md — SessionStore: atomic JSON persistence, 60s/500pt checkpoints, orphan recovery (<10-min resume rule)
- [x] 01-04-PLAN.md — HudStreamingService integration: LocationConsumer fan-out, recording binder API, 1Hz sport_state broadcast, live notification
- [x] 01-05-PLAN.md — Recording UI: MainActivity card, confirm-to-stop, live metrics, battery-exemption + background-location onboarding
- [x] 01-06-PLAN.md — RecordingWatchdog (staleness + AlarmManager chain) + manifest permissions/receiver
- [x] 01-07-PLAN.md — On-device verification: mock-GPS pipeline checks, kill/restart recovery, 30-min screen-off OPPO gate

### Phase 2: Glasses Sport HUD
**Goal:** Glasses display real-time sport metrics during activity recording
**Mode:** mvp
**Depends on:** Phase 1
**Requirements:** HUD-01, HUD-02, HUD-03, HUD-04
**Success Criteria** (what must be TRUE):
  1. User can cycle the glasses layout via tap to reach the new SPORT mode: tap cycle is Full → Corner → Sport → Full
  2. Elapsed time, current speed/pace, and distance traveled display on glasses and update in real-time (~1Hz)
  3. Sport HUD uses monochrome green rendering consistent with existing HUD visual style
  4. Phone-set Mini modes (Mini Strip, Mini Split) are unchanged: glasses tap from a Mini mode returns to Full (existing behavior preserved)
**Plans:** TBD
**UI hint:** yes

### Phase 3: Strava Authentication
**Goal:** User authenticates with Strava; tokens managed securely
**Mode:** mvp
**Depends on:** Nothing
**Requirements:** AUTH-01, AUTH-02, AUTH-03
**Delivers:** The OkHttp Strava API client + token authenticator, proven via an authenticated GET /athlete call.
**Success Criteria** (what must be TRUE):
  1. User can tap "Connect Strava" and complete OAuth 2.0 login via the phone browser
  2. After successful login, user returns to the app and sees confirmation of their Strava connection
  3. Authentication persists across app restarts (no re-login required)
  4. Access tokens auto-refresh transparently -- API calls work even after token expiry
**Plans:** TBD

### Phase 4: Strava Route Import + Navigation
**Goal:** User imports Strava routes and navigates them on glasses
**Mode:** mvp
**Depends on:** Phase 3
**Requirements:** RIMP-01, RIMP-02, RIMP-03, RIMP-04, NAVV-01, NAVV-02, NAVV-03
**GPX navigation strategy (decided 2026-07-02):** Downsample imported GPX with Douglas-Peucker (epsilon ~10-20m, target ≤200 points), then call OSRM /route with ALL downsampled points as via-waypoints (steps=true) with `waypoints=0;{last}` so intermediate points are silent via points (single leg — without this, every via point splits a leg and emits spurious arrive/depart steps) to get real turn-by-turn steps and a road-snapped route. Graceful fallback: if OSRM via-point routing fails or is unavailable, navigate the raw GPX waypoints in follow-route mode (route line + distance-to-next-waypoint; no turn arrows or TTS). Complexity: MEDIUM-HIGH. Scope: (a) OsrmClient multi-waypoint support with silent-via handling (`waypoints=0;{last}`) and a defensive filter dropping non-final zero-distance arrive steps (currently builds a 2-point URL only), (b) a new waypoint-accepting NavigationManager path (startNavigation() currently accepts only a destination) — fix the known steps/currentStepIndex data race in code touched by this work, (c) Douglas-Peucker downsampling, (d) follow-route fallback mode. URL-length note: ~200 coordinates ≈ 4KB GET URL — acceptable; if OSRM rejects the request, reduce the via-point count.
**Success Criteria** (what must be TRUE):
  1. User can browse their Strava routes (name, distance, elevation) in the phone app
  2. User can select a route, import it, and preview the route line on the phone map
  3. User can start navigation on the imported route -- route line and guidance appear on the glasses
  4. Off-route detection and auto-recalculation work correctly for Strava imported routes
  5. Winding and switchback-style routes display correctly without direction reversal (butterfly behavior avoided)
**Plans:** TBD
**UI hint:** yes

### Phase 5: Activity Summary + Strava Upload
**Goal:** User views activity summaries and uploads completed activities to Strava
**Mode:** mvp
**Depends on:** Phase 1, Phase 3, Phase 4 (Phase 4 builds route endpoints (list, GPX export) on the Phase 3 client; UPL-01 and UPL-04 can proceed after Phase 1 alone)
**Requirements:** UPL-01, UPL-02, UPL-03, UPL-04
**Success Criteria** (what must be TRUE):
  1. After recording stops, user sees an activity summary screen with total time, distance, and average speed/pace
  2. User can upload the activity to Strava with one tap and sees upload progress/confirmation
  3. Past recorded activities are listed and viewable on the phone at any time
  4. If upload fails, activity data remains available locally for retry (data is never deleted before upload succeeds)
**Plans:** TBD
**UI hint:** yes

## Progress

**Execution Order:**
Phases execute in numeric order: 1 -> 2 -> 3 -> 4 -> 5

Note: Phases 1 and 3 share no dependencies and could proceed in either order. Phase 4 depends on Phase 3. Phase 5 depends on both Phase 1 (recording data) and Phase 3 (Strava auth tokens).

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Activity Recording Engine | 7/7 | Complete   | 2026-07-03 |
| 2. Glasses Sport HUD | 0/0 | Not started | - |
| 3. Strava Authentication | 0/0 | Not started | - |
| 4. Strava Route Import + Navigation | 0/0 | Not started | - |
| 5. Activity Summary + Strava Upload | 0/0 | Not started | - |
