# Phase 4: Strava Route Import + Navigation - Research

**Researched:** 2026-07-03
**Domain:** Strava routes API + GPX parsing + Douglas-Peucker + OSRM via-point routing + NavigationManager surgery (Android/Kotlin, no coroutines)
**Confidence:** HIGH (OSRM mechanics, DP math, OSRM profile behavior, and XmlPullParser testability empirically verified this session; Strava route schema from official docs + changelog)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**GPX → navigable route (the core):**
- Parse GPX `<trkpt lat lon [ele]>` via android.util.Xml / XmlPullParser (no GPX lib)
- Douglas-Peucker downsample: epsilon starts at **15m** (mid of the 10–20m band; tune empirically on-device with a real route); target **≤200 output points**
- OSRM routing: extend OsrmClient with a NEW multi-coordinate method that builds `/route/v1/{profile}/{lng,lat};{lng,lat};...` from ALL downsampled points, with `waypoints=0;{lastIndex}` (silent via points → single leg, real turn-by-turn, NO spurious mid-route arrivals), `overview=full&geometries=geojson&steps=true`
- Defensive filter: drop any zero-distance `arrive` step that is NOT the final step
- Fallback: if OSRM via-point routing fails/errors, navigate the raw downsampled GPX waypoints in "follow route" mode — route line + distance-to-next-waypoint, no turn arrows/TTS; a clear "Follow route" label instead of maneuver text
- OSRM profile: use `driving` on the existing public instance (works for on-road cycling/running); follow-route fallback covers off-road/bike-path snapping failures. Note FOSSGIS bike/foot instances (routing.openstreetmap.de) as a future STATE todo — do NOT add a second routing host in v1.

**NavigationManager waypoint-accepting path (new):**
- Add `startNavigationWithRoute(waypoints, steps)` (or overload) that accepts a pre-computed route + steps and skips the internal OSRM A→B call; existing proximity step-advancement + off-route + arrival logic reused unchanged
- Fix the known steps/currentStepIndex data race IN THIS PHASE (STATE assigns it here): make them @Volatile / guarded, matching the ActivitySessionManager thread-safety discipline from Phase 1
- Follow-route mode is a NavigationManager flag: when steps are absent, emit "Follow route" + distance to next downsampled waypoint instead of maneuver-driven step text

**Route browsing UI:**
- New "MY STRAVA ROUTES" section/list, shown on MainActivity only when Strava-connected (reuse Phase-3 connection state); each row: route name, distance (imperial-aware), elevation gain
- Tap a route → fetch GPX → downsample → preview the route line on the existing osmdroid navMapView (RIMP-04) with a "START NAVIGATION" button
- Loading/empty/error states: spinner while fetching; "No routes found" empty; toast on API/parse error
- Recording auto-start is NOT coupled to route navigation (REC-01 opt-in remains)

**Rate-limit awareness:**
- Log X-RateLimit / X-ReadRateLimit usage on the routes-list + gpx-export calls (headers already surfaced by Phase-3 client); no hard enforcement in v1, but a 429 → toast "Strava rate limit — try again shortly"

### Claude's Discretion
- Douglas-Peucker implementation details (iterative vs recursive; perpendicular-distance math)
- Exact route-list row layout (follow existing list-item conventions)
- Whether preview reuses the live-nav MapView or a dedicated preview instance
- StravaRouteImporter / GpxParser class split within com.rokid.hud.phone.strava

### Deferred Ideas (OUT OF SCOPE)
- Second OSRM host (FOSSGIS bike/foot) for off-road snapping — v1.x; follow-route fallback covers it for now
- Turn-by-turn from GPX bearing inference — explicitly rejected (ARCHITECTURE anti-pattern 2)
- Route creation/editing — out of scope (use Strava)
- Caching imported routes offline — v2
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| RIMP-01 | Browse saved/starred Strava routes (name, distance, elevation) | `GET /athlete/routes` schema verified (Standard Stack §Strava Routes API); route-list UI insertion in MainActivity below search area |
| RIMP-02 | Import a selected route as GPX via Strava API | `GET /routes/{id}/export_gpx` returns `application/gpx+xml` body, `read_all` scope (already granted Phase 3) [CITED: developers.strava.com] |
| RIMP-03 | Parse + Douglas-Peucker downsample GPX → OSRM-compatible waypoints | GPX parse via `XmlPullParserFactory` (JVM-testable §Validation); DP equirectangular math verified accurate to 0.0009% at city scale (Code Examples §DP) |
| RIMP-04 | Preview imported route on phone map before navigation | Reuse existing `updateNavMap()` Polyline + BoundingBox pattern (MainActivity:893-912); layout-timing gotcha documented (Pitfall 5) |
| NAVV-01 | Start turn-by-turn navigation following an imported route | `NavigationManager.startNavigationWithRoute()` new path; feeds existing `onRouteCalculated`→`sendRoute`+`sendStepsList` broadcast (Architecture §NavigationManager surgery) |
| NAVV-02 | Existing route-line/arrows/TTS work via OSRM via-point routing; degrade to follow-route when OSRM unavailable | OSRM `waypoints=0;{last}` single-leg verified live (Code Examples §OSRM); follow-route emits a synthetic step so `sendStepsList` still broadcasts (Pitfall 3) |
| NAVV-03 | Off-route detection + auto-recalc work for Strava routes | Reuses `nearestRouteDistance` (80m) + `calculateRoute` reroute; **reroute path must re-target the route, not a single dest** (Pitfall 2 — the sharp edge) |
</phase_requirements>

## Summary

This phase has almost zero library-discovery surface: every dependency it needs (OkHttp, Gson, osmdroid, `org.json`, JUnit) is already on the classpath, and the one XML-parsing capability it adds (`XmlPullParser`) ships inside Android itself. The real research payload is **exact version-specific mechanics** — because three of this phase's core behaviors were already wrong once in the design docs (the "waypoint reuse works unchanged" claim that STATE corrected), and getting them wrong again silently breaks navigation on-device with no test to catch it.

The four load-bearing facts, all **empirically verified against the live public OSRM instance this session**: (1) `waypoints=0;{last}` collapses an N-coordinate route from N-1 legs (N-1 spurious `arrive`/`depart` pairs) to a **single leg with exactly one depart + one final zero-distance arrive** — verified with a 4-coord request; (2) a **200-coordinate GET URL is 4,091 bytes and returns HTTP 200 in <1s**, with the practical ceiling at ~500 coords (10KB) / rejected by 600 (12KB) — so ≤200 has a 2.5x margin and **no POST is needed**; (3) the public `router.project-osrm.org` instance **serves ONLY the car profile and silently ignores the profile path segment** — `driving`, `cycling`, `foot` return byte-identical distance AND duration, which sharpens the locked "use driving" decision into "profile choice is irrelevant on this host, follow-route is the only real bike-path mitigation"; (4) **equirectangular perpendicular distance is accurate to 0.0009% vs haversine cross-track at city scale**, so Douglas-Peucker should use the flat projection, not haversine.

The second research payload is the **NavigationManager surgery seam**. The existing wiring (verified by reading HudStreamingService:302-324, 571-584) means the glasses render entirely from `onRouteCalculated`/`onStepChanged` callbacks that fire `sendRoute` + `sendStepsList`. Two consequences dominate the plan: **(a)** the new waypoint path must fire the *same* `onRouteCalculated(waypoints, dist, dur, steps)` callback so the glasses pipeline is untouched; **(b)** `sendStepsList()` early-returns when `nav.steps.isEmpty()` (HudStreamingService:577), so **follow-route mode must populate `steps` with at least one synthetic "Follow route" step** or the glasses show no guidance at all. The data-race fix (STATE-assigned to this phase) is small: `steps` and `currentStepIndex` are written on a `Thread{}` inside `calculateRoute` and read on the main-thread location callback + by `sendStepsList`; make both `@Volatile` and assign the pair atomically.

**Primary recommendation:** Split into `GpxParser` (pure, XmlPullParserFactory-based, JVM-testable), a `RouteDownsampler` (pure equirectangular Douglas-Peucker, iterative stack), `OsrmClient.getRouteVia(points)` (new multi-coord method with `waypoints=0;{last}` + non-final-arrive filter), `StravaApiClient.getRoutes()/exportGpx()` (extend the Phase-3 authenticated client), and `NavigationManager.startNavigationWithRoute(waypoints, steps)` (+ `@Volatile` race fix + follow-route synthetic step). Every seam except the two Android-glue points is a pure function with a unit test.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Browse Strava routes | API/Backend (phone→Strava) | Phone UI (list) | Strava owns the route data; phone is the authenticated client |
| Fetch + parse GPX | Phone (pure logic) | — | GPX body is opaque text; parsing is a pure transform, no network after fetch |
| Douglas-Peucker downsample | Phone (pure geometry) | — | Pure function: points in → points out; zero Android/network coupling |
| OSRM via-point routing | API/Backend (phone→OSRM) | Phone (URL builder is pure) | OSRM computes road-snapping + steps; phone builds the URL and parses |
| Navigation state / step advance | Phone (NavigationManager) | Glasses (render only) | Phone is the brain (existing architecture); glasses are a stateless display |
| Route line + guidance render | Glasses (HudView) | — | Glasses consume RouteMessage/StepMessage/StepsListMessage unchanged |
| Route preview map | Phone UI (osmdroid) | — | Preview is phone-only; glasses only render during active navigation |

**Boundary note:** The glasses side needs **ZERO changes** this phase. Everything the glasses render (route Polyline, maneuver arrows, TTS, upcoming-steps list) is already driven by the three existing BT messages, which the phone already broadcasts from the NavigationCallback. Follow-route mode is "just" sending `instruction="Follow route"` as the StepMessage/StepInfo string — no new message type, no new field.

## Standard Stack

### Core (all already on the classpath — verify versions only, no new production deps)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| OkHttp | 4.12.0 | Strava routes-list + GPX-export HTTP calls (via the Phase-3 `StravaApiClient`) | Already the authenticated transport; Authenticator + rate-limit logging built [VERIFIED: phone/build.gradle.kts:73] |
| Gson | 2.10.1 | Deserialize `GET /athlete/routes` JSON → `StravaRoute` models | STACK decision: Gson for Strava responses only; matches existing `StravaModels.kt` pattern [VERIFIED: phone/build.gradle.kts:75] |
| `android.util.Xml` / `org.xmlpull.v1.*` | Android built-in (kxml2) | Parse the GPX `<trkpt>` stream | Ships inside Android (AOSP libcore is kxml2); no GPX library needed per STACK [CITED: developer.android.com/reference/android/util/Xml] |
| osmdroid | 6.1.18 | Route-line preview Polyline on `navMapView` | Already used for live-nav map; `updateNavMap()` pattern reused [VERIFIED: phone/build.gradle.kts:65] |
| `org.json` | 20231013 (runtime) / 20231013 (test) | OSRM GeoJSON response parse (existing `OsrmClient` convention) | Existing `OsrmClient.parseRouteResponse` uses it; the multi-coord method reuses the same parse [VERIFIED: phone/build.gradle.kts:82] |

### Supporting (test classpath only — ONE new test dependency)
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `net.sf.kxml:kxml2` | 2.3.0 | JVM XmlPullParser impl so `GpxParser` unit tests run on plain JVM (mirrors the existing `org.json:json` test-dep trick) | `testImplementation` ONLY — production uses Android's built-in kxml2. See Validation §GPX Testability. |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| XmlPullParserFactory + kxml2 test dep | Robolectric | Robolectric is a heavier runtime (~seconds/test, shadows android.jar); the project deliberately runs pure-JVM tests (`ProtocolCodecTest` comment: "the mockable android.jar stubs would throw"). kxml2-as-test-dep keeps the fast pure-JVM discipline. [ASSUMED — based on project's established no-Robolectric pattern] |
| `driving` profile on public OSRM | `cycling`/`foot` on public OSRM | **Irrelevant on router.project-osrm.org — profile is ignored, always car (VERIFIED live).** A real bike/foot profile requires a different host (FOSSGIS routing.openstreetmap.de), which is a deferred v1.x decision. |
| Manual GET URL (≤200 coords) | OSRM POST body | POST unnecessary: 200 coords = 4KB GET, works fine (VERIFIED). POST would deviate from the existing `HttpURLConnection` GET convention in OsrmClient for zero benefit. |

**Installation:** No production dependency changes. One test dependency:
```kotlin
// phone/build.gradle.kts — testImplementation block
testImplementation("net.sf.kxml:kxml2:2.3.0")   // JVM XmlPullParser for GpxParser tests
```

**Version verification (run before finalizing the plan):**
```bash
# OkHttp / Gson / osmdroid / security-crypto / browser are pinned & verified in Phase 3 — no re-check needed.
# Only confirm kxml2 resolves on the test classpath:
./gradlew :phone:dependencies --configuration testDebugUnitTestRuntimeClasspath | grep kxml2
```

## Package Legitimacy Audit

> Java/Maven artifacts (not npm/PyPI). slopcheck is npm/pip-oriented and was unavailable this session; verified via Maven Central directly.

| Package | Registry | Age | Downloads | Source Repo | slopcheck | Disposition |
|---------|----------|-----|-----------|-------------|-----------|-------------|
| `net.sf.kxml:kxml2` | Maven Central | 2.3.0 published 2009 (16 yrs) | ubiquitous (bundled in Android AOSP) | kxml2.sourceforge.net / github mirrors | unavailable | Approved — test-classpath only; this IS Android's own XML impl [VERIFIED: search.maven.org] |
| OkHttp 4.12.0 | Maven Central | mature | 100M+ | github.com/square/okhttp | unavailable | Already in tree (Phase 3) |
| Gson 2.10.1 | Maven Central | mature | 100M+ | github.com/google/gson | unavailable | Already in tree (Phase 3) |
| osmdroid 6.1.18 | Maven Central | mature | widely used | github.com/osmdroid/osmdroid | unavailable | Already in tree (pre-existing) |

**Packages removed due to slopcheck [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none

*slopcheck (a pip/npm tool) could not be installed this session. The single new dependency (`net.sf.kxml:kxml2`) is not a novel supply-chain surface: it is the reference XmlPull implementation that Android's own runtime bundles (AOSP `libcore/xml`), verified present on Maven Central at 2.3.0. It is added as `testImplementation` only, so it never ships in either APK. Because slopcheck was unavailable, the planner MAY still gate the kxml2 line behind a `checkpoint:human-verify` before adding it, though the Maven Central verification + AOSP-bundled provenance make this low-risk.*

## Architecture Patterns

### System Architecture Diagram

```
  ┌─────────────────────────────────────────────────────────────────────────┐
  │  MainActivity (phone UI)                                                  │
  │                                                                           │
  │  [Strava connected?]──yes──► "MY STRAVA ROUTES" list                      │
  │         │ (Phase-3 isConnected())         │ tap row                       │
  │         no → hide section                 ▼                               │
  │                              Thread{} StravaApiClient.getRoutes(page)      │
  │                                           │ List<StravaRoute>             │
  │                                           ▼ render rows (name/dist/elev)  │
  │                              tap route ─► Thread{} exportGpx(idStr)        │
  │                                           │ GPX body (String)             │
  │                                           ▼                               │
  │                              GpxParser.parse(gpx) ─► List<TrackPoint>      │
  │                                           │ (pure, XmlPullParserFactory)  │
  │                                           ▼                               │
  │                              RouteDownsampler.simplify(pts, eps=15m)       │
  │                                           │ ≤200 LatLng (pure equirect DP) │
  │                                           ▼                               │
  │                              OsrmClient.getRouteVia(downsampled)           │
  │                              URL: /route/v1/driving/{lng,lat;...}          │
  │                                   ?overview=full&geometries=geojson        │
  │                                   &steps=true&waypoints=0;{last}           │
  │                                           │                                │
  │                          ┌────────────────┴────────────────┐              │
  │                     OSRM Ok                            OSRM fail/error     │
  │                          │ RouteResult                       │            │
  │                          │ (waypoints + real steps,          │ fallback   │
  │                          │  non-final arrive filtered)       ▼            │
  │                          │                     RouteResult(downsampled     │
  │                          │                       waypoints, [synthetic     │
  │                          │                       "Follow route" step])     │
  │                          └────────────────┬────────────────┘              │
  │                                           ▼                                │
  │                      updateNavMap() preview Polyline + fit bounds (RIMP-04)│
  │                                  + "START NAVIGATION" button               │
  │                                           │ tap                            │
  │                                           ▼                                │
  │                 service.startNavigationWithRoute(waypoints, steps)         │
  └───────────────────────────────────────────┼───────────────────────────────┘
                                               ▼
  ┌─────────────────────────────────────────────────────────────────────────┐
  │  HudStreamingService.navigationManager (existing NavigationCallback)      │
  │   onRouteCalculated(wp,dist,dur,steps)──► sendRoute()  + sendStepsList()   │
  │   onStepChanged(instr,manv,dist)      ──► sendStep()   + sendStepsList()   │
  │   onLocationUpdate (1Hz) advances currentStepIndex / off-route reroute     │
  └───────────────────────────────────────────┼───────────────────────────────┘
                                               ▼  BT SPP (route / step / steps_list)
  ┌─────────────────────────────────────────────────────────────────────────┐
  │  GLASSES (unchanged): HudView draws route Polyline + arrows + TTS          │
  └─────────────────────────────────────────────────────────────────────────┘
```

### Recommended Project Structure
```
phone/src/main/java/com/rokid/hud/phone/
├── strava/
│   ├── StravaApiClient.kt      # EXTEND: + getRoutes(page), + exportGpx(idStr)
│   ├── StravaModels.kt         # EXTEND: + StravaRoute, StravaRouteMap (Gson)
│   ├── GpxParser.kt            # NEW: pure, XmlPullParserFactory-based
│   └── RouteDownsampler.kt     # NEW: pure equirectangular Douglas-Peucker
├── OsrmClient.kt               # EXTEND: + getRouteVia(points): RouteResult
├── NavigationManager.kt        # EXTEND: + startNavigationWithRoute(); @Volatile race fix; follow-route flag
├── HudStreamingService.kt      # EXTEND: + startNavigationWithRoute() passthrough
└── MainActivity.kt             # EXTEND: route-list UI + preview + START NAVIGATION wiring

phone/src/test/java/com/rokid/hud/phone/
├── strava/
│   ├── GpxParserTest.kt        # NEW (needs kxml2 test dep)
│   ├── RouteDownsamplerTest.kt # NEW (pure)
│   └── StravaRouteModelTest.kt # NEW (Gson parse: int type/sub_type, id_str)
└── OsrmViaUrlTest.kt           # NEW: URL builder + non-final-arrive filter (pure)
```

### Pattern 1: OSRM Via-Point URL Builder (the verified core)
**What:** Build a single OSRM request from all downsampled points with the intermediate points as *silent* via points.
**When to use:** Any pre-planned multi-point route (Strava GPX). NOT for the existing 2-point A→B (leave `getRoute` untouched).
**Example (verified request shape):**
```kotlin
// Source: live router.project-osrm.org verification (this session).
// getRoute (existing) is 2-point; getRouteVia is the NEW multi-coord path.
fun buildViaUrl(points: List<Waypoint>): String {
    // OSRM coordinate order is lng,lat (NOT lat,lng) — matches existing getRoute.
    val coords = points.joinToString(";") { "${it.longitude},${it.latitude}" }
    val last = points.size - 1
    // waypoints=0;{last} => only first & last are leg boundaries; the rest silently shape the route.
    // Result (VERIFIED): single leg, ONE depart + ONE final zero-distance arrive, real turn steps between.
    return "$BASE_URL/$coords?overview=full&geometries=geojson&steps=true&waypoints=0;$last"
}
// BASE_URL stays "https://router.project-osrm.org/route/v1/driving" — the profile is IGNORED
// by the public host anyway (VERIFIED: driving/cycling/foot return byte-identical routes).
```
**Verified evidence (4-coord request):**
- WITHOUT `waypoints=`: 3 legs, `depart` count = 3, `arrive` count = 3 (spurious mid-route arrivals — the bug)
- WITH `waypoints=0;3`: **1 leg, 1 depart, 1 arrive** (arrive is final, distance=0)

### Pattern 2: Non-Final Zero-Distance Arrive Filter (belt-and-braces)
**What:** Even with `waypoints=0;{last}`, defensively drop any `arrive`-type step that has `distance == 0.0` and is not the last step in the list.
**When to use:** In `getRouteVia`'s step-parse loop, before returning `RouteResult`.
**Example:**
```kotlin
// Applied after flattening legs→steps (there is only ONE leg with waypoints=0;last,
// but the filter is cheap insurance against any OSRM host that ignores the param).
val filtered = steps.filterIndexed { i, s ->
    !(s.maneuver == "arrive" && s.distance == 0.0 && i != steps.lastIndex)
}
```
**Note:** With `waypoints=0;{last}` verified to produce exactly one final arrive, this filter is a no-op on the happy path — but it makes the code correct even if a future host or a param typo reintroduces mid-route arrives. Keep it (matches the locked decision).

### Pattern 3: Follow-Route Fallback with a Synthetic Step (the non-obvious wiring)
**What:** When OSRM via-point routing throws/errors, build a `RouteResult` from the downsampled waypoints with a **single synthetic "Follow route" step** so the glasses pipeline still broadcasts guidance.
**When to use:** In the `catch` around `getRouteVia`, OR as a NavigationManager follow-route flag.
**Why the synthetic step is mandatory:** `HudStreamingService.sendStepsList()` early-returns when `nav.steps.isEmpty()` (HudStreamingService:577). An empty steps list = glasses render the route line but NO instruction text / no steps_list. A one-element `[NavigationStep("Follow route", "straight", distToNextWaypoint, ...)]` keeps `onStepChanged` + `sendStepsList` firing.
```kotlin
// Fallback RouteResult (in importer, or NavigationManager follow-route mode):
val followStep = NavigationStep(
    instruction = "Follow route",
    maneuver = "straight",
    distance = 0.0,               // updated live to distance-to-next-downsampled-waypoint
    duration = 0.0,
    locationLat = waypoints.first().latitude,
    locationLng = waypoints.first().longitude
)
RouteResult(waypoints = downsampled, steps = listOf(followStep), totalDistance, totalDuration = 0.0)
```
In follow-route mode, `onLocationUpdate` should update the "Follow route" step's live distance to the next *downsampled waypoint* (not a maneuver point) and re-emit `onStepChanged`. See Pitfall 3.

### Pattern 4: Equirectangular Douglas-Peucker (iterative)
**What:** Simplify the GPX track to ≤200 points using point-to-segment perpendicular distance in a local flat projection.
**Why equirectangular, not haversine:** VERIFIED this session — at city scale, equirectangular point-to-segment distance differs from haversine cross-track by **0.0009%** (sub-millimeter on a 400m segment), far under a 15m epsilon. Flat projection is faster (one `cos(lat0)` per segment, no per-point trig) and accurate enough.
**Why iterative:** Strava tracks can be 10k+ points; naive recursion risks a deep stack. Use an explicit index-range stack.
**Example:** see Code Examples §Douglas-Peucker.

### Anti-Patterns to Avoid
- **Feeding the target `≤200` count as a naive every-Nth stride** — that destroys curve fidelity on switchbacks (PITFALLS Pitfall 3 under-density). DP by epsilon preserves shape; the ≤200 is a *safety cap*, applied by raising epsilon if DP still returns >200 (see Open Q1).
- **Inferring turns from GPX bearing changes** — explicitly rejected (ARCHITECTURE anti-pattern 2). Turn data comes from OSRM or not at all (follow-route).
- **Rerouting to a single destination on off-route** — the existing `calculateRoute(lat,lng,destLat,destLng)` is 2-point. For a Strava route, an off-route reroute that targets only the final dest **throws away the whole route shape** (Pitfall 2). The reroute must re-run via-point routing from current position through the remaining downsampled waypoints, OR (simpler v1) reroute current→routeEnd via `getRoute` as a graceful degrade. Decide in plan.
- **Adding a second OSRM host in v1** — deferred. `driving` on the public instance + follow-route only.
- **Using `android.util.Xml.newPullParser()` directly in `GpxParser`** — it throws `RuntimeException: Stub!` on the JVM test classpath, making the parser untestable. Use `XmlPullParserFactory.newInstance().newPullParser()` so kxml2 (test) and Android-builtin (prod) both satisfy it (Validation §GPX Testability).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| GPX XML parsing | A regex/string scanner for `<trkpt>` | `XmlPullParser` (Android built-in) | GPX has attributes, namespaces, nested `<ele>`, CDATA, self-closing tags; regex breaks on real-world files |
| Road-snapping + turn steps from a GPX line | Bearing-change turn inference | OSRM via-point routing | Without road-network data you cannot tell a real turn from a switchback or GPS noise (anti-pattern 2) |
| JSON deserialization for routes | Manual `org.json` field-picking for Strava | Gson typed models | STACK decision; `type`/`sub_type` are ints with domain meaning, `id_str` prevents 64-bit truncation — typed model documents this |
| Route-line rendering on preview | Custom Canvas overlay | osmdroid `Polyline` (existing `updateNavMap`) | Already built, already themed (#00E676, 12px); reuse verbatim |
| Perpendicular distance on a sphere | Haversine cross-track for DP | Equirectangular flat projection | VERIFIED accurate to 0.0009% at city scale; haversine cross-track is overkill and slower |

**Key insight:** This phase's failure mode is not "missing a library" — it is "getting a verified API contract subtly wrong and silently breaking navigation." Every hand-roll temptation here (GPX regex, bearing turns, manual JSON) has a documented failure the pitfalls already cataloged. The correct posture is: extend the existing verified seams (`OsrmClient`, `StravaApiClient`, `updateNavMap`), add two pure functions (parse, downsample), and change NavigationManager as little as possible.

## Runtime State Inventory

> Rename/refactor inventory — this phase is **additive (greenfield within existing modules)**, but it touches shared mutable navigation state, so the relevant "runtime state" is the in-memory NavigationManager race, not stored/OS state.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | **None** — no datastore keys renamed; imported routes are NOT persisted in v1 (caching deferred). GPX is fetched, downsampled, routed, discarded. | none |
| Live service config | **None** — no external service config embeds a renamed string. Strava app config (callback domain `rokidhud`, client id/secret) is unchanged from Phase 3. | none |
| OS-registered state | **None** — no Task Scheduler / launchd / pm2 / broadcast-receiver registrations added or renamed. | none |
| Secrets/env vars | **None** — reuses Phase-3 `BuildConfig.STRAVA_CLIENT_ID/SECRET` and the ESP `strava_auth` token store unchanged; no new secret keys. | none |
| Build artifacts | **One test-classpath dependency** (`net.sf.kxml:kxml2:2.3.0`) added — stale test build cache after the gradle change; `./gradlew :phone:testDebugUnitTest` reruns clean. No production APK artifact changes. | rerun tests after dep add |
| **In-memory shared state (the real item)** | `NavigationManager.steps` + `currentStepIndex` are data-raced: written on the `calculateRoute` `Thread{}`, read on the main-thread `onLocationUpdate` + by `HudStreamingService.sendStepsList()`. STATE assigns the fix to THIS phase. | `@Volatile` both fields; assign the (steps, index) pair together; the new `startNavigationWithRoute` sets them on the caller thread before firing `onRouteCalculated` |

**Nothing found in categories 1-5 beyond the kxml2 test dep** — verified by: no `SharedPreferences`/file writes in the new code paths, no manifest changes, no new BuildConfig fields, reuse of Phase-3 auth artifacts.

## Common Pitfalls

### Pitfall 1: Follow-route mode renders no guidance because steps is empty
**What goes wrong:** OSRM fails, fallback builds a `RouteResult` with `steps = emptyList()`, glasses show the route line but zero instruction text; user thinks navigation is broken.
**Why it happens:** `HudStreamingService.sendStepsList()` returns early on `nav.steps.isEmpty()` (HudStreamingService:577), and `NavigationManager.onLocationUpdate` returns early on `steps.isEmpty()` (NavigationManager:63) — so no `onStepChanged` ever fires.
**How to avoid:** Follow-route mode MUST inject at least one synthetic "Follow route" `NavigationStep` (Pattern 3). Its distance is updated live to the next downsampled waypoint.
**Warning signs:** Route Polyline visible on glasses, instruction area blank, no TTS, no steps_list broadcast in logcat.

### Pitfall 2: Off-route reroute discards the Strava route shape (NAVV-03 sharp edge)
**What goes wrong:** User strays 80m off a Strava loop; `calculateRoute(lat,lng,destLat,destLng)` fires and reroutes to the *final destination as a 2-point A→B*, collapsing the beautiful route into a straight-ish OSRM path — the imported route is gone for the rest of the ride.
**Why it happens:** The existing reroute path is destination-only (`destLat/destLng`). A via-point route has no single "destination" semantics — its shape lives in the waypoints.
**How to avoid:** In the waypoint-accepting path, either (a) on off-route, re-run `getRouteVia` from current position through the *remaining* downsampled waypoints (best fidelity, more code), or (b) v1-simple: reroute current→lastWaypoint via 2-point `getRoute` and accept the shape loss until the user rejoins, or (c) in follow-route mode, just recompute distance-to-nearest-remaining-waypoint (no OSRM call). **This is a plan decision — flag it.** The forward-only `currentStepIndex` must be preserved either way (Pitfall 3 butterfly prevention).
**Warning signs:** After a wrong turn, the glasses route line snaps from curvy to straight; step count drops dramatically.

### Pitfall 3: Butterfly/switchback on dense or overlapping routes
**What goes wrong:** On a switchback or lollipop loop, `nearestRouteDistance` (off-route check, NavigationManager:144) matches the *wrong pass* of an overlapping segment, and step advancement races ahead because dense steps are <150m apart.
**Why it happens:** Two causes: (a) waypoints too dense (raw GPX) → steps <150m → rapid multi-advance; (b) overlapping segments confuse closest-point matching.
**How to avoid:** (a) Douglas-Peucker to ≤200 points spaces waypoints out (this is the primary fix — the whole reason for DP). (b) Preserve the EXISTING forward-only `currentStepIndex` (Phase-1 review confirmed it already only increments; NavigationManager:72-101 never decrements). Do NOT add index-rewind. (c) In follow-route mode, advance the "next downsampled waypoint" pointer forward-only too.
**Warning signs:** Navigation arrow flips at a switchback; off-route alarm fires while clearly on the line; next-waypoint distance jumps 200m→5m→150m.

### Pitfall 4: Strava `type`/`sub_type` decoded as strings; `id` truncated to 32-bit
**What goes wrong:** Gson model declares `type: String` → gets `1`/`2` as a number, or `id: Int` → truncates a 64-bit route id; export_gpx then 404s on a wrong id.
**Why it happens:** `type` and `sub_type` are **integers** in the Strava Route JSON (1=ride, 2=run; sub 1=road/2=mtb/3=cx/4=trail/5=mixed) [CITED: strava.github.io/api/v3/routes]. Route `id` is a 64-bit integer; Strava added `id_str` specifically because apps mishandle 64-bit ids (changelog 2020-06-05) [CITED: developers.strava.com/docs/changelog].
**How to avoid:** Model `type: Int?`, `subType: Int?`, and **use `id_str` (String) for the export_gpx URL** (mirrors the Phase-3 decision to use `id_str` for uploads). Map int→label for the UI ("Ride"/"Run") if desired.
**Warning signs:** Route names show but tapping 404s; sub-type filter never matches; ids look correct for small accounts but break for large route ids.

### Pitfall 5: osmdroid `zoomToBoundingBox` no-ops on the preview map (not laid out yet)
**What goes wrong:** Route imported, `updateNavMap` calls `zoomToBoundingBox` before the preview MapView has completed layout → map stays at default zoom/center, route off-screen.
**Why it happens:** `zoomToBoundingBox` needs the view's measured width/height; called too early (import happens before the nav panel is visible/laid out) it silently does nothing. `addOnFirstLayoutListener` fires too early per osmdroid issue #236/#337 [CITED: github.com/osmdroid/osmdroid/issues/236].
**How to avoid:** Defer the fit to after layout: `navMapView.post { navMapView.zoomToBoundingBox(box, false); navMapView.invalidate() }`. The existing live-nav `updateNavMap` gets away without this because the map is already visible+laid-out by the time a route calculates; the *preview* path may not be — so wrap the preview fit in `post{}`.
**Warning signs:** Preview shows a world map or wrong region; works on second import (map now laid out) but not first.

### Pitfall 6: `/athletes/{id}/routes` returns 403 even for your own id
**What goes wrong:** Using the documented path form `GET /athletes/{athlete_id}/routes` returns 403 Forbidden even with a valid token for the same athlete.
**Why it happens:** Well-reported Strava quirk — the by-id form is finicky; the reliable form for the current athlete's own routes is the singular `GET /athlete/routes` (no id in path) [CITED: communityhub.strava.com; corroborated by stravalib].
**How to avoid:** Use `GET https://www.strava.com/api/v3/athlete/routes?per_page=N&page=M` (singular `athlete`). `per_page` max is 200; default 30.
**Warning signs:** 403 on the routes list despite `getAthlete()` succeeding with the same token.

## Code Examples

### Douglas-Peucker (iterative, equirectangular) — pure, JVM-testable
```kotlin
// Source: RDP algorithm (en.wikipedia.org/wiki/Ramer-Douglas-Peucker_algorithm),
// equirectangular projection verified accurate to 0.0009% vs haversine at city scale (this session).
object RouteDownsampler {
    private const val R = 6_371_000.0

    /** Perpendicular distance (meters) from p to segment a→b via local equirectangular projection. */
    private fun perpM(
        pLat: Double, pLng: Double,
        aLat: Double, aLng: Double,
        bLat: Double, bLng: Double
    ): Double {
        val lat0 = Math.toRadians(aLat)
        val cos0 = Math.cos(lat0)
        fun x(lng: Double) = Math.toRadians(lng - aLng) * cos0 * R
        fun y(lat: Double) = Math.toRadians(lat - aLat) * R
        val px = x(pLng); val py = y(pLat)
        val bx = x(bLng); val by = y(bLat)
        val seg2 = bx * bx + by * by
        if (seg2 == 0.0) return Math.hypot(px, py)
        val t = ((px * bx + py * by) / seg2).coerceIn(0.0, 1.0)
        return Math.hypot(px - t * bx, py - t * by)
    }

    /** Iterative RDP. epsilonM in meters. Returns a subset (endpoints always kept). */
    fun simplify(points: List<Waypoint>, epsilonM: Double): List<Waypoint> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true; keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (lo, hi) = stack.removeLast()
            if (hi - lo < 2) continue
            var idx = -1; var maxD = -1.0
            for (i in lo + 1 until hi) {
                val d = perpM(
                    points[i].latitude, points[i].longitude,
                    points[lo].latitude, points[lo].longitude,
                    points[hi].latitude, points[hi].longitude
                )
                if (d > maxD) { maxD = d; idx = i }
            }
            if (maxD > epsilonM) {
                keep[idx] = true
                stack.addLast(lo to idx)
                stack.addLast(idx to hi)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }
}
```

### GPX parse via XmlPullParserFactory — pure, JVM-testable with kxml2
```kotlin
// Source: developer.android.com XmlPullParser guidance; XmlPullParserFactory
// (not android.util.Xml) so kxml2-on-test-classpath satisfies it on the JVM.
object GpxParser {
    /** Extracts ordered (lat,lng[,ele]) from all <trkpt> across all <trkseg>. Never throws. */
    fun parse(gpx: String): List<Waypoint> {
        val out = ArrayList<Waypoint>()
        try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            val parser = factory.newPullParser()
            parser.setInput(java.io.StringReader(gpx))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "trkpt") {
                    val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                    val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                    if (lat != null && lon != null) out.add(Waypoint(lat, lon))
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            android.util.Log.w("GpxParser", "GPX parse failed: ${e.message}")
        }
        return out
    }
}
// NOTE: multi-<trkseg> is handled implicitly — every <trkpt> anywhere is collected in order.
// The downsampled points cross trkseg boundaries; for v1 that is acceptable (single-day routes
// are one continuous track). If a route has intentional gaps, the DP line will bridge them —
// flag if the user's routes have multi-segment gaps (Open Q2).
```

### Strava route Gson models — int type/sub_type, id_str
```kotlin
// Source: strava.github.io/api/v3/routes + developers.strava.com changelog (id_str, 2020-06-05).
// ALL NULLABLE (Gson-via-Unsafe caveat, same as StravaModels.kt Phase 3).
data class StravaRoute(
    @SerializedName("id") val id: Long?,
    @SerializedName("id_str") val idStr: String?,          // USE THIS for export_gpx URL (64-bit safe)
    @SerializedName("name") val name: String?,
    @SerializedName("distance") val distance: Double?,      // meters
    @SerializedName("elevation_gain") val elevationGain: Double?, // meters
    @SerializedName("type") val type: Int?,                // 1=ride 2=run
    @SerializedName("sub_type") val subType: Int?,         // 1=road 2=mtb 3=cx 4=trail 5=mixed
    @SerializedName("private") val isPrivate: Boolean?,
    @SerializedName("starred") val starred: Boolean?,
    @SerializedName("map") val map: StravaRouteMap?
)
data class StravaRouteMap(
    @SerializedName("summary_polyline") val summaryPolyline: String?
)
```

### StravaApiClient extension — getRoutes + exportGpx
```kotlin
// EXTENDS the Phase-3 client (same auth/rate-limit discipline). BLOCKING, Thread{} only.
fun getRoutes(page: Int = 1, perPage: Int = 30): List<StravaRoute> {
    val token = auth.ensureFreshToken() ?: return emptyList()
    // Singular /athlete/routes (NOT /athletes/{id}/routes — that 403s; Pitfall 6).
    val req = Request.Builder()
        .url("$BASE/athlete/routes?per_page=$perPage&page=$page")
        .header("Authorization", "Bearer $token").build()
    return try {
        client.newCall(req).execute().use { resp ->
            logRateLimits(resp)                          // reuse Phase-3 private logger
            if (!resp.isSuccessful) { Log.w(TAG, "GET /athlete/routes ${resp.code}"); return emptyList() }
            val arr = gson.fromJson(resp.body?.string(), Array<StravaRoute>::class.java)
            arr?.toList() ?: emptyList()
        }
    } catch (e: Exception) { Log.e(TAG, "getRoutes failed: ${e.message}", e); emptyList() }
}

fun exportGpx(routeIdStr: String): String? {
    val token = auth.ensureFreshToken() ?: return null
    val req = Request.Builder()
        .url("$BASE/routes/$routeIdStr/export_gpx")   // read_all scope (granted Phase 3)
        .header("Authorization", "Bearer $token").build()
    return try {
        client.newCall(req).execute().use { resp ->
            logRateLimits(resp)
            if (!resp.isSuccessful) { Log.w(TAG, "export_gpx ${resp.code}"); return null }
            resp.body?.string()                          // raw GPX (application/gpx+xml)
        }
    } catch (e: Exception) { Log.e(TAG, "exportGpx failed: ${e.message}", e); null }
}
```

### NavigationManager waypoint path + @Volatile race fix
```kotlin
// EXTEND NavigationManager. Existing fields become @Volatile (STATE-assigned race fix).
@Volatile var steps: List<NavigationStep> = emptyList(); private set
@Volatile var currentStepIndex = 0; private set
@Volatile private var followRoute = false            // true when steps are synthetic (OSRM failed)

/** New: accept a pre-computed route + steps, skip the internal OSRM A→B call. */
fun startNavigationWithRoute(waypoints: List<Waypoint>, steps: List<NavigationStep>, followRouteMode: Boolean) {
    isNavigating = true
    this.followRoute = followRouteMode
    // Assign the (steps, index, waypoints) set on THIS thread before notifying — no Thread{} here,
    // so the write happens-before the callback; @Volatile guarantees the main-thread reader sees it.
    this.routeWaypoints = waypoints
    this.steps = steps
    this.currentStepIndex = 0
    if (waypoints.isNotEmpty()) { destLat = waypoints.last().latitude; destLng = waypoints.last().longitude }
    mainHandler.post {
        callback.onRouteCalculated(waypoints, /*dist*/0.0, /*dur*/0.0, steps)  // fill real totals from RouteResult
        if (steps.isNotEmpty()) callback.onStepChanged(steps[0].instruction, steps[0].maneuver, steps[0].distance)
    }
}
// In onLocationUpdate: when followRoute, instead of maneuver-point advancement, advance a
// forward-only "next downsampled waypoint" index and set the synthetic step's distance to it.
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| "Reuse NavigationManager waypoints unchanged for Strava routes" (original research claim) | Add a waypoint-accepting path; existing `startNavigation` is destination-only | STATE 2026-07-02 (verified against code) | This phase MUST add the new path, not reuse; the claim was false |
| Plain OSRM via-points (every point a waypoint) | `waypoints=0;{last}` silent via points → single leg | STATE 2026-07-03 (verified live) | Prevents ~199 spurious mid-route "Arrived!" banners on a 200-point route |
| "Pick `cycling`/`foot` profile for bike/run routes" | Public host ignores profile (always car); follow-route is the only bike-path mitigation in v1 | This session (verified live) | Do not bother switching profile on router.project-osrm.org; note FOSSGIS host as v1.x |
| `id` (int) for Strava resource ids | `id_str` (string) for 64-bit safety | Strava changelog 2020-06-05 | Use `id_str` for export_gpx URL |

**Deprecated/outdated:**
- Strava Route `timestamp` field: marked DEPRECATED in the API docs — do not rely on it; use `created_at`/`updated_at` if a date is ever needed (not needed for v1 UI).

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `net.sf.kxml:kxml2:2.3.0` on the JVM test classpath satisfies `XmlPullParserFactory.newInstance()` for GpxParser tests, matching the `org.json:json` precedent | Validation / Standard Stack | LOW — if it doesn't resolve, fall back to Robolectric for GpxParserTest only, or test GpxParser via an injected factory with a stub. Verified kxml2 exists on Maven Central; the newInstance→kxml2 wiring is the standard documented approach but not executed this session. |
| A2 | `GET /athlete/routes` (singular) reliably lists the current athlete's own routes with `read` scope; the by-id form 403s | Pitfall 6 / Code Examples | LOW-MED — corroborated by community reports + stravalib, not executed (requires live token). If singular also 403s, the routes require `read_all` (already granted). Device-verify step covers this. |
| A3 | export_gpx works with the `read_all` scope already granted in Phase 3 (private routes) | RIMP-02 | LOW — official docs state read_all covers private routes; Phase-3 SUMMARY confirms read_all granted. Public routes need only read. |
| A4 | 15m epsilon yields ≤200 points on typical city/road Strava routes without a secondary cap firing | Pattern 4 / Open Q1 | MED — depends on route length/density; a 100km alpine route at 15m may exceed 200. Mitigation: secondary "raise epsilon until ≤200" loop. Device-tune per STATE todo. |
| A5 | The single OSRM leg's steps render on glasses identically to a normal 2-point route's steps | NAVV-02 | LOW — StepsListMessage/StepMessage are identical structs; glasses code is unchanged; verified the response shape matches `getRoute`'s parse. |
| A6 | Multi-`<trkseg>` routes can be flattened into one continuous point list for v1 | Code Examples §GPX | LOW-MED — true for single-day continuous routes; a route with deliberate gaps would get a bridged DP line. Flag if user routes have gaps (Open Q2). |

**These six assumptions are what discuss-phase / device verification should confirm.** A1, A2, A4 are the highest-value to nail down (A1 unblocks the test approach; A2 unblocks the list call; A4 is the epsilon knob).

## Open Questions

1. **Secondary cap when DP-at-15m still returns >200 points.**
   - What we know: DP-by-epsilon preserves shape; ≤200 is a hard cap for the OSRM URL (well under the ~500 limit) and for navigation sanity.
   - What's unclear: On a long/dense route, 15m may leave >200 points. A naive truncate loses the tail; a re-run with a larger epsilon is cleaner.
   - Recommendation: If `simplify(pts,15).size > 200`, loop raising epsilon (e.g., ×1.5) until ≤200, OR bisect epsilon. Cheap; keeps shape proportional. Plan should include this guard.

2. **Multi-segment (`<trkseg>`) GPX with intentional gaps.**
   - What we know: v1 flattens all trkpt into one list; single-day continuous routes are fine.
   - What's unclear: Whether the user's Strava routes ever contain multiple disjoint segments (rare for a planned route).
   - Recommendation: Flatten for v1 (documented). Device-verify with the user's real route; if gaps appear, revisit in v1.x.

3. **Off-route reroute strategy for via-point routes (NAVV-03).**
   - What we know: The existing reroute is 2-point destination-only, which discards the Strava shape (Pitfall 2).
   - What's unclear: Whether v1 does full via-reroute (best), 2-point degrade, or follow-route distance-only.
   - Recommendation: Plan should pick one explicitly. Simplest correct v1: on off-route in *routed* mode, re-run `getRouteVia` from current position through remaining downsampled waypoints (reuse the importer); in *follow-route* mode, just recompute nearest-remaining-waypoint distance (no OSRM). Preserve forward-only index.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| OSRM public instance (router.project-osrm.org) | OSRM via-point routing | ✓ (verified live this session) | current | follow-route mode (raw downsampled waypoints) |
| Strava API (www.strava.com/api/v3) | routes list + GPX export | ✓ (Phase 3 auth working) | v3 | none — RIMP-01/02 require it (network-dependent by design) |
| `net.sf.kxml:kxml2:2.3.0` (test) | GpxParser JVM tests | ✓ (verified on Maven Central) | 2.3.0 | Robolectric for GpxParserTest only |
| Android built-in XmlPullParser (kxml2) | GpxParser production | ✓ (ships in Android ≥ all target SDKs) | AOSP | none needed |
| osmdroid tiles (preview) | RIMP-04 preview map | ✓ (existing live-nav uses them) | 6.1.18 | phone-side tile proxy already handles offline (existing) |

**Missing dependencies with no fallback:** none (Strava/OSRM are network services, online by design; both have graceful degrades — follow-route for OSRM, error toast for Strava).
**Missing dependencies with fallback:** none blocking at build time.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 4.13.2 (plain JVM, no Robolectric) |
| Config file | none — Gradle `testOptions { unitTests.isReturnDefaultValues = true }` in phone/build.gradle.kts:53-55 |
| Quick run command | `./gradlew :phone:testDebugUnitTest --tests "com.rokid.hud.phone.strava.*" --tests "com.rokid.hud.phone.OsrmViaUrlTest" --tests "com.rokid.hud.phone.RouteDownsamplerTest"` |
| Full suite command | `./gradlew :phone:testDebugUnitTest :shared:testDebugUnitTest` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| RIMP-01 | Route JSON parses: int `type`/`sub_type`, `id_str` preserved, nulls tolerated | unit | `./gradlew :phone:testDebugUnitTest --tests "*.StravaRouteModelTest"` | ❌ Wave 0 |
| RIMP-02 | export_gpx URL uses `id_str`; getRoutes URL is singular `/athlete/routes` with page/per_page | unit | `--tests "*.StravaRouteModelTest"` (URL-builder assertions) | ❌ Wave 0 |
| RIMP-03a | GPX `<trkpt>` extraction: single-seg, multi-seg, missing-ele, malformed → []; lat/lng order | unit (kxml2) | `--tests "*.GpxParserTest"` | ❌ Wave 0 |
| RIMP-03b | Douglas-Peucker: colinear→endpoints only; switchback preserved; ≤200 cap; equirect distance correctness | unit (pure) | `--tests "*.RouteDownsamplerTest"` | ❌ Wave 0 |
| RIMP-04 | Preview draws Polyline + fits bounds (view-dependent) | manual/device | route import shows line on phone map, fit-to-bounds | manual |
| NAVV-01 | Waypoint path fires onRouteCalculated with the passed steps | unit (fake callback) | `--tests "*.NavigationRouteTest"` | ❌ Wave 0 |
| NAVV-02a | OSRM via-URL builder: `waypoints=0;{last}`, lng,lat order, correct params | unit (pure) | `--tests "*.OsrmViaUrlTest"` | ❌ Wave 0 |
| NAVV-02b | Non-final zero-distance arrive filter drops mid, keeps final | unit (pure) | `--tests "*.OsrmViaUrlTest"` | ❌ Wave 0 |
| NAVV-02c | Follow-route builds a non-empty synthetic step (guards the empty-steps trap) | unit | `--tests "*.NavigationRouteTest"` | ❌ Wave 0 |
| NAVV-02d | End-to-end route-line + arrows + TTS on glasses | manual/device | navigate a real Strava route, glasses show line + turns | manual |
| NAVV-03 | Off-route reroute preserves route shape (not 2-point collapse); forward-only index | unit + device | `--tests "*.NavigationRouteTest"` (index monotonic) + device wrong-turn | ❌ Wave 0 + device |

### Sampling Rate
- **Per task commit:** the quick run command (strava + Osrm + Downsampler + Navigation route tests) — all pure JVM, <5s.
- **Per wave merge:** full suite `./gradlew :phone:testDebugUnitTest :shared:testDebugUnitTest` (Phase-1/2/3 = 110 tests must stay green).
- **Phase gate:** full suite green + the device verification batch (below) before `/gsd-verify-work`.

### Wave 0 Gaps
- [ ] `phone/src/test/java/com/rokid/hud/phone/strava/GpxParserTest.kt` — covers RIMP-03a (needs kxml2 test dep first)
- [ ] `phone/src/test/java/com/rokid/hud/phone/strava/RouteDownsamplerTest.kt` — covers RIMP-03b (pure)
- [ ] `phone/src/test/java/com/rokid/hud/phone/strava/StravaRouteModelTest.kt` — covers RIMP-01/02 (Gson parse + URL builders)
- [ ] `phone/src/test/java/com/rokid/hud/phone/OsrmViaUrlTest.kt` — covers NAVV-02a/b (URL builder + arrive filter; extract these as pure functions so no network)
- [ ] `phone/src/test/java/com/rokid/hud/phone/NavigationRouteTest.kt` — covers NAVV-01/02c/03 (waypoint path via a fake NavigationCallback; follow-route synthetic step; forward-only index)
- [ ] Test dep add: `testImplementation("net.sf.kxml:kxml2:2.3.0")` in phone/build.gradle.kts

**Testability seam requirement:** `OsrmClient.getRouteVia` must expose its **URL builder** and **step-filter** as pure functions (e.g., `buildViaUrl(points): String`, `filterArriveSteps(steps): List`) so `OsrmViaUrlTest` runs with zero network. Same discipline as Phase-3's pure `StravaOAuth.buildAuthorizeUrl`. The network `execute()` is the only non-testable line.

## Security Domain

> `security_enforcement: true`, ASVS level 1. This phase adds two authenticated GET calls and parses untrusted XML — the relevant surface is input validation of the GPX body and safe handling of the route data.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no (reuses Phase-3 tokens) | Phase-3 ESP token store + Authenticator unchanged |
| V3 Session Management | no | — |
| V4 Access Control | no | Single-user app; Strava enforces per-token scope server-side |
| V5 Input Validation | **yes** | GPX is untrusted XML from the network — parse defensively (never throw), validate lat/lng numeric + range; OSRM response parsed with existing `org.json` guards |
| V6 Cryptography | no (never hand-roll) | HTTPS enforced by OkHttp; no new crypto |

### Known Threat Patterns for GPX + HTTP-GET (Android/Kotlin)

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| XXE / external-entity injection via GPX | Tampering / Info-disclosure | `XmlPullParserFactory` with `isNamespaceAware=false` and NO DTD/entity processing enabled (XmlPullParser does not resolve external entities by default); never call `setFeature(FEATURE_PROCESS_DOCDECL, true)` |
| Malformed/huge GPX (billion-laughs, giant file) → OOM/ANR | DoS | Parse on a `Thread{}` (never main); GPX export for a single route is bounded (~KB-MB per ARCHITECTURE scaling); DP cap bounds downstream memory |
| Untrusted lat/lng values (NaN, out-of-range) reaching OSRM URL / map | Tampering | Validate `lat in -90..90`, `lng in -180..180`, finite doubles in GpxParser; skip invalid `<trkpt>` |
| Route id injection into export_gpx URL path | Tampering | `id_str` comes from Strava's own JSON (trusted origin); still URL-safe (numeric string) — no user free-text in the path |
| Plaintext HTTP fallback | Info-disclosure | All URLs are `https://`; OkHttp default rejects cleartext on modern targetSdk; no `http://` literal in new code |

**No new secrets, no new storage, no new exported components** this phase — the security delta over Phase 3 is purely "parse untrusted XML safely + validate coordinates."

## Sources

### Primary (HIGH confidence)
- **Live OSRM verification (this session)** — `router.project-osrm.org/route/v1/*`: `waypoints=0;{last}` single-leg behavior (4-coord test), 200-coord GET URL = 4KB HTTP 200, URL ceiling ~500 coords, **profile path segment ignored (driving/cycling/foot byte-identical)**.
- **Equirectangular vs haversine DP accuracy (this session)** — computed 0.0009% delta at city scale → equirectangular is accurate enough for a 15m epsilon.
- **Codebase reads (this session)** — OsrmClient.kt (2-point `getRoute`, GeoJSON parse), NavigationManager.kt (destination-only start, forward-only index at :72-101, race on steps/currentStepIndex), HudStreamingService.kt:302-324/571-584 (NavigationCallback→sendRoute/sendStepsList wiring; `sendStepsList` empty-guard at :577), MainActivity.kt:193-231/848-912 (navCallback, updateNavMap Polyline+BoundingBox), StravaApiClient.kt / StravaModels.kt (Phase-3 client + Gson pattern to extend), Messages.kt/ProtocolConstants.kt (RouteMessage/StepMessage/StepsListMessage already exist), build.gradle.kts (deps + `unitTests.isReturnDefaultValues`).
- **Strava API docs** — developers.strava.com/docs/reference (routes endpoints, per_page≤200, private routes need read_all), strava.github.io/api/v3/routes (Route JSON: int type/sub_type, distance/elevation_gain meters), developers.strava.com/docs/changelog (id_str added 2020-06-05; timestamp deprecated).

### Secondary (MEDIUM confidence)
- **`/athlete/routes` singular vs by-id 403** — communityhub.strava.com reports + stravalib client behavior (get_routes) — corroborated, not executed with a live token.
- **XmlPullParser JVM testability** — unittesting1.blogspot.com + copyprogramming.com (Stub! exception on `Xml.newPullParser()`; use `XmlPullParserFactory.newInstance()` + kxml2 on test classpath; `returnDefaultValues` does NOT fix it).
- **osmdroid zoomToBoundingBox layout timing** — github.com/osmdroid/osmdroid issues #236/#337/#866 (call after layout via post/onLayout; addOnFirstLayoutListener fires too early).
- **kxml2 on Maven Central** — search.maven.org: `net.sf.kxml:kxml2:2.3.0` confirmed present.

### Tertiary (LOW confidence)
- Route model field details cross-referenced from community-maintained Swagger mirrors (timheuer/strava-net, sshevlyagin/strava-api-v3.1) — used only to corroborate the official schema, not as sole source.

## Metadata

**Confidence breakdown:**
- OSRM via-point mechanics + URL limits + profile behavior: **HIGH** — empirically verified against the live public instance this session.
- Douglas-Peucker math (equirectangular): **HIGH** — accuracy computed and confirmed under-epsilon; algorithm is textbook.
- NavigationManager surgery + glasses wiring: **HIGH** — read directly from source; the empty-steps trap and race are confirmed in the code.
- Strava route schema (fields, scopes, pagination): **HIGH** for field types/scopes (official docs + changelog); **MEDIUM** for the singular `/athlete/routes` reliability (community-corroborated, not token-executed).
- XmlPullParser JVM test approach: **MEDIUM** — kxml2 exists and the newInstance-factory approach is the documented standard, but the exact wiring wasn't run this session (A1).

**Research date:** 2026-07-03
**Valid until:** 2026-08-02 for Strava/OSRM API shapes (stable, but the public OSRM instance has no SLA — re-verify `waypoints=0;{last}` if navigation regresses); 7 days for any assumption the user's real-route device test can settle sooner (epsilon, multi-seg, off-route strategy).
