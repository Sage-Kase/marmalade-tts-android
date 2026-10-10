#!/usr/bin/env python3
"""Transcribe the speed stage's clips for its lab (release-smoke.py --whisper-python).

Run by a Python that has faster_whisper (e.g. a scratch venv); the gate itself
doesn't need it. Reads [{"wav": path, "lang": "en"}, ...] on stdin, prints
{path: transcript} as JSON. Offline: the model must already be in the
Hugging Face cache (MTTS_WHISPER_MODEL, default "small", multilingual).
"""
import json
import os
import sys

os.environ.setdefault("HF_HUB_OFFLINE", "1")
from faster_whisper import WhisperModel  # noqa: E402

model = WhisperModel(os.environ.get("MTTS_WHISPER_MODEL", "small"), device="cpu", compute_type="int8")
out = {}
for job in json.load(sys.stdin):
    segments, _ = model.transcribe(job["wav"], language=job["lang"], beam_size=5)
    out[job["wav"]] = " ".join(s.text.strip() for s in segments)
print(json.dumps(out, ensure_ascii=False))
