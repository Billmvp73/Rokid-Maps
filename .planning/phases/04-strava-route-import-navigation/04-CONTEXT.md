# Phase 4: Strava Route Import + Navigation - Context

**Gathered:** 2026-07-03
**Status:** Ready for planning
**Mode:** Smart discuss (autonomous — recommendations auto-accepted per user authorization)

<domain>
## Phase Boundary

User imports Strava routes and navigates them on glasses. Delivers: browse saved/starred Strava routes (GET /athlete/routes via the Phase-3 authenticated OkHttp client), import a selected route as GPX (GET /routes/{id}/export_gpx), parse + Douglas-Peucker downsample to ≤200 waypoints, OSRM via-point routing with `waypoints=0;{last}` for real turn-by-turn (with follow-route fallback), phone-map preview, and navigation on glasses reusing the existing route-line/maneuver/TTS pipeline. Requirements: RIMP-01..04, NAVV-01..03. Auth is Phase 3 (done); upload is Phase 5.

</domain>

<decisions>
## Implementation Decisions

### GPX → navigable route (THE core, per the locked STATE decisions)
- Parse GPX <trkpt lat lon [ele]> via android.util.Xml / XmlPullParser (STACK.md — no GPX lib)
- Douglas-Peucker downsample: epsilon starts at 15m (mid of the researched 10-20m band; STATE todo — tune empirically in device testing with a real route); target ≤200 output points
- OSRM routing: extend OsrmClient with a NEW multi-coordinate method that builds `/route/v1/{profile}/{lng,lat};{lng,lat};...` from ALL downsampled points, with `waypoints=0;{lastIndex}` (silent via points → single leg, real turn-by-turn, NO spurious mid-route arrivals — the verified STATE decision), `overview=full&geometries=geojson&steps=true`
- Defensive filter: drop any zero-distance `arrive` step that is not the final step (belt-and-braces vs the leg-boundary artifact)
- Fallback: if OSRM via-point routing fails/errors, navigate the raw downsampled GPX waypoints in "follow route" mode — route line + distance-to-next-waypoint, no turn arrows/TTS; a clear "Follow route" label instead of maneuver text (PITFALLS UX)
- OSRM profile: default `/route/v1/driving` today; try `cycling` for ride sport and `walking`(foot) for run — BUT public router.project-osrm.org only serves `driving`. Decision: use `driving` on the existing public instance (works for on-road cycling/running); the follow-route fallback covers off-road/bike-path snapping failures. Note FOSSGIS bike/foot instances (routing.openstreetmap.de) as a future option in a STATE todo — do NOT add a second routing host in v1.

### NavigationManager waypoint-accepting path (new — the existing startNavigation is destination-only)
- Add `startNavigationWithRoute(waypoints, steps)` (or overload) that accepts a pre-computed route + steps and skips the internal OSRM A→B call; the existing proximity step-advancement + off-route + arrival logic is reused unchanged
- Fix the known steps/currentStepIndex data race IN THIS PHASE (the STATE decision assigns it here — this is the only phase that rebuilds NavigationManager): make them @Volatile / guarded, matching the ActivitySessionManager thread-safety discipline from Phase 1
- Follow-route mode is a NavigationManager flag: when steps are absent, emit "Follow route" + distance to next downsampled waypoint instead of maneuver-driven step text

### Route browsing UI
- New "MY STRAVA ROUTES" section/list, shown on MainActivity only when Strava-connected (reuse Phase-3 connection state); each row: route name, distance (imperial-aware), elevation gain
- Tap a route → fetch GPX → downsample → preview the route line on the existing osmdroid navMapView (RIMP-04) with a "START NAVIGATION" button
- Loading/empty/error states: spinner while fetching; "No routes found" empty; toast on API/parse error (existing conventions)
- Recording auto-start is NOT coupled to route navigation (REC-01 opt-in remains — user starts recording separately if they want it)

### Rate-limit awareness
- Log X-RateLimit / X-ReadRateLimit usage on the routes-list + gpx-export calls (headers already surfaced by the Phase-3 client); no hard enforcement in v1 (single user well under 100 reads/15min), but a 429 → toast "Strava rate limit — try again shortly"

### Claude's Discretion
- Douglas-Peucker implementation details (iterative vs recursive; perpendicular-distance math)
- Exact route-list row layout (follow existing list-item conventions)
- Whether preview reuses the live-nav MapView or a dedicated preview instance
- StravaRouteImporter / GpxParser class split within com.rokid.hud.phone.strava

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- Phase-3 StravaApiClient (authenticated OkHttp + Authenticator + rate-limit logging) — add getRoutes() + exportGpx() endpoints
- OsrmClient (object) — getRoute(from,to) is 2-point ONLY (verified); add the multi-waypoint via-point method beside it; NavigationStep/RouteResult data classes reused
- NavigationManager — startNavigation(dest) is destination-only; add the waypoint-accepting path; existing onLocationUpdate proximity logic (150m step advance, 80m off-route, 30m arrival) reused
- osmdroid navMapView in MainActivity + existing route-line Polyline drawing (currentRouteWaypoints) for preview + live nav
- Glasses side needs ZERO changes: route/step/steps_list BT messages already broadcast + rendered (existing pipeline); follow-route just sends "Follow route" as the instruction string

### Established Patterns
- Thread{} for network (OSRM/Strava), callback to UI via runOnUiThread; NavigationCallback interface
- Gson for Strava JSON responses; XmlPullParser for GPX; org.json elsewhere
- try/catch+Log; toasts for user errors
- haversineM duplicated per-class (NavigationManager has one) — reuse the pattern for Douglas-Peucker perpendicular distance

### Integration Points
- StravaApiClient.getRoutes()/exportGpx() → route list UI + GpxParser
- GpxParser + DouglasPeucker → OsrmClient.getViaRoute() → NavigationManager.startNavigationWithRoute() → existing RouteMessage/StepMessage/StepsListMessage broadcast → glasses render (unchanged)
- Device verify: reuse both devices; a real Strava route on the user's account drives the end-to-end (import → preview → navigate → glasses shows route line + steps); batches with the Phase-3 live-auth device session
- Unit-testable seams (no Android): Douglas-Peucker (pure geometry), GPX parse (string in → points out via XmlPullParser — needs the org.json-style JVM caveat; XmlPullParser IS available on JVM via kxml2? verify in research), OSRM URL builder (waypoints=0;{last} correctness), arrive-step filter, follow-route decision

</code_context>

<specifics>
## Specific Ideas

- The waypoints=0;{last} decision is VERIFIED against live OSRM (STATE decision, Phase-1 review) — the plan must build exactly that, not plain via-points
- Butterfly/switchback avoidance (PITFALLS Pitfall 3): forward-only step index already exists in NavigationManager (Phase-1 review confirmed) — preserve it; the downsampling + via-routing is what prevents the density problems
- Douglas-Peucker epsilon + OSRM profile are the two device-tuning knobs (STATE todos) — pick sane defaults (15m, driving), verify on a real route, adjust
- Phase-2 leftover device spots + Phase-3 live auth all fold into the combined device session at Phase-4 verification

</specifics>

<deferred>
## Deferred Ideas

- Second OSRM host (FOSSGIS bike/foot) for off-road snapping — v1.x (STATE todo); follow-route fallback covers it for now
- Turn-by-turn from GPX bearing inference — explicitly rejected (ARCHITECTURE anti-pattern 2)
- Route creation/editing — out of scope (use Strava)
- Caching imported routes offline — v2

</deferred>

---

*Phase: 04-strava-route-import-navigation*
*Context gathered: 2026-07-03 via autonomous smart discuss*
