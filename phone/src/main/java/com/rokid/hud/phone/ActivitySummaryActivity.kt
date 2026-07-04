package com.rokid.hud.phone

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.rokid.hud.phone.strava.GpxWriter
import com.rokid.hud.phone.strava.PollOutcome
import com.rokid.hud.phone.strava.PollResult
import com.rokid.hud.phone.strava.StartOutcome
import com.rokid.hud.phone.strava.StartResult
import com.rokid.hud.phone.strava.StravaAuthManager
import com.rokid.hud.phone.strava.StravaTokenStore
import com.rokid.hud.phone.strava.StravaUploader
import com.rokid.hud.phone.strava.UploadState
import com.rokid.hud.phone.strava.driveUpload
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * The activity-summary screen (UPL-01/02/03): shown when a recording is finished
 * and reopened from the history list. Launched with the session id ONLY
 * ([EXTRA_SESSION_ID]) — never the trackPoints, which on a real ride are
 * thousands of points and would throw TransactionTooLargeException through the
 * intent (05-RESEARCH Open Q2 locked decision). The Activity reads the finalized
 * SessionData from disk on a background Thread.
 *
 * Renders total time, moving time, distance, avg speed (read from SessionData —
 * never recomputed), avg pace (DERIVED via SummaryMath), sport, start time, and
 * the recorded route on an osmdroid map. One tap on Upload generates GPX, guards
 * validity, POSTs + polls Strava on a Thread driven by the pure Plan-01
 * driveUpload state machine, and cycles the button through the UploadState
 * variants; the on-disk JSON is written back ONLY on success (Pitfall 4 / UPL-03).
 *
 * IMPERIAL FLAG: read from getSharedPreferences("MainActivity", MODE_PRIVATE) —
 * the SAME activity-local store MainActivity writes via Activity.getPreferences
 * (which maps to the file named by getLocalClassName() = "MainActivity"). An
 * app-wide getSharedPreferences would always read the default `false` (metric)
 * and show wrong units.
 */
class ActivitySummaryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ActivitySummary"
        const val EXTRA_SESSION_ID = "session_id"
        private const val UPLOAD_DEADLINE_MS = 120_000L
        // MainActivity persists the imperial toggle via Activity.getPreferences,
        // whose backing file is getLocalClassName() = "MainActivity".
        private const val MAIN_PREFS = "MainActivity"
        private const val PREF_IMPERIAL = "use_imperial"
    }

    private lateinit var mapView: MapView
    private lateinit var btnUpload: Button
    private lateinit var uploadStatus: TextView

    private var sessionId: String? = null
    private var sessionData: SessionData? = null

    // Set true in onDestroy so an in-flight upload poll loop abandons the poll
    // (folded into the driveUpload deadline predicate — a destroyed screen stops).
    @Volatile
    private var cancelled = false

    private val auth: StravaAuthManager by lazy { StravaAuthManager(this, StravaTokenStore(this)) }
    private val uploader: StravaUploader by lazy { StravaUploader(auth) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_activity_summary)

        val id = intent.getStringExtra(EXTRA_SESSION_ID)
        if (id.isNullOrEmpty()) {
            Toast.makeText(this, "No activity to show", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        sessionId = id

        mapView = findViewById(R.id.summaryMap)
        btnUpload = findViewById(R.id.btnUploadStrava)
        uploadStatus = findViewById(R.id.uploadStatusText)
        findViewById<Button>(R.id.btnSummaryDone).setOnClickListener { finish() }

        // osmdroid is initialized app-wide in HudApplication.onCreate — no re-init.
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        mapView.setMultiTouchControls(true)

        // Read the finalized session off the main thread (multi-thousand-point JSON).
        Thread {
            val store = SessionStore(File(filesDir, "activities"))
            // WR-01: the primary finish→summary flow launches this Activity in the
            // same synchronous block that calls service.stopRecording(), but the
            // finalize (flushLocations → finalizeAsync on the store's serial
            // executor) completes ~1-2s LATER — the {id}.json file may not exist
            // yet on first read. Do NOT make stopRecording synchronous (flushing
            // is async IPC; blocking the main thread is worse). Instead retry the
            // disk read (~3s budget) so the read window covers the finalize lag.
            // Recovery from History is always available if the budget is exhausted.
            var data: SessionData? = null
            repeat(15) {
                data = store.readSession(id)
                if (data != null) return@repeat
                try { Thread.sleep(200) } catch (_: InterruptedException) {}
            }
            val loaded = data
            if (loaded == null) {
                runOnUiThread {
                    Toast.makeText(this, "Activity not found", Toast.LENGTH_SHORT).show()
                    finish()
                }
                return@Thread
            }
            sessionData = loaded
            runOnUiThread { renderMetrics(loaded) }
            renderRoute(loaded)
            refreshUploadAvailability(loaded)
        }.start()
    }

    private fun isImperial(): Boolean =
        getSharedPreferences(MAIN_PREFS, Context.MODE_PRIVATE).getBoolean(PREF_IMPERIAL, false)

    /** Renders all metric TextViews on the main thread. */
    private fun renderMetrics(data: SessionData) {
        val imperial = isImperial()
        findViewById<TextView>(R.id.summaryTotalTime).text = SportFormat.formatElapsed(data.elapsedMs)
        // Moving time is the REC-02 metric finally surfaced (UPL-01).
        findViewById<TextView>(R.id.summaryMovingTime).text = SportFormat.formatElapsed(data.movingMs)
        findViewById<TextView>(R.id.summaryDistance).text = SportFormat.formatDist(data.distanceM, imperial)
        // Avg speed is read straight from SessionData (moving-based — Pitfall 5), never recomputed.
        findViewById<TextView>(R.id.summaryAvgSpeed).text = SportFormat.formatSpeed(data.avgSpeedMps, imperial)
        // Avg pace is DERIVED (not persisted — Pitfall 6).
        val paceMsPerKm = SummaryMath.avgPaceMsPerKm(data.movingMs, data.distanceM)
        findViewById<TextView>(R.id.summaryAvgPace).text = SportFormat.formatPace(paceMsPerKm, imperial)
        findViewById<TextView>(R.id.summarySport).text = sportLabel(data.sport)
        findViewById<TextView>(R.id.summaryStartTime).text = prettyStart(data.startTime)
    }

    /**
     * Draws the recorded route on the map with the Polyline + BoundingBox-in-post
     * idiom (osmdroid returns a zero-size view before layout — Pitfall 5). Track
     * points are mapped defensively: any non-finite lat/lng is skipped. Runs off
     * the main thread; the overlay mutation + zoom are posted to the map.
     */
    private fun renderRoute(data: SessionData) {
        val geoPoints = data.trackPoints
            .filter { it.lat.isFinite() && it.lng.isFinite() }
            .map { GeoPoint(it.lat, it.lng) }
        runOnUiThread {
            mapView.overlays.removeIf { it is Polyline }
            if (geoPoints.isEmpty()) {
                // No valid track — leave the empty map as a placeholder.
                mapView.invalidate()
                return@runOnUiThread
            }
            val line = Polyline().apply {
                outlinePaint.color = Color.parseColor("#FC5200")
                outlinePaint.strokeWidth = 12f
                outlinePaint.isAntiAlias = true
                setPoints(geoPoints)
            }
            mapView.overlays.add(line)
            val box = BoundingBox.fromGeoPoints(geoPoints)
            // Defer the fit until the map is laid out (Pitfall 5).
            mapView.post {
                mapView.zoomToBoundingBox(box, false)
                mapView.invalidate()
            }
        }
    }

    /**
     * Evaluates Strava connection on a background thread (isConnected is a
     * BLOCKING ESP read) and enables/disables the Upload button. When connected,
     * the click wires the one-tap upload; when not, the button is disabled with
     * the "Connect Strava first" hint.
     */
    private fun refreshUploadAvailability(data: SessionData) {
        val connected = auth.isConnected()
        runOnUiThread {
            if (data.stravaUploaded) {
                btnUpload.isEnabled = false
                uploadStatus.text = "Already uploaded to Strava"
            } else if (connected) {
                btnUpload.isEnabled = true
                btnUpload.text = "Upload to Strava"
                uploadStatus.text = ""
                btnUpload.setOnClickListener { startUpload(data) }
            } else {
                btnUpload.isEnabled = false
                uploadStatus.text = "Connect Strava first"
            }
        }
    }

    /**
     * Runs the one-tap upload on a Thread: build GPX → validity guard → POST →
     * poll, all interpreted by the pure Plan-01 [driveUpload]. The 2s poll
     * spacing and the 120s deadline live here (Wave 3 owns timing; the driver is
     * pure). The button is disabled on click to guard against a double-tap; the
     * write-back to disk fires ONLY in driveUpload's success branch (Pitfall 4 /
     * UPL-03). Never rethrows — the local file is the source of truth.
     */
    private fun startUpload(data: SessionData) {
        btnUpload.isEnabled = false
        val deadline = System.currentTimeMillis() + UPLOAD_DEADLINE_MS
        Thread {
            driveUpload(
                start = {
                    val gpx = GpxWriter.write(data.trackPoints, data.sport, data.startTime)
                    if (!GpxWriter.isValidForUpload(gpx)) {
                        // Fail fast — no network cost on an unusable recording.
                        return@driveUpload StartOutcome.Failed("Recording has no valid track to upload")
                    }
                    when (val r = uploader.startUpload(
                        gpx, defaultName(data), data.id, GpxWriter.sportType(data.sport)
                    )) {
                        is StartResult.Started -> StartOutcome.Started(r.idStr)
                        is StartResult.RateLimited -> StartOutcome.RateLimited
                        is StartResult.Failed -> StartOutcome.Failed(r.message)
                    }
                },
                poll = { idStr ->
                    // The 2s spacing lives here (Wave 3 owns timing; the driver is pure).
                    Thread.sleep(2_000)
                    when (val p = uploader.poll(idStr)) {
                        is PollResult.Ready -> PollOutcome.Ready(p.activityId)
                        is PollResult.Duplicate -> PollOutcome.Duplicate(p.activityId)
                        is PollResult.Processing -> PollOutcome.Processing
                        is PollResult.Error -> PollOutcome.Error(p.message)
                    }
                },
                isDeadlineReached = { cancelled || System.currentTimeMillis() >= deadline },
                emit = { st -> runOnUiThread { renderUploadState(st, data) } },
                // Write-back ONLY on success (Plan-01 atomic add-only; UPL-03).
                onSuccess = { activityId ->
                    SessionStore(File(filesDir, "activities")).updateUploadState(data.id, activityId)
                }
            )
        }.start()
    }

    /**
     * Maps a [UploadState] emission to the status text + Upload button. Terminal
     * failure/pending/rate-limited states re-enable the button as "Retry" (the
     * same untouched local JSON re-uploads; duplicate recovery makes the re-POST
     * idempotent — Pitfall 4 / UPL-03). Done offers "View on Strava".
     */
    private fun renderUploadState(state: UploadState, data: SessionData) {
        when (state) {
            is UploadState.Uploading -> {
                btnUpload.isEnabled = false
                uploadStatus.text = "Uploading…"
            }
            is UploadState.Processing -> {
                btnUpload.isEnabled = false
                uploadStatus.text = "Strava is processing…"
            }
            is UploadState.Done -> {
                uploadStatus.text = "Uploaded ✓ — View on Strava"
                btnUpload.isEnabled = true
                btnUpload.text = "View on Strava"
                btnUpload.setOnClickListener { openStravaActivity(state.activityId) }
            }
            is UploadState.Failed -> {
                uploadStatus.text = state.message
                setRetry(data)
            }
            is UploadState.RateLimited -> {
                uploadStatus.text = "Strava busy — retry shortly"
                setRetry(data)
            }
            is UploadState.Pending -> {
                uploadStatus.text = "Upload pending — retry later"
                setRetry(data)
            }
        }
    }

    /** Re-arms the button as a Retry that re-runs the upload against the untouched JSON. */
    private fun setRetry(data: SessionData) {
        btnUpload.isEnabled = true
        btnUpload.text = "Retry"
        btnUpload.setOnClickListener { startUpload(data) }
    }

    /** Opens the uploaded activity on Strava (numeric id only — no user data in the URL). */
    private fun openStravaActivity(activityId: Long) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.strava.com/activities/$activityId"))
            )
        } catch (e: Exception) {
            Log.w(TAG, "Open Strava activity failed: ${e.message}")
            Toast.makeText(this, "Could not open Strava", Toast.LENGTH_SHORT).show()
        }
    }

    /** A sport+date default upload title — no title editing in v1 (deferred). */
    private fun defaultName(data: SessionData): String {
        val label = sportLabel(data.sport)
        val date = data.startTime.take(10) // ISO date prefix (yyyy-MM-dd)
        return if (date.isNotEmpty()) "$label • $date" else label
    }

    private fun sportLabel(sport: String): String = when (sport) {
        "run" -> "Run"
        "ride" -> "Ride"
        else -> sport.replaceFirstChar { it.uppercase() }
    }

    /** Best-effort prettify: "2026-07-03T09:15:00Z" → "2026-07-03 09:15". Falls back to raw. */
    private fun prettyStart(startTime: String): String {
        if (startTime.length < 16) return startTime
        val date = startTime.take(10)
        val time = startTime.substring(11, 16)
        return "$date $time"
    }

    override fun onResume() {
        super.onResume()
        // WR-02: the null/empty-session-id guard in onCreate finish()es BEFORE
        // mapView is assigned via findViewById, but the lifecycle still runs
        // onResume before teardown — guard against the uninitialized lateinit.
        if (::mapView.isInitialized) mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        // WR-02: same uninitialized-property guard as onResume (null-id path).
        if (::mapView.isInitialized) mapView.onPause()
    }

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
    }
}
