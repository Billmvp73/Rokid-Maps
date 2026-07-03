package com.rokid.hud.phone.strava

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.rokid.hud.phone.MainActivity

/**
 * No-UI deep-link forwarder for rokidhud://callback (03-RESEARCH Pattern 3,
 * the AppAuth RedirectUriReceiverActivity pattern).
 *
 * MINIMAL EXPORTED SURFACE (threat T-03-07): no other logic, reads only
 * intent.data, no UI, no state. Forwards to MainActivity with
 * CLEAR_TOP|SINGLE_TOP — delivered via onNewIntent when warm, recreated via
 * onCreate after process death — which also pops the Custom Tab off the
 * back stack, then finishes synchronously (Theme.NoDisplay requires
 * finish() before onResume completes).
 *
 * Never log the URI: it carries the authorization code + state, and a logged
 * code inside its 10-minute validity window plus the extractable APK secret
 * would be a token-theft path (T-03-04).
 */
class StravaCallbackActivity : Activity() {

    companion object {
        private const val TAG = "StravaCallback"
        const val ACTION_STRAVA_CALLBACK = "com.rokid.hud.phone.strava.CALLBACK"
        const val EXTRA_CALLBACK_URI = "strava_callback_uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "Strava callback received")
        val forward = Intent(this, MainActivity::class.java).apply {
            action = ACTION_STRAVA_CALLBACK
            putExtra(EXTRA_CALLBACK_URI, intent?.data?.toString())
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(forward)
        finish()
    }
}
