---
phase: 2
slug: glasses-sport-hud
status: ready
nyquist_compliant: true
wave_0_complete: false
created: 2026-07-03
---

# Phase 2 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 4.13.2 plain JVM (Phase 1 pattern); glasses module gains its first `testImplementation` (Wave 0) |
| **Config file** | none — Wave 0 installs (`testImplementation("junit:junit:4.13.2")` in glasses/build.gradle.kts; `org.json:json:20231013` if HudState tests touch codec types) |
| **Quick run command** | `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && cd /Users/bilhuang/Documents/rokid-maps && java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :glasses:testDebugUnitTest -q` |
| **Full suite command** | `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && cd /Users/bilhuang/Documents/rokid-maps && java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain testDebugUnitTest assembleDebug -q` |
| **Estimated runtime** | ~30s quick / ~120s full |

---

## Sampling Rate

- **After every task commit:** quick command for the touched module
- **After every plan wave:** full suite (all modules + assembleDebug)
- **Before phase verification:** full suite green + on-device glasses checks
- **Max feedback latency:** 120 seconds

---

## Per-Task Verification Map

(Filled by planner. Key testable seams from RESEARCH: `HudState.applySportState(msg, nowMs)` + `sportDisplayMode(nowMs)` pure functions; 3-way `toggleLayout()` cycle incl. Mini→Full preservation; staleness precedence FINISHED > NO-DATA > dim > live.)

On-device validation (final plan — devices: OPPO `3B164G01Y7L00000`, glasses `1901092544802583`; adb at `/opt/homebrew/share/android-commandlinetools/platform-tools/adb`; adb server restart re-attaches sleeping glasses):
- Deploy new glasses APK; tap-cycle Full → Corner → Sport → Full via `input tap 240 320` spaced ≥2s apart (single tap → onSingleTapConfirmed → toggleLayout; KEYCODE_ENTER is the shutdown gesture — never send it), screencap each mode (SC#1/HUD-03)
- **Layout-revert regression**: enter SPORT during an active 1Hz sport_state stream, wait ≥3s, screencap — mode must persist (RESEARCH critical finding)
- Phone recording + mock feed (Phase 1 harness): glasses SPORT screencap shows live speed/pace/elapsed/distance updating between two screencaps ~5s apart (HUD-01/HUD-02)
- Kill feed → staleness: dim at >3s, NO DATA at >10s (screencaps); stop recording → FINISHED banner (screencap)
- Green-only check: screencap pixel scan — no non-green/black pixels in SPORT mode (HUD-04)
- Zero `Unknown message` warnings for sport_state in glasses logcat
