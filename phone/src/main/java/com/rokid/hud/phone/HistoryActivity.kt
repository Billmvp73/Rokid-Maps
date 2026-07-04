package com.rokid.hud.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Past-activities list (UPL-04): every finalized session, newest-first, with a
 * date / sport / distance / duration line and an uploaded-vs-not badge. Tapping a
 * row reopens its [ActivitySummaryActivity] (by id — never trackPoints).
 *
 * The list source is [SessionStore.listFinalSessions] (already newest-first);
 * each File's nameWithoutExtension is the session id, read back through the public
 * [SessionStore.readSession]. Loading is off the main thread (multi-thousand-point
 * JSON per file); binding happens on the main thread into a plain ListView +
 * BaseAdapter (no RecyclerView dependency; findViewById convention).
 *
 * IMPERIAL FLAG: read from getSharedPreferences("MainActivity", MODE_PRIVATE) —
 * the SAME activity-local store MainActivity + ActivitySummaryActivity use (an
 * app-wide read would always be metric).
 */
class HistoryActivity : AppCompatActivity() {

    companion object {
        private const val MAIN_PREFS = "MainActivity"
        private const val PREF_IMPERIAL = "use_imperial"
    }

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        listView = findViewById(R.id.historyList)
        emptyView = findViewById(R.id.historyEmpty)

        Thread {
            val store = SessionStore(File(filesDir, "activities"))
            // listFinalSessions is newest-first; readSession(id) is the public read path.
            val rows = store.listFinalSessions().mapNotNull { f ->
                store.readSession(f.nameWithoutExtension)
            }
            runOnUiThread { bind(rows) }
        }.start()
    }

    private fun isImperial(): Boolean =
        getSharedPreferences(MAIN_PREFS, Context.MODE_PRIVATE).getBoolean(PREF_IMPERIAL, false)

    private fun bind(rows: List<SessionData>) {
        if (rows.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            listView.visibility = View.GONE
            return
        }
        emptyView.visibility = View.GONE
        listView.visibility = View.VISIBLE
        listView.adapter = HistoryAdapter(rows, isImperial())
        listView.setOnItemClickListener { _, _, position, _ ->
            val data = rows[position]
            startActivity(
                Intent(this, ActivitySummaryActivity::class.java)
                    .putExtra(ActivitySummaryActivity.EXTRA_SESSION_ID, data.id)
            )
        }
    }

    private inner class HistoryAdapter(
        private val items: List<SessionData>,
        private val imperial: Boolean
    ) : BaseAdapter() {

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView
                ?: LayoutInflater.from(this@HistoryActivity)
                    .inflate(R.layout.item_activity, parent, false)
            val data = items[position]
            view.findViewById<TextView>(R.id.rowDate).text = prettyDate(data.startTime)
            view.findViewById<TextView>(R.id.rowSport).text = sportLabel(data.sport)
            view.findViewById<TextView>(R.id.rowStats).text =
                "${SportFormat.formatDist(data.distanceM, imperial)}  ·  ${SportFormat.formatElapsed(data.elapsedMs)}"
            val badge = view.findViewById<TextView>(R.id.rowUploadBadge)
            if (data.stravaUploaded) {
                badge.text = "Uploaded ✓"
                badge.setTextColor(android.graphics.Color.parseColor("#00E676"))
            } else {
                badge.text = "Not uploaded"
                badge.setTextColor(android.graphics.Color.parseColor("#9E9E9E"))
            }
            return view
        }
    }

    private fun sportLabel(sport: String): String = when (sport) {
        "run" -> "Run"
        "ride" -> "Ride"
        else -> sport.replaceFirstChar { it.uppercase() }
    }

    /** ISO "2026-07-03T09:15:00Z" → "2026-07-03 09:15". Falls back to raw / date-only. */
    private fun prettyDate(startTime: String): String {
        if (startTime.length < 16) return startTime
        val date = startTime.take(10)
        val time = startTime.substring(11, 16)
        return "$date $time"
    }
}
