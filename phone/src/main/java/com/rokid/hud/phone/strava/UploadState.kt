package com.rokid.hud.phone.strava

/**
 * The user-facing state of a Strava upload, driven by [driveUpload].
 *
 * Terminal states are Done / Failed / RateLimited / Pending. Only Done carries
 * an activity id, and — critically (Pitfall 4) — the write-back to disk happens
 * ONLY on the transition into Done, never on Failed / RateLimited / Pending, so
 * a network drop or 2-min timeout always leaves the local session retryable
 * (stravaUploaded stays false).
 */
sealed class UploadState {
    /** POST /uploads in flight. */
    object Uploading : UploadState()

    /** POST accepted; polling GET /uploads/{id} for the activity id. */
    object Processing : UploadState()

    /** Ready OR duplicate-recovered — the upload succeeded. */
    data class Done(val activityId: Long) : UploadState()

    /** Non-recoverable processing error (e.g. "Time information is missing"). */
    data class Failed(val message: String) : UploadState()

    /** 429 on the POST — "Strava busy, retry shortly". */
    object RateLimited : UploadState()

    /** 2-min deadline reached while still processing — retry later. */
    object Pending : UploadState()
}

/**
 * Outcome of the POST /uploads start seam (the network call is Wave 2/3; the
 * driver is pure).
 */
sealed class StartOutcome {
    /** 201 accepted; [idStr] is the 64-bit-safe upload id for the poll path. */
    data class Started(val idStr: String) : StartOutcome()

    /** 429 — rate limited. */
    object RateLimited : StartOutcome()

    /** Any other start failure. */
    data class Failed(val message: String) : StartOutcome()
}

/** Outcome of a single GET /uploads/{id} poll seam. */
sealed class PollOutcome {
    /** status "ready" + activity_id set. */
    data class Ready(val activityId: Long) : PollOutcome()

    /** Server surfaced a duplicate directly with the existing activity id. */
    data class Duplicate(val activityId: Long) : PollOutcome()

    /** Still processing — keep polling. */
    object Processing : PollOutcome()

    /**
     * A terminal error string. If it matches the duplicate pattern
     * ("<file> duplicate of activity <id>") the driver reinterprets it as a
     * successful Duplicate; otherwise it is a hard Failed.
     */
    data class Error(val message: String) : PollOutcome()
}

/**
 * PURE upload state machine. Interprets injected start/poll/deadline seams into
 * a sequence of [UploadState] emissions and, on success only, a single
 * [onSuccess] write-back call. Contains NO Thread / sleep / network / timing —
 * the real 2s poll spacing and 2-min deadline live in the Android glue (Wave
 * 2/3) that supplies [poll] and [isDeadlineReached].
 *
 * Transitions:
 * - emit(Uploading); run [start].
 * - StartOutcome.RateLimited -> emit(RateLimited), return (no poll, no write-back).
 * - StartOutcome.Failed      -> emit(Failed), return (no write-back).
 * - StartOutcome.Started     -> emit(Processing); loop [poll] until a terminal
 *   poll OR [isDeadlineReached] is true:
 *     - Ready(id) / Duplicate(id)      -> onSuccess(id) THEN emit(Done(id)); return.
 *     - Error whose message parses as a duplicate -> treated as Duplicate (success).
 *     - Error (non-duplicate)          -> emit(Failed); return (NO write-back).
 *     - Processing                     -> keep polling.
 *   Deadline reached with no terminal  -> emit(Pending); return (NO write-back).
 *
 * The write-back ([onSuccess]) is invoked in EXACTLY ONE place — the success
 * branch — so stravaUploaded can never flip on failure or timeout (Pitfall 4).
 */
fun driveUpload(
    start: () -> StartOutcome,
    poll: (idStr: String) -> PollOutcome,
    isDeadlineReached: () -> Boolean,
    emit: (UploadState) -> Unit,
    onSuccess: (activityId: Long) -> Unit
) {
    emit(UploadState.Uploading)
    when (val started = start()) {
        is StartOutcome.RateLimited -> {
            emit(UploadState.RateLimited)
            return
        }
        is StartOutcome.Failed -> {
            emit(UploadState.Failed(started.message))
            return
        }
        is StartOutcome.Started -> {
            emit(UploadState.Processing)
            while (!isDeadlineReached()) {
                when (val result = poll(started.idStr)) {
                    is PollOutcome.Ready -> {
                        onSuccess(result.activityId)
                        emit(UploadState.Done(result.activityId))
                        return
                    }
                    is PollOutcome.Duplicate -> {
                        onSuccess(result.activityId)
                        emit(UploadState.Done(result.activityId))
                        return
                    }
                    is PollOutcome.Error -> {
                        // A duplicate error is success (no re-upload, no data loss).
                        val dupId = GpxWriter.parseDuplicateActivityId(result.message)
                        if (dupId != null) {
                            onSuccess(dupId)
                            emit(UploadState.Done(dupId))
                        } else {
                            emit(UploadState.Failed(result.message))
                        }
                        return
                    }
                    is PollOutcome.Processing -> {
                        // keep polling
                    }
                }
            }
            // Deadline reached with no terminal poll — retryable, no write-back.
            emit(UploadState.Pending)
        }
    }
}
