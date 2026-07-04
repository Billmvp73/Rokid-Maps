# Cross-Phase Integration Audit — Rokid HUD Maps v1 (Phases 1–5)

**Scope:** CODE-LEVEL cross-phase integration + E2E flow wiring. No device actions
(the on-device pass is separately batched under plan 05-04, pending phone unlock).
**Method:** Grep/Read every seam at both the producing and consuming side; verify
types, JSON field names, and lifecycle agree. Not trusting SUMMARYs.
**Date:** 2026-07-03
**Build:** `testDebugUnitTest` BUILD SUCCESSFUL — **208 tests, 0 failures, 0 errors**
across shared/phone/glasses (18 test classes). `assembleDebug` exit 0 previously
confirmed by phase verifiers.

## Overall Verdict: **INTEGRATED**

All five cross-phase code seams are wired end-to-end; producing and consuming sides
agree on types, JSON field names, and lifecycle. No BLOCKER or WARNING integration
gaps found. The only remaining gate is the batched on-device pass (real OAuth
Authorize, real-route navigation on glasses, real Strava upload, Phase-2 leftover
spots) — that is device confirmation, **not** a code gap.

---

## Flow 1 — Record → Summary → Upload → History (Phase 1 → 5): **PASS**

Full chain traced; the session JSON schema is symmetric on write and read, and the
async-finalize race is explicitly covered.

| Seam | Producer | Consumer | Verdict |
|------|----------|----------|---------|
| finalize → disk | `HudStreamingService.stopRecording` → `asm.stopSession()` → `SessionStore.finalizeAsync(data)` (HudStreamingService.kt:446-451) | `SessionStore.finalizeSync` → `writeAtomic({id}.json)` (SessionStore.kt:221-238) | WIRED |
| stop-flow → summary | MainActivity captures `service.currentSessionId()` **before** `stopRecording()`, launches `ActivitySummaryActivity` with `EXTRA_SESSION_ID` only (MainActivity.kt:1229-1239) | `ActivitySummaryActivity.onCreate` reads `EXTRA_SESSION_ID` (ActivitySummaryActivity.kt:83) | WIRED |
| schema round-trip | `SessionStore.toJson` keys: id/sport/startTime/endTime/elapsedMs/movingMs/distanceM/avgSpeedMps/stravaUploaded/trackPoints[lat,lng,alt,ts,speed,acc,brg] (SessionStore.kt:71-95) | `SessionStore.fromJson` reads **identical** keys (SessionStore.kt:110-143); `renderMetrics` reads elapsedMs/movingMs/distanceM/avgSpeedMps (ActivitySummaryActivity.kt:139-149) | WIRED — no field drift |
| trackPoints → GPX | `SessionData.trackPoints` | `GpxWriter.write(data.trackPoints, data.sport, data.startTime)` reads p.lat/p.lng/p.alt/p.ts (GpxWriter.kt:56-79); called at ActivitySummaryActivity.kt:223 | WIRED |
| upload write-back → history badge | `SessionStore.updateUploadState` sets `stravaUploaded=true` via `toJsonWithActivityId` (reuses toJson base — no drift) (SessionStore.kt:341-368); called on upload success (ActivitySummaryActivity.kt:260) | `HistoryActivity` badge reads `data.stravaUploaded` (HistoryActivity.kt:97) via `listFinalSessions()`→`readSession()` | WIRED |

**P5-WR-01 async-finalize race — COVERED.** `finalizeAsync` runs on SessionStore's
serial executor and completes ~1-2s after the synchronous summary launch, so
`{id}.json` may not exist on first read. `ActivitySummaryActivity` retries the disk
read `repeat(15)` with 200ms sleep (~3s budget), then falls back to "recover from
History" (ActivitySummaryActivity.kt:112-125). Producer (`stopSession` string state
"finished", sport "ride"/"run") matches everything downstream.

**UPL-03 (no data loss) — WIRED.** Write-back only in `driveUpload`'s success branch;
retry/duplicate paths re-upload the untouched JSON; `writeAtomic` (temp+fsync+rename)
never leaves a partial file.

---

## Flow 2 — Strava auth → Route import (P3→4) AND auth → Upload (P3→5): **PASS**

A single `StravaAuthManager` → single `TokenRefreshCoordinator` → single
`StravaTokenStore` is the authenticated path for BOTH Phase-4 route calls and Phase-5
upload calls. There is no divergent auth client.

| Consumer | Auth entry | Reactive 401 net | Verdict |
|----------|-----------|------------------|---------|
| P3 `StravaApiClient.getAthlete` | `auth.ensureFreshToken()` (StravaApiClient.kt:107) | `StravaAuthenticator(auth)` on `client` (line 88) | WIRED |
| P4 `StravaApiClient.getRoutes` | `auth.ensureFreshToken()` (line 142) | same `client` authenticator | WIRED |
| P4 `StravaApiClient.exportGpx` | `auth.ensureFreshToken()` (line 178) | same | WIRED |
| P5 `StravaUploader.startUpload` | `auth.ensureFreshToken()` (StravaUploader.kt:113) | `StravaAuthenticator(auth)` on its `client` (line 83) | WIRED |
| P5 `StravaUploader.poll` | `auth.ensureFreshToken()` (line 169) | same | WIRED |

- `ensureFreshToken()` → `coordinator.ensureFreshToken()` → single-flight refresh under
  one lock, proactive 30-min window; 401 net double-checks inside the same lock
  (TokenRefreshCoordinator.kt:58-118). Both phases use the identical refresh path.
- **Connection gating reads the same state Phase 3 writes.** Route list is revealed
  via `stravaAuthManager.connectedAthleteName()` (MainActivity.kt:355) →
  `tokenStore.load()`. Upload button gated on `auth.isConnected()`
  (ActivitySummaryActivity.kt:192) → `tokenStore.load() != null`. Phase 3 writes via
  `tokenStore.save()` on code exchange (StravaAuthManager.kt:199).
- **Same token store instance guaranteed:** `StravaTokenStore.PREFS_FILE = "strava_auth"`
  is a fixed constant, so every `StravaTokenStore(context)` (MainActivity and
  ActivitySummaryActivity each construct their own) reads/writes the same
  EncryptedSharedPreferences file. No split-brain token state.

---

## Flow 3 — Route import → OSRM via-routing → NavigationManager → BT → glasses render (P4→glasses): **PASS**

The longest chain. Every hop wired; the follow-route empty-steps trap is closed
upstream; encode/decode use identical `ProtocolConstants`.

Chain: `exportGpx` → `GpxParser.parse` → `RouteDownsampler.downsampleForRoute` →
`OsrmClient.getRouteVia(downsampled)` → `service.startNavigationWithRoute(waypoints,
steps, ...)` → `NavigationManager.startNavigationWithRoute` → `NavigationCallback`
→ `sendRoute`+`sendStepsList`+`sendStep` → `ProtocolCodec.encode*` →
`BluetoothClient.processMessage` → `ProtocolCodec.decode` → `HudState` → `HudView`.

| Seam | Detail | Verdict |
|------|--------|---------|
| import → OSRM | `onStravaRouteSelected` runs exportGpx→parse→downsample→`getRouteVia`, try/catch → `buildFollowRouteResult` (MainActivity.kt:482-508) | WIRED |
| silent-via (butterfly fix) | `buildViaUrl` emits `waypoints=0;{last}` — single leg (OsrmClient.kt:143-147); `filterArriveSteps` drops non-final zero-distance arrives (line 156) | WIRED |
| type agreement | `RouteResult.steps: List<NavigationStep>` (OsrmClient.kt:17-22) === `startNavigationWithRoute(steps: List<NavigationStep>)` (HudStreamingService.kt:388-395, NavigationManager.kt:99-105) | WIRED |
| nav → callback | `startNavigationWithRoute` fires `onRouteCalculated`+`onStepChanged` on main looper after publishing @Volatile fields (NavigationManager.kt:117-122) | WIRED |
| callback → broadcast | service `NavigationCallback.onRouteCalculated` → `sendRoute()`+`sendStepsList()`; `onStepChanged` → `sendStep()`+`sendStepsList()` (HudStreamingService.kt:303-312) | WIRED |
| **follow-route ≠ empty-steps** | `buildFollowRouteResult` always carries ONE synthetic "Follow route" step (OsrmClient.kt:170-188); `sendStepsList` early-returns only when `nav.steps.isEmpty()` (HudStreamingService.kt:608) — one step ⇒ broadcasts | WIRED — trap closed |
| encode↔decode fields | Route/Step/StepsList encode+decode both use `ProtocolConstants.FIELD_*` (ProtocolCodec.kt:36-55,116-128,157-251) — shared module, one source | WIRED |
| glasses render | `processMessage` maps Route→waypoints/totalDistance/totalDuration, Step→instruction/maneuver/stepDistance, StepsList→allSteps/currentStepIndex (BluetoothClient.kt:175-231); `HudView.drawLiveMap` uses `state.waypoints` (HudView.kt:375,426), upcoming steps use `state.allSteps` (lines 507-549) | WIRED |
| off-route reroute (NAVV-03) | waypoint routes re-run `getRouteVia(reroutePoints)` shape-preserving, atomic-publish-on-main, follow-route degrade on failure (NavigationManager.kt:277-337) | WIRED |
| @Volatile race fix | steps/currentStepIndex/routeWaypoints/followRoute/nextWaypointIndex/isWaypointRoute all @Volatile; writes happen-before mainHandler.post (NavigationManager.kt:39-59) | WIRED |

**Device-only confirmation (not a gap):** actual OSRM route fidelity, real-glasses
render, and live off-route recalculation are the plan 04-06 / 05-04 on-device items.

---

## Flow 4 — sport_state: phone broadcast → glasses SPORT layout (P1→2): **PASS**

| Seam | Detail | Verdict |
|------|--------|---------|
| ~1Hz broadcast | `startSportStateTicker` at `SPORT_STATE_TICK_MS = 1000L`; `broadcastSportState(snap)` at start, each tick, and finished stop (HudStreamingService.kt:64,424,448,652-665) | WIRED |
| encode (v:1) | `encodeSportState` writes `v=1` + et/mt/d/cs/ap/st/sp (ProtocolCodec.kt:130-140) | WIRED |
| field mapping producer | `MetricsSnapshot` → `SportStateMessage`: elapsedMs/movingMs/distanceM/currentSpeedMps/avgPaceMsPerKm/sessionState/sport (HudStreamingService.kt:653-662) | WIRED |
| decode | `ProtocolCodec.decode` → `ParsedMessage.SportState` reads same 7 fields + version (ProtocolCodec.kt:252-262) | WIRED |
| decode → state | `BluetoothClient` → `HudState.applySportState(msg, elapsedRealtime())` copies all 7 fields + receipt clock (BluetoothClient.kt:269; HudState.kt:102-111) | WIRED |
| state → render | `HudView.drawSportLayout` reads elapsedMs/currentSpeedMps/avgPaceMsPerKm/distanceM via SportFormat; monochrome green; staleness ladder (HudView.kt:626-718) | WIRED |
| SPORT reachable | `HudState.toggleLayout` cycles FULL→CORNER→SPORT→FULL (HudState.kt:126-134); `HudView` gesture → `onLayoutToggle` → `btClient.toggleLayout()` (HudView.kt:159-172, HudActivity.kt:75,224) | WIRED (HUD-03) |
| sessionState strings agree | phone `stateString()` emits "idle"/"tracking"/"finished" (ActivitySessionManager.kt:376-378); glasses `sportDisplayMode` keys on "idle"/"finished" (HudState.kt:118-124) | WIRED |
| SPORT survives 1Hz re-send | settings re-broadcast preserves SPORT mode (BluetoothClient.kt:207-212, 02-REVIEW WR-01) | WIRED |

`exactJsonKeys` test locks the wire to exactly `{t,v,et,mt,d,cs,ap,st,sp}` (9 keys)
and `versionFieldIsOne` locks `v=1` — both green.

---

## Flow 5 — Protocol integrity (single source): **PASS**

- **Single source proven:** both apps declare `implementation(project(":shared"))`
  (phone/build.gradle.kts:59, glasses/build.gradle.kts:33). All field names and
  message-type strings come from `shared/ProtocolConstants`. **No phone-only or
  glasses-only divergent constants are possible** — there is one compiled definition.
- **Message-type count:** 13 types in `ProtocolConstants.MessageType`
  (state, route, step, notification, settings, wifi_creds, tile_req, tile_resp,
  apk_start, apk_chunk, apk_end, steps_list, sport_state).
- **Codec symmetry:** `ProtocolCodec` has an `encode*` for all 13 and a `decode`
  `when` arm for all 13 → `ParsedMessage` (14 variants incl. `Unknown`).
  Glasses `BluetoothClient.processMessage` has a branch for **every** `ParsedMessage`
  variant (exhaustive `when`, no `else` fall-through) — verified all handled.
- New sport_state / route / step message types encode↔decode via identical
  `ProtocolConstants.FIELD_*` on both sides. Round-trip unit-tested (green).

---

## Requirements Integration Map

| Requirement | Integration Path | Status |
|-------------|-----------------|--------|
| REC-07 (sport_state broadcast) | ASM snapshot → SportStateMessage → encode(v:1) → broadcast 1Hz | WIRED |
| HUD-01/02/04 (SPORT render) | decode → applySportState → HudState → drawSportLayout | WIRED |
| HUD-03 (tap to SPORT) | HudView gesture → onLayoutToggle → toggleLayout (Full→Corner→Sport→Full) | WIRED |
| REC-01/06 (record→persist) | stopRecording → stopSession → finalizeAsync → {id}.json | WIRED |
| RIMP-01 (route list) | getRoutes(ensureFreshToken) → renderStravaRoutes; gated on connectedAthleteName | WIRED |
| RIMP-02 (import GPX) | exportGpx(ensureFreshToken) → GpxParser.parse | WIRED |
| RIMP-03 (downsample) | GpxParser → RouteDownsampler.downsampleForRoute → getRouteVia | WIRED |
| RIMP-04 (preview) | previewImportedRoute draws Polyline on osmdroid | WIRED |
| NAVV-01 (start nav on route) | startNavigationWithRoute → onRouteCalculated → sendRoute/sendStepsList | WIRED |
| NAVV-02 (guidance + follow-route fallback) | getRouteVia steps OR synthetic follow step; sendStepsList non-empty | WIRED |
| NAVV-03 (off-route reroute) | rerouteThroughRemainingWaypoints via getRouteVia (shape-preserving) | WIRED |
| AUTH-01/02/03 (OAuth + persist + refresh) | StravaAuthManager/TokenStore/Coordinator — shared by P4+P5 | WIRED (code); live OAuth device-only |
| UPL-01 (summary) | currentSessionId → ActivitySummaryActivity → readSession → renderMetrics + route map | WIRED |
| UPL-02 (one-tap upload) | driveUpload → startUpload/poll (ensureFreshToken) → updateUploadState | WIRED (code); real upload device-only |
| UPL-03 (no data loss) | write-back only on success; retry re-uploads untouched JSON; atomic write | WIRED |
| UPL-04 (history) | listFinalSessions → readSession → stravaUploaded badge | WIRED |

**Requirements with no cross-phase wiring (self-contained by design — not gaps):**
REC-02/03/04/05 (metric computation, accuracy gate, hysteresis, watchdog) live
entirely inside Phase 1's ActivitySessionManager / RecordingWatchdog and feed the
sport_state producer; they have no additional cross-phase touchpoint beyond
MetricsSnapshot (already traced in Flow 1/4).

---

## Device-Only Confirmations (flagged as such — NOT integration gaps)

These require the physical OPPO phone / Rokid glasses and are batched in plan 05-04
(+ leftover 04-06 / 03-04 spots), gated on phone unlock:

1. Real Strava OAuth Authorize round-trip (rokidhud://callback) and token persistence/rotation.
2. Live glasses render of route line + SPORT metrics over actual Bluetooth SPP.
3. Real OSRM route on a real Strava route (single-leg, butterfly loop, off-route reshape, follow-route).
4. Real Strava upload + human feed confirmation + no-duplicate re-upload.
5. 30-min screen-off recording reliability on ColorOS.

---

## Summary

Every expected cross-phase connection resolves to **WIRED**. No orphaned exports at
seams, no APIs without consumers, no broken E2E chains, no schema drift between
producer and consumer. Types, JSON field names, and lifecycle agree at all five
seams. 208 unit tests green; both APKs compile. The milestone is code-INTEGRATED;
only the batched on-device verification pass remains.
