---
phase: 04-strava-route-import-navigation
plan: 02
subsystem: api
tags: [osrm, routing, navigation, via-point, kotlin, junit]

# Dependency graph
requires:
  - phase: 04-strava-route-import-navigation (Plan 01)
    provides: shared Waypoint type + phone test infra (JUnit 4.13.2, org.json test dep) — no code dependency, parallel wave
provides:
  - "OsrmClient.getRouteVia(points): multi-waypoint via-point routing with waypoints=0;{last} single-leg URL"
  - "OsrmClient.buildViaUrl(points): pure URL builder (lng,lat coords, waypoints=0;{lastIndex})"
  - "OsrmClient.filterArriveSteps(steps): pure non-final zero-distance arrive filter"
  - "OsrmClient.buildFollowRouteResult(downsampled): pure follow-route fallback with one synthetic 'Follow route' step"
  - "shared private parseRouteBody(body) reused by both getRoute and getRouteVia (no parse duplication)"
affects: [Plan 03 (NavigationManager startNavigationWithRoute consumes RouteResult + follow-route fallback), Plan 05 (Strava importer calls getRouteVia + catches → buildFollowRouteResult), Plan 06 (device verification of single-leg via routing)]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Pure-function seams for network methods: URL builder + step filter extracted so they unit-test with zero network (mirrors Phase-3 StravaOAuth.buildAuthorizeUrl)"
    - "Route-or-throw contract: getRouteVia throws on failure; the follow-route fallback is the caller's responsibility (matches existing getRoute)"

key-files:
  created:
    - "phone/src/test/java/com/rokid/hud/phone/OsrmViaUrlTest.kt"
  modified:
    - "phone/src/main/java/com/rokid/hud/phone/OsrmClient.kt"

key-decisions:
  - "getRouteVia throws on OSRM failure (route-or-throw), keeping fallback in the caller — matches getRoute's contract, not a swallow-and-degrade inside OsrmClient"
  - "Renamed parseRouteResponse → shared parseRouteBody so getRoute + getRouteVia parse once; getRouteVia applies filterArriveSteps on top via RouteResult.copy()"
  - "buildFollowRouteResult.totalDistance = sum of haversine legs across downsampled waypoints (private haversineM helper), 0.0 for empty input"

patterns-established:
  - "Pattern 1: verified waypoints=0;{last} single-leg via-URL (silent intermediate points, no spurious mid-route arrivals)"
  - "Pattern 2: belt-and-braces non-final zero-distance arrive filter applied even though the happy path yields one final arrive"
  - "Pattern 3: mandatory single synthetic 'Follow route' step so HudStreamingService.sendStepsList never early-returns on empty steps"

requirements-completed: [NAVV-02]

# Metrics
duration: 3min
completed: 2026-07-03
---

# Phase 4 Plan 02: OSRM Via-Point Routing Core Summary

**OsrmClient.getRouteVia with the verified waypoints=0;{last} single-leg URL, a non-final-arrive filter, and a non-empty synthetic "Follow route" fallback — all seams extracted as pure functions with 12 zero-network unit tests.**

## Performance

- **Duration:** ~3 min
- **Started:** 2026-07-03T17:30:52-07:00 (RED commit)
- **Completed:** 2026-07-03T17:33:02-07:00 (Task 2 commit)
- **Tasks:** 2
- **Files modified:** 2 (1 created, 1 modified)

## Accomplishments
- Added `getRouteVia(points)` — the multi-coordinate OSRM path that builds `/route/v1/driving/{lng,lat;...}?...&waypoints=0;{last}`, does the same `HttpURLConnection` GET as `getRoute`, and applies the arrive filter before returning. Throws on failure so the caller owns the follow-route fallback.
- Extracted three PURE functions (`buildViaUrl`, `filterArriveSteps`, `buildFollowRouteResult`) so the load-bearing mechanics unit-test with zero network — the only untestable line is the HTTP read.
- Refactored the GeoJSON+legs parse into a shared private `parseRouteBody(body)` reused by both `getRoute` and `getRouteVia` — the `getJSONObject("geometry")` parse exists exactly once (no duplication).
- 12-test `OsrmViaUrlTest` locks the verified contract: exact `waypoints=0;3` on 4 points, lng,lat order, full param set, mid-arrive dropped / final kept, exactly one non-empty "Follow route" step.

## Task Commits

Each task was committed atomically:

1. **Task 1 (RED): failing test for URL builder + arrive filter + follow-route step** - `53bb0f1` (test)
2. **Task 1 (GREEN): pure buildViaUrl + filterArriveSteps + buildFollowRouteResult** - `fc7d0de` (feat)
3. **Task 2: getRouteVia network method + shared parseRouteBody refactor** - `a7223d4` (feat)

_TDD Task 1 = test (RED) → feat (GREEN); no REFACTOR commit needed (implementation was clean on first pass)._

## Files Created/Modified
- `phone/src/main/java/com/rokid/hud/phone/OsrmClient.kt` - Added `getRouteVia` + the three pure seams + a private `haversineM` for follow-route distance; renamed `parseRouteResponse` → shared `parseRouteBody`. `getRoute` behavior unchanged.
- `phone/src/test/java/com/rokid/hud/phone/OsrmViaUrlTest.kt` - 12 pure JVM tests (no android.*) covering NAVV-02a/b/c.

## Decisions Made
- **Route-or-throw for `getRouteVia`:** matches `getRoute`'s existing "throw on OSRM error" contract. The follow-route degrade is chosen by the caller (Plan 03/05) via try/catch → `buildFollowRouteResult`, keeping OsrmClient a pure router. (Per plan Task 2 instruction.)
- **Shared `parseRouteBody` over a second parse copy:** renamed the existing `parseRouteResponse` and pointed both `getRoute` and `getRouteVia` at it; `getRouteVia` layers `filterArriveSteps` via `RouteResult.copy(steps = ...)`. Keeps the `getJSONObject("geometry")` parse count at exactly 1 (acceptance criterion).
- **`buildFollowRouteResult.totalDistance`:** used the sum of haversine legs across the downsampled waypoints (plan allowed "sum of haversine legs or 0.0"); added a small private `haversineM` helper rather than 0.0 so the fallback carries a real total distance. Empty input → empty RouteResult (0.0).

## Deviations from Plan

None - plan executed exactly as written.

The plan's Task 2 explicitly directed the `parseRouteResponse` → `parseRouteBody` refactor (extract, do not duplicate), so the rename is planned work, not a deviation.

## Issues Encountered
None. RED failed as expected (unresolved `buildViaUrl`/`filterArriveSteps`/`buildFollowRouteResult`); GREEN passed 12/12 on first implementation; the full phone suite stayed green (115/115) after the parse refactor.

## User Setup Required
None - no external service configuration required. `getRouteVia` uses the existing public OSRM host (`https://router.project-osrm.org`, no API key) and the existing `HttpURLConnection` convention. No new production dependencies.

## Verification Evidence
- `OsrmViaUrlTest`: **12 tests, 0 failures, 0 errors, 0 skipped** (`TEST-com.rokid.hud.phone.OsrmViaUrlTest.xml`).
- Full phone suite after refactor: **115 tests, 0 failures, 0 errors** (ActivitySessionManagerTest 38, OsrmViaUrlTest 12, SessionStoreTest 20, StravaAuthUrlTest 14, StravaModelsTest 8, StravaTokenStoreTest 8, TokenExpiryTest 15) — `getRoute` path unchanged, no regression.
- Acceptance greps: `grep -c 'waypoints=0;'`=4 (≥1); `grep -c 'Follow route'`=2 (≥1); `grep -c 'fun getRoute('`=1 (untouched); `grep -c 'fun getRouteVia'`=1; `grep -c 'buildViaUrl'`=3 (≥2); `grep -c 'filterArriveSteps'`=3 (≥2); `grep -c 'getJSONObject("geometry")'`=1 (parse once).
- Threat model: T-04-05 confirmed — no `http://` literal introduced (`buildViaUrl` uses the existing `https://` BASE_URL).

## Next Phase Readiness
- **Plan 03 (NavigationManager):** `getRouteVia(points): RouteResult` and `buildFollowRouteResult(downsampled): RouteResult` are ready to wire into `startNavigationWithRoute(waypoints, steps)`; the follow-route synthetic step guarantees `sendStepsList` broadcasts (Pitfall 1 guarded at the source).
- **Plan 05 (Strava importer):** call `getRouteVia(downsampled)` inside a `Thread{}`, catch → `buildFollowRouteResult(downsampled)` for the OSRM-fail degrade.
- **Plan 06 (device):** the live single-leg via request (one depart, one final arrive, no spurious mid-route arrivals in logcat) is the remaining device check — the URL shape and filter are unit-locked here.
- No blockers.

## Self-Check: PASSED

- FOUND: phone/src/main/java/com/rokid/hud/phone/OsrmClient.kt
- FOUND: phone/src/test/java/com/rokid/hud/phone/OsrmViaUrlTest.kt
- FOUND: .planning/phases/04-strava-route-import-navigation/04-02-SUMMARY.md
- FOUND commit: 53bb0f1 (test RED)
- FOUND commit: fc7d0de (feat GREEN, Task 1)
- FOUND commit: a7223d4 (feat, Task 2)

---
*Phase: 04-strava-route-import-navigation*
*Completed: 2026-07-03*
