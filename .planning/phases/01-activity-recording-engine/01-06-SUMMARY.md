---
phase: 01-activity-recording-engine
plan: 06
subsystem: background-reliability
tags: [watchdog, alarmmanager, doze, staleness, exact-alarm, background-location, broadcast-receiver, rec-05]

# Dependency graph
requires:
  - phase: 01-activity-recording-engine (plan 01-04)
    provides: "rec_active/rec_session_id prefs, ActivitySessionManager.lastFixElapsedRealtimeMs @Volatile anchor, recording notification builder, startRecording/stopRecording/orphan-resume lifecycle, START_STICKY L3"
provides:
  - "RecordingWatchdog: L1 15s main-looper staleness self-chain (>30s GPS silence -> onStale warning) + L2 15-min AlarmManager exact allow-while-idle self-chain with canScheduleExactAlarms gate and inexact fallback"
  - "WatchdogReceiver: manifest-declared (exported=false), survives process death, no-ops unless rec_active, restarts the FGS with ACTION_WATCHDOG_CHECK, never reschedules (service owns the chain)"
  - "Service-side L2 recovery: ACTION_WATCHDOG_CHECK handling in onStartCommand -> FLP remove+re-request when >30s stale during TRACKING, alarm rescheduled while recording is active"
  - "Manifest: ACCESS_BACKGROUND_LOCATION + SCHEDULE_EXACT_ALARM + USE_EXACT_ALARM declared (runtime bg-location request ships in plan 01-05)"
  - "Staleness warning surface: recording notification text swaps to 'Recording — GPS signal lost (Ns)' via override parameter; ticker refresh restores normal text"
affects: [01-05, 01-07, phase-2-glasses-hud]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "AlarmManager exact self-chain with permission gate: API>=31 && canScheduleExactAlarms -> setExactAndAllowWhileIdle; API<31 -> unconditional; else setAndAllowWhileIdle + Log.w degradation; whole body try/catch(SecurityException) because revocation races the gate"
    - "Single-owner alarm chain: receiver triggers, service reschedules — identical PendingIntent (REQ code + FLAG_UPDATE_CURRENT|FLAG_IMMUTABLE) makes double-schedules replace instead of stack"
    - "Watchdog callbacks as constructor lambdas consulting the nullable engine safely — watchdog stays decoupled from the service's field lifecycle"

key-files:
  created:
    - phone/src/main/java/com/rokid/hud/phone/RecordingWatchdog.kt
  modified:
    - phone/src/main/AndroidManifest.xml
    - phone/src/main/java/com/rokid/hud/phone/HudStreamingService.kt

key-decisions:
  - "Exact-alarm strategy: BOTH SCHEDULE_EXACT_ALARM and USE_EXACT_ALARM declared (sideloaded app, Play policy moot) — USE_EXACT_ALARM auto-grants on API 33+; API 31-32 may deny -> graceful setAndAllowWhileIdle degradation, no settings-prompt UX in v1"
  - "Watchdog action handled AFTER the !running init block in onStartCommand (plan-delegated ordering): dead-process deliveries run orphan recovery first so the staleness probe sees the resumed TRACKING state; live-service deliveries skip init and handle immediately — one placement covers both"
  - "Recovery scope kept to simple FLP re-init (removeLocationUpdates + requestLocationUpdates); no priority-toggling escalation until the OPPO device pass shows silent-FLP incidents"
  - "onStale stamps the shared notification throttle (notifyRecording) so the GPS-lost text holds for the full >=10s window before a ticker refresh can restore normal text; 15s L1 cadence re-asserts while silence persists"
  - "onDestroy stops the watchdog (graceful teardown stands it down); a hard kill skips onDestroy so the OS-side alarm survives with rec_active=true — exactly the state L2 recovery is built for"

patterns-established:
  - "Manifest receiver pair in one file: RecordingWatchdog (scheduler/timer) + WatchdogReceiver (alarm target) sharing the RecWatchdog log tag; receiver reads prefs by literal key since service constants are private"

requirements-completed: [REC-05]

# Metrics
duration: 11min
completed: 2026-07-03
---

# Phase 01 Plan 06: Recording Watchdog Summary

**REC-05 defensive layers L1/L2 are live: a 15s in-process staleness chain surfaces >30s GPS silence as a log + notification warning, and a Doze-surviving 15-minute exact-alarm self-chain (permission-gated, inexact fallback) restarts the service or re-initializes FLP when a recording goes silent — with ACCESS_BACKGROUND_LOCATION and both exact-alarm permissions now declared**

## Performance

- **Duration:** ~11 min
- **Started:** 2026-07-03T16:25:42Z
- **Completed:** 2026-07-03T16:36:00Z
- **Tasks:** 2/2
- **Files:** 1 created, 2 modified

## Accomplishments

- A recording the OS silently strangles now announces itself (staleness warning in logcat + notification text) and heals (FLP re-init when the process lives, `startForegroundService` restart + orphan resume when it died) instead of losing the ride
- The alarm chain self-terminates with the recording at every exit: `stop()` cancels on stopRecording/onDestroy, the receiver no-ops when `rec_active` is false, and the service only reschedules while TRACKING — no idle alarms
- Exact-alarm scheduling can never throw an unhandled SecurityException: `canScheduleExactAlarms()` gate on API 31+, unconditional exact pre-31, inexact `setAndAllowWhileIdle` fallback with a `Log.w`, and the whole body try/caught because revocation can race the gate (RESEARCH Pitfall 2)
- Staleness math uses `SystemClock.elapsedRealtime()` against ASM's `@Volatile` monotonic anchor only — no wall-clock timestamps anywhere in the watchdog (RESEARCH Pitfall 6; `grep getTime()` returns nothing)

## Task Commits

1. **Task 1: RecordingWatchdog — L1 staleness timer + L2 exact-alarm chain + receiver** — `9059720`
2. **Task 2: Manifest permissions + receiver declaration + service wiring** — `9414bbb`

## Exact-Alarm Strategy (as chosen)

Both `SCHEDULE_EXACT_ALARM` and `USE_EXACT_ALARM` are declared. This app is sideloaded, so the Play-policy restriction on `USE_EXACT_ALARM` is moot: on API 33+ it auto-grants (non-revocable), making `canScheduleExactAlarms()` true. On API 31-32 `SCHEDULE_EXACT_ALARM` may be denied — the watchdog degrades to `setAndAllowWhileIdle` (inexact but still fires in Doze) with a `Log.w("Exact alarms denied — watchdog degraded to inexact")`. Detection latency grows; nothing breaks. No settings-prompt UX in v1 per the plan's discretion-area decision.

## Receiver Flow

```
AlarmManager (15 min, ELAPSED_REALTIME_WAKEUP, allow-while-idle)
  └─> WatchdogReceiver.onReceive          [manifest-declared, exported=false]
        ├─ action != ACTION_WATCHDOG_CHECK        -> return (spoof guard, T-06-01)
        ├─ rokid_hud_prefs rec_active == false     -> return, NO reschedule (chain dies)
        └─ ContextCompat.startForegroundService(HudStreamingService, ACTION_WATCHDOG_CHECK)
              [try/catch: a SecurityException here is the Pitfall-1 bg-location case;
               L3 orphan recovery covers the session on the next app open]
  └─> HudStreamingService.onStartCommand
        ├─ startForeground (recording form when TRACKING)
        ├─ !running -> full init incl. orphan resume (dead-process path)
        └─ handleWatchdogCheck():
             TRACKING?  no  -> return (no reschedule — chain dies with the recording)
             age > 30s? yes -> removeLocationUpdates + startLocationUpdates + Log.w
             always while TRACKING -> recordingWatchdog.scheduleNextAlarm()
```

The receiver never reschedules — the service is the single owner of the chain. Double-scheduling on the dead-process path (orphan resume calls `start()`, then `handleWatchdogCheck` reschedules) is harmless: both use the identical PendingIntent (REQ 1001, `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`), so the second set replaces the first.

## Service Lifecycle Hooks (where the watchdog lives)

| Hook | Call | Location |
|------|------|----------|
| Recording starts | `recordingWatchdog.start()` | `startRecording` success block, after `startSportStateTicker()` |
| Orphan resume | `recordingWatchdog.start()` | `initRecording` resume path, after `startSportStateTicker()` |
| Recording stops | `recordingWatchdog.stop()` | `stopRecording` posted finalization block, after `stopSportStateTicker()` |
| Service teardown | `recordingWatchdog.stop()` | `onDestroy`, after `stopSportStateTicker()` and before `sessionStore.shutdown()` |
| Alarm delivery | `handleWatchdogCheck()` | `onStartCommand`, after the `!running` init block |
| Staleness warning | `onRecordingStale(ageMs)` | watchdog L1 `onStale` lambda -> `notifyRecording(snap, "Recording — GPS signal lost (Ns)")` |

rec_active prefs coverage confirmed (plan step 7): set in `startRecording` + orphan resume, cleared in `stopRecording`'s posted block + `onDestroy`'s TRACKING branch — the receiver's no-op guard is sound; a hard kill skips onDestroy leaving `rec_active=true` for recovery.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] startForeground would blank the live recording notification on every watchdog delivery**
- **Found during:** Task 2 (onStartCommand wiring)
- **Issue:** `onStartCommand` unconditionally called `startForeground(NOTIFICATION_ID, buildNotification())` — every watchdog `startForegroundService` delivery to a live recording service (every 15 min) would have swapped "Recording — {distance}" to the static "Streaming to glasses" for up to 10s until the next ticker refresh
- **Fix:** Notification chosen by state: `buildRecordingNotification(snap)` when TRACKING, static `buildNotification()` otherwise
- **Files modified:** phone/src/main/java/com/rokid/hud/phone/HudStreamingService.kt
- **Commit:** `9414bbb`

### Plan-vs-reality adaptations

**2. [Adaptation] recordingWatchdog is a non-null `val` field initializer, not constructed inside the !running init block**
- The acceptance grep requires the literal `recordingWatchdog.stop()` (>=2) — a nullable field's `recordingWatchdog?.stop()` fails that grep. The constructor only touches the main looper (Context is used lazily by start/schedule/stop), and the lambdas consult `activitySessionManager` null-safely, so field-init order is irrelevant. Same construction inputs as planned; call sites read plainly.

**3. [Plan-delegated ordering] Watchdog action handled AFTER the !running init block**
- The plan first said "BEFORE the !running check" then explicitly delegated: "action handling may need to run after init; place it accordingly and document." Placed after: dead-process deliveries need orphan recovery to run first so `handleWatchdogCheck` sees the resumed TRACKING state (before init, `activitySessionManager` is null and the check would no-op, leaving recovery solely to the resume path's `start()`); live-service deliveries skip the init block so placement is equivalent for them.

**4. [Adaptation] WatchdogReceiver carries its own private companion constants**
- `TAG = "RecWatchdog"` (same tag as RecordingWatchdog for one logcat surface) plus literal `rokid_hud_prefs`/`rec_active` keys as private consts — the service's `PREFS_HUD`/`PREF_REC_ACTIVE` are private per repo convention, and the plan itself specified the literal strings.

### Environmental note

The plan's verify commands referenced the main repo path; verification ran from this executor's worktree root per parallel-isolation rules — same Gradle tasks, same gates.

## Verification Results

- Task 1 gate: `:phone:compileDebugKotlin` — exit 0
- Task 2 gate: `assembleDebug :shared:testDebugUnitTest :phone:testDebugUnitTest` — exit 0 (manifest merge clean, both APKs build, full suite green)
- All Task 1 greps pass: `setExactAndAllowWhileIdle`, `setAndAllowWhileIdle`, `canScheduleExactAlarms`, `FLAG_IMMUTABLE`, `STALENESS_THRESHOLD_MS = 30_000L`, `WATCHDOG_INTERVAL_MS = 15 * 60_000L`, `class WatchdogReceiver : BroadcastReceiver` present; `getTime()` absent; 166 lines (min 80)
- All Task 2 greps pass: three new permissions in manifest; `android:name=".WatchdogReceiver"` with `android:exported="false"`; `RecordingWatchdog(` and `ACTION_WATCHDOG_CHECK` in service; `recordingWatchdog.stop()` count = 2
- On-device watchdog behavior (alarm firing in Doze, receiver restart, ColorOS kill recovery) is plan 01-07's device pass — unmockable in JVM tests per RESEARCH

## Threat Model Dispositions (as implemented)

- T-06-01 (mitigate): receiver `exported="false"`, action-string check first, `rec_active` no-op guard
- T-06-02 (mitigate): broadcast PendingIntent built with `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`
- T-06-03 (mitigate): background location used only by OS-initiated recovery paths (receiver -> FGS restart); alarms scheduled only while recording; chain self-terminates via `rec_active` and the TRACKING-only reschedule
- T-06-04 (mitigate): `canScheduleExactAlarms()` gate + inexact fallback + try/catch(SecurityException); USE_EXACT_ALARM auto-grant preferred on 33+; L3 orphan recovery absorbs a revocation force-stop
- T-06-05 (accept + detect): ColorOS kills cannot be prevented dev-side — L1/L2 here + L3 (01-04) detect and recover; user-side checklist lands in plan 01-07

## Known Stubs

None — no placeholders, no TODO/FIXME, no hardcoded empty values. All REC-05 code-level layers are complete: WakeLock (existing) + FGS notification (01-04) + staleness warning (here) + alarm-chain recovery (here) + START_STICKY orphan resume (01-04); the battery-exemption prompt and bg-location runtime request are plan 01-05's UI work by design.

## Next Phase Readiness

- Plan 01-05 (UI): nothing here blocks it; `ACCESS_BACKGROUND_LOCATION` is now declared so its runtime request ("Allow all the time" settings route) can ship
- Plan 01-07 (device pass): Success Criterion 3 preconditions in place for the 30-minute OPPO gate — verify exact-alarm grant state (`adb shell dumpsys alarm | grep rokid`), watchdog firing in Doze, and receiver-driven restart after `adb shell am kill com.rokid.hud.phone`

## Self-Check: PASSED

- Created file exists: phone/src/main/java/com/rokid/hud/phone/RecordingWatchdog.kt (166 lines)
- Both task commits present in git log: 9059720, 9414bbb
- Full suite + assembleDebug green at Task 2 gate; no unexpected deletions; no untracked files left behind
