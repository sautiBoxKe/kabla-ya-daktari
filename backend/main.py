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
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from claude_report import generate_report

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

# In-memory only — restart wipes it. Fine for a one-day demo; swap for a real
# datastore before this touches actual patient data.
SESSIONS: dict[str, dict] = {}

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
    return SESSIONS.setdefault(phone, {"messages": [], "report": None})


@app.get("/")
def index():
    return FileResponse(STATIC_DIR / "index.html")


@app.post("/message")
def post_message(msg: IncomingMessage, x_intake_token: Optional[str] = Header(default=None)):
    _check_token(x_intake_token)
    session = _get_session(msg.phone)
    session["messages"].append(
        {
            "text": msg.text,
            "from_clinic": msg.from_clinic,
            "ts": msg.ts or datetime.now(timezone.utc).isoformat(),
        }
    )
    session["report"] = None  # new message invalidates any prior report
    return {"ok": True, "phone": msg.phone, "message_count": len(session["messages"])}


@app.get("/sessions")
def list_sessions():
    return [
        {
            "phone": phone,
            "message_count": len(s["messages"]),
            "has_report": s["report"] is not None,
        }
        for phone, s in SESSIONS.items()
    ]


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
