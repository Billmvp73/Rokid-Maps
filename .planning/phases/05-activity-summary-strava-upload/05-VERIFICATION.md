---
phase: 05-activity-summary-strava-upload
verified: 2026-07-03T00:00:00Z
status: human_needed
score: 4/4 code-verifiable must-haves verified (device/human confirmation pending — plan 05-04)
overrides_applied: 0
scope: code-level only (device verification 05-04 is the pending batched milestone-finale)
re_verification:
  previous_status: null
  note: initial verification
human_verification:
  - test: "On the OPPO test phone, tap Upload on a recorded activity → confirm it appears in the user's REAL Strava feed with a route line + plausible metrics"
    expected: "The activity shows up in the user's Strava feed (correct sport, route, time/distance) — the one irreducible UPL-02 true proof (no adb can observe strava.com)"
    why_human: "Requires a live Strava account + human eyes on the Strava feed; the code path (GPX → POST /uploads → poll → activity_id) is fully verified but the end-state lives on Strava's servers"
  - test: "Re-upload the same activity → confirm duplicate recovery resolves to the SAME activity (no second activity in the feed, no data loss)"
    expected: "The duplicate-error path (`duplicate of activity <id>`) recovers the existing activity id and marks success; the feed shows exactly one activity"
    why_human: "The live Strava duplicate response can only be produced against the real API on a second upload of a real activity"
  - test: "Simulate an upload failure (airplane mode / token revoke) mid-upload → confirm the on-disk {id}.json keeps stravaUploaded:false and Retry re-uploads the same file"
    expected: "Local JSON is untouched on failure (no strava_activity_id, stravaUploaded=false); Retry works against the same file (UPL-03 on hardware — adb-provable)"
    why_human: "Requires inducing a real network/token failure on-device and inspecting the on-disk file via adb; batched into the device session"
  - test: "On the real phone, record → Finish → confirm the summary opens with plausible time/moving-time/distance/avg speed+pace and a route line (the WR-01 ~3s retry window landing on the real async finalize)"
    expected: "The summary renders (not 'Activity not found') because the 15×200ms disk-read retry covers the real finalizeAsync lag on real hardware timing"
    why_human: "The WR-01 fix is a timing/race change; only real-device finalize timing confirms the retry budget covers it (the fix is present and unit-safe, but the race is device-timing-dependent)"
  - test: "On the real phone, open History → confirm the just-uploaded session shows an 'uploaded ✓' badge and tapping it reopens the summary (UPL-04 on hardware)"
    expected: "History lists the session newest-first with the uploaded badge; row tap reopens ActivitySummaryActivity"
    why_human: "End-to-end UI observation after a real upload write-back; batched into the device session"
---

# Phase 5: Activity Summary + Strava Upload Verification Report

**Phase Goal:** User views activity summaries and uploads completed activities to Strava
**Verified:** 2026-07-03 (code-level)
**Status:** human_needed
**Re-verification:** No — initial verification
**Scope:** CODE-LEVEL only. Plan 05-04 (milestone-finale device verification) is legitimately pending a phone unlock + the user confirming the activity in their real Strava feed. All code-verifiable must-haves are met; the remaining items are device/human-only and batched into 05-04.

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | (SC1/UPL-01) After recording stops, user sees an activity summary with total time, **moving time**, distance, avg speed AND avg pace (imperial-aware, pace derived), sport, start, and a route map | ✓ VERIFIED (code) | `ActivitySummaryActivity.renderMetrics` (lines 137-150) renders all 7 metrics; avg speed read from `data.avgSpeedMps` (144), avg pace `SummaryMath.avgPaceMsPerKm(movingMs, distanceM)` (146, derived not persisted), imperial via `getSharedPreferences("MainActivity")` (133-134). `renderRoute` (158-183) draws osmdroid Polyline from `trackPoints` (BoundingBox-in-post). Launch id-only from `MainActivity` (1229-1239). **P5-WR-01 fix present**: `readSession` retried 15×/200ms (~3s) before "Activity not found" (113-117) |
| 2 | (SC2/UPL-02) User can upload to Strava with one tap and sees upload progress/confirmation | ✓ VERIFIED (code) — real-feed proof pending | `startUpload` → `Thread` → pure `driveUpload` (217-264). GPX build → `isValidForUpload` guard → `StravaUploader.startUpload`/`poll` (223-244). `StravaUploader` POSTs `MultipartBody.FORM` (boundary owned by OkHttp; NO manual Content-Type — grep=0), `data_type=gpx`, `sport_type` PascalCase, `external_id`=session id (114-120); poll GET `/uploads/{id_str}` (171); 6 progress states rendered (272-301). Duplicate regex unanchored, treated as success (GpxWriter 43, 147; UploadState 119-129) |
| 3 | (SC3/UPL-04) Past recorded activities are listed and viewable at any time | ✓ VERIFIED (code) | `HistoryActivity` reads `listFinalSessions` (50), rows show date/sport/distance/duration (92-95) + uploaded✓/not-uploaded badge (97-103), tap → `ActivitySummaryActivity` by id (69-75), empty state (61-65). Entry point in `MainActivity` (724) |
| 4 | (SC4/UPL-03) If upload fails, local data remains available for retry; data never deleted before upload succeeds | ✓ VERIFIED (code) | Write-back only in `driveUpload` success branch (`onSuccess` → `updateUploadState`, 258-261); state machine fires `onSuccess` in exactly one place (UploadState 109-118). `updateUploadStateSync` (SessionStore 359-368) reads full session, `copy(stravaUploaded=true)`, appends only `strava_activity_id` via `toJsonWithActivityId` (157-158), atomic `writeAtomic` temp+fsync+rename (171-191) — preserves all trackPoints. NO delete/purge method (grep=0, comment line 311). Retry re-runs against untouched JSON (304-308) |

**Score:** 4/4 code-verifiable truths VERIFIED. Device/human confirmation of each on hardware is pending (plan 05-04).

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `phone/.../strava/GpxWriter.kt` | GPX 1.1 writer + validity guard + duplicate regex + sportType | ✓ VERIFIED | 158 lines; `Instant.ofEpochMilli(p.ts).toString()` <time> on every trkpt (78-80); NaN `<ele>` omit (74); XmlPullParserFactory not android.util.Xml (59, grep=0); unanchored regex (43); PascalCase sportType (154-157) |
| `phone/.../strava/UploadState.kt` | UploadState sealed + pure driveUpload | ✓ VERIFIED | 139 lines; write-back (`onSuccess`) only in Ready/Duplicate branch (109-118); timeout→Pending no write-back (136); duplicate-error reinterpreted as success (119-129) |
| `phone/.../strava/StravaUploader.kt` | POST /uploads multipart + poll GET /uploads/{id_str} | ✓ VERIFIED | 219 lines; `MultipartBody.FORM` (114-115), only Authorization header (124), id_str poll path (171), `ensureFreshToken` both methods (113, 169), 429 distinct (130-133 POST RateLimited / 177-181 poll Error), never rethrows |
| `phone/.../strava/StravaModels.kt` (UploadResponse) | all-nullable Gson model | ✓ VERIFIED | UploadResponse all-nullable @SerializedName id/id_str/external_id/error/status/activity_id (165-172) |
| `phone/.../SummaryMath.kt` | pace derivation mirroring recorder | ✓ VERIFIED | `avgPaceMsPerKm` 100m floor + roundToLong (29-31); avgSpeed passthrough (38) |
| `phone/.../SessionStore.kt` (readSession/updateUploadState) | atomic add-only write-back | ✓ VERIFIED | readSession (325-333), updateUploadState async (341-349) + Sync seam (359-368), toJsonWithActivityId add-only (157-158) |
| `phone/.../SportFormat.kt` | extracted imperial-aware formatters | ✓ VERIFIED | present; MainActivity delegates (SportFormat referenced in summary + history + card) |
| `phone/.../ActivitySummaryActivity.kt` | summary + upload driver | ✓ VERIFIED | 361 lines; all 3 review fixes present (WR-01 113-117, WR-02 348/354, WR-03 253-256) |
| `phone/.../HistoryActivity.kt` | past-activities list + badge | ✓ VERIFIED | 121 lines; listFinalSessions, badge, row→summary |
| `phone/.../AndroidManifest.xml` | both activities exported=false | ✓ VERIFIED | ActivitySummaryActivity (58-59) + HistoryActivity (63-64) both exported=false, no intent-filter |
| Layouts (summary/history/item) | all required view IDs | ✓ VERIFIED | activity_activity_summary.xml (11/11 IDs), activity_history.xml (historyList/historyEmpty), item_activity.xml (rowDate/rowSport/rowStats/rowUploadBadge) |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| MainActivity.confirmStopRecording | ActivitySummaryActivity | startActivity + EXTRA_SESSION_ID captured before stopRecording | ✓ WIRED | id captured (1229) BEFORE stopRecording (1230); id-only launch (1237-1238) |
| ActivitySummaryActivity upload Thread | driveUpload / StravaUploader / updateUploadState | GpxWriter.write → isValidForUpload → startUpload/poll → updateUploadState on success | ✓ WIRED | full chain (223-261); write-back only in onSuccess |
| HistoryActivity | SessionStore.listFinalSessions | rows → ActivitySummaryActivity(id) | ✓ WIRED | listFinalSessions (50), row tap intent (69-75) |
| HudStreamingService | currentSessionId | snapshotSession()?.id passthrough | ✓ WIRED | getter present (490) |
| StravaUploader.startUpload | okhttp3.MultipartBody | MultipartBody.Builder().setType(FORM) | ✓ WIRED | 114-115; OkHttp owns boundary, no manual Content-Type (grep=0) |
| StravaUploader | StravaAuthManager.ensureFreshToken | Bearer per request | ✓ WIRED | 113, 169 |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Full build + unit test gate | `testDebugUnitTest assembleDebug -q` | exit 0 (phone-debug.apk built, all unit tests pass) | ✓ PASS |
| GpxWriterTest (time-on-every-point Pitfall-4 trap) | test-results XML | tests=15 failures=0 | ✓ PASS |
| UploadStateMachineTest (write-back-only-on-success) | test-results XML | tests=8 failures=0 | ✓ PASS |
| SessionStoreTest (UPL-03 preserves all trackPoints, no .tmp) | test-results XML | tests=28 failures=0 | ✓ PASS |
| StravaUploadModelTest (all-nullable, id_str verbatim) | test-results XML | tests=5 failures=0 | ✓ PASS |
| SummaryMathTest (pace mirrors recorder) | test-results XML | tests=5 failures=0 | ✓ PASS |
| Real end-to-end upload to Strava | (device — plan 05-04) | pending | ? SKIP → human |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| UPL-01 | 05-01, 05-03 | Activity summary: total/moving time, distance, avg speed/pace, route map | ✓ SATISFIED (code) | ActivitySummaryActivity.renderMetrics + renderRoute |
| UPL-02 | 05-01, 05-02, 05-03 | One-tap upload (GPX, POST, poll) | ✓ SATISFIED (code); feed-proof pending | StravaUploader + driveUpload; real-feed = human moment (05-04) |
| UPL-03 | 05-01, 05-02, 05-03 | Upload failure never deletes local data; retry | ✓ SATISFIED (code) | write-back only on success + atomic add-only + no delete method |
| UPL-04 | 05-01, 05-03 | View past activities (local history) | ✓ SATISFIED (code) | HistoryActivity + listFinalSessions |

No orphaned requirements — all four UPL-* IDs mapped to plans and implemented.

### Review-Warning Fixes Confirmed (all 3 present in code)

| Finding | Fix commit | Verified in code |
|---------|-----------|------------------|
| P5-WR-01 (finish→summary races async finalize) | `49158ea` | `ActivitySummaryActivity.kt:113-117` — `repeat(15) { readSession; if null Thread.sleep(200) }` (~3s budget) before "Activity not found" |
| P5-WR-02 (uninitialized mapView in onResume/onPause) | `b2831c2` | `:348`, `:354` — `if (::mapView.isInitialized) mapView.onResume()/onPause()` |
| P5-WR-03 (renderUploadState on destroyed activity) | `08b0dbb` | `:253-256` — `emit` gated on `!cancelled && !isFinishing && !isDestroyed` |

All 3 fix commits confirmed to modify `ActivitySummaryActivity.kt` (git show --stat).

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| — | — | No TODO/FIXME/XXX/HACK/PLACEHOLDER/"coming soon" in any Phase-5 created or modified file | ℹ️ Info | Clean — no debt markers |

The only "Stub!" token in the source is inside a GpxWriter doc comment explaining why `android.util.Xml` (which throws `RuntimeException: Stub!` on JVM) is avoided — descriptive, not a stub. `return null`/empty-collection patterns are legitimate (readSession null-for-missing, empty-track placeholder) — none flow to user-visible output as hollow data.

### Commits Verified

All 11 referenced commits exist in git:
- Plan 05-01: `a98f501`, `60634f6`, `a199cde`
- Plan 05-02: `e750925`, `f3f3903`
- Plan 05-03: `9e0b108`, `9062e49`, `0226290`
- Review fixes: `49158ea` (WR-01), `b2831c2` (WR-02), `08b0dbb` (WR-03)

`phone/build.gradle.kts` unchanged (zero new dependencies) — confirmed.

### Human Verification Required

The v1.0 milestone finale (plan 05-04) is legitimately pending a phone unlock + the user's real Strava account. These items are device/human-only — the code path for each is fully verified, but the true end-state lives on Strava's servers or depends on real-device timing:

1. **Real upload appears in Strava feed (UPL-02 true proof)** — tap Upload → confirm the activity shows in the user's real Strava feed with a route + plausible metrics. *The one irreducible human moment — no adb can see strava.com.*
2. **Live duplicate re-upload** — re-upload the same activity → confirm duplicate recovery resolves to the SAME activity (no second feed entry, no data loss).
3. **Upload-fail → retry (UPL-03 on hardware)** — induce a real failure (airplane mode/token revoke) → confirm on-disk JSON keeps `stravaUploaded:false` and Retry works (adb-provable).
4. **WR-01 ~3s retry window on real finalize** — record → Finish → confirm the summary renders (not "Activity not found") against real-device finalizeAsync timing.
5. **History badge after real upload (UPL-04 on hardware)** — open History → confirm the uploaded✓ badge and tap-to-reopen.

All five are enumerated in plan 05-04's own frontmatter as the batched device session (which brackets the pending Phase-3 live Authorize + Phase-4 real-route). 05-04-SUMMARY.md / device results are absent, confirming the session has not yet run.

### Gaps Summary

**No code gaps.** All four ROADMAP Success Criteria (UPL-01..04) are fully implemented and verified against the actual codebase, not merely the SUMMARY claims:
- The load-bearing Pitfall-4 traps are all handled in code AND asserted by tests: ISO-8601 UTC `<time>` on every trkpt (the flagged trap), OkHttp-owned multipart boundary (no manual Content-Type), id_str poll path, unanchored duplicate regex treated as success, proactive token refresh, and — the UPL-03 structural guarantee — write-back exclusively in the success branch over an atomic add-only `writeAtomic` that preserves every trackPoint with no delete/purge method.
- All 3 code-review warnings (WR-01/02/03) are confirmed fixed in `ActivitySummaryActivity.kt`.
- Full `testDebugUnitTest assembleDebug` gate exits 0; 61 Phase-5 unit tests pass (15+8+28+5+5 across the five suites).

**Status is `human_needed`, not `passed`,** solely because the milestone-finale device verification (plan 05-04) — the real upload landing in the user's Strava feed being UPL-02's only true proof — has not yet been run. This is a legitimately-pending human confirmation, not a code defect. Once 05-04 is executed (phone unlock + human feed confirmation), the phase closes.

---

_Verified: 2026-07-03 (code-level)_
_Verifier: Claude (gsd-verifier)_
