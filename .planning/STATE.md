---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: executing
last_updated: "2026-07-04T00:28:07.778Z"
last_activity: 2026-07-04
progress:
  total_phases: 5
  completed_phases: 2
  total_plans: 21
  completed_plans: 14
  percent: 40
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-07-02)

**Core value:** Cyclists and runners see their route and live performance metrics floating in their field of view, keeping their eyes on the road and their phone in their pocket.
**Current focus:** Phase 4 — Strava Route Import + Navigation

## Current Position

Phase: 4 (Strava Route Import + Navigation) — EXECUTING
Plan: 1 of 6
**Phase:** 0 of 5 (not yet started)
**Plan:** None
**Status:** Executing Phase 4
**Last activity:** 2026-07-04

**Progress:** [                    ] 0%

## Performance Metrics

*No plans completed yet.*

## Accumulated Context

### Decisions

| Decision | Rationale | Date |
|----------|-----------|------|
| Protocol messages folded into Phase 1 | No standalone protocol phase; protocol types are an implementation detail within the recording engine. Avoids a phase with zero mapped requirements. | 2026-07-02 |
| Phases 1 and 3 are independent | Recording engine and Strava auth share no dependencies. Execution order is flexible, though UI/UX may inform the sequence chosen. | 2026-07-02 |
| Battery defenses built into Phase 1 | Per PITFALLS.md: recording reliability must be proven before upload (Phase 5) is built. | 2026-07-02 |
| Douglas-Peucker downsampling in Phase 4 | Required for GPX route-to-waypoint conversion. Prevents butterfly behavior on loops and switchbacks. | 2026-07-02 |
| Local persistence before upload in Phase 5 | Per PITFALLS.md: never delete local session data before upload succeeds. | 2026-07-02 |
| Strava OAuth is highest-risk phase | Per PITFALLS.md: budget extra time for debugging redirect URI mismatches, client_secret handling, and Android deep link quirks. | 2026-07-02 |
| GPX routes navigate via OSRM via-point routing: Douglas-Peucker downsample (epsilon ~10-20m, ≤200 points), then OSRM /route with all points as via-waypoints (steps=true); fallback to follow-route mode (route line + distance to next waypoint, no turn instructions/TTS) when OSRM fails | Verified code facts: NavigationManager.startNavigation() accepts only a destination and OsrmClient builds 2-point URLs — the research claim that waypoint reuse works unchanged was false. Via-points restore real turn-by-turn for Strava routes | 2026-07-02 |
| Protocol split: sport_state message type + phone-side ~1Hz broadcast ship in Phase 1 (REC-07); glasses consumption ships in Phase 2 (HUD-02) | Phase 1 is log-verifiable without glasses work; Phase 2 consumes an already-proven message | 2026-07-02 |
| PAUSED session state deferred to v2; v1 machine is IDLE → TRACKING → FINISHED (with reset) | Moving time is computed from a speed threshold (<0.5 m/s = stopped), not from a paused state | 2026-07-02 |
| Moving time consumer in v1 = activity summary only (UPL-01), not the sport HUD | Keeps the SPORT layout simple; sport_state still carries moving time so the summary pipeline has it | 2026-07-02 |
| No activity_summary glasses message in v1; activity summary is phone-side UI (UPL-01). sport_state carries session state as a field | Keeps glasses protocol surface minimal; nothing consumes a summary message on glasses in v1 | 2026-07-03 |
| sport_state carries protocol version field v:1; full-protocol version negotiation deferred | Bounds Phase 1 scope; existing message set unchanged for backward compatibility | 2026-07-03 |
| NavigationManager data-race fix owned by Phase 4 (not Phase 1) | The race lives in NavigationManager, which only Phase 4 modifies; fixing it in Phase 1 widens blast radius with no tests covering navigation | 2026-07-03 |
| Via-point routing must pass waypoints=0;{last} (silent via points, single leg) | Without it every intermediate via splits a leg and emits arrive/depart step pairs — ~199 spurious mid-route 'Arrived!' banners/TTS on a 200-point route (verified against live OSRM and the OsrmClient/MainActivity/HudView code paths); defensive filter drops non-final zero-distance arrive steps | 2026-07-03 |
| Hysteresis evaluates raw Doppler speed; the REC-04 5-point moving average was removed | On-device (OPPO, 01-07 Part B): MA exit-lag kept moving-state alive ~3 ticks per stop, leaking a measured 6.67m of jitter distance per stop; raw thresholds exit on the first sub-0.3 fix (zero leak, unit-tested) | 2026-07-03 |
| Accepted-pair hops implying >50 m/s are track seams (no distance, anchor advances) | On-device (01-07): a mock→real provider teleport (60.3km in 1s) landed inside moving-state and counted as distance; the plausibility gate rejects impossible motion while preserving PITFALLS #5 reacquisition semantics (10s/100m gaps still count, unit-tested) | 2026-07-03 |

### Key Constraints

- No cloud services -- existing architecture avoids cloud dependencies
- Android only (phone + Rokid glasses)
- Existing codebase has no tests, no coroutines, no DI
- Strava OAuth has no PKCE support (client_secret must be in APK)
- OEM battery optimization is the top reliability risk for activity recording

### Pending Todos

- Douglas-Peucker epsilon (10m vs 20m) — decide empirically in Phase 4 with a real Strava route
- OEM battery-optimization verification for Phase 1 SC — test on the user's actual connected phone (30-min screen-off recording)
- Sport HUD layout design (metric arrangement on monochrome green) — resolve during Phase 2 discuss/UI-spec
- ~~Verify Strava rate-limit figures AND OAuth scope set~~ RESOLVED (03-RESEARCH, live 2026-07-03): new-app limits 200/15min + 2,000/day overall, 100 reads/15min + 1,000 reads/day; read_all confirmed required for private routes; scopes comma-delimited
- Validate on the OPPO test phone whether ACCESS_BACKGROUND_LOCATION is actually required — the existing app already records screen-off via its location-type foreground service; keep the permission only if device testing shows it is needed
- Pre-release: 2-hour screen-off recording validation on the OPPO test phone
- Phase 1 planning: REC-04's 5-point moving average applies to GPS speed (authoritative); PITFALLS #5's position-averaging suggestion is superseded — do not implement both
- Phase 1 planning: decide whether moving-time uses the same 0.7/0.3 hysteresis as moving-distance (recommended: yes, one moving-state flag drives both) — current docs leave moving-time at a flat 0.5 m/s
- Phase 3 planning: AUTH-03 proactive token refresh (before expiry) is authoritative; ARCHITECTURE Pattern 3's reactive 401 Authenticator is the fallback layer, not the primary mechanism
- ~~Phase 4: OSRM profile choice~~ RESOLVED (04-RESEARCH, live-verified): router.project-osrm.org serves ONLY the car profile and silently ignores the profile path segment (driving/cycling/foot return byte-identical routes) — so profile is moot; use `driving`; follow-route fallback is the only real bike-path mitigation. FOSSGIS bike/foot host deferred to v1.x.

### Blockers / Concerns

See: `.planning/research/PITFALLS.md` for detailed analysis and prevention strategies.

### Risk Register

| ID | Risk | Severity | Phase | Status |
|----|------|----------|-------|--------|
| R1 | Strava OAuth redirect URI/client_secret debugging blocks integration | HIGH | Phase 3 | Not started |
| R2 | OEM battery optimization kills GPS mid-activity | HIGH | Phase 1 | Not started |
| R3 | GPX waypoint density breaks NavigationManager (butterfly loops) | MEDIUM | Phase 4 | Not started |
| R4 | Activity upload data loss (async failure, token expiry, duplicates) | HIGH | Phase 5 | Not started |
| R5 | GPS noise inflates distance (phantom distance at stops) | MEDIUM | Phase 1 | Not started |
| R6 | Callback-heavy codebase fragility when adding stateful components | MEDIUM | Phase 1+ | Not started |
| R7 | Protocol drift between phone and glasses without version negotiation | MEDIUM | Phase 1 | Not started |
| R8 | ACCESS_BACKGROUND_LOCATION missing on Android 10+ | MEDIUM | Phase 1 | Not started |
| R9 | No test infrastructure makes regression detection impossible | HIGH | Phase 1+ | Not started |

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260702-v6h | Fix design-doc review blockers in .planning docs | 2026-07-03 | d2dcddc | [260702-v6h-fix-design-doc-review-blockers-in-planni](./quick/260702-v6h-fix-design-doc-review-blockers-in-planni/) |
| 260702-w4n | Iteration-2 design-doc fixes from re-review (3 blockers + 10 warnings) | 2026-07-03 | 480d1e5 | [260702-w4n-iteration-2-design-doc-fixes-from-re-rev](./quick/260702-w4n-iteration-2-design-doc-fixes-from-re-rev/) |
| 260702-wvg | Iteration-3 design-doc fixes from re-review #2 (2 blockers + 7 warnings) | 2026-07-03 | 09a102e | [260702-wvg-iteration-3-design-doc-fixes-from-re-rev](./quick/260702-wvg-iteration-3-design-doc-fixes-from-re-rev/) |
| 260703-05e | Iteration-4 design-doc fixes from re-review #3 (2 blockers + 7 warnings) | 2026-07-03 | 9d56da6 | [260703-05e-iteration-4-design-doc-fixes-from-re-rev](./quick/260703-05e-iteration-4-design-doc-fixes-from-re-rev/) |

## Session Continuity

**Last session:** 2026-07-03T20:51:59.098Z
**Stopped at:** Phase 2 verified 14/14 (device spots folded forward); advancing to Phase 3
**Resume file:** None
