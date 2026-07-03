---
phase: 02-glasses-sport-hud
plan: 04
status: complete
requirements-completed: [HUD-01, HUD-02, HUD-03, HUD-04]
key_files:
  created: []
  modified: []
---

# Plan 02-04 Summary — On-Device Verification (Rokid glasses + OPPO phone)

Executed inline by the orchestrator via adb per the phase grant. New glasses APK (SPORT mode) deployed to `1901092544802583`; phone `3B164G01Y7L00000` ran the fixed Phase-1 build with the mock-GPS harness. Session: `20260703-131424-69057f7c` (finalized).

## Verdict table

| Check | Verdict | Evidence |
|---|---|---|
| Tap cycle Full → Corner → Sport → Full (HUD-03 / SC#1) | **PASS** | Screencaps per mode: full map frame (119KB), corner (37KB), SPORT metrics frame, then tap → back to full map (122KB) |
| Layout-revert regression (RESEARCH critical finding) | **PASS** | SPORT persisted across t0/t6/t11 screencaps under an active 1Hz sport_state stream — toggle ownership via BluetoothClient works |
| Live metrics ~1Hz (HUD-01/HUD-02 / SC#2) | **PASS** | SPORT frames show ELAPSED 0:02:26 → later frames advancing; primary numeral 19.8 km/h = the mock feed's 5.5 m/s exactly; pace 2:59/km; distance 478m → 667m across captures |
| Sport-aware layout per locked CONTEXT | **PASS** | Ride → speed as huge primary with km/h unit label, pace secondary, elapsed top, distance bottom — matches the locked arrangement |
| Staleness ladder (dim >3s, NO DATA >10s) | **PASS** | Phone app killed mid-stream: +13s screencap shows "NO DATA" primary, retained values dimmed, BT:-- strip, still [ SPORT ] |
| FINISHED precedence | **PASS** | After stop, SPORT shows "FINISHED" banner over retained finals (0:04:41 · 0.0 km/h · 667 m) — sticky across re-entry into SPORT |
| Monochrome green (HUD-04 / SC#3) | **PASS** | Pixel scan on 3 SPORT frames: 0/307,200 non-green pixels each (R==0 ∧ B==0 everywhere) |
| Mini-mode preservation (SC#4) | **PASS (unit + code path)** | 3-way cycle logic unit-tested incl. MINI→FULL reset; device spot covered by unchanged Settings-driven path (no phone Mini toggle exercised this pass) |
| Protocol hygiene | **PASS** | Glasses logcat: 0 "Unknown message" lines across the whole session |

## Phase-1 residuals CLOSED in the same session (fixed build `1b52505` + `aa3ecaa`)

| Residual | Verdict | Evidence |
|---|---|---|
| Stationary-window distance flatness | **PASS — 0.00m** | Two windows (59 and 89 ticks, cs<0.3): d-delta exactly 0.00m each (was 6.67m pre-fix) |
| Teleport exclusion | **PASS** | Kill mid-mock → resume with real desk fixes: d 667m (no 60km jump); hysteresis blocked the hop on this path (resumed state starts non-moving); the moving-path seam gate remains unit-locked |
| Kill → restart → resume (re-test on fixed build) | **PASS** | `Resumed interrupted session 20260703-131424-69057f7c` from restarted pid; recording continued; crash-rewind to checkpoint (≤60s) observed (734m broadcast → 667m resumed) — by design |
| Mock harness teardown (Pitfall 9) | **DONE** | Feeder dead; Developer-Options mock-app selection cleared (appops now `MOCK_LOCATION: deny`); `svc power stayon false` restored |

Remaining by design: the 2-hour pre-release screen-off validation stays in STATE Pending Todos (not a phase gate).

## Notes

- uiautomator dumps intermittently return stale trees on ColorOS (claimed missing REC panel while the screencap showed it live) — visual screencap verification is authoritative; noted for future device passes.
- Glasses sleep drops adbd; `adb kill-server && adb start-server` re-attaches (used twice).
- Evidence (screencaps, logcats) in the session scratchpad per T-07-02; no raw coordinates in this SUMMARY.
