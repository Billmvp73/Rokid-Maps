---
phase: 01-activity-recording-engine
plan: 01
subsystem: protocol
tags: [bluetooth-spp, org-json, junit4, sport-state, kotlin, gradle]

# Dependency graph
requires: []
provides:
  - "First unit-test infrastructure: testImplementation junit 4.13.2 + real org.json in shared and phone modules"
  - "sport_state protocol message: SportStateMessage, ParsedMessage.SportState, encodeSportState with hardcoded v:1, defensive optXxx decode"
  - "Recording data contracts in SessionModels.kt: SessionState, TrackPoint, MetricsSnapshot, MetricsListener, SessionData"
  - "Glasses compile compatibility: documented no-op SportState when-branch in BluetoothClient.processMessage"
affects: [01-02, 01-03, 01-04, 01-05, 01-07, phase-2-glasses-hud]

# Tech tracking
tech-stack:
  added: ["junit:junit:4.13.2 (test)", "org.json:json:20231013 (test classpath duplicate — shadows mockable android.jar stubs)"]
  patterns:
    - "Plain-JVM JUnit4 tests under src/test/java (repo's first tests)"
    - "sport_state carries protocol version v:1; codec is a dumb serializer (no clamping)"
    - "Pure-Kotlin contract files (no android.* imports) for JVM-testable phone-module types"

key-files:
  created:
    - shared/src/test/java/com/rokid/hud/shared/protocol/ProtocolCodecTest.kt
    - phone/src/main/java/com/rokid/hud/phone/SessionModels.kt
  modified:
    - shared/build.gradle.kts
    - phone/build.gradle.kts
    - shared/src/main/java/com/rokid/hud/shared/protocol/ProtocolConstants.kt
    - shared/src/main/java/com/rokid/hud/shared/protocol/Messages.kt
    - shared/src/main/java/com/rokid/hud/shared/protocol/ProtocolCodec.kt
    - glasses/src/main/java/com/rokid/hud/glasses/BluetoothClient.kt

key-decisions:
  - "isReturnDefaultValues=true only in phone module (shared needs only real org.json; RESEARCH Pitfall 7 caution)"
  - "REC-07 NOT marked complete: this plan delivers the protocol half only; plans 01-04/01-07 deliver the ~1Hz broadcast + monotonic behavior the requirement text demands"
  - "Glasses SportState when-branch is a documented Phase-1 no-op (compile compatibility for the sealed-class change; HUD consumption is Phase 2 HUD-02)"

patterns-established:
  - "Test classpath: testImplementation org.json duplicate shadows mockable android.jar stubs on plain JVM"
  - "Protocol versioning: only sport_state carries v (hardcoded 1 at encode; full negotiation deferred)"
  - "Contract-first files: SessionModels.kt is pure Kotlin so Wave-2 JVM tests compile against it directly"

requirements-completed: []  # REC-07 intentionally NOT marked — protocol half only; broadcast half lands in 01-04 (see Decisions)

# Metrics
duration: 9min
completed: 2026-07-03
---

# Phase 01 Plan 01: Test Infrastructure + sport_state Protocol Summary

**sport_state BT message (v:1) with defensive round-trip codec, the repo's first 7 green JVM unit tests, and pure-Kotlin recording contracts that unblock Wave-2 parallel plans**

## Performance

- **Duration:** ~9 min
- **Started:** 2026-07-03T15:33:24Z
- **Completed:** 2026-07-03T15:42:30Z
- **Tasks:** 3/3 completed
- **Files modified:** 8 (2 created, 6 modified)

## Accomplishments

- Repo's first unit-test infrastructure: JUnit 4.13.2 + real org.json on the test classpath in both shared and phone modules; **7 tests, 0 failures, 0 errors** in `ProtocolCodecTest` (exact count: stateMessageRoundTrip, malformedLineDecodesToUnknown, sportStateRoundTrip, versionFieldIsOne, exactJsonKeys, missingFieldsDecodeWithDefaults, malformedSportStateDecodesToUnknown)
- sport_state message (REC-07 protocol half): `SportStateMessage` + `ParsedMessage.SportState` + `encodeSportState` emitting exactly `t,v,et,mt,d,cs,ap,st,sp` with hardcoded `v:1`; decode uses `optXxx` defaults (0 / "idle" / "ride") inside the existing try/catch so malformed input yields `ParsedMessage.Unknown`, never throws
- `SessionModels.kt` contracts (SessionState, TrackPoint, MetricsSnapshot, MetricsListener, SessionData) — pure Kotlin, no android.* imports, so Wave-2 plans (ActivitySessionManager, SessionStore) compile and JVM-test independently
- Both APKs still assemble: `assembleDebug` produced `phone-debug.apk` and `glasses-debug.apk` — **confirmation that glasses assembleDebug still passes** after the sealed-variant addition (exhaustive-when satisfied by the documented no-op branch)

## Task Commits

Each task was committed atomically:

1. **Task 1: Install test infrastructure in both modules and prove it with codec smoke tests** - `41e1230` (test)
2. **Task 2: Define recording data contracts in SessionModels.kt** - `df5c5cc` (feat)
3. **Task 3: sport_state message type, codec, and round-trip tests (TDD)** - RED `e0adc25` (test) → GREEN `39a9665` (feat); no refactor commit needed (implementation followed existing codec patterns exactly)

## Files Created/Modified

- `shared/build.gradle.kts` - testImplementation junit 4.13.2 + org.json 20231013 (intentional duplicate of the implementation line — real org.json shadows android.jar stubs)
- `phone/build.gradle.kts` - same test deps + `testOptions { unitTests.isReturnDefaultValues = true }` inside android {} (phone only)
- `shared/src/test/java/com/rokid/hud/shared/protocol/ProtocolCodecTest.kt` - 102 lines, 7 tests: STATE round-trip smoke + 5 sport_state behavior tests
- `phone/src/main/java/com/rokid/hud/phone/SessionModels.kt` - recording data contracts with KDoc'd sentinel conventions (NaN / -1.0 / epoch-ms ts) and ownership notes
- `shared/src/main/java/com/rokid/hud/shared/protocol/ProtocolConstants.kt` - FIELD_VERSION/ELAPSED/MOVING_TIME/SPORT_DISTANCE/CURRENT_SPEED/AVG_PACE/SESSION_STATE/SPORT + MessageType.SPORT_STATE
- `shared/src/main/java/com/rokid/hud/shared/protocol/Messages.kt` - SportStateMessage data class
- `shared/src/main/java/com/rokid/hud/shared/protocol/ProtocolCodec.kt` - SportState sealed variant (before Unknown), encodeSportState, SPORT_STATE decode branch (before else); no clamping logic (verified by grep)
- `glasses/src/main/java/com/rokid/hud/glasses/BluetoothClient.kt` - one-line no-op `is ParsedMessage.SportState` branch before `is ParsedMessage.Unknown` (compile compatibility only)

## Decisions Made

- **REC-07 left unchecked in REQUIREMENTS.md:** the requirement text includes "broadcasts it at ~1Hz during recording" and monotonic elapsed/distance — delivered by plans 01-04/01-07 (which also list REC-07). Marking now would falsify traceability; the orchestrator/later plan should mark it when the broadcast lands.
- **testOptions only in phone:** shared's ProtocolCodec imports only org.json, so `isReturnDefaultValues` is unnecessary there and its false-green risk is avoided (RESEARCH Pitfall 7).
- **Codec stays a dumb serializer:** `v:1` hardcoded at encode; monotonic clamping deferred to ActivitySessionManager per PATTERNS Integration Warning 5.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Fresh worktree missing gitignored `local.properties` (SDK location)**
- **Found during:** Task 1 (first Gradle invocation)
- **Issue:** `SDK location not found` — `local.properties` is gitignored so the worktree checkout lacks it
- **Fix:** Created worktree-local `local.properties` with `sdk.dir=/opt/homebrew/share/android-commandlinetools` (replicated from the main repo); file remains gitignored and uncommitted
- **Files modified:** `local.properties` (worktree-local, not committed)
- **Verification:** Subsequent Gradle runs succeed

**2. [Note] Verify commands run from worktree root with relative paths**
- The plan's verify commands hardcode `cd /Users/bilhuang/Documents/rokid-maps` (main repo). Per worktree-isolation instructions, all Gradle invocations ran from the worktree root with relative paths. Same tasks, same gates, correct working tree.

## Gradle Sync Surprises

None beyond the missing `local.properties` above. First test-classpath resolution downloaded junit/hamcrest quietly; total build times stayed in the seconds-to-~1min range. `:phone:testDebugUnitTest` reports NO-SOURCE (no phone tests yet) — expected per plan acceptance criteria.

## Known Stubs

| Stub | File | Reason |
|------|------|--------|
| `is ParsedMessage.SportState -> { /* no-op */ }` | `glasses/src/main/java/com/rokid/hud/glasses/BluetoothClient.kt` | Intentional per plan: compile compatibility for the sealed-class change only. Phase 2 (HUD-02) consumes sport_state on the glasses HUD. |

## TDD Gate Compliance

Task 3 (`tdd="true"`): RED commit `e0adc25` (tests failed on unresolved references — confirmed before commit) → GREEN commit `39a9665` (all 7 tests pass). No REFACTOR commit — none needed.

## Self-Check: PASSED
