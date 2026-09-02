package com.kabladaktari.intake

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Doctor-facing list of patients who've messaged in, pulled from GET /sessions. */
class SessionsActivity : AppCompatActivity() {

    private lateinit var medicalContainer: LinearLayout
    private lateinit var otherContainer: LinearLayout
    private lateinit var otherToggle: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private var otherExpanded = false
    private var otherCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sessions)

        medicalContainer = findViewById(R.id.medicalContainer)
        otherContainer = findViewById(R.id.otherContainer)
        otherToggle = findViewById(R.id.otherToggle)
        status = findViewById(R.id.statusText)
        progress = findViewById(R.id.progressBar)

        findViewById<View>(R.id.refreshButton).setOnClickListener { load() }
        otherToggle.setOnClickListener {
            otherExpanded = !otherExpanded
            renderOtherToggle()
            otherContainer.visibility = if (otherExpanded) View.VISIBLE else View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        // Covers both the initial load and returning from ReportActivity with a
        // freshly generated report, so status tags stay current.
        load()
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        status.text = ""
        medicalContainer.removeAllViews()
        otherContainer.removeAllViews()
        BackendApi.fetchSessions(this) { sessions, error ->
            progress.visibility = View.GONE
            if (sessions == null) {
                status.text = "Failed to load: ${error ?: "unknown error"}"
                return@fetchSessions
            }
            if (sessions.isEmpty()) {
                status.text = "No patient messages yet."
                return@fetchSessions
            }

            val (medical, other) = sessions.partition { it.isMedical == true }

            if (medical.isEmpty()) {
                status.text = "No patient conversations detected yet."
            }
            for (s in medical) medicalContainer.addView(buildRow(s))

            otherCount = other.size
            for (s in other) otherContainer.addView(buildRow(s))
            otherToggle.visibility = if (otherCount > 0) View.VISIBLE else View.GONE
            renderOtherToggle()
        }
    }

    private fun renderOtherToggle() {
        val arrow = if (otherExpanded) "▲" else "▼"
        otherToggle.text = "$arrow ${if (otherExpanded) "Hide" else "Show"} $otherCount other chat" +
            if (otherCount == 1) "" else "s"
    }

    private fun buildRow(s: SessionSummary): View {
        val row = LayoutInflater.from(this).inflate(R.layout.list_item_session, medicalContainer, false)
        val avatar = row.findViewById<TextView>(R.id.avatarText)
        val name = row.findViewById<TextView>(R.id.nameText)
        val subtitle = row.findViewById<TextView>(R.id.subtitleText)
        val badge = row.findViewById<TextView>(R.id.badgeText)

        avatar.text = s.phone.trimStart('+').firstOrNull { it.isLetterOrDigit() }
            ?.uppercaseChar()?.toString() ?: "?"
        name.text = s.phone
        subtitle.text = "${s.messageCount} message" + if (s.messageCount == 1) "" else "s"

        when {
            s.urgent == true -> setBadge(badge, "URGENT", R.color.error, R.color.surface)
            s.hasReport -> setBadge(badge, "Report ready", R.color.positive_soft, R.color.positive)
            else -> badge.visibility = View.GONE
        }

        row.setOnClickListener {
            startActivity(
                Intent(this, ReportActivity::class.java).putExtra(ReportActivity.EXTRA_PHONE, s.phone)
            )
        }
        return row
    }

    private fun setBadge(badge: TextView, text: String, bgColorRes: Int, textColorRes: Int) {
        badge.visibility = View.VISIBLE
        badge.text = text
        badge.setTextColor(ContextCompat.getColor(this, textColorRes))
        (badge.background.mutate() as GradientDrawable).setColor(ContextCompat.getColor(this, bgColorRes))
    }
}
