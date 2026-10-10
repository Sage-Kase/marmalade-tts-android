"""The release gate's installed-bundle check: the pure parts (no phone).

The gate must time the engines users get. The rc's engine files are whatever
its Engines screen last installed, which can be an older download than the
build's catalog offers (#51: the 6a's Kokoro was still the pre-v26 bundle
while the gate's table said v26). The rc isn't debuggable, so the gate can't
read each engine's .install_meta.json. Instead the app logs one line per
engine and voice pack on disk whenever its TTS service starts
(install/InstalledBundle.kt, tag EngineBundle):

  kokoro-direct-v1_0 installed=v23 catalog=v26 OUTDATED sha=0123456789ab
  vits-marmalade-v1:<pack id> installed=v24 catalog=v24 current sha=0123456789ab

release-smoke.py restarts the app, reads those lines, and refuses to measure
when an engine under test isn't on its catalog download.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

BUNDLE_RE = re.compile(r"\bEngineBundle\s*:\s*(\S+) installed=(\S+) catalog=(\S+) (current|OUTDATED) sha=(\S+)")


@dataclass(frozen=True)
class Bundle:
    name: str  # engine name, or "<engine>:<pack id>" for a voice pack
    installed: str  # release tag the files came from ("v23"), "?" without install meta
    catalog: str  # the release the build's catalog downloads
    current: bool


def parse(log: str) -> dict[str, Bundle]:
    """The EngineBundle lines in [log], by name; a later line wins."""
    out = {}
    for m in BUNDLE_RE.finditer(log):
        name, installed, catalog, state, _ = m.groups()
        out[name] = Bundle(name, installed, catalog, state == "current")
    return out


def key(engine: str, voice: str | None, vits_engine: str) -> str:
    """The bundle a voice speaks from: its engine's, or for VITS its pack's."""
    if engine == vits_engine and voice and ":" in voice:
        return f"{engine}:{voice.split(':', 1)[1].split('#')[0]}"
    return engine


def problems(bundles: dict[str, Bundle], wanted: list[str]) -> list[str]:
    """Why the gate shouldn't measure: each wanted bundle that is on the phone
    but not on its catalog download. One that isn't on the phone at all is the
    stages' to report (a FAIL row), not this check's."""
    out = []
    for name in wanted:
        b = bundles.get(name)
        if b and not b.current:
            got = "a download with no install record" if b.installed == "?" else f"the {b.installed} download"
            out.append(f"{name}: installed from {got}, the catalog offers {b.catalog}")
    return out


def label(bundles: dict[str, Bundle], name: str) -> str:
    """The table's bundle column: what is installed, not what the catalog says."""
    b = bundles.get(name)
    if b is None:
        return "-"
    return b.installed if b.current else f"{b.installed} (catalog {b.catalog})"
