#!/usr/bin/env python3
"""Release gate: every engine x every language speaks on the test phone, and keeps up at speed.

Installs the build under test with `adb install -r`, then drives it through
Android's public TextToSpeech API (the probe APK, see probe/). Nothing is
played aloud. Two stages, both run by default:

  smoke  one sentence per (engine, language) the build advertises, rendered to
         a WAV. A row passes when audio came out (samples, plausible length,
         not silence) and the app's process logged no exception.
  speed  one voice per engine at 1.0x, 1.25x, 1.5x and 2.0x (speed.py): first
         sound, and whether real-time playback would have stalled, judged
         against the app's own speed warning and the last release's baseline.

Prints pass/fail tables and exits 1 if any row fails (2 on a setup error).
Usage and the one-time phone setup: README.md next to this file.
"""
from __future__ import annotations

import argparse
import base64
import fcntl
import json
import os
import re
import subprocess
import sys
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path

import bundles
import routing
import speed

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
PROBE_PKG = "app.marmalade.tts.smokeprobe"
PROBE = f"{PROBE_PKG}/.SmokeProbe"
PROBE_APK = HERE / "build" / "probe.apk"

# Engines a release must be able to speak with (names from the *VoiceCatalog
# ENGINE constants), in both flavors: only the developer-only Pocket twin is
# fdroidOnly, and system TTS never offers developer engines. One missing from
# the phone is a FAIL, not a skip: a gate that passes because an engine isn't
# installed proves nothing.
EXPECTED_ENGINES = ["kitten-direct-v0_8", "kokoro-direct-v1_0", "kokoro-de-v1_0",
                    "pocket-tts-en-v2026_04", "vits-marmalade-v1"]
# Advertised but not spoken by the gate: needs a network and an API key.
SKIPPED_ENGINES = {"cloud-api-v1": "cloud voices need a network and a key"}
VITS_ENGINE = "vits-marmalade-v1"
# The voice the app advertises as its default (KittenDirectVoiceCatalog). A
# request for it is read as an auto-filled echo and spoken by the primary
# alias instead (routing.py), so no row may ask for it.
KITTEN_DEFAULT_VOICE = re.search(
    r'DEFAULT_VOICE_ID = "([^"]+)"',
    (REPO / "app/src/main/java/app/marmalade/tts/data/KittenDirectVoiceCatalog.kt").read_text()).group(1)
# Request languages (ISO 639-2) for an engine's accented tier
# (EngineCatalog partialLanguageCodes): no voice of its own, so the gate
# reaches it the way a client does, with that request language on one of the
# engine's voices. Its sentence is `accented:<code>` in sentences.tsv.
ISO3 = {"de": "deu", "bg": "bul"}

# A row's audio must be at least this loud (RMS, dBFS) to count as speech.
MIN_RMS_DB = -50.0
# Plausible length: within [MIN_FACTOR, MAX_FACTOR] x a nominal reading time
# (seconds per character at 1.0x; kana and Han read far slower per character),
# divided by the phone's system speech rate. Android applies that rate
# (Settings → Text-to-speech → Speech rate) over a client's setSpeechRate, so
# the gate reads it rather than changing a shared phone's setting.
SEC_PER_CHAR = {"ja": 0.17, "zh": 0.25}
SEC_PER_CHAR_DEFAULT = 0.065
MIN_FACTOR, MAX_FACTOR = 0.35, 4.0
ROW_TIMEOUT_S = 240
# How long to wait for the app's EngineBundle lines after its TTS service starts.
BUNDLE_LOG_TIMEOUT_S = 15
SPEED_BASELINE = HERE / "speed-baseline.json"
WHISPER_CHECK = HERE / "whisper-check.py"

LOGCAT_RE = re.compile(r"^\s*(\d+\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.*?)\s*:\s?(.*)$")
EXCEPTION_RE = re.compile(r"\b[\w.$]*(Exception|Error)\b|^\s*at [\w.$<>]+\(|^Caused by:")


def die(msg: str) -> None:
    print(f"release-smoke: {msg}", file=sys.stderr)
    sys.exit(2)


@dataclass
class Row:
    engine: str
    locale: str
    voice: str
    request_lang: str | None = None  # an accented-tier row's language
    bundle: str = "?"
    result: str = "FAIL"
    reasons: list[str] = field(default_factory=list)
    duration_s: float | None = None
    rms_db: float | None = None
    synth_ms: int | None = None
    wav: str | None = None
    exceptions: list[str] = field(default_factory=list)


class Phone:
    def __init__(self, serial: str):
        self.serial = serial

    def adb(self, *args: str, check: bool = True, timeout: float | None = 120) -> str:
        p = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True, text=True, timeout=timeout)
        if check and p.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {p.stderr.strip() or p.stdout.strip()}")
        return p.stdout

    def sh(self, cmd: str, **kw) -> str:
        return self.adb("shell", cmd, **kw)

    def now(self) -> str:
        """Device clock as 'seconds.millis' (logcat -T's epoch form)."""
        s = self.sh("date +%s.%N").strip()
        sec, frac = s.split(".")
        return f"{sec}.{frac[:3]}"

    def speech_rate(self) -> float:
        """The system TTS speech rate as a multiplier (Settings.Secure tts_default_rate, 100 = 1.0x)."""
        v = self.sh("settings get secure tts_default_rate").strip()
        return int(v) / 100 if v.isdigit() and int(v) > 0 else 1.0

    def skin(self) -> tuple[float, int]:
        """VIRTUAL-SKIN from the thermal HAL's live readings (the cached section
        goes stale while the screen is off): (°C, throttling status, 0 = none)."""
        hal = self.sh("dumpsys thermalservice").split("Current temperatures from HAL", 1)[-1]
        m = re.search(r"mValue=([\d.]+), mType=\d+, mName=VIRTUAL-SKIN, mStatus=(\d+)", hal)
        return (float(m.group(1)), int(m.group(2))) if m else (0.0, 0)

    def mem_available_mb(self) -> int:
        m = re.search(r"MemAvailable:\s+(\d+)", self.sh("cat /proc/meminfo"))
        return int(m.group(1)) // 1024 if m else -1

    def pids(self, pkg: str) -> set[str]:
        return set(self.sh(f"pidof {pkg}", check=False).split())

    def package_info(self, pkg: str) -> dict[str, str]:
        out = self.sh(f"dumpsys package {pkg}")
        info = {}
        for key, pat in (("versionName", r"\S+"), ("versionCode", r"\d+"), ("lastUpdateTime", r".+")):
            m = re.search(rf"{key}=({pat})", out)
            if m:
                info[key] = m.group(1).strip()
        info["debuggable"] = "DEBUGGABLE" in out.split("pkgFlags=", 1)[-1].split("\n", 1)[0]
        info["installed"] = f"Package [{pkg}]" in out
        return info


def find_serial(wanted_model: str, serial: str | None) -> str:
    if serial is None:
        serial = os.environ.get("ANDROID_SERIAL")
    if serial is None:
        tag = "model:" + wanted_model.replace(" ", "_")
        lines = subprocess.run(["adb", "devices", "-l"], capture_output=True, text=True).stdout.splitlines()
        found = [ln.split()[0] for ln in lines[1:] if " device " in ln + " " and tag in ln]
        if not found:
            die(f"no {wanted_model} on adb (adb connect it first, or pass --serial)")
        serial = found[0]
    model = Phone(serial).sh("getprop ro.product.model").strip()
    if model != wanted_model:
        die(f"{serial} is a {model!r}, not the test phone ({wanted_model!r}); refusing to touch it")
    return serial


def hold_phone_lock(path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    f = open(path, "a")
    try:
        fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        print(f"waiting for the phone lock {path} …", flush=True)
        fcntl.flock(f, fcntl.LOCK_EX)
    return f  # held until the process exits


def backup_app_data(phone: Phone, pkg: str, dest_root: Path) -> None:
    """Copy a debuggable app's Room DB and DataStore off the phone before an install."""
    dest = dest_root / f"{pkg.rsplit('.', 1)[-1]}-{time.strftime('%Y-%m-%d-%H%M%S')}-release-smoke"
    (dest / "databases").mkdir(parents=True)
    (dest / "datastore").mkdir()
    pulls = [f"databases/marmalade_db{s}" for s in ("", "-shm", "-wal")] + \
            ["files/datastore/marmalade_settings.preferences_pb"]
    for rel in pulls:
        target = dest / ("datastore" if rel.startswith("files/") else "databases") / Path(rel).name
        p = subprocess.run(["adb", "-s", phone.serial, "exec-out", "run-as", pkg, "cat", rel], capture_output=True)
        if p.returncode == 0 and p.stdout:
            target.write_bytes(p.stdout)
    if not (dest / "databases" / "marmalade_db").exists():
        die(f"backup of {pkg} found no database (run-as failed?) — not installing over it")
    print(f"backed up {pkg} DB + datastore → {dest}")


def apk_package(apk: Path) -> str:
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk"))
    aapt2 = sorted((sdk / "build-tools").glob("*/aapt2"))
    if not aapt2:
        die("no aapt2 under $ANDROID_HOME/build-tools")
    return subprocess.run([str(aapt2[-1]), "dump", "packagename", str(apk)],
                          capture_output=True, text=True, check=True).stdout.strip()


def install(phone: Phone, apk: Path, backup_root: Path) -> str:
    pkg = apk_package(apk)
    info = phone.package_info(pkg)
    if info["installed"]:
        print(f"{pkg} on phone: {info.get('versionName')} ({info.get('versionCode')}), "
              f"last updated {info.get('lastUpdateTime')}")
        if info["debuggable"]:
            backup_app_data(phone, pkg, backup_root)
        else:
            print(f"{pkg} is not debuggable: its data can't be copied off (an rc holds no user data)")
    print(f"adb install -r {apk}")
    phone.adb("install", "-r", str(apk), timeout=600)
    return pkg


def probe(phone: Phone, watch: str | None = None, since: str = "", start_pids: frozenset[str] = frozenset(),
          **extras: str) -> dict[str, str]:
    """Run the probe once. With [watch], a crash of that package (its fatal
    exception logged since [since]) or a death or restart of its process ends
    the row at once instead of after the probe's own timeout. [start_pids] is
    the process seen before the row; without it the first one seen counts."""
    args = []
    for k, v in extras.items():
        args += ["-e", k, v]
    p = subprocess.Popen(["adb", "-s", phone.serial, "shell", "am instrument -w " + " ".join(args) + f" {PROBE}"],
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    deadline, pid = time.monotonic() + ROW_TIMEOUT_S, min(start_pids, default=None)
    while True:
        try:
            out, _ = p.communicate(timeout=0.5)
            break
        except subprocess.TimeoutExpired:
            pass
        failure = None
        if time.monotonic() > deadline:
            failure = f"no result in {ROW_TIMEOUT_S} s"
        elif watch:
            now = sorted(phone.pids(watch))
            crash = phone.adb("logcat", "-d", "-T", since, "-s", "AndroidRuntime:E", check=False)
            if f"Process: {watch}," in crash:
                failure = "app crashed mid-row"
            elif pid is None:
                pid = now[0] if now else None
            elif pid not in now:
                failure = f"app process {pid} died mid-row"
        if failure:
            p.kill()
            p.communicate()
            phone.sh(f"am force-stop {PROBE_PKG}", check=False)
            return {"status": "error", "error": failure}
    res: dict[str, str] = {}
    for line in out.splitlines():
        if line.startswith("INSTRUMENTATION_RESULT: ") and "=" in line:
            k, v = line[len("INSTRUMENTATION_RESULT: "):].split("=", 1)
            res[k] = v
    if "status" not in res:
        res = {"status": "error", "error": "probe gave no result: " + " | ".join(out.split("\n")[-4:]).strip()}
    return res


def load_sentences() -> dict[str, str]:
    out = {}
    for line in (HERE / "sentences.tsv").read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.startswith("#"):
            lang, text = line.split("\t", 1)
            out[lang] = text.strip()
    return out


def read_catalog() -> tuple[dict[str, str], dict[str, list[str]]]:
    """From EngineCatalog.kt: engine name → the bundle release its catalog entry
    downloads (e.g. 'v23'), and engine name → its accented-tier language codes."""
    src = (REPO / "app/src/main/java/app/marmalade/tts/install/EngineCatalog.kt").read_text()
    out, partial = {}, {}
    for m in re.finditer(r'name = "([\w.-]+)",(.*?)(?=\n\s+name = |\Z)', src, re.S):
        v = re.search(r"releases/download/(v\d+)/", m.group(2))
        if v:
            out.setdefault(m.group(1), v.group(1))
        codes = re.search(r"partialLanguageCodes = listOf\(([^)]*)\)", m.group(2))
        if codes:
            partial.setdefault(m.group(1), re.findall(r'"([\w-]+)"', codes.group(1)))
    packs = (REPO / "app/src/main/java/app/marmalade/tts/install/VoicePackCatalog.kt").read_text()
    v = re.search(r"PACK_RELEASE_BASE_URL[^=]*=\s*\"[^\"]*/(v\d+)\"", packs)
    if v:
        out[VITS_ENGINE] = v.group(1)
    return out, partial


class LangDetector:
    """The app's utterance language detector (lang/LangDetector.kt), reading the
    same langdetect.tab. A Kokoro voice phonemizes in the DETECTED language and
    only falls back to the request language when detection abstains, so an
    accented row's sentence must make it abstain or the row never reaches the
    accented tier. Script stage reduced to "kana, Han or Devanagari wins"."""

    MIN_TRIGRAMS, SHORT_TRIGRAMS, MIN_MARGIN, SHORT_MARGIN = 8, 24, 2.0, 8.0

    def __init__(self, table: Path):
        lines = table.read_text(encoding="utf-8").splitlines()
        self.langs = lines[1].split()
        self.floor = [int(x) for x in lines[3].split()]
        keys = lines[4].replace("_", " ")
        self.costs = base64.b64decode(lines[5])
        n = len(self.langs)
        self.offsets = {keys[i * 3:i * 3 + 3]: i * n for i in range(len(keys) // 3)}

    def detect(self, text: str) -> str | None:
        script = sum(1 for c in text if "\u3040" <= c <= "\u30ff" or "\u4e00" <= c <= "\u9fff"
                     or "\u0900" <= c <= "\u097f")
        letters = sum(1 for c in text if c.isalpha())
        if script and script >= letters - script:
            return "ja/zh/hi"
        norm = " ".join("".join(c if c.isalpha() else " " for c in text.lower()).split())
        if not norm:
            return None
        padded, n = f" {norm} ", len(self.langs)
        n_tri = len(padded) - 2
        if n_tri < self.MIN_TRIGRAMS:
            return None
        totals = [0] * n
        for t in range(n_tri):
            at = self.offsets.get(padded[t:t + 3])
            for li in range(n):
                totals[li] += self.floor[li] if at is None else self.costs[at + li]
        order = sorted(range(n), key=lambda li: (totals[li], li))
        best, second = order[0], order[1]
        frac = max(0.0, (self.SHORT_TRIGRAMS - n_tri) / (self.SHORT_TRIGRAMS - self.MIN_TRIGRAMS))
        need = self.MIN_MARGIN + (self.SHORT_MARGIN - self.MIN_MARGIN) * frac
        return None if (totals[second] - totals[best]) / n_tri < need else self.langs[best]


def pick_rows(voices: list[tuple[str, str]], all_voices: bool, partial: dict[str, list[str]]) -> list[Row]:
    """One voice per (engine, locale) — per VITS pack, since each pack is its own model — or every
    voice; then one row per accented-tier language, on the engine's first voice. Never the app's
    default voice (KITTEN_DEFAULT_VOICE) unless every voice is asked for: the primary alias speaks it,
    and that row then fails the routing check."""
    rows, seen = [], set()
    for name, locale in sorted(voices):
        engine = name.split(":", 1)[0]
        if engine in SKIPPED_ENGINES or (name == KITTEN_DEFAULT_VOICE and not all_voices):
            continue
        key = name if all_voices else (engine, locale, name.split(":", 1)[1].split("#")[0] if engine == VITS_ENGINE else "")
        if key in seen:
            continue
        seen.add(key)
        rows.append(Row(engine=engine, locale=locale, voice=name))
    for engine, codes in partial.items():
        first = next((r.voice for r in rows if r.engine == engine), None)
        rows += [Row(engine=engine, locale=f"{c} (accented)", voice=first, request_lang=c)
                 for c in codes if first]
    return rows


def app_exceptions(phone: Phone, pkg: str, since: str, pids: set[str], allow: list[re.Pattern]) -> list[str]:
    """Lines the app's process logged since [since] that show an exception, a crash or an error."""
    log = phone.adb("logcat", "-d", "-v", "epoch", "-T", since, timeout=60)
    pids = set(pids)
    for m in re.finditer(rf"Start proc (\d+):{re.escape(pkg)}/|Process: {re.escape(pkg)}, PID: (\d+)", log):
        pids.add(m.group(1) or m.group(2))
    hits = []
    for line in log.splitlines():
        m = LOGCAT_RE.match(line)
        if not m:
            continue
        _, pid, _, level, tag, msg = m.groups()
        died = re.search(rf"Process {re.escape(pkg)} \(pid \d+\) has died", msg)
        if pid not in pids and not died:
            continue
        bad = died or level in "EF" or (level == "W" and EXCEPTION_RE.search(msg))
        text = f"{level}/{tag}: {msg}"
        if bad and not any(a.search(text) for a in allow):
            hits.append(text)
    return hits


def spoken_as(phone: Phone, since: str, pids: set[str]) -> list[routing.Resolved]:
    """The app's request lines (routing.py) logged by [pids] since [since]."""
    log = phone.adb("logcat", "-d", "-v", "epoch", "-T", since, "-s", f"{routing.TAG}:I", check=False)
    mine = [ln for ln in log.splitlines() if (m := LOGCAT_RE.match(ln)) and m.group(2) in pids]
    return routing.parse("\n".join(mine))


def load_allowlist() -> list[re.Pattern]:
    path = HERE / "logcat-allow.txt"
    lines = path.read_text().splitlines() if path.exists() else []
    return [re.compile(ln) for ln in lines if ln.strip() and not ln.startswith("#")]


def judge(row: Row, res: dict[str, str], text: str, rate: float) -> None:
    if res.get("status") != "ok":
        row.reasons.append(res.get("error", "probe error"))
        return
    row.synth_ms = int(res.get("synthMs", 0))
    samples = int(res.get("samples", 0))
    row.duration_s = int(res.get("durationMs", 0)) / 1000
    row.rms_db = float(res.get("rmsDb", -120))
    lang = row.request_lang or row.locale.split("-")[0]
    nominal = len(text) * SEC_PER_CHAR.get(lang, SEC_PER_CHAR_DEFAULT) / rate
    if samples == 0:
        row.reasons.append("no audio")
    elif not (MIN_FACTOR * nominal <= row.duration_s <= MAX_FACTOR * nominal):
        row.reasons.append(f"implausible length {row.duration_s:.2f} s (expected ~{nominal:.1f} s)")
    if samples and row.rms_db < MIN_RMS_DB:
        row.reasons.append(f"silent (RMS {row.rms_db} dBFS)")


def print_table(rows: list[Row], header: str) -> str:
    cols = ["engine", "locale", "voice", "bundle", "result", "audio", "RMS dB", "synth", "why"]
    data = []
    for r in rows:
        data.append([r.engine, r.locale, r.voice.split(":", 1)[-1], r.bundle, r.result,
                     f"{r.duration_s:.2f} s" if r.duration_s is not None else "-",
                     f"{r.rms_db:.1f}" if r.rms_db is not None else "-",
                     f"{r.synth_ms / 1000:.1f} s" if r.synth_ms else "-",
                     "; ".join(r.reasons)[:160]])
    return print_rows(header, cols, data)


def print_rows(header: str, cols: list[str], data: list[list[str]]) -> str:
    widths = [max(len(c), *(len(d[i]) for d in data)) if data else len(c) for i, c in enumerate(cols)]
    fmt = lambda cells: "| " + " | ".join(c.ljust(w) for c, w in zip(cells, widths)) + " |"
    lines = [header, "", fmt(cols), "|" + "|".join("-" * (w + 2) for w in widths) + "|"]
    lines += [fmt(d) for d in data]
    table = "\n".join(lines)
    print(table)
    return table


def wake_state(phone: Phone) -> str:
    m = re.search(r"mWakefulness=(\w+)", phone.sh("dumpsys power"))
    return m.group(1) if m else "?"


def smoke_stage(phone: Phone, pkg: str, rows: list[Row], sentences: dict[str, str], detector: LangDetector,
                bundle_of, allow: list[re.Pattern], rate: float, out: Path) -> None:
    for i, row in enumerate(rows, 1):
        row.bundle = bundle_of(row.engine, row.voice)
        key = f"accented:{row.request_lang}" if row.request_lang else row.locale.split("-")[0]
        text = sentences.get(key)
        if text is None:
            row.reasons.append(f"no `{key}` line in sentences.tsv")
            continue
        extra = {}
        if row.request_lang:
            if row.request_lang not in ISO3:
                row.reasons.append(f"no ISO 639-2 code for {row.request_lang} in ISO3")
                continue
            seen = detector.detect(text)
            if seen is not None:
                row.reasons.append(f"the app's language detector reads the `{key}` line as {seen}, so the "
                                   f"row would never reach the accented tier: pick a line it abstains on")
                continue
            extra["lang"] = ISO3[row.request_lang]
        tag = re.sub(r"[^\w.-]", "_", f"{i:02d}-{row.voice}" + (f"-{row.request_lang}" if row.request_lang else ""))
        print(f"[{i}/{len(rows)}] {row.voice} ({row.locale}) …", flush=True)
        since, pids = phone.now(), phone.pids(pkg)
        res = probe(phone, watch=pkg, since=since, start_pids=frozenset(pids),
                    engine=pkg, mode="speak", voice=row.voice,
                    text64=base64.b64encode(text.encode()).decode(), out=tag, **extra)
        time.sleep(0.5)  # let the app's last log lines land
        judge(row, res, text, rate)
        if res.get("status") == "ok":
            why = routing.mismatch(row.voice, rate, spoken_as(phone, since, pids | phone.pids(pkg)))
            if why:
                row.reasons.append(why)
        row.exceptions = app_exceptions(phone, pkg, since, pids | phone.pids(pkg), allow)
        if row.exceptions:
            row.reasons.append(f"app logged: {row.exceptions[0][:120]}")
        if res.get("file"):
            local = out / "wav" / f"{tag}.wav"
            if subprocess.run(["adb", "-s", phone.serial, "pull", res["file"], str(local)],
                              capture_output=True).returncode == 0:
                row.wav = str(local)
        row.result = "PASS" if not row.reasons else "FAIL"


def read_bundles(phone: Phone, since: str) -> dict[str, bundles.Bundle]:
    """The app's EngineBundle lines since [since] (its TTS service logs them
    once per start, all at once): which download each engine is on."""
    deadline, seen = time.monotonic() + BUNDLE_LOG_TIMEOUT_S, {}
    while time.monotonic() < deadline:
        found = bundles.parse(phone.adb("logcat", "-d", "-v", "epoch", "-T", since, "-s", "EngineBundle:I", check=False))
        if found and found == seen:  # nothing new in the last poll: all logged
            return found
        seen = found
        time.sleep(1)
    return seen


def bundle_names(engines: list[str]) -> list[str]:
    """The bundles the engines under test speak from: VITS's released packs, every other engine's own."""
    names = [e for e in engines if e != VITS_ENGINE]
    if VITS_ENGINE in engines:
        names += [f"{VITS_ENGINE}:{p}" for p in speed.released_vits_packs(REPO)]
    return names


def speed_voices(advertised: set[str], engines: list[str]) -> list[tuple[str, str | None]]:
    """(engine, voice) per speed-stage row group: SPEED_VOICES, plus the first
    advertised voice of each released VITS pack. None = not advertised."""
    picks = [(e, v if v in advertised else None) for e, v in speed.SPEED_VOICES.items()]
    for pack in speed.released_vits_packs(REPO):
        voice = next((v for v in sorted(advertised)
                      if v.startswith(VITS_ENGINE + ":") and v.split(":", 1)[1].split("#")[0] == pack), None)
        picks.append((VITS_ENGINE, voice or f"{VITS_ENGINE}:{pack}?"))
    return [(e, v) for e, v in picks if e in engines]


def wait_cool(phone: Phone, max_skin_c: float, timeout_s: float) -> tuple[float, int, bool]:
    """Wait until the phone's skin is at most [max_skin_c] and unthrottled.
    A warm phone renders a third slower, which would read as a regression."""
    deadline = time.monotonic() + timeout_s
    while True:
        skin, status = phone.skin()
        if (skin <= max_skin_c and status == 0) or time.monotonic() > deadline:
            return skin, status, skin <= max_skin_c and status == 0
        print(f"[speed] cooling down: skin {skin:.1f} °C (status {status}), waiting for {max_skin_c:.1f} °C …",
              flush=True)
        time.sleep(30)


def cold_after(res: dict[str, str]) -> float:
    """Monotonic time at which [res]'s audio would have finished playing, plus
    a margin (speed.cold_wait_ms): the next render waits for it, so it starts cold."""
    now = time.monotonic()
    if res.get("status") != "ok" or not res.get("chunks"):
        return now
    first, stalls = speed.replay(speed.parse_chunks(res["chunks"]), int(res["sampleRate"]))
    return now + speed.cold_wait_ms(first, int(res["durationMs"]), stalls, int(res["synthMs"])) / 1000


def speed_stage(phone: Phone, pkg: str, picks: list[tuple[str, str | None]], texts: dict[str, str],
                bundle_of, allow: list[re.Pattern], takes: int, out: Path,
                max_skin_c: float, cool_timeout_s: float) -> list[speed.SpeedRow]:
    """Render every (voice, speed) [takes] times, interleaved (take 1 of every
    speed, then take 2, ...) so thermal drift spreads across speeds. Each
    voice starts on a cool phone and is warmed up first, so first sound is a
    loaded engine's. Every render starts cold: it waits until the previous
    one's audio would have finished playing (speed.COLD_MARGIN_MS)."""
    (out / "speed").mkdir(exist_ok=True)
    rows = []
    for engine, voice in picks:
        group = [speed.SpeedRow(engine=engine, voice=voice or speed.SPEED_VOICES.get(engine, engine), speed=s,
                                bundle=bundle_of(engine, voice)) for s in speed.SPEEDS]
        rows += group
        if voice is None or voice.endswith("?"):
            for r in group:
                r.reasons.append("voice not advertised (engine or pack not installed on the phone)")
            continue
        skin, status, cool = wait_cool(phone, max_skin_c, cool_timeout_s)
        for r in group:
            r.skin_c, r.mem_available_mb = round(skin, 1), phone.mem_available_mb()
        if not cool:
            for r in group:
                r.reasons.append(f"phone too warm to measure: skin {skin:.1f} °C, status {status}, "
                                 f"after {cool_timeout_s / 60:.0f} min")
            continue
        text = texts["de" if engine == "kokoro-de-v1_0" else "en"]
        text64 = base64.b64encode(text.encode()).decode()
        print(f"[speed] {voice}: skin {skin:.1f} °C, {group[0].mem_available_mb} MB free; warm-up", flush=True)
        ready_at = cold_after(probe(phone, engine=pkg, mode="speak", voice=voice, rate="1.0", out="speed-warmup",
                                    text64=base64.b64encode(b"Hello there.").decode()))
        for take in range(takes):
            for r in group:
                time.sleep(max(0.0, ready_at - time.monotonic()))
                print(f"[speed] {voice} {r.speed:g}x take {take + 1}/{takes} …", flush=True)
                since, pids = phone.now(), phone.pids(pkg)
                tag = re.sub(r"[^\w.-]", "_", f"{voice}-{r.speed:g}x")
                res = probe(phone, watch=pkg, since=since, start_pids=frozenset(pids), engine=pkg, mode="speak",
                            voice=voice, rate=f"{r.speed:g}", text64=text64, out=f"speed-{tag}")
                ready_at = cold_after(res)
                time.sleep(0.5)
                r.exceptions += app_exceptions(phone, pkg, since, pids | phone.pids(pkg), allow)
                if res.get("status") != "ok" or int(res.get("samples", 0)) == 0 or not res.get("chunks"):
                    r.reasons.append(res.get("error", "no audio"))
                    continue
                why = routing.mismatch(voice, r.speed, spoken_as(phone, since, pids | phone.pids(pkg)))
                if why:
                    if why not in r.reasons:
                        r.reasons.append(why)
                    continue
                first, stalls = speed.replay(speed.parse_chunks(res["chunks"]), int(res["sampleRate"]))
                t = speed.Take(first_sound_ms=first, synth_ms=int(res["synthMs"]),
                               duration_ms=int(res["durationMs"]), stalls=stalls)
                if take == 0:
                    local = out / "speed" / f"{tag}.wav"
                    if subprocess.run(["adb", "-s", phone.serial, "pull", res["file"], str(local)],
                                      capture_output=True).returncode == 0:
                        t.wav = f"speed/{local.name}"
                        if stalls:
                            speed.write_as_heard(local, stalls, out / speed.as_heard_name(t.wav))
                r.takes.append(t)
    return rows


def whisper(rows: list[speed.SpeedRow], texts: dict[str, str], python: str, out: Path) -> None:
    """Transcribe each row's clip (needs a Python with faster_whisper; offline)."""
    jobs = [{"wav": str(out / r.takes[0].wav), "lang": "de" if r.engine == "kokoro-de-v1_0" else "en"}
            for r in rows if r.takes and r.takes[0].wav]
    if not jobs:
        return
    p = subprocess.run([python, str(WHISPER_CHECK)], input=json.dumps(jobs), capture_output=True, text=True)
    if p.returncode != 0:
        print(f"whisper check failed (not part of the gate): {p.stderr.strip()[-300:]}", file=sys.stderr)
        return
    heard = json.loads(p.stdout)
    for r in rows:
        if r.takes and r.takes[0].wav and str(out / r.takes[0].wav) in heard:
            r.whisper = heard[str(out / r.takes[0].wav)]
            r.whisper_match = speed.word_match(texts["de" if r.engine == "kokoro-de-v1_0" else "en"], r.whisper)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apk", type=Path, help="install this build first (adb install -r); default: test what's installed")
    ap.add_argument("--package", default="app.marmalade.tts.rc", help="app under test (default: the R8 smoke build)")
    ap.add_argument("--serial", help="adb serial (default: $ANDROID_SERIAL, else the connected --model)")
    ap.add_argument("--model", default="Pixel 6a", help="refuse any phone but this model (default: Pixel 6a)")
    ap.add_argument("--stage", choices=["all", "smoke", "speed"], default="all", help="which stages to run (default: all)")
    ap.add_argument("--engines", help="only these engines, comma-separated (e.g. kokoro-de-v1_0 after a bundle swap)")
    ap.add_argument("--all-voices", action="store_true", help="smoke: speak every advertised voice, not one per language")
    ap.add_argument("--takes", type=int, default=2, help="speed: renders per row; judged on the best (default: 2)")
    ap.add_argument("--cool-skin-c", type=float, default=33.0,
                    help="speed: wait until the phone's skin is this cool before each engine (default: 33)")
    ap.add_argument("--cool-timeout", type=float, default=15,
                    help="speed: minutes to wait for that; an engine still too warm fails (default: 15)")
    ap.add_argument("--allow-outdated", action="store_true",
                    help="measure engines that aren't on the catalog's download (the table says which)")
    ap.add_argument("--write-baseline", action="store_true",
                    help="speed: after judging, store this run's rows as the baseline (README, Baseline)")
    ap.add_argument("--whisper-python", default=os.environ.get("MTTS_WHISPER_PYTHON"),
                    help="speed: a Python with faster_whisper, to transcribe the clips for the lab (not gated)")
    ap.add_argument("--out", type=Path, help="results dir (default: build/release-smoke/<time> in the repo)")
    ap.add_argument("--lock", type=Path, default=Path.home() / ".cache/pixel8a-bench.lock",
                    help="shared test-phone lock, held for the whole run")
    ap.add_argument("--backup-dir", type=Path,
                    default=Path(os.environ.get("MTTS_BACKUP_DIR", Path.home() / "coding/scratch/mtts-device-backups")),
                    help="where a debuggable app's DB/datastore is copied before an install")
    a = ap.parse_args()

    sentences = load_sentences()
    catalog_tags, partial = read_catalog()
    detector = LangDetector(REPO / "app/src/main/assets/langdetect.tab")
    model = speed.read_warning_model(REPO)
    allow = load_allowlist()
    engines = a.engines.split(",") if a.engines else EXPECTED_ENGINES
    unknown = [e for e in engines if e not in EXPECTED_ENGINES]
    if unknown:
        die(f"unknown engine(s) {unknown}; expected some of {EXPECTED_ENGINES}")
    out = a.out or REPO / "build" / "release-smoke" / time.strftime("%Y-%m-%d-%H%M%S")
    (out / "wav").mkdir(parents=True, exist_ok=True)
    if not PROBE_APK.exists():
        subprocess.run([str(HERE / "build-probe.sh")], check=True)

    serial = find_serial(a.model, a.serial)
    phone = Phone(serial)
    _lock = hold_phone_lock(a.lock)
    print(f"phone lock held; testing on {serial}")

    pkg = install(phone, a.apk, a.backup_dir) if a.apk else a.package
    info = phone.package_info(pkg)
    if not info["installed"]:
        die(f"{pkg} is not installed (pass --apk)")
    phone.adb("install", "-r", str(PROBE_APK))
    build = f"{pkg} {info.get('versionName')} ({info.get('versionCode')})"
    rate = phone.speech_rate()
    print(f"under test: {build}; system speech rate {rate:.2f}x")

    # Restart the app so its TTS service logs which download each engine is on.
    phone.sh(f"am force-stop {pkg}")
    since = phone.now()
    listed = probe(phone, engine=pkg, mode="list")
    if listed.get("status") != "ok":
        die(f"can't list {pkg}'s voices: {listed.get('error')}")
    installed = read_bundles(phone, since)
    outdated = bundles.problems(installed, bundle_names(engines))
    if not installed:
        outdated = [f"{pkg} logged no EngineBundle lines, so which engine downloads are installed is unknown "
                    f"(a build from before #51?)"]
    for line in outdated:
        print(f"NOT THE CATALOG'S BUNDLE: {line}", file=sys.stderr)
    if outdated and not a.allow_outdated:
        die("refusing to measure engines users won't get. Update them in the app under test "
            "(Marmalade → Engines → Update; README, One-time phone setup), or pass --allow-outdated")

    def bundle_of(engine: str, voice: str | None) -> str:
        if not installed:  # unverified: the catalog's release
            return catalog_tags.get(engine, "?") + "?"
        return bundles.label(installed, bundles.key(engine, voice, VITS_ENGINE))

    voices = [tuple(v.split(",", 1)) for v in listed.get("voices", "").split(";") if "," in v]
    stamp = time.strftime("%Y-%m-%d %H:%M")
    results: dict = {"build": build, "serial": serial, "speech_rate": rate,
                     "bundles": {n: asdict(b) for n, b in installed.items()}, "not_catalog": outdated}
    failed = 0

    if a.stage in ("all", "smoke"):
        rows = [r for r in pick_rows(voices, a.all_voices, partial) if r.engine in engines]
        advertised = {r.engine for r in rows}
        missing = [Row(engine=e, locale="-", voice="-", reasons=["engine not installed on the phone"])
                   for e in engines if e not in advertised]
        smoke_stage(phone, pkg, rows, sentences, detector, bundle_of, allow, rate, out)
        rows += missing
        bad = [r for r in rows if r.result != "PASS"]
        failed += len(bad)
        header = f"release smoke — {build} — {stamp} — speech rate {rate:.2f}x — {len(rows) - len(bad)}/{len(rows)} pass"
        table = print_table(rows, header)
        (out / "table.md").write_text(table + "\n")
        results["rows"] = [asdict(r) for r in rows]

    if a.stage in ("all", "speed"):
        texts = {"en": sentences["speed:en"], "de": sentences["speed:de"]}
        screen = wake_state(phone)
        if screen == "Awake":  # measure as when listening: screen off
            phone.sh("input keyevent KEYCODE_SLEEP")
            time.sleep(2)
            screen = f"{wake_state(phone)}, after sleep key"
        print(f"\nspeed stage: screen {screen}; {a.takes} take(s) per row", flush=True)
        picks = speed_voices({name for name, _ in voices}, engines)
        srows = speed_stage(phone, pkg, picks, texts, bundle_of, allow, a.takes, out,
                            a.cool_skin_c, a.cool_timeout * 60)
        if a.whisper_python:
            whisper(srows, texts, a.whisper_python, out)
        base = speed.load_baseline(SPEED_BASELINE)["rows"]
        for group in (srows[i:i + len(speed.SPEEDS)] for i in range(0, len(srows), len(speed.SPEEDS))):
            at_1x = next((r.best("duration_ms") for r in group if r.speed == 1.0 and r.takes), None)
            for r in group:
                speed.judge(r, model, at_1x, base.get(r.key))
        bad = [r for r in srows if r.result == "FAIL"]
        failed += len(bad)
        warned = sum(r.result == "WARNED" for r in srows)
        summary = (f"{len(srows) - len(bad)}/{len(srows)} rows pass"
                   + (f" ({warned} stall where the app warns)" if warned else "")
                   + (f"; {len(bad)} FAIL" if bad else ""))
        header = f"speed stage — {build} — {stamp} — {summary}"
        table = print_rows(header, speed.TABLE_COLS, speed.table(srows))
        (out / "speed-table.md").write_text(table + "\n")
        lab = speed.write_lab(out, srows, {"build": build, "date": stamp, "phone": f"{a.model} {serial}",
                                           "screen": f"screen {screen}", "takes": a.takes, "summary": summary,
                                           "threshold": model.threshold}, texts)
        print(f"lab: {lab}")
        results["speed"] = {"screen": screen, "takes": a.takes, "rows": [asdict(r) for r in srows]}
        if a.write_baseline:
            n = speed.merge_baseline(SPEED_BASELINE, srows, build, time.strftime("%Y-%m-%d"), a.model)
            print(f"baseline: wrote {n} row(s) to {SPEED_BASELINE} (review and commit it)")

    (out / "results.json").write_text(json.dumps(results, indent=2, ensure_ascii=False))
    print(f"\nresults: {out}")
    if failed:
        print(f"GATE FAILED: {failed} row(s)", file=sys.stderr)
        return 1
    print("GATE PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
