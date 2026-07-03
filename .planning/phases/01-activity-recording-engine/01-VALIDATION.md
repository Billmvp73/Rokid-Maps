---
phase: 1
slug: activity-recording-engine
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-07-03
---

# Phase 1 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 4.13.2 (plain JVM — no Robolectric); `org.json:json:20231013` on test classpath |
| **Config file** | none — Wave 0 installs (`testImplementation` in `shared/build.gradle.kts` + `phone/build.gradle.kts`, `unitTests.isReturnDefaultValues = true` in phone) |
| **Quick run command** | `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && cd /Users/bilhuang/Documents/rokid-maps && java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :shared:testDebugUnitTest -q` |
| **Full suite command** | `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && cd /Users/bilhuang/Documents/rokid-maps && java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain testDebugUnitTest assembleDebug -q` |
| **Estimated runtime** | ~40s quick / ~120s full (first runs slower) |

---

## Sampling Rate

- **After every task commit:** Run the quick command for the touched module (`:shared:` or `:phone:` testDebugUnitTest)
- **After every plan wave:** Run the full suite command (all module tests + assembleDebug)
- **Before `/gsd-verify-work`:** Full suite must be green AND both APKs must assemble
- **Max feedback latency:** 120 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| (filled by planner) | | | REC-01..07 | | | | | | |

Wave 0 gaps from RESEARCH.md Validation Architecture (must close before feature tasks):
1. `testImplementation("junit:junit:4.13.2")` in shared + phone build.gradle.kts
2. `testImplementation("org.json:json:20231013")` in both (Android stubs org.json on JVM)
3. `testOptions { unitTests.isReturnDefaultValues = true }` in phone (Log.* stubs)
4. First test files compile + run green (`:shared:testDebugUnitTest`, `:phone:testDebugUnitTest`)

On-device validation (post-wave, adb — devices: OPPO `3B164G01Y7L00000`, glasses `1901092544802583`; adb at `/opt/homebrew/share/android-commandlinetools/platform-tools/adb`):
- sport_state visible at ~1Hz in `adb logcat` while recording (SC#1)
- Mock-GPS track feed (FusedLocationProvider setMockMode or `adb shell cmd location providers`) drives distance/hysteresis end-to-end (SC#2)
- 30-min screen-off recording on the OPPO with continuous track (SC#3)
- Kill + restart recovers checkpoint session (SC#4)
