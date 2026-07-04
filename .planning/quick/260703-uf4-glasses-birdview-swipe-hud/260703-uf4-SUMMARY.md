---
phase: quick-260703-uf4
plan: 260703-uf4
subsystem: ui
tags: [android, kotlin, glasses-hud, bluetooth-protocol, org-json, custom-view, gesturedetector, birdview, fit-to-bounds]
status: complete

# Dependency graph
requires:
  - phase: Phase 2 (HUD glasses consumption)
    provides: HudState / MapLayoutMode tap-cycle, HudView green-monochrome rendering, BluetoothClient message dispatch
  - phase: Phase 4 (navigation + reroute)
    provides: NavigationManager route/reroute callback pipeline, RouteMessage BT broadcast
provides:
  - Backward-compatible route `full` flag (protocol constant + RouteMessage field + codec)
  - Phone flag source wiring (nav-start = full=true, reroutes = full=false) threaded through onRouteCalculated
  - Glasses WHOLE_ROUTE layout mode + separate wholeRoute birdview source (reroute-immune)
  - 4-way forward tap/swipe cycle + reverse cycle (toggleLayoutBack)
  - RouteBounds fit-to-bounds projector (android-free) + HudView.drawWholeRoute birdview
  - Swipe gestures: onFling (HudView) + DPAD/SYSTEM_NAVIGATION keys (HudActivity.dispatchKeyEvent)
affects: [glasses-hud, navigation, bluetooth-protocol]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Optional boolean protocol flag via optBoolean(..., false) — legacy-JSON backward-compatible, no new message type"
    - "Android-free projection helper (RouteBounds) as a pure-JVM testable seam, mirroring SportFormat discipline"
    - "Callback-parameter discriminator (full: Boolean) to distinguish first-broadcast from reroute at the single sendRoute edge"

key-files:
  created:
    - glasses/src/main/java/com/rokid/hud/glasses/RouteBounds.kt
    - glasses/src/test/java/com/rokid/hud/glasses/RouteBoundsTest.kt
  modified:
    - shared/src/main/java/com/rokid/hud/shared/protocol/ProtocolConstants.kt
    - shared/src/main/java/com/rokid/hud/shared/protocol/Messages.kt
    - shared/src/main/java/com/rokid/hud/shared/protocol/ProtocolCodec.kt
    - phone/src/main/java/com/rokid/hud/phone/NavigationManager.kt
    - phone/src/main/java/com/rokid/hud/phone/HudStreamingService.kt
    - phone/src/main/java/com/rokid/hud/phone/MainActivity.kt
    - glasses/src/main/java/com/rokid/hud/glasses/HudState.kt
    - glasses/src/main/java/com/rokid/hud/glasses/BluetoothClient.kt
    - glasses/src/main/java/com/rokid/hud/glasses/HudView.kt
    - glasses/src/main/java/com/rokid/hud/glasses/HudActivity.kt

key-decisions:
  - "Route `full` is a flag on the existing route message (optBoolean default false), NOT a new message type — BT message-type count stays 13, legacy glasses builds decode to false"
  - "D4 no-clobber enforced at the BluetoothClient copy() seam: full=true seeds wholeRoute AND updates the live route; full=false updates only the live route, leaving the birdview source pinned to the original route"
  - "Swipe covered two ways (onFling + dpad/nav keys) because the touchpad is a DPAD/keyboard device — getevent on ROKID,PSOC-TP-R confirmed KEY_LEFT/RIGHT/UP/DOWN, so DPAD_LEFT/RIGHT drive prev/next with UP/DOWN + SYSTEM_NAVIGATION_* as belt-and-braces"
  - "WHOLE_ROUTE birdview renders over black (no tiles at fit-zoom) — prioritizes full route SHAPE per D1; north-up (no bearing rotation), unlike the live map"

patterns-established:
  - "Pattern: optional BT protocol flags degrade to a safe default via optBoolean/optString so old builds on either side never break"
  - "Pattern: extract projection/format math into an android-free object for pure-JVM tests; leave the Canvas draw for hardware screencap verification"

requirements-completed: [UF4-D1, UF4-D2, UF4-D3, UF4-D4]

# Metrics
duration: 13min
completed: 2026-07-03
---

# Phase quick-260703-uf4: Glasses Whole-Route Birdview + 4-Page Swipe HUD Summary

**Bird's-eye WHOLE_ROUTE HUD page (fit-to-bounds, green-monochrome, north-up) with a swipeable 4-page touchpad cycle, backed by a backward-compatible route `full` flag so the birdview always shows the ORIGINAL imported route and reroutes never clobber it.**

## Performance

- **Duration:** ~13 min
- **Started:** 2026-07-03T22:07 (execution handoff)
- **Completed:** 2026-07-03T22:20:42-0700 (final task commit)
- **Tasks:** 3 (all TDD)
- **Files modified:** 15 unique source/test files (incl. 2 new glasses files + 1 pre-existing Strava test aligned)

## Accomplishments
- **D4 protocol + phone flag source:** `FIELD_ROUTE_FULL` constant + `RouteMessage.full` (default false) + encode/decode via `optBoolean(..., false)`; phone threads `full` through the (now 5-arg) `onRouteCalculated` — nav-start paths send `full=true`, all four reroute paths send `full=false`. Message-type count stays 13; legacy route JSON without the key decodes to false.
- **D2/D3 glasses state:** `MapLayoutMode.WHOLE_ROUTE`; `toggleLayout()` is now the 4-way forward cycle (FULL → CORNER → SPORT → WHOLE_ROUTE → FULL) and new `toggleLayoutBack()` is the exact reverse; MINI_* collapse to FULL in both directions. `BluetoothClient` exposes `toggleLayoutBack()` and stores a `full=true` route into a separate `wholeRoute` (birdview source) while always updating the live route.
- **D1 birdview + D3 gestures:** new android-free `RouteBounds.fit` projector (aspect-preserving, north-up, degenerate-bbox-safe); `HudView.drawWholeRoute` draws the full route line fit-to-bounds with start dot / end ring / current player-arrow (green-only, black background). `onFling` (horizontal-dominant, thresholded) + `HudActivity.dispatchKeyEvent` DPAD_LEFT/RIGHT/UP/DOWN + SYSTEM_NAVIGATION_* drive prev/next; single-tap-forward and double-tap-quit unchanged.
- **219 JVM tests green** (shared 10, phone 187, glasses 22), `assembleDebug` exit 0 across all three tasks — including the D4 no-clobber invariant and the route-flag round-trip / legacy-default guards.

## Task Commits

Each task was committed atomically (TDD: test + implementation folded per task):

1. **Task 1: Shared route `full` flag end-to-end + phone nav-start/reroute wiring** — `04ac985` (feat)
2. **Task 2: Glasses HudState WHOLE_ROUTE + wholeRoute + 4-way & reverse cycle + BT store** — `1e6aec4` (feat)
3. **Task 3: Glasses birdview rendering (RouteBounds + drawWholeRoute) + swipe gestures** — `b5f03ed` (feat)

_Note: Task 1 also aligned two stale StravaAuthUrlTest assertions (see Deviations)._

## Files Created/Modified
- `shared/.../protocol/ProtocolConstants.kt` — added `FIELD_ROUTE_FULL = "full"` (MessageType untouched, still 13)
- `shared/.../protocol/Messages.kt` — `RouteMessage.full: Boolean = false` (last field, backward-compatible)
- `shared/.../protocol/ProtocolCodec.kt` — encode `full`; decode via `optBoolean(FIELD_ROUTE_FULL, false)`
- `shared/.../protocol/ProtocolCodecTest.kt` — route flag round-trip (true/false) + legacy-default-false
- `phone/.../NavigationManager.kt` — `onRouteCalculated` gains `full`; nav-start = true, reroutes = false; `calculateRoute` gains a `full` param
- `phone/.../HudStreamingService.kt` — override + `sendRoute` migrated to 5/4 args; stop-nav clears with `full=false`
- `phone/.../MainActivity.kt` — override migrated to 5 args (UI ignores `full`)
- `phone/.../NavigationRouteTest.kt` — `FakeNavigationCallback` override migrated to 5 args
- `phone/.../strava/StravaAuthUrlTest.kt` — aligned stale redirect_uri assertions to shipped source (deviation)
- `glasses/.../HudState.kt` — `WHOLE_ROUTE` enum + `wholeRoute` field; 4-way `toggleLayout` + reverse `toggleLayoutBack`
- `glasses/.../BluetoothClient.kt` — `toggleLayoutBack()`; Route branch seeds `wholeRoute` only on `full=true`
- `glasses/.../HudView.kt` — WHOLE_ROUTE draw + `"[ ROUTE ]"` label; `onFling` + `onSwipeForward/Back` lambdas + `SWIPE_MIN_VELOCITY`; `drawWholeRoute`
- `glasses/.../HudActivity.kt` — wire swipe callbacks; DPAD/SYSTEM_NAVIGATION key handling in `dispatchKeyEvent`
- `glasses/.../RouteBounds.kt` (new) — android-free fit-to-bounds `Projector`
- `glasses/.../RouteBoundsTest.kt` (new) — single-point/two-point/empty projection tests

## Decisions Made
- `full` is a flag on the existing route message, not a new type (BT surface unchanged at 13 types; old builds degrade to false).
- D4 no-clobber lives at the `HudState.copy()` seam and is unit-proven at pure JVM (no Robolectric/Looper needed).
- Swipe handled via both `onFling` and dpad/nav keys; hardware-confirmed the ROKID touchpad emits DPAD keys, so DPAD_LEFT/RIGHT are primary with UP/DOWN + SYSTEM_NAVIGATION_* as belt-and-braces. KEYCODE_ENTER (quit) and KEYCODE_BACK left untouched.
- Birdview renders over black (no tiles cached at fit-zoom) and is north-up — the priority per D1 is the full route shape, not a tiled basemap.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Aligned stale StravaAuthUrlTest redirect_uri assertions to the shipped source constant**
- **Found during:** Task 1 (build+test gate — 2 of 187 phone tests failed, blocking the required exit-0 gate)
- **Issue:** `StravaAuthUrlTest.buildAuthorizeUrlProducesExactMobileAuthorizeUrl` and `lockedConstantsMatchContextDecisions` asserted the redirect URI as `rokidhud://callback`, but commit `ea09e21` ("fix(03): correct Strava redirect_uri host to registered callback domain") had already changed the shipped source constant `StravaOAuth.REDIRECT_URI` to `rokidhud://rokidhud` (host must equal Strava's registered Authorization Callback Domain, live-verified 2026-07-03) without updating the test. These two tests had been red since `ea09e21`, which predates this quick task, and are on a compile path completely disjoint from this task's changes (route protocol + navigation).
- **Fix:** Updated the two stale test expectations (`rokidhud%3A%2F%2Frokidhud` in the authorize URL and `rokidhud://rokidhud` in the constants assertion) to match the authoritative shipped source. Source behavior unchanged.
- **Files modified:** phone/src/test/java/com/rokid/hud/phone/strava/StravaAuthUrlTest.kt
- **Verification:** StravaAuthUrlTest 14/0/0; full `testDebugUnitTest` + `assembleDebug` exit 0.
- **Committed in:** `04ac985` (Task 1 commit). Rationale also logged to `deferred-items.md`.

**2. [Rule 3 - Blocking] Added transitional WHOLE_ROUTE branches to HudView's exhaustive `when` blocks in Task 2**
- **Found during:** Task 2 (adding `WHOLE_ROUTE` to the enum broke the two exhaustive `when (state.layoutMode)` blocks in HudView, failing compilation and thus Task 2's gate)
- **Issue:** The enum and the view are in the same module, so Task 2's gate cannot pass unless HudView compiles, but the real `drawWholeRoute` belongs to Task 3.
- **Fix:** In Task 2 added the final `"[ ROUTE ]"` mode-indicator label and a one-line transitional onDraw branch (`WHOLE_ROUTE -> drawFullScreenLayout(...)`), clearly commented as replaced by Task 3. Task 3 swapped that single line for the real `drawWholeRoute(...)`. No shipped stub remained after Task 3.
- **Files modified:** glasses/src/main/java/com/rokid/hud/glasses/HudView.kt
- **Verification:** Task 2 gate exit 0; Task 3 replaced the transitional line and re-verified exit 0.
- **Committed in:** `1e6aec4` (Task 2 transitional) → `b5f03ed` (Task 3 real rendering)

---

**Total deviations:** 2 (both Rule 3 blocking, both necessary to keep each task's required exit-0 gate meaningful)
**Impact on plan:** No scope creep and no product-behavior change. Deviation 1 fixed a pre-existing red baseline unrelated to this task; deviation 2 is an intra-plan Task-2→Task-3 compile-ordering bridge fully resolved within the same plan.

## Issues Encountered
- The build+test gate uses `-q`, which prints nothing on a clean success — verified true exit status by capturing `$?` to a log file and separately asserting zero `<failure>` elements across all module test-results and `BUILD SUCCESSFUL` on a non-`-q` final run.

## Known Stubs
None. The Task-2 transitional onDraw branch was replaced by the real `drawWholeRoute` in Task 3 (commit `b5f03ed`); no placeholder rendering ships. `wholeRoute` is wired to a live data source (the `full=true` route message), not mock/empty data.

## User Setup Required
None — no external service configuration required. Pure Kotlin edits against existing dependencies (junit + org.json already on the classpath).

## Next Phase Readiness
- Code-complete and JVM-verified (219 tests green, `assembleDebug` exit 0). Ready for the orchestrator's on-device deploy + on-glasses screencap verification of all 4 pages (FULL / CORNER / SPORT / WHOLE_ROUTE) and the physical swipe input (which key the touchpad actually emits).
- Hardware-side open item (by design, deferred to the orchestrator): confirm the WHOLE_ROUTE birdview renders the full route shape with start/end/current markers, and confirm DPAD_LEFT/RIGHT alone can reach all four pages on the real touchpad.

## Self-Check: PASSED

- Created files verified present: `RouteBounds.kt`, `RouteBoundsTest.kt`, this SUMMARY, `deferred-items.md`.
- Task commits verified in git log: `04ac985`, `1e6aec4`, `b5f03ed`.
- Invariant re-confirmed: `ProtocolConstants.MessageType` still declares exactly 13 entries.
- Final full gate (`testDebugUnitTest assembleDebug`, no `-q`): `BUILD SUCCESSFUL`, exit 0, 219 tests, 0 failures.

---
*Phase: quick-260703-uf4*
*Completed: 2026-07-03*
