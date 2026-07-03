---
phase: 1
slug: activity-recording-engine
status: ready
nyquist_compliant: true
wave_0_complete: false
created: 2026-07-03
updated: 2026-07-03
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
| 01-01/T1 | 01-01 | 1 | REC-07 (infra) | T-01-SC | Pinned registry-verified test deps | unit (smoke) | `:shared:testDebugUnitTest :phone:testDebugUnitTest` | ❌ created by task | Pending |
| 01-01/T2 | 01-01 | 1 | REC-07 (contracts) | — | JVM-pure contracts, no PAUSED state | compile | `:phone:compileDebugKotlin` | ❌ created by task | Pending |
| 01-01/T3 | 01-01 | 1 | REC-07 | T-01-01 | Malformed sport_state → Unknown, never throws | unit (tdd) | `:shared:testDebugUnitTest` | ❌ created by task | Pending |
| 01-02/T1 | 01-02 | 2 | REC-01, REC-02 | T-02-02 | Monotonic clamps; main-thread confinement + @Volatile | unit (tdd) | `:phone:testDebugUnitTest --tests "*ActivitySessionManagerTest*"` | ❌ created by task | Pending |
| 01-02/T2 | 01-02 | 2 | REC-03, REC-04 | T-02-02 | Accuracy gate + hysteresis kill phantom distance (R5) | unit (tdd) | `:phone:testDebugUnitTest --tests "*ActivitySessionManagerTest*"` | ❌ created by task | Pending |
| 01-03/T1 | 01-03 | 2 | REC-06 | T-03-01, T-03-02 | filesDir-only via File ctor; atomic temp+fsync+rename | unit (tdd) | `:phone:testDebugUnitTest --tests "*SessionStoreTest*"` | ❌ created by task | Pending |
| 01-03/T2 | 01-03 | 2 | REC-06 | T-03-02, T-03-03 | Corrupt checkpoint quarantined, never crashes; final-before-delete | unit (tdd) | `:phone:testDebugUnitTest --tests "*SessionStoreTest*"` | ❌ created by task | Pending |
| 01-04/T1 | 01-04 | 3 | REC-01, REC-05, REC-06 | T-04-03 | startForeground SecurityException hardening; no crash-loop | compile + suite | `:phone:compileDebugKotlin` + full suite | modifies existing | Pending |
| 01-04/T2 | 01-04 | 3 | REC-07, REC-05 | T-04-01, T-04-02 | No coordinates in sport_state log lines or notification | build + suite | `assembleDebug` + full suite | modifies existing | Pending |
| 01-05/T1 | 01-05 | 4 | REC-01, REC-02 | T-05-03 | Opt-in start; confirm-to-stop; no discard | build | `:phone:compileDebugKotlin :phone:assembleDebug` | modifies existing | Pending |
| 01-05/T2 | 01-05 | 4 | REC-05 | T-05-01 | Minimal-privilege bg-location ask-once; decline never blocks | compile + suite | `:phone:compileDebugKotlin` + full suite | modifies existing | Pending |
| 01-06/T1 | 01-06 | 4 | REC-05 | T-06-01, T-06-02 | Gated exact alarms; FLAG_IMMUTABLE; monotonic staleness | compile | `:phone:compileDebugKotlin` | ❌ created by task | Pending |
| 01-06/T2 | 01-06 | 4 | REC-05 | T-06-01, T-06-03 | exported=false receiver; rec_active no-op guard | build + suite | `assembleDebug` + full suite | modifies existing | Pending |
| 01-07/T1 | 01-07 | 5 | REC-05 (prep) | T-07-01 | Mock path adb-only or src/debug-only | device (adb) | `adb ... pm list packages \| grep com.rokid.hud.phone` | — | Pending |
| 01-07/T2 | 01-07 | 5 | REC-05 (SC#3) | T-07-01 | 30-min screen-off gate | manual-only (human checkpoint; OEM kill behavior unmockable) | — (human protocol; scripted asserts in T1/T3) | — | Pending |
| 01-07/T3 | 01-07 | 5 | REC-03/04/06/07 (SC#1/2/4) | T-07-02 | No raw coordinate dumps in SUMMARY | device (adb analysis) | `adb ... run-as com.rokid.hud.phone ls files/activities/ \| grep .json` | — | Pending |

Wave 0 gaps from RESEARCH.md Validation Architecture (closed by plan 01-01):
1. `testImplementation("junit:junit:4.13.2")` in shared + phone build.gradle.kts — plan 01-01 Task 1
2. `testImplementation("org.json:json:20231013")` in both (Android stubs org.json on JVM) — plan 01-01 Task 1
3. `testOptions { unitTests.isReturnDefaultValues = true }` in phone (Log.* stubs) — plan 01-01 Task 1
4. First test files compile + run green (`:shared:testDebugUnitTest`, `:phone:testDebugUnitTest`) — plan 01-01 Tasks 1+3
5. ASM designed with primitive-parameter `onFix(...)` for plain-JVM tests — plan 01-02 Task 1 design constraint

On-device validation (plan 01-07, adb — devices: OPPO `3B164G01Y7L00000`, glasses `1901092544802583` (identity re-verified in 01-07 Task 1); adb at `/opt/homebrew/share/android-commandlinetools/platform-tools/adb`):
- sport_state visible at ~1Hz in `adb logcat` while recording (SC#1) — 01-07 Task 2/3
- Mock-GPS track feed (`adb shell cmd location providers` primary; in-app debug feeder fallback) drives distance/hysteresis end-to-end (SC#2) — 01-07 Task 2/3
- 30-min screen-off recording on the OPPO with continuous track (SC#3) — 01-07 Task 2 (human) + Task 3 (analysis)
- Kill + restart recovers checkpoint session (SC#4) — 01-07 Task 2/3
