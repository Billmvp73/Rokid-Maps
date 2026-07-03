---
phase: 02-glasses-sport-hud
plan: 03
subsystem: glasses-hud
tags: [kotlin, android-canvas, sport-hud, rendering, staleness-ticker]

# Dependency graph
requires:
  - phase: 02-glasses-sport-hud plan 01
    provides: SportFormat formatters, HudState.sportDisplayMode staleness ladder, SPORT enum + empty drawSportLayout stub
  - phase: 02-glasses-sport-hud plan 02
    provides: live 1Hz sport_state consumption with SystemClock.elapsedRealtime() receipt stamp; layout ownership in BluetoothClient.currentState
provides:
  - Full drawSportLayout implementation — CONTEXT-locked big-numeral metric screen (elapsed top, 120f sport-aware primary, unit line, secondary line, distance bottom), no map, no tiles
  - Display-mode ladder rendering — LIVE bright, STALE_DIM/STALE_NO_DATA one-level color step-down, NO DATA banner in the primary slot, FINISHED banner over bright retained values, NOT RECORDING hint with ASCII dash placeholders
  - Pre-allocated sport paint group (7 paints) using only the five existing hud*Green palette fields (HUD-04)
  - SPORT-only 1Hz self-rescheduling invalidate ticker (sportTick) with setter transition-edge arm/disarm and attach/detach lifecycle guards
affects: [02-04 device verification]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Per-pass paint color mutation for the dim ladder (drawStatusBar pattern) on pre-allocated paints — zero allocation in the 1Hz draw path"
    - "Transition-edge ticker lifecycle: state setter compares value.layoutMode vs field.layoutMode before assignment; removeCallbacks+postDelayed on enter, removeCallbacks on leave/detach"

key-files:
  created: []
  modified:
    - glasses/src/main/java/com/rokid/hud/glasses/HudView.kt

key-decisions:
  - "sportBannerPaint stays hudBrightGreen in ALL modes including STALE_NO_DATA — banners (NO DATA / FINISHED / NOT RECORDING) are alerts; the dim step-down applies only to values and labels"
  - "Unit line retained (dimmed) under the NO DATA banner — part of 'all other values retained, dimmed'; shows which metric slot has no data"
  - "NOT_RECORDING draws the ELAPSED/DISTANCE labels with ASCII '--' values so the layout stays recognizable around the hint"
  - "Moving dot sits on the primary baseline at cx + primaryWidth/2 + 20f in the 26f secondary paint — worst-case primary '--:--' (~360px) keeps the dot inside the 480px canvas"

patterns-established:
  - "Sport geometry as fractions of h with 640-reference comments: 0.19/0.26/0.36/0.52/0.59/0.69/0.81/0.88"

requirements-completed: [HUD-01, HUD-04]

# Metrics
duration: 8min
completed: 2026-07-03
---

# Phase 2 Plan 03: Sport Rendering Summary

**SPORT mode now renders the full CONTEXT-locked big-numeral screen — sport-aware 120f primary (Ride=speed, Run=pace), display-mode ladder with one-level green step-down, and a SPORT-only 1Hz ticker that keeps dim/NO DATA transitions visible after messages stop — in a HudView-only diff using only the five existing greens.**

## Performance

- **Duration:** ~8 min
- **Started:** 2026-07-03T19:51:21Z
- **Completed:** 2026-07-03T19:59:30Z
- **Tasks:** 2/2
- **Files modified:** 1 (HudView.kt only — confirmed by diff scope check below)

## Accomplishments

- **drawSportLayout fully implemented** (replaces the 02-01 stub): elapsed (top, H:MM:SS 44f), sport-aware primary numeral (120f MONOSPACE BOLD, Ride -> speed / Run -> pace with lenient `state.sport != "run"` selection), primary unit line, non-primary secondary line, distance (bottom, 48f) — NO map, no tileManager/drawLiveMap references in the SPORT path (verified by awk-scoped grep)
- **All five display modes render per the locked spec:** LIVE bright; STALE_DIM steps values to `hudDimGreen` and labels to `hudDarkGreen` (one level down each); STALE_NO_DATA shows "NO DATA" via the 40f banner paint in the primary slot (120f would be ~504px wide — overflow) with dimmed values retained; FINISHED banner at 0.36h over FULL-brightness retained values (staleness-immune); NOT_RECORDING hint with ASCII "--" placeholders
- **HUD-04 palette proof:** `grep -c "Color.parseColor"` = 5, unchanged — the palette fields at lines 22-26 remain the only color definitions; all 7 sport paints reference `hud*Green` fields exclusively
- **SPORT-only 1Hz ticker:** self-rescheduling `sportTick` runnable (phone sportStateTicker shape, Handler-family only — no coroutines/Timer/Thread); armed on the setter's enter-SPORT edge and on attach-while-SPORT, disarmed on leave-SPORT and in onDetachedFromWindow before super; grep counts exactly 2x `postDelayed(sportTick, 1000L)` and 4x `removeCallbacks(sportTick)` (run() self-reschedules via the distinct `postDelayed(this, 1000L)` token)
- **Full suite green after both tasks:** shared 7 + phone 58 + glasses 14 tests, both APKs assemble

## Task Commits

Each task was committed atomically:

1. **Task 1: Full drawSportLayout with sport paints, display-mode ladder, CONTEXT geometry** - `1fcfdf0` (feat)
2. **Task 2: SPORT-only 1Hz staleness ticker with setter-transition and attach/detach lifecycle** - `183d958` (feat)

**Plan metadata:** committed separately as docs(02-03) with this SUMMARY.

## Files Created/Modified

- `glasses/src/main/java/com/rokid/hud/glasses/HudView.kt` — `import android.os.SystemClock`; sport paint group (7 pre-allocated paints after the turn-alert exemplar block); full `drawSportLayout`; `sportTick` runnable; transition-edge state setter; `onAttachedToWindow`/`onDetachedFromWindow` overrides. Sole file in the plan diff.

## Final Geometry Values (recorded per plan output spec)

All y positions are fractions of `h`, comments carry the 480x640 reference values:

| Row | Content | Paint | y fraction | ~y @640 |
|-----|---------|-------|-----------|---------|
| label | "ELAPSED" 16f | sportLabelPaint | 0.19h | 120 |
| elapsed | H:MM:SS 44f | sportElapsedPaint | 0.26h | 165 |
| banner | "FINISHED" 40f BOLD | sportBannerPaint | 0.36h | 230 |
| primary | speed/pace 120f BOLD + dot | sportPrimaryPaint | 0.52h | 330 |
| unit | "km/h" / "/km" 22f | sportUnitPaint | 0.59h | 375 |
| secondary | non-primary metric 26f | sportSecondaryPaint | 0.69h | 440 |
| label | "DISTANCE" 16f | sportLabelPaint | 0.81h | 520 |
| distance | formatDistance 48f | sportDistancePaint | 0.88h | 565 |

Moving dot: `cx + sportPrimaryPaint.measureText(primaryText)/2 + 20f` on the primary baseline, 26f secondary paint, rendered only in LIVE/STALE_DIM. All content below y=24f — status strip (y=14f) and "[ SPORT ]" indicator drawn by onDraw after the branch, untouched (Pitfall 7). `drawStatusBar` left untouched per plan step 4.

## Glyph Substitutions (recorded per plan output spec)

- ASCII `"--"` placeholders in NOT_RECORDING instead of em-dash (RESEARCH A4 tofu risk; visual intent identical; 02-04 screencap confirms coverage)
- `"--:--"` pace sentinel and `●`/`○` moving dot come from SportFormat as shipped in 02-01 — no new glyphs introduced by this plan

## Diff Scope Confirmation (recorded per plan output spec)

`git diff --name-only 1f952e9..HEAD` returns exactly one file: `glasses/src/main/java/com/rokid/hud/glasses/HudView.kt`. No other source, build, or planning file was touched by the two task commits.

## Decisions Made

- Banner color stays `hudBrightGreen` in every mode, including inside the dimmed STALE_NO_DATA pass — the plan's dim rule enumerates values and labels; "NO DATA" is an attention alert replacing the primary, and the paint's declared color is hudBrightGreen
- The unit line is retained (dimmed) under the NO DATA banner as part of "all other values retained, dimmed"
- NOT_RECORDING keeps the ELAPSED/DISTANCE labels above their "--" placeholders (layout stays recognizable; the plan specifies placeholders for the elapsed/secondary/distance rows and does not exclude labels); no unit line drawn since the primary slot holds the hint
- Pitfall-9 discretion adopted as planned: Ride secondary pace line hidden entirely while `avgPaceMsPerKm == 0L`; Run primary shows "--:--"

## Deviations from Plan

None - plan executed exactly as written. (Execution-environment note: Gradle was invoked from the git worktree root rather than the plan's literal `cd /Users/bilhuang/Documents/rokid-maps`, per worktree isolation rules; identical targets and flags. Same note as 02-01/02-02.)

## Issues Encountered

None.

## Known Stubs

None — this plan removed the last Phase 2 code stub (the empty `drawSportLayout` body tracked in 02-01/02-02 summaries). The "--" placeholder strings in NOT_RECORDING are locked design elements of the idle screen, not stubs.

## Threat Model Notes

No new threat surface. T-02-07 mitigations implemented exactly as registered: the ticker is bounded at 1Hz, SPORT-only, layoutMode-guarded inside run(), and removed on mode-exit and detach — rendering rate is fully decoupled from message rate. T-02-08: all rendered strings come from total formatters (02-01 tested) and reach only `drawText`.

## Next Phase Readiness

- Plan 02-04 (device verification) can proceed: SPORT renders live data, staleness transitions appear autonomously (verification step 7 depends on the ticker shipped here), FINISHED precedence is draw-time (step 8), and the green-only pixel scan precondition holds (SPORT draws no tiles)
- Visual/behavioral proof (numeral legibility, dim/NO DATA/FINISHED transitions, green-only pixels) is 02-04's on-device job — Canvas output has no JVM-assertable surface

## User Setup Required

None — no external services, no new permissions, no new dependencies.

## Self-Check: PASSED

- HudView.kt modified and present on disk; SUMMARY exists at .planning/phases/02-glasses-sport-hud/02-03-SUMMARY.md
- Both task commits (1fcfdf0, 183d958) present in git log
- Full suite re-run exit 0 after Task 2: 79 tests green (shared 7, phone 58, glasses 14), both APKs assembled
- All 12 acceptance greps pass, including corrected ticker counts (postDelayed=2, removeCallbacks=4) and palette count (Color.parseColor=5)
