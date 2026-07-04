# Phase 5: Activity Summary + Strava Upload - Context

**Gathered:** 2026-07-03
**Status:** Ready for planning
**Mode:** Smart discuss (autonomous — recommendations auto-accepted per user authorization)

<domain>
## Phase Boundary

User views activity summaries and uploads completed activities to Strava. Delivers: an activity-summary screen after recording stops (total time, moving time, distance, avg speed/pace, route map), GPX generation from the recorded TrackPoints, one-tap Strava upload (POST /uploads multipart, poll GET /uploads/{id} until activity_id), and a past-activities list (local history, viewable anytime, upload-retry). Requirements: UPL-01..04. Depends on Phase 1 (SessionStore/SessionModels — done) + Phase 3 (StravaApiClient auth — done). The milestone's capstone.

</domain>

<decisions>
## Implementation Decisions

### Activity summary screen
- A dedicated ActivitySummaryActivity (new) launched when recording stops (from MainActivity's stop→finish flow) AND openable from the history list; shows: total time (elapsedMs), moving time (movingMs — the REC-02 metric finally surfaced per UPL-01), distance, avg speed AND avg pace (imperial-aware), sport, start time, and the route drawn on an osmdroid map from trackPoints
- "Upload to Strava" button (enabled only when Strava-connected; hint "Connect Strava first" otherwise), + "Done"/back
- Summary reads the finalized SessionData JSON from SessionStore (the file is already the source of truth — REC-06)

### GPX generation from track points (the upload payload — PITFALLS-critical)
- Generate GPX 1.1 via android.util.Xml / XmlSerializer (STACK.md — no GPX lib): <trk><trkseg><trkpt lat lon><ele/><time/></trkpt>...
- EVERY trkpt MUST have a <time> in ISO-8601 UTC (PITFALLS Pitfall 4 — missing time → "Time information is missing" rejection); derive from TrackPoint.ts (epoch ms → Instant → ISO UTC)
- Include <ele> when altitude is present (NaN → omit); route the recorded track, NOT the Strava route GPX
- GPX validity guard before upload: non-empty, well-formed, every trkpt has time — fail fast with a clear message (PITFALLS "looks done but isn't")

### Upload flow (async + robust — the milestone's highest-risk surface, PITFALLS Pitfall 4)
- POST /uploads (multipart: file=GPX bytes, data_type=gpx, name, activity_type from sport, external_id=session id) via a NEW StravaUploader on the Phase-3 authenticated client; let OkHttp set the multipart Content-Type/boundary (NEVER set it manually — PITFALLS gotcha)
- Use id_str for the upload id (64-bit safe); poll GET /uploads/{id_str} every 2s up to 2 min; success = activity_id set; then mark session stravaUploaded=true + store the activity_id
- Duplicate handling: on error string "duplicate of activity {id}", regex-extract the activity_id and treat as success (PITFALLS recovery — no data loss, no re-upload)
- Proactive token refresh already handled by the Phase-3 client (ensureFreshToken); a 6h+ ride's expired token refreshes transparently at upload time
- Progress UI states: "Uploading…" → "Strava is processing…" → "Uploaded ✓ (View on Strava)" / "Upload failed — Retry" / after 2-min timeout "Pending — Retry later" (PITFALLS UX)
- NEVER delete local session data on upload (success or fail) — local JSON is the source of truth, Strava is a copy (locked project decision)

### Persistence & history
- SessionData already persists locally (Phase 1 REC-06) — Phase 5 ADDS: the stravaUploaded flag + strava_activity_id are written back to the session JSON on successful upload (SessionStore gains an update method)
- Past-activities list: a section/list on MainActivity (or a HistoryActivity) reading SessionStore.listFinalSessions(); each row: date, sport, distance, duration, an "uploaded ✓" / "not uploaded" badge; tap → ActivitySummaryActivity
- Failed/pending uploads keep stravaUploaded=false → retry from the summary; no auto-retry-on-launch in v1 (v1.x per FEATURES)

### Rate limits
- Upload is a WRITE (1,000 writes/15min — single user never near it); log X-RateLimit-Usage on the POST + polls; 429 → "Strava busy — retry shortly"

### Claude's Discretion
- ActivitySummaryActivity vs a fragment/section; HistoryActivity vs a MainActivity list — pick the cleaner given no-Fragment convention
- GpxWriter / StravaUploader class split within com.rokid.hud.phone.strava
- Exact summary layout + map styling (existing dark-theme conventions)
- Poll backoff details within the 2s/2min envelope

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- SessionStore.listFinalSessions() + SessionData (Phase 1) — id/sport/startTime/endTime/elapsedMs/movingMs/distanceM/avgSpeedMps/stravaUploaded/trackPoints[lat,lng,alt,ts,speed,acc,brg]; SessionStore is the source of truth, add an updateUploadState(id, activityId) method
- StravaApiClient (Phase 3) — authenticated OkHttp + ensureFreshToken + logRateLimits + never-rethrow; add uploadActivity()/pollUpload() (multipart via OkHttp MultipartBody)
- MainActivity stop→"Finish recording?"→finish flow (Phase 1) — on finish, launch ActivitySummaryActivity with the session id
- osmdroid MapView + route Polyline drawing (used in nav + Phase-4 preview) — reuse for the summary route map
- SportFormat/formatDist/formatPace conventions (imperial-aware) for the summary numbers
- XmlSerializer (android.util.Xml) for GPX generation — mirror the Phase-4 GpxParser's kxml2 test approach for JVM-testable GPX WRITING

### Established Patterns
- Thread{} for network (upload/poll) + runOnUiThread for progress; Gson for Strava JSON responses; org.json for session JSON
- try/catch+Log, never rethrow; toasts + in-screen status for user errors; @Volatile where cross-thread
- NEVER log token material; multipart boundary set by OkHttp

### Integration Points
- MainActivity finish → ActivitySummaryActivity(sessionId) → SessionStore.read → summary + map
- ActivitySummaryActivity "Upload" → StravaUploader (GpxWriter → POST → poll) → SessionStore.updateUploadState → UI state
- History list → SessionStore.listFinalSessions → row → ActivitySummaryActivity
- Manifest: register ActivitySummaryActivity (+ HistoryActivity if separate)
- Device verify: reuse both devices; record a short activity (mock or real) → summary → upload to the user's REAL Strava (the human confirms it appears in their feed) — batches with the Phase-3 auth + Phase-4 route session
- JVM-testable seams: GpxWriter (points → GPX string, every trkpt has ISO-UTC time — assert), duplicate-activity-id regex extraction, upload-status state machine (uploading/processing/done/failed/pending), GPX validity guard, avg pace/speed summary math

</code_context>

<specifics>
## Specific Ideas

- PITFALLS Pitfall 4 is the checklist: async poll (2s/2min), duplicate regex, timestamps required (ISO-8601 UTC every point), don't-manually-set-Content-Type, id_str, token refresh at upload, never-delete-local. The plan must address each.
- moving time finally has a consumer here (UPL-01 summary) — the STATE decision routed it to exactly this screen
- external_id = session id enables Strava-side dedup as a secondary guard (primary is the duplicate-error-string parse)
- The whole flow is device-verifiable end to end with a real (or mock-recorded) activity + the user's real Strava account — this is the milestone finale

</specifics>

<deferred>
## Deferred Ideas

- Auto-retry pending uploads on next launch — v1.x (FEATURES)
- Auto-sync (upload without confirm) — v2 (SOCL-02)
- Segment results / kudos / social — out of scope
- Editing activity title/description before upload — v1.x (default name from sport + date)
- Elevation gain/loss computed metric — v2 (RECV-03)

</deferred>

---

*Phase: 05-activity-summary-strava-upload*
*Context gathered: 2026-07-03 via autonomous smart discuss*
