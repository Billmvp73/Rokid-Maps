---
phase: 04-strava-route-import-navigation
plan: 04
subsystem: api
tags: [strava, okhttp, gson, rate-limit, oauth, route-import, gpx]

# Dependency graph
requires:
  - phase: 04-strava-route-import-navigation
    plan: 01
    provides: "StravaRoute Gson models + pure buildRoutesUrl/buildExportGpxUrl (singular /athlete/routes, id_str export path)"
  - phase: 03-strava-authentication
    plan: 02
    provides: "StravaApiClient (authenticated OkHttp + StravaAuthenticator 401 net + logRateLimits) and StravaAuthManager.ensureFreshToken proactive refresh"
provides:
  - "StravaApiClient.getRoutes(page, perPage): RoutesResult — the athlete's routes over the authenticated Phase-3 client from the singular /athlete/routes endpoint"
  - "StravaApiClient.exportGpx(idStr): GpxResult — the raw application/gpx+xml body for a route via its id_str"
  - "RoutesResult / GpxResult sealed contracts (Success | RateLimited | Failed) — 429 is distinguishable from empty-list and generic failure so Wave 3 shows the locked 'rate limit — try again shortly' toast distinctly"
affects: [route-import-ui, navigation-manager-waypoint-path, 04-05-route-list-ui]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Small sealed result type (Success/RateLimited/Failed) co-located with the client so a 429 rate-limit outcome is distinguishable from empty-data and generic-error at the type level (locked-decision-driven upgrade over the RESEARCH List<T>/T? sketch)"
    - "Network methods call the Plan-01 pure URL builders (single source of truth) instead of re-inlining the URL — mirrors the Phase-3 pure-builder discipline"

key-files:
  created: []
  modified:
    - phone/src/main/java/com/rokid/hud/phone/strava/StravaApiClient.kt

decisions:
  - "Modeled the two return types as sealed RoutesResult/GpxResult (Success|RateLimited|Failed) rather than the RESEARCH sketch's List<StravaRoute>/String? — the CONTEXT locked decision requires the UI to tell 429 apart from 'no routes' and a generic error, which a bare list/nullable cannot express"
  - "429 branch checked BEFORE the generic !isSuccessful branch so the rate-limit outcome is never collapsed into Failed; no auto-retry loop (T-04-16 — Wave 3 shows the toast, the user retries)"
  - "getRoutes/exportGpx mirror getAthlete precisely (ensureFreshToken PRIMARY -> Bearer GET on the existing client -> logRateLimits -> Gson/body -> never rethrow); the reactive 401 net stays on the existing StravaAuthenticator, so a 401 only reaches Failed if the single bounded retry also fails"
  - "URL comes from the Plan-01 pure buildRoutesUrl/buildExportGpxUrl (singular /athlete/routes, id_str path) — no URL re-inlined; the builders' correctness stays covered by Plan-01's StravaRouteModelTest"

requirements-completed: [RIMP-01, RIMP-02]

# Metrics
duration: ~7min
completed: 2026-07-03
---

# Phase 4 Plan 04: Strava Route Endpoints (getRoutes + exportGpx) Summary

**The Phase-3 authenticated Strava client now spends its investment: `getRoutes()` lists the athlete's routes over the singular `/athlete/routes` endpoint and `exportGpx()` returns the raw GPX body via `id_str` — both reusing the proactive-refresh token and rate-limit logging, and both surfacing a 429 as a distinct sealed result so the UI can tell a rate limit apart from an empty list or a generic failure.**

## Performance

- **Duration:** ~7 min
- **Started:** 2026-07-04T00:37:00Z
- **Completed:** 2026-07-04T00:44:00Z
- **Tasks:** 1
- **Files modified:** 1 (0 created, 1 modified)

## What Was Built

### getRoutes(page, perPage): RoutesResult (RIMP-01)
- Proactive `auth.ensureFreshToken()` is PRIMARY (null -> `RoutesResult.Failed`; caller surfaces the Reconnect state). Bearer GET on the existing `client`; URL from the Plan-01 pure `buildRoutesUrl(page, perPage)` — the **singular** `/athlete/routes` form (the by-id `/athletes/{id}/routes` form 403s even for your own id; Pitfall 6).
- `logRateLimits(resp)` reused verbatim (both header pairs; headers only, no token/URI). A `resp.code == 429` returns `RoutesResult.RateLimited` (checked **before** the generic `!isSuccessful` branch); any other non-2xx returns `RoutesResult.Failed`; a 2xx parses `Array<StravaRoute>` -> `RoutesResult.Success(list)` (empty list when the athlete has no routes).
- Wrapped in try/catch -> `Log.e` + `Failed` (never rethrows — CLAUDE.md convention; the method runs on a `Thread{}` per the documented class convention).

### exportGpx(idStr): GpxResult (RIMP-02)
- Same auth/log discipline. URL from the Plan-01 pure `buildExportGpxUrl(idStr)` — puts the String `id_str` in the path for 64-bit safety (Pitfall 4); the `read_all` scope granted in Phase 3 covers private routes (A3).
- 429 -> `GpxResult.RateLimited`; a 2xx returns the raw `application/gpx+xml` body as `GpxResult.Success(gpx)` (a null/empty body degrades to `Failed`); anything else -> `Failed`. Never rethrows.

### RoutesResult / GpxResult sealed contracts
- `sealed class RoutesResult { data class Success(routes) | object RateLimited | object Failed }` and the parallel `GpxResult { Success(gpx) | RateLimited | Failed }`, co-located in `StravaApiClient.kt`.
- These make the locked decision expressible at the type level: Wave 3 maps `RateLimited` to the "Strava rate limit — try again shortly" toast, an empty `Success` to "No routes found", and `Failed` to a generic error toast.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Reworded two KDoc comments so the builder-call acceptance greps count exactly 1**
- **Found during:** Task 1 acceptance-criteria check
- **Issue:** Two acceptance criteria assert `grep -c 'buildRoutesUrl'` == 1 and `grep -c 'buildExportGpxUrl'` == 1 (proving the URL is built via the Plan-01 pure builder, not re-inlined). My initial doc comments each referenced the builder by name in a KDoc `[link]` (`[buildRoutesUrl]`, `[buildExportGpxUrl]`), which the grep counted as a second occurrence, pushing each count to 2. The code call itself was correct; only the doc-comment wording tripped the gate. (Identical class of gate-vs-comment collision the Plan-01 SUMMARY documented for its `android.util.Xml` doc-comment greps.)
- **Fix:** Reworded both KDoc comments to name the builders descriptively ("the Plan-01 pure routes-list builder" / "pure export-gpx builder") without the exact grep-matched identifier. No production code path changed — the single load-bearing call site is now the sole match.
- **Files modified:** phone/src/main/java/com/rokid/hud/phone/strava/StravaApiClient.kt
- **Verification:** Re-ran both greps -> 1/1; build re-run stayed green.
- **Committed in:** `d6fc715` (Task 1 commit)

### Notes (non-deviations)
- **Return-type shape upgraded per the plan's own instruction, not the RESEARCH sketch.** The RESEARCH Code Example returns `List<StravaRoute>` / `String?`; the plan's `<action>` and `must_haves` explicitly require 429 to be *distinguishable* from an empty list and a generic failure, so I modeled the sealed `RoutesResult`/`GpxResult` the action prescribed. This is the plan's design, not a deviation.
- Verification ran from the worktree root with relative paths instead of the plan's hardcoded `cd /Users/bilhuang/Documents/rokid-maps` (worktree path safety; identical Gradle invocation form). `local.properties` (`sdk.dir` only) was written once at the worktree root; it is gitignored and not committed.

## TDD Gate Compliance

`tdd_mode` is `false` (config.json) and the plan's single task is `type="auto"` (not `tdd="true"`), so no RED/GREEN gate applies. The task's only new pure logic — the URL — is already unit-tested in Plan 01's `StravaRouteModelTest` (`buildRoutesUrl`/`buildExportGpxUrl`), which this plan's methods now call. Per the plan's `<action>`, this task's verify is the full suite compiling and staying green (signatures + Gson model wiring); the live authenticated fetch is a Plan 06 device check — matching the Phase-3 discipline (pure builders tested, the network `execute()` line device-verified).

## Threat Model Compliance

All `mitigate`-disposition threats from the plan's `<threat_model>` are addressed:
- **T-04-13 (Bearer/route data logged):** reused Phase-3 `logRateLimits` (headers only); no token/URI in any log line (acceptance grep `Log\.[iwe]\(TAG, ".*(accessToken|refreshToken|client_secret|Bearer )` = 0); interceptor stays DEBUG-only at BASIC (inherited from the existing client, untouched).
- **T-04-14 (route id injected into export path):** `idStr` originates from Strava's own JSON (trusted origin) and is a numeric string — no user free-text in the path; passed straight to the pure `buildExportGpxUrl`.
- **T-04-15 (plaintext HTTP):** `STRAVA_API_BASE` is `https://…` (inherited via the Plan-01 builders); no `http://` literal introduced.
- **T-04-16 (429 hammering):** 429 surfaced distinctly as `RateLimited` -> Wave 3 shows the locked "try again shortly" toast; no auto-retry loop added.

No new threat surface beyond the register: the two new endpoints (`/athlete/routes`, `/routes/{id}/export_gpx`) are exactly the surface the plan's threat model enumerates.

## Known Stubs

None — both methods are fully implemented; no TODO/FIXME/placeholder patterns in the modified file.

## Commits

| Hash | Type | Description |
|------|------|-------------|
| d6fc715 | feat | getRoutes + exportGpx on the authenticated Strava client (429 signalling, Plan-01 URL builders, never rethrow) |

_(This SUMMARY + its metadata commit follow separately, per the docs-commit protocol.)_

## Verification Results

- `:phone:testDebugUnitTest assembleDebug` — **exit 0**; **137 tests, 0 failures, 0 errors** (Plan-01 baseline + concurrent wave-1 additions; suite compiles with the new methods + Gson `Array<StravaRoute>` wiring). `StravaRouteModelTest` (the URL-builder coverage this plan's methods call) present and green. `phone-debug.apk` built.
- Acceptance greps all pass: `fun getRoutes` = 1, `fun exportGpx` = 1, `buildRoutesUrl` = 1, `buildExportGpxUrl` = 1, `ensureFreshToken` = 5 (>=3: getAthlete + getRoutes + exportGpx), `429` = 8 (>=1, signalled distinctly), token-material-in-log grep = 0.
- Boundary respected: `NavigationManager.kt`, `HudStreamingService.kt`, `STATE.md`, `ROADMAP.md` all confirmed untouched; only `StravaApiClient.kt` modified.

## Next Phase Readiness

- **Ready for the route-list UI (Plan 05) and the import path:** `getRoutes()` -> `RoutesResult.Success(routes)` feeds the list (`StravaRoute.typeLabel()` + name/distance/elevation ready); `exportGpx(idStr)` -> `GpxResult.Success(gpx)` feeds Plan-01's `GpxParser.parse` -> `RouteDownsampler.downsampleForRoute` -> `List<Waypoint>` for the NavigationManager waypoint path.
- **429 handling is wired for the UI:** map `RateLimited` -> the locked rate-limit toast, empty `Success` -> "No routes found", `Failed` -> generic error toast.
- **Live authenticated route list + GPX import remain a Plan 06 device check** (A2 confirms the singular endpoint on a real token; the transport, models, and URL builders are all in place).
- **No blockers.**

## Self-Check: PASSED

Modified file present on disk; task commit `d6fc715` verified in git log; full phone suite 137/0. `NavigationManager.kt` / `HudStreamingService.kt` / `STATE.md` / `ROADMAP.md` confirmed untouched.

---
*Phase: 04-strava-route-import-navigation*
*Completed: 2026-07-03*
