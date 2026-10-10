# marmalade-tts-android — repo map

> **Read this first when investigating or fixing this codebase.** This
> file is the orientation pass that lets you skip 10+ Grep/Glob calls.
> Last updated against v0.1.17 (commit e60ab4d). Specific file:line
> refs may drift; the *shape* of the map is stable.
>
> - Entries touched by the v1.1.0 review pass (2026-09-22, commits
>   `fc46ffa`..) are current: reader routing, synth service, install
>   failures, chunking, voice filtering, alias fallback.
> - [ ] **TODO (2026-08-02): stale refresh needed** — still documents the
>   sherpa-onnx engines (`SherpaEngine`, `KittenEngine`, `KokoroEngine`,
>   `KittenVoiceCatalog`) removed in June, and Kitten Mini removed in
>   `ec79437`. Trust the code over this map until refreshed.

## In one sentence

Offline TTS engine for Android. Compose UI on top of vendored
Sherpa-ONNX inference, ships engine models as downloadable plugins,
registers itself as a system TTS service so any app can route through
it. Sister project to `/home/max/coding/marmalade-tts-cli` (the
desktop CLI ancestor) and `/home/max/coding/marmalade-android` (the
chat app whose visual identity was ported here).

## Build & run

```bash
cd /home/max/coding/marmalade-tts-android
./gradlew assembleDebug       # build only
./gradlew installDebug        # build + install on connected device
./gradlew test                # unit tests (Robolectric + JVM)
./gradlew :app:lint           # lint
```

`applicationIdSuffix = ".debug"` so the installed package is
`app.marmalade.tts.debug`. Debug-signed releases only through v0.1.x.

Unit tests run per flavor: `testFdroidDebugUnitTest` and
`testPlayDebugUnitTest` must both pass. A fresh worktree has no
`local.properties` and no espeak-ng checkout: build with
`ANDROID_HOME=$HOME/Android/Sdk` and run
`git -c protocol.file.allow=always submodule update --init third_party/espeak-ng`
first (the shared submodule URL points at the main checkout's copy).

**Release smoke gate (#28), before every release:**
`scripts/release-smoke/release-smoke.py --apk <-PsmokeRelease APK>` makes
every engine × language the build advertises speak one sentence on the
Pixel 6a, from the R8 build, and exits non-zero on any failure (no audio,
implausible length, silence, or an error/exception logged by the app's
process). It drives the app through the public `TextToSpeech` API from a
test-only probe APK (`scripts/release-smoke/probe/`, built by the SDK's
build tools outside Gradle), so R8's renaming doesn't matter and no
debug-only hook is needed. The rc package's engine bundles are installed
once through its own UI (not debuggable, so nothing can be copied in).
Unit tests can't catch what it catches: v1.1.0's German died on ICU regex
(#22) while every JVM test passed. Kokoro's accented de/bg rows send that
request language with a Kokoro voice. Kokoro honours it only when
`LangDetector` abstains on the text, and it reads most German prose as en or
fr, so those sentences are picked (and checked) to make it abstain. Not
covered: developer-only engines (system TTS never offers them). Gotcha: the phone's
system speech rate (`settings get secure tts_default_rate`) reaches
`SynthesisRequest.speechRate` even when the client called
`setSpeechRate(1.0f)`, so the gate scales its length check by it. README
next to the script.

**Headless emulator on chocolate (#34):** `scripts/emulator/emulator.sh`
(`provision` / `start` / `stop`, piped over ssh:
`ssh chocolate 'bash -s' -- start < scripts/emulator/emulator.sh`) boots
an API 35 x86_64 AVD with no window or audio as `emulator-5580`. Only the
debug build carries x86_64, so it runs debug builds, not the R8 rc. Bundles
are sideloaded by extracting a tarball into `files/engines/` with `run-as`
(no install meta = treated as current), and the release gate's probe speaks
through the public TTS API to WAVs. Use it for wide checks a phone would
tie up (every language, two bundles A/B); it checks the app's path, not
phone-exact audio (x86 ORT rounds differently) and not speed. Always `stop`
it when done. README next to the script.

## Module structure

Single Gradle module `:app`. No multi-module split (yet).

```
app/src/main/
  java/app/marmalade/tts/
    audio/            Synthesis pipeline + audio effects
    data/             Room DB + DataStore Prefs + voice catalogs
    di/               Hilt DI graph (single AppModule)
    engine/           SherpaEngine base + Kitten/Kokoro subclasses
    install/          EngineCatalog + EngineInstaller (HTTP + tar.bz2)
    preprocessing/    Text rules + emoji prosody + ProsodyApplier
    service/          TTS service, foreground synth, helpers
    ui/               Compose screens, ViewModels, navigation, theme
  res/
    drawable/         9 mascot vectors (3 used: happy, speaking, focused)
    values/           strings.xml, themes.xml, colors.xml
    xml/              tts_engine.xml (TTS engine descriptor)
  assets/             (currently empty)
  AndroidManifest.xml
```

## Architecture — one liner per layer

- **UI**: Jetpack Compose + Material 3, bottom nav, single
  `MainActivity` host, `AppRoot` runs the nav graph.
- **State**: ViewModels expose `StateFlow`s; UI collects via
  `collectAsStateWithLifecycle`.
- **DI**: Hilt. Single `AppModule` provides DAOs, settings, engines,
  installer, router.
- **Data**: Room (v4 schema, 3 entities) for voice/alias/per-app
  mappings; DataStore Preferences for user settings (theme, primary
  alias, per-engine preprocessing rule toggles).
- **Engines**: Vendored Sherpa-ONNX AAR (`libs/sherpa-onnx-static-link-onnxruntime-1.13.2.aar`).
  Models download separately per engine (setup recommends one per
  device: see "Engine speed + recommendation"). Since v0.1.19 Kokoro is **multi-language** — 53 voices
  across American + British English, Spanish, French, Hindi, Italian,
  Japanese, Brazilian Portuguese, and Mandarin. Voice/language
  orthogonality holds at the synthesizer (non-English voice speaks
  English with the voice's accent).

## Key files by concern

When investigating **{concern}**, start at **{files}**:

### Synthesis pipeline (input → audio out)
- `audio/SynthesisPipeline.kt` — *canonical* pipeline since v0.1.17;
  shared by all three call sites
- `audio/Synthesizer.kt` — in-app Speak path
- `service/MarmaladeTtsService.kt` — system TTS service (external
  apps call here); has `runBlocking` hot-path caches (v0.1.16) for
  voice→engine and rule lookup
- `service/MarmaladeSynthService.kt` — the Narrator's foreground host
  (step C1): a shell that turns intents into Narrator commands and projects
  the Narrator's state onto the notification and media session
  (`ServiceUi.render`). Only `ACTION_SPEAK` and `ACTION_HOST` (both sent via
  `startForegroundService`) promote it; once nothing is on air it leaves the
  foreground and drops the notification at once (no stale "Speaking…" with a
  live Pause after Stop; a read arriving within the grace promotes it again)
  and stops itself 1.5 s later (`stopSelfResult(lastStartId)`, so a newer
  start in flight still reaches its `startForeground`). `ACTION_SPEAK` → `SpeakShim` → one Narrator one-shot and
  one `PreviewCompletions` post. A new speak queues behind other spoken text,
  reads over a playing article (which resumes after it) and **replaces an
  article the user paused** (the Narrator decides — plan §2.8)
- Order of the canonical chain: emoji-detect → preprocess →
  strip-emoji → engine synth → ProsodyApplier → EffectChain (for the
  Narrator's audio the EffectChain step runs on the playback side — see
  "effect chain at PLAYBACK" under Known quirks)

### Engines
- `engine/EngineRegistry.kt` — **the one `engineName → TtsEngine` table**
  (2026-09-29). Name narrowing (`knownEngineOrDefault`, `engineNameFor`,
  unknown → Kokoro Direct), system TTS's narrower set
  (`SYSTEM_TTS_ENGINE_NAMES`: developer-only engines never offered to other
  apps), `onDevice` (all but cloud: residency, native-handle release,
  "release all") and `warmable` (on-device minus developer-only). A new
  engine is added HERE; never write another `when (engineName)` dispatch.
- **Debug-only bench (`app/src/debug/.../devbench/`, issue #1 "B1", 2026-10-04)**:
  `Kokoro7mDevEngine` (Kokoro-7M-Distill as a `KokoroDirectEngine` subclass,
  one style row, en-us, bundle **sideloaded** into
  `filesDir/engines/kokoro-7m-dev/`) and `EngineBenchService`, an adb-only
  foreground service (exported, guarded by `android.permission.DUMP`) that
  renders a sentence file through any engine to WAVs **without playing
  anything** and logs `B1Bench` lines (TTFA, RTF, load ms, PSS delta, thermal).
  Neither is in `EngineRegistry`/`EngineCatalog`: the debug source set keeps
  them out of every release build, developer mode included. Usage in the
  service's file comment; tests in `app/src/testDebug/`.
  The 7M only works on misaki's phoneme spellings (issue #4: plain espeak
  gave 31–34% WER, upstream's own pipeline 3–4%), so it overrides
  `KokoroDirectEngine.englishPhonemes` (a no-op seam for Kokoro-82M) with
  tied espeak → `EspeakToMisaki`, a port of misaki's own EspeakFallback.
  **A small student is only as good as the front end it was distilled on**:
  check that before blaming the model.
  Streaming was prototyped here as dev engines: Kitten (issue #3 "D1a",
  `~/coding/scratch/kitten-stream-d1a/`) and Kokoro (#5 "D1c",
  `~/coding/scratch/kokoro-stream-d1c/`). Both are gone: since #10 and #13
  "E" that is how **KittenDirect and KokoroDirect themselves render** (see
  "Kitten streams" and "Kokoro streams" below). On the phone each generator
  call carries ~120 ms of fixed overhead, which is why slices double up to
  4 s instead of staying at 1 s (#5, #9). **Intra-op spinning helps the split
  path only** (#5 clean phone bench, 2026-10-07, Heart, B1 set; first audio
  median/worst, RTF): unsplit Kokoro 1.81 s / 5.6 s, RTF 0.70; streamed
  1.36 s / 2.3 s, RTF 0.85; streamed + spin 0.94 s / 1.38 s, RTF 0.79; the
  unsplit model with spin RTF 0.78 vs 0.75 without. The planner and every
  slice are many short ORT calls, and sleeping pool threads pay a wake-up on
  each. #13 measured spinning's cost in process CPU time over a 6-minute
  screen-off read: +2.5% (within run-to-run noise), so it stays on.
- **Debug-only model timing (`devbench/OnnxBenchService`, issue #12 "X1")**:
  times ANY `.onnx`/`.ort` file with no engine around it, silently (outputs
  are dropped). One adb start = one line of `files/x1/specs.txt` = one
  session, then **the process kills itself**, so every run's load time and
  PSS start from a fresh process (it releases any engine that app start
  warmed first). The spec grammar, input recipes (seeded random, constants,
  `.npy` sliced or wrapped along an axis, dims linear in `T`) and log lines
  (`X1Bench`: load ms, PSS total/native/other, VmHWM, first call, per-size
  median, fixed + per-audio-second fit) are in `OnnxBenchSpec`, pinned by
  `OnnxBenchSpecTest`. Session knobs: `ep`, `threads`, `opt`, `arena`,
  `mempattern`, `prepack`, `spin`, `mmap` (`.ort` with initializers in the
  mapping), `shrink`. Driver, models and results:
  `~/coding/scratch/x1-phone-timing/` (`x1.py`). Gotchas: ORT 1.26's
  "Runtime"-style `.ort` of Kokoro's QDQ graph won't load (selector replay
  error), so CPU runs use "Fixed" style with `--target_platform arm`; the
  8a flags LIGHT throttling at ~39 °C skin (`VIRTUAL-SKIN`), so the driver
  gates on that and requeues a run that ended throttled.
  `BenchDevice` holds the thermal line and foreground notice both bench
  services share. **Findings to know before touching an engine's
  session setup** (X1, Pixel 8a, measurement only; results lab
  `tts-training-research/x1-results-lab.html`):
  - XNNPACK keeps a separate weight copy per reuse of a shared layer:
    ALBERT on XNNPACK is +280 MB against +35 MB on the plain CPU EP, which
    is most of Kitten's 364 MB. XNNPACK was also slower than the CPU EP for
    all 21 models tried. **Applied in #13 "A"**: Kitten's planner left
    XNNPACK.
  - With prepacking off, the XNNPACK planner SIGSEGVs in ORT 1.26.
  - 4 intra-op threads ran Kitten's generator 1.8× faster than the 5 that
    `CpuClusterDetector` picked. **Why (#13 "C")**: a screen-off reader is a
    foreground service, and the 8a's `foreground` cpuset is CPUs 0–7 (the
    prime core, CPU 8, is only in `top-app`), so the 5th thread ran on a
    little core. The autodetect now leaves out a lone prime core when at
    least two other fast cores remain (`CpuClusterDetector.threadsFor`; a
    1 + 1 + 6 Snapdragon 765G keeps it, or it would drop to one thread);
    apps can't read `/dev/cpuset` (SELinux), so the rule is general, not a
    lookup.
  - A fresh or idle process's first ~1 s of calls costs +40–85 ms each.
  - An arena shrink after each run halves the generator's and Kokoro's
    peak RAM. **Applied in #13 "B"** (`OrtRunAbort(shrinkArena)`, Kitten's
    generator and every Kokoro run).
  - **A/B in one APK (#13)**: debug builds read session knobs from
    `files/dev/engine-knobs.properties` at engine load (`EngineDevKnobs`;
    the release twin returns null): `threads`, `shrink=false`,
    `kitten.plannerXnnpack=true`, `kokoro.stream=false`, `kokoro.spin`.
    `EngineBenchService --ez exit true` gives each run a fresh process and
    logs peak PSS, VmHWM, process CPU time and battery charge per run;
    `--es dumpTokens` writes phone-exact Kokoro token ids. Driver:
    `~/coding/scratch/lean-engines/bench.py`.
- **Kitten's planner runs a trained small text encoder, not ALBERT**
  (issue #14 "X2" trained it, #17 put it in the app): a 4-layer width-256
  encoder (3.4M weights, X1's `te-d256` shape; ALBERT + `bert_encoder` was
  5.8M) maps the same token ids to the same `d_en` that Kitten's duration
  and F0/N predictors consume; everything else in `kitten.onnx` stays.
  `split.py`'s `with_student` swaps it in by graph surgery (its output
  replaces `/bert_encoder/Add_output_0`, names prefixed `x2/`, opset 17 →
  20), so the planner is deterministic from `kitten.onnx` + `student.onnx`
  (both sha-pinned). Desktop parity with #14's PyTorch path is exact
  (durations identical on all 136 B1 cases, F0 within 3e-4 Hz); vs ALBERT
  0.108 frames/token (90% identical), F0 5.3 Hz, streamed Whisper WER
  4.46% vs 4.33% (all number/spelling formatting). `genstats/` was
  recomputed with the new planner. The student must see exactly the app's
  Kitten token ids (it was trained on them), and its position table caps
  inputs at 512 tokens like ALBERT's (`x2//pos/Gather` errors past it).
  A dynamic-int8 student passes the same lines (4 MB vs 13.5 MB) but is
  not shipped (#17 report). Weights, training, ONNX export:
  `~/coding/scratch/x2-encoder/`; #17 parity/WER/bench scripts:
  `~/coding/scratch/kitten-small-planner/`.
- **Kokoro's planner is about half ALBERT on a slower phone** (issue #16;
  in the app since #25 as "the v26 planner", see "Kokoro streams"). On the Pixel 6a (Tensor G1, the app's CPU EP,
  4 threads, spin) the planner takes 209/435/830/1248 ms at 32/80/160/240
  tokens and ALBERT + `bert_encoder` (fp32; the rest of the planner is
  already int8 QDQ) is 44–52% of it; x86 desktop 27–32%; the 8a is
  unmeasured. Two drop-ins for d_en (`/bert_encoder/Add_output_0`,
  [1,T,512]), both verified on desktop only:
  - **int8 ALBERT** needs ALBERT's Identity nodes folded before
    `quantize_dynamic` (the shared layer's weights reach its MatMuls through
    them: a plain call quantizes 8 of 74), and the int8 planner must be
    re-joined from parts (quantizing the whole planner aborts ORT 1.26/1.30
    at load). All-int8 fails Chinese (1.6 frames/token, 31 Hz); keeping the
    FFN expansion MatMul fp32 ("int8f") passes all 8 languages tested and saves
    ~half of ALBERT's time.
  - **An English-only student** (#14's 4-layer width-256 encoder with a
    512-wide output, distilled from Kokoro's ALBERT on half #14's corpus):
    within the X2 lines on English (0.14 frames/token, 3.6 Hz, WER equal;
    a width-192, 2.0M version passes too, 0.16 / 3.9 Hz, but saves only
    ~14 ms more), NOT on ja/zh, so other languages keep ALBERT. On the 6a ALBERT alone is
    645 ms at 240 tokens, the student 40 ms; the planner with it 245/645 ms
    at 80/240 tokens (today's 433/1244).
  - Every lighter planner (int8 or student ALBERT) on the 6a sometimes
    starts a fresh bench process ~1.8× slow and flips fast mid-run; today's
    planner never did (18 runs), nor did today's planner re-joined from its
    parts the same way (so it's the graph's weight, not the surgery).
    Pinning ORT's pool threads to the big cores ("6;7;8") cures the int8
    planners, not the student's.
  - **In the app's own process (#25, 6a, a 157-token English / ~92-token
    Spanish sentence ×8, fresh process per run, screen off)** it is not a
    start-up effect and not special to the lighter graphs: single pieces
    take 1.4–2× at any point, in some runs (v26 English 4 of 42 pieces,
    normal 0.51 s; Spanish 4 of 60, 0.44 s), and today's whole planner
    flips more (0.87 ↔ 1.2 s English, 0.52 ↔ 0.86 s Spanish). Likely
    cause: the 6a's `foreground` cpuset is CPUs 0–5 (`/proc/<pid>/status`
    `Cpus_allowed_list`), so its two X1 cores (6–7) are top-app only and a
    screen-off reader has two fast cores (A76, 4–5) for 4 threads. ORT's
    `session.intra_op_thread_affinities` ids are **1-based** (#16's "6;7;8"
    = CPUs 5–7, two of them outside that cpuset). Pinning the pool to the
    A76s ("5;6", 3 threads) or 2–3 threads unpinned is slower overall
    (English 0.61 s normal; Spanish 40% slow pieces), so the thread rule
    stays 4 unpinned; the `kokoro.affinity` knob remains for A/Bs.
  Scripts, parts and results: `~/coding/scratch/kokoro-planner-k16/`
  (README.md; corpus, teacher store and checkpoints on chocolate, same path;
  lab `tts-training-research/kokoro-planner-lab.html`).
- **Debug-only pickable engines (`BuildTypeEngines`, issue #9)**: a
  prototype engine can be made selectable like any other voice in the DEBUG
  build (aliases, the picker, the primary alias that system TTS uses). None
  is listed today: "Kitten Nano (streaming)" retired in #10 and "Kokoro
  (streaming)" (`kokoro-stream-dev`) in #13 "E". The seam is
  `engine/BuildTypeEngines.kt`, one file per build type: `src/debug/` lists
  the engines, their voice rows, labels and an on-disk check, and binds the
  instances into `EngineRegistry`'s `Set<TtsEngine>` (Hilt `@ElementsIntoSet`);
  `src/release/` is an empty twin with the same signatures, so release builds
  list, route to and load nothing extra. `retiredEngines` (retired engine →
  replacement with the same voice keys; debug: `kokoro-stream-dev` →
  KokoroDirect) is applied at every start by `data/RetiredEngines.kt`
  (`migrateRetiredEngines`): aliases and the default voice move, the retired
  voice rows go; idempotent, a no-op in release. Main code consults the seam
  in `EngineRegistry` (`ENGINE_NAMES`, never `warmable`, `displayName`),
  `MarmaladeTtsApplication` (retirements, then rows upserted on every start,
  outside CATALOG_VERSION), `EngineInstaller.verify` +
  `probeInstalledVoiceAssets` (sideloaded = installed), `VoicePath` and the
  alias editor's engine list. Bundles stay sideloaded (no catalog entry); a
  debug-only engine goes through the seam, never into `EngineCatalog`.
- **Retired voices** (issue #19): a voice dropped from a catalog goes in
  that catalog's `retiredVoices` (retired id → replacement id, same
  engine), and `migrateRetiredVoices` (`data/RetiredEngines.kt`) applies it
  on every start, release too, after the reseed: aliases keep their ids and
  move with `VoiceAliasDao.repointVoice`, the default voice follows, the
  row is deleted. Today: Pocket's `marius` → `alba` (its WAV stays in the
  bundle, unread), mirrored for the clean twin by
  `PocketDevVoiceCatalog.retiredVoices`. `PocketVoiceCatalog.OFFER_VCTK_VOICES`
  is the one-line switch that would retire `azelma`/`eponine`/`fantine` the
  same way. The installer's Pocket layout check requires a WAV per offered
  voice only.
- **Kitten streams** (issue #10, `engine/kitten/KittenDirectEngine.kt`):
  the baked bundle is `planner.onnx` + `gen.onnx` + `genstats/<voice>.bin`
  + `voices/<voice>.bin`, cut from upstream's `kitten.onnx` by
  `scripts/kitten-stream/split.py` (the text encoder is the X2 student
  since #17, Kitten's other weights unchanged; `verify.py` checks the split
  is exact, slices join at >120 dB SNR, the fixed norm statistics sit at
  Kitten's own run-to-run noise, ~0.6 dB mel, and prints the student's
  timing/pitch distance from ALBERT). The seed's `SHA256SUMS` lives in the
  seed dir and is its **content fingerprint**: `seedFromAssets` (and so
  `refreshSeed` at app start) re-copies an installed Kitten whose copy of
  that file differs from the APK's, once; `EngineInstallerSeedTest` fails
  if it doesn't match the seed's files.
  Each clause chunk is planned ONCE (style row, durations, pitch, decoder
  front, harmonic source), then the generator renders its speech frames in
  slices of 0.5 s, 1 s, doubling to 4 s with 4 frames of context
  (`KittenStreamSlicing`), each sent as its own `SynthAudio`. Every planner
  and slice run goes through `AbortableInference`; `synthLock` is held per
  ORT call, never across a send. No non-streaming mode (one slice costs what
  the unsplit model did; each extra slice ~120 ms on an 8a). Clause chunking
  (F rules) stays: it is prosody, not latency. The layout check rejects the
  pre-1.2 `kitten.onnx` layout, so `MarmaladeTtsApplication` replaces an
  installed old copy (old layout, or old/missing `SHA256SUMS`) from the
  seed at start (`EngineInstaller.refreshSeed`; an uninstalled Kitten stays
  uninstalled) and `EngineInstaller.install`
  copies a baked engine from the APK (`SeedAssets`) instead of downloading
  — the v22 download still has the old layout. Only a piece's first slice
  is a `SynthAudio.resumePoint` (see the Narrator's resume points).
- **Kokoro streams** (issue #13 "E", `engine/kokoro/KokoroDirectEngine.kt`,
  prototyped in #5): a bundle with `planner.onnx` + `gen.onnx`
  + `genstats/<voice>.bin` (a non-empty file for every catalog voice, or it
  isn't the streaming layout: `hasAllGenStats`; an unknown voice key renders
  with speaker 0's stats, `genStatsKeyFor`; the int8 v23 model cut in two,
  weights unchanged; scripts `~/coding/scratch/kokoro-stream-d1c/`) plans
  each piece ONCE and renders it in Kitten's slices (0.5 s, 1 s, doubling to
  4 s, 4 frames of context; `KokoroStreamSlicing`, whose slices never cut
  inside the trailing pause, so TailTrim + SilenceCompressor run on the last
  slice alone). The generator's 48 norms can't use one fixed table per voice
  (~2.9 dB off): `genstats/<voice>.bin` is a small per-voice linear model and
  `KokoroGenStats` turns it into each piece's table from its pause share and
  length. Sentences stay whole up to the 500-token cap (budget target 360);
  over-cap chunks still re-split (`tokenPieces`). The planner reads a whole
  piece before its first slice (~5 ms a token on the 8a), so a first
  sentence over 160 tokens is cut at a clause into an ~80-token head when
  its plan would be heard: nothing playing covers it, or a pre-roll is due
  (`kokoroStreamCutFirst`; #13 review, the long-article set waited up to
  5.5 s without it). Pre-roll above 1× banks SLICES, not chunks
  (`PrerollGate.streamBankMs`): played audio enough to cover the request's
  estimated deficit, from the last measured streamed RTF (0.85 before any),
  with the hold's render time capped at 2 s. Holding whole chunks made a
  sped-up listener wait for a full sentence plus the next one's plan.
  Both graphs: CPU EP (QDQ),
  intra-op spinning ON (#5 clean phone bench, Heart: first sound median /
  worst 1.81 s / 5.6 s unsplit → 0.94 s / 1.38 s streamed + spinning). A
  bundle without the layout (v23 and older, the German engine, the debug 7M)
  renders exactly as before
  through `model.onnx` (the whole path: short first
  piece, chunk-grain `PrerollGate`, spinning off). `isInstalled` and the
  installer's layout check accept either; `modelFile()` prefers a `.ort` of
  any graph. Debug knobs (`EngineDevKnobs`): `kokoro.stream=false` forces the
  whole path when model.onnx is present, `kokoro.spin=false|true`. The
  `render path:` log line at load names the path, threads, spin and shrink;
  `StreamPerf` has one plan line per request (`cutFirst`, `bank`), then per
  piece `kokoro plan=…`, per slice `kokoro slice=i/n`, and `kokoro piece …
  rtf=`. `lexicon-zh.txt` (68k entries, 0.6–0.8 s) loads on the first text
  with a CJK run (`mandarinLexicon()`), not at load (#13 "D2").
  The v26 download (#25) is the first to ship the layout: `planner-rest.ort`
  + `textenc.ort` + `textenc-en.ort` + `gen.ort` ("Fixed" style, ARM; the
  ORT converter isn't byte-deterministic, so the files are frozen in
  `~/coding/scratch/kokoro-v26/ort/` with their sums) + #13's genstats,
  unchanged (refitting all 53 voices under the v26 planners moved nothing
  beyond 0.07 dB), and no `phonemizer/` (the unread espeak-ng-data;
  `model-format.txt` stays, `isQuantizedBundle` reads it). Built by `~/coding/scratch/kokoro-v26/bundle/make_bundle.sh`.
  On the 6a (screen off, B1 set) first sound 2.1 s → 0.8 s (Heart),
  3.7 s → 1.5 s (Spanish); load 3.1 s → 1.0 s; peak −110 to −230 MB; RTF
  0.89 → 0.94 and processor time +35–70% per audio minute (streaming's).
  **The v26 planner** (issue #25): `planner-rest` (the planner minus ALBERT
  + `bert_encoder`; extra input `/bert_encoder/Add_output_0`, [1,T,512]) fed
  per piece by one of two text encoders (tokens → that tensor):
  `textenc-en` (#16's d256 English student) when the piece's ids are English
  (an `en*` language and no Han run, `textEncoderFor`; the student reads only
  token ids, so the voice doesn't matter), else `textenc` (#16's "int8f"
  ALBERT, every language). `hasSplitPlanner` = planner-rest + textenc
  (textenc-en optional; without it English uses textenc) and it wins over a
  whole `planner` when both are present. Encoder sessions open on first use
  (`textEncoderSession`, ~50–90 ms / ~230 ms on the 6a) and stay open; the
  warmup opens textenc too when the default voice is non-English. Two whole
  planners were rejected: each carries the same 89 MB "rest", so the
  download would grow ~80 MB and a language switch would reload 100 MB.
  Debug knobs: `kokoro.planner=whole` (use a whole `planner` beside the
  v26 files, for an A/B), `kokoro.affinity` (ORT
  `session.intra_op_thread_affinities` for the streaming graphs). The plan
  line names the encoder (`enc=`), the load line both files.
- **Pocket streams** (issue #15, `engine/PocketEngine.kt` + `engine/pocket/`;
  both flavors since #29). Five graphs: text_conditioner, mimi_encoder (voice prompt),
  flow_lm_main (the AR transformer, int8, 6 KV caches of 1000 rows), flow_lm_flow
  (one LSD step), mimi_decoder (56 state slots). Per mini-chunk: fresh flow_lm
  state from the voice prompt, AR frames at 12.5 Hz, each latent frame decoded
  into 1920 samples. What #15 changed, and why (results lab
  `tts-training-research/pocket-speed-lab.html`, Pixel 6a):
  - **State is carried, never copied** (`PocketStateCarry`): a run's output
    tensors are the next run's inputs, and the `Result` owning them closes once
    nothing references it. ORT 1.26's `OnnxTensor.getFloatBuffer()` allocates a
    heap array and copies; the old path did that for the 49 MB of KV caches twice
    per AR frame (1.5 s per frame on the 6a). Init states (`flowInitStates`,
    `mimiState`) are only read, never refilled: refilling the decoder's slots one
    value at a time cost ~0.95 s per sentence. ORT never writes a graph input.
  - **The voice prompt runs once per voice** (`voicePrompt`, "C"): phase 1 has
    no randomness, so later chunks start from its cached state frame. It holds
    ~49 MB native while the engine is loaded (the 1000-row cache; ~22 MB at
    448 rows with "F"), freed by `release()` (residency evicts idle engines)
    and on deleting that cloned voice. Dropping it after every request was
    measured on the 6a and rejected: first sound +190 ms, CPU +10%, and idle
    PSS no lower (799 vs 774 MB, mean of 2: the native heap keeps the freed
    memory and re-running phase 1 grows it).
  - **Decode batches of at most 46 frames are exact** (`PocketMimiBatch`, "D"):
    the export bakes mimi's attention ring at 1000 slots, and the decoder writes
    16 steps per latent frame with a 250-step lookback, so a batch B is exact
    iff `16·B + 249 ≤ capacity`. Larger batches overwrite keys still needed and
    corrupt early frames SILENTLY (the old P-AL 64-frame re-decode garbled
    frames 8–32 of long sentences; probably the old "distorted word" reports).
  - **Every AR frame needs a flow_lm cache row** (#23): the voice prompt
    (BOS + up to 375 rows for a 30 s clone), the chunk's tokens and one row
    per frame must fit in the cache (1000 rows shipped); past it
    flow_lm_main throws a Reshape error and the request fails. So a word
    over the chunk's token limit (URL, hash) is cut into pieces
    (`PocketTokenSplit`; it and the chunk packing count tokens after
    `preprocessForPocket`, whose trailing `.` is a token), and the
    frame cap is cut to the rows left (`PocketCacheShapes.frameBudget`,
    logs `Frame cap … cut to …`).
  - **Streamed render** (`streamChunks`, "E"): frames go to the decoder in
    slices while the AR loop runs; by default the decoder runs on its own
    coroutine (`pocket.stream=inline` decodes on the AR thread). The decoder's
    job queue holds 2 slices (`DECODE_QUEUE`), so when the Narrator's
    read-ahead permit closes the AR loop stops too: past it the engine renders
    at most `ENGINE_AHEAD_CHUNKS` (8, now counting slices) + 4 slices, about
    30 s or 6 sentences of long text on the 6a (an unlimited queue rendered
    the whole segment). Slices are
    cut by deadline (`PocketSliceClock`): the first at 6 frames, each later
    one when waiting one more frame would let playback run dry (min 4 frames,
    max the exact batch). Fixed doubling slices (6/12/24/46) stalled in 13
    of 14 sentences at RTF 0.63, and a length-estimated hold only halved
    that (sentence lengths are only known to ±15%). A first-sound hold
    (`PocketStreamPlan.holdMs`, capped 3 s) is only set when the render
    can't keep up (slow phone, high speed); the decoder's cost per slice is
    fitted live (`PocketDecodeCost`, ~60 ms + 24 ms/frame on the 6a; no line
    until sizes span 8 frames, or a 6- and a 7-frame slice tilt it to ~0 ms).
  - **Decode safety factor** ("G", `PocketSliceClock.DECODE_SAFETY` = 3): the
    deadline assumes a slice may take 3× its usual decode. A screen-off app
    (even a foreground service) is in the `foreground` cpuset, cores 0–5 on
    the 6a: no big cores. The AR loop and the decoder each keep a mid core
    busy, and now and then the scheduler parks the decoder on a little core
    for seconds (2.4–5.5× slower slices, ~1 in 50). With the bare estimate
    70 of 91 slices arrived with < 0.5 s of audio left; with 3×: pauses
    1.3 s → 0 per 3-paragraph run, 0.44 s → 0.01 s per 14 sentences, same
    RTF, +3% CPU, audio within 1 LSB. Per-thread cores: `ps -T -p <pid> -o
    TID,PSR,TIME+` (`~/coding/scratch/pocket-speed/psr.sh`).
  - **Threads** ("B", `PocketSessionPlan`): the flow head and the decoder get
    half the threads (at least 2): with the decoder alongside the AR loop, two
    full pools oversubscribed the 6a (2 decoder threads: same RTF, −11% CPU,
    audio within 1 LSB). XNNPACK stays on everywhere (off: −4% RTF, +10% CPU);
    intra-op spinning stays off (on: RTF 0.61 → 0.84, CPU ×2.4, because
    spinning threads starve the decoder running alongside).
  - **Symbolic cache axes** ("F", `PocketCacheShapes`): if a bundle ships
    `*_dyncache` graphs (cache axis −1 in the declared shape), flow_lm's caches
    are sized per voice (`roundUp64(promptRows + tokens + maxFrames)`, ≤ 1000)
    and mimi's ring at 320 (batch ≤ 4). The published bundle doesn't have them.
  - **Weights versions** (#31): Kyutai's `english_2026-09` only retrained
    the flow_lm backbone (transformer, text conditioner, speaker projection,
    EOS head, BOS embeddings). The flow head and the whole Mimi codec are
    bit-identical to 2026-04, so `flow_lm_flow` and `mimi_decoder` (and the
    ≤ 46-frame exact batch) carry over unchanged. `mimi_encoder.onnx` bakes
    the speaker projection in, so a voice embedding (`voice_cache/*.emb`,
    a cloned voice's `.bin`) belongs to one weights version: never reuse
    one across versions. Kyutai's gated repo needs an accepted prohibited-use
    prompt; the ungated `kyutai/pocket-tts-without-voice-cloning` has the
    same weights with the Mimi encoder zeroed, so an export from it takes
    the encoder graph from the previous bundle and swaps in the new
    speaker projection (initializer `val_419`, `[32,1024]` = W^T). Export
    tooling, configs and checks: `~/coding/scratch/pocket-2026-09/` on
    chocolate (`tools/`, `README` in `export/` on marmalade); the exporter
    run on 2026-04 reproduces v21's graphs exactly.
  - Debug knobs (`EngineDevKnobs`): `pocket.xnnpack`, `pocket.spin`,
    `pocket.<text|enc|dec|main|flow>.threads` (`PocketSessionPlan`),
    `pocket.stream=inline`, `pocket.decode=<frames>`, `pocket.decodeSafety`, `pocket.seed` (bit-exact
    A/B), `pocket.ortProfile=<requests>`, `pocket.file.<graph>` (sideloaded
    graph), `pocket.cacheLen`, `pocket.mimiCap`. Logcat `StreamPerf`: per chunk
    `pocket chunk=i prof …` (AR split main/state/flow) and `… decode=…`, per
    slice `pocket slice …`, per request `pocket hold=…`. Bench driver and
    analysis scripts: `~/coding/scratch/pocket-speed/`.
- `engine/SherpaEngine.kt` — abstract base (loadLock, ensureModelLoaded,
  synthesize, release, floatToPcm16, sampleRate)
- `engine/KittenEngine.kt`, `engine/KokoroEngine.kt` — subclasses,
  ~100 lines each; only override `buildModelConfig`, `speakerIdFor`,
  `engineName`, `modelFileName`, `defaultSampleRate`
- `data/KittenVoiceCatalog.kt`, `data/KokoroVoiceCatalog.kt` — static
  voice metadata (seeded into Room at app startup).
  `KokoroVoiceCatalog.languageFor(voiceKey)` derives the natural BCP-47
  language code from the upstream voice-key prefix (a=en-US, b=en-GB,
  e=es-ES, f=fr-FR, h=hi-IN, i=it-IT, j=ja-JP, p=pt-BR, z=zh-CN).

- **Kokoro chunk sizing is in model tokens** (`TextChunker.planByTokens`,
  budget `TOKEN_BUDGET` in `KokoroDirectEngine`, inherited by the German
  engine): sentences split at `.!?;:` + space, newlines and 。！？；：; tiny
  sentences merge up to 90 tokens (never past 200, never the request's
  first); a sentence over 270 is cut at `,;:`/—/、，；： to ≤ 200, then
  word level (spaces; Japanese kana→kanji/katakana steps), then hard. (The
  streaming path uses target 360 and never the short first piece below; see
  "Kokoro streams".) Every piece of a cut sentence uses the WHOLE sentence's style row
  (`TokenChunk.rowTokens` → `kokoroStyleRow`) — Kokoro picks its row by
  token count, and a short piece's own row sounds like a sentence end.
  **Short first piece** (Max's blind A/B, `docs/release/first-piece-lab.html`):
  when the audio Kokoro already emitted (`engine/PlaybackHorizon`, an
  in-engine estimate — the service passes no "cold" flag) can't cover
  rendering the first sentence, that sentence alone is cut at a clause
  mark (never a word gap) into a 15–55-token first piece sized for
  ~1.1 s of render from the last measured ms/token, then pieces growing
  by `0.9 / (RTF × playbackRate)` (`kokoroStreamBudget`). No seam trim.
  One `D/StreamPerf: kokoro plan …` line per request (cutFirst, aheadMs,
  firstPiece, growth, msPerToken). Other engines still
  chunk by characters (`TextChunker.chunk` / `clauseChunks`).
- **Over-cap chunks**: `audio/TextChunker.splitToFit(text, fits)`
  re-splits a chunk that still overflows an engine's cap (clause
  punctuation → whitespace → hard cut, nothing dropped). Kokoro and
  Kitten call it with a "phonemizes to ≤ cap tokens" predicate
  (`tokenPieces` / `ipaPieces`) and render + emit each piece as its own
  stream chunk, never joined first;
  `TtsEngine.maxInputChars` is the per-engine character cap.

### Narrator (`playback/`) — the owner of all in-app speech
The reader-session redesign (local plan `docs/release/READER-SESSION-PLAN.md`,
steps A → C1 → C2 → C3 → E → G). Since C1 every in-app read goes through the
one Narrator: the reader drives it directly (`ReaderPlaybackController`), the
Speak screen / previews / shares / Tasker reach it through the service's
`ACTION_SPEAK` shim (`SpeakShim`; step E moves the in-app callers in-process),
and `MarmaladeSynthService` is its foreground host. System TTS
(`MarmaladeTtsService`) does not use it. The package itself is pure Kotlin.
- `playback/NarratorApi.kt` — `NarratorControl` (commands, one
  `StateFlow<NarratorState>`), `NarratorShell` (focus/host signals) and the
  ports the service shell will implement (`SessionResolver`,
  `SegmentPreparer`, `FocusPort`, `HostPort`, `ResidencyPort`, …).
- `playback/StartPolicy.kt` — pure start/hold: today's PrerollGate formula
  minus the audio already banked, fed the session's RTF EWMA.
- `playback/RenderPlanner.kt` — pure "what to render next": ~60 s ahead for
  on-device voices (also while paused), cloud only this segment + the next.
  `NarratorConfig(todaysDepthOnly = true)` depth-limits every voice — how
  C1–C3 must run until G (Pocket holds its lock for a whole utterance). The
  60 s cap applies to depth-limited voices too, so one huge segment (a long
  share with no blank lines) never renders whole into the heap.
- `playback/AudioOutput.kt` — the writer port: stream items (Begin/Write/End)
  queue at their frame, controls (Play/Pause/Flush/SetTempo) apply at the
  next slice; every chunk tagged `(sessionId, epoch, segment, chunk)`.
- `playback/Narrator.kt` — the one owner: a command loop on its own
  single thread (`Narrator.newLoopDispatcher()`) whose handlers never
  suspend; resolution and rendering are child jobs reporting tagged events.
  After every message `settle()` plans the render (RenderPlanner), runs the
  StartPolicy, writes chunks to the output, syncs track play/pause, focus
  and residency, and publishes the state. Audio is keyed by per-session
  epochs (a jump/stop/resume bumps it and flushes the writer); child jobs
  by their own token. Encodes Max's §6 answers: a share while the article
  plays interrupts on the `Interrupt` track and the article resumes from the
  start of the interrupted chunk's sentence; ~60 s ahead on-device, cloud this block +
  next; preparing continues while paused except after `PauseReason.Navigation`;
  every speed change (cloud too) is the writer's live Tempo, engines render
  at 1.0 (speed range: the article 0.5–3, the reader's chips; a one-shot
  0.25–4, system TTS's range, for Tasker's `EXTRA_SPEED`); permanent focus loss → `Paused(FocusLoss)`; 30 min paused → stopped
  (Idle, the shell stops); Previous/Next act on the article only (Next past
  the last block finishes it; from a stopped article both start playing, as
  the reader always did). `play`/`pause`/`toggle` act on the one-shot on air,
  else the article (notification, media keys); `playArticle`/`pauseArticle`
  are the reader's article-only buttons.
  A stop drops the article's audio and restarts its block on Play — a
  re-render need not cut the same chunks (Kokoro's short first piece).
  **Resume points** (issue #11): every rewind to "the heard chunk" (resume
  after an interrupting share, rewriting the other sessions on a flushed
  track) goes through `Session.listenerChunk()`, which steps back to the
  last chunk flagged `SynthAudio.resumePoint`. Engines that render whole
  pieces leave it true; streaming ones flag only a piece's first slice
  (KittenDirect, KokoroDirect streaming), so on-device voices resume
  at the sentence (or clause) start, never mid-word. The cloud engine flags
  a request's first network buffer: a cloud resume restarts the interrupted
  REQUEST (≤ 1000 chars, usually the paragraph; the MP3 path is one buffer
  per request anyway). `trimHeard` never drops the listener's sentence. The
  render budget counts ahead from the heard chunk, not the resume point (a
  whole cloud paragraph would otherwise count as "ahead").
- `playback/SegmentRenderer.kt` — one render job: prepare, stream the
  engine at 1.0, wait for the budget permit between chunks, one TTFA sample
  and one RTF sample per job (chunks after a permit wait are skewed: the
  engines keep rendering into their own buffer meanwhile — cut from the
  channelFlow's 64 to `ENGINE_AHEAD_CHUNKS = 8` by a fused `.buffer(8)`).
- Device side (step C1): `di/NarratorModule.kt` builds the one `Narrator`
  (`NarratorConfig(todaysDepthOnly = true)` until G, loop on
  `Narrator.newLoopDispatcher()`, logcat tag `Narrator`) over
  `service/NarratorResolver.kt` (voice once per session: alias / installed /
  explicit / language-aware share, the no-alias fallback, rules, cloud flag;
  `NarratorPreparer` = rules → emoji strip → per-segment phonemizer language),
  `service/NarratorPorts.kt` (`ServiceHost` = "service is foreground now",
  `AudioFocusPort` = one `AudioFocusRequest` per hold, `NarratorStats` = TTFA
  into `VoiceLatencyTracker` + RTF into settings, `NarratorWakeLock`/`WakeLockArm`
  = held while buffering/playing, re-armed per heard chunk), `EngineResidency`
  (the `ResidencyPort`), `KeepaliveCoordinator` (session start) and
  `service/AudioTrackOutput.kt` — the writer (step C2): ONE thread,
  `narrator-writer`, is the only caller of the in-app AudioTracks (at most two:
  `Track.Main`, `Track.Interrupt`). It runs `playback/TrackWriter.kt` — pure
  Kotlin, the same code the harness runs — per track: a flat state machine
  that applies commands between ≤ 20 ms input slices and writes non-blocking.
  **Playback is continuous**: one track for every block of a session and across
  sessions at the same sample rate (no drain, rebuild or chain restart at a
  paragraph); a Begin at another rate waits until the audio ahead is heard,
  then rebuilds at that boundary frame. One `StreamingEffectChain` (live Tempo
  + alias effects) per session. Flush = pause → `AudioTrack.flush()` (ignored
  while playing!) → head and frame marks reset → older epochs dropped. **The
  mixer plays whole periods**, so a track's last few tens of ms stay unplayed
  until more data comes (the old per-block "head stalled … abandoning drain"):
  when the writer has nothing to write and real audio is stuck, it pads
  silence just past it (at a session end at once, mid-session after 40 ms).
  The pad doubles while the head stays stuck (AudioTrack starts a new or
  flushed track only near a full ~250 ms buffer) and shrinks back to 60 ms
  once the real audio is heard. On Pause the queued padding is flushed — if
  the head is still short (AudioTrack pauses asynchronously), re-checked for
  200 ms. The live Tempo stage holds back up to a frame (~43 ms): about to run
  dry mid-session, the writer pushes it out (`drainLiveTempo`, later effects
  keep their state, one ring-out at the session end).
  Logcat `D/AudioTrackOutput`: `track opened|released|rebuild`, and per block
  boundary `seg A→B session=S pad=Xms underrun=Yms` (0/0 = gapless). A refused
  format, failed write or parked track (head frozen 2.5 s while playing real
  audio) → `OutputEvent.TrackFailed` for EVERY session with audio on or queued
  for that track (a short one-shot can sit on it whole, End applied) → each
  fails. A track idle 30 s is released; a paused or idle writer sleeps on its
  inbox. The Narrator rewrites a flushed track's other sessions under a new
  epoch, so a report sent before the Flush applied is ignored. The harness's
  `VirtualAudioOutput` can model the device's start threshold
  (`startThresholdMs` + a 250 ms `bufferMs`) and asynchronous pause
  (`pauseLatencyMs`); its 30 ms default buffer hides both.
  `service/ServiceUi.kt` is the pure state → notification/media-session table.
  `service/SpeakShim.kt` holds the `ACTION_SPEAK` shim and `PlaybackHost`
  (`ServicePlaybackHost` sends `ACTION_HOST` so the service is foreground
  before the reader plays; the Narrator fails a session whose host never
  comes up within 5 s with `FailureKind.HOST_REFUSED`). **Android refuses
  `startForegroundService` from the background** (targetSdk 36): a link shared
  then left before it loads would autoplay into that wall. So
  `service/ArticleLoadHold.kt` starts the host from `ShareIntentActivity`
  (still in front) and the service shows "Loading article…" until the reader's
  load is over (`ReaderViewModel` ends the hold after play / failure / rebind /
  back-out; 90 s timeout); `ServicePlaybackHost.start()` counts an
  already-foreground host as success. A refused start from the background shows
  `reader_error_background_start` ("Couldn't start reading in the background.
  Tap Play."), not "Synthesis failed". After a permanent focus loss the service leaves the
  foreground (`STOP_FOREGROUND_DETACH`) with a swipe-away-able notification
  whose delete intent stops the read; it reports a lost host to the Narrator
  only when destroyed while still foreground. Destroyed while detached (the
  system stops background services), it leaves that notification up, re-posted
  without the released media session, timing out with the Narrator's 30 min
  paused timeout.
- Tests: `test/.../playback/` — `NarratorHarness` (virtual clock, fake
  ports, invariants), `FakeEngine` (lock, non-abortable mode, over-cap
  re-split, internal buffer), `VirtualAudioOutput` (the device's own
  `TrackWriter` over a virtual AudioTrack that plays whole mixer periods,
  ignores a flush while playing and can be parked; side-band frame tags, so
  stale audio is caught frame-exact), and the §5.2 scenarios in
  `Narrator{Scenarios,Budget,Sessions,Output,ResumePoint}Test` (Output = C2:
  no gap at a paragraph, one track per session, stale epochs, flush
  mid-write, rate change, interrupt, parked track). The writer against a REAL
  AudioTrack: `androidTest/.../AudioTrackOutputInstrumentedTest` — **emulator
  only**, run with `am instrument`, never `connectedAndroidTest` (recipe in
  its KDoc). The reader's stories
  (`ReaderPlaybackControllerTest`, `ReaderViewModelTest`), the shim
  (`SpeakShimTest`) and the shell's duties (`NarratorShellTest`) run against
  the same harness.

### Cloud API engine (hosted voices)
- `engine/api/CloudApiEngine.kt` — OpenAI-compatible `/audio/speech`
  synthesis with true streaming (WAV header parse + chunked PCM emit).
  Text over `maxInputChars` (1000) is sent as several requests:
  sentence-packed chunks, `splitToFit` for a single oversize sentence.
  One engine for all providers; the voice id carries provider + model:
  `cloud-api-v1:<provider>:<model>:<voice>`.
- `data/cloud/CloudProviders.kt` — provider descriptors as data
  (parse of `cloud-providers.json` + of live `/models?type=tts`).
- `data/cloud/CloudProviderStore.kt` — merges bundled asset, remotely
  fetched descriptor overrides (engines repo), and per-provider live
  voice discovery; owns the engine's `voice_meta` rows via
  `VoiceMetaDao.replaceEngine` (no static catalog / CATALOG_VERSION
  involvement). Caches under `filesDir/cloud/`. The remote copy may add
  providers and change models, but may only move a **built-in**
  provider's `baseUrl` (where saved API keys go) within its own site —
  `CloudProviders.pinBuiltInSites`; an off-site move keeps the bundled
  URL and sets `CloudProvider.movedOffSite`, which the Cloud screen shows
  as "update the app" for keyed providers. A provider only in the remote
  list is held the same way once the user saves a key for it:
  `setCloudApiKey` records the `baseUrl` the key was saved for
  (`SettingsRepository.cloudApiKeyBaseUrls`) and `CloudProviders.pinKeyedSites`
  refuses an off-site move from it on every load. Keys saved before that
  (≤ 1.1.0) get their provider's current URL on first load (trust on first
  use, `CloudProviderStore.pinKeyed`). The site rule is a PSL-free
  approximation (limits documented on `registrableDomain`). Any change to
  the engines repo's `cloud-providers.json` needs Max's manual review;
  agents never merge it.
- "Installed" = any provider key in `SettingsRepository.cloudApiKeys`
  (`cloud_api_key_<provider>` prefs; legacy `cloud_api_key` reads as
  Venice). Configure UI: Engines tab → Cloud voices card →
  `ui/screen/CloudApiScreen.kt`.

### Install / download
- `install/EngineCatalog.kt` — descriptors for installable engines
  (URL, sha256, archiveRoot, label, isRecommended). Single tarball
  per engine (`.tar.bz2`).
- `install/EngineInstaller.kt` — HTTP download, sha256 verify,
  tar.bz2 extract via Apache `commons-compress`, atomic rename. Per-
  engine `StateFlow<InstallState>` (Idle / Downloading / Extracting /
  Installed / Failed).
  - Refuses up front (`InstallFailure.NO_SPACE`) when free space can't
    hold the remaining download + unpacked size + margin; HTTP 404/410
    maps to `InstallFailure.NOT_AVAILABLE`. `InstallState.Failed.failure`
    carries the kind; `ui/screen/EnginesScreen.installFailureText(state)`
    is the one localized rendering (engine cards, pack rows, onboarding).
  - A failed download keeps its partial archive for resume
    (`Failed.partialDownloadBytes`); "Remove download" →
    `discardDownload` / `discardPackDownload` deletes it. Stale `*.tmp`
    scratch dirs/archives are swept when the installer is constructed.

### Engine speed + recommendation (issues #18, #19)
- `perf/DeviceCapability.kt` — the probe: the baked Kitten renders one
  fixed sentence in the app (onboarding, reader and alias screens ask),
  stored once per install (`SettingsRepository.kittenRtfMeasurement`;
  one older than `MEASURED_SINCE_MILLIS` is measured again), plus a Tier-1
  CPU score from sysfs for when it can't run.
- `perf/EngineRecommender.kt` — every engine's cost is a fixed multiple of
  that probe RTF (`REFERENCE_COST`), measured once on the reference phone
  (Pixel 6a, 2026-10-09, `~/coding/scratch/engine-speed-18/`, lab
  `tts-training-research/engine-speed-lab.html`); borrowed from Marmalade
  STT's `SpeedBudget`. `recommend(probe, offered)`: the first of
  `candidates` (Pocket where the build offers it, else Kokoro; then Kitten)
  predicted at RTF ≤ 0.8 (= `SpeedPerfWarning`'s headroom) takes the one
  Recommended pill; others are FINE up to 0.9, then MAY_BE_SLOW; Kitten is
  never warned. Flavor-aware only through `EngineCatalog.visibleTo`; since
  Pocket reached Play (#29) both builds offer the same engines, so a phone
  gets the same pill on either (the Pixel 6a: Pocket, predicted 0.58).
  `predictedRtf` feeds the speed-up warning before measured RTFs accrue.
  Kokoro's cost (4.17) is the v26 streaming render, from #25's same-phone
  bench scaled by that session's v23 re-measure (see the KDoc): on the 6a
  Kokoro predicts 0.86, so the Play build still recommends Kitten there.
- Re-bench before changing a cost or a `SpeedTier`: the probe's in-app RTF
  (screen on, fast cores) is lower than the bench service's (screen off),
  so costs are engine service RTF ÷ in-app probe RTF. Debug
  `devbench/DevStateService` replays onboarding and forces a probe value
  (`--ez onboarded false --ef kittenRtf <x>`, <= 0 clears it).
- `install/EngineCatalog.kt` `SpeedTier` — the card's 4-segment meter,
  from the same bench: Kitten/Jenny 4, Pocket 3, Kokoro/Kokoro German 2
  (MODERATE), HEAVY only the Pocket clean-reference build.

### Preprocessing
- `preprocessing/Preprocessor.kt` — applies the rule set
- `preprocessing/PreprocessingRules.kt` — 15 rules ported from CLI
  (numbers, abbreviations, symbols, etc.)
- `preprocessing/EngineProfiles.kt` — default rule sets per engine
- `preprocessing/EmojiProsody.kt` — detects emoji → emotion mapping
- `preprocessing/ProsodyApplier.kt` — applies emotion to engine
  params (speed, energy)
- Per-language text fixups that every engine needs live at the espeak
  choke point, `phonemizer/EspeakPhonemizer.phonemize`, not in the rule
  set: `EsTextFixups` (Spanish voices, before espeak: old spelling's
  standalone á/é/ó/ú → a/e/o/u, else espeak says "a acentuada", #26) and
  `EnPhonemeFixups` (English voices, after espeak: IPA repairs like
  "yeah", per acoustic model).

### Persistence
- `data/db/MarmaladeDb.kt` — RoomDatabase, schema v12 (history in its
  kdoc). Every step has a migration except 1→2 (v1 held no user data) and 9→10
  (alias re-key): the armed `fallbackToDestructiveMigration()` resets
  those on purpose.
  The fallback would also hide a broken new migration by wiping aliases,
  so test each one without it: `Migration11To12Test` builds a real v11
  file from `app/schemas/.../11.json` and opens it with the migration
  alone (Robolectric has no instrumentation assets for
  `MigrationTestHelper`).
- **Catalog versioning**: `MarmaladeTtsApplication.CATALOG_VERSION` (int,
  bumped on every catalog content change) gates a one-shot `upsertAll`
  re-seed on cold start; the last-applied version lives in DataStore via
  `SettingsRepository.catalogVersion`. Pre-v0.1.19 the seed used a
  "rows-absent" gate which left existing installs stranded when a
  catalog *expanded* (the bug that hid 52 of the 53 multi-lang Kokoro
  voices from upgraders). Bump the constant when you change any
  `*VoiceCatalog`.
- `data/db/VoiceMeta.kt` + DAO — installed voices (engine, voice id,
  display name, gender, language, isInstalled flag)
- `data/db/VoiceAlias.kt` + DAO — user "personas" (name + engine +
  voiceId + speed + effectId + phonemizationLanguage + expressiveness).
  `fallbackAliasId` holds another alias's **id** (not a FK; the editor
  writes ids since v1.1.0). `expressiveness` (v12, #36) is Pocket's
  sampling temperature (`data/Expressiveness.kt`: 0.2–0.7, null = the
  engine default `POCKET_DEFAULT` = 0.7, the Lively end by Max's call). It rides
  to the engine as `TtsEngine.synthesize/synthesizeStream(…,
  expressiveness)` on every path an alias reaches: the Narrator
  (`ResolvedVoice.expressiveness` → `SegmentRenderer`), the Speak screen
  (`SpeechPlayer.speak` → `EXTRA_EXPRESSIVENESS` →
  `OneShotVoice.Explicit`) and system TTS (`SynthParams`). Pocket and its
  dev twin turn it into the flow head's noise std (`pocketNoiseStd`) and
  log `request temperature=…` per request; every other engine ignores it.
- `data/db/AppAliasMapping.kt` + DAO — per-app routing (packageName
  → aliasName)
- `data/SettingsRepository.kt` — DataStore Prefs (theme preset, theme
  mode, primary alias name, keep-engine-loaded, per-engine preprocessing
  enables, the last "What's new" notes seen — see Navigation). `keepEngineLoaded` is **stored but unused** as of v0.1.16
  — UI toggle was removed; the engines don't honour it yet.

### TTS service surface (external apps)
- `service/MarmaladeTtsService.kt` — `TextToSpeechService` subclass;
  handles `onSynthesizeText`, `onIsLanguageAvailable`, `onLoadLanguage`,
  `onGetLanguage`, `onLoadVoice`
- `service/CheckVoiceDataActivity.kt` — Android invokes this to
  enumerate installed voices (BCP-47 → ISO-639-3 conversion). Filters
  through `MarmaladeTtsService.advertisableVoices`, the same rule the
  service's voice list uses (VITS per pack + released only)
- `service/GetSampleTextActivity.kt` — returns "Hello, this is
  Marmalade speaking." for the system picker's Play button
- `service/TtsRouter.kt` — `@Singleton` that resolves
  `(callerPackage) → VoiceAlias?` via: per-app mapping → primary
  alias → engine default. `fallbackVoiceIdFor(alias)` resolves a cloud
  alias's offline fallback by id, then by name (legacy rows stored a name)
- `AndroidManifest.xml` — `TTS_SERVICE` intent-filter declares
  `DEFAULT` category, `CHECK_TTS_DATA` + `GET_SAMPLE_TEXT` + `CONFIGURE_ENGINE`
  filters are also wired. `xml/tts_engine.xml` declares
  `settingsActivity` pointing at `MainActivity`.

### Other entry points
- `service/MarmaladeSynthService.kt` — the Narrator's foreground host,
  with media-session/lock-screen transport (see Pipeline above)
- `ui/intent/ShareIntentActivity.kt` — share-sheet target +
  `PROCESS_TEXT` selection action; dispatches to MarmaladeSynthService,
  or hands a shared link to reader mode
- `ui/intent/ShareRouting.kt` — the pure "reader or speak?" decision the
  share trampoline makes (ACTION_SEND that is essentially just a link —
  `reader/SharedUrlDetector.findLinkShare`: a URL plus at most a short
  one-line title — → reader; everything else, prose containing a link and
  PROCESS_TEXT included → speak). Unit-tested on the JVM.
- `service/SpeakDispatcher.kt` — wraps the foreground-service start
  intent for shared text (share sheet, PROCESS_TEXT, the reader's "read
  text as-is"); marks it `EXTRA_SHARED`, so the Narrator's resolver
  (`NarratorResolver`, `OneShotVoice.LanguageAware`) detects the whole
  text's language once and, if the
  primary alias's voice doesn't speak it, uses the same choice as the
  reader (`LanguageVoiceSelector`: other on-device alias → installed on-device voice
  → primary), logged as `D/ShareVoice`. Explicit-voice callers (Speak
  screen, previews) and the reader are untouched; system TTS
  (`MarmaladeTtsService`) keeps its own per-utterance rerouting

### Navigation
- `ui/AppRoot.kt` — Scaffold + NavigationBar (5 tabs) + NavHost.
  Tabs: **Speak / Aliases / Effects / Engines / Settings** (Aliases
  promoted to a tab in v0.1.18; Voices left the bottom bar and Engines
  rejoined it in v0.3.0-alpha.12 — Voices is now a detail route
  `voices?engine={e}`, reached from Speak or engine-scoped from
  EngineDetailScreen). Other detail routes: EngineDetail/{name},
  CloudApi, Licenses, EffectEditor, Reader. Bottom bar hides on detail
  routes (`showBottomBar` predicate at the top of AppRoot).
- **Reader mode** (`Routes.ReaderPattern` = `reader?url=…&text=…` →
  `ui/reader/ReaderScreen.kt` + `ReaderViewModel.kt`): share a link →
  fetch + extract (`reader/ArticleFetcher`, `reader/ArticleExtractor`,
  parsed on the `@ReaderParseDispatcher` from
  `reader/ReaderParseDispatcher.kt`, off Main) → article rendered as
  native Compose text blocks. Page furniture that Readability keeps
  (`nav`/`aside`/`footer`/`dialog` landmarks and their ARIA roles,
  infoboxes, navboxes, sidebars, hatnotes, edit links, footnote-marker
  superscripts like `[1]`/`[citation needed]`) is removed by tag, role and
  class name in `ArticleExtractor.removeNoise` *before* Readability runs —
  its cleaned HTML has no class names left (Readability sometimes keeps a
  wrapper around the whole page, menus included). Its output keeps `<a>`
  tags, so `reader/LinkFurniture` then drops link-shaped furniture while
  walking it: `ul`/`ol` with ≥70% of letters in links (unless a paragraph
  ending in a colon introduces it), link-only rows and runs of short
  link-only blocks, and the headings standing over nothing but those.
  `ArticleCleanup` then filters the block list. Entered from ShareIntentActivity (or the
  playback notification), which starts MainActivity with
  `EXTRA_READER_URL`. MainActivity takes a request only from a fresh
  launch or `onNewIntent` — not a recreation or a Recents replay (a
  request still pending when onboarding was up survives via saved
  state). AppRoot navigates with `popUpTo(ReaderPattern) { inclusive }`
  and no `launchSingleTop`, so each new link gets a fresh entry and
  ViewModel; the same URL already on top is left alone. The article is
  in-memory only — never persisted. The list follows the spoken block
  (`ReaderAutoScroll`) except after a user drag, and never while
  TalkBack's touch exploration is on (watched live via
  `AccessibilityManager`) — it would steal accessibility focus; then only
  a ToC pick scrolls. **Voice per article** (`reader/ReaderVoicePicker.kt`
  over the shared `lang/VoiceForLanguage.kt` + `lang/LanguageVoiceSelector.kt`,
  2026-09-28): once per article, before the first block, the reader
  detects the article's language (`LangDetector` over the first ~2000
  chars) and, if the primary alias's voice doesn't speak it
  (`VoiceMeta.languageCode`, language subtag only; a multilingual cloud
  voice — OpenAI-style, stored as `en-US` as a placeholder — counts as
  speaking everything, `CloudApiVoiceCatalog.hasKnownLanguage`), reads it in another
  on-device alias whose voice does (`VoiceChoice.Alias` →
  `TtsRouter.resolveAlias(aliasId=…)` in `NarratorResolver`), else an installed on-device,
  pickable voice of that language (`VoiceChoice.Installed`, dry, 1.0x), else the
  primary as before. **Never a cloud voice or cloud alias** (Max's privacy
  rule): text reaches a provider only through a cloud primary. Undetected language keeps the
  primary. A rebind keeps the article's voice; logged as `D/ReaderVoice`.
  The reader UI shows no voice name. The reading speed (`ReaderSpeedSheet`) is
  session-only and an **absolute override** of the reading alias's speed,
  not a factor on it: each new article starts at that alias's own speed
  (`ReaderViewModel.startingSpeed` → `ReaderPlaybackController.open`),
  and it is the Narrator's session speed, applied at playback *instead of*
  the alias's speed (voice, effect and language still come from the
  alias). A non-chip alias speed gets its
  own chip for the whole article (`readerSpeedChoices` over
  `ReaderPlaybackState.startingSpeed`), so picking another speed never
  removes the way back to it. A mid-article speed change is applied
  live (the writer's Tempo; no restart, no re-render — cloud voices too).
  **The reader is a Narrator client** (`reader/ReaderPlaybackController`,
  step C1): it holds the extracted article for rebinds, forwards commands
  (`playArticle`/`pauseArticle`, `jumpTo`, `next`/`previous`, `setSpeed`,
  `pause(Navigation)` on back) after `PlaybackHost.start()` for any command
  that may start audio, and projects `NarratorState` onto
  `ReaderPlaybackState` (a share reading over the article shows as Playing).
  Forward or a tap onto the prepared next block plays its audio at once; the
  Narrator keeps heard audio for an instant Back.
- `ui/AppRootViewModel.kt` — collects theme preset + mode + onboarded
  flag from `SettingsRepository`; drives `MainActivity` decisions.
- `ui/onboarding/OnboardingScreen.kt` + `OnboardingViewModel.kt` —
  5-step flow: Welcome → EnginePick → Installing → CreateAlias →
  SystemDefault. `finish()` flips the onboarded flag. A failed install
  row keeps the installer's own `Failed` (localized via
  `installFailureText`) and offers Retry + "Remove download".
- `MainActivity.kt` — decides between `OnboardingScreen` and `AppRoot`
  based on `onboarded` flag; sets theme via `MarmaladeTtsTheme`.
- **"What's new" pop-up** (#41, 1.2): `ui/whatsnew/WhatsNewDialog.kt`
  (an M3 `AlertDialog`; title is a heading, notes a TalkBack list) shown by
  `AppRoot` over the Speak route only, while `AppRootViewModel.showWhatsNew`.
  The rule is `ui/whatsnew/WhatsNew.decide`, run once per process when
  `AppRootViewModel` is created: stored
  `SettingsRepository.whatsNewSeenVersion` (DataStore
  `whats_new_seen_version`) ≥ `WhatsNew.NOTES_VERSION` → nothing; else not
  onboarded at launch → fresh install, record the version unshown; else
  (a 1.1.0 user has no key but is onboarded) → show, and any dismissal
  records it. Settings → About → "What's new" opens the same dialog and
  records nothing. **New notes for a release:** replace
  `res/values*/strings_whats_new.xml` (8 locales; public text) and raise
  `NOTES_VERSION` to that release's versionCode without the ABI digit.

### DI
- `di/AppModule.kt` — single Hilt module. Provides:
  - `MarmaladeDb` + 3 DAOs
  - `SettingsRepository`
  - `EngineFilesDir` (typealias `() -> File`)
  - `KittenEngine`, `KokoroEngine` (both `@Singleton open`)
  - `NativeEngineHandle` — `release(engineName)` drops only that
    engine's native handle (`NativeEngineHandle.routing` over
    `EngineRegistry.onDevice`; an unknown name releases all), so
    uninstalling one engine can't abort another's read
  - `EngineRegistry` is `@Inject`-constructed (not provided here); so are
    `EngineResidency` and `EngineWarmup`, which take their engine lists from it
  - `EngineInstaller`
  - `TtsRouter`

### Theme
- `ui/theme/Theme.kt` — `MarmaladeTtsTheme(darkTheme, themePreset, content)`
  + `resolveThemeIsDark(mode, isSystemDark)` helper
- `ui/theme/Color.kt` — palette + `ThemePreset` enum
  (`SYSTEM / MARMALADE / MIDNIGHT / FOREST / BERRY`); MARMALADE is
  the default since v0.1.10

## Data flow (mermaid-free, just text)

**External app calls TTS** → Android binds `MarmaladeTtsService` →
`onSynthesizeText` resolves voice via cache (or `runBlocking` DAO
miss) → `TtsRouter.resolveAlias(callerPackage)` picks the alias →
`runSynthesisPipeline(text, engine, voiceId, speed, rules, preset, synthLambda)` →
engine synth callback hits `KittenEngine.synthesize()` or
`KokoroEngine.synthesize()` → PCM16 written back to the framework
callback.

**In-app Speak (SpeakScreen)** → `SpeakViewModel.speak(text)` →
`Synthesizer.speak()` → `runSynthesisPipeline(...)` → result handed to
`MarmaladeSynthService` for foreground playback.

**Engine install** → user taps card → `EnginesViewModel.install(name)` →
`EngineInstaller.install(name, onProgress)` → HTTP GET → sha256
verify → tar.bz2 extract → atomic rename `scratch/ → engines/<name>/` →
StateFlow emits `Installed` → `VoiceMetaDao` rows for that engine
flip their `isInstalled` flag.

## Conventions

- **Package naming**: lowercase, separated by concern (`audio.`,
  `engine.`, `service.`). Never use `util.` as a bucket.
- **Compose**: every screen has its own `*Screen.kt` + `*ViewModel.kt`.
  Top-level `@Composable fun XScreen(onNavigate..., viewModel: VM = hiltViewModel())`.
- **Nested Scaffold**: `AppRoot`'s outer Scaffold owns status-bar
  insets. **Every per-screen Scaffold must use
  `contentWindowInsets = WindowInsets(0)` AND pass
  `windowInsets = WindowInsets(0)` to its TopAppBar.** Otherwise the
  bar double-pads. Imported bug from sister chat app, fixed in v0.1.14.
- **Hilt + Activities**: Hilt rejects bare `Activity`. Use
  `ComponentActivity` for activities that need `@AndroidEntryPoint`.
  See `CheckVoiceDataActivity.kt`.
- **Engine name constants**: literal `"kitten"` / `"kokoro"` are
  duplicated across the codebase (catalogs, profiles, when-blocks).
  TODO: consolidate into an enum. Code reviewer flagged this as a
  MEDIUM finding.
- **`@string/app_name` is "Marmalade TTS"** (proper case since
  v0.1.17). Package names and applicationId stay lowercase
  (`app.marmalade.tts`).

## Open architectural decision — sherpa-onnx vs onnxruntime-direct

**Current state:** marmalade-tts-android bundles
`libs/sherpa-onnx-static-link-onnxruntime-1.13.2.aar` (Apache-2.0). That AAR
wraps Microsoft's ONNX Runtime with TTS-specific helpers
(`OfflineTts`, `OfflineTtsKokoroModelConfig`, voices.bin parsing, lexicon
+ espeak phonemiser, multi-lang routing).

**Alternative:** depend on `com.microsoft.onnxruntime:onnxruntime-android`
(MIT, v1.26.0+ as of May 2026) directly and write the TTS layer in-app.

**When to consider switching:** every Kokoro audio-quality regression
we've hit (v0.1.19's `kokoro-int8-multi-lang-v1_0` tinny output is the
latest) traces back to sherpa-onnx config quirks — lexicon paths,
`lang="en"` vs `""`, the int8 vs fp32 model pairing, `ruleFsts` on the
outer config vs inner. Direct onnxruntime would let us feed phoneme IDs
straight into Kokoro and skip the whole lexicon/espeak class of bugs.
**Maise** (Mobile-Artificial-Intelligence/maise, MIT) is a working
reference for this path — ~120 LoC for the Kokoro ONNX session and
~200 LoC for **OpenPhonemizer** (BSD-3 English G2P, ONNX-based, no
spaCy/pyopenjtalk/espeak dependency). Audited GREEN 2026-05-23.

**Trade-off:** sherpa-onnx gives multi-language G2P routing for free
(via lexicon + jieba + espeak fallback); onnxruntime-direct + a single
phonemiser is English-only. For the "accented English via non-English
Kokoro voices" use case (the actual goal of v0.1.19), English-only
G2P is sufficient — the voice embedding alone produces the accent.
For real multi-language *text* synthesis (Japanese text → Japanese
speech), you'd need a Misaki-equivalent G2P per language.

**GPL boundary note:** `woheller69/ttsengine` (SherpaTTS, the engine
Max uses as fallback on his device) is **GPL-3.0**. Its
`TtsEngine.kt` Kokoro setup confirms the team uses fp32 `model.onnx`,
not the int8 variant we shipped — independent corroboration of the
v0.1.19 bug. But we **cannot port any code** from it; only observe
patterns. Don't read SherpaTTS source files into a context that will
write Marmalade code.

## Known quirks / recent gotchas

- **No Quick Settings tile (removed 2026-09-27, before 1.1.0 shipped)**:
  the "Speak clipboard" tile was pulled rather than debugged. If it comes
  back: a `TileService` can't read the clipboard on Android 10+ (only the
  focused app or default IME can), so it needs a focus-holding trampoline
  activity. The last (still buggy) attempt is in commit `2c6ffb7`
  (`SpeakClipboardTileService` + `SpeakClipboardActivity`).
- **Cancelling a stream aborts the ONNX run in flight** (Kokoro incl.
  German, Kitten, VITS): every `session.run` goes through
  `engine/AbortableInference.kt`, which gives the run its own
  `RunOptions` and calls `setTerminate(true)` when the collecting
  coroutine is cancelled; the resulting OrtException comes back as a
  CancellationException (logcat: `StreamPerf: <engine> inference aborted
  (cancelled)`). Without it the abandoned chunk ran to completion under
  synthLock and the service's synth mutex, and a reader tap paid for it.
  A new ORT engine should route its runs through it too, consuming and
  closing the `Result` inside the block. Pocket doesn't use it: its AR
  steps are short with an `ensureActive` between them, but a cancelled
  render still waits for a decoder slice in flight (up to ~1 s for a
  46-frame slice on a 6a) before releasing `synthLock`.
- **User `speed` is a time-stretch, not a model parameter**: Pocket's
  ONNX graphs are autoregressive with no speed input, and Kokoro's and
  Kitten's `speed` tensors saturate (Kokoro ~2.2x for a requested 3.0x;
  Kitten byte-identical ~1.85x for both 2.5x and 3.0x) while slurring
  articulation — so all three set
  `TtsEngine.supportsNativeSpeed = false`. Kitten's per-voice priors are
  NOT user speed and stay in its tensor.
  System TTS routes the request through `service/SpeedFallback.kt`
  first, which hands the engine 1.0 and prepends an
  `EffectBlock.Tempo(speed)` time-stretch to the effect chain (issue #7;
  Kokoro and Kitten joined 2026-09-12, mirroring the CLI's move to
  `sox tempo`).
  A new engine that can't (or shouldn't) honour `speed` only has to
  override the flag. The Narrator renders every engine at 1.0 and applies
  every speed at playback (next quirk).
- **The Narrator's effect chain runs at PLAYBACK, not in the render**
  (2026-09-28, carried into the Narrator in C1): render jobs hand the
  writer raw engine PCM rendered at 1.0 (so RTF is measured pre-stretch);
  the writer (`playback/TrackWriter` in `service/AudioTrackOutput`, one
  `StreamingEffectChain` per session with a live Tempo stage in front of the
  alias's effects) shapes it in 20 ms slices right before the AudioTrack. A speed change is an
  `OutputCommand.SetTempo`, audible within the AudioTrack's ~250 ms buffer,
  logged `D/Narrator: speed A→B` — no restart, no re-render, cloud voices
  included. Engine pre-roll (`playbackRate`) is sized at stream start only
  (untouched until C3), so a big mid-block speed increase can briefly
  underrun on a slow device. `MarmaladeTtsService` (system TTS) is
  unaffected — its chain still runs inline.
- **TTS engine registration requires `DEFAULT` category** on the
  `TTS_SERVICE` intent-filter AND `CHECK_TTS_DATA` activity AND a
  populated `tts_engine.xml` with `settingsActivity`. All three are
  needed for Android to list the engine in Settings. Fixed in v0.1.15.
- **runBlocking on TTS worker thread**: `onSynthesizeText` runs on
  the framework worker, watchdog ~10s. v0.1.16 added
  `ConcurrentHashMap` caches at `onCreate`/`onLoadLanguage` to keep
  the hot path lock-free; a single defensive runBlocking fallback
  remains for cache misses.
- **Hilt + saved-state-registry-owner**: composables outside
  NavBackStackEntry can't use `hiltViewModel()`. Use `viewModel()` at
  the AppRoot + OnboardingScreen entry points.
- **Effect framework status**: `EffectChain.kt` is real pure-Kotlin
  DSP (3-tap comb-filter reverb for CAVE, bit-crush + LPF + vibrato
  for ROBOT, HPF+LPF+softclip for TELEPHONE). Not a stub. Effects
  bind to aliases (`VoiceAlias.effectPreset`), not voices, so they
  only fire when an alias is active. `SpeakViewModel` auto-applies
  the primary alias on init (v0.1.18) so effects fire on first
  Speak without needing the user to tap the alias chip manually.
- **Voice list filtering**: Room holds every catalog voice, so disk
  state decides what's pickable. `data/VoiceAvailability.kt`:
  `probeInstalledVoiceAssets` (engine `verify` + per-pack `verifyPack`)
  → `pickableVoices(assets, showDeveloper)` — on disk (a VITS voice needs
  its own pack) and, outside developer mode, not a developer-only engine
  or unreleased pack. The Play flavor has no developer mode at all: its
  toggle is hidden, and `EngineCatalog.effectiveDeveloperMode` (applied by
  `SettingsRepository.showDeveloperEngines` and `pickableVoices`) turns a
  stored `true` — F-Droid data carried over — into false there. It also
  hides unreleased packs (`VoicePackCatalog.showsUnreleased`, also used by
  the Engines/EngineDetail pack lists). Shared by `VoicePickerViewModel`, the alias
  editor and onboarding's alias step.
  Tests that need a pack listed must use the released pack (Jenny,
  `VoicePackCatalog.EN_JENNY_DIOCO_MEDIUM`) or expect
  `showsUnreleased(...)`: Play hides staged packs even with developer mode
  on, and a `flow.first { … }` waiting for one hangs into an
  `UncompletedCoroutinesError` after a minute instead of failing fast.
- **Voice credit lines** (#37): both pickers (`VoicePickerScreen`'s and
  `VoicePickerSheet`'s `VoiceRow`) show `VoiceCreditLine` under a voice,
  from `data/VoiceCredit.kt` (`VoiceCredits.forVoice`). The data sits next
  to each catalog: `PocketVoiceCatalog.creditFor(key)` (dev twin shares
  it), `VoicePack.credit` per pack, `KokoroGermanVoiceCatalog.CREDIT`.
  Kokoro/Kitten stock voices, cloud voices and Pocket clones have none on
  purpose (no documented source to credit). Keep it in step with
  CREDITS.md / LICENSES/ when a voice or pack is added: `VoiceCreditsTest`
  fails for an offered voice or pack without one. Only "Voice donor" and
  "model by" are translated; names and licence names are shown as written.
- **`kokoro-int8-multi-lang-v1_0` is an unblessed power-user export**
  — added to sherpa-onnx releases by PR #2137 but never included in
  the team's own APK build script (`scripts/apk/generate-tts-apk-script.py`).
  Naive dynamic int8 quantization on a vocoder produces the
  "tinny / staticy" output the v0.1.19 multi-lang Kokoro shipped with.
  If we ever want multi-lang Kokoro via sherpa-onnx, use either the
  **fp32 `kokoro-multi-lang-v1_0`** (~349 MB) or the team-validated
  **`kokoro-int8-multi-lang-v1_1`** (~92 MB). Never int8-v1.0.
- **Our Kokoro-family ONNX files carry a broken inverse STFT** (issue #6,
  2026-10-05). Kokoro's export-friendly `CustomSTFT` (`disable_complex=True`)
  never doubles the one-sided middle bins and never divides by the window
  overlap. So DC and Nyquist come out 2× too strong: +6 dB rumble below
  100 Hz, −3–4 dB presence across 1–10 kHz, +3 dB fizz near 12 kHz. This is
  in the shipped 82M int8 v23 and the fp32 it came from (Kitten and the
  German Thorsten files contain the same weights). It costs the 82M UTMOS 4.48 → 4.36 (Heart) and
  4.31 → 3.97 (Michael). The fix is data, not code: scale the two fp32
  initializers `kmodel.decoder.generator.stft.weight_backward_{real,imag}`
  (rows 1..9 ×2, all ÷1.5;
  `~/coding/scratch/kokoro-upstream-ab/patch_istft.py`). **Check per model:**
  one trained through this STFT compensates for it. The 7M student was, and
  the patch makes it worse. Same issue: the style row (`kokoroStyleRow`'s
  count vs upstream's count−1) is audibly and measurably irrelevant.
  **Not shipped (Max, blind listen 2026-10-06):** he could not hear the
  patched 82M from today's on any clip, so the v23 bundle stays as is.
- **Kitten nano 0.8 is trainable** (issue #7 "K0", 2026-10-06). Its baked
  `kitten.onnx` (since #10 shipped cut in two, see "Kitten streams") is an fp32 Kokoro `KModel` (hidden 128, 2 layers, a 256-wide
  decoder, the same ALBERT and 178-symbol vocab) and was trained through this
  same `CustomSTFT`. A PyTorch rebuild loading the ONNX weights matches it to
  fp32 rounding (identical durations), and fine-tunes on chocolate. Rebuild,
  weight map, Kokoro→Kitten teacher pipeline and measured training cost:
  `~/coding/scratch/kitten-trainable-k0/README.md`.
  **Kitten nano is band-limited to ~8 kHz** (nothing above 7.8 kHz; Kokoro
  has a full 8–12 kHz octave). Fine-tuning it on a full-band teacher with
  only StyleTTS2's spectral-convergence loss fills that band with hiss and
  steady tones at the iSTFT bin centres (9.6/10.8 kHz), pauses included (#8).
  Either low-pass the teacher to ~7.8 kHz or add log-mel L1; train on crops
  with real context each side, or every sentence ends in a tick. Details:
  `~/coding/scratch/kitten-trainable-k0/k0-hiss/README.md`.

## Where the docs live

- `CHANGELOG.md` — per-version changes (kept current; check before
  assuming a "recent change" is on main)
- `SPEC.md` — original v0.1 spec
- `ROADMAP.md` — planned v0.2+ work
- `STUBS.md` — known deferred items (some closed in v0.1.16's "Keep
  engine loaded" toggle removal)
- `PRIVACY.md`, `SECURITY.md` — what the app does and doesn't access
- `LICENSES/kitten-direct.md`, `LICENSES/kokoro-direct.md`,
  `LICENSES/pocket-tts.md`, `LICENSES/runtime-libraries.md` —
  per-component licenses (Apache-2.0 models; espeak-ng GPL-3.0-or-later,
  compiled from source into the APK, which makes the distributed APK a
  GPL combined work while source files stay MIT)
- `.review/*.md` — historical review artifacts from multi-agent
  audits. Useful for context on past decisions; don't treat as
  current truth.

## When investigating something not in this map

- `Grep` for the symbol/class first
- `Glob` for filenames second
- If you spawn a subagent, **point it at this file in the briefing**
  so it doesn't repeat the orientation work
- Update this file when you discover something a future agent should
  know (new architectural choices, new gotchas, new conventions)
