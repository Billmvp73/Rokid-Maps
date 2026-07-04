package com.rokid.hud.phone

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.LocationServices

/**
 * DEBUG-ONLY mock GPS route feeder (plan 01-07 Task 1 fallback — ColorOS blocks the
 * pure-adb test-provider path with MANAGE_APP_OPS_MODES SecurityException).
 *
 * Lives in phone/src/debug/ so it is physically absent from release builds (T-07-01).
 * Requires this app to be selected in Developer Options > "Select mock location app".
 *
 * Trigger:
 *   adb shell am broadcast -n com.rokid.hud.phone/.MockRouteFeeder \
 *     -a com.rokid.hud.phone.debug.MOCK_ROUTE --es cmd start [--ei loops N]
 *   ... --es cmd stop
 *
 * Track (per plan 01-07): segment (i) 90 moving fixes (+0.00005 deg lat/tick, ~5.5 m/s),
 * segment (ii) 60 stationary fixes jittering ±0.00001 deg (drift — distance must NOT grow),
 * segment (iii) 60 moving fixes. 1 Hz. `loops` repeats the whole pattern (default 1;
 * use a large value for the 30-minute screen-off gate).
 */
class MockRouteFeeder : BroadcastReceiver() {

    companion object {
        private const val TAG = "MockRouteFeeder"
        private const val ACTION = "com.rokid.hud.phone.debug.MOCK_ROUTE"
        private const val START_LAT = 37.7749
        private const val START_LNG = -122.4194
        private const val MOVE_STEP_DEG = 0.00005
        private const val JITTER_DEG = 0.00001

        @Volatile private var running = false
        @Volatile private var feederThread: Thread? = null
    }

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        when (intent.getStringExtra("cmd") ?: "start") {
            "stop" -> {
                Log.i(TAG, "Stop requested")
                running = false
            }
            "point" -> {
                // DEBUG verification aid: hold a STATIC location (e.g. an on-route waypoint)
                // so navigation sees the phone as on-route. adb: --es cmd point --ed lat X --ed lng Y
                if (running) { running = false; feederThread?.join(1500) }
                val lat = intent.getDoubleExtra("lat", START_LAT)
                val lng = intent.getDoubleExtra("lng", START_LNG)
                val appContext = context.applicationContext
                running = true
                feederThread = Thread {
                    val client = LocationServices.getFusedLocationProviderClient(appContext)
                    try {
                        client.setMockMode(true)
                        Log.i(TAG, "Mock POINT hold at $lat,$lng")
                        while (running) { push(client, lat, lng, speed = 0.5f); SystemClock.sleep(1000) }
                    } catch (e: Exception) {
                        Log.e(TAG, "Point feeder failed: ${e.message}", e)
                    } finally {
                        try { client.setMockMode(false) } catch (_: Exception) {}
                        running = false
                    }
                }.apply { name = "MockPoint"; start() }
            }
            "start" -> {
                if (running) { Log.w(TAG, "Feeder already running"); return }
                val loops = intent.getIntExtra("loops", 1)
                val appContext = context.applicationContext
                running = true
                feederThread = Thread {
                    val client = LocationServices.getFusedLocationProviderClient(appContext)
                    try {
                        client.setMockMode(true)
                        Log.i(TAG, "Mock mode ON — feeding ${loops} loop(s) of 90/60/60 @1Hz")
                        var lat = START_LAT
                        var loop = 0
                        while (running && loop < loops) {
                            // Segment (i): 90 moving fixes
                            for (i in 0 until 90) {
                                if (!running) break
                                lat += MOVE_STEP_DEG
                                push(client, lat, START_LNG, speed = 5.5f)
                                SystemClock.sleep(1000)
                            }
                            // Segment (ii): 60 stationary fixes with drift jitter
                            for (i in 0 until 60) {
                                if (!running) break
                                val jLat = lat + if (i % 2 == 0) JITTER_DEG else -JITTER_DEG
                                push(client, jLat, START_LNG, speed = 0.0f)
                                SystemClock.sleep(1000)
                            }
                            // Segment (iii): 60 moving fixes
                            for (i in 0 until 60) {
                                if (!running) break
                                lat += MOVE_STEP_DEG
                                push(client, lat, START_LNG, speed = 5.5f)
                                SystemClock.sleep(1000)
                            }
                            loop++
                            Log.i(TAG, "Loop ${loop}/${loops} complete")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Feeder failed: ${e.message}", e)
                    } finally {
                        try {
                            client.setMockMode(false)
                            Log.i(TAG, "Mock mode OFF (teardown)")
                        } catch (e: Exception) {
                            Log.w(TAG, "Mock teardown failed: ${e.message}")
                        }
                        running = false
                    }
                }.apply { name = "MockRouteFeeder"; start() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun push(
        client: com.google.android.gms.location.FusedLocationProviderClient,
        lat: Double,
        lng: Double,
        speed: Float
    ) {
        val loc = Location("gps").apply {
            latitude = lat
            longitude = lng
            altitude = 12.0
            accuracy = 5.0f
            this.speed = speed
            bearing = 0.0f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        client.setMockLocation(loc)
    }
}
