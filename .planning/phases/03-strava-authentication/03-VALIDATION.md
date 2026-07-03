---
phase: 3
slug: strava-authentication
status: ready
nyquist_compliant: true
wave_0_complete: false
created: 2026-07-03
---

# Phase 3 — Validation Strategy

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 4.13.2 plain JVM (established); Gson on test classpath comes with the phone module deps |
| **Config file** | existing (Phase 1 infra); no new Wave-0 needed unless plan adds test-only deps |
| **Quick run command** | `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && cd /Users/bilhuang/Documents/rokid-maps && java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :phone:testDebugUnitTest -q` |
| **Full suite command** | `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && cd /Users/bilhuang/Documents/rokid-maps && java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain testDebugUnitTest assembleDebug -q` |
| **Estimated runtime** | ~40s quick / ~130s full |

## Sampling Rate

- After every task commit: quick command
- After every plan wave: full suite
- Before phase verification: full suite + on-device OAuth pass
- Max feedback latency: 130 seconds

## JVM-testable seams (from 03-RESEARCH Validation Architecture)

- Authorize-URL builder (endpoint, client_id, redirect_uri=rokidhud://callback, comma scopes, state param)
- State-param generation + callback validation (mismatch → rejected)
- Token/athlete Gson models: happy path + all-nullable refresh response (no athlete) + malformed JSON
- Expiry math: proactive-refresh threshold (30 min), expires_at persistence round-trip (plain data holder — ESP itself is device-only)
- Single-flight refresh: two threads request refresh simultaneously → exactly one network attempt (fake transport)
- Rate-limit header parsing (X-RateLimit-Usage, X-ReadRateLimit-Usage → logged)

## Device-only (final plan; devices OPPO 3B164G01Y7L00000 + glasses idle; adb path standard)

- Intent-filter resolution: `adb shell am start -a android.intent.action.VIEW -d "rokidhud://callback?code=FAKE&state=WRONG"` → app handles + REJECTS bad state (logcat)
- Real OAuth: launch Connect → Custom Tab → **HUMAN MOMENT: user taps Authorize on Strava** → callback → token exchange logcat → card shows "Connected as {athlete}"
- GET /athlete authenticated call proof (logcat, rate-limit headers logged)
- Persistence: force-stop + relaunch → still connected (AUTH-02)
- Refresh: debug-only forced-refresh hook (long-press card) → refresh logcat + rotation persisted (AUTH-03)
- Backup-restore hardening: AEADBadTagException wrapper path unit-covered; strava_auth.xml excluded in both backup rule files (grep)
- Fold-in from Phase 2: Mini-toggle device spot, WR-01 SPORT-survives-settings-resend, imperial variant — run during this phase's device session

## Human prerequisite (blocking E2E only)

Strava API app under the user's account (Callback Domain `rokidhud`); client_id/secret → local.properties `strava.client.id`/`strava.client.secret`. Code + unit tests + graceful-degradation card proceed without them.
