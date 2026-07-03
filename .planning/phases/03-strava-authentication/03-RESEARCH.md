# Phase 3: Strava Authentication - Research

**Researched:** 2026-07-03
**Domain:** OAuth 2.0 Authorization Code Grant (no PKCE) on Android — Chrome Custom Tabs, custom-scheme deep link, EncryptedSharedPreferences token storage, OkHttp authenticated client
**Confidence:** HIGH (all load-bearing claims verified live against official Strava docs, Google Maven artifact metadata, and OkHttp docs on 2026-07-03)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

#### OAuth redirect mechanics
- Custom scheme deep link: `rokidhud://callback` as the redirect_uri parameter
- Strava app settings "Authorization Callback Domain" = `rokidhud` (bare word, no scheme/slashes — PITFALLS Integration Gotchas)
- Authorization via Chrome Custom Tab (androidx.browser) against `https://www.strava.com/oauth/mobile/authorize` (AUTH-01, PITFALLS: mobile endpoint, never WebView)
- Token exchange/refresh POST `https://www.strava.com/oauth/token` (NO /api/v3 prefix — PITFALLS gotcha)
- Callback intent-filter: its own `<intent-filter>` block on MainActivity (or a dedicated lightweight activity if cleaner) with BROWSABLE + DEFAULT categories, android:exported="true" (Android 12+ requirement)
- Scopes comma-delimited (NOT space): `read,read_all,activity:write`

#### Scope set
- `read,read_all,activity:write` requested at first auth — read_all covers private-route listing (settles the STATE todo question); activity:write pre-granted for Phase 5 so no re-auth mid-milestone

#### Token lifecycle
- Proactive refresh: before any authenticated call, if expires_at is within 30 minutes, refresh first (AUTH-03 authoritative — STATE todo resolution: proactive primary)
- Reactive fallback: OkHttp Authenticator retries once on 401 after a synchronized refresh (ARCHITECTURE Pattern 3 demoted to fallback layer)
- Storage: EncryptedSharedPreferences (androidx.security:security-crypto 1.1.0 — deprecated-but-final, per STACK.md) holding access_token, refresh_token, expires_at, athlete_id, athlete_name
- Refresh-token rotation: Strava returns a new refresh_token on every refresh — always persist the returned one
- Both-tokens-invalid → surface "Reconnect to Strava" state (PITFALLS recovery); local activity data untouched

#### Credentials
- client_id + client_secret from local.properties keys `strava.client.id` / `strava.client.secret` → BuildConfig fields (matching the existing rokid.* credential pattern); missing values → BuildConfig empty strings → Strava card shows "Set up Strava API keys" hint instead of Connect (graceful dev-mode degradation)
- Accepted risk (locked project decision): secret extractable from APK; sideloaded single-user app

#### UI placement & connection state
- New STRAVA card on MainActivity between RECORD and GLASSES SETTINGS: disconnected → "CONNECT STRAVA" button; connected → "Connected as {athlete_name}" + DISCONNECT button
- Disconnect = wipe stored tokens/athlete locally only (no remote deauthorize call in v1)
- Success toast on auth completion ("Connected to Strava as {name}" — PITFALLS UX)
- All Strava code in package com.rokid.hud.phone.strava (ARCHITECTURE)

### Claude's Discretion
- StravaAuthManager/StravaApiClient class split details within the researched architecture
- Exact card styling (existing bg_card conventions)
- Custom-tab vs dedicated callback activity implementation detail

### Deferred Ideas (OUT OF SCOPE)
- Remote deauthorize on disconnect — v1.x
- Multiple Strava accounts — out of scope
- Certificate pinning for strava.com — noted in PITFALLS security; defer unless research flags it as trivial (risk-accept per single-user app)
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| AUTH-01 | Login via OAuth 2.0 Authorization Code Grant, Chrome Custom Tab, `https://www.strava.com/oauth/mobile/authorize`, comma-delimited scopes, no PKCE, client_secret via BuildConfig | Mobile endpoint + all authorize params + token-exchange form fields verified live against official docs (Findings §Strava Facts). Custom Tab minimal-launch + auto-fallback verified (§Architecture Patterns 2). Callback activity pattern (§Pattern 3) avoids duplicate MainActivity. BuildConfig injection pattern verified in phone/build.gradle.kts (rokid.* precedent, buildConfig=true already on). |
| AUTH-02 | Tokens persist securely across restarts in EncryptedSharedPreferences | security-crypto 1.1.0 verified compatible (minCompileSdk=34 — checked from actual AAR metadata). MasterKey init pattern + backup-exclusion requirement (Pitfall 2) + catch-and-reset recovery (§Pattern 4). |
| AUTH-03 | Auto-refresh before expiry (6-hour token) when making Strava API calls | 6h lifetime + rotation + `grant_type=refresh_token` form fields verified live. Proactive 30-min window (locked) + single-flight synchronized refresh + OkHttp Authenticator fallback with responseCount guard (§Pattern 5, verified against OkHttp official recipes). |
</phase_requirements>

## Summary

Everything the locked decisions assume was re-verified live on 2026-07-03 and holds, with three corrections/hard constraints the planner must fold in:

1. **androidx.browser is pinned to 1.8.0.** Versions 1.9.0/1.10.0 require `minCompileSdk=36` and AGP ≥ 8.9.1 (verified from AAR metadata on Google Maven); this project is compileSdk 34 / AGP 8.7.3. 1.8.0 requires only compileSdk 34 and is fully sufficient for a one-shot `launchUrl`. This also definitively rules out the newer AuthTab API (1.9.0+) — the locked intent-filter deep-link mechanism is the right and only choice here. security-crypto 1.1.0 is compatible (minCompileSdk=34, minAGP 8.1.1).

2. **Rate limits are lower than the figures in CLAUDE.md.** Official docs today: new apps get **200 requests/15 min and 2,000/day overall; 100 reads/15 min and 1,000 reads/day** (reads = non-upload endpoints). The "300 reads/15min, 1,000 writes/15min" figure in CLAUDE.md is stale. Irrelevant for Phase 3 volumes (a handful of calls) but resolves the STATE todo — log both `X-RateLimit-Usage` and the newer `X-ReadRateLimit-Usage` headers.

3. **The EncryptedSharedPreferences file must be excluded from Auto Backup.** The Keystore master key never leaves the device; a restored prefs file is undecryptable and throws `AEADBadTagException` at init. The project's backup rules currently exclude only `activities/` — the plan must add a `sharedpref` exclusion for the token prefs file in both `backup_rules.xml` and `data_extraction_rules.xml`, plus a catch-and-reset wrapper so a corrupt store degrades to "Reconnect to Strava" instead of crashing.

Scope semantics verified: `read` covers **public** routes only; `read_all` = "read private routes, private segments, and private events for the user" — the locked `read,read_all,activity:write` set is correct for Phase 4 private-route listing. Refresh-token rotation confirmed ("once a new refresh token code has been returned, the older code will no longer work"). Token exchange takes exactly `client_id, client_secret, code, grant_type=authorization_code` (no redirect_uri — a Strava deviation from standard OAuth); refresh takes `client_id, client_secret, grant_type=refresh_token, refresh_token`.

**Primary recommendation:** Build four small pieces in `com.rokid.hud.phone.strava` — `StravaTokenStore` (ESP wrapper, backup-excluded, catch-and-reset), `StravaAuthManager` (state param, authorize URL, code exchange, single-flight refresh, plain OkHttp client for token endpoints), `StravaApiClient` (OkHttp with proactive-refresh + Authenticator fallback, blocking methods called from `Thread{}` per codebase convention), and a dedicated no-UI `StravaCallbackActivity` (exported, intent-filter, forwards to MainActivity with `CLEAR_TOP|SINGLE_TOP`, finishes). Pin `androidx.browser:browser:1.8.0`.

## Project Constraints (from CLAUDE.md)

| Directive | Impact on this phase |
|-----------|---------------------|
| Kotlin only, no Java | All new files Kotlin |
| **No coroutines** — raw `Thread()`, `Executors`, `Handler` | Token exchange/refresh run on `Thread{}`; `StravaApiClient` methods are blocking, called from background threads (OsrmClient convention). OkHttp `Authenticator` runs on the calling request thread — blocking refresh inside it is the intended usage |
| Gson for Strava API responses; org.json elsewhere | `StravaModels.kt` Gson data classes for token + athlete responses only |
| All HTTP via HttpURLConnection *except* Strava (STACK.md: OkHttp for Strava) | OkHttp 4.12.0 (already declared) for all Strava endpoints |
| try/catch + Log, never rethrow; Toasts for user-facing errors | Auth errors → `Log.w/e` + toast; never propagate |
| companion object constants, TAG per class | Standard on all new classes |
| No DI, manual wiring in onCreate | MainActivity owns StravaAuthManager instance; constructor-param wiring only |
| minSdk 28 / targetSdk 34 / compileSdk 34, Kotlin 2.1.0, AGP 8.7.3 | Drives the browser-1.8.0 pin (see Pitfall 1) |
| Strava constraint: OAuth code grant, no PKCE, secret via BuildConfig, tokens in EncryptedSharedPreferences | Matches locked decisions |
| CLAUDE.md rate-limit figure "300 reads/15min, 1,000 writes/15min" | **Stale** — verified current figures: 200/15min + 2,000/day overall; 100 reads/15min + 1,000 reads/day. Recommend correcting CLAUDE.md when this phase ships |
| GSD workflow enforcement | Work through /gsd-execute-phase |

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Authorization UI (login + consent) | System browser (Custom Tab) | Strava app (if installed, may natively handle the mobile authorize URL) | Strava blocks WebView auth; RFC 8252 requires external user-agent. App never sees credentials |
| Callback routing (`rokidhud://callback`) | Android OS intent resolution | `StravaCallbackActivity` (phone app) | OS matches scheme via manifest intent-filter; dedicated activity forwards + finishes |
| state generation/validation, code exchange, refresh | Phone background thread (`StravaAuthManager`) | — | Requires client_secret; network I/O must be off main thread |
| Token persistence | Android Keystore + ESP file (`StravaTokenStore`) | — | Master key hardware-backed where available; file in app-private storage, backup-excluded |
| Authenticated API access (GET /athlete) | Phone background thread (`StravaApiClient` + OkHttp) | OkHttp Authenticator (reactive 401 fallback) | Proactive refresh primary (locked); Authenticator retries once |
| Connection-state UI (card, toasts) | MainActivity (main thread) | — | Reads persisted athlete name; all mutations via runOnUiThread |
| Credentials at build time | Gradle BuildConfig from local.properties | — | Existing rokid.* precedent; empty-string defaults for graceful degradation |

## Strava Facts (verified live 2026-07-03)

All from `https://developers.strava.com/docs/authentication/` and `/docs/rate-limits/` unless noted.

| Fact | Value | Provenance |
|------|-------|------------|
| Mobile authorize endpoint | `GET https://www.strava.com/oauth/mobile/authorize` — still documented, Android-specific section exists | [CITED: developers.strava.com/docs/authentication] |
| Authorize query params | `client_id` (integer), `redirect_uri`, `response_type=code`, `approval_prompt` (`auto`\|`force`, default auto), `scope`, `state` (optional, echoed back) | [CITED: developers.strava.com/docs/authentication] |
| Scope delimiter | "comma- or URL-safe space-delimited" — comma form (locked) is officially supported | [CITED: developers.strava.com/docs/authentication] |
| Valid scopes | `read`, `read_all`, `profile:read_all`, `profile:write`, `activity:read`, `activity:read_all`, `activity:write` | [CITED: developers.strava.com/docs/authentication] |
| `read` grants | "read public segments, public routes, public profile data, public posts, public events, club feeds, and leaderboards" | [CITED: developers.strava.com/docs/authentication] |
| `read_all` grants | "read private routes, private segments, and private events for the user" → **private-route listing (Phase 4) requires `read_all`; locked scope set confirmed correct** | [CITED: developers.strava.com/docs/authentication] |
| Token exchange | `POST https://www.strava.com/oauth/token` with form fields `client_id, client_secret, code, grant_type=authorization_code` — **no redirect_uri field** (Strava deviation from RFC 6749 §4.1.3) | [CITED: developers.strava.com/docs/authentication] |
| Refresh | Same URL, form fields `client_id, client_secret, grant_type=refresh_token, refresh_token` | [CITED: developers.strava.com/docs/authentication] |
| Access-token lifetime | "Access tokens expire six hours after they are created" (`expires_at` = epoch seconds, `expires_in` also returned) | [CITED: developers.strava.com/docs/authentication] |
| Refresh rotation | "A refresh token is issued back to the application after all successful requests"; "once a new refresh token code has been returned, the older code will no longer work" → always persist the returned refresh_token (locked decision confirmed) | [CITED: developers.strava.com/docs/authentication] |
| Exchange response JSON | `token_type ("Bearer"), expires_at, expires_in, refresh_token, access_token, athlete` (summary athlete object — initial exchange only; refresh responses carry tokens only, no athlete) | [CITED: developers.strava.com/docs/authentication]; athlete-absent-on-refresh: [ASSUMED — docs show refresh example without athlete; model field must be nullable regardless] |
| Rate limits (new apps) | **Overall: 200 req/15 min, 2,000/day. Non-upload (read): 100 req/15 min, 1,000/day.** 429 when exceeded. Increases require app review, "not a guarantee" | [CITED: developers.strava.com/docs/rate-limits] |
| Rate-limit headers | `X-RateLimit-Limit`, `X-RateLimit-Usage`, `X-ReadRateLimit-Limit`, `X-ReadRateLimit-Usage` — each "15-minute limit, followed by daily limit", comma-separated | [CITED: developers.strava.com/docs/rate-limits] |
| GET /athlete | `GET https://www.strava.com/api/v3/athlete` — works with any valid token; `profile:read_all` yields detailed representation, others get summary (summary is fine: id, firstname, lastname present) | [CITED: developers.strava.com/docs/reference] |
| Callback query params | Strava redirects to `redirect_uri` with `code`, `scope` (comma list of scopes the athlete ACTUALLY granted — consent screen has per-scope checkboxes), and echoed `state`; on deny, an `error` param (no `code`) | [CITED: developers.strava.com/docs/authentication — param list]; deny-param name `access_denied`: [ASSUMED — handle "no code present" as the failure signal, which is robust to the exact error value] |
| Callback domain field | Strava app settings "Authorization Callback Domain" = bare word `rokidhud` (no scheme/slashes) | [CITED: .planning/research/PITFALLS.md Pitfall 1 — battle-tested; not re-verifiable without Strava login] |

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `com.squareup.okhttp3:okhttp` | 4.12.0 (already declared) | Strava HTTP: token exchange, refresh, GET /athlete | STACK.md locked; Authenticator + FormBody + interceptors; CXR SDK pins 4.x |
| `com.google.code.gson:gson` | 2.10.1 (already declared) | Parse token + athlete JSON into data classes | STACK.md locked; already on classpath |
| `androidx.security:security-crypto` | 1.1.0 (NEW) | EncryptedSharedPreferences for tokens | Locked decision. **Verified compatible: minCompileSdk=34, minAGP=8.1.1** (from AAR metadata, dl.google.com). Final stable release 2025-07-30; deprecated-but-functional. Pulls tink-android 1.8.0 transitively |
| `androidx.browser:browser` | **1.8.0** (NEW — do NOT use 1.9.0/1.10.0) | Chrome Custom Tab launch | **1.9.0 and 1.10.0 require minCompileSdk=36 + AGP 8.9.1 — incompatible with this project (compileSdk 34, AGP 8.7.3). 1.8.0 requires minCompileSdk=34.** Verified from actual AAR metadata. No 1.8.x patch releases exist (checked maven-metadata.xml) |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `com.squareup.okhttp3:logging-interceptor` | 4.12.0 (already declared) | Wire logging during dev | Wrap in `if (BuildConfig.DEBUG)`; NEVER log Authorization headers at `Level.HEADERS`+ in release — use `Level.BASIC` or redact |
| JUnit | 4.13.2 (already declared) | Unit tests for URL builder, parsing, expiry math | Existing test infra (`phone/src/test`) |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| CustomTabsIntent (1.8.0) + manifest deep link | `AuthTabIntent` (browser 1.9.0+) — purpose-built OAuth tab returning result via ActivityResult, no intent-filter | **Unavailable**: requires browser 1.9.0+ → compileSdk 36 + AGP 8.9.1. Also changes the locked intent-filter callback mechanism. Do not use |
| Manual flow | AppAuth-Android | Rejected in STACK.md (~300KB, overkill for one provider); would also fight the no-PKCE Strava quirk |
| EncryptedSharedPreferences | DataStore + Tink / plain Keystore | Rejected in STACK.md; locked decision stands (final-stable 1.1.0) |

**Installation (phone/build.gradle.kts):**
```kotlin
// Strava auth (Phase 3)
implementation("androidx.security:security-crypto:1.1.0")  // minCompileSdk 34 — verified compatible
implementation("androidx.browser:browser:1.8.0")           // PINNED: 1.9.0+ needs compileSdk 36 + AGP 8.9.1

// BuildConfig — alongside the existing ROKID_* fields in defaultConfig:
buildConfigField("String", "STRAVA_CLIENT_ID",
    "\"${localProps.getProperty("strava.client.id", "")}\"")
buildConfigField("String", "STRAVA_CLIENT_SECRET",
    "\"${localProps.getProperty("strava.client.secret", "")}\"")
```
`buildFeatures { buildConfig = true }` is already enabled and the `localProps` loader with empty-string defaults already exists [VERIFIED: phone/build.gradle.kts:8-33].

**Version verification (performed 2026-07-03):**
```bash
# Verified by downloading actual artifacts from Google Maven and reading AAR metadata:
# security-crypto-1.1.0.aar  → minCompileSdk=34, minAndroidGradlePluginVersion=8.1.1  ✓ compatible
# browser-1.10.0.aar         → minCompileSdk=36, minAndroidGradlePluginVersion=8.9.1  ✗
# browser-1.9.0.aar          → minCompileSdk=36, minAndroidGradlePluginVersion=8.9.1  ✗
# browser-1.8.0.aar          → minCompileSdk=34                                        ✓ compatible
```

## Package Legitimacy Audit

| Package | Registry | Age | Downloads | Source Repo | slopcheck | Disposition |
|---------|----------|-----|-----------|-------------|-----------|-------------|
| androidx.security:security-crypto:1.1.0 | Google Maven (dl.google.com) | 1.1.0 stable since 2025-07-30; library since 2019 | N/A (Google Maven doesn't publish counts) | android.googlesource.com (AOSP androidx) | N/A (Maven; tool unavailable) | Approved — [VERIFIED: dl.google.com artifact + developer.android.com/jetpack/androidx/releases/security] |
| androidx.browser:browser:1.8.0 | Google Maven (dl.google.com) | 1.8.0 since 2024-03-06; library since 2015 (chromium custom-tabs) | N/A | android.googlesource.com (AOSP androidx) | N/A (Maven; tool unavailable) | Approved — [VERIFIED: dl.google.com artifact + developer.android.com/jetpack/androidx/releases/browser] |

**Packages removed due to slopcheck [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none

slopcheck could not be installed in this environment and targets npm/PyPI ecosystems. These are first-party `androidx.*` artifacts on Google's own Maven repository (no third-party publishing — squatting is not possible in this namespace). Both artifacts were verified by downloading the actual AARs from `dl.google.com/android/maven2` and cross-checking official release-notes pages. Registry + authoritative-docs verification is satisfied; no human-verify gate needed beyond the normal build (a wrong coordinate fails dependency resolution loudly).

## Architecture Patterns

### System Architecture Diagram

```
                     PHONE APP                                    EXTERNAL
┌───────────────────────────────────────────────┐
│ MainActivity (main thread)                     │
│  STRAVA card ──tap CONNECT──► StravaAuthManager│
│       ▲                          │             │
│       │ runOnUiThread            │ buildAuthorizeUrl()
│       │ (toast, card state)      │ + persist state param
│       │                          ▼             │
│       │            CustomTabsIntent.launchUrl ─┼──► System browser (Custom Tab)
│       │                                        │      │  https://www.strava.com/
│       │                                        │      │  oauth/mobile/authorize?...
│       │                                        │      │  [HUMAN: login + Authorize]
│       │                                        │      ▼
│  ┌────┴──────────────┐   ACTION_VIEW intent   │   302 → rokidhud://callback
│  │ onNewIntent /     │◄──CLEAR_TOP|SINGLE_TOP─┼──────┘ ?code&scope&state
│  │ onCreate intent   │   StravaCallbackActivity│  (OS resolves scheme via
│  │ routing           │   (no UI, finishes)     │   manifest intent-filter)
│  └────┬──────────────┘                         │
│       │ handleCallback(uri) — validate state   │
│       ▼                                        │
│  Thread {                                      │
│    StravaAuthManager.exchangeCode(code) ───────┼──► POST /oauth/token
│         │ parse TokenResponse (Gson)           │    grant_type=authorization_code
│         ▼                                      │
│    StravaTokenStore.save(tokens, athlete) ──►EncryptedSharedPreferences
│         │                                      │    (Keystore master key;
│    StravaApiClient.getAthlete() ───────────────┼──► GET /api/v3/athlete
│         │   ├─ proactive: ensureFreshToken()   │    Authorization: Bearer …
│         │   │    (expires_at − now < 30 min    │
│         │   │     → synchronized refresh) ─────┼──► POST /oauth/token
│         │   └─ reactive: Authenticator on 401  │    grant_type=refresh_token
│         ▼        (single retry, single-flight) │
│    runOnUiThread { card → "Connected as X" }   │
│  }                                             │
└───────────────────────────────────────────────┘
```

Trace the primary use case: tap CONNECT → browser consent (human) → OS routes `rokidhud://callback` to StravaCallbackActivity → forwarded to MainActivity → background thread exchanges code → tokens persisted encrypted → authenticated GET /athlete proves the client → UI shows "Connected as {name}".

### Recommended Project Structure

```
phone/src/main/java/com/rokid/hud/phone/strava/
├── StravaAuthManager.kt       # OAuth flow: state param, authorize URL, code exchange,
│                              #   single-flight refresh, disconnect. Owns a PLAIN OkHttpClient
│                              #   (no authenticator) for token-endpoint calls.
├── StravaApiClient.kt         # Authenticated API: OkHttp with proactive-refresh + Authenticator
│                              #   fallback. Blocking methods — call from background threads only.
├── StravaTokenStore.kt        # EncryptedSharedPreferences wrapper: save/load/clear
│                              #   StoredTokens(accessToken, refreshToken, expiresAt, athleteId,
│                              #   athleteName). Catch-and-reset on init failure.
├── StravaModels.kt            # Gson data classes: TokenResponse, StravaAthlete (all fields nullable)
└── StravaCallbackActivity.kt  # No-UI forwarder: exported, intent-filter for rokidhud://callback
phone/src/test/java/com/rokid/hud/phone/strava/
├── StravaAuthUrlTest.kt       # authorize-URL builder, state generation/validation
├── StravaModelsTest.kt        # Gson parsing: exchange (with athlete) + refresh (without)
└── TokenExpiryTest.kt         # needsRefresh() boundary math, callback-URI param parsing
```

### Pattern 1: Authorize URL + state parameter

**What:** Build the mobile authorize URL with a CSRF `state` nonce; persist state until the callback returns; validate once, then discard (single-use).
**When to use:** Every CONNECT tap generates a fresh state.

```kotlin
// JVM-pure (no android.net.Uri) so it is unit-testable — see Validation Architecture
object StravaOAuth {
    const val AUTHORIZE_URL = "https://www.strava.com/oauth/mobile/authorize"
    const val TOKEN_URL = "https://www.strava.com/oauth/token"   // NO /api/v3 prefix
    const val REDIRECT_URI = "rokidhud://callback"
    const val SCOPES = "read,read_all,activity:write"            // comma-delimited (locked)

    fun buildAuthorizeUrl(clientId: String, state: String): String =
        AUTHORIZE_URL +
            "?client_id=" + urlEncode(clientId) +
            "&redirect_uri=" + urlEncode(REDIRECT_URI) +
            "&response_type=code" +
            "&approval_prompt=auto" +
            "&scope=" + urlEncode(SCOPES) +      // commas encode to %2C — accepted (standard form encoding)
            "&state=" + urlEncode(state)

    fun newState(): String {
        val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun urlEncode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
// Source: params verified against developers.strava.com/docs/authentication (2026-07-03)
```

### Pattern 2: Custom Tab one-shot launch (no service binding)

**What:** `CustomTabsIntent.Builder().build().launchUrl(...)`. No `CustomTabsServiceConnection`, no warmup, no `<queries>` needed for this path — the intent is a plain `ACTION_VIEW` that the system resolves.
**Fallbacks (verified):** if no installed browser supports Custom Tabs, "the CustomTabsIntent will open the user's default browser instead" (it degrades to a normal VIEW intent). The only hard failure is *no browser at all* → `ActivityNotFoundException` → catch + toast (matches codebase error conventions).
**Note:** because it is a VIEW intent to `https://www.strava.com/...`, if the Strava app is installed and holds verified App Links, Android may hand the authorize URL to the Strava app for native consent — this is the *intended* behavior of the mobile endpoint, and the redirect still returns to `rokidhud://callback` either way. The verifier should expect either surface.

```kotlin
// Source: developer.chrome.com/docs/android/custom-tabs/guide-get-started (verified 2026-07-03)
try {
    CustomTabsIntent.Builder().build()
        .launchUrl(this, Uri.parse(StravaOAuth.buildAuthorizeUrl(clientId, state)))
} catch (e: ActivityNotFoundException) {
    Log.w(TAG, "No browser available for Strava auth: ${e.message}")
    Toast.makeText(this, "No browser available", Toast.LENGTH_LONG).show()
}
```

### Pattern 3: Dedicated callback activity (recommended over intent-filter on MainActivity)

**What:** A tiny exported activity owns the `rokidhud://callback` intent-filter, immediately forwards the URI to MainActivity with `CLEAR_TOP|SINGLE_TOP`, and finishes. This is the AppAuth `RedirectUriReceiverActivity` pattern.
**Why (vs intent-filter directly on MainActivity):** MainActivity has default `standard` launchMode; a browser-fired VIEW intent would stack a **second MainActivity instance** on top of the Custom Tab (duplicate-instance trap). `CLEAR_TOP|SINGLE_TOP` from the forwarder instead (a) delivers to the existing instance via `onNewIntent`, (b) pops the Custom Tab off the back stack automatically, and (c) still works after process death (cold start delivers via `onCreate`'s intent). No launchMode change to MainActivity → zero risk to existing launcher behavior. CONTEXT.md grants this choice as Claude's discretion; this is the prescription.

```xml
<!-- AndroidManifest.xml — separate activity, its own intent-filter block (PITFALLS gotcha).
     android:exported="true" is MANDATORY on Android 12+ for any activity with an intent-filter
     (install-time failure otherwise). autoVerify does NOT apply to custom schemes (App Links
     are https-only) — do not add it. -->
<activity
    android:name=".strava.StravaCallbackActivity"
    android:exported="true"
    android:theme="@android:style/Theme.NoDisplay">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="rokidhud" android:host="callback" />
    </intent-filter>
</activity>
```

```kotlin
class StravaCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forward = Intent(this, MainActivity::class.java).apply {
            action = ACTION_STRAVA_CALLBACK
            putExtra(EXTRA_CALLBACK_URI, intent?.data?.toString())
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(forward)
        finish()
    }
}
// MainActivity: override onNewIntent(intent) + check intent in onCreate (cold-start path);
// route ACTION_STRAVA_CALLBACK to stravaAuthManager.handleCallback(uriString).
```

**Note on `Theme.NoDisplay`:** on API 23+ an activity with this theme MUST call `finish()` before `onResume()` completes (it does — synchronously in `onCreate`), otherwise the system throws. The pattern above is safe.

### Pattern 4: EncryptedSharedPreferences with catch-and-reset

**What:** Single lazily-created instance, initialized off the main thread, wrapped so any init failure (backup-restore key mismatch, Keystore corruption) wipes the file and returns a fresh store → user sees "Reconnect to Strava" (locked recovery), never a crash.

```kotlin
// security-crypto 1.1.0 API (MasterKey.Builder — not the deprecated-in-1.0 MasterKeys)
class StravaTokenStore(private val context: Context) {
    companion object {
        private const val TAG = "StravaTokenStore"
        const val PREFS_FILE = "strava_auth"   // must be backup-excluded — see Pitfall 2
    }
    @Volatile private var prefs: SharedPreferences? = null

    private fun prefs(): SharedPreferences? {
        prefs?.let { return it }
        synchronized(this) {                    // ESP creation is NOT safe to race (tink #535)
            prefs?.let { return it }
            return try { create().also { prefs = it } }
            catch (e: Exception) {              // AEADBadTagException etc. after backup-restore
                Log.e(TAG, "Token store corrupt, resetting: ${e.message}", e)
                context.deleteSharedPreferences(PREFS_FILE)   // API 24+; minSdk 28 OK
                try { create().also { prefs = it } }
                catch (e2: Exception) { Log.e(TAG, "Token store unavailable", e2); null }
            }
        }
    }

    private fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context, PREFS_FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    // save()/load()/clear() for accessToken, refreshToken, expiresAt(Long), athleteId(Long), athleteName
}
```

**Threading facts:** read/write on an opened ESP behaves like normal SharedPreferences (thread-safe). *Creation* is the expensive part — Keystore + Tink keyset generation can take hundreds of ms (ANRs reported when done on the main thread) and concurrent first-creation has crashed in the wild (Tink issue #535). All Strava work already runs on background threads; keep first access there. [MEDIUM: tink-crypto/tink#535, tink-java#23, community reports]

### Pattern 5: Single-flight refresh — proactive primary + Authenticator fallback (no coroutines)

**What:** One `synchronized` lock serializes ALL refresh attempts. Proactive path checks `expires_at` before each call (30-min window, locked). Reactive path is an OkHttp `Authenticator` that (a) bounds retries via the official `responseCount` pattern, (b) re-reads the stored token inside the lock — if another thread already refreshed, retry with the fresh token *without* a second refresh.

```kotlin
class StravaAuthManager(private val tokenStore: StravaTokenStore) {
    companion object {
        private const val TAG = "StravaAuth"
        private const val REFRESH_WINDOW_SEC = 30 * 60L          // locked: 30 minutes
    }
    private val refreshLock = Any()
    // PLAIN client for token endpoints — no authenticator (prevents refresh->401->refresh recursion)
    private val tokenClient = OkHttpClient()

    /** Blocking. Returns a currently-valid access token, refreshing under the lock if needed.
     *  Null => both tokens invalid or no tokens => caller surfaces Reconnect state. */
    fun ensureFreshToken(): String? = synchronized(refreshLock) {
        val t = tokenStore.load() ?: return null
        if (t.expiresAt - nowSec() > REFRESH_WINDOW_SEC) return t.accessToken
        refreshLocked(t)?.accessToken
    }

    /** Must hold refreshLock. POST grant_type=refresh_token; persists rotated refresh_token.
     *  CRITICAL: only wipe tokens on definitive rejection (HTTP 400/401 from the token endpoint).
     *  IOException = transient network — keep tokens, return null, caller shows retryable error. */
    private fun refreshLocked(t: StoredTokens): StoredTokens? { /* OkHttp FormBody POST … */ }
}

class StravaAuthenticator(private val auth: StravaAuthManager,
                          private val tokenStore: StravaTokenStore) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.responseCount >= 2) return null             // official give-up pattern
        val failed = response.request.header("Authorization")?.removePrefix("Bearer ")
        val fresh = synchronized(auth.refreshLock) {
            val current = tokenStore.load() ?: return null
            if (current.accessToken != failed) current.accessToken   // another thread refreshed
            else auth.refreshLockedPublic(current)?.accessToken ?: return null
        }
        return response.request.newBuilder().header("Authorization", "Bearer $fresh").build()
    }
}
private val Response.responseCount: Int
    get() = generateSequence(this) { it.priorResponse }.count()
// Source: responseCount + return-null-to-give-up verified at square.github.io/okhttp/recipes (2026-07-03)
```

**Blocking rules:** the Authenticator runs synchronously on the thread executing the request — for this codebase that is always an app-owned background `Thread{}` (all `StravaApiClient` methods are blocking, per OsrmClient convention). A blocking refresh POST inside `authenticate()` is the standard, intended usage. Never call `StravaApiClient` from the main thread (NetworkOnMainThreadException would fire anyway). [CITED: square.github.io/okhttp/recipes — recipe pattern performs blocking work inside authenticate; threading not further specified in docs]

### Pattern 6: Gson models with all-nullable fields

**What:** Gson instantiates Kotlin data classes via `Unsafe` — constructor defaults are skipped and missing JSON fields land as `null` **even in non-null Kotlin types**, deferring the crash to first use. Declare every field nullable and validate explicitly after parse.

```kotlin
// Refresh responses have NO athlete — nullable is mandatory, not defensive
data class TokenResponse(
    @SerializedName("token_type") val tokenType: String?,
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("refresh_token") val refreshToken: String?,
    @SerializedName("expires_at") val expiresAt: Long?,          // epoch seconds
    @SerializedName("expires_in") val expiresIn: Long?,
    @SerializedName("athlete") val athlete: StravaAthlete?       // initial exchange only
)
data class StravaAthlete(
    @SerializedName("id") val id: Long?,                          // Strava IDs exceed Int range
    @SerializedName("username") val username: String?,
    @SerializedName("firstname") val firstname: String?,
    @SerializedName("lastname") val lastname: String?
)
```

### Anti-Patterns to Avoid
- **Intent-filter directly on MainActivity without launchMode thought:** browser VIEW intent creates a duplicate MainActivity instance (standard launchMode). Use the dedicated forwarder (Pattern 3).
- **WebView for authorization:** Strava blocks it; violates RFC 8252. Custom Tab only (locked).
- **Refreshing through the authenticated client:** token-endpoint 401 would re-enter the Authenticator → recursion. Token endpoints use a plain OkHttpClient.
- **Wiping tokens on IOException during refresh:** transient network failure must not force re-auth. Wipe only on HTTP 400/401 from the token endpoint.
- **Logging tokens or client_secret:** log presence/expiry ("token stored, expires_at=…"), a hash suffix at most. `logging-interceptor` at `Level.HEADERS`/`BODY` leaks Authorization headers and token JSON — debug builds only, and prefer `Level.BASIC`.
- **`android.net.Uri` inside the parse/validate seams:** local unit tests stub Android classes (`isReturnDefaultValues = true` returns null/0) — the seams become untestable. Use `java.net.URI`/string parsing in pure-JVM helpers (it parses `rokidhud://callback?code=x&state=y` fine).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Token encryption at rest | Custom AES/Keystore wrapper | EncryptedSharedPreferences (security-crypto 1.1.0) | Keyset management, IVs, AAD, key rotation are all footguns; locked decision |
| Secure random state | `Random()`/timestamp nonce | `java.security.SecureRandom` (32 bytes) | CSRF defense is only as strong as the entropy |
| Browser auth surface | WebView, embedded login | CustomTabsIntent | Strava blocks WebView; system browser isolates credentials |
| HTTP + retry-on-401 | HttpURLConnection retry loops | OkHttp Authenticator + responseCount | Official, bounded, cooperates with connection pooling |
| JSON → objects | org.json field-by-field for Strava | Gson data classes | STACK.md locked; typed parse errors beat silent nulls (with nullable fields + validation) |
| State-param comparison | `==` on strings | `MessageDigest.isEqual(a.toByteArray(), b.toByteArray())` | Constant-time compare; trivial to use, closes a (theoretical) timing channel |

**Key insight:** every hand-rolled piece of OAuth plumbing (state, storage, refresh serialization) has a well-known attack or crash mode; the libraries and patterns above are the smallest battle-tested units that avoid them without adopting a framework.

## Common Pitfalls

### Pitfall 1: androidx.browser above 1.8.0 breaks the build
**What goes wrong:** Adding `browser:1.9.0`/`1.10.0` (the "current" versions) fails the build: AGP checks `minCompileSdk=36` and `minAndroidGradlePluginVersion=8.9.1` from AAR metadata; project is compileSdk 34 / AGP 8.7.3.
**Why it happens:** AndroidX rolled browser to SDK-36 compilation in the 1.9.0 cycle (July 2025).
**How to avoid:** Pin `androidx.browser:browser:1.8.0` with a comment. Do not "helpfully" bump it.
**Warning signs:** Gradle error "requires libraries and applications that depend on it to compile against version 36 or later" or "requires Android Gradle plugin 8.9.1".

### Pitfall 2: ESP file restored by Auto Backup → AEADBadTagException crash-loop
**What goes wrong:** After a device migration/backup restore, the app crashes (or the store throws) on first token access — the restored prefs file was encrypted with a Keystore key that does not exist on the new device/install.
**Why it happens:** Keystore keys are hardware/device-bound and never included in backups; the ciphertext is orphaned. `android:allowBackup="true"` is set in this manifest, and current rules exclude only `activities/`.
**How to avoid:** (1) Exclude the token prefs from BOTH rule files:
```xml
<!-- backup_rules.xml (API ≤ 30) -->
<exclude domain="sharedpref" path="strava_auth.xml" />
<!-- data_extraction_rules.xml: add the same line under BOTH <cloud-backup> and <device-transfer> -->
```
(2) Catch-and-reset wrapper (Pattern 4) as defense in depth — wipe + recreate on init failure → "Reconnect to Strava".
**Warning signs:** `javax.crypto.AEADBadTagException` / `InvalidProtocolBufferException` in init stack traces after reinstall/restore.
[MEDIUM-HIGH: tink-java#23, flutter_secure_storage#853, ed-george/encrypted-shared-preferences docs mirror — official JetSec docs carry this exact warning]

### Pitfall 3: Duplicate MainActivity from the browser redirect
**What goes wrong:** Callback lands, but a second MainActivity instance appears (fresh state, no service binding); back button reveals the old one; auth state and UI diverge.
**Why it happens:** MainActivity is `standard` launchMode; VIEW intents from the browser create new instances.
**How to avoid:** Dedicated `StravaCallbackActivity` + `CLEAR_TOP|SINGLE_TOP` forward (Pattern 3). Handle the forwarded intent in BOTH `onNewIntent` (warm) and `onCreate` (cold/process-death).
**Warning signs:** `dumpsys activity activities` shows two MainActivity records; card state resets after auth.

### Pitfall 4: User can uncheck scopes on Strava's consent screen
**What goes wrong:** Token exchange succeeds but Phase 4/5 calls fail with 403 — the athlete granted only `read` because `read_all`/`activity:write` checkboxes were deselected on the consent page.
**Why it happens:** Strava's consent UI makes each requested scope individually optional; the callback's `scope` query param reports what was ACTUALLY granted.
**How to avoid:** Parse `scope` from the callback. If it lacks any of the three required scopes, treat as failure: toast "Strava permissions incomplete — please reconnect and keep all boxes checked", do not persist tokens (or persist + show a warning state; prescription: don't persist — clean retry beats a half-working connection).
**Warning signs:** GET /athlete works but routes return 402/403-style errors later.

### Pitfall 5: state param not validated (or reused)
**What goes wrong:** Any app/browser can fire `rokidhud://callback?code=attacker_code` — without state validation the app would exchange an attacker-chosen code (session fixation / CSRF).
**How to avoid:** Generate per-attempt (SecureRandom, 32 bytes hex), persist until callback, compare constant-time, clear after ONE use regardless of outcome, reject mismatches with a log + toast. Custom schemes get no OS-level ownership (any app can claim `rokidhud://`) — state is the primary integrity check; the code alone is useless without client_secret, which is the locked accepted-risk boundary.
**Warning signs:** `adb shell am start -a android.intent.action.VIEW -d "rokidhud://callback?state=WRONG&code=x"` does anything other than reject.

### Pitfall 6: First ESP access on the main thread
**What goes wrong:** Jank or ANR on low-end devices at first token read (Keystore + Tink keyset generation is slow, occasionally seconds).
**How to avoid:** All token-store access stays on background threads (auth flow already is). For the card's initial "Connected as X" render, either read on a `Thread{}` + `runOnUiThread`, or mirror just the non-secret display fields (athleteName) in plain prefs for instant UI (tokens stay encrypted). Prescription: background read + `runOnUiThread` — simplest, no data duplication.
**Warning signs:** StrictMode disk-read violations; frozen frames on cold start.

### Pitfall 7: Missing-credentials path not degraded gracefully
**What goes wrong:** Developer builds without `strava.client.id` in local.properties (currently the case — verified absent), taps CONNECT, gets an opaque Strava error page ("invalid client_id").
**How to avoid:** Locked decision — `BuildConfig.STRAVA_CLIENT_ID.isEmpty()` → card shows "Set up Strava API keys" hint instead of the CONNECT button. Never launch the Custom Tab with an empty client_id.

## Code Examples

Verified patterns beyond those embedded above:

### GET /athlete (proves the client — AUTH success criterion #4)
```kotlin
// StravaApiClient — blocking; call from Thread{}
class StravaApiClient(private val auth: StravaAuthManager, tokenStore: StravaTokenStore) {
    companion object { private const val TAG = "StravaApi"
                       private const val BASE = "https://www.strava.com/api/v3" }
    private val client = OkHttpClient.Builder()
        .authenticator(StravaAuthenticator(auth, tokenStore))
        .build()

    fun getAthlete(): StravaAthlete? {
        val token = auth.ensureFreshToken() ?: return null      // proactive (locked primary)
        val req = Request.Builder().url("$BASE/athlete")
            .header("Authorization", "Bearer $token").build()
        return try {
            client.newCall(req).execute().use { resp ->
                logRateLimits(resp)                              // PITFALLS gotcha: awareness now, enforcement Phase 4
                if (!resp.isSuccessful) { Log.w(TAG, "GET /athlete ${resp.code}"); return null }
                Gson().fromJson(resp.body?.string(), StravaAthlete::class.java)
            }
        } catch (e: Exception) { Log.e(TAG, "GET /athlete failed: ${e.message}", e); null }
    }

    private fun logRateLimits(resp: Response) {
        val overall = resp.header("X-RateLimit-Usage")           // "15min,daily"
        val read = resp.header("X-ReadRateLimit-Usage")          // newer read-specific pair
        if (overall != null || read != null) Log.i(TAG, "Rate usage overall=$overall read=$read")
    }
}
// Headers verified at developers.strava.com/docs/rate-limits (2026-07-03)
```

### Token exchange (form-encoded POST — exact fields verified)
```kotlin
fun exchangeCode(code: String): TokenResponse? {
    val body = FormBody.Builder()
        .add("client_id", BuildConfig.STRAVA_CLIENT_ID)
        .add("client_secret", BuildConfig.STRAVA_CLIENT_SECRET)
        .add("code", code)
        .add("grant_type", "authorization_code")   // NO redirect_uri field — Strava quirk
        .build()
    val req = Request.Builder().url(StravaOAuth.TOKEN_URL).post(body).build()
    // …execute on tokenClient (plain), parse with Gson, validate non-null access/refresh/expires_at,
    // persist via tokenStore.save() including rotated refresh_token + athlete id/name
}
```

### Callback parsing (JVM-pure seam)
```kotlin
// java.net.URI handles custom schemes; android.net.Uri would be null-stubbed in unit tests
fun parseCallback(uriString: String): Map<String, String> =
    java.net.URI(uriString).rawQuery?.split("&")?.mapNotNull { p ->
        val i = p.indexOf('='); if (i <= 0) null
        else java.net.URLDecoder.decode(p.substring(0, i), "UTF-8") to
             java.net.URLDecoder.decode(p.substring(i + 1), "UTF-8")
    }?.toMap() ?: emptyMap()
// keys of interest: "code", "state", "scope" (granted scopes), absence of "code" => denied/error
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Strava default limits 100/15min (older docs) or CLAUDE.md's "300 reads/15min" | 200 overall/15min + 2,000/day; 100 reads/15min + 1,000 reads/day for new apps; separate `X-ReadRateLimit-*` headers | Docs current as of 2026-07-03 | Log both header pairs; correct CLAUDE.md figure |
| `MasterKeys.getOrCreate(AES256_GCM_SPEC)` (security-crypto 1.0.0) | `MasterKey.Builder(context).setKeyScheme(AES256_GCM).build()` | 1.1.0 line | Use the 1.1.0 API; the 1.0-era API is doubly deprecated |
| security-crypto actively developed | Entire library deprecated at 1.1.0 (final, 2025-07-30) — "in favour of existing platform APIs and direct use of Android Keystore" | 2025-06/07 | Locked decision already accounts for this: deprecated-but-final, functional; no migration this milestone |
| Custom Tab + manifest deep link for OAuth | `AuthTabIntent` (browser 1.9.0+) returns the redirect via ActivityResult, no intent-filter | browser 1.9.0 (2025-07-30), API stabilized 1.10.0 (2026-03-25) | Unavailable here (compileSdk-36 wall) — classic pattern remains correct and universal |
| AppAuth-Android as default | For single-provider, no-PKCE flows, manual Custom-Tab flow is the leaner standard | — | STACK.md decision reaffirmed |

**Deprecated/outdated:**
- `MasterKeys` (1.0.0 API): replaced by `MasterKey.Builder` — use the latter.
- `approval_prompt=force`: only needed to re-show consent for incremental scopes; use `auto`.
- Retrofit for this feature: rejected (STACK.md) — coroutine-oriented, unnecessary for 3 endpoints.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Refresh responses omit the `athlete` object | Strava Facts | None — model field is nullable; athlete name persisted at initial exchange |
| A2 | Deny redirect carries `error=access_denied` (exact param value) | Strava Facts | None — logic keys off "no `code` param" which covers all failure shapes |
| A3 | Tink stores its two encrypted keysets inside the same named prefs file (so excluding `strava_auth.xml` from backup is sufficient) | Pitfall 2 | Low — catch-and-reset wrapper recovers even if a stray keyset file restored; verifier can `run-as` list shared_prefs to confirm file inventory on device |
| A4 | `EncryptedSharedPreferences.create(context, fileName, masterKey, keyScheme, valueScheme)` parameter order (1.1.0 MasterKey overload) | Pattern 4 | None in practice — a wrong signature fails compilation immediately |
| A5 | OkHttp Authenticator executes on the request's calling thread (blocking refresh is safe/intended) | Pattern 5 | Low — official recipe itself performs blocking work in authenticate(); all call sites are app background threads regardless |
| A6 | Strava's consent screen allows per-scope deselection, granted set reported via callback `scope` param | Pitfall 4 | Low — validation code is cheap; if scopes can't be deselected the check simply always passes |
| A7 | Strava app (if installed) may natively handle the mobile authorize URL via App Links; callback unaffected | Pattern 2 | None — either surface ends at `rokidhud://callback`; verifier informed to expect both |

## Open Questions

1. **Strava developer-app registration (human prerequisite)**
   - What we know: `local.properties` contains NO `strava.*` keys (verified). A Strava API application must be created at strava.com/settings/api with Authorization Callback Domain = `rokidhud`, and its client_id/secret added to local.properties before end-to-end verification.
   - What's unclear: whether the user already has a Strava API app from another project.
   - Recommendation: make this a `checkpoint:human-verify`-style setup task at the START of execution (code work can proceed in parallel thanks to the graceful-degradation card, but E2E verification blocks on it).

2. **Forced-refresh verification hook**
   - What we know: expires_at lives inside ESP — not editable via adb; waiting 5.5 hours is impractical.
   - Recommendation: debug-only trigger (e.g., long-press on the connected card when `BuildConfig.DEBUG`) calling a `forceRefresh()` that ignores the 30-min window — logcat then proves rotation (log refresh_token *hash suffix* only) + subsequent GET /athlete 200. Cheap, contained, removable.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Strava API app credentials (`strava.client.id/secret` in local.properties) | E2E OAuth verification | ✗ (keys absent — verified) | — | Graceful-degradation card (locked) lets all code build + unit-test; human creates app at strava.com/settings/api |
| Google Maven reachability (new deps) | Build | ✓ (artifacts downloaded during research) | security-crypto 1.1.0, browser 1.8.0 | — |
| Internet + strava.com | Auth flow, GET /athlete | ✓ (docs fetched) | — | — |
| Java/Gradle toolchain | Build + unit tests | ✗ in this shell (no JRE on PATH; `sdk.dir` empty in local.properties) | Gradle 8.11.1 wrapper present | User's Android Studio environment — proven working (Phases 1–2 built, unit-tested, and device-verified) |
| adb + test device (OPPO phone) | Device verification | ✗ in this shell | — | User's device session, as in Phases 1–2; Phase 2 leftovers (Mini-toggle, WR-01 on-hardware, imperial variant) fold into THIS phase's device session per CONTEXT |
| Browser on test device (Custom Tabs) | AUTH-01 | Assumed present (standard Android) | — | launchUrl auto-falls back to default browser; ActivityNotFoundException toast if none |

**Missing dependencies with no fallback:**
- None that block code/plan execution. Strava credentials block only the final E2E human verification step.

**Missing dependencies with fallback:**
- Strava credentials (degrade to setup-hint card until provided); build/device toolchain (user environment, proven in prior phases).

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 4.13.2 (existing: `phone/src/test`, `shared/src/test`; `unitTests.isReturnDefaultValues = true`) |
| Config file | `phone/build.gradle.kts` (testImplementation already declared) |
| Quick run command | `./gradlew :phone:testDebugUnitTest --tests "com.rokid.hud.phone.strava.*"` |
| Full suite command | `./gradlew :phone:testDebugUnitTest :shared:testDebugUnitTest` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| AUTH-01 | Authorize URL exact (endpoint, params, comma scopes, encoded redirect) | unit | `./gradlew :phone:testDebugUnitTest --tests "*.StravaAuthUrlTest"` | ❌ Wave 0 |
| AUTH-01 | state generation (length/uniqueness) + validation (match/mismatch/single-use) | unit | same as above | ❌ Wave 0 |
| AUTH-01 | Callback URI parsing: code/state/scope extraction; missing-code = failure; partial-scope detection | unit | `--tests "*.TokenExpiryTest"` (or its own class) | ❌ Wave 0 |
| AUTH-01 | Custom Tab launch, browser consent, deep-link routing, StravaCallbackActivity forward | manual-only (device) | — human: tap Authorize; adb assists (see Sampling notes) | — |
| AUTH-02 | TokenResponse Gson parse: initial exchange (athlete present), refresh (athlete null), missing-field nullability | unit | `--tests "*.StravaModelsTest"` | ❌ Wave 0 |
| AUTH-02 | Encrypted persistence across restart; backup exclusion; catch-and-reset | manual-only (device) | `adb shell am force-stop com.rokid.hud.phone` + relaunch → card still "Connected as {name}"; `adb shell run-as com.rokid.hud.phone ls shared_prefs` shows `strava_auth.xml` (contents ciphertext — verify state via UI) | — |
| AUTH-03 | needsRefresh boundary math (29:59 vs 30:01 before expires_at; past-expiry; missing token) | unit | `--tests "*.TokenExpiryTest"` | ❌ Wave 0 |
| AUTH-03 | Single-flight: concurrent ensureFreshToken calls produce exactly one refresh (fake refresher counting invocations, 2 threads) | unit | same | ❌ Wave 0 |
| AUTH-03 | Live refresh + rotation + GET /athlete 200 | manual-only (device) | debug forced-refresh hook + logcat filter `StravaAuth\|StravaApi` | — |

### Sampling Rate
- **Per task commit:** `./gradlew :phone:testDebugUnitTest --tests "com.rokid.hud.phone.strava.*"`
- **Per wave merge:** `./gradlew :phone:testDebugUnitTest :shared:testDebugUnitTest`
- **Phase gate:** full suite green + the device E2E session (below) before `/gsd-verify-work`

**The one human moment (everything else automatable):** the user must tap CONNECT STRAVA, complete Strava login if needed, leave all scope checkboxes checked, and tap **Authorize** in the browser/Strava app. Around that moment, scriptable verification:
1. Pre-flight (no human): `adb shell pm resolve-activity -a android.intent.action.VIEW -d "rokidhud://callback"` → resolves to StravaCallbackActivity (intent-filter proof).
2. Negative path (no human): `adb shell am start -a android.intent.action.VIEW -d "rokidhud://callback?state=WRONG\&code=fake"` → logcat shows state-mismatch rejection, no token exchange attempted.
3. During the human tap: `adb logcat -s StravaAuth:V StravaApi:V StravaTokenStore:V` captures state-generated → callback-received/state-ok → exchange 200 → tokens-stored (expires_at only, never token values) → GET /athlete 200 + athlete name → rate-limit usage headers.
4. Post: force-stop + relaunch persistence check (AUTH-02); debug forced-refresh hook for AUTH-03 rotation proof.

### Wave 0 Gaps
- [ ] `phone/src/test/java/com/rokid/hud/phone/strava/StravaAuthUrlTest.kt` — covers AUTH-01 (URL + state)
- [ ] `phone/src/test/java/com/rokid/hud/phone/strava/StravaModelsTest.kt` — covers AUTH-02 (Gson parse/nullability)
- [ ] `phone/src/test/java/com/rokid/hud/phone/strava/TokenExpiryTest.kt` — covers AUTH-03 (expiry math, single-flight, callback parse)
- [ ] Framework install: none — JUnit 4 already configured; keep seams free of `android.net.Uri`/Context so they run as local unit tests

## Security Domain

ASVS Level 1 (`security_enforcement: true`, block on high).

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes | OAuth 2.0 Authorization Code Grant via external user-agent (Custom Tab, RFC 8252). No PKCE — provider limitation, locked accepted risk (secret in APK, sideloaded single-user app) |
| V3 Session Management | yes | 6h access token + rotated refresh token; proactive+reactive refresh; disconnect wipes local tokens; both-invalid → explicit Reconnect state |
| V4 Access Control | yes | Scope minimization to the three required scopes; granted-scope validation from callback (Pitfall 4) |
| V5 Input Validation | yes | state param: SecureRandom, single-use, constant-time compare; callback params parsed defensively (missing code = deny); Gson nullable fields + explicit post-parse validation |
| V6 Cryptography | yes | EncryptedSharedPreferences (AES256-GCM values, AES256-SIV keys, Keystore master key) — never hand-roll |
| V7 Errors & Logging | yes | Never log access_token/refresh_token/client_secret; logging-interceptor debug-only at BASIC; log expiry + hash suffix at most |
| V9 Communications | yes | HTTPS-only endpoints (hardcoded https URLs); cert pinning explicitly deferred (locked) |

### Known Threat Patterns for OAuth-on-Android

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| CSRF / login-session fixation on callback | Spoofing/Tampering | state param: 32-byte SecureRandom, persisted per attempt, single-use, constant-time compare |
| Custom-scheme hijack (another app claims `rokidhud://`) | Spoofing/Info disclosure | Intercepted code is unusable without client_secret; secret-in-APK residual risk is the LOCKED accepted risk boundary (documented, single-user sideloaded app). App Links not applicable (no domain) |
| Token theft via backup extraction | Info disclosure | Backup-exclude `strava_auth.xml` (also fixes the restore crash); ESP ciphertext useless without device Keystore anyway |
| Token leak via logs | Info disclosure | Redaction rules above; verifier greps captured logcat for token substrings as a check |
| WebView credential harvesting | Spoofing | Forbidden (Strava blocks; Custom Tab locked) |
| Refresh-loop DoS on 401 | DoS | responseCount ≥ 2 give-up; single-flight lock; wipe-only-on-400/401 rule prevents auth-loss from transient network |

## Sources

### Primary (HIGH confidence)
- https://developers.strava.com/docs/authentication/ — endpoints, params, scopes + grants, token/refresh form fields, rotation, 6h lifetime, response JSON (fetched 2026-07-03)
- https://developers.strava.com/docs/rate-limits/ — current default limits (200/2,000 overall; 100/1,000 read), 4 header names, 429 (fetched 2026-07-03)
- dl.google.com/android/maven2 AAR metadata — minCompileSdk/minAGP for security-crypto 1.1.0, browser 1.8.0/1.9.0/1.10.0; browser maven-metadata.xml (no 1.8.x patches); security-crypto 1.1.0 POM (tink-android 1.8.0) (downloaded 2026-07-03)
- https://developer.android.com/jetpack/androidx/releases/security — 1.1.0 final 2025-07-30; all-APIs deprecation statement (fetched 2026-07-03)
- https://developer.android.com/jetpack/androidx/releases/browser — 1.10.0 stable 2026-03-25; AuthTab timeline (fetched 2026-07-03)
- https://square.github.io/okhttp/recipes/ — Authenticator recipe, responseCount pattern, return-null semantics (fetched 2026-07-03)
- https://developer.chrome.com/docs/android/custom-tabs/guide-get-started — minimal launchUrl, no-binding, default-browser fallback (fetched 2026-07-03)
- Codebase [VERIFIED]: phone/build.gradle.kts (BuildConfig pattern, buildConfig=true, deps), AndroidManifest.xml (allowBackup, rules files), backup_rules.xml/data_extraction_rules.xml (only `activities/` excluded), MainActivity.kt structure (1160 lines, no onNewIntent yet), local.properties (no strava keys), existing test infra

### Secondary (MEDIUM confidence)
- https://github.com/tink-crypto/tink-java/issues/23 + https://github.com/google/tink/issues/535 — AEADBadTagException after restore; concurrent-init crashes
- https://github.com/ed-george/encrypted-shared-preferences — post-deprecation fork mirroring the official backup-exclusion warning
- https://github.com/juliansteenbakker/flutter_secure_storage/issues/853 — backup-restore failure corroboration
- .planning/research/PITFALLS.md + STACK.md + ARCHITECTURE.md — battle-tested project research (callback-domain bare word, no-/api/v3 token URL, WebView ban, OkHttp/Gson decisions)

### Tertiary (LOW confidence)
- Community articles on Custom-Tab OAuth patterns (joebirch.co; oversecured.com scheme-hijack analysis) — informed Pitfall 5 framing; mitigations rest on primary sources

## Metadata

**Confidence breakdown:**
- Strava endpoints/scopes/limits: HIGH — official docs fetched during research
- Dependency versions/compatibility: HIGH — actual artifact metadata inspected, not release notes alone
- ESP backup/threading pitfalls: MEDIUM-HIGH — multiple independent issue trackers + docs-mirror; exact keyset-file location assumed (A3) with safe prescription
- OAuth callback activity pattern: HIGH — established AppAuth-style pattern; Android 12+ exported requirement is platform-documented
- Verification plan: HIGH — grounded in tools proven during Phases 1–2 on the same device

**Research date:** 2026-07-03
**Valid until:** ~2026-08-03 (Strava docs and AndroidX stable channel move slowly; re-check rate-limit page if Phase 4 slips further)
