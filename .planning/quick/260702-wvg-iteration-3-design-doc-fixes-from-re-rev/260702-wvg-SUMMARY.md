---
phase: quick-260702-wvg
plan: 01
subsystem: planning-docs
tags: [design-review, roadmap, requirements, strava, navigation, hysteresis]

# Dependency graph
requires:
  - phase: quick-260702-w4n
    provides: iteration-2 design-doc fixes (3 blockers + 10 warnings) that this pass converges on
provides:
  - Design docs free of the two surviving waypoint-reuse contradictions (B1) — both ARCHITECTURE.md lines now name the new Phase-4 waypoint-accepting NavigationManager path
  - Phase 1 SC#3 testable on the actual OPPO test device (30-min screen-off); 2-hour run tracked as a STATE.md pre-release todo (B2)
  - Single ownership of the NavigationManager steps/currentStepIndex race fix — Phase 4 scope item (b); Phase 1 SC#6 covers only new recording components (W-a)
  - All three W-b anchors (STACK Installation, SUMMARY stack bullet, ARCHITECTURE build-order bullet) state OkHttp/logging-interceptor/Gson are already explicitly declared in phone/build.gradle.kts — verify versions only
  - REC-04 single governing 0.7/0.3 m/s hysteresis rule (nominal 0.5 m/s), cited identically by ROADMAP Phase 1 SC#2 (W-d)
  - PITFALLS Pitfall 7 prevention scoped to sport_state-only versioning; Pitfall 3 cites OsrmClient's real ≤500-point stride (W-e, W-f)
  - FEATURES auto-pause Alternative aligned with the moving-time-summary-only STATE decision (W-g)
  - AUTH-01 scope-confirmation parenthetical + extended Phase 3 research todo (W-h)
affects: [phase-1-activity-recording, phase-3-strava-auth, phase-4-route-import-navigation]

# Tech tracking
tech-stack:
  added: []
  patterns: []

key-files:
  created: []
  modified:
    - .planning/ROADMAP.md
    - .planning/REQUIREMENTS.md
    - .planning/STATE.md
    - .planning/research/ARCHITECTURE.md
    - .planning/research/PITFALLS.md
    - .planning/research/FEATURES.md
    - .planning/research/SUMMARY.md
    - .planning/research/STACK.md

key-decisions:
  - "NavigationManager data-race fix owned by Phase 4 (not Phase 1) — the race lives in code only Phase 4 modifies (recorded as new STATE.md decision row)"
  - "Phase 1 SC#3 targets the project's actual test device (OPPO Find X9 Ultra / ColorOS, 30-min run); 2-hour validation deferred to a pre-release STATE.md todo"

patterns-established: []

requirements-completed: []  # REC-04 and AUTH-01 in the plan frontmatter are requirement-TEXT corrections only — both remain unchecked/pending in REQUIREMENTS.md; no implementation delivered

# Metrics
duration: 4min
completed: 2026-07-03
---

# Quick Task 260702-wvg: Iteration-3 Design-Doc Fixes from Re-Review #2 Summary

**Applied the final-convergence review fixes: 2 blockers (stale waypoint-reuse claims, untestable 2-hour SC) and 7 warnings (race-fix dual ownership, phantom dependency instructions x3 docs, conflicting REC-04 thresholds, over-broad protocol-versioning scope, invented waypoint-density figure, stale auto-pause alternative, unconfirmed OAuth scopes) across 8 planning docs.**

## Performance

- **Duration:** ~4 min
- **Started:** 2026-07-03T06:50:16Z
- **Completed:** 2026-07-03T06:54:30Z
- **Tasks:** 2/2 completed
- **Files modified:** 8 (docs only; phone/build.gradle.kts verified untouched via git diff gate)

## Accomplishments

- **B1 closed:** Both surviving ARCHITECTURE.md lines claiming Strava waypoints reuse `startNavigation()` (Internal Boundaries table row, build-order Phase 4 wire bullet) now name the new waypoint-accepting NavigationManager path added in Phase 4, consistent with the locked via-point decision.
- **B2 closed:** ROADMAP Phase 1 SC#3 is now testable on the hardware the project has (OPPO Find X9 Ultra, 30-min screen-off); the 2-hour run became a STATE.md pre-release Pending Todo.
- **W-a:** Exactly one phase owns the steps/currentStepIndex race fix — Phase 4 scope item (b) carries it (confirmed surviving); Phase 1 SC#6 rewritten to cover only new recording components' thread-safety. New STATE.md decision row records the ownership.
- **W-b:** All three anchors (STACK.md Installation intro + code comment, SUMMARY.md stack bullet, ARCHITECTURE.md build-order bullet) now say OkHttp/logging-interceptor/Gson are already explicitly declared in phone/build.gradle.kts — verify versions only.
- **W-d:** REC-04 reduced to a single governing hysteresis rule (enter >0.7 m/s, exit <0.3 m/s, nominal 0.5 m/s) with the 5-point moving-average sentence preserved; ROADMAP SC#2 cites the identical rule and keeps the phantom-distance intent.
- **W-e/W-f:** PITFALLS Pitfall 7 prevention #1 scoped to sport_state-only versioning per ROADMAP Phase 1 SC#5; Pitfall 3 replaced the invented ~100-200 figure with OsrmClient's real ≤500-point stride.
- **W-g:** FEATURES auto-pause Alternative now matches the moving-time-summary-only STATE decision (elapsed on HUD, both in phone summary).
- **W-h:** AUTH-01 gained the scope-confirmation parenthetical; the STATE.md Phase 3 research todo now also covers the OAuth scope set.

## Task Commits

Each task was committed atomically:

1. **Task 1: Fix core planning docs — ROADMAP.md, REQUIREMENTS.md, STATE.md** - `1e1906c` (docs) — STATE.md edits applied but left uncommitted per orchestrator constraint
2. **Task 2: Fix research docs — ARCHITECTURE.md, PITFALLS.md, FEATURES.md, SUMMARY.md, STACK.md** - `09a102e` (docs)

**Plan metadata:** committed by orchestrator (STATE.md + this SUMMARY left uncommitted by design)

## Files Created/Modified

- `.planning/ROADMAP.md` - Phase 1 SC#2 hysteresis citation, SC#3 OPPO 30-min criterion, SC#6 Phase-4 race-fix deferral (committed `1e1906c`)
- `.planning/REQUIREMENTS.md` - REC-04 hysteresis-governs wording; AUTH-01 scope-confirmation parenthetical (committed `1e1906c`)
- `.planning/STATE.md` - New decision row (race fix owned by Phase 4), extended Strava-scope todo, new 2-hour pre-release todo, iteration-3 Last activity line (UNCOMMITTED — orchestrator commits)
- `.planning/research/ARCHITECTURE.md` - B1 waypoint-reuse corrections (2 lines) + W-b dependency-claim reword (committed `09a102e`)
- `.planning/research/PITFALLS.md` - Pitfall 7 sport_state-only versioning scope; Pitfall 3 ≤500-point stride figure (committed `09a102e`)
- `.planning/research/FEATURES.md` - Auto-pause Alternative aligned with summary-only moving-time decision (committed `09a102e`)
- `.planning/research/SUMMARY.md` - Stack bullet: deps already declared, verify versions only (committed `09a102e`)
- `.planning/research/STACK.md` - Installation section reframed as current declarations; only security-crypto + browser are new (committed `09a102e`)

## Decisions Made

None beyond the plan — all decisions were FINAL from the completed code-verified re-review; edits implemented exactly as specified.

## Deviations from Plan

None - plan executed exactly as written.

All VERIFY-only items confirmed unchanged:
- ROADMAP.md Phase 4 scope item (b) still carries "fix the known steps/currentStepIndex data race in code touched by this work"
- SUMMARY.md line 11 Executive Summary sentence untouched (per-file diff shows exactly 1 line changed — line 16 only)
- STACK.md Supporting Libraries table cells untouched (diff shows exactly 2 lines changed — Installation intro + code comment)
- Adjacent 0.5 m/s mentions (PITFALLS Pitfall 5, SUMMARY finding 5, ARCHITECTURE state-machine note, STATE decision row) untouched — consistent with the new nominal-0.5 wording

## Issues Encountered

None. Worktree merge-base differed from the dispatched base commit at startup; reset to `b8dd1de` per the worktree_branch_check instructions before any work.

## Known Stubs

None — docs-only change, no code or data wiring involved.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Design docs are internally consistent for Phase 1 planning: single hysteresis rule, testable SCs, single race-fix owner, no phantom dependency work.
- Phase 3 planners inherit two explicit research todos in STATE.md (rate limits + OAuth scope set).
- Phase 4 planners see the waypoint-accepting-path decision uniformly across ARCHITECTURE.md and ROADMAP.md.

## Self-Check: PASSED

- All 8 target files exist and contain the new anchors (T1-PASS, T2-PASS gates)
- Commits `1e1906c` and `09a102e` exist on `worktree-agent-a5f18064634b0cc27`
- `git diff --quiet phone/build.gradle.kts` passes (read-only evidence untouched)
- Cross-file checks: hysteresis rule identical in REQUIREMENTS + ROADMAP; 4 mentions of "waypoint-accepting NavigationManager path" in ARCHITECTURE.md (>= 3 required)

---
*Phase: quick-260702-wvg*
*Completed: 2026-07-03*
