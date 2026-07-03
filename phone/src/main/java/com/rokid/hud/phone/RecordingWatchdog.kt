package com.rokid.hud.phone

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Layered recording watchdog (REC-05, RESEARCH Pattern 7).
 *
 * L1 — in-process staleness timer: a main-looper Handler self-chain checks
 * every [STALENESS_CHECK_MS] whether the monotonic last-fix anchor is older
 * than [STALENESS_THRESHOLD_MS] while a session is TRACKING and surfaces a
 * warning via [onStale] (log + notification text swap). Staleness math uses
 * SystemClock.elapsedRealtime() ONLY — never the fix's wall-clock timestamp,
 * which device clock corrections can shift (RESEARCH Pitfall 6).
 *
 * L2 — AlarmManager self-chain: a 15-minute exact allow-while-idle alarm
 * wakes [WatchdogReceiver] even in Doze and even after process death, so a
 * silently-dead FLP subscription or an OEM-killed service (RESEARCH
 * Pitfall 4 — ColorOS) is detected and recovered instead of losing the ride.
 * Exact scheduling is permission-gated on API 31+ and degrades gracefully to
 * an inexact alarm when denied (RESEARCH Pitfall 2).
 *
 * L3 (START_STICKY + orphan-checkpoint resume) shipped in plan 01-04.
 *
 * Lifecycle: [start] with recording, [stop] with recording — no alarms are
 * scheduled while idle.
 */
class RecordingWatchdog(
    private val context: Context,
    private val isTracking: () -> Boolean,
    private val lastFixElapsedRealtimeMs: () -> Long,
    private val onStale: (Long) -> Unit
) {

    companion object {
        private const val TAG = "RecWatchdog"
        private const val STALENESS_CHECK_MS = 15_000L
        /** GPS silence beyond this age (monotonic) is surfaced as a warning. */
        const val STALENESS_THRESHOLD_MS = 30_000L
        /**
         * Must stay above the Doze 9-minute allow-while-idle throttle
         * (RESEARCH Pattern 7) — never go below ~10 minutes.
         */
        private const val WATCHDOG_INTERVAL_MS = 15 * 60_000L
        private const val REQ_WATCHDOG = 1001
        const val ACTION_WATCHDOG_CHECK = "com.rokid.hud.phone.WATCHDOG_CHECK"
    }

    private val handler = Handler(Looper.getMainLooper())

    private val stalenessCheck = object : Runnable {
        override fun run() {
            if (isTracking()) {
                val ageMs = SystemClock.elapsedRealtime() - lastFixElapsedRealtimeMs()
                if (ageMs > STALENESS_THRESHOLD_MS) {
                    Log.w(TAG, "GPS stale: no fix for ${ageMs / 1000}s")
                    onStale(ageMs)
                }
            }
            handler.postDelayed(this, STALENESS_CHECK_MS)
        }
    }

    /** Starts the L1 staleness chain and schedules the first L2 alarm. Idempotent. */
    fun start() {
        handler.removeCallbacks(stalenessCheck)
        handler.postDelayed(stalenessCheck, STALENESS_CHECK_MS)
        scheduleNextAlarm()
    }

    /** Stops the L1 chain and cancels the pending L2 alarm. Idempotent. */
    fun stop() {
        handler.removeCallbacks(stalenessCheck)
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            am?.cancel(buildAlarmPendingIntent())
        } catch (e: Exception) {
            Log.w(TAG, "Watchdog alarm cancel failed: ${e.message}")
        }
    }

    /**
     * Schedules the next L2 check [WATCHDOG_INTERVAL_MS] from now. Exact when
     * permitted (allow-while-idle fires in Doze); inexact fallback otherwise —
     * detection latency grows, nothing breaks. The whole body is guarded
     * against SecurityException because exact-alarm revocation can race the
     * canScheduleExactAlarms() gate (RESEARCH Pitfall 2).
     */
    fun scheduleNextAlarm() {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (am == null) {
                Log.e(TAG, "AlarmManager unavailable — watchdog alarm not scheduled")
                return
            }
            val pi = buildAlarmPendingIntent()
            val triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                // No exact-alarm permission exists pre-31 — always allowed.
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                Log.w(TAG, "Exact alarms denied — watchdog degraded to inexact")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Watchdog schedule failed: ${e.message}", e)
        }
    }

    private fun buildAlarmPendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQ_WATCHDOG,
            Intent(context, WatchdogReceiver::class.java).setAction(ACTION_WATCHDOG_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

/**
 * L2 alarm target. Manifest-declared (android:exported="false") so it
 * survives process death: when the OS killed the recording mid-session, the
 * alarm still fires and this receiver restarts [HudStreamingService], which
 * resumes the session via orphan recovery. Does nothing when no recording is
 * active — the chain dies with the recording (no idle alarms). The receiver
 * never reschedules; the service owns the chain and reschedules when it
 * handles [RecordingWatchdog.ACTION_WATCHDOG_CHECK].
 */
class WatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RecWatchdog"
        private const val PREFS_HUD = "rokid_hud_prefs"
        private const val PREF_REC_ACTIVE = "rec_active"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RecordingWatchdog.ACTION_WATCHDOG_CHECK) return
        val recActive = context.getSharedPreferences(PREFS_HUD, Context.MODE_PRIVATE)
            .getBoolean(PREF_REC_ACTIVE, false)
        if (!recActive) return // no active recording — let the chain die
        try {
            // Background FGS start is allowed here (exact-alarm exemption #5,
            // RESEARCH Pattern 7), but the location-TYPE prerequisite still
            // requires ACCESS_BACKGROUND_LOCATION — without that grant the
            // service's startForeground throws (Pitfall 1) and L3 orphan
            // recovery covers the session on the next app open instead.
            ContextCompat.startForegroundService(
                context,
                Intent(context, HudStreamingService::class.java)
                    .setAction(RecordingWatchdog.ACTION_WATCHDOG_CHECK)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog restart blocked: ${e.message}", e)
        }
    }
}
