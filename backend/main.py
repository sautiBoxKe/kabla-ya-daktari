"""
Kabla ya Daktari — backend.

Receives WhatsApp intake messages (from the Android notification-listener app,
or from demo/simulate.py), buffers them per phone number, and calls Claude
(see claude_report.py) to turn the transcript into a pre-consult brief.

Run:
    cd backend
    pip install -r requirements.txt
    uvicorn main:app --reload --host 0.0.0.0 --port 8000
"""

import os
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Optional

from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from claude_report import classify_is_medical, generate_report
from pdf_report import build_patient_pdf

load_dotenv(Path(__file__).resolve().parent.parent / ".env")

app = FastAPI(title="Kabla ya Daktari intake API")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # hackathon speed; tighten before any real deployment
    allow_methods=["*"],
    allow_headers=["*"],
)

STATIC_DIR = Path(__file__).resolve().parent / "static"
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")

# In-memory only — restart wipes it. Never persisted to disk or a database:
# patient-reported symptoms shouldn't outlive the reason they were collected.
# _purge_stale_sessions() below also age this out on a timer, and a doctor can
# clear a session immediately via DELETE /session/{phone}.
SESSIONS: dict[str, dict] = {}

# How long a session survives with no new messages before it's auto-purged.
# We can't detect "the patient deleted this WhatsApp chat" without reading
# WhatsApp's own data, which this project deliberately never does (see
# README "Consent & scope") — this timer plus the manual clear action are the
# honest substitute: don't hold patient data any longer than the visit needs.
SESSION_TTL_HOURS = float(os.environ.get("SESSION_TTL_HOURS", "24"))

INTAKE_SHARED_SECRET = os.environ.get("INTAKE_SHARED_SECRET", "").strip()


class IncomingMessage(BaseModel):
    phone: str
    text: str
    from_clinic: bool = False
    ts: Optional[str] = None


def _check_token(x_intake_token: Optional[str]) -> None:
    if INTAKE_SHARED_SECRET and x_intake_token != INTAKE_SHARED_SECRET:
        raise HTTPException(status_code=401, detail="bad or missing X-Intake-Token")


def _get_session(phone: str) -> dict:
    return SESSIONS.setdefault(
        phone, {"messages": [], "report": None, "is_medical": None, "last_activity": None}
    )


def _purge_stale_sessions() -> None:
    cutoff = datetime.now(timezone.utc) - timedelta(hours=SESSION_TTL_HOURS)
    stale = [
        phone
        for phone, s in SESSIONS.items()
        if s.get("last_activity") is not None and s["last_activity"] < cutoff
    ]
    for phone in stale:
        del SESSIONS[phone]


@app.get("/")
def index():
    return FileResponse(STATIC_DIR / "index.html")


@app.post("/message")
def post_message(msg: IncomingMessage, x_intake_token: Optional[str] = Header(default=None)):
    _check_token(x_intake_token)
    _purge_stale_sessions()
    session = _get_session(msg.phone)
    session["messages"].append(
        {
            "text": msg.text,
            "from_clinic": msg.from_clinic,
            "ts": msg.ts or datetime.now(timezone.utc).isoformat(),
        }
    )
    session["report"] = None  # new message invalidates any prior report
    session["is_medical"] = classify_is_medical(session["messages"])
    session["last_activity"] = datetime.now(timezone.utc)
    return {"ok": True, "phone": msg.phone, "message_count": len(session["messages"])}


@app.get("/sessions")
def list_sessions():
    _purge_stale_sessions()
    return [
        {
            "phone": phone,
            "message_count": len(s["messages"]),
            "has_report": s["report"] is not None,
            "is_medical": s.get("is_medical"),
            "urgent": (s["report"] or {}).get("urgent") if s["report"] else None,
        }
        for phone, s in SESSIONS.items()
    ]


@app.delete("/session/{phone}")
def delete_session(phone: str, x_intake_token: Optional[str] = Header(default=None)):
    """Doctor-triggered wipe of one patient's data — e.g. once the consult is
    done, or if they'd rather clear it than wait out SESSION_TTL_HOURS."""
    _check_token(x_intake_token)
    SESSIONS.pop(phone, None)
    return {"ok": True}


@app.get("/messages/{phone}")
def get_messages(phone: str):
    if phone not in SESSIONS:
        raise HTTPException(status_code=404, detail="no session for that phone number")
    return SESSIONS[phone]["messages"]


@app.post("/report/{phone}")
def create_report(phone: str):
    if phone not in SESSIONS or not SESSIONS[phone]["messages"]:
        raise HTTPException(status_code=404, detail="no messages for that phone number yet")
    report = generate_report(SESSIONS[phone]["messages"])
    SESSIONS[phone]["report"] = report
    return report


@app.get("/report/{phone}")
def get_report(phone: str):
    if phone not in SESSIONS:
        raise HTTPException(status_code=404, detail="no session for that phone number")
    if SESSIONS[phone]["report"] is None:
        raise HTTPException(status_code=404, detail="no report generated yet — POST /report/{phone} first")
    return SESSIONS[phone]["report"]


@app.get("/report/{phone}/pdf")
def get_report_pdf(phone: str):
    """A short, patient-facing version of the report (not the full clinician
    brief) that a doctor can optionally hand over as a PDF."""
    if phone not in SESSIONS or SESSIONS[phone]["report"] is None:
        raise HTTPException(status_code=404, detail="no report generated yet — POST /report/{phone} first")
    pdf_bytes = build_patient_pdf(phone, SESSIONS[phone]["report"])
    return Response(
        content=pdf_bytes,
        media_type="application/pdf",
        headers={"Content-Disposition": 'attachment; filename="visit-summary.pdf"'},
    )
