---
phase: quick-260702-w4n
plan: 01
subsystem: planning-docs
tags: [docs, design-review, strava, sport-hud]
requires: []
provides:
  - "Design docs consistent with locked requirements (REC-01 opt-in recording, REC-07 sport_state naming, HUD-03 tap cycle, UPL-01 phone-side summary)"
  - "AUTH-01 cites the correct Strava mobile authorization endpoint"
  - "PITFALLS.md models match verified code behavior (forward-only step index, existing unknown-message logging)"
affects: [phase-1, phase-2, phase-3, phase-4, phase-5]
tech-stack:
  added: []
  patterns: []
key-files:
  created: []
  modified:
    - .planning/research/ARCHITECTURE.md
    - .planning/research/PITFALLS.md
    - .planning/research/SUMMARY.md
    - .planning/REQUIREMENTS.md
    - .planning/ROADMAP.md
    - .planning/STATE.md
    - .planning/PROJECT.md
    - CLAUDE.md
key-decisions:
  - "No activity_summary glasses message in v1; activity summary is phone-side UI (UPL-01); sport_state carries session state as a field"
  - "sport_state carries protocol version field v:1; full-protocol version negotiation deferred"
duration: 11m
completed: 2026-07-03
---

# Quick Task 260702-w4n: Iteration-2 Design-Doc Fixes Summary

**One-liner:** Applied all 3 blockers + 10 warnings from the code-verified fresh-eyes re-review — recording is opt-in per REC-01, SportState naming replaces ActivityMetrics everywhere (including split-line ASCII-box tokens), AUTH-01 cites oauth/mobile/authorize, and PITFALLS.md now models actual NavigationManager/BluetoothClient behavior.

## Fix Dispositions

| Fix | File(s) | Disposition |
|-----|---------|-------------|
| B1a | ARCHITECTURE.md build-order bullet | EDIT applied — recording start/stop is user-initiated (REC-01 opt-in); do NOT wire NavigationManager lifecycle; arrival surfaces stop prompt (auto-stop deferred to v1.x) |
| B1b | ARCHITECTURE.md Flow 4 head (2 lines) | EDIT applied — stop is user action only; arrival may surface a stop prompt |
| B1c | ARCHITECTURE.md system-overview edge label | EDIT applied — "recording is user-initiated (opt-in, REC-01)", border re-padded |
| B1d | ARCHITECTURE.md Flow 3 head | VERIFY-only — confirmed unchanged ("manual opt-in, independent of navigation" + REC-01 note intact) |
| B2 | REQUIREMENTS.md AUTH-01 | EDIT applied — endpoint now `https://www.strava.com/oauth/mobile/authorize` (matches PITFALLS.md Integration Gotchas) |
| B3a | PITFALLS.md Pitfall 3 why-bullets | EDIT applied — verified forward-only 150m step advancement (NavigationManager.kt:72-101); closest-point only in off-route detection (NavigationManager.kt:144-147); risks reframed as rapid multi-step advancement + wrong-pass off-route matching |
| B3b | PITFALLS.md Pitfall 3 prevention #3 | EDIT applied — preserve/extend EXISTING forward-only index behavior |
| B3c | PITFALLS.md Pitfall 7 | EDIT applied — unknown messages already logged (BluetoothClient.kt:258-259); real gaps: no version negotiation + silent wrong-default field decodes; prevention #2 now says keep/extend existing warning |
| W1 | ARCHITECTURE.md SportState renames | EDIT applied — 10 single-line ActivityMetricsMessage, 3 ParsedMessage.ActivityMetrics, broadcastActivityMetrics, MessageType.ACTIVITY_METRICS, split-line box tokens (box interior rewritten at uniform 92 cols), reconciliation note now "per REC-07". `sport_metrics` token: VERIFY-only, zero occurrences confirmed |
| W2 | ARCHITECTURE.md build order Phase 3 | EDIT applied — SPORT mode reachable via glasses tap cycle only (Full → Corner → Sport → Full) per HUD-03; auto-switch deferred |
| W3 | PITFALLS.md debt table | EDIT applied — use Gson (transitive via CXR SDK, per STACK.md) with typed data classes; do NOT add Moshi/kotlinx.serialization |
| W4 | ARCHITECTURE.md box + SUMMARY.md (2 lines) | EDIT applied — SessionState message marked NOT in v1 (state rides in sport_state); SUMMARY.md describes exactly 1 new message type, no activity_summary |
| W5 | REQUIREMENTS.md UPL-01 + ROADMAP.md Phase 5 SC#1 | EDIT applied — both trigger on recording stop, not navigation end |
| W6a | ROADMAP.md Phase 3 | EDIT applied — Delivers line added: OkHttp Strava API client + token authenticator, proven via authenticated GET /athlete |
| W6b | ROADMAP.md Phase 5 depends-on | EDIT applied — Phase 4 builds route endpoints (list, GPX export) on the Phase 3 client |
| W7 | ROADMAP.md Phase 1 SC#5 | EDIT applied — versioning scoped to the new sport_state message; existing-set versioning explicitly deferred |
| W8 | STATE.md Pending Todos | EDIT applied — OPPO ACCESS_BACKGROUND_LOCATION validation todo added; Risk Register row R8 untouched (VERIFY) |
| W9 | CLAUDE.md | EDIT applied — "12 message types:" (the 12-item list was already correct) |
| W10 | PROJECT.md | EDIT applied — STRA-01..09 superseded-by note inserted before Out of Scope |
| Decisions | STATE.md Decisions table | EDIT applied — 2 rows added (no activity_summary glasses message; sport_state-only versioning), both dated 2026-07-03 |
| Last activity | STATE.md | EDIT applied — "2026-07-03 -- Iteration-2 design-doc fixes applied (3 blockers + 10 warnings from fresh-eyes re-review)" |

## Task Commits

| Task | Commit | Files |
|------|--------|-------|
| 1 — ARCHITECTURE.md (B1, W1, W2, W4) | cbe61a6 | .planning/research/ARCHITECTURE.md |
| 2 — PITFALLS/SUMMARY/REQUIREMENTS (B3, W3, W4, B2, W5) | 53d78b7 | .planning/research/PITFALLS.md, .planning/research/SUMMARY.md, .planning/REQUIREMENTS.md |
| 3 — ROADMAP/STATE/PROJECT/CLAUDE (W5-W10, decisions) | 480d1e5 | .planning/ROADMAP.md, .planning/PROJECT.md, CLAUDE.md (STATE.md edited but intentionally left uncommitted for the orchestrator) |

## Verification

- T1-PASS, T2-PASS, T3-PASS (all automated gates, run from worktree root)
- Cross-file: `oauth/mobile/authorize` present in both REQUIREMENTS.md and PITFALLS.md; `sport_state` count in ARCHITECTURE.md = 4 (>= 4); diff spans exactly the 8 target files
- Shared-module protocol box re-verified at uniform 92 display columns after rewrite

## Deviations from Plan

None - plan executed exactly as written. (The requirements frontmatter `[AUTH-01, UPL-01]` covers requirement-TEXT corrections only; the requirements themselves remain unimplemented and their checkboxes were deliberately left unchecked.)

## Known Stubs

None — docs-only change.

## Self-Check: PASSED
