---
gsd_state_version: '1.0'
status: planning
progress:
  total_phases: 5
  completed_phases: 0
  total_plans: 0
  completed_plans: 0
  percent: 0
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-07-02)

**Core value:** Cyclists and runners see their route and live performance metrics floating in their field of view, keeping their eyes on the road and their phone in their pocket.
**Current focus:** Phase 1 -- Activity Recording Engine

## Current Position

**Phase:** 0 of 5 (not yet started)
**Plan:** None
**Status:** Ready to plan
**Last activity:** 2026-07-02 -- Design-doc review fixes applied to planning docs (12 blockers)

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
- Verify Strava rate-limit figures at developers.strava.com during Phase 3 research

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

## Session Continuity

**Last session:** 2026-07-02 -- Design-doc review fixes applied
**Stopped at:** All 25 v1 requirements mapped across 5 phases. Awaiting approval.
**Resume file:** None
