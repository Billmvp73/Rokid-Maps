---
phase: 01-activity-recording-engine
plan: 05
subsystem: phone-ui
tags: [recording-card, live-metrics, confirm-dialog, battery-exemption, background-location, onboarding]

# Dependency graph
requires:
  - phase: 01-activity-recording-engine (plan 01-04)
    provides: "Recording binder API (startRecording/stopRecording/recordingState/currentMetrics/setMetricsListener) + 1Hz MetricsListener ticker"
  - phase: 01-activity-recording-engine (plan 01-02)
    provides: "MetricsSnapshot/SessionState/MetricsListener contracts in SessionModels.kt"
provides:
  - "RECORD card on MainActivity: Ride/Run sport toggle (persisted, default ride), big Start Recording button, live recording panel (REC badge + elapsed/distance/speed/pace at 1Hz), red Stop button"
  - "Confirm-to-stop dialog 'Finish recording?' with NO discard path — sessions always save"
  - "Recording UI re-sync from service on connect AND resume: activity recreation mid-recording restores the live panel and repaints from currentMetrics()"
  - "First-recording onboarding (REC-05 consent layers): battery-exemption prompt (once per app launch, skipped when exempt) chained via onDismiss into the ask-once background-location explainer + RC_BG_LOCATION request"
  - "formatElapsed (h:mm:ss) / formatSpeed (mph|km/h) / formatPace (m:ss /mi|/km, en-dash idle) formatters honoring the existing imperial toggle"
  - "sport_type + bg_loc_asked prefs in rokid_hud_prefs (service-readable)"
affects: [01-06, 01-07, phase-2-glasses-hud, phase-5-history-upload]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Metrics listener as object-expression field with every callback body wrapped in runOnUiThread (navCallback convention)"
    - "syncRecordingUiFromService(): visibility swap + immediate snapshot repaint — one helper serves onServiceConnected, onResume, and the start flow"
    - "Dialog chaining via setOnDismissListener so sequential onboarding prompts never stack"
    - "Process-scoped once-per-launch flag as companion-object var (survives activity recreation, resets on process death)"

key-files:
  created: []
  modified:
    - phone/src/main/res/layout/activity_main.xml
    - phone/src/main/java/com/rokid/hud/phone/MainActivity.kt

key-decisions:
  - "Start flow calls syncRecordingUiFromService() (which itself calls updateRecordingUi(true)) so the card paints the t=0 snapshot instantly instead of sitting blank until the first 1Hz tick"
  - "recordingExemptionPromptShown lives in the companion object — 'once per app launch' means process-scoped, and an activity-instance field would re-prompt after every recreation"
  - "PREF_BG_LOC_ASKED is set when the explainer is SHOWN (covers Continue, Skip, and outside-tap dismissal) — strictly ask-once"
  - "Sport toggle row got id sportToggleRow so updateRecordingUi can hide it with btnStartRecording while recording"
  - "finished sessionState in the metrics callback also flips the UI back — covers stop paths not initiated from this Activity instance"

patterns-established:
  - "Recording section methods grouped under '// ── Activity recording ─────' between Navigation and Wi-Fi sharing sections"
  - "Formatters colocated with formatDist; all unit branching through the single isImperial() source"

requirements-completed: [REC-01, REC-02, REC-05]

# Metrics
duration: 9min
completed: 2026-07-03
---

# Phase 01 Plan 05: Recording UI Summary

**The phase's core user story is now functional end-to-end from the phone screen: an opt-in RECORD card starts a ride/run session via the 01-04 binder, live 1Hz elapsed/distance/speed/pace render in the user's units next to a REC badge, stop requires a "Finish recording?" confirm with no discard path, and first-recording onboarding walks through battery exemption + background location without ever gating the recording**

## Performance

- **Duration:** ~9 min
- **Started:** 2026-07-03T16:25:17Z
- **Completed:** 2026-07-03T16:34:30Z
- **Tasks:** 2/2
- **Files modified:** 2 (activity_main.xml +152, MainActivity.kt +153 net — purely additive vs base; zero removed lines)

## Accomplishments

- REC-01 user surface: recording starts ONLY from an explicit Start Recording tap (navigation code untouched — zero removed lines in MainActivity vs base); sport toggle Ride/Run persists as `sport_type` in `rokid_hud_prefs` (default ride) and is passed to `startRecording(sport)`
- REC-02 user surface: elapsed (h:mm:ss), distance (formatDist), current speed (mph/km/h), and pace (m:ss /mi|/km secondary line) update at ~1Hz from the SAME MetricsListener that feeds the BT broadcast; imperial/metric follows the existing units toggle
- Stop → AlertDialog "Finish recording?" / "Your activity will be saved." with Finish + Keep recording only — `grep -qi discard` exits 1 (no discard path anywhere)
- UI survives activity recreation: `setMetricsListener(recMetricsListener)` + `syncRecordingUiFromService()` run in both onServiceConnected and onResume; when `recordingState() == TRACKING` the panel restores and repaints from `currentMetrics()`
- REC-05 consent layers: battery-exemption dialog fires on FIRST recording start (never app launch), at most once per process, skipped when already exempt; background-location explainer chains off its dismissal, asks once ever (`bg_loc_asked`), and denial of either only logs/toasts — the running recording is never stopped or blocked

## Task Commits

1. **Task 1: Recording card layout + MainActivity wiring with live metrics** — `3b15d1a`
2. **Task 2: First-recording onboarding — battery exemption + background location** — `e5f0a1a`
3. **Post-task fix: paint initial snapshot immediately on start** — `8e3bab4`

## Final View IDs (as built)

`sportToggleRow`, `btnSportRide`, `btnSportRun`, `btnStartRecording`, `recordingPanel`, `recBadge`, `recElapsedText`, `recDistanceText`, `recSpeedText`, `recPaceText`, `btnStopRecording`

Layout structure matches the plan sketch: RECORD section card (bg_card, 16dp padding, SectionTitle "RECORD") inserted between SEARCH & NAVIGATE and LIVE NAVIGATION; recordingPanel is bg_card_accent with the badge+elapsed row, distance+speed row, pace secondary line (#81C784 13sp), and the #D32F2F Stop button. recBadge uses the existing `bg_card_red` drawable with 8dp horizontal / 2dp vertical padding — no new drawables created.

## Prompt Sequencing Behavior (as built)

1. User taps Start Recording → `service!!.startRecording(sport)` → on `true` the panel shows and paints the t=0 snapshot; **recording is already running before any prompt appears**
2. `promptRecordingBatteryExemption()`: gated on API≥M, not-yet-shown-this-launch, and `!isIgnoringBatteryOptimizations`. "Allow" fires ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (package: URI, try/catch); "Not now" → `Log.w("recording without battery exemption")` and proceeds
3. On that dialog's dismiss (any path: Allow/Not now/outside tap) — or immediately when it was skipped — `maybeRequestBackgroundLocation()`: gated on API≥Q, not granted, `bg_loc_asked` false. Flag set at show time. "Continue" → `ActivityCompat.requestPermissions(ACCESS_BACKGROUND_LOCATION, RC_BG_LOCATION)` (settings-routed on API 30+, expected); "Skip" proceeds
4. `onRequestPermissionsResult` RC_BG_LOCATION branch: logs grant result; on denial toasts "Recording works, but won't auto-recover if the system kills the app" — never touches the running recording

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Blank-card flash] Start flow uses syncRecordingUiFromService() instead of bare updateRecordingUi(true)**
- **Found during:** post-task review
- **Issue:** The service's first MetricsListener callback is postDelayed ~1s (01-04 ticker design: the immediate t=0 sport_state goes to BT broadcast, not the listener), so the freshly shown panel rendered empty TextViews for up to a second
- **Fix:** `syncRecordingUiFromService()` — which calls `updateRecordingUi(true)` and then repaints from `currentMetrics()` — replaces the bare visibility call; the plan's mandated `updateRecordingUi(true)` still executes, plus an immediate snapshot paint
- **Files modified:** phone/src/main/java/com/rokid/hud/phone/MainActivity.kt
- **Commit:** `8e3bab4`

### Plan-vs-reality adaptations

**2. [Adaptation] showFirstRecordingPrompts() carried a placeholder body inside the Task 1 commit**
- Task 1's start flow calls the function but its implementation is Task 2's scope; an empty-bodied definition let the Task 1 gate (`:phone:compileDebugKotlin :phone:assembleDebug`) pass standalone. Task 2 (`e5f0a1a`) replaced it minutes later — no placeholder remains in the final tree.

**3. [Rule 2 - UX feedback] Toast "Could not start recording" on false return**
- The plan specified no else-branch for `startRecording(sport) == false` (service not running / already TRACKING). Silent button taps are a feedback bug; a short toast follows the guard-toast convention.

**4. [Adaptation] Sport toggle row id `sportToggleRow`**
- The plan named no id for the row but requires `updateRecordingUi` to toggle "sport toggle row visibility" — it needs an id to be referenced. camelCase per convention.

**5. [Interpretation] "Process-scoped boolean" placed in the companion object**
- An activity-instance field would reset on every recreation and re-prompt mid-session; companion-object `var recordingExemptionPromptShown` gives true once-per-app-launch semantics.

### Environmental note

The plan's verify commands referenced the main repo path (`cd /Users/bilhuang/Documents/rokid-maps`); verification ran from this executor's worktree root instead per parallel-isolation rules — same Gradle tasks, same gates.

## Verification Results

- Task 1 gate: `:phone:compileDebugKotlin :phone:assembleDebug` — exit 0
- Task 2 gate: `:phone:compileDebugKotlin :shared:testDebugUnitTest :phone:testDebugUnitTest` — exit 0
- Overall: `assembleDebug :shared:testDebugUnitTest :phone:testDebugUnitTest` (all three modules) — exit 0
- All acceptance greps pass: all 10 required layout ids present; `"Finish recording?"` present; `grep -qi discard` exits 1; `setMetricsListener` ×2 (onServiceConnected + onResume); `fun formatPace` present; `ACCESS_BACKGROUND_LOCATION` present; `isIgnoringBatteryOptimizations` ×2 (existing nav prompt + recording prompt); `RC_BG_LOCATION` in companion + request + result branch; `showFirstRecordingPrompts()` has exactly one call site, inside the start handler
- Regression: `git diff 8d22c65..HEAD` touches ONLY the two planned files; MainActivity diff is purely additive (zero removed lines vs base); AndroidManifest.xml, STATE.md, ROADMAP.md untouched
- Manual smoke (card starts/stops a real recording on the OPPO) deferred to plan 01-07 as specified

## Threat Model Dispositions (as implemented)

- T-05-01 (mitigate): ACCESS_BACKGROUND_LOCATION requested only with the in-context explainer during first-recording onboarding, ask-once flag, fully functional on denial; manifest declaration ships in 01-06
- T-05-02 (accept): card shows metrics only — no coordinates rendered
- T-05-03 (mitigate): start is an explicit tap (never auto-started by navigation); stop requires the confirm dialog

## Known Stubs

None — no placeholders, TODO/FIXME, or hardcoded empty values flowing to consumers. Metric TextViews are populated at panel-show time from `currentMetrics()` and at 1Hz thereafter.

## Next Phase Readiness

- Plan 01-06 (same wave): owns AndroidManifest.xml — the `ACCESS_BACKGROUND_LOCATION` declaration makes this plan's runtime request functional; `bg_loc_asked`/`sport_type` live in `rokid_hud_prefs`
- Plan 01-07 (device verification): full on-phone flow is exercisable — start (with onboarding prompts on first run), 1Hz card updates in both unit systems, confirm-stop, recreation-restore mid-recording
- Phase 5: sport type is captured per session now, so Strava upload needs no retrofit

## Self-Check: PASSED

- Both modified files exist and compile; all three APK/test gates re-verified green
- All task commits present in git log: 3b15d1a, e5f0a1a, 8e3bab4
