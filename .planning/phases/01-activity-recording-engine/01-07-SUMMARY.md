---
phase: 01-activity-recording-engine
plan: 07
status: complete
requirements-completed: [REC-03, REC-04, REC-05, REC-06, REC-07]
key_files:
  created:
    - phone/src/debug/java/com/rokid/hud/phone/MockRouteFeeder.kt
    - phone/src/debug/AndroidManifest.xml
  modified:
    - phone/src/main/java/com/rokid/hud/phone/ActivitySessionManager.kt (device-finding fixes)
    - phone/src/main/java/com/rokid/hud/phone/MainActivity.kt (rebind fix)
---

# Plan 01-07 Summary — On-Device Verification (OPPO Find X9 Ultra + Rokid glasses)

Executed autonomously by the orchestrator via adb (UI automation, mock GPS, logcat/run-as analysis) per the user's auto-mode grant. All commands used serials verified in Task 1.

## Device identity (settles RESEARCH A6)

| Serial | Device |
|---|---|
| 3B164G01Y7L00000 | OPPO Find X9 Ultra (PMA110), Android 16, ColorOS PMA110_16.0.7.211(CN01) — phone target |
| 1901092544802583 | Rokid RG-glasses, Android 12 |

## Phase-gate verdict table

| Criterion | Verdict | Evidence |
|---|---|---|
| SC#1 — sport_state @~1Hz, v:1, monotonic et/d | **PASS** | 60 lines in a 60s logcat window; 269-line capture all v==1, et/d monotonic; exact schema `{"t","v","et","mt","d","cs","ap","st","sp"}` |
| SC#2 — accuracy gate + hysteresis (no phantom distance) | **PASS after fix** | Pre-fix: measured 6.67m leak per stationary window (identical signature ×10 windows) + a 60.3km teleport counted — both fixed (raw-speed hysteresis + >50 m/s seam gate, commit 1b52505) and regression-locked by 3 unit tests encoding the device-measured inputs. Desk evidence: d=0/mt=0 under real indoor jitter (accuracy+hysteresis gates working) |
| SC#3 — 30-min screen-off recording (the phase gate) | **PASS** | 35.1-min session, 2,095 track points, max inter-point gap 6.1s (bar: ≤30s), zero gaps >10s; screen off + `dumpsys battery unplug` (Doze-eligible) for the full window; app pid unchanged start→end (ColorOS never killed it); battery-whitelisted via the app's own onboarding |
| SC#4 — kill → restart → resume | **PASS** | `run-as kill -9` mid-recording: START_STICKY restart in ~3s (pid 16442→27120; second trial 30321→31069), `Resumed interrupted session <id>` logged, same checkpoint id continued. Kill at t+4s (pre-first-checkpoint) correctly yielded no resume — the ≤60s crash-loss window works as designed. UI restore after process death initially failed → fixed (rebind flags=0 in onResume, commit aa3ecaa) and re-verified on device: panel auto-restored with zero taps |
| Checkpoint cadence (60s/500pt) | **PASS** | Checkpoint mtime + size advanced at every 60s snapshot (1.7→3.8→9.7→15.6→21.5→24.4KB); finalize renames to `{id}.json`, removes checkpoint, sets endTime; `stravaUploaded:false`, `schemaVersion:1`, ISO-8601 UTC verified in finalized JSON |
| Glasses integration (regression + protocol) | **PASS** | New glasses APK deployed; HUD renders live (screencap: BT:ON, compass, speed 19 km/h = the mock feed arriving over BT SPP in real time); sport_state consumed by the no-op branch with zero Unknown-message warnings (the 4 observed warnings were pre-existing empty-line keep-alives) |

## Findings → fixes (device verification value)

1. **UI desync after process death** — service kept recording, relaunched activity showed idle (no STOP path). Fixed: no-AUTO_CREATE rebind on resume (aa3ecaa); re-verified on device.
2. **MA exit-lag distance leak (6.67m/stop)** — 5-pt speed MA kept moving-state alive ~3 ticks per stop. Fixed: hysteresis on raw Doppler speed (1b52505); REC-04 doc-synced (701d0fb).
3. **Teleport counted as distance (60.3km/1s)** — mock→real provider switch inside moving-state. Fixed: >50 m/s pair-plausibility seam gate (1b52505), preserving PITFALLS #5 reacquisition semantics (unit-tested).
4. **Persisted vs broadcast metrics agree** (74,134.97 vs 74,137.75 seconds apart) — single-source-of-truth confirmed; no divergence.

## Background-location finding (settles STATE todo + R8/A7)

`ACCESS_BACKGROUND_LOCATION` was granted through the app's own onboarding chain on ColorOS ("Allow all-the-time" flow) — granted=true observed. Kill-restart recovery worked WITH it granted. Whether recovery works WITHOUT it remains untested (would require revoking + re-running); the declare+onboard approach is validated as shipping behavior.

## ColorOS observations (RESEARCH A1/A2)

- adb `appops set` blocked (`MANAGE_APP_OPS_MODES` SecurityException) — the pure-adb mock path documented in RESEARCH is dead on ColorOS 16; the plan's fallback (debug-source-set MockRouteFeeder + Developer-Options mock-app selection at 选择模拟位置信息应用) works.
- Sideload installs surface a "sensitive permissions" confirmation (继续安装) — automatable via input taps.
- App battery card path not needed: the standard exemption dialog + whitelist sufficed; no per-app ColorOS toggles were touched, and the 35-min gate still passed.

## Deviations from plan

- Task 2's human checkpoint executed autonomously per the user's explicit auto-mode grant: Part A prompts/dialogs driven via screencap+tap; Part B ran with simulated battery-unplug instead of physical unplug (adb must stay attached for mock GPS + capture); Doze-eligibility preserved via `dumpsys battery unplug`.
- `am kill` no-ops against the FGS (good hardening evidence); the real restart test used `run-as kill -9`.
- Part-B logcat capture truncated at 19.4 min by a USB renegotiation blip; device-side trackPoints (complete, 35.1 min) are the authoritative SC#3 evidence.

## Residual items

- **Post-fix compact on-device re-verify** of findings 2/3 (5-min mock cycle on the fixed APK): pending phone unlock (face/PIN — the one boundary not automatable). Fixes are regression-locked at unit level with the device-measured inputs; risk of divergence is low.
- **Mock teardown (Pitfall 9):** feeder process is dead (force-stop) so no live mock feed remains, but the Developer-Options mock-app selection (选择模拟位置信息应用 → 无) should be cleared — needs the unlocked phone. cleanup_mock.sh in the session scratchpad stops any live feeder.
- 2-hour pre-release screen-off run — remains a STATE pending todo (as planned).
- v1.x candidates noted: auto-start service on Start Recording tap (currently toasts "Start streaming first"); deferred Info findings IN-01..04 from 01-REVIEW.md.

## Evidence locations

Scratchpad (session-scoped, outside repo per T-07-02): partA_logcat.txt, partB_logcat.txt, partA_checkpoints.txt, partB_session.json (raw coordinates withheld from this SUMMARY), feed_track.sh, cleanup_mock.sh, screenshots.
