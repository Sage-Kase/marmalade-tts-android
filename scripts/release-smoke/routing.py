"""Which voice actually spoke a gate request, from the app's own log (#50).

The app's TTS service doesn't always speak the voice a client asks for: a
per-app mapping wins over it, and a request for the voice the app advertises
as its default (Kitten's Bella, the catalog default) reads as an auto-filled
echo and goes to the user's primary alias, with that alias's speed and
effect. On the gate's rc the primary is Pocket, so until #50 every "Kitten"
row was timed on Pocket. The service logs one line per request
(MarmaladeTtsService, Info, so R8 keeps it):

    onSynthesizeText: asked=<voice or -> voice=<voice> engine=<engine> speed=<x> effectBlocks=<n> chars=<n>

and a row counts only when the voice it asked for spoke it, at the rate it
asked for, with no effect.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

TAG = "MarmaladeTtsService"
LINE_RE = re.compile(r"onSynthesizeText: asked=(\S+) voice=(\S+) engine=(\S+) speed=([\d.]+) effectBlocks=(\d+)")


@dataclass
class Resolved:
    asked: str
    voice: str
    engine: str
    speed: float
    effects: int


def parse(log: str) -> list[Resolved]:
    return [Resolved(m.group(1), m.group(2), m.group(3), float(m.group(4)), int(m.group(5)))
            for m in LINE_RE.finditer(log)]


def mismatch(voice: str, rate: float, seen: list[Resolved]) -> str | None:
    """Why the request(s) in [seen] didn't speak [voice] at [rate], or None when they did."""
    if not seen:
        return (f"the app logged no request line ({TAG} onSynthesizeText at Info): "
                "a build from before #50, or the request never reached the service")
    for r in seen:
        if r.voice != voice:
            return (f"spoken by {r.voice}, not {voice}: the app routed the request to an alias "
                    "(the primary, for its default voice) or a per-app mapping")
        if abs(r.speed - rate) > 0.01:
            return f"spoken at {r.speed:g}x, not {rate:g}x: an alias's speed applied"
        if r.effects:
            return f"an alias's effect applied ({r.effects} effect blocks)"
    return None
