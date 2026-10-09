# Release smoke gate

Before every release, make every engine speak every language it offers on a
real phone, from the R8-minified release build. Unit tests can't catch this
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
4. Lists the voices the app advertises and picks one per engine × language
   (per voice pack for VITS; `--all-voices` for every voice). It renders that
   language's line from `sentences.tsv` to a WAV on the phone. Nothing is
   played.
   Kokoro's accented tier (German and Bulgarian, `partialLanguageCodes` in
   `EngineCatalog`) has no voice of its own. The gate reaches it the way a
   client does: a Kokoro voice plus that request language (`deu`, `bul`).
   Kokoro uses the request language only when its language detector abstains
   on the text, so those rows' `accented:<code>` sentences must make it abstain.
   The gate checks that with the app's own `langdetect.tab` and fails the row
   if not. Most German prose reads as English or French to the detector.
5. A row passes when audio came out (samples, a plausible length for the
   sentence, not silence) and the app's process logged no error or exception
   while rendering it. The length check divides by the phone's system
   speech rate (Settings → Text-to-speech → Speech rate), which Android
   applies over any rate a client asks for; the gate reads it and never
   changes it. If the app's process dies or restarts during a row, that row
   fails at once and the run carries on. An expected engine that the phone
   doesn't advertise is a FAIL row, not a skip. Cloud voices are skipped
   (network + key).
6. Prints a pass/fail table and exits 1 on any failure (2 on a setup error).
   Results (`table.md`, `results.json`, the WAVs) go to
   `build/release-smoke/<time>/`.

The `bundle` column is the engine bundle release the build's catalog
downloads. The rc's bundles come from its own installer, so that is what ran,
unless the Engines screen shows the engine as having an update.

## Run it

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleFdroidRelease -PsmokeRelease     # app.marmalade.tts.rc
scripts/release-smoke/release-smoke.py \
    --apk app/build/outputs/apk/fdroid/release/app-fdroid-release.apk
```

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
built in). `adb install -r` keeps them across later builds.

## Changing it

- New language: add its line to `sentences.tsv`. The gate fails any
  advertised language that has none.
- New engine: add it to `EXPECTED_ENGINES` in `release-smoke.py`.
- A log line that is harmless but matches the error check: add a regex to
  `logcat-allow.txt`, with a comment saying why it is harmless.
- The probe builds from the SDK's build tools (`build-probe.sh`), outside
  Gradle, so the app's reproducible build is untouched.

## Not covered

- How the audio sounds. Ear tests stay manual (`docs/release/device-test-lab.html`).
- Developer-only engines: system TTS never offers them to other apps.
