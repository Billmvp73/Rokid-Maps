---
task: 260703-w1l
title: Defer off-route reroute until rider joins imported Strava route
status: complete
date: 2026-07-03
requirements: [NAVV-03]
commit: c8001a7
files_modified:
  - phone/src/main/java/com/rokid/hud/phone/NavigationManager.kt
  - phone/src/test/java/com/rokid/hud/phone/NavigationRouteTest.kt
gate: "testDebugUnitTest + assembleDebug — exit 0, 0 failures, 0 errors"
---

# Quick Task 260703-w1l: Defer Off-Route Reroute Until Rider Joins Imported Strava Route — Summary

**One-liner:** NavigationManager now distinguishes "approaching an imported Strava route from home" from "deviated off a route I was on" — it defers the off-route reroute until the rider has joined the route (latched via `hasBeenOnRoute`), shows "Head to route → {dist}" while approaching (no OSRM, no Thread), and caps mid-ride reroute waypoints to ≤25 for URL reliability.

## Problem

Flagged during device verification (STATE.md "Open follow-up"): tapping START NAVIGATION while far off-route immediately rerouted through all ~200 remaining waypoints; a very long reroute can fail into a "Follow route" straight lead-in on the instruction page. The reroute at start was semantically wrong — an at-home start is *approaching* the route, not a mid-ride deviation.

## What Changed (phone-only: NavigationManager.kt + NavigationRouteTest.kt)

**Fix 1 — `hasBeenOnRoute` gating field**
- Added `@Volatile private var hasBeenOnRoute = false`. Reset to `false` in `startNavigation`, `startNavigationWithRoute`, and `stopNavigation` so every nav start is treated as "not yet on route".
- Added test-only getters `hasBeenOnRouteForTest` and `lastRerouteTimeForTest` (mirroring the existing `nextWaypointIndexForTest` pattern) so the reroute/approach *decision* is observable synchronously (main-Looper posts are no-ops on plain JVM).

**Fix 2 — join detection**
- `nearestRouteDistance(lat,lng)` is computed **once** at the existing off-route site and reused for both the join latch and the off-route decision (no second call). For a waypoint route, the first fix within `OFF_ROUTE_RADIUS_M` (80 m) of any waypoint latches `hasBeenOnRoute = true` and logs `Joined route` once (guarded by `!hasBeenOnRoute`, fires only on the transition). It does not early-return; approach fixes are always >150 m from maneuver points so they always reach this site.

**Fix 3 — approach-vs-reroute branch (waypoint path only)**
- When `nearestDist > OFF_ROUTE_RADIUS_M`:
  - `isWaypointRoute && !hasBeenOnRoute` (approaching, never joined): emit `onStepChanged("Head to route", "straight", nearestDist)` — **no Thread, no OSRM, imported `routeWaypoints`/`steps`/`followRoute` untouched**. Throttled by a **separate** `lastApproachEmitTime` timestamp so the approach cooldown is independent of the real-reroute cooldown and `lastRerouteTime` stays 0 until a genuine reroute.
  - Else (joined-then-deviated, OR a destination-only route): existing behavior **byte-for-byte unchanged** — `REROUTE_COOLDOWN_MS` gate → `lastRerouteTime = now` → `onRerouting()` → `rerouteThroughRemainingWaypoints` (waypoint) or `calculateRoute(..., full=false)` (destination). A non-waypoint route can never enter the approach branch.

**Fix 4 — cap remaining reroute waypoints**
- Added `private const val MAX_REROUTE_WAYPOINTS = 25`.
- Added pure `internal fun capRerouteWaypoints(list)`: returns the list unchanged when `size <= 25`; otherwise even-stride downsamples to ≤25 points via evenly-spaced rounded indices, **always keeping first + last** (`LinkedHashSet` dedupes any rounding collisions). No field reads beyond the const, no Android/network → JVM-unit-testable.
- `rerouteThroughRemainingWaypoints` caps the `remaining` slice with `capRerouteWaypoints` **before** building `reroutePoints`. The forward-only `progressIndex` slice-start, the WR-01 5-field atomic publish on `mainHandler.post`, `full=false` on the reroute broadcast, and the try/catch → `buildFollowRouteResult` fallback are all unchanged.

## Tests Added (NavigationRouteTest.kt)

Assertions use synchronous caller-thread witnesses (posts are no-ops on plain JVM — documented in the class KDoc):

- **`startOffRouteNeverJoinedDoesNotReroute`** — imported route + FAR start fix: `hasBeenOnRouteForTest == false`, `lastRerouteTimeForTest == 0L` (no dispatch → approach branch ran), `steps === importedSteps`, `currentStepIndex == 0`.
- **`joinThenDeviateReroutesOnlyAfterJoining`** — on-route fix latches `hasBeenOnRoute` with `lastRerouteTime == 0L` (join alone doesn't reroute); a subsequent FAR fix advances `lastRerouteTime > 0L` (real reroute dispatched only after joining); indices stay ≥0. 5-waypoint route so the join fix triggers neither step-advance nor arrival.
- **`capRerouteWaypointsBoundsSizeAndKeepsEndpoints`** — 200-point list → size ≤25 keeping first+last; a ≤25-point list is returned unchanged (equals input). Asserts the documented literal `25` rather than widening const visibility.

## Verification

Single build+test gate (implementation + tests share it):

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && cd /Users/bilhuang/Documents/rokid-maps && \
  java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain testDebugUnitTest assembleDebug -q
```

**Result: exit 0.** `NavigationRouteTest` 8 → 11 tests (all 3 new present by name), 0 skipped/failures/errors. Phone module aggregate: 190 tests, 0 failures, 0 errors (runtime count; the plan's "219→222" is a repo-wide figure across all modules — the binding criterion is exit 0 with zero failures, which holds, and the +3 delta matches). `assembleDebug` produced `phone/build/outputs/apk/debug/phone-debug.apk`.

## Invariants Preserved

- **WR-01:** the 5-field atomic route publish stays inside `mainHandler.post` in `rerouteThroughRemainingWaypoints` (untouched).
- **D4:** `full = false` on all reroute broadcasts — the glasses `wholeRoute` birdview source is never clobbered.
- **Pitfall 3:** forward-only `currentStepIndex` / `nextWaypointIndex` — this change adds no index writes to `onLocationUpdate`.
- **REROUTE_COOLDOWN_MS** still gates real reroutes; the approach emit uses an independent `lastApproachEmitTime` (so a genuine reroute right after a join is not suppressed by a recent approach emit).
- Thread bodies stay `try/catch` — no exception propagation (CLAUDE.md).
- The destination-only `calculateRoute(lat,lng,destLat,destLng,full=false)` reroute path (`isWaypointRoute == false`) is behaviorally unchanged.

## Deviations from Plan

None — plan executed exactly as written, including both plan-checker wording resolutions:
1. Join-check placed at the single `nearestDist = nearestRouteDistance(...)` computation (no second call), reused for both the join latch and the off-route decision.
2. Test acceptance treated as "exit 0, zero failures"; counts annotated as runtime totals.

## Known Stubs

None.

## Not in Scope (orchestrator)

Device verification is NOT part of this task. The orchestrator deploys the phone APK and device-verifies: import a Strava route → tap START off-route via a mock GPS point → confirm the glasses show "Head to route → {dist}" (not a straight-line "Follow route") and the birdview still shows the full route → then feed an on-route point → real turn-by-turn resumes.

## Self-Check: PASSED

- FOUND: phone/src/main/java/com/rokid/hud/phone/NavigationManager.kt (modified)
- FOUND: phone/src/test/java/com/rokid/hud/phone/NavigationRouteTest.kt (modified)
- FOUND: commit c8001a7 (feat(w1l): defer off-route reroute until rider joins imported Strava route)
- Gate: exit 0, 0 failures, 0 errors
