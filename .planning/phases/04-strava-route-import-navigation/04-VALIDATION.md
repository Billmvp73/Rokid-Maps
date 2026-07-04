---
phase: 4
slug: strava-route-import-navigation
status: ready
nyquist_compliant: true
wave_0_complete: false
created: 2026-07-03
---

# Phase 4 — Validation Strategy

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 4.13.2 plain JVM. Wave-0 adds `testImplementation("net.sf.kxml:kxml2:2.3.0")` for XmlPullParser on JVM (android.util.Xml throws Stub! — mirrors the org.json test-dep trick). |
| **Quick run command** | `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && cd /Users/bilhuang/Documents/rokid-maps && java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :phone:testDebugUnitTest -q` |
| **Full suite command** | `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && cd /Users/bilhuang/Documents/rokid-maps && java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain testDebugUnitTest assembleDebug -q` |
| **Estimated runtime** | ~45s quick / ~140s full |

## Sampling Rate

- After every task commit: quick command
- After every plan wave: full suite
- Before phase verification: full suite + on-device real-route pass
- Max feedback latency: 140 seconds

## JVM-testable seams (from 04-RESEARCH Validation Architecture)

- **GpxParser** (kxml2): trkpt lat/lon/ele extraction, multi-trkseg flatten, malformed GPX → empty/graceful
- **RouteDownsampler** (Douglas-Peucker, pure): epsilon-in-meters, ≤200 secondary cap (raise-epsilon loop — Open Q A4), curve preservation on a synthetic switchback
- **OsrmClient.getRouteVia URL builder**: exact `/route/v1/driving/{lng,lat};...?waypoints=0;{lastIndex}&overview=full&geometries=geojson&steps=true` shape; coord count → lastIndex correctness
- **Arrive-step filter**: drops non-final zero-distance arrive steps; keeps the final arrival
- **Follow-route decision + synthetic step**: steps-absent → inject one "Follow route" step (so HudStreamingService.sendStepsList doesn't early-return on empty — the verified trap) + distance-to-next-waypoint
- **NavigationManager thread-safety**: steps/currentStepIndex @Volatile/guarded (the Phase-4-owned race fix) — assert via a concurrent-access test or code-shape grep
- **Strava route Gson models**: id_str, name, distance, elevation_gain, integer type/sub_type; happy + missing-field parse

## Device-only (final plan; batches with Phase-3 live auth + Phase-2 fold-ins into ONE phone session)

- Real Strava route on the user's account: browse list (name/distance/elevation) → tap → GPX import → downsample → phone-map preview (RIMP-01..04)
- START NAVIGATION → glasses show the route line + turn-by-turn steps (NAVV-01/02); logcat: single leg, no spurious mid-route "arrive"
- Winding/switchback route: navigation arrow does NOT flip 180° (NAVV — butterfly avoidance); off-route detection fires when deviating (NAVV-03)
- Follow-route fallback: force an OSRM failure (or a route OSRM can't snap) → "Follow route" label + distance on glasses, route line still drawn
- DP-epsilon (15m default) + ≤200 cap sanity on the real route; adjust if the preview line is visibly wrong (Open Q A4)

## Human moment

The user selects one of their real Strava routes and confirms the on-glasses result looks right (route shape matches). Everything else is adb-scriptable. Batches with the pending Phase-3 Authorize + Phase-2 spots.
