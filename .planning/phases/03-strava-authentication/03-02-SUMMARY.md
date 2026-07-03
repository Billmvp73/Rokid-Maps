---
phase: 03-strava-authentication
plan: 02
subsystem: auth
tags: [strava, oauth, android, encrypted-shared-preferences, custom-tabs, okhttp, deep-link]
requires:
  - "03-01: StravaOAuth seams, StravaModels (parseTokenResponse/StoredTokens), TokenRefreshCoordinator + TokenPersistence/RefreshTransport/RefreshOutcome"
provides:
  - "StravaTokenStore : TokenPersistence over EncryptedSharedPreferences (MasterKey.Builder AES256_GCM; AES256_SIV keys / AES256_GCM values) with injected-factory catch-and-reset — JVM-tested"
  - "StravaAuthManager: launchAuthorize(Activity): Boolean (Custom Tab, empty-keys guard), handleCallback(String?): CallbackResult (state single-use -> validate -> code -> scopes -> exchange), OkHttp RefreshTransport on a PLAIN client, ensureFreshToken/forceRefresh/retryTokenAfter401/isConnected/connectedAthleteName/disconnect"
  - "CallbackResult sealed contract: Connected(athleteName) | StateMismatch | Denied | ScopesIncomplete | ExchangeFailed (Wave 3 maps these to card state + toasts)"
  - "StravaApiClient.getAthlete() + StravaAuthenticator (responseCount >= 2 give-up, single 401 retry) with both rate-limit header pairs logged"
  - "StravaCallbackActivity (exported, Theme.NoDisplay) + rokidhud://callback intent-filter; ACTION_STRAVA_CALLBACK / EXTRA_CALLBACK_URI forwarding contract to MainActivity"
  - "BuildConfig.STRAVA_CLIENT_ID / STRAVA_CLIENT_SECRET from local.properties (empty-string defaults); browser pinned 1.8.0 + security-crypto 1.1.0; strava_auth.xml backup-excluded in all three rule sections"
affects:
  - "03-03 (MainActivity card wires launchAuthorize/handleCallback/CallbackResult + onNewIntent routing; debug forceRefresh hook)"
  - "03-04 (device gate: adb resolve-activity, state-mismatch negative test, rt# rotation grep, logcat redaction scan)"
  - "Phase 4/5 (StravaApiClient is the authenticated transport for routes + uploads)"
tech-stack:
  added:
    - "androidx.security:security-crypto:1.1.0 (minCompileSdk 34 — resolved clean against AGP 8.7.3)"
    - "androidx.browser:browser:1.8.0 (PINNED — 1.9.0+ needs compileSdk 36/AGP 8.9.1)"
  patterns:
    - "Injected espFactory/onCorrupt constructor so ESP reset logic is JVM-testable without Keystore"
    - "Token endpoints on a PLAIN OkHttpClient; only resource calls carry the Authenticator (no refresh recursion)"
    - "Consume-then-validate nonce: pending state removed from prefs before any check, dead after one attempt"
key-files:
  created:
    - phone/src/main/java/com/rokid/hud/phone/strava/StravaTokenStore.kt
    - phone/src/main/java/com/rokid/hud/phone/strava/StravaAuthManager.kt
    - phone/src/main/java/com/rokid/hud/phone/strava/StravaApiClient.kt
    - phone/src/main/java/com/rokid/hud/phone/strava/StravaCallbackActivity.kt
    - phone/src/test/java/com/rokid/hud/phone/strava/StravaTokenStoreTest.kt
  modified:
    - phone/build.gradle.kts
    - phone/src/main/res/xml/backup_rules.xml
    - phone/src/main/res/xml/data_extraction_rules.xml
    - phone/src/main/AndroidManifest.xml
decisions:
  - "rt# hash hoisted to a local val before the exchange-ok Log.i so the no-secrets log grep stays at zero while the rt#=<hex> format the 03-04 rotation gate pins is unchanged"
  - "CallbackResult declared top-level in StravaAuthManager.kt (matches RefreshOutcome precedent in TokenRefreshCoordinator.kt) — Wave 3 references CallbackResult.Connected directly"
  - "Omitted the plan's unused private Gson field in StravaAuthManager — all token-body parsing goes through Wave-1 parseTokenResponse"
metrics:
  duration: "~6 min"
  completed: "2026-07-03"
  tasks: 3
  tests-added: 8
  files-created: 5
  files-modified: 4
---

# Phase 3 Plan 02: Strava Android Integration Summary

**One-liner:** The entire AUTH-01/02/03 machine now exists on Android — encrypted backup-excluded token store with tested catch-and-reset, Custom-Tab authorize with single-use CSRF nonce and fail-closed scope validation, exact Strava exchange/refresh contracts on a plain OkHttp client, an exported no-UI deep-link forwarder, and an authenticated GET /athlete client with proactive refresh plus one bounded 401 retry.

## What Was Built

### StravaTokenStore (ESP + catch-and-reset, JVM-tested)
- Implements Wave-1 `TokenPersistence` over EncryptedSharedPreferences file `strava_auth` (matches all three backup-exclusion rule entries).
- security-crypto 1.1.0 API only: `MasterKey.Builder(context).setKeyScheme(AES256_GCM)` + `EncryptedSharedPreferences.create(..., AES256_SIV, AES256_GCM)` — zero references to the deprecated 1.0-era key API.
- Injected-factory primary constructor (`espFactory`, `onCorrupt`, `log`) makes the reset path unit-testable; the `Context` secondary constructor wires `createEsp` + `deleteSharedPreferences(PREFS_FILE)`.
- Corruption path (simulated AEADBadTagException on first creation): file deleted exactly once, second creation succeeds, store works. Both-fail path: `load()` null, `save()`/`clear()` no-op — degrades to the disconnected state, never a crash loop.
- `load()` returns null unless access/refresh non-blank AND expires_at present (partial write = disconnected, never a half-token); `athleteId` uses a -1 sentinel for null.
- 8 new JVM tests in StravaTokenStoreTest via a ~55-line HashMap-backed `FakeSharedPreferences` + `FakeEditor`.

### StravaAuthManager (Custom Tab, state lifecycle, exchange, refresh transport)
- `launchAuthorize(activity)`: refuses on empty BuildConfig keys (Pitfall 7), generates the SecureRandom state, persists it in PLAIN prefs `strava_oauth` (nonce is not a secret; no main-thread Keystore work at CONNECT tap), launches `CustomTabsIntent` at the mobile authorize URL, catches `ActivityNotFoundException`.
- `handleCallback(uriString)` — BLOCKING, `Thread{}` only. Hard-ordered pipeline: **consume** pending state (removed before any check — single-use regardless of outcome) → constant-time `validateState` with **no network on mismatch** → missing `code` = `Denied` (robust to Strava's exact error param) → `grantedScopesComplete` fail-closed **before** spending the single-use code → `exchangeCode`.
- `exchangeCode`: POST exactly `client_id, client_secret, code, grant_type=authorization_code` to `StravaOAuth.TOKEN_URL` — the file contains zero occurrences of the RFC-standard callback-URI form field (verified Strava deviation). Persists `StoredTokens` with athlete id + display name; logs `exchange ok, token stored, expires_at=... rt#=<unsigned hex>` for the 03-04 rotation gate.
- RefreshTransport (inline object): POST `client_id, client_secret, grant_type=refresh_token, refresh_token` on the PLAIN `tokenClient` (no authenticator — refresh can never re-enter the Authenticator); 2xx→`Success`/`TransientError("malformed body")`, non-2xx→`Rejected(code)`, exceptions→`TransientError`. Wipe/rotation/single-flight policy stays in the Wave-1 coordinator, wired via `TokenRefreshCoordinator(store, transport, log = { Log.i(TAG, it) })`.
- Thin delegation: `ensureFreshToken`/`forceRefresh`/`retryTokenAfter401`; `isConnected`/`connectedAthleteName` (BLOCKING ESP reads); `disconnect()` = local wipe only.

### CallbackResult contract (Wave 3 consumes this)

```kotlin
sealed class CallbackResult {
    data class Connected(val athleteName: String) : CallbackResult()
    object StateMismatch : CallbackResult()
    object Denied : CallbackResult()
    object ScopesIncomplete : CallbackResult()
    object ExchangeFailed : CallbackResult()
}
```

Top-level in `StravaAuthManager.kt`. MainActivity (03-03) routes `ACTION_STRAVA_CALLBACK` / `EXTRA_CALLBACK_URI` (constants on `StravaCallbackActivity`) into `handleCallback` on a `Thread{}` and maps each variant to card state + toast.

### StravaApiClient + StravaAuthenticator
- `getAthlete()`: proactive `auth.ensureFreshToken()` PRIMARY (30-min window inside the coordinator) → Bearer GET `https://www.strava.com/api/v3/athlete` → logs BOTH `X-RateLimit-Usage` and `X-ReadRateLimit-Usage` → Gson parse. Never rethrows.
- `StravaAuthenticator`: reactive fallback — `responseCount >= 2` give-up (official OkHttp pattern), single retry with `auth.retryTokenAfter401(failedToken)` (coordinator's already-refreshed double-check avoids a second network refresh).
- Wire logging DEBUG-only at `Level.BASIC` — no verbose levels anywhere in the file.
- The `/api/v3` asymmetry is load-bearing and documented: resource calls use the prefix, token endpoints don't.

### StravaCallbackActivity + manifest
- Plain `Activity`, `Theme.NoDisplay`, finishes synchronously in `onCreate` after forwarding `intent.data.toString()` to MainActivity with `CLEAR_TOP|SINGLE_TOP` (warm delivery via onNewIntent, cold via onCreate, Custom Tab popped off the back stack).
- Minimal exported surface (T-03-07): reads only `intent.data`; logs the event line only, never the URI.
- Manifest: own `<activity>` block after DeviceScanActivity with exactly one `<intent-filter>` (`VIEW` + `DEFAULT` + `BROWSABLE`, `scheme="rokidhud"` `host="callback"`), `android:exported="true"`, no App Links verification attribute. MainActivity block untouched (launchMode stays standard).

### Build wiring
- **Dependency resolution confirmed:** `androidx.browser:browser:1.8.0` and `androidx.security:security-crypto:1.1.0` both resolve and `assembleDebug` exits 0 against compileSdk 34 / AGP 8.7.3 (the browser pin is the whole point — 1.9.0+ requires compileSdk 36 + AGP 8.9.1).
- `STRAVA_CLIENT_ID` / `STRAVA_CLIENT_SECRET` BuildConfig fields read `strava.client.id` / `strava.client.secret` with empty-string defaults, matching the rokid.* precedent exactly — the build stays green with no keys present (verified: this worktree built with an sdk.dir-only local.properties).
- `strava_auth.xml` sharedpref exclusion in backup_rules.xml (1) + data_extraction_rules.xml under BOTH `<cloud-backup>` and `<device-transfer>` (2) = 3 total. Existing `activities/` excludes and the Phase-1 T-03-01 comments untouched.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] rt# hash hoisted out of the Log.i interpolation**
- **Found during:** Task 2 acceptance-criteria check
- **Issue:** The action step prescribed `Log.i(TAG, "... rt#=${Integer.toHexString(r.refreshToken!!.hashCode())}")`, but the acceptance criterion requires `grep -cE 'Log\.[diwe]\(TAG, ".*(accessToken|refreshToken|client_secret|\$code)'` to return 0 — the identifier `refreshToken` inside the log string matches the gate. The two prescriptions are mutually exclusive.
- **Fix:** `val rtHash = Integer.toHexString(r.refreshToken!!.hashCode())` on the preceding line; the log emits the identical `rt#=<unsigned hex>` output the 03-04 device gate greps for, and the no-secrets gate passes.
- **Files modified:** phone/src/main/java/com/rokid/hud/phone/strava/StravaAuthManager.kt
- **Commit:** 571023b

**2. [Rule 1 - Dead code] Unused `private val gson = Gson()` omitted from StravaAuthManager**
- **Found during:** Task 2 implementation
- **Issue:** The plan listed the field as "(reused)", but every token-endpoint body goes through Wave-1 `parseTokenResponse` (which owns its own Gson) — the manager never parses JSON directly, so the field would be an unused warning-producing member.
- **Fix:** Field omitted. `StravaApiClient` (which does parse athlete JSON directly) keeps its own reused `Gson` instance per plan.
- **Files modified:** phone/src/main/java/com/rokid/hud/phone/strava/StravaAuthManager.kt
- **Commit:** 571023b

**3. [Rule 2 - Missing error handling] Broad `catch (e: Exception)` added after IOException in exchangeCode and the refresh transport**
- **Found during:** Task 2 implementation
- **Issue:** The plan enumerated only IOException for exchangeCode, but `handleCallback` runs on a raw `Thread{}` (Wave 3) where any uncaught runtime exception crashes the process — CLAUDE.md convention: "Wrap every I/O or SDK call in try/catch. Never propagate exceptions to callers."
- **Fix:** Second catch clause logging + returning null (exchange) / `TransientError` (refresh transport, which the plan itself specified as "catch IOException/Exception").
- **Files modified:** phone/src/main/java/com/rokid/hud/phone/strava/StravaAuthManager.kt
- **Commit:** 571023b

### Notes (non-deviations)
- Verification commands ran from the worktree root instead of the plan's literal `cd /Users/bilhuang/Documents/rokid-maps` (worktree path safety; identical Gradle invocation form).
- No deviation from RESEARCH Patterns 2–5 beyond what the plan itself designed: Pattern 2 verbatim minus the toast (UI feedback is Wave 3's job — `launchAuthorize` returns Boolean); Pattern 3 verbatim plus the event log line; Pattern 4 restructured behind the plan's injected factory (reset semantics identical, now JVM-tested); Pattern 5's lock/wipe policy delegated to the Wave-1 coordinator via `retryTokenAfter401` instead of the sketch's shared-lock-object access (the plan's design — no policy re-implementation).

## User Setup Still Required (03-04 gate)
- Create a Strava API application at strava.com/settings/api with Authorization Callback Domain exactly `rokidhud` (bare word).
- Add `strava.client.id` / `strava.client.secret` to the main checkout's local.properties. Until then the build stays green and Wave 3's card shows the setup hint (empty-string BuildConfig defaults).

## TDD Gate Compliance

Task 1 (`tdd="true"`) followed RED → GREEN:

| Task | RED (test commit) | GREEN (feat commit) |
|------|-------------------|---------------------|
| 1 — StravaTokenStore | 9d65035 (compile-fail: unresolved StravaTokenStore) | 320b394 (8/8 green + assembleDebug) |

No REFACTOR commit needed. Tasks 2–3 were non-TDD per plan.

## Commits

| Hash | Type | Description |
|------|------|-------------|
| 9d65035 | test | Failing tests for StravaTokenStore round-trip and catch-and-reset |
| 320b394 | feat | Encrypted token store + pinned deps + BuildConfig fields + backup exclusions |
| 571023b | feat | StravaAuthManager + StravaCallbackActivity + manifest intent-filter |
| 9066cab | feat | StravaApiClient + StravaAuthenticator with bounded 401 retry |

## Verification Results

- `:phone:testDebugUnitTest :shared:testDebugUnitTest assembleDebug` — exit 0 (both APKs; 110 tests, 0 failures: strava package 45 = Wave-1 37 + 8 new; Phase-1/2 suites unaffected).
- `grep -c 'strava_auth.xml'` across the two rule files totals 3 (1 + 2).
- MainActivity manifest block untouched (diff against the wave base shows no MainActivity/launchMode changes).
- Every task acceptance grep passed, including: browser 1.8.0 pinned with zero 1.9/1.10 matches; `MasterKey.Builder` present with zero matches for the deprecated 1.0 key API name; zero occurrences of the callback-URI form field in StravaAuthManager.kt; `responseCount >= 2`; both rate-limit headers; `BuildConfig.DEBUG` + `Level.BASIC` with zero verbose logging levels; no token/secret material in any log statement.

## Threat Model Dispositions Applied

| Threat | Mitigation implemented |
|--------|------------------------|
| T-03-01 (callback CSRF) | consume-then-validate single-use nonce; constant-time compare (Wave 1); mismatch rejected before any network call |
| T-03-02 (tokens at rest) | ESP AES256-SIV/AES256-GCM + Keystore master key; strava_auth.xml excluded in all 3 backup-rule sections |
| T-03-03 (secret in APK) | Accepted per locked decision — BuildConfig injection matches the rokid.* precedent, nothing further |
| T-03-04 (log disclosure) | Events/status/expires_at/rt# hex only; no URI, code, or token values logged; interceptor DEBUG-only at BASIC; no-secrets grep gates green |
| T-03-05 (auth UI spoofing) | Custom Tab (browser 1.8.0) at the mobile authorize endpoint; no WebView anywhere |
| T-03-06 (401 refresh loop) | responseCount >= 2 give-up; coordinator single-flight lock; token endpoints on the plain client; wipe only on 400/401 (Wave 1) |
| T-03-07 (exported surface) | StravaCallbackActivity reads only intent.data, no UI/state, forwards + finishes synchronously |
| T-03-08 (scope deselection) | grantedScopesComplete fail-closed BEFORE the code is spent; tokens never persisted on partial grant |
| T-03-SC (supply chain) | Only the two RESEARCH-audited first-party androidx artifacts added, at the exact audited versions |

No new threat surface beyond the plan's threat model (the plain `strava_oauth` nonce prefs file is plan-prescribed and carries no secret).

## Known Stubs

None — every function is fully implemented; no TODO/FIXME/placeholder patterns in any created or modified file.

## For Wave 3 (03-03)

Wire in MainActivity: `StravaAuthManager(context, StravaTokenStore(context))` in onCreate; route `ACTION_STRAVA_CALLBACK` in both `onNewIntent` and `onCreate` to `Thread { handleCallback(intent.getStringExtra(StravaCallbackActivity.EXTRA_CALLBACK_URI)) }`; map `CallbackResult` variants to card state/toasts via `runOnUiThread`; CONNECT tap → `launchAuthorize(this)` (false → keys-missing hint or no-browser toast); DISCONNECT → `Thread { disconnect() }`; card render reads `connectedAthleteName()` on a background thread (first ESP access is slow — Pitfall 6). Debug rotation hook → `forceRefresh()`.

## Self-Check: PASSED

All 5 created source/test files and the SUMMARY exist on disk; all 4 task commits (9d65035, 320b394, 571023b, 9066cab) present in git log.
