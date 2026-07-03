---
phase: 02-glasses-sport-hud
verified: 2026-07-03T21:55:00Z
status: human_needed
score: 14/14 must-haves verified
overrides_applied: 0
human_verification:
  - test: "Mini-mode device spot (SC#4 residual): with a recording streaming, enable the phone mini-map toggle -> glasses show [ STRIP ]; one spaced tap (input tap 240 320) -> [ FULL ]. Repeat with split style if convenient."
    expected: "[ STRIP ] (or [ SPLIT ]) while the phone toggle is on; a single glasses tap returns [ FULL ]"
    why_human: "The 02-04 device pass explicitly skipped this spot ('no phone Mini toggle exercised this pass' — verdict was PASS unit + code path), and the Settings branch that computes Mini modes was subsequently edited by the WR-01 fix (958f512, landed after the device pass 27a313a). Code + unit tests cover it; a 2-minute device check closes the loop."
  - test: "WR-01 post-fix device check: enter SPORT during an active recording, then trigger a settings re-send (toggle any phone setting, e.g. TTS, or walk out of BT range and back to force a reconnect)"
    expected: "Glasses stay in [ SPORT ] — the mode is no longer evicted to [ FULL ] by the settings re-broadcast; Mini toggle must still override to [ STRIP ]/[ SPLIT ]"
    why_human: "Fix commit 958f512 landed AFTER the 02-04 device pass, so the device evidence predates it. BluetoothClient has no JVM test surface (android.* imports), and this settings-eviction path was the phase's primary-use-case bug (rider in SPORT, phone in pocket, routine BT drop)."
  - test: "Sport/unit device variants (low risk): toggle imperial on the phone while in SPORT (expect ~mph primary), then record a Run activity (expect M:SS pace primary with speed secondary)"
    expected: "Imperial: primary numeral in mph with 'mph' unit label; Run: pace M:SS primary with '/km' (or '/mi') unit and speed as the secondary line"
    why_human: "02-04-PLAN truth #4 called for these on device but the verdict table carries no mph/Run evidence (all captures are Ride/metric). Every link is unit-verified (SportFormat imperial vectors, applySportState sp mapping, isRide branch read in source) — end-to-end hardware observation is the only missing layer."
---

# Phase 2: Glasses Sport HUD — Verification Report

**Phase Goal:** Glasses display real-time sport metrics during activity recording
**Verified:** 2026-07-03T21:55:00Z
**Status:** human_needed (all 14 must-haves verified in codebase + device evidence; 3 device-bound follow-ups need human hands)
**Re-verification:** No — initial verification

## Build/Test Gate (run by verifier, this session)

`testDebugUnitTest assembleDebug` -> **exit 0**. Test counts from JUnit XML:

| Suite | Tests | Failures | Errors |
|---|---|---|---|
| shared: ProtocolCodecTest | 7 | 0 | 0 |
| phone: ActivitySessionManagerTest + SessionStoreTest | 58 | 0 | 0 |
| glasses: HudStateTest (8) + SportFormatTest (6) | 14 | 0 | 0 |
| **Total** | **79** | **0** | **0** |

Both APKs assembled: `phone/build/outputs/apk/debug/phone-debug.apk` (18.7MB), `glasses/build/outputs/apk/debug/glasses-debug.apk` (7.5MB). The glasses module's first-ever unit tests (Wave 0 gap) run green.

## MVP-Mode Note

ROADMAP Phase 2 has `mode: mvp` but its goal line is not user-story formatted (`user-story.validate` -> false). All four PLAN files carry the user-story transliteration ("As a cyclist or runner mid-activity, I want to see my elapsed time, current speed or pace, and distance floating on my glasses, updating every second, so that I keep my eyes on the road and my phone in my pocket while my ride or run is measured"), and the roadmap defines four concrete Success Criteria — verification proceeded against those, matching the Phase-1 precedent. The User Flow Coverage section below satisfies the MVP-mode report structure. Informational only; consider reformatting the ROADMAP goal via `/gsd mvp-phase 2` before the milestone audit if strict MVP-goal formatting matters.

## User Flow Coverage

User story: «As a cyclist or runner mid-activity, I want to see my elapsed time, current speed or pace, and distance floating on my glasses, updating every second, so that I keep my eyes on the road and my phone in my pocket while my ride or run is measured.»

| Step | Expected | Evidence | Status |
|------|----------|----------|--------|
| Recording streams metrics | Phone broadcasts sport_state at ~1Hz | `HudStreamingService.kt:134` sportStateTicker -> `:622` encodeSportState (Phase-1 device-proven) | ✓ |
| Tap glasses to SPORT | Third tap-cycle mode reachable | `HudView.kt:160` onSingleTapConfirmed -> `HudActivity.kt:224` btClient.toggleLayout() -> `HudState.kt:126-134` 3-way cycle; device screencaps [ CORNER ] -> [ SPORT ] -> [ FULL ] | ✓ |
| See elapsed/speed-or-pace/distance | Big green numerals, no map | `HudView.kt:626-720` drawSportLayout; device: ELAPSED 0:02:26, 19.8 km/h primary, pace 2:59/km secondary, distance row | ✓ |
| Updating every second | ~1Hz redraws + staleness ticker | `BluetoothClient.kt:269` applySportState per message + `HudView.kt:134-141` sportTick; device: elapsed advancing, distance 478m -> 667m | ✓ |
| Outcome: eyes on road, phone in pocket | SPORT persists hands-free | Device: [ SPORT ] held across t0/t6/t11 under active 1Hz stream (revert regression closed); WR-01 fix preserves SPORT across settings re-sends (`BluetoothClient.kt:207-212`) — post-fix device confirmation is human item 2 | ✓ (one human follow-up) |

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | SC#1: Tap cycle Full -> Corner -> Sport -> Full | ✓ VERIFIED | `HudState.kt:126-134` (FULL->CORNER->SPORT->FULL); `tapCycleFullCornerSportFull` test; 02-04 device screencap sequence [ CORNER ] -> [ SPORT ] -> [ FULL ]; layout-revert regression proven on device (SPORT held ≥3s under 1Hz stream) |
| 2 | SC#2: Elapsed, speed/pace, distance display and update ~1Hz | ✓ VERIFIED | `drawSportLayout` renders all three (`HudView.kt:687-719`); `BluetoothClient.kt:269` consumes every sport_state; device: elapsed 0:02:26 advancing, 19.8 km/h = mock 5.5 m/s x 3.6 exactly, distance 478m -> 667m across captures |
| 3 | SC#3: Monochrome green rendering | ✓ VERIFIED | All 7 sport paints reference only the 5 `hud*Green` palette fields (`HudView.kt:97-125,647-653`); `Color.parseColor` count = 5 (unchanged); device pixel scan: 0/307,200 non-green pixels on 3 SPORT frames (R==0 ∧ B==0) |
| 4 | SC#4: Mini modes unchanged; tap from Mini returns Full | ✓ VERIFIED | Settings Mini branch byte-identical (`BluetoothClient.kt:202-206`, WR-01 fix touched only the else-chain); `HudState.kt:131-132` MINI_* -> FULL_SCREEN; `miniModesTapReturnToFull` test; device Mini spot deferred -> human item 1 |
| 5 | Glasses module runs first plain-JVM tests green | ✓ VERIFIED | `glasses/build.gradle.kts` testImplementation junit:4.13.2 (count 1); 14 tests, 0 failures this session |
| 6 | applySportState maps all 7 fields + stamps receipt | ✓ VERIFIED | `HudState.kt:102-111` (7 fields + lastSportStateAtMs = injected nowMs); `applySportStateMapsAllFieldsAndStampsReceipt` + `sportFieldsSurviveUnrelatedCopies` tests |
| 7 | Staleness precedence NOT_RECORDING > FINISHED > NO_DATA(>10s) > DIM(>3s) > LIVE | ✓ VERIFIED | `HudState.kt:118-124` strict-> ladder; `stalenessLadder`/`finishedBeatsStaleness`/`neverReceivedIsNotRecording`/`idleIsNotRecording` tests; device: NO DATA at +13s after force-stop, FINISHED bright and sticky |
| 8 | SportFormat exact strings (H:MM:SS, --:-- sentinel, one-decimal, imperial, 0.7 dot) | ✓ VERIFIED | `SportFormat.kt:30-57`; 6 worked-vector tests incl. imperial (7:53/mi, 13.9 mph) and boundary dot (0.7 -> hollow) |
| 9 | sport_state consumption wired: no-op gone, single call site, 1Hz delivery | ✓ VERIFIED | `BluetoothClient.kt:269` (applySportState count = 1, "dropped in Phase 1" comment count = 0); `onStateUpdate(currentState)` at `:274` fires after every message |
| 10 | Layout-mode ownership in BluetoothClient (tap survives stream) | ✓ VERIFIED | `BluetoothClient.kt:56-59` public toggleLayout mutation+notify; `HudActivity.kt:223-225` one-line delegate; old path absent (grep 0); lambda preserves only battery/wifi; device: SPORT persisted t0/t6/t11 |
| 11 | SPORT rendering: full ladder, no map, movingMs never rendered | ✓ VERIFIED | All 5 display-mode branches in `drawSportLayout` (NOT RECORDING `:655-664`, FINISHED `:690-693`, NO DATA `:695-698`, LIVE/DIM stack); zero drawLiveMap/tileManager refs inside the function; movingMs count in HudView = 0 |
| 12 | SPORT-only 1Hz ticker with sound lifecycle | ✓ VERIFIED | `HudView.kt:134-141` self-rescheduling sportTick; setter transition edges `:144-153`; attach re-arm `:178`; detach removal `:182`; counts: postDelayed(sportTick,1000L)=2, removeCallbacks(sportTick)=4; no coroutine/Timer/Thread |
| 13 | 02-REVIEW warning fixes present in source | ✓ VERIFIED | WR-01: SPORT-preserve else-if `BluetoothClient.kt:207-212` (958f512, +5 lines, 1 file); WR-02: Locale.US at `SportFormat.kt:32,44,51` (7e6abf9); WR-03: cacheSizeChanged captured before assignment `HudActivity.kt:94,104` (1e90f7c) |
| 14 | 02-04 device evidence internally consistent; all referenced commits exist | ✓ VERIFIED | 19.8 km/h = 5.5x3.6 exact; 0/307,200 = 480x640 exact; 667m final consistent with kill/resume narrative (734m broadcast -> 667m checkpoint rewind); all 13 referenced commits (1757c39...1e90f7c, 1b52505, aa3ecaa) confirmed in git log |

**Score:** 14/14 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `glasses/build.gradle.kts` | testImplementation junit:4.13.2 | ✓ VERIFIED | Count = 1, exact coordinate |
| `glasses/src/main/java/com/rokid/hud/glasses/SportFormat.kt` | Pure-Kotlin formatter object | ✓ VERIFIED | 59 lines, `object SportFormat`, only `java.util.Locale` import (JVM-pure), Locale.US-pinned |
| `glasses/src/main/java/com/rokid/hud/glasses/HudState.kt` | SPORT enum, SportDisplayMode, 8 fields, seams, 3-way cycle | ✓ VERIFIED | 135 lines, all present, android-import-free |
| `glasses/src/main/java/com/rokid/hud/glasses/HudView.kt` | Full drawSportLayout + paints + sportTick + lifecycle | ✓ VERIFIED | 774 lines; SPORT dispatch `:195`, "[ SPORT ]" indicator `:728`, full implementation `:626-720` — no stub remains |
| `glasses/src/main/java/com/rokid/hud/glasses/BluetoothClient.kt` | applySportState call + public toggleLayout | ✓ VERIFIED | Lines 56-59, 269; WR-01 branch 207-212 |
| `glasses/src/main/java/com/rokid/hud/glasses/HudActivity.kt` | btClient.toggleLayout delegation | ✓ VERIFIED | Line 224; lambda untouched except WR-03 capture |
| `glasses/src/test/.../HudStateTest.kt` | Cycle/mapping/staleness tests, ≥60 lines | ✓ VERIFIED | 113 lines, 8 genuine worked-vector tests |
| `glasses/src/test/.../SportFormatTest.kt` | Formatter vectors, ≥40 lines | ✓ VERIFIED | 59 lines, 6 tests incl. boundaries + imperial |
| `.planning/phases/.../02-04-SUMMARY.md` | Phase-gate verdict table | ✓ VERIFIED | Verdict table with one row per SC + revert regression + green scan + protocol hygiene |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| HudView onDraw | drawSportLayout | `MapLayoutMode.SPORT ->` branch | ✓ WIRED | `HudView.kt:195` |
| BluetoothClient processMessage | HudState.applySportState | SportState branch + SystemClock.elapsedRealtime() | ✓ WIRED | `BluetoothClient.kt:269`, single call site |
| HudActivity | BluetoothClient.toggleLayout | onLayoutToggle -> toggleLayout() body | ✓ WIRED | `HudActivity.kt:75,224`; gesture source `HudView.kt:160-163` |
| HudView drawSportLayout | HudState.sportDisplayMode | draw-time classification, monotonic clock | ✓ WIRED | `HudView.kt:629` |
| HudView drawSportLayout | SportFormat | formatElapsed/formatPace/formatSpeed/movingDot | ✓ WIRED | `HudView.kt:673-705` |
| HudView distance row | formatDistance | existing private formatter reused | ✓ WIRED | `HudView.kt:718` (+ sub-1m explicit zero at `:716-717`) |
| HudState | shared SportStateMessage | applySportState parameter import | ✓ WIRED | `HudState.kt:3` |
| phone sport_state broadcast | glasses SPORT numerals | BT SPP -> ProtocolCodec -> applySportState -> onStateUpdate -> drawSportLayout | ✓ WIRED | Full pipeline traced in code + 02-04 device evidence end-to-end |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|--------------|--------|--------------------|--------|
| drawSportLayout | state.elapsedMs/currentSpeedMps/avgPaceMsPerKm/distanceM/sport/sessionState | applySportState <- ProtocolCodec.decode (`ProtocolCodec.kt:252-253`) <- BT line <- phone sportStateTicker (`HudStreamingService.kt:134`, encodeSportState `:622` fed by live GPS session) | Yes — device captures show real advancing values | ✓ FLOWING |
| drawSportLayout staleness | state.lastSportStateAtMs | SystemClock.elapsedRealtime() at receipt (`BluetoothClient.kt:269`) vs draw-time clock (`HudView.kt:629`) — same monotonic family | Yes — device NO DATA transition observed after force-stop | ✓ FLOWING |
| HudView.state | BluetoothClient.currentState | onStateUpdate -> HudActivity lambda -> hudView.state (`HudActivity.kt:89-99`) | Yes | ✓ FLOWING |

No hollow props, no static returns, no hardcoded-empty data in the sport path.

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Full suite + APK assembly | `java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain testDebugUnitTest assembleDebug -q` | exit 0; 79/79 tests; both APKs produced | ✓ PASS |
| Tap-cycle + staleness + formatter semantics | JUnit (14 glasses tests) | 0 failures | ✓ PASS |
| On-device HUD behavior | adb-driven pass documented in 02-04-SUMMARY (session 20260703-131424-69057f7c) | 9/9 verdict rows PASS | ✓ PASS (executor-run; consistency-audited, not re-run — glasses hardware session) |

### Probe Execution

No `scripts/*/tests/probe-*.sh` probes exist in this repo (no scripts dir). The phase's declared executable gate is the Gradle full-suite command — run by this verifier, exit 0 (see Build/Test Gate).

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| HUD-01 | 02-01, 02-03, 02-04 | SPORT layout: elapsed, speed/pace (selected units), distance | ✓ SATISFIED | `drawSportLayout` full implementation + device captures |
| HUD-02 | 02-01, 02-02, 02-04 | ~1Hz updates via sport_state | ✓ SATISFIED | `BluetoothClient.kt:269` + sportTick + device (elapsed/distance advancing); 0 Unknown-x-sport_state logcat lines |
| HUD-03 | 02-01, 02-02, 02-04 | SPORT via tap; Full->Corner->Sport->Full; Mini reset preserved | ✓ SATISFIED | 3-way cycle + ownership fix + device revert-regression proof |
| HUD-04 | 02-03, 02-04 | Monochrome green consistent with HUD style | ✓ SATISFIED | 5-color palette only + 0/307,200 pixel scans |

No orphaned requirements: REQUIREMENTS.md maps exactly HUD-01..HUD-04 to Phase 2, and all four are claimed across the plans. (Doc hygiene note: REQUIREMENTS.md checkboxes/traceability still read "Pending" for HUD-01..04 — same pre-existing state as Phase 1's REC rows; a milestone-audit flip, not a code gap.)

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| — | — | No TBD/FIXME/XXX/TODO/HACK/placeholder markers in any of the 8 phase files | — | The 02-01 `drawSportLayout` stub was filled by 02-03 (verified: 95-line implementation, no empty bodies) |

Info-level review items IN-01..IN-06 (comment overclaims, formatElapsed negative-input rendering, two test-coverage gaps, missing Unix `gradlew`) remain open with recorded DEFERRED dispositions in 02-REVIEW.md — none blocks the phase goal.

### Human Verification Required

#### 1. Mini-mode device spot (SC#4 residual)

**Test:** With a recording streaming, enable the phone mini-map toggle -> glasses screencap; one spaced tap (`input tap 240 320`).
**Expected:** [ STRIP ] (or [ SPLIT ]) while the toggle is on; single tap returns [ FULL ].
**Why human:** The 02-04 device pass deferred this spot ("no phone Mini toggle exercised this pass" — verdict PASS on unit + code path), and the Settings branch computing Mini modes was subsequently edited by the WR-01 fix (958f512 landed after device summary 27a313a). Code reads correct and unit tests cover the tap-return path; a 2-minute device check closes it.

#### 2. WR-01 post-fix device check (SPORT survives settings re-send)

**Test:** Enter SPORT during an active recording, then toggle any phone setting (or force a BT drop/reconnect).
**Expected:** Glasses stay in [ SPORT ]; Mini toggle still overrides to [ STRIP ]/[ SPLIT ].
**Why human:** The fix landed after the device pass, BluetoothClient has no JVM test surface, and this eviction path was the phase's primary-use-case bug. The pre-fix device pass actually observed the bug firing (02-04-PLAN worked around it: "Re-enter SPORT ... if a reconnect Settings message reset the layout").

#### 3. Imperial + Run device variants (low risk)

**Test:** Toggle imperial while in SPORT; then record a Run activity.
**Expected:** mph primary with "mph" label; Run shows M:SS pace primary with speed secondary.
**Why human:** Called for by 02-04-PLAN truth #4 but absent from the verdict-table evidence (all captures Ride/metric). Every link is unit-verified; only the end-to-end hardware observation is missing.

Standing pre-release item (NOT a Phase 2 gate): the 2-hour screen-off recording validation remains in STATE.md Pending Todos by design (Phase 1 SC#3 wording).

### Gaps Summary

No gaps. All four ROADMAP Success Criteria are achieved in the codebase and backed by the 02-04 on-device evidence trail (internally consistent: 19.8 km/h = 5.5 m/s x 3.6 exact; 0/307,200 = 480x640 exact; kill/resume distance narrative coheres). All three 02-REVIEW warnings are verifiably fixed in source with tightly-scoped commits. The three human items above are confirmation passes on device-bound behaviors — two exist because the review fixes landed after the device pass, one because the device pass deferred the Mini spot. None indicates a codebase defect.

---

_Verified: 2026-07-03T21:55:00Z_
_Verifier: Claude (gsd-verifier)_

## Orchestrator routing note (2026-07-03)

The 3 human-verification spots (Mini-toggle device spot, WR-01 post-fix on-hardware, imperial/run variants) are FOLDED into the next device session (Phase 3+ passes — the OAuth flow requires extended phone-UI time anyway). Attempted immediately post-verification; blocked twice by phone screen-state churn between USB renegotiations. Fix presence in source re-confirmed; bonus capture obtained: SPORT idle "NOT RECORDING" hint renders per locked CONTEXT (wr01_after_connect.png). Fixed builds are deployed on both devices.
