---
phase: 05-activity-summary-strava-upload
plan: 03
subsystem: activity-summary-ui
tags: [ui, activity, osmdroid, strava-upload, history, no-coroutines, thread-blocking, id-only-launch]

# Dependency graph
requires:
  - phase: 05-activity-summary-strava-upload
    plan: 01
    provides: "SessionStore.readSession/listFinalSessions/updateUploadState + SummaryMath.avgPaceMsPerKm + GpxWriter.write/isValidForUpload/sportType + UploadState/StartOutcome/PollOutcome + the pure driveUpload state machine"
  - phase: 05-activity-summary-strava-upload
    plan: 02
    provides: "StravaUploader.startUpload/poll + StartResult/PollResult (mapped onto StartOutcome/PollOutcome)"
  - phase: 03-strava-authentication
    provides: "StravaAuthManager.isConnected/ensureFreshToken + StravaTokenStore(context)"
  - phase: 01-activity-recording
    provides: "HudStreamingService recording lifecycle (stopRecording clears PREF_REC_SESSION_ID) + ActivitySessionManager.snapshotSession"
provides:
  - "SportFormat: imperial-aware formatDist/formatSpeed/formatPace/formatElapsed (single source of truth; MainActivity delegates via preserved private wrappers)"
  - "ActivitySummaryActivity: session-id-only summary screen (metrics + osmdroid route + one-tap Strava upload via driveUpload/StravaUploader with live progress states)"
  - "HistoryActivity: past-activities list (date/sport/distance/duration + uploaded badge) newest-first from listFinalSessions; row -> summary"
  - "HudStreamingService.currentSessionId(): main-thread passthrough of the recording session id (captured before stopRecording clears it)"
  - "MainActivity finish->summary launch (id-only) + a 'View History' entry point"
affects: [05-activity-summary-strava-upload]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Session-id-ONLY Activity launch + on-disk read (readSession) — never trackPoints via intent (TransactionTooLargeException guard, T-05-11)"
    - "Wave-3 driver supplies the timing (Thread.sleep(2000) poll spacing + now+120_000 deadline + @Volatile cancel) around the pure Plan-01 driveUpload; write-back fires only in the success branch"
    - "Extract-to-object + preserved private wrappers: SportFormat is the single source of truth while every existing MainActivity call site stays untouched (no large refactor)"
    - "osmdroid Polyline + BoundingBox-in-post idiom (deferred fit until layout — Pitfall 5); defensive non-finite lat/lng skip"

key-files:
  created:
    - phone/src/main/java/com/rokid/hud/phone/SportFormat.kt
    - phone/src/main/java/com/rokid/hud/phone/ActivitySummaryActivity.kt
    - phone/src/main/java/com/rokid/hud/phone/HistoryActivity.kt
    - phone/src/main/res/layout/activity_activity_summary.xml
    - phone/src/main/res/layout/activity_history.xml
    - phone/src/main/res/layout/item_activity.xml
  modified:
    - phone/src/main/java/com/rokid/hud/phone/MainActivity.kt
    - phone/src/main/java/com/rokid/hud/phone/HudStreamingService.kt
    - phone/src/main/AndroidManifest.xml

decisions:
  - "SportFormat threads the imperial Boolean in (no Context) so it is pure; MainActivity keeps its four private wrappers (formatDist/formatElapsed/formatSpeed/formatPace) delegating to it — every existing call site unchanged, formatElev stays local (unrelated helper)"
  - "Imperial flag read from getSharedPreferences(\"MainActivity\", MODE_PRIVATE) in BOTH new activities — Activity.getPreferences maps to the file named getLocalClassName()=\"MainActivity\", so this reads the user's real toggle; an app-wide getSharedPreferences would always read the default false (metric) (checker WR)"
  - "The whole ActivitySummaryActivity was authored to Task-1 scope first (button disabled + hint), then Task 2 edited the SAME file to add the driveUpload driver + renderUploadState — matching the plan's task boundaries as two atomic commits"
  - "currentSessionId() uses activitySessionManager?.snapshotSession()?.id (main-thread-confined); the UI calls it on the main thread at the Finish tap, BEFORE stopRecording clears PREF_REC_SESSION_ID"
  - "HistoryActivity reads via the PUBLIC readSession(nameWithoutExtension) only (not internal fromJson, which is not reachable cross-file at the plan's package path) — listFinalSessions is already newest-first so order is preserved"
  - "History list uses a plain ListView + inner BaseAdapter inflating item_activity — no RecyclerView dependency, matches the no-Fragment/findViewById convention"

requirements-completed: [UPL-01, UPL-02, UPL-03, UPL-04]

# Metrics
duration: 20min
completed: 2026-07-04
---

# Phase 5 Plan 03: Activity Summary + Strava Upload UI + History Summary

**One-liner:** The user-visible capstone slice — Finish a recording and the phone opens `ActivitySummaryActivity` (launched with the session id ONLY, reading the finalized `SessionData` from disk) showing total/moving time, distance, avg speed (from data), avg pace (derived), sport, start time, and the recorded route on an osmdroid map, with a one-tap Strava upload that generates GPX → guards validity → POSTs + polls on a Thread driven by the pure Plan-01 `driveUpload` (2s spacing + 120s deadline + cancel flag live in the driver), cycling the button through Uploading → Processing → Uploaded ✓ (View on Strava) / Retry / Pending and writing back `stravaUploaded=true`+id ONLY on success; plus a newest-first `HistoryActivity` list with uploaded badges that reopens a summary on tap, the four metric formatters extracted into a shared `SportFormat` object (MainActivity delegating via preserved wrappers), a service `currentSessionId()` passthrough, and the two exported=false manifest registrations.

## Performance

- **Duration:** ~20 min
- **Tasks:** 3 (all `type="auto"`)
- **Files:** 9 (6 created, 3 modified)
- **Build:** `:phone:assembleDebug` BUILD SUCCESSFUL after every task
- **Tests:** phone 186 / shared 7, **0 failures** (unchanged from the pre-plan baseline — the UI wiring touches no pure seam)

## Accomplishments

- **`SportFormat` (extracted single source of truth)** — `object SportFormat` with `formatDist(m, imperial)`, `formatSpeed(mps, imperial)`, `formatPace(msPerKm, imperial)`, `formatElapsed(ms)`, bodies lifted VERBATIM from MainActivity's four private helpers (imperial threaded in; `formatPace` returns "–:–– /km|/mi" for `msPerKm ≤ 0`). MainActivity's four private wrappers now delegate (`SportFormat.formatX(..., isImperial())`) so all existing call sites (route info, nav distance, live rec card, steps list) are untouched. `formatElev` (unrelated) stays local.
- **`ActivitySummaryActivity` (UPL-01/02/03)** — `companion { EXTRA_SESSION_ID = "session_id" }`. onCreate reads the id (finish+toast if missing), wires the MapView (MAPNIK, zoom NEVER, multitouch), and on a `Thread{}` calls `SessionStore(File(filesDir, "activities")).readSession(id)` (null → toast+finish). Renders total time / **moving time** (the REC-02 metric finally surfaced) / distance / avg speed (**from `data.avgSpeedMps`** — never recomputed) / avg pace (**`SummaryMath.avgPaceMsPerKm(movingMs, distanceM)`** — derived) / sport / start time; draws the route via `trackPoints.filter { lat/lng finite }` → Polyline (`#FC5200`, 12f) → `BoundingBox.fromGeoPoints` fitted in `mapView.post {}` (empty → placeholder map). `onResume/onPause` drive the map. The upload is wired as a `Thread{}` calling the pure `driveUpload(start, poll, isDeadlineReached, emit, onSuccess)`: `start` = `GpxWriter.write` → `isValidForUpload` guard (fail-fast, no network cost) → `uploader.startUpload(gpx, defaultName, data.id, GpxWriter.sportType(sport))` mapped `StartResult`→`StartOutcome`; `poll` = `Thread.sleep(2_000)` then `uploader.poll(idStr)` mapped `PollResult`→`PollOutcome`; `isDeadlineReached` = `cancelled || now >= now+120_000`; `emit` = `runOnUiThread { renderUploadState(...) }`; `onSuccess` = `SessionStore(...).updateUploadState(data.id, activityId)` (write-back ONLY here). `renderUploadState` maps the six `UploadState` variants to status text + button (Uploading/Processing disabled; Done → "View on Strava" ACTION_VIEW `https://www.strava.com/activities/$id`; Failed/RateLimited/Pending → "Retry" re-running against the untouched JSON). `@Volatile cancelled` set in onDestroy abandons an in-flight poll.
- **`HistoryActivity` (UPL-04)** — onCreate loads on a `Thread{}`: `listFinalSessions().mapNotNull { readSession(it.nameWithoutExtension) }` (newest-first preserved), binds a `ListView` + inner `BaseAdapter` inflating `item_activity` (date, sport, `SportFormat.formatDist` · `SportFormat.formatElapsed`, and an "Uploaded ✓" green / "Not uploaded" grey badge). Row tap → `ActivitySummaryActivity` by id. Empty → "No recorded activities yet" placeholder.
- **`HudStreamingService.currentSessionId()`** — `fun currentSessionId(): String? = activitySessionManager?.snapshotSession()?.id` near `recordingState()`; a main-thread-safe passthrough so MainActivity grabs the id at the Finish tap **before** `stopRecording()` clears `PREF_REC_SESSION_ID`.
- **MainActivity finish→summary + history entry** — `confirmStopRecording`'s "Finish" lambda now captures `val sessionId = service?.currentSessionId()` **before** `service?.stopRecording()`, and after the existing UI update + toast launches `ActivitySummaryActivity` with the id-only extra (never trackPoints). A "View History" button (added to `activity_main.xml` under Start Recording) opens `HistoryActivity`.
- **Manifest** — `ActivitySummaryActivity` + `HistoryActivity` registered `android:exported="false"` with `@style/Theme.RokidHud`, no intent-filters (mirrors DeviceScanActivity; T-05-10 / V4 exported surface).
- **Zero new dependencies** — `phone/build.gradle.kts` unchanged (verified 0-diff vs base); osmdroid 6.1.18 + OkHttp/Gson already present.

## Device-verification hooks (for the Wave-4 / Plan-04 device plan)

Exact adb-launchable entry points + on-disk assertions:

- **Summary (post-finish):** end-to-end via the UI — start a recording, tap **Finish** → `ActivitySummaryActivity` opens automatically with the just-finished session id.
- **Summary (direct adb launch):** `adb shell am start -n com.rokid.hud.phone/.ActivitySummaryActivity --es session_id "<id>"` where `<id>` is a `{yyyyMMdd-HHmmss}-{shortUuid}` filename stem under `filesDir/activities/` (i.e. `<id>.json`). Extra key is `session_id` (= `ActivitySummaryActivity.EXTRA_SESSION_ID`). A missing/unknown id → toast "Activity not found"/"No activity to show" + finish.
- **History (direct adb launch):** `adb shell am start -n com.rokid.hud.phone/.HistoryActivity` (or the in-app "View History" button). Rows are newest-first; each row tap re-launches the summary by id.
- **Upload button gating:** the "Upload to Strava" button is enabled ONLY when `StravaAuthManager.isConnected()` (an ESP token exists). Not connected → disabled + "Connect Strava first". Already-uploaded session → disabled + "Already uploaded to Strava".
- **On-disk success assertion (UPL-03):** after a successful upload, the session JSON at `filesDir/activities/<id>.json` gains `"stravaUploaded":true` AND `"strava_activity_id":<Long>` (added atomically via `SessionStore.updateUploadState`; the write-back reuses `writeAtomic` temp+fsync+rename). On any failure/timeout the JSON is UNTOUCHED (no `strava_activity_id`, `stravaUploaded` stays false) and Retry re-uploads the same file (idempotent via duplicate recovery).
- **Progress states to observe:** Uploading… → Strava is processing… → Uploaded ✓ — View on Strava (opens `https://www.strava.com/activities/<id>`). Failure paths surface Retry ("Recording has no valid track to upload" when the GPX guard fails; "Strava busy — retry shortly" on POST 429; "Upload pending — retry later" at the 120s deadline).
- **Imperial units:** both summary and history read `getSharedPreferences("MainActivity", MODE_PRIVATE).getBoolean("use_imperial", false)` — toggle the Units switch in MainActivity, then reopen the summary/history to see mi/ft/mph/·min/mi.

## Public surface delivered

```kotlin
// com.rokid.hud.phone.SportFormat (object) — single source of truth, no Context
fun formatDist(m: Double, imperial: Boolean): String
fun formatSpeed(mps: Double, imperial: Boolean): String
fun formatPace(msPerKm: Long, imperial: Boolean): String   // "–:–– /km|/mi" when msPerKm <= 0
fun formatElapsed(ms: Long): String                        // H:MM:SS

// com.rokid.hud.phone.ActivitySummaryActivity
companion object { const val EXTRA_SESSION_ID = "session_id" }   // id-only launch

// com.rokid.hud.phone.HudStreamingService
fun currentSessionId(): String?   // capture BEFORE stopRecording clears PREF_REC_SESSION_ID
```

## Decisions Made

- **SportFormat threads imperial in; wrappers preserved** — the object has no Context, so the four formatters stay pure (JVM-testable) and MainActivity's private `formatDist/formatElapsed/formatSpeed/formatPace` delegate with `isImperial()`. This makes SportFormat the single source of truth WITHOUT touching any of the ~8 existing call sites — a minimal, regression-safe extraction. `formatElev` is an unrelated elevation helper and was left in place.
- **Imperial store = `getSharedPreferences("MainActivity")` (checker WR)** — `Activity.getPreferences(MODE_PRIVATE)` in MainActivity maps to the file named by `getLocalClassName()` = `"MainActivity"`. The two new activities (whose own class names differ) must therefore name that file explicitly to read the user's real toggle; a plain app-wide read would always see the default `false` and render metric.
- **Session-id-ONLY launch + disk read (locked)** — neither activity passes `trackPoints` through an intent (a real ride is thousands of points → `TransactionTooLargeException`, T-05-11 / 05-RESEARCH Open Q2). The summary reads `SessionData` via `readSession(id)` on a `Thread{}`; history reads per-file via `readSession(nameWithoutExtension)`.
- **Timing lives in the Wave-3 driver, not the seam** — `driveUpload` stays pure; this Activity supplies `Thread.sleep(2_000)` in the `poll` lambda, `System.currentTimeMillis() + 120_000` for the deadline, a `@Volatile cancelled` flag (set in onDestroy) folded into `isDeadlineReached`, `runOnUiThread` for `emit`, and `SessionStore.updateUploadState` for `onSuccess`. The write-back therefore fires in exactly one place (the success branch) — a mid-poll drop or the 120s timeout leaves the JSON retryable (Pitfall 4 / UPL-03).
- **`currentSessionId()` via `snapshotSession()?.id`** — the main-thread-confined snapshot id, captured by the UI on the main thread at the Finish tap before `stopRecording()` clears the pref. (The alternative — reading `PREF_REC_SESSION_ID` from `PREFS_HUD` — would also work but couples the UI-facing getter to the durable-flag pref; the snapshot id is the authoritative in-memory value.)
- **History via public `readSession` only** — `SessionStore.fromJson` is `internal` and not the intended read path for the summary/history; the public `readSession(id)` (which internally calls `fromJson`) is used with each File's `nameWithoutExtension` (the session id). `listFinalSessions()` is already newest-first, so no re-sort is needed.
- **Plain ListView + BaseAdapter for history** — no RecyclerView dependency (none present), matching the no-Fragment / findViewById convention; the inner adapter inflates `item_activity` with `convertView` recycling.

## Deviations from Plan

**None — the plan executed exactly as written.** All three tasks' actions, verify gates, and grep gates matched the plan; no bugs, missing functionality, or blocking issues were encountered (Rules 1–4 did not fire). Environment notes (non-deviations): (1) the plan's `<verification>` refers to running from the repo root — verification ran from the worktree root per worktree path-safety, with the identical Gradle-wrapper invocation form; (2) `local.properties` (sdk.dir) is gitignored and was created for the build only, never committed.

## Known Stubs

None. All three surfaces are fully wired: the summary renders real `SessionData` and drives a real Strava upload, history lists real finalized sessions, and the finish→summary launch + service getter are live. No TODO/FIXME/placeholder/hardcoded-empty patterns in any created or modified file (the "coming in Task 2" guard the plan explicitly forbade was NOT used — Task 2 edited the file to add the real driver).

## Threat Model Compliance

All `mitigate`-disposition threats from the plan's `<threat_model>` are addressed:
- **T-05-10 (new activities exported to other apps):** both `ActivitySummaryActivity` + `HistoryActivity` are `android:exported="false"` with no intent-filter — internal navigation only. (Grep: exported="false" present on both blocks.)
- **T-05-11 (TransactionTooLargeException from bulk trackPoints via intent):** launch carries the session id ONLY; both activities read `trackPoints` from disk via `readSession`. Grep: `putExtra.*trackPoints|getParcelableArrayListExtra` = **0** in both activities.
- **T-05-12 (optimistic success marking on a network drop):** `updateUploadState` is called ONLY in `driveUpload`'s `onSuccess` (the Ready/Duplicate branch); a drop/timeout lands in Failed/Pending with the local JSON untouched → safe retry (UPL-03).
- **T-05-13 (GPX/coordinates/token via UI logs):** the Activity logs only a status-open failure message (`Log.w` on the Strava intent) — a grep of every `Log.` line for `gpx|Bearer|token|.lat|.lng|coordinate` = **0**. The DEBUG interceptor stays BASIC (Plan 02).
- **T-05-14 (View-on-Strava intent leaking data):** the ACTION_VIEW opens only `https://www.strava.com/activities/<numeric Long id>` — no user data in the URL.
- **T-05-SC (dependency installs):** zero packages added; `phone/build.gradle.kts` is 0-diff vs the plan base.

No new threat surface beyond the register.

## Verification Results

- `:phone:assembleDebug` — BUILD SUCCESSFUL after Task 1, Task 2, and Task 3 (both new activities + three layouts + manifest compile).
- `:phone:testDebugUnitTest :shared:testDebugUnitTest` — BUILD SUCCESSFUL; **phone 186 / shared 7, 0 failures** (identical to the pre-plan baseline; the pure Wave-1/2 seams are unaffected by the UI wiring).
- Grep gates (all pass): Task 1 — `SummaryMath.avgPaceMsPerKm` = 1, trackPoints-via-intent = 0; Task 2 — `driveUpload` = 7, `updateUploadState` = 1, `isValidForUpload` = 1; Task 3 — manifest `ActivitySummaryActivity`/`HistoryActivity` present + both exported=false, `currentSessionId` in MainActivity (captured on the line above `stopRecording()`), `listFinalSessions` in HistoryActivity.
- Imperial-store gate: `getSharedPreferences("MainActivity"` present in BOTH new activities (checker WR).
- `phone/build.gradle.kts` unchanged vs base (zero new dependencies).
- Security scan: no `Log.` statement in either new activity emits a token, GPX body, or coordinate.

## Commits

| Hash | Type | Description |
|------|------|-------------|
| 9e0b108 | feat | Extract SportFormat + ActivitySummaryActivity metrics + osmdroid route map (id-only launch) |
| 9062e49 | feat | Wire the one-tap Strava upload (GPX → guard → POST → poll via driveUpload) with progress states |
| 0226290 | feat | HistoryActivity + list layouts + MainActivity finish→summary + history entry + service currentSessionId() + manifest |

## Self-Check: PASSED

All created files verified present (`SportFormat.kt`, `ActivitySummaryActivity.kt`, `HistoryActivity.kt`, `activity_activity_summary.xml`, `activity_history.xml`, `item_activity.xml`); all three task commit hashes (`9e0b108`, `9062e49`, `0226290`) verified in git log. Full gate green: `:phone:assembleDebug` builds; phone 186 + shared 7 tests, 0 failures. Grep gates green (driveUpload/updateUploadState/isValidForUpload present; both activities exported=false; currentSessionId before stopRecording; listFinalSessions in history; 0 trackPoints intent extras; imperial store `"MainActivity"` in both activities). `phone/build.gradle.kts` unchanged (0 new deps). STATE.md / ROADMAP.md NOT modified (orchestrator owns them).

---
*Phase: 05-activity-summary-strava-upload*
*Completed: 2026-07-04*
