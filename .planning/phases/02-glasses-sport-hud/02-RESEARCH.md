# Phase 2: Glasses Sport HUD - Research

**Researched:** 2026-07-03
**Domain:** Android custom-View rendering (glasses module) + Bluetooth SPP message consumption — pure intra-codebase phase, no new libraries
**Confidence:** HIGH (every implementation claim verified by reading the current source; sport_state behavior device-proven in Phase 1)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**SPORT layout composition**
- Pure metrics screen — huge numerals, NO map in SPORT mode (map modes are one tap away; recording continues regardless of layout; low-res monochrome favors big type)
- Thin top status strip retained (existing BT/battery strip style) so connectivity stays visible

**Metric arrangement (resolves the STATE pending todo "Sport HUD layout design")**
- Sport-aware primary metric from sport_state's `sp` field: Ride → current speed (km/h or mph per imperial toggle) as the huge center numeral; Run → pace (min/km or min/mi) as the huge center numeral
- Secondary rows: elapsed time (top, HH:MM:SS), distance (bottom, existing formatDistance conventions)
- The non-primary of speed/pace shows as a small secondary line
- Moving-state indicator: small ● when moving (derived: cs above ~0.7), hollow ○ when stopped — subtle, no text churn
- Existing green shade hierarchy only (#00FF00 primary numeral, #00CC00 secondary, #008800 labels) — HUD-04

**Stale/idle data handling (PITFALLS UX)**
- No sport_state for >3s while in SPORT mode → dim all values (mid-green)
- No sport_state for >10s → show "NO DATA" in place of the primary numeral (values retained dimmed elsewhere)
- `st: "finished"` → "FINISHED" banner over retained final values
- Not recording (never received tracking data this connection, or st idle) → "NOT RECORDING" hint + em-dash placeholders

**Tap-cycle reachability**
- SPORT is ALWAYS in the tap cycle: FULL_SCREEN → SMALL_CORNER → SPORT → FULL_SCREEN (locked HUD-03 wording; predictable tap count)
- Phone-set Mini modes unchanged: tap from MINI_BOTTOM/MINI_SPLIT returns to FULL_SCREEN (existing behavior preserved — Phase 2 SC#4)

### Claude's Discretion
- Exact font sizes/positions within the 480×640 (logical) canvas — follow HudView's existing paint/typeface conventions and drawFullScreenLayout structure
- Whether the pace secondary line hides when pace is 0/unset (<100m guard from phone) — pick the cleaner render
- Precise dim-color values from the existing green hierarchy

### Deferred Ideas (OUT OF SCOPE)
- Auto-switch to SPORT on recording start — deferred (tap-only per HUD-03)
- Configurable data fields on HUD — v1.x (FEATURES.md)
- Moving time on the HUD — summary-only in v1 (locked decision)
- Lap/splits display — v2
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| HUD-01 | Glasses display a new SPORT layout mode showing elapsed time, current speed/pace (selected units), distance traveled | `drawSportLayout` slot-in point identified (HudView.kt:128 dispatch); formatting spec written (Code Examples §4); geometry guidance for the verified 480×640 canvas |
| HUD-02 | Sport metrics update in real-time (~1Hz) by consuming `sport_state` | Verified: BluetoothClient.processMessage already fires `onStateUpdate(currentState)` after EVERY message (line 263), so replacing the no-op branch (line 258) with a `copy()` yields 1Hz redraws for free; staleness ticker design for redraws when messages STOP |
| HUD-03 | SPORT reachable via glasses tap; cycle Full → Corner → Sport → Full; Mini modes still phone-only and tap-return-to-Full | toggleLayout extension is 3 lines — BUT a critical pre-existing bug reverts tap-selected modes within ≤1s during streaming (Pitfall 1). Fix identified and required for HUD-03 to be verifiable |
| HUD-04 | Monochrome green rendering consistent with existing HUD | Exact palette constants documented (HudView.kt:22-26); paint conventions documented; SPORT has no map → no tilePaint needed; optional automated pixel-scan check |
</phase_requirements>

## Summary

Phase 2 is a small, sharply-bounded glasses-module phase: add one enum value, ~8 data-class fields, one draw method, one formatting helper object, a 1Hz redraw ticker, and replace a one-line no-op. Everything upstream is done and device-proven: the phone broadcasts `sport_state` at 1Hz (verified 60 msgs/60s on the OPPO in 01-07), the shared codec round-trips it (unit-tested), and the current glasses APK already decodes it into `ParsedMessage.SportState` and drops it.

The single most important research finding is a **pre-existing layout-mode ownership bug that will break HUD-03 exactly when it matters**: `toggleLayout()` mutates only `hudView.state`, but every incoming BT message wholesale-replaces `hudView.state` from `BluetoothClient.currentState` (preserving only battery/wifi). During recording, sport_state arrives at 1Hz — so a tap to SPORT would visibly revert to the previous mode within a second. The fix is to route the toggle through `BluetoothClient` so `currentState` (the replicated source of truth) carries the toggled mode. Without this fix the phase's success criteria cannot pass on device.

Second finding that removes planned work: because `HudState` is a Kotlin data class and every message handler uses `currentState.copy(...)`, the new sport fields are **automatically preserved through all existing copy sites** — no edits to the State/Settings/Route/Notification handlers are needed. The only structural changes are the enum/when sites (which the Kotlin 2.1 compiler will enumerate via exhaustiveness errors), the no-op branch replacement, and the toggle-ownership fix.

**Primary recommendation:** Implement in this order — (1) HudState: SPORT enum value + 8 fields + pure helpers (`applySportState`, `sportDisplayMode`) with JVM tests; (2) BluetoothClient: no-op replacement + `toggleLayout()` ownership fix; (3) HudView: `drawSportLayout` + mode-indicator label + SPORT-only 1Hz ticker via `View.postDelayed`; (4) on-device verification with the Phase 1 MockRouteFeeder harness, asserting the tap cycle survives an active 1Hz stream.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Metric computation (elapsed/moving/distance/speed/pace) | Phone (`ActivitySessionManager`) | — | Shipped in Phase 1; glasses NEVER recompute metrics, only display them [VERIFIED: codebase] |
| ~1Hz broadcast cadence | Phone (`HudStreamingService.sportStateTicker`) | — | Ticker-driven, not per-fix; keeps flowing with stale GPS [VERIFIED: HudStreamingService.kt:126-159] |
| Wire decode | Shared (`ProtocolCodec`) | — | Already decodes `sport_state` leniently with defaults; Phase 1 locked, do NOT modify [VERIFIED: ProtocolCodec.kt:252-262] |
| Message → state mapping | Glasses (`BluetoothClient.processMessage`) | `HudState.applySportState` (pure) | Replace the no-op branch; put the field mapping in a pure HudState function for JVM testability |
| Staleness detection | Glasses (`HudState.sportDisplayMode(nowMs)` — pure) | `HudView` ticker (redraw trigger) | Decision logic pure/testable; the View only supplies `SystemClock.elapsedRealtime()` and schedules redraws |
| SPORT rendering | Glasses (`HudView.drawSportLayout`) | — | Custom View onDraw, matching the 4 existing layout methods |
| Tap cycle / mode ownership | Glasses (`HudState.toggleLayout` + `BluetoothClient.toggleLayout`) | `HudActivity` (wiring only) | Mode must live in `BluetoothClient.currentState` or incoming messages revert it (Pitfall 1) |
| Unit selection (metric/imperial) | Glasses formatters reading `HudState.useImperial` | Phone settings message (source) | `useImperial` already flows via Settings; formatters just consume it [VERIFIED: BluetoothClient.kt:204] |

## Standard Stack

### Core

No new runtime libraries. This phase uses only APIs already present and device-proven in the glasses module:

| Library/API | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Android `View` + `Canvas`/`Paint` | framework (compileSdk 34) | `drawSportLayout` rendering | The glasses app's entire UI is one custom View; all 4 existing layouts use it [VERIFIED: HudView.kt] |
| `View.postDelayed`/`removeCallbacks` | framework | 1Hz staleness redraw ticker in SPORT mode | Codebase-consistent no-coroutines scheduling; phone's `sportStateTicker` uses the identical self-rescheduling shape [VERIFIED: HudStreamingService.kt:126-165, HudActivity.kt:53-157] |
| `android.os.SystemClock.elapsedRealtime()` | framework | `lastSportStateAtMs` monotonic timestamps | CONTEXT-locked ("timestamps via SystemClock where monotonic needed"); wall clock jumps would corrupt staleness math |
| shared `ProtocolCodec` / `SportStateMessage` | project(":shared") | already decoded message | Phase 1 deliverable, device-proven; zero codec changes this phase |

### Supporting (test-only, Wave 0)

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `junit:junit` | 4.13.2 | First glasses-module unit tests | Exact coordinate already used by `shared` and `phone` (Phase 1 pattern) [VERIFIED: shared/build.gradle.kts, phone/build.gradle.kts] |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `View.postDelayed` ticker in HudView | `Handler(Looper.getMainLooper())` ticker in HudActivity (like `exitWhenStoppedRunnable`) | Both codebase-consistent. In-View keeps the rendering concern in the renderer, auto-scopes to view lifecycle (`onDetachedFromWindow`), and needs no Activity plumbing. Recommend in-View. |
| Hand-rolled `formatElapsed` | `android.text.format.DateUtils.formatElapsedTime` | DateUtils is an android.jar stub in plain-JVM tests (throws) — it would make the formatter untestable. Hand-roll the 3-line formatter (see Don't Hand-Roll inversion note). |
| `HudState.applySportState` pure function | inline `copy()` in BluetoothClient like other branches | Inline matches existing branch style but is not JVM-testable (BluetoothClient needs Context/Log). The pure function on HudState costs nothing and gives HUD-02's mapping a unit test. Recommend the pure function. |

**Installation (Wave 0, glasses/build.gradle.kts dependencies block):**
```kotlin
testImplementation("junit:junit:4.13.2")
```

**Version verification:** `junit:junit:4.13.2` is not a new package — it is the exact coordinate already resolved and running green in `shared` and `phone` since Phase 1 (`:shared:testDebugUnitTest` re-run green during this research session on 2026-07-03). No registry lookup needed; Gradle resolves it from the same cache. [VERIFIED: codebase + local test run]

## Package Legitimacy Audit

| Package | Registry | Age | Downloads | Source Repo | slopcheck | Disposition |
|---------|----------|-----|-----------|-------------|-----------|-------------|
| junit:junit:4.13.2 | Maven Central | in-repo since Phase 1 | — | github.com/junit-team/junit4 | not run | Approved — identical coordinate already in shared/ and phone/ build files; no new supply-chain surface |

**Packages removed due to slopcheck [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none

*No new external packages are introduced this phase. slopcheck was not run because the only dependency addition reuses a coordinate already resolved, cached, and executing in this repository.*

## Architecture Patterns

### System Architecture Diagram

```
PHONE (done, Phase 1)                    BLUETOOTH SPP                GLASSES (Phase 2 work in [brackets])
─────────────────────                    ─────────────                ────────────────────────────────────
ActivitySessionManager ──MetricsSnapshot──► HudStreamingService
  (1Hz ticker, metrics)                     broadcastSportState()
                                                 │  {"t":"sport_state","v":1,"et","mt","d","cs","ap","st","sp"}
                                                 ▼  newline-delimited JSON, 1Hz while recording
                                        BluetoothClient.readFromSocket
                                                 │ readLine()
                                                 ▼
                                        ProtocolCodec.decode ──► ParsedMessage.SportState
                                                 │
                                                 ▼
                              processMessage: [replace no-op → currentState = currentState.applySportState(msg, now)]
                                                 │
                                                 ▼
                              onStateUpdate(currentState)  ◄──── [btClient.toggleLayout() also lands here]
                                                 │ runOnUiThread (HudActivity lambda,
                                                 │  preserves batteryLevel + wifiConnected only)
                                                 ▼
                              hudView.state = ... ──setter──► postInvalidate()
                                                 │            [+ start/stop 1Hz sportTick when mode==SPORT]
                                                 ▼
                              HudView.onDraw ── when(layoutMode) ──► [drawSportLayout(canvas, w, h)]
                                                 │                        │ reads HudState.sportDisplayMode(now)
                                                 ▼                        ▼ LIVE / STALE_DIM / STALE_NO_DATA /
                              drawStatusBar + drawModeIndicator            FINISHED / NOT_RECORDING
                              (drawn AFTER the branch — SPORT gets
                               the status strip + "[ SPORT ]" for free)

User tap (touchscreen single-tap) ──► HudView.gestureDetector.onSingleTapConfirmed
   ──► onLayoutToggle ──► HudActivity.toggleLayout() ──► [btClient.toggleLayout()]   ◄── THE Pitfall-1 fix
                                                          (was: hudView.state.toggleLayout(), reverted by next message)
```

Primary use case trace: phone records → 1Hz sport_state → BluetoothClient maps into HudState → HudActivity hands to HudView on main thread → onDraw renders SPORT metrics. When messages stop, the SPORT-only ticker keeps invalidating so `sportDisplayMode` transitions (dim → NO DATA) become visible without new input.

### Recommended Project Structure

```
glasses/src/main/java/com/rokid/hud/glasses/
├── HudState.kt          # +SPORT enum value; +8 fields; +applySportState(msg, nowMs); +sportDisplayMode(nowMs); toggleLayout 3-way
├── HudView.kt           # +drawSportLayout(canvas, w, h); +sport paints; +sportTick runnable + attach/detach; +"[ SPORT ]" label
├── BluetoothClient.kt   # replace SportState no-op; +fun toggleLayout()
├── HudActivity.kt       # toggleLayout() body: hudView.state.toggleLayout() → btClient.toggleLayout()
└── SportFormat.kt       # NEW: object SportFormat — pure Kotlin formatters (elapsed / pace / speed / moving dot)
glasses/src/test/java/com/rokid/hud/glasses/
├── HudStateTest.kt      # NEW: toggle cycle, applySportState mapping, sportDisplayMode thresholds, field preservation
└── SportFormatTest.kt   # NEW: formatter cases incl. boundaries
glasses/build.gradle.kts # +testImplementation junit
```

`shared/` is untouched this phase. `phone/` is untouched this phase (all phone-side work shipped in Phase 1).

### HudView Anatomy — exactly what the planner is extending [VERIFIED: HudView.kt, current working tree]

**Palette fields (lines 22-26):** `hudBrightGreen #00FF00`, `hudGreen #00CC00`, `hudDimGreen #008800`, `hudDarkGreen #004400`, `hudFaintGreen #003300`. CONTEXT locks the sport mapping: primary numeral `#00FF00`, secondary `#00CC00`, labels `#008800`. Dim-state color is discretion — recommend `hudDimGreen #008800` for dimmed values and `hudDarkGreen #004400` for dimmed labels (one step down each).

**Paint conventions:** pre-allocated `Paint` fields with `Paint(Paint.ANTI_ALIAS_FLAG).apply { color=…; typeface = Typeface.MONOSPACE; textSize = … }`; bold via `Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)`; the turn-alert block (lines 79-94) is the in-file exemplar of a pre-allocated paint group with a comment — mirror it with a `// Sport layout paints (pre-allocated, all green)` group. Existing draw code also mutates `p.color = …` per pass (drawStatusBar) and derives local paints via `Paint(textPaint).apply{…}` (drawMiniSplitLayout) — both acceptable; pre-allocation preferred for the per-second path.

**onDraw dispatch (lines 122-138), draw order matters:** bg fill → `when(state.layoutMode)` branch → `drawStatusBar` (top strip, y=14f: BT/WiFi/BAT/speed) → `drawModeIndicator` (top-right `"[ FULL ]"` etc., y=14f) → `drawTurnAlertOverlay` → closingMessage overlay. Consequences:
- SPORT gets the status strip and mode indicator **for free** — do not redraw them in `drawSportLayout`; just keep content below y≈24f (locked decision "thin top status strip retained" is satisfied by doing nothing).
- The turn-alert overlay will still pop over SPORT if the user is simultaneously navigating with `showTurnAlert` on — this is existing draw-order behavior and desirable (eyes-on-road); leave it.
- The status strip's small speed readout (`state.showSpeed`) will duplicate the SPORT numeral. Recommend leaving it (zero-diff, harmless); suppressing it in SPORT would touch the shared `drawStatusBar` path for cosmetic gain.

**Existing text sizes for scale calibration:** 11f status strip, 13-16f small text, 20f body, 22-28f emphasis, 64f turn-alert arrow. Canvas is **480×640 portrait — verified from actual device screencaps in `screenshots/glasses/*.png` (480×640 PNG), matching CONTEXT's "480×640 logical canvas"**. MONOSPACE digit advance ≈ 0.6×textSize, so a 120f primary ("24.3" ≈ 288px, "5:32" ≈ 360px) fits with margins. Suggested geometry (discretion — all positions as fractions of w/h like existing layouts):

| Row | Content | Paint | y (of 640) |
|-----|---------|-------|-----------|
| status strip | (existing, free) | — | 14 |
| label | "ELAPSED" 16f `#008800` | labels | ~120 |
| elapsed | H:MM:SS 44f `#00CC00` | secondary | ~165 |
| primary | speed or pace ~120f BOLD `#00FF00` + moving dot ●/○ | primary | ~330 (center) |
| primary unit | "km/h" / "min/km" 22f `#008800` | labels | ~375 |
| secondary | non-primary of speed/pace 26f `#00CC00` | secondary | ~440 |
| label | "DISTANCE" 16f `#008800` | labels | ~520 |
| distance | formatDistance 48f `#00CC00` | secondary | ~565 |

**formatDistance (line 581, private, reuse for the distance row):** metric ≥1000m → `"%.1f km"` else `"%.0f m"`; imperial ≥0.1mi → `"%.1f mi"` else `"%.0f ft"`; **returns `""` for <1m** — guard in SPORT: while tracking with distanceM<1 render "0 m" (or "0 ft"), and em-dash placeholders only in NOT_RECORDING (Pitfall 8).

**drawModeIndicator (line 561):** add `MapLayoutMode.SPORT -> "[ SPORT ]"`.

### Pattern 1: Sport-field mapping as a pure HudState function (the testable seam)

**What:** Put the SportStateMessage→HudState mapping and the staleness classification on HudState (pure Kotlin, no android.*), so the first glasses unit tests can cover HUD-02/HUD-03 logic on the JVM.
**When to use:** Replacing the no-op branch; anywhere staleness is decided.
**Why:** `SessionModels.kt` and `ProtocolCodecTest.kt` established the repo convention — "PURE Kotlin (no android.* imports) so plain-JVM unit tests can use these types directly"; the shared test KDoc confirms android.jar stubs throw in unit tests. [VERIFIED: SessionModels.kt:3-9, ProtocolCodecTest.kt:8-13]

### Pattern 2: Self-rescheduling ticker without coroutines

**What:** A `Runnable` that re-posts itself while a condition holds — the exact shape of the phone's `sportStateTicker` (`mainHandler.postDelayed(this, SPORT_STATE_TICK_MS)`) and HudActivity's `exitWhenStoppedRunnable` lifecycle (`removeCallbacks` on cancel). [VERIFIED: HudStreamingService.kt:126-165, HudActivity.kt:142-158]
**When to use:** SPORT-mode staleness redraws — while `layoutMode == SPORT`, invalidate every 1000ms so dimming appears even when no messages arrive.
**Lifecycle rules:** start on entering SPORT (state setter transition and `onAttachedToWindow`); stop via `removeCallbacks` on leaving SPORT (setter) and in `onDetachedFromWindow`. minSdk 28 means pre-attach `View.postDelayed` calls are queued until attach, but the setter-driven start makes this moot in practice [ASSUMED: framework behavior — see Assumptions A2].

### Pattern 3: Layout-mode ownership through BluetoothClient (the Pitfall-1 fix)

**What:** All layoutMode changes must land in `BluetoothClient.currentState`, because HudActivity's `onStateUpdate` lambda wholesale-replaces `hudView.state` from it (preserving only `batteryLevel`/`wifiConnected`). [VERIFIED: HudActivity.kt:89-95, BluetoothClient.kt:156-264]
**How:** add `fun toggleLayout()` to BluetoothClient that copies `currentState` through `HudState.toggleLayout()` and fires `onStateUpdate`; HudActivity calls it. Settings messages retain their existing power to override layout (phone mini-map toggle → MINI_*/FULL) — unchanged semantics.
**Threading note:** `currentState` is `@Volatile` with read-modify-write from the BT reader thread; the toggle adds a main-thread RMW. The worst-case interleaving loses one message's field update for one second — the same benign race class that already exists between `connectLoop`'s `copy(btConnected=…)` and `processMessage`. Match the existing pattern (no lock); if the planner wants zero-race, `@Synchronized` on the three `currentState` mutation sites is the minimal hardening. Do not introduce new concurrency primitives beyond what the codebase uses (`synchronized` exists in `sendTileRequest`).

### Anti-Patterns to Avoid

- **Preserving layoutMode in the HudActivity lambda** (adding `layoutMode = hudView.state.layoutMode` next to battery/wifi): breaks phone-driven MINI_BOTTOM/MINI_SPLIT settings, because Settings-message layout changes would be masked. The fix belongs in BluetoothClient, not the lambda.
- **Editing existing `copy()` handlers to "thread through" sport fields:** unnecessary — Kotlin data-class `copy()` preserves all unnamed fields. Enumerated proof in Common Pitfalls §2 table. Adding churn there is pure risk.
- **Computing metrics on glasses** (e.g., deriving pace from `cs`, or accumulating distance): phone is the single source of truth (STATE decision; 01-07 verified persisted-vs-broadcast agreement). Glasses format and display, nothing else.
- **`SystemClock`/`Log`/`DateUtils` inside HudState or SportFormat:** kills JVM testability (stubs throw). Pass `nowMs` as a parameter.
- **`input keyevent 66` (ENTER) to "tap" the glasses during verification:** KEYCODE_ENTER is wired to `shutdownApp()` — it kills the app. Use `input tap x y` single taps spaced ≥1s (double-tap is also shutdown). [VERIFIED: HudActivity.kt:160-167, 75-76]

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Periodic redraws | Timer/coroutine/thread-sleep loop | `View.postDelayed` self-rescheduling Runnable | Codebase has zero coroutines (locked constraint); Handler-family scheduling is the in-repo pattern proven on both apps |
| sport_state parsing | Any glasses-side JSON handling | `ProtocolCodec.decode` (already returns typed `SportStateMessage`) | Phase 1 locked + device-proven; the message arrives pre-parsed at the no-op branch |
| Distance display | New distance formatter | existing `HudView.formatDistance` | CONTEXT-locked ("existing formatDistance conventions"); imperial handling included |
| Unit conversion factors | New constants | the in-file constants `3.6f` (km/h), `2.23694f` (mph), `1.609344` (mi) | Exact values already used in drawStatusBar/speakInstruction — divergent constants would make the SPORT numeral disagree with the status strip |
| Tap detection | Key/touch handling changes | existing `GestureDetector` wiring (`onLayoutToggle`) | Single-tap→toggle, double-tap→shutdown already correct; Phase 2 changes only what toggle *does* |

**Inversion — DO hand-roll these two (they're 3-liners and libraries break testability):** `formatElapsed(ms)` (H:MM:SS) and `formatPace(msPerKm, imperial)` (M:SS + unit). `DateUtils.formatElapsedTime` is an android.jar stub in unit tests; java.time adds nothing for a modulo-arithmetic formatter.

**Key insight:** this phase's risk is not missing libraries — it's state-ownership and lifecycle details in a callback-heavy, no-framework codebase (R6 in the risk register). Every "build" decision should minimize new moving parts.

## Common Pitfalls

### Pitfall 1: Tap-selected layout mode reverts within ≤1s during streaming (CRITICAL — blocks HUD-03)
**What goes wrong:** User taps to SPORT while recording; the next BT message (sport_state at 1Hz, gps state at ~1Hz) snaps the display back to the previous mode.
**Why it happens:** `HudActivity.toggleLayout()` mutates only `hudView.state` (line 219-221). Every `processMessage` ends with `onStateUpdate(currentState)` (BluetoothClient.kt:263), and the HudActivity lambda replaces `hudView.state` with `newState.copy(batteryLevel=…, wifiConnected=…)` (lines 92-95) — layoutMode comes from `BluetoothClient.currentState`, which the tap never touched. Latent today (toggling during active streaming already reverts); Phase 2's guaranteed 1Hz stream makes it a hard requirement failure. [VERIFIED: code paths read end-to-end this session]
**How to avoid:** Pattern 3 — `btClient.toggleLayout()` mutating `currentState`. 
**Warning signs:** device test — tap to SPORT during mock recording, screencap 2s later still shows `[ SPORT ]`. If the fix is skipped, the screencap shows the old mode. Make this an explicit verification assertion.

### Pitfall 2: Planning unnecessary copy-site edits (inverse pitfall — the work that is NOT needed)
**What goes wrong:** Plans add "preserve sport fields" edits to every message handler, churning proven code.
**Why it happens:** Assumption that new fields need explicit threading. They don't: `copy(named-args-only)` preserves everything else. Full enumeration of every HudState construction site [VERIFIED]:

| Site | Kind | Action needed |
|------|------|---------------|
| BluetoothClient.kt:32 `currentState = HudState()` | fresh default | none — sport defaults (`lastSportStateAtMs=0`) mean NOT RECORDING, correct |
| BluetoothClient.kt:87/96 `copy(btConnected=…)` | copy | none — auto-preserved |
| BluetoothClient.kt State/Route/Step/Settings/StepsList branches | copy | none — auto-preserved |
| BluetoothClient.kt:185 `withNotification(...)` | copy via helper | none |
| **BluetoothClient.kt:258 SportState no-op** | — | **REPLACE: `currentState = currentState.applySportState(parsed.msg, SystemClock.elapsedRealtime())`** |
| HudView.kt:99 `state = HudState()` | fresh default | none |
| HudActivity.kt:92 lambda `newState.copy(battery, wifi)` | copy | none — sport fields ride in `newState` |
| HudActivity.kt:61 battery, :83 wifi, :229 closingMessage | copy of `hudView.state` | none |
| **HudActivity.kt:219 `toggleLayout()`** | copy of `hudView.state` | **CHANGE to `btClient.toggleLayout()` (Pitfall 1)** |

**How to avoid:** the plan should list exactly two behavioral edit sites in existing state plumbing (bolded above), plus the additive enum/when/draw code.

### Pitfall 3: Missed `when` branches after adding the SPORT enum value
**What goes wrong:** A dispatch site silently ignores SPORT (falls to an `else`) or fails to compile.
**Why it happens/how it saves you:** Kotlin ≥1.7 makes non-exhaustive `when` *statements* over enums a compile **error**, and the project is on Kotlin 2.1.0 — so the three exhaustive sites (`HudView.onDraw` :128, `HudView.drawModeIndicator` :561, `HudState.toggleLayout` :63) will fail compilation until updated [ASSUMED: error-vs-warning nuance — A1; the sites are enumerated here regardless, so the plan does not depend on it]. The one site the compiler will NOT flag: `BluetoothClient` Settings mapping (:196-201) has an `else` — it only ever produces MINI_*/FULL and needs **no** change (settings never select SPORT in v1).
**Warning signs:** grep `MapLayoutMode\.` after the enum edit; expect exactly the three updated sites plus the settings mapping.

### Pitfall 4: Staleness dims the FINISHED screen (threshold-precedence bug)
**What goes wrong:** After stop, the phone broadcasts one final `st:"finished"` sport_state and **stops the ticker** [VERIFIED: HudStreamingService.kt:426-435] — so "no message for >3s/10s" is the *designed* post-finish condition. Naive staleness would dim and then NO-DATA the FINISHED banner.
**How to avoid:** precedence ladder in `sportDisplayMode`: NOT_RECORDING (never received or `st=="idle"`) → FINISHED (`st=="finished"`, no staleness applied, retained values bright) → staleness only while `st=="tracking"` (>10s NO_DATA, >3s DIM, else LIVE). This exact ladder is JVM-testable with fabricated `nowMs`.
**Warning signs:** device test — stop recording; glasses must still show FINISHED + final values undimmed 15s later.

### Pitfall 5: Wall-clock timestamps for staleness
**What goes wrong:** `System.currentTimeMillis()` jumps with NTP/timezone; staleness spuriously fires or never fires.
**How to avoid:** `SystemClock.elapsedRealtime()` at the BluetoothClient call site only, stored in `lastSportStateAtMs`; HudView compares against the same clock at draw. CONTEXT-locked. Keep the clock OUT of HudState methods (parameter injection) for testability.

### Pitfall 6: Device verification taps triggering shutdown
**What goes wrong:** Two `input tap`s <~300ms apart register as a double-tap (`onDoubleTap → shutdownApp`), or someone uses `input keyevent 66` (KEYCODE_ENTER → shutdownApp). The HUD shows "Rokid Maps is closing" and the app dies mid-test.
**How to avoid:** single taps at (240,320), spaced ≥1s; never send ENTER. [VERIFIED: HudView.kt:106-116, HudActivity.kt:160-167] [ASSUMED: ~300ms double-tap window — A3]

### Pitfall 7: Status-strip collision and redundancy
**What goes wrong:** SPORT content drawn above y≈20 collides with the always-on status strip (drawn after the branch, y=14f).
**How to avoid:** start SPORT content below y≈24f (the geometry table does). Leave `drawStatusBar` untouched — its small speed readout duplicating the numeral is acceptable (see anatomy notes).

### Pitfall 8: `formatDistance` returns empty string below 1m
**What goes wrong:** At recording start the distance row renders blank instead of "0 m".
**How to avoid:** in `drawSportLayout`, special-case `distanceM < 1` while tracking → "0 m"/"0 ft"; em-dash placeholders belong only to NOT_RECORDING (CONTEXT).

### Pitfall 9: `ap == 0` sentinel (pace unset below 100m)
**What goes wrong:** Rendering "0:00/km" pace for the first 100m looks like data, not absence. Phone floors pace to 0 until 100m moving distance [VERIFIED: ActivitySessionManager.kt:372-373, `PACE_MIN_DISTANCE_M = 100.0`; pace derives from movingMs, not elapsed].
**How to avoid:** `formatPace(0, …) → "--:--"`. Discretion decision (CONTEXT allows hiding the secondary pace line instead): recommend showing "--:--" for the Run *primary* (a huge blank hole is worse) and **hiding** the *secondary* pace line when 0 (cleaner render for Ride). Use ASCII `--:--`/`--` rather than U+2014 em dash unless the device screencap confirms the glyph (MONOSPACE renders arrows fine, so coverage is likely, but ASCII is bulletproof) [ASSUMED: glyph coverage — A4].

### Pitfall 10: Locale-sensitive `String.format`
**What goes wrong:** `%.1f` yields "24,3" under comma-decimal locales.
**How to avoid:** Existing code (`formatDistance`, status bar) already uses locale-default `String.format` — the glasses device is Locale.US-adjacent and TTS is set to Locale.US. Match the existing convention (no `Locale.US` argument) for consistency; this is a pre-existing, device-accepted exposure, not a Phase 2 regression. Note only.

## Code Examples

All examples are prescriptive skeletons derived from the verified codebase conventions (source: this repository, current working tree).

### 1. HudState additions (pure Kotlin — the JVM-testable core)

```kotlin
// HudState.kt — enum gains one value
enum class MapLayoutMode {
    FULL_SCREEN,
    SMALL_CORNER,
    /** Tap-cycle: pure sport metrics, no map (HUD-01..04) */
    SPORT,
    MINI_BOTTOM,
    MINI_SPLIT
}

/** SPORT rendering states derived from session state + message age (Pitfall 4 ladder). */
enum class SportDisplayMode { NOT_RECORDING, FINISHED, LIVE, STALE_DIM, STALE_NO_DATA }

data class HudState(
    // ... existing 24 fields unchanged ...
    // Sport HUD (Phase 2) — fed only by sport_state messages; CONTEXT-locked names
    val elapsedMs: Long = 0L,
    val movingMs: Long = 0L,              // carried, not rendered in v1 (summary-only decision)
    val distanceM: Double = 0.0,
    val currentSpeedMps: Double = 0.0,
    val avgPaceMsPerKm: Long = 0L,        // 0 = unset (<100m phone-side floor)
    val sessionState: String = "idle",    // "idle" | "tracking" | "finished"
    val sport: String = "ride",           // "ride" | "run"
    val lastSportStateAtMs: Long = 0L     // SystemClock.elapsedRealtime() at receipt; 0 = never
) {
    companion object {
        const val MAX_NOTIFICATIONS = 8
        const val SPORT_STALE_DIM_MS = 3_000L
        const val SPORT_STALE_NODATA_MS = 10_000L
        const val MOVING_DOT_THRESHOLD_MPS = 0.7   // matches phone hysteresis enter threshold
    }

    /** Pure mapping for the BluetoothClient SportState branch. nowMs injected for testability. */
    fun applySportState(msg: SportStateMessage, nowMs: Long): HudState = copy(
        elapsedMs = msg.elapsedMs,
        movingMs = msg.movingMs,
        distanceM = msg.distanceM,
        currentSpeedMps = msg.currentSpeedMps,
        avgPaceMsPerKm = msg.avgPaceMsPerKm,
        sessionState = msg.sessionState,
        sport = msg.sport,
        lastSportStateAtMs = nowMs
    )

    /** Precedence ladder — FINISHED beats staleness; staleness only applies while tracking. */
    fun sportDisplayMode(nowMs: Long): SportDisplayMode = when {
        lastSportStateAtMs == 0L || sessionState == "idle" -> SportDisplayMode.NOT_RECORDING
        sessionState == "finished" -> SportDisplayMode.FINISHED
        nowMs - lastSportStateAtMs > SPORT_STALE_NODATA_MS -> SportDisplayMode.STALE_NO_DATA
        nowMs - lastSportStateAtMs > SPORT_STALE_DIM_MS -> SportDisplayMode.STALE_DIM
        else -> SportDisplayMode.LIVE
    }

    fun toggleLayout(): HudState = copy(
        layoutMode = when (layoutMode) {
            MapLayoutMode.FULL_SCREEN -> MapLayoutMode.SMALL_CORNER
            MapLayoutMode.SMALL_CORNER -> MapLayoutMode.SPORT      // was FULL_SCREEN
            MapLayoutMode.SPORT -> MapLayoutMode.FULL_SCREEN       // new
            MapLayoutMode.MINI_BOTTOM -> MapLayoutMode.FULL_SCREEN // unchanged
            MapLayoutMode.MINI_SPLIT -> MapLayoutMode.FULL_SCREEN  // unchanged
        }
    )
}
```

Note: unknown `sessionState` strings (future protocol values) fall through to the staleness branches → rendered as LIVE-with-aging, never a crash — acceptable lenient-decode posture matching `ProtocolCodec`.

### 2. BluetoothClient — the two edits

```kotlin
// processMessage(): replace line 258's no-op
is ParsedMessage.SportState ->
    currentState = currentState.applySportState(parsed.msg, SystemClock.elapsedRealtime())
// (onStateUpdate(currentState) at the end of processMessage already delivers it at 1Hz — HUD-02)

// New: layout ownership fix (Pitfall 1). Mirrors how connectLoop mutates currentState + notifies.
fun toggleLayout() {
    currentState = currentState.toggleLayout()
    onStateUpdate(currentState)
}
```

```kotlin
// HudActivity.kt — toggleLayout() body change (line 219-221)
private fun toggleLayout() {
    btClient.toggleLayout()   // was: hudView.state = hudView.state.toggleLayout()
}
```

(Add `import android.os.SystemClock` to BluetoothClient.)

### 3. HudView — SPORT ticker and dispatch

```kotlin
// dispatch (onDraw when-branch gains):
MapLayoutMode.SPORT -> drawSportLayout(canvas, w, h)
// drawModeIndicator gains:
MapLayoutMode.SPORT -> "[ SPORT ]"

// 1Hz staleness ticker — SPORT-only, self-rescheduling (phone sportStateTicker shape)
private val sportTick = object : Runnable {
    override fun run() {
        if (state.layoutMode == MapLayoutMode.SPORT) {
            invalidate()
            postDelayed(this, 1000L)
        }
    }
}

var state: HudState = HudState()
    set(value) {
        val enteredSport = value.layoutMode == MapLayoutMode.SPORT &&
                field.layoutMode != MapLayoutMode.SPORT
        val leftSport = value.layoutMode != MapLayoutMode.SPORT &&
                field.layoutMode == MapLayoutMode.SPORT
        field = value
        if (enteredSport) { removeCallbacks(sportTick); postDelayed(sportTick, 1000L) }
        if (leftSport) removeCallbacks(sportTick)
        postInvalidate()
    }

override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    if (state.layoutMode == MapLayoutMode.SPORT) { removeCallbacks(sportTick); postDelayed(sportTick, 1000L) }
}

override fun onDetachedFromWindow() {
    removeCallbacks(sportTick)
    super.onDetachedFromWindow()
}
```

Transition-edge start/stop (rather than unconditional remove+post per 1Hz message) keeps the tick phase independent of message arrival; either variant is acceptable — the self-rescheduling guard makes over-scheduling harmless.

### 4. SportFormat — pure formatters (new file, `object` per codebase convention)

```kotlin
package com.rokid.hud.glasses

/** Pure Kotlin (no android.*) so plain-JVM tests cover it — SessionModels.kt convention. */
object SportFormat {
    private const val MPS_TO_KMH = 3.6          // matches HudView.drawStatusBar
    private const val MPS_TO_MPH = 2.23694      // matches HudView.drawStatusBar
    private const val KM_TO_MI = 1.609344       // matches HudView.formatDistance/speakInstruction

    /** 945000 -> "0:15:45"; rolls over hours unbounded. */
    fun formatElapsed(ms: Long): String {
        val totalSec = ms / 1000
        return String.format("%d:%02d:%02d", totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)
    }

    /** ap=0 (unset, <100m) -> "--:--". 294000 metric -> "4:54". Imperial converts to per-mile. */
    fun formatPace(msPerKm: Long, imperial: Boolean): String {
        if (msPerKm <= 0L) return "--:--"
        val msPerUnit = if (imperial) (msPerKm * KM_TO_MI).toLong() else msPerKm
        val totalSec = msPerUnit / 1000
        return String.format("%d:%02d", totalSec / 60, totalSec % 60)
    }

    fun paceUnit(imperial: Boolean): String = if (imperial) "/mi" else "/km"

    /** 6.2 m/s metric -> "22.3". One decimal (numeral); status strip keeps its integer style. */
    fun formatSpeed(mps: Double, imperial: Boolean): String =
        String.format("%.1f", mps * if (imperial) MPS_TO_MPH else MPS_TO_KMH)

    fun speedUnit(imperial: Boolean): String = if (imperial) "mph" else "km/h"

    /** CONTEXT: ● moving (cs > ~0.7), ○ stopped. */
    fun movingDot(currentSpeedMps: Double): String =
        if (currentSpeedMps > HudState.MOVING_DOT_THRESHOLD_MPS) "●" else "○"
}
```

Worked test vectors (from the Phase 1 sample message, ProtocolCodecTest.kt:48-56): `et=945000 → "0:15:45"`; `ap=294000 → "4:54"/km`, imperial `→ "7:53"/mi` (473,147 ms/mi); `cs=6.2 → "22.3" km/h / "13.9" mph`; boundary `formatElapsed(3_599_000)="0:59:59"`, `formatElapsed(3_600_000)="1:00:00"`.

### 5. drawSportLayout skeleton (structure mirror of drawFullScreenLayout)

```kotlin
// ── Sport metrics: no map, huge numerals (HUD-01..04) ─────────────────
private fun drawSportLayout(canvas: Canvas, w: Float, h: Float) {
    val mode = state.sportDisplayMode(SystemClock.elapsedRealtime())
    val dimmed = mode == SportDisplayMode.STALE_DIM || mode == SportDisplayMode.STALE_NO_DATA
    val cx = w / 2f

    // choose colors per CONTEXT hierarchy; one step down when dimmed
    val primaryColor = if (dimmed) hudDimGreen else hudBrightGreen
    val secondaryColor = if (dimmed) hudDimGreen else hudGreen
    val labelColor = if (dimmed) hudDarkGreen else hudDimGreen

    when (mode) {
        SportDisplayMode.NOT_RECORDING -> { /* "NOT RECORDING" hint + "--" placeholders */ }
        SportDisplayMode.FINISHED -> { /* "FINISHED" banner + retained values, bright */ }
        else -> {
            // elapsed (top), primary numeral (center: ride→speed, run→pace),
            // NO DATA replaces primary when STALE_NO_DATA,
            // secondary line (the other of speed/pace), distance (bottom via formatDistance)
        }
    }
    // NOTE: drawStatusBar/drawModeIndicator drawn by onDraw AFTER this — do not duplicate.
}
```

Primary selection: `val isRide = state.sport != "run"` (decode defaults unknown sports to "ride" already; treat anything non-"run" as ride for the same lenient posture).

### 6. First glasses unit tests (Wave 0 shape, mirrors ProtocolCodecTest style)

```kotlin
// glasses/src/test/java/com/rokid/hud/glasses/HudStateTest.kt
class HudStateTest {
    @Test fun tapCycleFullCornerSportFull() {
        var s = HudState(layoutMode = MapLayoutMode.FULL_SCREEN)
        s = s.toggleLayout(); assertEquals(MapLayoutMode.SMALL_CORNER, s.layoutMode)
        s = s.toggleLayout(); assertEquals(MapLayoutMode.SPORT, s.layoutMode)
        s = s.toggleLayout(); assertEquals(MapLayoutMode.FULL_SCREEN, s.layoutMode)
    }
    @Test fun miniModesTapReturnToFull() { /* MINI_BOTTOM->FULL, MINI_SPLIT->FULL */ }
    @Test fun applySportStateMapsAllFieldsAndStampsReceipt() { /* sample msg, nowMs=12345 */ }
    @Test fun sportFieldsSurviveUnrelatedCopies() {
        val s = HudState().applySportState(sample, 111L).copy(btConnected = true)
            .withNotification(NotificationItem("t", "x", "p", 1L))
        assertEquals(111L, s.lastSportStateAtMs)  // proves Pitfall-2 claim
    }
    @Test fun stalenessLadder() {
        val s = HudState().applySportState(sample.copy(sessionState = "tracking"), 1_000L)
        assertEquals(SportDisplayMode.LIVE, s.sportDisplayMode(3_999L))          // 2999ms old
        assertEquals(SportDisplayMode.STALE_DIM, s.sportDisplayMode(4_001L))     // 3001ms
        assertEquals(SportDisplayMode.STALE_NO_DATA, s.sportDisplayMode(11_001L))
    }
    @Test fun finishedBeatsStaleness() { /* st=finished, now far ahead -> FINISHED */ }
    @Test fun neverReceivedIsNotRecording() { assertEquals(SportDisplayMode.NOT_RECORDING, HudState().sportDisplayMode(99_999L)) }
}
```

HudState/SportFormat reference no android.* classes, so — unlike `phone` — glasses tests need neither `isReturnDefaultValues` nor the org.json test artifact. Only `testImplementation("junit:junit:4.13.2")`.

## State of the Art

| Old Approach (pre-Phase 2) | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `ParsedMessage.SportState -> { }` no-op | `applySportState` copy into HudState | this phase | HUD-02 delivered by existing `onStateUpdate` plumbing |
| 2-mode tap cycle (Full↔Corner) | 3-mode cycle incl. SPORT | this phase | HUD-03; requires Pitfall-1 ownership fix to be observable |
| layoutMode owned ambiguously (hudView.state for taps, currentState for settings) | BluetoothClient.currentState owns layout | this phase | Fixes latent revert bug; settings override semantics unchanged |

**Deprecated/outdated:** nothing removed; shared protocol v:1 unchanged (STATE decision: version negotiation deferred).

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Kotlin ≥1.7 (project: 2.1.0) makes non-exhaustive `when` statements over enum subjects a compile error, so the 3 dispatch sites self-surface | Pitfall 3 | LOW — sites are explicitly enumerated in this doc; if it's only a warning, the plan's task list still covers all three |
| A2 | `View.postDelayed` before attach is queued until attach on API 26+ (minSdk 28) | Pattern 2 | LOW — ticker start is setter/attach-driven, so pre-attach posting doesn't occur in practice; `onAttachedToWindow` restart is the belt-and-braces |
| A3 | GestureDetector double-tap window ≈300ms (verification tap spacing) | Pitfall 6 | LOW — spacing taps ≥1s makes the exact window irrelevant |
| A4 | U+25CF/U+25CB (●/○) and em-dash render in the glasses MONOSPACE font | Metric arrangement / Pitfall 9 | LOW — arrows (←→↩⑂) already render on this device per existing HUD; verification screencap confirms; ASCII fallback ("*"/"o", "--") is a 1-line change |
| A5 | Glasses device (serial 1901092544802583) can be re-attached via adb for on-device SC verification | Environment / Validation | MEDIUM — without it, HUD SCs fall back to unit tests + a human-verify checkpoint on the physical device (Phase 1 precedent: both devices were authorized) |

## Open Questions

1. **Glasses adb availability at verification time**
   - What we know: the Rokid glasses (Android 12, serial 1901092544802583) were adb-authorized and driven in 01-07; today only the OPPO phone is attached.
   - What's unclear: whether the glasses will be plugged in when the execute phase reaches device verification.
   - Recommendation: plan the device-verification task with an explicit precondition check (`adb devices` must list both serials) and a `checkpoint:human-verify` fallback asking the user to connect the glasses.
2. **Mock-location app selection state on the OPPO**
   - What we know: 01-07 left "选择模拟位置信息应用" possibly still set to the debug phone app (clearing was a pending residual needing the unlocked phone).
   - What's unclear: current Developer-Options state.
   - Recommendation: verification task should assert the feeder actually produces fixes (logcat `MockRouteFeeder: Mock mode ON` + moving speed on the recording card) before running glasses assertions; if fixes don't flow, re-select the mock app in Developer Options (phone must be unlocked — human step).
3. **Secondary-line render when pace unset (declared discretion)**
   - Recommendation recorded in Pitfall 9: Run primary shows `--:--`; Ride secondary pace line hides while `ap==0`. Planner may adopt as-is.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| JDK 17 | gradle build/tests | ✓ | openjdk 17.0.19 at `/opt/homebrew/opt/openjdk@17` (NOT on PATH — must `export JAVA_HOME`) | — |
| Gradle wrapper | build/tests | ✓ (jar) | `gradle/wrapper/gradle-wrapper.jar` present; **no `gradlew` unix script** (only gradlew.bat) — use the GradleWrapperMain invocation below (Phase 1 canonical form) | — |
| adb | install/verify on device | ✓ | `/opt/homebrew/share/android-commandlinetools/platform-tools/adb` (NOT on PATH — use absolute path) | — |
| OPPO phone (3B164G01Y7L00000) | recording + mock feed | ✓ attached now | Android 16 / ColorOS | — |
| Rokid glasses (1901092544802583) | SPORT-mode screencaps, tap cycle | ✗ not currently attached | Android 12 | unit tests + human-verify checkpoint until re-attached (A5) |
| MockRouteFeeder harness | 1Hz metric feed | ✓ | `phone/src/debug/` receiver, exported, action `com.rokid.hud.phone.debug.MOCK_ROUTE` | real GPS walk-test (slow) |
| Baseline test suite | regression gate | ✓ | `:shared:testDebugUnitTest` re-run green during this research session | — |

**Canonical build/test invocation (verified green this session):**
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && cd /Users/bilhuang/Documents/rokid-maps && \
java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain <tasks> -q
```

**Missing dependencies with no fallback:** none.
**Missing dependencies with fallback:** glasses adb connection (fallback: JVM tests now, device checkpoint when attached).

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 4.13.2, plain-JVM unit tests (`testDebugUnitTest`), no instrumentation |
| Config file | per-module `build.gradle.kts`; **glasses module has NO test deps yet — Wave 0** |
| Quick run command | `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && cd /Users/bilhuang/Documents/rokid-maps && java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :glasses:testDebugUnitTest -q` |
| Full suite command | `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && cd /Users/bilhuang/Documents/rokid-maps && java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :shared:testDebugUnitTest :phone:testDebugUnitTest :glasses:testDebugUnitTest assembleDebug -q` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| HUD-01 | Sport fields map from message; formatters produce elapsed/pace/speed/distance strings (incl. `ap=0`, rollover, imperial) | unit | quick run (`HudStateTest`, `SportFormatTest`) | ❌ Wave 0 |
| HUD-01 | SPORT screen visually renders all metrics on device | manual-only (visual) + scripted screencap | `adb -s 1901092544802583 exec-out screencap -p > sport.png` — justification: Canvas output has no JVM-assertable surface | — |
| HUD-02 | 1Hz consumption: `applySportState` stamps receipt; staleness ladder boundaries (3s/10s/finished/never) | unit | quick run (`stalenessLadder`, `finishedBeatsStaleness`, `neverReceivedIsNotRecording`) | ❌ Wave 0 |
| HUD-02 | Live ~1Hz updates end-to-end; zero Unknown-message warnings | integration (device) | screencaps 5s apart show elapsed +≈5s; `adb -s <glasses> logcat -d -s HudBtClient \| grep -c "Unknown message"` == 0 for sport_state lines | harness ✅ (Phase 1) |
| HUD-03 | Cycle Full→Corner→Sport→Full; Mini→Full preserved; sport fields survive unrelated copies | unit | quick run (`tapCycleFullCornerSportFull`, `miniModesTapReturnToFull`, `sportFieldsSurviveUnrelatedCopies`) | ❌ Wave 0 |
| HUD-03 | Tap-selected SPORT survives an active 1Hz stream (Pitfall-1 fix proof) | integration (device) | tap → wait 2s → screencap shows `[ SPORT ]` while feeder runs | harness ✅ |
| HUD-04 | Only green-hierarchy colors used | unit-adjacent (code review) + device screencap; optional pixel scan asserting R==0∧B==0 across sport.png (SPORT draws no tiles, so pure-green-on-black holds exactly) | manual/optional script — justification: paint colors are compile-time constants | — |

### Sampling Rate
- **Per task commit:** quick run (`:glasses:testDebugUnitTest`; add `:glasses:compileDebugKotlin` on HudView-only tasks)
- **Per wave merge:** full suite command (all three modules + both APKs assemble)
- **Phase gate:** full suite green + on-device verification script below before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `glasses/build.gradle.kts` — add `testImplementation("junit:junit:4.13.2")` (no `isReturnDefaultValues`, no org.json needed — tests are pure Kotlin)
- [ ] `glasses/src/test/java/com/rokid/hud/glasses/HudStateTest.kt` — covers HUD-02 (staleness/mapping), HUD-03 (cycle)
- [ ] `glasses/src/test/java/com/rokid/hud/glasses/SportFormatTest.kt` — covers HUD-01 (formatting)

### On-Device Verification Plan (reuses Phase 1 harness — serials from 01-07)

`ADB=/opt/homebrew/share/android-commandlinetools/platform-tools/adb`; PHONE=3B164G01Y7L00000; GLASSES=1901092544802583. Precondition: both serials in `$ADB devices`; phone unlocked; mock-location app selected in Developer Options (Open Question 2).

1. **Deploy:** full-suite build, then `$ADB -s $PHONE install -r phone/build/outputs/apk/debug/phone-debug.apk` and `$ADB -s $GLASSES install -r glasses/build/outputs/apk/debug/glasses-debug.apk`.
2. **Start phone side:** launch app → Start streaming (`btnStart`) → pick sport (`btnSportRide`/`btnSportRun`) → Start Recording (`btnStartRecording`) — 01-07 drove these via screencap+`input tap`. Then feeder: `$ADB -s $PHONE shell am broadcast -n com.rokid.hud.phone/.MockRouteFeeder -a com.rokid.hud.phone.debug.MOCK_ROUTE --es cmd start --ei loops 3` (90 moving @5.5 m/s / 60 stationary / 60 moving, 1Hz).
3. **Glasses up:** `$ADB -s $GLASSES shell am start -n com.rokid.hud.glasses/.HudActivity`; confirm `BT:ON` via screencap.
4. **SC — tap cycle under load (HUD-03 + Pitfall-1):** three `input tap 240 320` spaced ≥2s, screencap after each: mode indicator sequence `[ CORNER ]` → `[ SPORT ]` → `[ FULL ]`. All while sport_state streams.
5. **SC — live updates (HUD-01/02):** in SPORT during a moving segment, screencaps at t and t+5s: elapsed advances ~5s; distance grows (~27m); Ride primary ≈ "19.8" km/h (5.5 m/s); moving dot ● during segment (i), ○ during segment (ii); logcat `-s HudBtClient` shows zero sport_state Unknown warnings (pre-existing empty-line keep-alive warnings are expected noise per 01-07).
6. **SC — units:** toggle imperial on phone settings → screencap: mph (Ride) or /mi pace (Run). Repeat one pass with Run selected → primary is pace `M:SS`, secondary is speed.
7. **SC — staleness:** while SPORT + tracking, `$ADB -s $PHONE shell am force-stop com.rokid.hud.phone` → glasses screencaps at +5s (values dimmed, `BT:--`) and +12s ("NO DATA" primary, dimmed values retained). Relaunch phone app afterward.
8. **SC — FINISHED precedence:** with fresh recording, stop it on the phone (Finish dialog) → glasses shows FINISHED banner + final values; screencap again ≥15s later — still FINISHED, not dimmed/NO DATA.
9. **SC — mini preserved:** phone mini-map toggle on → glasses `[ STRIP ]`; single tap → `[ FULL ]`.
10. **Teardown:** feeder `--es cmd stop`; stop recording/streaming; (residual from 01-07: clear mock-app selection when convenient).

## Security Domain

`security_enforcement: true`, ASVS L1. This phase adds no network calls, no auth, no storage, no new permissions, and no new packages; the glasses remain a display-only consumer of an already-shipped message.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | — (BT bonding is the existing trust boundary; unchanged) |
| V3 Session Management | no | — |
| V4 Access Control | no | — |
| V5 Input Validation | yes | `ProtocolCodec.decode` already lenient (`optXxx` + defaults; malformed → `ParsedMessage.Unknown`) [VERIFIED: ProtocolCodec.kt:252-267]. Glasses-side additions must stay total: unknown `sport` → treat as "ride"; unknown `sessionState` → staleness branch; no string is ever executed or used as a path/URL |
| V6 Cryptography | no | — (never hand-roll; none needed) |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Malformed/hostile sport_state from a bonded device | Tampering | Lenient typed decode + total rendering functions (no crash paths); display-only fields — no state machine on glasses acts on them |
| Location disclosure via new message | Information Disclosure | Non-issue by design: sport_state carries metrics only, never coordinates (threat T-04-01 comment upheld) [VERIFIED: HudStreamingService.kt:617-619] |
| Message flood → render thrash | DoS | Pre-existing surface: glasses `readLine()` has no length cap (phone side caps 1024B). Phase 2 adds no new commands; SPORT drops tile traffic entirely (no map). No change required; noted for the risk register |
| Debug harness in release | Elevation | MockRouteFeeder lives in `phone/src/debug/` — physically absent from release (T-07-01) [VERIFIED: debug manifest comment] |

## Sources

### Primary (HIGH confidence — read this session, current working tree)
- `glasses/src/main/java/com/rokid/hud/glasses/HudView.kt` — full file (palette, paints, dispatch, layouts, status bar, formatDistance, gesture wiring)
- `glasses/src/main/java/com/rokid/hud/glasses/HudState.kt` — full file (fields, toggleLayout, copy pattern)
- `glasses/src/main/java/com/rokid/hud/glasses/BluetoothClient.kt` — full file (no-op branch :258, onStateUpdate flow :263, currentState mutation sites)
- `glasses/src/main/java/com/rokid/hud/glasses/HudActivity.kt` — full file (state lambda :89-95, toggleLayout :219, KEYCODE_ENTER :160, handler patterns)
- `shared/src/main/java/com/rokid/hud/shared/protocol/{Messages,ProtocolCodec,ProtocolConstants}.kt` — SportStateMessage types/units, lenient decode, wire keys
- `phone/src/main/java/com/rokid/hud/phone/{SessionModels,ActivitySessionManager,HudStreamingService,MainActivity}.kt` — sessionState/sport value sets, pace floor (100m), ticker precedent, finished-broadcast-then-stop, recording UI ids
- `phone/src/debug/{AndroidManifest.xml, java/.../MockRouteFeeder.kt}` — harness trigger + track profile
- Build system: `shared|glasses|phone/build.gradle.kts`, Phase 1 PLAN files' `<automated>` commands; `:shared:testDebugUnitTest` executed green this session
- Device evidence: `.planning/phases/01-activity-recording-engine/01-07-SUMMARY.md` (1Hz schema on-device, serials, ColorOS notes); `screenshots/glasses/*.png` (480×640 measured)
- Environment probes this session: adb path + attached devices, JDK 17.0.19, wrapper jar, missing gradlew script

### Secondary (MEDIUM confidence)
- none required — no external libraries or services are involved in this phase

### Tertiary (LOW confidence / training-knowledge)
- Kotlin when-exhaustiveness error timing, View.postDelayed pre-attach queueing, GestureDetector double-tap window, glyph coverage — all tagged in Assumptions Log (A1-A4) with low blast radius

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — zero new runtime deps; test dep is a coordinate already in the repo
- Architecture: HIGH — every integration point read end-to-end; the one behavioral risk (layout revert) traced through actual code paths, not inferred
- Pitfalls: HIGH for the code-derived ones (1,2,4,7,8,9); MEDIUM for framework nuances (3,5,6 — assumption-tagged where relevant)
- Validation: HIGH — commands are the Phase 1 canonical form, baseline re-run green during research

**Research date:** 2026-07-03
**Valid until:** stable while the glasses module is untouched by other phases (Phases 3-5 are phone-only) — re-verify line numbers if any quick-task edits glasses files first
