---
phase: 01-activity-recording-engine
plan: 02
subsystem: recording-engine
tags: [gps, state-machine, hysteresis, haversine, junit4, kotlin, jvm-tests]

# Dependency graph
requires:
  - phase: 01-activity-recording-engine (plan 01-01)
    provides: "SessionModels.kt contracts (SessionState, TrackPoint, MetricsSnapshot, SessionData) + JUnit4 test infrastructure"
provides:
  - "ActivitySessionManager: single source of truth for recording state, GPS filtering, and live metrics (REC-01..REC-04 engine core)"
  - "IDLE -> TRACKING -> FINISHED state machine with resume-from-checkpoint and monotonic injected-clock time base"
  - "Filtering pipeline: (0,20]m inclusive accuracy gate, 5-point valid-speed MA, 0.7/0.3 strict hysteresis, haversine distance on accepted consecutive points"
  - "REC-07 monotonicity contract clamped at the source (maxElapsedMs/maxDistanceM)"
  - "pollCheckpoint 60s/500pt arming for plan 01-03's SessionStore + plan 01-04's service ticker"
  - "35 plain-JVM unit tests driving the primitive-parameter onFix entry"
affects: [01-04, 01-05, 01-06, 01-07]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Main-thread-confined state machine with immutable outward snapshots and exactly one @Volatile published field"
    - "Injected-clock default parameters (nowElapsedRealtimeMs/nowWallMs) on every public entry for JVM testability"
    - "Primitive-parameter onFix core with a zero-logic Location adapter (recordLocation)"
    - "Monotonic clamps at the metrics source, never in the codec"

key-files:
  created:
    - phone/src/main/java/com/rokid/hud/phone/ActivitySessionManager.kt
    - phone/src/test/java/com/rokid/hud/phone/ActivitySessionManagerTest.kt
  modified: []

key-decisions:
  - "startSession only valid from IDLE — a FINISHED session requires explicit reset() first, preventing silent loss of an unsaved finished session (01-04 stop flow: stopSession -> persist -> reset)"
  - "reset() refused while TRACKING (Log.w contract violation) — no accidental live-session wipe"
  - "lastFixElapsedRealtimeMs re-anchored at start/resume so watchdog staleness is measured from session begin (no false positive at t=0)"
  - "NaN-speed ticks skip hysteresis evaluation entirely: window unchanged means MA unchanged, so the flag provably cannot change (Pattern 5 steps 4+5 merged under one NaN guard)"
  - "The fix that ENTERS moving counts its full inter-fix delta into movingMs (locked Pattern 5 order: hysteresis step 5 before moving-time step 6)"
  - "avgPace floor helper landed in Task 1's snapshot builder (trivial pure function needed by MetricsSnapshot); Task 2's tests exercise it once distance can accumulate"

patterns-established:
  - "Scenario-builder test helpers (startedAsm/fix/driveScenario) feed onFix sequences with explicit clocks — template for 01-04/01-07 engine tests"
  - "Boundary-value tests pin locked semantics exactly: 20.0 accepted / 20.1 rejected, MA 0.7 no-enter / 0.71 enter, MA 0.3 no-exit / below exits"

requirements-completed: [REC-01, REC-02, REC-03, REC-04]

# Metrics
duration: 16min
completed: 2026-07-03
---

# Phase 01 Plan 02: ActivitySessionManager Core Summary

**Main-thread-confined recording state machine with injected-clock time base, inclusive-20m accuracy gate, 5-point speed-MA hysteresis, and REC-07 monotonic clamps — proven drift-free by 35 plain-JVM tests with zero Android dependencies in the test file**

## Performance

- **Duration:** ~16 min
- **Started:** 2026-07-03T15:46:53Z
- **Completed:** 2026-07-03T16:03:33Z
- **Tasks:** 2/2 (both TDD: RED committed before GREEN)
- **Files modified:** 2 created

## Accomplishments

- ActivitySessionManager is the single source of truth for recording: same accumulators feed future saved sessions and the sport_state broadcast (ARCHITECTURE anti-pattern 5 defense)
- The R5 phantom-distance scenario (20 jittering fixes at 5m accuracy, speeds 0.0–0.4 m/s) accumulates exactly 0.0m — hysteresis never engages, so drift never becomes distance
- Every locked boundary is pinned by a test: accuracy 20.0 adds distance / 20.1 does not; MA 0.7 does not enter moving / 0.71 does; MA exactly 0.3 does not exit / 0.298 does; window size proven to be exactly 5 valid speeds by exit timing
- Elapsed time derives purely from the injected monotonic clock (backward clock reads are clamped, REC-07); the wall clock is captured once for ISO-8601 startTime

## Task Commits

Each task was committed atomically (TDD: test then feat):

1. **Task 1: Session state machine, lifecycle, track buffer, and time base**
   - `fbf2d9d` (test) — 21 failing tests: lifecycle, id/startTime formats, track buffer, injected clock, resume bases, checkpoint arming, defensive copies
   - `cd723e6` (feat) — state machine + time base + track buffer + checkpoint arming; 21 tests green
2. **Task 2: Filtering pipeline — accuracy gate, speed MA, hysteresis, haversine distance, pace**
   - `4210d6e` (test) — 14 failing/guarding tests: gate boundaries, window semantics, hysteresis boundaries, one-flag gating, drift scenario, haversine tolerance, Doppler display speed, pace floor, monotonicity sweep
   - `9f8c682` (feat) — Pattern 5 pipeline inside onFix + verbatim NavigationManager haversine; 35 tests green

## TDD Gate Compliance

Both tasks followed RED -> GREEN with the test commit preceding the feat commit; both RED runs were verified failing before commit (Task 1: compile failure on missing class; Task 2: 10 of 14 new tests failing, the rest intentional absence-assertion regression guards). No REFACTOR commits needed.

## Public API (consumed by plans 01-04 / 01-05)

```kotlin
class ActivitySessionManager {
    var state: SessionState                      // private set; main-thread-confined
    @Volatile var lastFixElapsedRealtimeMs: Long // private set; ONLY cross-thread field (watchdog staleness)

    fun startSession(sport: String, nowElapsedRealtimeMs: Long = SystemClock.elapsedRealtime(), nowWallMs: Long = System.currentTimeMillis()): Boolean
    fun stopSession(nowElapsedRealtimeMs: Long = ..., nowWallMs: Long = ...): SessionData?
    fun resumeFrom(data: SessionData, nowElapsedRealtimeMs: Long = ..., nowWallMs: Long = ...): Boolean
    fun reset()                                   // FINISHED/IDLE -> IDLE; refused while TRACKING
    fun recordLocation(loc: Location)             // zero-logic adapter -> onFix
    internal fun onFix(lat: Double, lng: Double, alt: Double, ts: Long, speedMps: Double, accuracyM: Double, bearingDeg: Double, elapsedRealtimeMs: Long)
    fun currentSnapshot(nowElapsedRealtimeMs: Long = ...): MetricsSnapshot
    fun snapshotSession(nowElapsedRealtimeMs: Long = ..., endTimeIso: String? = null): SessionData?  // null while IDLE; defensive track copy
    fun pollCheckpoint(nowElapsedRealtimeMs: Long = ...): SessionData?  // fires on 60s OR 500 points, then re-arms
}
```

Sentinel conventions (per SessionModels.kt): absent alt/speed/bearing -> `Double.NaN`; unknown accuracy -> `-1.0`. `sessionState` strings: `"idle" | "tracking" | "finished"`.

**Test count: 35** (21 Task 1 + 14 Task 2), all green; full suite (shared 7 + phone 35) green.

## Files Created/Modified

- `phone/src/main/java/com/rokid/hud/phone/ActivitySessionManager.kt` — 422-line main-thread-confined state machine + filtering + metrics; no coroutines, no I/O, Log.w only on contract violations
- `phone/src/test/java/com/rokid/hud/phone/ActivitySessionManagerTest.kt` — 533-line JVM suite driving onFix with explicit clocks; zero android.* imports

## Decisions Made

See `key-decisions` frontmatter. Notable: the exact-0.3 hysteresis boundary test relies on the verified fact that the sequential double sum of five 0.3 literals lands on exactly 1.5, so `average()` returns the exact double 0.3 and the strict `< 0.3` comparison holds — no tolerance fudging at the locked boundary.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None. The plan's embedded verify command referenced the main repo path (`cd /Users/bilhuang/Documents/rokid-maps`); verification was run from the worktree root instead per parallel-execution isolation rules — same Gradle tasks, same gates.

## Known Stubs

None — no placeholders, no TODO/FIXME, no hardcoded empty values flowing to consumers. The class is fully implemented for its plan scope; service wiring (LocationConsumer fan-out, 1Hz ticker) is plan 01-04 by design.

## Next Phase Readiness

- Plan 01-04 can wire `recordLocation` into the FLP fan-out and pull `currentSnapshot()`/`pollCheckpoint()` from its 1Hz ticker as-is
- Plan 01-03's SessionStore persists the exact `SessionData` shape ASM emits (checkpoint: endTime null; final: endTime set)
- Resume path is deterministic: `resumeFrom` excludes the process-dead gap and cannot teleport-bridge distance (first accepted post-resume fix starts a fresh segment)

## Self-Check: PASSED

- All created files exist on disk (ActivitySessionManager.kt, ActivitySessionManagerTest.kt, this SUMMARY)
- All four task commits present: fbf2d9d, cd723e6, 4210d6e, 9f8c682
- Full suite re-verified green at self-check time (shared 7 + phone 35 tests)
