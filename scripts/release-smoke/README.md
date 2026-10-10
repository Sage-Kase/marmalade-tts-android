# Release smoke gate

Before every release, make every engine speak every language it offers on a
real phone, from the R8-minified release build, and check that each engine
keeps up at the speeds the app offers (the speed stage, below). Unit tests can't catch this
class of bug: v1.1.0 shipped with German unable to speak at all because
Android's regex (ICU) rejects a flag the JVM accepts (#22). Other bugs only a
device shows: JNI, missing assets, classes R8 stripped.

## What it does

`release-smoke.py`:

1. Finds the test phone (a Pixel 6a by default; any other model is refused)
   and holds the shared phone lock (`~/.cache/pixel8a-bench.lock`) for the
   whole run.
2. With `--apk`, installs the build with `adb install -r` (never an
   uninstall). A debuggable app's Room DB and DataStore are copied off the
   phone first (`--backup-dir`); the rc build isn't debuggable and holds no
   user data, so it is installed without one.
3. Installs the probe (`probe/`, package `app.marmalade.tts.smokeprobe`), a
   test-only APK that drives the app through Android's public
   `TextToSpeech` API: the same path TalkBack and every other app use, and
   one that works however R8 renamed the app's classes.
4. Checks that every engine under test is on the download the build's
   catalog offers. It restarts the app (`am force-stop`) and reads the lines
   its TTS service logs on start (tag `EngineBundle`, one per engine and voice
   pack on disk: the release its `.install_meta.json` records, the catalog's,
   and whether they match). If one isn't, the gate stops before measuring
   (exit 2) and names it: update it in the app's Engines screen, or pass
   `--allow-outdated` to measure it as it is. A build from before #51 logs no
   such lines and is refused the same way. Why: the rc keeps whatever its
   Engines screen last installed, and #42's Kokoro rows timed the pre-v26
   bundle while the table said v26.
5. Lists the voices the app advertises and picks one per engine × language
   (per voice pack for VITS; `--all-voices` for every voice). It renders that
   language's line from `sentences.tsv` to a WAV on the phone. Nothing is
   played, here or in the speed stage: the gate never needs sound.
   Kokoro's accented tier (German and Bulgarian, `partialLanguageCodes` in
   `EngineCatalog`) has no voice of its own. The gate reaches it the way a
   client does: a Kokoro voice plus that request language (`deu`, `bul`).
   Kokoro uses the request language only when its language detector abstains
   on the text, so those rows' `accented:<code>` sentences must make it abstain.
   The gate checks that with the app's own `langdetect.tab` and fails the row
   if not. Most German prose reads as English or French to the detector.
6. A row passes when audio came out (samples, a plausible length for the
   sentence, not silence), the voice it asked for spoke it at the rate it
   asked for (below, "Which voice spoke"), and the app's process logged no
   error or exception while rendering it. This stage sets no speech rate, so the phone's system
   speech rate (Settings → Text-to-speech → Speech rate) applies; the length
   check divides by it. The gate reads it and never changes it. (A client
   that calls `setSpeechRate` gets its own rate: the speed stage does.) If the app's process dies or restarts during a row, that row
   fails at once and the run carries on. An expected engine that the phone
   doesn't advertise is a FAIL row, not a skip. Cloud voices are skipped
   (network + key).
7. Runs the speed stage (below).
8. Prints a pass/fail table per stage and exits 1 on any failure (2 on a
   setup error). Results (`table.md`, `speed-table.md`, `results.json`, the
   WAVs, `speed-lab.html`) go to `build/release-smoke/<time>/`.

**Which voice spoke** (#50). The app doesn't always speak the voice a client
asks for: a per-app mapping wins, and a request for the voice it advertises as
its default (Kitten Bella) reads as an auto-filled echo and goes to the
primary alias, with that alias's speed and effect. On the rc the primary is
Pocket, so until #50 every "Kitten" row, smoke and speed, was Pocket. The TTS
service now logs one Info line per request (`MarmaladeTtsService:
onSynthesizeText: asked=… voice=… engine=… speed=… effectBlocks=…`), and a
row fails unless the voice it asked for spoke it, at its rate, with no
effect (`routing.py`). No row asks for Kitten's default voice (the smoke
stage takes the next one, the speed stage Leo). A build from before #50 logs
no such line and fails every row.

The `bundle` column is the release the engine's files on the phone came
from (a VITS row: its pack's), read from the app's `EngineBundle` lines. With
`--allow-outdated` an engine off the catalog shows as `v23 (catalog v26)`.
`results.json` keeps every logged bundle under `bundles`.

## Speed stage

Does playback keep up at 1.0×, 1.25×, 1.5× and 2.0×, and does the app warn
where it doesn't? Users speed voices up (alias slider, reader chips); an engine
that renders slower than the audio plays stalls mid-sentence. The app's speed
warning (`perf/SpeedPerfWarning.kt`) is meant to say so beforehand. This stage
checks that it tells the truth, and that no release gets slower unnoticed.

- **Rows.** One voice per engine (`SPEED_VOICES` in `speed.py`: Kitten Leo,
  Kokoro Heart, Kokoro German Thorsten, Pocket Alba, plus the first voice of
  every released VITS pack) × the four speeds. Every row reads the same
  sentence pair, `speed:en` in `sentences.tsv` (Kokoro German: `speed:de`): a
  short sentence then a long one, the hardest common case for chunking.
  The engine is warmed up first, and each row is rendered `--takes` times
  (default 2, interleaved across speeds) and judged on the best take.
- **Every take starts cold.** After each render the gate waits until its
  audio would have finished playing in real time (from first sound, plus any
  stalls), plus 1 s (`COLD_MARGIN_MS`). The app's playback horizon
  (`engine/PlaybackHorizon.kt`) counts emitted audio as still playing until
  then, and its chunking reads it: renders back to back saw 4–6 s of audio
  "still ahead", kept whole sentences and hid the stall a listener meets
  after a pause.
- **How it measures, silently.** The probe asks for the speed with
  `setSpeechRate`, as a client app would, renders to a file, and timestamps
  every chunk as the engine hands it to Android (`onAudioAvailable`).
  `speed.py` replays those arrival times against a real-time audio clock:
  **first sound** is when the first chunk arrived; a **stall** is a chunk
  arriving after everything before it had already played out (gaps under
  50 ms are buffer jitter and not counted). This is the system-TTS path
  (TalkBack, other apps). The in-app reader schedules its own segments
  (`MarmaladeSynthService`) and isn't measured here, but its engine runs in
  the slower place (below).
- **Verdicts.**
  - PASS: keeps up (no stall of 300 ms or more; shorter ones land at a chunk
    seam as a slightly long pause).
  - WARNED: stalls, and the app shows its speed warning at that speed. Some
    engines can't keep up at 2× on a 6a; the app saying so is correct.
    Reported, not failed.
  - FAIL: no audio or an error; the audio didn't get shorter with speed (the
    rate never reached the engine); a stall at a speed where the app shows
    **no** warning (the warning lies); or slower than the baseline (below).
  The warning is computed the way a fresh install on the reference phone
  sees it: no measured RTF yet, so `EngineRecommender`'s prediction
  (`ANCHOR_PROBE_RTF` × `REFERENCE_COST`) × speed > 0.8. The gate reads those
  constants from the Kotlin, so retuning a cost retunes the gate.
- **Which cores this measures** (#50). Android's TTS manager
  (system_server) binds the engine for every client with
  `BIND_SCHEDULE_LIKE_TOP_APP`, so a system-TTS engine runs on the
  `top-app` cpuset whatever app asks and whether the screen is on or off:
  the Pixel 6a's two X1 cores included. The gate's numbers are therefore what
  TalkBack and other apps' users get. The in-app reader is different: with the
  screen off its engine runs in the app's own foreground service, in the
  `foreground` cpuset (CPUs 0–5 on the 6a, no X1), about half as fast
  (Kokoro v26 at 1.0×: RTF ~0.43 here, ~1.0 there, with 1–2 s stalls). The
  speed warning's costs are measured on that slower path, so the gate's
  "warning lies" check is lenient by design: a stall on the fast path where
  the slow-path prediction shows no warning is a real failure.
- **Screen off, phone cool.** It runs with the screen off, as when listening
  (it presses the sleep key if the screen is on). Before each engine it
  waits until the phone's skin sensor reads 33 °C or less with no thermal throttling (`--cool-skin-c`,
  up to `--cool-timeout` 15 min, else that engine's rows fail as too warm):
  a 6a warm from the previous engine renders up to a third slower, which
  would read as a regression. Each row records the skin temperature and free
  memory it started from. A full run takes about half an hour, mostly
  cooling.
- **Output.** `speed-table.md` and `speed-lab.html` with the clips: “as
  rendered” and, where a row stalled, “as heard” (the clip with silence put
  in at each stall). With `--whisper-python <python with faster_whisper>`
  (or `MTTS_WHISPER_PYTHON`) each clip is also transcribed offline
  (`whisper-check.py`) and the lab shows how much of the text Whisper heard.
  That's for listening, not gated. To serve the lab, copy `speed-lab.html`
  and `speed/` into a registered labs directory.

### Baseline

`speed-baseline.json` holds the last release's numbers on the reference phone
per `voice@speed`: first sound, render RTF (render time ÷ audio length) and
longest stall, best of the takes. A row fails when a value exceeds
baseline × factor + slack (`speed.py`: first sound × 1.5 + 200 ms, render
RTF × 1.25 + 0.05, stall × 1.25 + 250 ms), above the run-to-run spread on a
cooled 6a. A row with no baseline entry fails too, so a new voice or engine can't
skip the comparison.

Refresh it on purpose only, once a release's numbers are checked: run the
gate with `--write-baseline`, review the diff of `speed-baseline.json`, commit
it with the release. It replaces only the rows that ran, so one engine can be
refreshed alone. Example, after a new Kokoro German bundle is installed in
the rc through its Engines screen:

```bash
scripts/release-smoke/release-smoke.py --engines kokoro-de-v1_0 --write-baseline
```

(`--engines` limits both stages to those engines; `--stage speed` skips the
smoke stage.) A deliberate slowdown (a bigger model) is the same: re-measure,
say why in the commit.

## Run it

```bash
export ANDROID_HOME=$HOME/Android/Sdk
flock ~/.cache/marmalade-gradle.lock ./gradlew :app:assembleFdroidRelease -PsmokeRelease  # app.marmalade.tts.rc
scripts/release-smoke/release-smoke.py \
    --apk app/build/outputs/apk/fdroid/release/app-fdroid-release.apk
```

Both stages run by default; `--stage smoke` or `--stage speed` runs one, and
`--engines a,b` limits them to those engines. The smoke stage takes a few
minutes on the 6a, the speed stage about half an hour (mostly cool-down).

Without `--apk` it tests what is already installed (`--package`, default
`app.marmalade.tts.rc`). Both flavors offer the same engines to system TTS, so
the same command tests a Play build (`assemblePlayRelease -PsmokeRelease`,
`app-play-release.apk`); the Play and F-Droid rc builds share the rc package,
so test one at a time.

## One-time phone setup

The rc build is a separate app (`app.marmalade.tts.rc`) with its own data, and
it isn't debuggable, so engine bundles can't be copied into it. Install each
engine once through the rc's own UI: open Marmalade (rc) → Engines → install
Kokoro, Kokoro German, Pocket TTS and VITS Marmalade's Jenny pack (Kitten is
built in). `adb install -r` keeps them across later builds. When a build's
catalog moves an engine to a new download, the gate refuses that engine until
it is updated the same way (Engines → Update).

## Changing it

- New language: add its line to `sentences.tsv`. The gate fails any
  advertised language that has none.
- New engine: add it to `EXPECTED_ENGINES` in `release-smoke.py`, its speed
  voice to `SPEED_VOICES` in `speed.py`, then measure its baseline rows
  (`--engines <name> --write-baseline`).
- Speed-stage logic: `python3 -m unittest discover scripts/release-smoke`.
- A log line that is harmless but matches the error check: add a regex to
  `logcat-allow.txt`, with a comment saying why it is harmless.
- The probe builds from the SDK's build tools (`build-probe.sh`), outside
  Gradle, so the app's reproducible build is untouched.

## Not covered

- How the audio sounds. Ear tests stay manual (`docs/release/device-test-lab.html`);
  the speed lab is for listening, the gate only times.
- The in-app reader (`MarmaladeSynthService` segments): its engine runs on
  slower cores with the screen off (see Speed stage), and the gate drives
  system TTS only. Timing the reader would need the R8 build to log its chunk
  arrivals.
- Developer-only engines: system TTS never offers them to other apps.
