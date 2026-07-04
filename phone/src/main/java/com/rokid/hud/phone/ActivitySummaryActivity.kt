package com.rokid.hud.phone

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.rokid.hud.phone.strava.StravaAuthManager
import com.rokid.hud.phone.strava.StravaTokenStore
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
 * the recorded route on an osmdroid map. The one-tap Strava upload (button +
 * status) is wired in Task 2.
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

    private val auth: StravaAuthManager by lazy { StravaAuthManager(this, StravaTokenStore(this)) }

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
            val data = store.readSession(id)
            if (data == null) {
                runOnUiThread {
                    Toast.makeText(this, "Activity not found", Toast.LENGTH_SHORT).show()
                    finish()
                }
                return@Thread
            }
            sessionData = data
            runOnUiThread { renderMetrics(data) }
            renderRoute(data)
            refreshUploadAvailability(data)
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
     * BLOCKING ESP read) and sets the Upload button hint. In Task 1 the button
     * stays disabled with its "Connect Strava first" hint when not connected;
     * Task 2 wires the one-tap upload driver on the connected click.
     */
    private fun refreshUploadAvailability(data: SessionData) {
        val connected = auth.isConnected()
        runOnUiThread {
            if (data.stravaUploaded) {
                btnUpload.isEnabled = false
                uploadStatus.text = "Already uploaded to Strava"
            } else if (!connected) {
                btnUpload.isEnabled = false
                uploadStatus.text = "Connect Strava first"
            }
        }
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
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }
}
