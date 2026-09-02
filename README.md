# Kabla ya Daktari — "before the doctor"

Track: **Kazi: Kabla ya Daktari** — Claude Impact Lab / AI Mashinani 02, Nairobi.

Patients text their symptoms to the clinic's WhatsApp intake number before
they arrive. This turns that raw chat into a short, structured pre-consult
brief the clinician can scan in 15 seconds — never a diagnosis, never a
treatment suggestion, always an escalation flag when something sounds urgent.

## How it works

```
Patient's WhatsApp  --texts-->  Clinic's WhatsApp (intake phone)
                                        |
                        [Android notification-listener app]
                          reads the notification banner,
                          POSTs {phone, text} to the backend
                                        |
                                        v
                         FastAPI backend (backend/main.py)
                        buffers the transcript per phone number
                                        |
                         clinician clicks "generate report"
                                        |
                                        v
                    Claude (backend/claude_report.py) reads the
                 transcript and returns a structured JSON brief:
        chief complaint, symptoms, duration, self-reported vitals,
      current meds, allergies, history, red flags, urgent y/n, summary
                                        |
                                        v
                  backend/static/index.html — the doctor's view
```

**Where Claude sits, and what it must never do** (see `SYSTEM_PROMPT` in
`backend/claude_report.py` for the enforced version): Claude only
*structures* what the patient already typed. It never states or implies a
diagnosis, never suggests a medication or dosage the patient didn't already
mention taking, and never tells a patient their symptoms are minor. Any
red-flag content (chest pain, breathing trouble, stroke signs, severe
bleeding, suicidal ideation, a feverish child under 5, etc.) sets
`urgent: true` and an instruction to seek care immediately — Claude doesn't
try to grade severity beyond that. Every report carries a disclaimer that
it's AI-generated and the clinician must verify with the patient directly.

## Consent & scope — read this before you demo it as "real"

The Android piece reads notifications the way
[fetchWhatsappdata](https://github.com/suyashm002/fetchWhatsappdata) does —
no root, no touching WhatsApp's database. That's only ethical if it runs on
**the clinic's own intake phone** (the WhatsApp account patients are
knowingly texting), not on a patient's personal device. Don't install this
on anyone's phone without them knowing you're doing it.

Two real limitations, worth stating plainly in the submission ("what works
live, what is mocked"):
- Android truncates long notification text and never exposes media (photos,
  voice notes) — only what's in the notification banner. Fine for a typed
  symptom chat, not for anything richer.
- The "phone" field is really "whatever label WhatsApp puts in the
  notification" (a saved contact name or a raw number) — not a verified
  phone number. A real deployment should pull the number from the WhatsApp
  Business Platform API instead of trusting the notification title.

## Running it

### 1. Backend

```bash
cd backend
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp ../.env.example ../.env   # then fill in ANTHROPIC_API_KEY
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

Open http://localhost:8000 — that's the doctor's report view.

No `ANTHROPIC_API_KEY` set yet? The backend still runs and returns a clearly
labeled mock report, so you can wire up the rest of the demo first.

### 2. Demo without the phone (recommended primary demo path)

```bash
cd demo
pip install -r requirements.txt
python3 simulate.py                 # a normal symptom chat
python3 simulate.py --urgent        # a red-flag chat, to show the escalation
```

Then in the browser: pick the phone number from the dropdown, click
**Generate / refresh report**, and narrate the JSON→brief transform live.
This is the reliable path — recommend leading the demo with it.

### 3. Demo with the real Android app (bonus, if time allows)

1. Open `android/` in Android Studio, run on a phone that's logged into a
   spare WhatsApp/WhatsApp Business account acting as the "clinic line."
2. In the app, set **Backend URL** to your laptop's LAN IP
   (`http://<your-ip>:8000`, not `localhost`) and the shared token to match
   `INTAKE_SHARED_SECRET` in your `.env`. Tap **Enable notification access**
   and turn the app on.
3. From a second phone, text that WhatsApp number a symptom conversation.
   Watch messages land in `GET /sessions` and the report view live.

## Submission form — draft language

**One sentence:** Kabla ya Daktari turns a patient's WhatsApp symptom chat
into a structured pre-consult brief for the clinician, before the patient
even reaches the exam room.

**Where Claude sits in the build:** Claude (Sonnet) reads the raw WhatsApp
intake transcript and structures it into a pre-consult brief — chief
complaint, duration, self-reported vitals, current medications, allergies,
and flagged red-flag symptoms. It must never issue a diagnosis, never
suggest a drug or dosage, and never downplay a patient's symptoms; any
red-flag content sets an explicit "seek care now" flag instead of a
severity judgment. Every output carries an AI-generated-summary disclaimer
for clinician review.

## Repo layout

```
backend/    FastAPI app + Claude integration + doctor-facing report view
demo/       simulate.py — scripted WhatsApp conversation for a reliable demo
android/    Minimal Kotlin app: NotificationListenerService for the clinic's
            intake phone, posts to backend/main.py's /message endpoint
```
