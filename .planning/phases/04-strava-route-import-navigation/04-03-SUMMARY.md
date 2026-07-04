---
phase: 04-strava-route-import-navigation
plan: 03
subsystem: navigation
tags: [navigation, osrm, via-point, follow-route, thread-safety, volatile, kotlin, junit]

# Dependency graph
requires:
  - phase: 04-strava-route-import-navigation (Plan 02)
    provides: OsrmClient.getRouteVia (single-leg via-routing) + buildFollowRouteResult (non-empty synthetic follow-route fallback) — consumed by the reroute path and the follow-route degrade
provides:
  - "NavigationManager.startNavigationWithRoute(waypoints, steps, totalDistance, totalDuration, followRouteMode): waypoint-accepting nav path that skips the internal OSRM A→B call and fires the same onRouteCalculated the glasses pipeline consumes"
  - "@Volatile steps/currentStepIndex/routeWaypoints — the STATE-assigned cross-thread data-race fix (T-04-09); writes on the caller thread happen-before the callback post"
  - "Follow-route mode: forward-only nextWaypointIndex + live synthetic-step distance to the next downsampled waypoint (NAVV-02c)"
  - "Shape-preserving off-route reroute: getRouteVia through the REMAINING downsampled waypoints (routeWaypoints-indexed, clamped), buildFollowRouteResult fallback, never a 2-point collapse (NAVV-03)"
  - "HudStreamingService.startNavigationWithRoute passthrough — pure delegation into NavigationManager, reuses sendRoute + sendStepsList unchanged (glasses side untouched)"
affects: [Plan 05 (Strava importer calls service.startNavigationWithRoute after getRouteVia/buildFollowRouteResult), Plan 06 (device verification of live route line + follow-route + off-route shape preservation + butterfly-free switchbacks)]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "@Volatile publication of cross-thread nav state assigned on the caller thread BEFORE the mainHandler.post (happens-before) — no Thread{} in startNavigationWithRoute; the reader is the main-thread onLocationUpdate + HudStreamingService.sendStepsList"
    - "Forward-only monotonic indices (currentStepIndex routed-mode, nextWaypointIndex follow-route) — never rewound on backward GPS jitter (Pitfall 3 butterfly prevention)"
    - "Shape-preserving reroute slices routeWaypoints (fresh nearest-forward index clamped to lastIndex), NEVER currentStepIndex/steps (different cardinality → IndexOutOfBounds risk; checker WR-1)"
    - "Field-invariant unit tests for Android-Handler-bound classes: assert the SYNCHRONOUS caller-thread state (no Looper needed under isReturnDefaultValues=true), not the posted callback — the no-Robolectric discipline"

key-files:
  created:
    - "phone/src/test/java/com/rokid/hud/phone/NavigationRouteTest.kt"
  modified:
    - "phone/src/main/java/com/rokid/hud/phone/NavigationManager.kt"
    - "phone/src/main/java/com/rokid/hud/phone/HudStreamingService.kt"

key-decisions:
  - "Off-route reroute strategy (resolves 04-RESEARCH Open Q 3): full via-reroute through the REMAINING downsampled waypoints (best fidelity) — chosen because getRouteVia already exists this phase (no extra host, no new dependency); preserves the Strava route shape (Pitfall 2)"
  - "Reroute slice indexes routeWaypoints via a FRESH nearest-forward index (minByOrNull haversine, coerceIn 0..lastIndex), not currentStepIndex — routed-mode steps have different cardinality than routeWaypoints, so slicing routeWaypoints by currentStepIndex could IndexOutOfBounds on a switchback (checker WR-1)"
  - "nextWaypointIndex names the waypoint being HEADED TOWARD (starts at 0): reaching a waypoint advances the pointer past it. Tests were designed around this semantic (sim starts before wp[0])"
  - "Unit tests assert synchronous field invariants (steps/index/nextWaypointIndex/isNavigating) rather than callback args — Handler.post is a stubbed no-op on plain JVM (no Robolectric), so the load-bearing NAVV guarantees are the caller-thread state set before the post"

patterns-established:
  - "Pattern 1: waypoint-accepting nav path fires the SAME onRouteCalculated → sendRoute + sendStepsList pipeline as the destination-only path, so the glasses render with ZERO glasses-side changes"
  - "Pattern 2: getRouteVia-fail → buildFollowRouteResult (followRoute=true) degrade on the reroute Thread{}, never a 2-point collapse, never a rethrow (T-04-12; CLAUDE.md never-propagate-I/O)"
  - "Pattern 3: isWaypointRoute flag gates reroute strategy — waypoint routes use shape-preserving via-reroute, destination-only routes keep the legacy 2-point reroute unchanged (no non-Strava regression)"

requirements-completed: [NAVV-01, NAVV-02, NAVV-03]

# Metrics
duration: 7min
completed: 2026-07-03
---

# Phase 4 Plan 03: NavigationManager Waypoint Path + Race Fix + Shape-Preserving Reroute Summary

**NavigationManager.startNavigationWithRoute waypoint path with a @Volatile cross-thread race fix, a forward-only follow-route pointer + live synthetic-step distance, and an off-route reroute that re-runs getRouteVia through the remaining downsampled waypoints (never a 2-point shape collapse) — plus a pure HudStreamingService passthrough that reuses the existing sendRoute/sendStepsList glasses pipeline unchanged.**

## Performance

- **Duration:** ~7 min
- **Started:** 2026-07-03T17:39:32-07:00 (context load)
- **Completed:** 2026-07-03T17:46:41-07:00 (Task 3 commit)
- **Tasks:** 3
- **Files modified:** 3 (1 created, 2 modified)

## Accomplishments
- Added `startNavigationWithRoute(waypoints, steps, totalDistance, totalDuration, followRouteMode)` — the waypoint-accepting nav path that stores the pre-computed route + steps, skips the internal OSRM A→B call `startNavigation` makes, and fires the same `onRouteCalculated`/`onStepChanged` the glasses pipeline already consumes (NAVV-01).
- Applied the STATE-assigned data-race fix (T-04-09): `steps`, `currentStepIndex`, and `routeWaypoints` are now `@Volatile`; the waypoint-path writes happen on the caller thread BEFORE the `mainHandler.post`, so happens-before + `@Volatile` publishes them to the main-thread `onLocationUpdate` reader and `HudStreamingService.sendStepsList`.
- Implemented follow-route mode: a forward-only `nextWaypointIndex` advances toward the next downsampled waypoint, the single synthetic "Follow route" step's live distance updates to that waypoint, and the pointer never rewinds on backward GPS jitter (NAVV-02c; Pitfall 3).
- Implemented the shape-preserving off-route reroute (the NAVV-03 sharp edge): for a waypoint route, off-route re-runs `getRouteVia` from the current position through the REMAINING downsampled waypoints (preserving the Strava shape — Pitfall 2), with a `buildFollowRouteResult` degrade on OSRM failure and NEVER a 2-point collapse. The slice indexes `routeWaypoints` via a fresh nearest-forward index clamped to `lastIndex` (never `currentStepIndex`/`steps` — checker WR-1).
- Added the `HudStreamingService.startNavigationWithRoute` pure passthrough — it delegates to `NavigationManager` and reuses the existing `NavigationCallback → sendRoute + sendStepsList` broadcast with ZERO glasses-side changes.
- Full `:phone` suite (145 tests, 0 failures) + `assembleDebug` green; the destination-only nav path and existing BT/GPS/tile behavior are unchanged.

## Task Commits

Each task was committed atomically:

1. **Task 1 (RED): failing test for waypoint path / follow-route pointer / monotonic index** - `8d59c7f` (test)
2. **Task 1 (GREEN): startNavigationWithRoute + @Volatile race fix + follow-route mode** - `49495a0` (feat)
3. **Task 2: shape-preserving off-route reroute via remaining downsampled waypoints** - `23c662a` (feat)
4. **Task 3: HudStreamingService.startNavigationWithRoute passthrough** - `4a7c5ab` (feat)

_TDD Task 1 = test (RED) → feat (GREEN); no REFACTOR commit needed (implementation was clean on first pass)._

## Files Created/Modified
- `phone/src/main/java/com/rokid/hud/phone/NavigationManager.kt` - Added `startNavigationWithRoute`, the `@Volatile` race fix on `steps`/`currentStepIndex`/`routeWaypoints`, `followRoute`/`nextWaypointIndex`/`isWaypointRoute` state, the `onFollowRouteUpdate` forward-only pointer + live synthetic-step distance, and the `rerouteThroughRemainingWaypoints` shape-preserving via-reroute. The existing destination-only `startNavigation` + the forward-only routed-mode advancement (the maneuver-point loop) are unchanged.
- `phone/src/main/java/com/rokid/hud/phone/HudStreamingService.kt` - Added the `startNavigationWithRoute` pure passthrough beside `startNavigation`. `initNavigation`, the `NavigationCallback`, `sendRoute`, `sendStep`, and `sendStepsList` are untouched.
- `phone/src/test/java/com/rokid/hud/phone/NavigationRouteTest.kt` - 8 pure-JVM field-invariant tests (no `android.*`) covering NAVV-01/02c/03: waypoint path stores steps + resets index, follow-route holds exactly one synthetic step with a monotonic forward-only pointer, routed-mode `currentStepIndex` is monotonic non-decreasing across a simulated traversal, off-route reroute never drives the indices negative, and `stopNavigation` resets follow-route state.

## Decisions Made
- **Off-route reroute strategy (resolves 04-RESEARCH Open Q 3):** picked the "best fidelity" full via-reroute through the remaining downsampled waypoints over the v1-simple 2-point degrade. Justified because `getRouteVia` already exists this phase (Plan 02) — no extra host, no new dependency — and it is the only option that keeps the Strava route shape after a wrong turn (Pitfall 2 warns the 2-point degrade "throws away the whole route shape"). Recorded as a KDoc on `rerouteThroughRemainingWaypoints`.
- **Slice `routeWaypoints`, never `currentStepIndex`/`steps` (checker WR-1):** in routed mode `steps` has a different cardinality than `routeWaypoints`, so slicing `routeWaypoints` by `currentStepIndex` risks IndexOutOfBounds on a switchback. The reroute derives a fresh nearest-forward waypoint index (`minByOrNull` haversine) clamped to `routeWaypoints.lastIndex` — always a valid slice start; follow-route mode uses `maxOf(nextWaypointIndex, progressIndex)` to stay forward-only.
- **`nextWaypointIndex` semantic = "the waypoint being headed toward" (starts at 0):** reaching a waypoint advances the pointer past it. The follow-route tests were designed around this (the sim starts ~550m before wp[0]) after the initial test assumptions were corrected (see Issues Encountered).
- **Assert synchronous field state, not callback args:** `NavigationManager` posts callbacks via `mainHandler.post`, which is a stubbed no-op on plain JVM (`unitTests.isReturnDefaultValues = true`, no Robolectric). The load-bearing NAVV guarantees are the caller-thread field state set before the post, so the tests assert `steps`/`currentStepIndex`/`nextWaypointIndex`/`isNavigating` directly — matching the project's OsrmViaUrlTest / ActivitySessionManagerTest discipline.

## Deviations from Plan

None - plan executed exactly as written.

Every field change, the reroute strategy, the follow-route pointer, and the service passthrough were explicitly directed by the plan's `<action>` blocks and the 04-RESEARCH code sketches. The `parseRouteBody` reuse, `getRouteVia`, and `buildFollowRouteResult` were provided by Plan 02 (consumed, not re-implemented).

## Issues Encountered
- **Two initial test-design assumptions were wrong (self-corrected during the GREEN phase, not implementation bugs).** My first draft of `followRouteNextWaypointPointerAdvancesForwardOnly` and `stopNavigationResetsFollowRouteState` assumed the follow-route pointer initially targets `wp[1]` and that reaching the last waypoint would not trip arrival. The correct implementation semantic is that `nextWaypointIndex` names the waypoint being headed toward (starts at 0, advances past a waypoint when reached), and reaching the last waypoint triggers `onArrived` (resetting `isNavigating`). Fixed the two tests to start the sim before `wp[0]` and to stop at an intermediate waypoint (three-waypoint route) so the pointer advances without tripping arrival. All 8 tests then passed; no production code changed to satisfy the corrected tests.
- The RED test failed exactly as expected (unresolved `startNavigationWithRoute` / `nextWaypointIndexForTest`), confirming the TDD gate.

## User Setup Required
None - no external service configuration required. The reroute path uses the existing public OSRM host (`https://router.project-osrm.org`, no API key) via the Plan 02 `getRouteVia`; no new production dependencies were added.

## Verification Evidence
- `NavigationRouteTest`: **8 tests, 0 failures, 0 errors, 0 skipped** (waypoint-path field invariants, follow-route single synthetic step + forward-only pointer, monotonic `currentStepIndex`/`nextWaypointIndex` across traversal, off-route reroute index-safety, stop-resets-state).
- Full `:phone` suite: **145 tests, 0 failures, 0 errors** (ActivitySessionManagerTest 38, NavigationRouteTest 8, OsrmViaUrlTest 12, SessionStoreTest 20, GpxParserTest 7, RouteDownsamplerTest 7, StravaAuthUrlTest 14, StravaModelsTest 8, StravaRouteModelTest 8, StravaTokenStoreTest 8, TokenExpiryTest 15) — no Phase 1/2/3 regression.
- `:phone:assembleDebug`: **exit 0** (Task 3 signature compatibility — `HudStreamingService.startNavigationWithRoute` matches `NavigationManager.startNavigationWithRoute`).
- Task 1 acceptance greps: `grep -c '@Volatile'`=8 (≥3 — steps/currentStepIndex/routeWaypoints + the new state); `grep -c 'fun startNavigationWithRoute'`=1; `grep -c 'nextWaypointIndex--|nextWaypointIndex -='`=0 (forward-only); `grep -c 'currentStepIndex--|currentStepIndex -='`=0 (forward-only preserved — Pitfall 3).
- Task 2 acceptance greps: `grep -c 'getRouteVia'`=3 (≥1); the waypoint off-route branch calls `rerouteThroughRemainingWaypoints` (getRouteVia + remaining waypoints), NOT the 2-point `calculateRoute(...destLat, destLng)` — the two 2-point call sites are the `else` destination-only path (unchanged) and the empty-routeWaypoints defensive guard; the reroute slice uses a fresh nearest-forward index clamped to `lastIndex` (WR-1 safe).
- Task 3 acceptance greps: `grep -c 'fun startNavigationWithRoute'` (service)=1; `grep -c 'navigationManager?.startNavigationWithRoute'`=1; `initNavigation`/`sendRoute`/`sendStepsList` unchanged vs base (only new KDoc text matched those keywords).
- Threat model: T-04-09 mitigated (`@Volatile` ≥3, caller-thread happens-before); T-04-10 mitigated (waypoint reroute preserves shape via `getRouteVia`, no 2-point collapse); T-04-11 mitigated (0 index decrements); T-04-12 mitigated (`getRouteVia` wrapped in try/catch → `buildFollowRouteResult`, never rethrows).
- No file deletions across the 4 commits; no untracked files; scope limited to the 3 planned files (no STATE.md/ROADMAP.md/StravaApiClient.kt touched).

## Next Phase Readiness
- **Plan 05 (Strava importer):** `HudStreamingService.startNavigationWithRoute(waypoints, steps, totalDistance, totalDuration, followRouteMode)` is ready — after the importer calls `OsrmClient.getRouteVia(downsampled)` (or `buildFollowRouteResult` on failure), it feeds the `RouteResult` straight into this passthrough and the glasses render with no glasses-side changes.
- **Plan 06 (device):** the live checks remain — the route line stays curvy after a wrong turn (shape preservation), a single leg (one depart, one final arrive, no spurious mid-route arrivals), butterfly-free switchbacks, and the follow-route "Follow route → Nm" live readout. The field invariants and the reroute/pointer logic are unit-locked here.
- No blockers.

## Known Stubs
None. Follow-route mode is fully wired (real forward-only pointer + live haversine distance to the next downsampled waypoint, not a placeholder); the reroute path degrades to a real synthetic route (`buildFollowRouteResult`), not an empty/stub result.

## Self-Check: PASSED

- FOUND: phone/src/main/java/com/rokid/hud/phone/NavigationManager.kt
- FOUND: phone/src/main/java/com/rokid/hud/phone/HudStreamingService.kt
- FOUND: phone/src/test/java/com/rokid/hud/phone/NavigationRouteTest.kt
- FOUND: .planning/phases/04-strava-route-import-navigation/04-03-SUMMARY.md
- FOUND commit: 8d59c7f (test RED)
- FOUND commit: 49495a0 (feat GREEN, Task 1)
- FOUND commit: 23c662a (feat, Task 2)
- FOUND commit: 4a7c5ab (feat, Task 3)

---
*Phase: 04-strava-route-import-navigation*
*Completed: 2026-07-03*
