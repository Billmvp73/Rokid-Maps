---
phase: 02-glasses-sport-hud
plan: 02
subsystem: glasses-hud
tags: [kotlin, bluetooth-spp, sport-hud, hudstate, layout-mode]

# Dependency graph
requires:
  - phase: 02-01 (sport HUD core)
    provides: HudState.applySportState(msg, nowMs) seam + 3-way toggleLayout() cycle (both regression-locked by 14 glasses JVM tests) and MapLayoutMode.SPORT
  - phase: 01 (phone sport engine)
    provides: ParsedMessage.SportState(val msg: SportStateMessage) decode variant + the device-proven 1Hz phone broadcast
provides:
  - Live sport_state consumption — every decoded message lands in BluetoothClient.currentState via applySportState with a SystemClock.elapsedRealtime() receipt stamp, delivered to HudView by the pre-existing onStateUpdate at the end of processMessage (~1Hz, zero new plumbing)
  - Layout-mode single ownership — public BluetoothClient.toggleLayout() mutates currentState, so tap-selected modes survive the 1Hz message stream (HUD-03 revert fix)
  - HudActivity.toggleLayout() delegating to btClient (single-tap wiring at onLayoutToggle unchanged)
affects: [02-03 sport rendering, 02-04 device verification]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Monotonic receipt clock at the call site only: SystemClock.elapsedRealtime() is passed into applySportState from processMessage; HudState stays clock-free and JVM-testable (Pitfall 5)"
    - "State mutation + notify shape: `currentState = currentState.X(); onStateUpdate(currentState)` mirrored from connectLoop for the new public toggleLayout()"

key-files:
  created: []
  modified:
    - glasses/src/main/java/com/rokid/hud/glasses/BluetoothClient.kt
    - glasses/src/main/java/com/rokid/hud/glasses/HudActivity.kt

key-decisions:
  - "toggleLayout() placed directly after getCurrentState() in the short public API block (plan allowed 'after stop() or alongside sendTileRequest')"
  - "No locks introduced for the new main-thread RMW on @Volatile currentState — deliberately matching the codebase's existing benign-race posture (RESEARCH Pattern 3, R6)"

patterns-established:
  - "layoutMode single owner: BluetoothClient.currentState carries the tapped mode; Settings messages retain their existing override power (MINI_BOTTOM/MINI_SPLIT/FULL) with unchanged semantics"

requirements-completed: [HUD-02, HUD-03]

# Metrics
duration: 4min
completed: 2026-07-03
---

# Phase 2 Plan 02: Bluetooth Sport Wiring Summary

**sport_state now flows phone -> BluetoothClient -> HudState -> HudView at 1Hz with a monotonic receipt stamp, and layout-mode ownership moved into BluetoothClient.currentState so a tap to SPORT can no longer be reverted by the next incoming message.**

## Performance

- **Duration:** ~4 min
- **Started:** 2026-07-03T19:45:07Z
- **Completed:** 2026-07-03T19:49:00Z
- **Tasks:** 2/2
- **Files modified:** 2

## Accomplishments

- **HUD-02 data path complete:** the Phase-1 no-op `is ParsedMessage.SportState -> { }` is gone; the branch is now `currentState = currentState.applySportState(parsed.msg, SystemClock.elapsedRealtime())`, and the pre-existing `onStateUpdate(currentState)` at the end of processMessage delivers every update — no new plumbing, exactly one `applySportState` call site (grep count 1)
- **HUD-03 ownership fix in place:** new public `BluetoothClient.toggleLayout()` mutates `@Volatile currentState` then notifies, mirroring the connectLoop mutation+notify shape; `HudActivity.toggleLayout()` is now a one-line delegate `btClient.toggleLayout()` — the replicated source of truth that every incoming message re-projects onto `hudView.state` now carries the tapped mode
- **Anti-pattern avoided:** the HudActivity onStateUpdate lambda was NOT touched — it still preserves only `batteryLevel`/`wifiConnected` (grep: `layoutMode = hudView.state.layoutMode` absent), so phone-driven Settings layout overrides (Mini Strip / Mini Split / Full) keep working (Phase 2 SC#4)
- **Full suite green:** 79 tests (shared 7, phone 58, glasses 14) + `assembleDebug` builds both APKs, exit 0

## Task Commits

1. **Task 1: Replace the SportState no-op with applySportState consumption (HUD-02 wiring)** — `9efcdc7` (feat)
2. **Task 2: Route layout toggling through BluetoothClient so taps survive the 1Hz stream (HUD-03 revert fix)** — `40d9aa9` (fix)

**Plan metadata:** committed separately as docs(02-02) with this SUMMARY.

## Files Created/Modified

- `glasses/src/main/java/com/rokid/hud/glasses/BluetoothClient.kt` — `import android.os.SystemClock` added; SportState branch consumes via applySportState with monotonic stamp; new 4-line public `toggleLayout()` after `getCurrentState()`
- `glasses/src/main/java/com/rokid/hud/glasses/HudActivity.kt` — `toggleLayout()` body swapped from `hudView.state = hudView.state.toggleLayout()` to `btClient.toggleLayout()` (onLayoutToggle/onDoubleTap wiring untouched)

## Diff Scope Confirmation (plan output requirement)

`git diff` against the wave base touches **exactly the two enumerated files and only the enumerated regions**:

| Edit site | Region | Change |
|-----------|--------|--------|
| BluetoothClient.kt import block | +1 line | `import android.os.SystemClock` |
| BluetoothClient.kt public API | +5 lines after `getCurrentState()` | new `fun toggleLayout()` (mutation + notify) |
| BluetoothClient.kt processMessage | 1 line replaced | no-op SportState branch -> `applySportState(parsed.msg, SystemClock.elapsedRealtime())` |
| HudActivity.kt toggleLayout() | 1 line replaced | `hudView.state = hudView.state.toggleLayout()` -> `btClient.toggleLayout()` |

Total: 2 files changed, 8 insertions(+), 2 deletions(-). No other state plumbing (State/Route/Step/Settings/Notification branches, HudActivity lambda) was modified.

## Threading Observations (plan output requirement)

- `toggleLayout()` adds a **main-thread** read-modify-write on `@Volatile currentState`, concurrent with the BT reader thread's RMWs in `processMessage`/`connectLoop`. Because each assignment is a wholesale reference swap of an immutable data class, the worst-case interleaving loses one message's field update (or briefly one tap transition) for at most one message period (~1s) before the next message re-projects — the same benign race class that already exists between `connectLoop`'s `copy(btConnected=...)` and `processMessage`. Per plan directive, **no locks or new concurrency primitives were introduced**: `grep -c synchronized` in BluetoothClient.kt is still 1 (the pre-existing `sendTileRequest` block only).
- The notify path from a tap is synchronous on the main thread: `toggleLayout()` -> `onStateUpdate` -> HudActivity lambda -> `runOnUiThread` (executes inline when already on main) -> `hudView.state` re-projection, so the tapped mode renders on the next frame.

## Decisions Made

- `toggleLayout()` placed immediately after `getCurrentState()` — keeps the short public API (start/stop/getCurrentState/toggleLayout) grouped, satisfying the plan's "after stop() or alongside sendTileRequest" placement directive.
- Commit type for Task 2 is `fix` (it repairs the HUD-03 layout-revert defect identified by RESEARCH Pitfall 1), Task 1 is `feat` (new data-path behavior).

## Deviations from Plan

None — plan executed exactly as written. (Execution-environment note: Gradle was invoked from the git worktree root rather than the plan's literal `cd /Users/bilhuang/Documents/rokid-maps`, per worktree isolation rules; identical targets and flags.)

## Issues Encountered

None.

## Known Stubs

No new stubs introduced by this plan. The pre-existing `drawSportLayout` empty body in `HudView.kt` (documented in 02-01-SUMMARY, plan-mandated) is unchanged and is filled by Plan 02-03; it does not block this plan's goal — SPORT mode now receives real 1Hz data and the tapped mode persists, which is this plan's MVP slice.

## Threat Model Outcomes

- T-02-03 (Tampering, mitigate): satisfied as designed — sport values enter only through ProtocolCodec's lenient typed decode (malformed -> `ParsedMessage.Unknown`, never throws) into the pure, 02-01-tested `applySportState` copy; fields are display-only. No additional mitigation code was needed.
- T-02-04 (DoS, accept) and T-02-05 (state race, accept): accepted per plan; no new redraw surface or concurrency primitives added.
- No new trust boundaries or threat flags discovered — the diff introduces no network endpoints, auth paths, file access, or schema changes.

## Verification Gates

- Task 1 gate: `:glasses:testDebugUnitTest :glasses:assembleDebug` exit 0
- Task 2 gate: `:glasses:testDebugUnitTest :glasses:assembleDebug` exit 0
- Plan gate: `:shared:testDebugUnitTest :phone:testDebugUnitTest :glasses:testDebugUnitTest assembleDebug` exit 0 (79 tests green, both APKs assembled)
- All 9 acceptance-criteria greps across both tasks pass (call-site counts, absence of the no-op comment, absence of the anti-pattern lambda preserve, synchronized count unchanged at 1)

## Next Phase Readiness

- Plan 02-03 fills `drawSportLayout` — it can now render live data: sport fields update in `HudState` at message rate and `sportDisplayMode(nowMs)` staleness works against real `lastSportStateAtMs` stamps
- Plan 02-04 step 2 executes the Pitfall-1 device proof (tap to SPORT survives an active 1Hz stream) — this plan's unit surface cannot observe it, as noted in the plan's verification section

## User Setup Required

None.

## Self-Check: PASSED

- Both modified source files + SUMMARY exist on disk
- Both task commits (9efcdc7, 40d9aa9) present in git log
- Plan verification suite exit 0: 79 tests green, both APKs assembled
