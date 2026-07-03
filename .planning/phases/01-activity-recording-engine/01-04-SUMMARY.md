---
phase: 01-activity-recording-engine
plan: 04
subsystem: service-integration
tags: [foreground-service, location-fanout, bluetooth-broadcast, notification, checkpoint, crash-recovery, android-14]

# Dependency graph
requires:
  - phase: 01-activity-recording-engine (plan 01-01)
    provides: "SportStateMessage + ProtocolCodec.encodeSportState + SessionModels contracts"
  - phase: 01-activity-recording-engine (plan 01-02)
    provides: "ActivitySessionManager state machine (startSession/stopSession/resumeFrom/recordLocation/currentSnapshot/snapshotSession/pollCheckpoint/reset)"
  - phase: 01-activity-recording-engine (plan 01-03)
    provides: "SessionStore atomic persistence (writeCheckpointSync/Async, finalizeAsync, recoverOrphans, shutdown)"
provides:
  - "LocationConsumer fan-out: every FLP fix (oldest→newest, not lastLocation) dispatched to registered consumers; NavigationManager untouched"
  - "Recording binder API on HudStreamingService: startRecording/stopRecording/recordingState/currentMetrics/setMetricsListener (plan 01-05 consumes)"
  - "~1Hz sport_state ticker: BT broadcast + Log.d logcat surface + MetricsListener + pollCheckpoint from ONE snapshot per tick (REC-07 runtime half)"
  - "Checkpoint wiring: 60s/500pt trigger → writeCheckpointAsync; stop → finalizeAsync; onDestroy → sync crash checkpoint (REC-06 wiring)"
  - "Orphan recovery at service start: <10min checkpoint resumes mid-recording, older finalize as interrupted"
  - "Live FGS notification: 'Recording — {distance}' + system chronometer elapsed; static form restored on stop (REC-05 notification layer)"
  - "Android 14 FGS hardening: startForeground SecurityException → log + stopSelf + START_NOT_STICKY (no crash-loop)"
  - "rec_active/rec_session_id prefs in rokid_hud_prefs for the plan 01-06 watchdog"
affects: [01-05, 01-06, 01-07, phase-2-glasses-hud]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "LocationConsumer CopyOnWriteArrayList fan-out appended to the END of onLocationUpdate (StateMessage/navigation semantics unchanged)"
    - "Main-looper self-chaining ticker Runnable that re-posts ONLY while TRACKING (leak-safe; removeCallbacks is belt-and-braces)"
    - "flushLocations + mainHandler.post ordering: drained fixes are processed before stopSession sees them"
    - "System chronometer (setUsesChronometer + setWhen(now - elapsedMs)) for zero-notify elapsed ticking; ~10s throttled text updates"

key-files:
  created: []
  modified:
    - phone/src/main/java/com/rokid/hud/phone/HudStreamingService.kt

key-decisions:
  - "Ticker re-posts inside the TRACKING branch only — if state leaves TRACKING without removeCallbacks the chain dies safely instead of ticking forever"
  - "First tick is postDelayed(1s), not immediate: startRecording already broadcasts the immediate st=tracking snapshot, avoiding a double broadcast at t=0"
  - "notifyRecording stamps lastNotifUpdateMs on every call, so the start-swap and ticker throttle share one monotonic clock (SystemClock.elapsedRealtime)"
  - "rec_active cleared in onDestroy only after the TRACKING crash-checkpoint: graceful teardown stands the watchdog down; a hard kill skips onDestroy so the flag survives for 01-06 recovery"
  - "stopRecording's posted block guards on running: if the service was destroyed first, the onDestroy checkpoint already preserved the session and finalizeAsync would hit a shut-down executor"
  - "rec_session_id sourced from asm.snapshotSession()?.id at start (track buffer is empty then — the defensive copy is free)"

patterns-established:
  - "Recording engine init as initRecording() mirroring initNavigation(): construct ASM + SessionStore(File(filesDir, \"activities\")), register permanent consumer, recoverOrphans BEFORE startLocationUpdates"
  - "broadcastSportState(snap): encode → broadcast → Log.d(TAG, \"sport_state $json\") — one helper is the entire sport_state emission + logcat verification surface"

requirements-completed: [REC-01, REC-06, REC-07]

# Metrics
duration: 13min
completed: 2026-07-03
---

# Phase 01 Plan 04: Service Wiring Summary

**GPS→metrics→BT/disk is now a running pipeline inside the single existing FGS: LocationConsumer fan-out feeds ASM, a main-looper ticker broadcasts sport_state at ~1Hz with logcat proof, checkpoints persist on the 60s/500pt trigger, orphans resume on restart, and Android-14 background restarts can no longer crash-loop**

## Performance

- **Duration:** ~13 min
- **Started:** 2026-07-03T16:08:32Z
- **Completed:** 2026-07-03T16:21:20Z
- **Tasks:** 2/2
- **Files modified:** 1 (HudStreamingService.kt only, per plan frontmatter; +295/−2 lines)

## Accomplishments

- Recording works with navigation stopped (free ride): the consumer is registered permanently and ASM ignores fixes outside TRACKING; zero navigationManager lines changed (verified via `git diff f6b19e2..HEAD | grep navigationManager` → empty)
- `onLocationResult` now iterates `result.locations` oldest→newest — no `result.lastLocation` remains anywhere in the file (Pitfall 5 closed)
- One `MetricsSnapshot` per tick feeds BT broadcast, UI listener, notification, and checkpoint trigger — saved data provably equals broadcast data (threat T-04-04 mitigated structurally)
- Full crash story: ticker checkpoints via `pollCheckpoint` → `writeCheckpointAsync`; onDestroy writes a synchronous best-effort checkpoint for a still-TRACKING session; `recoverOrphans()` at next start resumes (<10 min) or finalizes as interrupted; `SecurityException` on background `startForeground` logs + stops instead of crash-looping (T-04-03)

## Task Commits

1. **Task 1: LocationConsumer fan-out, recording lifecycle binder API, persistence wiring, FGS hardening** — `90ec900`
2. **Task 2: 1Hz sport_state ticker + live recording notification** — `869b193`

Note on task split: both tasks modify the same file. The ticker implementation, recording-notification builder, and their call sites (`startSportStateTicker`/`notifyRecording` in startRecording/orphan-resume, `stopSportStateTicker` in stop/onDestroy) landed in Task 2 per the plan's own "(Task 2)" annotation; Task 1 compiles and passes the full suite standalone.

## Binder API As Built (plan 01-05 consumes)

```kotlin
// All main-thread (local binder direct calls; ASM is main-thread-confined)
fun startRecording(sport: String): Boolean
    // false when service not running / already TRACKING; FINISHED auto-reset()s first.
    // On true: rec_active prefs set, one immediate sport_state (st=tracking) broadcast,
    // recording notification swapped in, 1Hz ticker started.
fun stopRecording()
    // Unit. flushLocations() FIRST, then posts the finalization block behind the
    // drained fixes: stopSession → final sport_state (st=finished) broadcast →
    // metricsListener.onMetrics(finished snap) → finalizeAsync → ticker stop →
    // static notification restore → rec_active cleared.
    // UI observes completion via the finished MetricsListener callback.
fun recordingState(): SessionState          // IDLE when engine not initialized
fun currentMetrics(): MetricsSnapshot?      // null while IDLE; frozen snapshot while FINISHED
fun setMetricsListener(l: MetricsListener?) // invoked from the ticker on the main thread
```

## Ticker Design

- `sportStateTicker`: object-expression Runnable on `mainHandler` (main looper). Each tick while TRACKING: `currentSnapshot()` → `broadcastSportState(snap)` (encode + broadcast + `Log.d(TAG, "sport_state $json")`) → `metricsListener?.onMetrics(snap)` → `pollCheckpoint()?.let { writeCheckpointAsync(it) }` → throttled `notifyRecording(snap)` at ≥10s → `postDelayed(this, SPORT_STATE_TICK_MS)`.
- Re-post happens INSIDE the TRACKING branch — the chain self-terminates on any state exit; `stopSportStateTicker()` (removeCallbacks) in the stop block and onDestroy is defense in depth.
- Constants: `SPORT_STATE_TICK_MS = 1000L`, `NOTIF_TEXT_UPDATE_MS = 10_000L` (companion, per convention).
- The ticker (not per-fix callbacks) defines cadence: during GPS gaps sport_state keeps flowing with frozen moving/distance and st stays "tracking" — the logcat surface plan 01-07 greps at ~1Hz.

## Notification Layer (REC-05 slice)

- `buildRecordingNotification(snap)`: same channel (`HudApplication.CHANNEL_ID`, IMPORTANCE_LOW), same small icon + FLAG_IMMUTABLE contentIntent as `buildNotification()`; title "Rokid HUD — Recording"; text `"Recording — {distance}"`; elapsed via `setUsesChronometer(true)` + `setWhen(now − snap.elapsedMs)` (system ticks it, zero notify() calls); `setOnlyAlertOnce(true)`, `setOngoing(true)`.
- **Cached-settings field for unit formatting: `cachedSettings` (type `SettingsMessage?`)** — `formatRecordingDistance` reads `cachedSettings?.useImperial == true` → `"%.2f mi"` at m/1609.344, else `"%.2f km"` at m/1000, try/catch → metric fallback with Log.w.
- Stop path restores the static "Streaming to glasses" notification AFTER the final st=finished broadcast (NOTIFICATION_ID=1 shared — Integration Warning 4 honored).

## Deviations from Plan

### Plan-vs-reality adaptations

**1. [Adaptation] cachedSettings is a typed field, not a JSON string**
- **Found during:** Task 2 (distance formatting)
- **Issue:** The plan said to decode "the service's cached settings JSON via ProtocolCodec.decode"; the actual field re-sent to new BT clients is `private var cachedSettings: SettingsMessage?` — already a decoded object (the plan's read_first anticipated this: "SettingsMessage/settings JSON")
- **Fix:** Read `cachedSettings?.useImperial` directly — no encode/decode round-trip; the mandated try/catch → metric fallback with Log.w retained
- **Commit:** `869b193`

**2. [Rule 2 - Missing guard] `if (!running) return@post` in stopRecording's posted block**
- **Found during:** Task 1
- **Issue:** If the service is destroyed between `stopRecording()` and its posted finalization block executing, `sessionStore.finalizeAsync` would call `executor.execute` on a shut-down executor → RejectedExecutionException on the main thread (crash). The onDestroy crash-checkpoint already preserves the session in that window
- **Fix:** Guard the posted block on `running`; also wrapped the `finalizeAsync` dispatch in try/catch per convention
- **Files modified:** phone/src/main/java/com/rokid/hud/phone/HudStreamingService.kt
- **Commit:** `90ec900`

**3. [Style] `?.let` instead of the plan's literal `!!` in the onDestroy checkpoint**
- `activitySessionManager?.snapshotSession()?.let { sessionStore?.writeCheckpointSync(it) }` — repo convention avoids `!!`; behavior identical (state==TRACKING guarantees non-null)

**4. [Interpretation] "after finalize-in-onDestroy" for clearing rec_active**
- onDestroy performs a checkpoint (not a finalize) for a TRACKING session; the prefs clear was placed after that checkpoint write. Semantics: graceful teardown stands the 01-06 watchdog down; a hard kill skips onDestroy entirely, so rec_active survives for watchdog-driven recovery

### Environmental note

The plan's verify commands referenced the main repo path (`cd /Users/bilhuang/Documents/rokid-maps`); verification ran from this executor's worktree root instead per parallel-isolation rules — same Gradle tasks, same gates.

## Verification Results

- Task 1 gate: `:phone:compileDebugKotlin :shared:testDebugUnitTest :phone:testDebugUnitTest` — exit 0
- Task 2 gate: `assembleDebug :shared:testDebugUnitTest :phone:testDebugUnitTest` — exit 0 (both APKs build; glasses compiles the shared sport_state codec)
- All acceptance greps pass: `for (loc in result.locations)` present / `result.lastLocation` absent; `fun startRecording(sport: String): Boolean`, `fun stopRecording()`, `recoverOrphans`, `catch (e: SecurityException)`, `flushLocations`, `encodeSportState`, `Log.d(TAG, "sport_state`, `setUsesChronometer(true)`, `setOnlyAlertOnce(true)`, `SPORT_STATE_TICK_MS = 1000L`, `pollCheckpoint` all present
- Regression: `git diff f6b19e2..HEAD` shows zero modified navigationManager lines; tile/BT-server code untouched
- Manual smoke (logcat sport_state at ~1Hz on device) deferred to plan 01-07 as specified

## Threat Model Dispositions (as implemented)

- T-04-01 (accept): sport_state carries metrics only — no coordinates in message or log line
- T-04-02 (mitigate): notification shows distance + elapsed only, existing IMPORTANCE_LOW channel
- T-04-03 (mitigate): try/catch(SecurityException) → Log.e + stopSelf + START_NOT_STICKY
- T-04-04 (mitigate): single snapshot per tick feeds BT + UI + notification + checkpoint
- T-04-05 (mitigate): recording notification contentIntent uses FLAG_IMMUTABLE (same as existing)

## Known Stubs

None — no placeholders, no TODO/FIXME, no hardcoded empty values flowing to consumers. The MVP slice is complete: starting a session via the binder records, persists, and streams sport_state to connected glasses. On-phone buttons are plan 01-05 by design.

## Next Phase Readiness

- Plan 01-05 (UI): binder surface above is final; MainActivity should call `setMetricsListener` on bind/resume (navCallback re-attach pattern) and wrap callbacks in runOnUiThread
- Plan 01-06 (watchdog): `rec_active`/`rec_session_id` live in `rokid_hud_prefs`; `ActivitySessionManager.lastFixElapsedRealtimeMs` is the @Volatile staleness anchor; a hard kill leaves rec_active=true
- Plan 01-07 (on-device): grep `adb logcat -s HudStreaming:D` for `sport_state` lines at ~1Hz during a recording; REC-05 partially satisfied here (notification layer) — battery-exemption prompt (01-05) and watchdog (01-06) complete it

## Self-Check: PASSED

- Modified file exists and compiles: phone/src/main/java/com/rokid/hud/phone/HudStreamingService.kt
- Both task commits present in git log: 90ec900, 869b193
- Full suite + assembleDebug re-verified green at Task 2 gate
