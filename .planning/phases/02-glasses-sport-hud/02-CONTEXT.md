# Phase 2: Glasses Sport HUD - Context

**Gathered:** 2026-07-03
**Status:** Ready for planning
**Mode:** Smart discuss (autonomous — recommendations auto-accepted per user authorization)

<domain>
## Phase Boundary

Glasses display real-time sport metrics during activity recording. Delivers: a new SPORT layout mode in HudView consuming the `sport_state` message the phone already broadcasts (proven on-device in Phase 1), tap-cycle integration (Full → Corner → Sport → Full), and monochrome-green rendering consistent with the existing HUD. Requirements: HUD-01..HUD-04. Phone-side work shipped in Phase 1; Strava is Phases 3-5.

</domain>

<decisions>
## Implementation Decisions

### SPORT layout composition
- Pure metrics screen — huge numerals, NO map in SPORT mode (map modes are one tap away; recording continues regardless of layout; low-res monochrome favors big type)
- Thin top status strip retained (existing BT/battery strip style) so connectivity stays visible

### Metric arrangement (resolves the STATE pending todo "Sport HUD layout design")
- Sport-aware primary metric from sport_state's `sp` field: Ride → current speed (km/h or mph per imperial toggle) as the huge center numeral; Run → pace (min/km or min/mi) as the huge center numeral
- Secondary rows: elapsed time (top, HH:MM:SS), distance (bottom, existing formatDistance conventions)
- The non-primary of speed/pace shows as a small secondary line
- Moving-state indicator: small ● when moving (derived: cs above ~0.7), hollow ○ when stopped — subtle, no text churn
- Existing green shade hierarchy only (#00FF00 primary numeral, #00CC00 secondary, #008800 labels) — HUD-04

### Stale/idle data handling (PITFALLS UX)
- No sport_state for >3s while in SPORT mode → dim all values (mid-green)
- No sport_state for >10s → show "NO DATA" in place of the primary numeral (values retained dimmed elsewhere)
- `st: "finished"` → "FINISHED" banner over retained final values
- Not recording (never received tracking data this connection, or st idle) → "NOT RECORDING" hint + em-dash placeholders

### Tap-cycle reachability
- SPORT is ALWAYS in the tap cycle: FULL_SCREEN → SMALL_CORNER → SPORT → FULL_SCREEN (locked HUD-03 wording; predictable tap count)
- Phone-set Mini modes unchanged: tap from MINI_BOTTOM/MINI_SPLIT returns to FULL_SCREEN (existing behavior preserved — Phase 2 SC#4)

### Claude's Discretion
- Exact font sizes/positions within the 480×640 (logical) canvas — follow HudView's existing paint/typeface conventions and drawFullScreenLayout structure
- Whether the pace secondary line hides when pace is 0/unset (<100m guard from phone) — pick the cleaner render
- Precise dim-color values from the existing green hierarchy

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- `HudView.kt` — existing layout dispatch (`when(layoutMode)` at ~129), text paints, green ColorMatrix, `formatDistance` (~581), status strip renderer; add `drawSportLayout(canvas, w, h)`
- `HudState.kt` — `MapLayoutMode` enum (add SPORT), `toggleLayout()` (~63: FULL↔CORNER today; extend to 3-way cycle), immutable copy() state pattern; add sport metric fields (elapsedMs, movingMs, distanceM, currentSpeedMps, avgPaceMsPerKm, sessionState, sport, lastSportStateAtMs)
- `BluetoothClient.kt` — `processMessage()` when-branch: replace the Phase-1 no-op `is ParsedMessage.SportState -> { }` with HudState updates (the message is already decoded — Phase 1 shipped the codec + broadcast, device-proven at 1Hz)
- `HudActivity.kt` — touchpad KEYCODE_ENTER handling that calls toggleLayout (single-tap cycle; double-tap = shutdown, unchanged)

### Established Patterns
- Custom View onDraw rendering, `postInvalidate()` on state set — no XML layouts on glasses
- Green-scale only: #00FF00 / #00CC00 / #008800 / #004400 / #003300
- Imperial/metric via existing settings message (`useImperial` in HudState)
- No coroutines; timestamps via SystemClock where monotonic needed

### Integration Points
- sport_state fields (phone side, locked, device-verified): t/v/et/mt/d/cs/ap/st/sp — decode already exists in shared ProtocolCodec
- Staleness: track `lastSportStateAtMs = SystemClock.elapsedRealtime()` on each SportState message; HudView compares at draw time (a 1Hz invalidate ticker may be needed while in SPORT mode so dimming appears without new messages)
- Glasses tests: glasses module has NO test infra yet — HudState.toggleLayout() and sport-field copy logic are JVM-testable if tests are added (shared module test pattern from Phase 1 applies)

</code_context>

<specifics>
## Specific Ideas

- Verification: on-device via adb — screencap the glasses in SPORT mode while the phone records with the mock feeder (both devices already authorized; Phase 1 harness reusable); logcat must show zero Unknown-message warnings for sport_state
- The existing glasses APK already compiles the SportState variant (Phase 1 no-op branch) — Phase 2 replaces the no-op with consumption

</specifics>

<deferred>
## Deferred Ideas

- Auto-switch to SPORT on recording start — deferred (tap-only per HUD-03)
- Configurable data fields on HUD — v1.x (FEATURES.md)
- Moving time on the HUD — summary-only in v1 (locked decision)
- Lap/splits display — v2

</deferred>

---

*Phase: 02-glasses-sport-hud*
*Context gathered: 2026-07-03 via autonomous smart discuss*
