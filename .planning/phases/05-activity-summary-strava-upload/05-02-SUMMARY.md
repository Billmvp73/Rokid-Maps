---
phase: 05-activity-summary-strava-upload
plan: 02
subsystem: strava-uploader-network
tags: [strava, upload, okhttp, multipart, gson, poll, id-str, no-coroutines, thread-blocking]

# Dependency graph
requires:
  - phase: 03-strava-authentication
    provides: "StravaAuthManager.ensureFreshToken (proactive refresh) + StravaAuthenticator (reactive single-401 retry) + the StravaApiClient discipline mirrored here (DEBUG-only BASIC interceptor, logRateLimits both header pairs, 429-distinct, never rethrow)"
  - phase: 05-activity-summary-strava-upload
    plan: 01
    provides: "GpxWriter.parseDuplicateActivityId (unanchored duplicate-id regex) consumed by poll(); the pure driveUpload state machine + StartOutcome/PollOutcome seams these network results map onto"
provides:
  - "StravaUploader(auth): startUpload(gpx,name,externalId,sportType): StartResult (POST /uploads MultipartBody.FORM — OkHttp owns the boundary) + poll(idStr): PollResult (GET /uploads/{id_str}); BLOCKING/Thread{}-only, stateless per call"
  - "StartResult sealed type: Started(idStr) | RateLimited | Failed(message) — Wave 3 maps onto Plan-01 StartOutcome"
  - "PollResult sealed type: Ready(activityId) | Duplicate(activityId) | Processing | Error(message) — Wave 3 maps onto Plan-01 PollOutcome"
  - "UploadResponse Gson model (all-nullable: id/id_str/external_id/error/status/activity_id; id_str verbatim for the poll path)"
affects: [activity-summary-ui, strava-upload]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "OkHttp 4.x multipart upload via MultipartBody.Builder().setType(FORM) — OkHttp emits multipart/form-data; boundary=...; the request carries ONLY the Authorization header (a hand-set Content-Type is the documented data:empty failure)"
    - "OkHttp 4.x .toRequestBody(mediaType) Kotlin extension (RequestBody.create is deprecated) for the in-memory GPX file part"
    - "Stateless-per-call network seam: the 2s poll spacing + 2-min deadline live in the Wave-3 Thread{} driving the pure Plan-01 driveUpload, so a mid-poll network drop never optimistically marks success"
    - "Mirror of StravaApiClient exactly: ensureFreshToken primary, StravaAuthenticator reactive net, logRateLimits (both header pairs), 429 as a distinct result, never rethrow"

key-files:
  created:
    - phone/src/main/java/com/rokid/hud/phone/strava/StravaUploader.kt
    - phone/src/test/java/com/rokid/hud/phone/strava/StravaUploadModelTest.kt
  modified:
    - phone/src/main/java/com/rokid/hud/phone/strava/StravaModels.kt

decisions:
  - "startUpload treats resp.error != null as Failed even on a 2xx (Strava can 201 an enqueued upload but also 4xx with an error body); a null-safe Gson parse feeds both the success id and the failure message"
  - "poll() returns Error (not a bespoke RateLimited) on 429 — a poll rate-limit is transient and the caller keeps polling within its deadline; only startUpload distinguishes RateLimited (a POST 429 is a terminal 'try again shortly' the UI surfaces)"
  - "poll() empty/unparseable-but-2xx body -> Processing (keep polling), never a false Ready — an ambiguous poll must not claim success (Pitfall 4)"
  - "id_str is the poll-path id; startUpload falls back to id?.toString() only if id_str is absent (defensive 64-bit path — Pitfall 3)"

requirements-completed: [UPL-02, UPL-03]

# Metrics
duration: 14min
completed: 2026-07-04
---

# Phase 5 Plan 02: Strava Upload Network Layer Summary

**One-liner:** The Strava-upload HTTP layer now exists — a stateless `StravaUploader` that POSTs the recorded GPX as an OkHttp `MultipartBody.FORM` (OkHttp owns the boundary; the request carries only the Bearer header) and polls `GET /uploads/{id_str}` interpreting Strava's async result as Ready / Duplicate (recovered via `GpxWriter.parseDuplicateActivityId`, no re-upload) / Processing / Error, plus an all-nullable `UploadResponse` Gson model — every HTTP concern isolated behind `StartResult`/`PollResult` seams so Wave 3 drives it via the pure Plan-01 `driveUpload`, and the whole thing mirrors the shipped `StravaApiClient` discipline (ensureFreshToken primary, logRateLimits, 429-distinct, never rethrow).

## Performance

- **Duration:** ~14 min
- **Tasks:** 2 (both `type="auto"`)
- **Files:** 3 (2 created, 1 modified)
- **Tests:** phone **186** / shared 7, **0 failures** (5 net-new in `StravaUploadModelTest`; all pre-existing Phase 1–4 + Plan-01 tests unaffected)

## Accomplishments

- **`UploadResponse` Gson model (all-nullable)** — added to `StravaModels.kt` next to `StravaRoute`/`TokenResponse`, following the same Gson-via-Unsafe discipline: `id: Long?`, `idStr: String?` (`id_str`), `externalId: String?` (`external_id`), `error: String?`, `status: String?`, `activityId: Long?` (`activity_id`). A missing/renamed field lands as null, never a typed crash. `idStr` is documented as the poll-path id (64-bit safe); `error`/`status`/`activityId` drive the poll interpretation. Parsed with Gson (STACK rule — Gson for Strava responses, org.json stays for session JSON).
- **`StravaUploadModelTest` (5 JVM tests)** — mirrors `StravaRouteModelTest`. Parses all three documented bodies with a plain `Gson().fromJson`: (a) initial processing (activity_id null, id_str verbatim, error null), (b) ready (status "Your activity is ready.", activity_id set — a 64-bit id asserted un-truncated), (c) duplicate error (status error, error string carries the trailing id, `parseDuplicateActivityId` recovers `21234316`). Also asserts id_str verbatim == id.toString(), and a JSON missing `external_id`/`activity_id`/`error` parses to null without throwing.
- **`StravaUploader` (POST + poll, stateless per call)** — a `class StravaUploader(auth)` with its own private `OkHttpClient` mirroring `StravaApiClient` (authenticator `StravaAuthenticator(auth)` + DEBUG-only `Level.BASIC` interceptor), a private `Gson`, and `companion { TAG; BASE = ".../api/v3"; GPX_MEDIA }`. `startUpload` builds `MultipartBody.Builder().setType(FORM)` with `data_type=gpx`/`name`/`sport_type`/`external_id`/`file("$externalId.gpx", gpx.toRequestBody(GPX_MEDIA))` and sets ONLY the `Authorization` header (OkHttp emits the `multipart/form-data; boundary=...` — a hand-set Content-Type is the `data:empty` failure); a 429 → `RateLimited`, a non-2xx or `body.error != null` → `Failed(message)`, else `Started(idStr ?: id.toString())`. `poll(idStr)` GETs `/uploads/$idStr` (id_str in the path — Pitfall 3), interprets Ready(activityId)/Duplicate(via `parseDuplicateActivityId`)/Processing/Error, treats a 429 or exception as a transient `Error` (never a false success). Both methods call `ensureFreshToken()` first and `logRateLimits` (both header pairs); neither rethrows. No sleep/deadline loop here — that is Wave 3's.
- **Zero new dependencies** — `phone/build.gradle.kts` untouched; OkHttp 4.12.0 + Gson 2.10.1 already on the classpath (Phase 3). `assembleDebug` classpath unchanged.

## Public API delivered (for the Wave 3 UI consumer)

```kotlin
// com.rokid.hud.phone.strava.StravaUploader
class StravaUploader(auth: StravaAuthManager) {
    // POST /uploads (multipart; OkHttp owns the boundary). BLOCKING — Thread{} only.
    fun startUpload(gpx: String, name: String, externalId: String, sportType: String): StartResult
    // GET /uploads/{id_str}. ONE stateless poll. BLOCKING — Thread{} only.
    fun poll(idStr: String): PollResult
}

sealed class StartResult {
    data class Started(val idStr: String) : StartResult()  // 201 enqueued; idStr for the poll path
    object RateLimited : StartResult()                     // POST 429 — "Strava busy, try again shortly"
    data class Failed(val message: String) : StartResult() // non-2xx / body.error / no id / exception
}
sealed class PollResult {
    data class Ready(val activityId: Long) : PollResult()      // status "ready" + activity_id set
    data class Duplicate(val activityId: Long) : PollResult()  // recovered existing id — no re-upload
    object Processing : PollResult()                           // still queued — keep polling
    data class Error(val message: String) : PollResult()       // non-dup error / 429 / exception — NOT success
}

// com.rokid.hud.phone.strava.StravaModels (added)
data class UploadResponse(
    val id: Long?, val idStr: String?, val externalId: String?,
    val error: String?, val status: String?, val activityId: Long?
)  // @SerializedName maps id_str/external_id/activity_id; all-nullable
```

### The driveUpload → UI contract for Wave 3

Wave 3's `ActivitySummaryActivity` supplies the timing that this stateless layer deliberately omits, wiring these network results into the **pure Plan-01 `driveUpload`** (which already owns the transition table + the write-back-once-on-success invariant):

```kotlin
val uploader = StravaUploader(authManager)
Thread {
    driveUpload(
        // start: map StartResult -> Plan-01 StartOutcome
        start = {
            val gpx = GpxWriter.write(data.trackPoints, data.sport, data.startTime)
            if (!GpxWriter.isValidForUpload(gpx)) return@driveUpload StartOutcome.Failed("no valid track")
            when (val r = uploader.startUpload(gpx, defaultName(data), data.id, GpxWriter.sportType(data.sport))) {
                is StartResult.Started     -> StartOutcome.Started(r.idStr)
                is StartResult.RateLimited -> StartOutcome.RateLimited
                is StartResult.Failed      -> StartOutcome.Failed(r.message)
            }
        },
        // poll: map PollResult -> Plan-01 PollOutcome; Thread.sleep(2000) between polls is the DRIVER'S job
        poll = { idStr ->
            Thread.sleep(2_000)
            when (val p = uploader.poll(idStr)) {
                is PollResult.Ready      -> PollOutcome.Ready(p.activityId)
                is PollResult.Duplicate  -> PollOutcome.Duplicate(p.activityId)
                is PollResult.Processing -> PollOutcome.Processing
                is PollResult.Error      -> PollOutcome.Error(p.message)
            }
        },
        isDeadlineReached = { System.currentTimeMillis() >= deadline }, // deadline = now + 120_000
        emit = { st -> runOnUiThread { renderUploadState(st) } },       // UploadState -> UI text/button
        onSuccess = { activityId -> SessionStore(dir).updateUploadState(data.id, activityId) } // write-back ONLY here
    )
}.start()
```

Key contract points Wave 3 must honor (all already enforced by Plan-01's pure machine, but stated so the UI wires them right):
- **Poll spacing + the 2-min deadline are the driver's** — `StravaUploader.poll` is one call. Put `Thread.sleep(2_000)` in the `poll` lambda (or before it) and compute `deadline = System.currentTimeMillis() + 120_000` for `isDeadlineReached`.
- **Write-back fires ONLY on success** — `onSuccess` (→ `SessionStore.updateUploadState`) is invoked by `driveUpload` exactly once, in the Ready/Duplicate branch. A `PollResult.Error` (incl. a 429 or a mid-poll network drop) is never a success, so the session stays `stravaUploaded=false` and a 2-min timeout lands in `Pending` — retry is always safe (a re-POST is idempotent via duplicate recovery).
- **`PollResult.Error` on 429 is intentional** — a poll rate-limit is transient; `driveUpload` interprets it and the driver keeps polling within the deadline. Only `StartResult.RateLimited` (a POST 429) is the distinct "Strava busy — retry shortly" the UI surfaces up front.
- **`sport_type` is PascalCase** — pass `GpxWriter.sportType(data.sport)` (`Ride`/`Run`), not the lowercase session sport.

## Decisions Made

- **`resp.error != null` is a failure even on a 2xx** — Strava returns the same `UploadResponse` body on both the 201 enqueue and a 4xx rejection; a null-safe Gson parse of the body feeds both the success `idStr` and the failure `message` (`parsed?.error ?: "HTTP ${resp.code}"`), so a bad-request body with an error string is surfaced as `Failed(error)` rather than a spurious `Started`.
- **`poll()` returns `Error` (not a bespoke `RateLimited`) on 429** — a poll rate-limit is transient and the Wave-3 driver keeps polling within its 2-min deadline; a distinct terminal `RateLimited` only makes sense for the POST (which `startUpload` provides). This keeps `PollResult` a clean 4-way that maps 1:1 onto the Plan-01 `PollOutcome` (`Ready`/`Duplicate`/`Processing`/`Error`).
- **Empty/unparseable-but-2xx poll body → `Processing`, never `Ready`** — an ambiguous poll result must never optimistically claim success (Pitfall 4). A null Gson parse on a non-2xx is `Error("HTTP <code>")`; on a 2xx it is `Processing` (keep polling until the real `activity_id` or the deadline).
- **`id_str` fallback to `id?.toString()`** — the poll path uses `id_str` (64-bit safe, Pitfall 3); the numeric `id` is used only if `id_str` is somehow absent, and if both are missing the POST is `Failed("Upload returned no id")` rather than polling a bad path.
- **Mirrored `StravaApiClient` verbatim, not refactored into a shared base** — the plan prescribed mirroring the shipped client's discipline (own OkHttpClient with the authenticator + DEBUG-only BASIC interceptor, `logRateLimits` with both header pairs). Kept them as parallel structures rather than extracting a base class (no such refactor was in scope; the two clients stay independently readable).

## Deviations from Plan

**None — the plan executed exactly as written.** Both tasks' actions, verify gates, and grep gates matched the plan; no bugs, missing functionality, or blocking issues were encountered (Rules 1–4 did not fire). The only environment note (a non-deviation): verification commands ran from the worktree root rather than the plan's literal repo path, per worktree path-safety — the Gradle invocation form is identical.

## Known Stubs

None. `startUpload` and `poll` are fully implemented and the model is fully test-covered. No TODO/FIXME/placeholder/hardcoded-empty patterns in any created or modified file. (The deadline/poll-spacing loop being absent from `StravaUploader` is by design — it belongs to Wave 3's driver per the Plan-01 contract, documented above — not a stub.)

## Threat Model Compliance

All `mitigate`-disposition threats from the plan's `<threat_model>` are addressed:
- **T-05-05 / T-05-06 (GPX + Bearer token disclosure via logging):** the OkHttp client is HTTPS-only with the DEBUG interceptor pinned to `Level.BASIC` (request line only, never the body); `logRateLimits` logs only the two header-usage strings; a grep of every `Log.*` statement in `StravaUploader.kt` confirms zero occurrences of `gpx`/`Bearer`/`token`/coordinate/`.lat`/`.lng`.
- **T-05-07 (manual multipart boundary corrupting the body):** `MultipartBody.Builder().setType(FORM)` — OkHttp owns the boundary; the request sets only the `Authorization` header. Grep gate for a hand-set `Content-Type`/`"multipart/form-data"` string = **0**.
- **T-05-08 (malformed/oversized upload response):** every Gson parse is inside the method's try/catch (never rethrows); the all-nullable `UploadResponse` tolerates missing fields; `poll()` is a single stateless call (the 2-min deadline is Wave 3's), and an ambiguous body is `Processing`, never a false success.
- **T-05-09 (401 refresh loop at upload):** `ensureFreshToken` is the proactive PRIMARY in both methods; the reactive `StravaAuthenticator`'s `responseCount >= 2` give-up (Phase 3) prevents a refresh-loop DoS; token endpoints stay on the Phase-3 plain client (this uploader only carries the resource-call authenticator).
- **T-05-SC (dependency installs):** zero packages added; `phone/build.gradle.kts` unchanged.

No new threat surface beyond the register — the uploader adds one `strava.com` HTTPS endpoint pair (`POST /uploads` + `GET /uploads/{id_str}`) already enumerated in the trust boundaries.

## Verification Results

- `:phone:assembleDebug` — BUILD SUCCESSFUL (the Android network code compiles; no new deps).
- `:phone:testDebugUnitTest :shared:testDebugUnitTest` — BUILD SUCCESSFUL; **phone 186 / shared 7, 0 failures** (Plan-01 seams + the new 5-case `UploadResponse` model test; no regression from the Phase 1–4 suites).
- `StravaUploadModelTest` result XML: `tests="5" skipped="0" failures="0" errors="0"`.
- Grep gates (all pass): `MultipartBody.FORM` = 1 (≥1); hand-set `Content-Type`/`"multipart/form-data"` = **0**; `uploads/$idStr` in the poll path = 1 (≥1); `ensureFreshToken` present in both methods.
- Security scan: no `Log.*` statement in `StravaUploader.kt` emits the token, GPX body, or any coordinate.
- `phone/build.gradle.kts` unchanged (zero new dependencies).

## Commits

| Hash | Type | Description |
|------|------|-------------|
| e750925 | feat | UploadResponse Gson model (all-nullable, id_str verbatim) + StravaUploadModelTest (5 cases) |
| f3f3903 | feat | StravaUploader — multipart POST /uploads (OkHttp boundary) + stateless poll GET /uploads/{id_str} |

## For Wave 3 (activity-summary-ui)

Wire `StravaUploader(authManager)` into `ActivitySummaryActivity`'s "Upload to Strava" `Thread{}` and feed `startUpload`/`poll` into the pure Plan-01 `driveUpload` (see "The driveUpload → UI contract" above): `Thread.sleep(2_000)` in the `poll` lambda, `deadline = now + 120_000` for `isDeadlineReached`, `runOnUiThread { renderUploadState(...) }` for `emit`, and `SessionStore.updateUploadState(data.id, activityId)` for `onSuccess` (fires only on Ready/Duplicate — never on Error/Pending, so a failed/interrupted upload leaves the local JSON untouched and retry stays safe). Map `StartResult`/`PollResult` onto `StartOutcome`/`PollOutcome` exactly as shown. Gate the upload button on `authManager.isConnected()` (hint "Connect Strava first" otherwise), pass `GpxWriter.sportType(data.sport)` for the PascalCase `sport_type`, and render the six `UploadState` variants (Uploading/Processing/Done/Failed/RateLimited/Pending) as the locked progress UI.

## Self-Check: PASSED

All created/modified files verified present (`StravaUploader.kt`, `StravaUploadModelTest.kt`, `StravaModels.kt` with `UploadResponse`); both task commit hashes (`e750925`, `f3f3903`) verified in git log. Full gate green: phone 186 + shared 7 tests, 0 failures; `assembleDebug` builds. Grep gates green (`MultipartBody.FORM` present, hand-set Content-Type = 0, id_str in the poll path, `ensureFreshToken` in both methods, no token/GPX in any log). `phone/build.gradle.kts` unchanged. STATE.md / ROADMAP.md NOT modified (orchestrator owns them).

---
*Phase: 05-activity-summary-strava-upload*
*Completed: 2026-07-04*
