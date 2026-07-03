# Phase 1: Activity Recording Engine - Research

**Researched:** 2026-07-03
**Domain:** Android GPS activity recording (foreground service, background reliability, local persistence, BT protocol extension, first unit tests)
**Confidence:** HIGH (all version-specific claims verified against official docs or AOSP source; ColorOS specifics MEDIUM)

## Summary

This phase needs **zero new runtime dependencies**. Everything builds on the existing `HudStreamingService` foreground service (`location|connectedDevice`, 1Hz FusedLocationProvider on the main looper, PARTIAL_WAKE_LOCK, BT broadcast loop). The only new build changes are test-only: `testImplementation("junit:junit:4.13.2")` plus `testImplementation("org.json:json:20231013")` — with those two lines, `ProtocolCodec` and the `ActivitySessionManager` core logic run as plain-JVM JUnit4 tests with **no Robolectric** [VERIFIED: Maven Central + Google issuetracker 37053370].

Three findings determine the plan's shape. **(1) ACCESS_BACKGROUND_LOCATION is NOT needed for the happy path**: a foreground service with `foregroundServiceType="location"` started while the app is visible keeps full location access when the screen turns off — this is documented "foreground location access" [CITED: developer.android.com/develop/sensors-and-location/location/permissions]. It IS needed for the *recovery* paths: START_STICKY restarts and watchdog-triggered restarts happen with the app in the background, and Android 14 throws `SecurityException` when creating a location-type FGS from the background without it [CITED: developer.android.com/develop/background-work/services/fg-service-types + real-world crash reports]. **(2) The battery-optimization exemption is load-bearing, not optional**: in Doze the system *ignores partial wake locks*; only exempted apps "can use the network and hold partial wake locks during Doze" [CITED: developer.android.com/training/monitoring-device-state/doze-standby]. On ColorOS — which kills background services when the screen turns off with "no known solution on the dev end" [CITED: dontkillmyapp.com/oppo] — the exemption plus user-side settings (Allow background activity / auto-launch) are the only defenses. **(3) The exact-alarm watchdog must be permission-gated**: `SCHEDULE_EXACT_ALARM` is denied by default on fresh installs targeting API 33+, revocation force-stops the app and cancels all alarms, and allow-while-idle alarms are throttled to one per 9 minutes per app in Doze [CITED: developer.android.com alarm + doze docs] — the 15-minute self-chain design clears the throttle, but needs `canScheduleExactAlarms()` gating with an inexact fallback.

**Primary recommendation:** Implement `ActivitySessionManager` as a main-thread-confined state machine (the FLP callback already arrives on the main looper, as do UI start/stop calls), with an immutable metrics snapshot handed to the BT broadcast, notification updater, and a single-thread checkpoint executor (mirroring `DiskTileCache`). Persist via write-temp → fsync → `renameTo` in pure `java.io` so persistence is JVM-unit-testable.

## User Constraints (from CONTEXT.md)

<user_constraints>

### Locked Decisions

**Recording controls (phone UI)**
- Dedicated recording card on MainActivity main screen (below search area): big "Start Recording" button; while recording shows live metrics + Stop button
- Recording indicator: foreground-service notification text updates live ("Recording — {distance} · {elapsed}") + in-app REC badge with elapsed time
- Stop requires a confirm dialog ("Finish recording?") to prevent accidental stops
- No discard option at stop — sessions always auto-save; deletion arrives with Phase 5 history UI

**Live metrics on phone**
- Card shows: elapsed time, distance, current speed — respecting the existing imperial/metric toggle
- UI updates at 1Hz from the same metrics callback that feeds the BT broadcast (single source of truth)
- Pace (min/km or min/mi) shown as a secondary line alongside speed
- Minimal sport-type toggle (Ride/Run, default Ride) at start; stored as `sport` field in session JSON — Phase 5 Strava upload requires an activity type, capturing it now avoids a retrofit

**Session storage & identity**
- Sessions stored under `context.filesDir/activities/` (app-internal storage only — never external storage, per security pitfall)
- One file per session: `{yyyyMMdd-HHmmss}-{shortUuid}.json`; checkpoint file `{id}.checkpoint.json` written every 60s or 500 points (whichever first), atomically renamed/finalized to `{id}.json` on stop
- Orphan checkpoint detection on service/app start (crash recovery per PITFALLS Recovery Strategies)
- Session JSON schema: `id`, `sport` ("ride"|"run"), `startTime`/`endTime` (ISO-8601 UTC — GPX-ready), `elapsedMs`, `movingMs`, `distanceM`, `avgSpeedMps`, `stravaUploaded: false`, `trackPoints[]` of `{lat, lng, alt, ts, speed, acc}`
- Keep all sessions in v1 (no auto-purge)

**Service integration & lifecycle**
- `ActivitySessionManager` is a standalone class owned by `HudStreamingService` (single foreground service — a second FGS is forbidden per ARCHITECTURE anti-pattern 1), fed via the LocationConsumer/observer pattern; it owns its own state and thread-safety
- Recording works WITHOUT navigation (free ride/run); navigation never auto-starts/stops recording (REC-01 opt-in, locked project decision)
- START_STICKY restart mid-recording: auto-resume from checkpoint when checkpoint is <10 minutes stale; otherwise finalize as an interrupted session
- Battery-optimization exemption prompt fires on FIRST recording start (not app launch); GPS-staleness warning when no fix for >30s (REC-05)

**Specific locked mechanics**
- sport_state JSON per REC-07 + ARCHITECTURE sample: `{"t":"sport_state","v":1,"et":…,"mt":…,"d":…,"cs":…,"ap":…,"st":"idle|tracking|finished","sp":"ride|run"}` — elapsed/distance monotonic non-decreasing within a session
- Distance accumulation rules: reject fixes with accuracy >20m; moving-state hysteresis enter >0.7 m/s exit <0.3 m/s; 5-point moving average applies to GPS **speed** (NOT position averaging — superseded); haversine on accepted consecutive points; `location.speed` (Doppler) for display speed
- Moving time uses the SAME hysteresis moving-state flag as distance (one flag drives both)
- Test device is OPPO Find X9 Ultra (ColorOS, Android 16) — 30-min screen-off recording is the phase gate; verification via adb (logcat + mock location feed)

### Claude's Discretion
- Exact layout/typography of the recording card (match existing dark theme + bg_* drawable conventions)
- AlarmManager watchdog interval details and recovery mechanics (REC-05 defensive layers), within researched patterns
- Precise Douglas-Peucker/moving-average internals are NOT this phase; only the speed-based 5-point moving average from REC-04 applies here
- Test file organization (shared vs phone module placement per TESTING.md priorities)

### Deferred Ideas (OUT OF SCOPE)
- PAUSED state / manual pause-resume — v2 (locked decision)
- Auto-stop on arrival, lap/splits, configurable HUD fields — v1.x per FEATURES.md
- Full-protocol version negotiation — deferred; only sport_state carries `v:1`
- Activity history list UI + deletion — Phase 5 (UPL-04)

</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| REC-01 | Opt-in GPS track recording (lat, lng, alt, speed, bearing, ts per point), independent of navigation | LocationConsumer fan-out pattern (Pattern 1); state machine main-thread confinement (Pattern 3); recording card wiring via existing `findViewById`/`runOnUiThread` conventions |
| REC-02 | Live metrics: elapsed, moving time, distance, current speed/pace | Monotonic elapsed via `SystemClock.elapsedRealtime()` anchor (Pattern 4); metrics snapshot single-source-of-truth (Pattern 3); Doppler speed via `location.speed` with `hasSpeed()` guard |
| REC-03 | Accuracy >20m (or ≤0/unknown) recorded in log but excluded from distance | Accuracy gate algorithm (Pattern 5); `location.hasAccuracy()` semantics |
| REC-04 | 0.7/0.3 m/s moving hysteresis + 5-point moving average on GPS speed | Hysteresis + speed-MA algorithm spec with pseudocode (Pattern 5); JVM-unit-testable design |
| REC-05 | Background reliability: WakeLock, FGS notification, battery exemption, ACCESS_BACKGROUND_LOCATION, AlarmManager watchdog, >30s staleness warning | FGS-location permission matrix (Pitfall 1); exact-alarm permission mechanics + fallbacks (Pattern 7, Pitfall 2); Doze/exemption facts (Pitfall 3); ColorOS mitigation checklist (Pitfall 4); staleness via `elapsedRealtimeNanos` (Pitfall 6) |
| REC-06 | JSON persistence on stop + 60s/500pt checkpoint; survives restart | Atomic write pattern in pure java.io for testability (Pattern 6); single-thread checkpoint executor mirroring `DiskTileCache`; orphan-checkpoint recovery flow |
| REC-07 | `sport_state` message (shared module, `v:1`) broadcast ~1Hz with monotonic elapsed/distance | Codec extension in exact existing style (Pattern 2); monotonic clamp at encode boundary; field constants table |
</phase_requirements>

## Project Constraints (from CLAUDE.md)

Directives that bind this phase's plans:

- **Kotlin only**, JVM target 17, minSdk 28, targetSdk/compileSdk 34
- **No coroutines** — raw `Thread`, `Executors.newSingleThreadExecutor()`, `Handler`/`HandlerThread` only
- **JSON via `org.json`** (`JSONObject`/`JSONArray`) — no Gson/Moshi/kotlinx.serialization in new shared/session code
- **No DI, no MVVM/Compose/Fragments** — manual wiring in `onCreate()`, `findViewById`, `runOnUiThread`
- **Error handling**: every I/O call wrapped in try/catch; log with `Log.w/e` (new code must never silently swallow — CONCERNS Pitfall 6); failed reads return null/sentinels
- **Constants** in `companion object` UPPER_SNAKE_CASE with `TAG`
- **Thread safety**: `@Volatile` + `synchronized`; `CopyOnWriteArrayList` for listener lists; single-thread executor for serial disk writes (mirror `DiskTileCache`)
- **Battery**: background operation with screen off via the established WakeLock + FGS pattern
- **GSD workflow**: all edits through GSD commands
- **Prefs**: `getSharedPreferences(name, MODE_PRIVATE)` with manual `edit().putXxx().apply()`
- **Resource naming**: `bg_*` drawables, `camelCase` view IDs, dark theme `#121212` with green accent `#00E676`

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| GPS acquisition (1Hz FLP) | Phone FGS (`HudStreamingService`) | — | Already owns FLP subscription + WakeLock; single GPS pipeline (anti-pattern 1 forbids a second) |
| Track accumulation + metrics | New component (`ActivitySessionManager`, phone) | — | Single source of truth for distance/pace (ARCHITECTURE anti-pattern 5); owns its state/thread-safety |
| GPS filtering (accuracy gate, hysteresis, speed MA) | `ActivitySessionManager` | — | Pure logic, colocated with metrics so saved data == broadcast data |
| sport_state message type + codec | Shared module (`protocol/`) | — | Both APKs must compile identical codec (existing 3-module rationale) |
| ~1Hz sport_state broadcast | Phone FGS (`broadcast()`) | — | Existing BT SPP server owns all writers; ASM never touches sockets |
| Session persistence + checkpoints | New store (phone), single-thread executor | Storage (`filesDir/activities/`) | Serial disk writes off the main thread (DiskTileCache pattern); internal storage only (security) |
| Recording UI (card, confirm dialog, REC badge) | Phone UI (`MainActivity`) | Phone FGS (state queries via binder) | Existing bind-service + `runOnUiThread` pattern |
| Live notification updates | Phone FGS (NotificationManager, ID=1) | — | FGS owns its notification; `HudApplication.CHANNEL_ID` exists (IMPORTANCE_LOW) |
| Watchdog (GPS silence detection) | Phone FGS (Handler timer) | OS AlarmManager (exact alarm chain) | In-process timer works while WakeLock held; AlarmManager survives process death/Doze |
| Battery exemption + permission prompts | Phone UI (`MainActivity`) | OS Settings | Activity context required for permission/settings intents |
| Glasses consumption of sport_state | OUT OF SCOPE (Phase 2) | — | Phase split per STATE decision; unknown types already log-and-drop on glasses |

## Standard Stack

### Core (all existing — no new runtime dependencies)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `com.google.android.gms:play-services-location` | 21.1.0 (pinned; latest 21.4.0) | FLP GPS at 1Hz; `LocationRequest.Builder`; `setMockMode`/`flushLocations` | Already in use; 21.1.0 contains every API this phase needs (Builder API since 21.0.0). Do NOT upgrade — convention is no dependency churn [VERIFIED: Google Maven metadata] |
| `org.json:json` | 20231013 (pinned in shared) | sport_state codec + session JSON | Project JSON convention; runtime uses Android framework org.json, artifact is compile-time [VERIFIED: Maven Central] |
| `androidx.core:core-ktx` | 1.12.0 | `NotificationCompat` live updates | Already in phone module |
| Android framework | API 28–34 | `AlarmManager`, `PowerManager`, `SystemClock`, `Handler`, `java.io` file APIs | No dependency needed for watchdog/persistence |

### Supporting (new, test-only)

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `junit:junit` | 4.13.2 | First unit tests (REC success criterion 7) | `testImplementation` in `shared` AND `phone`. 4.13.2 is the latest JUnit4 and the version shown in official Android testing docs [VERIFIED: Maven Central latest=4.13.2; CITED: developer.android.com/training/testing/local-tests] |
| `org.json:json` | 20231013 | Real org.json on the unit-test classpath (framework classes are stubbed in the mockable android.jar and throw "not mocked") | `testImplementation` in both modules; match the pinned 20231013 to avoid version drift with the compile classpath [VERIFIED: Maven Central; CITED: Google issuetracker 37053370] |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Plain JVM JUnit4 + org.json testImplementation | Robolectric | Robolectric adds a heavy dependency + slow sandbox for zero benefit here — `ProtocolCodec` imports only org.json, and ASM core logic can take primitives. Reserve Robolectric for future Android-framework-coupled tests |
| AlarmManager watchdog | WorkManager/JobScheduler | Jobs are deferred in Doze; Android 16 additionally enforces job runtime quota even while an FGS runs [CITED: developer.android.com/about/versions/16/behavior-changes-all]. AlarmManager allow-while-idle is the correct tool and matches the no-new-frameworks constraint |
| Pure java.io temp+fsync+rename in a session store | `android.util.AtomicFile` (API 17+) | AtomicFile is the framework-blessed implementation of the same pattern, but it is Android-only — persistence would become untestable on plain JVM. The manual pattern is ~15 lines and JVM-testable; AtomicFile is the fallback if plans prefer framework code over testability |
| `Handler(Looper.getMainLooper())` in-process staleness timer | `ScheduledExecutorService` | Either fits conventions; Handler matches existing service code and posts naturally to main thread where session state lives |

**Installation (build.gradle.kts additions only):**
```kotlin
// shared/build.gradle.kts and phone/build.gradle.kts — dependencies block
testImplementation("junit:junit:4.13.2")
testImplementation("org.json:json:20231013")

// phone/build.gradle.kts only — android block (Log.* calls in tested classes return defaults instead of throwing)
testOptions {
    unitTests.isReturnDefaultValues = true
}
```

**Version verification (performed 2026-07-03):**
- `junit:junit` → latest/release `4.13.2` [VERIFIED: repo1.maven.org/maven2/junit/junit/maven-metadata.xml]
- `org.json:json` → latest `20260522`; project pins `20231013` (keep the pin) [VERIFIED: repo1.maven.org]
- `play-services-location` → latest `21.4.0`; project pins `21.1.0` (keep the pin) [VERIFIED: dl.google.com/android/maven2]

## Package Legitimacy Audit

| Package | Registry | Age | Downloads | Source Repo | slopcheck | Disposition |
|---------|----------|-----|-----------|-------------|-----------|-------------|
| `junit:junit:4.13.2` | Maven Central | JUnit4 lineage ~20 yrs; 4.13.2 released 2021 | Ubiquitous (most-depended-upon JVM artifact class) | github.com/junit-team/junit4 | N/A — no Maven support | Approved — registry-verified + cited verbatim in official Android docs |
| `org.json:json:20231013` | Maven Central | Artifact lineage ~15 yrs | Ubiquitous | github.com/stleary/JSON-java | N/A — no Maven support | Approved — already a declared dependency of `shared` (not net-new) |

**Packages removed due to slopcheck [SLOP] verdict:** none (see note)
**Packages flagged as suspicious [SUS]:** none

*Note: slopcheck 0.6.1 was installed and run, but it only supports PyPI/npm ecosystems — it mis-reported both Maven coordinates as "[SLOP] does not exist on pypi", which is a cross-ecosystem false positive, not a verdict about Maven. Verification was therefore performed directly against the authoritative registries (repo1.maven.org, dl.google.com/android/maven2) with exact version-string matches, and `junit:junit:4.13.2` additionally appears verbatim in official Android documentation. Hallucinated-package risk is excluded; no `checkpoint:human-verify` gate needed for these two test-only artifacts, though the planner may add one at zero cost.*

## Architecture Patterns

### System Architecture Diagram

```
                       PHONE (single process, single FGS)
┌────────────────────────────────────────────────────────────────────────────────┐
│  GPS satellites                                                                │
│      │                                                                         │
│      ▼                                                                         │
│  FusedLocationProvider (1Hz, HIGH_ACCURACY, main-looper callback)              │
│      │  LocationResult (iterate .locations, oldest→newest)                     │
│      ▼                                                                         │
│  HudStreamingService.onLocationUpdate(loc)              [main thread]          │
│      ├─► StateMessage broadcast (existing, unchanged)                          │
│      ├─► NavigationManager.onLocationUpdate (existing, untouched)              │
│      └─► LocationConsumer fan-out ──► ActivitySessionManager.recordLocation() │
│                                            │  [main-thread-confined state]    │
│                             ┌──────────────┼──────────────────┐               │
│                             ▼              ▼                  ▼               │
│                      accuracy gate    hysteresis flag    track buffer         │
│                      (>20m → log      (0.7/0.3 m/s on   (ALL fixes incl.      │
│                       only)            5-pt speed MA)     rejected ones)      │
│                             └──────┬───────┘                  │               │
│                                    ▼                          ▼               │
│                          MetricsSnapshot (immutable)    checkpoint trigger    │
│                                    │                    (60s OR 500 pts)      │
│              ┌─────────────────────┼────────────┐             │               │
│              ▼                     ▼            ▼             ▼               │
│   sport_state encode      MainActivity     notification   single-thread       │
│   → broadcast() @1Hz      metrics card     text update    executor:           │
│   (existing BT writers)   (1Hz callback)   (~10s cadence) serialize snapshot  │
│              │                                             → tmp+fsync        │
│              ▼                                             → renameTo         │
│   Glasses (Phase 2 consumes;                                    │             │
│   today: logs "Unknown message")                                ▼             │
│                                              filesDir/activities/             │
│                                              {id}.checkpoint.json →           │
│                                              {id}.json (finalize on stop)     │
│                                                                                │
│  WATCHDOG (parallel plane):                                                    │
│   L1: Handler timer (15s tick) — staleness >30s → warn (REC-05)               │
│   L2: AlarmManager setExactAndAllowWhileIdle 15-min self-chain                │
│       → BroadcastReceiver checks last-fix age; recovery = re-init FLP;        │
│         if process dead: startForegroundService (needs bg-location, P1 below) │
│   L3: START_STICKY + orphan-checkpoint scan in onStartCommand                 │
└────────────────────────────────────────────────────────────────────────────────┘
```

### Recommended Project Structure

```
shared/src/main/java/com/rokid/hud/shared/protocol/
├── Messages.kt            # + SportStateMessage data class
├── ProtocolConstants.kt   # + FIELD_VERSION, FIELD_ELAPSED, FIELD_MOVING_TIME, FIELD_SPORT_DISTANCE,
│                          #   FIELD_CURRENT_SPEED, FIELD_AVG_PACE, FIELD_SESSION_STATE, FIELD_SPORT,
│                          #   MessageType.SPORT_STATE
└── ProtocolCodec.kt       # + encodeSportState(), decode case, ParsedMessage.SportState

phone/src/main/java/com/rokid/hud/phone/
├── ActivitySessionManager.kt   # state machine, filtering, metrics (core logic JVM-testable)
├── SessionStore.kt             # atomic checkpoint/final writes, orphan scan (pure java.io + org.json)
├── RecordingWatchdog.kt        # AlarmManager chain + staleness Handler
├── HudStreamingService.kt      # LocationConsumer fan-out, sport_state broadcast, notification updates
└── MainActivity.kt             # recording card, confirm dialog, permission/exemption prompts

shared/src/test/java/com/rokid/hud/shared/protocol/ProtocolCodecTest.kt
phone/src/test/java/com/rokid/hud/phone/ActivitySessionManagerTest.kt
phone/src/test/java/com/rokid/hud/phone/SessionStoreTest.kt
```

### Pattern 1: LocationConsumer Fan-Out (locked by CONTEXT/ARCHITECTURE)

**What:** `HudStreamingService.onLocationUpdate()` dispatches the `Location` to a consumer list; ASM is one consumer. NavigationManager stays untouched (its data race is Phase 4's).
**When to use:** wiring ASM into the existing GPS pipeline without modifying navigation.

```kotlin
// HudStreamingService — matches ARCHITECTURE.md Pattern 1
interface LocationConsumer { fun onLocationUpdate(location: Location) }
private val locationConsumers = CopyOnWriteArrayList<LocationConsumer>()  // convention: COW list for listeners

// IMPORTANT for recording: iterate ALL locations, not just lastLocation.
// Current builder (maxUpdateDelayMillis default 0) disables batching, but LocationResult
// may still carry >1 fix; .locations is ordered oldest→newest.
// [CITED: developers.google.com LocationRequest.Builder — batching requires maxUpdateDelay >= 2*interval]
locationCallback = object : LocationCallback() {
    override fun onLocationResult(result: LocationResult) {
        for (loc in result.locations) onLocationUpdate(loc)
    }
}
```

### Pattern 2: sport_state Codec in Exact Existing Style (REC-07)

**What:** New message type following the verbatim conventions of `ProtocolCodec.kt` (fetched from source): `JSONObject().apply{}` encode, `optXxx` defensive decode, `ParsedMessage.Unknown` on failure.

**Field mapping (locked by CONTEXT/ARCHITECTURE sample):**

| JSON key | Constant | Type | Meaning |
|----------|----------|------|---------|
| `t` | existing `FIELD_TYPE` | String | `"sport_state"` (`MessageType.SPORT_STATE`) |
| `v` | `FIELD_VERSION = "v"` | Int | Protocol version, always `1` (sport_state only; negotiation deferred) |
| `et` | `FIELD_ELAPSED = "et"` | Long | Elapsed ms — monotonic non-decreasing |
| `mt` | `FIELD_MOVING_TIME = "mt"` | Long | Moving ms (hysteresis flag drives it) |
| `d` | `FIELD_SPORT_DISTANCE = "d"` | Double | Distance meters — monotonic non-decreasing |
| `cs` | `FIELD_CURRENT_SPEED = "cs"` | Double | Doppler m/s |
| `ap` | `FIELD_AVG_PACE = "ap"` | Long | Average pace ms/km (0 until distance ≥ ~100m to avoid absurd values) |
| `st` | `FIELD_SESSION_STATE = "st"` | String | `"idle"` \| `"tracking"` \| `"finished"` |
| `sp` | `FIELD_SPORT = "sp"` | String | `"ride"` \| `"run"` |

```kotlin
// Source style: shared/src/main/java/com/rokid/hud/shared/protocol/ProtocolCodec.kt (verified in repo)
fun encodeSportState(msg: SportStateMessage): String = JSONObject().apply {
    put(ProtocolConstants.FIELD_TYPE, ProtocolConstants.MessageType.SPORT_STATE)
    put(ProtocolConstants.FIELD_VERSION, 1)
    put(ProtocolConstants.FIELD_ELAPSED, msg.elapsedMs)
    put(ProtocolConstants.FIELD_MOVING_TIME, msg.movingMs)
    put(ProtocolConstants.FIELD_SPORT_DISTANCE, msg.distanceM)
    put(ProtocolConstants.FIELD_CURRENT_SPEED, msg.currentSpeedMps)
    put(ProtocolConstants.FIELD_AVG_PACE, msg.avgPaceMsPerKm)
    put(ProtocolConstants.FIELD_SESSION_STATE, msg.sessionState)
    put(ProtocolConstants.FIELD_SPORT, msg.sport)
}.toString()

// decode case inside the existing when(): use optLong/optDouble/optString with safe defaults,
// mirroring StateMessage decode; malformed → existing catch → ParsedMessage.Unknown(raw)
```

**Monotonicity contract (PITFALLS #7):** clamp at the source — ASM keeps `maxElapsedMs`/`maxDistanceM` and never emits a snapshot below them. Do NOT clamp in the codec (codec stays a dumb serializer; keeps round-trip tests trivial).

### Pattern 3: Main-Thread-Confined State Machine with Immutable Snapshots

**What:** Everything that mutates session state runs on the main thread; everything that leaves the session is an immutable snapshot.

**Why this works (verified in repo):** `requestLocationUpdates(..., Looper.getMainLooper())` — GPS callbacks arrive on main. UI start/stop arrive on main. The existing `broadcast(...)` for StateMessage is already invoked from the main-thread callback. So IDLE→TRACKING→FINISHED transitions, filtering, and metric accumulation need **no locks at all** if confined to main; the only cross-thread traffic is (a) an immutable `MetricsSnapshot` data class handed to the checkpoint executor, and (b) `@Volatile` published fields for anything read off-thread (e.g., watchdog receiver reading last-fix time). This satisfies "new components own their thread-safety" (success criterion 6) with the simplest correct design, matching the codebase's `HudState` copy() philosophy.

```kotlin
class ActivitySessionManager {
    enum class SessionState { IDLE, TRACKING, FINISHED }
    var state: SessionState = SessionState.IDLE; private set   // main-thread-confined
    @Volatile var lastFixElapsedRealtimeMs: Long = 0L; private set  // read by watchdog thread

    data class MetricsSnapshot(          // immutable — safe to hand to any thread
        val elapsedMs: Long, val movingMs: Long, val distanceM: Double,
        val currentSpeedMps: Double, val avgPaceMsPerKm: Long,
        val sessionState: String, val sport: String, val trackPointCount: Int
    )
    // callback interface for MainActivity card + service broadcast (single source of truth)
    interface MetricsListener { fun onMetrics(snapshot: MetricsSnapshot) }
}
```

**Testability rule:** core logic must be reachable without `android.location.Location`. Give ASM an internal fix-entry taking primitives; `recordLocation(Location)` is a thin main-source adapter:

```kotlin
fun recordLocation(loc: Location) = onFix(
    loc.latitude, loc.longitude,
    if (loc.hasAltitude()) loc.altitude else Double.NaN,
    loc.time,                                   // wall clock, for trackPoint ts (GPX-ready)
    if (loc.hasSpeed()) loc.speed.toDouble() else Double.NaN,
    if (loc.hasAccuracy()) loc.accuracy.toDouble() else -1.0,
    SystemClock.elapsedRealtime()               // monotonic, for elapsed/staleness
)
internal fun onFix(lat: Double, lng: Double, alt: Double, ts: Long,
                   speedMps: Double, accuracyM: Double, elapsedRealtimeMs: Long) { /* all logic here */ }
```
JUnit tests drive `onFix(...)` directly on plain JVM — no Android types, no Robolectric.

### Pattern 4: Monotonic Time Base

**What:** `elapsedMs = SystemClock.elapsedRealtime() - sessionStartElapsedRealtime`. Wall clock (`System.currentTimeMillis()`) is captured ONCE at start for the ISO-8601 `startTime`, and per-point `ts` comes from `location.time`.
**Why:** `elapsedRealtime()` is monotonic including deep sleep; wall clock can jump (NTP, timezone) and would corrupt elapsed/moving time and violate REC-07 monotonicity. `SystemClock` docs designate `elapsedRealtime` as the recommended interval-timing basis [CITED: developer.android.com/reference/android/os/SystemClock]. Same rule for staleness — see Pitfall 6.

### Pattern 5: Filtering Algorithm (REC-03 + REC-04, locked semantics)

Order of operations per accepted fix (pseudocode the planner can hand to tasks):

```
onFix(...):
  1. append TrackPoint(lat,lng,alt,ts,speed,acc) to track buffer   # ALWAYS (REC-03: log everything)
  2. lastFixElapsedRealtimeMs = elapsedRealtimeMs                  # staleness anchor
  3. rawSpeed = speedMps (NaN if !hasSpeed → treat as unusable for MA this tick)
  4. speedMA  = mean of last ≤5 valid rawSpeed values              # 5-pt moving average on SPEED (locked)
  5. hysteresis: if !moving && speedMA > 0.7 → moving = true       # enter
                 if  moving && speedMA < 0.3 → moving = false      # exit
  6. movingMs += (elapsedRealtimeMs - prevElapsedRealtimeMs) if moving   # same flag drives moving time (locked)
  7. distance gate: accepted = (accuracyM in (0, 20]) && moving    # >20m, ≤0, or unknown → excluded (REC-03)
     if accepted && lastAcceptedPoint != null:
         distanceM += haversine(lastAcceptedPoint, this)           # haversine on accepted consecutive points (locked)
     if accepted: lastAcceptedPoint = this
  8. displaySpeed = rawSpeed (Doppler, locked); pace = derived from avg (elapsed/distance) for `ap`
  9. clamp: elapsedMs/distanceM never below previous emitted values (REC-07 monotonicity)
 10. emit MetricsSnapshot → listeners (BT broadcast, UI, notification throttler)
 11. checkpoint trigger: if (elapsed since last checkpoint ≥ 60s) || (points since last ≥ 500) → snapshot to executor
```

Edge cases the tests must cover: first fix (no distance), accuracy exactly 20.0m (inclusive accept — "greater than 20m are excluded" per REC-03), NaN speed ticks (MA skips, hysteresis holds state), hysteresis boundary values 0.7/0.3 exclusive per REC-04 wording ("above 0.7", "below 0.3"), stationary GPS-drift sequence accumulating zero distance (R5), clock-independent elapsed.

### Pattern 6: Atomic Checkpoint Write (REC-06)

**What:** Serialize a `MetricsSnapshot` + full track buffer copy to JSON on the single-thread executor; write temp file; fsync; rename over the checkpoint name.

```kotlin
// SessionStore — pure java.io + org.json → JVM-testable with TemporaryFolder
fun writeAtomic(target: File, json: String) {
    val tmp = File(target.parentFile, target.name + ".tmp")
    try {
        FileOutputStream(tmp).use { fos ->
            fos.write(json.toByteArray(Charsets.UTF_8))
            fos.fd.sync()                                  // durability before rename
        }
        if (!tmp.renameTo(target)) {                       // POSIX rename: atomic within same filesystem
            Log.e(TAG, "Checkpoint rename failed: ${tmp.name} -> ${target.name}")  // renameTo returns false, never throws
        }
    } catch (e: Exception) {
        Log.e(TAG, "Checkpoint write failed: ${e.message}", e)   // convention: log, never propagate
        try { tmp.delete() } catch (_: Exception) {}
    }
}
```

`filesDir/activities/` and its temp files live on one filesystem, so `renameTo` is the atomic POSIX `rename(2)`; this is the same mechanism `android.util.AtomicFile` uses (write-new + rename/backup, caller does its own threading) [CITED: developer.android.com/reference/android/util/AtomicFile]. Framework `AtomicFile` is acceptable if a task prefers it, at the cost of on-JVM testability.

**Lifecycle:** checkpoint = `{id}.checkpoint.json` (overwritten atomically each cycle); on stop → write final `{id}.json` atomically, then delete checkpoint; on service start → scan for `*.checkpoint.json` without matching final file → if fresher than 10 min, auto-resume; else finalize as interrupted (locked decision).

### Pattern 7: Layered Watchdog (REC-05, discretion area — recommended shape)

| Layer | Mechanism | Cadence | Detects | Survives |
|-------|-----------|---------|---------|----------|
| L1 | `Handler(Looper.getMainLooper()).postDelayed` self-chain | 15s | GPS silence >30s → staleness warning (Toast/notification text + log) | Screen off (WakeLock held + exemption) |
| L2 | `AlarmManager.setExactAndAllowWhileIdle` self-chain → `BroadcastReceiver` | 15 min | Frozen process, ignored WakeLock, silently-dead FLP → re-init `requestLocationUpdates`; if service dead → `startForegroundService` | Doze (allow-while-idle), process death |
| L3 | `START_STICKY` + orphan-checkpoint scan in `onStartCommand` | on restart | OEM kill mid-recording → resume (<10 min stale) or finalize interrupted | Anything that kills the process |

Facts constraining L2 [all CITED: developer.android.com]:
- Allow-while-idle alarms fire **at most once per 9 minutes per app** in Doze — 15-min chain clears this with margin (do not go below ~10 min).
- `SCHEDULE_EXACT_ALARM` is **denied by default** on fresh installs of apps targeting API 33+. Gate with `alarmManager.canScheduleExactAlarms()`; request via `Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)` (system "Alarms & reminders" screen) at first recording start alongside the battery-exemption prompt.
- If denied: fall back to `setAndAllowWhileIdle()` (inexact, still fires in Doze, may drift) or `setWindow()` (min 10-minute window on 12+). The watchdog degrades gracefully — detection latency grows, nothing breaks.
- Revocation **force-stops the app and cancels all its exact alarms**; `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` receiver should re-check `canScheduleExactAlarms()` and reschedule.
- `USE_EXACT_ALARM` (API 33+) is auto-granted and non-revocable but Play-policy-restricted to alarm/calendar apps. This app is sideloaded (not Play-distributed), so declaring **both** and preferring `USE_EXACT_ALARM` is technically viable — flag as a planner option; `SCHEDULE_EXACT_ALARM` + prompt is the conservative default.
- The exact-alarm receiver IS allowed to start an FGS from the background ("app invokes an exact alarm to complete an action that the user requests" is exemption #5; "user turns off battery optimizations" is exemption #13) — but the location-*type* prerequisite still applies (Pitfall 1).
- PendingIntents: `FLAG_IMMUTABLE` (required practice on 31+; existing code already uses it).

### Pattern 8: Live FGS Notification (locked: "Recording — {distance} · {elapsed}")

- Same `NOTIFICATION_ID = 1` + `HudApplication.CHANNEL_ID` (`IMPORTANCE_LOW` — no sound, correct for this).
- `NotificationManager.notify(1, updatedNotification)` updates in place; `setOnlyAlertOnce(true)` prevents repeated head-up/sound.
- Prefer `setUsesChronometer(true).setWhen(System.currentTimeMillis() - elapsedMs)` — the system ticks elapsed time in the notification **without any notify() calls**; then refresh the distance text at a ~10s cadence or every ~100m.
- Rationale: notification updates are rate-limited per package (~10 enqueues/sec hard cutoff since Nougat; platform engineers recommend ≤5/sec) [MEDIUM: AOSP NotificationManagerService + saket.me analysis]. 1Hz notify() would work but is wasteful; chronometer + sparse text updates is the standard fitness-app pattern.
- On stop, restore the static "Streaming to glasses" text (service keeps running for HUD streaming).

### Anti-Patterns to Avoid

- **Second foreground service** for recording — forbidden (ARCHITECTURE anti-pattern 1; two notifications, duplicate GPS, kill races).
- **Distance from `location.distanceTo()` or `speed × time`** — locked to haversine on accepted points; one distance implementation only (ASM), or saved ≠ broadcast metrics (anti-pattern 5).
- **Per-tick disk writes** — 3,600 writes/hour; buffer in memory, checkpoint 60s/500pt (locked).
- **Position-averaging smoother** — explicitly superseded; the 5-point MA applies to GPS *speed* only (STATE todo resolved in CONTEXT).
- **Wall-clock deltas for elapsed/moving/staleness** — see Pattern 4 / Pitfall 6.
- **`result.lastLocation` for recording** — drops sibling fixes in multi-location results; iterate `result.locations`.
- **Auto-start/auto-stop recording from navigation events** — REC-01 locked opt-in.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Atomic file replace | Custom journaling/double-buffer scheme | temp + `fd.sync()` + `renameTo` (or framework `AtomicFile`) | POSIX rename atomicity is the OS primitive; anything fancier re-implements AtomicFile badly |
| Monotonic session clock | Wall-clock arithmetic with drift correction | `SystemClock.elapsedRealtime()` anchor | Framework already provides the monotonic-through-sleep clock |
| Exact-alarm permission UX | Custom permission explainer flows | `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` / `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intents | System screens are the only way; both verified current APIs |
| Notification elapsed ticker | 1Hz notify() loop rebuilding the notification | `setUsesChronometer(true)` | System renders the ticking clock for free, immune to rate limiting |
| Mock GPS injection | Custom socket/broadcast test harness | FLP `setMockMode`/`setMockLocation` (in-app debug feeder) or `adb shell cmd location providers …` | Both are supported, verified mechanisms (see Code Examples) |
| Great-circle distance | New distance formula | Existing haversine approach (NavigationManager precedent) | Locked decision; consistency across codebase |

**Key insight:** every "hard" problem in this phase (atomicity, monotonic time, Doze survival, mock injection) has an OS-provided primitive; the engineering work is *wiring and gating*, not algorithms.

## Common Pitfalls

### Pitfall 1: Location-FGS restart from background throws SecurityException (Android 14+)
**What goes wrong:** Recording runs fine; ColorOS kills the process at minute 20; START_STICKY (or the watchdog alarm) restarts the service with the app in the background; `startForeground()` with the location type throws `SecurityException` ("Starting FGS with type location … the app must be in the eligible state/exemptions"); recording never resumes and the crash may loop.
**Why it happens:** Two independent rule layers. (a) FGS *launch* from background — covered by exemptions (exact alarm = #5, battery-exemption = #13) [CITED: developer.android.com/develop/background-work/services/fgs/restrictions-bg-start]. (b) Location *type prerequisite* — "you cannot create a location foreground service while your app is in the background, unless you've been granted ACCESS_BACKGROUND_LOCATION" [CITED: developer.android.com/develop/background-work/services/fg-service-types]. Layer (b) bites even when (a) passes. This is a documented, widely-reported crash signature on targetSdk 34 [VERIFIED: expo/expo#27336, B4X threads].
**How to avoid:**
1. The happy path needs nothing: FGS started while `MainActivity` is visible = foreground location access, retained with screen off — "Your app retains access when it's placed in the background, such as when the user presses the Home button or turns their device's display off" [CITED: location/permissions doc].
2. For robust recovery, add `ACCESS_BACKGROUND_LOCATION` (manifest) and request "Allow all the time" on first recording start. On Android 11+ this cannot be a dialog — the request routes the user to the settings screen; request `ACCESS_FINE_LOCATION` first (already granted in practice), then background as a separate step.
3. Wrap every `startForeground()` in try/catch(SecurityException) → log + retry path when an activity next resumes (never crash-loop).
4. On-device validation on the OPPO (STATE todo): force-stop mid-recording (`adb shell am kill com.rokid.hud.phone` — kill, not force-stop, to let START_STICKY work), observe whether restart succeeds with/without background permission.
**Warning signs:** logcat `SecurityException: Starting FGS with type location`, recording resumes only when the user reopens the app.

### Pitfall 2: Exact-alarm permission denied/revoked kills the watchdog silently
**What goes wrong:** Fresh install → `SCHEDULE_EXACT_ALARM` not granted → `setExactAndAllowWhileIdle` throws SecurityException, or plans assume it's granted; later the user revokes it in "Alarms & reminders" → the app is **force-stopped and all alarms cancelled** — mid-recording this is a hard kill from a settings toggle.
**Why:** Default-deny for fresh installs targeting 33+ (this app targets 34); revocation semantics are documented [CITED: developer.android.com/develop/background-work/services/alarms/schedule].
**How to avoid:** `canScheduleExactAlarms()` before every schedule; inexact `setAndAllowWhileIdle` fallback; `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` receiver to reschedule on grant; orphan-checkpoint recovery (L3) covers the revocation force-stop. Consider `USE_EXACT_ALARM` (sideloaded app — Play policy moot).
**Warning signs:** `canScheduleExactAlarms()==false` in logs; watchdog receiver never fires in a 30-min test.

### Pitfall 3: Doze ignores the WakeLock unless the app is battery-exempt
**What goes wrong:** 30-min screen-off test on a *stationary* phone (desk test): deep Doze engages, "the system ignores wake locks", FLP callbacks stop, staleness alarm fires but can't fix Doze — test fails even on stock Android, before ColorOS is even involved.
**Why:** Doze restrictions apply regardless of FGS; the FGS only exempts from App Standby. Exempted apps, however, "can use the network and hold partial wake locks during Doze and App Standby" [CITED: doze-standby doc].
**How to avoid:** The locked battery-exemption prompt on first recording start is the fix — treat `isIgnoringBatteryOptimizations()==true` as a *precondition to start recording* (soft-block with the prompt; `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission already in manifest). Note deep Doze needs a stationary device — real rides rarely trigger it, but the desk-bound 30-min verification test WILL; run the gate test with the exemption granted, and prefer a moving test (or mock feed, which keeps fixes flowing) for realism.
**Warning signs:** fixes stop ~30-60 min into a stationary screen-off test; `dumpsys deviceidle` shows `IDLE` state.

### Pitfall 4: ColorOS kills the service regardless of correct Android-side code
**What goes wrong:** All permissions correct, exemption granted — ColorOS still terminates background services when the screen turns off. dontkillmyapp: "No known solution on the dev end" [CITED: dontkillmyapp.com/oppo].
**What code CAN do:** (a) battery-exemption prompt (done above — on ColorOS this maps to "Don't optimize"/"Allow background activity" in newer builds); (b) best-effort deep link to OPPO startup manager wrapped in try/catch → fall back to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` (component names like `com.coloros.safecenter/.startupapp.StartupAppListActivity` vary by version and throw `ActivityNotFoundException` — never hard-depend) [ASSUMED — component names from community sources, verify on device]; (c) first-recording checklist dialog instructing the user; (d) watchdog L2/L3 to detect+recover what can't be prevented.
**What the USER must do on the Find X9 Ultra (ColorOS 15/16-era paths, verify on device):** Settings → Apps → Rokid HUD Maps → Battery usage → **Allow background activity** + **Allow auto launch** (+ Allow foreground activity); Battery → Power Saving Mode OFF during rides; pin the app in recents (lock icon) [MEDIUM: OPPO support + drivequant ColorOS-15 guide + techbone; exact wording differs per ColorOS version].
**Warning signs:** logcat gap with no exception at kill time; `lastFixElapsedRealtimeMs` frozen while service process is gone; activity resumes as "interrupted session" on next launch.

### Pitfall 5: Recording only `result.lastLocation`
**What goes wrong:** Occasional multi-location `LocationResult`s (and any future batching) get collapsed to the newest fix — distance undercounts, track has holes.
**How to avoid:** Iterate `result.locations` (oldest→newest) for the consumer fan-out (existing StateMessage path may keep lastLocation semantics). Batching is off with the current request (`maxUpdateDelayMillis` default 0; batching requires ≥2× interval) [CITED: LocationRequest.Builder docs], so this is cheap defense, not a behavior change. Call `fusedLocationClient.flushLocations()` on stopSession() to drain any undelivered fixes before finalizing.

### Pitfall 6: Staleness detection from `location.getTime()`
**What goes wrong:** `getTime()` is GPS/UTC wall time — device clock corrections make fixes look fresh/stale wrongly; PITFALLS #2's `Location.getTime()` suggestion is the trap.
**How to avoid:** Compare `SystemClock.elapsedRealtime()` against the `@Volatile` last-fix anchor captured from `SystemClock.elapsedRealtime()` at delivery (or `location.elapsedRealtimeNanos/1_000_000`). `Location.elapsedRealtimeNanos` is the documented freshness basis (API 17+; `getElapsedRealtimeAgeMillis()` needs API 33 — compute manually for minSdk 28).

### Pitfall 7: Unit tests explode with "Method … not mocked" or silently pass on stubs
**What goes wrong:** `JSONObject` in a plain-JVM test hits the mockable android.jar stub → `RuntimeException: not mocked`. Or `returnDefaultValues=true` is set globally and a test asserts against a stubbed `Location` getter returning 0 — false green.
**Why:** The unit-test android.jar contains no real code; every method throws by default [CITED: developer.android.com/training/testing/local-tests].
**How to avoid:** (a) `testImplementation("org.json:json:20231013")` in both modules — the real artifact on the test classpath shadows the stubs (standard, widely-documented fix; Google's own tracker acknowledges org.json should not be stubbed) [VERIFIED: issuetracker.google.com/37053370 + multiple community sources]. (b) `unitTests.isReturnDefaultValues = true` ONLY in phone (for `Log.*` in ASM/SessionStore); official docs caution to use it sparingly — mitigate by never asserting on Android-type getters: tests drive `onFix(primitives)` and `SessionStore` with `java.io.File` temp dirs. (c) shared module needs no `returnDefaultValues` — `ProtocolCodec` imports only org.json (verified in source).

### Pitfall 8: `renameTo` fails silently
**What goes wrong:** `File.renameTo` returns `false` (no exception) on failure; an unlucky checkpoint cycle leaves a stale checkpoint and nobody notices until crash recovery restores old data.
**How to avoid:** Always check the boolean and `Log.e` on failure (convention requires logging anyway). Same-directory renames inside `filesDir` are same-filesystem → atomic; do not write temp files to `cacheDir` and rename into `filesDir` (cross-mount rename can fail).

### Pitfall 9: Mock mode leaks into real use
**What goes wrong:** After a mock-feed test session, FLP stays in mock mode — real GPS suppressed for the app until reboot/reset; "my ride recorded a straight line at the office".
**Why:** `setMockMode(true)` makes FLP report only `setMockLocation` fixes and clears the FLP cache; the app must be the selected mock-location app (`adb shell appops set com.rokid.hud.phone android:mock_location allow`) and must set mock mode back to false [VERIFIED: FLP docs/community — API current in 21.1.0, not deprecated].
**How to avoid:** Debug-only feeder class; `setMockMode(false)` in a finally; visible "MOCK" tag in the notification while feeding.

### Pitfall 10: Notification updates re-alert or get dropped
**What goes wrong:** Live text updates make the notification re-sound/flash each cycle, or very chatty updates hit the per-package enqueue rate limit and later notify() calls are silently dropped.
**How to avoid:** `setOnlyAlertOnce(true)`, channel already IMPORTANCE_LOW, chronometer for elapsed, ~10s distance-text cadence (Pattern 8).

## Code Examples

### Gradle: first test infrastructure (Wave 0)

```kotlin
// shared/build.gradle.kts — append to dependencies { }
testImplementation("junit:junit:4.13.2")
testImplementation("org.json:json:20231013")

// phone/build.gradle.kts — append to dependencies { } and android { }
testImplementation("junit:junit:4.13.2")
testImplementation("org.json:json:20231013")
android {
    // ...existing...
    testOptions { unitTests.isReturnDefaultValues = true }  // Log.* returns defaults; use with care (official caution)
}
```

Run (JAVA_HOME required on this machine — see Environment Availability):

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :shared:testDebugUnitTest                      # codec tests, ~seconds
./gradlew :shared:testDebugUnitTest :phone:testDebugUnitTest   # full suite
```

### ProtocolCodec round-trip test (shared, plain JVM)

```kotlin
// shared/src/test/java/com/rokid/hud/shared/protocol/ProtocolCodecTest.kt
class ProtocolCodecTest {
    @Test fun sportStateRoundTrip() {
        val msg = SportStateMessage(elapsedMs = 945_000, movingMs = 823_000, distanceM = 8420.0,
            currentSpeedMps = 6.2, avgPaceMsPerKm = 294_000, sessionState = "tracking", sport = "ride")
        val decoded = ProtocolCodec.decode(ProtocolCodec.encodeSportState(msg))
        assertTrue(decoded is ParsedMessage.SportState)
        assertEquals(msg, (decoded as ParsedMessage.SportState).msg)
    }
    @Test fun malformedLineDecodesToUnknown() {
        assertTrue(ProtocolCodec.decode("{\"t\":\"sport_state\",garbage") is ParsedMessage.Unknown)
    }
    @Test fun versionFieldPresent() {
        val json = JSONObject(ProtocolCodec.encodeSportState(/* … */))
        assertEquals(1, json.getInt("v"))
    }
}
```

### Exact-alarm watchdog schedule + gate

```kotlin
// RecordingWatchdog — API-verified pattern
private fun scheduleNext(context: Context) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val pi = PendingIntent.getBroadcast(context, REQ_WATCHDOG,
        Intent(context, WatchdogReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS   // 15 min ≥ Doze 9-min throttle
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)  // no permission pre-31
        } else {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)       // inexact fallback
            Log.w(TAG, "Exact alarms denied — watchdog degraded to inexact")
        }
    } catch (e: SecurityException) { Log.e(TAG, "Watchdog schedule failed: ${e.message}", e) }
}
```

### Staleness check (L1, main-thread Handler)

```kotlin
private val stalenessCheck = object : Runnable {
    override fun run() {
        val ageMs = SystemClock.elapsedRealtime() - sessionManager.lastFixElapsedRealtimeMs
        if (sessionManager.state == SessionState.TRACKING && ageMs > 30_000) {
            Log.w(TAG, "GPS stale: no fix for ${ageMs / 1000}s")   // REC-05 warning surface
            // + notification text swap "GPS signal lost" / UI callback
        }
        handler.postDelayed(this, 15_000)
    }
}
```

### Debug mock-route feeder (on-device end-to-end verification)

```kotlin
// Debug-only. Prerequisite once per install:
//   adb shell appops set com.rokid.hud.phone android:mock_location allow
// (equivalent to Developer options → Select mock location app)
class MockRouteFeeder(private val flp: FusedLocationProviderClient) {
    fun feed(track: List<DoubleArray> /* lat,lng,speedMps */, intervalMs: Long = 1000) {
        Thread {
            try {
                Tasks.await(flp.setMockMode(true))
                for (p in track) {
                    val loc = Location(LocationManager.GPS_PROVIDER).apply {
                        latitude = p[0]; longitude = p[1]; speed = p[2].toFloat()
                        accuracy = 5f; time = System.currentTimeMillis()
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    }
                    Tasks.await(flp.setMockLocation(loc))
                    Thread.sleep(intervalMs)
                }
            } catch (e: Exception) { Log.e(TAG, "Mock feed failed: ${e.message}", e) }
            finally { try { Tasks.await(flp.setMockMode(false)) } catch (_: Exception) {} }  // Pitfall 9
        }.start()
    }
}
```

`setMockMode`/`setMockLocation` are current (non-deprecated) in play-services-location 21.1.0; requirement is being the selected mock-location app [VERIFIED: FLP reference/community]. Include `ACCESS_MOCK_LOCATION` in a debug-only manifest if lint demands it.

### Alternative: pure-adb mock provider (no app code, Android 12+ shell)

```bash
# Verified against AOSP LocationShellCommand.java [VERIFIED: aosp-mirror source]
adb shell appops set com.android.shell android:mock_location allow
adb shell cmd location providers add-test-provider gps --supportsSpeed --supportsBearing --supportsAltitude
adb shell cmd location providers set-test-provider-enabled gps true
adb shell cmd location providers set-test-provider-location gps --location 37.7749,-122.4194 --accuracy 5
# loop set-test-provider-location from a host script to replay a track;
# remove-test-provider gps when done
```

Caveat: feeds the platform `gps` provider that FLP fuses from; OEM builds occasionally diverge — the in-app feeder is the deterministic primary, this is the zero-code secondary. [MEDIUM: syntax verified in AOSP; ColorOS behavior unverified]

### Phase-gate verification (30-min screen-off on the OPPO)

```bash
ADB=/opt/homebrew/share/android-commandlinetools/platform-tools/adb
$ADB shell dumpsys deviceidle whitelist | grep rokid          # exemption granted?
$ADB shell am get-standby-bucket com.rokid.hud.phone          # expect 10 (ACTIVE) while recording
$ADB logcat -s HudStreaming:V ActivitySession:V | ts          # watch 1Hz sport_state/fix logs for gaps
# success: no fix gap >30s across 30 min with screen off; checkpoint mtime advances every 60s:
$ADB shell "run-as com.rokid.hud.phone ls -la files/activities/"
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `LocationRequest.create()` + setters | `LocationRequest.Builder(priority, intervalMs)` | play-services-location 21.0.0 (2022) | Codebase already current — no migration |
| Exact alarms pre-granted | `SCHEDULE_EXACT_ALARM` default-DENIED for fresh installs targeting 33+; `USE_EXACT_ALARM` for alarm/calendar apps | Android 14 (2023) | Watchdog must gate + fallback (Pattern 7) |
| FGS types optional | Type-specific manifest permission + runtime prerequisites enforced (`FOREGROUND_SERVICE_LOCATION`) | Android 14 / targetSdk 34 | Already declared; restart-from-background is the new failure mode (Pitfall 1) |
| `Location.isFromMockProvider()` | `Location.isMock()` (API 31+) | Android 12 | Only relevant to the debug feeder |
| BOOT_COMPLETED could start any FGS | dataSync/camera/mediaPlayback/phoneCall/mediaProjection/microphone banned from BOOT_COMPLETED; **location still allowed** | Android 15, only when targeting 35+ | Not applicable at targetSdk 34; safe if a boot receiver is ever added |
| FGS runtime unlimited | 6-hour timeout for dataSync/mediaProcessing; **no timeout for location type** | Android 15 | Long rides safe [CITED: behavior-changes-15] |
| Jobs unconstrained beside FGS | JobScheduler quota enforced even with FGS running | Android 16 (all apps) | Irrelevant — this phase uses no JobScheduler/WorkManager (and must not) |

**Deprecated/outdated:**
- `LocationRequest.create()`: deprecated — not used here.
- PITFALLS.md's `Location.getTime()`-based staleness: superseded by `elapsedRealtimeNanos` (Pitfall 6).
- PITFALLS.md's position moving-average (n=3): superseded by locked speed-MA decision.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | ColorOS 15/16 settings path on Find X9 Ultra is Settings → Apps → [app] → Battery usage → Allow background activity / auto launch (naming varies) | Pitfall 4 | User checklist wording wrong → user can't find toggles; fix by screenshotting during on-device validation |
| A2 | OPPO startup-manager deep-link component names (`com.coloros.safecenter/...`) still resolve on ColorOS 16 | Pitfall 4 | `ActivityNotFoundException` → fallback to app-details settings already designed in |
| A3 | Notification enqueue rate limit (~10/s hard, ≤5/s recommended) — derived from AOSP source + engineer commentary, not formal docs | Pattern 8 | Worst case: 1Hz updates fine anyway; design already avoids the limit |
| A4 | `adb shell cmd location` test-provider path feeds ColorOS's FLP identically to stock Android | Code Examples | In-app FLP feeder is the primary mechanism; adb path is convenience |
| A5 | `setMockMode(true)` scope is app-visible FLP behavior (docs ambiguous on device-global reach) | Pitfall 9 | Either way, `setMockMode(false)` teardown handles it |
| A6 | Two attached adb devices — `1901092544802583` assumed the OPPO phone, `3B164G01Y7L00000` assumed the Rokid glasses (unconfirmed which is which) | Environment | Use `adb -s <serial> shell getprop ro.product.model` before scripted verification |
| A7 | START_STICKY system restart of an already-running-then-killed location FGS is subject to the same background-start type check as a fresh start (strongly indicated by crash reports; not stated verbatim in docs) | Pitfall 1 | If restart is actually exempt, ACCESS_BACKGROUND_LOCATION becomes purely optional — on-device validation (STATE todo) settles it |

## Open Questions

1. **Ship `ACCESS_BACKGROUND_LOCATION` in v1 manifest, or validate first?**
   - What we know: not needed for happy path (verified); needed for background restart paths on Android 14+ (crash-report-verified, A7); CONTEXT says "pending on-device validation".
   - What's unclear: whether ColorOS/Android 16 restart behavior actually hits the type check on this device.
   - Recommendation: declare the permission + request "Allow all the time" during first-recording onboarding, AND run the kill-restart validation on the OPPO. If the user declines, recording still works; only crash-recovery degrades — log it and surface in the interrupted-session flow. Planner should make the on-device validation a checkpoint task.
2. **`USE_EXACT_ALARM` vs `SCHEDULE_EXACT_ALARM`**
   - Sideloaded app → Play policy irrelevant; USE_EXACT_ALARM removes both the prompt and the revocation-force-stop hazard.
   - Recommendation: declare both; prefer USE_EXACT_ALARM (API 33+) at runtime, fall back to SCHEDULE_EXACT_ALARM flow on API 31-32. Planner's call within the REC-05 discretion area.
3. **Session JSON `schemaVersion` field**
   - Locked schema lists fields without a version. Phase 5 (GPX export/upload) will read these files; a `"schemaVersion":1` key is cheap insurance and additive (does not contradict the locked field list).
   - Recommendation: include it; confirm at plan review since the schema is a locked decision.
4. **Watchdog recovery scope when the process is alive but FLP is silent**
   - Re-calling `requestLocationUpdates` after `removeLocationUpdates` is the researched recovery (PITFALLS #2); whether to also toggle priority (HIGH_ACCURACY → BALANCED → HIGH) as a nudge is unproven. Keep simple re-init first; escalate only if the OPPO test shows silent-FLP incidents.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| JDK 17 | Gradle build/tests | ✓ (NOT on default PATH) | OpenJDK 17.0.19 at `/opt/homebrew/opt/openjdk@17` | Plans MUST `export JAVA_HOME=/opt/homebrew/opt/openjdk@17` before `./gradlew` — bare `java` fails in this shell |
| Gradle wrapper | build | ✓ | 8.11.1 (repo wrapper) | — |
| Android SDK | compile | ✓ | `sdk.dir=/opt/homebrew/share/android-commandlinetools`, platform android-34 present | — |
| adb | on-device verification | ✓ (NOT on PATH) | 1.0.41 at `/opt/homebrew/share/android-commandlinetools/platform-tools/adb` | Use absolute path or extend PATH in scripts |
| Test device (OPPO Find X9 Ultra) | phase gate | ✓ (2 devices attached: `1901092544802583`, `3B164G01Y7L00000`) | Android 16 / ColorOS | Identify with `adb -s <serial> shell getprop ro.product.model` (A6) |
| Internet (Maven Central + Google Maven) | dependency resolution for junit/org.json test artifacts | ✓ (verified via curl) | — | — |

**Missing dependencies with no fallback:** none.
**Missing dependencies with fallback:** `java`/`adb` PATH gaps — absolute paths documented above; executor tasks must not assume PATH.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 4.13.2 (net-new — repo has zero tests; Wave 0 installs it) |
| Config file | none — `testOptions.unitTests.isReturnDefaultValues = true` added to `phone/build.gradle.kts` only |
| Quick run command | `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :shared:testDebugUnitTest` |
| Full suite command | `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :shared:testDebugUnitTest :phone:testDebugUnitTest` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| REC-01 | IDLE→TRACKING→FINISHED transitions; start requires IDLE; stop finalizes; reset | unit | `./gradlew :phone:testDebugUnitTest --tests "*ActivitySessionManagerTest*"` | ❌ Wave 0 |
| REC-02 | elapsed/moving/distance/speed/pace computation from synthetic fix sequences | unit | same as REC-01 | ❌ Wave 0 |
| REC-03 | accuracy >20m / ≤0 / unknown excluded from distance but present in track log | unit | same | ❌ Wave 0 |
| REC-04 | 0.7/0.3 hysteresis (boundary values), 5-pt speed MA, stationary-drift = 0 distance | unit | same | ❌ Wave 0 |
| REC-05 | staleness flag >30s from monotonic anchor (logic only); watchdog scheduling gate | unit (logic) + manual-only (30-min screen-off gate — requires physical OPPO; justification: OEM kill behavior is unmockable) | `--tests "*Staleness*"` + manual protocol in Code Examples | ❌ Wave 0 |
| REC-06 | checkpoint JSON round-trip; atomic write (temp+rename); orphan detection; <10-min resume rule | unit (java.io TemporaryFolder) | `./gradlew :phone:testDebugUnitTest --tests "*SessionStoreTest*"` | ❌ Wave 0 |
| REC-07 | sport_state encode/decode round-trip; `v:1`; malformed→Unknown; monotonic clamp in ASM | unit | `./gradlew :shared:testDebugUnitTest --tests "*ProtocolCodecTest*"` (+ ASM monotonicity in phone tests) | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew :shared:testDebugUnitTest` (seconds) or the module owning the change
- **Per wave merge:** full suite (`:shared:` + `:phone:` testDebugUnitTest) + `./gradlew assembleDebug`
- **Phase gate:** full suite green + 30-min screen-off on-device protocol (Code Examples) before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `shared/build.gradle.kts` + `phone/build.gradle.kts` — add junit + org.json testImplementation; phone testOptions
- [ ] `shared/src/test/java/com/rokid/hud/shared/protocol/ProtocolCodecTest.kt` — covers REC-07
- [ ] `phone/src/test/java/com/rokid/hud/phone/ActivitySessionManagerTest.kt` — covers REC-01..04 (+ monotonic clamp)
- [ ] `phone/src/test/java/com/rokid/hud/phone/SessionStoreTest.kt` — covers REC-06
- [ ] ASM designed with primitive-parameter `onFix(...)` so the above run on plain JVM (design prerequisite, not a file)

## Security Domain

### Applicable ASVS Categories (Level 1)

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | No auth surface this phase (Strava is Phase 3) |
| V3 Session Management | no | — |
| V4 Access Control | yes (minimal) | Session files in `filesDir` (app-private, MODE_PRIVATE) — never external storage (locked decision); no exported components added; watchdog receiver `android:exported="false"` |
| V5 Input Validation | yes | sport_state decode via defensive `optXxx` + catch→`ParsedMessage.Unknown` (existing codec pattern); checkpoint reads validate JSON + required fields before resume, corrupt checkpoint → finalize-as-interrupted, never crash |
| V6 Cryptography | no | No secrets/tokens this phase; no crypto to hand-roll |
| V8 Data Protection | yes | Track logs are precise location history: internal storage only; note `android:allowBackup="true"` in the existing manifest means `files/activities/` enters device backups — acceptable for a personal app, flag to user in Phase 5 if history grows (advisory, not a phase change) |
| V7 Error Handling & Logging | yes | Log staleness/kill events for diagnosis; avoid logging full coordinate streams at INFO in release (existing code logs coordinates — keep new per-fix logs at `Log.d`) |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Malformed BT JSON lines from a paired device | Tampering/DoS | Existing 1024-byte line cap + decode-to-Unknown; sport_state adds no new parse surface on phone (phone only encodes; glasses decode in Phase 2) |
| Mock-location spoofing of recordings | Spoofing | Debug-only feeder; optionally record `location.isMock()` (API 31+) into points later — not required v1 |
| Mutable PendingIntent hijack (watchdog) | Elevation | `PendingIntent.FLAG_IMMUTABLE` (pattern already used in `buildNotification()`) |
| Location-history disclosure via world-readable files | Info disclosure | `filesDir` app-private; never `getExternalFilesDir`/MediaStore (locked + PITFALLS security table) |
| Checkpoint corruption → data loss | Tampering/Integrity | Atomic temp+fsync+rename; validate-on-read; keep final+checkpoint until finalize succeeds |

## Sources

### Primary (HIGH confidence)
- developer.android.com/develop/background-work/services/fg-service-types — location FGS permission + runtime prerequisites + background-start type restriction (fetched 2026-07-03)
- developer.android.com/develop/sensors-and-location/location/permissions — FGS = foreground location access, retained with screen off
- developer.android.com/develop/background-work/services/alarms/schedule — SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM mechanics, revocation semantics, fallbacks, setWindow 10-min floor
- developer.android.com/training/monitoring-device-state/doze-standby — 9-min allow-while-idle throttle, wake locks ignored in Doze, exemption grants (network + partial wake locks)
- developer.android.com/develop/background-work/services/fgs/restrictions-bg-start — 14-item background-FGS-start exemption list (exact alarm #5, battery exemption #13)
- developer.android.com/about/versions/15/behavior-changes-15 — BOOT_COMPLETED FGS type bans (location NOT banned; targeting-35 only); no location-FGS timeout
- developer.android.com/about/versions/16/behavior-changes-all — JobScheduler quota beside FGS (n/a)
- developer.android.com/training/testing/local-tests — mockable android.jar throws by default; returnDefaultValues caution; junit:junit shown as the framework
- AOSP `LocationShellCommand.java` (aosp-mirror/platform_frameworks_base) — exact `cmd location providers …` syntax + shell mock appop
- Maven Central + Google Maven metadata (curl, 2026-07-03) — junit 4.13.2 latest; org.json versions; play-services-location 21.1.0→21.4.0
- Repo source verification: `HudStreamingService.kt` (main-looper FLP, lastLocation-only callback, static notification, START_STICKY, WakeLock), `ProtocolCodec.kt`/`Messages.kt` (org.json-only imports, decode→Unknown), `DiskTileCache.kt` (single-thread executor), manifests (permissions present/absent), build files (no test deps)

### Secondary (MEDIUM confidence)
- dontkillmyapp.com/oppo — ColorOS kill-on-screen-off, "no dev-end solution", user-setting checklist (page content skews to older ColorOS)
- issuetracker.google.com/37053370 + dev.to/medium community posts — org.json testImplementation pattern for unit tests
- expo/expo#27336, B4X forum threads — Android 14 `SecurityException: Starting FGS with type location` on background restart (real-world crash signature)
- saket.me "Android Nougat and rate limiting of notification updates" + AOSP NotificationManagerService — notification enqueue rate limiting
- FusedLocationProviderClient reference/community (B4X, microG source) — setMockMode/setMockLocation current, mock-app requirement
- OPPO support + drivequant ColorOS-15 guide + techbone — modern ColorOS battery setting paths
- developers.google.com LocationRequest.Builder (via search excerpt) — maxUpdateDelayMillis default 0, batching threshold 2× interval, flushLocations

### Tertiary (LOW confidence — flagged for validation)
- OPPO startup-manager deep-link component names (community-sourced, version-drifting) — A2
- `cmd location` behavior on ColorOS specifically — A4

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new runtime deps; test deps registry-verified with official-doc citation
- Architecture: HIGH — locked by CONTEXT/ARCHITECTURE; thread-confinement design grounded in verified repo facts (main-looper FLP)
- Android 14/15/16 FGS + alarm behavior: HIGH — official docs fetched this session
- ColorOS specifics: MEDIUM — dontkillmyapp + OEM community; exact Find X9 Ultra paths need on-device confirmation (A1/A2)
- Unit-test approach: HIGH — official docs + verified codec imports; the one soft spot (classpath shadowing of stubs) is a decade-standard pattern with Google-tracker acknowledgment
- Mock-location verification: HIGH for mechanisms (FLP API + AOSP shell source); MEDIUM for ColorOS interplay

**Research date:** 2026-07-03
**Valid until:** ~2026-08-03 (stable domain; re-check only if targetSdk bumps to 35+, which activates the Android 15 BOOT_COMPLETED/timeout rules)
