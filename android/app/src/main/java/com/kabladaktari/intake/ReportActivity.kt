package com.kabladaktari.intake

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar

/**
 * Doctor's view of one patient's pre-consult report: load whatever's already
 * generated, let the doctor (re)generate it, and hand off a draft message to
 * WhatsApp's own share sheet — the doctor reviews/edits there before sending,
 * this app never sends to the patient directly.
 */
class ReportActivity : AppCompatActivity() {

    private lateinit var phone: String
    private var currentReport: Report? = null

    private lateinit var titleView: TextView
    private lateinit var urgentBanner: TextView
    private lateinit var reportCard: MaterialCardView
    private lateinit var body: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var generateButton: Button
    private lateinit var shareButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)

        phone = intent.getStringExtra(EXTRA_PHONE) ?: run {
            finish()
            return
        }

        titleView = findViewById(R.id.reportTitle)
        urgentBanner = findViewById(R.id.urgentBanner)
        reportCard = findViewById(R.id.reportCard)
        body = findViewById(R.id.reportBody)
        status = findViewById(R.id.statusText)
        progress = findViewById(R.id.progressBar)
        generateButton = findViewById(R.id.generateButton)
        shareButton = findViewById(R.id.shareButton)

        titleView.text = phone

        generateButton.setOnClickListener {
            setLoading(true)
            status.text = "Generating report…"
            BackendApi.generateReport(this, phone) { report, error ->
                setLoading(false)
                if (report != null) {
                    render(report)
                    Snackbar.make(shareButton, "Report ready", Snackbar.LENGTH_SHORT).show()
                } else {
                    status.text = "Failed: ${error ?: "unknown error"}"
                }
            }
        }

        shareButton.setOnClickListener {
            val report = currentReport ?: return@setOnClickListener
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, buildShareText(report))
            }
            startActivity(Intent.createChooser(sendIntent, "Share with patient"))
        }

        setLoading(true)
        BackendApi.fetchReport(this, phone) { report, error ->
            setLoading(false)
            if (report != null) {
                render(report)
            } else {
                status.text = "No report yet — tap 'Generate / refresh report'."
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        generateButton.isEnabled = !loading
    }

    private fun render(report: Report) {
        currentReport = report
        urgentBanner.visibility = if (report.urgent) View.VISIBLE else View.GONE
        body.text = formatReport(report)
        reportCard.visibility = View.VISIBLE
        shareButton.visibility = View.VISIBLE
        status.text = ""
    }

    private fun formatReport(r: Report): String {
        fun join(items: List<String>) = items.joinToString(", ").ifBlank { "—" }
        val vitals = listOf(r.vitalsTemperature, r.vitalsOther).filter { it.isNotBlank() }.joinToString(", ")

        return buildString {
            append("Chief complaint: ${r.chiefComplaint.ifBlank { "—" }}\n")
            append("Duration: ${r.duration.ifBlank { "—" }}\n\n")
            append("Symptoms: ${join(r.symptoms)}\n")
            append("Self-reported vitals: ${vitals.ifBlank { "—" }}\n")
            append("Current medications: ${join(r.medications)}\n")
            append("Allergies: ${join(r.allergies)}\n")
            append("Relevant history: ${join(r.history)}\n\n")
            append("Red flags: ${if (r.redFlags.isEmpty()) "None reported" else join(r.redFlags)}\n\n")
            append("Summary for doctor:\n${r.summaryForDoctor}\n\n")
            append(r.disclaimer)
        }
    }

    private fun buildShareText(r: Report): String {
        val followUp = if (r.urgent) {
            "Please come in or seek emergency care immediately."
        } else {
            "Please come in for a consultation when convenient, and bring this message."
        }
        return "Hi, thanks for sharing your symptoms. ${r.summaryForDoctor}\n\n$followUp"
    }

    companion object {
        const val EXTRA_PHONE = "extra_phone"
    }
}
