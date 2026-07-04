package com.rokid.hud.glasses

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class HudActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val RC_BT = 200
        private const val TAG = "HudActivity"
        private const val ROKID_DOUBLE_BACK_ACTION = "com.rokid.glass.homekey.doubleback"
        private const val EXIT_ON_STOP_DELAY_MS = 400L
        private const val CLOSING_MESSAGE_DURATION_MS = 1800L
        private const val CLOSING_MESSAGE = "Rokid Maps is closing"
    }

    private lateinit var hudView: HudView
    private lateinit var btClient: BluetoothClient
    private lateinit var tileManager: TileManager
    private lateinit var wifiConnector: WifiConnector

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastSpokenInstruction = ""

    /** Set true when we start another activity (e.g. installer) so onStop doesn't exit the app. */
    private var launchingExternalActivity = false
    /** Set true when we're showing "Rokid Maps is closing" so we don't double-invoke shutdown. */
    private var isShuttingDown = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var exitWhenStoppedRunnable: Runnable? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (scale > 0) (level * 100) / scale else -1
            hudView.state = hudView.state.copy(batteryLevel = pct)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hud)
        goFullscreen()

        hudView = findViewById(R.id.hudView)

        tileManager = TileManager(applicationContext) { hudView.postInvalidate() }
        hudView.tileManager = tileManager

        hudView.onLayoutToggle = { toggleLayout() }
        hudView.onDoubleTap = { shutdownApp() }
        hudView.onSwipeForward = { btClient.toggleLayout() }
        hudView.onSwipeBack = { btClient.toggleLayoutBack() }

        tts = TextToSpeech(this, this)
        wifiConnector = WifiConnector(applicationContext)
        wifiConnector.onStatusChanged = { wifiConnected ->
            Log.i(TAG, "Wi-Fi status changed: connected=$wifiConnected")
            runOnUiThread {
                hudView.state = hudView.state.copy(wifiConnected = wifiConnected)
            }
        }

        btClient = BluetoothClient(
            context = this,
            onStateUpdate = { newState ->
                runOnUiThread {
                    // Capture BEFORE assignment — comparing after always matched
                    // (dead code since 55f707d; cache-size setting never applied).
                    // Found by 02-REVIEW WR-03.
                    val cacheSizeChanged = newState.tileCacheSizeMb != hudView.state.tileCacheSizeMb
                    // Preserve locally-sourced fields that BT state doesn't carry
                    hudView.state = newState.copy(
                        batteryLevel = hudView.state.batteryLevel,
                        wifiConnected = hudView.state.wifiConnected
                    )
                    tileManager.onTileRequestViaProxy = if (newState.btConnected) {
                        { z, x, y, id -> btClient.sendTileRequest(z, x, y, id) }
                    } else null
                    // Update disk cache size from settings
                    if (cacheSizeChanged) {
                        tileManager.updateCacheSize(newState.tileCacheSizeMb)
                    }
                    if (newState.ttsEnabled && ttsReady
                        && newState.instruction.isNotBlank()
                        && newState.instruction != lastSpokenInstruction
                    ) {
                        lastSpokenInstruction = newState.instruction
                        speakInstruction(newState.instruction, newState.stepDistance, newState.useImperial)
                    }
                }
            },
            onWifiCreds = { ssid, pass, enabled ->
                if (enabled && ssid.isNotBlank()) {
                    Log.i(TAG, "Auto-connecting to Wi-Fi: $ssid")
                    wifiConnector.connect(ssid, pass)
                } else {
                    Log.i(TAG, "Disconnecting shared Wi-Fi")
                    wifiConnector.disconnect()
                }
            }
        )
        btClient.onTileReceived = { id, data -> tileManager.deliverTile(id, data) }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(doubleBackReceiver, IntentFilter(ROKID_DOUBLE_BACK_ACTION), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(doubleBackReceiver, IntentFilter(ROKID_DOUBLE_BACK_ACTION))
        }
        requestPermissionsAndStartBt()
    }

    private val doubleBackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ROKID_DOUBLE_BACK_ACTION) {
                Log.i(TAG, "Double-tap back broadcast received — shutting down")
                shutdownApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        exitWhenStoppedRunnable?.let { mainHandler.removeCallbacks(it) }
        exitWhenStoppedRunnable = null
        launchingExternalActivity = false
    }

    override fun onStop() {
        super.onStop()
        if (launchingExternalActivity || isFinishing) return
        val runnable = Runnable {
            Log.i(TAG, "App moved to background — shutting down")
            shutdownApp(showMessage = false)
        }
        exitWhenStoppedRunnable = runnable
        mainHandler.postDelayed(runnable, EXIT_ON_STOP_DELAY_MS)
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                // Existing double-tap-to-quit — unchanged.
                KeyEvent.KEYCODE_ENTER -> {
                    Log.i(TAG, "Touchpad double-tap (KEYCODE_ENTER) — shutting down")
                    shutdownApp()
                    return true
                }
                // Page swipe FORWARD (next). Hardware confirmed to emit KEY_RIGHT -> DPAD_RIGHT
                // (getevent on ROKID,PSOC-TP-R); DOWN + SYSTEM_NAVIGATION_* covered belt-and-braces.
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT,
                KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN -> {
                    Log.i(TAG, "Touchpad swipe forward (keyCode=${event.keyCode}) — next page")
                    btClient.toggleLayout()
                    return true
                }
                // Page swipe BACK (previous).
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT,
                KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP -> {
                    Log.i(TAG, "Touchpad swipe back (keyCode=${event.keyCode}) — previous page")
                    btClient.toggleLayoutBack()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        exitWhenStoppedRunnable?.let { mainHandler.removeCallbacks(it) }
        try { unregisterReceiver(doubleBackReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) { }
        btClient.stop()
        wifiConnector.disconnect()
        tileManager.shutdown()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA
                    && result != TextToSpeech.LANG_NOT_SUPPORTED
            if (ttsReady) {
                Log.i(TAG, "TTS initialized successfully")
            } else {
                Log.w(TAG, "TTS language not supported")
            }
        } else {
            Log.w(TAG, "TTS init failed with status $status")
        }
    }

    private fun speakInstruction(instruction: String, distance: Double, useImperial: Boolean) {
        if (!ttsReady) return
        val distText = if (useImperial) {
            val feet = distance * 3.28084
            val miles = distance / 1609.344
            when {
                miles >= 0.1 -> String.format("in %.1f miles", miles)
                feet >= 50 -> "in ${feet.toInt()} feet"
                distance > 0 -> "now"
                else -> ""
            }
        } else {
            when {
                distance >= 1000 -> String.format("in %.1f kilometers", distance / 1000)
                distance >= 50 -> "in ${distance.toInt()} meters"
                distance > 0 -> "now"
                else -> ""
            }
        }
        val speech = if (distText.isNotBlank()) "$instruction, $distText" else instruction
        tts?.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "nav_step")
    }

    private fun toggleLayout() {
        btClient.toggleLayout()
    }

    private fun shutdownApp(showMessage: Boolean = true) {
        if (isShuttingDown) return
        isShuttingDown = true
        exitWhenStoppedRunnable?.let { mainHandler.removeCallbacks(it) }
        exitWhenStoppedRunnable = null
        if (showMessage && !isFinishing) {
            hudView.state = hudView.state.copy(closingMessage = CLOSING_MESSAGE)
            mainHandler.postDelayed({
                doShutdown()
            }, CLOSING_MESSAGE_DURATION_MS)
        } else {
            doShutdown()
        }
    }

    private fun doShutdown() {
        Log.i(TAG, "Shutting down app")
        finishAndRemoveTask()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun installReceivedApk(file: File) {
        try {
            launchingExternalActivity = true
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.i(TAG, "Install intent started for received APK")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch installer: ${e.message}")
        }
    }

    private fun requestPermissionsAndStartBt() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CHANGE_WIFI_STATE)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.CHANGE_WIFI_STATE)
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), RC_BT)
        }
        // Always start BT regardless of permission results -- on Rokid glasses
        // standard permissions may not apply or dialogs may not show
        btClient.start()
        Log.i(TAG, "Bluetooth client started (permissions pending: ${needed.size})")
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, perms, results)
        Log.i(TAG, "Permissions result: rc=$rc granted=${results.count { it == PackageManager.PERMISSION_GRANTED }}/${results.size}")
    }

    @Suppress("DEPRECATION")
    private fun goFullscreen() {
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {}

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.decorView?.let { decor ->
                    decor.windowInsetsController?.let { ctrl ->
                        ctrl.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                        ctrl.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        } catch (_: Exception) {}
    }
}
