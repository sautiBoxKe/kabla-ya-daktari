#!/usr/bin/env python3
"""
Demo fallback: feeds a scripted WhatsApp-style symptom chat into the backend,
one message at a time, so you have a reliable live demo even if the Android
notification-listener app isn't wired up in time.

Usage:
    python demo/simulate.py                      # normal case, default phone
    python demo/simulate.py --urgent              # red-flag case
    python demo/simulate.py --phone +254712345678 --delay 1.5
    python demo/simulate.py --host http://192.168.1.20:8000   # phone on same wifi
"""

import argparse
import os
import time

import requests

NORMAL_CONVO = [
    "Hi, naomba kuongea na daktari kabla sijafika",
    "I've had a headache and mild fever since yesterday evening",
    "Temperature was 38.1 this morning, checked with a thermometer at home",
    "Also feeling tired, no cough, no vomiting",
    "I'm currently taking paracetamol, took one 500mg tablet last night",
    "No known allergies",
    "I'm 29, no other health conditions, not pregnant",
]

URGENT_CONVO = [
    "Hello, I need help, my father is not feeling well",
    "He's 61, he's complaining of chest pain since 20 minutes ago",
    "He also says he's having trouble breathing",
    "He is sweating a lot and looks pale",
    "He takes medication for high blood pressure, amlodipine",
    "No known allergies that we know of",
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="http://localhost:8000")
    parser.add_argument("--phone", default="+254700000001")
    parser.add_argument("--delay", type=float, default=2.0, help="seconds between messages")
    parser.add_argument("--urgent", action="store_true", help="use the red-flag demo script")
    parser.add_argument("--token", default=os.environ.get("INTAKE_SHARED_SECRET", ""))
    args = parser.parse_args()

    convo = URGENT_CONVO if args.urgent else NORMAL_CONVO
    headers = {"X-Intake-Token": args.token} if args.token else {}

    print(f"Sending {len(convo)} messages to {args.host} as {args.phone} "
          f"({'URGENT' if args.urgent else 'normal'} script)\n")

    for text in convo:
        print(f"  Patient: {text}")
        resp = requests.post(
            f"{args.host}/message",
            json={"phone": args.phone, "text": text, "from_clinic": False},
            headers=headers,
            timeout=10,
        )
        resp.raise_for_status()
        time.sleep(args.delay)

    print(f"\nDone. Open {args.host}/ , pick {args.phone} from the dropdown, "
          f"and click 'Generate / refresh report'.")
    print(f"Or trigger it here too: curl -X POST {args.host}/report/{args.phone.replace('+', '%2B')}")


if __name__ == "__main__":
    main()
