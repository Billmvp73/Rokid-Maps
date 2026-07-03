---
phase: 03-strava-authentication
plan: 03
subsystem: auth
tags: [strava, oauth, android, main-activity, deep-link, custom-tabs, ui]
requires:
  - "03-02: StravaTokenStore/StravaAuthManager/StravaApiClient, CallbackResult contract, StravaCallbackActivity ACTION_STRAVA_CALLBACK/EXTRA_CALLBACK_URI forwarding"
provides:
  - "STRAVA card in activity_main.xml (stravaCard, stravaStatusText, stravaSetupHint, btnConnectStrava, btnDisconnectStrava) placed between RECORD and GLASSES SETTINGS"
  - "MainActivity dual-path callback routing: first-ever onNewIntent override (warm) + onCreate intent routing (cold, Pitfall 3)"
  - "refreshStravaCard() single-writer card state machine: setup-hint / CONNECT / Connected-as truth table with background ESP reads (Pitfall 6)"
  - "CallbackResult -> toast mapping + GET /athlete authenticated-client proof on Connected (ROADMAP Delivers)"
  - "DEBUG-only stravaCard long-press: forceRefresh + GET /athlete — Wave 4's 2-second AUTH-03 rotation proof"
affects:
  - "03-04 (device gate: greps the logcat markers below; long-press is the rotation hook)"
  - "Phase 4/5 (users can now actually connect — route import and upload build on the connected state)"
tech-stack:
  added: []
  patterns:
    - "Thread{} + runOnUiThread for every token-store/network touch (no coroutines, codebase convention)"
    - "Single-writer UI state: refreshStravaCard() is the only mutator of the four card views' visibility/text"
    - "Keys-empty guard is synchronous (no store read needed); all other card states resolve on a background thread"
key-files:
  created: []
  modified:
    - phone/src/main/res/layout/activity_main.xml
    - phone/src/main/java/com/rokid/hud/phone/MainActivity.kt
decisions:
  - "STRAVA card inserted immediately before the GLASSES SETTINGS banner (after navStatus card) — visible position between RECORD and GLASSES SETTINGS since navStatus is gone by default (CONTEXT locked UI placement)"
  - "btnConnectStrava uses Strava brand orange #FC5200 inside bg_card conventions (plan-granted discretion)"
  - "GET /athlete null after Connected is logged but NOT surfaced as failure — tokens are persisted; Authenticator/proactive refresh recovers on next use"
metrics:
  duration: "~7 min"
  completed: "2026-07-03"
  tasks: 3
  tests-added: 0
  files-created: 0
  files-modified: 2
---

# Phase 3 Plan 03: MainActivity Strava Card + Callback Routing Summary

**One-liner:** The Wave-2 auth machine is now user-reachable — a STRAVA card with a three-state truth table, Custom-Tab connect, dual-path (warm onNewIntent / cold onCreate) callback routing into a background token exchange with per-result toasts, a GET /athlete proof on connect, and a DEBUG long-press forced-refresh hook for the Wave-4 rotation gate.

## What Was Built

### STRAVA card (activity_main.xml)
- New `stravaCard` LinearLayout inserted after the navStatus (LIVE DIRECTIONS) card and immediately before the GLASSES SETTINGS banner — renders between RECORD and GLASSES SETTINGS in the visible column (navStatus is `gone` by default).
- Matches RECORD-card conventions exactly: `bg_card` background, 16dp padding, 12dp bottom margin, `SectionTitle` header, buttons with `insetTop/insetBottom="0dp"`.
- Four dynamic views, all defaulting hidden/neutral (runtime `refreshStravaCard()` is the single writer): `stravaStatusText` ("Not connected" initial), `stravaSetupHint` (gone), `btnConnectStrava` (Strava orange #FC5200, gone), `btnDisconnectStrava` (muted #2A2A2A, gone).

### Card state truth table (as implemented in refreshStravaCard)

| BuildConfig keys | Tokens stored | stravaStatusText | stravaSetupHint | btnConnectStrava | btnDisconnectStrava |
|------------------|--------------|------------------|-----------------|------------------|---------------------|
| either empty | (not read — synchronous return, Pitfall 7) | "Not configured" | VISIBLE | GONE | GONE |
| both present | none | "Not connected" | GONE | VISIBLE | GONE |
| both present | stored | "Connected as {name}" | GONE | GONE | VISIBLE |

- The keys-empty branch never launches or offers Connect (Pitfall 7 — no empty-client_id Custom Tab) and needs no store read.
- The two token-dependent states resolve on a `Thread{}` (first ESP access pays Keystore + Tink init — Pitfall 6); all view mutations go through `runOnUiThread`.
- Called from: end of `onCreate`, `onResume` (idempotent self-correct), after every callback result, after disconnect, after forced refresh.

### Callback routing (both delivery paths — Pitfall 3)
- **Warm:** first-ever `onNewIntent` override in MainActivity — `super.onNewIntent(intent)`, `setIntent(intent)`, then `handleStravaCallbackIntent(intent)`. StravaCallbackActivity's CLEAR_TOP|SINGLE_TOP forward lands here while MainActivity is alive.
- **Cold:** `handleStravaCallbackIntent(intent)` at the very end of `onCreate` — if the process died under the Custom Tab, the forwarded intent arrives on recreation.
- `handleStravaCallbackIntent` guards on `StravaCallbackActivity.ACTION_STRAVA_CALLBACK`, logs the event only (never the URI — T-03-04), and runs `stravaAuthManager.handleCallback(uri)` on a `Thread{}` — zero validation in the Activity, single validated pipeline (T-03-01).
- On `CallbackResult.Connected`, a background `stravaApiClient.getAthlete()` exercises the authenticated client end-to-end (ROADMAP Delivers). A null athlete is logged but NOT treated as auth failure — tokens are already persisted.

### Exact toast strings

| Trigger | Toast |
|---------|-------|
| CallbackResult.Connected | `Connected to Strava as {athleteName}` |
| CallbackResult.StateMismatch | `Strava connection rejected (security check failed) — try Connect again` |
| CallbackResult.Denied | `Strava authorization was declined` |
| CallbackResult.ScopesIncomplete | `Strava permissions incomplete — reconnect and keep all permission boxes checked` |
| CallbackResult.ExchangeFailed | `Strava connection failed — check your network and try again` |
| launchAuthorize returned false | `Cannot start Strava connect — check API keys and browser` |
| Disconnect complete | `Disconnected from Strava` |
| Long-press: refresh failed / no tokens | `Force refresh: no tokens / refresh failed` |
| Long-press: refresh + athlete OK | `Force refresh OK — athlete verified` |
| Long-press: refreshed, athlete failed | `Refreshed, but GET /athlete failed` |

### Connect / Disconnect / Debug hook
- `btnConnectStrava` → `stravaAuthManager.launchAuthorize(this)`; false (missing keys or no browser) surfaces the launch-failure toast.
- `btnDisconnectStrava` → background `disconnect()` (local wipe ONLY — CONTEXT locked, no remote deauthorize in v1) → toast + card re-read.
- `stravaCard.setOnLongClickListener` → `if (!BuildConfig.DEBUG) return@setOnLongClickListener false` (release builds leave long-press unconsumed — T-03-07) → background `forceRefresh()` + `getAthlete()` → tri-state toast + card re-read. This is Wave 4's 2-second AUTH-03 rotation proof.
- Manager wiring in `onCreate` after `btAudioRouter.init()`, manual DI per codebase convention: `StravaTokenStore(applicationContext)` → `StravaAuthManager(applicationContext, stravaTokenStore)` → `StravaApiClient(stravaAuthManager)`.

## Logcat markers for Wave 4 (03-04 device gate)

All tags: `StravaAuth` (manager + coordinator via injected log), `StravaCallback` (forwarder), `StravaApi` (client), `MainActivity` (routing).

| Marker (grep) | Emitted when |
|---------------|--------------|
| `StravaAuth: state generated, launching authorize` | CONNECT tap, before Custom Tab opens |
| `StravaCallback: Strava callback received` | rokidhud://callback hit the exported forwarder |
| `MainActivity: Strava callback intent received` | forward reached MainActivity (warm OR cold path) |
| `StravaAuth: exchange ok, token stored, expires_at=<epoch> rt#=<hex1>` | code exchange success — FIRST rt# value |
| `StravaAuth: refresh ok expires_at=<epoch> rt#=<hex2>` | forced refresh (long-press) — SECOND rt# value; `hex2 != hex1` is the AUTH-03 rotation proof |
| `MainActivity: GET /athlete ok: connected athlete verified` | authenticated-client proof right after Connected |
| `StravaApi: Rate usage overall=<n,m> read=<n,m>` | every /athlete response (rate-limit awareness) |
| `StravaAuth: state mismatch — rejecting callback` | negative test: forged/replayed state |
| `StravaAuth: callback without code (user denied or error)` | user declined on the consent screen |
| `StravaAuth: granted scopes incomplete — not persisting tokens` | scope checkbox deselected |
| `StravaAuth: exchange failed http=<code>` | token endpoint rejected the code |
| `StravaAuth: disconnected — local tokens wiped` | DISCONNECT tap |
| `MainActivity: GET /athlete failed post-connect (tokens stored; will retry on next use)` | connect OK but athlete fetch failed (NOT an auth failure) |

## Deviations from Plan

None — plan executed exactly as written.

### Notes (non-deviations)
- Verification commands ran from the worktree root instead of the plan's literal `cd /Users/bilhuang/Documents/rokid-maps` (worktree path safety; identical Gradle invocation form — same as the 03-02 precedent).
- `runOnUiThread` count grew exactly +3 (9 → 12), matching the acceptance criterion's minimum.

## Verification Results

- Full repo gate `:phone:testDebugUnitTest :shared:testDebugUnitTest :glasses:testDebugUnitTest assembleDebug` — exit 0 (124 tests, 0 failures; both APKs assembled).
- All five layout ids present; STRAVA block ordering verified: navStatus (422) < stravaCard (532) < GLASSES SETTINGS banner (593).
- `override fun onNewIntent` present (first in class); `handleStravaCallbackIntent(intent)` appears exactly twice (cold + warm); `ACTION_STRAVA_CALLBACK`, `Connected to Strava as`, `getAthlete`, `setOnLongClickListener` + `BuildConfig.DEBUG`, `forceRefresh` all grep-positive.
- Token-store reads: the only `connectedAthleteName()` call sits inside the `Thread{}` block of `refreshStravaCard`; no `isConnected()` usage on any thread.
- No existing behavior changed: AndroidManifest.xml diff vs wave base is empty (launcher intent-filter untouched, zero `launchMode` occurrences); MainActivity diff is 150 insertions / 0 deletions (pure appends, existing listeners unmodified).
- New MainActivity log lines carry event descriptions only — no URI, code, or token material (T-03-04).

## Threat Model Dispositions Applied

| Threat | Mitigation implemented |
|--------|------------------------|
| T-03-01 (callback spoofing/tampering) | MainActivity does zero validation — raw URI goes straight into StravaAuthManager.handleCallback's single validated pipeline (consume-then-validate state, fail-closed scopes) |
| T-03-04 (info disclosure via UI/logs) | Card + toasts show athlete display name ONLY; the one new TAG log is "Strava callback intent received" (event only, never the URI) |
| T-03-07 (debug hook tampering) | Long-press gated on BuildConfig.DEBUG (short-circuits to false, unconsumed in release); performs only an operation the app already does; exposes nothing beyond existing toasts |
| T-03-03 (client_secret in APK) | Accepted per locked decision — no new secret surface added by this plan |

## Known Stubs

None — every function is fully implemented; no TODO/FIXME/placeholder patterns in either modified file.

## Commits

| Hash | Type | Description |
|------|------|-------------|
| 9ab5e03 | feat | STRAVA card layout between RECORD and GLASSES SETTINGS |
| ab03799 | feat | Card state machine + dual-path callback routing + GET /athlete proof |
| b019ee6 | feat | DEBUG-only long-press forced-refresh hook (AUTH-03 rotation proof) |

## For Wave 4 (03-04)

- The ENTIRE phase story now works on a device with API keys in local.properties (`strava.client.id` / `strava.client.secret`) — Wave 4 only proves it.
- Rotation gate: connect → grep `rt#=` (first value) → long-press the connected STRAVA card → grep `rt#=` again (second, different value) → done in ~2 seconds on device.
- Negative test: replay/forge a `rokidhud://callback?...&state=wrong` → expect `state mismatch — rejecting callback` + the security-check toast, no network call.
- Redaction scan: `adb logcat` during the full flow must show no `code=`, token values, or full callback URI — only the markers in the table above.

## Self-Check: PASSED

- Both modified files exist on disk with all required patterns.
- All 3 task commits (9ab5e03, ab03799, b019ee6) present in git log.
