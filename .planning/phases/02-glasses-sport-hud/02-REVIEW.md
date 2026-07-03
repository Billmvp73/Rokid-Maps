---
phase: 02-glasses-sport-hud
reviewed: 2026-07-03T20:33:23Z
depth: standard
files_reviewed: 8
files_reviewed_list:
  - glasses/build.gradle.kts
  - glasses/src/main/java/com/rokid/hud/glasses/SportFormat.kt
  - glasses/src/main/java/com/rokid/hud/glasses/HudState.kt
  - glasses/src/main/java/com/rokid/hud/glasses/HudView.kt
  - glasses/src/main/java/com/rokid/hud/glasses/BluetoothClient.kt
  - glasses/src/main/java/com/rokid/hud/glasses/HudActivity.kt
  - glasses/src/test/java/com/rokid/hud/glasses/SportFormatTest.kt
  - glasses/src/test/java/com/rokid/hud/glasses/HudStateTest.kt
findings:
  critical: 0
  warning: 3
  info: 6
  total: 9
status: issues_found
---

# Phase 2: Code Review Report — Glasses Sport HUD

**Reviewed:** 2026-07-03T20:33:23Z
**Depth:** standard
**Files Reviewed:** 8 (Phase 2 delta, verified against `git diff 39a9665..HEAD`)
**Status:** issues_found

## Summary

The Phase 2 delta is well-constructed overall. The five focus areas were each traced end to end:

- **Ticker lifecycle (verified sound):** Every arm site (`HudView.kt:150`, `:178`) does `removeCallbacks` before `postDelayed`, so at most one pending `sportTick` exists; `onDetachedFromWindow` (`HudView.kt:181-184`) removes it; a `postDelayed` on a detached view lands in the view's `HandlerActionQueue` and is either cleaned up by the attach-time re-arm (`:178` removes first) or GC'd with the view. No leak path, no double-chain path found. One comment overclaim noted (IN-02).
- **`applySportState`/`sportDisplayMode` (verified correct):** pure functions with injected clocks; strict-`>` thresholds match both the doc comments (`HudState.kt:86-89`) and the boundary tests; FINISHED precedence is staleness-immune; never-received (`lastSportStateAtMs == 0`) and `"idle"` both map to NOT_RECORDING; unknown `sessionState`/`sport` values degrade leniently (`state.sport != "run"` at `HudView.kt:669` handles unknown sports). One misleading comment (IN-03) and one input-guard asymmetry (IN-04).
- **Rendering (verified clean):** `drawSportLayout` uses only the pre-allocated sport paints and mutates colors per pass — zero per-frame `Paint`/`Path` allocation in the new code (the pre-existing allocations in `drawDirections`/`drawCompass`/etc. are not Phase 2 delta). No division-by-zero exists in pace/speed/distance formatting; the sub-1m `formatDistance` empty-string gap is explicitly handled (`HudView.kt:716-718`); the moving-dot `measureText` offset stays on-canvas for all realistic numerals.
- **Threading:** the accepted T-02-05 race disposition holds but its wording under-enumerates the manifestations (IN-01); `hudView.state` writes are main-thread-confined via `runOnUiThread`, so the setter's transition detection is race-free.
- **Tests:** assertions are worked vectors, not tautologies. Two real gaps: locale-dependent assertions (WR-02) and an untested "unbounded rollover" claim (IN-05).

Three Warnings: SPORT mode is silently evicted by any Settings message including the automatic re-send on BT reconnect (WR-01); `SportFormat` + its tests are default-locale-sensitive (WR-02); and one pre-existing always-false conditional in the reviewed `onStateUpdate` block that disables tile-cache resizing (WR-03, out of Phase 2 delta, flagged for triage).

## Warnings

### WR-01: Any Settings message — including the automatic re-send on BT reconnect — silently evicts SPORT mode mid-activity

**File:** `glasses/src/main/java/com/rokid/hud/glasses/BluetoothClient.kt:202-211`
**Issue:** The Settings branch unconditionally recomputes `layoutMode`:

```kotlin
val layoutMode = if (parsed.msg.useMiniMap) {
    when (parsed.msg.miniMapStyle) {
        "split" -> MapLayoutMode.MINI_SPLIT
        else -> MapLayoutMode.MINI_BOTTOM
    }
} else MapLayoutMode.FULL_SCREEN
```

The phone re-sends cached settings to every newly connected client (`phone/.../HudStreamingService.kt:586-597`, invoked at `:757`) and on every settings change. So the primary Phase 2 use case — rider in SPORT mode, phone in pocket — is broken by a routine BT drop/reconnect: the glasses snap back to FULL_SCREEN and the rider must tap twice while moving to restore SPORT. Any phone-side toggle (TTS, cache size, etc.) does the same. This interaction is a direct consequence of the new tap-cycle-only SPORT mode; unlike the state race (T-02-05, explicit `accept` in `02-02-PLAN.md:148`), it has no recorded disposition — `02-04-PLAN.md:132` merely works around it during verification ("Re-enter SPORT on the glasses if a reconnect Settings message reset the layout"), confirming it fires in practice.
**Fix:** Preserve the glasses-local SPORT choice when the incoming settings do not request a mini-map override (Mini behavior and Phase 2 SC#4 unchanged):

```kotlin
val layoutMode = if (parsed.msg.useMiniMap) {
    when (parsed.msg.miniMapStyle) {
        "split" -> MapLayoutMode.MINI_SPLIT
        else -> MapLayoutMode.MINI_BOTTOM
    }
} else if (currentState.layoutMode == MapLayoutMode.SPORT) {
    MapLayoutMode.SPORT // tap-cycle choice survives settings re-sends
} else MapLayoutMode.FULL_SCREEN
```

(Optionally extend the same preservation to SMALL_CORNER, but that reset is pre-existing behavior.) Alternatively, record an explicit accept disposition mirroring T-02-05 if v1 deliberately keeps this.

### WR-02: SportFormat and its tests depend on the JVM/device default locale — tests fail on non-English-locale machines

**File:** `glasses/src/main/java/com/rokid/hud/glasses/SportFormat.kt:28,40,47`; `glasses/src/test/java/com/rokid/hud/glasses/SportFormatTest.kt:17-19,25-30,41-43`
**Issue:** All three formatters use single-argument `String.format(...)`, which formats with `Locale.getDefault()`. The new tests then hard-assert dot-decimal, Latin-digit output (`assertEquals("22.3", SportFormat.formatSpeed(6.2, imperial = false))`). On a JVM whose default locale uses comma decimals (de, fr, es, …) `formatSpeed` returns `"22,3"` and the suite fails — the module's first unit tests are non-deterministic across developer machines/CI locales. On-device the same mechanism renders locale digits: under Arabic-family locales `%d`/`%02d` produce U+0660-range digits, exactly the glyph-coverage risk the implementation itself avoids elsewhere (`HudView.kt:656` chooses ASCII `"--"` over em-dash for "tofu risk"). `SportFormat.kt:45` documents "Locale-default like formatDistance" as deliberate, but the tests contradict that choice by pinning expected output to one locale's formatting.
**Fix:** Pin the locale in `SportFormat` (a monochrome HUD renders fixed glyphs, not locale-sensitive prose):

```kotlin
String.format(Locale.US, "%d:%02d:%02d", totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)
// likewise SportFormat.kt:40 and :47
```

and update the `SportFormat.kt:45` comment. (Pinning `Locale.US` only inside the tests would fix determinism but leave the device digit risk.)

### WR-03: [PRE-EXISTING — outside Phase 2 delta] Tile-cache resize comparison is always false; `updateCacheSize` is unreachable from settings

**File:** `glasses/src/main/java/com/rokid/hud/glasses/HudActivity.kt:99-102`
**Issue:** Found while reviewing the `onStateUpdate` marshaling path (focus #1). The guard compares `newState` against `hudView.state` *after* `hudView.state` was assigned from `newState` at `:92-95`:

```kotlin
hudView.state = newState.copy(batteryLevel = ..., wifiConnected = ...)   // :92-95
...
if (newState.tileCacheSizeMb != hudView.state.tileCacheSizeMb) {         // always false
    tileManager.updateCacheSize(newState.tileCacheSizeMb)                // dead call
}
```

The `copy` does not alter `tileCacheSizeMb`, so both sides are always equal and `TileManager.updateCacheSize` (`TileManager.kt:89`) is never invoked — the phone's cache-size setting silently never applies on the glasses. Introduced in commit `55f707d`, before Phase 2; reported here because it lives inside the reviewed lambda. Not a Phase 2 regression.
**Fix:** Capture the previous value before assignment:

```kotlin
val prevCacheSizeMb = hudView.state.tileCacheSizeMb
hudView.state = newState.copy(...)
...
if (newState.tileCacheSizeMb != prevCacheSizeMb) {
    tileManager.updateCacheSize(newState.tileCacheSizeMb)
}
```

## Info

### IN-01: T-02-05 RMW race — acceptance holds, but two manifestations exceed the recorded worst case

**File:** `glasses/src/main/java/com/rokid/hud/glasses/BluetoothClient.kt:56-59,165-264`
**Issue:** Assessment requested by review focus #1. `currentState` is `@Volatile` (visibility only); read-modify-write now happens from two threads: the reader thread (`processMessage`, 9 copy sites, plus `connectLoop` at `:93,:102`) and the main thread (`toggleLayout` at `:57`). The accepted disposition (`02-02-PLAN.md:148`) records the worst case as "one message's fields lag one second." Two additional same-class manifestations exist: (a) if the reader's RMW straddles the toggle's write, the tap is lost outright (user re-taps); (b) if the reader calls `onStateUpdate` with a pre-toggle snapshot concurrently with the tap handler, the posted `runOnUiThread` lambda applies the stale layout *after* the toggle's synchronous update — the display reverts for up to one message period, then self-heals. All windows are microseconds against a 1Hz stream; all outcomes are transient and self-healing; the acceptance rationale (match the no-new-primitives codebase posture) remains sound.
**Fix:** None required under the accepted disposition. If posture ever changes, wrapping the three `currentState` writers in `synchronized(this)` closes all three manifestations without altering the callback contract.

### IN-02: sportTick comment overclaims "over-scheduling harmless"

**File:** `glasses/src/main/java/com/rokid/hud/glasses/HudView.kt:130-133`
**Issue:** The comment says "The layoutMode guard stops the chain automatically and makes over-scheduling harmless." The guard only stops chains when *not* in SPORT. While in SPORT, two concurrently pending posts of the self-rescheduling runnable would each re-post — a sustained 2Hz double chain. The real protection is that every arm site (`:150`, `:178`) calls `removeCallbacks` before `postDelayed`, guaranteeing at most one pending instance. A maintainer trusting the comment could add a bare `postDelayed(sportTick, ...)` and create the double chain.
**Fix:** Reword, e.g.: "The layoutMode guard stops the chain when leaving SPORT; arm sites must always removeCallbacks before postDelayed so at most one chain exists."

### IN-03: drawSportLayout comment misstates where sport-value leniency lives

**File:** `glasses/src/main/java/com/rokid/hud/glasses/HudView.kt:668` (vs `shared/.../ProtocolCodec.kt:260`)
**Issue:** "lenient: decode already defaults unknown sports to 'ride'" is inaccurate — `json.optString(FIELD_SPORT, "ride")` defaults only a *missing* `sp` field; unknown values (`"hike"`) pass through verbatim. The actual unknown-value handling is the `val isRide = state.sport != "run"` check on the next line. Behavior is correct; the comment could mislead someone into adding an exhaustive `when(sport)` elsewhere expecting normalized input.
**Fix:** Reword to: "lenient: missing sp decodes to 'ride', and any non-'run' value renders as ride here."

### IN-04: formatElapsed renders garbage for negative input; asymmetric with formatPace's guard

**File:** `glasses/src/main/java/com/rokid/hud/glasses/SportFormat.kt:26-29`
**Issue:** `formatPace` guards `msPerKm <= 0L` (`:37`), but `formatElapsed(-1000)` produces `"0:00:-1"` (`%02d` emits the sign). A negative `et` requires a buggy/hostile phone build — the current sender design uses monotonic elapsed — but the phone-side sender is still unimplemented, so the contract is unenforced, and the display-side guard is one line.
**Fix:** `val totalSec = (ms / 1000).coerceAtLeast(0)`.

### IN-05: Test coverage gaps — untested "unbounded rollover" claim and the 3s dim boundary

**File:** `glasses/src/test/java/com/rokid/hud/glasses/SportFormatTest.kt:14-20`; `glasses/src/test/java/com/rokid/hud/glasses/HudStateTest.kt:79-89`
**Issue:** The remaining assertions are genuine worked vectors (verified: 294000ms/km → 4:54 and 7:53/mi; 6.2 m/s → 22.3 / 13.9; strict-> dot threshold at exactly 0.7). Two gaps: (a) `formatElapsedRendersHoursMinutesSecondsWithUnboundedHourRollover` and the doc claim "hours roll unbounded past 9:59:59" (`SportFormat.kt:25`) have no vector above 1:00:00 — the >9:59:59 behavior the name claims is unasserted; (b) `stalenessLadder` tests the exact 10,000ms boundary (strict `>`) but not the exact 3,000ms boundary, leaving the DIM threshold's strictness unpinned.
**Fix:** Add `assertEquals("10:15:42", SportFormat.formatElapsed(36_942_000L))` and `assertEquals(SportDisplayMode.LIVE, s.sportDisplayMode(4_000L))` (exactly 3,000ms old).

### IN-06: [PRE-EXISTING — repo level] Unix `gradlew` launcher missing; new tests are not runnable from a fresh checkout with in-repo tooling

**File:** `glasses/build.gradle.kts:36` (test infra); repo root (only `gradlew.bat` is tracked)
**Issue:** The wrapper jar and properties are tracked, but the Unix `gradlew` script is neither tracked nor gitignored — on macOS/Linux (this repo's development platform) `./gradlew :glasses:test` fails with "no such file". Phase 2 adds the module's first unit tests, making this pre-existing gap operationally relevant: the executor ran Gradle from an environment-provided installation (`02-01-SUMMARY.md:98`), which a fresh clone or CI runner will not have. (Confirmed in this review environment: tests could not be executed; findings above are statically verified.)
**Fix:** Run `gradle wrapper --gradle-version 8.11.1` once and commit the generated `gradlew` script.

---

_Reviewed: 2026-07-03T20:33:23Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_

## Fix Log

| Finding | Status | Commit |
|---|---|---|
| WR-01 (SPORT evicted by settings re-send) | FIXED | 958f512 |
| WR-02 (locale-unpinned formatters) | FIXED | 7e6abf9 |
| WR-03 (dead cache-size comparison, pre-existing) | FIXED | 1e90f7c |
| IN-01..IN-06 | DEFERRED (accepted dispositions / polish) | — |
