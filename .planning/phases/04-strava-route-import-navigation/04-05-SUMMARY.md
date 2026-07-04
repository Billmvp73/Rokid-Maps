---
phase: 04-strava-route-import-navigation
plan: 05
subsystem: ui
tags: [strava, route-import, gpx, osmdroid, osrm, navigation, kotlin, findviewbyid]

# Dependency graph
requires:
  - phase: 04-strava-route-import-navigation
    plan: 01
    provides: "GpxParser.parse + RouteDownsampler.downsampleForRoute (GPX -> <=200 Waypoint) + StravaRoute Gson model (name/distance/elevationGain/typeLabel/idStr)"
  - phase: 04-strava-route-import-navigation
    plan: 02
    provides: "OsrmClient.getRouteVia (single-leg via-route, route-or-throw) + buildFollowRouteResult (non-empty synthetic follow-route fallback)"
  - phase: 04-strava-route-import-navigation
    plan: 03
    provides: "HudStreamingService.startNavigationWithRoute passthrough -> NavigationManager waypoint path (reuses sendRoute/sendStepsList; glasses unchanged)"
  - phase: 04-strava-route-import-navigation
    plan: 04
    provides: "StravaApiClient.getRoutes(): RoutesResult + exportGpx(idStr): GpxResult (sealed Success|RateLimited|Failed so 429 is distinguishable)"
  - phase: 03-strava-authentication
    plan: 02
    provides: "StravaAuthManager.connectedAthleteName() connection-state gate + the refreshStravaCard single-writer pattern this plan extends"
provides:
  - "MY STRAVA ROUTES list in MainActivity: Strava-connected-gated (name/typeLabel/distance/elevation rows), loading spinner / 'No routes found' / generic-error / distinct 429 toast states"
  - "Tap-to-import flow: exportGpx -> GpxParser.parse -> RouteDownsampler.downsampleForRoute -> OsrmClient.getRouteVia (buildFollowRouteResult fallback) on a Thread{}"
  - "osmdroid route-line preview on a dedicated stravaRoutePreviewMap with a post{}-deferred zoomToBoundingBox fit (Pitfall 5 layout-timing fix)"
  - "START NAVIGATION (btnStartRouteNav) -> service.startNavigationWithRoute(waypoints, steps, dist, dur, followRoute); reuses the existing navCallback glasses broadcast unchanged"
  - "item_strava_route.xml row layout (name bold + type · distance · elevation, imperial-aware)"
  - "formatElev(m) MainActivity helper (feet vs meters)"
affects: [04-06-device-verification]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Additive parallel UI entry point: the Strava route-import path reuses the existing Polyline/BoundingBox + navCallback + startNavigation-guard machinery without touching the search->OSRM destination path (regression-safe extension of a large load-bearing Activity)"
    - "Single-writer connection gate extended: refreshStravaCard is the SOLE writer of both the STRAVA card AND the MY STRAVA ROUTES card visibility (keys-empty/not-connected -> GONE, connected -> VISIBLE + load-once)"
    - "post{}-deferred zoomToBoundingBox for a freshly-VISIBLE osmdroid preview map (the live-nav map gets away without it; a just-shown preview map is not laid out yet on first fit)"
    - "Load-once-per-connect guard (stravaRoutesLoaded) so idempotent onResume refreshStravaCard re-reads never re-fetch getRoutes on every resume"

key-files:
  created:
    - phone/src/main/res/layout/item_strava_route.xml
  modified:
    - phone/src/main/java/com/rokid/hud/phone/MainActivity.kt
    - phone/src/main/res/layout/activity_main.xml

key-decisions:
  - "Committed the three plan tasks as ONE atomic commit (not three): they are inseparable at file granularity — Task 1's row-click adapter calls Task 2's importer, which stores the RouteResult that Task 3's START NAVIGATION consumes; bindViews references the Task 2/3 methods. Any mid-file split yields a non-compiling intermediate commit, and git add -p / git stash are unavailable in the worktree. Chose an always-compiling history + a per-task-documented message over a fabricated 3-commit split."
  - "The imported-route preview uses a DEDICATED stravaRoutePreviewMap inside the routes card (not the existing navMapView) so the preview coexists with the live-nav map and lives visually next to the route list — matching the CONTEXT 'preview reuses the navMapView pattern' (the Polyline+BoundingBox pattern, on its own MapView instance) rather than sharing the single live-nav MapView"
  - "Follow-route fallback tracked via a local followRouteMode boolean set in the getRouteVia catch, threaded through previewImportedRoute -> pendingFollowRoute -> startNavigationWithRoute(..., pendingFollowRoute) so the glasses render the route line with the single synthetic 'Follow route' step when OSRM fails"
  - "429 (RateLimited) on either getRoutes or exportGpx resets the load-once guard (stravaRoutesLoaded=false) so the user can retry after the limit clears, and shows the distinct locked 'Strava rate limit — try again shortly' toast (T-04-20) rather than a generic error"

patterns-established:
  - "Pattern: Strava-connected-gated card driven by the SAME single-writer refreshStravaCard that gates the account card (one source of truth for connection-derived visibility)"
  - "Pattern: network + untrusted-XML import entirely on a Thread{} (exportGpx + GpxParser.parse), runOnUiThread only for the render/preview (T-04-17 ANR mitigation; the <=200 downsample cap bounds memory)"
  - "Pattern: post{}-deferred fit for a just-made-VISIBLE osmdroid MapView"

requirements-completed: [RIMP-01, RIMP-02, RIMP-03, RIMP-04, NAVV-01, NAVV-02]

# Metrics
duration: ~18min
completed: 2026-07-03
---

# Phase 4 Plan 05: MY STRAVA ROUTES List + GPX Import Preview + START NAVIGATION Summary

**The user-visible vertical slice of Phase 4: a Strava-connected-gated MY STRAVA ROUTES list (name / type / imperial-aware distance / elevation with loading, empty, error, and a distinct 429 state) whose rows import a route on a background Thread (exportGpx -> GPX parse -> downsample -> OSRM via-route, with a follow-route fallback), preview the route line on an osmdroid map fitted via a post{}-deferred bounding box, and a START NAVIGATION button that hands the pre-computed waypoints to the Wave-2 waypoint nav path so the route line and guidance appear on the glasses — all additive to MainActivity with the search / nav / record / strava-card / settings paths untouched.**

## Performance

- **Duration:** ~18 min
- **Started:** 2026-07-04T00:38:00Z
- **Completed:** 2026-07-04T00:56:00Z
- **Tasks:** 3 (committed as one atomic vertical-slice commit — see Decisions)
- **Files modified:** 3 (1 created, 2 modified; 406 insertions)

## Accomplishments
- **MY STRAVA ROUTES list (RIMP-01):** a `stravaRoutesCard` section (hidden by default) that `refreshStravaCard` single-writes visible only when Strava is connected, then `loadStravaRoutes()` fetches `getRoutes()` on a `Thread{}` and renders name (bold) + `"type · distance · elevation"` rows — imperial-aware via `formatDist` + the new `formatElev`. Spinner while loading, "No routes found" on an empty `Success`, a generic error toast on `Failed`, and the distinct locked "Strava rate limit — try again shortly" toast on `RateLimited` (429).
- **Tap-to-import -> preview (RIMP-02/03/04):** `onStravaRouteSelected` runs the whole import on a `Thread{}` (network + XML parse off the main thread — T-04-17): `exportGpx(idStr)` -> `GpxParser.parse` -> `RouteDownsampler.downsampleForRoute` -> `OsrmClient.getRouteVia`, with `buildFollowRouteResult` in the catch as the follow-route fallback. `previewImportedRoute` draws the route `Polyline` on a dedicated osmdroid `stravaRoutePreviewMap` and DEFERS the `zoomToBoundingBox` fit via `stravaRoutePreviewMap.post{}` (Pitfall 5). Preview only — no navigation started here.
- **START NAVIGATION (NAVV-01/02 phone side):** `btnStartRouteNav` -> `startImportedRouteNavigation()` mirrors the existing `startNavigation()` streaming-bound guard ("Start streaming first"), then calls `service!!.startNavigationWithRoute(waypoints, steps, totalDistance, totalDuration, pendingFollowRoute)` — the Plan-03 passthrough. The existing service `navCallback` already broadcasts route/step/steps_list to the glasses, so no new broadcast wiring was added.
- **Recording stays opt-in (REC-01):** no `startRecording`/`startSportRecording` anywhere in the import/preview/navigate path (acceptance grep = 0).
- `:phone:assembleDebug` + the full `:phone` unit suite (**145 tests, 0 failures, 0 errors**) green — the same baseline as Plan 03, so no Phase 1/2/3 regression.

## Task Commits

The three plan tasks were delivered in ONE atomic commit (rationale in Decisions — they are inseparable at file granularity and any split would not compile):

1. **Tasks 1+2+3 (MY STRAVA ROUTES list + GPX import preview + START NAVIGATION)** - `b2bcb83` (feat)

**Plan metadata:** (this SUMMARY commit follows separately, per the docs-commit protocol)

## Files Created/Modified
- `phone/src/main/res/layout/item_strava_route.xml` - **Created.** Route-list row: an icon (🚴/🏃) + a vertical name (bold, `routeName`) / meta (`routeMeta`) pair, following the `item_search_result.xml` convention and dark-theme colors.
- `phone/src/main/res/layout/activity_main.xml` - **Modified.** Inserted the `stravaRoutesCard` section (SectionTitle "MY STRAVA ROUTES", `stravaRoutesProgress`, `stravaRoutesEmpty`, `stravaRoutesList`, and a `stravaRoutePreviewPanel` holding `stravaRoutePreviewName`/`stravaRoutePreviewInfo`/`stravaRoutePreviewMap` + `btnStartRouteNav`) immediately after the STRAVA card, `visibility="gone"` by default, matching `bg_card` + margins.
- `phone/src/main/java/com/rokid/hud/phone/MainActivity.kt` - **Modified.** New view fields + bindings (`initRoutePreviewMap`, row click, START NAVIGATION click); the routes-card gate in `refreshStravaCard`; `loadStravaRoutes`/`renderStravaRoutes`/`adjustStravaRoutesListHeight`; the import flow `onStravaRouteSelected`; `previewImportedRoute` (post{}-deferred fit); `startImportedRouteNavigation`; the `formatElev` helper; and `stravaRoutePreviewMap.onResume()/onPause()` lifecycle. `pendingRoute`/`pendingFollowRoute` hold the imported route between preview and navigation.

## Decisions Made
- **One atomic commit for the three tasks (not three).** The tasks form one indivisible UI vertical slice at file granularity: `bindViews` (Task 1) wires the row-click to `onStravaRouteSelected` (Task 2) and the button to `startImportedRouteNavigation` (Task 3); Task 2 stores `pendingRoute` that Task 3 consumes. Splitting mid-file produces a non-compiling intermediate commit, and `git add -p` / `git stash` are unavailable in the worktree (interactive flags blocked; stash is prohibited across worktrees). I chose an always-compiling history with a per-task-documented commit message over a fabricated 3-commit split that would either break the build or require risky reconstruction of a large load-bearing Activity. All three tasks' `<verify>` (assembleDebug, and the full suite for Task 3) pass against the single commit.
- **Dedicated preview MapView, not the shared navMapView.** CONTEXT says the preview "reuses the navMapView pattern" — I read that as the `Polyline` + `BoundingBox.fromGeoPoints` + `zoomToBoundingBox` PATTERN, applied on a `stravaRoutePreviewMap` that lives inside the routes card. A dedicated instance lets the preview sit next to the route list and coexist with the live-nav map (no state fighting between preview and active navigation).
- **Follow-route flag threaded through preview to navigation.** `onStravaRouteSelected` sets a local `followRouteMode=true` in the `getRouteVia` catch, `previewImportedRoute` stores it in `pendingFollowRoute`, and `startImportedRouteNavigation` passes it to `startNavigationWithRoute(..., pendingFollowRoute)` so the glasses render the route line with the single synthetic "Follow route" step when OSRM fails.
- **429 resets the load-once guard.** `stravaRoutesLoaded` prevents re-fetching on every idempotent `onResume` -> `refreshStravaCard`, but a `RateLimited`/`Failed` result resets it so the next connect/resume retries; the 429 shows the distinct locked toast (T-04-20), not a generic error.

## Deviations from Plan

None - plan executed exactly as written.

Every seam (`getRoutes`/`exportGpx`/`GpxParser.parse`/`downsampleForRoute`/`getRouteVia`/`buildFollowRouteResult`/`startNavigationWithRoute`) was consumed exactly as the four prior-wave SUMMARYs handed it off; the layout section, the states, the post{} fit, and the START NAVIGATION guard all match the plan's `<action>` blocks. The only judgement call — bundling the three tasks into one commit — is a commit-granularity decision forced by file-level task interleaving + worktree tooling limits, not a change to the delivered behavior or scope (documented above under Decisions rather than as a behavioral deviation).

## Issues Encountered
- **Per-task atomic commits were not cleanly separable.** The three tasks edit the same two core files with hard interdependencies (bindViews references Task 2/3 methods), so no mid-file boundary compiles. With `git add -p` and `git stash` both unavailable in the worktree, I committed the coherent slice once with a message enumerating each task's contribution. Resolved without touching STATE.md/ROADMAP.md and with a green build + full suite at the single commit.

## Threat Model Compliance
All `mitigate`-disposition threats from the plan's `<threat_model>` are addressed:
- **T-04-17 (large/malformed GPX ANR):** the entire import — `exportGpx` (network) AND `GpxParser.parse` (XML) — runs on a `Thread{}`; `GpxParser` never throws (Plan 01); the `<=200` `downsampleForRoute` cap bounds all downstream memory. Only the render/preview is on `runOnUiThread`.
- **T-04-18 (route selection silently records):** no `startRecording`/`startSportRecording` in `loadStravaRoutes`/`onStravaRouteSelected`/`previewImportedRoute`/`startImportedRouteNavigation` (acceptance grep = 0). REC-01 opt-in preserved.
- **T-04-19 (preview map no-ops off-screen on first import):** `zoomToBoundingBox` is deferred via `stravaRoutePreviewMap.post{}` (the map is freshly made VISIBLE and not laid out on the first fit).
- **T-04-20 (429 shown as a generic error):** both `getRoutes` and `exportGpx` `RateLimited` outcomes map to the distinct locked "Strava rate limit — try again shortly" toast, not the generic error toast.

No new threat surface beyond the register — the import consumes exactly the two Plan-04 endpoints and the Plan-01/02 pure seams; no new network endpoint, auth path, or trust boundary was introduced.

## Known Stubs
None. The list, import, preview, and navigation are fully wired to live seams (`getRoutes`/`exportGpx` real network, `getRouteVia` real routing with a real synthetic follow-route fallback, `startNavigationWithRoute` the real service passthrough). No TODO/FIXME/placeholder/hardcoded-empty-data patterns in the changed code. The follow-route path degrades to a real synthetic route (`buildFollowRouteResult`), not an empty/stub result.

## Device-Verification Hooks for Plan 06
The live end-to-end user story is a Plan 06 device check (this plan is the wiring; the seams underneath are unit-locked). On a real connected Strava account, verify:
1. **Connected gate + list:** MY STRAVA ROUTES appears only when Strava is connected, shows the athlete's real routes with name + imperial-aware distance/elevation; toggling the Imperial Units switch reflects in the rows on the next render.
2. **States:** a spinner during load; "No routes found" for an account with zero routes; the distinct "Strava rate limit — try again shortly" toast if a 429 is hit (hammer getRoutes or use a rate-limited token); a generic "Couldn't load Strava routes" on other failures.
3. **Import + preview:** tapping a route shows "Importing …", then the route line on the preview map fitted to bounds (confirm the fit is correct on the FIRST import — the post{} defer is the thing under test); a huge GPX must not ANR (T-04-17).
4. **Follow-route fallback:** force an OSRM failure (airplane-mode the routing host or a route OSRM can't route) — the preview should still show the line with a "Follow route (no turn guidance)" info label, and START NAVIGATION should still work.
5. **START NAVIGATION -> glasses:** with streaming bound, START NAVIGATION shows the LIVE DIRECTIONS panel and the glasses render the route line + either real turns or the "Follow route" label (single-leg: one depart, one final arrive, no spurious mid-route arrivals — Plan 02/03 device checks); without streaming, the "Start streaming first" toast fires.
6. **Recording stays opt-in:** importing/navigating a route never starts recording (the RECORD card stays idle).

## Next Phase Readiness
- **Ready for Plan 06 (device verification):** the full connect -> browse -> tap -> preview -> START NAVIGATION -> glasses flow is wired; the six hooks above enumerate exactly what to confirm on-device.
- **Boundary respected:** STATE.md and ROADMAP.md were NOT modified (orchestrator owns them); only the three planned files changed. No new production dependencies.
- **No blockers.**

## Self-Check: PASSED

- FOUND: phone/src/main/res/layout/item_strava_route.xml
- FOUND: phone/src/main/res/layout/activity_main.xml
- FOUND: phone/src/main/java/com/rokid/hud/phone/MainActivity.kt
- FOUND: .planning/phases/04-strava-route-import-navigation/04-05-SUMMARY.md
- FOUND commit: b2bcb83 (feat — MY STRAVA ROUTES list + GPX import preview + START NAVIGATION)
- STATE.md / ROADMAP.md confirmed untouched by the task commit.
- `:phone:assembleDebug` exit 0 (APK built); `:phone:testDebugUnitTest` 145/0/0.

---
*Phase: 04-strava-route-import-navigation*
*Completed: 2026-07-03*
