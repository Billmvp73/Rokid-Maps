# Phase 3: Strava Authentication - Context

**Gathered:** 2026-07-03
**Status:** Ready for planning
**Mode:** Smart discuss (autonomous — recommendations auto-accepted per user authorization)

<domain>
## Phase Boundary

User authenticates with Strava; tokens managed securely. Delivers: OAuth 2.0 Authorization Code Grant (NO PKCE — Strava doesn't support it; client_secret via BuildConfig from local.properties), Chrome Custom Tab authorization against the MOBILE endpoint, deep-link callback, token exchange + storage in EncryptedSharedPreferences, proactive auto-refresh with reactive 401 fallback, the OkHttp Strava API client proven via an authenticated GET /athlete, and the phone-UI Strava card. Requirements: AUTH-01..AUTH-03. Route endpoints are Phase 4; upload is Phase 5.

</domain>

<decisions>
## Implementation Decisions

### OAuth redirect mechanics
- Custom scheme deep link: `rokidhud://callback` as the redirect_uri parameter
- Strava app settings "Authorization Callback Domain" = `rokidhud` (bare word, no scheme/slashes — PITFALLS Integration Gotchas)
- Authorization via Chrome Custom Tab (androidx.browser) against `https://www.strava.com/oauth/mobile/authorize` (AUTH-01, PITFALLS: mobile endpoint, never WebView)
- Token exchange/refresh POST `https://www.strava.com/oauth/token` (NO /api/v3 prefix — PITFALLS gotcha)
- Callback intent-filter: its own <intent-filter> block on MainActivity (or a dedicated lightweight activity if cleaner) with BROWSABLE + DEFAULT categories, android:exported="true" (Android 12+ requirement)
- Scopes comma-delimited (NOT space): `read,read_all,activity:write`

### Scope set
- `read,read_all,activity:write` requested at first auth — read_all covers private-route listing (settles the STATE todo question); activity:write pre-granted for Phase 5 so no re-auth mid-milestone

### Token lifecycle
- Proactive refresh: before any authenticated call, if expires_at is within 30 minutes, refresh first (AUTH-03 authoritative — STATE todo resolution: proactive primary)
- Reactive fallback: OkHttp Authenticator retries once on 401 after a synchronized refresh (ARCHITECTURE Pattern 3 demoted to fallback layer)
- Storage: EncryptedSharedPreferences (androidx.security:security-crypto 1.1.0 — deprecated-but-final, per STACK.md) holding access_token, refresh_token, expires_at, athlete_id, athlete_name
- Refresh-token rotation: Strava returns a new refresh_token on every refresh — always persist the returned one
- Both-tokens-invalid → surface "Reconnect to Strava" state (PITFALLS recovery); local activity data untouched

### Credentials
- client_id + client_secret from local.properties keys `strava.client.id` / `strava.client.secret` → BuildConfig fields (matching the existing rokid.* credential pattern); missing values → BuildConfig empty strings → Strava card shows "Set up Strava API keys" hint instead of Connect (graceful dev-mode degradation)
- Accepted risk (locked project decision): secret extractable from APK; sideloaded single-user app

### UI placement & connection state
- New STRAVA card on MainActivity between RECORD and GLASSES SETTINGS: disconnected → "CONNECT STRAVA" button; connected → "Connected as {athlete_name}" + DISCONNECT button
- Disconnect = wipe stored tokens/athlete locally only (no remote deauthorize call in v1)
- Success toast on auth completion ("Connected to Strava as {name}" — PITFALLS UX)
- All Strava code in package com.rokid.hud.phone.strava (ARCHITECTURE)

### Claude's Discretion
- StravaAuthManager/StravaApiClient class split details within the researched architecture
- Exact card styling (existing bg_card conventions)
- Custom-tab vs dedicated callback activity implementation detail

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- OkHttp 4.12.0 + Gson 2.10.1 already explicitly declared in phone/build.gradle.kts (verified) — androidx.browser + security-crypto are the only NEW dependencies
- local.properties → BuildConfig pattern exists for rokid.* credentials (build.gradle.kts precedent)
- MainActivity card structure + bg_card drawables + tapText-able buttons; prefs conventions
- Existing Thread{} + callback conventions for the token-exchange network calls (NO coroutines)

### Established Patterns
- try/catch + Log, never rethrow; toasts for user-facing errors
- companion-object constants; TAG per class
- Manual JSON via org.json for our own persistence — but Strava API responses parse via Gson data classes (STACK.md decision)

### Integration Points
- AndroidManifest: intent-filter for rokidhud://callback + android:exported
- MainActivity onCreate/onNewIntent: callback intent routing to StravaAuthManager
- phone/build.gradle.kts: BuildConfig fields + 2 new deps
- Verification: real OAuth needs the USER's Strava login on the phone (human-in-the-loop moment); scripted parts via adb; GET /athlete result proves the client (AUTH SC#4)

</code_context>

<specifics>
## Specific Ideas

- PITFALLS Integration Gotchas table is the checklist: mobile authorize endpoint, token URL without /api/v3, comma scopes, callback-domain bare word, separate intent-filter blocks, X-RateLimit-Usage header awareness (log it; enforcement Phase 4)
- Rate limits: 300 reads/15min, 1,000 writes/15min assumed; VERIFY live figures + scope behavior during this phase's research (STATE todo)
- Phase 2 leftover device spots (Mini-toggle, WR-01 on-hardware, imperial variant) fold into THIS phase's device session

</specifics>

<deferred>
## Deferred Ideas

- Remote deauthorize on disconnect — v1.x
- Multiple Strava accounts — out of scope
- Certificate pinning for strava.com — noted in PITFALLS security; defer unless research flags it as trivial (risk-accept per single-user app)

</deferred>

---

*Phase: 03-strava-authentication*
*Context gathered: 2026-07-03 via autonomous smart discuss*
