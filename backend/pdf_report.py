"""
Builds a short, patient-facing PDF from an already-generated report — a
simpler companion to the full clinician brief in claude_report.py, meant to
be handed to the patient rather than read by the doctor. No extra Claude
call: it's a deterministic template over the same structured fields.
"""

from typing import Any

from fpdf import FPDF

INK = (27, 26, 36)
INK_MID = (86, 81, 95)
ERROR = (163, 61, 73)
BODY = (50, 50, 50)


def build_patient_pdf(phone: str, report: dict[str, Any]) -> bytes:
    pdf = FPDF(format="A4")
    pdf.set_auto_page_break(auto=True, margin=18)
    pdf.add_page()
    pdf.set_margins(18, 18, 18)

    pdf.set_font("Helvetica", "B", 16)
    pdf.set_text_color(*INK)
    pdf.cell(0, 10, "Kabla ya Daktari - Visit Summary", ln=True)

    pdf.set_font("Helvetica", "", 10)
    pdf.set_text_color(*INK_MID)
    pdf.cell(0, 6, f"Prepared for: {phone}", ln=True)
    pdf.ln(6)

    def section(title: str, body: str) -> None:
        pdf.set_font("Helvetica", "B", 12)
        pdf.set_text_color(*INK)
        pdf.cell(0, 8, title, ln=True)
        pdf.set_font("Helvetica", "", 11)
        pdf.set_text_color(*BODY)
        pdf.multi_cell(0, 6, body or "Not stated")
        pdf.ln(3)

    section("Chief complaint", report.get("chief_complaint", ""))
    section("Duration", report.get("duration", ""))
    section("Symptoms", ", ".join(report.get("symptoms", [])) or "None reported")

    meds = ", ".join(report.get("current_medications", []))
    if meds:
        section("Current medications", meds)

    pdf.ln(2)
    if report.get("urgent"):
        pdf.set_font("Helvetica", "B", 12)
        pdf.set_text_color(*ERROR)
        pdf.multi_cell(0, 7, "IMPORTANT: Please seek in-person or emergency care as soon as possible.")
    else:
        pdf.set_font("Helvetica", "B", 12)
        pdf.set_text_color(*INK)
        pdf.multi_cell(0, 7, "Please come in for your consultation as advised by the clinic.")
    pdf.ln(4)

    pdf.set_font("Helvetica", "I", 9)
    pdf.set_text_color(120, 120, 120)
    pdf.multi_cell(
        0,
        5,
        report.get(
            "disclaimer",
            "AI-generated summary from patient-reported chat only. Not a diagnosis. "
            "Clinician must verify directly with the patient.",
        ),
    )

    return bytes(pdf.output())
