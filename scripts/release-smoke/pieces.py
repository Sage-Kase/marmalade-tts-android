"""How the app sent a gate request, from its own log (#54): the pure parts.

The streaming engines send a piece (a sentence, or a clause piece of one)
slice by slice. When rendering is slower than playback, playback runs dry
inside a sentence: a stutter. Chunk mode, decided per request, holds each
piece's slices until the rest of the piece can't run dry, so any wait falls
between pieces. The app logs two Info lines per request (R8 keeps both):

  RenderMode: kokoro-direct-v1_0 mode=chunk rtf=0.43 (predicted) speed=2.00 played=0.86 path=sliced
  MarmaladeTtsService: pieces: starts=0,52800,118080 samples=240000

The first comes from the engine (Kokoro, Kokoro German, Kitten, Pocket; VITS
renders whole sentences and logs none). path=whole: the engine renders every
piece whole before sending it, so it can't stall inside one either. The
second comes at the end of the request: where each piece starts in the audio
the client receives, in samples (16-bit mono, so piece i starts at byte
2 * starts[i]). speed.replay() uses it to tell a wait between pieces from a
stall inside one.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

MODE_TAG = "RenderMode"
PIECES_TAG = "MarmaladeTtsService"
# Kotlin's "%.2f" follows the phone's locale, so accept a decimal comma too.
MODE_RE = re.compile(r"\bRenderMode\s*:\s*(\S+) mode=(chunk|stream) rtf=(\S+) \((\w+)\) "
                     r"speed=([\d.,]+) played=(\S+) path=(\w+)")
PIECES_RE = re.compile(r"\bMarmaladeTtsService\s*:\s*pieces: starts=([\d,]*) samples=(\d+)")


@dataclass(frozen=True)
class RenderMode:
    engine: str
    mode: str  # chunk | stream
    rtf: float | None  # render ms per audio ms at 1.0x the decision used; None = unknown
    source: str  # engine | measured | predicted | none | forced
    speed: float
    path: str  # sliced | whole

    @property
    def label(self) -> str:
        """The table's mode: chunk, stream, or whole (every piece rendered whole)."""
        return "whole" if self.path == "whole" else self.mode

    @property
    def chunked(self) -> bool:
        """Sent in whole pieces, so a stall inside one is a bug."""
        return self.mode == "chunk" or self.path == "whole"


@dataclass(frozen=True)
class Pieces:
    starts: tuple[int, ...]  # sample offset of each piece in the audio the client got
    samples: int


def _num(s: str) -> float | None:
    return None if s == "-" else float(s.replace(",", "."))


def parse_modes(log: str) -> list[RenderMode]:
    return [RenderMode(m.group(1), m.group(2), _num(m.group(3)), m.group(4), _num(m.group(5)), m.group(7))
            for m in MODE_RE.finditer(log)]


def parse_pieces(log: str) -> list[Pieces]:
    return [Pieces(tuple(int(s) for s in m.group(1).split(",") if s), int(m.group(2)))
            for m in PIECES_RE.finditer(log)]


def latest(log: str) -> tuple[RenderMode | None, Pieces | None]:
    """The last mode line and the last pieces line in [log] (one request's), None when absent."""
    modes, pieces = parse_modes(log), parse_pieces(log)
    return (modes[-1] if modes else None), (pieces[-1] if pieces else None)
