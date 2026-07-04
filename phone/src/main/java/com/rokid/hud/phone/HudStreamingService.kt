package com.rokid.hud.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Binder
import android.os.PowerManager
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.rokid.hud.shared.protocol.*
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.UUID
import com.rokid.hud.shared.cache.DiskTileCache
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Consumer of the service's single GPS pipeline (LocationConsumer fan-out).
 * Registered consumers receive every fix delivered to onLocationUpdate;
 * NavigationManager wiring is untouched (its data race is Phase 4's).
 */
interface LocationConsumer {
    fun onLocationUpdate(location: Location)
}

class HudStreamingService : Service() {

    companion object {
        private const val TAG = "HudStreaming"
        private const val SERVICE_NAME = "RokidHudSPP"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val NOTIFICATION_ID = 1
        private const val LOCATION_INTERVAL_MS = 1000L
        private const val MAX_TILE_BYTES = 512 * 1024 // 512KB cap to avoid OOM on bad/corrupt responses
        private const val ACTIVITIES_DIR = "activities"
        private const val PREFS_HUD = "rokid_hud_prefs"
        private const val PREF_REC_ACTIVE = "rec_active"
        private const val PREF_REC_SESSION_ID = "rec_session_id"
        private const val SPORT_STATE_TICK_MS = 1000L
        private const val NOTIF_TEXT_UPDATE_MS = 10_000L
    }

    inner class LocalBinder : Binder() {
        fun getService(): HudStreamingService = this@HudStreamingService
    }

    private val binder = LocalBinder()
    private var serverSocket: BluetoothServerSocket? = null
    private val clients = CopyOnWriteArrayList<BufferedWriter>()
    private val clientSessions = CopyOnWriteArrayList<ClientSession>()
    private val tileExecutor = Executors.newFixedThreadPool(4)
    private val TILE_URLS = arrayOf(
        "https://basemaps.cartocdn.com/dark_all/%d/%d/%d@2x.png",
        "https://basemaps.cartocdn.com/dark_all/%d/%d/%d.png",
        "https://tile.openstreetmap.org/%d/%d/%d.png"
    )
    private val USER_AGENT = "RokidHudMaps/1.0 (Phone proxy)"

    private data class ClientSession(val socket: BluetoothSocket, val writer: BufferedWriter)
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    @Volatile private var running = false

    private var lastLat = 0.0
    private var lastLng = 0.0

    private var cachedSettings: SettingsMessage? = null
    private var cachedWifiCreds: WifiCredsMessage? = null
    private val speedLimitClient = OverpassSpeedLimitClient()
    private var diskTileCache: DiskTileCache? = null

    private var wakeLock: PowerManager.WakeLock? = null

    var navigationManager: NavigationManager? = null
    var uiCallback: NavigationCallback? = null

    // --- activity recording (REC-01/05/06/07): fan-out consumers + engine + store ---
    private val locationConsumers = CopyOnWriteArrayList<LocationConsumer>()
    private var activitySessionManager: ActivitySessionManager? = null
    private var sessionStore: SessionStore? = null
    private var metricsListener: MetricsListener? = null
    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    private var lastNotifUpdateMs = 0L

    /**
     * REC-05 L1/L2 watchdog. The lambdas consult the nullable recording
     * engine safely, so the field itself is non-null and construction-order
     * free (the constructor touches nothing but the main looper). Started
     * with recording (startRecording success / orphan resume), stopped with
     * recording (stopRecording finalization / onDestroy) — no alarms are
     * scheduled while idle.
     */
    private val recordingWatchdog = RecordingWatchdog(
        this,
        isTracking = { activitySessionManager?.state == SessionState.TRACKING },
        lastFixElapsedRealtimeMs = { activitySessionManager?.lastFixElapsedRealtimeMs ?: 0L },
        onStale = { ageMs -> onRecordingStale(ageMs) }
    )

    /**
     * ~1Hz sport_state ticker (REC-07 runtime half). The ticker — not per-fix
     * GPS callbacks — defines the cadence, so sport_state keeps flowing (with
     * frozen moving/distance, st stays "tracking") even through GPS gaps. One
     * snapshot per tick feeds BT broadcast, UI listener, notification, and
     * checkpoint trigger (single source of truth, threat T-04-04). Self-chains
     * only while TRACKING; started by startRecording/orphan resume, stopped by
     * the stopRecording finalization block and onDestroy.
     */
    private val sportStateTicker = object : Runnable {
        override fun run() {
            val asm = activitySessionManager ?: return
            if (asm.state != SessionState.TRACKING) return
            val snap = asm.currentSnapshot()
            broadcastSportState(snap)
            metricsListener?.onMetrics(snap)
            asm.pollCheckpoint()?.let { data ->
                try {
                    sessionStore?.writeCheckpointAsync(data)
                } catch (e: Exception) {
                    Log.w(TAG, "Checkpoint dispatch failed for ${data.id}: ${e.message}")
                }
            }
            // Distance-text refresh at ~10s cadence (notification rate-limit
            // defense, Pitfall 10) — elapsed ticks via the system chronometer.
            if (SystemClock.elapsedRealtime() - lastNotifUpdateMs >= NOTIF_TEXT_UPDATE_MS) {
                notifyRecording(snap)
            }
            mainHandler.postDelayed(this, SPORT_STATE_TICK_MS)
        }
    }

    private fun startSportStateTicker() {
        mainHandler.removeCallbacks(sportStateTicker)
        mainHandler.postDelayed(sportStateTicker, SPORT_STATE_TICK_MS)
    }

    private fun stopSportStateTicker() {
        mainHandler.removeCallbacks(sportStateTicker)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // Keep the recording form when a session is TRACKING — every
            // startForegroundService delivery (watchdog check, UI re-start)
            // re-runs this path, and swapping in the static text would blank
            // the live recording notification until the next ticker refresh.
            val trackingSnap = activitySessionManager
                ?.takeIf { it.state == SessionState.TRACKING }
                ?.currentSnapshot()
            val notification =
                if (trackingSnap != null) buildRecordingNotification(trackingSnap)
                else buildNotification()
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Android 14+: a location-type FGS restarted from the background without
            // ACCESS_BACKGROUND_LOCATION is rejected — log + stop, never crash-loop
            // (recovery happens via orphan checkpoint on the next user launch).
            Log.e(TAG, "startForeground blocked (background start without bg-location?)", e)
            stopSelf()
            return START_NOT_STICKY
        } catch (e: IllegalStateException) {
            // API 31+ throws ForegroundServiceStartNotAllowedException — an
            // IllegalStateException subclass not referencable below API 31, so
            // catching ISE is the minSdk-28-safe form (WR-07) — when FGS
            // promotion is disallowed (OEM-restricted START_STICKY restarts,
            // expired background-start exemptions). Same no-crash-loop handling.
            Log.e(TAG, "startForeground blocked (FGS start not allowed)", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            diskTileCache = DiskTileCache(applicationContext)
            acquireWakeLock()
            initNavigation()
            initRecording()
            startBluetoothServer()
            startLocationUpdates()
        }
        // Watchdog L2 check — deliberately AFTER the init block: when the
        // process was dead, orphan recovery above has already resumed the
        // interrupted session, so the staleness probe below sees the resumed
        // TRACKING state; for a live service the block above is skipped and
        // the action is handled immediately.
        if (intent?.action == RecordingWatchdog.ACTION_WATCHDOG_CHECK) {
            handleWatchdogCheck()
        }
        return START_STICKY
    }

    /**
     * L2 recovery (REC-05): on each watchdog alarm, re-initialize the FLP
     * subscription when GPS has been silent >30s during TRACKING (simple
     * remove + re-request; no priority-toggling escalation until the OPPO
     * device pass shows silent-FLP incidents), then reschedule the next alarm
     * whenever a recording is active. Not TRACKING → no reschedule: the
     * alarm chain dies with the recording.
     */
    private fun handleWatchdogCheck() {
        val asm = activitySessionManager ?: return
        if (asm.state != SessionState.TRACKING) return
        val ageMs = SystemClock.elapsedRealtime() - asm.lastFixElapsedRealtimeMs
        if (ageMs > RecordingWatchdog.STALENESS_THRESHOLD_MS) {
            locationCallback?.let { fusedLocationClient?.removeLocationUpdates(it) }
            startLocationUpdates()
            Log.w(TAG, "Watchdog: reinitialized FLP after ${ageMs / 1000}s silence")
        }
        recordingWatchdog.scheduleNextAlarm()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm == null) {
            Log.e(TAG, "PowerManager null — WakeLock not acquired")
            return
        }
        val tag = "${packageName}:streaming"
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
            setReferenceCounted(false)
            try {
                acquire() // Hold until release() in onDestroy — keeps CPU running when screen off
            } catch (e: Exception) {
                Log.e(TAG, "WakeLock acquire failed: ${e.message}")
            }
        }
        if (wakeLock?.isHeld == true) {
            Log.i(TAG, "WakeLock acquired — maps keep updating when screen is off")
        } else {
            Log.e(TAG, "WakeLock not held after acquire")
        }
    }

    override fun onDestroy() {
        running = false
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
        navigationManager?.stopNavigation()
        locationCallback?.let { fusedLocationClient?.removeLocationUpdates(it) }
        // Best-effort L3 crash checkpoint: a still-TRACKING session is checkpointed
        // through the store's serial executor — never a second writer racing a
        // same-tick async checkpoint (WR-01) — and the bounded shutdownAndAwait
        // below drains it before teardown returns, so orphan recovery resumes it
        // on the next service start (REC-06). Graceful teardown clears the
        // watchdog flag — a hard kill skips onDestroy entirely, leaving
        // rec_active set for the watchdog to act on.
        if (activitySessionManager?.state == SessionState.TRACKING) {
            try {
                activitySessionManager?.snapshotSession()?.let { sessionStore?.writeCheckpointAsync(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Teardown checkpoint failed: ${e.message}", e)
            }
            clearRecordingPrefs()
        }
        stopSportStateTicker()
        // Graceful teardown stands the watchdog down (L1 chain + L2 alarm);
        // a hard kill skips onDestroy, so the OS-side alarm survives to
        // trigger watchdog-driven recovery.
        recordingWatchdog.stop()
        try { serverSocket?.close() } catch (_: Exception) {}
        for (s in clientSessions) {
            try { s.socket.close() } catch (_: Exception) {}
        }
        clientSessions.clear()
        for (w in clients) { try { w.close() } catch (_: Exception) {} }
        clients.clear()
        tileExecutor.shutdownNow()
        diskTileCache?.shutdown()
        sessionStore?.shutdownAndAwait(2_000L) // bounded drain: the queued teardown checkpoint completes (WR-01)
        super.onDestroy()
    }

    private fun initNavigation() {
        navigationManager = NavigationManager(object : NavigationCallback {
            override fun onRouteCalculated(waypoints: List<Waypoint>, totalDistance: Double, totalDuration: Double, steps: List<NavigationStep>) {
                sendRoute(waypoints, totalDistance, totalDuration)
                sendStepsList()
                uiCallback?.onRouteCalculated(waypoints, totalDistance, totalDuration, steps)
            }
            override fun onStepChanged(instruction: String, maneuver: String, distance: Double) {
                sendStep(instruction, maneuver, distance)
                sendStepsList()
                uiCallback?.onStepChanged(instruction, maneuver, distance)
            }
            override fun onNavigationError(message: String) {
                uiCallback?.onNavigationError(message)
            }
            override fun onArrived() {
                sendStep("You have arrived!", "arrive", 0.0)
                uiCallback?.onArrived()
            }
            override fun onRerouting() {
                uiCallback?.onRerouting()
            }
        })
    }

    /**
     * Recording engine wiring: constructs the session manager and store,
     * registers the permanent GPS consumer (ASM ignores fixes outside
     * TRACKING), and runs orphan-checkpoint recovery (REC-06 L3) — a
     * <10-minute-stale interrupted session resumes mid-recording, older ones
     * finalize as interrupted. Runs BEFORE startLocationUpdates so recovery
     * completes ahead of the first fix.
     */
    private fun initRecording() {
        val asm = ActivitySessionManager()
        activitySessionManager = asm
        val store = SessionStore(File(filesDir, ACTIVITIES_DIR))
        sessionStore = store
        locationConsumers.add(object : LocationConsumer {
            override fun onLocationUpdate(location: Location) {
                asm.recordLocation(location)
            }
        })
        try {
            val recovery = store.recoverOrphans()
            if (recovery.finalizedInterrupted > 0 || recovery.corrupt > 0) {
                Log.w(TAG, "Orphan recovery: ${recovery.finalizedInterrupted} finalized interrupted, ${recovery.corrupt} corrupt quarantined")
            }
            var resumed = false
            recovery.resumable?.let { data ->
                if (asm.resumeFrom(data)) {
                    Log.w(TAG, "Resumed interrupted session ${data.id}")
                    setRecordingPrefs(data.id)
                    notifyRecording(asm.currentSnapshot())
                    startSportStateTicker()
                    recordingWatchdog.start()
                    resumed = true
                }
            }
            if (!resumed) {
                // A kill that outlasted the resume window finalized-as-interrupted
                // above — clear the stale rec_active flag so the watchdog chain
                // dies instead of restarting the service for a dead session (WR-02).
                clearRecordingPrefs()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Orphan recovery failed: ${e.message}", e)
        }
    }

    fun startNavigation(destLat: Double, destLng: Double) {
        navigationManager?.startNavigation(destLat, destLng, lastLat, lastLng)
    }

    /**
     * Waypoint-accepting navigation entry point (NAVV-01): start turn-by-turn along a
     * PRE-COMPUTED route (Strava import → OSRM via-routing, or the follow-route fallback).
     *
     * PURE PASSTHROUGH — no new logic, no new state, no new glasses message type. It delegates
     * to [NavigationManager.startNavigationWithRoute], which fires the SAME
     * [NavigationCallback.onRouteCalculated]/[onStepChanged] that [initNavigation] already wires
     * to sendRoute + sendStepsList, so the route line + steps broadcast to the glasses UNCHANGED
     * (04-RESEARCH boundary note). Because follow-route always carries one synthetic "Follow route"
     * step (Plan 02 buildFollowRouteResult), sendStepsList does NOT early-return on empty steps —
     * the empty-steps trap (Pitfall 1) is closed upstream in NavigationManager, not here.
     */
    fun startNavigationWithRoute(
        waypoints: List<Waypoint>,
        steps: List<NavigationStep>,
        totalDistance: Double,
        totalDuration: Double,
        followRouteMode: Boolean
    ) {
        navigationManager?.startNavigationWithRoute(waypoints, steps, totalDistance, totalDuration, followRouteMode)
    }

    fun stopNavigation() {
        navigationManager?.stopNavigation()
        sendStep("", "", 0.0)
        sendRoute(emptyList(), 0.0, 0.0)
        broadcast(ProtocolCodec.encodeStepsList(StepsListMessage(emptyList(), 0)))
    }

    fun getLastLocation(): Pair<Double, Double> = Pair(lastLat, lastLng)

    /**
     * Start recording a session (REC-01 opt-in — navigation never triggers
     * this; free ride/run works with navigation stopped). Returns the session
     * manager's result; false when the service is not running or a session is
     * already TRACKING.
     */
    fun startRecording(sport: String): Boolean {
        if (!running) {
            Log.w(TAG, "startRecording ignored: service not running")
            return false
        }
        val asm = activitySessionManager ?: return false
        if (asm.state == SessionState.FINISHED) asm.reset()
        val started = asm.startSession(sport)
        if (started) {
            setRecordingPrefs(asm.snapshotSession()?.id ?: "")
            val snap = asm.currentSnapshot()
            broadcastSportState(snap)
            notifyRecording(snap)
            startSportStateTicker()
            recordingWatchdog.start()
        }
        return started
    }

    /**
     * Stop the active recording. flushLocations() is async IPC into Play
     * services — flushed fixes arrive whenever GMS responds, so finalization
     * is chained on the flush completion Task (WR-05): the listener runs on
     * the main thread by default and re-posts the finalization block behind
     * any drained fixes already enqueued on the main looper. Every drained
     * fix is therefore processed while still TRACKING, before stopSession.
     * Returns Unit — the UI observes completion via the finished
     * MetricsListener callback.
     */
    fun stopRecording() {
        val finalize = Runnable {
            if (!running) return@Runnable // destroyed first — the onDestroy checkpoint preserved the session
            val asm = activitySessionManager ?: return@Runnable
            val data = asm.stopSession() ?: return@Runnable
            val snap = asm.currentSnapshot()
            broadcastSportState(snap)
            metricsListener?.onMetrics(snap)
            try {
                sessionStore?.finalizeAsync(data)
            } catch (e: Exception) {
                Log.e(TAG, "Finalize dispatch failed for ${data.id}: ${e.message}", e)
            }
            stopSportStateTicker()
            recordingWatchdog.stop()
            // Static-notification restore AFTER the final finished sport_state
            // broadcast — the notification never says Recording once st=finished.
            try {
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, buildNotification())
            } catch (e: Exception) {
                Log.w(TAG, "Notification restore failed: ${e.message}")
            }
            clearRecordingPrefs()
        }
        val flc = fusedLocationClient
        if (flc == null) {
            mainHandler.post(finalize)
            return
        }
        try {
            flc.flushLocations().addOnCompleteListener { mainHandler.post(finalize) }
        } catch (e: Exception) {
            Log.w(TAG, "flushLocations failed: ${e.message}")
            mainHandler.post(finalize)
        }
    }

    /** Current recording lifecycle state (IDLE when the engine is not initialized). */
    fun recordingState(): SessionState = activitySessionManager?.state ?: SessionState.IDLE

    /** Live metrics snapshot, or null while IDLE (no session to report). */
    fun currentMetrics(): MetricsSnapshot? {
        val asm = activitySessionManager ?: return null
        if (asm.state == SessionState.IDLE) return null
        return asm.currentSnapshot()
    }

    /** UI metrics listener (MainActivity card); invoked on the main thread. */
    fun setMetricsListener(l: MetricsListener?) {
        metricsListener = l
    }

    /** Durable recording flag + session id for the watchdog (plan 01-06 consumes). */
    private fun setRecordingPrefs(sessionId: String) {
        getSharedPreferences(PREFS_HUD, MODE_PRIVATE).edit()
            .putBoolean(PREF_REC_ACTIVE, true)
            .putString(PREF_REC_SESSION_ID, sessionId)
            .apply()
    }

    private fun clearRecordingPrefs() {
        getSharedPreferences(PREFS_HUD, MODE_PRIVATE).edit()
            .putBoolean(PREF_REC_ACTIVE, false)
            .remove(PREF_REC_SESSION_ID)
            .apply()
    }

    fun sendSettings(
        ttsEnabled: Boolean, useImperial: Boolean = false,
        useMiniMap: Boolean = false, miniMapStyle: String = "strip",
        streamNotifications: Boolean = true, showUpcomingSteps: Boolean = false,
        showTurnAlert: Boolean = false, tileCacheSizeMb: Int = 100,
        showSpeed: Boolean = true, showSpeedLimit: Boolean = true
    ) {
        val msg = SettingsMessage(ttsEnabled, useImperial, useMiniMap, miniMapStyle, streamNotifications, showUpcomingSteps, showTurnAlert, tileCacheSizeMb, showSpeed, showSpeedLimit)
        cachedSettings = msg
        broadcast(ProtocolCodec.encodeSettings(msg))
    }

    fun sendWifiCreds(ssid: String, passphrase: String, enabled: Boolean) {
        val msg = WifiCredsMessage(ssid, passphrase, enabled)
        cachedWifiCreds = msg
        broadcast(ProtocolCodec.encodeWifiCreds(msg))
    }

    private val apkHandler = Handler(android.os.Looper.getMainLooper())

    /** Send an APK file to connected glasses in chunks over Bluetooth. Callbacks run on main thread. */
    fun sendApkToGlasses(
        uri: Uri,
        onProgress: ((sentChunks: Int, totalChunks: Int) -> Unit)? = null,
        onDone: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (clients.isEmpty()) {
            apkHandler.post { onError?.invoke("No glasses connected") ?: Unit }
            return
        }
        Thread {
            try {
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Cannot open APK file")
                val totalSize = pfd.statSize
                pfd.close()
                if (totalSize <= 0) throw IllegalStateException("Invalid APK size")
                val CHUNK_RAW = 3072
                val totalChunks = ((totalSize + CHUNK_RAW - 1) / CHUNK_RAW).toInt()
                broadcast(ProtocolCodec.encodeApkStart(ApkStartMessage(totalSize, totalChunks)))
                apkHandler.post { onProgress?.invoke(0, totalChunks) }
                contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(CHUNK_RAW)
                    var sent = 0
                    var index = 0
                    while (running && index < totalChunks) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        val chunk = if (read < buffer.size) buffer.copyOf(read) else buffer
                        val b64 = Base64.getEncoder().encodeToString(chunk)
                        broadcast(ProtocolCodec.encodeApkChunk(ApkChunkMessage(index, b64)))
                        sent++
                        index++
                        if (sent % 20 == 0 || index == totalChunks) {
                            val s = sent
                            val t = totalChunks
                            apkHandler.post { onProgress?.invoke(s, t) }
                        }
                    }
                    broadcast(ProtocolCodec.encodeApkEnd())
                    apkHandler.post {
                        onProgress?.invoke(totalChunks, totalChunks)
                        onDone?.invoke()
                    }
                } ?: throw IllegalStateException("Cannot read APK")
            } catch (e: Exception) {
                Log.w(TAG, "sendApkToGlasses failed", e)
                apkHandler.post { onError?.invoke(e.message ?: "Unknown error") ?: Unit }
            }
        }.start()
    }

    fun clearTileCache() { diskTileCache?.clear() }
    fun tileCacheSizeBytes(): Long = diskTileCache?.sizeBytes() ?: 0L
    fun updateTileCacheSize(mb: Int) { diskTileCache?.updateMaxSize(mb) }

    fun sendNotification(title: String?, text: String?, packageName: String?) {
        broadcast(ProtocolCodec.encodeNotification(
            NotificationMessage(title, text, packageName, System.currentTimeMillis())
        ))
    }

    fun sendStep(instruction: String, maneuver: String, distance: Double) {
        broadcast(ProtocolCodec.encodeStep(StepMessage(instruction, maneuver, distance)))
    }

    fun sendStepsList() {
        val nav = navigationManager ?: return
        if (nav.steps.isEmpty()) return
        val stepInfos = nav.steps.map { StepInfo(it.instruction, it.maneuver, it.distance) }
        broadcast(ProtocolCodec.encodeStepsList(StepsListMessage(stepInfos, nav.currentStepIndex)))
    }

    fun sendRoute(waypoints: List<Waypoint>, totalDistance: Double, totalDuration: Double) {
        broadcast(ProtocolCodec.encodeRoute(RouteMessage(waypoints, totalDistance, totalDuration)))
    }

    private fun resendCachedState(writer: BufferedWriter) {
        try {
            cachedSettings?.let {
                writer.write(ProtocolCodec.encodeSettings(it)); writer.newLine(); writer.flush()
                Log.i(TAG, "Re-sent settings to new client")
            }
            cachedWifiCreds?.let {
                writer.write(ProtocolCodec.encodeWifiCreds(it)); writer.newLine(); writer.flush()
                Log.i(TAG, "Re-sent wifi creds to new client")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to re-send cached state: ${e.message}")
        }
    }

    private fun broadcast(json: String) {
        val dead = mutableListOf<BufferedWriter>()
        for (writer in clients) {
            try {
                writer.write(json)
                writer.newLine()
                writer.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Client write failed", e)
                dead.add(writer)
            }
        }
        clients.removeAll(dead.toSet())
    }

    /**
     * Encode + broadcast one sport_state message from [snap] and log the line
     * — the logcat verification surface for the ~1Hz stream (REC-07).
     * sport_state carries metrics only, never coordinates (threat T-04-01).
     */
    private fun broadcastSportState(snap: MetricsSnapshot) {
        val json = ProtocolCodec.encodeSportState(
            SportStateMessage(
                elapsedMs = snap.elapsedMs,
                movingMs = snap.movingMs,
                distanceM = snap.distanceM,
                currentSpeedMps = snap.currentSpeedMps,
                avgPaceMsPerKm = snap.avgPaceMsPerKm,
                sessionState = snap.sessionState,
                sport = snap.sport
            )
        )
        broadcast(json)
        Log.d(TAG, "sport_state $json")
    }

    private fun sendToClient(writer: BufferedWriter, json: String) {
        try {
            writer.write(json)
            writer.newLine()
            writer.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Send to client failed: ${e.message}")
        }
    }

    private fun handleTileRequest(z: Int, x: Int, y: Int, id: String, writer: BufferedWriter) {
        tileExecutor.execute {
            try {
                if (!running) return@execute
                var data: String? = null

                // Check disk cache first
                val cached = diskTileCache?.get(z, x, y)
                if (cached != null) {
                    data = Base64.getEncoder().encodeToString(cached)
                } else {
                    for (template in TILE_URLS) {
                        try {
                            val url = URL(String.format(template, z, x, y))
                            val conn = url.openConnection() as HttpURLConnection
                            conn.setRequestProperty("User-Agent", USER_AGENT)
                            conn.connectTimeout = 8000
                            conn.readTimeout = 8000
                            if (conn.responseCode == 200) {
                                val bytes = readBounded(conn.inputStream, MAX_TILE_BYTES)
                                conn.disconnect()
                                if (bytes.isNotEmpty()) {
                                    data = Base64.getEncoder().encodeToString(bytes)
                                    diskTileCache?.put(z, x, y, bytes)
                                }
                                break
                            }
                            conn.disconnect()
                        } catch (e: Exception) {
                            Log.w(TAG, "Tile fetch $id failed: ${e.message}")
                        }
                    }
                }

                if (!running) return@execute
                if (!clients.contains(writer)) return@execute
                val resp = ProtocolCodec.encodeTileResp(TileResponseMessage(id = id, data = data))
                sendToClient(writer, resp)
            } catch (t: Throwable) {
                Log.e(TAG, "Tile handle $id error", t)
            }
        }
    }

    private fun readBounded(stream: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(maxBytes)
        val buf = ByteArray(8192.coerceAtMost(maxBytes))
        var total = 0
        var n: Int
        while (total < maxBytes) {
            n = stream.read(buf, 0, (buf.size).coerceAtMost(maxBytes - total))
            if (n == -1) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun runClientReader(session: ClientSession) {
        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(session.socket.inputStream, Charsets.UTF_8))
                while (running) {
                    val line = reader.readLine() ?: break
                    if (line.length > 1024) continue
                    try {
                        val parsed = ProtocolCodec.decode(line)
                        when (parsed) {
                            is ParsedMessage.TileReq -> handleTileRequest(
                                parsed.msg.z, parsed.msg.x, parsed.msg.y, parsed.msg.id, session.writer
                            )
                            else -> { /* ignore other inbound */ }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Client message parse failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Client reader ended: ${e.message}")
            } catch (t: Throwable) {
                Log.e(TAG, "Client reader error", t)
            } finally {
                try { session.socket.close() } catch (_: Exception) {}
                clientSessions.remove(session)
                clients.remove(session.writer)
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothServer() {
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter
        if (adapter == null) {
            Log.e(TAG, "Bluetooth not available")
            return
        }

        fun acceptLoop(socket: BluetoothServerSocket, label: String) {
            Thread {
                Log.i(TAG, "$label SPP server listening on UUID $SPP_UUID")
                while (running) {
                    try {
                        val client: BluetoothSocket = socket.accept()
                        val addr = try { client.remoteDevice.address } catch (_: Exception) { "unknown" }
                        Log.i(TAG, "$label client connected: $addr")
                        val writer = BufferedWriter(OutputStreamWriter(client.outputStream, Charsets.UTF_8))
                        val session = ClientSession(client, writer)
                        clientSessions.add(session)
                        clients.add(writer)
                        resendCachedState(writer)
                        runClientReader(session)
                    } catch (e: Exception) {
                        if (running) Log.w(TAG, "$label accept failed: ${e.message}")
                    }
                }
            }.start()
        }

        try {
            serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
            acceptLoop(serverSocket!!, "Insecure")
        } catch (e: Exception) {
            Log.e(TAG, "Insecure server failed: ${e.message}")
        }

        try {
            val secureSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME + "_S", SPP_UUID)
            acceptLoop(secureSocket, "Secure")
        } catch (e: Exception) {
            Log.w(TAG, "Secure server failed (insecure already running): ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_INTERVAL_MS / 2)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                // Iterate every fix (oldest→newest): taking only the newest fix
                // would drop sibling fixes in multi-location results and
                // undercount recorded distance (RESEARCH Pitfall 5).
                for (loc in result.locations) onLocationUpdate(loc)
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient!!.requestLocationUpdates(request, locationCallback!!, android.os.Looper.getMainLooper())
        }
    }

    private fun onLocationUpdate(loc: Location) {
        lastLat = loc.latitude
        lastLng = loc.longitude
        val distToNext = navigationManager?.getDistanceToNextStep(loc.latitude, loc.longitude) ?: -1.0
        val speedLimit = speedLimitClient.getCachedSpeedLimit(loc.latitude, loc.longitude)
        broadcast(ProtocolCodec.encodeState(
            StateMessage(loc.latitude, loc.longitude, loc.bearing, loc.speed, loc.accuracy,
                speedLimitKmh = speedLimit, distToNextStep = distToNext)
        ))
        navigationManager?.onLocationUpdate(loc.latitude, loc.longitude)
        for (consumer in locationConsumers) consumer.onLocationUpdate(loc)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, HudApplication.CHANNEL_ID)
            .setContentTitle("Rokid HUD Active")
            .setContentText("Streaming to glasses")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi).setOngoing(true).build()
    }

    /**
     * Live recording notification (REC-05): "Recording — {distance}" text with
     * elapsed rendered by the SYSTEM chronometer, which ticks without any
     * notify() calls (RESEARCH Pattern 8). Reuses the shared NOTIFICATION_ID
     * and channel; the static "Streaming to glasses" form is restored on stop.
     * Shows activity metrics only — never location (threat T-04-02).
     */
    private fun buildRecordingNotification(snap: MetricsSnapshot, overrideText: String? = null): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, HudApplication.CHANNEL_ID)
            .setContentTitle("Rokid HUD — Recording")
            .setContentText(overrideText ?: "Recording — ${formatRecordingDistance(snap.distanceM)}")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis() - snap.elapsedMs)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    /** Update the shared FGS notification in place (ticker-throttled — Pitfall 10). */
    private fun notifyRecording(snap: MetricsSnapshot, overrideText: String? = null) {
        try {
            lastNotifUpdateMs = SystemClock.elapsedRealtime()
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildRecordingNotification(snap, overrideText))
        } catch (e: Exception) {
            Log.w(TAG, "Recording notification update failed: ${e.message}")
        }
    }

    /**
     * L1 staleness surface (REC-05 >30s warning): swap the recording
     * notification text to a GPS-lost message. Runs on the main looper (the
     * watchdog's L1 Handler). Stamping the shared notification throttle via
     * notifyRecording keeps the warning visible for the full ≥10s window; the
     * next ticker refresh naturally restores the normal text once fixes
     * resume — no extra state machine, and while silence persists the 15s
     * staleness chain re-asserts the warning.
     */
    private fun onRecordingStale(ageMs: Long) {
        val asm = activitySessionManager ?: return
        Log.w(TAG, "Recording GPS signal lost for ${ageMs / 1000}s — surfacing warning")
        notifyRecording(asm.currentSnapshot(), "Recording — GPS signal lost (${ageMs / 1000}s)")
    }

    /**
     * Distance text honoring the cached settings' imperial flag
     * ([cachedSettings] — the same field re-sent to new BT clients); metric
     * fallback when settings were never sent or formatting fails.
     */
    private fun formatRecordingDistance(meters: Double): String = try {
        if (cachedSettings?.useImperial == true) {
            String.format("%.2f mi", meters / 1609.344)
        } else {
            String.format("%.2f km", meters / 1000.0)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Distance format failed: ${e.message}")
        String.format("%.2f km", meters / 1000.0)
    }
}
