package com.rokid.hud.phone

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rokid.hud.phone.strava.CallbackResult
import com.rokid.hud.phone.strava.GpxParser
import com.rokid.hud.phone.strava.GpxResult
import com.rokid.hud.phone.strava.RouteDownsampler
import com.rokid.hud.phone.strava.RoutesResult
import com.rokid.hud.phone.strava.StravaApiClient
import com.rokid.hud.phone.strava.StravaAuthManager
import com.rokid.hud.phone.strava.StravaCallbackActivity
import com.rokid.hud.phone.strava.StravaRoute
import com.rokid.hud.phone.strava.StravaTokenStore
import com.rokid.hud.shared.protocol.Waypoint
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val RC_PERMISSIONS = 100
        private const val RC_WIFI_PERM = 101
        private const val RC_PICK_APK = 102
        private const val RC_BG_LOCATION = 103
        private const val PREF_TTS = "tts_enabled"
        private const val PREF_IMPERIAL = "use_imperial"
        private const val PREF_MINI_MAP = "use_mini_map"
        private const val PREF_MINI_MAP_STYLE = "mini_map_style"
        private const val PREF_STREAM_NOTIFICATIONS = "stream_notifications"
        private const val PREF_SHOW_FULL_ROUTE_STEPS = "show_full_route_steps"
        private const val PREF_TURN_ALERT = "show_turn_alert"
        private const val PREF_TILE_CACHE_SIZE_MB = "tile_cache_size_mb"
        private const val PREF_SHOW_SPEED = "show_speed"
        private const val PREF_SHOW_SPEED_LIMIT = "show_speed_limit"
        private const val PREFS_GLASSES = "rokid_glasses"
        private const val PREFS_HUD = "rokid_hud_prefs"
        // Recording prefs live in PREFS_HUD (app-wide) so the service can read them
        private const val PREF_SPORT_TYPE = "sport_type"
        private const val PREF_BG_LOC_ASKED = "bg_loc_asked"
        // Process-scoped: battery-exemption prompt shows at most once per app launch
        private var recordingExemptionPromptShown = false
    }

    private lateinit var btAudioRouter: BluetoothAudioRouter

    // Header & status
    private lateinit var btnStart: Button
    private lateinit var glassesStatusDot: View
    private lateinit var glassesStatusText: TextView
    private lateinit var btnScanGlasses: Button
    private lateinit var btnUpdateGlassesApp: Button
    private lateinit var statusText: TextView

    // Navigate section
    private lateinit var searchInput: EditText
    private lateinit var btnSearch: ImageButton
    private lateinit var btnShowSaved: Button
    private lateinit var searchResults: ListView
    private lateinit var routeCard: LinearLayout
    private lateinit var routeDestText: TextView
    private lateinit var routeInfoText: TextView
    private lateinit var btnNavigate: Button
    private lateinit var btnSavePlace: Button

    // Live directions + map (shown only when navigating)
    private lateinit var navStatus: LinearLayout
    private lateinit var navMapView: MapView
    private lateinit var navInstructionText: TextView
    private lateinit var navDistanceText: TextView
    private lateinit var navFullStepsPanel: LinearLayout
    private lateinit var navFullStepsList: ListView
    private lateinit var switchShowFullRouteSteps: Switch
    private lateinit var btnStopNav: Button

    // Recording card
    private lateinit var sportToggleRow: LinearLayout
    private lateinit var btnSportRide: Button
    private lateinit var btnSportRun: Button
    private lateinit var btnStartRecording: Button
    private lateinit var recordingPanel: LinearLayout
    private lateinit var recBadge: TextView
    private lateinit var recElapsedText: TextView
    private lateinit var recDistanceText: TextView
    private lateinit var recSpeedText: TextView
    private lateinit var recPaceText: TextView
    private lateinit var btnStopRecording: Button

    // Strava card
    private lateinit var stravaCard: LinearLayout
    private lateinit var stravaStatusText: TextView
    private lateinit var stravaSetupHint: TextView
    private lateinit var btnConnectStrava: Button
    private lateinit var btnDisconnectStrava: Button

    // My Strava routes card (Strava-connected-gated route list + import preview)
    private lateinit var stravaRoutesCard: LinearLayout
    private lateinit var stravaRoutesProgress: ProgressBar
    private lateinit var stravaRoutesEmpty: TextView
    private lateinit var stravaRoutesList: ListView
    private lateinit var stravaRoutePreviewPanel: LinearLayout
    private lateinit var stravaRoutePreviewName: TextView
    private lateinit var stravaRoutePreviewInfo: TextView
    private lateinit var stravaRoutePreviewMap: MapView
    private lateinit var btnStartRouteNav: Button

    // Settings
    private lateinit var switchUnits: Switch
    private lateinit var switchTts: Switch
    private lateinit var switchMiniMap: Switch
    private lateinit var miniMapStyleGroup: RadioGroup
    private lateinit var radioStrip: RadioButton
    private lateinit var radioSplit: RadioButton
    private lateinit var switchWifiShare: Switch
    private lateinit var wifiShareStatus: TextView
    private lateinit var wifiInfoCard: LinearLayout
    private lateinit var wifiSsidText: TextView
    private lateinit var wifiPassText: TextView
    private lateinit var wifiClientsText: TextView
    private lateinit var hotspotSsidInput: EditText
    private lateinit var hotspotPassInput: EditText
    private lateinit var btnSendHotspotToGlasses: Button
    private lateinit var notifStatusText: TextView
    private lateinit var btnNotifAccess: Button
    private lateinit var switchStreamNotifications: Switch
    private lateinit var switchTurnAlert: Switch
    private lateinit var switchShowSpeed: Switch
    private lateinit var switchShowSpeedLimit: Switch
    private lateinit var spinnerCacheSize: Spinner
    private lateinit var btnClearCache: Button
    private lateinit var cacheSizeText: TextView

    // Managers
    private lateinit var wifiShareManager: WifiShareManager
    private lateinit var savedPlacesManager: SavedPlacesManager
    private lateinit var stravaTokenStore: StravaTokenStore
    private lateinit var stravaAuthManager: StravaAuthManager
    private lateinit var stravaApiClient: StravaApiClient

    // State
    private var service: HudStreamingService? = null
    private var bound = false
    private var streaming = false
    private var searchResultsList: List<SearchResult> = emptyList()
    private var savedPlacesList: List<SavedPlace> = emptyList()
    private var selectedDest: SearchResult? = null
    private var showingSaved = false
    private var currentRouteWaypoints: List<Waypoint> = emptyList()
    private var fullRouteSteps: List<NavigationStep> = emptyList()

    // My-Strava-routes state
    private var stravaRoutesList_data: List<StravaRoute> = emptyList()
    private var stravaRoutesLoaded = false
    // The imported route held for START NAVIGATION (Task 2 sets it, Task 3 consumes it)
    private var pendingRoute: RouteResult? = null
    private var pendingFollowRoute = false

    private val navMapHandler = Handler(Looper.getMainLooper())
    private val navMapUpdateRunnable = object : Runnable {
        override fun run() {
            if (!::navMapView.isInitialized || navStatus.visibility != View.VISIBLE) return
            val (lat, lng) = service?.getLastLocation() ?: return
            navMapView.controller.setCenter(GeoPoint(lat, lng))
            navMapView.controller.setZoom(17.0)
            navMapHandler.postDelayed(this, 2000L)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as HudStreamingService.LocalBinder).getService()
            bound = true
            streaming = true
            service?.uiCallback = navCallback
            service?.setMetricsListener(recMetricsListener)
            syncRecordingUiFromService()
            sendCurrentSettings()
            updateStreamingUi()
            updateCacheSizeText()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null; bound = false; streaming = false
            updateStreamingUi()
        }
    }

    private val navCallback = object : NavigationCallback {
        override fun onRouteCalculated(waypoints: List<Waypoint>, totalDistance: Double, totalDuration: Double, steps: List<NavigationStep>) {
            runOnUiThread {
                currentRouteWaypoints = waypoints
                fullRouteSteps = steps
                routeInfoText.text = "${formatDist(totalDistance)}  ·  ${formatTime(totalDuration)}"
                showNavStatus()
                updateNavMap()
                updateFullStepsList()
            }
        }
        override fun onStepChanged(instruction: String, maneuver: String, distance: Double) {
            runOnUiThread {
                navInstructionText.text = instruction
                navDistanceText.text = formatDist(distance)
                speakNavInstruction(instruction, distance)
            }
        }
        override fun onNavigationError(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Navigation error: $message", Toast.LENGTH_LONG).show()
                btnNavigate.isEnabled = true
                routeInfoText.text = "Route failed — try again"
            }
        }
        override fun onArrived() {
            runOnUiThread {
                navInstructionText.text = "You have arrived!"
                navDistanceText.text = ""
                speakNavInstruction("You have arrived!", 0.0)
                Toast.makeText(this@MainActivity, "Arrived at destination!", Toast.LENGTH_SHORT).show()
            }
        }
        override fun onRerouting() {
            runOnUiThread {
                navInstructionText.text = "Recalculating route..."
                navDistanceText.text = ""
            }
        }
    }

    // 1Hz live metrics from the service ticker (same callback that feeds the BT broadcast)
    private val recMetricsListener = object : MetricsListener {
        override fun onMetrics(snapshot: MetricsSnapshot) {
            runOnUiThread {
                recElapsedText.text = formatElapsed(snapshot.elapsedMs)
                recDistanceText.text = formatDist(snapshot.distanceM)
                recSpeedText.text = formatSpeed(snapshot.currentSpeedMps)
                recPaceText.text = formatPace(snapshot.avgPaceMsPerKm)
                if (snapshot.sessionState == "finished") updateRecordingUi(false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        savedPlacesManager = SavedPlacesManager(this)
        btAudioRouter = BluetoothAudioRouter(applicationContext)
        btAudioRouter.init()
        stravaTokenStore = StravaTokenStore(applicationContext)
        stravaAuthManager = StravaAuthManager(applicationContext, stravaTokenStore)
        stravaApiClient = StravaApiClient(stravaAuthManager)

        bindViews()
        setupWifiManager()
        setupListeners()
        updateGlassesStatus()
        updateNotifStatus()
        refreshStravaCard()
        // Cold-start callback path (Pitfall 3): if the process died under the
        // Custom Tab, StravaCallbackActivity's forward recreates us and the
        // callback arrives via onCreate's intent instead of onNewIntent.
        handleStravaCallbackIntent(intent)
    }

    /** Warm callback path: CLEAR_TOP|SINGLE_TOP forward lands here while alive. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleStravaCallbackIntent(intent)
        }
    }

    /**
     * Routes a forwarded rokidhud://callback into the Wave-2 validated pipeline.
     * Never logs the URI (it carries the authorization code + state, T-03-04);
     * all validation happens inside StravaAuthManager.handleCallback (T-03-01).
     */
    private fun handleStravaCallbackIntent(intent: Intent?) {
        if (intent?.action != StravaCallbackActivity.ACTION_STRAVA_CALLBACK) return
        val uri = intent.getStringExtra(StravaCallbackActivity.EXTRA_CALLBACK_URI)
        Log.i(TAG, "Strava callback intent received")
        Thread {
            val result = stravaAuthManager.handleCallback(uri)
            if (result is CallbackResult.Connected) {
                // Authenticated-client proof (ROADMAP Delivers). A null athlete is
                // NOT an auth failure: tokens are already persisted and the
                // Authenticator/proactive refresh recovers on next use.
                val athlete = stravaApiClient.getAthlete()
                Log.i(
                    TAG,
                    if (athlete != null) "GET /athlete ok: connected athlete verified"
                    else "GET /athlete failed post-connect (tokens stored; will retry on next use)"
                )
            }
            runOnUiThread {
                val msg = when (result) {
                    is CallbackResult.Connected -> "Connected to Strava as ${result.athleteName}"
                    CallbackResult.StateMismatch -> "Strava connection rejected (security check failed) — try Connect again"
                    CallbackResult.Denied -> "Strava authorization was declined"
                    CallbackResult.ScopesIncomplete -> "Strava permissions incomplete — reconnect and keep all permission boxes checked"
                    CallbackResult.ExchangeFailed -> "Strava connection failed — check your network and try again"
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refreshStravaCard()
            }
        }.start()
    }

    /**
     * SINGLE writer of the STRAVA card's state. Truth table:
     * keys empty -> setup hint (Pitfall 7, no store read); keys + no tokens ->
     * CONNECT; keys + tokens -> "Connected as {name}" + DISCONNECT. Token-store
     * reads run on a background thread (Pitfall 6: first ESP access pays
     * Keystore + Tink init, potentially hundreds of ms).
     */
    private fun refreshStravaCard() {
        if (BuildConfig.STRAVA_CLIENT_ID.isEmpty() || BuildConfig.STRAVA_CLIENT_SECRET.isEmpty()) {
            stravaStatusText.text = "Not configured"
            stravaSetupHint.visibility = View.VISIBLE
            btnConnectStrava.visibility = View.GONE
            btnDisconnectStrava.visibility = View.GONE
            // Keys missing (Pitfall 7): the routes list can never be connected — hide it.
            stravaRoutesCard.visibility = View.GONE
            return
        }
        Thread {
            val name = stravaAuthManager.connectedAthleteName()
            runOnUiThread {
                if (name != null) {
                    stravaStatusText.text = "Connected as $name"
                    btnConnectStrava.visibility = View.GONE
                    btnDisconnectStrava.visibility = View.VISIBLE
                    // Connected -> reveal the routes list; load once per connect
                    // (idempotent re-reads on resume must not re-fetch every time).
                    stravaRoutesCard.visibility = View.VISIBLE
                    if (!stravaRoutesLoaded) loadStravaRoutes()
                } else {
                    stravaStatusText.text = "Not connected"
                    btnConnectStrava.visibility = View.VISIBLE
                    btnDisconnectStrava.visibility = View.GONE
                    // Not connected -> hide the routes list and reset so a fresh
                    // connect re-fetches (e.g. after a disconnect token wipe).
                    stravaRoutesCard.visibility = View.GONE
                    stravaRoutesLoaded = false
                }
                stravaSetupHint.visibility = View.GONE
            }
        }.start()
    }

    // ── My Strava routes ───────────────────────────────────────────────────

    /**
     * Loads the connected athlete's routes on a background Thread (getRoutes is
     * BLOCKING — StravaApiClient convention), then renders name/distance/elevation
     * rows. Maps the three sealed outcomes to the locked UI states: RateLimited ->
     * the distinct rate-limit toast, Failed -> a generic error toast, empty Success
     * -> "No routes found", non-empty -> the row list. Recording is NEVER started
     * here (REC-01 opt-in — Pitfall UX "auto-start with route").
     */
    private fun loadStravaRoutes() {
        stravaRoutesLoaded = true
        stravaRoutesProgress.visibility = View.VISIBLE
        stravaRoutesEmpty.visibility = View.GONE
        stravaRoutesList.visibility = View.GONE
        Thread {
            val result = stravaApiClient.getRoutes()
            runOnUiThread {
                stravaRoutesProgress.visibility = View.GONE
                when (result) {
                    is RoutesResult.RateLimited -> {
                        // Locked message — distinct from a generic failure (T-04-20).
                        Toast.makeText(this, "Strava rate limit — try again shortly", Toast.LENGTH_LONG).show()
                        // Allow a manual retry (via reconnect / resume) after a limit.
                        stravaRoutesLoaded = false
                    }
                    is RoutesResult.Failed -> {
                        Toast.makeText(this, "Couldn't load Strava routes", Toast.LENGTH_SHORT).show()
                        stravaRoutesLoaded = false
                    }
                    is RoutesResult.Success -> {
                        stravaRoutesList_data = result.routes
                        if (result.routes.isEmpty()) {
                            stravaRoutesEmpty.visibility = View.VISIBLE
                        } else {
                            renderStravaRoutes(result.routes)
                            stravaRoutesList.visibility = View.VISIBLE
                            adjustStravaRoutesListHeight()
                        }
                    }
                }
            }
        }.start()
    }

    /** Populates the route rows: name (bold) + "distance · elevation" (imperial-aware). */
    private fun renderStravaRoutes(routes: List<StravaRoute>) {
        stravaRoutesList.adapter = object : ArrayAdapter<StravaRoute>(this, R.layout.item_strava_route, routes) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.item_strava_route, parent, false)
                val route = getItem(position)
                val icon = view.findViewById<TextView>(R.id.routeIcon)
                val name = view.findViewById<TextView>(R.id.routeName)
                val meta = view.findViewById<TextView>(R.id.routeMeta)
                icon.text = if (route?.type == 2) "🏃" else "🚴"
                name.text = route?.name?.takeIf { it.isNotBlank() } ?: "Untitled route"
                val dist = formatDist(route?.distance ?: 0.0)
                val elev = formatElev(route?.elevationGain ?: 0.0)
                meta.text = "${route?.typeLabel() ?: "Route"}  ·  $dist  ·  $elev"
                return view
            }
        }
    }

    private fun adjustStravaRoutesListHeight() {
        val count = stravaRoutesList.adapter?.count ?: 0
        val maxItems = minOf(count, 6)
        val itemH = (58 * resources.displayMetrics.density).toInt()
        val params = stravaRoutesList.layoutParams
        params.height = maxItems * itemH
        stravaRoutesList.layoutParams = params
    }

    private fun initRoutePreviewMap() {
        stravaRoutePreviewMap.setTileSource(TileSourceFactory.MAPNIK)
        stravaRoutePreviewMap.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        stravaRoutePreviewMap.setMultiTouchControls(true)
        stravaRoutePreviewMap.controller.setZoom(13.0)
    }

    /**
     * Imports the tapped route on a background Thread (network + XML parse off the
     * main thread — T-04-17 DoS: a large/malformed GPX must never parse on the UI
     * thread; the <=200 downsample cap bounds all downstream memory):
     *   exportGpx -> GpxParser.parse -> RouteDownsampler.downsampleForRoute ->
     *   OsrmClient.getRouteVia (follow-route fallback on OSRM failure).
     * On success, previews the route line on the osmdroid map and reveals START
     * NAVIGATION. Does NOT start navigation (preview only, RIMP-04) and NEVER starts
     * recording (REC-01 opt-in — Pitfall UX). The existing search->OSRM path is
     * untouched — this is an additive parallel entry point.
     */
    private fun onStravaRouteSelected(position: Int) {
        if (position >= stravaRoutesList_data.size) return
        val route = stravaRoutesList_data[position]
        val idStr = route.idStr
        if (idStr.isNullOrBlank()) {
            Toast.makeText(this, "Couldn't import route", Toast.LENGTH_SHORT).show()
            return
        }
        val routeLabel = route.name?.takeIf { it.isNotBlank() } ?: "Strava route"
        Toast.makeText(this, "Importing \"$routeLabel\"…", Toast.LENGTH_SHORT).show()
        Thread {
            when (val gpxResult = stravaApiClient.exportGpx(idStr)) {
                is GpxResult.RateLimited -> runOnUiThread {
                    Toast.makeText(this, "Strava rate limit — try again shortly", Toast.LENGTH_LONG).show()
                }
                is GpxResult.Failed -> runOnUiThread {
                    Toast.makeText(this, "Couldn't import route", Toast.LENGTH_SHORT).show()
                }
                is GpxResult.Success -> {
                    val points = GpxParser.parse(gpxResult.gpx)
                    if (points.isEmpty()) {
                        runOnUiThread {
                            Toast.makeText(this, "Route had no track points", Toast.LENGTH_SHORT).show()
                        }
                        return@Thread
                    }
                    val downsampled = RouteDownsampler.downsampleForRoute(points)
                    // Follow-route fallback keeps the flow alive with a non-empty
                    // synthetic step when OSRM fails (Pattern 3 / T-04-12).
                    var followRouteMode = false
                    val result = try {
                        OsrmClient.getRouteVia(downsampled)
                    } catch (e: Exception) {
                        Log.w(TAG, "getRouteVia failed, using follow-route fallback: ${e.message}")
                        followRouteMode = true
                        OsrmClient.buildFollowRouteResult(downsampled)
                    }
                    runOnUiThread { previewImportedRoute(routeLabel, result, followRouteMode) }
                }
            }
        }.start()
    }

    /**
     * Draws the imported route line on the preview map and reveals START NAVIGATION.
     * The bounding-box fit is DEFERRED via [MapView.post] (Pitfall 5): the preview
     * map is freshly made VISIBLE and may not be laid out yet on first import, so an
     * immediate zoomToBoundingBox would no-op with the route off-screen.
     */
    private fun previewImportedRoute(routeLabel: String, result: RouteResult, followRouteMode: Boolean) {
        pendingRoute = result
        pendingFollowRoute = followRouteMode
        currentRouteWaypoints = result.waypoints

        stravaRoutePreviewName.text = routeLabel
        val info = "${formatDist(result.totalDistance)}" +
            if (followRouteMode) "  ·  Follow route (no turn guidance)" else ""
        stravaRoutePreviewInfo.text = info

        stravaRoutePreviewPanel.visibility = View.VISIBLE

        val geoPoints = result.waypoints
            .filter { it.latitude.isFinite() && it.longitude.isFinite() }
            .map { GeoPoint(it.latitude, it.longitude) }
        stravaRoutePreviewMap.overlays.removeIf { it is Polyline }
        if (geoPoints.isNotEmpty()) {
            val line = Polyline().apply {
                outlinePaint.color = Color.parseColor("#FC5200")
                outlinePaint.strokeWidth = 12f
                outlinePaint.isAntiAlias = true
                setPoints(geoPoints)
            }
            stravaRoutePreviewMap.overlays.add(line)
            val box = BoundingBox.fromGeoPoints(geoPoints)
            // Defer the fit until the newly-visible map is laid out (Pitfall 5).
            stravaRoutePreviewMap.post {
                // WR-02: a single-point route (follow-route fallback on a 1-trkpt GPX, or a
                // trivial OSRM geometry) or all-coincident waypoints yield a zero-span
                // BoundingBox; osmdroid's zoomToBoundingBox divides by the lat/lng span and
                // takes log of the ratio, producing Infinity/NaN zoom (route off-screen or a
                // crash inside the tile-scaling math). Fall back to a centered fixed zoom.
                if (geoPoints.size < 2 ||
                    box.latitudeSpan < 1e-6 || box.longitudeSpanWithDateLine < 1e-6) {
                    stravaRoutePreviewMap.controller.setCenter(geoPoints.first())
                    stravaRoutePreviewMap.controller.setZoom(15.0)
                } else {
                    stravaRoutePreviewMap.zoomToBoundingBox(box, false)
                }
                stravaRoutePreviewMap.invalidate()
            }
        }
    }

    /**
     * START NAVIGATION on the imported route (NAVV-01/02 phone side). Mirrors the
     * existing [startNavigation] streaming-bound guard, then hands the pre-computed
     * waypoints + steps to the Plan-03 service passthrough. The existing service
     * navCallback already broadcasts route/step/steps_list to the glasses, so no new
     * broadcast wiring is needed. Recording is NOT started (REC-01 opt-in).
     */
    private fun startImportedRouteNavigation() {
        val route = pendingRoute ?: return
        if (!bound || service == null) {
            Toast.makeText(this, "Start streaming first", Toast.LENGTH_SHORT).show()
            return
        }
        service!!.startNavigationWithRoute(
            route.waypoints,
            route.steps,
            route.totalDistance,
            route.totalDuration,
            pendingFollowRoute
        )
        // Reuse the live-nav status panel; the service callback drives the rest.
        showNavStatus()
    }

    private fun bindViews() {
        btnStart = findViewById(R.id.btnStart)
        glassesStatusDot = findViewById(R.id.glassesStatusDot)
        glassesStatusText = findViewById(R.id.glassesStatusText)
        btnScanGlasses = findViewById(R.id.btnScanGlasses)
        btnUpdateGlassesApp = findViewById(R.id.btnUpdateGlassesApp)
        statusText = findViewById(R.id.statusText)

        searchInput = findViewById(R.id.searchInput)
        btnSearch = findViewById(R.id.btnSearch)
        btnShowSaved = findViewById(R.id.btnShowSaved)
        searchResults = findViewById(R.id.searchResults)
        routeCard = findViewById(R.id.routeCard)
        routeDestText = findViewById(R.id.routeDestText)
        routeInfoText = findViewById(R.id.routeInfoText)
        btnNavigate = findViewById(R.id.btnNavigate)
        btnSavePlace = findViewById(R.id.btnSavePlace)

        navStatus = findViewById(R.id.navStatus)
        navMapView = findViewById(R.id.navMapView)
        navInstructionText = findViewById(R.id.navInstructionText)
        navDistanceText = findViewById(R.id.navDistanceText)
        navFullStepsPanel = findViewById(R.id.navFullStepsPanel)
        navFullStepsList = findViewById(R.id.navFullStepsList)
        switchShowFullRouteSteps = findViewById(R.id.switchShowFullRouteSteps)
        btnStopNav = findViewById(R.id.btnStopNav)
        initNavMap()

        sportToggleRow = findViewById(R.id.sportToggleRow)
        btnSportRide = findViewById(R.id.btnSportRide)
        btnSportRun = findViewById(R.id.btnSportRun)
        btnStartRecording = findViewById(R.id.btnStartRecording)
        recordingPanel = findViewById(R.id.recordingPanel)
        recBadge = findViewById(R.id.recBadge)
        recElapsedText = findViewById(R.id.recElapsedText)
        recDistanceText = findViewById(R.id.recDistanceText)
        recSpeedText = findViewById(R.id.recSpeedText)
        recPaceText = findViewById(R.id.recPaceText)
        btnStopRecording = findViewById(R.id.btnStopRecording)
        updateSportToggleUi()

        switchUnits = findViewById(R.id.switchUnits)
        switchTts = findViewById(R.id.switchTts)
        switchMiniMap = findViewById(R.id.switchMiniMap)
        miniMapStyleGroup = findViewById(R.id.miniMapStyleGroup)
        radioStrip = findViewById(R.id.radioStrip)
        radioSplit = findViewById(R.id.radioSplit)
        switchWifiShare = findViewById(R.id.switchWifiShare)
        wifiShareStatus = findViewById(R.id.wifiShareStatus)
        wifiInfoCard = findViewById(R.id.wifiInfoCard)
        wifiSsidText = findViewById(R.id.wifiSsidText)
        wifiPassText = findViewById(R.id.wifiPassText)
        wifiClientsText = findViewById(R.id.wifiClientsText)
        hotspotSsidInput = findViewById(R.id.hotspotSsidInput)
        hotspotPassInput = findViewById(R.id.hotspotPassInput)
        btnSendHotspotToGlasses = findViewById(R.id.btnSendHotspotToGlasses)
        notifStatusText = findViewById(R.id.notifStatusText)
        btnNotifAccess = findViewById(R.id.btnNotifAccess)
        switchStreamNotifications = findViewById(R.id.switchStreamNotifications)

        switchTts.isChecked = getPreferences(MODE_PRIVATE).getBoolean(PREF_TTS, false)
        switchUnits.isChecked = getPreferences(MODE_PRIVATE).getBoolean(PREF_IMPERIAL, false)
        switchMiniMap.isChecked = getPreferences(MODE_PRIVATE).getBoolean(PREF_MINI_MAP, false)
        val savedStyle = getPreferences(MODE_PRIVATE).getString(PREF_MINI_MAP_STYLE, "strip")
        if (savedStyle == "split") radioSplit.isChecked = true else radioStrip.isChecked = true
        miniMapStyleGroup.visibility = if (switchMiniMap.isChecked) View.VISIBLE else View.GONE
        switchStreamNotifications.isChecked = getSharedPreferences(PREFS_HUD, MODE_PRIVATE).getBoolean(PREF_STREAM_NOTIFICATIONS, true)

        switchTurnAlert = findViewById(R.id.switchTurnAlert)
        switchTurnAlert.isChecked = getSharedPreferences(PREFS_HUD, MODE_PRIVATE).getBoolean(PREF_TURN_ALERT, false)

        switchShowSpeed = findViewById(R.id.switchShowSpeed)
        switchShowSpeed.isChecked = getSharedPreferences(PREFS_HUD, MODE_PRIVATE).getBoolean(PREF_SHOW_SPEED, true)

        switchShowSpeedLimit = findViewById(R.id.switchShowSpeedLimit)
        switchShowSpeedLimit.isChecked = getSharedPreferences(PREFS_HUD, MODE_PRIVATE).getBoolean(PREF_SHOW_SPEED_LIMIT, true)

        spinnerCacheSize = findViewById(R.id.spinnerCacheSize)
        btnClearCache = findViewById(R.id.btnClearCache)
        cacheSizeText = findViewById(R.id.cacheSizeText)
        setupCacheSpinner()

        stravaCard = findViewById(R.id.stravaCard)
        stravaStatusText = findViewById(R.id.stravaStatusText)
        stravaSetupHint = findViewById(R.id.stravaSetupHint)
        btnConnectStrava = findViewById(R.id.btnConnectStrava)
        btnDisconnectStrava = findViewById(R.id.btnDisconnectStrava)

        stravaRoutesCard = findViewById(R.id.stravaRoutesCard)
        stravaRoutesProgress = findViewById(R.id.stravaRoutesProgress)
        stravaRoutesEmpty = findViewById(R.id.stravaRoutesEmpty)
        stravaRoutesList = findViewById(R.id.stravaRoutesList)
        stravaRoutePreviewPanel = findViewById(R.id.stravaRoutePreviewPanel)
        stravaRoutePreviewName = findViewById(R.id.stravaRoutePreviewName)
        stravaRoutePreviewInfo = findViewById(R.id.stravaRoutePreviewInfo)
        stravaRoutePreviewMap = findViewById(R.id.stravaRoutePreviewMap)
        btnStartRouteNav = findViewById(R.id.btnStartRouteNav)
        initRoutePreviewMap()
        stravaRoutesList.setOnItemClickListener { _, _, pos, _ -> onStravaRouteSelected(pos) }
        // Let the route list scroll inside the outer ScrollView
        stravaRoutesList.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        btnStartRouteNav.setOnClickListener { startImportedRouteNavigation() }
    }

    private fun setupWifiManager() {
        wifiShareManager = WifiShareManager(applicationContext)
        wifiShareManager.init()
        wifiShareManager.onStateChanged = { state -> runOnUiThread { updateWifiUi(state) } }
        switchWifiShare.isChecked = wifiShareManager.wasEnabled()
        if (wifiShareManager.wasEnabled()) {
            wifiShareManager.startSharing()
        }
    }

    private fun setupListeners() {
        btnStart.setOnClickListener { checkPermissionsAndStart() }
        btnScanGlasses.setOnClickListener {
            startActivity(Intent(this, DeviceScanActivity::class.java))
        }
        btnUpdateGlassesApp.setOnClickListener { openApkPicker() }

        btnSearch.setOnClickListener { performSearch() }
        searchInput.setOnEditorActionListener { _, _, _ -> performSearch(); true }
        btnShowSaved.setOnClickListener { toggleSavedPlaces() }
        searchResults.setOnItemClickListener { _, _, pos, _ -> onItemSelected(pos) }
        btnNavigate.setOnClickListener { startNavigation() }
        btnSavePlace.setOnClickListener { saveCurrentPlace() }
        btnStopNav.setOnClickListener { stopNavigation() }
        btnSportRide.setOnClickListener { setSportType("ride") }
        btnSportRun.setOnClickListener { setSportType("run") }
        btnStartRecording.setOnClickListener { startRecording() }
        btnStopRecording.setOnClickListener { confirmStopRecording() }
        findViewById<Button>(R.id.btnViewHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        // Let the steps list scroll inside the outer ScrollView
        navFullStepsList.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        switchShowFullRouteSteps.isChecked = getSharedPreferences(PREFS_HUD, MODE_PRIVATE).getBoolean(PREF_SHOW_FULL_ROUTE_STEPS, false)
        switchShowFullRouteSteps.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_HUD, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_FULL_ROUTE_STEPS, isChecked).apply()
            navFullStepsPanel.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) updateFullStepsList()
            sendCurrentSettings()
        }
        btnNotifAccess.setOnClickListener { openNotificationListenerSettings() }

        switchTts.setOnCheckedChangeListener { _, isChecked ->
            getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_TTS, isChecked).apply()
            sendCurrentSettings()
        }

        switchUnits.setOnCheckedChangeListener { _, isChecked ->
            getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_IMPERIAL, isChecked).apply()
            sendCurrentSettings()
        }

        switchMiniMap.setOnCheckedChangeListener { _, isChecked ->
            getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_MINI_MAP, isChecked).apply()
            miniMapStyleGroup.visibility = if (isChecked) View.VISIBLE else View.GONE
            sendCurrentSettings()
        }

        miniMapStyleGroup.setOnCheckedChangeListener { _, checkedId ->
            val style = if (checkedId == R.id.radioSplit) "split" else "strip"
            getPreferences(MODE_PRIVATE).edit().putString(PREF_MINI_MAP_STYLE, style).apply()
            sendCurrentSettings()
        }

        switchStreamNotifications.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_HUD, MODE_PRIVATE).edit().putBoolean(PREF_STREAM_NOTIFICATIONS, isChecked).apply()
            sendCurrentSettings()
        }

        switchTurnAlert.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_HUD, MODE_PRIVATE).edit().putBoolean(PREF_TURN_ALERT, isChecked).apply()
            sendCurrentSettings()
        }

        switchShowSpeed.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_HUD, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_SPEED, isChecked).apply()
            sendCurrentSettings()
        }

        switchShowSpeedLimit.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_HUD, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_SPEED_LIMIT, isChecked).apply()
            sendCurrentSettings()
        }

        switchWifiShare.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) checkWifiPermissionsAndStart() else wifiShareManager.stopSharing()
        }

        btnSendHotspotToGlasses.setOnClickListener { sendHotspotToGlasses() }

        findViewById<Button>(R.id.btnBuyMeACoffee).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Support Rokid Maps")
                .setMessage("If you enjoy using Rokid Maps, consider buying me a coffee! Your support helps keep development going.")
                .setPositiveButton("Open Link") { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/charleshartmann")))
                }
                .setNegativeButton("Maybe Later", null)
                .show()
        }

        btnConnectStrava.setOnClickListener {
            if (!stravaAuthManager.launchAuthorize(this)) {
                Toast.makeText(this, "Cannot start Strava connect — check API keys and browser", Toast.LENGTH_LONG).show()
            }
        }
        btnDisconnectStrava.setOnClickListener {
            // CONTEXT locked: disconnect = local token wipe only, no remote deauthorize in v1.
            Thread {
                stravaAuthManager.disconnect()
                runOnUiThread {
                    Toast.makeText(this, "Disconnected from Strava", Toast.LENGTH_SHORT).show()
                    refreshStravaCard()
                }
            }.start()
        }
        // DEBUG-only AUTH-03 verification hook: forced token refresh + GET /athlete.
        // The coordinator logs "refresh ok expires_at=... rt#=<hex>" — a rt# that
        // differs from the exchange-time rt# is the on-device rotation proof
        // (Wave 4 greps logcat for two distinct rt# values). In release builds the
        // guard short-circuits to false, leaving long-press unconsumed (T-03-07).
        stravaCard.setOnLongClickListener {
            if (!BuildConfig.DEBUG) return@setOnLongClickListener false
            Thread {
                val token = stravaAuthManager.forceRefresh()
                val athlete = if (token != null) stravaApiClient.getAthlete() else null
                runOnUiThread {
                    Toast.makeText(
                        this,
                        when {
                            token == null -> "Force refresh: no tokens / refresh failed"
                            athlete != null -> "Force refresh OK — athlete verified"
                            else -> "Refreshed, but GET /athlete failed"
                        },
                        Toast.LENGTH_LONG
                    ).show()
                    refreshStravaCard()
                }
            }.start()
            true
        }
    }

    private var apkProgressDialog: AlertDialog? = null

    private fun openApkPicker() {
        if (!bound || service == null) {
            Toast.makeText(this, "Start streaming and connect glasses first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive", "application/apk"))
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "Select glasses APK"), RC_PICK_APK)
        } catch (e: Exception) {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), RC_PICK_APK)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_PICK_APK && resultCode == RESULT_OK && data?.data != null) {
            sendApkToGlasses(data.data!!)
        }
    }

    private fun sendApkToGlasses(uri: Uri) {
        apkProgressDialog = AlertDialog.Builder(this)
            .setTitle("Update glasses app")
            .setMessage("Sending APK... 0%")
            .setCancelable(false)
            .show()
        service!!.sendApkToGlasses(
            uri,
            onProgress = { sent, total ->
                val pct = if (total > 0) (100 * sent / total) else 0
                apkProgressDialog?.setMessage("Sending APK... $pct%")
            },
            onDone = {
                apkProgressDialog?.dismiss()
                apkProgressDialog = null
                Toast.makeText(this, "APK sent. Open the glasses and confirm install when prompted.", Toast.LENGTH_LONG).show()
            },
            onError = { msg ->
                apkProgressDialog?.dismiss()
                apkProgressDialog = null
                Toast.makeText(this, "Failed: $msg", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun sendHotspotToGlasses() {
        val ssid = hotspotSsidInput.text.toString().trim()
        val pass = hotspotPassInput.text.toString()
        if (ssid.isBlank()) {
            Toast.makeText(this, "Enter your hotspot name (SSID)", Toast.LENGTH_SHORT).show()
            return
        }
        if (!bound || service == null) {
            Toast.makeText(this, "Start streaming first so glasses are connected", Toast.LENGTH_SHORT).show()
            return
        }
        service!!.sendWifiCreds(ssid, pass, true)
        Toast.makeText(this, "Sent to glasses — they will enable Wi‑Fi and connect for internet", Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        navMapView.onResume()
        stravaRoutePreviewMap.onResume()
        updateGlassesStatus()
        updateNotifStatus()
        if (!bound) {
            // Re-attach to a live service after process death (START_STICKY keeps it
            // recording). Bind WITHOUT AUTO_CREATE so this never spawns the service;
            // onServiceConnected restores streaming/recording UI state.
            try {
                bindService(Intent(this, HudStreamingService::class.java), connection, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Rebind attempt failed: ${e.message}")
            }
        }
        if (bound) service?.uiCallback = navCallback
        if (bound) {
            service?.setMetricsListener(recMetricsListener)
            syncRecordingUiFromService()
        }
        if (streaming) btAudioRouter.connectAudio()
        if (bound) updateCacheSizeText()
        // Idempotent re-read so the card self-corrects if token state changed
        // while backgrounded (e.g., a Reconnect wipe from a failed refresh).
        refreshStravaCard()
    }

    override fun onPause() {
        super.onPause()
        navMapView.onPause()
        stravaRoutePreviewMap.onPause()
    }

    override fun onDestroy() {
        btAudioRouter.release()
        wifiShareManager.release()
        // Detach service→Activity callbacks BEFORE unbinding (WR-03): the
        // metrics listener and uiCallback strong-reference this Activity, and
        // the foreground service outlives it — left registered, the ticker
        // keeps invoking the destroyed Activity at 1 Hz for the whole ride.
        service?.setMetricsListener(null)
        service?.uiCallback = null
        // Always attempt unbind: a no-AUTO_CREATE rebind from onResume may be
        // registered without ever connecting (bound stays false) — unbinding
        // unconditionally prevents a leaked ServiceConnection.
        try { unbindService(connection) } catch (_: Exception) {}
        bound = false
        // WR-04: release osmdroid resources. onResume/onPause are wired but without
        // onDetach() the MapView's tile-provider thread pool and bitmap cache are
        // retained until GC; repeated route import/preview cycles accumulate that
        // state. Guarded against the not-yet-initialized lateinit maps.
        if (::navMapView.isInitialized) navMapView.onDetach()
        if (::stravaRoutePreviewMap.isInitialized) stravaRoutePreviewMap.onDetach()
        super.onDestroy()
    }

    // ── Glasses status ─────────────────────────────────────────────────────

    private fun updateGlassesStatus() {
        val prefs = getSharedPreferences(PREFS_GLASSES, MODE_PRIVATE)
        val savedName = prefs.getString("glasses_name", null)
        if (savedName != null) {
            glassesStatusText.text = "Paired: $savedName"
            glassesStatusDot.setBackgroundResource(R.drawable.bg_status_dot_connected)
            btnScanGlasses.text = "Change"
        } else {
            glassesStatusText.text = "No glasses paired"
            glassesStatusDot.setBackgroundResource(R.drawable.bg_status_dot_disconnected)
            btnScanGlasses.text = "Pair Glasses"
        }
    }

    private fun updateStreamingUi() {
        if (streaming) {
            btnStart.text = "Streaming"
            btnStart.isEnabled = false
            btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2E7D32.toInt())
            statusText.text = "Streaming to glasses — search a destination"
        } else {
            btnStart.text = "Start Streaming"
            btnStart.isEnabled = true
            btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00E676.toInt())
            statusText.text = "Tap Start Streaming to begin"
        }
    }

    // ── Search ─────────────────────────────────────────────────────────────

    private fun performSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isBlank()) return
        hideKeyboard()
        showingSaved = false
        btnSearch.isEnabled = false
        statusText.text = "Searching..."

        Thread {
            try {
                val results = NominatimClient.search(query)
                runOnUiThread {
                    searchResultsList = results
                    if (results.isEmpty()) {
                        statusText.text = "No results found"
                        searchResults.visibility = View.GONE
                    } else {
                        setResultsList(results.map { it.displayName }, false)
                        searchResults.visibility = View.VISIBLE
                        adjustListHeight()
                        statusText.text = "${results.size} results"
                    }
                    btnSearch.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Search error: ${e.message}"
                    btnSearch.isEnabled = true
                }
            }
        }.start()
    }

    // ── Saved places ───────────────────────────────────────────────────────

    private fun toggleSavedPlaces() {
        if (showingSaved && searchResults.visibility == View.VISIBLE) {
            searchResults.visibility = View.GONE
            showingSaved = false
            btnShowSaved.text = "Saved Places"
            return
        }
        showingSaved = true
        savedPlacesList = savedPlacesManager.getAll()
        if (savedPlacesList.isEmpty()) {
            Toast.makeText(this, "No saved places yet", Toast.LENGTH_SHORT).show()
            searchResults.visibility = View.GONE
            return
        }
        setResultsList(savedPlacesList.map { it.name }, true)
        searchResults.visibility = View.VISIBLE
        adjustListHeight()
        btnShowSaved.text = "Hide Saved"
        statusText.text = "${savedPlacesList.size} saved place(s)"

        searchResults.setOnItemLongClickListener { _, _, pos, _ ->
            if (showingSaved && pos < savedPlacesList.size) {
                val place = savedPlacesList[pos]
                savedPlacesManager.delete(place)
                Toast.makeText(this, "Removed: ${place.name}", Toast.LENGTH_SHORT).show()
                toggleSavedPlaces()
                true
            } else false
        }
    }

    private fun saveCurrentPlace() {
        val dest = selectedDest ?: return
        val parts = dest.displayName.split(",").map { it.trim() }
        val shortName = if (parts.size >= 2) parts.take(3).joinToString(", ") else dest.displayName
        savedPlacesManager.save(SavedPlace(shortName, dest.lat, dest.lng))
        Toast.makeText(this, "Saved: $shortName", Toast.LENGTH_SHORT).show()
        btnSavePlace.text = "Saved!"
        btnSavePlace.isEnabled = false
    }

    private fun onItemSelected(position: Int) {
        if (showingSaved) {
            if (position >= savedPlacesList.size) return
            val place = savedPlacesList[position]
            selectedDest = SearchResult(place.name, place.lat, place.lng)
        } else {
            if (position >= searchResultsList.size) return
            selectedDest = searchResultsList[position]
        }
        searchResults.visibility = View.GONE

        val dest = selectedDest!!
        routeDestText.text = dest.displayName
        routeInfoText.text = "Tap Start Navigation to calculate route"
        routeCard.visibility = View.VISIBLE
        navStatus.visibility = View.GONE
        btnNavigate.isEnabled = true
        btnSavePlace.text = "Save"
        btnSavePlace.isEnabled = true
    }

    private fun setResultsList(items: List<String>, isSaved: Boolean) {
        searchResults.adapter = object : ArrayAdapter<String>(this, R.layout.item_search_result, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.item_search_result, parent, false)
                val icon = view.findViewById<TextView>(R.id.resultIcon)
                val text = view.findViewById<TextView>(android.R.id.text1)
                icon.text = if (isSaved) "\u2B50" else "\uD83D\uDCCD"
                text.text = getItem(position)
                return view
            }
        }
    }

    private fun adjustListHeight() {
        val count = searchResults.adapter?.count ?: 0
        val maxItems = minOf(count, 5)
        val itemH = (52 * resources.displayMetrics.density).toInt()
        val params = searchResults.layoutParams
        params.height = maxItems * itemH
        searchResults.layoutParams = params
    }

    // ── Navigation ─────────────────────────────────────────────────────────

    private fun startNavigation() {
        val dest = selectedDest ?: return
        if (!bound || service == null) {
            Toast.makeText(this, "Start streaming first", Toast.LENGTH_SHORT).show()
            return
        }
        routeInfoText.text = "Calculating route..."
        btnNavigate.isEnabled = false
        service!!.startNavigation(dest.lat, dest.lng)
    }

    private fun showNavStatus() {
        navStatus.visibility = View.VISIBLE
        btnNavigate.isEnabled = true
        val showFullSteps = getSharedPreferences(PREFS_HUD, MODE_PRIVATE).getBoolean(PREF_SHOW_FULL_ROUTE_STEPS, false)
        switchShowFullRouteSteps.isChecked = showFullSteps
        navFullStepsPanel.visibility = if (showFullSteps) View.VISIBLE else View.GONE
        if (showFullSteps) updateFullStepsList()
        navMapHandler.postDelayed(navMapUpdateRunnable, 500L)
    }

    private fun stopNavigation() {
        navMapHandler.removeCallbacks(navMapUpdateRunnable)
        service?.stopNavigation()
        navStatus.visibility = View.GONE
        navInstructionText.text = ""
        navDistanceText.text = ""
        currentRouteWaypoints = emptyList()
        fullRouteSteps = emptyList()
    }

    private fun updateFullStepsList() {
        val items = fullRouteSteps.mapIndexed { i, step ->
            "${i + 1}. ${step.instruction} — ${formatDist(step.distance)}"
        }
        navFullStepsList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
    }

    private fun initNavMap() {
        navMapView.setTileSource(TileSourceFactory.MAPNIK)
        navMapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        navMapView.setMultiTouchControls(true)
        navMapView.controller.setZoom(15.0)
    }

    private fun updateNavMap() {
        if (currentRouteWaypoints.isEmpty()) return
        navMapView.overlays.removeIf { it is Polyline }
        val line = Polyline().apply {
            outlinePaint.color = Color.parseColor("#00E676")
            outlinePaint.strokeWidth = 12f
            outlinePaint.isAntiAlias = true
            setPoints(currentRouteWaypoints.map { GeoPoint(it.latitude, it.longitude) })
        }
        navMapView.overlays.add(line)
        val (lat, lng) = service?.getLastLocation() ?: run {
            val first = currentRouteWaypoints.first()
            Pair(first.latitude, first.longitude)
        }
        navMapView.controller.setCenter(GeoPoint(lat, lng))
        navMapView.controller.setZoom(17.0)
        val box = BoundingBox.fromGeoPoints(currentRouteWaypoints.map { GeoPoint(it.latitude, it.longitude) })
        navMapView.zoomToBoundingBox(box, false)
        navMapView.invalidate()
    }

    // ── Activity recording ─────────────────────────────────────────────────

    private fun sportType(): String =
        getSharedPreferences(PREFS_HUD, MODE_PRIVATE).getString(PREF_SPORT_TYPE, "ride") ?: "ride"

    private fun setSportType(sport: String) {
        getSharedPreferences(PREFS_HUD, MODE_PRIVATE).edit().putString(PREF_SPORT_TYPE, sport).apply()
        updateSportToggleUi()
    }

    private fun updateSportToggleUi() {
        val ride = sportType() == "ride"
        val selected = android.content.res.ColorStateList.valueOf(0xFF00E676.toInt())
        val unselected = android.content.res.ColorStateList.valueOf(0xFF2A2A2A.toInt())
        btnSportRide.backgroundTintList = if (ride) selected else unselected
        btnSportRide.setTextColor(if (ride) 0xFF000000.toInt() else 0xFFAAAAAA.toInt())
        btnSportRun.backgroundTintList = if (ride) unselected else selected
        btnSportRun.setTextColor(if (ride) 0xFFAAAAAA.toInt() else 0xFF000000.toInt())
    }

    private fun startRecording() {
        if (!bound || service == null) {
            Toast.makeText(this, "Start streaming first", Toast.LENGTH_SHORT).show()
            return
        }
        val sport = sportType()
        if (service!!.startRecording(sport)) {
            // Shows the panel (updateRecordingUi(true)) AND paints the initial
            // snapshot immediately — the first ticker callback is ~1s away.
            syncRecordingUiFromService()
            // Prompts appear over the already-running recording — start is NEVER
            // gated on prompt outcomes.
            showFirstRecordingPrompts()
        } else {
            Toast.makeText(this, "Could not start recording", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmStopRecording() {
        AlertDialog.Builder(this)
            .setTitle("Finish recording?")
            .setMessage("Your activity will be saved.")
            .setPositiveButton("Finish") { _, _ ->
                // Capture the session id BEFORE stopRecording clears PREF_REC_SESSION_ID.
                val sessionId = service?.currentSessionId()
                service?.stopRecording()
                updateRecordingUi(false)
                Toast.makeText(this, "Activity saved", Toast.LENGTH_SHORT).show()
                // Open the summary with the session id ONLY (never trackPoints — the
                // id-only extra avoids the TransactionTooLargeException IPC-size trap).
                if (sessionId != null) {
                    startActivity(
                        Intent(this, ActivitySummaryActivity::class.java)
                            .putExtra(ActivitySummaryActivity.EXTRA_SESSION_ID, sessionId)
                    )
                }
            }
            .setNegativeButton("Keep recording", null)
            .show()
    }

    private fun updateRecordingUi(recording: Boolean) {
        if (recording) {
            sportToggleRow.visibility = View.GONE
            btnStartRecording.visibility = View.GONE
            recordingPanel.visibility = View.VISIBLE
        } else {
            sportToggleRow.visibility = View.VISIBLE
            btnStartRecording.visibility = View.VISIBLE
            recordingPanel.visibility = View.GONE
        }
    }

    /**
     * Re-sync the card from the service so activity recreation mid-recording
     * restores the live panel (called on connect and resume).
     */
    private fun syncRecordingUiFromService() {
        val recording = service?.recordingState() == SessionState.TRACKING
        updateRecordingUi(recording)
        if (recording) {
            service?.currentMetrics()?.let { recMetricsListener.onMetrics(it) }
        }
    }

    /**
     * First-recording onboarding (REC-05 consent layers). Runs AFTER the
     * recording has already started — declining anything never blocks or
     * stops the recording; it only degrades Doze survival / crash recovery.
     * Triggered ONLY from the recording start flow (never on app launch).
     */
    private fun showFirstRecordingPrompts() {
        val batteryDialog = promptRecordingBatteryExemption()
        if (batteryDialog != null) {
            batteryDialog.setOnDismissListener { maybeRequestBackgroundLocation() }
        } else {
            maybeRequestBackgroundLocation()
        }
    }

    /**
     * Battery-optimization exemption prompt — fires on first recording start
     * (locked decision), at most once per app launch, skipped entirely when
     * already exempt. Returns the shown dialog so the caller can chain the
     * next onboarding prompt off its dismissal, or null when skipped.
     */
    private fun promptRecordingBatteryExemption(): AlertDialog? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        if (recordingExemptionPromptShown) return null
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        if (pm.isIgnoringBatteryOptimizations(packageName)) return null
        recordingExemptionPromptShown = true
        return AlertDialog.Builder(this)
            .setTitle("Keep recording when screen is off")
            .setMessage("Your recording is running. Without a battery exemption, this phone's aggressive battery management may kill GPS recording while the phone is in your pocket. Tap \"Allow\" and turn off battery optimization for this app so recordings keep running with the screen off.")
            .setPositiveButton("Allow") { _, _ ->
                try {
                    val i = Intent().apply {
                        action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(i)
                } catch (_: Exception) {}
            }
            .setNegativeButton("Not now") { _, _ ->
                Log.w(TAG, "recording without battery exemption")
            }
            .show()
    }

    /**
     * Background-location onboarding (ask-once). Lets the system restart a
     * recording after the app is killed. On API 30+ the system routes the
     * request to the settings screen — expected. ACCESS_FINE_LOCATION is
     * already granted before any recording since streaming requires it.
     */
    private fun maybeRequestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            == PackageManager.PERMISSION_GRANTED) return
        val hudPrefs = getSharedPreferences(PREFS_HUD, MODE_PRIVATE)
        if (hudPrefs.getBoolean(PREF_BG_LOC_ASKED, false)) return
        hudPrefs.edit().putBoolean(PREF_BG_LOC_ASKED, true).apply()
        AlertDialog.Builder(this)
            .setTitle("Allow all-the-time location")
            .setMessage("Background location lets the system restart a recording after the app is killed. Without it, a killed recording can only resume when you reopen the app.")
            .setPositiveButton("Continue") { _, _ ->
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), RC_BG_LOCATION)
            }
            .setNegativeButton("Skip", null)
            .show()
    }

    // ── Wi-Fi sharing ──────────────────────────────────────────────────────

    private fun updateWifiUi(state: WifiShareManager.State) {
        when (state) {
            WifiShareManager.State.OFF -> {
                wifiShareStatus.text = "Create Wi-Fi Direct hotspot for glasses"
                wifiInfoCard.visibility = View.GONE
                switchWifiShare.isChecked = false
                service?.sendWifiCreds("", "", false)
            }
            WifiShareManager.State.CREATING -> {
                wifiShareStatus.text = "Creating hotspot..."
                wifiInfoCard.visibility = View.GONE
            }
            WifiShareManager.State.ACTIVE -> {
                wifiShareStatus.text = "Hotspot active"
                wifiInfoCard.visibility = View.VISIBLE
                wifiSsidText.text = wifiShareManager.groupSsid
                wifiPassText.text = wifiShareManager.groupPassphrase
                val n = wifiShareManager.connectedClients
                wifiClientsText.text = if (n == 0) "Sending credentials to glasses..." else "$n device(s) connected"
                switchWifiShare.isChecked = true
                service?.sendWifiCreds(wifiShareManager.groupSsid, wifiShareManager.groupPassphrase, true)
            }
            WifiShareManager.State.FAILED -> {
                wifiShareStatus.text = "Failed: ${wifiShareManager.lastError}"
                wifiInfoCard.visibility = View.GONE
            }
        }
    }

    private fun checkWifiPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), RC_WIFI_PERM)
        } else {
            wifiShareManager.startSharing()
        }
    }

    // ── Notification status ────────────────────────────────────────────────

    private fun updateNotifStatus() {
        val enabled = isNotificationListenerEnabled()
        if (enabled) {
            notifStatusText.text = "Notifications forwarding to glasses"
            notifStatusText.setTextColor(0xFF66BB6A.toInt())
            btnNotifAccess.text = "Granted"
            btnNotifAccess.isEnabled = false
        } else {
            notifStatusText.text = "Show phone notifications on glasses"
            notifStatusText.setTextColor(0xFF757575.toInt())
            btnNotifAccess.text = "Grant"
            btnNotifAccess.isEnabled = true
        }
    }

    // ── Permissions & streaming ────────────────────────────────────────────

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), RC_PERMISSIONS)
        } else {
            startStreaming()
        }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, perms, results)
        when (rc) {
            RC_PERMISSIONS -> {
                if (results.all { it == PackageManager.PERMISSION_GRANTED }) startStreaming()
            }
            RC_WIFI_PERM -> {
                if (results.all { it == PackageManager.PERMISSION_GRANTED }) {
                    wifiShareManager.startSharing()
                } else {
                    switchWifiShare.isChecked = false
                    Toast.makeText(this, "Wi-Fi permissions required", Toast.LENGTH_SHORT).show()
                }
            }
            RC_BG_LOCATION -> {
                val granted = results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED
                Log.i(TAG, "Background location grant result: granted=$granted")
                if (!granted) {
                    // Never stop or block the running recording — only crash
                    // recovery degrades without background location.
                    Toast.makeText(this, "Recording works, but won't auto-recover if the system kills the app", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startStreaming() {
        val intent = Intent(this, HudStreamingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        streaming = true
        updateStreamingUi()
        statusText.text = "Streaming started — search a destination"
        btAudioRouter.connectAudio()
        promptBatteryOptimizationIfNeeded()
    }

    private fun promptBatteryOptimizationIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        AlertDialog.Builder(this)
            .setTitle("Keep running when screen is off")
            .setMessage("To keep maps and directions updating on your glasses when the phone screen turns off, allow this app to run in the background. Tap \"Allow\" below and turn off battery optimization for this app.")
            .setPositiveButton("Allow") { _, _ ->
                try {
                    val i = Intent().apply {
                        action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(i)
                } catch (_: Exception) {}
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    // ── Misc ───────────────────────────────────────────────────────────────

    private fun openNotificationListenerSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, HudNotificationListenerService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun speakNavInstruction(instruction: String, distance: Double) {
        if (!getPreferences(MODE_PRIVATE).getBoolean(PREF_TTS, false)) return
        btAudioRouter.speak(instruction, distance, isImperial())
    }

    private fun sendCurrentSettings() {
        val prefs = getPreferences(MODE_PRIVATE)
        val hudPrefs = getSharedPreferences(PREFS_HUD, MODE_PRIVATE)
        service?.sendSettings(
            ttsEnabled = prefs.getBoolean(PREF_TTS, false),
            useImperial = prefs.getBoolean(PREF_IMPERIAL, false),
            useMiniMap = prefs.getBoolean(PREF_MINI_MAP, false),
            miniMapStyle = prefs.getString(PREF_MINI_MAP_STYLE, "strip") ?: "strip",
            streamNotifications = hudPrefs.getBoolean(PREF_STREAM_NOTIFICATIONS, true),
            showUpcomingSteps = hudPrefs.getBoolean(PREF_SHOW_FULL_ROUTE_STEPS, false),
            showTurnAlert = hudPrefs.getBoolean(PREF_TURN_ALERT, false),
            tileCacheSizeMb = hudPrefs.getInt(PREF_TILE_CACHE_SIZE_MB, 100),
            showSpeed = hudPrefs.getBoolean(PREF_SHOW_SPEED, true),
            showSpeedLimit = hudPrefs.getBoolean(PREF_SHOW_SPEED_LIMIT, true)
        )
    }

    private fun setupCacheSpinner() {
        val sizes = listOf(50, 100, 200, 500)
        val labels = sizes.map { "$it MB" }
        spinnerCacheSize.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val savedSize = getSharedPreferences(PREFS_HUD, MODE_PRIVATE).getInt(PREF_TILE_CACHE_SIZE_MB, 100)
        val idx = sizes.indexOf(savedSize).coerceAtLeast(0)
        spinnerCacheSize.setSelection(idx)
        spinnerCacheSize.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val mb = sizes[pos]
                getSharedPreferences(PREFS_HUD, MODE_PRIVATE).edit().putInt(PREF_TILE_CACHE_SIZE_MB, mb).apply()
                service?.updateTileCacheSize(mb)
                sendCurrentSettings()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        btnClearCache.setOnClickListener {
            service?.clearTileCache()
            Toast.makeText(this, "Map cache cleared", Toast.LENGTH_SHORT).show()
            updateCacheSizeText()
        }
        updateCacheSizeText()
    }

    private fun updateCacheSizeText() {
        val bytes = service?.tileCacheSizeBytes() ?: 0L
        val mb = bytes / (1024.0 * 1024.0)
        cacheSizeText.text = String.format("Used: %.1f MB", mb)
    }

    private fun isImperial(): Boolean = getPreferences(MODE_PRIVATE).getBoolean(PREF_IMPERIAL, false)

    // The four metric formatters now live in SportFormat (single source of truth,
    // shared with ActivitySummaryActivity + HistoryActivity). These private
    // wrappers thread the activity-local isImperial() flag in and delegate, so
    // every existing call site here stays untouched.
    private fun formatDist(m: Double): String = SportFormat.formatDist(m, isImperial())

    /** Elevation gain formatting — feet (imperial) vs meters (metric). */
    private fun formatElev(m: Double): String = if (isImperial()) {
        String.format("%.0f ft", m * 3.28084)
    } else {
        String.format("%.0f m", m)
    }

    private fun formatElapsed(ms: Long): String = SportFormat.formatElapsed(ms)

    private fun formatSpeed(mps: Double): String = SportFormat.formatSpeed(mps, isImperial())

    private fun formatPace(msPerKm: Long): String = SportFormat.formatPace(msPerKm, isImperial())

    private fun formatTime(s: Double): String {
        val mins = (s / 60).toInt()
        return if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins} min"
    }
}
