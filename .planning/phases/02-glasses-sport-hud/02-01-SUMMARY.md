---
phase: 02-glasses-sport-hud
plan: 01
subsystem: glasses-hud
tags: [kotlin, junit4, sport-hud, hudstate, formatters, tdd]

# Dependency graph
requires:
  - phase: 01 (phone sport engine)
    provides: SportStateMessage data class + ProtocolCodec sport_state codec (shared module) and the device-proven 1Hz phone broadcast
provides:
  - MapLayoutMode.SPORT as a first-class layout mode in the 3-way tap cycle (FULL_SCREEN -> SMALL_CORNER -> SPORT -> FULL_SCREEN)
  - SportFormat pure-Kotlin formatter object (elapsed H:MM:SS, pace M:SS with --:-- sentinel, one-decimal speed, unit labels, moving dot)
  - HudState sport seams — applySportState(msg, nowMs) mapping + sportDisplayMode(nowMs) staleness precedence ladder, both with injected clocks
  - Glasses module test infrastructure (first testImplementation dependency; first 14 green JVM tests)
  - HudView SPORT dispatch branch to empty drawSportLayout stub + "[ SPORT ]" mode indicator
affects: [02-02 bluetooth wiring, 02-03 sport rendering, 02-04 device verification]

# Tech tracking
tech-stack:
  added: ["junit:junit:4.13.2 (glasses testImplementation — module's first test dependency, same pinned coordinate as shared/phone)"]
  patterns:
    - "Injected nowMs: HudState never calls SystemClock; call sites supply the clock so staleness logic is pure and JVM-testable"
    - "Pure-Kotlin formatter object (no android.*) mirroring the SessionModels.kt convention"

key-files:
  created:
    - glasses/src/main/java/com/rokid/hud/glasses/SportFormat.kt
    - glasses/src/test/java/com/rokid/hud/glasses/SportFormatTest.kt
    - glasses/src/test/java/com/rokid/hud/glasses/HudStateTest.kt
  modified:
    - glasses/build.gradle.kts
    - glasses/src/main/java/com/rokid/hud/glasses/HudState.kt
    - glasses/src/main/java/com/rokid/hud/glasses/HudView.kt

key-decisions:
  - "MOVING_DOT_THRESHOLD_MPS (0.7) lives on SportFormat, not HudState — plan-directed deviation from the RESEARCH skeleton; only the dot formatter consumes it, keeping Task 1 self-contained"
  - "Unknown future sessionState strings fall through to the staleness branches (lenient posture matching ProtocolCodec)"
  - "Locale-default String.format retained (matches existing formatDistance convention; no Locale.US added — RESEARCH Pitfall 10 accepted exposure)"
  - "Elapsed rendered H:MM:SS and ASCII \"--:--\" placeholders stand as shipped (orchestrator-accepted lexical deviations per plan revision note)"

patterns-established:
  - "Staleness precedence ladder: NOT_RECORDING (never received or st==idle) > FINISHED (immune to staleness) > STALE_NO_DATA (>10s) > STALE_DIM (>3s) > LIVE, strict > comparisons"
  - "sport_state receipt stamping: lastSportStateAtMs carries the injected SystemClock.elapsedRealtime() value; 0L means never received this connection"

requirements-completed: [HUD-01, HUD-02, HUD-03]

# Metrics
duration: 8min
completed: 2026-07-03
---

# Phase 2 Plan 01: Sport HUD Core Summary

**SPORT layout mode with JVM-tested metric formatters and an injected-clock staleness ladder — closing the glasses module's Wave 0 test gap with its first 14 green unit tests while both APKs keep assembling.**

## Performance

- **Duration:** ~8 min (446s)
- **Started:** 2026-07-03T19:33:42Z
- **Completed:** 2026-07-03T19:41:08Z
- **Tasks:** 2/2 (both TDD, RED -> GREEN commit pairs)
- **Files modified:** 6 (3 created, 3 modified)

## Accomplishments

- **Exact test count: 14 glasses tests green** (SportFormatTest 6 + HudStateTest 8), the glasses module's first-ever unit tests; full repo suite is 79 green (shared 7, phone 58, glasses 14)
- **Both APKs confirmed assembling** after the SPORT enum addition: `:glasses:assembleDebug` and full `assembleDebug` (phone-debug.apk + glasses-debug.apk) exit 0 — all exhaustive `when` sites satisfied under Kotlin 2.1
- SportFormat locks every metric string by worked vector: elapsed hour rollover (3_599_000 -> "0:59:59", 3_600_000 -> "1:00:00"), pace imperial conversion (294_000 ms/km -> "7:53" /mi), ap<=0 -> "--:--" sentinel, one-decimal speed, moving dot strict >0.7 m/s
- HudState gains the 8 sport fields, applySportState 7-field mapping + receipt stamp, and the sportDisplayMode precedence ladder — all pure functions with injected nowMs, proven immune to unrelated copy()/withNotification() chains
- 3-way tap cycle locked by test: FULL_SCREEN -> SMALL_CORNER -> SPORT -> FULL_SCREEN, with MINI_BOTTOM/MINI_SPLIT still returning to FULL_SCREEN (Phase 2 SC#4 preserved)

## Task Commits

Each TDD task produced a RED (test) then GREEN (feat) commit:

1. **Task 1: Install glasses test infrastructure and ship SportFormat** — `1757c39` (test, RED) -> `59f9bf1` (feat, GREEN)
2. **Task 2: SPORT mode + sport fields + pure seams + HudView branches** — `86047da` (test, RED) -> `36eb691` (feat, GREEN)

**Plan metadata:** committed separately as docs(02-01) with this SUMMARY.

## Files Created/Modified

- `glasses/build.gradle.kts` — adds `testImplementation("junit:junit:4.13.2")`, the module's only test dependency (grep count of testImplementation = 1)
- `glasses/src/main/java/com/rokid/hud/glasses/SportFormat.kt` — pure-Kotlin `object SportFormat`: formatElapsed, formatPace, paceUnit, formatSpeed, speedUnit, movingDot; conversion constants (3.6 / 2.23694 / 1.609344) equal the HudView status-strip values
- `glasses/src/main/java/com/rokid/hud/glasses/HudState.kt` — SPORT enum value, SportDisplayMode enum, 8 sport fields with defaults, SPORT_STALE_DIM_MS/SPORT_STALE_NODATA_MS constants, applySportState, sportDisplayMode, 3-way toggleLayout; stays android-import-free
- `glasses/src/main/java/com/rokid/hud/glasses/HudView.kt` — two one-line exhaustiveness branches (onDraw dispatch -> drawSportLayout; mode indicator -> "[ SPORT ]") plus the empty drawSportLayout stub for Plan 02-03
- `glasses/src/test/java/com/rokid/hud/glasses/SportFormatTest.kt` — 6 worked-vector tests (59 lines) incl. boundaries and imperial
- `glasses/src/test/java/com/rokid/hud/glasses/HudStateTest.kt` — 8 tests (113 lines): tap cycle, mini-return, field mapping, copy preservation, staleness ladder, FINISHED precedence, never-received, idle

## Decisions Made

- MOVING_DOT_THRESHOLD_MPS is owned by SportFormat rather than HudState — the plan's documented deviation from the RESEARCH skeleton (avoids a dead constant on HudState; phone owns real hysteresis). This is the one deviation from the RESEARCH skeletons the plan asked to record; no others occurred.
- drawSportLayout placed in its own `// ── Sport layout ──` section before the mode-indicator section, matching HudView's existing section-comment style.

## Deviations from Plan

None — plan executed exactly as written. (Execution-environment note: Gradle was invoked from the git worktree root rather than the plan's literal `cd /Users/bilhuang/Documents/rokid-maps`, per worktree isolation rules; identical targets and flags.)

## Issues Encountered

None.

## Known Stubs

| Stub | File | Reason |
|------|------|--------|
| `drawSportLayout` empty body | `glasses/src/main/java/com/rokid/hud/glasses/HudView.kt` | Intentional, plan-mandated dispatch target. Plan 02-03 implements the full CONTEXT-locked metric layout (geometry, dim hierarchy, staleness ticker). SPORT is already visibly distinct on device via the status strip + `[ SPORT ]` indicator drawn by onDraw. |

The stub does not block this plan's goal — this plan ships the JVM-testable core and compile-safe dispatch; live data lands in 02-02, rendering in 02-03.

## TDD Gate Compliance

Gate sequence verified in git log for both tasks: `test(...)` RED commit precedes `feat(...)` GREEN commit (1757c39 -> 59f9bf1; 86047da -> 36eb691). Both RED runs failed compilation on the not-yet-existing units (classic RED); no unexpected passes. No refactor commits were needed.

## Next Phase Readiness

- Plan 02-02 can wire `BluetoothClient.processMessage()`'s `ParsedMessage.SportState` no-op to `state.applySportState(msg, SystemClock.elapsedRealtime())` — the seam signature is locked by tests
- Plan 02-03 fills `drawSportLayout` using `SportFormat` + `sportDisplayMode(nowMs)`; every string and mode decision it needs is already regression-locked
- BluetoothClient's Settings layout mapping was verified untouched (grep count of MapLayoutMode.SPORT in BluetoothClient.kt = 0)

## User Setup Required

None — no external services, no new permissions, test-only dependency already resolved in sibling modules.

## Self-Check: PASSED

- All 6 code/test files + SUMMARY exist on disk
- All 4 task commits (1757c39, 59f9bf1, 86047da, 36eb691) present in git log
- Verification suite re-run exit 0: 79 tests green, both APKs assembled
