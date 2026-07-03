# Pitfalls Research

**Domain:** Strava API integration + activity recording on Android (AR glasses navigation app)
**Researched:** 2026-07-02
**Confidence:** HIGH (multiple verified sources for each finding)

> **NOTE:** "Phase to address" references in this document use the final roadmap numbering (Phase 1 Recording / Phase 2 Glasses HUD / Phase 3 Auth / Phase 4 Import+Nav / Phase 5 Upload). Original research-phase mapping: Research P1(Protocol)+P2(Recording)→Roadmap Phase 1; P3(Glasses)→Phase 2; P4(Auth+Import)→Phase 3 (Auth) + Phase 4 (Import+Nav); P5(Upload)→Phase 5.

## Critical Pitfalls

### Pitfall 1: Strava OAuth client_secret Exposure and Redirect URI Whack-a-Mole

**What goes wrong:**
The authorization flow fails with opaque "redirect_uri invalid" or "Bad Request" errors, or the OAuth code exchange silently fails. Users get stuck in a browser loop and never return to the app. In the worst case, credentials work temporarily then break after a reboot or reinstall because the token was stored in plain text.

**Why it happens:**
Three independent failure modes that compound:

1. **Strava does NOT support PKCE** (per official docs and community confirmation). The client_secret is required for every token exchange and refresh, but there is no secure way to store it on Android. Reverse engineering the APK extracts the secret from BuildConfig or resources. Strava's OAuth violates RFC 9700 (OAuth Security BCP) for native apps.

2. **Redirect URI mismatch is the #1 most-reported Strava OAuth error.** Strava's "Authorization Callback Domain" field in the API settings panel accepts a domain WITHOUT the scheme prefix (e.g., `myapp`, not `myapp://callback`). The actual redirect_uri parameter in the OAuth URL must include the scheme (e.g., `myapp://callback`). Developers get the wrong combination and chase phantom errors for hours. Additionally, Strava's web endpoint (`/oauth/authorize`) and mobile endpoint (`/oauth/mobile/authorize`) behave differently — using the wrong one causes inconsistent behavior.

3. **Android deep links are finicky.** On Android 12+, `android:exported="true"` is required on the activity receiving the OAuth callback intent. Missing `BROWSABLE` or `DEFAULT` categories in the intent filter means the browser never routes back to the app. Testing with `adb` can succeed while real-world browser redirects fail because Chrome Custom Tabs handles `https` schemes differently from custom schemes.

**How to avoid:**
- Accept that client_secret in APK is a risk, and mitigate it: use obfuscation (ProGuard/R8), store secret in BuildConfig via local.properties, and rotate the secret before public release (Strava allows regeneration in the API settings panel).
- Use `https://www.strava.com/oauth/token` NOT `https://www.strava.com/api/v3/oauth/token` for token exchange (no `/api/v3` prefix).
- Use the mobile authorization endpoint: `https://www.strava.com/oauth/mobile/authorize`.
- Set Strava's callback domain to just `myapp` (no scheme, no slashes). Send redirect_uri as `myapp://callback`.
- In AndroidManifest.xml, use a separate `<intent-filter>` block for the custom scheme redirect. Include `BROWSABLE` and `DEFAULT` categories. Set `android:exported="true"`.
- Store tokens in `EncryptedSharedPreferences` (AndroidX Security Crypto), never in plain SharedPreferences or raw DataStore.
- Test the OAuth flow end-to-end on a real device, not just emulator.

**Warning signs:**
- OAuth callback intent never fires on the device (Chrome shows "No app can open this link")
- `{"message":"Bad Request","errors":[{"resource":"Application","field":"redirect_uri","code":"invalid"}]}` in the redirect URL
- `"Record Not Found"` with `"field": "path", "code": "invalid"` from token exchange
- App works in emulator but fails on physical device

**Phase to address:**
Phase 3 (Strava Authentication) + Phase 4 (Strava Route Import + Navigation). This pitfall blocks the entire Strava integration and must be solved first before any Strava feature works.

---

### Pitfall 2: OEM Battery Optimizations Kill Activity Recording Mid-Session

**What goes wrong:**
The user starts a ride, puts the phone in their pocket or mounts it on the handlebars, and the activity records fine for 5-20 minutes. Then GPS silently stops. Later, the user discovers only a straight-line gap or a completely missing activity. The app was "running" (foreground service notification visible) but location updates stopped.

**Why it happens:**
Android's stock Doze mode and background execution limits are already aggressive. OEMs add their own layers: Xiaomi MIUI/HyperOS, Samsung One UI, Huawei EMUI, OPPO ColorOS, Vivo Funtouch OS, and others all have custom battery management that kills foreground services. This is not a bug — it is a deliberate feature of these ROMs.

Key mechanisms:
- Samsung's "Sleeping Apps" list activates after ~3 days of inactivity
- Xiaomi's MIUI resets autostart permissions after OTA updates
- Huawei's PowerGenie aggressively flags apps that wake the system
- OPPO/Vivo freeze apps during user-defined "sleep hours"
- GPS hardware can be silently killed at ~12% battery with NO error callback

The existing codebase has `WakeLock` but does NOT handle `ACCESS_BACKGROUND_LOCATION` (Android 10+) — the CONCERNS.md file flags this. Even with the WakeLock, GPS is separate from CPU wake state.

**How to avoid:**
This requires multiple defensive layers, not a single fix:

1. Request `ACCESS_BACKGROUND_LOCATION` on Android 10+ (currently missing per CONCERNS.md).
2. Check `isIgnoringBatteryOptimizations()` and guide the user to disable battery optimization with a device-specific deep link (dontkillmyapp.com pattern).
3. Implement an AlarmManager watchdog chain: self-chaining `setExactAndAllowWhileIdle()` every 15 minutes during recording to detect if GPS has stopped. If no location update received since last alarm, trigger a recovery (re-initialize FusedLocationProviderClient).
4. Register `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, and `MY_PACKAGE_REPLACED` receivers to re-establish services after reboot/update (Garmin and Wahoo apps do this).
5. Detect GPS data staleness: track `Location.getTime()` and broadcast a "GPS lost" message to glasses if >30 seconds since last fix (the existing codebase lacks this, per CONCERNS.md "No GPS timeout" finding).
6. Wrap all location requests in `withTimeoutOrNull(30_000L)` with a priority fallback chain (HIGH_ACCURACY -> BALANCED_POWER -> LOW_POWER -> lastLocation).
7. Track `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` — if the user revokes exact alarm permission, the entire watchdog chain dies silently.

**Warning signs:**
- Activity has straight-line gaps at predictable intervals (the OEM's kill period)
- GPS works with screen on, stops when screen off
- Activity "restarts" mid-ride in Strava (two partial activities)
- `lastLat`/`lastLng` in HudStreamingService stop updating but service is still alive
- Location permission check passes but `requestLocationUpdates` silently stops delivering

**Phase to address:**
Phase 1 (Activity Recording Engine). The recording reliability must be proven before the upload feature is built. Recording an activity that silently fails is worse than not recording at all — it erases user trust.

---

### Pitfall 3: Strava Route (GPX) to Navigation Waypoint Mismatch

**What goes wrong:**
The user imports a beautiful curvy Strava route, starts navigation, and one of these happens:
- The GPS snaps to the wrong part of the route (loops/overlap confusion)
- Turn instructions advance ahead of the user's actual position and never catch up
- Off-route detection triggers constantly because the GPX waypoints are too dense or too sparse
- The route line on the glasses shows the path accurately but the "next waypoint" jumps unpredictably

**Why it happens:**
The existing `NavigationManager` was designed for OSRM-generated routes with ~100-200 well-spaced waypoints and no duplicate segments. Strava GPX routes can have thousands of tightly-spaced track points (`<trkpt>` elements at 1-second intervals). The existing downsampling logic (`val stride = maxOf(1, coords.length() / 500)`) is from OsrmClient and operates on polyline coordinates, not GPX track points. Using raw GPX points directly into the navigation waypoint system will cause:

- **Actual advancement mechanism (verified):** NavigationManager advances a forward-only `currentStepIndex`, and only when the user comes within 150m of the NEXT step's turn point (with a one-step lookahead skip) — NavigationManager.kt:72-101. Closest-point matching exists ONLY in off-route detection (`nearestRouteDistance`, NavigationManager.kt:144-147).
- **Over-density risk:** dense GPX-derived steps spaced <150m cause rapid multi-step advancement — several steps consumed in quick succession, with instructions racing ahead of the rider.
- **Overlap risk:** on overlapping/doubled-back segments, the off-route closest-point check can match the wrong pass of the route — suppressing legitimate off-route alarms or masking wrong-direction travel.
- **Under-density after naive downsampling:** Aggressive uniform downsampling (take every Nth point) removes critical curve information on winding roads, making the displayed route line inaccurate.

**How to avoid:**
1. Do NOT feed raw GPX track points into NavigationManager's waypoint system. Instead, downsample using the Douglas-Peucker line simplification algorithm (epsilon ~10-20m), which preserves curve shape while removing redundant points. Target ~100-200 output points matching OSRM's typical output density.
2. When navigating a GPX route, the route line displayed on the glasses should use ALL points (simplified for display), but NavigationManager's step advancement should use ONLY the downsampled ~200 points. Decouple display fidelity from navigation logic.
3. Preserve and extend the EXISTING forward-only index behavior (NavigationManager already advances a forward-only step index): maintain the index of the last-consumed waypoint and always advance forward. As an extension, only allow index rewind if the user is >100m behind the last consumed point (off-route detected).
4. For overlapping route segments: store cumulative segment index, not just waypoint index. If the route has trkseg boundaries, treat each segment as an ordered sequence and don't match across segments.
5. Gracefully degrade turn-by-turn: GPX routes from Strava have no turn maneuver data. When navigating a GPX route, display "Follow route" with distance to next waypoint instead of turn arrows. If OSRM via-point routing fails, degrade to follow-route mode.

**Warning signs:**
- Navigation arrow flips direction when the user reaches a switchback/lollipop
- Off-route alarm triggers when the user is clearly on the highlighted route line
- Next-waypoint distance jumps from 200m to 5m to back to 150m
- Route line on glasses is a zigzag that doesn't match the actual road

**Phase to address:**
Phase 4 (Strava Route Import + Navigation). The GPX-to-waypoint conversion IS this phase. Must be designed before the route import UI is built.

---

### Pitfall 4: Activity Upload Fails Silently and Data Is Lost

**What goes wrong:**
The user finishes a 3-hour ride, hits "Upload to Strava," sees a spinner, and then... nothing. Or worse: the app shows "Uploaded" but the activity never appears on Strava. The local recording is already deleted. The ride is gone.

**Why it happens:**
Strava uploads are asynchronous — `POST /uploads` returns a 201 with an upload ID, but Strava processes the file in the background. The response's `status` field says "Your activity is still being processed." The app must then poll `GET /uploads/{id}` until `activity_id` is set. Multiple problems compound:

- **Network failure mid-poll:** If mobile data drops between initial POST and the final poll, the app never learns whether the upload succeeded. The user thinks it uploaded but it didn't.
- **Duplicate detection:** If the same GPX file is uploaded twice (e.g., retry button), Strava returns `error: "duplicate of activity {id}"` — the app must parse this error string and extract the existing activity_id (no structured error format).
- **GPX validation errors:** Missing `<time>` elements cause "Time information is missing" errors. The Strava route export GPX (used for navigation) has timestamps, but the locally-recorded GPX must also include timestamps for every track point. A common mistake is exporting the route GPX as the activity GPX without adding timestamps.
- **Token expiry during long rides:** Access tokens expire 6 hours after creation. If the ride is longer than 6 hours, the token used at upload time may be expired. The refresh flow must handle this gracefully.
- **Scale:** Failures compound: the ` "data" : "empty"` error occurs when the multipart Content-Type is set manually instead of letting the HTTP library handle it. The `external_id` field can help with dedup but is not the primary duplicate detection mechanism.
- **Async processing delay:** Strava can take 30+ seconds to process an upload. The app needs a timeout strategy: poll every 1-2 seconds for up to 2 minutes, then fall back to a "pending" state with retry.

**How to avoid:**
1. **Always persist locally first.** Session JSON is written to disk when recording stops. Upload is a separate, user-initiated action. Never discard local data until the user explicitly confirms or the upload is verified via `activity_id`.
2. Use `id_str` (string) instead of `id` (integer) for upload IDs from the Strava API response. Strava IDs can exceed 32-bit integer range.
3. Poll `GET /uploads/{id}` every 1-2 seconds for up to 2 minutes. After timeout, mark as "upload pending" with manual retry.
4. Parse the `error` field for `"duplicate of activity {id}"` — extract the existing activity_id via regex and treat it as a success (activity already exists on Strava).
5. Before POST, validate the GPX file: check for `<time>` elements in every `<trkpt>`, verify XML well-formedness, ensure the file is not empty.
6. Do NOT manually set `Content-Type: multipart/form-data` — let the HTTP library (OkHttp/Retrofit) handle it. Manually setting it corrupts the boundary delimiter.
7. Refresh the access token before starting the upload if more than 5 hours have elapsed since the last refresh. Use the `expires_at` field from the OAuth token response to check proactively.
8. Show upload progress/status in the UI even during async processing. Use "Upload pending / Processing / Complete / Failed — tap to retry" states.

**Warning signs:**
- Upload response returns 201 but polling always shows `activity_id: null`
- `error` field is non-null but `activity_id` is null
- Strava shows "Uploading..." in the user's feed but never completes
- `"data": "empty"` error on POST
- User reports "my activity showed up twice" (duplicate upload)
- User reports "activity was there yesterday but disappeared" (upload was never finalized)

**Phase to address:**
Phase 5 (Activity Summary + Strava Upload). The upload reliability is the capstone feature and what the user ultimately cares about. Must be designed with all error paths mapped.

---

### Pitfall 5: GPS Noise Inflates Distance and Corrupts Metrics

**What goes wrong:**
The app reports 42.5 km for a ride that Strava says is 41.2 km. Or the pace jumps from 5:00/km to 4:30/km when the user is stopped at a traffic light. The glasses show speed spikes of 60 km/h from GPS bounce. The user's recorded activity has phantom distance they didn't actually travel.

**Why it happens:**
The existing codebase uses haversine distance only for point-to-target proximity checks in `NavigationManager` (distance to destination, next step, and off-route detection). There is no existing cumulative-distance accumulator for consecutive GPS points — the haversine formula exists but is applied to navigation targets, not to GPS track accumulation. REC-02 distance computation is net-new code built from scratch in ActivitySessionManager; the existing haversine utility serves proximity checks only (a duplicate haversine implementation also lives in OverpassSpeedLimitClient).

1. **GPS jitter overestimation:** When stationary (e.g., traffic light), GPS coordinates drift by 3-10 meters randomly. The haversine sum of these drifts can add 100+ meters of phantom distance per hour. The app has no stationary detection or speed-based filtering.
2. **Signal loss underestimation:** When GPS drops out (tunnel, tree cover, device in pocket), the app draws a straight line between the last fix and the reacquired fix. On a winding road, this loses 10-30% of actual distance.
3. **Overcounting at high sample rates:** The existing GPS runs at 1Hz. Every tick adds distance even from sub-meter noise. Over 3 hours at 1Hz, accumulated noise is significant.
4. **No accuracy gating:** The code does not check `Location.getAccuracy()` before using the point. A 50m accuracy fix from a cold start or urban canyon adds a wildly inaccurate point.

**How to avoid:**
1. **Gate on accuracy:** Reject points where `location.accuracy > 20m` for distance accumulation. Still use them for display (avoids freezing the map), but don't add them to the distance sum.
2. **Speed-based filtering:** If `location.speed < 0.5 m/s` (~1.8 km/h), assume stationary. Do not add distance for that tick. The existing code already has speed data from GPS — this is a property check, not a new sensor.
3. **Moving average smoother:** Before computing haversine distance, buffer the last 3-5 points and use the average lat/lng. This removes sub-second GPS jitter without significant loss of accuracy on curves.
4. **Cumulative distance as source of truth:** Track distance as the sum of filtered haversine segments. Do NOT mix this with `location.distanceTo()` (which uses a different Earth model) or speed-based distance (`time * speed`). The ARCHITECTURE.md correctly identifies `ActivitySessionManager` as the single source of truth for metrics.
5. **Document the expected error:** Even Strava's own distance calculations differ between GPS provider, phone model, and recording app. A 2-5% discrepancy from Strava's post-processing is normal. Surface this in the activity summary ("Recorded distance: 42.5 km — Strava may process differently").
6. **For speed display on glasses:** Use GPS `location.speed` (Doppler-based, not derived from distance/time). Doppler speed is more responsive on hills and less prone to averaging artifacts.

**Warning signs:**
- Speed jumps to 0 when GPS fix is lost briefly
- Distance accumulated while the user is clearly stopped at a traffic light
- Recorded GPX file shows zigzag tracks in open areas
- Reported distance for a known route is significantly longer than usual
- Tracks show unrealistic position jumps (>100m in 1 second)

**Phase to address:**
Phase 1 (Activity Recording Engine). Distance filtering must be built into the recording component from the start. Retroactively fixing distance calculation after release means invalidating user activity data.

---

### Pitfall 6: Adding Stateful Features to a Callback-Heavy, Untested Codebase Breaks Everything

**What goes wrong:**
A seemingly simple change — "add a listener for when navigation starts so recording auto-starts" — accidentally breaks existing navigation because the callback chain is modified. Or: the new `ActivitySessionManager` introduces a data race with `NavigationManager` because both read/write shared state without synchronization. The app crashes (or silently misbehaves) during the user's first test ride, and there are no tests to catch it.

**Why it happens:**
The existing codebase has known structural problems:

- `NavigationManager.steps` and `currentStepIndex` are already data-raced across threads (CONCERNS.md: "Data race in NavigationManager")
- `lastLat`/`lastLng` in `HudStreamingService` are not volatile — cross-thread visibility is not guaranteed
- Broad `catch (_: Exception) {}` blocks in 30+ locations swallow errors that would reveal bugs in new code
- No dependency injection means new components must be instantiated and wired manually into the service
- No tests means every refactoring risk is a live deployment risk
- The existing `HudStreamingService` is a god object handling GPS, BT server, collision of apps, tile serving, and APK updates — adding `ActivitySessionManager` as another concern increases coupling

Classic mistake: adding the `ActivitySessionManager` as a direct dependency of `HudStreamingService`, instantiating it inside `onCreate()`, and calling it from the location callback without considering thread safety.

**How to avoid:**
1. **Extract, don't embed:** Create `ActivitySessionManager` as a standalone class with its own lifecycle. It should accept `Location` objects via a `recordLocation()` method and emit metrics via a callback/listener. `HudStreamingService` creates one instance and wires the callbacks, but the session manager owns its own state and thread safety.
2. **Minimize new shared mutable state:** Do NOT write to `lastLat`/`lastLng` or `NavigationManager.steps` from the ActivitySessionManager. It should maintain its own track point buffer. This prevents introducing new data races.
3. **Use the Observer pattern for startup/shutdown:** Instead of modifying `NavigationManager` to emit lifecycle events, poll a simple `isNavigating` flag from the service (which already exists). This is loosely coupled and doesn't modify existing callbacks.
4. **Write smoke tests for the session manager:** Even though the codebase has no tests, the `ActivitySessionManager` can be unit-tested independently (no Android dependencies for the core logic: state machine, distance calculation, pacing). Write at least 5-10 tests covering state transitions, pause/resume, and metric computation. This catches regressions from future changes.
5. **Add logging to ALL catch blocks in new code.** The existing pattern of empty catch blocks makes debugging impossible. New code should log exceptions, not swallow them.
6. **Use a single LocationConsumer list pattern.** The ARCHITECTURE.md recommends this: add `ActivitySessionManager` as a consumer of the location stream alongside the existing `NavigationManager`. This preserves the existing flow without modifying the navigation callback chain.

**Warning signs:**
- Existing navigation behaves differently after adding session manager code (e.g., steps advance differently)
- Stale `lastLat`/`lastLng` reported on glasses after recording starts
- Activity session unit tests fail or can't be written because of Android dependencies
- New code needs to access `NavigationManager` internals or `HudStreamingService` private fields
- Any change to a file in CONCERNS.md's "Data Race" list

**Phase to address:**
Phase 1 (Activity Recording Engine) and ongoing. Every new feature phase must respect the existing codebase's fragility. The first unit tests should be written in Phase 1.

---

### Pitfall 7: Glasses-Phone Protocol Drift and Missing Metric Message Types

**What goes wrong:**
The phone broadcasts `sport_state` messages at 1Hz during recording. The glasses ignore them because the message type isn't recognized, or parse them incorrectly because the field order changed. The app shows "no data" on the sport HUD while actually recording furiously.

**Why it happens:**
The existing protocol has no version negotiation between phone and glasses (CONCERNS.md: "No protocol version negotiation"). The `ParsedMessage` enum in the shared module is updated independently by phone and glasses codebases. If a developer adds a new message type but forgets to update the glasses codec, the glasses log and ignore the message. Unknown message types are ALREADY logged — BluetoothClient.kt:258-259 emits a `Log.w` "Unknown message" warning; the real gaps are (a) no protocol version negotiation, and (b) a known message type with renamed or mis-typed fields decodes to wrong defaults silently.

Additionally, the sport metrics message contains time-series data (elapsed time, distance) that has consistency constraints — the glasses need monotonic values. If the phone sends a distance that goes backwards (e.g., GPS noise filter resets cumulative distance), the glasses display a negative delta.

**How to avoid:**
1. **Protocol versioning:** Add `"v": 1` to every message in the shared protocol. The glasses check the version on connection and reject incompatible versions with a log error (and visible error on goggles). This is a one-time addition but prevents silent drift.
2. **Keep/extend the existing unknown-type warning** — BluetoothClient already logs unknown messages (BluetoothClient.kt:258-259).
3. **Message validation in the shared codec:** Before encoding, validate that `elapsedTimeMs >= 0`, `totalDistanceM >= lastSent`, and `currentSpeedMps >= 0`. If an invariant would be violated, clamp values rather than sending corrupt data.
4. **Monotonicity contract:** Document in `ProtocolConstants.kt` that `elapsedTimeMs` and `totalDistanceM` MUST be monotonic (non-decreasing) within a session. The glasses can use this to detect phone-side bugs.
5. **Test both sides with a null phone:** Build a test mode where the glasses render the sport HUD from hardcoded metrics, verifying the layout works independently of the phone broadcast.

**Warning signs:**
- "Unknown message type" in logcat during recording (silent or verbose)
- Sport HUD is blank but the GPS is clearly working (phone thinks it's broadcasting)
- Distance on glasses occasionally drops to a lower value and then climbs back up
- Adding a new metric field requires changes in 4+ files (message, constant, codec encode, codec decode, both sides) and any miss breaks silently

**Phase to address:**
Phase 1 (Activity Recording Engine) for the initial sport_state type. Phase 2 (Glasses Sport HUD) for handling the new message. The monotonicity contract and protocol versioning should be in Phase 1.

---

## Technical Debt Patterns

Shortcuts that seem reasonable but create long-term problems.

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| Embedding Strava client_secret in BuildConfig | Fastest path to working OAuth | Secret is reversible from APK; rotation requires rebuild + re-release | Always in this codebase (no BFF server possible per project constraints); mitigate with ProGuard, secret rotation before public release |
| Using raw `org.json` for Strava API responses | No new dependency | No type safety, no deserialization errors, no schema validation — an API field rename silently returns null | Never; the codebase already uses manual JSON and CONCERNS.md shows the pain. Use Gson (already transitive via the CXR SDK, per STACK.md's final decision) with typed data classes for Strava API responses — do NOT add a new serialization library (Moshi/kotlinx.serialization) |
| Writing track points to file every GPS tick | Simpler code | 3,600 disk writes/hour destroys battery and storage, causes UI jank | Never; always accumulate in memory, checkpoint every 60 seconds or 500 points |
| No local persistence before Strava upload | Fewer files to manage | Network failure or rate limit = activity data is lost forever | Never; local JSON is the source of truth, Strava is a copy |
| Single `try/catch` around upload flow | Quick error handling | Cannot distinguish between rate limit (retry later), duplicate (redirect to existing), malformed GPX (fix and retry), network (retry now), server error (wait) | Never; each error type needs different handling |
| Not validating GPX before upload | Saves a validation step | 5 minutes of uploading only to get an error back from Strava API 30 seconds later | Never; validate XML well-formedness and `<time>` presence before POST |
| Using `location.distanceTo()` for cumulative distance | Convenience method | Different Earth model than haversine used elsewhere produces inconsistent metrics | Never; use a single distance computation method throughout the codebase |

---

## Integration Gotchas

Common mistakes when connecting to external services.

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| **Strava OAuth token exchange URL** | Using `https://www.strava.com/api/v3/oauth/token` (with `/api/v3/`) | Use `https://www.strava.com/oauth/token` — no `/api/v3` prefix |
| **Strava OAuth authorization endpoint** | Using `/oauth/authorize` for mobile | Use `/oauth/mobile/authorize` for better mobile UX |
| **Strava redirect URI registration** | Registering `https://myapp.com/callback` in Strava settings | Register just `myapp` (domain without scheme) in the Authorization Callback Domain field |
| **Strava redirect URI in request** | Sending `myapp` or `myapp://` (no host/path) | Send `myapp://callback` as the `redirect_uri` parameter |
| **Android intent filter for callback** | Using a single `<intent-filter>` with multiple `<data>` tags | Use separate `<intent-filter>` blocks per scheme/host combination to avoid unexpected matching |
| **Strava scope string** | Using space-delimited scopes like `activity:write read` | Use comma-delimited: `activity:write,read` |
| **Strava upload Content-Type** | Manually setting `Content-Type: multipart/form-data` | Let OkHttp/Retrofit set it automatically; manual header breaks the boundary |
| **Strava upload data_type** | Not setting `data_type` parameter | Always send `data_type: "gpx"` when uploading a GPX file |
| **Strava rate limit header parsing** | Ignoring `X-RateLimit-Usage` response headers | Check these headers on every response and throttle preemptively |
| **Strava upload status polling** | Polling `GET /uploads` every 100ms | Poll every 1-2 seconds max; mean processing time is <2 seconds |
| **GPX timestamp format** | Using local time without timezone | Use ISO 8601 UTC: `<time>2024-01-01T12:00:00Z</time>` or Strava may reject |
| **GPX route download with timestamps** | Assuming Strava's route export GPX has timestamps | Routes exported via `GET /routes/{id}/export_gpx` DO have timestamps (they're from the original user upload). But GPX downloaded from someone else's activity will NOT have timestamps (privacy). Validate before assuming. |

---

## Performance Traps

Patterns that work at small scale but fail as usage grows.

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| **1Hz disk writes for track points** | Battery drain, storage wear, UI jank on low-end devices | Buffer in memory, checkpoint every 60s or 500 points | ~1 hour of recording (3,600 writes) |
| **Full GPX waypoints as navigation waypoints** | Navigation lag, off-route detection useless, waypoint advancement on every GPS tick | Douglas-Peucker downsample to ~200 waypoints | Route with >1,000 track points (any Strava route >10km) |
| **Naive haversine sum without filtering** | Distance 5-15% over actual traveled distance | Accuracy gate (>20m reject), speed gate (<0.5 m/s reject), moving average (n=3) | Immediately; every recording session |
| **No rate limit tracking on Strava API** | 429 errors during route listing (maybe 2 pages) | Track X-RateLimit-Usage headers per endpoint type (read vs overall) | After ~50 route list requests in 15 minutes during development/testing |
| **Sequential tile fetch with 8s timeout per URL** | Tile loading takes 24 seconds when tile server is slow | Shorten timeout to 3s per URL, use concurrent fetches | Already a problem per CONCERNS.md; any coverage gap triggers 3 cascading fails |
| **Busy-looping upload status poll** | Wastes battery and Strava rate limit | 2s polling interval, 2-minute timeout, then "pending" state | Every upload; strava processes asynchronously |

---

## Security Mistakes

Domain-specific security issues beyond general web security.

| Mistake | Risk | Prevention |
|---------|------|------------|
| **client_secret in APK without obfuscation** | Trivial extraction via `strings` or `dex2jar`. Someone creates a malicious app using your API credentials. | ProGuard/R8 obfuscation, store in BuildConfig, rotate before public release. Accept the risk — no BFF server available per project constraints. |
| **Token storage in plain SharedPreferences** | Any app with READ_EXTERNAL_STORAGE or root can read tokens. User gets impersonated on Strava. | Use `EncryptedSharedPreferences` from AndroidX Security Crypto (which wraps file-level encryption + key stored in Android Keystore). |
| **No certificate pinning for Strava API calls** | Compromised CA or network-level attack intercepts OAuth tokens transit | Use OkHttp `CertificatePinner` for strava.com endpoints. Pin the certificate or public key. |
| **WebView for OAuth** | App could inject JavaScript to steal credentials. Strava explicitly blocks WebView-based auth. | Use `ACTION_VIEW` intent with `https://www.strava.com/oauth/mobile/authorize`. Opens in system browser or Chrome Custom Tabs. |
| **GPX file containing location data saved in shared external storage** | Other apps with storage permission can read user's route history and frequent locations | Save session JSON + GPX exports in app-specific internal storage (`context.filesDir`). Do NOT use `getExternalFilesDir` or `MediaStore`. |
| **Uploading to Strava over unencrypted connection** | Activity GPX contains precise timestamps + location data = tracking history | Enforce HTTPS for ALL Strava API calls. The existing codebase already uses HttpURLConnection with URLs that happen to be HTTPS, but verify no plain HTTP fallback. |

---

## UX Pitfalls

| Pitfall | User Impact | Better Approach |
|---------|-------------|-----------------|
| **Activity recording silently stopped by OEM battery saver** | User completes ride, finds no activity recorded. Thinks app is broken. | Show a persistent "REC" indicator on glasses and phone. If GPS stops updating > 30s, show "GPS lost" warning on glasses and play a TTS alert. Guide user to disable battery optimization on first recording attempt. |
| **Strava OAuth success buried in browser redirect** | User is returned to app but sees no confirmation that they're logged in. Taps "Connect" again. | Show a toast or snackbar "Connected to Strava as @username" when the token exchange succeeds. Update the auth button to show the athlete's name and profile picture. |
| **Upload progress invisible** | User taps "Upload to Strava," sees nothing for 30 seconds, assumes it failed, taps again, and creates a duplicate. | Show progressive status in the activity summary screen: "Uploading (50%)" -> "Strava is processing your activity..." -> "Uploaded! View on Strava" with a link. |
| **GPX route navigation has no turn instructions** | User expects "Turn left in 200m" from a route that is just a GPX line. Sees no instructions, thinks navigation is broken. | When navigating a GPX-sourced route (not OSRM-computed), display a label "Following route" instead of turn arrows. Show distance to next waypoint. Optionally, offer to OSRM-calculate the GPX waypoints to generate step data (tradeoff: loses route accuracy, gains turn instructions). |
| **Activity saved but Strava upload fails** | User sees "Activity recorded" but no Strava upload. Does nothing because they don't know it failed. | Mark session JSON with `stravaUploaded: false`. Show a persistent badge on the activity summary. Auto-retry on next app launch. Notify via notification if upload has been pending > 1 hour. |
| **Pace/speed shows zero during GPS reacquisition** | User glances at glasses mid-ride, sees "0 km/h" for 5 seconds while GPS reacquires. | Show last known good values with a "stale" indicator (e.g., italic or dimmed). Only show 0 if GPS has been lost > 30 seconds. |
| **Activity auto-starts with Strava route navigation** | User is browsing routes, taps one to preview, and accidentally starts recording before they're actually riding. | Require explicit "Start Recording" action. Auto-start only from a dedicated "Start Ride" button, not from route selection. |

---

## "Looks Done But Isn't" Checklist

Things that appear complete but are missing critical pieces.

- [ ] **Strava OAuth flow:** Token exchange works. But token refresh fails silently when the refresh token itself expires (30-day lifetime). Verify: the app re-presents the login screen when both access and refresh tokens are invalid.
- [ ] **Strava route import:** Route appears in the list and GPX downloads. But the GPX has 10,000+ waypoints and navigation lags. Verify: GPX is downsampled to ~200 waypoints before being fed to NavigationManager.
- [ ] **Activity recording:** Distance and time display correctly. But the distance is inflated by GPS drift at traffic stops. Verify: speed-based filtering (< 0.5 m/s) rejects stationary drift.
- [ ] **Activity upload:** Upload returns 201 and polling shows `activity_id`. But the GPX file is missing `<time>` elements, and Strava reports "Time information is missing." Verify: every `<trkpt>` element has a valid `<time>` in ISO 8601 UTC format before upload.
- [ ] **Glasses sport HUD:** Sport_state messages arrive and render. But the glasses were updated independently from the phone and the message field order changed. Verify: protocol version negotiation or at minimum, log unrecognized message types.
- [ ] **Upload retry button:** User taps "Retry" and the app sends the GPX again. But Strava's duplicate detection catches the identical file and returns `error: "duplicate of activity {id}"`. Verify: the app parses this error, extracts the existing `activity_id`, and treats it as success (or deletes the original activity first).
- [ ] **Activity persistence:** Session JSON writes to disk on stop. But if the service crashes mid-recording, the session is lost. Verify: checkpoint writes every 60 seconds or 500 points for crash resilience.
- [ ] **Auto-start recording with navigation:** Recording starts when user starts navigating a Strava route. But if the user wants to navigate without recording (just exploring), there is no way to opt out. Verify: recording is opt-in with a clear toggle, not auto-started.
- [ ] **Upload status display:** Shows "Your activity is ready" from Strava. But the `status` field may contain escaped HTML (per Strava API docs). Verify: the status string is rendered safely (no HTML injection risk in the activity summary screen).

---

## Recovery Strategies

When pitfalls occur despite prevention, how to recover.

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| **OAuth token refresh fails (both tokens expired)** | LOW | Show "Reconnect to Strava" button. User re-authenticates with full OAuth flow. Local activity data is preserved — only the API connection is lost. |
| **Activity recording silently stopped by OEM** | HIGH | On next app launch, detect incomplete session (session JSON was started but never finalized). Show "We detected an interrupted recording. Would you like to save what we have?" with the partial data. |
| **Upload returns "duplicate of activity"** | LOW | Parse the `activity_id` from the error message. Redirect to the existing activity on Strava. Mark the local session as `stravaUploaded = true` with the returned activity_id. No data lost. |
| **Upload returns GPX validation error** | LOW | Show the specific error ("Missing time data in track points"). Let the user fix and retry, or export the GPX file manually for upload via the Strava website. |
| **Upload fails due to network loss mid-poll** | MEDIUM | Session JSON still has `stravaUploaded = false`. On next app launch, check for pending uploads and auto-retry. Show a notification "Activity X is ready to upload to Strava" if auto-retry fails. |
| **Phone app crashes mid-recording** | MEDIUM | Session JSON checkpoint (60s interval) means at most 60 seconds of data is lost. On recovery, detect the checkpoint file and resume recording or finalize with the partial data. User can choose to keep or discard the fragment. |
| **Glasses crash mid-activity** | LOW | Phone continues recording uninterrupted. Glasses reconnect via Bluetooth and re-render current metrics from the next `sport_state` broadcast (1Hz). No data loss — the glasses are stateless. |

---

## Pitfall-to-Phase Mapping

How roadmap phases should address these pitfalls.

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| Strava OAuth client_secret + redirect URI | Phase 3 (Strava Authentication) + Phase 4 (Strava Route Import + Navigation) | End-to-end OAuth flow test: tap "Connect Strava", complete login in browser, verify app receives callback, verify EncryptedSharedPreferences has tokens, verify refresh works after 1 hour. |
| OEM battery killing recording | Phase 1 (Activity Recording Engine) | Test on 3 OEM devices (Xiaomi, Samsung, Pixel) with screen off for 30 minutes. Verify GPS data is continuous with no gaps. Check don'tkillmyapp.com for each device. |
| GPX route to waypoint conversion | Phase 4 (Strava Route Import + Navigation) | Import a known twisted route (e.g., Alpine pass switchback). Verify ~200 waypoints preserved. Verify navigation doesn't butterfly on loops. Compare displayed route line to original GPX. |
| Upload failure and data loss | Phase 5 (Activity Summary + Strava Upload) | Simulate all error paths: network disconnected mid-poll, duplicate GPX, missing timestamps, expired token, Strava service error. Verify each produces the correct user-facing state (retry, redirect, re-auth, etc.). |
| GPS noise inflating distance | Phase 1 (Activity Recording Engine) | Walk back and forth on a 50m known straight line with phone in pocket. Verify reported distance is within 5% of actual. Verify while stationary at traffic light: 0m accumulated. |
| Callback-heavy codebase fragility | Phase 1 (ongoing) | Write unit tests for ActivitySessionManager state machine (5+ test cases). Verify existing navigation behavior is unchanged by comparing logs before/after changes. |
| Protocol drift between phone and glasses | Phase 1 (Activity Recording Engine) | Encode a sport_state message, decode it on the glasses side. Verify fields match. Verify unrecognized message type logs a warning (not silent drop). |

---

## Sources

- **Strava API documentation** — Official auth, rate limits, uploads: https://developers.strava.com/docs/authentication/, https://developers.strava.com/docs/uploads/
- **Strava Community "redirect_uri invalid" threads** — https://communityhub.strava.com/developers-api-7/api-v3-auth-error, https://communityhub.strava.com/developers-api-7/how-to-keep-client-secret-secure-in-public-app-1598
- **OEM battery killing foreground services** — dontkillmyapp.com, GPSLogger issue #199 (https://github.com/BasicAirData/GPSLogger/issues/199), dev.to article "What Android OEMs do to background apps" (https://dev.to/stoyan_minchev/what-android-oems-do-to-background-apps-and-the-11-layers-i-built-to-survive-it-28bb)
- **GPX route navigation bugs in the wild** — OsmAnd issues #13031, #16379, #18638; Locus Map forum overlapping course issue; Suunto forum route navigation thread
- **GPS distance calculation research** — Portland State University: "Comparison of GPS distance calculation methods" (https://pdxscholar.library.pdx.edu/cgi/viewcontent.cgi?article=1185&context=trec_reports)
- **Strava upload duplicate detection** — GOTOES forum: "Strava says I'm uploading a Duplicate Activity" (https://www.gotoes.org/stravatoolsforum/viewtopic.php?t=180)
- **Android deep link issues** — StackOverflow: deep link custom scheme redirect not working; Strava Community: invalid redirect_uri with Strava app
- **Project-specific concerns** — `.planning/codebase/CONCERNS.md` (data races, empty catch blocks, no protocol negotiation, no background location permission)

---
*Pitfalls research for: Strava API integration + activity recording on Android AR glasses navigation app*
*Researched: 2026-07-02*
