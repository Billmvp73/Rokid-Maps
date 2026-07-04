---
phase: 05-activity-summary-strava-upload
reviewed: 2026-07-03T00:00:00Z
depth: standard
files_reviewed: 14
files_reviewed_list:
  - phone/src/main/java/com/rokid/hud/phone/strava/GpxWriter.kt
  - phone/src/main/java/com/rokid/hud/phone/strava/UploadState.kt
  - phone/src/main/java/com/rokid/hud/phone/strava/StravaUploader.kt
  - phone/src/main/java/com/rokid/hud/phone/strava/StravaModels.kt
  - phone/src/main/java/com/rokid/hud/phone/SummaryMath.kt
  - phone/src/main/java/com/rokid/hud/phone/SessionStore.kt
  - phone/src/main/java/com/rokid/hud/phone/SportFormat.kt
  - phone/src/main/java/com/rokid/hud/phone/ActivitySummaryActivity.kt
  - phone/src/main/java/com/rokid/hud/phone/HistoryActivity.kt
  - phone/src/main/java/com/rokid/hud/phone/MainActivity.kt
  - phone/src/main/java/com/rokid/hud/phone/HudStreamingService.kt
  - phone/src/main/res/layout/activity_activity_summary.xml
  - phone/src/main/res/layout/activity_history.xml
  - phone/src/main/res/layout/item_activity.xml
findings:
  critical: 0
  warning: 3
  info: 3
  total: 6
status: issues_found
---

# Phase 5: Code Review Report

**Reviewed:** 2026-07-03
**Depth:** standard
**Files Reviewed:** 14
**Status:** issues_found

## Summary

Phase 5 (Activity Summary + Strava Upload) is well-constructed and the focus-area
invariants largely hold up under scrutiny:

- **StravaUploader** — poll is stateless-per-call; OkHttp owns the multipart boundary
  (only `Authorization` is set by hand, line 124); a 429 on POST maps to `RateLimited`
  and a 429 on GET maps to `Error` (never a false success); duplicate errors are
  recovered to `Duplicate`; logging is `Level.BASIC` DEBUG-only, and no
  token/GPX/coordinate content is logged. All catch blocks log-and-return per convention.
- **GpxWriter** — every `<trkpt>` unconditionally emits a `<time>` (lines 78-80);
  `<ele>` is omitted on non-finite altitude; the serializer comes from
  `XmlPullParserFactory` (kxml2), not `android.util.Xml`; `isValidForUpload` fail-fasts
  on empty / no-trkpt / any-trkpt-missing-`<time>`; the duplicate regex is unanchored
  and `\d+`→`toLongOrNull` only.
- **SessionStore.updateUploadState** — write-back is ADD-only (`toJsonWithActivityId`
  reuses `toJson` then appends), atomic (unique temp + `fd.sync()` + rename), runs off
  the main thread, and is forward-compatible (`strava_activity_id` read-and-ignored in
  `fromJson`, absent key = fine).
- **SummaryMath** — `avgPaceMsPerKm` mirrors `ActivitySessionManager.avgPaceMsPerKm`
  EXACTLY (same 100 m floor, same `roundToLong`, verified line-for-line); avg speed is a
  documented passthrough (never recomputed).
- **Imperial pref** — `getSharedPreferences("MainActivity", …)` in the two new
  activities matches `MainActivity.getPreferences(MODE_PRIVATE)` because
  `getLocalClassName()` for an activity in the app's base package is `"MainActivity"`
  (no dotted prefix). Delegation keeps every MainActivity call site working.
- **Manifest** — both new activities are `exported="false"` with no intent-filter.

Three Warnings and three Info items remain. The most consequential is a launch-time
race on the primary finish→summary flow (WR-01): the summary reads the finalized JSON
from disk while the service is still writing it asynchronously, so the happy path can
show "Activity not found."

## Warnings

### WR-01: Finish→summary launch races the asynchronous session finalize (summary can show "Activity not found")

**File:** `phone/src/main/java/com/rokid/hud/phone/MainActivity.kt:1210-1221`, `phone/src/main/java/com/rokid/hud/phone/HudStreamingService.kt:442-478`, `phone/src/main/java/com/rokid/hud/phone/ActivitySummaryActivity.kt:102-116`

**Issue:** On the primary "Finish recording" flow, `confirmStopRecording()` calls
`service.stopRecording()` and then, in the same synchronous block, launches
`ActivitySummaryActivity`. But `stopRecording()` finalizes the session **fully
asynchronously**: the finalize `Runnable` is posted only after `flushLocations()`
completes (`HudStreamingService.kt:473`), and the actual disk write is
`sessionStore.finalizeAsync(data)` (`:451`), which serializes on the store's
single-thread executor. Meanwhile `ActivitySummaryActivity.onCreate` spawns a Thread
that immediately calls `store.readSession(id)` (`ActivitySummaryActivity.kt:104`), which
reads the finalized `{id}.json` file. Because launching the Activity is far faster than
`flushLocations` → main-handler post → executor-queue → fsync+rename, the final file
almost certainly does not exist yet when `readSession` runs, so it returns null and the
screen shows "Activity not found" and immediately `finish()`es (`:105-110`). The activity
is still recoverable later from History, so this is not data loss — but the intended
happy path is broken.

**Fix:** Make the summary tolerant of the not-yet-finalized window instead of assuming
the file is present at launch. For example, retry the disk read a few times with a short
backoff before giving up:
```kotlin
Thread {
    val store = SessionStore(File(filesDir, "activities"))
    var data: SessionData? = null
    repeat(10) {                       // ~2s worst case
        data = store.readSession(id)
        if (data != null) return@repeat
        try { Thread.sleep(200) } catch (_: InterruptedException) {}
    }
    val loaded = data
    if (loaded == null) {
        runOnUiThread {
            Toast.makeText(this, "Activity not found", Toast.LENGTH_SHORT).show()
            finish()
        }
        return@Thread
    }
    sessionData = loaded
    runOnUiThread { renderMetrics(loaded) }
    renderRoute(loaded)
    refreshUploadAvailability(loaded)
}.start()
```
(Alternatively, have the service expose a synchronous "finalize now and return the id"
path, or defer the summary launch until finalize completes.)

### WR-02: Uninitialized `mapView` crashes in `onResume` when the summary is launched with a null/empty session id

**File:** `phone/src/main/java/com/rokid/hud/phone/ActivitySummaryActivity.kt:83-91`, `:319-322`

**Issue:** In `onCreate`, the null/empty-id guard runs `finish(); return`
(`:84-88`) **before** `mapView` is assigned via `findViewById` (`:91`). Calling `finish()`
inside `onCreate` does not stop the lifecycle from proceeding through
`onStart`/`onResume` before teardown, so `onResume()` executes `mapView.onResume()`
(`:321`) against the still-uninitialized `lateinit var mapView` (`:64`), throwing
`UninitializedPropertyAccessException` and crashing the app. Any caller that launches the
summary without `EXTRA_SESSION_ID` (or with an empty value) triggers this. The same
uninitialized-property risk applies to `onPause()`→`mapView.onPause()` (`:325`).

**Fix:** Guard the lifecycle overrides against the not-yet-initialized field:
```kotlin
override fun onResume() {
    super.onResume()
    if (::mapView.isInitialized) mapView.onResume()
}

override fun onPause() {
    super.onPause()
    if (::mapView.isInitialized) mapView.onPause()
}
```

### WR-03: `renderUploadState` runs on a destroyed activity after the deadline/cancel path fires

**File:** `phone/src/main/java/com/rokid/hud/phone/ActivitySummaryActivity.kt:232-233`, `:248-277`, `:329-332`

**Issue:** The upload driver's `emit` seam is `emit = { st -> runOnUiThread { renderUploadState(st, data) } }` (`:233`). When the screen is destroyed
mid-upload, `onDestroy` sets `cancelled = true` (`:330`); `isDeadlineReached` (`:232`)
then returns true, the poll loop in `driveUpload` exits, and it calls `emit(Pending)`.
That posts `renderUploadState(Pending, data)` (`:272-275`), which calls `setRetry(data)`
→ re-arms `btnUpload`'s click listener with `startUpload(data)` (`:280-283`) on a
finished Activity. The captured `this`/`data`/view references keep the destroyed Activity
reachable until the posted runnable drains, and any subsequent emission (e.g. a `Done`
that lands just after destroy) re-attaches a click handler that could relaunch an upload
Thread on a dead screen. No crash was proven (the detached views tolerate mutation), but
this is a latent leak/incorrect-lifecycle path the focus list explicitly calls out
("activity leak (listener/thread after finish)").

**Fix:** Make the UI emissions no-op once destroyed, e.g. gate `renderUploadState` on the
`cancelled`/`isFinishing`/`isDestroyed` state:
```kotlin
emit = { st -> runOnUiThread { if (!cancelled && !isFinishing) renderUploadState(st, data) } }
```
This also prevents the post-destroy `setRetry` from re-arming the button.

## Info

### IN-01: `defaultName` date-empty branch is effectively dead

**File:** `phone/src/main/java/com/rokid/hud/phone/ActivitySummaryActivity.kt:299-303`

**Issue:** `val date = data.startTime.take(10)` followed by
`if (date.isNotEmpty()) "$label • $date" else label`. `take(10)` on any non-empty
`startTime` yields a non-empty string, and a finalized `SessionData.startTime` is always
a populated ISO string, so the `else label` branch is unreachable in practice. Harmless,
but the guard implies a case that cannot occur.

**Fix:** Either drop the guard (`"$label • ${data.startTime.take(10)}"`) or, if defending
against a blank `startTime`, check `data.startTime.isNotBlank()` explicitly so the intent
is clear.

### IN-02: `prettyStart` / `prettyDate` assume a fixed ISO offset and are duplicated verbatim

**File:** `phone/src/main/java/com/rokid/hud/phone/ActivitySummaryActivity.kt:312-317`, `phone/src/main/java/com/rokid/hud/phone/HistoryActivity.kt:115-120`

**Issue:** Both helpers do `startTime.substring(11, 16)` to pull "HH:mm", assuming the
`T` separator sits at index 10 and the time begins at 11. The producer emits
`Instant.toString()` (UTC, `…T09:15:00Z`), so this holds today; but the two
implementations are byte-for-byte identical across two files, and any future change to
the timestamp format (e.g. an offset like `+02:00`) would silently misformat in both.
The `length < 16` guard prevents an out-of-bounds crash, so this is polish, not a bug.

**Fix:** Hoist the single implementation into the shared `SportFormat` object (already the
home of the other formatters) and call it from both activities, removing the duplication.

### IN-03: `sportLabel` is duplicated across the two new activities

**File:** `phone/src/main/java/com/rokid/hud/phone/ActivitySummaryActivity.kt:305-309`, `phone/src/main/java/com/rokid/hud/phone/HistoryActivity.kt:108-112`

**Issue:** The identical `sportLabel(sport)` `when` (run/ride/else-capitalize) appears in
both `ActivitySummaryActivity` and `HistoryActivity`. Minor duplication; note it also
differs from `GpxWriter.sportType` (which maps to Strava's PascalCase `Ride`/`Run` for
the API), so the two are intentionally separate — but the two *UI* copies could share one.

**Fix:** Move `sportLabel` alongside the other formatters in `SportFormat` and delegate
from both activities.

---

## Narrative Findings (AI reviewer)

All findings above are from direct adversarial review of the Phase 5 delta. No
`<structural_findings>` block was supplied, so there is no fallow substrate section.

Focus-area items explicitly verified as **correct** (no finding):

1. **StravaUploader poll loop** — `@Volatile cancelled` folds into the deadline
   predicate; the 2-min deadline is enforced by the caller
   (`ActivitySummaryActivity.kt:205,232`); `Thread.sleep(2_000)` sits inside `poll`
   (`:224`) so there is no busy-spin; duplicate→success is handled in both `poll`
   (`StravaUploader.kt:193-197`) and `driveUpload` (`UploadState.kt:119-129`); nothing is
   rethrown; OkHttp owns `Content-Type` (only `Authorization` set, `:124`); no
   token/GPX/coordinate is logged (`:214-218`, `:145`, `:202`).
2. **GpxWriter** — every `<trkpt>` gets an unconditional ISO-UTC `<time>`
   (`GpxWriter.kt:78-80`); NaN `<ele>` omitted (`:74`); kxml2 via `XmlPullParserFactory`
   (`:59`); `isValidForUpload` rejects empty/no-trkpt/missing-`<time>` (`:101-138`);
   well-formed start/end tag pairing verified.
3. **SessionStore.updateUploadState** — atomic (`writeAtomic` unique-temp + `fd.sync()`
   + rename, `:171-191`), ADD-only (`toJsonWithActivityId`, `:157-158`), off-main
   (`executor`, `:341-349`), forward-compatible (`fromJson` reads-and-ignores
   `strava_activity_id`, `:113-115`). Cross-instance concurrency with the service's
   checkpoint executor is safe because the unique-temp-name design tolerates concurrent
   writers (last atomic rename wins) and the file is finalized (checkpoint deleted)
   before the summary can write.
4. **ActivitySummaryActivity** — launched by session id only (no
   TransactionTooLargeException; `MainActivity.kt:1216-1220`); disk read off-main
   (`:102`); osmdroid `onResume`/`onPause` wired (`:319-327`); upload state machine
   covers uploading/processing/done/failed/pending (`:248-277`); route draw skips
   non-finite lat/lng and handles the empty-points case (`:144-168`).
5. **HistoryActivity** — list read off-main (`:47-54`), badge correct (`:96-103`),
   row→summary intent by id (`:69-75`), empty state wired (`:60-65` + layout).
6. **Imperial pref** — consistent `"MainActivity"` store across all three surfaces
   (verified against `MainActivity.getPreferences` semantics).
7. **Duplicate regex** unanchored (`GpxWriter.kt:43`); **sport_type** PascalCase mapping
   (`:154-157`); **pace** derived, not persisted (`SummaryMath.kt:29-31`).
8. **Manifest** — new activities `exported="false"`, no intent-filter
   (`AndroidManifest.xml:57-65`).

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
| WR-01 | fixed (requires human verification — timing/race change) | `49158ea` | Made the background disk read in `ActivitySummaryActivity.onCreate` resilient to the async finalize lag: retry `store.readSession(id)` up to 15 times with `Thread.sleep(200)` (~3s budget), breaking on the first non-null `SessionData`; only after the budget is exhausted does it toast "Activity not found" + `finish()`. `stopRecording` left asynchronous (flushLocations is async IPC — blocking main is worse). |
| WR-02 | fixed | `b2831c2` | Guarded `mapView.onResume()` / `mapView.onPause()` with `if (::mapView.isInitialized)` so the null/empty-session-id path (which `finish()`es before `mapView` is assigned) no longer crashes with `UninitializedPropertyAccessException` when the lifecycle still runs onResume/onPause. |
| WR-03 | fixed | `08b0dbb` | Gated the `driveUpload` `emit` lambda to no-op once the screen is gone: inside its `runOnUiThread`, early-out unless `!cancelled && !isFinishing && !isDestroyed`, so a terminal emission after `onDestroy` no longer calls `setRetry(...)` and re-arms `btnUpload`'s click listener on a finished Activity. Uses the existing `@Volatile cancelled` flag set in `onDestroy`. |

**Info findings (IN-01 … IN-03): deferred** — outside the fix scope (critical + warning only).
