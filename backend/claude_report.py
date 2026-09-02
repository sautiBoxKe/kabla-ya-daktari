"""
Turns a raw WhatsApp-style intake transcript into a structured pre-consult
brief for a clinician. This is the ONLY place Claude sits in this build.

Design intent ("Kazi: Kabla ya Daktari" — before the doctor):
  Claude reads what the patient already typed and organizes it so a
  clinician can scan it in 15 seconds before the patient walks in.
  It does NOT diagnose, it does NOT suggest treatment, and it always
  escalates anything that sounds urgent instead of judging severity itself.
"""

import json
import os
import re
from datetime import datetime, timezone
from typing import Any

from anthropic import Anthropic

# Anthropic model ids are date-versioned (e.g. claude-sonnet-4-5-20250929).
# Check console.anthropic.com for the exact current id and set CLAUDE_MODEL
# in your .env — this default is a best-effort fallback only.
CLAUDE_MODEL_DEFAULT = "claude-sonnet-4-5-20250929"

SYSTEM_PROMPT = """You are the intake-summarizing component inside "Kabla ya Daktari" \
("before the doctor"), a tool used in Kenyan clinics. A patient has been texting \
their symptoms to a WhatsApp intake line. You receive that raw chat transcript and \
must turn it into a short, structured pre-consult brief for the clinician who is \
about to see this patient.

Hard rules — these are safety-critical, not stylistic preferences:
1. Never state or imply a diagnosis. Do not name a probable condition, even tentatively.
2. Never recommend a medication, supplement, or dosage the patient did not already \
   report taking themselves. You may list what they say they're already taking.
3. Never tell the patient their symptoms are minor, normal, or nothing to worry about.
4. If the transcript contains ANY red-flag content — chest pain, trouble breathing, \
   severe or uncontrolled bleeding, stroke-like symptoms (face drooping, slurred \
   speech, one-sided weakness), signs of severe allergic reaction, suicidal ideation, \
   unresponsiveness/fainting, a child under 5 with a high fever, or pregnancy with \
   heavy bleeding or severe abdominal pain — set "urgent" to true and say the patient \
   should seek in-person or emergency care immediately. Do not try to judge how \
   urgent beyond that binary; when unsure, set urgent true.
5. Only include information the patient (or whoever is texting on their behalf) \
   actually stated. Never invent vitals, durations, or history that weren't mentioned. \
   If a field has no information, use an empty list/string — do not guess.
6. Write for a busy clinician: short, factual, no filler, no bedside-manner language.
7. This is always a support summary for a licensed clinician to verify against the \
   patient in person — never the final word.

Respond with ONLY a single valid JSON object, no markdown fences, no prose before or \
after, matching exactly this schema:

{
  "chief_complaint": "string, one line",
  "duration": "string, e.g. '3 days' or 'since this morning', empty string if unstated",
  "symptoms": ["string", "..."],
  "self_reported_vitals": {"temperature": "string or empty", "other": "string or empty"},
  "current_medications": ["string", "..."],
  "allergies": ["string", "..."],
  "relevant_history": ["string", "... (e.g. pregnant, diabetic, asthmatic, age if stated)"],
  "red_flags": ["string", "... specific phrases that triggered concern, empty list if none"],
  "urgent": true or false,
  "summary_for_doctor": "string, 2-4 sentences, factual synthesis",
  "disclaimer": "AI-generated summary from patient-reported chat only. Not a diagnosis. Clinician must verify directly with the patient."
}
"""


def _transcript_to_text(messages: list[dict[str, Any]]) -> str:
    lines = []
    for m in messages:
        who = "Patient" if not m.get("from_clinic") else "Clinic"
        ts = m.get("ts", "")
        lines.append(f"[{ts}] {who}: {m.get('text', '')}")
    return "\n".join(lines)


def _mock_report(messages: list[dict[str, Any]]) -> dict[str, Any]:
    """Used when ANTHROPIC_API_KEY isn't set, so the app still runs end to end."""
    text = " ".join(m.get("text", "") for m in messages).lower()
    urgent = any(
        kw in text
        for kw in ["chest pain", "can't breathe", "cannot breathe", "bleeding a lot", "unconscious"]
    )
    return {
        "chief_complaint": "[MOCK — set ANTHROPIC_API_KEY for a real report]",
        "duration": "",
        "symptoms": [m.get("text", "") for m in messages if not m.get("from_clinic")][:5],
        "self_reported_vitals": {"temperature": "", "other": ""},
        "current_medications": [],
        "allergies": [],
        "relevant_history": [],
        "red_flags": ["mock mode — not evaluated"] if urgent else [],
        "urgent": urgent,
        "summary_for_doctor": "This is a mock report because no ANTHROPIC_API_KEY is configured. "
        "Set it in backend/.env to get a real Claude-generated brief.",
        "disclaimer": "AI-generated summary from patient-reported chat only. Not a diagnosis. "
        "Clinician must verify directly with the patient.",
        "_mock": True,
    }


def _extract_json(raw: str) -> dict[str, Any]:
    raw = raw.strip()
    # Strip markdown fences if the model added them despite instructions.
    raw = re.sub(r"^```(json)?", "", raw).strip()
    raw = re.sub(r"```$", "", raw).strip()
    return json.loads(raw)


def generate_report(messages: list[dict[str, Any]]) -> dict[str, Any]:
    """messages: list of {"text": str, "ts": str, "from_clinic": bool}."""
    api_key = os.environ.get("ANTHROPIC_API_KEY", "").strip()
    if not api_key:
        report = _mock_report(messages)
    else:
        client = Anthropic(api_key=api_key)
        model = os.environ.get("CLAUDE_MODEL", "").strip() or CLAUDE_MODEL_DEFAULT
        transcript = _transcript_to_text(messages)
        resp = client.messages.create(
            model=model,
            max_tokens=1024,
            system=SYSTEM_PROMPT,
            messages=[{"role": "user", "content": f"Transcript:\n{transcript}"}],
        )
        raw = "".join(block.text for block in resp.content if block.type == "text")
        try:
            report = _extract_json(raw)
        except (json.JSONDecodeError, ValueError):
            report = {
                "chief_complaint": "[PARSE ERROR — see raw_response]",
                "duration": "",
                "symptoms": [],
                "self_reported_vitals": {"temperature": "", "other": ""},
                "current_medications": [],
                "allergies": [],
                "relevant_history": [],
                "red_flags": [],
                "urgent": True,
                "summary_for_doctor": "The model response could not be parsed as JSON. "
                "Read raw_response and check the transcript with the patient directly.",
                "disclaimer": "AI-generated summary from patient-reported chat only. Not a diagnosis. "
                "Clinician must verify directly with the patient.",
                "raw_response": raw,
            }

    report["generated_at"] = datetime.now(timezone.utc).isoformat()
    return report
