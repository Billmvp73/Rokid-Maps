---
phase: 03-strava-authentication
plan: 01
subsystem: auth
tags: [strava, oauth, kotlin, gson, tdd, pure-jvm]
requires: []
provides:
  - "StravaOAuth pure-JVM object: AUTHORIZE_URL/TOKEN_URL/REDIRECT_URI/SCOPES/REQUIRED_SCOPES/REFRESH_WINDOW_SEC, buildAuthorizeUrl, newState, validateState, parseCallback, grantedScopesComplete, needsRefresh"
  - "All-nullable Gson models TokenResponse + StravaAthlete(displayName), non-null StoredTokens, parseTokenResponse validator"
  - "TokenRefreshCoordinator with TokenPersistence + RefreshTransport interfaces and RefreshOutcome sealed class (Wave 2 implements the two interfaces over ESP/OkHttp)"
affects:
  - 03-02 (StravaAuthManager/StravaApiClient/StravaTokenStore build against these signatures)
  - 03-03 (debug forceRefresh hook)
  - 03-04 (device-gate logcat grep pins Integer.toHexString rt# format)
tech-stack:
  added: []
  patterns:
    - "Pure-JVM OAuth decision seams (java.net.URI/URLEncoder, no android.*) for local unit testing"
    - "Single-flight refresh: store.load() inside one synchronized lock; second caller sees fresh token and skips network"
    - "Wipe-only-on-definitive-rejection: HTTP 400/401 clears tokens; 429/5xx/IOException/malformed-200 keep them"
key-files:
  created:
    - phone/src/main/java/com/rokid/hud/phone/strava/StravaOAuth.kt
    - phone/src/main/java/com/rokid/hud/phone/strava/StravaModels.kt
    - phone/src/main/java/com/rokid/hud/phone/strava/TokenRefreshCoordinator.kt
    - phone/src/test/java/com/rokid/hud/phone/strava/StravaAuthUrlTest.kt
    - phone/src/test/java/com/rokid/hud/phone/strava/StravaModelsTest.kt
    - phone/src/test/java/com/rokid/hud/phone/strava/TokenExpiryTest.kt
  modified: []
decisions:
  - "Reworded TOKEN_URL comment to 'NO api-v3 path prefix' (not the literal '/api/v3') so the acceptance gate grep -c 'api/v3' == 0 passes while keeping the Strava-quirk warning"
  - "needsRefresh boundary tests pinned in BOTH StravaAuthUrlTest (Task 1 behavior) and TokenExpiryTest (artifact spec) — cheap duplication locking the same truth"
  - "refreshLocked validates Success bodies itself (malformed 200 treated as transient) — wipe policy and validation both live in the coordinator, not the transport"
metrics:
  duration: "~10 min"
  completed: "2026-07-03"
  tasks: 2
  tests-added: 37
  files-created: 6
---

# Phase 3 Plan 01: Strava OAuth Pure-JVM Core Summary

**One-liner:** Pure-JVM Strava OAuth core — exact mobile-authorize URL builder, SecureRandom CSRF state with constant-time validation, fail-closed scope checking, all-nullable Gson token models, and a single-flight refresh coordinator with rotation persistence — regression-locked by 37 JVM tests before any Android wiring exists.

## What Was Built

### StravaOAuth (object, pure JVM)
Every AUTH-01/AUTH-03 rule that can be a pure function:
- `buildAuthorizeUrl(clientId, state)` produces the exact `https://www.strava.com/oauth/mobile/authorize` URL with params in locked order (client_id, redirect_uri, response_type=code, approval_prompt=auto, scope, state); commas encode to `%2C`
- `TOKEN_URL = "https://www.strava.com/oauth/token"` — no api-v3 segment (Integration Gotchas row 1, pinned by test assertion `TOKEN_URL.contains("/api/v3") == false`)
- `newState()` — 32 SecureRandom bytes as 64 lowercase-hex chars
- `validateState(expected, received)` — `MessageDigest.isEqual` constant-time compare; null/empty rejected (T-03-01 mitigation)
- `parseCallback(uriString?)` — `java.net.URI` (no android.net.Uri), returns emptyMap on any malformed input
- `grantedScopesComplete(scopeParam?)` — fail-closed against `REQUIRED_SCOPES` (Pitfall 4, T-03-08 mitigation)
- `needsRefresh(expiresAtSec, nowSec)` — inclusive 30-minute boundary (1799s true, 1800s true, 1801s false, past-expiry true)

### StravaModels (Gson, all-nullable)
- `TokenResponse` — six `@SerializedName` all-nullable fields; `athlete` present on initial exchange only (refresh bodies carry no athlete)
- `StravaAthlete` — Long id (Strava IDs exceed Int range); `displayName()` chain: "First Last" → username → "Strava athlete"
- `StoredTokens` — the NON-NULL persisted shape, only constructed after validation
- `parseTokenResponse(json?)` — Gson parse in try/catch + explicit post-parse validation (access_token/refresh_token non-blank, expires_at non-null); never throws

### TokenRefreshCoordinator (class, pure JVM)
Wave 2 implements exactly two small interfaces; all refresh policy is already tested here:

```kotlin
interface TokenPersistence {
    fun load(): StoredTokens?
    fun save(tokens: StoredTokens)
    fun clear()
}

interface RefreshTransport {
    fun refresh(refreshToken: String): RefreshOutcome
}

sealed class RefreshOutcome {
    data class Success(val response: TokenResponse) : RefreshOutcome()
    data class Rejected(val httpCode: Int) : RefreshOutcome()      // any non-2xx
    data class TransientError(val message: String?) : RefreshOutcome()  // IOException etc.
}

class TokenRefreshCoordinator(
    store: TokenPersistence,
    transport: RefreshTransport,
    nowSec: () -> Long = { System.currentTimeMillis() / 1000L },
    log: (String) -> Unit = {}
) {
    fun ensureFreshToken(): String?                       // proactive 30-min window
    fun forceRefresh(): String?                           // window ignored (Wave-3 debug hook)
    fun retryTokenAfter401(failedAccessToken: String?): String?  // Authenticator double-check
}
```

Policy locked by tests: single-flight (2-thread race → exactly ONE transport call, both threads get the fresh token), rotated refresh_token always persisted with athleteId/athleteName preserved, wipe only on HTTP 400/401, keep tokens on 429/5xx/TransientError/malformed-200, empty store returns null everywhere with zero transport calls. Log lambda carries expires_at + `Integer.toHexString(refreshToken.hashCode())` only — never token material (T-03-04; unsigned hex pinned for the 03-04 device-gate grep).

## Test Count (exact)

| Class | Tests |
|-------|-------|
| StravaAuthUrlTest | 14 |
| StravaModelsTest | 8 |
| TokenExpiryTest | 15 |
| **Strava package total** | **37** |

Full suite (`:phone:testDebugUnitTest :shared:testDebugUnitTest`): 102 tests, 0 failures — Phase-1/2 tests unaffected (ActivitySessionManagerTest 38, SessionStoreTest 20, ProtocolCodecTest 7).

VALIDATION Wave-0 gaps 1–3 closed: all three test classes exist and pass.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] TOKEN_URL comment reworded to satisfy the api/v3 acceptance grep**
- **Found during:** Task 1 acceptance-criteria check
- **Issue:** The plan's action step prescribed the comment text `"NO /api/v3 prefix — Strava quirk"` on TOKEN_URL, but the acceptance criterion requires `grep -c "api/v3" StravaOAuth.kt` to return 0 — the two are mutually exclusive
- **Fix:** Comment reworded to `"NO api-v3 path prefix on the token endpoint — Strava quirk (PITFALLS Integration Gotchas)"`; intent preserved, mechanical gate passes; the real invariant (`TOKEN_URL.contains("/api/v3") == false`) is pinned by a test assertion
- **Files modified:** phone/src/main/java/com/rokid/hud/phone/strava/StravaOAuth.kt
- **Commit:** ef3e834

### Notes (non-deviations)
- Verification commands ran from the worktree root instead of the plan's literal `cd /Users/bilhuang/Documents/rokid-maps` (worktree path safety; same Gradle invocation form otherwise).
- No deviation from RESEARCH Pattern-1/5/6 skeletons beyond what the plan itself specified: Pattern 1 implemented verbatim (plus the plan's nullable `parseCallback(String?)` signature); Pattern 5's single-flight/wipe policy moved into the extracted coordinator exactly as the plan designed; Pattern 6 models match field-for-field.
- Added a 429-keeps-tokens test beyond the enumerated behavior cases (must_haves truths list 429 explicitly).
- needsRefresh boundary tests exist in both StravaAuthUrlTest and TokenExpiryTest (Task 1 behavior coverage + Task 2 artifact spec).

## TDD Gate Compliance

Both tasks followed RED → GREEN with per-phase commits:

| Task | RED (test commit) | GREEN (feat commit) |
|------|-------------------|---------------------|
| 1 — StravaOAuth + models | 492cc0b (compile-fail: unresolved StravaOAuth/StravaModels) | ef3e834 (37→22 of 37 green) |
| 2 — TokenRefreshCoordinator | 9eb1ac9 (compile-fail: unresolved coordinator types) | 6fd5671 (all 37 green) |

No REFACTOR commits needed — implementations landed clean.

## Commits

| Hash | Type | Description |
|------|------|-------------|
| 492cc0b | test | Failing tests for StravaOAuth url/state/callback/scope and Gson models |
| ef3e834 | feat | StravaOAuth pure-JVM seams and all-nullable Gson models |
| 9eb1ac9 | test | Failing tests for TokenRefreshCoordinator single-flight refresh |
| 6fd5671 | feat | TokenRefreshCoordinator single-flight refresh with rotation and wipe policy |

## Verification Results

- `:phone:testDebugUnitTest --tests "com.rokid.hud.phone.strava.*"` — exit 0, 37/37 green
- Full suite `:phone:testDebugUnitTest :shared:testDebugUnitTest` — exit 0, 102 tests
- No `^import android` in any of the three new main-source files
- Zero dependency changes: no gradle file modified in any commit
- All acceptance greps pass, including `Integer.toHexString` (unsigned hex for the device-gate grep) and `httpCode == 400 || outcome.httpCode == 401`

## Threat Model Dispositions Applied

| Threat | Mitigation implemented |
|--------|------------------------|
| T-03-01 (CSRF) | 32-byte SecureRandom nonce, MessageDigest.isEqual, null/empty rejected — unit-asserted |
| T-03-04 (log disclosure) | Log lambda emits expires_at + rt# hashCode hex only |
| T-03-06 (auth-loss DoS) | Wipe only on 400/401; 429/5xx/transient keep tokens — unit-asserted |
| T-03-08 (partial grant) | grantedScopesComplete fails closed — unit-asserted |
| T-03-SC (supply chain) | Zero new dependencies (Gson 2.10.1 + JUnit 4.13.2 already resolved) |

No new threat surface introduced beyond the plan's threat model (no network endpoints, no file access, no schema changes — all pure JVM logic).

## Known Stubs

None — every function is fully implemented; no TODO/FIXME/placeholder patterns in any created file.

## For Wave 2 (03-02)

Build `StravaTokenStore : TokenPersistence` (EncryptedSharedPreferences) and a `RefreshTransport` implementation inside StravaAuthManager (plain OkHttpClient, FormBody `client_id, client_secret, grant_type=refresh_token, refresh_token`, map non-2xx → `Rejected(code)`, IOException → `TransientError(msg)`). Wire `TokenRefreshCoordinator(store, transport, log = { Log.i(TAG, it) })`. Do not re-implement any window/rotation/wipe logic — it is all inside the coordinator and already tested.

## Self-Check: PASSED

All 6 created source/test files and the SUMMARY exist on disk; all 4 task commits (492cc0b, ef3e834, 9eb1ac9, 6fd5671) present in git log.
