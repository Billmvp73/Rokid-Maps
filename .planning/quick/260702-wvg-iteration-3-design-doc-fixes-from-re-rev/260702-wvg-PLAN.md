---
phase: quick-260702-wvg
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - .planning/ROADMAP.md
  - .planning/REQUIREMENTS.md
  - .planning/STATE.md
  - .planning/research/ARCHITECTURE.md
  - .planning/research/PITFALLS.md
  - .planning/research/FEATURES.md
  - .planning/research/SUMMARY.md
  - .planning/research/STACK.md
autonomous: true
requirements: [REC-04, AUTH-01]  # requirement-TEXT corrections only — docs task, no source code changes
must_haves:
  truths:
    - "ARCHITECTURE.md contains zero claims that Strava waypoints reuse the existing startNavigation() interface — both surviving lines (Internal Boundaries table, build-order Phase 4 wire bullet) name the new waypoint-accepting path added in Phase 4"
    - "ROADMAP.md Phase 1 SC#3 is testable on the actual test device (OPPO, 30-min screen-off); the 2-hour run is a STATE.md pre-release Pending Todo"
    - "Exactly one phase owns the NavigationManager steps/currentStepIndex race fix: Phase 4 scope item (b); Phase 1 SC#6 covers only new recording components' thread-safety and defers the pre-existing race to Phase 4"
    - "No doc instructs declaring OkHttp/Gson/logging-interceptor as new dependencies — all three W-b anchors (STACK Installation, SUMMARY stack bullet, ARCHITECTURE build-order bullet) say they are already explicitly declared in phone/build.gradle.kts, verify versions only"
    - "REC-04 has a single governing rule (0.7/0.3 m/s hysteresis, nominal 0.5 m/s) with the flat exclusion threshold removed; ROADMAP SC#2 cites the same hysteresis rule and keeps its phantom-distance intent"
    - "PITFALLS.md Pitfall 7 prevention #1 is scoped to sport_state-only versioning per ROADMAP Phase 1 SC#5; Pitfall 3 describes OsrmClient's real ≤500-point stride instead of the invented ~100-200 figure"
    - "FEATURES.md auto-pause Alternative cell matches the moving-time-summary-only STATE decision (elapsed time on HUD, both in phone summary)"
    - "STATE.md carries the Phase-4-ownership decision row, the extended Strava-scope todo, the new 2-hour pre-release todo, and the iteration-3 Last activity line"
  artifacts:
    - path: ".planning/ROADMAP.md"
      provides: "Phase 1 SC#2 hysteresis citation, SC#3 OPPO 30-min criterion, SC#6 Phase-4 race-fix deferral"
    - path: ".planning/REQUIREMENTS.md"
      provides: "REC-04 hysteresis-governs wording; AUTH-01 scope-confirmation parenthetical"
    - path: ".planning/STATE.md"
      provides: "New decision row (race fix owned by Phase 4), 2 todo changes, iteration-3 Last activity line"
    - path: ".planning/research/ARCHITECTURE.md"
      provides: "B1 waypoint-reuse corrections (lines ~497, ~558) + W-b dependency-claim reword (line ~553)"
    - path: ".planning/research/PITFALLS.md"
      provides: "Pitfall 7 prevention #1 v1-scope parenthetical; Pitfall 3 corrected waypoint-density figure"
    - path: ".planning/research/FEATURES.md"
      provides: "Auto-pause Alternative cell aligned with moving-time-summary-only decision"
    - path: ".planning/research/SUMMARY.md"
      provides: "Stack bullet reworded: deps already declared, verify versions only"
    - path: ".planning/research/STACK.md"
      provides: "Installation section reframed as current declarations (already present)"
  key_links:
    - from: ".planning/ROADMAP.md"
      to: ".planning/ROADMAP.md"
      via: "Phase 1 SC#6 defers the race fix to Phase 4 scope item (b), which still carries it"
      pattern: "steps/currentStepIndex"
    - from: ".planning/REQUIREMENTS.md"
      to: ".planning/ROADMAP.md"
      via: "same 0.7/0.3 hysteresis rule in REC-04 and Phase 1 SC#2"
      pattern: "0\\.7 m/s"
    - from: ".planning/ROADMAP.md"
      to: ".planning/STATE.md"
      via: "SC#3 points to the 2-hour pre-release validation todo"
      pattern: "2-hour"
    - from: ".planning/research/STACK.md"
      to: "phone/build.gradle.kts"
      via: "W-b claims match the actual declarations at lines 63-67 (read-only evidence, not modified)"
      pattern: "already explicitly declared"
---

<objective>
Apply the iteration-3 (final convergence) re-review #2 fixes to the design docs: 2 blockers (B1, B2) and 7 warnings (W-a, W-b, W-d, W-e, W-f, W-g, W-h), plus a STATE.md Last-activity update. All decisions are FINAL from a completed code-verified re-review — implement the edits exactly as specified below; do not re-litigate.

Purpose: Two stale waypoint-reuse lines survived iteration 2 and still contradict the locked Phase-4 via-point decision; Phase 1 SC#3 demands hardware the project does not have; the race fix is dual-owned by two phases; three docs instruct declaring dependencies that phone/build.gradle.kts already declares; REC-04 carries two conflicting speed thresholds. These would mislead phase planners and executors.

Output: 8 corrected doc files (.planning/*.md). No source or build-file changes.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
All anchors below were re-verified against the working tree on this planning pass (concurrent sessions have touched this repo). Line numbers are current as of planning; if any drift, locate by the quoted anchor string — every edit site has a unique quoted anchor.

Target files (do not read others):
@.planning/ROADMAP.md
@.planning/REQUIREMENTS.md
@.planning/STATE.md
@.planning/research/ARCHITECTURE.md
@.planning/research/PITFALLS.md
@.planning/research/FEATURES.md
@.planning/research/SUMMARY.md
@.planning/research/STACK.md

**CRITICAL grep-scope rule:** NEVER grep .planning/ recursively for stale phrases — this PLAN file itself quotes every stale phrase and would self-match. All verification greps are scoped to the exact target file, as written in each task's verify gate.

**READ-ONLY evidence (verified during planning — do NOT edit, do NOT re-read):**
phone/build.gradle.kts lines 63-67 already declare explicitly:
`com.squareup.okhttp3:okhttp:4.12.0`, `com.squareup.okhttp3:logging-interceptor:4.12.0`, `com.google.code.gson:gson:2.10.1` (plus retrofit 2.9.0 + converter-gson for the CXR SDK). This is the W-b ground truth.

**VERIFY-only findings (confirmed during planning, no edit needed):**
- W-a: ROADMAP.md line 64 Phase 4 scope item (b) already carries "— fix the known steps/currentStepIndex data race in code touched by this work". Leave untouched; Task 1's gate confirms it survives.
- W-b adjacent: SUMMARY.md line 11 ("Only 4 explicit production dependency declarations … all lightweight or already transitive") stays unchanged — it lists declarations the feature requires (which exist), not an instruction to add them. Only the line-16 Stack bullet is in scope.
- W-b adjacent: STACK.md Supporting Libraries table cells (lines ~25, ~27, "declare explicitly … to pin the version") stay unchanged — outside the three anchors named by the review. Only the Installation section is in scope.
- W-d adjacent: other "0.5 m/s" mentions (PITFALLS Pitfall 5, SUMMARY finding 5, ARCHITECTURE state-machine note, STATE decision row) stay unchanged — the new REC-04 wording retains 0.5 m/s as the *nominal* threshold, so they remain consistent.
</context>

<tasks>

<task type="auto">
  <name>Task 1: Fix core planning docs — ROADMAP.md (B2, W-a, W-d), REQUIREMENTS.md (W-d, W-h), STATE.md (B2, W-a, W-h, Last activity)</name>
  <files>.planning/ROADMAP.md, .planning/REQUIREMENTS.md, .planning/STATE.md</files>
  <action>
**ROADMAP.md — W-d (Phase 1 SC#2, line 25):** Replace
"2. Distance accumulation excludes GPS drift: accuracy >20m points are rejected from distance calculation; speed <0.5 m/s points are excluded from moving distance"
with
"2. Distance accumulation excludes GPS drift: accuracy >20m points are rejected from distance calculation; moving-state hysteresis (enter moving above 0.7 m/s, exit below 0.3 m/s — nominal 0.5 m/s threshold per REC-04) governs moving-distance accumulation, so no phantom distance accrues while stopped"
(keep the two-space list indent).

**ROADMAP.md — B2 (Phase 1 SC#3, line 26):** Replace
"3. Recording survives phone screen-off for at least 2 hours of continuous tracking on tested devices (Samsung, Xiaomi, Pixel minimum)"
with
"3. Recording survives phone screen-off for at least 30 minutes of continuous tracking on the project's test device (OPPO Find X9 Ultra / ColorOS — an aggressive battery-management OEM); a 2-hour pre-release validation run is tracked in STATE.md Pending Todos"

**ROADMAP.md — W-a (Phase 1 SC#6, line 29):** Replace
"6. NavigationManager data race fixed: `steps` and `currentStepIndex` made thread-safe (via `@Volatile`, `synchronized`, or `AtomicReference`)"
with
"6. New recording components own their mutable state with explicit thread-safety (ActivitySessionManager confines its state; @Volatile/synchronized where shared with the service); the pre-existing NavigationManager steps/currentStepIndex race is fixed in Phase 4 (scope item b), where that class is rebuilt"

**ROADMAP.md — W-a VERIFY only (line 64, Phase 4 GPX-strategy scope item b):** Confirm the fragment "— fix the known steps/currentStepIndex data race in code touched by this work" is still present. Do NOT edit that line.

**REQUIREMENTS.md — W-h (AUTH-01, line 12):** Change the fragment
"Required scopes: `read,activity:read_all,activity:write`. No PKCE"
to
"Required scopes: `read,activity:read_all,activity:write` (scope set to be confirmed against developers.strava.com during Phase 3 research — private-route listing may require read_all). No PKCE"
Rest of AUTH-01 unchanged.

**REQUIREMENTS.md — W-d (REC-04, line 34):** Replace the full bullet
"- [ ] **REC-04**: Speed filtering: points below 0.5 m/s are excluded from moving distance accumulation. Use a hysteresis band (start counting at >0.7 m/s, stop at <0.3 m/s) to prevent oscillation at the threshold boundary. Apply a 5-point moving-average filter on GPS speed values to reduce noise."
with
"- [ ] **REC-04**: Moving-state hysteresis governs distance accumulation: enter moving above 0.7 m/s, exit moving below 0.3 m/s (nominal 0.5 m/s threshold); distance accumulates only while in the moving state. Apply a 5-point moving-average filter on GPS speed values to reduce noise."
(The moving-average sentence is intentionally preserved — the fix resolves the flat-threshold-vs-hysteresis conflict, it does not drop the smoothing requirement.)

**STATE.md — Last activity (line 26):** Replace
"**Last activity:** 2026-07-03 -- Iteration-2 design-doc fixes applied (3 blockers + 10 warnings from fresh-eyes re-review)"
with
"**Last activity:** 2026-07-03 -- Iteration-3 design-doc fixes applied (2 blockers + 7 warnings from re-review #2)"

**STATE.md — W-a (Decisions table, currently ending at line 51):** Append row:
"| NavigationManager data-race fix owned by Phase 4 (not Phase 1) | The race lives in NavigationManager, which only Phase 4 modifies; fixing it in Phase 1 widens blast radius with no tests covering navigation | 2026-07-03 |"

**STATE.md — W-h (Pending Todos, line 66):** Replace
"- Verify Strava rate-limit figures at developers.strava.com during Phase 3 research"
with
"- Verify Strava rate-limit figures AND the OAuth scope set (private-route listing may require read_all) at developers.strava.com during Phase 3 research"

**STATE.md — B2 (Pending Todos, list currently ending at line 67):** Append bullet:
"- Pre-release: 2-hour screen-off recording validation on the OPPO test phone"
(Leave the existing 30-min OEM verification todo at line 64 untouched — it is the Phase 1 SC check; the new bullet is the separate pre-release run.)
  </action>
  <verify>
    <automated>! grep -q 'Samsung, Xiaomi, Pixel' .planning/ROADMAP.md && grep -q 'OPPO Find X9 Ultra' .planning/ROADMAP.md && ! grep -q 'NavigationManager data race fixed' .planning/ROADMAP.md && grep -q 'fixed in Phase 4 (scope item b)' .planning/ROADMAP.md && grep -q 'fix the known steps/currentStepIndex data race' .planning/ROADMAP.md && ! grep -q 'speed <0.5 m/s points are excluded' .planning/ROADMAP.md && grep -q 'nominal 0.5 m/s threshold per REC-04' .planning/ROADMAP.md && grep -q 'Moving-state hysteresis governs distance accumulation' .planning/REQUIREMENTS.md && ! grep -q 'Use a hysteresis band' .planning/REQUIREMENTS.md && grep -q '5-point moving-average filter' .planning/REQUIREMENTS.md && grep -q 'private-route listing may require read_all' .planning/REQUIREMENTS.md && grep -q 'owned by Phase 4 (not Phase 1)' .planning/STATE.md && grep -q 'Pre-release: 2-hour screen-off recording validation' .planning/STATE.md && grep -q 'rate-limit figures AND the OAuth scope set' .planning/STATE.md && grep -q 'Iteration-3 design-doc fixes applied' .planning/STATE.md && ! grep -q 'Iteration-2 design-doc fixes applied' .planning/STATE.md && echo T1-PASS</automated>
  </verify>
  <done>ROADMAP.md Phase 1 SC#2 cites the hysteresis rule, SC#3 targets the OPPO 30-min run with the 2-hour run deferred to STATE.md, SC#6 defers the race fix to Phase 4 while scope item (b) still carries it. REC-04 has a single governing hysteresis rule with the moving-average sentence intact; AUTH-01 carries the scope-confirmation parenthetical. STATE.md has the new decision row, both todo changes, and the iteration-3 Last activity line. T1-PASS prints.</done>
</task>

<task type="auto">
  <name>Task 2: Fix research docs — ARCHITECTURE.md (B1, W-b), PITFALLS.md (W-e, W-f), FEATURES.md (W-g), SUMMARY.md (W-b), STACK.md (W-b)</name>
  <files>.planning/research/ARCHITECTURE.md, .planning/research/PITFALLS.md, .planning/research/FEATURES.md, .planning/research/SUMMARY.md, .planning/research/STACK.md</files>
  <action>
**ARCHITECTURE.md — B1a (Internal Boundaries table, line 497):** In the row "`StravaRouteImporter` -> `NavigationManager`", replace the Notes cell
"`startNavigation(waypoints)` — same interface as OSRM routes"
with
"new waypoint-accepting NavigationManager path (added in Phase 4 — existing `startNavigation()` is destination-only)"
(First two cells of the row unchanged.)

**ARCHITECTURE.md — B1b (build order Phase 4, line 558):** Replace
"- Wire route selection -> `NavigationManager.startNavigation()` with GPX waypoints"
with
"- Wire route selection -> the new waypoint-accepting NavigationManager path (OSRM via-point route + steps; Phase 4 adds it)"

**ARCHITECTURE.md — W-b (build order Phase 4, line 553):** Replace
"- Declare OkHttp + Gson explicitly in the phone module (already transitive via the CXR SDK; no Retrofit — see STACK.md rationale)"
with
"- OkHttp, logging-interceptor, and Gson are already explicitly declared in phone/build.gradle.kts — verify versions only; no build changes needed (no Retrofit for Strava — see STACK.md rationale)"

**PITFALLS.md — W-e (Pitfall 7 prevention #1, line 239):** Append to the end of the sentence "This is a one-time addition but prevents silent drift." the parenthetical:
" (v1 scope per ROADMAP Phase 1 SC#5: the version field ships on sport_state only; full-protocol version negotiation is deferred)"
Do not change the rest of the prevention item.

**PITFALLS.md — W-f (Pitfall 3 "Why it happens", line 95):** In the sentence opening the paragraph, replace the fragment
"was designed for OSRM-generated routes with ~100-200 well-spaced waypoints and no duplicate segments."
with
"was designed for OSRM-generated routes with a few hundred well-spaced waypoints (OsrmClient strides route geometry down to ≤500 points; turn steps are far fewer) and no duplicate segments."
(Leave line 103's "Target ~100-200 output points" untouched — that is the downsampling target recommendation, not the design-claim being corrected.)

**FEATURES.md — W-g (Anti-Features table, auto-pause row, line 47):** Replace the Alternative cell
"Manual pause via glasses touchpad or phone. Moving time vs. elapsed time are both tracked; display moving time as primary metric."
with
"Manual pause via glasses touchpad or phone. Both moving and elapsed time are tracked; v1 shows elapsed time on the HUD and both in the phone summary (moving time is summary-only in v1 per STATE decision)."
(Other cells of the row unchanged.)

**SUMMARY.md — W-b (Stack section bullet, line 16):** Replace
"- **No new libraries needed beyond what's already on the classpath.** OkHttp 4.12.0 and Gson 2.10.1 are already transitive deps via the Rokid CXR SDK. Explicitly declare them to pin versions."
with
"- **No new libraries needed beyond what's already on the classpath.** OkHttp 4.12.0, logging-interceptor 4.12.0, and Gson 2.10.1 are already explicitly declared in phone/build.gradle.kts — verify versions only; no build changes needed."
(Leave line 11's Executive Summary sentence unchanged, per the VERIFY-only note.)

**STACK.md — W-b (Installation section):** Two edits; keep the code block itself (all declaration lines unchanged):
1. Replace the intro line 42
"Add these to the `phone/` module's `build.gradle.kts`:"
with
"OkHttp, logging-interceptor, and Gson are already explicitly declared in phone/build.gradle.kts — verify versions only; no build changes needed for them. The only new additions to the `phone/` module's `build.gradle.kts` are security-crypto and browser:"
2. Replace the first comment inside the code block (line 45)
"// Explicit declarations (already transitive, pinning for version control)"
with
"// Current declarations (already present in phone/build.gradle.kts — verify versions only)"

Do NOT touch phone/build.gradle.kts (read-only evidence) and do NOT touch any other line in these five files.
  </action>
  <verify>
    <automated>! grep -q 'same interface as OSRM routes' .planning/research/ARCHITECTURE.md && grep -q 'destination-only' .planning/research/ARCHITECTURE.md && ! grep -q 'with GPX waypoints' .planning/research/ARCHITECTURE.md && grep -q 'Phase 4 adds it' .planning/research/ARCHITECTURE.md && ! grep -q 'Declare OkHttp + Gson explicitly' .planning/research/ARCHITECTURE.md && grep -q 'already explicitly declared in phone/build.gradle.kts' .planning/research/ARCHITECTURE.md && grep -q 'version field ships on sport_state only' .planning/research/PITFALLS.md && ! grep -q '100-200 well-spaced waypoints' .planning/research/PITFALLS.md && grep -q 'strides route geometry down to' .planning/research/PITFALLS.md && ! grep -q 'display moving time as primary metric' .planning/research/FEATURES.md && grep -q 'moving time is summary-only in v1' .planning/research/FEATURES.md && ! grep -q 'Explicitly declare them to pin versions' .planning/research/SUMMARY.md && grep -q 'already explicitly declared in phone/build.gradle.kts' .planning/research/SUMMARY.md && ! grep -q 'Add these to the' .planning/research/STACK.md && ! grep -q 'already transitive, pinning for version control' .planning/research/STACK.md && grep -q 'already explicitly declared in phone/build.gradle.kts' .planning/research/STACK.md && git diff --quiet phone/build.gradle.kts && echo T2-PASS</automated>
  </verify>
  <done>ARCHITECTURE.md's two surviving waypoint-reuse lines name the new Phase-4 waypoint-accepting path, and its build-order bullet (like SUMMARY.md's stack bullet and STACK.md's Installation section) states the deps are already explicitly declared — verify versions only. PITFALLS.md Pitfall 7 prevention #1 carries the sport_state-only v1 scope; Pitfall 3 cites the real ≤500-point stride. FEATURES.md auto-pause alternative matches the summary-only moving-time decision. phone/build.gradle.kts is untouched. T2-PASS prints.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| None crossed | Docs-only change (.planning/*.md); no code, no build files, no dependencies, no external input processed |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-quick-wvg-01 | Tampering | Planning docs | accept | No package installs, no source or build-file changes; git history provides integrity/rollback |
</threat_model>

<verification>
Both task gates pass (T1-PASS, T2-PASS). Cross-file consistency spot-checks, each scoped to a single file (NEVER grep .planning/ recursively — this plan quotes the stale phrases):
- grep -q 'enter moving above 0.7 m/s' .planning/REQUIREMENTS.md && grep -q 'enter moving above 0.7 m/s' .planning/ROADMAP.md (REC-04 and SC#2 cite the same hysteresis rule)
- grep -c 'waypoint-accepting NavigationManager path' .planning/research/ARCHITECTURE.md returns >= 3 (the two B1 fixes join the two already-correct mentions)
- git diff --stat shows only the 8 target docs modified (plus this quick-task directory); phone/build.gradle.kts absent from the diff
</verification>

<success_criteria>
- Both blockers (B1 x2 lines, B2) and all 7 warnings (W-a, W-b x3 docs, W-d x2 docs, W-e, W-f, W-g, W-h x2 docs) applied exactly as specified; the VERIFY-only items confirmed unchanged
- STATE.md gains the Phase-4-ownership decision row, the extended scope todo, the 2-hour pre-release todo, and the iteration-3 Last activity line
- No source or build files touched; only the 8 listed docs modified
- All automated grep gates pass
</success_criteria>

<output>
Create `.planning/quick/260702-wvg-iteration-3-design-doc-fixes-from-re-rev/260702-wvg-SUMMARY.md` when done.
Commit all 8 modified docs + this quick-task directory with message: "docs(quick): iteration-3 design-doc fixes from re-review #2 (2 blockers + 7 warnings)"
</output>
