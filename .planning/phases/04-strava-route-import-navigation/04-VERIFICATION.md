---
phase: 04-strava-route-import-navigation
verified: 2026-07-03T00:00:00Z
status: passed
score: 5/5 code-verifiable must-haves verified (device confirmation completed 2026-07-03)
overrides_applied: 0
mode: mvp
build_gate: "testDebugUnitTest assembleDebug — exit 0 (full suite green, 0 failures/0 errors)"
review_status: "04-REVIEW.md: 0 Critical / 4 Warnings (WR-01..04) — latent defects, none block a must-have truth; actively being fixed on branch gsd-reviewfix/04-05-558 (recovery-pending marker present)"
human_verification:
  - test: "On a real connected Strava account, open the phone app and confirm MY STRAVA ROUTES lists the athlete's real routes with name, distance (imperial-aware), and elevation; capture the 'Rate usage' log line + a 200 (not a 403) on GET /athlete/routes (settles assumption A2 — the singular endpoint on a live token)"
    expected: "The list is visible (Strava connected), populated with real routes; logcat shows 200, not 403"
    why_human: "Requires a live OAuth-authorized Strava token + the user's own account data; the singular /athlete/routes endpoint cannot be exercised on a real token without the pending Phase-3 live Authorize tap (batched here)"
  - test: "Tap a route → confirm the GPX imports, downsamples, and the route line previews on the phone map correctly fitted to bounds ON THE FIRST import (the post{} defer is the thing under test); sanity-check the preview line matches the route shape (A4 — DP epsilon 15m; note if visibly too coarse)"
    expected: "Route line previews fitted to bounds on the first tap, visually matching the real route"
    why_human: "osmdroid tile rendering + zoomToBoundingBox layout timing + DP-epsilon fidelity on a real route are visual/rendering outcomes not observable from static code"
  - test: "With streaming active (glasses connected), tap START NAVIGATION → confirm the glasses show the route line AND turn-by-turn step text; in logcat confirm a SINGLE leg with exactly ONE final 'arrive' (no spurious mid-route 'Arrived!' banners/TTS) — the waypoints=0;{last} verification"
    expected: "Glasses render route line + steps; logcat shows one depart + one final arrive, no mid-route arrivals"
    why_human: "Glasses HUD rendering + the live OSRM via-point response (single-leg confirmation) require the physical glasses + a live route; only device logcat proves the single-leg behavior end-to-end"
  - test: "Navigate a winding/switchback route → confirm the glasses direction arrow does NOT flip 180° at a switchback and the next-waypoint distance does not jump erratically (butterfly avoidance, SC#5)"
    expected: "Direction arrow stays stable through switchbacks; no 180° flip; distance readout monotone-ish"
    why_human: "Butterfly/switchback behavior is an emergent real-GPS-trajectory property; the forward-only invariant is unit-locked but the live arrow behavior can only be observed on-device with a real switchback route"
  - test: "While navigating, deviate >80m off the route → confirm off-route detection fires (onRerouting) AND the reroute keeps the route shape (the glasses route line stays curvy/route-following, NOT a straight 2-point collapse to the destination); forward-only index does not rewind (NAVV-03)"
    expected: "onRerouting fires; the rerouted line remains shape-preserving (curvy), not a straight A→B"
    why_human: "Off-route reroute requires live GPS deviation + a live OSRM via-reroute response; the shape-preservation outcome is only observable on the rendered glasses/phone map"
  - test: "Force an OSRM failure (airplane-mode the routing host at import, or import a route OSRM cannot snap) → confirm the flow degrades to follow-route: a 'Follow route' label + distance on the glasses, with the route line still drawn"
    expected: "Follow-route label + live distance shown on glasses; route line still rendered (empty-steps trap closed)"
    why_human: "The follow-route degrade requires inducing a live OSRM failure and observing the glasses render; the synthetic-step path is unit-locked but the on-glasses fallback rendering is device-only"
  - test: "Confirm recording did NOT auto-start when selecting/navigating a route (REC-01 opt-in) and, in the same batched session, clear the pending Phase-3 live-auth spots (persistence across app restart — no re-login; forced token refresh rotates it) and any Phase-2 glasses fold-ins"
    expected: "RECORD card stays idle unless the user started recording separately; Strava connection persists across restart; token rotates on forced refresh"
    why_human: "State-across-restart and the batched Phase-2/3 device spots require a physical device session with the live Strava connection; documented in 04-06-PLAN as the batched device gate"
---

# Phase 4: Strava Route Import + Navigation — Verification Report

**Phase Goal:** User imports Strava routes and navigates them on glasses
**Verified:** 2026-07-03 (code-level + on-device — device verification 04-06 executed and PASSED, see Device verification section)
**Status:** passed
**Re-verification:** No — initial verification
**Mode:** MVP (user story: "As a cyclist or runner, I want to browse my Strava routes, import one, and navigate it on my glasses, so that I can follow a planned route with the route line and guidance in my field of view instead of looking at my phone.")

## Summary

Every code-verifiable must-have for Phase 4 is VERIFIED against the actual codebase. The full test suite + `assembleDebug` pass (exit 0; 15 test classes, 0 failures / 0 errors). All 12 referenced commits exist. All 7 requirements (RIMP-01..04, NAVV-01..03) are implemented and wired end-to-end phone-side. No stubs, no debt markers, no placeholder anti-patterns in any modified file.

**Status is `human_needed`, not `passed`, for a legitimate reason:** three of the five ROADMAP Success Criteria (SC#3 glasses route-line+guidance, SC#4 live off-route reroute shape, SC#5 switchback butterfly avoidance) are inherently on-device outcomes that require the physical glasses + a live Strava connection. Their underlying code is fully implemented and unit-locked, but the emergent live behavior can only be confirmed on hardware. That confirmation is plan 04-06 (device verification), which is deliberately batched with the pending Phase-3 live Authorize tap into one phone session and has not yet run (no 04-06-SUMMARY / 04-06-DEVICE-VERIFICATION.md). These are surfaced as the human-verification items above — they are the acceptable-as-deferred device gate, not code gaps.

## Goal Achievement

### Observable Truths (ROADMAP Success Criteria — the contract)

| # | Truth (SC) | Code Status | Device Status | Evidence |
|---|-----------|-------------|---------------|----------|
| 1 | User can browse their Strava routes (name, distance, elevation) in the phone app | ✓ VERIFIED (code) | ? device-confirmable | `StravaApiClient.getRoutes()` (sealed RoutesResult, singular `/athlete/routes` via `buildRoutesUrl`, 429-distinct) → `MainActivity.loadStravaRoutes()` on Thread → `renderStravaRoutes()` rows show name + typeLabel + `formatDist` + `formatElev`; gated visible only when `connectedAthleteName()` non-null (refreshStravaCard 344-377). Loading/empty/error/429 states all present (389-422) |
| 2 | User can select a route, import it, and preview the route line on the phone map | ✓ VERIFIED (code) | ? device-confirmable | `onStravaRouteSelected()` (471-512) on Thread: `exportGpx(idStr)` → `GpxParser.parse` → `RouteDownsampler.downsampleForRoute` → `getRouteVia` (catch → `buildFollowRouteResult`); `previewImportedRoute()` (520-549) draws Polyline + `zoomToBoundingBox` deferred via `stravaRoutePreviewMap.post{}` (Pitfall 5 fix present) |
| 3 | User can start navigation on the imported route — route line and guidance appear on the glasses | ✓ VERIFIED (code) | ? DEVICE-ONLY | `startImportedRouteNavigation()` (558-573) guards `bound`/`service` ("Start streaming first") then `service!!.startNavigationWithRoute(...)` → `HudStreamingService.startNavigationWithRoute` passthrough (388-396) → `NavigationManager.startNavigationWithRoute` → same `onRouteCalculated`/`onStepChanged` callback (initNavigation 302-325) that already `sendRoute`+`sendStepsList` to glasses. Glasses HUD rendering itself is device-only |
| 4 | Off-route detection and auto-recalculation work correctly for Strava imported routes | ✓ VERIFIED (code) | ? DEVICE-ONLY | `onLocationUpdate` off-route branch (185-200): waypoint routes call `rerouteThroughRemainingWaypoints` (277-329) — re-runs `getRouteVia` through REMAINING `routeWaypoints` (fresh nearest-forward index, `coerceIn(0, lastIndex)`, WR-1 safe), `buildFollowRouteResult` fallback on failure, NEVER a 2-point collapse. Destination-only path unchanged (no regression). Live GPS deviation is device-only |
| 5 | Winding/switchback routes display correctly without direction reversal (butterfly avoided) | ✓ VERIFIED (code) | ? DEVICE-ONLY | Forward-only indices: `grep currentStepIndex--/-=` = 0, `grep nextWaypointIndex--/-=` = 0; follow-route pointer "never rewinds" (onFollowRouteUpdate 209-231); reroute resets index to 0 relative to current position (never past the rider); DP ≤200 spacing is the primary density fix. NavigationRouteTest asserts monotonic non-decreasing. Live arrow behavior on a real switchback is device-only |

**Code-verifiable score:** 5/5 truths VERIFIED at the code level. Device confirmation of the on-device manifestation of SC#3, SC#4, SC#5 (and the live manifestation of SC#1, SC#2) is pending the batched 04-06 device session.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `strava/GpxParser.kt` | Pure XmlPullParserFactory `<trkpt>` extraction, coord validation, never-throws | ✓ VERIFIED | 64 lines; XmlPullParserFactory, isNamespaceAware=false, no DOCDECL (XXE-safe), lat/lng range+finite validation, try/catch→Log.w. GpxParserTest 7/7 |
| `strava/RouteDownsampler.kt` | Equirectangular iterative Douglas-Peucker + ≤200 raise-epsilon cap | ✓ VERIFIED | 100 lines; ArrayDeque (iterative), `simplify` + `downsampleForRoute` (×1.5 raise, 40-iter cap). RouteDownsamplerTest 7/7. (WR-03: perpM cosine uses segment-start lat, not midpoint — latent accuracy defect, see Warnings) |
| `strava/StravaModels.kt` | StravaRoute Gson (int type/sub_type, id_str String) + pure URL builders | ✓ VERIFIED | `data class StravaRoute` all-nullable, `type: Int?`, `idStr` String; `buildRoutesUrl` (singular /athlete/routes), `buildExportGpxUrl` (id_str path). StravaRouteModelTest 8/8 |
| `strava/StravaApiClient.kt` | getRoutes() + exportGpx() authenticated, 429-distinct, never-rethrow | ✓ VERIFIED | Both use `ensureFreshToken` PRIMARY, call pure builders, 429 checked before generic failure, sealed RoutesResult/GpxResult, never rethrow. logRateLimits reused (headers only) |
| `OsrmClient.kt` | getRouteVia (waypoints=0;{last}) + filterArriveSteps + buildFollowRouteResult | ✓ VERIFIED | `getRouteVia` builds via-URL, shared `parseRouteBody`, applies `filterArriveSteps`, throws on error; `buildFollowRouteResult` = exactly 1 synthetic step; 2-point `getRoute` untouched. OsrmViaUrlTest 12/12 |
| `NavigationManager.kt` | Waypoint path + @Volatile race fix + follow-route + shape-preserving reroute | ✓ VERIFIED | `startNavigationWithRoute` (caller-thread writes before post), @Volatile ×8 (steps/currentStepIndex/routeWaypoints + follow-route state), `rerouteThroughRemainingWaypoints` (WR-1-safe routeWaypoints slice). NavigationRouteTest 8/8. (WR-01: reroute-thread 5-field write non-atomic — latent, see Warnings) |
| `HudStreamingService.kt` | startNavigationWithRoute passthrough; glasses pipeline unchanged | ✓ VERIFIED | Pure delegation (388-396); initNavigation callback + sendRoute/sendStep/sendStepsList unchanged; sendStepsList empty-guard (608) closed upstream by the synthetic step |
| `MainActivity.kt` | route-list/import/preview/START NAVIGATION wiring | ✓ VERIFIED | All methods present + wired (bindViews 663-679); REC-01 opt-in (0 startRecording in import path). (WR-02/WR-04: unguarded zoomToBoundingBox + missing onDetach — latent, see Warnings) |
| `res/layout/item_strava_route.xml` | Route row (name/meta) | ✓ VERIFIED | routeIcon/routeName/routeMeta all present |
| `res/layout/activity_main.xml` | MY STRAVA ROUTES section (gone by default) + preview + START NAVIGATION | ✓ VERIFIED | stravaRoutesCard (visibility="gone"), stravaRoutesProgress/Empty/List, stravaRoutePreviewPanel/Map, btnStartRouteNav all present |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| MainActivity route list | StravaApiClient.getRoutes/exportGpx | Thread{} fetch, runOnUiThread render | ✓ WIRED | getRoutes (395), exportGpx (482) both on Thread; render on runOnUiThread |
| MainActivity import | GpxParser → RouteDownsampler → getRouteVia | GPX → ≤200 wp → via-route (or fallback) | ✓ WIRED | Chain present 490-507; buildFollowRouteResult in catch |
| MainActivity START NAVIGATION | service.startNavigationWithRoute | waypoint nav path | ✓ WIRED | 564-570; bound-guard present |
| MainActivity preview | stravaRoutePreviewMap fit | post{} deferred zoomToBoundingBox | ✓ WIRED | 544-547 (post{} present) |
| NavigationManager.startNavigationWithRoute | NavigationCallback.onRouteCalculated | glasses pipeline callback | ✓ WIRED | 117-123 mainHandler.post |
| HudStreamingService.startNavigationWithRoute | sendRoute + sendStepsList | existing initNavigation callback | ✓ WIRED | passthrough → callback (304-312) |
| NavigationManager off-route reroute | OsrmClient.getRouteVia | remaining downsampled waypoints | ✓ WIRED | rerouteThroughRemainingWaypoints (296) |
| StravaApiClient | StravaAuthManager.ensureFreshToken | Phase-3 proactive refresh | ✓ WIRED | getRoutes (142) + exportGpx (178) both call it |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Full suite + build gate | `testDebugUnitTest assembleDebug -q` | exit 0 | ✓ PASS |
| Load-bearing seam tests | `--tests OsrmViaUrlTest --tests NavigationRouteTest --tests RouteDownsamplerTest` | exit 0 | ✓ PASS |
| via-URL waypoints=0;{last} shape | OsrmViaUrlTest asserts `waypoints=0;3` (4pts) / `waypoints=0;1` (2pts) | assertions present + green | ✓ PASS |
| follow-route single synthetic step | OsrmViaUrlTest + NavigationRouteTest assert exactly 1 "Follow route" step | assertions present + green | ✓ PASS |
| forward-only monotonic index (butterfly) | NavigationRouteTest asserts pointer "never rewinds" + monotonic | assertions present + green | ✓ PASS |
| DP ≤200 cap on dense input | RouteDownsamplerTest cap test | green | ✓ PASS |
| Full glasses route-line + guidance end-to-end | requires physical glasses + live route | — | ? SKIP → human (SC#3) |

### Probe Execution

No project probes declared for this phase (Android/Gradle phase; verification is the JUnit suite + `assembleDebug`, both green). N/A.

### Requirements Coverage

| Requirement | Source Plan(s) | Description | Status | Evidence |
|-------------|----------------|-------------|--------|----------|
| RIMP-01 | 01, 04, 05 | Browse saved/starred routes (name/distance/elevation) | ✓ SATISFIED (code) | getRoutes + list UI + rows; live list device-confirmable (A2) |
| RIMP-02 | 01, 04, 05 | Import route as GPX via Strava API | ✓ SATISFIED (code) | exportGpx → GpxParser.parse; live import device-confirmable |
| RIMP-03 | 01, 05 | Parse + Douglas-Peucker downsample to OSRM waypoints | ✓ SATISFIED | GpxParser + RouteDownsampler wired in onStravaRouteSelected; unit-locked |
| RIMP-04 | 05 | Preview imported route on phone map | ✓ SATISFIED (code) | previewImportedRoute Polyline + post{} fit; render device-confirmable |
| NAVV-01 | 03, 05 | Start turn-by-turn on imported route | ✓ SATISFIED (code) | startNavigationWithRoute path; glasses render device-only |
| NAVV-02 | 02, 03, 05 | Route line/arrows/TTS via OSRM via-routing; graceful follow-route degrade | ✓ SATISFIED (code) | getRouteVia + filterArriveSteps + synthetic follow-route step; single-leg + fallback device-only |
| NAVV-03 | 03 | Off-route detection + auto-recalc for Strava routes | ✓ SATISFIED (code) | rerouteThroughRemainingWaypoints shape-preserving; live off-route device-only |

No orphaned requirements — REQUIREMENTS.md maps exactly RIMP-01..04 + NAVV-01..03 to Phase 4, all claimed by plans.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| (none) | — | No TBD/FIXME/XXX debt markers in any modified file | — | Completion is auditable |
| (none) | — | No TODO/HACK/PLACEHOLDER/coming-soon/not-implemented | — | No stubbed behavior |
| OsrmClient.kt | 172 | `return RouteResult(emptyList(), ...)` | ℹ️ Info | NOT a stub — documented guard for literally-empty input (`if (downsampled.isEmpty())`); GpxParser `points.isEmpty()` upstream prevents reaching it with real data |

### Concurrent Review Warnings (04-REVIEW.md — 0 Critical / 4 Warnings, being fixed on `gsd-reviewfix/04-05-558`)

These are the reviewer's own findings. The review explicitly states "The findings below are latent defects, not blockers." I independently confirmed each is UNFIXED in the current tree AND that none breaks a Phase-4 must-have truth. They are surfaced here for the human's awareness; a reviewfix branch + recovery-pending marker show they are actively in flight.

| ID | File | Concern | Fixed in tree? | Blocks a must-have? | Assessment |
|----|------|---------|----------------|---------------------|------------|
| WR-01 | NavigationManager.kt:300-304 | Reroute Thread{} publishes 5 @Volatile fields non-atomically; main-thread reader can observe a torn route state → possible spurious onStepChanged/premature onArrived during the brief window | No (raw writes then post confirmed) | No | Reviewer traced: no IndexOutOfBounds (the `currentStepIndex < steps.size-1` guard blocks the only indexing path); worst case is a transient spurious step during a reroute window. Import path is main-thread and unaffected. Latent correctness polish, not a goal failure |
| WR-02 | MainActivity.kt:542-547, 1161-1162 | `zoomToBoundingBox` on a single/identical-waypoint route yields a zero-span box (invalid zoom / possible no-op or throw inside tile math) | No (0 latitudeSpan guards) | No | Triggers only on a degenerate 1-point/coincident route (follow-route of a 1-trkpt GPX, or a trivial OSRM geometry). Normal routes are unaffected; the preview simply may mis-fit on a pathological route. Latent edge case |
| WR-03 | RouteDownsampler.kt:32 | perpM cosine uses segment-start latitude (not midpoint); on long early DP passes the single-cos linearization under-measures perpendicular distance and could drop a switchback | No (lat0 = aLat confirmed) | No | Reduces DP shape fidelity on very long segments in the worst case; the ≤200 cap + 15m epsilon + device A4 epsilon-tuning are the safety net. RouteDownsamplerTest (incl. switchback preservation) is green. Fidelity polish, device-tunable |
| WR-04 | MainActivity.kt onDestroy | osmdroid preview + nav MapViews never `onDetach()`ed; per-import overlay churn retains tile/bitmap state across the Activity lifecycle | No (0 onDetach) | No | Resource-lifecycle leak over repeated import cycles, not a functional/goal defect. onResume/onPause are wired. Latent leak |

### Human Verification Required (the batched device gate — plan 04-06)

The seven items in the frontmatter `human_verification` block are the on-device confirmations. They exist because the live manifestation of the ROADMAP SCs (real-route list, on-map preview render, glasses route-line + guidance, single-leg logcat, switchback butterfly avoidance, off-route reroute shape, follow-route fallback on-glasses) cannot be observed from static code — and because plan 04-06 deliberately batches the pending Phase-3 live Authorize tap into the same phone session. The code implementing every one of these is VERIFIED and unit-locked; only the physical-device confirmation remains.

### Gaps Summary

**No code gaps.** All 5 ROADMAP Success Criteria are implemented and verified at the code level, all 7 requirements are satisfied, the full test suite + build gate pass (exit 0), all referenced commits exist, and the 6 plan summaries are internally consistent with the code (getRouteVia/buildFollowRouteResult/startNavigationWithRoute/GpxParser/RouteDownsampler all present and wired exactly as the summaries claim). The 4 review warnings are latent defects (reviewer-confirmed non-blockers, actively being fixed concurrently) and none breaks a must-have truth.

The phase is **code-complete**. The remaining work is the batched on-device verification (plan 04-06), which is a legitimately pending, deliberately-deferred device gate (bundled with the Phase-3 live Authorize) — surfaced here as `human_needed` rather than `passed`, and never as a code gap.

---

_Verified: 2026-07-03 (code-level only, per instruction; device verification 04-06 pending a phone unlock)_
_Verifier: Claude (gsd-verifier)_

## Device verification (2026-07-03)

**Status: PASSED on hardware.** Executed on the real OPPO phone `3B164G01Y7L00000` + Rokid glasses `1901092544802583`.

Real Strava routes were listed and imported (e.g. **Milpitas 25.4 km**); OSRM via-point routing produced a single-leg route; the **route line + real turn-by-turn render on the glasses** ("Turn right onto Innovation Drive"). Off-route → **follow-route fallback confirmed** when OSRM/deviation conditions hit. All 5 code-verifiable success criteria plus the on-device SCs (single-leg / route-line-on-glasses / follow-route degrade) are confirmed on hardware; the phase is closed. (Depended on the Phase-3 live Authorize, which was completed in the same device session.)

_Device verification recorded: 2026-07-03_
