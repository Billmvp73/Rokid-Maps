---
phase: 04-strava-route-import-navigation
reviewed: 2026-07-03T00:00:00Z
depth: standard
files_reviewed: 9
files_reviewed_list:
  - phone/src/main/java/com/rokid/hud/phone/OsrmClient.kt
  - phone/src/main/java/com/rokid/hud/phone/NavigationManager.kt
  - phone/src/main/java/com/rokid/hud/phone/HudStreamingService.kt
  - phone/src/main/java/com/rokid/hud/phone/strava/GpxParser.kt
  - phone/src/main/java/com/rokid/hud/phone/strava/RouteDownsampler.kt
  - phone/src/main/java/com/rokid/hud/phone/strava/StravaApiClient.kt
  - phone/src/main/java/com/rokid/hud/phone/strava/StravaModels.kt
  - phone/src/main/java/com/rokid/hud/phone/MainActivity.kt
  - phone/src/main/res/layout/activity_main.xml
  - phone/src/main/res/layout/item_strava_route.xml
findings:
  critical: 0
  warning: 4
  info: 4
  total: 8
status: issues_found
---

# Phase 4: Code Review Report

**Reviewed:** 2026-07-03
**Depth:** standard
**Files Reviewed:** 9 (+2 layouts)
**Status:** issues_found

## Summary

Reviewed the Phase 4 delta: OSRM via-point routing (`getRouteVia`, `waypoints=0;{last}`, arrive-filter), the `NavigationManager` waypoint path + `@Volatile` race fix + shape-preserving reroute, the `HudStreamingService` passthrough, the Strava import stack (`GpxParser`, `RouteDownsampler`, `StravaApiClient`, `StravaModels`), and the `MainActivity` route-list/import/preview/START-NAVIGATION flow.

The security posture is solid: no hardcoded secrets, XXE is defended (XmlPullParser resolves no external entities, DOCTYPE processing left off), coordinates are validated before reaching OSRM/map, `HttpLoggingInterceptor` is pinned to `Level.BASIC` (no header/token leakage), 429 is surfaced without a retry loop, and the never-rethrow convention holds throughout. The three focus-area invariants I was asked to verify hold: (1) the reroute slices `routeWaypoints` with a freshly-derived nearest index clamped to `lastIndex` — never `currentStepIndex`/`steps`; (2) follow-route mode always injects one synthetic step, so `sendStepsList`'s empty-steps early-return is closed upstream; (3) OSRM coordinate order is `lng,lat` and `waypoints=0;{size-1}` indexing is correct.

The findings below are latent defects, not blockers: the highest-value ones are a multi-field visibility gap on the reroute thread (WR-01), a degenerate `BoundingBox` risk on single/identical-waypoint routes (WR-02), and a `perpM` projection-origin bug in Douglas-Peucker that under-measures perpendicular distance on long segments (WR-03).

## Warnings

### WR-01: Reroute thread publishes 5 related `@Volatile` fields non-atomically; main-thread reader can observe a torn route state

**File:** `phone/src/main/java/com/rokid/hud/phone/NavigationManager.kt:300-304` (writer) vs `:135-201` (reader)
**Issue:** `rerouteThroughRemainingWaypoints` runs on a background `Thread{}` and writes five interdependent fields with no lock:
```kotlin
routeWaypoints = result.waypoints
steps = result.steps
currentStepIndex = 0
nextWaypointIndex = 0
followRoute = false
```
`@Volatile` guarantees per-field visibility but NOT atomic group publication. `onLocationUpdate` (main thread, 1 Hz FLP callback) reads `steps`, `followRoute`, `routeWaypoints`, and `currentStepIndex` at different points in the same pass. A GPS fix landing mid-write can observe a mixed state — e.g. new `steps` with `followRoute` still `true`, or a new (small) `steps` list alongside a not-yet-reset large `currentStepIndex`. Traced outcomes: no IndexOutOfBounds (the `currentStepIndex < steps.size - 1` guard at line 153 blocks the only indexing path), but a spurious `onStepChanged`/premature `onArrived` can fire during the brief window. The `startNavigationWithRoute` import path is NOT affected because it executes on the main thread (button tap) and cannot interleave with the main-thread FLP callback — but the reroute path genuinely can. The class doc (lines 33-38, 90-95) reasons only about single-field happens-before and does not address the multi-field tear.
**Fix:** Publish the route atomically. Either (a) bundle the mutable route state into a single immutable holder and make one `@Volatile` reference assignment the sole publish point (mirrors the `HudState.copy()` discipline already used on the glasses side), or (b) since both the reroute-completion writes and `onLocationUpdate` reads can be marshalled to the main looper, `mainHandler.post { ... }` the five field writes so they land on the same thread that reads them:
```kotlin
mainHandler.post {
    routeWaypoints = result.waypoints; steps = result.steps
    currentStepIndex = 0; nextWaypointIndex = 0; followRoute = false
    callback.onRouteCalculated(...); if (result.steps.isNotEmpty()) callback.onStepChanged(...)
}
```

### WR-02: `zoomToBoundingBox` on a single-point / all-identical-waypoint route yields a zero-span BoundingBox (invalid zoom / possible crash)

**File:** `phone/src/main/java/com/rokid/hud/phone/MainActivity.kt:542-547` (preview) and `:1161-1162` (nav map)
**Issue:** `BoundingBox.fromGeoPoints(geoPoints)` produces a zero-area box when the route reduces to a single waypoint or all waypoints are coincident. `buildFollowRouteResult` returns exactly one waypoint when the downsampled GPX has one point (a 1-`trkpt` GPX passes the `points.isEmpty()` guard at MainActivity:491), and `getRouteVia`/OSRM can return a 1-coordinate geometry for a trivial route. osmdroid 6.1.18's `zoomToBoundingBox` divides by the latitude/longitude span and takes `Math.log` of the ratio; a zero span produces `Infinity`/`NaN` zoom, which at best no-ops with the route off-screen and at worst throws inside the tile-scaling math. The preview path is the more likely trigger since follow-route fallback is the exact case that yields degenerate geometry.
**Fix:** Guard for a degenerate box before zooming; fall back to `setCenter` + a fixed zoom:
```kotlin
val box = BoundingBox.fromGeoPoints(geoPoints)
stravaRoutePreviewMap.post {
    if (box.latitudeSpan > 1e-6 && box.longitudeSpanWithDateLine > 1e-6) {
        stravaRoutePreviewMap.zoomToBoundingBox(box, false)
    } else {
        stravaRoutePreviewMap.controller.setCenter(geoPoints.first())
        stravaRoutePreviewMap.controller.setZoom(16.0)
    }
    stravaRoutePreviewMap.invalidate()
}
```
Apply the same guard in `updateNavMap` (line 1161).

### WR-03: Douglas-Peucker `perpM` projects the point onto the wrong origin, under-measuring perpendicular distance and dropping shape detail on long segments

**File:** `phone/src/main/java/com/rokid/hud/phone/strava/RouteDownsampler.kt:27-42`
**Issue:** The equirectangular projection uses segment start `a` as the origin for both `x()` and `y()` and takes `cos0` at `aLat` only. That part is fine. The defect is subtler and is in the intent vs. behavior of the epsilon comparison combined with the projection's linearization error growing with segment length: `x(lng) = toRadians(lng - aLng) * cos0 * R` uses a *single* `cos(aLat)` scale for the whole segment. For a long DP segment spanning a large latitude delta (early iterations bridge the entire track — `lo=0, hi=size-1`), `cos(lat)` at the far end differs materially from `cos(aLat)`, so the projected `bx`/`px` are distorted and the perpendicular distance is computed against a warped segment. This *under-estimates* `maxD` for points far from `a`, so DP can prematurely stop subdividing and drop a genuine switchback in the first passes. The RESEARCH note (lines 8-11) benchmarks the equirectangular error at *city scale on a 400 m segment* — but the first DP passes operate on segments spanning the entire route (potentially tens of km), where the single-`cos` linearization error is orders of magnitude larger than the sub-mm claim.
**Fix:** Use the segment *midpoint* latitude for the cosine scale so the linearization error is symmetric and minimized across the segment:
```kotlin
val lat0 = Math.toRadians((aLat + bLat) / 2.0)
val cos0 = Math.cos(lat0)
```
Keep `a` as the projection origin. This bounds the distortion on the long early-pass segments without changing the per-point trig cost.

### WR-04: `stravaRoutePreviewMap` (osmdroid) is never detached; per-import overlay churn leaks tile-render state across the Activity lifecycle

**File:** `phone/src/main/java/com/rokid/hud/phone/MainActivity.kt:897-926` (lifecycle) and `:533-541` (per-import overlay add)
**Issue:** `stravaRoutePreviewMap.onResume()`/`onPause()` are wired (lines 898, 926), but there is no `onDetach()` in `onDestroy` for either map view, and every import calls `overlays.removeIf { it is Polyline }` then `overlays.add(line)`. osmdroid's `MapView` holds a tile-provider thread pool and bitmap cache that survive `onPause`; without `onDetach()` on teardown these are retained until GC, and repeated import/preview cycles accumulate `Polyline` bitmap state. This is a resource-lifecycle correctness gap (not the perf class): the preview map is made `VISIBLE` on first import and never torn down. `ActivitySummaryActivity` (a sibling osmdroid screen) is the reference for the correct pattern.
**Fix:** Add to `onDestroy`:
```kotlin
override fun onDestroy() {
    if (::navMapView.isInitialized) navMapView.onDetach()
    if (::stravaRoutePreviewMap.isInitialized) stravaRoutePreviewMap.onDetach()
    super.onDestroy()
}
```
(Confirm an existing `onDestroy` override; if present, fold these in rather than adding a second.)

## Info

### IN-01: `getRouteVia` builds the full coordinate URL with no upper-bound assertion; a caller that bypasses the 200-cap silently risks a rejected URL

**File:** `phone/src/main/java/com/rokid/hud/phone/OsrmClient.kt:143-147`
**Issue:** `buildViaUrl` joins every point into the path. The 200-point ceiling is enforced only in `RouteDownsampler.downsampleForRoute` upstream; `getRouteVia`/`buildViaUrl` themselves have no guard. The reroute path (`rerouteThroughRemainingWaypoints`) feeds `listOf(current) + remaining`, which is bounded by the original downsample, so this is safe today — but the invariant is implicit and load-bearing (a future caller passing raw GPX points would produce a multi-KB URL the public host rejects, surfacing as a generic OSRM failure). The doc comment acknowledges this ("the caller must reduce the via-point count") but the code does not defend it.
**Fix:** Add a defensive cap or `require(points.size <= 500)` at the top of `buildViaUrl`, or `.take(500)` with a `Log.w`, so the contract is enforced at the seam rather than trusted.

### IN-02: Follow-route mode re-broadcasts `step` + `steps_list` to glasses on every 1 Hz fix

**File:** `phone/src/main/java/com/rokid/hud/phone/NavigationManager.kt:230` → `HudStreamingService.kt:309-313`
**Issue:** `onFollowRouteUpdate` calls `callback.onStepChanged("Follow route", "straight", distToNext)` on every location update. The service `onStepChanged` handler both `sendStep(...)` and `sendStepsList()` — so two BT messages per second flow to the glasses for the entire follow-route ride, even though only the distance changes. TTS is de-duplicated (`BluetoothAudioRouter.speak` guards `instruction == lastSpokenInstruction`, line 109) so there is no audio spam, and the phone UI live-distance update is intended. This is a bandwidth/log-noise concern, not a correctness bug (and perf is out of v1 scope), but the `sendStepsList()` re-send in particular is redundant every tick since the step list never changes in follow-route mode.
**Fix:** In the service `onStepChanged`, skip `sendStepsList()` when the step content is unchanged, or throttle the follow-route re-emit in `onFollowRouteUpdate` to only fire when `distToNext` crosses a meaningful delta (e.g. ≥5 m change).

### IN-03: `renderStravaRoutes` uses `route?.type == 2` for the icon but `typeLabel()` for the text — two independent type-decode sites

**File:** `phone/src/main/java/com/rokid/hud/phone/MainActivity.kt:434` vs `StravaModels.kt:110-114`
**Issue:** The row icon is chosen with an inline `if (route?.type == 2) "🏃" else "🚴"` while the label comes from `route?.typeLabel()`. The mapping is duplicated: `typeLabel()` treats `null`/unknown as `"Route"`, but the icon logic collapses everything that is not `2` (including `null` and unknown types) to the bike glyph. A route with a null/unrecognized `type` shows a bike icon next to a "Route" label — a minor inconsistency, and a second place to update if the type enum grows.
**Fix:** Add a `typeIcon()` companion to `StravaRoute` beside `typeLabel()` and call it from the adapter, so the icon and label decode the same field once.

### IN-04: `adjustStravaRoutesListHeight` hardcodes the 58dp row height as a magic number, decoupled from `item_strava_route.xml`

**File:** `phone/src/main/java/com/rokid/hud/phone/MainActivity.kt:444-451`
**Issue:** `val itemH = (58 * resources.displayMetrics.density).toInt()` assumes each row is 58dp, but `item_strava_route.xml` sizes rows via `paddingVertical="14dp"` + content (icon 32dp) with no fixed height. If the item layout changes (e.g. a two-line name wraps to `maxLines="2"`), the computed ListView height will clip or over-allocate rows. The `minOf(count, 6)` cap is fine; the 58 is the fragile part.
**Fix:** Measure the first inflated row (`adapter.getView(0, null, list).measure(...); measuredHeight`) or move the list into the outer ScrollView with `wrap_content` and drop the manual height math.

---

_Reviewed: 2026-07-03_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_

---

## Fix Log

**Fixed at:** 2026-07-03
**Fixer:** Claude (gsd-code-fixer)
**Verification:** full `testDebugUnitTest assembleDebug` exits 0 after all fixes (phone-debug.apk built, all unit tests pass).

| Finding | Status | Commit | Applied fix |
|---------|--------|--------|-------------|
| WR-01 | fixed (requires human verification — concurrency change) | `c25fb86` | Moved the 5 interdependent route-field writes (`routeWaypoints`, `steps`, `currentStepIndex`, `nextWaypointIndex`, `followRoute`) + `onRouteCalculated`/`onStepChanged` emission in BOTH the success and follow-route-fallback branches of `rerouteThroughRemainingWaypoints` into `mainHandler.post { }`, serializing them with the main-thread `onLocationUpdate` reader (same pattern `startNavigationWithRoute` uses). The network `getRouteVia`/`buildFollowRouteResult` call stays on the background Thread. Destination-only `calculateRoute` path untouched. |
| WR-02 | fixed | `4870ced` | In `previewImportedRoute`, filter waypoints to finite lat/lng, then guard the deferred fit: if `< 2` points OR `box.latitudeSpan < 1e-6 || box.longitudeSpanWithDateLine < 1e-6`, fall back to `controller.setCenter(first) + setZoom(15.0)` instead of `zoomToBoundingBox` (which NaNs on zero span). Scoped to `previewImportedRoute` per disposition. |
| WR-03 | fixed | `8c0dab1` | In `RouteDownsampler.perpM`, replaced the single fixed origin-latitude cosine (`cos(aLat)`) with a per-call cosine at the segment MIDPOINT latitude (`cos((aLat+bLat)/2)`), bounding the equirectangular linearization error on the long early DP passes. `a` remains the projection origin; kept pure/JVM. Added `longHighLatitudeSegmentUsesMidpointLongitudeScale` test that exercises a ~111km high-latitude segment (fails under the old start-lat scaling); the existing switchback-preservation test still passes. |
| WR-04 | fixed | `69c8eb8` | Added `navMapView.onDetach()` and `stravaRoutePreviewMap.onDetach()` (each `::`-init-guarded) to `MainActivity.onDestroy` to release osmdroid tile-provider threads and bitmap cache. |

**Info findings (IN-01 … IN-04): deferred** — outside the fix scope (critical + warning only).
