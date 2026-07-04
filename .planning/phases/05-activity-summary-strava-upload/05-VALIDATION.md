---
phase: 5
slug: activity-summary-strava-upload
status: ready
nyquist_compliant: true
wave_0_complete: false
created: 2026-07-03
---

# Phase 5 — Validation Strategy

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 4.13.2 plain JVM. Zero new deps — kxml2 (GPX write via XmlPullParserFactory.newSerializer) + org.json + Gson all already on classpath. |
| **Quick run command** | `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && cd /Users/bilhuang/Documents/rokid-maps && java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :phone:testDebugUnitTest -q` |
| **Full suite command** | `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && cd /Users/bilhuang/Documents/rokid-maps && java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain testDebugUnitTest assembleDebug -q` |
| **Estimated runtime** | ~45s quick / ~140s full |

## Sampling Rate

- After every task commit: quick command
- After every plan wave: full suite
- Before phase verification: full suite + on-device real-upload pass
- Max feedback latency: 140 seconds

## JVM-testable seams (from 05-RESEARCH Validation Architecture)

- **GpxWriter** (kxml2 serializer): points → GPX 1.1; EVERY trkpt has an ISO-8601 UTC `<time>` (Pitfall 4 — assert on a fixed-ts fixture); `<ele>` present when altitude finite, omitted on NaN; well-formed re-parse round-trip
- **Duplicate-id regex**: `duplicate of activity (\d+)` matches "somefile.gpx duplicate of activity 123456" (unanchored — filename first, 05-RESEARCH catch) → extracts 123456; non-duplicate errors don't match
- **Upload state machine**: uploading → processing → done(activity_id) / duplicate→done / failed / pending(2-min timeout) transitions
- **GPX validity guard**: empty track → reject; missing-time → reject (defence before POST)
- **sport_type mapping**: session "ride"/"run" → "Ride"/"Run" PascalCase (activity_type deprecated — 05-RESEARCH)
- **Summary math**: avg speed (persisted avgSpeedMps) + avg pace DERIVED from distanceM+movingMs (pace not persisted — 05-RESEARCH); moving vs elapsed
- **SessionStore.readSession(id) + updateUploadState(id, activityId)**: read-modify-atomic-write round-trip; upload write-back only ADDS fields (UPL-03 — never loses trackPoints)

## Device-only (final plan; the MILESTONE FINALE — batches Phase-3 live auth + Phase-4 real-route + Phase-2 spots into ONE phone session)

- Record a short activity (mock GPS harness or real) → stop → ActivitySummaryActivity shows time/moving-time/distance/avg speed+pace/route map (UPL-01)
- "Upload to Strava" → progress states (Uploading → Processing → Uploaded ✓) → **HUMAN confirms the activity appears in their Strava feed** (UPL-02)
- Re-upload the same activity → duplicate handled as success (no dupe in feed, no data loss)
- Past-activities list shows the session with "uploaded ✓" badge; tap → summary reopens (UPL-04)
- Kill/upload-fail path: local JSON intact, retry works (UPL-03)

## Human moment

The user confirms the uploaded activity is really in their Strava feed (the true UPL-02 proof — no adb can see strava.com). Everything else adb-scriptable. This is the last human confirmation of the milestone.
