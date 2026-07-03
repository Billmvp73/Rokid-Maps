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
**Last activity:** 2026-07-02 -- Roadmap created

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

### Key Constraints

- No cloud services -- existing architecture avoids cloud dependencies
- Android only (phone + Rokid glasses)
- Existing codebase has no tests, no coroutines, no DI
- Strava OAuth has no PKCE support (client_secret must be in APK)
- OEM battery optimization is the top reliability risk for activity recording

### Pending Todos

- Douglas-Peucker epsilon (10m vs 20m) needs empirical testing with actual user routes
- OEM test devices: decide which phone brands to test battery behavior on (Samsung, Xiaomi, Pixel minimum per ROADMAP)
- Sport HUD layout exact design: what metric arrangement works best on monochrome green display?
- Strava OAuth client_secret management: embedded-in-APK risk accepted (no BFF server per project constraints)

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

## Session Continuity

**Last session:** 2026-07-02 -- Roadmap and state files created
**Stopped at:** All 23 v1 requirements mapped across 5 phases. Awaiting approval.
**Resume file:** None
