---
phase: 01-activity-recording-engine
verified: 2026-07-03T18:45:00Z
status: human_needed
score: 7/7 must-haves verified
overrides_applied: 0
human_verification:
  - test: "Post-fix compact on-device re-verify (5-min mock cycle on the fixed APK): drive the MockRouteFeeder track on the OPPO, confirm distance holds flat across the stationary-drift segment (finding 2: raw hysteresis) and no teleport distance on provider switch (finding 3: seam gate). This also smokes the recording pipeline on the WR-01..07-fixed binary, since the 35-min gate ran on a pre-fix build."
    expected: "Distance flat (<2m drift) during stationary window; no implausible-jump distance; sport_state stream and checkpoint cadence unchanged"
    why_human: "Requires the unlocked phone (face/PIN — the one boundary adb automation cannot cross, per 01-07-SUMMARY Residual items). Fixes are regression-locked at unit level with device-measured inputs; risk is low but the on-device confirmation is the declared closure."
  - test: "Mock teardown (Pitfall 9): on the unlocked OPPO, clear Developer Options mock-location app selection (选择模拟位置信息应用 → 无)"
    expected: "No mock-location app selected; real GPS provider is authoritative for future recordings"
    why_human: "Device-settings UI on a locked phone; feeder process is already dead (force-stopped) but the selection persists until manually cleared"
  - test: "2-hour pre-release screen-off recording validation on the OPPO (STATE.md Pending Todos — pre-release gate, NOT a Phase 1 exit gate per SC#3 wording)"
    expected: "Continuous track for 2 hours with screen off, no fix gap >30s, session finalizes correctly"
    why_human: "Wall-clock device endurance run; cannot be verified programmatically or compressed"
---

# Phase 1: Activity Recording Engine — Verification Report

**Phase Goal:** Phone records GPS activity with live metrics and robust background operation
**Verified:** 2026-07-03T18:45:00Z
**Status:** human_needed (all 7 success criteria verified; 3 device-bound follow-ups need human hands)
**Re-verification:** No — initial verification

## Build/Test Gate (run by verifier, this session)

`testDebugUnitTest assembleDebug` → **exit 0**. Test counts from JUnit XML:

| Suite | Tests | Failures | Errors |
|---|---|---|---|
| shared: ProtocolCodecTest | 7 | 0 | 0 |
| phone: ActivitySessionManagerTest | 38 | 0 | 0 |
| phone: SessionStoreTest | 20 | 0 | 0 |
| **Total** | **65** | **0** | **0** |

Both APKs assembled: `phone/build/outputs/apk/debug/phone-debug.apk`, `glasses/build/outputs/apk/debug/glasses-debug.apk`. (Commit 1b52505's message says "61 tests green"; the actual current suite is 65 — the count grew, no discrepancy against the codebase.)

## Goal Achievement

### Observable Truths (ROADMAP Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | `sport_state` defined in shared module and broadcast at ~1Hz with elapsed/distance/speed/pace/moving-time/state; log-verifiable | ✓ VERIFIED | `Messages.kt:75-83` SportStateMessage; `ProtocolConstants.kt:45-52,67` fields + type; `ProtocolCodec.kt:130-140` encode, `:252-262` decode branch, `:19` ParsedMessage.SportState. Service: `HudStreamingService.kt:134-155` 1Hz self-chaining ticker (SPORT_STATE_TICK_MS=1000L), `:621-635` broadcastSportState → BT broadcast + `Log.d(TAG, "sport_state $json")`. Device record: 60 lines in a 60s logcat window (01-07-SUMMARY) |
| 2 | Distance excludes GPS drift: >20m accuracy rejected; 0.7/0.3 moving-state hysteresis; no phantom distance | ✓ VERIFIED | `ActivitySessionManager.kt:44-46` constants; `:249-255` raw-Doppler hysteresis, strict boundaries (enter >0.7, exit <0.3); `:266` gate `accuracyM > 0.0 && <= 20.0 && moving` (exactly 20.0 accepted); `:276-280` >50 m/s seam gate (device finding 3). Tests: accuracy boundary ×3, hysteresis boundary ×2, `stationaryDriftAccumulatesZeroDistance`, `stationaryJitterAfterStopAddsNoDistance` (6.67m-leak regression), `implausibleJumpIsSeamNotDistance`, `reacquisitionGapStillCounts`. REC-04 amendment (MA removed post-device-verification) is properly documented: REQUIREMENTS.md:34, STATE.md decisions (raw hysteresis + seam gate rows), commits 1b52505 + 701d0fb — ROADMAP SC text as written matches the amended semantics |
| 3 | Recording survives ≥30 min screen-off on OPPO Find X9 Ultra; 2-hour run tracked in STATE.md | ✓ VERIFIED | Device evidence record (01-07-SUMMARY, adb-verified): 35.1-min screen-off session, 2,095 track points, max inter-point gap 6.1s (bar ≤30s), pid unchanged, Doze-eligible via `dumpsys battery unplug`. Referenced commits all exist in git log. 2-hour run present in STATE.md Pending Todos ("Pre-release: 2-hour screen-off recording validation on the OPPO test phone") — the SC's tracking clause is satisfied. Caveat: the gate ran on a pre-fix build; the fixed-APK compact re-verify is human item 1 |
| 4 | Session persists as local JSON on stop, survives restart, 60s/500pt checkpoint | ✓ VERIFIED | `SessionStore.kt` atomic writes (`:153` unique temp per write [WR-01 fix], `:161` fsync, `:163` checked renameTo), `:244-282` orphan recovery with strict <10-min resume, corrupt quarantine; `ActivitySessionManager.kt:337-348` 60s/500pt arming; service wiring: ticker checkpoint `:141-147`, stop → finalizeAsync `:429` chained on flushLocations Task `:451` [WR-05 fix], onDestroy checkpoint through executor + `shutdownAndAwait(2000)` `:278,:298`, `recoverOrphans` at init `:346` → `resumeFrom` `:352`. Device record: kill -9 → START_STICKY restart ~3s, "Resumed interrupted session" logged, checkpoint mtime/size advanced every 60s, finalize renames + removes checkpoint. 20 SessionStoreTest tests green |
| 5 | `sport_state` carries `"v": 1`; versioning of existing set deferred | ✓ VERIFIED | `ProtocolCodec.kt:132` `put(FIELD_VERSION, 1)` hardcoded at encode; tests `versionFieldIsOne` + `exactJsonKeys` (asserts exactly 9 keys t,v,et,mt,d,cs,ap,st,sp — matches 01-07-SUMMARY's device-observed schema verbatim). No other message type carries `v` (deferred as scoped) |
| 6 | New components own mutable state with explicit thread-safety; NavigationManager race untouched, documented as Phase 4's | ✓ VERIFIED | ASM main-thread confinement (KDoc `:17-25`), `@Volatile lastFixElapsedRealtimeMs` the only cross-thread field (`:69-70`); FLP callback requested on main looper (`HudStreamingService.kt:800`); ticker/binder/watchdog-L1 all on mainHandler; `CopyOnWriteArrayList` for consumers (`:103`); SessionStore serial executor + bounded drain. NavigationManager: zero commits touch it in the phase range (git log empty); race fields unchanged (`NavigationManager.kt:32,35`); ownership documented in STATE.md decision "NavigationManager data-race fix owned by Phase 4" + ROADMAP Phase 4 scope (b) + `HudStreamingService.kt:45` KDoc |
| 7 | Unit tests exist and pass: codec round-trip + ASM state machine + metrics | ✓ VERIFIED | First tests in repo. 65 green (verifier-run). Round-trip: `sportStateRoundTrip`, `malformedSportStateDecodesToUnknown`, `missingFieldsDecodeWithDefaults`. State machine: IDLE→TRACKING→FINISHED covered (`startSessionMovesToTracking`, `stopSessionFinalizesAndFreezes`, `resetFromFinishedReturnsToIdle`, resume ×3). Metrics: distance (haversine tolerance, monotonic sweep), pace (`avgPaceFloorsAt100Meters`), elapsed (injected monotonic clock, never-decreases) + 3 device-input regression tests |

**Score:** 7/7 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `shared/.../protocol/Messages.kt` | SportStateMessage | ✓ VERIFIED | 7-field data class, lines 75-83 |
| `shared/.../protocol/ProtocolConstants.kt` | SPORT_STATE + 8 field constants | ✓ VERIFIED | lines 45-52, 67 |
| `shared/.../protocol/ProtocolCodec.kt` | encodeSportState + decode + sealed variant | ✓ VERIFIED | 269 lines; defensive optXxx decode inside existing try/catch → Unknown on malformed |
| `shared/.../ProtocolCodecTest.kt` | round-trip + behavior tests | ✓ VERIFIED | 102 lines, 7 tests green |
| `phone/.../SessionModels.kt` | SessionState/TrackPoint/MetricsSnapshot/MetricsListener/SessionData | ✓ VERIFIED | 99 lines, all 5 contracts present, pure Kotlin |
| `phone/.../ActivitySessionManager.kt` | state machine + filtering + metrics | ✓ VERIFIED | 436 lines; all exported entries present (startSession/stopSession/resumeFrom/recordLocation/onFix/currentSnapshot/snapshotSession/pollCheckpoint/reset) |
| `phone/.../ActivitySessionManagerTest.kt` | state machine + metric + regression coverage | ✓ VERIFIED | 584 lines, 38 tests green incl. 3 device-input regressions |
| `phone/.../SessionStore.kt` | atomic persistence + orphan recovery | ✓ VERIFIED | 317 lines; WR-01 fix in place (unique temp, shutdownAndAwait) |
| `phone/.../SessionStoreTest.kt` | persistence tests | ✓ VERIFIED | 361 lines, 20 tests green |
| `phone/.../HudStreamingService.kt` | fan-out + binder + ticker + notification + recovery | ✓ VERIFIED | 890 lines; full binder API (startRecording/stopRecording/recordingState/currentMetrics/setMetricsListener); WR-02/05/07 fixes in place |
| `phone/.../MainActivity.kt` | recording card + confirm-stop + onboarding | ✓ VERIFIED | 1160 lines; "Finish recording?" dialog, zero `discard` matches, WR-03 listener cleanup in onDestroy, aa3ecaa no-AUTO_CREATE rebind in onResume |
| `phone/res/layout/activity_main.xml` | RECORD card views | ✓ VERIFIED | All 11 planned ids present (btnStartRecording, recordingPanel, recBadge, metric texts, sport toggle, stop) |
| `phone/.../RecordingWatchdog.kt` | L1 staleness + L2 alarm chain + receiver | ✓ VERIFIED | 169 lines; 30s staleness, 5-min interval (WR-02 fix, inside 10-min resume window), canScheduleExactAlarms gate, FLAG_IMMUTABLE, WatchdogReceiver |
| `phone/src/main/AndroidManifest.xml` | permissions + receiver + backup rules | ✓ VERIFIED | ACCESS_BACKGROUND_LOCATION + USE_EXACT_ALARM (SCHEDULE_EXACT_ALARM removed per WR-04 disposition); WatchdogReceiver exported=false; fullBackupContent + dataExtractionRules wired |
| `phone/res/xml/backup_rules.xml` + `data_extraction_rules.xml` | exclude activities/ from backup | ✓ VERIFIED | Both exist; cloud-backup + device-transfer exclusions (WR-06 fix) |
| `phone/src/debug/.../MockRouteFeeder.kt` + debug manifest | debug-only mock GPS feeder | ✓ VERIFIED | 123 + 24 lines; physically absent from release builds (src/debug source set) |
| `glasses/.../BluetoothClient.kt` | SportState no-op branch | ✓ VERIFIED | line 258, documented Phase-1 no-op before Unknown (Phase 2 consumes) |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| FLP `onLocationResult` | LocationConsumer fan-out | `for (loc in result.locations)` oldest→newest | ✓ WIRED | `HudStreamingService.kt:789-794`; `result.lastLocation` count = 0 |
| LocationConsumer | `ActivitySessionManager.recordLocation` | permanent consumer registered in initRecording | ✓ WIRED | `:340-344`; ASM ignores fixes outside TRACKING (free-ride + nav-independent) |
| 1Hz ticker | `ProtocolCodec.encodeSportState` → BT broadcast + logcat | `broadcastSportState(snap)` | ✓ WIRED | `:139`, `:621-635`; one snapshot per tick also feeds listener + notification + checkpoint (single source of truth) |
| Ticker | `SessionStore.writeCheckpointAsync` | `pollCheckpoint()?.let { ... }` | ✓ WIRED | `:141-147` |
| MainActivity | `HudStreamingService.startRecording/stopRecording` | binder calls on tap / confirm dialog | ✓ WIRED | `MainActivity.kt:790`, `:807` |
| Service ticker | MainActivity card | `setMetricsListener` (registered onServiceConnected + onResume, cleared onDestroy) | ✓ WIRED | `:165`, `:515`, `:534` (WR-03) |
| WatchdogReceiver | service restart | `ACTION_WATCHDOG_CHECK` → `handleWatchdogCheck()` → FLP re-init + reschedule | ✓ WIRED | `HudStreamingService.kt:212-235`; receiver no-ops when rec_active false |
| decode | `ParsedMessage.SportState` | when() branch before else | ✓ WIRED | `ProtocolCodec.kt:252`; glasses when-branch keeps exhaustiveness |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|--------------------|--------|
| Recording card TextViews | `MetricsSnapshot` fields | ASM accumulators ← GPS fixes via fan-out | Yes (device-verified live values; formatters branch on real snapshot fields) | ✓ FLOWING |
| BT sport_state stream | encoded snapshot | same single snapshot per tick | Yes (269-line device capture, et/d monotonic, all v==1) | ✓ FLOWING |
| Session JSON on disk | `SessionData` (defensive copy) | same accumulators | Yes (persisted vs broadcast metrics agree to seconds-apart tolerance per 01-07-SUMMARY finding 4) | ✓ FLOWING |
| FGS notification | distance + chronometer | same snapshot, 10s throttle | Yes | ✓ FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Full suite + both APKs | `testDebugUnitTest assembleDebug` | exit 0; 65/65 tests; 2 APKs | ✓ PASS |
| Codec schema exact | unit `exactJsonKeys` (asserts 9 keys + count) | green | ✓ PASS |
| Device behaviors (1Hz logcat, 30-min gate, kill/resume) | adb (01-07 record) | cannot re-run without device; evidence internally consistent — all 30 referenced commits exist, code matches every claim (schema, constants, fix content) | ? SKIP → recorded evidence accepted; residuals routed to human items |

### Probe Execution

No `scripts/*/tests/probe-*.sh` probes exist in this repo; no probes declared in any Phase 1 PLAN. Step skipped (nothing to execute). The declared verification vehicle was the Gradle suite + adb device pass, both accounted for above.

### Requirements Coverage

| Requirement | Source Plans | Description | Status | Evidence |
|-------------|--------------|-------------|--------|----------|
| REC-01 | 01-02, 01-04, 01-05 | Opt-in GPS track recording (all point fields), free-ride supported | ✓ SATISFIED | Explicit-tap start only (`MainActivity.kt:355,784`); navigation untouched (git diff clean); TrackPoint carries lat/lng/alt/ts/speed/acc/brg (`ASM:232`); consumer registered independent of navigation |
| REC-02 | 01-02, 01-05 | Live metrics: elapsed, moving time, distance, speed/pace | ✓ SATISFIED | MetricsSnapshot + accumulators; 1Hz card updates in user's units (formatElapsed/Speed/Pace) |
| REC-03 | 01-02, 01-07 | >20m/≤0/unknown accuracy in log but excluded from distance | ✓ SATISFIED | Unconditional append `ASM:232`; gate `:266`; boundary tests (20.0 accepted, 20.1/0/-1 rejected-from-distance-but-logged) |
| REC-04 (amended) | 01-02, 01-07 | Raw-Doppler 0.7/0.3 hysteresis + >50 m/s seam gate | ✓ SATISFIED | `ASM:249-255,276-280`; amendment traceable end-to-end (REQUIREMENTS.md:34 ↔ STATE.md decisions ↔ commits 1b52505/701d0fb ↔ regression tests) |
| REC-05 | 01-04, 01-05, 01-06, 01-07 | Background reliability: WakeLock, FGS notification, battery exemption, bg-location, watchdog, staleness | ✓ SATISFIED | WakeLock pre-existing; recording notification w/ chronometer; exemption + bg-location onboarding (decline never blocks); L1 30s staleness + L2 5-min alarm chain; 35-min device gate passed |
| REC-06 | 01-03, 01-04, 01-07 | JSON persistence on stop + 60s/500pt checkpoint + restart survival | ✓ SATISFIED | SessionStore + ASM arming + service wiring; device kill/resume proof |
| REC-07 | 01-01, 01-04, 01-07 | sport_state message + codec + ~1Hz broadcast + monotonic et/d | ✓ SATISFIED | Codec + ticker + monotonic clamps at source (`ASM:355-370`, `distanceAndElapsedMonotonicAcrossMixedScenario` test); device capture all-monotonic |

No orphaned requirements: REQUIREMENTS.md maps exactly REC-01..07 to Phase 1 and every one is claimed by ≥1 plan. (Note: REQUIREMENTS.md checkboxes/traceability still read "Pending" for REC-01..07 — tracking-file update is milestone-audit scope, not a code gap.)

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `phone/.../ActivitySessionManager.kt` | 155 | Stale KDoc: resumeFrom doc says "the speed-MA window clears" — the MA was removed in 1b52505; no such field exists | ℹ️ Info | Doc drift only; behavior correct and tested |
| `shared/.../Messages.kt` | 73 | `ApkEndMessage(val placeholder: Boolean = true)` | ℹ️ Info | Pre-existing code (APK-update protocol), not Phase 1 work |
| (review) | — | IN-01..IN-04 deferred by 01-REVIEW fix scope (dead pref key, glasses redraw churn, debug feeder race, id validation) | ℹ️ Info | Explicitly triaged + documented as v1.x candidates in 01-07-SUMMARY |

Zero TBD/FIXME/XXX/TODO/HACK markers across all 17 phase files. No empty-return stubs, no hardcoded empty props, no console-only handlers. All 7 review Warnings verified FIXED in code (WR-01: `SessionStore.kt:153,307`; WR-02: `RecordingWatchdog.kt:56` + `HudStreamingService.kt:365`; WR-03: `MainActivity.kt:534-535`; WR-04: manifest:28 only USE_EXACT_ALARM; WR-05: `HudStreamingService.kt:451`; WR-06: res/xml pair + manifest:33-34; WR-07: `HudStreamingService.kt:188`).

### Human Verification Required

#### 1. Post-fix compact device re-verify (fixed-APK 5-min mock cycle)

**Test:** On the unlocked OPPO, run the MockRouteFeeder track against the current build (contains 1b52505 + WR-01..07, all of which landed after the 35-min gate run). Confirm the stationary-drift segment holds distance flat and provider-switch teleports add no distance.
**Expected:** Distance flat (<2m) while stationary; teleport hop logged as seam, not counted; sport_state stream + checkpoint cadence unchanged.
**Why human:** Phone unlock (face/PIN) is the one boundary adb automation cannot cross (01-07-SUMMARY Residual items). Fixes are regression-locked at unit level with the device-measured inputs, so risk is low — but this is the declared closure for findings 2/3.

#### 2. Mock-app-selection teardown

**Test:** Developer Options → 选择模拟位置信息应用 → 无 (clear mock-location app selection) on the OPPO.
**Expected:** No mock-location app selected; future recordings use real GPS.
**Why human:** Device settings UI behind the lock screen; feeder process itself is already dead.

#### 3. 2-hour pre-release screen-off run (pre-release gate — tracked, not a Phase 1 exit gate)

**Test:** Full 2-hour screen-off recording on the OPPO per STATE.md Pending Todos.
**Expected:** Continuous track, no fix gap >30s, clean finalize.
**Why human:** Wall-clock endurance on physical hardware.

### Gaps Summary

None. All 7 ROADMAP success criteria are observably true in the codebase; the build/test gate passes from a clean run in this session (65 tests, both APKs); every summary-referenced commit exists; the REC-04 amendment is consistently documented across REQUIREMENTS.md, STATE.md decisions, code comments, and regression tests; all 7 code-review warnings are verifiably fixed in source. The three human items are device-bound follow-ups already declared in 01-07-SUMMARY residuals and STATE.md — none blocks the phase goal, but item 1 should be completed before relying on the recording engine for real rides, since the shipped binary's accumulator paths have only unit-level (not device-level) confirmation post-fix.

---

_Verified: 2026-07-03T18:45:00Z_
_Verifier: Claude (gsd-verifier)_
