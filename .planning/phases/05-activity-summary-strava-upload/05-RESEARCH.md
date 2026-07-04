# Phase 5: Activity Summary + Strava Upload - Research

**Researched:** 2026-07-03
**Domain:** Strava Upload API (async multipart + poll) · GPX 1.1 generation (XmlSerializer) · Android summary/history UI (osmdroid, no-Fragment) · SessionStore write-back — all on the no-coroutines, Thread{}+runOnUiThread, Gson-for-Strava/org.json-for-session codebase
**Confidence:** HIGH (Strava Upload API + OkHttp multipart + GPX structure verified against official docs; all codebase seams read directly; `Instant.toString()` format asserted from the JDK `ISO_INSTANT` contract)

## Summary

Phase 5 is an **integration-and-UI phase, not a new-domain phase**. Every production dependency it needs already ships in `phone/build.gradle.kts` (OkHttp 4.12.0, Gson 2.10.1, osmdroid 6.1.18), and every seam it extends already exists and is unit-tested: `SessionStore` (atomic JSON persistence, `listFinalSessions()`, the read/`fromJson`/`toJson`/`writeAtomic` primitives), `StravaApiClient` (authenticated OkHttp with proactive `ensureFreshToken` + reactive 401 `StravaAuthenticator` + `logRateLimits`), `SessionData`/`TrackPoint` (pure Kotlin, JVM-testable), and the osmdroid `Polyline` route-drawing pattern already used twice in `MainActivity`. The work is: (1) a `GpxWriter` that turns `List<TrackPoint>` into a GPX 1.1 string via `XmlPullParserFactory.newSerializer()` (the *writing* mirror of Phase 4's `GpxParser`); (2) a `StravaUploader`/upload methods on the client doing `POST /uploads` (OkHttp `MultipartBody`) then a `GET /uploads/{id_str}` poll loop; (3) `SessionStore.updateUploadState()` write-back reusing the atomic pattern; (4) `ActivitySummaryActivity` + a history surface.

The single highest-risk surface is **PITFALLS Pitfall 4** (async upload data loss). Its checklist maps 1:1 to verified facts below: `POST /uploads` returns `201` with `id_str` + `status="Your activity is still being processed."` + `activity_id=null`; you poll `GET /uploads/{id}` (≤1/sec, mean <2s) until `status="Your activity is ready."` and `activity_id` is set; duplicates come back as `status="There was an error processing your activity."` with `error="<filename> duplicate of activity <id>"` (note the **filename prefix** — the regex must not anchor at string start on "duplicate"); GPX must carry a `<time>` on every `<trkpt>` in ISO-8601 UTC or Strava rejects with "Time information is missing"; and you must **never** set the multipart `Content-Type` manually (OkHttp owns the boundary). `Instant.ofEpochMilli(ts).toString()` — the exact call the codebase already uses for `startTime`/`endTime` — produces the correct `2026-07-03T15:45:00Z` (or `...00.123Z`) shape for `<time>`. Local JSON is the source of truth; upload success only *adds* `stravaUploaded=true` + the activity id, and failure changes nothing on disk.

**Primary recommendation:** Split the work into pure JVM-testable seams (`GpxWriter`, a `parseDuplicateActivityId` regex helper, an upload-status state machine, a GPX-validity guard, summary pace/speed math) plus thin Android glue (`StravaUploader` network methods on the existing client, `SessionStore.updateUploadState`, `ActivitySummaryActivity`, history list). Use `XmlPullParserFactory.newSerializer()` (kxml2 already on the test classpath) — **not** `android.util.Xml.newSerializer()` (throws `Stub!` on JVM). Read `avgSpeedMps` straight from the persisted `SessionData` (it is already moving-time-based); **derive** avg pace from persisted `distanceM`+`movingMs` (pace is computed but NOT a stored field). Add no new production dependencies.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| GPX generation from track points | Phone (pure logic: `GpxWriter`) | — | Pure `List<TrackPoint>` → String transform; no I/O, no Android; JVM-testable like `GpxParser` [VERIFIED: 04-01-SUMMARY GpxParser pattern] |
| GPX validity guard (non-empty, every trkpt has time) | Phone (pure logic) | — | Fail-fast before any network cost; testable predicate [CITED: PITFALLS Pitfall 4 §5] |
| Multipart upload + poll loop | Phone (network: `StravaUploader`) | Strava API (async processing) | `POST /uploads` + `GET /uploads/{id}`; Strava owns the async activity-processing queue [CITED: developers.strava.com/docs/uploads] |
| Token freshness at upload time | Phone (`StravaAuthManager.ensureFreshToken` — Phase 3) | Strava OAuth | Proactive refresh already primary; reactive 401 `StravaAuthenticator` is the net [VERIFIED: StravaApiClient.kt, 03-02-SUMMARY] |
| Upload-state persistence (write-back) | Phone (`SessionStore.updateUploadState`) | — | Local JSON is source of truth; atomic read-modify-write reusing Phase-1 primitive [VERIFIED: SessionStore.kt] |
| Activity summary rendering (metrics + map) | Phone UI (`ActivitySummaryActivity`) | — | Reads finalized `SessionData` JSON; osmdroid `Polyline` from trackPoints [VERIFIED: MainActivity osmdroid usage] |
| History list | Phone UI (`MainActivity` list or `HistoryActivity`) | — | `SessionStore.listFinalSessions()` → rows → summary [VERIFIED: SessionStore.listFinalSessions] |
| Summary avg speed/pace math | Phone (pure logic) | — | `avgSpeedMps` read from SessionData; pace derived from distanceM+movingMs [VERIFIED: ActivitySessionManager.buildSessionData] |

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Activity summary screen**
- A dedicated `ActivitySummaryActivity` (new) launched when recording stops (from MainActivity's stop→finish flow) AND openable from the history list; shows: total time (elapsedMs), moving time (movingMs — the REC-02 metric finally surfaced per UPL-01), distance, avg speed AND avg pace (imperial-aware), sport, start time, and the route drawn on an osmdroid map from trackPoints
- "Upload to Strava" button (enabled only when Strava-connected; hint "Connect Strava first" otherwise), + "Done"/back
- Summary reads the finalized `SessionData` JSON from `SessionStore` (the file is already the source of truth — REC-06)

**GPX generation from track points (the upload payload — PITFALLS-critical)**
- Generate GPX 1.1 via `android.util.Xml` / `XmlSerializer` (STACK.md — no GPX lib): `<trk><trkseg><trkpt lat lon><ele/><time/></trkpt>...`
- EVERY trkpt MUST have a `<time>` in ISO-8601 UTC (PITFALLS Pitfall 4 — missing time → "Time information is missing" rejection); derive from `TrackPoint.ts` (epoch ms → Instant → ISO UTC)
- Include `<ele>` when altitude is present (NaN → omit); route the recorded track, NOT the Strava route GPX
- GPX validity guard before upload: non-empty, well-formed, every trkpt has time — fail fast with a clear message (PITFALLS "looks done but isn't")

**Upload flow (async + robust — the milestone's highest-risk surface, PITFALLS Pitfall 4)**
- `POST /uploads` (multipart: file=GPX bytes, data_type=gpx, name, activity_type from sport, external_id=session id) via a NEW `StravaUploader` on the Phase-3 authenticated client; let OkHttp set the multipart Content-Type/boundary (NEVER set it manually — PITFALLS gotcha)
- Use `id_str` for the upload id (64-bit safe); poll `GET /uploads/{id_str}` every 2s up to 2 min; success = `activity_id` set; then mark session `stravaUploaded=true` + store the activity_id
- Duplicate handling: on error string "duplicate of activity {id}", regex-extract the activity_id and treat as success (PITFALLS recovery — no data loss, no re-upload)
- Proactive token refresh already handled by the Phase-3 client (`ensureFreshToken`); a 6h+ ride's expired token refreshes transparently at upload time
- Progress UI states: "Uploading…" → "Strava is processing…" → "Uploaded ✓ (View on Strava)" / "Upload failed — Retry" / after 2-min timeout "Pending — Retry later" (PITFALLS UX)
- NEVER delete local session data on upload (success or fail) — local JSON is the source of truth, Strava is a copy (locked project decision)

**Persistence & history**
- `SessionData` already persists locally (Phase 1 REC-06) — Phase 5 ADDS: the `stravaUploaded` flag + `strava_activity_id` are written back to the session JSON on successful upload (`SessionStore` gains an update method)
- Past-activities list: a section/list on MainActivity (or a `HistoryActivity`) reading `SessionStore.listFinalSessions()`; each row: date, sport, distance, duration, an "uploaded ✓" / "not uploaded" badge; tap → `ActivitySummaryActivity`
- Failed/pending uploads keep `stravaUploaded=false` → retry from the summary; no auto-retry-on-launch in v1 (v1.x per FEATURES)

**Rate limits**
- Upload is a WRITE (1,000 writes/15min — single user never near it); log `X-RateLimit-Usage` on the POST + polls; 429 → "Strava busy — retry shortly"

### Claude's Discretion
- `ActivitySummaryActivity` vs a fragment/section; `HistoryActivity` vs a MainActivity list — pick the cleaner given no-Fragment convention
- `GpxWriter` / `StravaUploader` class split within `com.rokid.hud.phone.strava`
- Exact summary layout + map styling (existing dark-theme conventions)
- Poll backoff details within the 2s/2min envelope

### Deferred Ideas (OUT OF SCOPE)
- Auto-retry pending uploads on next launch — v1.x (FEATURES)
- Auto-sync (upload without confirm) — v2 (SOCL-02)
- Segment results / kudos / social — out of scope
- Editing activity title/description before upload — v1.x (default name from sport + date)
- Elevation gain/loss computed metric — v2 (RECV-03)
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| UPL-01 | After recording stops, phone displays an activity summary: total time, moving time, distance, average speed/pace, route map | `SessionData` (persisted) carries `elapsedMs`, `movingMs`, `distanceM`, `avgSpeedMps`, `trackPoints`; pace is **derived** from `distanceM`+`movingMs` (not a stored field — see Pitfall 6). osmdroid `Polyline` from trackPoints reuses the exact `MainActivity` route-draw pattern. Format helpers (`formatDist`/`formatSpeed`/`formatPace`/`formatElapsed`) exist but are `private` in MainActivity → extract to a shared object or duplicate (see Code Examples). |
| UPL-02 | Upload the completed activity to Strava with one tap (generate GPX, POST, poll) | `POST /uploads` multipart fields + response shape + `GET /uploads/{id}` poll flow all VERIFIED against official docs (Standard Stack / Code Examples). OkHttp `MultipartBody` idiom VERIFIED. `GpxWriter` via `XmlPullParserFactory.newSerializer()`. Token freshness already handled by Phase-3 `ensureFreshToken`. |
| UPL-03 | Upload failure never deletes local data; failed uploads can be retried | `SessionStore` never deletes on upload; `updateUploadState()` only *adds* `stravaUploaded=true`+`activityId` on success. Failure leaves JSON untouched (`stravaUploaded=false`). Duplicate-error recovery marks success without re-upload. Atomic write-back reuses Phase-1 `writeAtomic`. |
| UPL-04 | View past recorded activities (local history list) | `SessionStore.listFinalSessions()` already exists (newest-first, final `.json` only) — explicitly built as "the Phase-5 history/upload seam (UPL-04)". Each `File` → `fromJson` → `SessionData` row. |
</phase_requirements>

## Standard Stack

**No new production dependencies.** Every library Phase 5 needs is already declared and version-locked in `phone/build.gradle.kts` [VERIFIED: phone/build.gradle.kts read 2026-07-03].

### Core (already present — reuse, do not add)
| Library | Version | Purpose in Phase 5 | Why Standard |
|---------|---------|--------------------|--------------|
| `com.squareup.okhttp3:okhttp` | 4.12.0 | `MultipartBody` upload POST + poll GET | Already the transport for `StravaApiClient`; owns multipart boundary [VERIFIED: build.gradle.kts + StravaApiClient.kt] |
| `com.google.code.gson:gson` | 2.10.1 | Parse `POST /uploads` + `GET /uploads/{id}` JSON responses | STACK.md rule: Gson for Strava responses only [VERIFIED: build.gradle.kts, StravaModels.kt precedent] |
| `org.osmdroid:osmdroid-android` | 6.1.18 | Summary route map (`Polyline` from trackPoints) | Already used for nav preview + Strava route preview [VERIFIED: MainActivity.kt osmdroid usage] |
| `org.json:json` (Android built-in) | 20231013 (test dep) | `SessionStore` write-back JSON (`updateUploadState`) | STACK.md rule: org.json for session JSON [VERIFIED: SessionStore.kt] |
| `java.time.Instant` (JDK, minSdk 28) | JDK 17 | `<time>` ISO-8601 UTC per trkpt | Same call already used for `startTime`/`endTime`/interrupted `endTime` [VERIFIED: ActivitySessionManager.kt, SessionStore.kt] |
| `org.xmlpull.v1.XmlPullParserFactory` → `newSerializer()` | Android built-in (kxml2) | GPX 1.1 writing | Mirror of `GpxParser`'s JVM-testable factory approach [VERIFIED: GpxParser.kt + WebSearch] |

### Supporting (test classpath — already present)
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `junit:junit` | 4.13.2 | JVM unit tests | All pure-seam tests [VERIFIED: build.gradle.kts] |
| `net.sf.kxml:kxml2` | 2.3.0 (testImplementation) | Provides `KXmlSerializer` on JVM test classpath so `XmlPullParserFactory.newSerializer()` resolves off-device | `GpxWriter` tests — **this is what makes GPX WRITING JVM-testable** (same trick that made `GpxParser` testable) [VERIFIED: build.gradle.kts comment + 04-01-SUMMARY] |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `XmlPullParserFactory.newSerializer()` | `android.util.Xml.newSerializer()` | **Rejected.** `android.util.Xml` throws `RuntimeException: Stub!` on the JVM test classpath (`unitTests.isReturnDefaultValues=true` returns defaults for Log but NOT for factory-instantiated Xml) — would force Robolectric and break the fast pure-JVM discipline. `XmlPullParserFactory` works identically in prod (Android bundles kxml2) and test (kxml2 test dep) [VERIFIED: 04-01-SUMMARY GpxParser decision + WebSearch] |
| `XmlPullParserFactory.newSerializer()` | Hand-rolled string concatenation / `StringBuilder` GPX | **Rejected.** Manual XML risks unescaped characters in activity name/description and malformed output; a real serializer guarantees well-formedness (which the validity guard then re-checks). CONTEXT locks `XmlSerializer`. |
| Gson upload-response models | `org.json` for upload responses | **Rejected.** STACK.md + 03/04 precedent: Strava responses use Gson (typed, all-nullable). Consistency with `StravaRoute`/`TokenResponse`. org.json stays for the *session* JSON only. |
| New `HistoryActivity` | History list section inside `MainActivity` | Either is valid (Claude's discretion). A list section on MainActivity has less manifest/lifecycle surface; a `HistoryActivity` keeps MainActivity (already ~1560 lines) from growing. Recommend a small `HistoryActivity` for separation, but defer to planner. |

**Installation:** None. `assembleDebug` already builds with all of the above.

**Version verification:** All versions read directly from `phone/build.gradle.kts` on 2026-07-03 — no registry lookup needed because nothing is being added. OkHttp 4.12.0, Gson 2.10.1, osmdroid 6.1.18, kxml2 2.3.0 (test), junit 4.13.2 (test) are the exact locked versions the prior four phases compiled against.

## Package Legitimacy Audit

> Phase 5 installs **no external packages**. All libraries are pre-existing, version-locked dependencies audited and approved in Phases 1–4. slopcheck/registry verification is not applicable because the dependency set is unchanged.

| Package | Registry | Age | Downloads | Source Repo | slopcheck | Disposition |
|---------|----------|-----|-----------|-------------|-----------|-------------|
| (none added) | — | — | — | — | — | N/A — no new packages |

**Packages removed due to slopcheck [SLOP] verdict:** none (no packages evaluated — none added)
**Packages flagged as suspicious [SUS]:** none

*If a future planning decision proposes adding a package (it should not — this phase reuses existing deps), run the Package Legitimacy Gate before adding it to build.gradle.kts.*

## Architecture Patterns

### System Architecture Diagram

```
RECORDING STOP (existing Phase-1 flow)
  MainActivity.confirmStopRecording() ──"Finish"──> service.stopRecording()
    └─> ActivitySessionManager.stopSession() ──> SessionData (final)
         └─> SessionStore.finalizeAsync(data)  writes {id}.json  [source of truth]
    └─> [NEW] MainActivity launches ActivitySummaryActivity(sessionId)

ACTIVITY SUMMARY (new — read path)
  ActivitySummaryActivity(sessionId)
    │  Thread{} ─> SessionStore.read(sessionId) ─> fromJson ─> SessionData
    │                                                  │
    │  runOnUiThread ──────────────────────────────────┤
    ├─> render metrics: elapsedMs, movingMs, distanceM, avgSpeedMps (from data),
    │                    avgPace (DERIVED: movingMs / (distanceM/1000)), sport, startTime
    ├─> osmdroid MapView: Polyline over data.trackPoints (GeoPoint list) + fit bbox
    └─> "Upload to Strava" button  (enabled iff StravaAuthManager.isConnected())

UPLOAD (new — the Pitfall-4 surface)
  [tap Upload] ─> Thread{} {
    1. GpxWriter.write(data.trackPoints, sport, startTime) ─> gpx: String   (PURE)
       └─ every <trkpt> gets <time> = Instant.ofEpochMilli(ts).toString()
       └─ <ele> only when alt is finite
    2. GpxWriter.isValidForUpload(gpx) guard (non-empty, well-formed, every trkpt has time)  (PURE)
       └─ fail ─> runOnUiThread "Can't upload: GPX invalid"   (no network cost)
    3. StravaUploader.upload(gpx, name, externalId=data.id, sportType)
         ├─ ensureFreshToken()  (Phase-3 proactive; refreshes a 6h+ ride's token)
         ├─ POST /api/v3/uploads  (OkHttp MultipartBody.FORM; OkHttp sets boundary)
         │    body parts: file(gpx bytes, "<name>.gpx"), data_type=gpx, name,
         │                sport_type, external_id ; NO manual Content-Type
         │    ─> 201 {id_str, status:"...still being processed.", activity_id:null, error:null}
         │    ─> 429 ─> RateLimited ("Strava busy — retry shortly")
         │    ─> other non-2xx / error!=null ─> parse error
         └─ POLL GET /api/v3/uploads/{id_str}  every 2s, up to 2 min:
              ├─ status "ready" + activity_id set ─> Success(activityId)
              ├─ error contains "duplicate of activity <id>" ─> parseDuplicateActivityId
              │     ─> Success(existingActivityId)   (no re-upload, no data loss)
              ├─ error non-null (other) ─> Failed(msg)
              └─ 2-min timeout, still processing ─> Pending
    4. on Success ─> SessionStore.updateUploadState(id, activityId, uploaded=true)
                     (atomic read-modify-write; adds fields, deletes nothing)
    5. runOnUiThread ─> state machine ─> UI text + button
  }

HISTORY (new — UPL-04)
  HistoryActivity (or MainActivity section)
    │  Thread{} ─> SessionStore.listFinalSessions() ─> List<File>
    │              each ─> fromJson ─> SessionData
    │  runOnUiThread ─> rows: date, sport, formatDist, formatElapsed, ✓/✗ badge
    └─> tap row ─> ActivitySummaryActivity(sessionId)
```

### Recommended Project Structure
```
phone/src/main/java/com/rokid/hud/phone/
├── strava/
│   ├── GpxWriter.kt          # NEW  pure: List<TrackPoint> -> GPX 1.1 String + isValidForUpload guard + duplicate-id regex
│   ├── StravaUploader.kt     # NEW  network: POST /uploads + poll GET /uploads/{id}; UploadResult sealed type
│   ├── StravaModels.kt       # EXTEND  add UploadResponse Gson model (all-nullable) + sport->sport_type map
│   └── StravaApiClient.kt    # (unchanged, or host upload methods — planner's split choice)
├── ActivitySummaryActivity.kt   # NEW  reads SessionData, renders metrics+map, drives upload
├── HistoryActivity.kt           # NEW (or a MainActivity list section — discretion)
├── SessionStore.kt              # EXTEND  add read(id)/readSession + updateUploadState(id, activityId)
└── MainActivity.kt              # EXTEND  launch ActivitySummaryActivity on finish; entry to history

phone/src/main/res/layout/
├── activity_activity_summary.xml   # NEW  dark theme; MapView + metric TextViews + upload button
└── activity_history.xml            # NEW (if HistoryActivity) + item_activity.xml row

phone/src/test/java/com/rokid/hud/phone/strava/
├── GpxWriterTest.kt          # NEW  every-trkpt-has-time, ISO-UTC, NaN-alt omit, escaping, empty guard, well-formed
├── StravaUploadModelTest.kt  # NEW  UploadResponse parse (all-nullable), sport_type map
└── (duplicate-regex + state-machine tests co-located with their unit)
phone/src/test/java/com/rokid/hud/phone/
└── SessionStoreUpdateTest.kt # NEW  updateUploadState round-trip: adds fields, preserves trackPoints, atomic
```

### Pattern 1: JVM-testable GPX writer via XmlPullParserFactory (the *writing* mirror of GpxParser)
**What:** `GpxWriter` is a pure `object` (STACK convention for stateless utilities). It obtains a serializer from `XmlPullParserFactory.newInstance().newSerializer()` — **not** `android.util.Xml.newSerializer()` — writes to a `StringWriter`, and returns the string. Because kxml2 is on the test classpath, the same factory resolves in JVM unit tests.
**When to use:** For all GPX generation. The validity guard and the duplicate-id regex live in the same file (all pure, all testable together).
**Example:** see Code Examples → "GPX 1.1 generation".

### Pattern 2: OkHttp multipart with in-memory GPX (never set Content-Type)
**What:** Build a `MultipartBody.Builder().setType(MultipartBody.FORM)`, add the string form fields (`data_type=gpx`, `name`, `sport_type`, `external_id`) via `addFormDataPart(name, value)`, and add the file part via `addFormDataPart("file", "<name>.gpx", gpx.toRequestBody("application/gpx+xml".toMediaType()))`. Do **not** add a `Content-Type` header to the `Request` — OkHttp emits `multipart/form-data; boundary=...` automatically. Manually setting it produces the `"data": "empty"` / "invalid encoding" failure.
**When to use:** The `POST /uploads` call.
**Example:** see Code Examples → "Upload POST".

### Pattern 3: Thread{} poll loop + runOnUiThread progress (no coroutines)
**What:** The whole upload (POST + poll) runs on one `Thread{}` started from the Activity. Inside, `Thread.sleep(2000)` between `GET /uploads/{id}` polls, bounded by a wall-clock deadline (`System.currentTimeMillis() + 120_000`). Each state transition posts to the UI via `runOnUiThread { ... }`. A `@Volatile` cancel flag (set in `onStop`/`onDestroy`) lets the loop exit early if the user leaves the screen (the upload keeps the session on disk regardless).
**When to use:** The upload/poll driver in `ActivitySummaryActivity`.
**Example:** see Code Examples → "Poll loop + state machine".

### Pattern 4: Atomic upload-state write-back reusing the Phase-1 primitive
**What:** `SessionStore.updateUploadState(id, activityId)` reads `{id}.json` → `fromJson` → `data.copy(stravaUploaded = true)` → `toJson` (extended to also write `strava_activity_id`) → `writeAtomic(finalFile(id), json)`. This reuses the exact temp+fsync+rename primitive that is already unit-tested. It runs on the store's serial executor (or synchronously on the caller's upload Thread{}) — never on the main thread.
**When to use:** On upload success (real or duplicate-recovered).
**Example:** see Code Examples → "SessionStore.updateUploadState".

### Pattern 5: osmdroid route Polyline from trackPoints (reuse MainActivity's exact idiom)
**What:** Map `data.trackPoints` → `List<GeoPoint>` (skip any non-finite lat/lng defensively), `overlays.removeIf { it is Polyline }`, build a `Polyline().apply { setPoints(geoPoints); outlinePaint.color = ... }`, add it, then fit via `BoundingBox.fromGeoPoints(...)` deferred in `MapView.post { }` (osmdroid returns a zero-size view until laid out — the Phase-4 preview already hit and documented this).
**When to use:** Summary route map.
**Example:** MainActivity.kt:533-543 (Strava preview) and :1144-1159 (nav route) are the reference — same pattern, trackPoints instead of waypoints.

### Anti-Patterns to Avoid
- **`android.util.Xml.newSerializer()` in `GpxWriter`:** throws `Stub!` on JVM tests → forces Robolectric. Use `XmlPullParserFactory.newSerializer()`. [VERIFIED: 04-01-SUMMARY]
- **Manually setting `Content-Type: multipart/form-data` on the upload request:** corrupts the boundary → `"data": "empty"`. Let OkHttp own it. [CITED: PITFALLS Integration Gotchas; developers.strava.com]
- **Anchoring the duplicate regex at start-of-string:** the error is `"<filename> duplicate of activity <id>"` — the filename comes *first*. Match `duplicate of activity (\d+)` anywhere in the string. [VERIFIED: developers.strava.com example "Test_Walk.gpx duplicate of activity 21234316"]
- **Using `id` (Long) instead of `id_str` for the poll path:** Strava upload/activity ids exceed 32-bit; use `id_str` in `GET /uploads/{id_str}`. [CITED: PITFALLS Pitfall 4 §2; matches Phase-4 `idStr` discipline]
- **Deleting or truncating local JSON on any upload outcome:** local is source of truth; only *add* fields on success. [CITED: locked project decision, PITFALLS]
- **Reading `avgPaceMsPerKm` from SessionData:** it is NOT a persisted field (only `avgSpeedMps` is). Derive pace from `distanceM`+`movingMs`. [VERIFIED: SessionData schema, ActivitySessionManager.buildSessionData]
- **`sport_type` = `"ride"`/`"run"` (lowercase):** Strava `sport_type` is PascalCase and case-sensitive (`Ride`, `Run`). Map the session's lowercase sport. `activity_type` is deprecated. [VERIFIED: developers.strava.com SportType enum]
- **Blocking network on the main thread:** every Strava/GPX call runs on `Thread{}` (NetworkOnMainThreadException otherwise) — the codebase-wide convention. [VERIFIED: StravaApiClient "Thread{} only"]
- **Setting a manual `Content-Type` on the file *part*:** unnecessary and, per community reports, best left to OkHttp; the `data_type=gpx` field is what Strava uses to detect format, not the part MIME. Providing `application/gpx+xml` on the part is fine; providing a conflicting top-level header is the failure. [VERIFIED: WebSearch community reports]

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| GPX XML generation | String concatenation / `StringBuilder` with manual `<` escaping | `XmlPullParserFactory.newSerializer()` (`text()` auto-escapes) | Unescaped `&`/`<`/`>` in activity name or attribute values produces malformed XML Strava rejects; a serializer guarantees well-formedness |
| Multipart body + boundary | Hand-built `multipart/form-data` string with a boundary token | `okhttp3.MultipartBody.Builder` | The #1 documented Strava upload failure (`"data":"empty"`) is a hand-set boundary; OkHttp is already a dependency |
| Atomic file write for the state write-back | `FileWriter` overwriting `{id}.json` in place | `SessionStore.writeAtomic` (existing, tested) | An in-place overwrite that fails mid-write corrupts the source-of-truth JSON; the temp+fsync+rename primitive already exists and is unit-tested |
| ISO-8601 UTC timestamp formatting | Hand-built `SimpleDateFormat` with a timezone literal | `Instant.ofEpochMilli(ts).toString()` | Already the exact call used for `startTime`/`endTime`; guaranteed `...Z` UTC via `DateTimeFormatter.ISO_INSTANT`; `SimpleDateFormat` is not thread-safe and easy to misconfigure to local time (the "GPX timestamp" gotcha) |
| Token refresh at upload | New refresh logic before the POST | `StravaAuthManager.ensureFreshToken()` (Phase 3) | Proactive 30-min-window refresh is already primary; the reactive 401 `StravaAuthenticator` is already wired on the client |
| Strava response parsing | `org.json` hand-parse of the upload response | Gson `UploadResponse` data class (all-nullable) | STACK rule + 03/04 precedent; a field rename silently returns null instead of a mis-typed crash |
| osmdroid bounding-box fit | Manual center/zoom math | `BoundingBox.fromGeoPoints` in `MapView.post{}` | The Phase-4 preview already solved the "zero-size view before layout" trap this way |
| Route line rendering | Custom canvas overlay | osmdroid `Polyline` | Two existing call sites (nav + preview) do exactly this |

**Key insight:** Phase 5 is almost entirely *composition of existing, tested primitives*. The only genuinely new logic is small and pure: the GPX serialization body, a one-line duplicate-id regex, an upload state machine, a GPX validity predicate, and pace/speed arithmetic. Everything else is wiring OkHttp/osmdroid/SessionStore calls that already exist elsewhere in the codebase. The risk is not "can we build it" but "did we honor every item on the Pitfall-4 checklist" — which is why the plan should make each checklist item a discrete, verifiable task.

## Runtime State Inventory

> Phase 5 is additive (new activities + new methods + write-back of two fields). It renames nothing and migrates no existing data. This inventory confirms there is no hidden runtime state to reconcile.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | Existing `{id}.json` session files under `filesDir/activities/`. Phase 5 *adds* two fields (`stravaUploaded=true`, `strava_activity_id`) on successful upload via `updateUploadState`. Old files already have `stravaUploaded=false` (default) and no activity-id key → they read back correctly (`optBoolean` default false, activity-id read via `optString(..).takeIf{}`). | Code edit only (extend `toJson`/`fromJson` for the new activity-id key). NO migration of existing files — they are forward-compatible by construction. |
| Live service config | None. Upload targets the user's Strava account via the Phase-3 OAuth token (already stored in EncryptedSharedPreferences `strava_auth`). No new external service config, no new API app registration (the Phase-3 app + `activity:write` scope already cover uploads). | None — verified: AUTH-01 scope set is `read,activity:read_all,activity:write`; `activity:write` covers `POST /uploads` [CITED: developers.strava.com "Requires 'activity:write'"] |
| OS-registered state | None. No new receivers, no AlarmManager, no Task Scheduler, no boot registration. Two new `<activity>` entries in AndroidManifest.xml (standard launch, not exported unless launched cross-app — they are internal). | Manifest: register `ActivitySummaryActivity` (+ `HistoryActivity` if separate). `android:exported="false"` (internal navigation only). |
| Secrets/env vars | None new. `STRAVA_CLIENT_SECRET`/`STRAVA_CLIENT_ID` BuildConfig fields already exist and are consumed by Phase-3 refresh. Upload uses the Bearer access token, not the secret directly. | None — verified: no new BuildConfig field, no new local.properties key. |
| Build artifacts | None. No new dependency → no new transitive artifacts, no new egg-info/AAR. `assembleDebug` classpath is unchanged. | None — verified: build.gradle.kts dependency block requires no edit for Phase 5. |

**The canonical question — "after every file is updated, what runtime systems still hold old state?":** Nothing. Phase 5 introduces state (it does not rename existing state): the only persisted change is two new keys appended to session JSON on upload success, and pre-existing session files remain valid without migration.

## Common Pitfalls

### Pitfall 1: Missing `<time>` on any trkpt → "Time information is missing" rejection
**What goes wrong:** Strava enqueues the upload, then fails processing with `status="There was an error processing your activity."` and `error` mentioning missing time. The user sees a spinner resolve to failure 30s later.
**Why it happens:** GPX allows `<trkpt>` without `<time>`; the recorded track always has `ts` (epoch ms), but a writer bug (e.g., omitting `<time>` when writing, or writing it only when some other field is present) drops it.
**How to avoid:** `GpxWriter` writes `<time>Instant.ofEpochMilli(p.ts).toString()</time>` for **every** point unconditionally (`ts` is always present per the TrackPoint contract — it is `location.time`, never NaN). The `isValidForUpload` guard re-parses the produced GPX and asserts a `<time>` under every `<trkpt>` before the POST.
**Warning signs:** Poll returns `error` with "time"; a `GpxWriterTest` case with a NaN altitude still emits `<time>`.

### Pitfall 2: Duplicate regex misses because of the filename prefix
**What goes wrong:** On a retry (same GPX bytes), Strava returns `error="ride-2026....gpx duplicate of activity 21234316"`. A regex like `^duplicate of activity (\d+)$` or `"duplicate of activity {id}"` treated as an exact-prefix match fails → the app reports a generic failure and the user retries again, or worse, thinks data is lost.
**Why it happens:** The exact format is `"<external_id-or-filename> duplicate of activity <id>"` — the numeric activity id is at the *end*, preceded by the filename [VERIFIED: developers.strava.com example].
**How to avoid:** `Regex("duplicate of activity (\\d+)").find(error)?.groupValues?.get(1)` — search anywhere, capture the trailing digits. On match, treat as success: `updateUploadState(id, matchedActivityId)`.
**Warning signs:** A `parseDuplicateActivityId` test with a leading filename returns the id; a non-duplicate error returns null.

### Pitfall 3: `id` (Long) truncation on the poll path
**What goes wrong:** Using the numeric `id` in `GET /uploads/{id}` truncates large upload ids → 404 or wrong upload polled.
**Why it happens:** Strava ids exceed 32-bit; Gson-into-Long is safe but string interpolation of a mis-modeled Int, or preferring `id` over `id_str`, reintroduces the risk. This is the same sharp edge Phase 4 encoded as `idStr` for route export.
**How to avoid:** Model both `id: Long?` and `id_str: String?` in `UploadResponse`; use `id_str` for the poll URL path. If `id_str` is null (defensive), fall back to `id?.toString()`.
**Warning signs:** Poll URL contains a negative or wrapped number; `UploadResponse` model test confirms `id_str` parses verbatim.

### Pitfall 4: Network drop between POST and final poll leaves ambiguous state
**What goes wrong:** POST succeeds (201, upload enqueued), then mobile data drops during polling. The app never learns the outcome; if it optimistically marked success, the user sees "uploaded" for an activity that may not exist.
**Why it happens:** Upload is async; the truth is only known when a poll returns `activity_id`.
**How to avoid:** Only set `stravaUploaded=true` when a poll returns `status=ready`+`activity_id` (or a duplicate-error activity id). A poll exception → keep polling until the 2-min deadline, then land in **Pending** (`stravaUploaded` stays false) with a "Retry later" affordance. The session on disk is untouched, so retry is always safe (duplicate handling makes a re-POST idempotent).
**Warning signs:** State machine has an explicit `Pending` terminal distinct from `Failed`; the write-back happens ONLY in the success branch.

### Pitfall 5: `avgSpeedMps` (moving) vs an elapsed-based average — presenting an inconsistent number
**What goes wrong:** The summary shows an "avg speed" that disagrees with what the live sport HUD showed during the ride, confusing the user.
**Why it happens:** There are two valid averages: distance/moving-time (what the HUD and `SessionData.avgSpeedMps` use) and distance/elapsed-time (lower, includes stops). Recomputing from elapsed silently changes the number.
**How to avoid:** Read `avgSpeedMps` directly from the persisted `SessionData` (it is `dist/(movingMs/1000)` — moving-based [VERIFIED: buildSessionData]). Derive avg pace the same way: `movingMs / (distanceM/1000)` with the ≥100 m floor the live path uses (`avgPaceMsPerKm` returns 0 below 100 m — mirror that so a 20 m test walk shows "–:––" not a garbage pace). If the summary also wants an elapsed-based figure, label both explicitly ("moving avg" vs "overall avg").
**Warning signs:** Summary avg speed ≠ SessionData.avgSpeedMps; a summary-math test asserts moving-based pace and the sub-100 m floor.

### Pitfall 6: Pace is computed but NOT persisted — reading it off SessionData yields nothing
**What goes wrong:** A planner assumes `SessionData` carries pace (it carries `avgSpeedMps` only) and the summary shows a blank/zero pace.
**Why it happens:** `MetricsSnapshot` has `avgPaceMsPerKm`, but `SessionData` (the persisted schema) deliberately does not — only `avgSpeedMps` is stored [VERIFIED: SessionData fields vs MetricsSnapshot fields].
**How to avoid:** Compute pace in the summary from persisted `distanceM` + `movingMs`. Keep the formula identical to `ActivitySessionManager.avgPaceMsPerKm(dist)` so the summary matches the HUD. Consider a tiny shared pure helper so both call one implementation.
**Warning signs:** Grep shows `SessionData(...avgPaceMsPerKm...)` (there is no such field); summary pace is always 0.

### Pitfall 7: `XmlSerializer` requires an XML declaration + explicit `endDocument()`/`flush()`
**What goes wrong:** Truncated or declaration-less GPX; some parsers reject a missing prolog.
**Why it happens:** `XmlSerializer` writes nothing until `startDocument`/`endDocument` and the underlying writer is flushed.
**How to avoid:** `serializer.setOutput(writer)`; `serializer.startDocument("UTF-8", true)`; …; `serializer.endDocument()`; return `writer.toString()`. Set `http://xmlpull.org/v1/doc/features.html#indent-output` for readability if desired (optional). Emit `xmlns="http://www.topografix.com/GPX/1/1"`, `version="1.1"`, `creator="RokidHudMaps"` on the root `<gpx>`.
**Warning signs:** A `GpxWriterTest` that parses the output back with `GpxParser` and gets the same point count is the strongest guard (round-trip).

## Code Examples

Verified patterns from official sources and the existing codebase.

### GPX 1.1 generation (XmlPullParserFactory serializer — JVM-testable)
```kotlin
// Source: mirror of GpxParser.kt (XmlPullParserFactory) + Strava GPX 1.1 structure
//         (developers.strava.com/docs/uploads: trk>trkseg>trkpt[lat,lon]>ele,time)
// PURE object — no android.* import; kxml2 test dep resolves newSerializer() off-device.
object GpxWriter {
    private const val NS = "http://www.topografix.com/GPX/1/1"

    /** points -> GPX 1.1 string. Every trkpt gets <time> (ISO-8601 UTC). Never throws. */
    fun write(points: List<TrackPoint>, sport: String, startTimeIso: String): String {
        val writer = java.io.StringWriter()
        val s = org.xmlpull.v1.XmlPullParserFactory.newInstance().newSerializer()
        s.setOutput(writer)
        s.startDocument("UTF-8", true)
        s.setPrefix("", NS)                      // default namespace, no prefix
        s.startTag(NS, "gpx")
        s.attribute(null, "version", "1.1")
        s.attribute(null, "creator", "RokidHudMaps")
        s.startTag(NS, "trk")
        s.startTag(NS, "type").text(sport).endTag(NS, "type")   // "ride"/"run" (informational)
        s.startTag(NS, "trkseg")
        for (p in points) {
            s.startTag(NS, "trkpt")
            s.attribute(null, "lat", p.lat.toString())
            s.attribute(null, "lon", p.lng.toString())
            if (p.alt.isFinite()) {              // NaN altitude -> omit <ele> (locked)
                s.startTag(NS, "ele").text(p.alt.toString()).endTag(NS, "ele")
            }
            // REQUIRED on EVERY point (Pitfall 4). ts is epoch ms (location.time), always present.
            s.startTag(NS, "time")
                .text(java.time.Instant.ofEpochMilli(p.ts).toString())   // -> 2026-07-03T15:45:00Z
                .endTag(NS, "time")
            s.endTag(NS, "trkpt")
        }
        s.endTag(NS, "trkseg")
        s.endTag(NS, "trk")
        s.endTag(NS, "gpx")
        s.endDocument()
        return writer.toString()
    }

    /** Fail-fast guard BEFORE any network cost. Re-parses output; every trkpt must have time. */
    fun isValidForUpload(gpx: String): Boolean { /* parse via XmlPullParser; assert >=1 trkpt & each has <time> */ }

    private val DUP = Regex("duplicate of activity (\\d+)")     // filename comes FIRST — search anywhere
    fun parseDuplicateActivityId(error: String?): Long? =
        error?.let { DUP.find(it)?.groupValues?.get(1)?.toLongOrNull() }
}
```
Note: `Instant.ofEpochMilli(ts).toString()` uses `DateTimeFormatter.ISO_INSTANT` → always UTC with trailing `Z`; whole-second instants render `2026-07-03T15:45:00Z`, sub-second render `...15:45:00.123Z`. Both are valid ISO-8601 and accepted by Strava. [ASSUMED-JDK-CONTRACT: java.time.Instant.toString() Javadoc — could not run a local JVM to print it this session; the ISO_INSTANT behavior is stable and is already relied on for startTime/endTime in shipped Phase-1 code.]

### Upload POST (OkHttp MultipartBody — OkHttp owns the boundary)
```kotlin
// Source: square.github.io/okhttp/recipes (MultipartBody.FORM + addFormDataPart);
//         developers.strava.com/docs/uploads (fields: file, data_type, name, sport_type, external_id)
// OkHttp 4.x: use the Kotlin extension .toRequestBody(mediaType); RequestBody.create(mt, body) is DEPRECATED.
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

fun buildUploadRequest(gpx: String, name: String, externalId: String, sportType: String, bearer: String): Request {
    val filePart = gpx.toRequestBody("application/gpx+xml".toMediaType())
    val body = MultipartBody.Builder()
        .setType(MultipartBody.FORM)                       // -> multipart/form-data; boundary set by OkHttp
        .addFormDataPart("data_type", "gpx")               // REQUIRED; case-insensitive
        .addFormDataPart("name", name)
        .addFormDataPart("sport_type", sportType)          // PascalCase: "Ride"/"Run" (activity_type is deprecated)
        .addFormDataPart("external_id", externalId)        // = session id; secondary dedup guard
        .addFormDataPart("file", "$externalId.gpx", filePart)
        .build()
    return Request.Builder()
        .url("https://www.strava.com/api/v3/uploads")
        .header("Authorization", "Bearer $bearer")         // NO Content-Type header — OkHttp sets it
        .post(body)
        .build()
}
// sport_type mapping (session sport is lowercase "ride"/"run"):
fun sportType(sport: String): String = when (sport) { "run" -> "Run"; else -> "Ride" }
```
Response (201) parsed with Gson into an all-nullable model:
```jsonc
// GET/POST /uploads response — VERIFIED shape (developers.strava.com/docs/uploads)
{ "id": 123456, "id_str": "123456", "external_id": "…gpx",
  "error": null, "status": "Your activity is still being processed.", "activity_id": null }
// terminal ready:   status "Your activity is ready."  + activity_id: 987654321
// terminal error:   status "There was an error processing your activity." + error "<file> duplicate of activity 21234316"
```

### Poll loop + state machine (Thread{} + runOnUiThread, cancellable)
```kotlin
// Source: PITFALLS Pitfall 4 (poll every 1-2s up to 2 min) + codebase Thread{}/runOnUiThread convention
sealed class UploadState {
    object Uploading : UploadState()                       // POST in flight
    object Processing : UploadState()                      // polling
    data class Done(val activityId: Long) : UploadState()  // ready OR duplicate-recovered
    data class Failed(val message: String) : UploadState()
    object RateLimited : UploadState()                     // 429
    object Pending : UploadState()                         // 2-min timeout, still processing
}

// in ActivitySummaryActivity — @Volatile private var cancelled = false ; set true in onDestroy
private fun startUpload(data: SessionData) = Thread {
    fun ui(st: UploadState) = runOnUiThread { renderUploadState(st) }
    ui(UploadState.Uploading)
    val gpx = GpxWriter.write(data.trackPoints, data.sport, data.startTime)
    if (!GpxWriter.isValidForUpload(gpx)) { ui(UploadState.Failed("Recording has no valid track to upload")); return@Thread }
    when (val posted = uploader.startUpload(gpx, defaultName(data), data.id, sportType(data.sport))) {
        is StartResult.RateLimited -> { ui(UploadState.RateLimited); return@Thread }
        is StartResult.Failed      -> { ui(UploadState.Failed(posted.message)); return@Thread }
        is StartResult.Started     -> {
            ui(UploadState.Processing)
            val deadline = System.currentTimeMillis() + 120_000
            while (!cancelled && System.currentTimeMillis() < deadline) {
                Thread.sleep(2_000)
                when (val poll = uploader.poll(posted.idStr)) {
                    is PollResult.Ready     -> { SessionStore(dir).updateUploadState(data.id, poll.activityId)
                                                 ui(UploadState.Done(poll.activityId)); return@Thread }
                    is PollResult.Duplicate -> { SessionStore(dir).updateUploadState(data.id, poll.activityId)
                                                 ui(UploadState.Done(poll.activityId)); return@Thread }
                    is PollResult.Error     -> { ui(UploadState.Failed(poll.message)); return@Thread }
                    is PollResult.Processing -> { /* keep polling */ }
                }
            }
            if (!cancelled) ui(UploadState.Pending)        // stravaUploaded stays false -> retry later
        }
    }
}.start()
```

### SessionStore.updateUploadState (atomic write-back, adds fields only)
```kotlin
// Source: reuse SessionStore.writeAtomic + fromJson/toJson (SessionStore.kt, already tested)
// Extend toJson/fromJson to also (de)serialize an optional "strava_activity_id" Long key.
fun updateUploadState(id: String, activityId: Long) {
    executor.execute {                                     // serial executor; never main thread
        try {
            val file = finalFile(id)                       // {id}.json
            val existing = fromJson(file.readText(Charsets.UTF_8)) ?: return@execute
            val updated = existing.copy(stravaUploaded = true)
            // toJson(updated) additionally writes put("strava_activity_id", activityId)
            writeAtomic(file, toJsonWithActivityId(updated, activityId))   // temp+fsync+rename
        } catch (e: Exception) { Log.w(TAG, "updateUploadState failed for $id: ${e.message}") }
    }
}
```
Old files without the key read back fine: `optLong("strava_activity_id", -1L)` / absent = "not uploaded to a known id". Never deletes, never truncates — UPL-03 satisfied structurally.

### Format helpers for the summary (imperial-aware — currently private in MainActivity)
```kotlin
// Source: MainActivity.kt:1521-1560 — formatDist / formatSpeed / formatPace / formatElapsed are PRIVATE.
// The summary needs the same rendering. Options (planner's call):
//  (a) extract to a shared `object SportFormat` (cleanest, one source of truth), or
//  (b) duplicate the four small functions into ActivitySummaryActivity.
// Note imperial flag lives in getPreferences(MODE_PRIVATE)[PREF_IMPERIAL] (activity-scoped) — if
// extracting, thread the Boolean in rather than reading prefs inside the helper.
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `activity_type` upload field | `sport_type` (PascalCase, case-sensitive) | Strava deprecated `activity_type` (still accepted) | CONTEXT says "activity_type from sport" — prefer `sport_type` with `Ride`/`Run`; `activity_type` also works but is deprecated [VERIFIED: developers.strava.com] |
| `RequestBody.create(mediaType, body)` | `body.toRequestBody(mediaType)` Kotlin extension | OkHttp 4.0 (2019) | The MediaType-first form is deprecated in 4.12.0; use the extension (content-first) [VERIFIED: OkHttp upgrade guide] |
| `ResponseBody.create(mt, s)` similarly deprecated | `s.toResponseBody(mt)` | OkHttp 4.0 | Not needed here (we only build request bodies) |

**Deprecated/outdated:**
- `android.util.Xml.newSerializer()` for anything unit-tested: throws `Stub!` on JVM — superseded by `XmlPullParserFactory.newSerializer()`.
- Polling `GET /uploads` faster than ~1/sec: Strava recommends ≤1/sec (mean processing <2s); the CONTEXT 2s interval is safely conservative.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `Instant.ofEpochMilli(ts).toString()` prints `2026-07-03T15:45:00Z` (or `...00.123Z`), a Strava-accepted ISO-8601 UTC `<time>` | Code Examples, Standard Stack | LOW — this is the documented `ISO_INSTANT` contract and is already used for `startTime`/`endTime` in shipped Phase-1 code; a local JVM print could not be run this session (no JDK on PATH). If somehow wrong, GPX `<time>` would be malformed → validity guard + a `GpxWriterTest` asserting the `Z`/`T` shape catch it before upload. |
| A2 | Strava is content-agnostic about the multipart *file part* media type (`application/gpx+xml` is safe); it detects format from `data_type=gpx` | Pattern 2, Anti-Patterns | LOW — official docs say `data_type` selects the parser; community reports confirm the part MIME is not the failure (the top-level Content-Type is). If wrong, switch the part to `application/octet-stream`; behavior is a small, testable knob. |
| A3 | The Phase-3 granted scope set (`read,activity:read_all,activity:write`) already authorizes `POST /uploads` with no re-consent | Runtime State Inventory, UPL-02 | LOW — `activity:write` is exactly the documented upload scope [VERIFIED: developers.strava.com]; Phase-3 AUTH-01 requested it. Only risk: if the user's actual Strava app/token was granted a narrower scope at consent time → upload 401/403. Device verification (real upload) is the definitive check. |
| A4 | `sport_type` values `Ride`/`Run` are accepted for GPX uploads and map cleanly from the session's lowercase `ride`/`run` | Code Examples, Anti-Patterns | LOW — `Ride`/`Run` are in the documented SportType enum [VERIFIED]. If a value were rejected, Strava falls back to file/default detection; a wrong `sport_type` is a cosmetic activity-type label, not a data-loss failure. |

**Note:** No compliance/retention/security-standard assumptions are made — the security surface (token handling, HTTPS, internal storage) is inherited unchanged from Phase 3/1 and is not re-litigated here.

## Open Questions

1. **HistoryActivity vs MainActivity list section (UPL-04 surface)**
   - What we know: CONTEXT marks this as Claude's discretion; `SessionStore.listFinalSessions()` is ready either way; no-Fragment convention holds.
   - What's unclear: which is cleaner given MainActivity is already ~1560 lines.
   - Recommendation: a small dedicated `HistoryActivity` (own layout + `item_activity.xml` row) to avoid growing MainActivity; entry via a button/menu on MainActivity. Defer final call to the planner/UI-spec.

2. **Where exactly to launch `ActivitySummaryActivity` on stop**
   - What we know: `stopRecording()` finalizes async on a background handler (`finalizeAsync`); the session id is available immediately from `snapshotSession()?.id` / `PREF_REC_SESSION_ID`. `confirmStopRecording()` in MainActivity is the tap site.
   - What's unclear: whether to launch summary immediately on "Finish" tap (before finalize completes) or after. The finalize is fast but async.
   - Recommendation: launch `ActivitySummaryActivity(sessionId)` right after `service?.stopRecording()`; the summary reads on a `Thread{}` and can briefly retry/read the final file (finalize writes `{id}.json` atomically). Alternatively pass the in-memory `SessionData` via intent extras is NOT recommended (trackPoints can be thousands of points → TransactionTooLargeException) — pass only the `id` and read from disk.

3. **Reading a single session by id — `SessionStore` currently exposes only `listFinalSessions()` + internal `fromJson`**
   - What we know: `fromJson`/`finalFile` are internal; no public `read(id)` exists.
   - What's unclear: add a public `readSession(id): SessionData?` or have the summary use `listFinalSessions().first { it.name == "$id.json" }`.
   - Recommendation: add a tiny public `readSession(id: String): SessionData?` to `SessionStore` (reads `finalFile(id)`, returns `fromJson` or null) — cleaner and unit-testable; the history list can still map `listFinalSessions()` file-by-file.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| OkHttp | Upload multipart POST + poll | ✓ (declared) | 4.12.0 | — |
| Gson | Upload response parsing | ✓ (declared) | 2.10.1 | — |
| osmdroid | Summary route map | ✓ (declared) | 6.1.18 | — |
| kxml2 (test) | `GpxWriter` JVM tests (`newSerializer`) | ✓ (testImplementation) | 2.3.0 | — |
| JDK `java.time` | ISO-8601 `<time>` | ✓ (JVM 17, minSdk 28 has java.time) | 17 | — |
| Strava account + `activity:write` token | Real upload (device verify) | User-provided (Phase-3 OAuth) | — | Mock/deferred — automatable steps run without it; only the real-feed confirmation needs it |
| Live JDK on this research host | (research-time only) Print `Instant.toString()` | ✗ | — | Relied on documented `ISO_INSTANT` contract + shipped Phase-1 usage (A1) |

**Missing dependencies with no fallback:** None for building/testing. The only external requirement is the user's real Strava account for the end-to-end device confirmation (the milestone finale) — every other step is automatable/mockable.

**Missing dependencies with fallback:** Live JDK at research time (used documented contract instead — see A1); real Strava upload (unit tests + a mock/stubbed uploader cover all logic; only feed-appearance needs the real account).

## Validation Architecture

> `.planning/config.json` sets `commit_docs: true`; no `workflow.nyquist_validation:false` was found → validation section INCLUDED. The codebase has a real JUnit4 + org.json/kxml2 pure-JVM test culture (125 tests green through Phase 4) — Phase 5 extends it, no new framework needed.

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 4.13.2 (`testImplementation`) |
| Config file | `phone/build.gradle.kts` → `testOptions { unitTests.isReturnDefaultValues = true }` (makes `android.util.Log` no-op in tests; does NOT stub factory-instantiated Xml — hence kxml2) |
| Quick run command | `./gradlew :phone:testDebugUnitTest --tests "*GpxWriterTest*"` (single seam) |
| Full suite command | `./gradlew :shared:testDebugUnitTest :phone:testDebugUnitTest` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| UPL-02 | GPX has `<time>` on EVERY trkpt in ISO-8601 UTC (`Z`) | unit | `./gradlew :phone:testDebugUnitTest --tests "*GpxWriterTest*"` | ❌ Wave 0 |
| UPL-02 | `<ele>` present iff altitude finite; NaN alt omits `<ele>` but keeps `<time>` | unit | same | ❌ Wave 0 |
| UPL-02 | GPX round-trips through `GpxParser` (well-formed, point count preserved) | unit | same | ❌ Wave 0 |
| UPL-02 | Special chars in name/type are XML-escaped (no malformed output) | unit | same | ❌ Wave 0 |
| UPL-02 | `isValidForUpload` rejects empty / no-trkpt / missing-`<time>` GPX | unit | same | ❌ Wave 0 |
| UPL-02 | `parseDuplicateActivityId("<file> duplicate of activity 21234316")` → 21234316; non-dup → null | unit | `./gradlew :phone:testDebugUnitTest --tests "*GpxWriterTest*"` | ❌ Wave 0 |
| UPL-02 | `UploadResponse` Gson parse: all-nullable; `id_str` verbatim; ready vs processing vs error status | unit | `./gradlew :phone:testDebugUnitTest --tests "*StravaUploadModelTest*"` | ❌ Wave 0 |
| UPL-02 | `sportType("run")=="Run"`, else `"Ride"` | unit | same | ❌ Wave 0 |
| UPL-02 | Upload state machine: Uploading→Processing→Done/Failed/RateLimited/Pending transitions (pure driver, injected fake uploader) | unit | `./gradlew :phone:testDebugUnitTest --tests "*UploadStateMachineTest*"` | ❌ Wave 0 |
| UPL-01 | Summary math: avg speed = SessionData.avgSpeedMps (moving); pace = movingMs/(distanceM/1000) with sub-100m floor → "–:––" | unit | `./gradlew :phone:testDebugUnitTest --tests "*SummaryMathTest*"` | ❌ Wave 0 |
| UPL-03 | `SessionStore.updateUploadState` round-trip: sets `stravaUploaded=true`+activity id, PRESERVES trackPoints, atomic (temp+rename), old file without key reads back OK | unit | `./gradlew :phone:testDebugUnitTest --tests "*SessionStore*"` | ❌ Wave 0 (extend existing SessionStoreTest) |
| UPL-04 | `readSession(id)` returns the finalized SessionData; missing id → null | unit | `./gradlew :phone:testDebugUnitTest --tests "*SessionStore*"` | ❌ Wave 0 |
| UPL-01/02/03/04 | End-to-end: record→summary→upload→feed appears; retry after simulated failure | manual (device) | see Device verification | N/A — human confirms Strava feed |

### Sampling Rate
- **Per task commit:** the seam's own quick command (e.g., `--tests "*GpxWriterTest*"`) — sub-30s.
- **Per wave merge:** `./gradlew :phone:testDebugUnitTest` (full phone suite; ~125 existing + new).
- **Phase gate:** `./gradlew :shared:testDebugUnitTest :phone:testDebugUnitTest` green + device verification, before `/gsd-verify-work`.

### Wave 0 Gaps
- [ ] `phone/src/test/java/com/rokid/hud/phone/strava/GpxWriterTest.kt` — covers UPL-02 (time-on-every-point, ISO-UTC, NaN-ele, escaping, round-trip, validity guard, duplicate regex)
- [ ] `phone/src/test/java/com/rokid/hud/phone/strava/StravaUploadModelTest.kt` — covers UPL-02 (UploadResponse parse, sport_type map)
- [ ] `phone/src/test/java/com/rokid/hud/phone/strava/UploadStateMachineTest.kt` — covers UPL-02/03 (state transitions with a fake uploader; verifies write-back only on success)
- [ ] `phone/src/test/java/com/rokid/hud/phone/SummaryMathTest.kt` — covers UPL-01 (moving-based avg speed/pace, sub-100m floor) — or co-locate in a shared `SportFormat`/`SummaryMath` object test
- [ ] Extend `phone/src/test/java/com/rokid/hud/phone/SessionStoreTest.kt` — `updateUploadState` + `readSession` (UPL-03/04)
- [ ] Framework install: **none** — JUnit4 + kxml2 already on the test classpath.

*Note: the injected-fake seam pattern (Phase-3 `FakeSharedPreferences`, Phase-1 `File`-constructor store) applies to the uploader — inject a fake `StartResult`/`PollResult` producer so the state-machine and write-back are tested with zero network.*

## Security Domain

> `security_enforcement` not disabled in config → included. Phase 5 adds no new secret material and inherits the Phase-3 token security wholesale; this section confirms the small new surface.

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes (reuse) | Bearer token from `StravaAuthManager.ensureFreshToken`; EncryptedSharedPreferences at rest (Phase 3, unchanged) |
| V3 Session Management | no | No app-side sessions beyond the OAuth token (Phase 3) |
| V4 Access Control | yes (minor) | New activities `android:exported="false"` (internal navigation only); no exported surface added |
| V5 Input Validation | yes | GPX is *generated* locally (not untrusted); the *upload response* is parsed with all-nullable Gson + explicit checks (no trust of `error`/`status` shape). Duplicate regex operates on a Strava-supplied string — bounded `\d+` capture, no injection risk. |
| V6 Cryptography | yes (reuse) | HTTPS-only to `strava.com` (OkHttp default); no new crypto. Never log token or GPX-location material. |
| V9 Communications | yes | All Strava calls over HTTPS via the existing OkHttp client; upload body carries precise location+time → HTTPS is mandatory (already enforced) |
| V12 Files/Resources | yes | GPX built in memory and POSTed; session JSON stays in app-internal `filesDir/activities` (SessionStore has no Context → cannot reach external storage — Phase-1 T-03-01). No GPX file written to shared storage. |

### Known Threat Patterns for {Android upload client + Strava}
| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Location/time history exfiltration in transit (GPX = precise tracking data) | Information Disclosure | HTTPS-only (OkHttp); no plaintext fallback; upload body never logged [CITED: PITFALLS Security] |
| Token leakage via upload logging | Information Disclosure | `logRateLimits` logs only header usage; the upload interceptor stays DEBUG-only `Level.BASIC` (Phase-3 discipline); never log Authorization or body |
| GPX written to world-readable external storage | Information Disclosure | GPX is in-memory only; never persisted to `getExternalFilesDir`/MediaStore [CITED: PITFALLS Security] |
| Malformed/oversized upload response DoS | Denial of Service | Gson parse in try/catch (never rethrow); all-nullable model tolerates missing fields; poll loop is deadline-bounded (2 min) |
| Duplicate-error string injection into UI/state | Tampering | Regex captures only `\d+`; the activity id is a numeric Long, not echoed as HTML; status strings rendered as plain text (no WebView) |
| Re-upload creating duplicate activities | (data integrity) | `external_id` = session id (secondary dedup) + primary duplicate-error recovery → idempotent retry, no duplicate activities |

## Device Verification (the milestone finale)

Batches with the Phase-3 auth + Phase-4 route device session (same two devices, same real Strava account).

**Automatable (no human judgment):**
1. `./gradlew :shared:testDebugUnitTest :phone:testDebugUnitTest` — full suite green (all Wave-0 seams + existing 125).
2. `./gradlew :phone:assembleDebug` — APK builds with no new deps.
3. Grep gates (mirror prior phases): `GpxWriter.kt` contains NO `android.util.Xml`; the upload request builder sets NO `Content-Type` header; `sport_type` (not only deprecated `activity_type`); `id_str` used in the poll path; `updateUploadState` calls `writeAtomic` and contains NO `delete(`/`purge`.
4. Adb-drivable: launch `ActivitySummaryActivity` with a known session id (a mock-recorded `{id}.json` placed in `filesDir/activities`) → verify metrics render and the map draws a Polyline (screenshot diff or log assertion of point count).
5. Simulated-failure path: point the uploader at an unreachable host (or feed a fake 429) → verify the UI lands in `Pending`/`RateLimited` and the on-disk `{id}.json` still shows `stravaUploaded:false` (grep the file after) — proves UPL-03 without touching real Strava.

**Human confirmation moment (the one irreducible manual step):**
6. On the real phone, record a short activity (a few minutes of real GPS, or replay a mock provider track) → tap Finish → summary appears → tap "Upload to Strava" → watch the progress states → **the human opens the Strava app/website and confirms the activity appears in their feed** with plausible distance/time and a route line. Then tap "Retry" once and confirm it resolves to the existing activity via duplicate recovery (NOT a second activity in the feed) — proving the duplicate path end-to-end on real infrastructure.

This step is the definitive UPL-02 proof and the milestone's capstone; everything leading to it is automated.

## Sources

### Primary (HIGH confidence)
- `developers.strava.com/docs/uploads/` — POST /uploads fields, response object (`id`/`id_str`/`error`/`status`/`activity_id`), GET /uploads/{id} poll flow, duplicate error format ("<file> duplicate of activity <id>"), `data_type` values, `activity:write` scope, `sport_type` (deprecated `activity_type`) — the definitive upload contract
- `square.github.io/okhttp/recipes/` + `square.github.io/okhttp/changelogs/upgrading_to_okhttp_4/` — `MultipartBody.Builder`/`addFormDataPart`, `.toRequestBody(mediaType)` (RequestBody.create deprecated in 4.x), OkHttp sets Content-Type/boundary automatically
- Codebase (read directly 2026-07-03): `SessionStore.kt`, `SessionModels.kt`, `StravaApiClient.kt`, `StravaModels.kt`, `GpxParser.kt`, `ActivitySessionManager.kt`, `MainActivity.kt` (format helpers, osmdroid), `HudStreamingService.kt` (stop/finalize), `phone/build.gradle.kts`
- Prior summaries: `01-03-SUMMARY.md` (SessionStore API + `listFinalSessions` as the Phase-5 seam), `03-02-SUMMARY.md` (StravaApiClient + auth), `04-01-SUMMARY.md` (kxml2 JVM-testable XML seam — the writing mirror)
- `.planning/research/PITFALLS.md` Pitfall 4 + Integration Gotchas + Security + Recovery (the upload checklist)

### Secondary (MEDIUM confidence)
- Strava community/dev threads (via WebSearch) confirming: no manual Content-Type on the part; `sport_type` PascalCase enum values; duplicate-error handling in the wild
- JDK `java.time.Instant` / `DateTimeFormatter.ISO_INSTANT` contract for the `<time>` format (A1 — documented but not locally executed this session)

### Tertiary (LOW confidence)
- None relied upon for load-bearing claims.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — nothing added; all versions read from build.gradle.kts; every seam read directly.
- Strava Upload API shape: HIGH — official docs confirmed the exact fields, response, poll flow, and duplicate string.
- GPX generation / JVM-testability: HIGH — mirrors the shipped, tested `GpxParser` factory approach; kxml2 test dep already present.
- OkHttp multipart idiom: HIGH — official recipes + upgrade guide.
- `Instant.toString()` `<time>` format: MEDIUM-HIGH — documented ISO_INSTANT contract and already used for startTime/endTime in shipped code, but not locally printed this session (A1; guarded by a GpxWriterTest assertion).
- Summary math semantics: HIGH — read the exact `avgSpeedMps`/`avgPaceMsPerKm` formulas in ActivitySessionManager; confirmed pace is not a persisted field.

**Research date:** 2026-07-03
**Valid until:** 2026-08-02 (Strava Upload API is stable/versioned; OkHttp 4.12 and osmdroid 6.1.18 are pinned. Re-verify the duplicate error string and `sport_type` enum only if Strava publishes a v4 API.)
