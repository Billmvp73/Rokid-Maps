---
phase: 05-activity-summary-strava-upload
plan: 01
subsystem: strava-upload
tags: [strava, gpx, upload, state-machine, sessionstore, kxml2, tdd, pure-seams]

# Dependency graph
requires:
  - phase: 01-activity-recording
    provides: "SessionStore (toJson/fromJson/writeAtomic/finalFile/executor, listFinalSessions) + SessionData/TrackPoint pure contracts"
  - phase: 04-strava-route-import-navigation
    provides: "GpxParser (XmlPullParserFactory JVM-testable pattern, round-trip point-count check) + kxml2 testImplementation on the classpath"
provides:
  - "GpxWriter: pure GPX 1.1 writer (kxml2 serializer, ISO-UTC <time> on every trkpt, <ele> omitted on NaN) + isValidForUpload guard + parseDuplicateActivityId (unanchored) + sportType PascalCase map"
  - "UploadState sealed type (Uploading/Processing/Done/Failed/RateLimited/Pending) + StartOutcome/PollOutcome seams + pure driveUpload(start,poll,isDeadlineReached,emit,onSuccess) state machine"
  - "SummaryMath.avgPaceMsPerKm (mirrors ActivitySessionManager exactly, 100m floor) + avgSpeedMps passthrough"
  - "SessionStore.readSession(id) + updateUploadState(id, activityId) + updateUploadStateSync sync seam + toJsonWithActivityId + strava_activity_id JSON key contract"
affects: [05-activity-summary-strava-upload, activity-summary-ui, strava-uploader-network, history-list]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "JVM-testable GPX WRITING via XmlPullParserFactory.newSerializer() (the writing mirror of Phase-4 GpxParser; kxml2 test dep resolves off-device)"
    - "Pure state machine over injected function seams (start/poll/deadline/emit/onSuccess) — all timing (Thread/sleep/2s/2min) deferred to the Wave 2/3 caller"
    - "Write-back key added by re-parsing toJson output (toJsonWithActivityId) so the additive key set never drifts from the canonical serializer"

key-files:
  created:
    - phone/src/main/java/com/rokid/hud/phone/strava/GpxWriter.kt
    - phone/src/main/java/com/rokid/hud/phone/strava/UploadState.kt
    - phone/src/main/java/com/rokid/hud/phone/SummaryMath.kt
    - phone/src/test/java/com/rokid/hud/phone/strava/GpxWriterTest.kt
    - phone/src/test/java/com/rokid/hud/phone/strava/UploadStateMachineTest.kt
    - phone/src/test/java/com/rokid/hud/phone/SummaryMathTest.kt
  modified:
    - phone/src/main/java/com/rokid/hud/phone/SessionStore.kt
    - phone/src/test/java/com/rokid/hud/phone/SessionStoreTest.kt

decisions:
  - "GpxWriter uses XmlPullParserFactory.newSerializer() (NOT android.util.Xml, which throws Stub! on JVM) — keeps the fast pure-JVM test discipline, mirrors GpxParser"
  - "startTimeIso stays in write() signature (plan contract) but is NOT emitted as a <metadata><time> — Strava derives activity time from per-trkpt <time>, so point count == <time> count (round-trip guard); a metadata time would double-count"
  - "driveUpload is 100% pure (no Thread/sleep/network); the real 2s poll spacing + 2-min deadline live in the Wave 2/3 Android glue that supplies poll/isDeadlineReached — write-back (onSuccess) fires in exactly ONE place, the success branch (Pitfall 4)"
  - "strava_activity_id is a JSON-only key (write path) — SessionData in SessionModels.kt (a shared contract) gets NO new field; fromJson reads the key read-but-ignored so old key-less files are forward-compatible with no migration"
  - "toJsonWithActivityId re-parses toJson output and appends the key, so the additive write-back can never drift from the canonical key set/order"

requirements-completed: [UPL-01, UPL-02, UPL-03, UPL-04]

# Metrics
duration: 22min
completed: 2026-07-03
---

# Phase 5 Plan 01: Upload Payload + State Machine + Write-Back Seams Summary

**Four pure, JVM-tested seams that make the Phase-5 network/UI waves pure composition: a kxml2-backed GPX 1.1 writer with an ISO-UTC `<time>` on every point + a fail-fast validity guard + an unanchored duplicate-id regex + a PascalCase sport_type map; a `UploadState` sealed type driven by a timing-free `driveUpload` state machine whose write-back fires only on success; `SummaryMath` pace derivation that mirrors the recorder's 100m floor exactly; and `SessionStore.readSession`/`updateUploadState` atomic write-back that adds `stravaUploaded`+`strava_activity_id` without ever deleting a track point.**

## Performance

- **Duration:** ~22 min
- **Tasks:** 3 (all TDD RED→GREEN)
- **Files:** 8 (6 created, 2 modified)
- **Tests:** phone 181 / shared 7, **0 failures** (56 net-new across the four seams; all pre-existing Phase 1–4 tests unaffected)

## Accomplishments

- **`GpxWriter` (pure, `XmlPullParserFactory`)** — `write()` emits GPX 1.1 with a `<time>` = `Instant.ofEpochMilli(ts).toString()` (trailing `Z`, `T` separator) on **every** `<trkpt>`, `<ele>` only when altitude is finite, and the serializer's `text()` auto-escapes special chars. `isValidForUpload()` re-parses and rejects empty / no-`<trkpt>` / any-missing-`<time>` GPX before any network cost. `parseDuplicateActivityId()` uses an **unanchored** `Regex("duplicate of activity (\\d+)")` (filename comes first) → `toLongOrNull`. `sportType()` maps `run→Run`, else `Ride`. GpxWriterTest (15) proves each, incl. a `GpxParser` round-trip point-count match.
- **`UploadState` + `driveUpload` (pure state machine)** — sealed `UploadState` (Uploading/Processing/Done/Failed/RateLimited/Pending) + `StartOutcome`/`PollOutcome` seams. `driveUpload(start, poll, isDeadlineReached, emit, onSuccess)` interprets injected seams into state emissions with **no Thread/sleep/network**; a duplicate poll-error is reinterpreted as `Done` via `GpxWriter.parseDuplicateActivityId`; the write-back (`onSuccess`) fires **exactly once and only** in the Ready/Duplicate branch — non-duplicate error → Failed, timeout → Pending, RateLimited/start-Failed terminal, all with **no** write-back (Pitfall 4). UploadStateMachineTest (8) proves the transition table and the write-back-once invariant.
- **`SummaryMath` (pure)** — `avgPaceMsPerKm(movingMs, distanceM)` mirrors `ActivitySessionManager.avgPaceMsPerKm` **exactly**: `if (distanceM < 100.0) 0L else (movingMs / (distanceM/1000.0)).roundToLong()`, with `PACE_MIN_DISTANCE_M = 100.0` kept local so the floor visibly matches the recorder. `avgSpeedMps()` is a documenting passthrough (the summary reads `SessionData.avgSpeedMps` verbatim, never recomputes — Pitfall 5/6). SummaryMathTest (5) locks the formula incl. the sub-100m 0L floor and roundToLong behavior.
- **`SessionStore.readSession` + `updateUploadState`** — `readSession(id)` reads finalized `{id}.json` → `SessionData`, null on missing/unreadable, never throws. `updateUploadState(id, activityId)` runs on the serial executor and delegates to `updateUploadStateSync`, which reads, flips `stravaUploaded=true`, and re-persists WITH `strava_activity_id` via the existing `writeAtomic` (temp+fsync+rename) — **adds fields, deletes nothing** (UPL-03). `toJsonWithActivityId` re-parses `toJson` output so the key never drifts; `fromJson` reads the key read-but-ignored so pre-existing key-less files stay forward-compatible with no migration. SessionStoreTest gained 8 cases (round-trip, null-for-missing, preserves all trackPoints, no `.tmp` residue, old-file compat, async + sync seams).
- **Zero new dependencies** — `phone/build.gradle.kts` untouched; kxml2/org.json/JUnit4 already on the classpath. `assembleDebug` classpath unchanged.

## Public API delivered (for the Wave 2/3 consumers)

```kotlin
// com.rokid.hud.phone.strava.GpxWriter (object)
fun write(points: List<TrackPoint>, sport: String, startTimeIso: String): String
fun isValidForUpload(gpx: String): Boolean
fun parseDuplicateActivityId(error: String?): Long?
fun sportType(sport: String): String            // "run"->"Run", else "Ride"

// com.rokid.hud.phone.strava (UploadState.kt)
sealed class UploadState { Uploading; Processing; Done(activityId: Long); Failed(message: String); RateLimited; Pending }
sealed class StartOutcome { Started(idStr: String); RateLimited; Failed(message: String) }
sealed class PollOutcome  { Ready(activityId: Long); Duplicate(activityId: Long); Processing; Error(message: String) }
fun driveUpload(
    start: () -> StartOutcome,
    poll: (idStr: String) -> PollOutcome,
    isDeadlineReached: () -> Boolean,
    emit: (UploadState) -> Unit,
    onSuccess: (activityId: Long) -> Unit
)   // write-back = onSuccess, fired ONLY on Ready/Duplicate

// com.rokid.hud.phone.SummaryMath (object)
const val PACE_MIN_DISTANCE_M = 100.0
fun avgPaceMsPerKm(movingMs: Long, distanceM: Double): Long
fun avgSpeedMps(persistedAvgSpeedMps: Double): Double   // passthrough — read, do not recompute

// com.rokid.hud.phone.SessionStore
fun readSession(id: String): SessionData?
fun updateUploadState(id: String, activityId: Long)      // async, serial executor
fun updateUploadStateSync(id: String, activityId: Long)  // sync seam (tests + production delegate)
// JSON contract: on upload success the {id}.json gains "strava_activity_id":<Long> + "stravaUploaded":true.
// SessionData has NO strava_activity_id field — the id lives in the JSON layer only.
```

## Task Commits

Each task committed atomically after TDD RED (failing test verified) → GREEN (tests pass):

1. **Task 1: GpxWriter + validity guard + duplicate regex + sport_type** — `a98f501` (feat)
2. **Task 2: UploadState + driveUpload state machine + SummaryMath** — `60634f6` (feat)
3. **Task 3: SessionStore.readSession + updateUploadState atomic write-back** — `a199cde` (feat)

## Decisions Made

- **`XmlPullParserFactory.newSerializer()`, not `android.util.Xml`** — the latter throws `RuntimeException: Stub!` on the JVM test classpath. This mirrors Phase-4 `GpxParser` and keeps GPX WRITING unit-testable on plain JVM (kxml2 test dep), no Robolectric. Acceptance grep confirms `android.util.Xml` count = 0 in GpxWriter.kt.
- **No `<metadata><time>` in `write()`** — the plan's `write(points, sport, startIso)` signature keeps `startTimeIso`, but emitting it as a separate metadata time would make the `<time>` count exceed the point count and break the round-trip/point-count guard. Strava derives activity time from the per-trkpt `<time>` values, so the trkpt times are the single source of truth; `startTimeIso` is documented as reserved-by-contract.
- **`driveUpload` is timing-free** — all `Thread`/`sleep`/`2s`/`2min`/network live in the Wave 2/3 Android glue that supplies `poll`/`isDeadlineReached`. This isolates the risky transition logic (duplicate-error reinterpretation, write-back-only-on-success) into a deterministic unit. `onSuccess` is invoked in exactly one code path (the Ready/Duplicate branch).
- **`strava_activity_id` is JSON-layer only** — `SessionData` (SessionModels.kt, a shared contract) gets no new field. `updateUploadStateSync` writes the key via `toJsonWithActivityId` (which re-parses `toJson` output so the additive key can't drift), and `fromJson` reads it read-but-ignored so old key-less files load unchanged (forward-compatible by construction, no migration).
- **Write-back reuses `writeAtomic`, never `FileWriter`** — a mid-write crash leaves the old valid `{id}.json` intact (threat T-05-01 / UPL-03 structural). No delete/purge method was added (acceptance grep = 0).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Corrected a wrong expected timestamp literal in GpxWriterTest**
- **Found during:** Task 1 (GpxWriter GREEN phase)
- **Issue:** The plan's `<behavior>` gave an illustrative fixed-ts example — "with ts=1782000000000L the text is exactly 2026-06-20T13:20:00Z". My test asserted that literal. The actual JDK value of `Instant.ofEpochMilli(1_782_000_000_000L).toString()` is **`2026-06-21T00:00:00Z`** (1782000000 s = 2026-06-21T00:00:00 UTC), so the assertion failed against correct production output. This was a test-data error (a wrong example value carried from the plan), not a production fault — `write()` correctly uses the real `Instant.ofEpochMilli(p.ts).toString()`.
- **Fix:** Verified the exact value independently with a throwaway JDK program (`javac`/`java`), then corrected the test's expected literal to `2026-06-21T00:00:00Z` and the fixture start string to match. The core requirement (the `Z`/`T` ISO shape) remained asserted throughout via the separate shape check.
- **Files modified:** phone/src/test/java/com/rokid/hud/phone/strava/GpxWriterTest.kt
- **Committed in:** `a98f501` (Task 1 commit)

**2. [Rule 1 - Cleanup] Removed a stray no-op line from UploadStateMachineTest**
- **Found during:** Task 2 (GREEN phase)
- **Issue:** An initial `GpxWriter // touch to ensure same package resolves` no-op statement was left in one test; it was dead and added noise.
- **Fix:** Removed the line before running the suite (the driver already references `GpxWriter.parseDuplicateActivityId`, so package resolution needs no help).
- **Files modified:** phone/src/test/java/com/rokid/hud/phone/strava/UploadStateMachineTest.kt
- **Committed in:** `60634f6` (Task 2 commit)

---

**Total deviations:** 2 auto-fixed (1 test-data literal, 1 dead-line cleanup). Both local to this plan's own test files; neither changed delivered production behavior or scope.

## Known Stubs

None. All four seams are fully implemented and test-covered. (The only "Stub!" token in the source is inside a GpxWriter doc comment explaining why `android.util.Xml` — which throws `RuntimeException: Stub!` on JVM — is avoided; it is descriptive, not a stub.)

## Threat Model Compliance

All `mitigate`-disposition threats from the plan's `<threat_model>` are addressed and test-covered:
- **T-05-01 (updateUploadState in-place corruption):** write-back reuses the tested `writeAtomic` (temp+fsync+rename); `updateUploadStateLeavesExactlyOneFinalFileAndNoTmpResidue` proves one final file, no `.tmp` residue. No `FileWriter`-overwrite, no delete/purge (grep = 0).
- **T-05-02 (duplicate-error string injection):** `parseDuplicateActivityId` captures only `\d+` → `toLongOrNull`; non-numeric/garbage → null (Failed), the id is a `Long` never echoed as markup. `parseDuplicateActivityIdReturnsNullForNonDuplicateError` / `...ForNull` cover it.
- **T-05-03 (GPX location/time leaking to logs/disk):** `GpxWriter.write` returns an in-memory String; this plan writes NO GPX file and logs NO GPX/coordinate content (only `e.message` on failure). No token material anywhere.
- **T-05-04 (malformed/oversized GPX in isValidForUpload):** re-parse via `XmlPullParser` (XXE-safe, `isNamespaceAware=false`, no external entities — mirrors GpxParser), wrapped in try/catch → false; input is our own bounded output.
- **T-05-SC (dependency installs):** zero packages added; `build.gradle.kts` unchanged.

No new threat surface beyond the register.

## Next Wave Readiness

- **Wave 2/3 (StravaUploader network + ActivitySummaryActivity/HistoryActivity UI) can compose these seams directly:** the network glue calls `GpxWriter.write` → `isValidForUpload` → `StravaUploader.startUpload/poll` and feeds the outcomes into `driveUpload` (supplying the real `Thread`+`Thread.sleep(2000)` poll loop, a `System.currentTimeMillis()+120_000` deadline for `isDeadlineReached`, `runOnUiThread` for `emit`, and `SessionStore.updateUploadState` for `onSuccess`). The summary screen reads `SessionStore.readSession` and renders `SummaryMath.avgPaceMsPerKm(movingMs, distanceM)` + `SessionData.avgSpeedMps`. History reads `listFinalSessions()`.
- **Boundary respected:** STATE.md / ROADMAP.md NOT modified (orchestrator owns them). No Phase-1 persistence behavior changed — the SessionStore additions are additive `readSession`/`updateUploadState`; finalize/checkpoint/orphan paths and the full Phase-1 test suite are unchanged and green.
- **No blockers.** All four pure seams are unit-tested on plain JVM; only the network/Android composition remains for later plans in this phase.

## Self-Check: PASSED

All created/modified files verified present; all three task commit hashes (`a98f501`, `60634f6`, `a199cde`) verified in git log. Full gate green: phone 181 tests + shared 7 tests, 0 failures. Acceptance greps: GpxWriter.kt has NO `android.util.Xml` (count 0); SessionStore.kt has NO `deleteSession`/`purge` (count 0); the duplicate regex is unanchored (no leading `^`). `phone/build.gradle.kts` unchanged (zero new dependencies). STATE.md / ROADMAP.md confirmed untouched.

---
*Phase: 05-activity-summary-strava-upload*
*Completed: 2026-07-03*
