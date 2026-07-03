---
phase: quick-260702-v6h
plan: 01
subsystem: planning-docs
tags: [strava, osrm, via-waypoints, douglas-peucker, sport-state, oauth, pkce]

requires: []
provides:
  - Corrected planning docs reflecting verified code facts (2-point OsrmClient, destination-only startNavigation, Full<->Corner tap cycle, no Strava PKCE)
  - REC-06 (JSON persistence + checkpoint) and REC-07 (sport_state protocol + 1Hz broadcast) defined and mapped to Phase 1
  - GPX via-point routing strategy recorded in ROADMAP Phase 4 and STATE decisions
  - Phase-numbering banners in all three research docs
affects: [phase-1-recording, phase-2-glasses-hud, phase-3-auth, phase-4-import-nav, phase-5-upload]

tech-stack:
  added: []
  patterns: []

key-files:
  created: []
  modified:
    - .planning/REQUIREMENTS.md
    - .planning/ROADMAP.md
    - .planning/STATE.md
    - .planning/PROJECT.md
    - .planning/research/ARCHITECTURE.md
    - .planning/research/FEATURES.md
    - .planning/research/PITFALLS.md
    - .planning/research/SUMMARY.md

key-decisions:
  - "GPX routes navigate via OSRM via-point routing (Douglas-Peucker downsample, all points as via-waypoints, steps=true) with follow-route fallback"
  - "Protocol split: sport_state message + 1Hz broadcast ship in Phase 1 (REC-07); glasses consumption ships in Phase 2 (HUD-02)"
  - "PAUSED session state deferred to v2; v1 machine is IDLE -> TRACKING -> FINISHED"
  - "Moving time consumer in v1 = activity summary only (UPL-01), not the sport HUD"

patterns-established: []

requirements-completed: []  # Doc-fix task: NAVV-02, REC-05..07, HUD-02, HUD-03, UPL-01, UPL-03 redefined, not implemented

duration: 8min
completed: 2026-07-03
---

# Quick Task 260702-v6h: Fix Design-Doc Review Blockers Summary

**All 12 code-verified design-review fixes applied or verified across 8 planning docs: via-point GPX routing strategy, REC-06/REC-07 requirements, real tap-cycle criteria, PAUSED-to-v2 deferral, phase-numbering banners, and rate-limit verification notes**

## Performance

- **Duration:** 8 min
- **Started:** 2026-07-03T05:46:51Z
- **Completed:** 2026-07-03T05:54:51Z
- **Tasks:** 2
- **Files modified:** 8

## Fix-by-Fix Disposition

| # | Fix | Disposition | Files touched |
|---|-----|-------------|---------------|
| 1 | GPX via-point routing strategy (replace "waypoint reuse works unchanged" / "skips OSRM" claims) | **Edited** | REQUIREMENTS.md (NAVV-02), ROADMAP.md (Phase 4 strategy note), STATE.md (decision row), research/ARCHITECTURE.md (Flow 2 diagram + architecture decision + Pattern 4), research/FEATURES.md (2 table-stakes rows), research/PITFALLS.md (Pitfall 3 #5) |
| 2 | REC-06 (JSON persistence + checkpoint) and REC-07 (sport_state protocol + 1Hz broadcast) added; UPL-03/HUD-02 cross-referenced | **Edited** | REQUIREMENTS.md (REC-06/REC-07 + traceability + coverage, HUD-02, UPL-03), ROADMAP.md (Phase 1 requirements line, SC#1 broadcast criterion, SC#4 persistence), STATE.md (protocol-split decision row) |
| 3 | PKCE / rate-limit corrections (no "OAuth 2.0 with PKCE", no "600 requests/30min") | **Verified already-applied** (commit ae4fa84) | PROJECT.md Strava context + constraint lines, research/FEATURES.md OAuth row; sweep confirms zero stale occurrences in all 8 docs |
| 4 | Real tap cycle (Full -> Corner -> Sport -> Full; Mini modes phone-set, tap returns to Full) | **Half verified / half edited** | REQUIREMENTS.md HUD-03 verified already-correct (no edit); ROADMAP.md Phase 2 SC#1 + SC#4 edited |
| 5 | Phase-numbering banners in research docs | **Half edited / half verified** | Banners added to research/ARCHITECTURE.md, research/PITFALLS.md (adapted wording — "Phase to address" lines already renumbered), research/SUMMARY.md; all seven "Phase to address" lines + Pitfall-to-Phase Mapping table verified already using final roadmap numbering |
| 6 | OkHttp + Gson explicit declaration wording | **Mostly verified / one edit** | research/ARCHITECTURE.md build-order Phase 4 bullet edited; component table, Integration Points, Internal Boundaries verified already-applied |
| 7 | PAUSED deferred to v2 in every state-machine reference | **Edited** | research/ARCHITECTURE.md (industry-pattern bullet, diagram line width-preserved, state-machine Note, build-order Phase 2 bullet), STATE.md (deferral decision row) |
| 8 | UPL-01 adds moving time to activity summary | **Edited** | REQUIREMENTS.md UPL-01 |
| 9 | Rate-limit verify-at-developers.strava.com note | **Edited** | PROJECT.md Strava API constraint (plus reads/writes plural normalization matching CLAUDE.md), STATE.md todo |
| 10 | Pending Todos replaced with four review-derived items | **Edited** | STATE.md (dropped settled client_secret bullet; added Phase-4 epsilon, Phase-1 OEM verification, Phase-2 HUD layout, Phase-3 rate-limit verification) |
| 11 | REC-05 full battery-defense scope + unit-test success criterion | **Edited** | REQUIREMENTS.md REC-05 (GPS-staleness detection), ROADMAP.md Phase 1 SC#7 (ProtocolCodec round-trip + ActivitySessionManager tests) |
| 12 | Pitfall 5 haversine attribution names ActivitySessionManager and OverpassSpeedLimitClient | **Mostly verified / one edit** | research/PITFALLS.md Pitfall 5 final sentence edited; preceding proximity-only sentences verified already-applied |

Also applied (plan "Also" items): STATE.md last-activity/session lines updated, 23 -> 25 requirement count consistency.

## Accomplishments

- REQUIREMENTS.md now defines 25 v1 requirements (REC-06/REC-07 added) with consistent traceability and coverage counts; NAVV-02/HUD-02/UPL-01/UPL-03/REC-05 carry review wording
- ROADMAP.md Phase 1 owns protocol + persistence + first unit tests (REC-01..REC-07); Phase 2 SC match verified toggleLayout() behavior; Phase 4 records the via-point strategy with MEDIUM-HIGH complexity and 4-item scope
- STATE.md carries 4 new decision rows (10 total), 4 review-derived todos, and updated session lines
- No planning doc claims waypoint reuse works unchanged, OSRM is skipped for GPX routes, Strava supports PKCE, or a 5-mode tap cycle exists

## Task Commits

1. **Task 1: Fix core planning docs (REQUIREMENTS, ROADMAP, STATE, PROJECT)** - `547ca3b` (docs) — STATE.md edits applied but left uncommitted per orchestrator constraint
2. **Task 2: Fix research docs (ARCHITECTURE, FEATURES, PITFALLS, SUMMARY)** - `d2dcddc` (docs)

## Decisions Made

None beyond the plan — all 12 fixes were FINAL review decisions applied exactly as specified.

## Deviations from Plan

**Commit routing only (no content deviations):** The plan's Task 1 `<files>` includes .planning/STATE.md, but the orchestrator constraint directs that STATE.md is committed downstream with the docs commit. STATE.md edits (4 decision rows, 4 todos, activity/session/count lines) are fully applied in the working tree and excluded from the `547ca3b` task commit. All 12 fixes applied or verified as written; none skipped, none re-litigated.

## Verification Results

- Task 1 gate: **TASK1-OK**
- Task 2 gate: **TASK2-OK**
- Cross-doc consistency sweep (8 target docs): clean — no `OAuth 2.0 with PKCE`, `600 request`, `work unchanged`, `skips OSRM call`, or `Skip OSRM route calculation entirely`
- Spot checks: ROADMAP Phase 4 strategy note present; STATE.md Decisions table has 10 data rows; REQUIREMENTS traceability lists 25 rows
- `git status`: changes confined to .planning/ (only STATE.md + this SUMMARY uncommitted, by design); no source code or CLAUDE.md changes

## Issues Encountered

None. The plan's verify commands reference the main repo path (`cd /Users/bilhuang/Documents/rokid-maps`); gates were run from the worktree root instead so they verify the tree being edited.

## Next Phase Readiness

Planning docs are consistent with verified code facts and the final 5-phase roadmap — Phase 1 planning can proceed without false claims propagating into phase plans.

## Self-Check: PASSED

All 8 modified docs and the SUMMARY exist on disk; commits 547ca3b and d2dcddc verified in git log; working tree contains only the intentionally uncommitted STATE.md and this SUMMARY.

---
*Phase: quick-260702-v6h*
*Completed: 2026-07-03*
