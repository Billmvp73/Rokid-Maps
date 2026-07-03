# Research Summary: Strava Integration + Sport HUD

**Domain:** Strava API + activity recording for AR glasses navigation
**Researched:** 2026-07-02
**Sources:** STACK.md, FEATURES.md, ARCHITECTURE.md, PITFALLS.md, CONCERNS.md

> **NOTE:** Phase numbers in this document predate the final roadmap. Mapping: Research P1(Protocol)+P2(Recording)→Roadmap Phase 1; P3(Glasses)→Phase 2; P4(Auth+Import)→Phase 3 (Auth) + Phase 4 (Import+Nav); P5(Upload)→Phase 5.

## Executive Summary

Adding Strava route import, activity recording, and a sport HUD to Rokid HUD Maps is a well-scoped feature set that fits entirely within the existing phone/glasses/shared architecture. The phone remains the brain (OAuth, API calls, GPS recording), the glasses gain a new SPORT layout mode, and the shared protocol gets 1 new message type (`sport_state` for live metrics; the activity summary is phone-side UI per UPL-01 — no glasses message). **No new module needed. Only 4 explicit production dependency declarations** (OkHttp, Gson, security-crypto, browser — plus logging-interceptor for debug; all lightweight or already transitive).

## Key Findings by Dimension

### Stack
- **No new libraries needed beyond what's already on the classpath.** OkHttp 4.12.0, logging-interceptor 4.12.0, and Gson 2.10.1 are already explicitly declared in phone/build.gradle.kts — verify versions only; no build changes needed.
- **No Retrofit, no coroutines.** The codebase uses callback-heavy patterns with raw Thread/Executor. Adding Retrofit would force coroutine adoption — inconsistent with the rest of the app. Direct OkHttp usage with 6 Strava endpoints is simpler.
- **EncryptedSharedPreferences** for OAuth token storage (3 values: access_token, refresh_token, expires_at). Deprecated but stable, zero setup.
- **No GPX library.** Android's built-in `XmlPullParser`/`XmlSerializer` handles GPX 1.1 trivially. The only Kotlin GPX library is archived (Dec 2025).
- **No OAuth library.** Chrome Custom Tabs + manual redirect handling beats AppAuth-Android's ~300KB overhead for a single provider.

### Features
- **Table stakes (MVP must-have):** Strava OAuth login → browse/import routes → navigate on glasses → record activity (time/distance/pace) → show metrics on HUD → summary on phone → optional upload to Strava.
- **Key differentiators:** Metrics floating in field of view (no phone glance needed), hands-free route import in 3 taps, phone stays in pocket, one-tap upload to Strava.
- **Anti-features deferred:** Heart rate/cadence sensors, auto-pause, auto-start, Strava social feed, live segments, group tracking, music controls, multi-day routes, offline maps.

### Architecture
- **Activity recording plugs into existing GPS pipeline.** `HudStreamingService` already runs GPS at 1Hz. `ActivitySessionManager` becomes a consumer of the same location stream — no duplicate GPS subscriptions.
- **All new phone code under `com.rokid.hud.phone.strava.*`.** Self-contained package: `StravaAuthManager`, `StravaRouteImporter`, `StravaUploader`, `GpxParser`, `ActivitySessionManager`.
- **Glasses changes are minimal.** New `SPORT` layout mode in `HudView` (5th mode alongside FULL_SCREEN, SMALL_CORNER, MINI_BOTTOM, MINI_SPLIT). New `sport_state` protocol message for metrics.
- **Shared protocol gains:** `sport_state` message only (elapsed time, distance, speed/pace, moving time, session state); the activity summary is phone-side UI per UPL-01 — no summary message.
- **Build order dependency:** Protocol messages (shared) → Activity recording + Glasses layout (independent of Strava) ∥ Strava OAuth + Route import → Strava upload (depends on both).

### Pitfalls (Top 5)
1. **Strava OAuth is unusually finicky.** No PKCE support, client_secret must be in APK, redirect URI format differs between settings panel and URL param, mobile endpoint differs from web endpoint. Budget 2-3x initial estimate.
2. **OEM battery optimization kills GPS mid-ride.** Even with foreground service + WakeLock, Xiaomi/Samsung/Huawei/OPPO kill GPS. Need 5+ defensive layers: AlarmManager watchdog, WorkManager, boot receiver, fallback priority chain, battery optimization exemption.
3. **GPX route point density breaks navigation.** Raw GPX tracks can have 10,000+ points. Must downsample with Douglas-Peucker (preserves curves), not uniform stride (loses curves). `NavigationManager` was designed for ~200 OSRM waypoints.
4. **Activity upload has 4 failure modes.** Async processing (poll with 2s interval), duplicate detection (parse error string), GPX validation (timestamps required), token expiry mid-long-ride. **Always persist locally before upload — never delete-after-success.**
5. **GPS noise inflates distance.** Without accuracy gating (>20m reject) and speed filtering (<0.5 m/s reject), stopped-at-lights phantom distance adds 50-100m per stop.

## Confidence Assessment

| Area | Confidence | Reason |
|------|-----------|--------|
| Stack | HIGH | All recommended libraries already on classpath or built into Android SDK |
| Features | HIGH | Verified against Garmin/Wahoo/Karoo specs and Strava API docs |
| Architecture | MEDIUM | Patterns are standard but implementation details depend on codebase specifics |
| Pitfalls | HIGH | Each pitfall verified from multiple sources (Strava docs, Android docs, community forums) |

## Open Questions for Roadmap

- Douglas-Peucker epsilon (10m vs 20m) needs empirical testing with actual user routes
- Strava OAuth client_secret management: accept embedded-in-APK risk or add a minimal BFF?
- OEM test devices: which phone brands to test battery behavior on?
- Sport HUD layout exact design: what metric arrangement works best on monochrome green display?
