---
phase: 01-activity-recording-engine
reviewed: 2026-07-03T17:42:15Z
depth: standard
files_reviewed: 19
files_reviewed_list:
  - shared/src/main/java/com/rokid/hud/shared/protocol/Messages.kt
  - shared/src/main/java/com/rokid/hud/shared/protocol/ProtocolCodec.kt
  - shared/src/main/java/com/rokid/hud/shared/protocol/ProtocolConstants.kt
  - shared/src/test/java/com/rokid/hud/shared/protocol/ProtocolCodecTest.kt
  - shared/build.gradle.kts
  - phone/build.gradle.kts
  - phone/src/main/AndroidManifest.xml
  - phone/src/main/java/com/rokid/hud/phone/SessionModels.kt
  - phone/src/main/java/com/rokid/hud/phone/ActivitySessionManager.kt
  - phone/src/main/java/com/rokid/hud/phone/SessionStore.kt
  - phone/src/main/java/com/rokid/hud/phone/HudStreamingService.kt
  - phone/src/main/java/com/rokid/hud/phone/MainActivity.kt
  - phone/src/main/java/com/rokid/hud/phone/RecordingWatchdog.kt
  - phone/src/main/res/layout/activity_main.xml
  - phone/src/test/java/com/rokid/hud/phone/ActivitySessionManagerTest.kt
  - phone/src/test/java/com/rokid/hud/phone/SessionStoreTest.kt
  - phone/src/debug/java/com/rokid/hud/phone/MockRouteFeeder.kt
  - phone/src/debug/AndroidManifest.xml
  - glasses/src/main/java/com/rokid/hud/glasses/BluetoothClient.kt
findings:
  critical: 0
  warning: 7
  info: 4
  total: 11
status: issues_found
---

# Phase 1: Code Review Report — Activity Recording Engine

**Reviewed:** 2026-07-03T17:42:15Z
**Depth:** standard
**Files Reviewed:** 19
**Status:** issues_found

## Summary

Reviewed the full Phase 1 recording pipeline: protocol additions (sport_state), the main-thread-confined `ActivitySessionManager`, `SessionStore` persistence with atomic writes and orphan recovery, `HudStreamingService` recording lifecycle/ticker/notification wiring, `RecordingWatchdog` L1/L2, MainActivity recording UI + onboarding prompts, the debug mock feeder, and the glasses no-op branch.

The core metric math is correct and well-tested: haversine is a verbatim copy of `NavigationManager.haversineM` (NavigationManager.kt:149-156, matching the stated convention), the 0.7/0.3 hysteresis boundaries are strictly exclusive per REC-04 (`ActivitySessionManager.kt:246-249`, boundary tests at `ActivitySessionManagerTest.kt:391-420`), the accuracy gate accepts exactly 20.0 m and rejects >20/unknown/zero (`ActivitySessionManager.kt:262`), the 5-slot speed MA holds only valid speeds (`ActivitySessionManager.kt:242-251`), and elapsed/distance monotonic clamps live at the source (`ActivitySessionManager.kt:340-355`). Thread confinement holds: every `ActivitySessionManager` mutator call site traces to the main looper (FLP callback requested with `Looper.getMainLooper()` at HudStreamingService.kt:771, in-process binder calls from the Activity main thread, ticker/watchdog handlers on the main looper); `lastFixElapsedRealtimeMs` is the only `@Volatile` cross-published field. Confirmed per focus item 6: the `sport_state` debug log (HudStreamingService.kt:605) carries `et/mt/d/cs/ap/st/sp` only — no coordinates; the recording notification shows elapsed/distance only (HudStreamingService.kt:805-818); session JSON stays in `filesDir/activities` — but see WR-06 for the Auto Backup gap that decision opens.

No Critical findings. Seven Warnings: a teardown write race that can corrupt (and therefore quarantine-and-lose) the crash checkpoint, a watchdog-interval vs. resume-window mismatch that defeats the L2 recovery it exists for, an Activity leak through the new 1 Hz metrics listener, a manifest exact-alarm permission pair with Play-policy and dead-code consequences, an unfounded `flushLocations` ordering assumption at stop, cloud backup of the new location-history files, and an incomplete `startForeground` exception guard.

## Narrative Findings (AI reviewer)

## Warnings

### WR-01: Concurrent writers on the same `.tmp` path can corrupt the crash checkpoint, causing quarantine and loss of the session resume

**File:** `phone/src/main/java/com/rokid/hud/phone/SessionStore.kt:146-160`, `phone/src/main/java/com/rokid/hud/phone/HudStreamingService.kt:264-271`
**Issue:** `writeAtomic` always uses the fixed temp path `File(dir, target.name + ".tmp")` (SessionStore.kt:147). The sync variants bypass the single-thread executor: `HudStreamingService.onDestroy` calls `writeCheckpointSync` on the main thread (HudStreamingService.kt:266) while a `writeCheckpointAsync` queued by the same-tick `pollCheckpoint` (HudStreamingService.kt:141-147) may still be serializing/writing the same session's checkpoint on the executor thread. Both threads then open `FileOutputStream` on the identical `{id}.checkpoint.json.tmp`, truncating and interleaving each other's bytes before racing two `renameTo` calls. The corrupted checkpoint is later quarantined as `.corrupt` by `recoverOrphans` (SessionStore.kt:244-252) — never resumed, never finalized — so the exact teardown the L3 checkpoint exists to survive can silently discard the ride. Related: on rename failure the `.tmp` file is only deleted in the exception branch (SessionStore.kt:156-159), so a failed rename strands a stale `.tmp`; and `shutdown()` (SessionStore.kt:294-296) never awaits termination, so a prior service instance's executor can still be writing while a new instance's `recoverOrphans` scans the same directory in a process that survived the restart.
**Fix:** Make concurrent writers collision-free with a unique temp name, and route the teardown checkpoint through the executor with a short bounded await:
```kotlin
// SessionStore.writeAtomic
val tmp = File.createTempFile(target.name + ".", ".tmp", dir)

// SessionStore
fun shutdownAndAwait(timeoutMs: Long) {
    executor.shutdown()
    try { executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
}

// HudStreamingService.onDestroy — replace writeCheckpointSync + shutdown()
activitySessionManager?.snapshotSession()?.let { sessionStore?.writeCheckpointAsync(it) }
...
sessionStore?.shutdownAndAwait(2_000L)
```
(Unique temp names alone remove the corruption: each writer renames an intact file; last atomic rename wins.)

### WR-02: Watchdog L2 alarm interval (15 min) exceeds the 10-min resume window — process-death recovery frequently finalizes instead of resuming, and leaves `rec_active` stale

**File:** `phone/src/main/java/com/rokid/hud/phone/RecordingWatchdog.kt:53`, `phone/src/main/java/com/rokid/hud/phone/SessionStore.kt:45`, `phone/src/main/java/com/rokid/hud/phone/HudStreamingService.kt:334-346`
**Issue:** `WATCHDOG_INTERVAL_MS = 15 * 60_000L` but `MAX_RESUME_AGE_MS = 10 * 60_000L`. When the OS hard-kills a TRACKING process (the ColorOS scenario L2 explicitly targets), the pending alarm fires at `S + 15 min` where `S` is its schedule time. The last checkpoint's mtime is ≈ the kill time `K` (±60 s checkpoint cadence). Checkpoint age at alarm fire = `(S + 15 min) − K + [0..60 s]`, which is < 10 min only when `K > S + 5 min`: any kill in the first ~5 minutes of every 15-minute alarm window (≈ one third of kills, uniformly) yields a checkpoint older than the resume window, so `recoverOrphans` finalizes the ride as interrupted rather than resuming it — the watchdog restarts the service but recording does not continue, defeating its stated purpose. In that path nothing clears `PREF_REC_ACTIVE` (only the resume branch at HudStreamingService.kt:341 rewrites prefs; `clearRecordingPrefs` runs only in `stopRecording`/`onDestroy`-while-TRACKING), leaving the flag stale until the next recording. Additionally, the documented "degrades gracefully" inexact fallback (RecordingWatchdog.kt:113-114) does not work end-to-end on API 31-32 with exact alarms revoked: an inexact alarm is not on the background-FGS-start exemption list, so `ContextCompat.startForegroundService` in `WatchdogReceiver.onReceive` (RecordingWatchdog.kt:157-161) throws, is swallowed by the catch, and the chain dies after one fire with no reschedule.
**Fix:** Make the resume window strictly larger than the worst-case detection latency, e.g. `MAX_RESUME_AGE_MS = WATCHDOG_INTERVAL_MS + 2 * 60_000L` (17 min), or drop `WATCHDOG_INTERVAL_MS` to 10 min (still above the 9-min Doze throttle) and set `MAX_RESUME_AGE_MS` to 12 min. In `initRecording`, clear the prefs when recovery does not resume:
```kotlin
if (recovery.resumable == null) clearRecordingPrefs()
```
Document (or fix via `setAlarmClock`) the API 31-32 inexact-fallback dead end.

### WR-03: MainActivity leaks into the long-lived service — `setMetricsListener`/`uiCallback` never cleared on destroy, with 1 Hz callbacks into the dead Activity

**File:** `phone/src/main/java/com/rokid/hud/phone/MainActivity.kt:527-536`, `phone/src/main/java/com/rokid/hud/phone/MainActivity.kt:164-165`, `phone/src/main/java/com/rokid/hud/phone/HudStreamingService.kt:140`
**Issue:** `onServiceConnected` registers `service?.setMetricsListener(recMetricsListener)` (MainActivity.kt:165); `recMetricsListener` is an anonymous inner class holding a strong reference to the Activity. `onDestroy` (MainActivity.kt:527-536) unbinds but never nulls the listener (nor `uiCallback`). When the user backs out of the Activity mid-recording, the foreground service retains the destroyed Activity (its entire view tree) for the remainder of the ride, and the sport-state ticker invokes `metricsListener?.onMetrics(snap)` (HudStreamingService.kt:140) every second — `runOnUiThread` on a destroyed Activity still executes, updating detached views at 1 Hz for potentially hours. The `uiCallback` half of this pattern is pre-existing; the 1 Hz metrics listener is new in this phase and makes the leak continuous rather than event-driven.
**Fix:** In `MainActivity.onDestroy`, before unbinding:
```kotlin
service?.setMetricsListener(null)
service?.uiCallback = null
```

### WR-04: Manifest declares both SCHEDULE_EXACT_ALARM and USE_EXACT_ALARM — Play-policy exposure and dead-coded permission gating on API 33+

**File:** `phone/src/main/AndroidManifest.xml:24-25`, `phone/src/main/java/com/rokid/hud/phone/RecordingWatchdog.kt:107-115`
**Issue:** `USE_EXACT_ALARM` is install-time-granted, non-revocable, and Google Play policy restricts it to apps whose core function is an alarm clock or calendar — a fitness recorder does not qualify, so shipping this manifest to Play risks rejection. Declaring it also makes `canScheduleExactAlarms()` return true unconditionally on API 33+, which dead-codes the carefully built denial fallback in `scheduleNextAlarm` (RecordingWatchdog.kt:113-114) that the KDoc presents as the graceful-degradation story. The two declarations contradict each other's design intent.
**Fix:** Remove `USE_EXACT_ALARM` and keep only:
```xml
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
```
(If unconditional exactness were truly required, the correct pattern is `SCHEDULE_EXACT_ALARM` with `android:maxSdkVersion="32"` plus `USE_EXACT_ALARM` — but the app does not meet the Play policy bar for the latter.)

### WR-05: `stopRecording` drain ordering is not guaranteed — `flushLocations()` is async and its deliveries can land after the finalization block

**File:** `phone/src/main/java/com/rokid/hud/phone/HudStreamingService.kt:397-403`
**Issue:** The KDoc asserts "flushLocations posts them onto the main-looper queue, then posts the finalization block behind them." `FusedLocationProviderClient.flushLocations()` is asynchronous IPC into Google Play services: the flushed fixes are delivered whenever GMS responds, with completion signaled only via the returned `Task` — there is no guarantee they are enqueued on the main looper before the `mainHandler.post` at line 403. Fixes delivered after the block runs hit `onFix` in FINISHED state and are dropped from the saved session. Today the request uses no `setMaxUpdateDelayMillis`, so batching is minimal and the loss is bounded to the final fix or two — but the mechanism provides none of the guarantee the comment claims, and will silently under-record if batching is ever enabled.
**Fix:** Chain the finalization on the Task:
```kotlin
val finalize = Runnable { /* existing post body */ }
val flc = fusedLocationClient
if (flc != null) {
    flc.flushLocations().addOnCompleteListener { mainHandler.post(finalize) }
} else {
    mainHandler.post(finalize)
}
```

### WR-06: `allowBackup="true"` now ships recorded GPS track history to cloud backups — no backup exclusion for `filesDir/activities`

**File:** `phone/src/main/AndroidManifest.xml:29`, `phone/src/main/java/com/rokid/hud/phone/SessionStore.kt:38`, `phone/src/main/java/com/rokid/hud/phone/HudStreamingService.kt:326`
**Issue:** The phase deliberately confines session JSON (full lat/lng/ts track points — a movement diary of the user's rides and runs) to `File(filesDir, "activities")`, citing threat T-03-01. But the pre-existing `android:allowBackup="true"` with no `android:fullBackupContent`/`android:dataExtractionRules` means Android Auto Backup includes `filesDir` by default and uploads it to the user's Google account. Before this phase, `filesDir` held nothing location-sensitive; this phase creates a persistent location-history corpus there, so the attribute now silently copies precise GPS tracks off-device — at odds with the project's "no cloud dependencies" posture and the file-confinement rationale written into SessionStore's KDoc.
**Fix:** Add a backup rules file excluding the activities directory:
```xml
<application ... android:dataExtractionRules="@xml/data_extraction_rules"
             android:fullBackupContent="@xml/backup_rules">
```
```xml
<!-- res/xml/backup_rules.xml -->
<full-backup-content>
    <exclude domain="file" path="activities/" />
</full-backup-content>
```
(and the equivalent `<data-extraction-rules>` for API 31+).

### WR-07: `startForeground` guard catches only SecurityException — `ForegroundServiceStartNotAllowedException` escapes, contradicting the stated no-crash-loop contract

**File:** `phone/src/main/java/com/rokid/hud/phone/HudStreamingService.kt:168-188`
**Issue:** The try/catch around `startForeground` (HudStreamingService.kt:180-187) handles the Android 14 location-type SecurityException, but API 31+ also throws `ForegroundServiceStartNotAllowedException` — a subclass of `IllegalStateException`, not `SecurityException` — from `startForeground` when the app is in a state where FGS promotion is disallowed (OEM-restricted START_STICKY restarts and expired background-start exemptions are the field-reported paths). An uncaught throw here crashes the process in `onStartCommand`; with START_STICKY the system retries, producing exactly the crash-loop the comment says this block exists to prevent, and interfering with the orphan-recovery-on-next-launch fallback the comment relies on.
**Fix:** Broaden the guard while keeping the same recovery semantics:
```kotlin
} catch (e: Exception) { // SecurityException + ForegroundServiceStartNotAllowedException (ISE)
    Log.e(TAG, "startForeground blocked", e)
    stopSelf()
    return START_NOT_STICKY
}
```

## Info

### IN-01: `PREF_REC_SESSION_ID` is written and removed but never read

**File:** `phone/src/main/java/com/rokid/hud/phone/HudStreamingService.kt:63,448,455`
**Issue:** The comment at line 444 says "plan 01-06 consumes" this key, but `WatchdogReceiver` reads only `rec_active` (RecordingWatchdog.kt:148-150); no code reads `rec_session_id`. Dead data with a stale ownership comment.
**Fix:** Remove the key (and comment) or note the actual future consumer; keep only `PREF_REC_ACTIVE` until something reads the id.

### IN-02: Glasses SportState no-op branch still triggers `onStateUpdate(currentState)` — redundant 1 Hz HUD invalidations while recording

**File:** `glasses/src/main/java/com/rokid/hud/glasses/BluetoothClient.kt:258,263`
**Issue:** The new `is ParsedMessage.SportState -> { /* dropped in Phase 1 */ }` branch falls through to the unconditional `onStateUpdate(currentState)` at line 263, so every sport_state message (1 Hz for the whole recording) re-delivers an unchanged `HudState` and schedules a full `HudView` redraw. `postInvalidate` coalesces per frame, but the extra churn is avoidable and free to eliminate.
**Fix:** `is ParsedMessage.SportState -> return` (early return before the shared `onStateUpdate` call), matching the message's Phase-1 dropped semantics.

### IN-03: MockRouteFeeder start/stop race — an exiting feeder's teardown can clobber a newly started one (debug-only)

**File:** `phone/src/debug/java/com/rokid/hud/phone/MockRouteFeeder.kt:46-56,90-98`
**Issue:** `stop` sets `running = false`; a `start` arriving within the old thread's ≤1 s sleep window passes the `if (running)` guard, sets `running = true`, and spawns a new thread — then the old thread's `finally` executes `client.setMockMode(false)` and `running = false`, disabling mock mode under the new feeder and halting its loop. `feederThread` is also assigned but never used. Debug-only tooling driven by adb, so impact is confined to test sessions, but the failure mode (feeder silently dead after a quick stop/start) will cost debugging time.
**Fix:** Join the previous thread before starting a new one (`feederThread?.join(1500)`), or use a per-thread generation token so a stale `finally` cannot flip shared state; drop `feederThread` if unused.

### IN-04: Checkpoint-content `id` is used as a filename component — derive from the filename or validate (defense-in-depth)

**File:** `phone/src/main/java/com/rokid/hud/phone/SessionStore.kt:162-164,272-275`, `phone/src/main/java/com/rokid/hud/phone/HudStreamingService.kt:334-346`
**Issue:** Orphan recovery trusts `id` from the checkpoint's JSON body: a resumed session later writes `File(dir, data.id + CHECKPOINT_SUFFIX)`, and `finalizeInterrupted` writes `File(dir, data.id + FINAL_SUFFIX)`. An `id` containing `../` would escape the activities directory. Exploitation requires an attacker who can already write app-internal files (i.e., prior compromise), so this is hardening, not a live vulnerability — but the id is trivially validatable against the format the app itself generates.
**Fix:** In `fromJson`, reject ids failing `Regex("^[0-9]{8}-[0-9]{6}-[0-9a-f]{8}$")` (return null → corrupt-quarantine path), or key checkpoint/final filenames off the source filename during recovery instead of the JSON body.

## Fix Log

All 7 Warning findings FIXED (2026-07-03); all 4 Info findings deferred by fix scope.
Verification: `testDebugUnitTest :phone:assembleDebug` green (exit 0) after the final fix.

| Finding | Status | Commit | Applied fix |
|---------|--------|--------|-------------|
| WR-01 | FIXED | 8650f8d | `SessionStore.writeAtomic` now uses a unique same-directory temp per write (`File.createTempFile(name + ".", ".tmp", dir)` — same-dir rename stays atomic; stray temp deleted on rename failure). `onDestroy` teardown checkpoint routed through the serial executor (`writeCheckpointAsync`) and drained by new `SessionStore.shutdownAndAwait(2_000L)` (shutdown + awaitTermination, logs on timeout). Sync variants kept for tests. |
| WR-02 | FIXED | 156bde6 | `WATCHDOG_INTERVAL_MS` 15 min → 5 min (nominal; Doze allow-while-idle throttling can stretch to ~9 min — still inside `SessionStore.MAX_RESUME_AGE_MS` 10 min, which is unchanged per locked context decision; kills that outlast the window finalize-as-interrupted). `initRecording` now clears stale `rec_active` prefs whenever recovery does not resume. |
| WR-03 | FIXED | 03a8972 | `MainActivity.onDestroy` clears `service?.setMetricsListener(null)` and `service?.uiCallback = null` before unbinding (existing btAudioRouter/wifiShareManager release order kept). |
| WR-04 | FIXED | 76a0c6c | Disposition inverted the review's suggested direction: removed `SCHEDULE_EXACT_ALARM`, kept `USE_EXACT_ALARM`. Runtime `canScheduleExactAlarms()` gate + `setAndAllowWhileIdle` fallback kept as-is — it covers API 31-32 where `USE_EXACT_ALARM` does not exist. |
| WR-05 | FIXED | 8cf62e1 | Stop-path finalization chained on the flush Task: `flushLocations().addOnCompleteListener { mainHandler.post(finalize) }` (main-thread listener re-posts behind any drained fixes already on the looper). Running-guard preserved inside the block; null-client / flush-throw paths post `finalize` directly. |
| WR-06 | FIXED | a92a913 | Added `res/xml/backup_rules.xml` (`full-backup-content` exclude `domain="file" path="activities/"`) and `res/xml/data_extraction_rules.xml` (`cloud-backup` + `device-transfer` exclude same path); wired `android:fullBackupContent` + `android:dataExtractionRules` on the application element. `allowBackup` untouched (out of scope). |
| WR-07 | FIXED | 94cf36f | `startForeground` guard broadened with a second `catch (e: IllegalStateException)` (covers `ForegroundServiceStartNotAllowedException`, an ISE subclass not referencable below API 31 — minSdk-28-safe form) with the same log + `stopSelf()` + `START_NOT_STICKY` handling. |
| IN-01 | DEFERRED | — | Dead `rec_session_id` pref key — out of fix scope. |
| IN-02 | DEFERRED | — | Glasses SportState redundant `onStateUpdate` churn — out of fix scope. |
| IN-03 | DEFERRED | — | MockRouteFeeder start/stop race (debug-only) — out of fix scope. |
| IN-04 | DEFERRED | — | Checkpoint-content `id` filename validation (defense-in-depth) — out of fix scope. |

---

_Reviewed: 2026-07-03T17:42:15Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
_Fixed: 2026-07-03 — 7/7 Warnings fixed, 4 Info deferred (gsd-code-fixer)_
