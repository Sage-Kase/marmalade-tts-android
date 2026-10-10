"""The release gate's speed stage: the pure parts (no phone).

release-smoke.py drives the phone; this module turns what the probe reports
into verdicts. Per (engine, speed) row the probe renders the speed sentence
pair through the public TextToSpeech API with setSpeechRate(speed), to a file
(nothing is played), and timestamps every audio chunk as the engine hands it
over. Replaying those arrivals against a real-time audio clock gives what a
listener would have heard: when the first sound came, and where playback would
have run dry waiting for the next chunk (a stall).

A row fails when the app would mislead the user or a release got slower:
  - no audio, an error, or the speed not applied (length doesn't scale);
  - it stalls at a speed where the app shows no speed warning (the warning
    lies: SpeedPerfWarning with the reference phone's probe prediction);
  - it regressed past a tolerance against speed-baseline.json, the previous
    release's numbers on the same phone (first sound, render RTF, stalls).
A row that stalls where the app DOES warn is reported (WARNED), not failed:
some engines can't keep up at 2x on a Pixel 6a, and the app says so.
"""
from __future__ import annotations

import json
import re
import wave
from dataclasses import dataclass, field
from html import escape
from pathlib import Path

SPEEDS = [1.0, 1.25, 1.5, 2.0]

# One voice per engine (VITS: one per released pack, picked from what the
# phone advertises). Changing one makes its baseline rows missing: refresh
# them on purpose (README, "Baseline").
SPEED_VOICES = {
    "kitten-direct-v0_8": "kitten-direct-v0_8:Bella",
    "kokoro-direct-v1_0": "kokoro-direct-v1_0:af_heart",
    "kokoro-de-v1_0": "kokoro-de-v1_0:thorsten",
    "pocket-tts-en-v2026_04": "pocket-tts-en-v2026_04:alba",
}

# A gap in the replayed audio shorter than this is jitter the audio buffer
# absorbs; it isn't counted at all.
STALL_MIN_MS = 50
# A row keeps up while its longest stall is under this. Shorter stalls land at
# a chunk seam and read as a slightly long pause (on a cool 6a, Jenny stalls
# 90-250 ms after "Good morning." from 1.0x to 2.0x, 2026-10-10: listen in the
# lab's "as heard" column). Longer ones are an audible hang.
KEEPUP_MAX_STALL_MS = 300
# The audio at speed S must last (1.0x length / S) within this fraction:
# otherwise the speed never reached the engine. Pocket samples its prosody, so
# its lengths vary run to run by up to ~15%.
SPEED_LENGTH_TOLERANCE = 0.25

# Regression tolerances against the baseline row (same phone, same voice).
# A row regresses when a value exceeds baseline x factor + slack. Back-to-back
# runs on a cooled 6a (2026-10-10) differ by up to ~10% in render RTF, ~30% in
# first sound and ~25% in a Kokoro stall; these sit above that, so only a real
# slowdown trips them. A warm phone renders up to a third slower, hence the
# cool-down before each engine (release-smoke.py --cool-skin-c).
FIRST_SOUND_FACTOR, FIRST_SOUND_SLACK_MS = 1.5, 200
# Every take starts cold: after a render the gate waits until its audio would
# have finished playing, plus this margin. The app's playback horizon
# (engine/PlaybackHorizon.kt) counts emitted audio as still playing until real
# time catches up, and its chunking reads that: a take rendered right behind
# the last one saw 4-6 s "still ahead" and kept whole sentences, hiding the
# stall a listener meets after a pause (#51).
COLD_MARGIN_MS = 1000
RENDER_RTF_FACTOR, RENDER_RTF_SLACK = 1.25, 0.05
STALL_FACTOR, STALL_SLACK_MS = 1.25, 250


@dataclass
class Take:
    """One render of a row: what the probe saw."""
    first_sound_ms: int
    synth_ms: int
    duration_ms: int
    stalls: list[tuple[float, float]]  # (audio position ms, stall ms)
    wav: str | None = None

    @property
    def longest_stall_ms(self) -> float:
        return max((s for _, s in self.stalls), default=0.0)

    @property
    def render_rtf(self) -> float:
        """Render time / audio length at this speed: under 1 = faster than playback."""
        return self.synth_ms / self.duration_ms if self.duration_ms else 99.0


@dataclass
class SpeedRow:
    engine: str
    voice: str
    speed: float
    bundle: str = "?"
    result: str = "FAIL"  # PASS | WARNED | FAIL
    reasons: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)
    takes: list[Take] = field(default_factory=list)
    warns: bool | None = None  # would the app show the speed warning here?
    predicted_rtf: float | None = None  # the warning model's RTF at 1.0x
    exceptions: list[str] = field(default_factory=list)
    whisper: str | None = None
    whisper_match: float | None = None
    skin_c: float | None = None  # phone skin temperature when the engine's rows started
    mem_available_mb: int | None = None

    @property
    def key(self) -> str:
        return f"{self.voice}@{self.speed:g}"

    # Best of the takes, per metric: a stall that shows in every take is real;
    # one that shows in one is a hiccup (another app, a GC) the gate shouldn't
    # fail a release on. The lab still plays the first take, whatever it was.
    def best(self, metric: str) -> float | None:
        vals = [getattr(t, metric) for t in self.takes]
        return min(vals) if vals else None



def parse_chunks(spec: str) -> list[tuple[int, int]]:
    """The probe's "ms:bytes;ms:bytes" chunk list."""
    return [(int(a), int(b)) for a, b in (c.split(":") for c in spec.split(";") if c)]


def replay(chunks: list[tuple[int, int]], sample_rate: int) -> tuple[int, list[tuple[float, float]]]:
    """Play the chunks in real time from the first one's arrival (16-bit mono).

    Returns the first sound (ms after the request) and every stall of at least
    STALL_MIN_MS as (position in the audio, ms the clock waited). A stall is a
    chunk arriving after everything before it had already played out."""
    if not chunks:
        return -1, []
    first = chunks[0][0]
    clock = float(first)  # wall time at which the queued audio runs out
    played = 0.0  # audio ms queued so far
    stalls = []
    for at, nbytes in chunks:
        if at > clock:
            if at - clock >= STALL_MIN_MS:
                stalls.append((played, at - clock))
            clock = float(at)
        ms = nbytes / 2 / sample_rate * 1000
        clock += ms
        played += ms
    return first, stalls


def cold_wait_ms(first_sound_ms: float, duration_ms: float, stalls: list[tuple[float, float]],
                 synth_ms: float) -> float:
    """How long after a render finished to wait before the next one starts
    cold: the rest of its real-time playback (which began at first sound and
    was held up by every stall), plus COLD_MARGIN_MS."""
    playback_ends = first_sound_ms + duration_ms + sum(s for _, s in stalls)
    return max(0.0, playback_ends - synth_ms) + COLD_MARGIN_MS


def as_heard_name(wav: str) -> str:
    return wav[:-len(".wav")] + "-as-heard.wav"


def write_as_heard(src: Path, stalls: list[tuple[float, float]], dest: Path) -> None:
    """[src] with silence inserted at each stall: what the 6a would have played."""
    with wave.open(str(src), "rb") as w:
        params, frames = w.getparams(), w.readframes(w.getnframes())
    rate, width = params.framerate, params.sampwidth * params.nchannels
    out, pos = bytearray(), 0
    for at_ms, stall_ms in stalls:
        cut = min(len(frames), int(at_ms * rate / 1000) * width)
        out += frames[pos:cut] + b"\0" * (int(stall_ms * rate / 1000) * width)
        pos = cut
    out += frames[pos:]
    with wave.open(str(dest), "wb") as w:
        w.setparams(params)
        w.writeframes(bytes(out))


@dataclass
class WarningModel:
    """SpeedPerfWarning.shouldWarn on a fresh install of the reference phone:
    no measured RTF yet, so the probe prediction (EngineRecommender: the
    reference probe RTF x the engine's cost) decides."""
    anchor_probe_rtf: float
    costs: dict[str, float]  # engine name → REFERENCE_COST
    threshold: float

    def predicted_rtf(self, engine: str) -> float | None:
        cost = self.costs.get(engine)
        return None if cost is None else self.anchor_probe_rtf * cost

    def warns(self, engine: str, speed: float, static_threshold: float = 1.35) -> bool:
        rtf = self.predicted_rtf(engine)
        return speed > static_threshold if rtf is None else rtf * speed > self.threshold


def read_warning_model(repo: Path) -> WarningModel:
    """The warning's constants, read from the Kotlin so the gate can't drift from the app."""
    src = repo / "app/src/main/java/app/marmalade/tts"
    rec = (src / "perf/EngineRecommender.kt").read_text()
    warn = (src / "perf/SpeedPerfWarning.kt").read_text()
    engines = {}
    for f in (src / "data").glob("*VoiceCatalog.kt"):
        m = re.search(r'const val ENGINE = "([\w.-]+)"', f.read_text())
        if m:
            engines[f.stem] = m.group(1)
    engines["VitsVoiceCatalog"] = re.search(
        r'VITS_MARMALADE_ENGINE[^=]*=\s*"([\w.-]+)"',
        (src / "install/VoicePackCatalog.kt").read_text()).group(1)
    alias = {m.group(1): engines[m.group(2)]
             for m in re.finditer(r"private const val (\w+) = (\w+)\.ENGINE", rec)}
    block = re.search(r"REFERENCE_COST[^=]*= mapOf\((.*?)\)", rec, re.S).group(1)
    costs = {alias[k]: float(v) for k, v in re.findall(r"(\w+) to ([\d.]+)", block)}
    anchor = float(re.search(r"ANCHOR_PROBE_RTF: Double = ([\d.]+)", rec).group(1))
    threshold = float(re.search(r"EFFECTIVE_RTF_THRESHOLD: Double = ([\d.]+)", warn).group(1))
    return WarningModel(anchor, costs, threshold)


def released_vits_packs(repo: Path) -> list[str]:
    """Ids of the VITS packs ordinary users can install (VoicePack.released)."""
    src = (repo / "app/src/main/java/app/marmalade/tts/install/VoicePackCatalog.kt").read_text()
    return [m.group(1) for m in re.finditer(r'VoicePack\(\s*id = "([\w.-]+)",(?:(?!VoicePack\().)*?released = true',
                                            src, re.S)]


def judge(row: SpeedRow, model: WarningModel, length_at_1x_ms: float | None, baseline: dict | None) -> None:
    """Fill row.result/reasons/notes from its takes (see the module doc)."""
    row.predicted_rtf = model.predicted_rtf(row.engine)
    row.warns = model.warns(row.engine, row.speed)
    if row.exceptions:
        row.reasons.append(f"app logged: {row.exceptions[0][:120]}")
    if not row.takes:
        if not row.reasons:
            row.reasons.append("no audio")
        row.result = "FAIL"
        return
    duration = row.best("duration_ms")
    if length_at_1x_ms and row.speed != 1.0:
        want = length_at_1x_ms / row.speed
        if abs(duration - want) > SPEED_LENGTH_TOLERANCE * want:
            row.reasons.append(f"speed not applied? {duration / 1000:.2f} s, expected ~{want / 1000:.2f} s")
    stall = row.best("longest_stall_ms")
    keeps_up = stall < KEEPUP_MAX_STALL_MS
    if not keeps_up and not row.warns:
        row.reasons.append(f"stalls {stall:.0f} ms at a speed the app doesn't warn about")
    if baseline is None:
        row.reasons.append("no baseline row: check the numbers, then --write-baseline (README)")
    else:
        checks = [("first sound", row.best("first_sound_ms"), baseline["first_sound_ms"],
                   FIRST_SOUND_FACTOR, FIRST_SOUND_SLACK_MS, "{:.0f} ms"),
                  ("render RTF", row.best("render_rtf"), baseline["render_rtf"],
                   RENDER_RTF_FACTOR, RENDER_RTF_SLACK, "{:.2f}"),
                  ("longest stall", stall, baseline["longest_stall_ms"],
                   STALL_FACTOR, STALL_SLACK_MS, "{:.0f} ms")]
        for name, now, base, factor, slack, fmt in checks:
            if now > base * factor + slack:
                row.reasons.append(f"{name} regressed: {fmt.format(now)} vs baseline {fmt.format(base)}")
    if row.reasons:
        row.result = "FAIL"
    elif not keeps_up:
        row.result = "WARNED"
        row.notes.append(f"stalls {stall:.0f} ms; the app warns at this speed")
    else:
        row.result = "PASS"
        if row.warns:
            row.notes.append("keeps up, but the app warns at this speed")


def baseline_entry(row: SpeedRow, build: str, date: str) -> dict:
    return {"first_sound_ms": round(row.best("first_sound_ms")),
            "render_rtf": round(row.best("render_rtf"), 3),
            "longest_stall_ms": round(row.best("longest_stall_ms")),
            "build": build, "measured": date}


def load_baseline(path: Path) -> dict:
    return json.loads(path.read_text()) if path.exists() else {"rows": {}}


def merge_baseline(path: Path, rows: list[SpeedRow], build: str, date: str, phone: str) -> int:
    """Write the measured rows (only those) into the baseline file. Returns how many."""
    data = load_baseline(path)
    data["phone"] = phone
    data["about"] = ("Speed-stage numbers of the previous release on the reference phone, per voice@speed: "
                     "best of the takes. Refresh on purpose only (scripts/release-smoke/README.md, Baseline).")
    fresh = {r.key: baseline_entry(r, build, date) for r in rows if r.takes and not r.exceptions}
    data["rows"] = dict(sorted({**data.get("rows", {}), **fresh}.items()))
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
    return len(fresh)


def word_match(reference: str, heard: str) -> float:
    """Share of the reference's words Whisper heard, in order (0..1)."""
    import difflib
    norm = lambda s: re.findall(r"\w+", s.lower())
    ref, hyp = norm(reference), norm(heard)
    if not ref:
        return 0.0
    sm = difflib.SequenceMatcher(a=ref, b=hyp, autojunk=False)
    return sum(b.size for b in sm.get_matching_blocks()) / len(ref)


def table(rows: list[SpeedRow]) -> list[list[str]]:
    out = []
    for r in rows:
        stalls = r.best("longest_stall_ms")
        out.append([r.engine, r.voice.split(":", 1)[-1], f"{r.speed:g}x", r.bundle, r.result,
                    f"{r.best('first_sound_ms'):.0f} ms" if r.takes else "-",
                    f"{r.best('render_rtf'):.2f}" if r.takes else "-",
                    (f"{stalls:.0f} ms" if stalls else "none") if r.takes else "-",
                    "-" if r.warns is None else ("yes" if r.warns else "no"),
                    "; ".join(r.reasons + r.notes)[:160]])
    return out


TABLE_COLS = ["engine", "voice", "speed", "bundle", "result", "first sound", "render RTF",
              "longest stall", "app warns", "why"]

ENGINE_NAMES = {"kitten-direct-v0_8": "Kitten", "kokoro-direct-v1_0": "Kokoro",
                "kokoro-de-v1_0": "Kokoro German", "pocket-tts-en-v2026_04": "Pocket",
                "vits-marmalade-v1": "VITS"}


def write_lab(out: Path, rows: list[SpeedRow], meta: dict, texts: dict[str, str]) -> Path:
    """A mobile-first listening lab next to the clips (out/speed-lab.html).

    Meant to be copied into a served labs directory with its speed/ folder."""
    def verdict(r: SpeedRow) -> str:
        cls = {"PASS": "ok", "WARNED": "warn", "FAIL": "bad"}[r.result]
        return f'<span class="v {cls}">{r.result}</span>'

    sections = []
    for engine in dict.fromkeys(r.engine for r in rows):
        mine = [r for r in rows if r.engine == engine]
        voice = mine[0].voice.split(":", 1)[-1]
        pred = mine[0].predicted_rtf
        trs = []
        for r in mine:
            t = r.takes[0] if r.takes else None
            clip = f'<audio controls preload="none" src="{escape(t.wav)}"></audio>' if t and t.wav else "-"
            heard_wav = as_heard_name(t.wav) if t and t.wav and t.stalls else None
            heard = (f'<audio controls preload="none" src="{escape(heard_wav)}"></audio>'
                     if heard_wav else "same (no stall)")
            stalls = ", ".join(f"{s:.0f} ms at {a / 1000:.1f} s" for a, s in t.stalls) if t else "-"
            best = r.best("longest_stall_ms")
            if t and best != t.longest_stall_ms:
                stalls = (stalls or "none") + f" (best take: {f'{best:.0f} ms' if best else 'none'})"
            whisper = (f'{r.whisper_match:.0%} <small>“{escape(r.whisper or "")}”</small>'
                       if r.whisper_match is not None else "not run")
            cells = [f"{r.speed:g}×", verdict(r), clip, heard,
                     f"{r.best('first_sound_ms'):.0f} ms" if t else "-", escape(stalls or "none"),
                     "yes" if r.warns else "no", whisper, escape("; ".join(r.reasons + r.notes)) or "-"]
            trs.append("<tr>" + "".join(f"<td>{c}</td>" for c in cells) + "</tr>")
        lang = "de" if engine == "kokoro-de-v1_0" else "en"
        sections.append(f"""
<h2>{escape(ENGINE_NAMES.get(engine, engine))} <small>({escape(voice)})</small></h2>
<p class="meta">Warning model on the 6a: predicted RTF {f'{pred:.2f}' if pred else '-'} at 1.0×, so the app warns
from {_warn_from(pred, meta['threshold'])}. Measured from skin {mine[0].skin_c} °C with
{mine[0].mem_available_mb} MB free. Text: “{escape(texts[lang])}”</p>
<table>
<thead><tr><th>speed</th><th>gate</th><th>clip (as rendered)</th><th>as heard on the 6a</th>
<th>first sound</th><th>stalls</th><th>app warns</th><th>Whisper</th><th>notes</th></tr></thead>
<tbody>{''.join(trs)}</tbody></table>""")

    gate_rows = "".join("<tr>" + "".join(f"<td>{escape(c)}</td>" for c in cells) + "</tr>" for cells in table(rows))
    html = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Marmalade TTS — Speed Check Lab ({escape(meta['build'])})</title>
<link rel="stylesheet" href="/_assets/lab.css">
<script src="/_assets/lab-toc.js" defer></script>
<style>
 .v{{font-weight:700;padding:2px 8px;border-radius:6px}} .v.ok{{background:#1f4d2b}} .v.warn{{background:#5a4a12}}
 .v.bad{{background:#6b1d1d}} audio{{width:100%;min-width:220px;min-height:44px}} .meta{{opacity:.85}}
 small{{opacity:.8}}
</style></head><body>
<h1>Marmalade TTS — <span>Speed Check Lab</span></h1>
<p class="lede">Every engine at 1.0×, 1.25×, 1.5× and 2.0× on the Pixel 6a, through the system TTS path the release
gate drives. <b>{escape(meta['summary'])}</b></p>
<div class="note">
<p><b>How to listen.</b> “Clip” is the audio as the engine made it. “As heard on the 6a” is the same clip with
silence put in wherever playback would have waited for the engine (a stall). Nothing was played aloud: the
gate times each chunk as it arrives and replays that against the clock.</p>
<p><b>Gate rule.</b> PASS: keeps up (no stall of {KEEPUP_MAX_STALL_MS} ms or more). WARNED: stalls, and the app
shows its speed warning at that speed. FAIL: stalls where the app shows no warning, no audio, the speed didn't
apply, or slower than the last release's baseline.</p>
<p>Build {escape(meta['build'])} · {escape(meta['date'])} · {escape(meta['phone'])} ({escape(meta['screen'])}) ·
best of {meta['takes']} takes per row; clips are from the first take.</p>
</div>
{''.join(sections)}
<h2>Gate table</h2>
<table class="cards"><thead><tr>{''.join(f'<th>{c}</th>' for c in TABLE_COLS)}</tr></thead>
<tbody>{gate_rows}</tbody></table>
</body></html>
"""
    path = out / "speed-lab.html"
    path.write_text(html)
    return path


def _warn_from(pred: float | None, threshold: float) -> str:
    if pred is None:
        return "1.35× (no prediction: the static rule)"
    first = next((s for s in SPEEDS if pred * s > threshold), None)
    return "never in 1.0–2.0×" if first is None else f"{first:g}×"
