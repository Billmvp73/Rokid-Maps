package com.rokid.hud.phone.strava

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UPL-02/UPL-03: the PURE upload state machine (driveUpload) exercised with
 * injected start/poll/deadline seams — NO Thread, NO sleep, NO network. The real
 * timing (2s poll spacing, 2-min deadline) lives in the Wave 2/3 Android glue
 * that calls this driver; here we prove the transition rules and, above all, that
 * the write-back (onSuccess) fires EXACTLY once and ONLY in the success branch
 * (Pitfall 4 — stravaUploaded must never flip on failure/timeout).
 *
 * Pure JVM, no android.* references.
 */
class UploadStateMachineTest {

    /** Records emitted states + onSuccess calls for assertions. */
    private class Recorder {
        val states = mutableListOf<UploadState>()
        val successes = mutableListOf<Long>()
        val emit: (UploadState) -> Unit = { states.add(it) }
        val onSuccess: (Long) -> Unit = { successes.add(it) }
    }

    private val SESSION_ID = "20260703-154500-abc123"

    @Test
    fun readyAfterProcessingReachesDoneAndWritesBackOnce() {
        val r = Recorder()
        // Two Processing polls, then Ready(99).
        val polls = ArrayDeque(listOf(PollOutcome.Processing, PollOutcome.Processing, PollOutcome.Ready(99L)))
        driveUpload(
            start = { StartOutcome.Started("up-1") },
            poll = { polls.removeFirst() },
            isDeadlineReached = { false },
            emit = r.emit,
            onSuccess = r.onSuccess
        )
        // Uploading -> Processing -> Done(99)
        assertEquals(UploadState.Uploading, r.states.first())
        assertTrue("must pass through Processing", r.states.contains(UploadState.Processing))
        assertEquals(UploadState.Done(99L), r.states.last())
        // Write-back fires exactly once with the activity id.
        assertEquals(listOf(99L), r.successes)
    }

    @Test
    fun duplicateErrorReachesDoneViaParsedIdAndWritesBackOnce() {
        val r = Recorder()
        // A non-Ready ErrorOutcome whose message is a filename-prefixed duplicate.
        val polls = ArrayDeque(
            listOf<PollOutcome>(PollOutcome.Error("session-x.gpx duplicate of activity 555"))
        )
        driveUpload(
            start = { StartOutcome.Started("up-2") },
            poll = { polls.removeFirst() },
            isDeadlineReached = { false },
            emit = r.emit,
            onSuccess = r.onSuccess
        )
        // Duplicate is success — Done(555), write-back once, no re-upload.
        assertEquals(UploadState.Done(555L), r.states.last())
        assertEquals(listOf(555L), r.successes)
    }

    @Test
    fun duplicateOutcomeReachesDoneAndWritesBackOnce() {
        val r = Recorder()
        // An explicit Duplicate poll outcome (server surfaced it directly).
        val polls = ArrayDeque(listOf<PollOutcome>(PollOutcome.Duplicate(777L)))
        driveUpload(
            start = { StartOutcome.Started("up-3") },
            poll = { polls.removeFirst() },
            isDeadlineReached = { false },
            emit = r.emit,
            onSuccess = r.onSuccess
        )
        assertEquals(UploadState.Done(777L), r.states.last())
        assertEquals(listOf(777L), r.successes)
    }

    @Test
    fun nonDuplicateErrorReachesFailedAndNeverWritesBack() {
        val r = Recorder()
        val polls = ArrayDeque(listOf<PollOutcome>(PollOutcome.Error("Time information is missing")))
        driveUpload(
            start = { StartOutcome.Started("up-4") },
            poll = { polls.removeFirst() },
            isDeadlineReached = { false },
            emit = r.emit,
            onSuccess = r.onSuccess
        )
        assertTrue("last state must be Failed", r.states.last() is UploadState.Failed)
        assertEquals("Time information is missing", (r.states.last() as UploadState.Failed).message)
        // CRITICAL (Pitfall 4): no write-back on failure — stravaUploaded stays false.
        assertTrue("write-back must NOT fire on failure", r.successes.isEmpty())
    }

    @Test
    fun startRateLimitedIsTerminalWithNoPollAndNoWriteBack() {
        val r = Recorder()
        var polled = false
        driveUpload(
            start = { StartOutcome.RateLimited },
            poll = { polled = true; PollOutcome.Processing },
            isDeadlineReached = { false },
            emit = r.emit,
            onSuccess = r.onSuccess
        )
        assertEquals(UploadState.RateLimited, r.states.last())
        assertFalse("poll must never run when start is RateLimited", polled)
        assertTrue(r.successes.isEmpty())
    }

    @Test
    fun startFailedIsTerminalWithNoWriteBack() {
        val r = Recorder()
        driveUpload(
            start = { StartOutcome.Failed("network down") },
            poll = { PollOutcome.Processing },
            isDeadlineReached = { false },
            emit = r.emit,
            onSuccess = r.onSuccess
        )
        assertTrue(r.states.last() is UploadState.Failed)
        assertEquals("network down", (r.states.last() as UploadState.Failed).message)
        assertTrue(r.successes.isEmpty())
    }

    @Test
    fun deadlineReachedBeforeReadyLandsInPendingWithNoWriteBack() {
        val r = Recorder()
        // Poll always Processing; deadline reached immediately -> Pending.
        driveUpload(
            start = { StartOutcome.Started("up-5") },
            poll = { PollOutcome.Processing },
            isDeadlineReached = { true }, // 2-min timeout already elapsed
            emit = r.emit,
            onSuccess = r.onSuccess
        )
        assertEquals(UploadState.Pending, r.states.last())
        // CRITICAL (Pitfall 4): timeout leaves the session retryable — no write-back.
        assertTrue("write-back must NOT fire on timeout", r.successes.isEmpty())
    }

    @Test
    fun deadlineAfterSeveralProcessingPollsStillLandsPending() {
        val r = Recorder()
        var calls = 0
        driveUpload(
            start = { StartOutcome.Started("up-6") },
            poll = { PollOutcome.Processing },
            // First two checks allow polling, then the deadline trips.
            isDeadlineReached = { calls++ >= 2 },
            emit = r.emit,
            onSuccess = r.onSuccess
        )
        assertEquals(UploadState.Pending, r.states.last())
        assertTrue(r.successes.isEmpty())
    }
}
