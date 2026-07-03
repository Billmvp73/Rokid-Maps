# Phase 1: Activity Recording Engine - Context

**Gathered:** 2026-07-03
**Status:** Ready for planning
**Mode:** Smart discuss (autonomous — recommendations auto-accepted per user authorization)

<domain>
## Phase Boundary

Phone records GPS activity with live metrics and robust background operation. Delivers: opt-in activity recording (independent of navigation), GPS filtering (accuracy gate + moving-state hysteresis), live metrics computation (elapsed, moving time, distance, speed/pace), local JSON persistence with crash checkpoints, the `sport_state` protocol message + ~1Hz phone-side broadcast, multi-layer battery hardening, and the repo's first unit tests. Requirements: REC-01..REC-07. Glasses-side consumption is Phase 2; Strava is Phases 3-5.

</domain>

<decisions>
## Implementation Decisions

### Recording controls (phone UI)
- Dedicated recording card on MainActivity main screen (below search area): big "Start Recording" button; while recording shows live metrics + Stop button
- Recording indicator: foreground-service notification text updates live ("Recording — {distance} · {elapsed}") + in-app REC badge with elapsed time
- Stop requires a confirm dialog ("Finish recording?") to prevent accidental stops
- No discard option at stop — sessions always auto-save; deletion arrives with Phase 5 history UI

### Live metrics on phone
- Card shows: elapsed time, distance, current speed — respecting the existing imperial/metric toggle
- UI updates at 1Hz from the same metrics callback that feeds the BT broadcast (single source of truth)
- Pace (min/km or min/mi) shown as a secondary line alongside speed
- Minimal sport-type toggle (Ride/Run, default Ride) at start; stored as `sport` field in session JSON — Phase 5 Strava upload requires an activity type, capturing it now avoids a retrofit

### Session storage & identity
- Sessions stored under `context.filesDir/activities/` (app-internal storage only — never external storage, per security pitfall)
- One file per session: `{yyyyMMdd-HHmmss}-{shortUuid}.json`; checkpoint file `{id}.checkpoint.json` written every 60s or 500 points (whichever first), atomically renamed/finalized to `{id}.json` on stop
- Orphan checkpoint detection on service/app start (crash recovery per PITFALLS Recovery Strategies)
- Session JSON schema: `id`, `sport` ("ride"|"run"), `startTime`/`endTime` (ISO-8601 UTC — GPX-ready), `elapsedMs`, `movingMs`, `distanceM`, `avgSpeedMps`, `stravaUploaded: false`, `trackPoints[]` of `{lat, lng, alt, ts, speed, acc}`
- Keep all sessions in v1 (no auto-purge)

### Service integration & lifecycle
- `ActivitySessionManager` is a standalone class owned by `HudStreamingService` (single foreground service — a second FGS is forbidden per ARCHITECTURE anti-pattern 1), fed via the LocationConsumer/observer pattern; it owns its own state and thread-safety
- Recording works WITHOUT navigation (free ride/run); navigation never auto-starts/stops recording (REC-01 opt-in, locked project decision)
- START_STICKY restart mid-recording: auto-resume from checkpoint when checkpoint is <10 minutes stale; otherwise finalize as an interrupted session
- Battery-optimization exemption prompt fires on FIRST recording start (not app launch); GPS-staleness warning when no fix for >30s (REC-05)

### Claude's Discretion
- Exact layout/typography of the recording card (match existing dark theme + bg_* drawable conventions)
- AlarmManager watchdog interval details and recovery mechanics (REC-05 defensive layers), within researched patterns
- Precise Douglas-Peucker/moving-average internals are NOT this phase; only the speed-based 5-point moving average from REC-04 applies here
- Test file organization (shared vs phone module placement per TESTING.md priorities)

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- `HudStreamingService` — existing FGS (`location|connectedDevice`), 1Hz FusedLocationProvider (`LOCATION_INTERVAL_MS=1000`, HIGH_ACCURACY, 500ms min), PARTIAL_WAKE_LOCK, BT SPP broadcast loop (`broadcast()`), notification via `HudApplication.CHANNEL_ID`
- `ProtocolCodec`/`ProtocolConstants`/`Messages.kt` (shared module) — add `sport_state` message type + codec + `ParsedMessage.SportState` variant here (REC-07; carries `v:1`)
- `MainActivity` — dark-themed single activity; add recording card following existing `findViewById` + `runOnUiThread` conventions
- Existing prefs pattern (`getSharedPreferences(..., MODE_PRIVATE)`) for sport-type + first-run-prompt flags

### Established Patterns
- No coroutines — raw `Thread`, `Executors`, `Handler`; `@Volatile` + `synchronized` for shared state; `CopyOnWriteArrayList` for listener lists
- Manual JSON via `org.json.JSONObject` (session persistence follows this; no Gson in shared module)
- `try/catch` with `Log.w/e` on all I/O; never propagate; new code must LOG in catch blocks (never silently swallow — Pitfall 6)
- Constants in `companion object` UPPER_SNAKE_CASE with TAG
- Single-thread executor for serial disk writes (mirror `DiskTileCache` pattern for checkpoint writes)

### Integration Points
- `HudStreamingService.onLocationUpdate()` → add LocationConsumer dispatch (NavigationManager stays untouched — its data race is Phase 4's to fix)
- `HudStreamingService.broadcast()` → sport_state at 1Hz while TRACKING
- FGS notification → live text updates during recording (NotificationCompat, existing channel)
- AndroidManifest (phone): add `ACCESS_BACKGROUND_LOCATION` pending on-device validation (STATE todo — existing FGS location type may suffice; validate on the OPPO), `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` for the watchdog
- Unit tests: first `testImplementation` (JUnit4) in shared + phone build.gradle.kts (TESTING.md Priority 1-2)

</code_context>

<specifics>
## Specific Ideas

- sport_state JSON per REC-07 + ARCHITECTURE sample: `{"t":"sport_state","v":1,"et":…,"mt":…,"d":…,"cs":…,"ap":…,"st":"idle|tracking|finished","sp":"ride|run"}` — elapsed/distance monotonic non-decreasing within a session
- Distance accumulation rules (locked): reject fixes with accuracy >20m; moving-state hysteresis enter >0.7 m/s exit <0.3 m/s; 5-point moving average applies to GPS **speed** (NOT position averaging — superseded); haversine on accepted consecutive points; `location.speed` (Doppler) for display speed
- Moving time uses the SAME hysteresis moving-state flag as distance (one flag drives both — resolves STATE todo)
- Test device is OPPO Find X9 Ultra (ColorOS, Android 16) — 30-min screen-off recording is the phase gate; verification via adb (logcat + mock location feed)

</specifics>

<deferred>
## Deferred Ideas

- PAUSED state / manual pause-resume — v2 (locked decision)
- Auto-stop on arrival, lap/splits, configurable HUD fields — v1.x per FEATURES.md
- Full-protocol version negotiation — deferred; only sport_state carries `v:1`
- Activity history list UI + deletion — Phase 5 (UPL-04)

</deferred>

---

*Phase: 01-activity-recording-engine*
*Context gathered: 2026-07-03 via autonomous smart discuss*
