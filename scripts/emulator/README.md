# Headless emulator (chocolate)

`emulator.sh` runs the app's debug build in an Android emulator on chocolate,
with no window and no audio. It needs no phone, so it's free for long runs:
every language of an engine, A/B renders of two bundles, a quick check that a
build starts. It is a check of the app's path (phonemizer, which graphs load,
streaming, crashes), not of phone-exact audio: x86 ONNX Runtime kernels round
slightly differently from ARM, and the emulator's speed numbers mean nothing.

Only the debug build carries x86_64 native code (`app/build.gradle.kts`), so
the release (`-PsmokeRelease`) build can't run here; the release gate stays on
the Pixel 6a.

## Run it

From marmalade (the script runs on the emulator host, piped over ssh):

```bash
ssh chocolate 'bash -s' -- provision < scripts/emulator/emulator.sh   # once per host
ssh chocolate 'bash -s' -- start     < scripts/emulator/emulator.sh   # ~1 min; prints emulator-5580
ssh chocolate 'bash -s' -- stop      < scripts/emulator/emulator.sh   # always, when done
```

- `provision` downloads the pinned emulator (36.6.11) and system image
  (Android 15 / API 35, x86_64, no Play Store) from Google, checks their
  sha256, and writes the AVD `marmalade-tts-emu` (4 cores, 4 GB, Pixel 6
  screen). It needs `/dev/kvm` and the SDK's platform-tools, nothing else:
  chocolate has no JDK, so `sdkmanager`/`avdmanager` aren't used.
- `start --wipe` factory-resets the AVD first. Otherwise its data (installed
  app, sideloaded bundles) survives a stop.
- Before starting, check chocolate isn't running a game
  (`pgrep -af "[r]eaper SteamLaunch|[G]ameThread"`).

## Use it

Use chocolate's adb: `~/Android/Sdk/platform-tools/adb -s emulator-5580`.
Build the APK on marmalade (`assembleFdroidDebug`, under the Gradle lock) and
copy it over.

- **Install:** `adb install -r app-fdroid-debug.apk`. On first start the app
  seeds Kitten from its assets; nothing else is installed.
- **Engine bundles:** the debug app is debuggable, so a bundle tarball can be
  extracted straight into its files. The installer treats an engine directory
  without install metadata as installed from the current catalog:

  ```bash
  adb push kokoro-direct-v1_0.tar.gz /data/local/tmp/b.tar.gz && adb shell chmod 644 /data/local/tmp/b.tar.gz
  adb shell am force-stop app.marmalade.tts.debug
  adb shell "run-as app.marmalade.tts.debug sh -c 'rm -rf files/engines/kokoro-direct-v1_0 && cd files/engines && tar xzf /data/local/tmp/b.tar.gz'"
  adb shell rm /data/local/tmp/b.tar.gz
  ```

- **Speak through the real app:** the release gate's probe
  (`scripts/release-smoke/build-probe.sh` → `probe.apk`) drives the app through
  the public `TextToSpeech` API and saves a WAV; nothing is played:

  ```bash
  adb shell am instrument -w -e engine app.marmalade.tts.debug -e mode list app.marmalade.tts.smokeprobe/.SmokeProbe
  adb shell am instrument -w -e engine app.marmalade.tts.debug -e mode speak \
      -e voice kokoro-direct-v1_0:af_heart -e text64 "$(printf 'Hello there.' | base64 -w0)" -e out t1 \
      app.marmalade.tts.smokeprobe/.SmokeProbe
  adb pull /storage/emulated/0/Android/data/app.marmalade.tts.smokeprobe/files/t1.wav
  ```

  Add `-e lang deu` to send a request language with the voice (Kokoro's
  accented tier). The system TTS path detects each utterance's language and
  uses the request language only when the detector abstains; see the release
  gate's README.

## Precedent

#34 rendered every Kokoro language, v23 against v26, this way and scored the
clips with Whisper on chocolate. Its driver scripts are in
`~/coding/scratch/kokoro-v26-langs-34/` (`put_bundle.sh`, `render.py`,
`wer.py`).
