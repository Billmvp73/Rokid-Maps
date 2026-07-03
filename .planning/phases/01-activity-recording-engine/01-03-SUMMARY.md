---
phase: 01-activity-recording-engine
plan: 03
subsystem: persistence
tags: [org-json, atomic-write, checkpoint, crash-recovery, junit4, kotlin]

# Dependency graph
requires:
  - phase: 01-activity-recording-engine (plan 01-01)
    provides: "SessionData/TrackPoint contracts in SessionModels.kt + JUnit4/org.json test infrastructure with isReturnDefaultValues"
provides:
  - "SessionStore: crash-safe JSON persistence with atomic temp+fsync+renameTo writes"
  - "Checkpoint lifecycle: {id}.checkpoint.json overwritten atomically, finalized to {id}.json on stop"
  - "Orphan recovery implementing the locked <10-minute resume rule (RecoveryResult contract)"
  - "Corrupt-checkpoint quarantine as .checkpoint.corrupt (bytes preserved, never resumed)"
  - "listFinalSessions() newest-first seam for Phase 5 history/upload (UPL-04)"
affects: [01-04, 01-07, phase-5-strava-upload]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "File-constructor stores (not Context) for plain-JVM persistence tests (PATTERNS Warning 7)"
    - "Atomic write: same-dir temp + fd.sync() + checked renameTo boolean (RESEARCH Pattern 6 / Pitfall 8)"
    - "NaN sentinel encoding by JSON key omission (org.json forbids non-finite doubles)"
    - "Graceful executor.shutdown() (not shutdownNow) so queued final writes complete at teardown"

key-files:
  created:
    - phone/src/main/java/com/rokid/hud/phone/SessionStore.kt
    - phone/src/test/java/com/rokid/hud/phone/SessionStoreTest.kt
  modified: []

key-decisions:
  - "REC-06 marked complete at the store layer per plan scope; the 60s/500pt trigger cadence + service wiring land in 01-04 (which also lists REC-06), on-device restart proof in 01-07"
  - "RecoveryResult declared top-level in SessionStore.kt (store concern, not a SessionModels contract)"
  - "Resume boundary is strict: age < MAX_RESUME_AGE_MS resumes; exactly 10 minutes finalizes as interrupted"
  - "Interrupted finalize endTime = Instant.ofEpochMilli(checkpoint.lastModified()).toString() (ISO-8601 UTC)"
  - "listFinalSessions filters on FINAL_SUFFIX but excludes CHECKPOINT_SUFFIX (checkpoint names also end in .json)"

patterns-established:
  - "Sentinel round-trip: alt/speed/brg omitted when NaN, read back via optDouble(key, Double.NaN); acc always written with finite -1.0"
  - "Corrupt persisted state is quarantined (renamed .corrupt), never silently deleted (threat T-03-02)"
  - "Finalize order: write final file BEFORE deleting checkpoint — no window with neither file (threat T-03-03)"

requirements-completed: [REC-06]

# Metrics
duration: 11min
completed: 2026-07-03
---

# Phase 01 Plan 03: SessionStore Persistence Summary

**Crash-safe session persistence: atomic temp+fsync+rename JSON writes, single-checkpoint lifecycle, and fully unit-tested orphan recovery implementing the locked <10-minute resume rule — 20 plain-JVM tests green**

## Performance

- **Duration:** ~11 min
- **Started:** 2026-07-03T15:47:34Z
- **Completed:** 2026-07-03T15:58:30Z
- **Tasks:** 2/2 (both TDD: RED test commit -> GREEN feat commit)
- **Files modified:** 2 created

## Accomplishments

- `SessionStore(dir: File)` — constructor takes `java.io.File`, not Context, so every decision path (round-trip, atomicity, staleness, corruption) is proven on plain JVM with `TemporaryFolder` (no Robolectric, no device)
- Locked v1 JSON schema written exactly: `schemaVersion`, `id`, `sport`, `startTime`, `endTime` (omitted while in progress), `elapsedMs`, `movingMs`, `distanceM`, `avgSpeedMps`, `stravaUploaded`, `trackPoints[]`
- Orphan recovery decision logic complete: fresh (<10 min) checkpoint resumes (file left in place), stale finalizes as interrupted with endTime from file mtime, newest-wins among multiple fresh orphans, corrupt quarantined as `.checkpoint.corrupt`
- 20 SessionStoreTest tests + full suite (`:shared:` + `:phone:`) green

## Task Commits

Each task was committed atomically (TDD: RED then GREEN):

1. **Task 1: JSON serialization + atomic write primitive**
   - `b0f7847` (test) — failing round-trip/sentinel/corrupt/atomic-write tests
   - `b3ccd2a` (feat) — toJson/fromJson/writeAtomic + executor/shutdown
2. **Task 2: Checkpoint lifecycle, finalize, and orphan recovery**
   - `cc4c5ba` (test) — failing lifecycle/10-minute-boundary/quarantine tests
   - `5017f1c` (feat) — checkpoint/finalize sync+async, recoverOrphans, listFinalSessions

## Files Created/Modified

- `phone/src/main/java/com/rokid/hud/phone/SessionStore.kt` — atomic JSON persistence + checkpoint lifecycle + orphan recovery (297 lines)
- `phone/src/test/java/com/rokid/hud/phone/SessionStoreTest.kt` — 20 TemporaryFolder-based persistence tests (361 lines)

## Public API (consumed by plan 01-04)

```kotlin
// The service constructs: SessionStore(File(filesDir, "activities"))
class SessionStore(private val dir: File) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val CHECKPOINT_SUFFIX = ".checkpoint.json"
        const val FINAL_SUFFIX = ".json"
        const val MAX_RESUME_AGE_MS = 10 * 60_000L   // 600,000 ms
    }
    fun writeCheckpointSync(data: SessionData)       // atomic {id}.checkpoint.json overwrite
    fun writeCheckpointAsync(data: SessionData)      // serializes ON the serial executor
    fun finalizeSync(data: SessionData)              // writes {id}.json THEN deletes checkpoint
    fun finalizeAsync(data: SessionData)
    fun findOrphanCheckpoints(): List<File>          // checkpoints lacking a final file
    fun recoverOrphans(nowWallMs: Long = System.currentTimeMillis()): RecoveryResult
    fun listFinalSessions(): List<File>              // final .json only, newest first (Phase 5 seam)
    fun shutdown()                                   // graceful: queued final write completes
    // internal test seams: toJson(SessionData): String, fromJson(String): SessionData?,
    //                      writeAtomic(target: File, json: String)
}

data class RecoveryResult(
    val resumable: SessionData?,        // freshest <10-min orphan, or null; its checkpoint stays on disk
    val finalizedInterrupted: Int,      // stale/older orphans finalized with mtime-derived endTime
    val corrupt: Int                    // quarantined as {id}.checkpoint.corrupt
)
```

Service wiring contract for 01-04: call `recoverOrphans()` once in `onStartCommand` before location updates begin (synchronous); resume the returned session or start fresh; use `writeCheckpointAsync` on the 60s/500pt trigger and `finalizeAsync`/`finalizeSync` on stop; call `shutdown()` in `onDestroy`.

## Exact JSON Schema As Written

```jsonc
{
  "schemaVersion": 1,                      // JSON-layer constant, always first
  "id": "20260703-154500-abc123",
  "sport": "ride",                         // "ride" | "run"
  "startTime": "2026-07-03T15:45:00Z",     // ISO-8601 UTC
  "endTime": "2026-07-03T16:45:00Z",       // KEY OMITTED while session in progress (null)
  "elapsedMs": 3600000,
  "movingMs": 3200000,
  "distanceM": 25000.5,
  "avgSpeedMps": 6.94,
  "stravaUploaded": false,                 // Phase 5 upload contract, defaults false
  "trackPoints": [
    { "lat": 47.6062095, "lng": -122.3320708, "alt": 56.5,
      "ts": 1782000000000, "speed": 5.25, "acc": 8.0, "brg": 271.5 },
    { "lat": 47.6070001, "lng": -122.3330002, "ts": 1782000001000, "acc": -1.0 }
    // alt/speed/brg keys OMITTED when the value is NaN (org.json forbids
    // non-finite doubles); read back as Double.NaN via optDouble default.
    // acc always written; -1.0 means unknown. brg is additive per REC-01.
  ]
}
```

Decode rules: `id`/`sport`/`startTime` are required (missing -> fromJson returns null); everything else is `optXxx` with defaults (numerics 0, endTime empty-string -> null); corrupt input never throws (logged, returns null).

## Decisions Made

- Resume-boundary semantics: strict `age < MAX_RESUME_AGE_MS` (a checkpoint exactly 10 minutes old is finalized as interrupted, matching "fresher than 10 minutes" wording)
- `writeAtomic` builds its temp file as `File(dir, target.name + ".tmp")` per plan text — same directory as the target, guaranteeing same-filesystem atomic POSIX rename
- Async wrappers wrap the sync call in try/catch+Log.w on the executor (DiskTileCache put() shape) even though the sync methods already never throw — defense in depth on the background thread

## Deviations from Plan

None — plan executed exactly as written. (Environmental note: the plan's verify command referenced the main repo path; verification ran from this executor's worktree root instead, as required for parallel worktree isolation. Same Gradle invocation, same gates.)

## TDD Gate Compliance

Both tasks followed RED -> GREEN with commits in order: `test` b0f7847 -> `feat` b3ccd2a (Task 1); `test` cc4c5ba -> `feat` 5017f1c (Task 2). RED phases failed as expected (unresolved references to not-yet-written API). No refactor commits needed.

## Verification Results

- `:phone:testDebugUnitTest --tests "*SessionStoreTest*"` — 20 tests, 0 failures
- Full suite `:shared:testDebugUnitTest :phone:testDebugUnitTest` — BUILD SUCCESSFUL
- Acceptance greps: `class SessionStore(private val dir: File)` present; checked `renameTo` boolean present; `schemaVersion` present; NO `import android.content.Context` (only android.util.Log); `MAX_RESUME_AGE_MS = 10 * 60_000L` present; NO `fun deleteSession`/`fun purge` (keep-all-sessions locked decision)
- min_lines: SessionStore.kt 297 >= 120; SessionStoreTest.kt 361 >= 100
- Coverage of plan verification list: round-trip, NaN sentinel omission, corrupt input, atomic overwrite, orphan scan, 10-minute boundary both sides (5-min resume / 11-min interrupt), multi-orphan newest-wins, corrupt quarantine — all tested

## Next Phase Readiness

- Plan 01-04 (service wiring) can consume the full public API above; recovery is synchronous by design for onStartCommand
- Threat mitigations T-03-01/02/03 implemented as specified in the plan threat model; no new threat surface introduced (store writes only within the caller-supplied dir; no network, no Context, no external storage reachability)

## Self-Check: PASSED

- Files verified on disk: SessionStore.kt, SessionStoreTest.kt, 01-03-SUMMARY.md
- Commits verified in git log: b0f7847, b3ccd2a, cc4c5ba, 5017f1c
- 20/20 SessionStoreTest tests green; full shared+phone suite green
