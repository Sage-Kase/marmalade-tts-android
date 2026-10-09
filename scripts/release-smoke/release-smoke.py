#!/usr/bin/env python3
"""Release gate: every engine x every language speaks one sentence on the test phone.

Installs the build under test with `adb install -r`, then, through Android's
public TextToSpeech API (the probe APK, see probe/), renders one sentence per
(engine, language) the build advertises to a WAV on the phone. Nothing is
played. A row passes when audio came out (samples, plausible length, not
silence) and the app's process logged no exception while rendering it.

Prints a pass/fail table and exits 1 if any row fails (2 on a setup error).
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
    voice; then one row per accented-tier language, on the engine's first voice."""
    rows, seen = [], set()
    for name, locale in sorted(voices):
        engine = name.split(":", 1)[0]
        if engine in SKIPPED_ENGINES:
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
    widths = [max(len(c), *(len(d[i]) for d in data)) if data else len(c) for i, c in enumerate(cols)]
    fmt = lambda cells: "| " + " | ".join(c.ljust(w) for c, w in zip(cells, widths)) + " |"
    lines = [header, "", fmt(cols), "|" + "|".join("-" * (w + 2) for w in widths) + "|"]
    lines += [fmt(d) for d in data]
    table = "\n".join(lines)
    print(table)
    return table


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apk", type=Path, help="install this build first (adb install -r); default: test what's installed")
    ap.add_argument("--package", default="app.marmalade.tts.rc", help="app under test (default: the R8 smoke build)")
    ap.add_argument("--serial", help="adb serial (default: $ANDROID_SERIAL, else the connected --model)")
    ap.add_argument("--model", default="Pixel 6a", help="refuse any phone but this model (default: Pixel 6a)")
    ap.add_argument("--all-voices", action="store_true", help="speak every advertised voice, not one per language")
    ap.add_argument("--out", type=Path, help="results dir (default: build/release-smoke/<time> in the repo)")
    ap.add_argument("--lock", type=Path, default=Path.home() / ".cache/pixel8a-bench.lock",
                    help="shared test-phone lock, held for the whole run")
    ap.add_argument("--backup-dir", type=Path,
                    default=Path(os.environ.get("MTTS_BACKUP_DIR", Path.home() / "coding/scratch/mtts-device-backups")),
                    help="where a debuggable app's DB/datastore is copied before an install")
    a = ap.parse_args()

    sentences = load_sentences()
    bundles, partial = read_catalog()
    detector = LangDetector(REPO / "app/src/main/assets/langdetect.tab")
    allow = load_allowlist()
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

    listed = probe(phone, engine=pkg, mode="list")
    if listed.get("status") != "ok":
        die(f"can't list {pkg}'s voices: {listed.get('error')}")
    voices = [tuple(v.split(",", 1)) for v in listed.get("voices", "").split(";") if "," in v]
    rows = pick_rows(voices, a.all_voices, partial)
    advertised = {r.engine for r in rows}
    missing = [Row(engine=e, locale="-", voice="-", reasons=["engine not installed on the phone"])
               for e in EXPECTED_ENGINES if e not in advertised]

    for i, row in enumerate(rows, 1):
        row.bundle = bundles.get(row.engine, "?")
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
        row.exceptions = app_exceptions(phone, pkg, since, pids | phone.pids(pkg), allow)
        if row.exceptions:
            row.reasons.append(f"app logged: {row.exceptions[0][:120]}")
        if res.get("file"):
            local = out / "wav" / f"{tag}.wav"
            if subprocess.run(["adb", "-s", serial, "pull", res["file"], str(local)], capture_output=True).returncode == 0:
                row.wav = str(local)
        row.result = "PASS" if not row.reasons else "FAIL"

    rows += missing
    failed = [r for r in rows if r.result != "PASS"]
    header = (f"release smoke — {build} — {time.strftime('%Y-%m-%d %H:%M')} — speech rate {rate:.2f}x — "
              f"{len(rows) - len(failed)}/{len(rows)} pass")
    table = print_table(rows, header)
    (out / "table.md").write_text(table + "\n")
    (out / "results.json").write_text(json.dumps({"build": build, "serial": serial, "speech_rate": rate,
                                                  "rows": [asdict(r) for r in rows]}, indent=2, ensure_ascii=False))
    print(f"\nresults: {out}")
    if failed:
        print(f"GATE FAILED: {len(failed)} row(s)", file=sys.stderr)
        return 1
    print("GATE PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
