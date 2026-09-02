package com.kabladaktari.intake

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import java.io.File

/**
 * Doctor's view of one patient's pre-consult report: load whatever's already
 * generated, let the doctor (re)generate it, and hand off a draft message to
 * WhatsApp's own share sheet — the doctor reviews/edits there before sending,
 * this app never sends to the patient directly. Also offers a short PDF
 * export, and a way to wipe this patient's data once it's no longer needed.
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
    private lateinit var sharePdfButton: Button

    private val coldStartHandler = Handler(Looper.getMainLooper())
    private val coldStartHint = Runnable {
        status.text = "Waking up the backend — free-tier can take up to 50s if it's been idle…"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)

        phone = intent.getStringExtra(EXTRA_PHONE) ?: run {
            finish()
            return
        }

        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }

        titleView = findViewById(R.id.reportTitle)
        urgentBanner = findViewById(R.id.urgentBanner)
        reportCard = findViewById(R.id.reportCard)
        body = findViewById(R.id.reportBody)
        status = findViewById(R.id.statusText)
        progress = findViewById(R.id.progressBar)
        generateButton = findViewById(R.id.generateButton)
        shareButton = findViewById(R.id.shareButton)
        sharePdfButton = findViewById(R.id.sharePdfButton)

        titleView.text = phone

        generateButton.setOnClickListener {
            setLoading(true)
            status.text = "Generating report…"
            coldStartHandler.postDelayed(coldStartHint, 5000)
            BackendApi.generateReport(this, phone) { report, error ->
                coldStartHandler.removeCallbacks(coldStartHint)
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

        sharePdfButton.setOnClickListener { onSharePdf() }

        findViewById<TextView>(R.id.clearButton).setOnClickListener { confirmClear() }

        setLoading(true)
        coldStartHandler.postDelayed(coldStartHint, 5000)
        BackendApi.fetchReport(this, phone) { report, error ->
            coldStartHandler.removeCallbacks(coldStartHint)
            setLoading(false)
            if (report != null) {
                render(report)
            } else {
                status.text = "No report yet — tap 'Generate / refresh report'."
            }
        }
    }

    override fun onPause() {
        super.onPause()
        coldStartHandler.removeCallbacks(coldStartHint)
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
        sharePdfButton.visibility = View.VISIBLE
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

    private fun onSharePdf() {
        sharePdfButton.isEnabled = false
        BackendApi.downloadReportPdf(this, phone) { bytes, error ->
            sharePdfButton.isEnabled = true
            if (bytes == null) {
                Snackbar.make(sharePdfButton, "Couldn't get PDF: ${error ?: "unknown error"}", Snackbar.LENGTH_LONG)
                    .show()
                return@downloadReportPdf
            }
            try {
                val dir = File(cacheDir, "reports").apply { mkdirs() }
                val safeName = phone.replace(Regex("[^A-Za-z0-9]+"), "_").ifBlank { "patient" }
                val file = File(dir, "visit-summary-$safeName.pdf")
                file.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(sendIntent, "Share PDF with patient"))
            } catch (e: Exception) {
                Snackbar.make(sharePdfButton, "Couldn't share PDF: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear this patient's data?")
            .setMessage(
                "This deletes their messages and report from the backend. It can't be undone, " +
                    "and it doesn't touch anything in WhatsApp itself."
            )
            .setPositiveButton("Clear") { _, _ -> clearSession() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearSession() {
        BackendApi.deleteSession(this, phone) { ok, error ->
            if (ok) {
                finish()
            } else {
                Snackbar.make(shareButton, "Couldn't clear: ${error ?: "unknown error"}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_PHONE = "extra_phone"
    }
}
